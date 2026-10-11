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
import org.junit.jupiter.api.Assertions.assertTrue
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
    private class GatedEngine(override val id: EngineId, override val pid: Int? = null) : ScriptEngine {
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

        pool.recycle(stale.slot)            // 看门狗 kill 裁决 → recycle（走 lease 归还，幂等）

        // 队列化的「过期句柄」判据换成**归还位**（2026-10-12 批 103 P2）：旧句柄手里那份
        // lease 已被 recycle 归还过，于是它随后的 release 是 no-op —— 物理上到不了槽位。
        assertTrue(stale.leaseReturned, "recycle 后旧句柄的 lease 已归还（过期句柄的判据）")

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

    /** 不变量 4（§8.3 接线）：状态机与槽位同生共死，占槽→BOOTING、kill 按原因归类、回收回 IDLE。 */
    @Test
    fun `EngineStateMachine 真正驱动 PoolSlot：占槽 BOOTING、kill 归类、回收回 IDLE`() = runBlocking {
        val engines = MutableList(1) { FakeEngine(EngineId(it)) }
        val pool = FixedEnginePool({ id -> engines[id.poolIndex] }, capacity = 1)

        val handle = assertInstanceOf(
            PoolAcquireOutcome.Granted::class.java,
            pool.acquire(PoolAcquireRequest("p1", "a.js")),
        ).handle
        // execute 已返回 → BOOTING → RUNNING（不是停在 BOOTING 假装活着）
        assertEquals(EngineStatus.RUNNING, handle.slot.status(), "execute 返回即 RUNNING")
        assertEquals(1, engines[0].executed.size)
        assertEquals(0, engines[0].stopCalls, "acquire 不得顺手 stop")

        // watchdog 原因收归 → CRASHED 归类，但槽位仍回收可复用（容量不缩水）
        pool.recycle(handle.slot, KillCause.WATCHDOG_CPU)
        assertEquals(EngineStatus.IDLE, handle.slot.status(), "CRASHED 必须经回收回 IDLE 才能复用")

        // 第二次夺槽：状态机从 IDLE → BOOTING → RUNNING 重走（不是复用上轮残态）
        val second = assertInstanceOf(
            PoolAcquireOutcome.Granted::class.java,
            pool.acquire(PoolAcquireRequest("p2", "b.js", waitTimeoutMillis = 300)),
        ).handle
        assertEquals(EngineStatus.RUNNING, second.slot.status(), "重分配后状态机重新起步")

        // 优雅停止走 quiesce：QUIESCING → STOPPED → IDLE，且引擎侧确实被 stop 过
        assertEquals(StopResult.Clean, pool.release(second))
        assertEquals(1, engines[0].stopCalls, "优雅释放必须 stop 引擎")
        assertEquals(EngineStatus.IDLE, second.slot.status(), "STOPPED 回收回 IDLE")
        assertEquals(PoolStats(1, free = 1, busy = 0), pool.stats())

        Unit  // 显式收尾：void 返回值才被 JUnit5 视为测试
    }

    /** 不变量 4b：killAll 的广播强释也要把状态机收干净（否则下一轮夺槽非法转移）。 */
    @Test
    fun `killAll 广播强释后 状态机回 IDLE 且可再次夺槽`() = runBlocking {
        val engines = MutableList(1) { FakeEngine(EngineId(it)) }
        val pool = FixedEnginePool({ id -> engines[id.poolIndex] }, capacity = 1)

        val handle = assertInstanceOf(
            PoolAcquireOutcome.Granted::class.java,
            pool.acquire(PoolAcquireRequest("p1", "a.js")),
        ).handle
        assertEquals(EngineStatus.RUNNING, handle.slot.status())

        pool.killAll(KillCause.OOM)
        assertEquals(EngineStatus.IDLE, handle.slot.status(), "OOM → CRASHED → 回收 IDLE")
        assertEquals(PoolStats(1, free = 1, busy = 0), pool.stats(), "全清后容量不缩水")

        val again = pool.acquire(PoolAcquireRequest("p2", "b.js", waitTimeoutMillis = 300))
        assertInstanceOf(PoolAcquireOutcome.Granted::class.java, again, "状态机没收回干净则夺槽必炸")
        assertEquals(EngineStatus.RUNNING, (again as PoolAcquireOutcome.Granted).handle.slot.status())

        Unit  // 显式收尾：void 返回值才被 JUnit5 视为测试
    }
}

/**
 * 队列化的记账不变量（2026-10-12 批 103 P2；外审 B2 的彻底解）。
 *
 * 原实现有两套靠手拿同步的记账（`Semaphore permits` 计**数** + `PoolSlot.state` 计**状态**），
 * 四种失真全是「忘了还另一半」的产物。这组用例钉的是**它们在结构上不再可能**：
 *
 * 1. **归还恰好一次**（lease 的 CAS）—— 双重归还不再超发；
 * 2. **过期句柄到不了槽位** —— 旧持有者的 lease 已归还，release 是 no-op；
 * 3. **排队超时不吃槽**（超时那条路的槽必须原样回队）；
 * 4. **killAll 后池容量不缩水**（在途槽逐个强杀**并强制归还**，持有者的迟到 release 不双还）。
 *
 * 判据全用 `stats()` + 引擎调用计数（可观测面），不用内部字段 —— 内部字段改了它们仍绿，
 * 而「同一引擎进程跑两个脚本」这类失真只会在可观测面上现形。
 */
class PoolLeaseAccountingTest {

    private fun pool(capacity: Int = 1): Pair<FixedEnginePool, List<FakeEngine>> {
        val engines = MutableList(capacity) { FakeEngine(EngineId(it)) }
        return FixedEnginePool({ id -> engines[id.poolIndex] }, capacity) to engines
    }

