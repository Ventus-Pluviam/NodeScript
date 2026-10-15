package com.autoscript.appservice.runtime

import com.autoscript.domain.engine.EngineId
import com.autoscript.domain.engine.EngineStatus
import com.autoscript.domain.engine.KillCause
import com.autoscript.domain.engine.StopResult
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RuntimeControllerTest {

    private fun controller(
        capacity: Int = 1,
        watchdog: WatchdogPolicy = WatchdogPolicy(),
        engines: MutableList<FakeEngine>? = null,
    ): Pair<RuntimeController, MutableList<FakeEngine>> {
        val list = engines ?: MutableList(capacity) { FakeEngine(EngineId(it)) }
        val pool = FixedEnginePool({ id -> list[id.poolIndex] }, capacity)
        return RuntimeController(pool, watchdog) to list
    }

    /**
     * A11：**每一条 run 终结的路都要收口**，且**只**收一次。
     *
     * 钉的是"接缝接到了哪几条路上"—— 少接一条 = 那条路上脚本崩了投屏还挂着。
     * 四条路一次跑完：stop（被停）/ killRun（被掐）/ 自然退出（settleDone）/
     * killAll（宿主急停，广播式）。
     */
    @Test
    fun `四条 run 终结路径都收口且各只一次`() = runBlocking {
        val revoked = java.util.concurrent.ConcurrentHashMap<Long, AtomicInteger>()
        fun count(runId: Long) = revoked[runId]?.get() ?: 0

        val list = MutableList(4) { FakeEngine(EngineId(it)) }
        val pool = FixedEnginePool({ id -> list[id.poolIndex] }, 4)
        val c = RuntimeController(pool, WatchdogPolicy(), revokeRunResources = { runId ->
            revoked.computeIfAbsent(runId) { AtomicInteger() }.incrementAndGet()
        })

        val r1 = assertInstanceOf(
            RuntimeController.StartOutcome.Started::class.java,
            c.start(PoolAcquireRequest("p", "a.js", runNonce = "n1")),
        ).runId
        val r2 = assertInstanceOf(
            RuntimeController.StartOutcome.Started::class.java,
            c.start(PoolAcquireRequest("p", "a.js", runNonce = "n2")),
        ).runId
        val r3 = assertInstanceOf(
            RuntimeController.StartOutcome.Started::class.java,
            c.start(PoolAcquireRequest("p", "a.js", runNonce = "n3")),
        ).runId
        val r4 = assertInstanceOf(
            RuntimeController.StartOutcome.Started::class.java,
            c.start(PoolAcquireRequest("p", "a.js", runNonce = "n4")),
        ).runId

        c.stop(r1)                                     // 被停
        c.killRun(r2, KillCause.REQUESTED)             // 被掐
        c.awaitCompletion(r3)                          // 自然退出
        c.killAll(KillCause.WATCHDOG_CPU)          // 宿主急停（广播）

        listOf(r1, r2, r3, r4).forEach {
            assertEquals(1, count(it), "run $it 终结时收口恰好一次")
        }
        Unit
    }

    /** A11：进程级急停（应用被杀/测试收口）那条路同样要收口。 */
    @Test
    fun `forceStopAll 也收口`() = runBlocking {
        val revoked = java.util.concurrent.ConcurrentHashMap<Long, AtomicInteger>()
        val list = MutableList(2) { FakeEngine(EngineId(it)) }
        val pool = FixedEnginePool({ id -> list[id.poolIndex] }, 2)
        val c = RuntimeController(pool, WatchdogPolicy(), revokeRunResources = { runId ->
            revoked.computeIfAbsent(runId) { AtomicInteger() }.incrementAndGet()
        })
        val a = assertInstanceOf(
            RuntimeController.StartOutcome.Started::class.java,
            c.start(PoolAcquireRequest("p", "a.js", runNonce = "n1")),
        ).runId
        val b = assertInstanceOf(
            RuntimeController.StartOutcome.Started::class.java,
            c.start(PoolAcquireRequest("p", "a.js", runNonce = "n2")),
        ).runId
        c.forceStopAll(KillCause.ENGINE_REQUEST)
        assertEquals(1, revoked[a]?.get() ?: 0, "急停也收口")
        assertEquals(1, revoked[b]?.get() ?: 0, "急停也收口")
        Unit
    }

    /** A11：缝不接（缺省）时 run 终结照常走完 —— 这条是"没接线"不等于"功能坏掉"。 */
    @Test
    fun `未接收口缝时 run 终结不受影响`() = runBlocking {
        val (c, _) = controller()
        val started = assertInstanceOf(
            RuntimeController.StartOutcome.Started::class.java,
            c.start(PoolAcquireRequest("p1", "a.js", runNonce = "n1")),
        )
        assertEquals(RuntimeController.StopOutcome.StoppedClean, c.stop(started.runId))
        Unit
    }

    @Test
    fun `start 授权后 stop 干净`() = runBlocking {
        val (c, engines) = controller()
        val started = assertInstanceOf(
            RuntimeController.StartOutcome.Started::class.java,
            c.start(PoolAcquireRequest("p1", "a.js", runNonce = "n1")),
        )
        // 幂等锚点透传引擎（§8.5）
        assertEquals("n1", engines[0].executed.single().runNonce)
        assertEquals(setOf(started.runId), c.activeRunIds())
        assertEquals(RuntimeController.StopOutcome.StoppedClean, c.stop(started.runId))
        assertTrue(c.activeRunIds().isEmpty())
    }

    @Test
    fun `stop 未知 runId 如实 AlreadyGone`() = runBlocking {
        val (c, _) = controller()
        assertEquals(RuntimeController.StopOutcome.AlreadyGone, c.stop(999L))
    }

    @Test
    fun `满池排队超时回 QueueTimeout`() = runBlocking {
        val (c, _) = controller()
        val first = assertInstanceOf(
            RuntimeController.StartOutcome.Started::class.java,
            c.start(PoolAcquireRequest("p1", "a.js")),
        )
        val second = c.start(PoolAcquireRequest("p2", "b.js", waitTimeoutMillis = 100))
        assertInstanceOf(RuntimeController.StartOutcome.QueueTimeout::class.java, second)
        // 槽位仍被 first 占着：排队超时不占槽
        assertEquals(PoolStats(1, free = 0, busy = 1), c.stats())
        c.stop(first.runId)
        assertEquals(PoolStats(1, free = 1, busy = 0), c.stats())
    }

    @Test
    fun `引擎启动失败回 StartFailed 且槽位已收回`() = runBlocking {
        val engines = MutableList(1) { FakeEngine(EngineId(it)) }.also { it[0].failOnExecute = true }
        val (c, _) = controller(engines = engines)
        val outcome = c.start(PoolAcquireRequest("p1", "a.js"))
        assertInstanceOf(RuntimeController.StartOutcome.StartFailed::class.java, outcome)
        assertEquals(PoolStats(1, free = 1, busy = 0), c.stats())
    }

    @Test
    fun `killRun 强杀在途并返回 cause，不在途回 null`() = runBlocking {
        val (c, engines) = controller()
        val started = assertInstanceOf(
            RuntimeController.StartOutcome.Started::class.java,
            c.start(PoolAcquireRequest("p1", "a.js")),
        )
        val cause = c.killRun(started.runId, KillCause.WATCHDOG_CPU)
        assertEquals(KillCause.REQUESTED, cause) // FakeEngine.kill 固定回 REQUESTED
        assertEquals(1, engines[0].killCalls)
        assertTrue(c.activeRunIds().isEmpty())
        assertNull(c.killRun(started.runId, KillCause.WATCHDOG_CPU), "二次 kill 不在途 → null")
    }

    @Test
    fun `强杀后归还槽位与许可证 池容量不缩水`() = runBlocking {
        val (c, _) = controller()
        val started = assertInstanceOf(
            RuntimeController.StartOutcome.Started::class.java,
            c.start(PoolAcquireRequest("p1", "a.js")),
        )
        assertEquals(PoolStats(1, free = 0, busy = 1), c.stats())

        c.killRun(started.runId, KillCause.WATCHDOG_CPU)

        // 强杀是终结路径：槽位必须回到 FREE 且许可证必须归还，否则池容量永久缩水
        assertEquals(PoolStats(1, free = 1, busy = 0), c.stats(), "强杀后未归还槽位/许可证 → 池缩水")
        // 缩水的直接后果：下一次 start 拿不到证，挂到排队超时
        val second = c.start(PoolAcquireRequest("p2", "b.js", waitTimeoutMillis = 300))
        assertInstanceOf(
            RuntimeController.StartOutcome.Started::class.java, second,
            "强杀后槽位/许可证未归还：后续 start 只能排队到超时",
        )

        Unit  // 显式收尾：void 返回值才被 JUnit5 视为测试
    }

    @Test
    fun `强杀后旧句柄 stop 不拆新占用者且不超发许可证`() = runBlocking {
        val (c, _) = controller()
        val first = assertInstanceOf(
            RuntimeController.StartOutcome.Started::class.java,
            c.start(PoolAcquireRequest("p1", "a.js")),
        )
        c.killRun(first.runId, KillCause.OOM)
        val second = assertInstanceOf(
            RuntimeController.StartOutcome.Started::class.java,
            c.start(PoolAcquireRequest("p2", "b.js", waitTimeoutMillis = 300)),
        )
        // 强杀推进了槽位代次：旧 runId 再 stop 必须如实 AlreadyGone，绝不释放新占用者的槽位
        assertEquals(RuntimeController.StopOutcome.AlreadyGone, c.stop(first.runId))
        assertEquals(setOf(second.runId), c.activeRunIds(), "新占用者仍在途")
        assertEquals(PoolStats(1, free = 0, busy = 1), c.stats(), "许可证不超发")
        c.stop(second.runId)

        Unit  // 显式收尾：void 返回值才被 JUnit5 视为测试
    }

    @Test
    fun `killAll 清空在途并与 start 串行`() = runBlocking {
        val (c, engines) = controller(capacity = 2)
        val a = assertInstanceOf(
            RuntimeController.StartOutcome.Started::class.java,
            c.start(PoolAcquireRequest("p1", "a.js")),
        )
        val b = assertInstanceOf(
            RuntimeController.StartOutcome.Started::class.java,
            c.start(PoolAcquireRequest("p2", "b.js")),
        )
        c.killAll(KillCause.WATCHDOG_HEARTBEAT)
        assertTrue(c.activeRunIds().isEmpty())
        assertEquals(PoolStats(2, free = 2, busy = 0), c.stats())
        // killAll 后旧句柄再 stop：如实 AlreadyGone（不超发许可证）
        assertEquals(RuntimeController.StopOutcome.AlreadyGone, c.stop(a.runId))
        assertEquals(RuntimeController.StopOutcome.AlreadyGone, c.stop(b.runId))
        assertEquals(PoolStats(2, free = 2, busy = 0), c.stats())
        assertEquals(2, engines[0].killCalls + engines[1].killCalls, "两个忙槽各杀一次")
    }


    @Test
    fun `forceStopAll 急停清空在途并释放槽位`() = runBlocking {
        val (c, engines) = controller(capacity = 2)
        val a = assertInstanceOf(
            RuntimeController.StartOutcome.Started::class.java,
            c.start(PoolAcquireRequest("p1", "a.js")),
        )
        val b = assertInstanceOf(
            RuntimeController.StartOutcome.Started::class.java,
            c.start(PoolAcquireRequest("p2", "b.js")),
        )
        c.forceStopAll(KillCause.REQUESTED)
        assertTrue(c.activeRunIds().isEmpty())
        assertEquals(PoolStats(2, free = 2, busy = 0), c.stats())
        assertEquals(RuntimeController.StopOutcome.AlreadyGone, c.stop(a.runId))
        assertEquals(RuntimeController.StopOutcome.AlreadyGone, c.stop(b.runId))
        assertEquals(2, engines[0].killCalls + engines[1].killCalls, "两个忙槽各杀一次")
    }

    @Test
    fun `forceStopAll 空池幂等`() = runBlocking {
        val (c, _) = controller(capacity = 1)
        c.forceStopAll(KillCause.REQUESTED)
        assertEquals(PoolStats(1, free = 1, busy = 0), c.stats())
    }

    @Test
    fun `judge 纯判断不执行`() = runBlocking {
        val (c, _) = controller()
        val kill = c.judge(WatchdogSample(pid = 1, status = EngineStatus.RUNNING, heartbeatMillis = 10_000, cpuPercent = 1.0, rssBytes = 1))
        assertInstanceOf(WatchdogVerdict.Kill::class.java, kill)
        // 只判断不执行：在途表不动
        assertTrue(c.activeRunIds().isEmpty())
        val healthy = c.judge(WatchdogSample(pid = 1, status = EngineStatus.RUNNING, heartbeatMillis = 10, cpuPercent = 1.0, rssBytes = 1))
        assertEquals(WatchdogVerdict.Healthy, healthy)
    }

    @Test
    fun `并发 start 不超发许可证`() = runBlocking {
        val (c, _) = controller(capacity = 2)
        val outcomes = (1..6).map { i ->
            async { c.start(PoolAcquireRequest("p$i", "s$i.js", waitTimeoutMillis = 5_000)) }
        }.awaitAll()
        val started = outcomes.filterIsInstance<RuntimeController.StartOutcome.Started>()
        assertEquals(2, started.size, "容量 2 → 最多 2 个 Started")
        assertEquals(2, started.map { it.runId }.toSet().size, "runId 全局唯一（FakeEngine 全局序列）")
        // 释放后槽位回满
        for (s in started) c.stop(s.runId)
        assertEquals(PoolStats(2, free = 2, busy = 0), c.stats())
    }

    @Test
    fun `stop 释放与 killAll 并发不超发许可证`() = runBlocking {
        val (c, _) = controller(capacity = 1)
        val started = assertInstanceOf(
            RuntimeController.StartOutcome.Started::class.java,
            c.start(PoolAcquireRequest("p1", "a.js")),
        )
        val stopJob = launch { c.stop(started.runId) }
        val killJob = launch { c.killAll(KillCause.REQUESTED) }
        listOf(stopJob, killJob).forEach { it.join() }
        assertEquals(PoolStats(1, free = 1, busy = 0), c.stats())
    }

    @Test
    fun `awaitCompletion 引擎自退出结算 StoppedClean 并释放槽位`() = runBlocking {
        val (c, engines) = controller()
        val started = assertInstanceOf(
            RuntimeController.StartOutcome.Started::class.java,
            c.start(PoolAcquireRequest("p1", "a.js")),
        )
        engines[0].statusToReturn = EngineStatus.STOPPED // 宿主上报脚本已退出
        assertEquals(RuntimeController.Completed.StoppedClean, c.awaitCompletion(started.runId))
        assertTrue(c.activeRunIds().isEmpty())
        assertEquals(PoolStats(1, free = 1, busy = 0), c.stats())
    }

    @Test
    fun `awaitCompletion 软停超时结算 StopTimeout`() = runBlocking {
        val (c, engines) = controller()
        val started = assertInstanceOf(
            RuntimeController.StartOutcome.Started::class.java,
            c.start(PoolAcquireRequest("p1", "a.js")),
        )
        engines[0].stopResult = StopResult.TimedOut(partial = false)
        engines[0].statusToReturn = EngineStatus.QUIESCING // 四步 quiesce 未净
        val completed = c.awaitCompletion(started.runId)
        assertEquals(RuntimeController.Completed.StopTimeout(engines[0].summaryToReturn), completed, "StopTimeout 带 settle 时点的引擎摘要")
        assertEquals(PoolStats(1, free = 1, busy = 0), c.stats())
    }

    @Test
    fun `awaitCompletion 引擎崩溃结算 Killed 且不碰其他在途`() = runBlocking {
        val (c, engines) = controller(capacity = 2)
        val a = assertInstanceOf(
            RuntimeController.StartOutcome.Started::class.java,
            c.start(PoolAcquireRequest("p1", "a.js")),
        )
        val b = assertInstanceOf(
            RuntimeController.StartOutcome.Started::class.java,
            c.start(PoolAcquireRequest("p2", "b.js")),
        )
        engines[0].statusToReturn = EngineStatus.CRASHED
        val summary = com.autoscript.domain.engine.RunSummary(exitCode = 137, stderrTail = "FATAL: oom")
        engines[0].summaryToReturn = summary
        assertEquals(
            RuntimeController.Completed.Killed(summary),
            c.awaitCompletion(a.runId),
            "Killed 带 settle 时点从引擎侧取回的进程摘要",
        )
        assertEquals(setOf(b.runId), c.activeRunIds(), "只收走本 run 的槽位")
        assertEquals(PoolStats(2, free = 1, busy = 1), c.stats())
        c.stop(b.runId)
        Unit                                           // 显式收尾：void 返回值才被 JUnit5 视为测试
    }

    @Test
    fun `awaitCompletion 未知 runId 回 UnknownRun`() = runBlocking {
        val (c, _) = controller()
        assertEquals(
            RuntimeController.Completed.UnknownRun(null),
            c.awaitCompletion(999L),
            "未知 runId：无句柄可问，摘要如实 null",
        )
    }

    @Test
    fun `awaitCompletion 超时回 TimedOut 且仍在途`() = runBlocking {
        val (c, _) = controller()
        val started = assertInstanceOf(
            RuntimeController.StartOutcome.Started::class.java,
            c.start(PoolAcquireRequest("p1", "a.js")),
        )
        assertEquals(RuntimeController.Completed.TimedOut, c.awaitCompletion(started.runId, timeoutMillis = 100))
        assertEquals(setOf(started.runId), c.activeRunIds(), "超时不收槽位，调用方决定 kill/续等")
        c.stop(started.runId)
                Unit                                           // 显式收尾：void 返回值才被 JUnit5 视为测试
    }

    @Test
    fun `probeStatus 不在途回 null`() = runBlocking {
        val (c, _) = controller()
        assertNull(c.probeStatus(999L))
        val started = assertInstanceOf(
            RuntimeController.StartOutcome.Started::class.java,
            c.start(PoolAcquireRequest("p1", "a.js")),
        )
        assertEquals(EngineStatus.RUNNING, c.probeStatus(started.runId))
        c.stop(started.runId)
        assertNull(c.probeStatus(started.runId))
    }

    @Test
    fun `heartbeat 记账与 run 终结同生共死`() = runBlocking {
        val (c, _) = controller()
        assertNull(c.heartbeatMillis(1L), "未打过点：量不到，不回 0")

        val started = assertInstanceOf(
            RuntimeController.StartOutcome.Started::class.java,
            c.start(PoolAcquireRequest("p1", "a.js")),
        )
        assertTrue(c.heartbeat(started.runId, seq = 1))
        assertTrue(c.heartbeatMillis(started.runId)!! >= 0)
        // 同/旧 seq 不刷新时间戳 —— 积压帧不得让死掉的 run 装作活着
        assertFalse(c.heartbeat(started.runId, seq = 1))

        c.stop(started.runId)
        assertNull(c.heartbeatMillis(started.runId), "run 终结即遗忘：不留『假年轻』给复用 runId")
    }

    @Test
    fun `heartbeat 打到不在途 runId 不记账`() = runBlocking {
        val (c, _) = controller()
        assertFalse(c.heartbeat(999L, seq = 1), "从未存在的 runId → false")
        assertTrue(c.heartbeats().trackedRuns().isEmpty(), "无主心跳不得建账")

        val started = assertInstanceOf(
            RuntimeController.StartOutcome.Started::class.java,
            c.start(PoolAcquireRequest("p1", "a.js")),
        )
        assertTrue(c.heartbeat(started.runId, seq = 1))
        c.stop(started.runId)
        assertFalse(c.heartbeat(started.runId, seq = 2), "已结算的 run → false")
        assertTrue(c.heartbeats().trackedRuns().isEmpty(), "结算后打来的心跳不得复活旧账")
    }

    @Test
    fun `killRun 强杀也遗忘心跳`() = runBlocking {
        val (c, _) = controller()
        val started = assertInstanceOf(
            RuntimeController.StartOutcome.Started::class.java,
            c.start(PoolAcquireRequest("p1", "k.js")),
        )
        c.heartbeat(started.runId, seq = 1)
        assertEquals(KillCause.REQUESTED, c.killRun(started.runId, KillCause.WATCHDOG_CPU), "回的是引擎 kill 的 cause")
        assertNull(c.heartbeatMillis(started.runId), "killRun 同样是这条 run 的终点，一样遗忘")
    }

    @Test
    fun `statusOf 对照宿主自报与池侧投影，不一致如实报 drift`() = runBlocking {
        val (c, engines) = controller()
        val started = assertInstanceOf(
            RuntimeController.StartOutcome.Started::class.java,
            c.start(PoolAcquireRequest("p1", "a.js")),
        )
        val st = c.statusOf(started.runId)!!
        assertEquals(EngineStatus.RUNNING, st.host, "宿主自报（FakeEngine execute 后置 RUNNING）")
        assertEquals(EngineStatus.RUNNING, st.pool, "池侧状态机：BOOTING → RUNNING")
        assertFalse(st.drift, "稳态一致")

        // 宿主侧被外部改掉（真实现里=引擎自己飞了/野进程崩了）：如实报分歧，不自动修
        engines[0].statusToReturn = EngineStatus.STOPPED
        assertTrue(c.statusOf(started.runId)!!.drift, "分歧必须可见，不静默")
        assertEquals(PoolStats(1, free = 0, busy = 1), c.stats(), "报分歧不动记账")
        c.stop(started.runId)
        Unit                                           // 显式收尾：void 返回值才被 JUnit5 视为测试
    }

    @Test
    fun `statusOf 引擎读不到时 host 为 null 且不算 drift`() = runBlocking {
        val (c, engines) = controller()
        engines[0].statusToReturn = EngineStatus.RUNNING
        val started = assertInstanceOf(
            RuntimeController.StartOutcome.Started::class.java,
            c.start(PoolAcquireRequest("p1", "a.js")),
        )
        engines[0].blowStatus = true
        val st = c.statusOf(started.runId)!!
        assertNull(st.host, "宿主读不到 = 量不到（引擎已死/实现未接线）")
        assertFalse(st.drift, "量不到不是分歧：分歧需要两侧都能读")
        assertEquals(EngineStatus.RUNNING, st.pool, "池侧投影照常给出")
        engines[0].blowStatus = false
        c.stop(started.runId)
        Unit                                           // 显式收尾：void 返回值才被 JUnit5 视为测试
    }

    @Test
    fun `statusOf 不在途回 null，runStatuses 只列在途`() = runBlocking {
        val (c, _) = controller(capacity = 2)
        assertNull(c.statusOf(999L))
        val a = assertInstanceOf(
            RuntimeController.StartOutcome.Started::class.java,
            c.start(PoolAcquireRequest("p1", "a.js")),
        )
        c.start(PoolAcquireRequest("p2", "b.js"))
        assertEquals(2, c.runStatuses().size)
        c.stop(a.runId)
        assertEquals(1, c.runStatuses().size, "收走的 run 不再对照")
        c.killAll(com.autoscript.domain.engine.KillCause.REQUESTED)
        assertTrue(c.runStatuses().isEmpty())
        Unit                                           // 显式收尾：void 返回值才被 JUnit5 视为测试
    }

    @Test
    fun `killAll 清掉全部心跳账`() = runBlocking {
        val (c, _) = controller(capacity = 2)
        val a = assertInstanceOf(
            RuntimeController.StartOutcome.Started::class.java,
            c.start(PoolAcquireRequest("p1", "a.js")),
        )
        val b = assertInstanceOf(
            RuntimeController.StartOutcome.Started::class.java,
            c.start(PoolAcquireRequest("p2", "b.js")),
        )
        c.heartbeat(a.runId, seq = 1)
        c.heartbeat(b.runId, seq = 1)
        c.killAll(KillCause.REQUESTED)
        assertNull(c.heartbeatMillis(a.runId))
        assertNull(c.heartbeatMillis(b.runId))
    }
}

