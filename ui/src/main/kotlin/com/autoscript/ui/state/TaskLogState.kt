package com.autoscript.ui.state

import com.autoscript.domain.host.RunRow
import com.autoscript.domain.host.TaskLogSnapshot
import com.autoscript.domain.scripts.ScriptPaths
import java.time.ZoneId

/** 任务日志（全部项目的终态历史）的纯呈现态：未读取、失败、成功空列表互不冒充。 */
data class TaskLogState(
    val load: LoadState,
    val runs: List<TaskLogRowState>,
) {
    companion object {
        val NOT_LOADED = TaskLogState(LoadState.NotLoaded, emptyList())

        fun of(snapshot: TaskLogSnapshot, zone: ZoneId = ZoneId.systemDefault()) = TaskLogState(
            load = LoadState.Loaded,
            runs = snapshot.runs.map { TaskLogRowState.of(it, zone) },
        )

        /** 读失败：**保留 [previous] 已读到的行**（一次瞬时失败不该抹掉用户已看到的列表）。 */
        fun failed(t: Throwable, previous: TaskLogState = NOT_LOADED) =
            previous.copy(load = LoadState.of(t))
    }
}

/** 日志一行保留项目、退出码和完整诊断原文；未知时间不编造。 */
data class TaskLogRowState(
    val engineRunId: Long,
    val intentRunId: Long?,
    val projectId: String,
    val scriptPath: String,
    val stateLabel: String,
    val startedText: String?,
    val finishedText: String?,
    val exitCode: Int?,
    val crashSummary: String?,
) {
    /** 复制出去的整段摘要 —— 与画出来的那几行**同源**（改一处两边都改）。 */
    fun summaryText(): String = buildString {
        // 项目名走 `projectLabel`：根层条目没有项目，显示「根目录」（否则是「 / x.js」）。
        append("${ScriptPaths.projectLabel(projectId)} / $scriptPath\n")
        append("#$engineRunId · $stateLabel\n")
        append("开始：${startedText ?: "未记录"}\n")
        append("结束：${finishedText ?: "未记录"}\n")
        append("退出码：${exitCode?.toString() ?: "未知"}")
        intentRunId?.let { append("\n投递记录：#$it") }
        crashSummary?.let { append("\n$it") }
    }

    companion object {
        fun of(run: RunRow, zone: ZoneId) = TaskLogRowState(
            engineRunId = run.engineRunId,
            intentRunId = run.intentRunId,
            projectId = run.projectId,
            scriptPath = run.scriptPath,
            stateLabel = RunStateText.describe(run.state),
            startedText = run.startedAtMillis?.let { ScheduleText.absolute(it, zone) },
            finishedText = run.finishedAtMillis?.let { ScheduleText.absolute(it, zone) },
            exitCode = run.exitCode,
            crashSummary = run.crashSummary,
        )
    }
}
