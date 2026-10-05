package com.autoscript.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.autoscript.ui.components.ActionBarAction
import com.autoscript.ui.components.CountBadge
import com.autoscript.ui.components.EmptyHint
import com.autoscript.ui.components.LocalToast
import com.autoscript.ui.components.PillButton
import com.autoscript.ui.components.SectionHeader
import com.autoscript.ui.components.ActionBar
import com.autoscript.ui.components.Separator
import com.autoscript.ui.components.Cell
import com.autoscript.ui.components.ContextMenu
import com.autoscript.ui.components.MenuAction
import com.autoscript.ui.components.RefreshableBox
import com.autoscript.ui.components.ScrollToTopButton
import com.autoscript.ui.components.ToneText
import com.autoscript.ui.components.pressable
import com.autoscript.ui.components.pressableLongPress
import com.autoscript.ui.components.rememberCopyAction
import com.autoscript.ui.components.rememberDeletionParticles
import com.autoscript.ui.components.rememberLongPressFeedback
import com.autoscript.ui.components.rememberRefreshAction
import com.autoscript.ui.components.TabBarBottomClearance
import com.autoscript.ui.state.ActiveRunState
import com.autoscript.ui.state.ConsoleLineState
import com.autoscript.ui.state.ConsoleState
import com.autoscript.ui.state.LoadState
import com.autoscript.ui.state.stopToastMessage
import com.autoscript.ui.state.Status
import com.autoscript.ui.state.StatusTone
import com.autoscript.ui.theme.ThemeColors
import kotlinx.coroutines.launch

/**
 * 管理面板内的控制台子页（§7.3 数据面游标拉取 + §8.3 在途执行两端对照）。
 * 顶栏返回交回外壳，外壳同时守系统返回与页签可见性；关闭子页不清日志。
 *
 * 版式照 TG 的日志观感：**行是密的**（13sp、通栏单行、无行间大留白），
 * 前缀 `[HH:mm:ss] 级别 [#runId]` 一律用弱化色，只有 `error` 通栏标红 ——
 * 满屏红字等于没标（这条在旧版是写在 KDoc 里的经验，现在由 [ConsoleLineState.tone] 落地）。
 *
 * 诚实边界（与其他屏同一条纪律）：
 * - 没读到（首帧）显示「尚未读取」，读失败显示原因**并保留已读到的行**——
 *   一次瞬时失败抹掉用户已经看到的日志，比报错更糟；
 * - 读成功且行空才说「暂无日志」（那是真的没有输出，[LoadState.Loaded] 才敢这么说）；
 * - 丢包（有界队列容量满丢最老）非零**不藏**：用户看到的不是全部，得知道；
 * - 本批拉满（`pageFull`）提示可继续拉 —— 措辞是「可能还有」，拉满不等于确实还有；
 * - 在途执行：宿主状态**读不到**如实说读不到（不渲染成某个状态），分歧（drift）标红；
 *   每行一个「停止」按钮（按 runId 精确停 → 池四步 quiesce，已结算再点如实说"已不在途"，
 *   不抛 —— 那是 `AlreadyGone` 的诚实投影，不是失败）。
 * - 停止回执与读账分开 —— 停失败不清已读到的行（同任务屏 opError 不清清单一条理）；
 *   挂起中按钮禁用防连点。**停止三态（挂起/失败原文/回执）走外壳浮层（批 44）**：
 *   此前是列表顶上插的一行，插进来会把日志整体往下推。判读在 [stopToastMessage]
 *   （纯层、可 JVM 测）—— 拉满/丢包那两行留在列表里（那两行说的是"列表本身不全"的账，
 *   跟一次性回执不是一回事）。
 *
 * 游标与累积在 [ConsoleState]（MainActivity 经 `reloadConsole` 驱动），本屏只画。
 *
 * **交互**（TG 的日志观感之外，借的是它的手势读法）：
 * - **点一下日志行 = 复制该行**（原文进剪贴板，浮层弹一句"已复制该行"）——
 *   控制台的用处一半在"把这行贴给别人看"，而文字本身不可选（密排单行）；
 * - **长按在途执行行 = 菜单**（停止该执行 / 复制摘要）：停止按钮照旧留在行尾；
 * - **停止后那一行会消失**（在途表刷新后不再有它），消失处炸一簇粒子（同任务中心）。
 *
 * 丢弃的行（`droppedTotal` 涨）**不炸粒子**：它们是从列表**顶部**滚出去的，
 * 而用户的视线在底部新行上 —— 炸了也看不见，白白铺一层动画。
 */
