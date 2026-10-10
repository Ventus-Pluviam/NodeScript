package com.autoscript.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.autoscript.ui.components.ActionBar
import com.autoscript.ui.components.ActionBarAction
import com.autoscript.ui.components.EmptyHint
import com.autoscript.ui.components.PillButton
import com.autoscript.ui.components.ScrollToTopButton
import com.autoscript.ui.components.TabBarBottomClearance
import com.autoscript.ui.components.ToneText
import com.autoscript.ui.components.pressable
import com.autoscript.ui.components.rememberCopyAction
import com.autoscript.ui.components.rememberRefreshAction
import com.autoscript.ui.components.rememberScrollToTopVisible
import com.autoscript.ui.state.ConsoleCmdLineState
import com.autoscript.ui.state.ConsoleCmdState
import com.autoscript.ui.state.LoadState
import com.autoscript.ui.state.Status
import com.autoscript.ui.state.StatusTone
import com.autoscript.domain.npm.ShellConsoleMode
import com.autoscript.ui.state.modeLabel
import com.autoscript.ui.theme.ThemeColors
import kotlinx.coroutines.launch

/**
 * 管理面板内的控制台子页：**命令面**（§10.9 第 3 条「npm 终端视图」）。
 *
 * 2026-10-09 用户口径：「控制台不是放系统日志的地方，是用来执行命令的，比如 npm。
 * 或者装的一些依赖会有命令。」此前它是个只读日志查看器 —— 那些日志现在整体搬去了
 * **日志管理**（系统日志 / 脚本输出 / 任务日志三段），控制台只做一件事：**跑命令**。
 *
 * 三条边界写在这一屏上，因为用户在这里最容易误解：
 * - **shell 要显式进模式**（2026-10-09 用户口径：控制台要能执行 shell）：敲 `su` 进
 *   root（`su -c`）或 `shizuku` 进 adb（Shizuku，身份由 Shizuku 服务进程决定），`exit` 退出。默认模式下
 *   裸命令按**依赖提供的命令**解析（`tsc` / `eslint`…），与 shell 面不混；
 *   模式徽标常驻可见 —— 用户不该在以为敲的是 npm 时把命令送进 root shell；
 * - **命令跑在某个项目上**：`npm install` 要落进那个项目的 `node_modules`，
 *   所以顶部先选项目（只有一个项目时不画那一排 —— 一个格子的分段控件是在暗示还有别的）；
 * - **装好的依赖提供的命令**（`npx <bin>` / `npm run <script>`）走**人工审批**（§10.5）：
 *   未获批的那次会入队，批完**要重敲那一行** —— 本屏的回执把这句话说出来。
 *
 * 诚实边界（与其他屏同一条纪律）：
 * - 没读到（首帧）显示「尚未读取」，读失败显示原因**并保留已读到的行**——
 *   一次瞬时失败抹掉用户刚看到的 npm 输出，比报错更糟；
 * - 读成功且行空才说「还没有输出」（那是真的没敲过，[LoadState.Loaded] 才敢这么说）；
 * - 丢包（环容量满丢最老）非零**不藏**：用户看到的不是全部，得知道；
 * - 本批拉满提示可继续拉 —— 措辞是「可能还有」，拉满不等于确实还有；
 * - **在跑就禁用输入行**：重操作会挂起在安装会话上，灰按钮说明「现在不能敲」，
 *   而按下去没反应什么都说明不了。
 *
 * 游标与累积在 [ConsoleCmdState]（MainActivity 经 `reloadConsoleCmd` 驱动），本屏只画。
 *
 * **交互**：点一下输出行 = 复制该行原文（控制台的用处一半在"把这行贴给别人看"）；
 * 输入行回车即执行（与右侧「执行」同一动作）。
 */
