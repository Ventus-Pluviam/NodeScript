package com.autoscript.ui.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.autoscript.ui.components.Glyph
import com.autoscript.ui.components.GlyphKind
import com.autoscript.ui.components.LogLineRow
import com.autoscript.ui.components.pressableLongPress
import com.autoscript.ui.state.ConsoleLineState
import com.autoscript.ui.theme.ThemeColors

/**
 * 执行日志悬浮窗的**内容**（2026-10-13 用户令；两个宿主共用同一份）。
 *
 * 版式：标题行（标题 + 右上四颗图标钮，顺序固定）+ 日志列表（`LogLineRow`）。
 * 未绑定（执行还没进在途表 / **从开窗起一次都没绑上过**）→ 空态「本次执行未开始」。
 * 绑定那条跑完（结算）后窗口**保持**绑定、输出照常可见（用户令「脚本执行完不要
 * 自动清理输出」）—— 标题照旧只写路径，行区照常画；没出过一行且已结算的
 * 才画「本次执行没有输出」。
 *
 * **全回调化、不读任何 CompositionLocal**：系统宿主是离屏 `ComposeView`
 * （没有 MainActivity 的 `Theme` 之外的提供链 —— `Theme` 由宿主自己包，
 * 见 `SystemLogWindowHost`；`LocalToast`/`LocalClipboardManager` 在窗外拿不到）。
 * 点行复制、四个按钮全部经参数进来，in-app 宿主传 `rememberCopyAction` 那套，
 * 系统宿主传控制器直连的回调。
 */
