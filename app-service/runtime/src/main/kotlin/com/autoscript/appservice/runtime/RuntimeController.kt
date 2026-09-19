package com.autoscript.appservice.runtime

import com.autoscript.domain.bridge.HandleRef
import com.autoscript.domain.engine.EngineStatus
import com.autoscript.domain.engine.KillCause
import com.autoscript.domain.engine.StopResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 执行仲裁者（docs/framework-design.md §8 / §4.1 kill 权威）：
 * 池 + 看门狗的唯一装配点，JS `engines.*` 面的 Kotlin 对偶。
 *
 * 职责：
 * - [start]/[stop]/[killAll] 经 [guard] 串行化（FixedEnginePool 要求调用方串行化
 *   killAll 与 acquire/release，防许可证超发——本类就是那个调用方）；
 * - 以 runId 为键维护在途表：`stop(未知 runId)` 如实回 [StopOutcome.AlreadyGone]，
 *   绝不静默吞掉（JS `engines.stop(runId)` 语义）；
 * - kill 权威：[killRun]/[killAll] 是唯一强杀入口（看门狗只给裁决，见 [judge]）；
 * - [judge] 纯委托 [WatchdogPolicy]（只判 RUNNING 等语义见该类）；pid→run 的映射由
 *   调用方（:app 持 /proc 表）完成，本类只按 runId 强杀。
 */
