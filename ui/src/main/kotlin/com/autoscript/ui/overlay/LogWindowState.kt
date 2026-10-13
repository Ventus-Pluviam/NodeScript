package com.autoscript.ui.overlay

import com.autoscript.ui.state.ActiveRunState
import com.autoscript.ui.state.ConsoleLineState

/**
 * 执行日志悬浮窗的**纯状态**（2026-10-13 用户令；JVM 可测，不碰 Android）。
 *
 * 悬浮窗回答三个问题："看谁的输出"（[boundRunId]，由 [bind] 从在途表现值绑定）、
 * "看到哪"（[watermarkSeq]，清空按钮只推水位）、"多大"（[fullscreen] 紧凑/全屏切换）。
 *
 * **为什么绑定走"归属匹配"而不是调用方传 runId**：`runScriptFile`/`runTaskNow`
 * 那两条链**不回 runId**（挂起到结算只回 Unit）—— 调用方在开窗那一刻根本不知道
 * 号是多少。开窗只带 `(projectId, relPath)`，随后每次拉取 `activeRuns`（现值现取）
 * 按归属找到"这次触发起来的那条 run"（同归属取最大 runId = 最近一次）。
 * 定时任务在后台自己跑起来时**不调 open**（用户令：后台不弹窗），故误绑窗口不存在 ——
 * 窗开着的时候同归属的新 run 只可能是用户刚点的那一次（池容量默认 1，串行）。
 */
data class LogWindowState(
    val projectId: String,
    val relPath: String,
    /** 清空水位：≤ 它的一律不画（**不删文件** —— 详情页重开全文仍在；只进不退）。 */
    val watermarkSeq: Long = 0L,
    /** 紧凑/全屏（系统宿主 updateViewLayout，in-app 宿主改 Box 尺寸）。 */
    val fullscreen: Boolean = false,
    /** 绑定的在途 run（null = 这次执行还没进在途表 / 已结算）。 */
    val boundRunId: Long? = null,
    /** 绑定那条 run 此刻冻没冻住（暂停按钮的图标与可用态就看它）。 */
    val paused: Boolean = false,
) {
    /**
     * 窗标题（**单一归一化点**）：`/$projectId/$relPath` —— 用户口径的
     * `/data/test.js` 就是这个形状（relPath 是项目内路径，首段不是项目名，
     * 见 `ScriptLogRead` 的 KDoc；`/` 前缀是显示形态，落盘/索引一律不用它）。
     */
    val title: String get() = "/$projectId/$relPath"

    /** 这次执行的扩展名（文件类型图标的判据；取最后一段最后一个点之后，小写）。 */
    val ext: String get() = relPath
        .substringAfterLast('/', "")
        .substringAfterLast('.', "")
        .lowercase()

    /**
     * 在途表现值 → 绑定/更新（每次 `reloadConsole` 后调用）。
     *
     * - 同归属（projectId + scriptPath 都对上）取最大 runId = 最近一次触发；
     * - 窗开着但表里没有同归属（启动失败/门禁拒/已结算）→ boundRunId = null，
     *   窗显示「本次执行未开始」空态，用户手动关（不自关：失败 toast 已经说了原因，
     *   窗再自关等于抢话）；
     * - paused 随绑定 run 同步（run 结算消失后 paused 回 false，不留僵尸态）。
     */
    fun bind(activeRuns: List<ActiveRunState>): LogWindowState {
        val match = activeRuns
            .filter { it.projectId == projectId && it.scriptPath == relPath }
            .maxByOrNull { it.runId }
        return copy(
            boundRunId = match?.runId,
            paused = match?.paused == true,
        )
    }

    /**
     * 可见行：绑定 run 的行 + 水位之上（清空按钮推水位后，旧行不画、新行照进）。
     *
     * 未绑定（boundRunId null）→ 空表（空态由内容层画，不在这里编一行假的）。
     */
    fun visible(lines: List<ConsoleLineState>): List<ConsoleLineState> {
        val bound = boundRunId ?: return emptyList()
        return lines.filter { it.runId == bound && it.seq > watermarkSeq }
    }
}
