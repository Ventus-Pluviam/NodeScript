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
 * - [judge] 纯委托 [WatchdogPolicy]（只判 RUNNING 等语义见该类）；
 * - [watchAnchors] 供 [EngineWatchdog] 取「runId → 本次执行的引擎 pid」：pid 的事实来源是
 *   [com.autoscript.domain.engine.EngineRunReceipt.pid]（随启动 receipt 一并给出，不可得为
 *   null），调度循环自己不另建一张 pid 表 —— 在途表就在本类里，再抄一份必然错位；
 * - [heartbeat] 收引擎进程打来的心跳（§8.4 缺口②的宿主侧收单方），[heartbeatMillis]
 *   供看门狗问「距上次心跳多久」。心跳账本与在途账同生共死：run 终结即 [HeartbeatLedger.forget]；
 * - [statusOf] 同时读宿主自报状态与池侧状态机投影并**比对**（§8.3 最后一项：两者尚未互相校准时
 *   由本类如实报出分歧，而不是假装一致）。
 */
class RuntimeController(
    private val pool: EnginePool,
    private val watchdog: WatchdogPolicy = WatchdogPolicy(),
    /**
     * 心跳账本（§8.4「引擎进程打点、宿主记账」）。可注入：装配层与单测各持一份
     * （Application 域一个进程一个，替身进程各自隔离）。默认新建生产实例。
     */
    private val heartbeats: HeartbeatLedger = HeartbeatLedger(),
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
        heartbeats.forget(runId)
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
        heartbeats.forget(runId)
        val killed = handle.slot.engine.kill()
        // 强杀即终结：槽位 + 许可证必须成对归还（§8.2 记账）；
        // cause 透传给状态机归类（§8.3：REQUESTED→STOPPED，watchdog/OOM→CRASHED）
        pool.recycle(handle.slot, cause)
        return killed
    }

    /** 全部强杀（killAll 与 start/stop 串行，防许可证超发）。 */
    suspend fun killAll(reason: KillCause) = guard.withLock {
        val gone = active.keys.toList()
        active.clear()
        gone.forEach { heartbeats.forget(it) }
        pool.killAll(reason)
    }

    /**
     * 收口的**强制兜底**（docs §13 铁律 4 的执行侧部分）：绕开请求语义，直接强杀全部非 FREE
     * 槽位并复用。
     *
     * 与 [killAll] 的分工：[killAll] 是请求驱动的停止语义（看门狗裁决/用户停全部的落点，
     * 在途表经 guard 串行收走）；本方法是**进程级急停**（应用被杀/系统回收/测试收口）——
     * 不重建 guard 语义，只做「杀全部 + 清在途表 + 忘心跳」，事件面也不发 stopAll 那条流
     * （调用方按急停路径记账，不污染正常停止的审计）。
     */
    suspend fun forceStopAll(cause: KillCause) = guard.withLock {
        val gone = active.keys.toList()
        active.clear()
        gone.forEach { heartbeats.forget(it) }
        pool.killAll(cause)
    }

    /** 看门狗裁决（纯判断，不执行；执行走 [killRun]/[killAll]）。 */
    fun judge(sample: WatchdogSample, history: List<WatchdogSample> = emptyList()): WatchdogVerdict =
        watchdog.evaluate(sample, history)

    /**
     * 本控制器持有的裁决口径（装配层给 [EngineWatchdog] 用，保证**只有一份**阈值）。
     *
     * 为什么必须从这里取：裁决是 controller 落的（[judge] → [watchdog]），若装配层另 new 一个
     * [WatchdogPolicy]，两处的阈值就是两份事实 —— 改了其一，另一个静默不一致，现场表现为
     * "看门狗按 95% 判、循环按别的节奏跑"，极难查。
     */
    fun watchdogPolicy(): WatchdogPolicy = watchdog

    /** 心跳账本（[HeartbeatLedger]）：装配层经它把宿主持有的账本喂给看门狗。 */
    fun heartbeats(): HeartbeatLedger = heartbeats

    /**
     * 收一次心跳（§8.4 缺口②的宿主侧入口；JS `engines.heartbeat` 的落点）。
     *
     * 幂等由序号保证（[HeartbeatLedger.beat]）：同 seq 或更旧不回刷时间戳，
     * 所以重发/乱序帧不会把一个死掉的 run 假装成活的。
     *
     * 不在途 runId（已结算/从未存在）→ false 且**不记账**：已终结的 run 不得再收心跳，
     * 否则无主条目会堆积并把活 run 的账顶出记账上限（[HeartbeatLedger.maxRuns] 淘汰
     * 最旧条目），且调用方会把"已结算"误读成"一次有效心跳"。
     *
     * @return false = 不在途 / 该 seq 过期或重复（未被采纳）；true = 已刷新时间戳。
     */
    suspend fun heartbeat(runId: Long, seq: Long): Boolean {
        val live = guard.withLock { active.containsKey(runId) }
        if (!live) return false
        return heartbeats.beat(runId, seq)
    }

    /** 距上次心跳毫秒（从未打过点 → null，看门狗据此记 [EngineWatchdog.Tick.noHeartbeat]）。 */
    fun heartbeatMillis(runId: Long): Long? = heartbeats.sinceLastBeat(runId)

    fun stats(): PoolStats = pool.stats()

    /**
     * 看门狗锚点：在途 runId → 该次执行的引擎 pid（docs §8.4 调度循环的取数口）。
     *
     * pid 取 [com.autoscript.domain.engine.EngineRunReceipt.pid]，即**这次 run** 的 pid，
     * 而非宿主 [com.autoscript.domain.engine.ScriptEngine.pid] 的"当前值"——同一槽位换过
     * 执行体后两者不同。宿主不给 pid（实现未接线）时如实给 null：调用方按"无法度量"处理。
     */
    suspend fun watchAnchors(): List<WatchAnchor> = guard.withLock {
        active.values.map { WatchAnchor(it.receipt.runId, it.receipt.pid) }
    }

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

    /**
     * 宿主自报状态 + 池侧状态机投影的**对照**（§8.3 最后一项落地的第一步：不静默，只如实报分歧）。
     *
     * 为什么要这个：池侧状态机（[PoolSlot.status]）是记账投影，宿主 [ScriptEngine.status] 才是
     * 执行真相。真实现接上前两者必然有窗口期差距（宿主还报 RUNNING、池已回 IDLE），
     * 此前的缺口正是「不会响亮失败」。本方法不擅自修任何一侧，只把差异交出来 ——
     * 由看门狗/UI/日志决定怎么处理，调用方看得见才算校准。
     *
     * @return null = 该 runId 不在途（已结算/从未存在），无状态可校准。
     */
    suspend fun statusOf(runId: Long): RunStatus? {
        val handle = guard.withLock { active[runId] } ?: return null
        val host = try {
            handle.slot.engine.status()
        } catch (_: Exception) {
            null
        }
        val pool = handle.slot.status()
        val drift = host != null && !agrees(host, pool)
        return RunStatus(runId = runId, host = host, pool = pool, drift = drift)
    }

    /** 在途 run 的全量状态对照（诊断/看门狗用；不在途 → 空）。 */
    suspend fun runStatuses(): List<RunStatus> = guard.withLock {
        active.keys.toList()
    }.mapNotNull { statusOf(it) }

    /** 一次执行的两个状态来源（§8.3 校准）。 */
    data class RunStatus(
        val runId: Long,
        /** 宿主自报（[ScriptEngine.status]）；读不到 → null（引擎已死/实现未接线）。 */
        val host: EngineStatus?,
        /** 池侧状态机投影（[PoolSlot.status]）。 */
        val pool: EngineStatus,
        /** 两侧是否**不一致**（host 读不到时不判分歧：那可能是宿主已死，见 [statusOf] KDoc）。 */
        val drift: Boolean,
    )

    /**
     * 池侧投影与宿主自报何时算一致。
     *
     * 合法组合（其余视为分歧）：
     * - 池 BOOTING ↔ 宿主 IDLE/BOOTING：execute 尚未返回，池先跳；
     * - 池 RUNNING ↔ 宿主 RUNNING：稳态；
     * - 池 QUIESCING ↔ 宿主 QUIESCING/STOPPED：四步排空，宿主可能已先净；
     * - 池 IDLE ↔ 宿主任意：**不算分歧**（已回收，宿主此刻说什么都不该算异常）；
     * - host 为 null → 由 [RunStatus.drift] 单独表达，不进本函数。
     */
    private fun agrees(host: EngineStatus, pool: EngineStatus): Boolean = when (pool) {
        EngineStatus.IDLE -> true
        EngineStatus.BOOTING -> host == EngineStatus.BOOTING || host == EngineStatus.IDLE
        EngineStatus.RUNNING -> host == EngineStatus.RUNNING
        EngineStatus.QUIESCING -> host == EngineStatus.QUIESCING || host == EngineStatus.STOPPED
        EngineStatus.STOPPED, EngineStatus.CRASHED -> true
    }

    /** 在途执行的看门狗锚点（[watchAnchors] 的元素）。 */
    data class WatchAnchor(val runId: Long, val pid: Int?)

    companion object {
        const val POLL_INTERVAL_MILLIS: Long = 200
    }

    /** 完成结算：release 槽位 + 归档在途表（与 stop/killAll 串行，经 guard）。 */
    private suspend fun settleDone(runId: Long): Completed {
        val handle = guard.withLock { active.remove(runId) } ?: return Completed.UnknownRun
        heartbeats.forget(runId)
        return when (pool.release(handle)) {
            StopResult.Clean -> Completed.StoppedClean
            is StopResult.TimedOut -> Completed.StopTimeout
        }
    }

    /** 强杀结算：只收走本 run 的槽位（release 走 quiesce+kill 兜底），不碰其他在途。 */
    private suspend fun settleKilled(runId: Long): Completed {
        val handle = guard.withLock { active.remove(runId) } ?: return Completed.UnknownRun
        heartbeats.forget(runId)
        pool.release(handle)
        return Completed.Killed
    }

    /** 仅 RUNNING 判活的看门狗喂样口径（SUSPENDED 不存在于本状态机，见 EngineStatus）。 */
    fun isWatchable(status: EngineStatus): Boolean = status == EngineStatus.RUNNING
}
