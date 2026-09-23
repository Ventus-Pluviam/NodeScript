package com.autoscript.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 控制台（§7.3 数据面游标拉取 + §8.3 在途执行两端对照）。
 *
 * 诚实边界（与 [HomeScreen]/[CapabilityScreen]/[TaskCenterScreen] 同一条纪律）：
 * - 没读到（首帧）显示「尚未读取」，读失败显示原因**并保留已读到的行**——
 *   一次瞬时失败抹掉用户已经看到的日志，比报错更糟；
 * - 读成功且行空才说「暂无日志」（那是真的没有输出）；
 * - 丢包（有界队列容量满丢最老）非零**不藏**：用户看到的不是全部，得知道；
 * - 本批拉满（[ConsoleState.pageFull]）提示可继续拉 —— 措辞是「可能还有」，
 *   拉满不等于确实还有；
 * - 在途执行：宿主状态**读不到**如实说读不到（不渲染成某个状态），分歧（drift）标红；
 *   每行一个「停止」按钮（按 runId 精确停 → 池四步 quiesce，已结算再点如实说"已不在途"，
 *   不抛 —— 那是 `AlreadyGone` 的诚实投影，不是失败）。
 * - 停止回执（[ConsoleState.stopNotice]/[ConsoleState.stopError]）与读账分开 ——
 *   停失败不清已读到的行（同任务屏 opError 不清清单一条理）；挂起中按钮禁用防连点。
 *
 * 游标与累积在 [ConsoleState]（MainActivity 经 `reloadConsole` 驱动），本屏只画。
 */
@Composable
fun ConsoleScreen(
    state: ConsoleState,
    onRefresh: () -> Unit,
    onStopRun: (ActiveRunState) -> Unit,
    modifier: Modifier = Modifier,
) {
    MaterialTheme {
        Surface(
            modifier = modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("控制台", style = MaterialTheme.typography.headlineMedium)
                Header(state, onRefresh)
                DroppedBanner(state)
                StopFeedback(state)
                ActiveRunsBlock(state, onStopRun)
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(state.lines, key = { it.seq }) { LineRow(it) }
                }
            }
        }
    }
}

@Composable
private fun Header(state: ConsoleState, onRefresh: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    when {
        state.loadError != null -> Text(
            // 失败不吞：原异常文案是区分「壳未装配」与「收集器读崩了」的唯一线索；
            // 已读到的行仍在下面列出（失败不清行、不清游标）。
            "读控制台失败：${state.loadError}",
            color = colors.error,
        )
        !state.loaded -> Text(
            "尚未读取（点「刷新」现取）",
            color = colors.onSurfaceVariant,
        )
        state.lines.isEmpty() -> Text(
            // 读成功且真的没输出 —— 这一刻才敢说"暂无日志"。
            "读到了，暂无日志",
            color = colors.onSurfaceVariant,
        )
        else -> Text(
            "已读 ${state.lines.size} 行日志",
            color = colors.onSurfaceVariant,
        )
    }
    if (state.pageFull && state.loaded) {
        Text(
            "本批已拉满，可能还有更新的行 —— 点「刷新」继续拉取",
            color = colors.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
    Button(onClick = onRefresh) { Text("刷新") }
}

@Composable
private fun DroppedBanner(state: ConsoleState) {
    if (state.droppedTotal <= 0L) return
    // 丢包不藏：有界队列是数据面既定语义，但"显示的不是全部"必须说出来。
    Text(
        "已丢弃 ${state.droppedTotal} 行（队列有界，最早输出被覆盖）：控制台有缺口",
        color = MaterialTheme.colorScheme.tertiary,
        style = MaterialTheme.typography.bodySmall,
    )
}

/**
 * 停止回执：成功/失败各自一行，谁都不替谁说话 —— 与任务屏 [OpFeedback] 同一条理。
 * 失败红字原文透传（区分"壳未装配"与"读崩了"的唯一线索是原文）；成功只说"已请求停止"，
 * 以刷新后的在途表为准（停走是异步 quiesce，不赌停没停）。
 */
@Composable
private fun StopFeedback(state: ConsoleState) {
    val colors = MaterialTheme.colorScheme
    if (state.stopInFlight) {
        Text("正在停止…（挂起期间按钮停用）", color = colors.tertiary)
    }
    state.stopError?.let {
        Text("停止失败：$it", color = colors.error)
    }
    state.stopNotice?.let {
        Text(it, color = colors.tertiary)
    }
}

@Composable
private fun ActiveRunsBlock(state: ConsoleState, onStopRun: (ActiveRunState) -> Unit) {
    val colors = MaterialTheme.colorScheme
    if (state.activeRuns.isEmpty()) {
        // 读成功且没有在途执行 —— 真实事实（在途表是权威），与"没读到"分开。
        if (state.loaded && state.loadError == null) {
            Text("无在途执行", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        return
    }
    Text("在途执行 ${state.activeRuns.size} 条：", color = colors.onSurfaceVariant)
    for (run in state.activeRuns) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("#${run.runId}", style = MaterialTheme.typography.titleSmall)
                Text(
                    "池侧：${run.poolLabel} · " +
                        (run.hostLabel ?: "宿主状态读不到（引擎已死或未接线）"),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (run.hostLabel == null) colors.tertiary else colors.onSurfaceVariant,
                )
                if (run.drift) {
                    // §8.3 校准的事实：两端对不上（判据在 RuntimeController，这里只画）。
                    Text("状态分歧（宿主自报与池侧不一致）", color = colors.error, style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = { onStopRun(run) }, enabled = !state.stopInFlight) { Text("停止") }
            }
        }
    }
}

@Composable
private fun LineRow(line: ConsoleLineState) {
    val colors = MaterialTheme.colorScheme
    // 一行画完（时间 级别 归属 正文）：级别着色 —— error 通栏标红，warn 标签词自带识别，
    // 其余走正文色。分两条 Text 画前缀/正文会让行高翻倍，控制台行本该密。
    val color = when (line.level) {
        "error" -> colors.error
        else -> colors.onSurface
    }
    Text(
        buildString {
            append(line.timeText)
            append("  ")
            append(line.levelLabel)
            append("  ")
            // runId 0 是"引擎外"（宿主/装配期日志），不是"第 0 次执行"。
            if (line.external) append("[引擎外]") else append("[#${line.runId}]")
            append(" ")
            append(line.text)
        },
        color = color,
        style = MaterialTheme.typography.bodySmall,
    )
}
