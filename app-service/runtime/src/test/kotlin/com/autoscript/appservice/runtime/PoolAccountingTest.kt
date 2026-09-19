package com.autoscript.appservice.runtime

import com.autoscript.domain.bridge.HandleRef
import com.autoscript.domain.engine.EngineId
import com.autoscript.domain.engine.EngineRunReceipt
import com.autoscript.domain.engine.EngineRunRequest
import com.autoscript.domain.engine.EngineStatus
import com.autoscript.domain.engine.KillCause
import com.autoscript.domain.engine.ScriptEngine
import com.autoscript.domain.engine.StopResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

/**
 * 池记账一致性（§8.2）：许可证（Semaphore）与槽位状态（FREE/BUSY）必须始终一一对应。
 *
 * 三条不变量：
 * 1. **并发 acquire 各占各槽**：选槽与占槽必须原子 —— 否则并发 acquire 会选中同一 FREE 槽，
 *    同一引擎进程跑两个脚本（违反 §8.2「每脚本一进程」）；
 * 2. **杀槽必还证**：kill 收归（[EnginePool.recycle]）必须归还许可证 —— 否则 free=1 却
 *    无证可领，池容量永久缩水（后续 acquire 挂到超时）；
 * 3. **句柄带代次**：槽位被 kill 回收后重分配，旧句柄的 release 不得拆掉新占用者（§7.4 generation 纪律）。
 */
class PoolAccountingTest {

    /** 可挂起的假引擎：execute 停在 gate 上，用于把「选槽 → 占槽」窗口撑开。 */
    private class GatedEngine(override val id: EngineId) : ScriptEngine {
        val started = CompletableDeferred<Unit>()
        var gate = CompletableDeferred<Unit>()
        var executeCount = 0
            private set
        var killCount = 0
            private set
        var stopCount = 0
            private set

        override suspend fun execute(run: EngineRunRequest): EngineRunReceipt {
            executeCount++
            started.complete(Unit)
            gate.await()
            return EngineRunReceipt(runId = 1L, handle = HandleRef(0, 1))
        }

        override suspend fun stop(): StopResult {
            stopCount++
            return StopResult.Clean
        }

        override suspend fun kill(): KillCause {
            killCount++
            return KillCause.REQUESTED
        }

        override suspend fun status(): EngineStatus = EngineStatus.RUNNING

        /** 放行 execute（可重复开门：槽位重分配后新一轮 execute 停在新 gate 上）。 */
        fun open() {
            gate.complete(Unit)
            gate = CompletableDeferred()
        }
    }

    /** 不变量 1：并发 acquire 不得选中同一槽位。 */
    @Test
    fun `并发 acquire 各占各槽 不得同槽双跑`() = runBlocking {
        val engines = MutableList(2) { GatedEngine(EngineId(it)) }
        val pool = FixedEnginePool({ id -> engines[id.poolIndex] }, capacity = 2)

        val a = async { pool.acquire(PoolAcquireRequest("p1", "a.js")) }
        engines[0].started.await()          // A 已进入 execute（选槽窗口已被覆盖）

        val b = async { pool.acquire(PoolAcquireRequest("p2", "b.js")) }
        engines[1].started.await()          // B 必须落到另一个槽

        assertEquals(1, engines[0].executeCount, "同一引擎进程不得并发跑两个脚本（§8.2 每脚本一进程）")
        assertEquals(1, engines[1].executeCount)
        engines[0].open()
        engines[1].open()
        a.await()
        b.await()

        Unit  // 显式收尾：void 返回值才被 JUnit5 视为测试
    }

