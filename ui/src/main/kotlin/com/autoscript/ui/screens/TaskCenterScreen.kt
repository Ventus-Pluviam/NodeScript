package com.autoscript.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.autoscript.ui.components.ActionBarAction
import com.autoscript.ui.components.Dot
import com.autoscript.ui.components.LabeledRow
import com.autoscript.ui.components.PillButton
import com.autoscript.ui.components.SectionHeader
import com.autoscript.ui.components.ActionBar
import com.autoscript.ui.components.Separator
import com.autoscript.ui.components.Cell
import com.autoscript.ui.components.ToneText
import com.autoscript.ui.state.RegistrationForm
import com.autoscript.ui.state.ScheduleKind
import com.autoscript.ui.state.Status
import com.autoscript.ui.state.StatusTone
import com.autoscript.ui.state.TaskCenterState
import com.autoscript.ui.state.TaskRowState
import com.autoscript.ui.state.describe
import com.autoscript.ui.state.label
import com.autoscript.ui.theme.ThemeColors
import com.autoscript.domain.host.ScreenRequirement

/**
 * 任务中心（§8.6 排期 + §8.5 档案/恢复账 + 操作面「登记/取消/立即执行」）。
 *
 * 版式照 TG 的会话列表：一行 = 标题 + 次级摘要 + 右侧动作胶囊，行底一条分隔线。
 * 一条任务的两句摘要（计划 / 下一跳）合成一段次级色文字，这是 TG 会话列表里
 * "最后一条消息 + 时间"那种读法 —— 任务中心本质就是"你的脚本排期列表"。
 *
 * 诚实边界（与 [HomeScreen]/[CapabilityScreen] 同一条纪律）：
 * - 没读到（首帧/失败）显示「尚未读取」/失败原因，**不冒充**「一条任务都没有」；
 * - 停用的任务照样列出（还"在册"，只是不会跑）——不列会让人以为它被删了，
 *   且行首那颗 [Dot] 与 [TaskRowState.badges] 把它标成 PROBLEM；
 * - 降级投递（精确闹钟被收回 → `setWindow`）逐条标注「可能偏差」：§8.6 的承诺，
 *   不是错误（标 ATTENTION 而不是红）；
 * - 「未结算的执行」单列一段并点破它不是"正在跑"（见 `RunRowState`）；
 * - 恢复账三笔分开说（投了几条 / 几条过期未投 / 有没有失败），失败时先看失败那一句。
 *
 * **操作面**：
 * - 操作失败（`opError`）**不清任务清单** —— 清单还是上次读到的事实；
 * - 操作成功回执（`opNotice`）只说**调用被接受**：立即执行的成败
 *   在意图日志/控制台，不在此屏断言"跑成功了"；
 * - 挂起中（`opInFlight`）全部操作按钮禁用 —— 立即执行要挂到
 *   本次执行结算（排队 10s + 脚本超时），不禁用就会双击双投；
 * - 取消走一次确认对话框（误触成本 = 手工重登记全部字段）；
 * - 表单给 once/daily/cron 三态（cron 表达式格，缺省 `0 9 * * *`）；语义校验
 *   （空串/越界）由 `:app` 闸门统一裁决，原文进 `opError`。
 */
