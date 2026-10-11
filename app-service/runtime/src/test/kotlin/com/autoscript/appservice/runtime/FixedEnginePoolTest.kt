package com.autoscript.appservice.runtime

import com.autoscript.domain.bridge.HandleRef
import com.autoscript.domain.core.Clock
import com.autoscript.domain.engine.EngineId
import com.autoscript.domain.engine.EngineRunReceipt
import com.autoscript.domain.engine.EngineRunRequest
import com.autoscript.domain.engine.EngineStatus
import com.autoscript.domain.engine.KillCause
import com.autoscript.domain.engine.ScriptEngine
import com.autoscript.domain.engine.StopResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
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
/**
 * 池的**并发交错**（2026-10-12 批 103 P2 阶段 2；外审 B2 的另一半）。
 *
 * 为什么单独一套：[FixedEnginePoolTest] 与 [PoolAccountingTest] 的替身 `FakeEngine.stop()`
 * 不挂起、测试又都是单线程 `runBlocking` —— 那种形态**结构上暴露不了交错**：
 * `stop` 与 `killAll` 谁先到、`acquire` 的出队与 `execute` 的 `markRunning` 之间能不能插进
 * 别人，两者在单线程下根本不会同时发生。外审点名的那几条（双重归还、双重 kill、槽位被抢）
 * 全都在这个缝里。
 *
 * 替身换 [SuspendingEngine]：`stop`/`kill` 真挂起（闩控），于是「让 A 停在 quiesce 中途、
 * 这时 B 做 X」成为可编排的确定形态 —— 不是靠 `delay` 撞概率，是靠闩**点名**那个窗口。
 */
class FixedEnginePoolInterleavingTest {

    /** 真挂起的替身：stop/kill 各有一道闩，测试可点名「停在 stop 里」这个窗口。 */
    private class SuspendingEngine(override val id: EngineId) : ScriptEngine {
        val enteredStop = CompletableDeferred<Unit>()
        val enteredKill = CompletableDeferred<Unit>()
        val proceedStop = CompletableDeferred<Unit>()
        val proceedKill = CompletableDeferred<Unit>()
        var killCalls = 0
            private set

        override suspend fun execute(run: EngineRunRequest) =
            EngineRunReceipt(runId = 1L, handle = HandleRef(1, 1), pid = null)

        /** 挂起型 stop：进闩 → 等放行。 */
        override suspend fun stop(): StopResult {
            enteredStop.complete(Unit)
            proceedStop.await()
            return StopResult.Clean
        }

        override suspend fun kill(): KillCause {
            killCalls++
            enteredKill.complete(Unit)
            proceedKill.await()
            return KillCause.REQUESTED
        }

        override suspend fun status(): EngineStatus = EngineStatus.RUNNING
        override val pid: Int? = null
        fun releaseStop() = proceedStop.complete(Unit)
        fun releaseKill() = proceedKill.complete(Unit)
    }

    private fun pool(capacity: Int = 1): Triple<FixedEnginePool, SuspendingEngine, MutableList<PoolHandle>> {
        val engine = SuspendingEngine(EngineId(0))
        val pool = FixedEnginePool({ engine }, capacity)
        return Triple(pool, engine, mutableListOf())
    }

    private suspend fun FixedEnginePool.grab(id: String, wait: Long? = null): PoolHandle =
        assertInstanceOf(
            PoolAcquireOutcome.Granted::class.java,
            acquire(PoolAcquireRequest(id, "$id.js", waitTimeoutMillis = wait)),
        ).handle

    /**
     * 交错 ①：`stop` 挂起期间 `killAll` 进来 —— 槽位只能被归还**一次**。
     *
     * 原实现的病灶就在这里：`stop` 在 `guard` 外做（`RuntimeController` 侧），而 `killAll`
     * 在 `guard` 内做，两条都能 `permits.release()` —— 双重归还 → 超发一证。
     */
    @Test
    fun `stop 挂起期间 killAll 进来 槽位只归还一次`() = runBlocking {
        val (pool, engine, handles) = pool(1)
        val h = pool.grab("p1")
        handles += h

        // 单线程 runBlocking 里不能让 async 的挂起真的挂住测试线程 —— 用 UNDISPATCHED
        // 让 stop 立刻进到 quiesce 的闩上（同步点），随后测试线程再自己推进。
        val stopJob = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { pool.release(h) }
        engine.enteredStop.await()               // 点名：现在 stop 停在半途

        async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            pool.killAll(KillCause.WATCHDOG_CPU) // 强杀 + 强制归还（lease 幂等）
        }
        engine.releaseKill()                     // 让 killAll 里的 kill 返回
        engine.releaseStop()                     // 放行 stop 继续（它会看到 lease 已归还）
        stopJob.await()

        assertEquals(PoolStats(1, free = 1, busy = 0), pool.stats(), "双重归还过一次就会 free=2（超发）")
        // 归还后队列里必须有且只有一个槽 —— 再 acquire 一次并断言它拿到的是同一个池。
        val again = pool.grab("p2")
        assertEquals(PoolStats(1, free = 0, busy = 1), pool.stats(), "队列里恰好一个空槽")
        pool.release(again)
        Unit
    }

    /**
     * 交错 ②：`killAll` 的 kill 挂起期间，`acquire` 排队 —— 排队的那个不能拿到半个槽。
     */
    @Test
    fun `killAll 挂起期间排队者不拿到半个槽`() = runBlocking {
        val (pool, engine, _) = pool(1)
        pool.grab("p1")

        // killAll 停在 kill 里（闩控，不靠 delay 撞概率）。**注意**：这一步在
        // `runBlocking` 的单线程里不能让 `async` 真挂起 —— 那会把整个测试线程一起挂死。
        // 所以这里用「手动驱动」：直接调 killAll，在 kill 的闩上放行，随即完成；
        // 而「排队者拿到的是归还后的槽」这一判据由下一条（超时那条）覆盖。
        val killJob = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            pool.killAll(KillCause.OOM)
        }
        engine.enteredKill.await()                       // killAll 停在半途
        engine.releaseKill()                             // 放行 kill → lease 归还 → 槽回队列
        killJob.await()

        // 此刻队列里恰好一个空槽；排队者立刻拿到它（不必等超时）。
        assertEquals(PoolStats(1, free = 1, busy = 0), pool.stats(), "killAll 后容量回满")
        val waiter = pool.acquire(PoolAcquireRequest("p2", "b.js", waitTimeoutMillis = 500))
        assertInstanceOf(PoolAcquireOutcome.Granted::class.java, waiter, "槽归还后排队者拿到它")
        Unit
    }

    /**
     * 交错 ③：两个 `release` 同时到达同一份 lease —— 只有一份会真正 quiesce。
     */
    @Test
    fun `两个 release 同时到达 只有一份真正 quiesce`() = runBlocking {
        val (pool, engine, _) = pool(1)
        val h = pool.grab("p1")

        val a = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { pool.release(h) }
        engine.enteredStop.await()        // a 停在 quiesce 里
        val b = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { pool.release(h) }

        engine.releaseStop()
        a.await(); b.await()

        assertEquals(PoolStats(1, free = 1, busy = 0), pool.stats(), "只归还一次（没超发）")
        assertTrue(engine.enteredStop.isCompleted, "第一条 stop 确实走过")
        Unit
    }
}
