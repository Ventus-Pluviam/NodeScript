package com.autoscript.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import com.autoscript.domain.host.ScreenRequirement
import com.autoscript.ui.components.ActionBar
import com.autoscript.ui.components.ActionBarAction
import com.autoscript.ui.components.ActionBottomSheet
import com.autoscript.ui.components.ActionBottomSheetItem
import com.autoscript.ui.components.ContextMenu
import com.autoscript.ui.components.CopyNotice
import com.autoscript.ui.components.CountBadge
import com.autoscript.ui.components.Glyph
import com.autoscript.ui.components.GlyphKind
import com.autoscript.ui.components.MenuAction
import com.autoscript.ui.components.OvershootEasing
import com.autoscript.ui.components.PillButton
import com.autoscript.ui.components.RefreshableBox
import com.autoscript.ui.components.ScrollToTopButton
import com.autoscript.ui.components.SectionHeader
import com.autoscript.ui.components.Separator
import com.autoscript.ui.components.ToneText
import com.autoscript.ui.components.pressable
import com.autoscript.ui.components.pressableLongPress
import com.autoscript.ui.components.rememberCopyAction
import com.autoscript.ui.components.rememberDeletionParticles
import com.autoscript.ui.components.rememberLongPressFeedback
import com.autoscript.ui.components.rememberPressIndication
import com.autoscript.ui.components.rememberRefreshAction
import com.autoscript.ui.components.TabBarBottomClearance
import com.autoscript.ui.state.ActiveRunState
import com.autoscript.ui.state.ConsoleState
import com.autoscript.ui.state.LoadState
import com.autoscript.ui.state.RegistrationForm
import com.autoscript.ui.state.ScheduleKind
import com.autoscript.ui.state.Status
import com.autoscript.ui.state.StatusTone
import com.autoscript.ui.state.TaskCenterState
import com.autoscript.ui.state.TaskRowState
import com.autoscript.ui.state.TaskSort
import com.autoscript.ui.state.describe
import com.autoscript.ui.state.label
import com.autoscript.ui.state.matches
import com.autoscript.ui.state.sortedTasks
import com.autoscript.ui.theme.ThemeColors
import kotlinx.coroutines.launch

