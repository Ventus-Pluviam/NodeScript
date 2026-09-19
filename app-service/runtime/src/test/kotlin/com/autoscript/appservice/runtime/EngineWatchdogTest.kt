package com.autoscript.appservice.runtime

import com.autoscript.domain.engine.EngineId
import com.autoscript.domain.engine.EngineStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 调度循环验证（docs §8.4「生产调度循环已落地、心跳来源仍未接」）。
 *
 * 这里验的不是阈值（阈值在 [WatchdogPolicyTest]），而是**循环本身**有没有守住诚实口径：
 * 采样→裁决→落裁决→收尾遗忘，以及"量不到就不喂值"。
 */
class EngineWatchdogTest {

    /**
     * 最小 `/proc/<pid>/stat` 文本：comm 之后字段 3..9（state ppid pgrp session tty tpgid flags），
     * 字段 10..13 占位零，于是 **utime 落在字段 14、stime 字段 15** ——
     * `man 5 proc` 的字段号对应 parser 取的 ")" 之后第 11/12 个 token。
     */
    private fun stat(utime: Long): String {
        val fields = listOf("S", "1", "1", "0", "-1", "4194560") +
            List(5) { "0" } + listOf("$utime", "0") + List(8) { "0" }
        return "1 (node) " + fields.joinToString(" ")
    }

    private fun status(rssKb: Long = 1_024): String =
        "Name:\tnode\nVmRSS:\t$rssKb kB\n"

    /** 给定值时钟：真实单测里连续 tick 只隔几微秒，用系统钟会让 CPU 差分全变成 0.0%。 */
    private class FakeClock : EngineWatchdog.Clock {
        var now = 1_000L
        override fun nowMillis(): Long = now
        fun advance(millis: Long) { now += millis }
    }

    private fun rig(
        monitor: ProcessMonitor,
        policy: WatchdogPolicy = WatchdogPolicy(),
        heartbeat: (Long) -> Long? = { 100L },
        engine: FakeEngine = FakeEngine(EngineId(0)).also { it.pid = 4242 },
        clock: EngineWatchdog.Clock = FakeClock(),
    ): Triple<RuntimeController, EngineWatchdog, FakeEngine> {
        // controller 与 watchdog 必须持**同一个** policy：裁决由 controller.judge 落，
        // watchdog 只决定节奏与记账。两处各配一份 = 阈值悄悄不一致（调试地狱）。
        val controller = RuntimeController(FixedEnginePool({ engine }, capacity = 1), policy)
        val watchdog = EngineWatchdog(controller = controller, policy = policy, clock = clock)
            .withMonitor(monitor)
            .withHeartbeat(heartbeat)
        return Triple(controller, watchdog, engine)
    }

    /** 推进 [FakeClock] 一个心跳周期后跑一轮（真实循环的节奏 = 采样周期，见 EngineWatchdog.loop）。 */
    private suspend fun EngineWatchdog.tickEvery(period: Long, clock: EngineWatchdog.Clock): EngineWatchdog.Tick {
        (clock as FakeClock).advance(period)
        return tick()
    }

    @Test
    fun `一轮监督把采样喂给 policy 并如实记账`() = runBlocking {
        val monitor = ProcessMonitor(
            100,
            statReader = { stat(0) },
            statusReader = { status() },
        )
        val (controller, watchdog, _) = rig(monitor)
        controller.start(PoolAcquireRequest("p", "a.js"))

        val tick = watchdog.tick()
        assertEquals(1, tick.sampled, "一个在途 run → 采一次")
        assertTrue(tick.noPid.isEmpty() && tick.noHeartbeat.isEmpty() && tick.procUnreadable.isEmpty())
        assertTrue(tick.killed.isEmpty(), "健康样本不杀")
    }

