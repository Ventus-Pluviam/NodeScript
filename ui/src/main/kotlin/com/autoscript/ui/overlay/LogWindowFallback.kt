package com.autoscript.ui.overlay

import com.autoscript.domain.host.ScriptLogRow

/**
 * 快速脚本的**兜底补位号**：按**归属**从落盘执行索引里挑"这次执行"的那条 run。
 *
 * 病（2026-10-16 用户报「新增的这个脚本，运行的时候会执行上一次执行成功的脚本，
 * 而不会接收输入内容」）：补位号此前取的是**全库**最新的非引擎外行
 * （`consoleState.scriptOutputLines().maxByOrNull { it.runId }`）—— 它**没有归属过滤**。
 * 窗刚开（还没被在途表绑上）那一刻，只要控制台里存着**任何**更早的执行行
 * （上一次跑过的脚本、上一次失败的那条……），补位就把窗绑到那条上：标题是这次的路径、
 * 行区画的是上一次的输出、重跑钮点下去跑的也是窗里那个旧绑定 —— 读起来正是
 * 「它跑了上一次那个脚本」。归属过滤必须在这里补上，判据与 `LogWindowState.bind`
 * 的同归属匹配**同一套**（`projectId` + 项目内 `relPath` 全等）。
 *
 * 为什么从**落盘索引**（`HostSummary.scriptLogIndex`）挑而不是从控制台行挑：行里没有归属
 * 字段（`ConsoleLineRow` 只有 runId/level/text），而索引每条都带
 * `projectId` + `scriptPath` + `runId` —— 归属是这一层的唯一判据，只能从这里来。
 *
 * 取**最大** runId = 同归属最近一次（runId 单调递增，见 `NodeProcessEngine` 的发号）。
 * 调用方只在窗**从没绑上过**（`boundRunId == null`）时才查（见 `MainActivity` 的轮询），
 * 所以这条读口的成本不进常规轮次。
 */
fun fallbackRunIdFor(state: LogWindowState, rows: List<ScriptLogRow>): Long? =
    rows.asSequence()
        .filter { it.projectId == state.projectId && it.scriptPath == state.relPath }
        .maxByOrNull { it.runId }
        ?.runId