    /** 不变量 2：kill 收归 → 许可证必须归还，池容量不缩水。 */
    @Test
    fun `recycle 杀槽后许可证归还 池容量不缩水`() = runBlocking {
        val engines = MutableList(1) { FakeEngine(EngineId(it)) }
        val pool = FixedEnginePool({ id -> engines[id.poolIndex] }, capacity = 1)

        val first = assertInstanceOf(
            PoolAcquireOutcome.Granted::class.java,
            pool.acquire(PoolAcquireRequest("p1", "a.js")),
        ).handle
        assertEquals(PoolStats(1, free = 0, busy = 1), pool.stats())

        pool.recycle(first.slot)            // 看门狗 kill 后收归（kill 权威 RuntimeController，此处直测池记账）
        assertEquals(SlotState.FREE, first.slot.state, "槽位已回收")

        // 关键：槽位 FREE 但许可证未还 = 池永久缩水（第二次 acquire 会挂到超时）
        val second = pool.acquire(PoolAcquireRequest("p2", "b.js", waitTimeoutMillis = 300))
        assertInstanceOf(
            PoolAcquireOutcome.Granted::class.java, second,
            "杀槽必须归还许可证：否则 free=1 却无证可领，池容量永久缩水",
        )

        Unit  // 显式收尾：void 返回值才被 JUnit5 视为测试
    }

    /** 不变量 2b：recycle 幂等 —— 重复收归不超发许可证。 */
    @Test
    fun `recycle 幂等 重复收归不超发许可证`() = runBlocking {
        val engines = MutableList(1) { FakeEngine(EngineId(it)) }
        val pool = FixedEnginePool({ id -> engines[id.poolIndex] }, capacity = 1)

        val handle = assertInstanceOf(
            PoolAcquireOutcome.Granted::class.java,
            pool.acquire(PoolAcquireRequest("p1", "a.js")),
        ).handle

        pool.recycle(handle.slot)
        pool.recycle(handle.slot)           // 重复收归：不得再还一次证
        assertEquals(PoolStats(1, free = 1, busy = 0), pool.stats())

        // 若超发：两次 acquire 都能立刻拿到证，但只有一个槽 → 第二次必然排队到超时
        val a = pool.acquire(PoolAcquireRequest("p2", "b.js", waitTimeoutMillis = 300))
        val b = pool.acquire(PoolAcquireRequest("p3", "c.js", waitTimeoutMillis = 300))
        assertInstanceOf(PoolAcquireOutcome.Granted::class.java, a)
        assertInstanceOf(PoolAcquireOutcome.TimedOut::class.java, b, "容量 1：第二次 acquire 必须排队到超时")

        Unit  // 显式收尾：void 返回值才被 JUnit5 视为测试
    }

    /** 不变量 3：过期句柄（generation 不匹配）release 是空操作，不拆新占用者。 */
    @Test
    fun `杀槽重分配后 旧句柄 release 不得拆掉新占用者`() = runBlocking {
        // FakeEngine：execute 立即返回、stop 计数 —— 无 gate，无协程时序竞态
        val engines = MutableList(1) { FakeEngine(EngineId(it)) }
        val pool = FixedEnginePool({ id -> engines[id.poolIndex] }, capacity = 1)

        val stale = assertInstanceOf(
            PoolAcquireOutcome.Granted::class.java,
            pool.acquire(PoolAcquireRequest("p1", "a.js")),
        ).handle
        assertEquals(PoolStats(1, free = 0, busy = 1), pool.stats())

        pool.recycle(stale.slot)            // 看门狗 kill 裁决 → recycle（槽位 FREE + 证还回 + 代次 +1）

        // 代次验证：stale 句柄捕获的代次已过期（handle 构造后槽位被收归推进过）
        assertFalse(stale.slotGeneration == stale.slot.generation, "stale 句柄代次必须过期")

        val fresh = assertInstanceOf(
            PoolAcquireOutcome.Granted::class.java,
            pool.acquire(PoolAcquireRequest("p2", "b.js", waitTimeoutMillis = 300)),
        ).handle
        assertEquals(PoolStats(1, free = 0, busy = 1), pool.stats())

        // 迟到释放：旧脚本的收尾（scheduler 侧）绝不能停掉新脚本的引擎
        assertEquals(StopResult.Clean, pool.release(stale))
        assertEquals(2, engines[0].executed.size, "第二次 acquire 会再次 execute 同一（已回收）引擎")
        assertEquals(0, engines[0].stopCalls, "旧句柄不得触发新占用者的 stop")
        assertEquals(PoolStats(1, free = 0, busy = 1), pool.stats(), "槽位仍归新句柄占用")

        pool.release(fresh)
        assertEquals(PoolStats(1, free = 1, busy = 0), pool.stats())
    }
}
