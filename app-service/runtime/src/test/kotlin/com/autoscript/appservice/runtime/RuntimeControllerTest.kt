package com.autoscript.appservice.runtime

import com.autoscript.domain.engine.EngineId
import com.autoscript.domain.engine.EngineStatus
import com.autoscript.domain.engine.KillCause
import com.autoscript.domain.engine.StopResult
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
        assertEquals(RuntimeController.Completed.StopTimeout, c.awaitCompletion(started.runId))
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
        assertEquals(RuntimeController.Completed.Killed, c.awaitCompletion(a.runId))
        assertEquals(setOf(b.runId), c.activeRunIds(), "只收走本 run 的槽位")
        assertEquals(PoolStats(2, free = 1, busy = 1), c.stats())
        c.stop(b.runId)
        Unit                                           // 显式收尾：void 返回值才被 JUnit5 视为测试
    }

    @Test
    fun `awaitCompletion 未知 runId 回 UnknownRun`() = runBlocking {
        val (c, _) = controller()
        assertEquals(RuntimeController.Completed.UnknownRun, c.awaitCompletion(999L))
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
