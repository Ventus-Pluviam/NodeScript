package com.autoscript.appservice.scheduler.persist

import com.autoscript.appservice.scheduler.core.IntentRun
import com.autoscript.appservice.scheduler.core.RunOutcome
import com.autoscript.domain.scripts.ScreenGuarantee
import com.autoscript.appservice.scheduler.core.TriggerSource
import com.autoscript.domain.scripts.IntentStore

/**
 * 存储行 ↔ 运行态映射（2026-10-08 自 `IntentStore.kt` 原样拆出）。
 *
 * **为什么这两个函数不跟着接口搬去 `:domain`**：[IntentStore] 是 SPI，已搬去
 * `:domain`（`SqliteIntentStore` 住 `:platform:system`，靠依赖铁律 `:platform:*` →
 * `:domain` 够到它）；而本文件两个扩展函数依赖 `core` 的运行态类型
 * （[IntentRun]/[RunOutcome]/[TriggerSource]/[ScreenGuarantee]），那些**只在
 * `:app-service:scheduler`**。映射方向是单向的（存储行 → 运行态；`persist` 不反向
 * 依赖 `core` 之外的东西），所以它们留在本模块，与 [PersistentIntentLog] 同层。
 */

/** core 侧映射：StoredRow → IntentRun。 */
internal fun IntentStore.StoredRow.toIntentRun(): IntentRun = IntentRun(
    runId = runId,
    projectId = start.projectId,
    scriptPath = start.scriptPath,
    runNonce = start.runNonce,
    trigger = TriggerSource.valueOf(start.trigger),
    scheduledAtMillis = start.scheduledAtMillis,
    screen = ScreenGuarantee.valueOf(start.screen),
    outcome = outcome?.let {
        when (it.name) {
            "SUCCEEDED" -> RunOutcome.Succeeded
            "FAILED" -> RunOutcome.Failed()
            "CANCELLED" -> RunOutcome.Cancelled
            "CRASHED" -> RunOutcome.Crashed(it.detail)
            "INTERRUPTED" -> RunOutcome.Interrupted
            else -> error("未知 outcome: ${it.name}")
        }
    },
    startedAtMillis = start.startedAtMillis,
    deadlineMillis = start.deadlineMillis,
    args = start.args,
    timeoutMillis = start.timeoutMillis,
    committedAtMillis = committedAtMillis,
)

internal fun RunOutcome.toStored(): IntentStore.StoredOutcome = when (this) {
    RunOutcome.Succeeded -> IntentStore.StoredOutcome.SUCCEEDED
    is RunOutcome.Failed -> IntentStore.StoredOutcome.FAILED
    RunOutcome.Cancelled -> IntentStore.StoredOutcome.CANCELLED
    is RunOutcome.Crashed -> IntentStore.StoredOutcome.crashed(message)
    RunOutcome.Interrupted -> IntentStore.StoredOutcome.INTERRUPTED
}