    @Test
    fun `心跳失联的裁决经 controller 落到强杀`() = runBlocking {
        val monitor = ProcessMonitor(100, statReader = { stat(0) }, statusReader = { status() })
        // 距上次心跳 2000ms ≥ 500×3：policy 判杀
        val (controller, watchdog, engine) = rig(monitor, heartbeat = { 2_000L })
        val started = assertInstanceOf(
            RuntimeController.StartOutcome.Started::class.java,
            controller.start(PoolAcquireRequest("p", "a.js")),
        )

        val tick = watchdog.tick()
        assertEquals(listOf(started.runId), tick.killed)
        assertEquals(1, engine.killCalls, "裁决经 controller 落到引擎 kill")
        assertTrue(controller.activeRunIds().isEmpty(), "已结算：在途表收走")
        assertEquals(PoolStats(1, free = 1, busy = 0), controller.stats(), "杀槽必还证（§8.2）")
    }

    @Test
    fun `杀完的 pid 当轮 forget，复用不背旧账`() = runBlocking {
        // 每 500ms 墙钟吃掉 500ms CPU = 恒定 100%
        val cpu = AtomicCpu()
        val monitor = ProcessMonitor(100, statReader = { stat(cpu.next()) }, statusReader = { status() })
        // 窗口 1500ms / 周期 500ms = 3 个连续高采样（含本次）才杀
        val policy = WatchdogPolicy(cpuWindowMillis = 1_500, missedHeartbeatLimit = 100)
        val clock = FakeClock()
        val (controller, watchdog, _) = rig(monitor, policy, clock = clock)
        controller.start(PoolAcquireRequest("p", "a.js"))

        val first = watchdog.tickEvery(period = 500, clock)   // 首轮：首采样不给假差分 → 0.0%
        assertTrue(first.killed.isEmpty(), "首轮不给假差分")
        val second = watchdog.tickEvery(period = 500, clock)
        assertTrue(second.killed.isEmpty(), "第 2 轮：1 段高采样 = 500ms < 1500ms")
        val third = watchdog.tickEvery(period = 500, clock)
        assertTrue(third.killed.isEmpty(), "第 3 轮：2 段 = 1000ms < 1500ms")
        val fourth = watchdog.tickEvery(period = 500, clock)
        assertEquals(1, fourth.killed.size, "第 4 轮：3 段 = 1500ms ≥ 窗口 → 杀")
        assertEquals(listOf(4242), fourth.forgotten, "kill 的 pid 当轮就 forget")
        assertTrue(monitor.trackedPids().isEmpty(), "基线已丢弃：OS 复用该 pid 时重新起步")
    }

    @Test
    fun `CPU 连段跨轮延续，中间掉下去就重新计时`() = runBlocking {
        // 高 CPU 段每 500ms 吃掉 500ms（=100%）；[AtomicCpu.drop] 之后那一次回吐 0（=0%）打断连段
        val cpu = AtomicCpu()
        val monitor = ProcessMonitor(100, statReader = { stat(cpu.next()) }, statusReader = { status() })
        val policy = WatchdogPolicy(cpuWindowMillis = 1_500, missedHeartbeatLimit = 100)
        val clock = FakeClock()
        val (controller, watchdog, _) = rig(monitor, policy, clock = clock)
        controller.start(PoolAcquireRequest("p", "a.js"))
        val started = controller.activeRunIds().single()

        watchdog.tickEvery(500, clock)                       // 第 1 轮：0.0%
        assertTrue(watchdog.tickEvery(500, clock).killed.isEmpty(), "第 2 轮：1 段 = 500ms")
        assertTrue(watchdog.tickEvery(500, clock).killed.isEmpty(), "第 3 轮：2 段 = 1000ms < 1500ms")
        // 第 4 轮：3 段 = 1500ms ≥ 窗口 → 杀（连段跨轮被记账，不是只看本轮样本）
        val fourth = watchdog.tickEvery(500, clock)
        assertEquals(listOf(started), fourth.killed, "连段跨轮被记账，不是只看本轮样本")

        // 重来一次：中间插一个低 CPU 采样，连段应清零重新计时
        watchdog.reset()
        controller.start(PoolAcquireRequest("p", "b.js"))
        watchdog.tickEvery(500, clock)                       // 0.0%
        watchdog.tickEvery(500, clock)                       // 1 段高采样
        cpu.drop()                                           // 下一轮 CPU 掉到低值：连段断在这里
        val interrupted = watchdog.tickEvery(500, clock)
        assertTrue(interrupted.killed.isEmpty(), "低采样打断连段后重新计时")
        val after = watchdog.tickEvery(500, clock)
        assertTrue(after.killed.isEmpty(), "打断后从 0 计：2 段才 1000ms < 1500ms")
    }