@Composable
fun ConsoleScreen(
    state: ConsoleState,
    onRefresh: suspend () -> Unit,
    onStopRun: (ActiveRunState) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val status = Status.of(
        load = state.load,
        notLoadedText = "尚未读取（点右上「刷新」现取）",
        loadedText = "已读 ${state.lines.size} 行日志",
    )
    val refresh = rememberRefreshAction(onRefresh)
    val copy = rememberCopyAction()
    // 停止三态走外壳浮层（批 44）：此前插在列表顶上，弹一条就把日志整体往下推一次。
    // 文案一个字没动（判读见 [stopToastMessage]）。拉满/丢包那两行**留在列表里** ——
    // 它们说的是"这列表不全"，是持续为真的事实，不是会自己消失的回执。
    val toast = LocalToast.current
    val stopToast = stopToastMessage(state.stopError, state.stopNotice, state.stopInFlight)
    // 键是**解析出的那一句**：同一句连着出现不重弹（键没变），换了一句才弹。
    LaunchedEffect(stopToast) { stopToast?.let { toast?.show(it) } }
    val particles = rememberDeletionParticles()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val particleColor = ThemeColors.accent

    // 在途执行消失（被停掉/自己跑完）时在原地炸一簇。日志行不参与（见类 KDoc）。
    val runIds = state.activeRuns.map { it.runId.toString() }
    LaunchedEffect(runIds) { particles.sync(runIds, particleColor) }

    Column(modifier.fillMaxWidth().background(ThemeColors.background)) {
        ActionBar(
            title = "控制台",
            onBack = onBack,
            subtitle = status.text,
            subtitleTone = status.tone,
            actions = { ActionBarAction("刷新", refresh::trigger) },
        )
        Box(Modifier.weight(1f)) {
            RefreshableBox(Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(bottom = TabBarBottomClearance()),
                ) {
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
                    if (state.activeRuns.isNotEmpty()) {
                        item {
                            SectionHeader("在途执行 ${state.activeRuns.size}")
                        }
                        items(state.activeRuns, key = { it.runId }) { run ->
                            // animateItem 要有 key 才生效（在途行有 runId）：新起一次执行时
                            // 这行是**淡入落位**进来的，而不是凭空出现。
                            Box(
                                Modifier
                                    .animateItem()
                                    .onGloballyPositioned {
                                        particles.place(run.runId.toString(), it.boundsInParent())
                                    },
                            ) {
                                ActiveRunRow(
                                    run,
                                    stopInFlight = state.stopInFlight,
                                    onStop = { onStopRun(run) },
                                    onCopy = { copy.copy("已复制执行摘要", run.summaryText()) },
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
                    items(state.lines, key = { it.seq }) { line ->
                        LineRow(
                            line = line,
                            modifier = Modifier.animateItem(),
                            onCopy = { copy.copy("已复制该行", line.displayText()) },
                        )
                    }
                }
            }
            particles.Overlay(Modifier.matchParentSize())
            ScrollToTopButton(
                visible = listState.firstVisibleItemIndex > 0,
                onClick = { scope.launch { listState.animateScrollToItem(0) } },
                // 回顶钮抬到悬浮底栏上方（胶囊占位 + 导航 inset，另加 8dp 呼吸 —— TG 的 FAB 同款让位）。
                modifier = Modifier.align(Alignment.BottomEnd)
                    .padding(start = 16.dp, end = 16.dp, bottom = TabBarBottomClearance(extra = 8.dp)),
            )
        }
    }
}

/** 读失败时那一行（原文在 [ConsoleState.loadError] 里；此函数只在有失败时调用）。 */
private fun ConsoleState.loadErrorOrNull(): String? =
    (load as? LoadState.Failed)?.reason

/**
 * 一条在途执行：`#runId` + 池侧/宿主两态 + 停止。
 *
 * 宿主状态读不到（`hostLabel == null`）时那一行是 ATTENTION 而不是 MUTED ——
 * "引擎已死或未接线"是用户该知道的事，不该和"正常"一个颜色。
 *
 * 手势：**点一下复制摘要**（把这行贴去别处问人时，runId 与两端状态正是要贴的东西）、
 * **长按出菜单**（停止 / 复制摘要）—— 行尾那颗「停止」照旧保留。
 */
@Composable
private fun ActiveRunRow(
    run: ActiveRunState,
    stopInFlight: Boolean,
    onStop: () -> Unit,
    onCopy: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val longPressFeedback = rememberLongPressFeedback()
    Box {
        Cell(
            modifier = Modifier.pressableLongPress(
                role = Role.Button,
                onLongClick = {
                    longPressFeedback()
                    menuOpen = true
                },
                onClick = onCopy,
            ),
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
        ContextMenu(
            expanded = menuOpen,
            onDismiss = { menuOpen = false },
            actions = listOf(
                MenuAction(
                    label = "停止该执行",
                    onClick = onStop,
                    enabled = !stopInFlight,
                    // 红字：停止是带后果的动作（TG 的删除项也是红的）。
                    tone = StatusTone.PROBLEM,
                ),
                MenuAction(label = "复制摘要", onClick = onCopy),
            ),
        )
    }
}

/** 复制用的执行摘要 —— 与行里画的那句**同源**（改一处两边都改）。 */
private fun ActiveRunState.summaryText(): String =
    "#$runId 池侧：$poolLabel · ${hostLabel ?: "宿主状态读不到（引擎已死或未接线）"}"

/**
 * 一行控制台输出：时间 级别 归属 正文，画成一整条。
 *
 * 分两条 `Text` 画前缀/正文会让行高翻倍（不同 baseline 的两行），控制台行本该密；
 * 单条 `Text` 里用弱化色做不到逐段着色，故前缀用弱化字、文字与正文同色，
 * 只有 `error` 通栏红 —— 这是"密"与"可读"在这屏的取舍点。
 *
 * **点一下即复制这一行**（[displayText] 与画出来的字符串是同一个函数，不另拼一份）：
 * 整行**通栏可点**（`fillMaxWidth` 在 `pressable` 之前），故点哪都能中，不必点在字上。
 */
@Composable
private fun LineRow(
    line: ConsoleLineState,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ToneText(
        text = line.displayText(),
        tone = line.tone,
        style = MaterialTheme.typography.bodySmall,
        modifier = modifier
            .fillMaxWidth()
            .pressable(role = Role.Button, onClick = onCopy)
            .padding(horizontal = 16.dp, vertical = 1.dp),
    )
}

/** 一行日志画出来（也是复制出去）的那串字。 */
private fun ConsoleLineState.displayText(): String = buildString {
    append(timeText)
    append("  ")
    append(levelLabel)
    append("  ")
    // runId 0 是"引擎外"（宿主/装配期日志），不是"第 0 次执行"。
    if (external) append("[引擎外]") else append("[#$runId]")
    append(" ")
    append(text)
}
