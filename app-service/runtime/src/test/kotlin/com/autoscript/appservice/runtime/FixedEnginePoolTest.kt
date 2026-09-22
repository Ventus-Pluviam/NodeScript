package com.autoscript.appservice.runtime

import com.autoscript.domain.core.Clock
import com.autoscript.domain.engine.EngineId
import com.autoscript.domain.engine.KillCause
import com.autoscript.domain.engine.StopResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FixedEnginePoolTest {

    private fun fixedClock(): Clock {
        var t = 0L
        return Clock { t++ }
    }

    @Test
    fun `acquire 在空槽启动引擎并回填请求`() = runBlocking {
        val engines = MutableList(2) { FakeEngine(EngineId(it)) }
        val pool = FixedEnginePool({ id -> engines[id.poolIndex] }, capacity = 2, clock = fixedClock())

        val outcome = pool.acquire(PoolAcquireRequest(projectId = "p1", scriptPath = "main.js"))
        val granted = assertInstanceOf(PoolAcquireOutcome.Granted::class.java, outcome)
        assertEquals(1, engines[0].executed.size)
        assertEquals("main.js", engines[0].executed.single().scriptPath)
        assertEquals("p1", granted.handle.request.projectId)
        assertEquals(PoolStats(2, free = 1, busy = 1), pool.stats())
    }

    @Test
    fun `满池排队并在释放后获准`() = runBlocking {
        val engines = MutableList(1) { FakeEngine(EngineId(it)) }
        val pool = FixedEnginePool({ id -> engines[id.poolIndex] }, capacity = 1)
        val first = assertInstanceOf(
            PoolAcquireOutcome.Granted::class.java,
            pool.acquire(PoolAcquireRequest("p1", "a.js")),
        ).handle

        val secondStarted = CompletableDeferred<Boolean>()
        var second: PoolAcquireOutcome? = null
        val job = launch {
            second = pool.acquire(PoolAcquireRequest("p2", "b.js", waitTimeoutMillis = 5_000))
            secondStarted.complete(true)
        }
        // 池满时第二个请求必须挂起等待，而非立刻获得
        assertTrue(!secondStarted.isCompleted)
        pool.release(first)
        job.join()
        assertInstanceOf(PoolAcquireOutcome.Granted::class.java, second)
        assertEquals(2, engines[0].executed.size, "执行两次：释放后第二个才跑")
    }

    @Test
    fun `排队超时返回 TimedOut 且不占槽`() = runBlocking {
        val engines = MutableList(1) { FakeEngine(EngineId(it)) }
        val pool = FixedEnginePool({ id -> engines[id.poolIndex] }, capacity = 1)
        val first = assertInstanceOf(
            PoolAcquireOutcome.Granted::class.java,
            pool.acquire(PoolAcquireRequest("p1", "a.js")),
        ).handle

        val outcome = pool.acquire(PoolAcquireRequest("p2", "b.js", waitTimeoutMillis = 100))
        assertInstanceOf(PoolAcquireOutcome.TimedOut::class.java, outcome)
        pool.release(first)
        assertEquals(PoolStats(1, free = 1, busy = 0), pool.stats())
    }

    @Test
    fun `引擎启动失败返回 Failed 并还槽`() = runBlocking {
        val engines = MutableList(1) { FakeEngine(EngineId(it)) }.also { it[0].failOnExecute = true }
        val pool = FixedEnginePool({ id -> engines[id.poolIndex] }, capacity = 1)
        val outcome = pool.acquire(PoolAcquireRequest("p1", "a.js"))
        assertInstanceOf(PoolAcquireOutcome.Failed::class.java, outcome)
        assertEquals(PoolStats(1, free = 1, busy = 0), pool.stats(), "失败必须收回槽位")
    }

    @Test
    fun `stop 超时时 release 由池 kill 兜底`() = runBlocking {
        val engines = MutableList(1) { FakeEngine(EngineId(it)) }
        val pool = FixedEnginePool({ id -> engines[id.poolIndex] }, capacity = 1)
        val handle = assertInstanceOf(
            PoolAcquireOutcome.Granted::class.java,
            pool.acquire(PoolAcquireRequest("p1", "a.js")),
        ).handle

        engines[0].stopResult = StopResult.TimedOut(partial = true)
        val result = pool.release(handle)
        assertInstanceOf(StopResult.TimedOut::class.java, result)
        assertEquals(1, engines[0].killCalls, "TimedOut 后必须 kill 兜底")
        assertEquals(PoolStats(1, free = 1, busy = 0), pool.stats())
    }

    @Test
    fun `release 幂等：重复释放不崩`() = runBlocking {
        val engines = MutableList(1) { FakeEngine(EngineId(it)) }
        val pool = FixedEnginePool({ id -> engines[id.poolIndex] }, capacity = 1)
        val handle = assertInstanceOf(
            PoolAcquireOutcome.Granted::class.java,
            pool.acquire(PoolAcquireRequest("p1", "a.js")),
        ).handle
        pool.release(handle)
        assertEquals(StopResult.Clean, pool.release(handle), "重复 release 应视为已净")
        assertEquals(PoolStats(1, free = 1, busy = 0), pool.stats())
    }

    @Test
    fun `killAll 强杀忙槽复位且不超发许可证`() = runBlocking {
        val engines = MutableList(2) { FakeEngine(EngineId(it)) }
        val pool = FixedEnginePool({ id -> engines[id.poolIndex] }, capacity = 2)
        val handle = assertInstanceOf(
            PoolAcquireOutcome.Granted::class.java,
            pool.acquire(PoolAcquireRequest("p1", "a.js")),
        ).handle

        pool.killAll(KillCause.WATCHDOG_CPU)
        assertEquals(1, engines[0].killCalls, "只杀忙槽")
        assertEquals(0, engines[1].killCalls, "空闲槽不杀")
        assertEquals(PoolStats(2, free = 2, busy = 0), pool.stats())

        // killAll 后旧句柄再 release：必须视为已净且不再超发许可证
        assertEquals(StopResult.Clean, pool.release(handle))
        assertEquals(PoolStats(2, free = 2, busy = 0), pool.stats())
    }
}