package com.autoscript.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.autoscript.domain.host.ScreenRequirement

/**
 * 任务中心（§8.6 排期 + §8.5 档案/恢复账 + 操作面「登记/取消/立即执行」）。
 *
 * 诚实边界（与 [HomeScreen]/[CapabilityScreen] 同一条纪律）：
 * - 没读到（首帧/失败）显示「尚未读取」/失败原因，**不冒充**「一条任务都没有」；
 * - 停用的任务照样列出（还"在册"，只是不会跑）——不列会让人以为它被删了；
 * - 降级投递（精确闹钟被收回 → `setWindow`）逐条标注「可能偏差」：§8.6 的承诺，
 *   不是错误；
 * - 「未结算的执行」单列一段并点破它不是"正在跑"（见 [RunRowState]）；
 * - 恢复账三笔分开说（投了几条 / 几条过期未投 / 有没有失败），失败时先看失败那一句。
 *
 * **操作面**（本片补上登记/取消/立即执行）：
 * - 操作失败（[TaskCenterState.opError]）**不清任务清单** —— 清单还是上次读到的事实；
 * - 操作成功回执（[TaskCenterState.opNotice]）只说**调用被接受**：立即执行的成败
 *   在意图日志/控制台，不在此屏断言"跑成功了"；
 * - 挂起中（[TaskCenterState.opInFlight]）全部操作按钮禁用 —— 立即执行要挂到
 *   本次执行结算（排队 10s + 脚本超时），不禁用就会双击双投；
 * - 取消走一次确认对话框（误触成本 = 手工重登记全部字段）；
 * - 表单给 once/daily/cron 三态（cron 表达式格，缺省 `0 9 * * *`）；语义校验
 *   （空串/越界）由 `:app` 闸门统一裁决，原文进 [TaskCenterState.opError]。
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

    MaterialTheme {
        Surface(
            modifier = modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("任务中心", style = MaterialTheme.typography.headlineMedium)
                Header(state, onRefresh)
                OpFeedback(state)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // 挂起中禁用：立即执行要等执行结算，重复点 = 双投。
                    Button(
                        onClick = { showForm = !showForm },
                        enabled = !state.opInFlight,
                    ) {
                        Text(if (showForm) "收起登记" else "登记任务")
                    }
                }
                if (showForm) {
                    RegistrationBlock(
                        form = form,
                        onChange = { form = it },
                        onSubmit = {
                            // 解析抛错（形状非法）也走操作回执通道：原文进 opError，不吞。
                            onRegister(form)
                        },
                        enabled = !state.opInFlight,
                    )
                }
                RecoveryBlock(state)
                UnfinishedBlock(state)
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.tasks, key = { it.id }) { task ->
                        TaskCard(
                            task = task,
                            opInFlight = state.opInFlight,
                            onRunNow = { onRunNow(task) },
                            onCancel = { pendingCancel = task },
                        )
                    }
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

@Composable
private fun Header(state: TaskCenterState, onRefresh: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    when {
        !state.loaded && state.loadError == null -> Text(
            "尚未读取（点「刷新」现取）",
            color = colors.onSurfaceVariant,
        )
        !state.loaded -> Text(
            // 失败不吞：原异常文案是区分「壳未装配」与「寄存器读崩了」的唯一线索。
            "读任务失败：${state.loadError}",
            color = colors.error,
        )
        state.tasks.isEmpty() -> Text(
            // 读成功且真的空 —— 这一刻才敢说"没有任务"。
            "读到了，没有已登记的任务",
            color = colors.onSurfaceVariant,
        )
        else -> Text(
            "共 ${state.tasks.size} 条任务（含已停用）",
            color = colors.onSurfaceVariant,
        )
    }
    Button(onClick = onRefresh, enabled = !state.opInFlight) { Text("刷新") }
}

/**
 * 操作回执（登记/取消/立即执行）：三行各自独立，谁都不替谁说话 ——
 * 失败红字 + 成功回执可同屏（上一次成功后紧接着一次失败时，现场两条都要）。
 */
@Composable
private fun OpFeedback(state: TaskCenterState) {
    val colors = MaterialTheme.colorScheme
    if (state.opInFlight) {
        Text("执行中…（挂起期间按钮停用)", color = colors.tertiary)
    }
    state.opError?.let {
        Text("操作失败：$it", color = colors.error)
    }
    state.opNotice?.let {
        Text(it, color = colors.tertiary)
    }
}

/**
 * 登记表单：字段全空起步（提交空表单过不了 `:app` 闸门的空串校验，
 * 不会误建任务 —— 默认值本身即最小防误触）。
 */