@Composable
fun ConsoleScreen(
    state: ConsoleCmdState,
    projects: List<String>,
    onRefresh: suspend () -> Unit,
    onSelectProject: (String) -> Unit,
    onDraft: (String) -> Unit,
    onRun: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * 命令历史（最近的在最前，已去重）—— 由宿主读口给（`HostSummary.consoleHistory`）。
     *
     * 缺省空表：没接线 / 还没读到就是"没有历史可补"，不是错误。
     * 界面**不自己存**：历史要跨进程重启还在，那是落盘的事（`ConsoleHistory`）。
     */
    history: List<String> = emptyList(),
) {
    val status = Status.of(
        load = state.load,
        notLoadedText = "尚未读取（点右上「刷新」现取）",
        loadedText = state.project?.let { "$it · ${state.lines.size} 行输出" } ?: "没有可跑命令的项目",
    )
    val refresh = rememberRefreshAction(onRefresh)
    val copy = rememberCopyAction()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // 新行落在尾部：跟着滚到底，否则敲完一行看不到结果（用户得手动滑）。
    LaunchedEffect(state.lines.size) {
        if (state.lines.isNotEmpty()) listState.animateScrollToItem(state.lines.lastIndex)
    }

    Column(modifier.fillMaxSize().background(ThemeColors.background)) {
        ActionBar(
            title = "控制台",
            onBack = onBack,
            subtitle = status.text,
            subtitleTone = status.tone,
            actions = { ActionBarAction("刷新", refresh::trigger) },
        )
        ToneText(
            text = "在这里跑 npm 命令（npm install / ci / ls / audit…）、依赖装好后提供的命令" +
                "（npx <命令> / npm run <脚本>），以及 shell 命令 —— shell 要先敲 su（root）" +
                "或 shizuku（adb）进特权模式，exit 退出。",
            tone = StatusTone.MUTED,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        (state.load as? LoadState.Failed)?.let { failed ->
            ToneText(
                text = "读取失败：${failed.reason}（下面显示的是上次读到的，可能已过期）",
                tone = StatusTone.PROBLEM,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        state.opError?.let {
            ToneText(it, StatusTone.PROBLEM, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        state.opNotice?.let {
            ToneText(it, StatusTone.MUTED, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        // 项目选择只在**真的不止一个**项目时出现（与依赖管理页同一条）。
        if (projects.size > 1) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                projects.forEach { p ->
                    PillButton(p, selected = p == state.project, onClick = { onSelectProject(p) })
                }
            }
        }
        Box(Modifier.weight(1f)) {
            OutputList(state, listState) { line -> copy.copy("已复制该行", line.text) }
            ScrollToTopButton(
                visible = rememberScrollToTopVisible(listState),
                onClick = { scope.launch { listState.animateScrollToItem(0) } },
                modifier = Modifier.align(Alignment.BottomEnd)
                    .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            )
        }
        InputRow(state, onDraft, onRun, history)
    }
}

/**
 * 输出列表（ECHO / PHASE / OUTPUT / WARNING / RESULT 五类行，按 kind 与成败着色）。
 *
 * 着色判读在 [ConsoleCmdLineState.tone]（它读的是宿主给的 kind 与 ok，不按文本猜）——
 * 本函数只画。
 */
@Composable
private fun OutputList(
    state: ConsoleCmdState,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onCopyLine: (ConsoleCmdLineState) -> Unit,
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 8.dp),
    ) {
        if (state.gap) {
            item {
                // 丢包不藏：环是有界的（既定语义），但"显示的不是全部"必须说出来。
                ToneText(
                    text = "更早的输出已从缓冲区滚出（环有界）：上面这段不是全部",
                    tone = StatusTone.ATTENTION,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }
        if (state.pageFull) {
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
        if (state.project == null && state.load.isLoaded) {
            item { EmptyHint("还没有项目：先到项目页建一个，命令要跑在某个项目的依赖上") }
        } else if (state.load.isLoaded && state.lines.isEmpty()) {
            item { EmptyHint("读到了，还没有输出 —— 在下面敲一行，例如 npm install axios") }
        }
        // 有 key（seq）+ animateItem，新行才是"滑进来"的；否则一屏输出是整块往下跳。
        items(state.lines, key = { it.seq }) { line ->
            ToneText(
                text = line.timeText + "  " + line.text,
                tone = line.tone,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .animateItem()
                    .fillMaxWidth()
                    .pressable(role = Role.Button, onClick = { onCopyLine(line) })
                    .padding(horizontal = 16.dp, vertical = 1.dp),
            )
        }
    }
}

/**
 * 输入行 + 「执行」（裸 `BasicTextField`，同 `RegistryScreen` 的编辑行）。
 *
 * 跑动中（[ConsoleCmdState.canRun] 为 false）整行禁用：见类 KDoc 那条 ——
 * 灰按钮说明「现在不能敲」，按下去没反应什么都说明不了。
 */
@Composable
private fun InputRow(
    state: ConsoleCmdState,
    onDraft: (String) -> Unit,
    onRun: () -> Unit,
    history: List<String>,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        // 历史补全（2026-10-10 批 90）：**草稿为空时**才画 —— 已经在敲字的时候把
        // 历史顶上来，会把人正在写的那行挤走。点一下 = 把它填进输入框（不直接执行：
        // 历史里那条多半要改一改再跑，直接执行等于替用户按了回车）。
        if (history.isNotEmpty() && state.draft.isEmpty()) {
            HistoryRow(history, onPick = onDraft)
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CommandField(
                state.draft, onDraft,
                enabled = state.canRun, onSubmit = onRun, modifier = Modifier.weight(1f),
                mode = state.mode,
            )
            PillButton("执行", selected = false, enabled = state.canRun, onClick = onRun)
        }
        // 特权模式徽标（2026-10-09）：**进了模式必须一眼看得见** —— 否则用户以为自己在
        // 默认模式里敲 npm，实际每条都在往 root shell 送（§9.3「三通道必须显式指定」）。
        if (state.mode != ShellConsoleMode.DEFAULT) {
            ToneText(
                text = "▲ " + modeLabel(state.mode) + " —— exit 退出",
                tone = StatusTone.ATTENTION,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        ToneText(
            text = when {
                !state.load.isLoaded -> "尚未读取：点右上「刷新」现取一次"
                state.project == null -> "没有可跑命令的项目"
                state.running -> "有命令在跑：跑完才能敲下一行"
                state.opInFlight -> "正在提交…"
                state.mode == ShellConsoleMode.DEFAULT ->
                    "默认：裸命令按依赖提供的命令解析（tsc / eslint…）；su 或 shizuku 进特权模式"
                else -> "特权模式：整行原样交给 shell（引号与管道归 shell 自己解析）；exit 退出"
            },
            tone = StatusTone.MUTED,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * 命令历史那一行（横向滚动，最近的在最左）。
 *
 * 只画前 [HISTORY_VISIBLE] 条：控制台是拿来敲命令的，历史条占掉半屏就本末倒置了。
 * 全量仍在宿主侧（`HostSummary.consoleHistory` 给的是完整列表），这里只是"手边够得着"。
 */
@Composable
private fun HistoryRow(history: List<String>, onPick: (String) -> Unit) {
    Row(
        // **横向滚动**而不是换行：一条 `npm install @scope/pkg@1.2.3 -D` 能占掉大半屏，
        // 换行会让这一行长成三行、把输出区挤小。滚动条的语义也更对 —— 它是"手边那一排"，
        // 不是"全部历史"。
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToneText(
            text = "历史",
            tone = StatusTone.MUTED,
            style = MaterialTheme.typography.bodySmall,
        )
        history.take(HISTORY_VISIBLE).forEach { cmd ->
            PillButton(historyLabel(cmd), selected = false, onClick = { onPick(cmd) })
        }
    }
}

/** 历史行最多画几条（见 [HistoryRow]：够手边用即可，全量在宿主侧）。 */
private const val HISTORY_VISIBLE = 6

/** 历史条上的短标签：太长就截断（填进输入框的仍是**原文**，截的只是那颗按钮的字）。 */
private fun historyLabel(cmd: String): String =
    if (cmd.length <= HISTORY_LABEL_MAX) cmd else cmd.take(HISTORY_LABEL_MAX - 1) + "…"

private const val HISTORY_LABEL_MAX = 24

@Composable
private fun CommandField(
    value: String,
    onChange: (String) -> Unit,
    enabled: Boolean,
    onSubmit: () -> Unit,
    modifier: Modifier,
    mode: ShellConsoleMode,
) {
    val palette = ThemeColors
    Row(
        modifier
            .height(36.dp)
            .background(palette.fieldBackground, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(
                    text = if (mode == ShellConsoleMode.DEFAULT) "npm install axios" else "ls -la /sdcard",
                    color = palette.text.copy(alpha = 0.5f),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                enabled = enabled,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = if (enabled) palette.text else palette.text.copy(alpha = 0.5f),
                ),
                cursorBrush = SolidColor(palette.cursor),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onDone = { onSubmit() },
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
