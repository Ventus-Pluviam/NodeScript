package com.autoscript.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.autoscript.ui.components.ActionBar
import com.autoscript.ui.components.ActionBarAction
import com.autoscript.ui.components.Cell
import com.autoscript.ui.components.ContextMenu
import com.autoscript.ui.components.CountBadge
import com.autoscript.ui.components.EmptyHint
import com.autoscript.ui.components.FileTypeAvatar
import com.autoscript.ui.components.LocalToast
import com.autoscript.ui.components.LogLineRow
import com.autoscript.ui.components.MenuAction
import com.autoscript.ui.components.PillButton
import com.autoscript.ui.components.ScrollToTopButton
import com.autoscript.ui.components.SectionHeader
import com.autoscript.ui.components.Separator
import com.autoscript.ui.components.TabBarBottomClearance
import com.autoscript.ui.components.ToneText
import com.autoscript.ui.components.pressable
import com.autoscript.ui.components.pressableLongPress
import com.autoscript.ui.components.rememberCopyAction
import com.autoscript.ui.components.rememberDeletionParticles
import com.autoscript.ui.components.rememberLongPressFeedback
import com.autoscript.ui.components.rememberRefreshAction
import com.autoscript.ui.components.rememberScrollToTopVisible
import com.autoscript.ui.state.ActiveRunState
import com.autoscript.ui.state.ConsoleLineState
import com.autoscript.ui.state.ConsoleState
import com.autoscript.ui.state.LoadState
import com.autoscript.ui.state.ScriptLogRowUi
import com.autoscript.ui.state.StatusTone
import com.autoscript.ui.state.TaskLogRowState
import com.autoscript.ui.state.TaskLogState
import com.autoscript.ui.state.scriptOutputLines
import com.autoscript.ui.state.stopToastMessage
import com.autoscript.ui.state.systemLogLines
import com.autoscript.ui.theme.ThemeColors
import kotlinx.coroutines.launch

/**
 * 日志管理页（管理面板 → 日志管理）：三个列表，分段钮切换。
 *
 * - **系统日志**：宿主自己的行（`runId == 0`，即 [ConsoleLineState.external]）——
 *   装配、恢复、闹钟与保活事件；
 * - **脚本输出**：脚本进程的 console 输出（`runId != 0`）**+ 在途执行块置顶**（带「停止」）。
 *   这一段原先是控制台的全部内容 —— 2026-10-09 用户口径「日志搬去日志管理」，
 *   控制台改做命令面（§10.9 第 3 条），日志一行不少地落到这里；
 * - **任务日志**：全部项目的终态执行历史（原先项目页里的「历史」子页，2026-10-07 用户口径
 *   搬到这里，并从「某个项目」改成「全部项目」—— 每行带 `项目 / 脚本`）。
 *
 * **日志归属**：脚本桥连接认证后绑定 engineRunId，console 输出按执行归属显示；
 * 系统日志只读宿主 `HostLog` 的 runId=0 行，启动期有界缓冲在壳激活时回放。
 * 两者都不是完整 logcat，也不跨进程重启持久保存；任务日志是终态历史，不是 console 全文。
 *
 * 读取与刷新由外壳驱动（进入本页/回前台/手动刷新）；本屏只画，状态原样来自 [ConsoleState]
 * 与 [TaskLogState]（控制台游标与累积行与本页共用一份，读失败保留已读到的行）。
 *
 * 「停止」回执走外壳浮层（批 44 的三态判读 [stopToastMessage]）：此前它是控制台列表顶上
 * 插的一行，插进来会把日志整体往下推一次。判读是纯层、可 JVM 测。
 */