/**
 * A7「取消必须穿透」判据（backlog A7）：宿主探针抛 CancellationException 时，
 * [RuntimeController] 的三条观测路径**不得**把它折成业务结论 ——
 * 折了就等于"桥 TTL 到点取消协程"被谎报成"引擎已死 / 查不到状态"，
 * 超时语义在观测面上失真（这正是 A7 点名的病灶）。
 */
class RuntimeControllerCancellationTest {

    private val cancel = kotlinx.coroutines.CancellationException("宿主探针被取消")

    private fun controllerWithBlownProbe(): Pair<RuntimeController, FakeEngine> {
        val engine = FakeEngine(EngineId(0)).apply { statusThrowable = cancel }
        val pool = FixedEnginePool({ engine }, 1)
        return RuntimeController(pool, WatchdogPolicy()) to engine
    }

    @Test
    fun `awaitCompletion 不被取消折成 Killed`() = runBlocking {
        val (c, _) = controllerWithBlownProbe()
        val started = assertInstanceOf(
            RuntimeController.StartOutcome.Started::class.java,
            c.start(PoolAcquireRequest("p1", "a.js", runNonce = "n1")),
        )
        val thrown = try {
            c.awaitCompletion(started.runId, timeoutMillis = 1_000)
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            e
        }
        assertTrue(thrown === cancel, "取消必须原样穿透，而不是被 settleKilled 折成业务结论")
    }