@Composable
private fun RegistrationBlock(
    form: RegistrationForm,
    onChange: (RegistrationForm) -> Unit,
    onSubmit: () -> Unit,
    enabled: Boolean,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("登记任务", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = form.name,
                onValueChange = { onChange(form.copy(name = it)) },
                label = { Text("任务名") },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = form.projectId,
                onValueChange = { onChange(form.copy(projectId = it)) },
                label = { Text("项目 id") },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = form.scriptPath,
                onValueChange = { onChange(form.copy(scriptPath = it)) },
                label = { Text("脚本路径（项目内相对路径）") },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onChange(form.copy(kind = ScheduleKind.ONCE)) },
                    enabled = enabled,
                ) { Text(if (form.kind == ScheduleKind.ONCE) "· 一次" else "一次") }
                Button(
                    onClick = { onChange(form.copy(kind = ScheduleKind.DAILY)) },
                    enabled = enabled,
                ) { Text(if (form.kind == ScheduleKind.DAILY) "· 每日" else "每日") }
                Button(
                    onClick = { onChange(form.copy(kind = ScheduleKind.CRON)) },
                    enabled = enabled,
                ) { Text(if (form.kind == ScheduleKind.CRON) "· cron" else "cron") }
            }
            if (form.kind == ScheduleKind.ONCE) {
                OutlinedTextField(
                    value = form.delaySecondsText,
                    onValueChange = { onChange(form.copy(delaySecondsText = it)) },
                    label = { Text("延迟秒数") },
                    singleLine = true,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else if (form.kind == ScheduleKind.DAILY) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = form.hourText,
                        onValueChange = { onChange(form.copy(hourText = it)) },
                        label = { Text("时 (0-23)") },
                        singleLine = true,
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth(0.4f),
                    )
                    OutlinedTextField(
                        value = form.minuteText,
                        onValueChange = { onChange(form.copy(minuteText = it)) },
                        label = { Text("分 (0-59)") },
                        singleLine = true,
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                OutlinedTextField(
                    value = form.cronText,
                    onValueChange = { onChange(form.copy(cronText = it)) },
                    label = { Text("cron 表达式（分 时 日 月 周，如 0 9 * * *）") },
                    singleLine = true,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (screen in ScreenRequirement.entries) {
                    Button(
                        onClick = { onChange(form.copy(screen = screen)) },
                        enabled = enabled,
                    ) {
                        Text(
                            if (form.screen == screen) "· ${screen.label()}" else screen.label(),
                        )
                    }
                }
            }
            Button(onClick = onSubmit, enabled = enabled) { Text("登记") }
        }
    }
}

private fun ScreenRequirement.label(): String = when (this) {
    ScreenRequirement.SCREEN_ON -> "亮屏"
    ScreenRequirement.ANY -> "任意"
    ScreenRequirement.SCREEN_OFF -> "熄屏"
}

@Composable
private fun RecoveryBlock(state: TaskCenterState) {
    val recovery = state.recovery ?: return
    val colors = MaterialTheme.colorScheme
    if (recovery.failureText != null) {
        Text("上次启动恢复失败：${recovery.failureText}", color = colors.error)
        return
    }
    Text(
        buildString {
            append("上次启动恢复 ${recovery.total} 条，重投 ${recovery.retried} 条")
            if (recovery.expired > 0) {
                // 过期不等于没恢复：它是"封口记账、不重投"（§8.6 deadline），单独说。
                append("，其中 ${recovery.expired} 条已过约定时刻（未重投）")
            }
        },
        color = colors.onSurfaceVariant,
    )
}

@Composable
private fun UnfinishedBlock(state: TaskCenterState) {
    if (state.unfinishedRuns.isEmpty()) return
    val colors = MaterialTheme.colorScheme
    // 非空不藏：生产实现里这一栏应当恒空，非空 = 有执行没结算（§8.5 孤儿）。
    Text("有 ${state.unfinishedRuns.size} 条执行未结算：", color = colors.tertiary)
    for (run in state.unfinishedRuns) {
        Text(
            buildString {
                append("· #${run.engineRunId} ${run.scriptPath}：${run.stateLabel}")
                run.intentRunId?.let { append("（意图 #$it）") } ?: append("（无意图关联）")
                run.startedText?.let { append("，起于 $it") }
            },
            color = colors.tertiary,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun TaskCard(
    task: TaskRowState,
    opInFlight: Boolean,
    onRunNow: () -> Unit,
    onCancel: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(task.name, style = MaterialTheme.typography.titleMedium)
            Text(task.scheduleText)
            task.nextFireText?.let { Text("下次：$it", color = colors.onSurfaceVariant) }
            Text(task.scriptPath, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            if (!task.enabled) {
                // 停用照样列出来（还"在册"），但说清它不会跑 —— 不说会让人以为丢了。
                Text("已停用（不会自动投递；仍可立即执行）", color = colors.error)
            }
            if (task.degraded) {
                // §8.6 承诺的「可能偏差」：会跑，但不保证守时。
                Text("降级投递（可能偏差）：精确闹钟不可用，已按窗口投递", color = colors.tertiary)
            }
            if (task.once) {
                // 与「立即执行」的终态化语义对齐：跑完就出册，事前说破。
                Text("一次性任务：执行过后自动移出注册表", color = colors.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRunNow, enabled = !opInFlight) { Text("立即执行") }
                Button(onClick = onCancel, enabled = !opInFlight) { Text("取消") }
            }
        }
    }
}