@Composable
fun LogWindowContent(
    state: LogWindowState,
    lines: List<ConsoleLineState>,
    /**
     * 第一颗钮位的统一动作（2026-10-15 用户令「执行完变重跑」）：在途 = 暂停/继续
     * （[LogWindowController.togglePause]），已结算 = 重新执行（`LogWindowController.rerun`）。
     * 内容层只看 [state] 决定画什么图标与文案，动作本身由调用方按 [LogWindowState] 分派。
     */
    onFirstAction: () -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit,
    onFullscreenToggle: () -> Unit,
    onCopyLine: (String) -> Unit,
    /** 头部拖拽：本次的位移增量（px，屏幕上的真实位移）。 */
    onDrag: (Offset) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(ThemeColors.surface),
    ) {
        // 头部（拖拽区）：标题 + 四钮。顺序用户令固定：暂停 / 清空 / 关闭 / 全屏。
        Row(
            Modifier
                .fillMaxWidth()
                .dragHandle(onDrag)
                .padding(start = 16.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = state.titleText,
                color = ThemeColors.text,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // 第一颗钮位：暂停/继续/重跑三态（2026-10-15 用户令）。
            // - 在途且没暂停 → 暂停（点 = 暂停）；在途且已暂停 → 继续（点 = 恢复）；
            // - 已结算（跑完/被停/崩溃）→ **重跑**（用户令「执行完变重跑图标」）：
            //   点 = 重新执行同一文件，长按 = 也是重跑（同一语义，防"没长按对"）。
            WindowIconButton(
                kind = when {
                    state.paused -> GlyphKind.RESUME
                    state.running -> GlyphKind.PAUSE
                    else -> GlyphKind.RUN
                },
                description = when {
                    state.paused -> "继续执行"
                    state.running -> "暂停执行"
                    else -> "重新执行"
                },
                enabled = state.running || state.boundRunId != null,
                onClick = onFirstAction,
                onLongClick = onFirstAction,
            )
            WindowIconButton(kind = GlyphKind.CLEAR, description = "清空日志", onClick = onClear)
            WindowIconButton(kind = GlyphKind.CLOSE, description = "关掉悬浮窗", onClick = onClose)
            WindowIconButton(
                kind = GlyphKind.FULLSCREEN,
                description = if (state.fullscreen) "退出全屏" else "打开全屏",
                onClick = onFullscreenToggle,
            )
        }
        Box(Modifier.fillMaxSize().weight(1f)) {
            if (state.boundRunId == null) {
                // **只有「从开窗起一次都没绑上过、兜底补绑也没补上」才到这里**：跑过的那条
                // 即使结算了也保持绑定（用户令「执行完不要自动清理输出」），起跑即结算的
                // 快速脚本由兜底补绑接住（见 `LogWindowState.bind` 的 fallbackRunId）——
                // 落这里的只剩：启动失败 / 门禁拒 / 起跑即被停的那一线，或跑得太快**一行都没出**
                // 就结束了（在途表没见到、行里也摘不出号）。
                Text(
                    text = "本次执行未开始（启动失败、未通过门禁，或起跑即被停止）",
                    color = ThemeColors.textSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.align(Alignment.Center).padding(16.dp),
                )
            } else if (lines.isEmpty()) {
                Text(
                    text = when {
                        state.paused -> "已暂停 —— 恢复后输出继续"
                        // 「已结算且一行都没有」：如实说没有输出，并指路持久全文 ——
                        // 内存环（2000 行）理论上可能把这次的行挤掉，落盘那份不会
                        // （`ScriptLogStore` 只追加），故不把"读不到"说成"没发生"。
                        !state.running -> "本次执行没有输出（已结束）\n全文见 日志管理 → 脚本输出"
                        else -> "等待输出…"
                    },
                    color = ThemeColors.textSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.align(Alignment.Center).padding(16.dp),
                )
            } else {
                val listState = rememberLazyListState()
                // 新行到底：行数变了就跟到尾（用户手动往上翻时也会被拉回 ——
                // P0 接受：悬浮窗是"盯着看"的面，不是"翻历史"的面，历史去详情页翻）。
                LaunchedEffect(lines.size) {
                    if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(lines, key = { it.seq }) { line ->
                        LogLineRow(line = line, onCopy = onCopyLine, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

/**
 * 悬浮窗标题行的一颗图标钮（28dp 触控格 + 按压缩放反馈走全仓 `pressable`）。
 *
 * [description] 进 `contentDescription`（读屏据此念「暂停执行」而不是「按钮」）——
 * 图标钮没有文字，无障碍名只能从这儿来。
 */
@Composable
private fun WindowIconButton(
    kind: GlyphKind,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onLongClick: () -> Unit = {},
) {
    Box(
        modifier
            .size(36.dp)
            .semantics { contentDescription = description }
            .pressableLongPress(
                enabled = enabled,
                role = Role.Button,
                onLongClick = onLongClick,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Glyph(kind = kind, tint = if (enabled) ThemeColors.textSecondary else ThemeColors.textTertiary, size = 20.dp)
    }
}

/**
 * 头部拖拽手势：把 `positionChange()` 交给宿主
 * （系统窗 → `updateViewLayout` 改 x/y；in-app → 状态层 offset）。
 *
 * **不要"补偿"**（2026-10-14 用户实测"轻轻一动就移动出去很远"，起因就是补偿）：
 * Compose 的 `positionChange()` 已经把 `position` 与 `previousPosition` **用同一份
 * 当前坐标**换算过（事件派发时逐点 `screenToLocal`），所以差值恒等于屏幕上的真实位移
 * —— 窗自己挪了不会让它变小。再"补上一步落位量"就是**把位移加两遍**，而且逐帧累积：
 * 第 1 帧 10、第 2 帧 10+10=20、第 3 帧 10+20=30…… 窗越拖越快，正是"轻轻一动就飞出去"。
 *
 * **真正要防的是手势检测被重组打断**（2026-10-13 那次"不跟手"）：拖动每帧都改状态 →
 * 重喂内容 → 重组 → `pointerInput` 的 key 若是回调本身（每次新建实例），检测协程会被
 * 自己的重组取消重启，手指还没抬手势就断。故 key 恒为 [Unit]（检测器活过整场拖动），
 * 回调经 `rememberUpdatedState` 现取最新那份。
 *
 * **夹取**：手势层只管报位移，夹取在状态层做（它才知道窗多大、边界多宽）—— 窗贴边后
 * 位移继续报但落位被夹住，手指回退时坐标跟着回，天然不跳。
 */
@Composable
private fun Modifier.dragHandle(onDrag: (Offset) -> Unit): Modifier {
    val latest = rememberUpdatedState(onDrag)
    return pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull() ?: continue
                if (change.pressed) {
                    val delta = change.positionChange()
                    if (delta != Offset.Zero) latest.value(delta)
                }
                event.changes.forEach { it.consume() }
            }
        }
    }
}
