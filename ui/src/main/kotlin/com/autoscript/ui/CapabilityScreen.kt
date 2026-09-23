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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.autoscript.domain.permission.Capability
import com.autoscript.domain.permission.CapabilityState

/**
 * 能力中心（§9.5）：枚举所有能力 + 三态 + 引导文案 + 一键跳转系统页。
 *
 * 诚实边界（与 [HomeScreen] 同一条纪律）：
 * - 没读到（首帧/失败）显示「尚未读取」/失败原因，**不冒充**「一个能力都没有」；
 * - 每行原样显示宿主给的引导文案（`:domain` 那份 `guideText`），呈现层不加工 ——
 *   加工过的引导文案会和系统里的真实路径漂移；
 * - 三态的说法逐态不同（可用/降级可用/被拒绝），因为"用户该做什么"逐态不同；
 * - 「去授权」按钮的显隐直接读 [CapabilityRowState.canRequestGrant]，呈现层不做判断。
 * - 降级中的定时任务单列一段：那是 §8.6 承诺要标注「可能偏差」的账，不是权限问题。
 */
@Composable
fun CapabilityScreen(
    state: CapabilityCenterState,
    onRefresh: () -> Unit,
    onOpenSettings: (Capability) -> Unit,
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
                Text("能力中心", style = MaterialTheme.typography.headlineMedium)
                Header(state, onRefresh)
                DegradedAlarms(state)
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.rows, key = { it.capability.name }) { row ->
                        CapabilityCard(row, onOpenSettings)
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(state: CapabilityCenterState, onRefresh: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    when {
        !state.loaded && state.loadError == null -> Text(
            "尚未读取（点「刷新」现问系统）",
            color = colors.onSurfaceVariant,
        )
        !state.loaded -> Text(
            // 失败不吞：原异常文案是现场唯一能区分「ROM 查询崩了」与「装配没接线」的线索。
            "读能力态失败：${state.loadError}",
            color = colors.error,
        )
        else -> Text(
            "共 ${state.rows.size} 项能力（三态现问系统，不缓存）",
            color = colors.onSurfaceVariant,
        )
    }
    Button(onClick = onRefresh) { Text("刷新") }
}

@Composable
private fun DegradedAlarms(state: CapabilityCenterState) {
    if (state.degradedAlarmTaskIds.isEmpty()) return
    val colors = MaterialTheme.colorScheme
    // 非空不藏：精确闹钟被收回时这些任务降级成了 setWindow，排期**可能偏差**（§8.6 的承诺）。
    Text(
        "以下定时任务已降级（可能偏差）：${state.degradedAlarmTaskIds.joinToString("、")}",
        color = colors.tertiary,
    )
}

@Composable
private fun CapabilityCard(row: CapabilityRowState, onOpenSettings: (Capability) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(row.title, style = MaterialTheme.typography.titleMedium)
            Text(
                row.stateLabel,
                color = stateColor(row.state, colors.primary, colors.tertiary, colors.error),
            )
            // 引导文案原样显示（含 GRANTED 时那句"当前可用"）—— 不按三态去猜该不该显示。
            Text(row.guide, style = MaterialTheme.typography.bodySmall)
            if (row.canRequestGrant) {
                Button(onClick = { onOpenSettings(row.capability) }) { Text("去授权") }
            }
        }
    }
}

/**
 * 三态配色。抽成函数是为了让"GRANTED 不是唯一好看的那一态"这件事在代码里可见：
 * DEGRADED 用 tertiary（能用但受限，不是错），DENIED 才用 error。
 */
private fun stateColor(
    state: CapabilityState,
    granted: Color,
    degraded: Color,
    denied: Color,
): Color = when (state) {
    CapabilityState.GRANTED -> granted
    CapabilityState.DEGRADED -> degraded
    CapabilityState.DENIED -> denied
}