    @Test
    fun `宿主不给 pid 时如实记 noPid 不判死`() = runBlocking {
        val monitor = ProcessMonitor(100, statReader = { stat(0) }, statusReader = { status() })
        val engine = FakeEngine(EngineId(0)).also { it.pid = null }
        val (controller, watchdog, _) = rig(monitor, engine = engine)
        controller.start(PoolAcquireRequest("p", "a.js"))

        val tick = watchdog.tick()
        assertEquals(1, tick.noPid.size, "pid 不可得：单项记账")
        assertTrue(tick.killed.isEmpty(), "量不到 ≠ 该死")
        assertTrue(tick.noHeartbeat.isEmpty() && tick.procUnreadable.isEmpty())
    }

    @Test
    fun `心跳未接线时不喂假心跳`() = runBlocking {
        val monitor = ProcessMonitor(100, statReader = { stat(0) }, statusReader = { status() })
        val (controller, watchdog, _) = rig(monitor, heartbeat = { null })
        controller.start(PoolAcquireRequest("p", "a.js"))

        val tick = watchdog.tick()
        assertEquals(0, tick.noPid.size + tick.procUnreadable.size)
        assertEquals(1, tick.noHeartbeat.size, "心跳来源未接：如实记账")
        assertTrue(tick.killed.isEmpty(), "不拿看门狗自己的轮转周期冒充心跳（那会静默关掉最要害的一路）")
    }

    @Test
    fun `proc 读不到记 procUnreadable，不猜健康也不判死`() = runBlocking {
        val monitor = ProcessMonitor(100, statReader = { null }, statusReader = { null })
        val (controller, watchdog, _) = rig(monitor)
        controller.start(PoolAcquireRequest("p", "a.js"))

        val tick = watchdog.tick()
        assertEquals(1, tick.procUnreadable.size)
        assertTrue(tick.killed.isEmpty())
    }

    @Test
    fun `run 正常结束后 pid 被 forget`() = runBlocking {
        val monitor = ProcessMonitor(100, statReader = { stat(0) }, statusReader = { status() })
        val (controller, watchdog, _) = rig(monitor)
        val started = assertInstanceOf(
            RuntimeController.StartOutcome.Started::class.java,
            controller.start(PoolAcquireRequest("p", "a.js")),
        )
        watchdog.tick()
        assertTrue(monitor.trackedPids().isNotEmpty(), "监督过就有基线")

        controller.stop(started.runId)                  // 优雅结束
        val tick = watchdog.tick()
        assertEquals(listOf(4242), tick.forgotten)
        assertEquals(0, tick.sampled, "无在途 run：本轮不采样")
        assertTrue(monitor.trackedPids().isEmpty())
    }

    @Test
    fun `start 幂等 stop 后停转`() = runBlocking {
        val monitor = ProcessMonitor(100, statReader = { stat(0) }, statusReader = { status() })
        val (_, watchdog, _) = rig(monitor)
        val scope = CoroutineScope(Job())
        watchdog.start(scope)
        watchdog.start(scope)                            // 重复 start：只保留一个轮转
        assertTrue(watchdog.isRunning())
        watchdog.stop()
        assertFalse(watchdog.isRunning())
        scope.cancel()
    }

    /**
     * utime 游标：[next] 默认 +100 jiffies（配 500ms 墙钟 = 100% CPU）；
     * [drop] 之后那一次回吐 0 jiffies（= 0%），用来打断 CPU 连段。
     */
    private class AtomicCpu {
        private var value = 0L
        private var dropNext = false

        fun next(): Long {
            val step = if (dropNext) 0L else 100L
            dropNext = false
            value += step
            return value
        }

        fun drop() { dropNext = true }
    }
}