@Composable
fun LogManagementScreen(
    consoleState: ConsoleState,
    taskLogState: TaskLogState,
    scriptLogState: com.autoscript.ui.state.ScriptLogState,
    onRefreshConsole: suspend () -> Unit,
    onRefreshTaskLog: suspend () -> Unit,
    onRefreshScriptLog: suspend () -> Unit,
    onReadScriptLog: suspend (Long) -> List<com.autoscript.domain.host.ConsoleLineRow>,
    onStopRun: (ActiveRunState) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(TAB_SYSTEM) }
    val systemLines = consoleState.systemLogLines()
    val refresh = rememberRefreshAction {
        when (selectedTab) {
            TAB_TASK -> onRefreshTaskLog()
            TAB_SCRIPT -> onRefreshScriptLog()
            else -> onRefreshConsole()
        }
    }
    // 停止三态走外壳浮层（批 44）：键是**解析出的那一句** —— 同一句连着出现不重弹，
    // 换了一句才弹。停止的回执与读账分开（停失败不清已读到的行）。
    val toast = LocalToast.current
    val stopToast = stopToastMessage(consoleState.stopError, consoleState.stopNotice, consoleState.stopInFlight)
    LaunchedEffect(stopToast) { stopToast?.let { toast?.show(it) } }

    Column(modifier.fillMaxSize().background(ThemeColors.background)) {
        ActionBar(
            title = "日志管理",
            onBack = onBack,
            actions = { ActionBarAction("刷新", refresh::trigger) },
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PillButton("系统日志", selected = selectedTab == TAB_SYSTEM, onClick = { selectedTab = TAB_SYSTEM })
            PillButton("脚本输出", selected = selectedTab == TAB_SCRIPT, onClick = { selectedTab = TAB_SCRIPT })
            PillButton("任务日志", selected = selectedTab == TAB_TASK, onClick = { selectedTab = TAB_TASK })
        }
        when (selectedTab) {
            TAB_SYSTEM -> SystemLogList(consoleState, systemLines, Modifier.weight(1f))
            TAB_SCRIPT -> ScriptOutputList(
                consoleState = consoleState,
                scriptLogState = scriptLogState,
                onReadScriptLog = onReadScriptLog,
                onStopRun = onStopRun,
                modifier = Modifier.weight(1f),
            )
            else -> TaskLogList(taskLogState, Modifier.weight(1f))
        }
    }
}

private const val TAB_SYSTEM = 0
private const val TAB_SCRIPT = 1
private const val TAB_TASK = 2

