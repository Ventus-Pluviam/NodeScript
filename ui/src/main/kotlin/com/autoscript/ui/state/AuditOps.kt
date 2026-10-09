package com.autoscript.ui.state

import com.autoscript.domain.host.HostSummary
import kotlinx.coroutines.CancellationException

/**
 * 审计页的读取操作（挂起；**顶层函数而不是 `MainActivity` 的成员**）。
 *
 * 搬出 Activity 的理由与 [loadRegistry]/[loadScriptEnv] 逐字相同：`MainActivity` 的函数数
 * 贴着 detekt 的 `TooManyFunctions` 线，且这段本身就是"拿读口算下一份状态"的纯逻辑 ——
 * 放这里能用 `FakeHost` 直接测（`:ui` 的单测门跑 JVM，`MainActivity` 构造不出来）。
 *
 * 三落点与 `reloadNpm` 同构，不另立口径：
 * - 读口未接线 → 失败态带原因（**不冒充**「你没做过任何操作」）；
 * - 抛错 → 失败态**保留已读到的那份**；
 * - 成功 → 全量覆盖（宿主读数是权威，不累积 —— 与 [loadConsoleCmd] 的增量语义相反，
 *   因为这份读口本来就是全量历史，不是游标流）。
 *
 * **审计页只读**：这里没有写操作。审批/安装/镜像源那些动作的入口在各自的页上，
 * 本页是它们的**记录**，不是它们的按钮（§10.5-2 审计的意义就在这里）。
 */
internal suspend fun loadAudit(host: HostSummary?, previous: AuditState): AuditState = try {
    if (host == null) {
        AuditState.failed(
            IllegalStateException("宿主摘要未接线（Application 未实现 HostSummary）"),
            previous,
        )
    } else {
        AuditState.of(host.npmHistory(), previous)
    }
} catch (e: CancellationException) {
    throw e
} catch (t: Exception) {
    AuditState.failed(t, previous)
}

/** 换一个筛选档（只改显示，不重读 —— 宿主那条读口是无参全量）。 */
internal fun filterAudit(state: AuditState, projectId: String?): AuditState =
    state.copy(projectFilter = projectId)
