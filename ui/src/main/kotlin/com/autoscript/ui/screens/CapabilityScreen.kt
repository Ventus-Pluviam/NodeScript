package com.autoscript.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.autoscript.ui.components.ActionBarAction
import com.autoscript.ui.components.Dot
import com.autoscript.ui.components.PillButton
import com.autoscript.ui.components.SectionHeader
import com.autoscript.ui.components.TgActionBar
import com.autoscript.ui.components.TgDivider
import com.autoscript.ui.components.TgRow
import com.autoscript.ui.components.ToneText
import com.autoscript.ui.state.CapabilityCenterState
import com.autoscript.ui.state.CapabilityRowState
import com.autoscript.ui.state.Status
import com.autoscript.ui.state.StatusTone
import com.autoscript.ui.theme.TgTheme
import com.autoscript.domain.permission.Capability

/**
 * 能力中心（§9.5）：枚举所有能力 + 三态 + 引导文案 + 一键跳转系统页。
 *
 * 版式照 Telegram 的设置分组列表：一行 = 名称 + 状态小圆点 + 引导文案，
 * 「去授权」是行尾那颗文字胶囊 —— TG 里权限项也是这么摆的（点整行或点右侧按钮去系统页）。
 *
 * 诚实边界（与 [HomeScreen] 同一条纪律）：
 * - 没读到（首帧/失败）显示「尚未读取」/失败原因，**不冒充**「一个能力都没有」；
 * - 每行原样显示宿主给的引导文案（`:domain` 那份 `guideText`），呈现层不加工 ——
 *   加工过的引导文案会和系统里的真实路径漂移；
 * - 三态的说法逐态不同（可用/降级可用/被拒绝），因为"用户该做什么"逐态不同；
 *   着色档由 [CapabilityRowState.tone] 给（判读在可测的面，不在 Composable 里）；
 * - 「去授权」按钮的显隐直接读 [CapabilityRowState.canRequestGrant]，呈现层不做判断。
 * - 降级中的定时任务单列一段：那是 §8.6 承诺要标注「可能偏差」的账，不是权限问题。
 * - 安装体积单列一段（§15 E1「接受并明示」）：超支是既成事实，披露的时机是**装之前**
 *   用户能读到的那一屏，而不是装完才发现。
 */
@Composable
fun CapabilityScreen(
    state: CapabilityCenterState,
    onRefresh: () -> Unit,
    onOpenSettings: (Capability) -> Unit,
    modifier: Modifier = Modifier,
) {
    val status = Status.of(
        load = state.load,
        notLoadedText = "尚未读取（点右上「刷新」现问系统）",
        loadedText = "${state.rows.size} 项能力（三态现问系统，不缓存）",
    )
    Column(modifier.fillMaxWidth().background(TgTheme.colors.background)) {
        TgActionBar(
            title = "能力中心",
            subtitle = status.text,
            subtitleTone = status.tone,
            actions = { ActionBarAction("刷新", onRefresh) },
        )
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            state.installSize?.let { size ->
                item {
                    SectionHeader("安装体积")
                    ToneText(
                        text = size.text(),
                        tone = if (size.engineFilesPresent) StatusTone.MUTED else StatusTone.ATTENTION,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                    TgDivider()
                }
            }
            if (state.degradedAlarmTaskIds.isNotEmpty()) {
                item {
                    // 非空不藏：精确闹钟被收回时这些任务降级成了 setWindow，
                    // 排期**可能偏差**（§8.6 的承诺）。
                    SectionHeader("可能偏差")
                    ToneText(
                        text = "以下定时任务已降级（可能偏差）：${state.degradedAlarmTaskIds.joinToString("、")}",
                        tone = StatusTone.ATTENTION,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                    TgDivider()
                }
            }
            items(state.rows, key = { it.capability.name }) { row ->
                CapabilityRow(row, onOpenSettings)
                TgDivider()
            }
        }
    }
}

/**
 * 一行能力：左圆点（着色档）+ 名称/状态 + 引导文案 + 行尾「去授权」胶囊。
 *
 * 引导文案**永远**显示（含 GRANTED 时那句"当前可用"）—— 不按三态去猜该不该显示。
 */
@Composable
private fun CapabilityRow(row: CapabilityRowState, onOpenSettings: (Capability) -> Unit) {
    TgRow(
        leading = { Dot(row.tone) },
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        trailing = if (row.canRequestGrant) {
            {
                PillButton(
                    text = "去授权",
                    selected = false,
                    onClick = { onOpenSettings(row.capability) },
                )
            }
        } else null,
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ToneText(
                    text = row.title,
                    tone = StatusTone.NEUTRAL,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f, fill = false),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                ToneText(
                    text = row.stateLabel,
                    tone = row.tone,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            ToneText(
                text = row.guide,
                tone = StatusTone.MUTED,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
