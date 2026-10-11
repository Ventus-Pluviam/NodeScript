package com.autoscript.appservice.runtime

import com.autoscript.domain.core.Clock
import com.autoscript.domain.core.SystemClock
import com.autoscript.domain.engine.EngineId
import com.autoscript.domain.engine.EngineRunRequest
import com.autoscript.domain.engine.KillCause
import com.autoscript.domain.engine.ScriptEngine
import com.autoscript.domain.engine.StopResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 定容引擎进程池（docs §8.2 进程池决议）：
 * - 并发上限 = 容量（公平 Semaphore）；超载挂起等待，绝不静默丢任务（§8.6）；
 * - acquire 中引擎启动失败自动收回槽位 + 还许可证（不占槽）；
 * - release 走 [PoolSlot.quiesce]，TimedOut → kill 兜底；
 * - killAll 强杀全部非 FREE 槽位并回许可证。
 *
 * 并发约定（2026-10-11 批 96 修订）：killAll 与 acquire/release **不再要求调用方整段串行化** ——
 * 排队等证（[acquire] 的 `permits.acquire`）可能挂到 `waitTimeoutMillis`（调度链路 60–120s），
 * 把它圈进调用方的锁里会让 stop/killAll/看门狗取锚一起停摆（外审 B1 实测：容量 1 时
 * 「A 在跑、B 排队」→ `stop(A)` 耗时 ≈ B 的排队超时）。现在本类自己扛并发，靠两件：
 * 1. **急停代次**（[stopEpoch]）：[killAll] 推进一步；抢槽路径在「等完证夺槽前」与
 *    「execute 返回后」两处对表，代次变了即视为本条已被急停作废 —— 绝不夺槽、绝不
 *    `markRunning`（那会在已被 forceFree 的槽上抛非法转移，并把刚起的进程留在账外）；
 * 2. **夺槽仍原子**：许可证 → 槽位一一对应依旧在 stateLock 临界区内一次完成。
 * 槽位代次（§7.4 generation 纪律）在 stateLock 临界区内读写，过期句柄 release 不拆新占用者。
 */
