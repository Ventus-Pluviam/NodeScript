package com.autoscript.appservice.scheduler.core

import com.autoscript.domain.scripts.ScheduledTask
/**
 * 恢复结果：一次未完成意向的封口 + 重投（§8.5）。
 *
 * 2026-10-07（backlog D7）自 `Scheduler.kt` 原样外迁：它是 `recoverUncommitted` 的
 * **返回值形状**，与「怎么恢复」是两件事（同批外迁的还有 `ScheduledTask` 与
 * `DefaultDeadlines`）。语义逐字未改。
 */
data class RecoveryRecord(
    val oldRunId: Long,
    val newRunId: Long,
    val runNonce: String,
    val outcome: RunOutcome,
    /** 因 [PendingRun.deadlineMillis] 到期而未重投（§8.6）：outcome 必为 [RunOutcome.Cancelled]。 */
    val expired: Boolean = false,
)
