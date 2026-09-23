package com.autoscript.ui

import com.autoscript.domain.host.HostSummary
import com.autoscript.domain.host.ShellSummary

/**
 * 首屏状态（纯数据，Compose 之外可 JVM 测）。
 *
 * 三态如实：
 * - [summaryWired] = false —— 宿主 Application 没实现 [HostSummary]（装配没接读口），
 *   显示「未接线」，**不冒充**「壳未就绪」（那是另一种事实）；
 * - shellReady —— 来自 [ShellSummary]；false 不区分装配中/失败（见其 KDoc）；
 * - missedAlarms —— 漏投账本条数，非零是如实记账不是错误。
 */
data class HomeState(
    val summaryWired: Boolean,
    val shellReady: Boolean,
    val missedAlarms: Int,
) {
    companion object {
        /** 读口未接线的哨兵（MainActivity 构造前/宿主未实现时）。 */
        val UNWIRED = HomeState(summaryWired = false, shellReady = false, missedAlarms = 0)

        /** 现取快照（每次调用重新 `as?` + `shellSummary()`，不缓存 —— 装配在 IO 域异步完成）。 */
        fun read(host: HostSummary?): HomeState {
            val summary: ShellSummary = host?.shellSummary() ?: return UNWIRED
            return HomeState(
                summaryWired = true,
                shellReady = summary.shellReady,
                missedAlarms = summary.missedAlarms,
            )
        }
    }
}