class FixedEnginePool(
    engineFactory: (EngineId) -> ScriptEngine,
    override val capacity: Int,
    private val clock: Clock = SystemClock,
) : EnginePool {

    init {
        require(capacity > 0) { "池容量必须 > 0: $capacity" }
    }

    private val slots: List<PoolSlot> =
        List(capacity) { PoolSlot(it, engineFactory(EngineId(it)), clock) }
    // kotlinx-coroutines Semaphore 无 fair 参数（fair 是 JUC API），默认即 FIFO 公平队
    private val permits = Semaphore(capacity)
    private val stateLock = Any()   // 非挂起记账临界区：夺槽/recycle/release 的许可证↔槽位对应在此原子完成

    /**
     * 急停代次（[killAll] 推进；读写均在 stateLock 临界区内）。
     *
     * 为什么要它：调用方不再整段串行化 acquire 与 killAll（见类 KDoc），于是「排队等证」
     * 与「execute 启动中」两个窗口里都可能发生急停。两个窗口各自对表一次，代次变了就把
     * 本条 acquire 判为作废 —— 这是「killAll 返回之后不存在账外执行体」这条不变式的落点。
     */
    private var stopEpoch: Long = 0L

    override suspend fun acquire(request: PoolAcquireRequest): PoolAcquireOutcome {
        // 等证前先记代次：这一步可能挂到 waitTimeoutMillis（调度链路 60–120s），
        // 期间完全可能与 killAll 并发 —— 等完再对表。
        val epochAtEntry = synchronized(stateLock) { stopEpoch }

        val gotPermit = if (request.waitTimeoutMillis == null) {
            permits.acquire()
            true
        } else {
            withTimeoutOrNull(request.waitTimeoutMillis) { permits.acquire() } != null
        }
        if (!gotPermit) return PoolAcquireOutcome.TimedOut

        // 夺槽必须原子且**先于** execute：许可证 → 槽位一一对应（stateLock 临界区内 markBusy + 代次前进）。
        // 若把 markBusy 放到 execute 之后，execute 的真实启动耗时就是窗口期 ——
        // 并发 acquire 会同时选中同一 FREE 槽，同一引擎进程跑两个脚本（违反 §8.2 每脚本一进程）。
        val slot: PoolSlot
        synchronized(stateLock) {
            if (stopEpoch != epochAtEntry) {
                permits.release()       // 等证期间急停：本条作废，立刻还证（不夺槽、不起进程）
                return PoolAcquireOutcome.Failed(ABORTED_BY_STOP)
            }
            val free = slots.firstOrNull { it.state == SlotState.FREE }
            if (free == null) {
                permits.release()       // 有证无槽 = 记账失真，绝不吞证转死锁
                return PoolAcquireOutcome.Failed("池记账失真：持有许可证但无 FREE 槽")
            }
            free.markBusy(clock.nowMillis())
            free.occupy()
            slot = free
        }
        val generation = slot.generation

        return try {
            val receipt = slot.engine.execute(
                EngineRunRequest(
                    projectId = request.projectId,
                    scriptPath = request.scriptPath,
                    args = request.args,
                    runNonce = request.runNonce,        // 幂等锚点透传执行体（§8.5）
                    timeoutMillis = request.scriptTimeoutMillis,
                    // 授权快照原样下传（A5，§11）：引擎不重算，只把它交给身份签发方。
                    authorization = request.authorization,
                )
            )
            if (!stillOurs(slot, generation)) {
                // 启动期间被 killAll 强释（槽位已 FREE / 代次已推进）：这条执行体已不属于任何人 ——
                // 当场收掉，**绝不 markRunning**（那会在 IDLE 上抛非法状态转移，且把一条账外野进程
                // 留在设备上）。急停广播路径本就在池内置 kill，这里同一条纪律。
                killQuietly(slot)
                PoolAcquireOutcome.Failed(ABORTED_BY_STOP)
            } else {
                slot.markRunning()           // execute 返回 = 进程已拉起：BOOTING → RUNNING（§8.3）
                PoolAcquireOutcome.Granted(PoolHandle(request, slot, receipt))
            }
        } catch (e: CancellationException) {
            recycle(slot)               // 调用方取消：槽位复位 + 还证必须成对，否则池缩水
            throw e                     // 取消语义照常向上传播
        } catch (e: Exception) {
            recycle(slot)               // 引擎启动失败：槽位收回（Failed 契约）
            PoolAcquireOutcome.Failed(e.message ?: (e::class.simpleName ?: "engine start failed"))
        }
    }

    /** 本槽位是否仍是这次 acquire 的（未被 killAll 强释）：stateLock 临界区内的对表。 */
    private fun stillOurs(slot: PoolSlot, generation: Long): Boolean = synchronized(stateLock) {
        slot.state != SlotState.FREE && slot.generation == generation
    }

    /** 收掉一条**没人认领**的执行体（见 [acquire] 的急停分支）；已经死了/收不动都不阻断归还路径。 */
    private suspend fun killQuietly(slot: PoolSlot) {
        try {
            slot.engine.kill()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 进程可能已随 killAll 死掉：收尸失败不改变「本条作废」这个结论。
        }
    }

    override suspend fun release(handle: PoolHandle): StopResult {
        // killAll 已强释的槽位（FREE）重复 release：视为已净，绝不超发许可证
        if (handle.slot.state == SlotState.FREE) return StopResult.Clean
        // 过期句柄（槽位被 kill 回收后又重分配）：绝不拆新占用者（§7.4 generation 纪律）
        synchronized(stateLock) {
            if (handle.slotGeneration != handle.slot.generation) return StopResult.Clean
        }
        return try {
            handle.slot.quiesce()
        } finally {
            permits.release()
        }
    }

    override suspend fun killAll(reason: KillCause) {
        // 代次推进与强释同临界区：并发的 acquire 要么在夺槽前看到新代次（直接作废），
        // 要么在 execute 返回后看到槽位已 FREE（走 killQuietly 那条），不存在第三种。
        synchronized(stateLock) { stopEpoch += 1 }
        slots.forEach { slot ->
            if (slot.state != SlotState.FREE) {
                slot.engine.kill()
                slot.forceFree(reason)
                permits.release()
            }
        }
    }

    override fun stats(): PoolStats {
        val free = slots.count { it.state == SlotState.FREE }
        return PoolStats(capacity = capacity, free = free, busy = capacity - free)
    }

    /**
     * 看门狗 kill 后的槽位收归（§8.4 kill → 重分配）：
     * 槽位复位 + 许可证归还必须原子（同一把 stateLock 内），
     * 否则「槽位 FREE 但许可证已超发/未还」都会让池记账失真：
     * - 未还证 → free=1 但无证可领，池容量永久缩水；
     * - release（旧句柄）补还 → 超发一证，两个占用者并存。
     * 收归同时推进占位代次：旧句柄在此之后一律过期，放不进 [release]。
     *
     * @param cause 收归原因（§8.3 状态机归类）：watchdog/OOM 原因 → CRASHED；null 或
     *   [KillCause.REQUESTED] 在 RUNNING/QUIESCING 收归 → STOPPED，BOOTING（启动失败
     *   走本方法收口）→ CRASHED —— 没跑起来的「主动收」不伪造干净停。
     *   调用方不关心终态归因时可省。
     */
    override fun recycle(slot: PoolSlot, cause: KillCause?) {
        synchronized(stateLock) {
            if (slot.state != SlotState.FREE) {
                slot.reuse(cause ?: KillCause.REQUESTED)   // RUNNING→STOPPED；BOOTING 启动失败→CRASHED
                slot.occupy()          // 收归也推进代次：旧句柄此后一律过期
                permits.release()
            }
            // 已 FREE：幂等收归，不超发许可证
        }
    }

    companion object {
        /**
         * 等证/启动期间撞上急停的如实回话（[acquire] → [PoolAcquireOutcome.Failed]）：
         * 不是"引擎起不来"，而是"这条启动已被急停作废" —— 两者在诊断上必须分得开。
         */
        const val ABORTED_BY_STOP: String = "启动期间宿主急停（killAll/forceStopAll）：本次执行已作废"
    }
}