    @Test
    fun `probeStatus 不被取消折成 null`() = runBlocking {
        val (c, _) = controllerWithBlownProbe()
        val started = assertInstanceOf(
            RuntimeController.StartOutcome.Started::class.java,
            c.start(PoolAcquireRequest("p1", "a.js", runNonce = "n1")),
        )
        var leaked = false
        try {
            c.probeStatus(started.runId)
        } catch (e: kotlinx.coroutines.CancellationException) {
            leaked = e === cancel
        }
        assertTrue(leaked, "取消不得被折成 null（那会让调用方以为\"查不到状态\"）")
    }

    @Test
    fun `statusOf 不被取消折成 host=null 的假对照`() = runBlocking {
        val (c, _) = controllerWithBlownProbe()
        val started = assertInstanceOf(
            RuntimeController.StartOutcome.Started::class.java,
            c.start(PoolAcquireRequest("p1", "a.js", runNonce = "n1")),
        )
        var leaked = false
        try {
            c.statusOf(started.runId)
        } catch (e: kotlinx.coroutines.CancellationException) {
            leaked = e === cancel
        }
        assertTrue(leaked, "取消不得被折成 host=null（那会造出一条假的\"宿主无状态\"对照）")
    }

    /**
     * 落盘口（2026-10-13）：start 成功即记 index 一次，参数取这次请求的归属。
     */
    @Test
    fun `start 成功调 onRunStarted 一次 参数是这次请求的归属`() = runBlocking {
        val seen = mutableListOf<String>()
        val pool2 = FixedEnginePool({ id -> FakeEngine(EngineId(id.poolIndex)) }, 1)
        val c2 = RuntimeController(
            pool2,
            WatchdogPolicy(),
            onRunStarted = { runId, project, path, at ->
                seen += "$runId|$project|$path|$at"
            },
        )
        val started = assertInstanceOf(
            RuntimeController.StartOutcome.Started::class.java,
            c2.start(PoolAcquireRequest("demo", "data/test.js", runNonce = "nx")),
        )
        assertEquals(1, seen.size, "Granted 分支恰好一次")
        val parts = seen.single().split("|")
        assertEquals(started.runId.toString(), parts[0])
        assertEquals("demo", parts[1])
        assertEquals("data/test.js", parts[2])
        c2.killAll(KillCause.REQUESTED)
        Unit
    }

