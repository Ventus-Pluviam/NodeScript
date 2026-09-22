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
 * 并发约定：killAll 与 acquire/release 需由调用方（RuntimeController）串行化，防许可证超发；
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

    override suspend fun acquire(request: PoolAcquireRequest): PoolAcquireOutcome {
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
        val slot = synchronized(stateLock) {
            val free = slots.firstOrNull { it.state == SlotState.FREE }
            if (free == null) {
                permits.release()       // 有证无槽 = 记账失真，绝不吞证转死锁
                null
            } else {
                free.markBusy(clock.nowMillis())
                free.occupy()
                free
            }
        } ?: return PoolAcquireOutcome.Failed("池记账失真：持有许可证但无 FREE 槽")

        return try {
            val receipt = slot.engine.execute(
                EngineRunRequest(
                    projectId = request.projectId,
                    scriptPath = request.scriptPath,
                    args = request.args,
                    runNonce = request.runNonce,        // 幂等锚点透传执行体（§8.5）
                    timeoutMillis = request.scriptTimeoutMillis,
                )
            )
            slot.markRunning()               // execute 返回 = 进程已拉起：BOOTING → RUNNING（§8.3）
            PoolAcquireOutcome.Granted(PoolHandle(request, slot, receipt))
        } catch (e: CancellationException) {
            recycle(slot)               // 调用方取消：槽位复位 + 还证必须成对，否则池缩水
            throw e                     // 取消语义照常向上传播
        } catch (e: Exception) {
            recycle(slot)               // 引擎启动失败：槽位收回（Failed 契约）
            PoolAcquireOutcome.Failed(e.message ?: (e::class.simpleName ?: "engine start failed"))
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
}