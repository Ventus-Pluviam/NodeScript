package com.autoscript.ui.overlay

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.autoscript.domain.host.HostSummary
import com.autoscript.ui.state.ActiveRunState

/**
 * 执行日志悬浮窗的**控制器**（2026-10-13 用户令；Activity `remember` 一份）。
 *
 * 只管状态机，不管画：开（先开窗再跑 —— 跑起来之前窗里是空态）、关、
 * 清空（推水位，不删文件）、暂停/继续（问宿主，false 如实 toast）、绑定刷新。
 * 内容组合（`LogWindowContent`）由宿主层（`LogWindowHosts.kt`）构建 —— 回调里要
 * `scope.launch`（togglePause 是挂起的）与 clipboard，控制器自己没有协程域，
 * 不替调用方拿（故本文件零 Compose UI 依赖，只有运行时状态）。
 *
 * **开窗只带归属不带 runId**（见 `LogWindowState.bind` 的 KDoc：调用链不回 runId）。
 * 定时/后台触发**不调 [open]** —— 那条路由没有"用户在看"这件事，弹窗是打扰；
 * 日志照存（`onRunStarted` 不分触发源）。
 */
class LogWindowController(
    private val hostSummary: () -> HostSummary?,
    private val toast: (String) -> Unit,
    val systemHost: SystemLogWindowHost?,
) {
    /** null = 窗没开（系统窗与 in-app 窗都不画）。 */
    var state: LogWindowState? by mutableStateOf(null)
        private set

    /** 此刻走的是系统窗还是 in-app 回退（open 那一刻按权限定，开关期间不变）。 */
    var usingSystemWindow: Boolean by mutableStateOf(false)
        private set

    /**
     * 开窗（文件 ▶ / 立即执行触发；先开再跑 —— 跑起来之前窗里是「未开始」空态）。
     *
     * 已开着（上一条还没关）→ 替换绑定（旧窗直接切到新归属，不叠两层窗）。
     * 系统窗的首次 `show` 由 `SystemWindowSync` 效应接（state 非空即弹），
     * in-app 窗由 `LogWindowInAppHost` 画 state —— 本函数只落状态。
     */
    fun open(projectId: String, relPath: String) {
        state = LogWindowState(projectId = projectId, relPath = relPath)
        usingSystemWindow = (systemHost != null) && (hostSummary()?.overlayAllowed() == true)
        if (!usingSystemWindow && systemHost != null) {
            // 有系统宿主但没权限：每次开窗说一次，不记"已说过" ——
            // 权限随时可能在设置里打开，"只说一次"会让用户错过已授权的事实。
            toast("未授予悬浮窗权限，日志窗显示在应用内；可在能力中心授权")
        }
    }

    /** 关窗（系统窗撤 view，in-app 窗靠 state null 不画）。 */
    fun close() {
        state = null
        systemHost?.hide()
    }

    /** 系统窗弹失败（运行时被拒）→ 落回 in-app（由 `SystemWindowSync` 调）。 */
    fun fallbackToInApp() {
        usingSystemWindow = false
        systemHost?.hide()
    }

    /**
     * 在途表现值 → 绑定（每次 `reloadConsole` 后调用；现值现取）。
     */
    fun bind(activeRuns: List<ActiveRunState>) {
        val current = state ?: return
        state = current.bind(activeRuns)
    }

    /**
     * 清空日志（**只推水位，不删文件**）：`watermarkSeq` 推到当前可见最大 seq ——
     * 窗里旧行消失、新行照进；详情页重开全文仍在（用户令"清空日志"是清窗，不是删档）。
     */
    fun clearLogs(visibleMaxSeq: Long) {
        val current = state ?: return
        if (visibleMaxSeq > current.watermarkSeq) {
            state = current.copy(watermarkSeq = visibleMaxSeq)
        }
    }

    /** 全屏切换（系统宿主 updateViewLayout，in-app 宿主读同一份 state 改尺寸）。 */
    fun toggleFullscreen() {
        val current = state ?: return
        val next = !current.fullscreen
        state = current.copy(fullscreen = next)
        if (usingSystemWindow) systemHost?.setFullscreen(next)
    }

    /**
     * 暂停/继续（悬浮窗第一颗钮；boundRunId null 时不问宿主 —— 没绑上就没有可停的东西）。
     *
     * false（冻不住/已结算）→ toast 如实说，不假装成功。
     */
    suspend fun togglePause() {
        val current = state ?: return
        val runId = current.boundRunId ?: return
        val host = hostSummary() ?: run {
            toast("宿主摘要未接线（Application 未实现 HostSummary）")
            return
        }
        try {
            val ok = if (current.paused) host.resumeRun(runId) else host.pauseRun(runId)
            if (!ok) {
                toast(if (current.paused) "恢复失败：该执行已结算或不支持" else "该执行不支持暂停（本设备冻不住该进程）")
            }
            // 成败都由下一次 bind 刷新 paused（现值现取，不在这里猜）。
        } catch (t: Exception) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            toast("暂停失败：${t.message ?: t.javaClass.simpleName}")
        }
    }
}
