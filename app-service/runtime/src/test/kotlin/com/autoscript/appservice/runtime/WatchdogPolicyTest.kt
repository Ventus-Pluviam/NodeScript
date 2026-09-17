package com.autoscript.appservice.runtime

import com.autoscript.domain.engine.EngineStatus
import com.autoscript.domain.engine.KillCause
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class WatchdogPolicyTest {

    private val policy = WatchdogPolicy()
    private fun sample(
        pid: Int = 42,
        status: EngineStatus = EngineStatus.RUNNING,
        heartbeat: Long = 100,
        cpu: Double = 10.0,
        rss: Long = 64L * 1024 * 1024,
    ) = WatchdogSample(pid, status, heartbeat, cpu, rss)

    @Test
    fun `正常心跳健康`() {
        assertEquals(
            WatchdogVerdict.Healthy,
            policy.evaluate(sample(heartbeat = 499)),
        )
    }

    @Test
    fun `心跳失联超限即杀`() {
        val verdict = assertInstanceOf(
            WatchdogVerdict.Kill::class.java,
            policy.evaluate(sample(heartbeat = 500 * 3)),
        )
        assertEquals(KillCause.WATCHDOG_HEARTBEAT, verdict.cause)
    }

    @Test
    fun `非 RUNNING 不做裁定`() {
        for (status in listOf(EngineStatus.SUSPENDED, EngineStatus.QUIESCING, EngineStatus.STOPPED)) {
            assertEquals(
                WatchdogVerdict.Healthy,
                policy.evaluate(sample(status = status, heartbeat = 99_999, cpu = 99.9)),
                "$status 不应触发看门狗",
            )
        }
    }

    @Test
    fun `CPU 风暴需持续满窗口才杀`() {
        // 窗口 30s / 周期 500ms = 60 个连续高采样（含本次）才触发
        val history58 = List(58) { sample(cpu = 99.0) }
        // 58 历史 + 本次 = 59 个 → 29500ms < 30000ms，仍健康
        assertEquals(WatchdogVerdict.Healthy, policy.evaluate(sample(cpu = 99.0), history58))
        // 59 历史 + 本次 = 60 个 → 30000ms ≥ 阈值 → 杀
        val verdict = assertInstanceOf(
            WatchdogVerdict.Kill::class.java,
            policy.evaluate(sample(cpu = 99.0), history58 + sample(cpu = 99.0)),
        )
        assertEquals(KillCause.WATCHDOG_CPU, verdict.cause)
        // 中间出现低采样 → 连段打断，重启计时
        val interrupted = history58 + sample(cpu = 1.0)
        assertEquals(
            WatchdogVerdict.Healthy,
            policy.evaluate(sample(cpu = 99.0), interrupted),
            "低采样打断后应重新计时",
        )
    }

    @Test
    fun `RSS 超硬阈值即杀`() {
        val verdict = assertInstanceOf(
            WatchdogVerdict.Kill::class.java,
            policy.evaluate(sample(rss = 512L * 1024 * 1024)),
        )
        assertEquals(KillCause.OOM, verdict.cause)
    }

    @Test
    fun `history 只统计同 pid`() {
        val other = listOf(sample(pid = 7, cpu = 99.0), sample(pid = 7, cpu = 99.0))
        assertEquals(WatchdogVerdict.Healthy, policy.evaluate(sample(pid = 42, cpu = 99.9), other))
    }
}