/**
 * 任务栏（TG 联系人页 `ContactsActivity` 的版式复刻，内容从联系人换成任务）：
 *
 * - **屏底**：`windowBackgroundGray`（[ThemeColors.surfaceMuted]）—— TG 那页灰底
 *   （`contentView.setBackgroundColor(key_windowBackgroundGray)`）；
 * - **顶栏**：标题「任务栏」（对应 TG 的「联系人」：Medium 20sp，`createTitleTextView`
 *   的 portrait 档），右上角**排序切换钮**（TG `msg_contacts_name`/`msg_contacts_time`
 *   两态：图标画的是"切过去的那一档"——按名称排序时显示时钟）+「刷新」；
 * - **搜索栏**：`FragmentSearchField` 版式（52dp 槽位、水平 6dp 边距、圆角 20dp 的
 *   **白色**药丸 + 微投影、15sp 文字、提示词半透明）—— 灰底上这颗药丸是白的
 *   （`createRoundRectDrawableShadowed(key_windowBackgroundWhite)`），与项目页那颗
 *   灰底搜索框（白屏上压 5% 黑）不是同一个键；
 * - **白色圆角卡片**（`setSections(12, 16, false)` 的读法：水平 12dp 边距、圆角 16dp、
 *   无投影 —— `SharedConfig.shadowsInSections` 缺省 false，首行距顶 4dp），**运行中的
 *   任务与定时任务同卡**（用户拍板：一个药丸框包两组，组间留白）；
 * - **分组头**：TG `GraySectionCell`（32dp 条、14sp Medium、`graySection` 底 +
 *   `graySectionText` 字），**整条可点 = 展开/收起**（TG 的 rightTextView 可点位）；
 * - **任务行**：TG `UserCell` 的 call 样式（联系人页实际用的那档：行高 56dp、头像
 *   44dp 圆、名字 15sp Medium、次行 13sp、分隔线缩进 68dp）—— 头像用脚本类型徽标
 *   （项目页 [FileTypeAvatar] 的 44dp 版），行尾「立即执行」= 实心播放三角，
 *   该任务挂起中（`opTargetTaskId`）换成**转圈的开口弧**；
 * - **手势**（TG 列表的读法）：点行 = 底部操作面板（立即执行/取消任务/复制脚本路径）、
 *   长按 = 上下文菜单（同三项，已摆出的按钮不撤）；
 * - **FAB**：TG `FragmentFloatingButton`（48dp 圆、按下缩到 0.9、松开过冲回 1），
 *   只有一个动作 = 登记任务（表单弹底部面板）。
 *
 * 诚实边界（与其他屏同一条纪律，一条不松）：
 * - 没读到/读失败**不冒充**空清单：副标题三态各自说话（[Status.count]），卡内空行
 *   也区分「尚未读取」「读失败：原文」「读到了，没有」三种；
 * - 操作失败（`opError`）**不清任务清单**；成功回执只说"调用被接受"；
 * - 在途执行的数据来自**控制台**的运行列表（[ConsoleState.activeRuns]）：停止的
 *   失败/回执（`stopError`/`stopNotice`）在这里也如实给一行；
 * - 恢复账/未结算执行**不再是列表**（本版不做任务日志），但非空时仍各给**一行**
 *   如实提示 —— 藏事实与铺一屏日志之间取的是"说一句"；
 * - 取消走一次确认对话框（误触成本 = 手工重登记全部字段）；
 * - 挂起中（`opInFlight`）操作按钮全部停用 —— 立即执行要挂到执行结算，
 *   不禁用就会双击双投。「刷新」不在此列（幂等读）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskCenterScreen(
    state: TaskCenterState,
    /** 控制台状态：本屏只取在途执行那几样（activeRuns/停止三态/读账），不碰日志行。 */
    console: ConsoleState,
    onRefresh: suspend () -> Unit,
    onRunNow: (TaskRowState) -> Unit,
    onCancel: (TaskRowState) -> Unit,
    onRegister: (RegistrationForm) -> Unit,
    onStopRun: (ActiveRunState) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 待确认的取消对象：null = 无对话框。确认框是防误触，不是权限门。
    var pendingCancel by remember { mutableStateOf<TaskRowState?>(null) }
    // 点中的行（底部操作面板）：null = 收起。
    var sheetTask by remember { mutableStateOf<TaskRowState?>(null) }
    var showForm by remember { mutableStateOf(false) }
    var form by remember { mutableStateOf(RegistrationForm()) }
    // 搜索词/排序档/两组展开态：本屏私有的现值，不进读口（与项目页 FileSort 同分工）。
    // 排序缺省 = 按时间（TG SharedConfig.sortContactsByName 缺省 false 的同款取向）。
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(TaskSort.TIME) }
    var runsExpanded by rememberSaveable { mutableStateOf(true) }
    var tasksExpanded by rememberSaveable { mutableStateOf(true) }

    val refresh = rememberRefreshAction(onRefresh)
    val copy = rememberCopyAction()
    val particles = rememberDeletionParticles()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val particleColor = ThemeColors.accent

    val status = Status.count(
        load = state.load,
        notLoadedText = "尚未读取（点右上「刷新」现取）",
        total = state.tasks.size,
        emptyText = "读到了，没有已登记的任务",
        unit = "条任务（含已停用）",
    )

    // 任务消失（取消、或一次性任务跑完出册）时在原地炸一簇粒子。
    val taskIds = state.tasks.map { it.id }
    LaunchedEffect(taskIds) { particles.sync(taskIds, particleColor) }

    val filteredTasks = remember(state.tasks, query, sort) {
        sortedTasks(state.tasks.filter { it.matches(query) }, sort)
    }

    // 粒子层与列表同一坐标系：行位用窗口坐标减容器原点换算（行不再直接是 lazy 项，
    // 而是卡片 Column 的子级 —— boundsInParent 会差一层卡片偏移）。
    var containerOrigin by remember { mutableStateOf(Offset.Zero) }

    Column(modifier.fillMaxWidth().background(ThemeColors.surfaceMuted)) {
        ActionBar(
            title = "任务栏",
            titleStyle = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Medium),
            subtitle = status.text,
            subtitleTone = status.tone,
            actions = {
                // 排序切换（TG ContactsActivity：图标 = 切过去的那一档 ——
                // 按名称排序时显示时钟，按时间时显示字母 A）。
                val sortLabel = if (sort == TaskSort.NAME) {
                    "当前按名称排序，点按切换为按时间"
                } else {
                    "当前按时间排序，点按切换为按名称"
                }
                Box(
                    Modifier
                        .size(44.dp)
                        .semantics { contentDescription = sortLabel }
                        .pressable(
                            role = Role.Button,
                            overlay = ThemeColors.pressedOverlay,
                            onClick = { sort = if (sort == TaskSort.NAME) TaskSort.TIME else TaskSort.NAME },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Glyph(
                        kind = if (sort == TaskSort.NAME) GlyphKind.SORT_TIME else GlyphKind.SORT_NAME,
                        tint = ThemeColors.barIcon,
                    )
                }
                ActionBarAction("刷新", refresh::trigger)
            },
        )
        SearchField(
            query = query,
            onChange = { query = it },
        )
        Box(
            Modifier
                .weight(1f)
                .onGloballyPositioned { containerOrigin = it.positionInWindow() },
        ) {
            RefreshableBox(Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    // TG ListSectionsDecoration 的读法：段内条目左右 12dp、首行顶 4dp。
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = TabBarBottomClearance()),
                ) {
                    item { CopyNotice(copy) }
                    if (state.opInFlight) {
                        item { FeedbackLine("执行中…（挂起期间按钮停用）", StatusTone.MUTED) }
                    }
                    state.opError?.let {
                        item { FeedbackLine("操作失败：$it", StatusTone.PROBLEM) }
                    }
                    state.opNotice?.let {
                        item { FeedbackLine(it, StatusTone.OK) }
                    }
                    if (console.stopInFlight) {
                        item { FeedbackLine("正在停止…（挂起期间按钮停用）", StatusTone.MUTED) }
                    }
                    console.stopError?.let {
                        item { FeedbackLine("停止失败：$it", StatusTone.PROBLEM) }
                    }
                    console.stopNotice?.let {
                        item { FeedbackLine(it, StatusTone.OK) }
                    }
                    state.recovery?.text()?.let { text ->
                        item {
                            SectionHeader("恢复账")
                            FeedbackLine(
                                text = text,
                                tone = if (state.recovery?.failureText != null) StatusTone.PROBLEM else StatusTone.MUTED,
                            )
                        }
                    }
                    if (state.unfinishedRuns.isNotEmpty()) {
                        // 不再铺未结算列表（本版不做任务日志），但非空必须说一句：
                        // "正在跑"与"上一进程遗物"混读会让用户等一个不会结束的东西。
                        item {
                            FeedbackLine(
                                text = "档案有 ${state.unfinishedRuns.size} 条未结算（上一进程遗物，不是此刻正在跑）",
                                tone = StatusTone.ATTENTION,
                            )
                        }
                    }
                    item {
                        // 白色圆角卡片：两组同卡（clip 先于 background，圆角裁住全部内层）。
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(ThemeColors.background),
                        ) {
                            GroupHeader(
                                title = "运行中的任务",
                                expanded = runsExpanded,
                                onClick = { runsExpanded = !runsExpanded },
                            )
                            if (runsExpanded) {
                                when {
                                    !console.load.isLoaded ->
                                        InCardHint("尚未读取（点右上「刷新」现取）")
                                    console.load is LoadState.Failed ->
                                        InCardHint("读失败：${console.load.failedReason()}", StatusTone.PROBLEM)
                                    console.activeRuns.isEmpty() ->
                                        InCardHint("无在途执行")
                                    else -> console.activeRuns.forEachIndexed { i, run ->
                                        Box(
                                            Modifier.onGloballyPositioned { coords ->
                                                val origin = coords.positionInWindow() - containerOrigin
                                                particles.place(run.runId.toString(), Rect(origin, coords.size.toSize()))
                                            },
                                        ) {
                                            RunRow(
                                                run = run,
                                                stopInFlight = console.stopInFlight,
                                                showDivider = i < console.activeRuns.lastIndex,
                                                onStop = { onStopRun(run) },
                                                onCopy = { copy.copy("已复制执行摘要", run.summaryText()) },
                                            )
                                        }
                                    }
                                }
                            }
                            // 组间留白：同一张白卡里两条灰色分组头之间的一段卡底。
                            Spacer(Modifier.height(8.dp))
                            GroupHeader(
                                title = "定时任务",
                                expanded = tasksExpanded,
                                onClick = { tasksExpanded = !tasksExpanded },
                            )
                            if (tasksExpanded) {
                                when {
                                    state.load is LoadState.Failed ->
                                        InCardHint("读失败：${state.load.failedReason()}", StatusTone.PROBLEM)
                                    !state.load.isLoaded ->
                                        InCardHint("尚未读取（点右上「刷新」现取）")
                                    filteredTasks.isEmpty() && query.isNotBlank() ->
                                        InCardHint("没有匹配的任务")
                                    filteredTasks.isEmpty() ->
                                        InCardHint("读到了，没有已登记的任务（右下角登记）")
                                    else -> filteredTasks.forEachIndexed { i, task ->
                                        Box(
                                            Modifier.onGloballyPositioned { coords ->
                                                val origin = coords.positionInWindow() - containerOrigin
                                                particles.place(task.id, Rect(origin, coords.size.toSize()))
                                            },
                                        ) {
                                            TaskRow(
                                                task = task,
                                                inFlight = state.opInFlight && state.opTargetTaskId == task.id,
                                                opInFlight = state.opInFlight,
                                                showDivider = i < filteredTasks.lastIndex,
                                                onClick = { sheetTask = task },
                                                onRunNow = { onRunNow(task) },
                                                onCancel = { pendingCancel = task },
                                                onCopyPath = { copy.copy("已复制脚本路径", task.scriptPath) },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            // 粒子层与列表同层（同一套坐标），且不吞触摸。
            particles.Overlay(Modifier.matchParentSize())
            ScrollToTopButton(
                visible = listState.firstVisibleItemIndex > 0,
                onClick = { scope.launch { listState.animateScrollToItem(0) } },
                // 回顶钮抬到 FAB 上方（同项目页的让位档：extra 64dp）。
                modifier = Modifier.align(Alignment.BottomEnd)
                    .padding(start = 16.dp, end = 16.dp, bottom = TabBarBottomClearance(extra = 64.dp)),
            )
            // FAB（TG FragmentFloatingButton：48dp 圆、动作蓝、按下缩放回弹）。
            // 本屏只有登记一个动作，没有子按钮可展开 —— 不做旋转。
            val source = remember { MutableInteractionSource() }
            val pressed by source.collectIsPressedAsState()
            val fabScale by animateFloatAsState(
                targetValue = if (pressed) 0.9f else 1f,
                // 按下 80ms 线性缩到 0.9，松开 350ms 过冲回 1（ScaleStateListAnimator 的档）。
                animationSpec = if (pressed) tween(80) else tween(350, easing = OvershootEasing(1.5f)),
                label = "taskFabScale",
            )
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 20.dp, bottom = TabBarBottomClearance(extra = 8.dp))
                    .size(48.dp)
                    .graphicsLayer {
                        scaleX = fabScale
                        scaleY = fabScale
                    }
                    .background(ThemeColors.featuredButton, CircleShape)
                    .clickable(
                        interactionSource = source,
                        indication = rememberPressIndication(),
                        role = Role.Button,
                        onClick = { showForm = true },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Glyph(kind = GlyphKind.PENCIL, tint = ThemeColors.featuredButtonText, size = 24.dp)
            }
        }
    }

    sheetTask?.let { task ->
        ActionBottomSheet(
            title = task.name,
            onDismiss = { sheetTask = null },
        ) {
            ActionBottomSheetItem(
                label = "立即执行",
                enabled = !state.opInFlight,
                onClick = {
                    sheetTask = null
                    onRunNow(task)
                },
            )
            ActionBottomSheetItem(
                label = "取消任务",
                enabled = !state.opInFlight,
                // 红字：撤销排期是带后果的动作（TG 的删除项也是红的）。
                tone = StatusTone.PROBLEM,
                onClick = {
                    sheetTask = null
                    pendingCancel = task
                },
            )
            ActionBottomSheetItem(
                label = "复制脚本路径",
                onClick = {
                    copy.copy("已复制脚本路径", task.scriptPath)
                    sheetTask = null
                },
            )
        }
    }

    pendingCancel?.let { task ->
        AlertDialog(
            onDismissRequest = { pendingCancel = null },
            title = { Text("取消任务") },
            text = {
                Text("将撤销「${task.name}」的排期并移出注册表（已执行过的记录保留）。确定取消？")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingCancel = null
                        onCancel(task)
                    },
                ) { Text("取消任务") }
            },
            dismissButton = {
                TextButton(onClick = { pendingCancel = null }) { Text("返回") }
            },
        )
    }

    if (showForm) {
        ModalBottomSheet(
            onDismissRequest = { showForm = false },
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            containerColor = ThemeColors.sheetBackground,
            // TG 的遮罩是 51/255（M3 默认 32% 更深，要压到 20%）。
            scrimColor = Color.Black.copy(alpha = 51f / 255f),
            dragHandle = null,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 8.dp),
            ) {
                RegistrationBlock(
                    form = form,
                    onChange = { form = it },
                    onSubmit = {
                        // 收起面板再提交：结果（回执/失败原文）在列表那层的反馈行里说，
                        // 面板开着会把它们盖住。
                        showForm = false
                        onRegister(form)
                    },
                    enabled = !state.opInFlight,
                )
            }
        }
    }
}

