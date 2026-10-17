package com.autoscript.ui.state

import com.autoscript.domain.host.ScriptLogRow
import com.autoscript.domain.host.ScriptLogSnapshot
import com.autoscript.domain.scripts.ScriptPaths
import java.time.ZoneId

/**
 * 脚本输出日志的文件列表（日志管理页「脚本输出」tab；2026-10-13 用户令）。
 *
 * 一条 = **一次执行**（与 `TaskLogState` 的"终态历史"不同：这里含"在跑的"，
 * 那是 [ScriptLogRowUi.running] 这一列，不是第二种数据）。
 *
 * 三落点与 `TaskLogState` 同构：未读取 / 失败（保留已读到的行）/ 成功。
 * 标题形态在这里定（`/$projectId/$scriptPath`，与悬浮窗标题同一归一化点 ——
 * 见 `LogWindowState.titleText`）：快照层只给事实，斜杠形态是呈现偏好。
 */
data class ScriptLogState(
    val load: LoadState,
    val rows: List<ScriptLogRowUi>,
) {
    companion object {
        val NOT_LOADED = ScriptLogState(LoadState.NotLoaded, emptyList())

        fun of(snapshot: ScriptLogSnapshot, zone: ZoneId = ZoneId.systemDefault()) = ScriptLogState(
            load = LoadState.Loaded,
            rows = snapshot.rows.map { ScriptLogRowUi.of(it, zone) },
        )

        /** 读失败：**保留 [previous] 已读到的行**（与 TaskLogState 同一条纪律）。 */
        fun failed(t: Throwable, previous: ScriptLogState = NOT_LOADED) =
            previous.copy(load = LoadState.of(t))
    }
}

/** 一次执行的日志条目呈现态（标题 + 图标判据 + 右侧两行）。 */
data class ScriptLogRowUi(
    val runId: Long,
    /** 标题（`/$projectId/$scriptPath`，如 `/data/test.js`）。 */
    val title: String,
    /** 文件类型图标判据（最后一段最后一个点之后，小写；`FileTypeAvatar` 的 ext）。 */
    val ext: String,
    /** 启动时刻人话（`ScheduleText.absolute`，与任务日志同口径）。 */
    val startedText: String,
    /** 日志大小人话（`formatFileSize`；running 时不画，画「运行中」）。 */
    val sizeText: String,
    /** 还在跑 —— 呈现层此时大小位置画「运行中」。 */
    val running: Boolean,
) {
    companion object {
        fun of(row: ScriptLogRow, zone: ZoneId) = ScriptLogRowUi(
            runId = row.runId,
            // 根层条目没有项目段（`projectId` 空串）：直接拼会渲染成 `//x.js`，
            // 故走 `ScriptPaths.fullRelPath`（那条判据只有一份，2026-10-16）。
            title = "/" + ScriptPaths.fullRelPath(row.projectId, row.scriptPath),
            // 同 `LogWindowState.ext`：`substringAfterLast('/')` 不能带第二参数
            // （传 "" 会让没有斜杠的路径整个变空串）。
            ext = row.scriptPath
                .substringAfterLast('/')
                .substringAfterLast('.', "")
                .lowercase(),
            startedText = ScheduleText.absolute(row.startedAtMillis, zone),
            sizeText = ScriptFileRowUi.formatFileSize(row.sizeBytes),
            running = row.running,
        )
    }
}
