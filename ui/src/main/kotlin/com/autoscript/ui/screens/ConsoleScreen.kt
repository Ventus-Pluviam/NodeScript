package com.autoscript.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.autoscript.ui.components.ActionBarAction
import com.autoscript.ui.components.CountBadge
import com.autoscript.ui.components.EmptyHint
import com.autoscript.ui.components.PillButton
import com.autoscript.ui.components.SectionHeader
import com.autoscript.ui.components.ActionBar
import com.autoscript.ui.components.Separator
import com.autoscript.ui.components.Cell
import com.autoscript.ui.components.ToneText
import com.autoscript.ui.state.ActiveRunState
import com.autoscript.ui.state.ConsoleLineState
import com.autoscript.ui.state.ConsoleState
import com.autoscript.ui.state.LoadState
import com.autoscript.ui.state.Status
import com.autoscript.ui.state.StatusTone
import com.autoscript.ui.theme.ThemeColors

/**
 * 控制台（§7.3 数据面游标拉取 + §8.3 在途执行两端对照）。
 *
 * 版式照 TG 的日志观感：**行是密的**（13sp、通栏单行、无行间大留白），
 * 前缀 `[HH:mm:ss] 级别 [#runId]` 一律用弱化色，只有 `error` 通栏标红 ——
 * 满屏红字等于没标（这条在旧版是写在 KDoc 里的经验，现在由 [ConsoleLineState.tone] 落地）。
 *
 * 诚实边界（与 [HomeScreen]/[CapabilityScreen]/[TaskCenterScreen] 同一条纪律）：
 * - 没读到（首帧）显示「尚未读取」，读失败显示原因**并保留已读到的行**——
 *   一次瞬时失败抹掉用户已经看到的日志，比报错更糟；
 * - 读成功且行空才说「暂无日志」（那是真的没有输出，[LoadState.Loaded] 才敢这么说）；
 * - 丢包（有界队列容量满丢最老）非零**不藏**：用户看到的不是全部，得知道；
 * - 本批拉满（`pageFull`）提示可继续拉 —— 措辞是「可能还有」，拉满不等于确实还有；
 * - 在途执行：宿主状态**读不到**如实说读不到（不渲染成某个状态），分歧（drift）标红；
 *   每行一个「停止」按钮（按 runId 精确停 → 池四步 quiesce，已结算再点如实说"已不在途"，
 *   不抛 —— 那是 `AlreadyGone` 的诚实投影，不是失败）。
 * - 停止回执与读账分开 —— 停失败不清已读到的行（同任务屏 opError 不清清单一条理）；
 *   挂起中按钮禁用防连点。
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
    val status = Status.of(
        load = state.load,
        notLoadedText = "尚未读取（点右上「刷新」现取）",
        loadedText = "已读 ${state.lines.size} 行日志",
    )
    Column(modifier.fillMaxWidth().background(ThemeColors.background)) {
        ActionBar(
            title = "控制台",
            subtitle = status.text,
            subtitleTone = status.tone,
            actions = { ActionBarAction("刷新", onRefresh) },
        )
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            if (state.pageFull && state.load.isLoaded) {
                item {
                    // 措辞是「可能还有」：拉满不等于确实还有。
                    ToneText(
                        text = "本批已拉满，可能还有更新的行 —— 点「刷新」继续拉取",
                        tone = StatusTone.MUTED,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
            if (state.droppedTotal > 0L) {
                item {
                    // 丢包不藏：有界队列是数据面既定语义，但"显示的不是全部"必须说出来。
                    ToneText(
                        text = "已丢弃 ${state.droppedTotal} 行（队列有界，最早输出被覆盖）：控制台有缺口",
                        tone = StatusTone.ATTENTION,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
            if (state.stopInFlight) {
                item { FeedbackLine("正在停止…（挂起期间按钮停用）", StatusTone.MUTED) }
            }
            state.stopError?.let {
                // 失败红字原文透传（区分"壳未装配"与"读崩了"的唯一线索是原文）
                item { FeedbackLine("停止失败：$it", StatusTone.PROBLEM) }
            }
            state.stopNotice?.let {
                // 成功只说"已请求停止"，以刷新后的在途表为准（停走是异步 quiesce，不赌停没停）
                item { FeedbackLine(it, StatusTone.OK) }
            }
            if (state.activeRuns.isNotEmpty()) {
                item {
                    SectionHeader("在途执行 ${state.activeRuns.size}")
                }
                items(state.activeRuns, key = { it.runId }) { run ->
                    // animateItem 要有 key 才生效（在途行有 runId）：新起一次执行时
                    // 这行是**淡入落位**进来的，而不是凭空出现。
                    Box(Modifier.animateItem()) {
                        ActiveRunRow(
                            run,
                            stopInFlight = state.stopInFlight,
                            onStop = { onStopRun(run) },
                        )
                        Separator()
                    }
                }
            } else if (state.load.isLoaded && state.loadErrorOrNull() == null) {
                item {
                    // 读成功且没有在途执行 —— 真实事实（在途表是权威），与"没读到"分开。
                    SectionHeader("无在途执行")
                }
            }
            if (state.lines.isEmpty() && state.load.isLoaded) {
                // 读成功且行空才说「暂无日志」—— 那是真的没有输出。
                item { EmptyHint("读到了，暂无日志") }
            }
            // 控制台是**累积**列表：新行落在尾部。有 key（seq）+ animateItem，
            // 新行才是"滑进来"的；否则一屏日志是整块往下跳。
            items(state.lines, key = { it.seq }) { LineRow(it, Modifier.animateItem()) }
        }
    }
}

/** 读失败时那一行（原文在 [ConsoleState.loadError] 里；此函数只在有失败时调用）。 */
private fun ConsoleState.loadErrorOrNull(): String? =
    (load as? LoadState.Failed)?.reason