class RuntimeController(
    private val pool: EnginePool,
    private val watchdog: WatchdogPolicy = WatchdogPolicy(),
) {
    private val guard = Mutex()
    private val active = HashMap<Long, PoolHandle>()

    /** 启动结果（对偶 JS PoolAcquireOutcome：granted/timedOut/failed）。 */
    sealed interface StartOutcome {
        data class Started(val runId: Long, val handle: HandleRef) : StartOutcome
        data object QueueTimeout : StartOutcome
        data class StartFailed(val message: String) : StartOutcome
    }

    /** 停止结果（对偶 ScriptEngine.stop + 句柄已失踪的诚实上报）。 */
    sealed interface StopOutcome {
        data object StoppedClean : StopOutcome
        data class StoppedTimeout(val partial: Boolean) : StopOutcome
        data object AlreadyGone : StopOutcome
    }

    /** 取槽启动；满则按 [PoolAcquireRequest.waitTimeoutMillis] 排队（null = 无限等）。 */
    suspend fun start(request: PoolAcquireRequest): StartOutcome = guard.withLock {
        when (val outcome = pool.acquire(request)) {
            is PoolAcquireOutcome.Granted -> {
                val receipt = outcome.handle.receipt
                active[receipt.runId] = outcome.handle
                StartOutcome.Started(receipt.runId, receipt.handle)
            }
            PoolAcquireOutcome.TimedOut -> StartOutcome.QueueTimeout
            is PoolAcquireOutcome.Failed -> StartOutcome.StartFailed(outcome.message)
        }
    }

    /** 优雅停止一次执行（四步 quiesce 由池/槽位驱动，TimedOut 已 kill 兜底）。 */
    suspend fun stop(runId: Long): StopOutcome {
        val handle = guard.withLock { active.remove(runId) } ?: return StopOutcome.AlreadyGone
        return when (val result = pool.release(handle)) {
            StopResult.Clean -> StopOutcome.StoppedClean
            is StopResult.TimedOut -> StopOutcome.StoppedTimeout(result.partial)
        }
    }

    /**
     * 强杀一次执行（看门狗裁决的落点；kill 权威 §4.1）。
     *
     * **终结路径必须收归槽位**：本方法既是 kill 入口也是这次 run 的结束点
     * （[awaitCompletion] 的 CRASHED/ settleKilled 同口径），所以 kill 之后必须
     * 把槽位与许可证一并还给池 —— 否则 `free` 与可领许可证永久错位，池容量缩水
     * （后续 start 一律排队到超时，表现为「引擎再不接活」）。
     * 收归走 [EnginePool.recycle]（池侧原子完成「槽位复位 + 还证」，幂等不超发），
     * 与 [killAll] 的强释路径区分：killAll 是广播式全清，不逐个 recycle。
     * 不用 [EnginePool.release]：那会先走四步 quiesce（stop）再 kill 兜底 ——
     * 对一个已经决定强杀的执行体是多余的一次「礼貌请求」，且 stop 的语义是
     * 优雅排空，与 kill 权威语义相反。
     *
     * @return null = 该 runId 不在途（已被 stop/killAll 收走），调用方不得视为成功 kill。
     */
    suspend fun killRun(runId: Long, cause: KillCause): KillCause? {
        val handle = guard.withLock { active.remove(runId) } ?: return null
        val killed = handle.slot.engine.kill()
        pool.recycle(handle.slot)          // 强杀即终结：槽位 + 许可证必须成对归还（§8.2 记账）
        return killed
    }

    /** 全部强杀（killAll 与 start/stop 串行，防许可证超发）。 */
    suspend fun killAll(reason: KillCause) = guard.withLock {
        active.clear()
        pool.killAll(reason)
    }

    /** 看门狗裁决（纯判断，不执行；执行走 [killRun]/[killAll]）。 */
    fun judge(sample: WatchdogSample, history: List<WatchdogSample> = emptyList()): WatchdogVerdict =
        watchdog.evaluate(sample, history)

    fun stats(): PoolStats = pool.stats()

    /** 在途 runId 快照（诊断/UI 用）。 */
    fun activeRunIds(): Set<Long> = active.keys.toSet()

    /**
     * 完成等待（docs §8.2 执行语义：JS `engines.exec` 句柄的 `onExit` 订阅 ——
     * 脚本退出/崩溃是事件，不是 start 的返回值）。
     *
     * 语义：
     * - 正常 stop（优雅退出）→ [Completed.StoppedClean]；
     * - 软停超时（四步 quiesce 未净，已 kill 兜底）→ [Completed.StopTimeout]；
     * - 看门狗/killAll 强杀 → [Completed.Killed]；
     * - 未知 runId（已结算/从未存在）→ [Completed.UnknownRun]；
     * - [timeoutMillis] 内未完成 → [Completed.TimedOut]（返回，不抛；调用方决定 kill/续等）。
     *
     * 本类只做状态机结算：完成/强杀两种终态都收走槽位并归档在途表；
     * kill 仍只经 [killRun]/[killAll]（kill 权威 §4.1）。
     */
    sealed interface Completed {
        data object StoppedClean : Completed   // 优雅退出
        data object StopTimeout : Completed    // 软停超时（已 kill 兜底）
        data object Killed : Completed         // 看门狗/killAll 强杀
        data object UnknownRun : Completed     // 未知 runId（已结算/从未存在）
        data object TimedOut : Completed       // 等待超时（仍在途，调用方决定后续）
    }

    /**
     * 等待一次执行终结。轮询口径：仅 RUNNING 判活（[isWatchable]；SUSPENDED
     * 不存在于本状态机）。引擎侧 [ScriptEngine.status] 由宿主实现喂实；
     * FakeEngine 语义下 stop 后即 STOPPED，可直接结算。
     */
    suspend fun awaitCompletion(runId: Long, timeoutMillis: Long = 30_000): Completed {
        val handle = guard.withLock { active[runId] } ?: return Completed.UnknownRun
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (true) {
            val status = try {
                handle.slot.engine.status()
            } catch (_: Exception) {
                return settleKilled(runId)
            }
            // CRASHED 是终态（引擎已死但 kill 权威尚未收走）：按 Killed 结算并释放槽位。
            // 注意顺序：isWatchable 仅 RUNNING 为真，CRASHED 须先判（否则被 settleDone 吞掉）。
            if (status == EngineStatus.CRASHED) {
                return settleKilled(runId)
            }
            if (!isWatchable(status)) {
                return settleDone(runId)
            }
            if (System.currentTimeMillis() >= deadline) return Completed.TimedOut
            delay(POLL_INTERVAL_MILLIS)
        }
    }

    /** runId → 引擎侧状态快照的轻量查询（UI/任务中心轮询用，不在途 → null）。 */
    suspend fun probeStatus(runId: Long): EngineStatus? {
        val handle = guard.withLock { active[runId] } ?: return null
        return try {
            handle.slot.engine.status()
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val POLL_INTERVAL_MILLIS: Long = 200
    }

    /** 完成结算：release 槽位 + 归档在途表（与 stop/killAll 串行，经 guard）。 */
    private suspend fun settleDone(runId: Long): Completed {
        val handle = guard.withLock { active.remove(runId) } ?: return Completed.UnknownRun
        return when (pool.release(handle)) {
            StopResult.Clean -> Completed.StoppedClean
            is StopResult.TimedOut -> Completed.StopTimeout
        }
    }

    /** 强杀结算：只收走本 run 的槽位（release 走 quiesce+kill 兜底），不碰其他在途。 */
    private suspend fun settleKilled(runId: Long): Completed {
        val handle = guard.withLock { active.remove(runId) } ?: return Completed.UnknownRun
        pool.release(handle)
        return Completed.Killed
    }

    /** 仅 RUNNING 判活的看门狗喂样口径（SUSPENDED 不存在于本状态机，见 EngineStatus）。 */
    fun isWatchable(status: EngineStatus): Boolean = status == EngineStatus.RUNNING
}