@Composable
fun TaskCenterScreen(
    state: TaskCenterState,
    onRefresh: () -> Unit,
    onRunNow: (TaskRowState) -> Unit,
    onCancel: (TaskRowState) -> Unit,
    onRegister: (RegistrationForm) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 待确认的取消对象：null = 无对话框。确认框是防误触，不是权限门。
    var pendingCancel by remember { mutableStateOf<TaskRowState?>(null) }
    var showForm by remember { mutableStateOf(false) }
    var form by remember { mutableStateOf(RegistrationForm()) }

    val status = Status.count(
        load = state.load,
        notLoadedText = "尚未读取（点右上「刷新」现取）",
        total = state.tasks.size,
        emptyText = "读到了，没有已登记的任务",
        unit = "条任务（含已停用）",
    )

    Column(modifier.fillMaxWidth().background(ThemeColors.background)) {
        ActionBar(
            title = "任务中心",
            subtitle = status.text,
            subtitleTone = status.tone,
            actions = {
                ActionBarAction(
                    text = if (showForm) "收起" else "登记",
                    onClick = { showForm = !showForm },
                    enabled = !state.opInFlight,
                )
                ActionBarAction("刷新", onRefresh, enabled = !state.opInFlight)
            },
        )
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            if (state.opInFlight) {
                item { FeedbackLine("执行中…（挂起期间按钮停用）", StatusTone.MUTED) }
            }
            state.opError?.let {
                item { FeedbackLine("操作失败：$it", StatusTone.PROBLEM) }
            }
            state.opNotice?.let {
                item { FeedbackLine(it, StatusTone.OK) }
            }
            if (showForm) {
                item {
                    RegistrationBlock(
                        form = form,
                        onChange = { form = it },
                        onSubmit = {
                            // 解析抛错（形状非法）也走操作回执通道：原文进 opError，不吞。
                            onRegister(form)
                        },
                        enabled = !state.opInFlight,
                    )
                    Separator()
                }
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
                item {
                    SectionHeader("未结算的执行 ${state.unfinishedRuns.size}")
                    // 非空不藏：生产实现里这一栏应当恒空，非空 = 有执行没结算（§8.5 孤儿）。
                    ToneText(
                        text = "档案未结算 —— 不是此刻正在跑",
                        tone = StatusTone.ATTENTION,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                    )
                }
                items(state.unfinishedRuns, key = { it.engineRunId }) { run ->
                    Cell(
                        modifier = Modifier.animateItem(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                    ) {
                    ToneText(
                        text = buildString {
                            append("#${run.engineRunId} ${run.scriptPath}：${run.stateLabel}")
                            run.intentRunId?.let { append("（意图 #$it）") }
                                ?: append("（无意图关联）")
                            run.startedText?.let { append("，起于 $it") }
                        },
                        tone = StatusTone.ATTENTION,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Separator()
                }
                }
            }
            items(state.tasks, key = { it.id }) { task ->
                // 登记/取消后列表会增删，animateItem 让增删是"落位/让位"而不是瞬移。
                Box(Modifier.animateItem()) {
                    TaskRow(
                        task = task,
                        opInFlight = state.opInFlight,
                        onRunNow = { onRunNow(task) },
                        onCancel = { pendingCancel = task },
                    )
                    Separator()
                }
            }
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
 * 一条任务：行首圆点（停用/降级）+ 名称 + 次级摘要 + 行尾「立即执行 / 取消」。
 *
 * 摘要两行合一：`scheduleText` 与 `nextFireText` 拼一段——TG 会话列表就是这种
 * "主行 + 灰字摘要"的两栏；分成两行会让列表高度虚增一倍。
 * [TaskRowState.badges] 给的提示挂摘要后面，不另开一段。
 */
@Composable
private fun TaskRow(
    task: TaskRowState,
    opInFlight: Boolean,
    onRunNow: () -> Unit,
    onCancel: () -> Unit,
) {
    val badges = task.badges()
    // 行首圆点取"最严重"那一档：停用 > 降级 > 一次性（顺序即严重度）。
    val worst = badges.firstOrNull()?.first ?: StatusTone.OK
    Cell(
        leading = { Dot(worst) },
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        trailing = {
            Row {
                PillButton("立即执行", selected = false, onClick = onRunNow, enabled = !opInFlight)
                Spacer(Modifier.width(4.dp))
                PillButton("取消", selected = false, onClick = onCancel, enabled = !opInFlight)
            }
        },
    ) {
        Column {
            ToneText(
                text = task.name,
                tone = if (task.enabled) StatusTone.NEUTRAL else StatusTone.MUTED,
                style = MaterialTheme.typography.titleMedium,
            )
            ToneText(
                text = buildString {
                    append(task.scheduleText)
                    task.nextFireText?.let { append(" · 下次 $it") }
                    badges.forEach { append(" · ${it.second}") }
                },
                tone = if (task.enabled) StatusTone.MUTED else StatusTone.MUTED,
                style = MaterialTheme.typography.bodySmall,
            )
            ToneText(
                text = task.scriptPath,
                tone = StatusTone.MUTED,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/**
 * 登记表单：字段全空起步（提交空表单过不了 `:app` 闸门的空串校验，
 * 不会误建任务 —— 默认值本身即最小防误触）。
 *
 * 排期三态与亮屏要求都用 [PillButton]（TG 的筛选胶囊），亮屏那一组**带一句后果**：
 * 保活没生效时亮屏/熄屏任务会被拒（§8.7），那是用户最常撞的失败面。
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