/** 顶栏/横幅式的一行反馈（挂起、成功回执、失败原文三态共用）。 */
@Composable
private fun FeedbackLine(text: String, tone: StatusTone) {
    ToneText(
        text = text,
        tone = tone,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/**
 * 一条在途执行：`#runId` + 池侧/宿主两态 + 停止。
 *
 * 宿主状态读不到（`hostLabel == null`）时那一行是 ATTENTION 而不是 MUTED ——
 * "引擎已死或未接线"是用户该知道的事，不该和"正常"一个颜色。
 */
@Composable
private fun ActiveRunRow(run: ActiveRunState, stopInFlight: Boolean, onStop: () -> Unit) {
    Cell(
        leading = {
            CountBadge("#${run.runId}")
        },
        trailing = {
            PillButton("停止", selected = false, onClick = onStop, enabled = !stopInFlight)
        },
    ) {
        Column {
            ToneText(
                text = "池侧：${run.poolLabel} · " +
                    (run.hostLabel ?: "宿主状态读不到（引擎已死或未接线）"),
                tone = run.tone,
                style = MaterialTheme.typography.bodySmall,
            )
            if (run.drift) {
                // §8.3 校准的事实：两端对不上（判据在 RuntimeController，这里只画）。
                ToneText(
                    text = "状态分歧（宿主自报与池侧不一致）",
                    tone = StatusTone.PROBLEM,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * 一行控制台输出：时间 级别 归属 正文，画成一整条。
 *
 * 分两条 `Text` 画前缀/正文会让行高翻倍（不同 baseline 的两行），控制台行本该密；
 * 单条 `Text` 里用弱化色做不到逐段着色，故前缀用弱化字、文字与正文同色，
 * 只有 `error` 通栏红 —— 这是"密"与"可读"在这屏的取舍点。
 */
@Composable
private fun LineRow(line: ConsoleLineState, modifier: Modifier = Modifier) {
    ToneText(
        text = buildString {
            append(line.timeText)
            append("  ")
            append(line.levelLabel)
            append("  ")
            // runId 0 是"引擎外"（宿主/装配期日志），不是"第 0 次执行"。
            if (line.external) append("[引擎外]") else append("[#${line.runId}]")
            append(" ")
            append(line.text)
        },
        tone = line.tone,
        style = MaterialTheme.typography.bodySmall,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 1.dp),
    )
}