/** 反馈行（挂起 / 失败原文 / 成功回执三态共用一条排版）。 */
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
 * 分组头（TG `GraySectionCell` 的逐项读法）：32dp 条、底 `graySection`、
 * 字 14sp Medium `graySectionText`、左右 16dp；右侧文字槽 = 展开/收起
 * （TG 的 rightTextView 可点位同位 —— 整条可点，动作写在右边）。
 */
@Composable
private fun GroupHeader(
    title: String,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val palette = ThemeColors
    Row(
        Modifier
            .fillMaxWidth()
            .height(32.dp)
            .background(palette.graySection)
            .pressable(role = Role.Button, overlay = palette.pressedOverlay, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            color = palette.graySectionText,
            style = MaterialTheme.typography.labelLarge,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = if (expanded) "收起" else "展开",
            color = palette.graySectionText,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/** 卡片内的空/错位一行（不是 [EmptyHint] 那种大留白居中 —— 卡内行要密）。 */
@Composable
private fun InCardHint(text: String, tone: StatusTone = StatusTone.MUTED) {
    ToneText(
        text = text,
        tone = tone,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

/**
 * 一条在途执行（TG UserCell 的两行读法，内容是控制台在途表的那两句）：
 * 行首 `#runId` 计数徽标 + 池侧/宿主状态（分歧标红）+ 行尾「停止」。
 * 点一下复制摘要、长按出菜单（停止/复制摘要）—— 与控制台同一套手势。
 */
@Composable
private fun RunRow(
    run: ActiveRunState,
    stopInFlight: Boolean,
    showDivider: Boolean,
    onStop: () -> Unit,
    onCopy: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val longPressFeedback = rememberLongPressFeedback()
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .pressableLongPress(
                    role = Role.Button,
                    onLongClick = {
                        longPressFeedback()
                        menuOpen = true
                    },
                    onClick = onCopy,
                )
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CountBadge("#${run.runId}")
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                ToneText(
                    text = "池侧：${run.poolLabel} · " +
                        (run.hostLabel ?: "宿主状态读不到（引擎已死或未接线）"),
                    tone = run.tone,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (run.drift) {
                    ToneText(
                        text = "状态分歧（宿主自报与池侧不一致）",
                        tone = StatusTone.PROBLEM,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            PillButton("停止", selected = false, onClick = onStop, enabled = !stopInFlight)
        }
        if (showDivider) Separator(indentDp = 68)
        ContextMenu(
            expanded = menuOpen,
            onDismiss = { menuOpen = false },
            actions = listOf(
                MenuAction(
                    label = "停止该执行",
                    onClick = onStop,
                    enabled = !stopInFlight,
                    tone = StatusTone.PROBLEM,
                ),
                MenuAction(label = "复制摘要", onClick = onCopy),
            ),
        )
    }
}

/** 复制用的执行摘要 —— 与行里画的那句**同源**（改一处两边都改；控制台同名函数的镜像）。 */
private fun ActiveRunState.summaryText(): String =
    "#$runId 池侧：$poolLabel · ${hostLabel ?: "宿主状态读不到（引擎已死或未接线）"}"

/**
 * 一条定时任务（TG `UserCell` call 样式的逐项几何：行高 56dp 起、头像 44dp 圆、
 * 名字 15sp Medium、次行 13sp、分隔线缩进 68dp）。
 *
 * - 头像 = 脚本类型徽标（项目页 [FileTypeAvatar] 的 44dp 版：扩展名哈希取色 + 类型字形）；
 * - 次行 = 计划 + 下一跳；停用/降级/一次性的标记另起小行（折叠时挤在摘要行尾
 *   会被省略号截掉，诚实边界不缩水）；
 * - 行尾「立即执行」：实心播放三角（accent 蓝），**该任务挂起中**换成转圈的开口弧
 *   —— 「它真的在跑」与「其他按钮暂时不可用」是两件事；
 * - 点行 = 底部操作面板，长按 = 上下文菜单（同三项；已摆出的按钮不撤）。
 */
@Composable
private fun TaskRow(
    task: TaskRowState,
    inFlight: Boolean,
    opInFlight: Boolean,
    showDivider: Boolean,
    onClick: () -> Unit,
    onRunNow: () -> Unit,
    onCancel: () -> Unit,
    onCopyPath: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val longPressFeedback = rememberLongPressFeedback()
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .pressableLongPress(
                    role = Role.Button,
                    onLongClick = {
                        longPressFeedback()
                        menuOpen = true
                    },
                    onClick = onClick,
                )
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.width(14.dp))
            TaskAvatar(task.scriptPath, task.name)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                ToneText(
                    text = task.name,
                    tone = if (task.enabled) StatusTone.NEUTRAL else StatusTone.MUTED,
                    style = MaterialTheme.typography.titleSmall,
                )
                ToneText(
                    text = buildString {
                        append(task.scheduleText)
                        task.nextFireText?.let { append(" · 下次 $it") }
                    },
                    tone = StatusTone.MUTED,
                    style = MaterialTheme.typography.bodySmall,
                )
                for ((tone, text) in task.badges()) {
                    ToneText(
                        text = "· $text",
                        tone = tone,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            PlayButton(
                inFlight = inFlight,
                enabled = !opInFlight,
                onClick = onRunNow,
            )
            Spacer(Modifier.width(10.dp))
        }
        if (showDivider) Separator(indentDp = 68)
        ContextMenu(
            expanded = menuOpen,
            onDismiss = { menuOpen = false },
            actions = listOf(
                MenuAction(
                    label = "立即执行",
                    onClick = onRunNow,
                    enabled = !opInFlight,
                    tone = StatusTone.LINK,
                ),
                MenuAction(
                    label = "取消任务",
                    onClick = onCancel,
                    enabled = !opInFlight,
                    tone = StatusTone.PROBLEM,
                ),
                MenuAction(label = "复制脚本路径", onClick = onCopyPath),
            ),
        )
    }
}

/**
 * 行尾的「立即执行」：44dp 触控目标里一颗 20dp 图标。
 * 未执行 = 实心播放三角；挂起中 = 开口弧转圈（0.9s 一圈，线性 —— 匀速才是"进行中"）。
 */
@Composable
private fun PlayButton(
    inFlight: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(44.dp)
            .semantics {
                contentDescription = if (inFlight) "执行中" else "立即执行"
            }
            .pressable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (inFlight) {
            // 色值在组合期取好再进绘制 lambda（ThemeColors 是 CompositionLocal 读口，
            // 绘制帧里读不到组合环境）。
            val accent = ThemeColors.accent
            val spin = rememberInfiniteTransition(label = "taskSpin")
            val angle by spin.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(tween(durationMillis = 900, easing = LinearEasing)),
                label = "taskSpinAngle",
            )
            Canvas(Modifier.size(20.dp)) {
                drawArc(
                    color = accent,
                    startAngle = angle,
                    sweepAngle = 280f,
                    useCenter = false,
                    style = Stroke(width = this.size.minDimension * 0.16f, cap = StrokeCap.Round),
                )
            }
        } else {
            Glyph(kind = GlyphKind.PLAY, tint = ThemeColors.accent, size = 20.dp)
        }
    }
}

/**
 * 任务头像：脚本类型徽标（项目页 [FileTypeAvatar] 的 44dp 版 —— TG 联系人行头像
 * 44dp 圆的那一格）。扩展名哈希取色取字，常见类型画白线字形。
 * 不直接复用项目页那颗：它 private 且钉死 52dp，这里按 TG 行的 44dp 重画同款。
 */
@Composable
private fun TaskAvatar(scriptPath: String, name: String) {
    val palette = ThemeColors
    val ext = scriptPath.substringAfterLast('.', "").lowercase()
    val colors = palette.fileAvatarColors
    val slot = if (ext.isNotEmpty()) {
        ext.first().code % colors.size
    } else {
        (name.firstOrNull()?.code ?: 0).mod(colors.size)
    }
    val knownGlyph = when (ext) {
        "js", "mjs", "cjs", "ts", "json" -> GlyphKind.FILE_JS
        "md", "txt", "log", "doc" -> GlyphKind.FILE_DOC
        "html", "htm", "css" -> GlyphKind.FILE_HTML
        "sh", "bash", "zsh" -> GlyphKind.FILE_SH
        else -> null
    }
    Box(
        Modifier
            .size(44.dp)
            .background(colors[slot], CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (knownGlyph != null) {
            Glyph(kind = knownGlyph, tint = Color.White, size = 22.dp, weight = 1.15f)
        } else {
            Text(
                text = ext.take(4).ifEmpty { "?" },
                color = Color.White,
                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold),
            )
        }
    }
}

/**
 * 圆角搜索栏（TG `FragmentSearchField` 的逐字版式）：52dp 槽位、水平 6dp 边距、
 * 内缩 3dp 的**白色**药丸（圆角 20dp + `createRoundRectDrawableShadowed` 的微投影，
 * 这里用 2dp elevation 近似）、放大镜 24dp 距药丸左 12dp（文字位 48dp 同口径）、
 * 提示词/输入文字 15sp、提示词 50% 透明、图标 60%。
 *
 * 与项目页 [SearchField] 不是同一个底：那页白屏上压 5% 黑，这页灰底上是白药丸
 * （`key_windowBackgroundWhite`）—— 两个键，不能合成一个组件参数糊过去。
 */
@Composable
private fun SearchField(
    query: String,
    onChange: (String) -> Unit,
) {
    val palette = ThemeColors
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(46.dp)
                .shadow(elevation = 2.dp, shape = RoundedCornerShape(20.dp), clip = false)
                .background(palette.background, RoundedCornerShape(20.dp)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.width(12.dp))
            Glyph(kind = GlyphKind.SEARCH, tint = palette.text.copy(alpha = 0.6f))
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        text = "搜索任务",
                        color = palette.text.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = palette.text),
                    // 光标色是 `groupcreate_cursor`（[ThemeColors.cursor]），与强调色不是一个键。
                    cursorBrush = SolidColor(palette.cursor),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.width(12.dp))
        }
    }
}