    /**
     * 暂停账（2026-10-13）：`pauseRun` 冻住 → 锚点排除 + `RunStatus.paused`；
     * `resumeRun` 解冻 → 锚点回来 + startedAt 刷新（宽限与期限顺延）。
     *
     * FakeEngine 的 pause()/resume() 缺省 false（= 冻不住）—— 这里把两个开关打开，
     * 走的就是生产那条"冻得住"的路。
     */
    @Test
    fun `pauseRun 进集并排除看门狗锚点 resumeRun 出集并顺延`() = runBlocking {
        val engines = MutableList(1) { FakeEngine(EngineId(it)) }
        engines.single().pauseResult = true
        engines.single().resumeResult = true
        val pool = FixedEnginePool({ id -> engines[id.poolIndex] }, 1)
        var now = 1_000_000L
        val c = RuntimeController(pool, WatchdogPolicy(), clock = { now })
        val started = assertInstanceOf(
            RuntimeController.StartOutcome.Started::class.java,
            c.start(PoolAcquireRequest("demo", "a.js", runNonce = "np")),
        )
        val runId = started.runId
        assertEquals(1, c.watchAnchors().size, "未暂停时锚点在")

        assertTrue(c.pauseRun(runId))
        assertTrue(c.watchAnchors().isEmpty(), "暂停的 run 不进看门狗锚点，否则 1.5s 必杀")
        val st = c.statusOf(runId)
        assertTrue(st?.paused == true, "RunStatus 带 paused 给 UI")
        assertEquals("demo", st?.projectId)
        assertEquals("a.js", st?.scriptPath)

        now += 600_000L // 暂停 10 分钟
        assertTrue(c.resumeRun(runId))
        assertEquals(1, c.watchAnchors().size, "解冻后锚点回来")
        val anchor = c.watchAnchors().single()
        assertEquals(now, anchor.startedAtMillis, "startedAt 刷新 = 宽限与期限顺延整个暂停时长")
        assertFalse(c.statusOf(runId)?.paused == true)
        c.killAll(KillCause.REQUESTED)
        Unit
    }

