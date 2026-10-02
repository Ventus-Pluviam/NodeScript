package com.autoscript.ui.state

import com.autoscript.domain.host.HostSummary
import com.autoscript.domain.host.ShellSummary

/**
 * 首屏状态（纯数据，Compose 之外可 JVM 测）。
 *
 * 三条事实各自独立、不互相推断（这三件在旧版是三个裸 `Boolean`，编译器管不住
 * "由 A 推 B"）：[shellReady] 只来自 `ShellSummary.shellReady`（不区分装配中/失败 ——
 * 那是 logcat 的事），[keepAliveActive] 只来自保活面（**壳就绪 ≠ 保活生效**，
 * 熄屏的 `SCREEN_ON` 任务会被如实拒绝，这是"任务为什么没跑"的直接答案）。
 *
 * [summaryWired] = false 是**第四**种事实：宿主 Application 没实现 [HostSummary]，
 * 显示「未接线」而不是「壳未就绪」—— 后者会让用户去等一个永远不会到来的装配完成。
 */
data class HomeState(
    val summaryWired: Boolean,
    val shellReady: Boolean,
    val missedAlarms: Int,
    val keepAliveActive: Boolean,
    /** 壳状态那一句 + 该用哪档色（判读在这里，色值在主题）。 */
    val shellTone: StatusTone,
    val shellText: String,
) {
    companion object {
        /** 读口未接线的哨兵（MainActivity 构造前/宿主未实现时）。 */
        val UNWIRED = HomeState(
            summaryWired = false,
            shellReady = false,
            missedAlarms = 0,
            keepAliveActive = false,
            shellTone = StatusTone.PROBLEM,
            shellText = "宿主摘要未接线（Application 未实现 HostSummary）",
        )

        /** 现取快照（每次调用重新 `as?` + `shellSummary()`，不缓存 —— 装配在 IO 域异步完成）。 */
        fun read(host: HostSummary?): HomeState {
            val summary: ShellSummary = host?.shellSummary() ?: return UNWIRED
            return HomeState(
                summaryWired = true,
                shellReady = summary.shellReady,
                missedAlarms = summary.missedAlarms,
                keepAliveActive = summary.keepAliveActive,
                shellTone = if (summary.shellReady) StatusTone.OK else StatusTone.PROBLEM,
                shellText = if (summary.shellReady) {
                    "壳已就绪"
                } else {
                    "壳未就绪（装配中或失败，原因见 logcat「壳自装配失败」）"
                },
            )
        }
    }
}