/**
 * 登记表单：字段全空起步（提交空表单过不了 `:app` 闸门的空串校验，
 * 不会误建任务 —— 默认值本身即最小防误触）。
 *
 * 排期三态与亮屏要求都用 [PillButton]（TG 的筛选胶囊），亮屏那一组**带一句后果**：
 * 保活没生效时亮屏/熄屏任务会被拒（§8.7），那是用户最常撞的失败面。
 * 入口在本屏 FAB → 底部面板（[TaskCenterScreen]），不再是顶栏内联块。
 */
@Composable
private fun RegistrationBlock(
    form: RegistrationForm,
    onChange: (RegistrationForm) -> Unit,
    onSubmit: () -> Unit,
    enabled: Boolean,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionHeader("登记任务", Modifier.padding(horizontal = 0.dp))
        Field(form.name, "任务名", enabled) { onChange(form.copy(name = it)) }
        Field(form.projectId, "项目 id", enabled) { onChange(form.copy(projectId = it)) }
        Field(form.scriptPath, "脚本路径（项目内相对路径）", enabled) {
            onChange(form.copy(scriptPath = it))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (kind in ScheduleKind.entries) {
                PillButton(
                    text = kind.label(),
                    selected = form.kind == kind,
                    enabled = enabled,
                    onClick = { onChange(form.copy(kind = kind)) },
                )
            }
        }
        when (form.kind) {
            ScheduleKind.ONCE -> Field(form.delaySecondsText, "延迟秒数", enabled) {
                onChange(form.copy(delaySecondsText = it))
            }
            ScheduleKind.DAILY -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(form.hourText, "时 (0-23)", enabled, Modifier.weight(0.4f)) {
                    onChange(form.copy(hourText = it))
                }
                Field(form.minuteText, "分 (0-59)", enabled, Modifier.weight(0.6f)) {
                    onChange(form.copy(minuteText = it))
                }
            }
            ScheduleKind.CRON -> Field(form.cronText, "cron（分 时 日 月 周，如 0 9 * * *）", enabled) {
                onChange(form.copy(cronText = it))
            }
        }
        ToneText(
            text = "亮屏要求",
            tone = StatusTone.MUTED,
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (screen in ScreenRequirement.entries) {
                PillButton(
                    text = screen.describe().first,
                    selected = form.screen == screen,
                    enabled = enabled,
                    onClick = { onChange(form.copy(screen = screen)) },
                )
            }
        }
        ToneText(
            text = form.screen.describe().second,
            tone = StatusTone.MUTED,
            style = MaterialTheme.typography.labelSmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(
                text = "登记",
                selected = false,
                enabled = enabled,
                onClick = onSubmit,
            )
        }
    }
}

@Composable
private fun Field(
    value: String,
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
    )
}