@Composable
private fun SystemLogList(state: ConsoleState, lines: List<ConsoleLineState>, modifier: Modifier = Modifier) {
    val copy = rememberCopyAction()
    LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = TabBarBottomClearance())) {
        item {
            ToneText(
                text = "宿主装配、恢复、闹钟与保活事件（runId 0）。脚本输出在本页「脚本输出」段；" +
                    "仅保留本次进程内的有界日志，不是完整 logcat。壳装配失败时本列表暂不可读。",
                tone = StatusTone.MUTED,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        if (state.droppedTotal > 0L) {
            item {
                // 丢包不藏：有界队列是数据面既定语义，但「显示的不是全部」必须说出来。
                ToneText(
                    text = "已丢弃 ${state.droppedTotal} 行（队列有界，最早输出被覆盖）：日志有缺口",
                    tone = StatusTone.ATTENTION,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }
        if (state.pageFull && state.load.isLoaded) {
            item {
                ToneText(
                    text = "本批已拉满，可能还有更新的行 —— 点「刷新」继续拉取",
                    tone = StatusTone.MUTED,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }
        (state.load as? LoadState.Failed)?.let { failed ->
            item {
                // 读失败带原文，已读到的行仍在下面（一次瞬时失败不抹掉已看到的日志）。
                ToneText(
                    text = "读取失败：${failed.reason}",
                    tone = StatusTone.PROBLEM,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        if (state.load.isLoaded && lines.isEmpty()) {
            item { EmptyHint("读到了，暂无系统日志") }
        }
        items(lines, key = { it.seq }) { line ->
            ToneText(
                text = line.systemLogText(),
                tone = line.tone,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .animateItem()
                    .fillMaxWidth()
                    .pressable(role = Role.Button, onClick = { copy.copy("已复制该行", line.systemLogText()) })
                    .padding(horizontal = 16.dp, vertical = 1.dp),
            )
        }
    }
}

/**
 * 「脚本输出」段（2026-10-13 用户令）：**按执行一条**的日志文件列表 + 在途执行块置顶。
 *
 * 一条 = 一次执行（标题 `/<project>/<path>`，左文件类型图标，右启动时间 + 大小；
 * 还在跑画「运行中」）。点一行进详情（该次执行的落盘全文，`LogLineRow` 同款行）。
 *
 * 在途块为什么还在这儿：`TaskCenterScreen` 与本段都画 `ConsoleState.activeRuns`，
 * 「停止」那条链（按 runId 精确停 → 池四步 quiesce）是**执行**面的东西 ——
 * 它跟着"看执行"走，不跟着"看文件"走。列表是落盘（过去 + 正在），在途是现值。
 *
 * dropped/pageFull 横幅**不在这里**：那是内存控制台行数据面的账（有界 2000），
 * 文件列表没有"拉满/丢包"这件事 —— 系统日志 tab 原样保留。
 */
@Composable
private fun ScriptOutputList(
    consoleState: ConsoleState,
    scriptLogState: com.autoscript.ui.state.ScriptLogState,
    onReadScriptLog: suspend (Long) -> List<com.autoscript.domain.host.ConsoleLineRow>,
    onStopRun: (ActiveRunState) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 详情（null = 列表；非 null = 该次执行的详情面，屏内局部状态 ——
    // 返回回列表，不清已读到的列表；与管理子页的层级无关，进详情不收底栏）。
    var selectedRunId by rememberSaveable { mutableStateOf<Long?>(null) }
    val selected = selectedRunId
    if (selected != null) {
        ScriptLogDetail(
            runId = selected,
            onReadScriptLog = onReadScriptLog,
            onBack = { selectedRunId = null },
            modifier = modifier,
        )
        return
    }
    val copy = rememberCopyAction()
    val particles = rememberDeletionParticles()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // 在途执行消失（被停掉/自己跑完）时在原地炸一簇。
    val runIds = consoleState.activeRuns.map { it.runId.toString() }
    // 主题色在组合期取好再交给 effect：`ThemeColors.accent` 是 @Composable 读，
    // 在 effect 的挂起块里读它编译不过（且那本来就是"取一次、整簇同色"的语义）。
    val particleColor = ThemeColors.accent
    LaunchedEffect(runIds) { particles.sync(runIds, particleColor) }

    Box(modifier.fillMaxWidth()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = TabBarBottomClearance()),
        ) {
            item {
                ToneText(
                    text = "每次执行一条：标题是脚本路径，右侧是启动时间与日志大小；" +
                        "还在跑的显示「运行中」。点一行看该次执行的完整输出。",
                    tone = StatusTone.MUTED,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            (consoleState.load as? LoadState.Failed)?.let { failed ->
                item {
                    ToneText(
                        text = "在途读取失败：${failed.reason}",
                        tone = StatusTone.PROBLEM,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            if (consoleState.activeRuns.isNotEmpty()) {
                item { SectionHeader("在途执行 ${consoleState.activeRuns.size}") }
                items(consoleState.activeRuns, key = { it.runId }) { run ->
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
                            stopInFlight = consoleState.stopInFlight,
                            onStop = { onStopRun(run) },
                            onCopy = { copy.copy("已复制执行摘要", run.summaryText()) },
                        )
                        Separator()
                    }
                }
            } else if (consoleState.load.isLoaded) {
                item {
                    // 读成功且没有在途执行 —— 真实事实（在途表是权威），与"没读到"分开。
                    SectionHeader("无在途执行")
                }
            }
            when (val load = scriptLogState.load) {
                LoadState.NotLoaded -> item { EmptyHint("尚未读取脚本输出") }
                is LoadState.Failed -> item {
                    ToneText(
                        text = "读取脚本输出失败：${load.reason}",
                        tone = StatusTone.PROBLEM,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                LoadState.Loaded -> if (scriptLogState.rows.isEmpty()) {
                    item { EmptyHint("暂无脚本输出（跑一个脚本就有了）") }
                }
            }
            items(scriptLogState.rows, key = { it.runId }) { row ->
                ScriptLogEntryRow(
                    row = row,
                    onClick = { selectedRunId = row.runId },
                    modifier = Modifier.animateItem(),
                )
                Separator()
            }
        }
        particles.Overlay(Modifier.matchParentSize())
        ScrollToTopButton(
            visible = rememberScrollToTopVisible(listState),
            onClick = { scope.launch { listState.animateScrollToItem(0) } },
            modifier = Modifier.align(Alignment.BottomEnd)
                .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        )
    }
}

/**
 * 一条在途执行：`#runId` + 池侧/宿主两态 + 停止（版式原样搬自旧控制台）。
 *
 * 宿主状态读不到（`hostLabel == null`）时那一行是 ATTENTION 而不是 MUTED ——
 * "引擎已死或未接线"是用户该知道的事，不该和"正常"一个颜色。
 *
 * 手势：**点一下复制摘要**、**长按出菜单**（停止 / 复制摘要）—— 行尾那颗「停止」照旧保留。
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
            leading = { CountBadge("#${run.runId}") },
            trailing = { PillButton("停止", selected = false, onClick = onStop, enabled = !stopInFlight) },
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

@Composable
private fun TaskLogList(state: TaskLogState, modifier: Modifier = Modifier) {
    val copy = rememberCopyAction()
    LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = TabBarBottomClearance())) {
        item {
            ToneText(
                text = "仅显示已归档的终态；启动失败无引擎记录，不在此列表内。点一行复制摘要。",
                tone = StatusTone.MUTED,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        when (val load = state.load) {
            LoadState.NotLoaded -> item { EmptyHint("尚未读取任务日志") }
            is LoadState.Failed -> item {
                ToneText(
                    text = "读取任务日志失败：${load.reason}",
                    tone = StatusTone.PROBLEM,
                    modifier = Modifier.padding(16.dp),
                )
            }
            LoadState.Loaded -> if (state.runs.isEmpty()) item { EmptyHint("暂无已归档的终态执行") }
        }
        items(state.runs, key = { it.engineRunId }) { run ->
            TaskLogRow(run, onCopy = { copy.copy("已复制执行摘要", run.summaryText()) })
            Separator()
        }
    }
}

@Composable
private fun TaskLogRow(run: TaskLogRowState, onCopy: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .pressable(role = Role.Button, onClick = onCopy)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            "${run.projectId} / ${run.scriptPath}",
            style = MaterialTheme.typography.titleMedium,
            color = ThemeColors.text,
        )
        Text("#${run.engineRunId} · ${run.stateLabel}", color = ThemeColors.text)
        ToneText("开始：${run.startedText ?: "未记录"}", StatusTone.MUTED)
        ToneText("结束：${run.finishedText ?: "未记录"}", StatusTone.MUTED)
        ToneText("退出码：${run.exitCode?.toString() ?: "未知"}", StatusTone.MUTED)
        run.intentRunId?.let { ToneText("投递记录：#$it", StatusTone.MUTED) }
        run.crashSummary?.let { ToneText(it, StatusTone.PROBLEM, modifier = Modifier.padding(top = 8.dp)) }
    }
}

/**
 * 一次执行的日志条目（一行 = 一次执行；2026-10-13 用户令）。
 *
 * 版式 = 文件行：左 44dp 文件类型图标（与任务头像同档，TG 联系人行那一格），
 * 中标题 + 启动时间，右大小 / 运行中。点行进详情（`onClick`）。
 */
@Composable
private fun ScriptLogEntryRow(
    row: ScriptLogRowUi,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .pressable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileTypeAvatar(ext = row.ext, name = row.title, size = 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = row.title,
                color = ThemeColors.text,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            ToneText(
                text = "启动 ${row.startedText}",
                tone = StatusTone.MUTED,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (row.running) {
            // 在跑：大小位置让给「运行中」（大小在变，写一个数就是撒谎）。
            ToneText(text = "运行中", tone = StatusTone.LINK, style = MaterialTheme.typography.bodySmall)
        } else {
            ToneText(text = row.sizeText, tone = StatusTone.MUTED, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * 一次执行的日志详情（点列表行进来；2026-10-13 用户令）。
 *
 * 内容经 [onReadScriptLog] 挂起拉取（`HostSummary.scriptLogContent`，
 * 截尾 2000 行）。三落点：未读 → 空态；失败 → 原因（保留上次读到的行）；
 * 成功 → 全文（时间升序）。行渲染复用 `LogLineRow`（与悬浮窗同一份）。
 * 返回回列表（屏内局部状态，不动管理子页层级、不收底栏）。
 */
@Composable
private fun ScriptLogDetail(
    runId: Long,
    onReadScriptLog: suspend (Long) -> List<com.autoscript.domain.host.ConsoleLineRow>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val copy = rememberCopyAction()
    var load by rememberSaveable(runId) { mutableStateOf<LoadState>(LoadState.NotLoaded) }
    // **行内容不放进 rememberSaveable**（2026-10-15 真机闪退）：详情页打开后 `LaunchedEffect`
    // 把全文存进 Compose 的注册表（registry），该 runId 的详情每次组合都要重新 `register`，
    // 而注册那一下对**现值**做 canBeSaved —— 空 `List` 是 Serializable（Bundle 收）、
    // 读回来的 `List<String>` 也是 Serializable，**两者都过**；但 `String` 的
    // `CharSequence` 抽象返回的匿名实现不是 Serializable，子列表（`List.subList`）也
    // 不是 —— 一旦行内容形状落在这两种上，`register` 的 `requireCanBeSaved` 就抛
    // `IllegalArgumentException`（Registry 拒绝），异常从离屏/主组合冒到进程 → 闪退。
    // 详情是**屏内局部现值**（返回即弃），行数据不需要跨配置存活；丢掉"已读的行"在
    // 进详情前本来就没读过，语义与 [load] 的 NotLoaded 一致。用 `remember` 而不是
    // `rememberSaveable`：配置重建重新拉一次，成本只是一次本地文件读。
    var lines by remember(runId) { mutableStateOf<List<String>>(emptyList()) }
    // 行是富结构（level/time/text），saveable 存不了 —— 存原文 JSON 行，画时再解。
    // 解码失败的行显示原文（坏行不掀翻整份详情，与 ScriptLogRead.content 同纪律）。
    LaunchedEffect(runId) {
        load = try {
            val rows = onReadScriptLog(runId)
            lines = rows.map { "${it.atMillis}|${it.level}|${it.text}" }
            LoadState.Loaded
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Exception) {
            LoadState.of(t)
        }
    }
    Column(modifier.fillMaxSize().background(ThemeColors.background)) {
        ActionBar(title = "#$runId", onBack = onBack)
        when (val current = load) {
            LoadState.NotLoaded -> EmptyHint("正在读取…")
            is LoadState.Failed -> ToneText(
                text = "读取失败：${current.reason}",
                tone = StatusTone.PROBLEM,
                modifier = Modifier.padding(16.dp),
            )
            LoadState.Loaded -> if (lines.isEmpty()) {
                EmptyHint("这次执行没有输出")
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = TabBarBottomClearance()),
                ) {
                    items(lines, key = { it.hashCode() }) { raw ->
                        val parts = raw.split('|', limit = 3)
                        val text = if (parts.size == 3) {
                            val time = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
                                .format(
                                    java.time.Instant.ofEpochMilli(parts[0].toLongOrNull() ?: 0L)
                                        .atZone(java.time.ZoneId.systemDefault()),
                                )
                            val levelLabel = com.autoscript.ui.state.LevelText.describe(parts[1])
                            "$time  $levelLabel  ${parts[2]}"
                        } else {
                            raw
                        }
                        ToneText(
                            text = text,
                            tone = if (parts.getOrNull(1) == "error") StatusTone.PROBLEM else StatusTone.NEUTRAL,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .fillMaxWidth()
                                .pressable(role = Role.Button, onClick = { copy.copy("已复制该行", text) })
                                .padding(horizontal = 16.dp, vertical = 1.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 系统日志一行（也是复制出去的那串字）：本列表全是 runId 0，故不再每行写归属。 */
private fun ConsoleLineState.systemLogText(): String = "$timeText  $levelLabel  $text"
