package com.autoscript.appservice.runtime

import com.autoscript.domain.engine.EngineStatus
import com.autoscript.domain.engine.KillCause

/**
 * 看门狗三路判定（docs §8.4，纯策略、可单测；/proc 采样在 :main 侧，此处只喂样本）：
 * - 心跳失联：距上次心跳 ≥ 周期 × missedHeartbeatLimit；
 * - CPU 风暴：同 pid 最近样本连段（粒度 = 心跳周期）足够长且均值 ≥ 阈值；
 * - RSS 硬阈值：超限即 OOM 判定。
 *
 * 只判 RUNNING；SUSPENDED 不回度量（§8.3：防止误杀合法暂停）。
 * 看门狗不做自动化自愈，只输出 kill 裁决（恢复建议属上层，§8.4 诚实原则）。
 */
data class WatchdogSample(
    val pid: Int,
    val status: EngineStatus,
    val heartbeatMillis: Long,          // 距上次心跳
    val cpuPercent: Double,             // 本采样周期均值（0..100）
    val rssBytes: Long,
)

sealed interface WatchdogVerdict {
    data object Healthy : WatchdogVerdict
    data class Kill(val cause: KillCause, val reason: String) : WatchdogVerdict
}

class WatchdogPolicy(
    /** 心跳周期：也即**采样周期**——调度循环必须与它同源，否则"活着的引擎被误判失联"。 */
    val heartbeatIntervalMillis: Long = 500,
    private val missedHeartbeatLimit: Int = 3,
    val cpuHighPercent: Double = 95.0,
    private val cpuWindowMillis: Long = 30_000,
    private val rssHardLimitBytes: Long = 512L * 1024 * 1024,
) {
    init {
        require(heartbeatIntervalMillis > 0) { "heartbeatIntervalMillis 必须 > 0（采样周期与失联阈值同源）" }
        require(missedHeartbeatLimit > 0)
        require(cpuWindowMillis > 0)
        require(cpuHighPercent > 0.0)
    }

    /**
     * @param history 同 pid 的近期采样（含早于本次的样本）；用于判定 CPU 持续时长。
     */
    fun evaluate(sample: WatchdogSample, history: List<WatchdogSample> = emptyList()): WatchdogVerdict {
        if (sample.status != EngineStatus.RUNNING) return WatchdogVerdict.Healthy

        if (sample.heartbeatMillis >= heartbeatIntervalMillis * missedHeartbeatLimit) {
            return WatchdogVerdict.Kill(
                KillCause.WATCHDOG_HEARTBEAT, "心跳失联 ${sample.heartbeatMillis}ms ≥ ${heartbeatIntervalMillis * missedHeartbeatLimit}ms",
            )
        }
        if (sample.rssBytes >= rssHardLimitBytes) {
            return WatchdogVerdict.Kill(
                KillCause.OOM, "RSS ${sample.rssBytes} ≥ 硬阈值 $rssHardLimitBytes",
            )
        }

        val streak = (history.filter { it.pid == sample.pid } + sample)
            .takeLastWhile { it.cpuPercent >= cpuHighPercent }
        val sustained = streak.size * heartbeatIntervalMillis
        if (streak.isNotEmpty() && sustained >= cpuWindowMillis) {
            return WatchdogVerdict.Kill(
                KillCause.WATCHDOG_CPU, "CPU ${sample.cpuPercent}% 持续 ${sustained}ms ≥ $cpuWindowMillis",
            )
        }
        return WatchdogVerdict.Healthy
    }
}