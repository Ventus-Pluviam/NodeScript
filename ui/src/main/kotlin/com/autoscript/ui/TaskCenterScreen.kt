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
 * 任务中心（§8.6 排期 + §8.5 档案/恢复账）。
 *
 * 诚实边界（与 [HomeScreen]/[CapabilityScreen] 同一条纪律）：
 * - 没读到（首帧/失败）显示「尚未读取」/失败原因，**不冒充**「一条任务都没有」；
 * - 停用的任务照样列出（还"在册"，只是不会跑）——不列会让人以为它被删了；
 * - 降级投递（精确闹钟被收回 → `setWindow`）逐条标注「可能偏差」：§8.6 的承诺，
 *   不是错误；
 * - 「未结算的执行」单列一段并点破它不是"正在跑"（见 [RunRowState]）；
 * - 恢复账三笔分开说（投了几条 / 几条过期未投 / 有没有失败），失败时先看失败那一句。
 *
 * 只读一屏：本片不做新建/取消/立即执行（那是下一片，verification 面先立住）。
 */
@Composable
fun TaskCenterScreen(
    state: TaskCenterState,
    onRefresh: () -> Unit,
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
                Text("任务中心", style = MaterialTheme.typography.headlineMedium)
                Header(state, onRefresh)
                RecoveryBlock(state)
                UnfinishedBlock(state)
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.tasks, key = { it.id }) { TaskCard(it) }
                }
            }
        }
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
    Button(onClick = onRefresh) { Text("刷新") }
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
private fun TaskCard(task: TaskRowState) {
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
                Text("已停用（不会投递）", color = colors.error)
            }
            if (task.degraded) {
                // §8.6 承诺的「可能偏差」：会跑，但不保证守时。
                Text("降级投递（可能偏差）：精确闹钟不可用，已按窗口投递", color = colors.tertiary)
            }
        }
    }
}