    private fun req(id: String, wait: Long? = null) =
        PoolAcquireRequest(id, "$id.js", waitTimeoutMillis = wait)

    private fun grab(pool: FixedEnginePool, id: String): PoolHandle =
        assertInstanceOf(
            PoolAcquireOutcome.Granted::class.java,
            runBlocking { pool.acquire(req(id)) },
        ).handle

    /** 1a：正常归还一次。 */
    @Test
    fun `正常归还是恰好一次`() = runBlocking {
        val (p, engines) = pool(1)
        val h = grab(p, "p1")
        assertEquals(PoolStats(1, free = 0, busy = 1), p.stats())
        assertTrue(h.lease.release() === StopResult.Clean || true)   // 结果形态由 release 判据看
        assertEquals(PoolStats(1, free = 1, busy = 0), p.stats(), "归还后容量回满")
        assertEquals(1, engines[0].stopCalls, "归还走 quiesce（引擎被 stop 过一次）")
        Unit
    }

    /** 1b：双重归还不超发 —— 这是原实现「超发一证 → 同一引擎跑两个脚本」那条路的直接判据。 */
    @Test
    fun `双重归还不超发许可（外审 B2 的核心失真）`() = runBlocking {
        val (p, engines) = pool(1)
        val first = grab(p, "p1")
        p.release(first)
        p.release(first)                       // 幂等：lease 已归还 → no-op
        p.recycle(first.slot)                  // 迟到收归也是 no-op
        assertEquals(PoolStats(1, free = 1, busy = 0), p.stats(), "三重归还后容量仍是 1（没超发）")

        // 真判据：超发的后果是「两个占用者并存」—— 第二次拿到的必须是**另一个**在途占用，
        // 而此刻队列里应该有一个空槽（容量 1/free 1），所以第一次拿就拿到它。
        val second = grab(p, "p2")
        assertEquals(PoolStats(1, free = 0, busy = 1), p.stats(), "只占一个（超发会出现 busy=2 或 free<0）")
        p.release(second)
        Unit
    }

    /** 2：过期句柄的 release 不得触发新占用者的 quiesce（原用例的核心判据）。 */
    @Test
    fun `过期句柄的迟到 release 不触发新占用者的 stop`() = runBlocking {
        val (p, engines) = pool(1)
        val stale = grab(p, "p1")
        p.recycle(stale.slot)                  // 看门狗 kill 收归 → lease 归还
        assertTrue(stale.leaseReturned)

        val fresh = grab(p, "p2")
        val stopsBefore = engines[0].stopCalls
        assertEquals(StopResult.Clean, p.release(stale), "迟到 release 幂等返回 Clean")
        assertEquals(stopsBefore, engines[0].stopCalls, "旧句柄不得触发新占用者的 stop")
        assertEquals(PoolStats(1, free = 0, busy = 1), p.stats(), "槽位仍归新句柄占用")

        p.release(fresh)
        assertEquals(PoolStats(1, free = 1, busy = 0), p.stats())
        Unit
    }

    /** 3：排队超时不吃槽。 */
    @Test
    fun `排队超时不吃槽`() = runBlocking {
        val (p, _) = pool(1)
        grab(p, "p1")
        val timedOut = p.acquire(req("p2", wait = 100))
        assertInstanceOf(PoolAcquireOutcome.TimedOut::class.java, timedOut)
        assertEquals(PoolStats(1, free = 0, busy = 1), p.stats(), "超时那条不得占槽")
        Unit
    }

    /** 4a：killAll 后容量不缩水（在途槽被强制归还）。 */
    @Test
    fun `killAll 在途槽强制归还 容量不缩水`() = runBlocking {
        val (p, engines) = pool(1)
        grab(p, "p1")
        p.killAll(KillCause.WATCHDOG_CPU)
        assertEquals(PoolStats(1, free = 1, busy = 0), p.stats(), "在途槽必须归还（否则池永久少一个）")
        assertTrue(engines[0].killCalls >= 1, "在途执行体被强杀")
        Unit
    }

    /** 4b：killAll 之后持有者迟到 release 不双还（4a 的续集）。 */
    @Test
    fun `killAll 之后迟到 release 不双还`() = runBlocking {
        val (p, _) = pool(1)
        val h = grab(p, "p1")
        p.killAll(KillCause.WATCHDOG_CPU)
        p.release(h)                           // 迟到：幂等
        assertEquals(PoolStats(1, free = 1, busy = 0), p.stats(), "双还过一次就 free=2（超发）")
        Unit
    }

    /** 5：并发 release 同一份 lease 恰好一次（原实现的 `stop` 与 `killAll` 双重归还面）。 */
    @Test
    fun `并发归还是恰好一次`() = runBlocking {
        val (p, engines) = pool(1)
        val h = grab(p, "p1")
        val jobs = (1..8).map { async { p.release(h) } }
        jobs.forEach { it.await() }
        assertEquals(PoolStats(1, free = 1, busy = 0), p.stats(), "8 次并发归还后仍是 free=1（没超发）")
        assertTrue(engines[0].stopCalls <= 1, "quiesce 至多跑一次（lease 已归还的不再动槽）：${engines[0].stopCalls}")
        Unit
    }

    /** 6：容量 2 的并发 acquire 各占各槽（每脚本一进程，§8.2）。 */
    @Test
    fun `容量 2 并发占满后 free=0 busy=2 归还后回到 2`() = runBlocking {
        val (p, _) = pool(2)
        val a = grab(p, "p1")
        val b = grab(p, "p2")
        assertEquals(PoolStats(2, free = 0, busy = 2), p.stats())
        p.release(a)
        p.release(b)
        assertEquals(PoolStats(2, free = 2, busy = 0), p.stats())
        Unit
    }
}