    @Test
    fun `pauseRun 非在途与引擎冻不住都回 false 且不进集`() = runBlocking {
        // **不能调上面那个 `controller()`**：它是 `RuntimeControllerTest` 的私有成员，
        // 本类（`RuntimeControllerCancellationTest`）看不到 —— 就地建，与同类其余用例同形。
        val pool = FixedEnginePool({ id -> FakeEngine(EngineId(id.poolIndex)) }, 1)
        val c = RuntimeController(pool, WatchdogPolicy())
        assertFalse(c.pauseRun(999L), "从未存在 → false，不抛")
        val started = assertInstanceOf(
            RuntimeController.StartOutcome.Started::class.java,
            c.start(PoolAcquireRequest("p", "a.js", runNonce = "nq")),
        )
        // FakeEngine 缺省 pause() = false（冻不住）
        assertFalse(c.pauseRun(started.runId))
        assertEquals(1, c.watchAnchors().size, "没冻住的 run 不得被排除 —— 否则该杀的不杀")
        assertFalse(c.resumeRun(started.runId), "没冻住就没什么可解")
        c.killAll(KillCause.REQUESTED)
        Unit
    }

    /**
     * stdin 送行（2026-10-16）：`sendInput` 与 `pauseRun` 同一条"在途表精确命中"口径 ——
     * 在途才转给引擎、不在途直接 false 不问引擎、引擎说写不进也如实 false。
     *
     * 三条都要钉：窗里的 `boundRunId` **结算后仍留着**（结算不解绑），那时用户敲进
     * 输入框的一行必须落空而不是写进一个已死的管道 —— 这正是"不问引擎"那条的价值。
     */
    @Test
    fun `sendInput 只在途转给引擎 不在途与写不进都回 false`() = runBlocking {
        val engines = MutableList(1) { FakeEngine(EngineId(it)) }
        val pool = FixedEnginePool({ id -> engines[id.poolIndex] }, 1)
        val c = RuntimeController(pool, WatchdogPolicy())

        assertFalse(c.sendInput(999L, "甲"), "从未存在 → false，不抛")
        assertTrue(engines.single().sentInputs.isEmpty(), "不在途的号根本不该问引擎")

        val started = assertInstanceOf(
            RuntimeController.StartOutcome.Started::class.java,
            c.start(PoolAcquireRequest("demo", "a.js", runNonce = "ns")),
        )
        assertTrue(c.sendInput(started.runId, "甲"), "在途 → 转给引擎并如实回它的 true")
        assertEquals(listOf("甲"), engines.single().sentInputs)

        engines.single().sendInputResult = false
        assertFalse(c.sendInput(started.runId, "乙"), "管道关了（引擎回 false）→ 如实 false，不假装送到")

        c.killAll(KillCause.REQUESTED)
        assertFalse(c.sendInput(started.runId, "丙"), "结算后离开在途表 → false，不再问引擎")
        assertEquals(listOf("甲", "乙"), engines.single().sentInputs, "结算后那行没有落到引擎上")
    }
}
