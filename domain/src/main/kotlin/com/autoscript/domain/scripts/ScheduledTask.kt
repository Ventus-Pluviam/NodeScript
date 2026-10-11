package com.autoscript.domain.scripts

import java.time.ZoneId

/**
 * 登记载荷的形状（一次登记 = 计划 + 投递参数 + 屏幕契约）；存储面（SQLite 实现）需要它，故随 [TaskStore] 搬进 `:domain`（2026-10-11 批 102）。
 *
 * 定时任务模型（docs §8.6 / §9.6 定时 API）：
 * 一次登记 = 一个调度计划 + 投递参数 + 屏幕契约；每次触发生成新的 runNonce（意图日志幂等锚点）。
 *
 * 2026-10-07（backlog D7）自 `Scheduler.kt` 原样外迁：它是**登记载荷的形状**，
 * 与「怎么调度」是两件事（同批外迁的还有 `RecoveryRecord` 与 `DefaultDeadlines`）。
 * 语义逐字未改。
 */
data class ScheduledTask(
    val id: String,                             // 任务标识（alarm requestCode / UI 引用）
    val name: String,
    val projectId: String,
    val scriptPath: String,
    val schedule: TimedSchedule,
    val screen: ScreenGuarantee = ScreenGuarantee.ANY,
    val args: List<String> = emptyList(),
    val scriptTimeoutMillis: Long? = null,      // 透传引擎
    val timezone: ZoneId = ZoneId.systemDefault(),
    val enabled: Boolean = true,
)
