package com.autoscript.domain.host

/**
 * 脚本输出日志的**文件列表**（日志管理页「脚本输出」tab；2026-10-13 用户令）。
 *
 * 一条 = **一次执行**（不是一个脚本）：同一脚本跑几回就有几行，由
 * `filesDir/.ns/script-logs/` 的 `index.jsonl`（`ScriptLogStore` 写）+ 每个
 * `&lt;runId&gt;.log` 的文件大小拼出来。
 *
 * 与 [ConsoleSnapshot.activeRuns] 的分工：`running` 是读快照那一刻"它还在不在途"，
 * 由调用方（`ScriptLogRead.snapshot`）把在途 runId 集带进来 —— 快照本身不持有控制器，
 * 只做"这一刻长什么样"的投影（与 [TaskLogSnapshot] 只含终态同理：本快照含"在跑的"，
 * 那是 [`running][ScriptLogRow.running] 那一列，不是第二种数据）。
 */
data class ScriptLogSnapshot(
    val rows: List<ScriptLogRow>,
)

/**
 * 一次执行的日志条目。
 *
 * @property runId 执行号（`&lt;runId&gt;.log` 的文件名键；详情读口的钥匙）。
 * @property projectId 触发时那个项目。
 * @property scriptPath 触发时那条相对路径（**项目内形状**，如 `data/test.js` ——
 *   任务登记与任务日志都用这一套，文件清单的 `proj/…` 首段形状在这里**不**用，
 *   见 `ScriptLogRead` 的 KDoc）。
 * @property startedAtMillis 执行开始的墙钟（index 行的 `at`）。
 * @property sizeBytes 日志文件字节数（文件缺失记 0 —— 崩溃可能让 index 先落而行没写）。
 * @property running 读快照时它还在跑 —— 呈现层此时**不画大小、画「运行中」**。
 */
data class ScriptLogRow(
    val runId: Long,
    val projectId: String,
    val scriptPath: String,
    val startedAtMillis: Long,
    val sizeBytes: Long,
    val running: Boolean,
)
