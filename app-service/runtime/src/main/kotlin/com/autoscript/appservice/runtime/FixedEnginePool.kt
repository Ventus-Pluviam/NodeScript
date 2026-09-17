package com.autoscript.appservice.runtime

import com.autoscript.domain.engine.EngineId
import com.autoscript.domain.engine.EngineRunRequest
import com.autoscript.domain.engine.KillCause
import com.autoscript.domain.engine.ScriptEngine
import com.autoscript.domain.engine.StopResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 定容引擎进程池（docs §8.2 进程池决议）：
 * - 并发上限 = 容量（公平 Semaphore）；超载挂起等待，绝不静默丢任务（§8.6）；
 * - acquire 中引擎启动失败自动还许可证（不占槽）；
 * - release 走 [PoolSlot.quiesce]，TimedOut → kill 兜底；
 * - killAll 强杀全部非 FREE 槽位并回许可证。
 *
 * 并发约定：killAll 与 acquire/release 需由调用方（RuntimeController）串行化，防许可证超发。
 */
class FixedEnginePool(
    engineFactory: (EngineId) -> ScriptEngine,
    override val capacity: Int,
    private val clock: RuntimeClock = RuntimeClock.system,
) : EnginePool {

    init {
        require(capacity > 0) { "池容量必须 > 0: $capacity" }
    }

    private val slots: List<PoolSlot> =
        List(capacity) { PoolSlot(it, engineFactory(EngineId(it)), clock) }
    // kotlinx-coroutines Semaphore 无 fair 参数（fair 是 JUC API），默认即 FIFO 公平队
    private val permits = Semaphore(capacity)
    private val mutex = Mutex()

    override suspend fun acquire(request: PoolAcquireRequest): PoolAcquireOutcome {
        val gotPermit = if (request.waitTimeoutMillis == null) {
            permits.acquire()
            true
        } else {
            withTimeoutOrNull(request.waitTimeoutMillis) { permits.acquire() } != null
        }
        if (!gotPermit) return PoolAcquireOutcome.TimedOut

        val slot = mutex.withLock { slots.first { it.state == SlotState.FREE } }
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
            slot.markBusy(clock.nowMillis())
            PoolAcquireOutcome.Granted(PoolHandle(request, slot, receipt))
        } catch (e: Exception) {
            permits.release()
            PoolAcquireOutcome.Failed(e.message ?: (e::class.simpleName ?: "engine start failed"))
        }
    }

    override suspend fun release(handle: PoolHandle): StopResult {
        // killAll 已强释的槽位（FREE）重复 release：视为已净，绝不超发许可证
        if (handle.slot.state == SlotState.FREE) return StopResult.Clean
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
                slot.forceFree()
                permits.release()
            }
        }
    }

    override fun stats(): PoolStats {
        val free = slots.count { it.state == SlotState.FREE }
        return PoolStats(capacity = capacity, free = free, busy = capacity - free)
    }
}