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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.autoscript.ui.components.Glyph
import com.autoscript.ui.components.GlyphKind
import com.autoscript.ui.components.LogLineRow
import com.autoscript.ui.components.pressable
import com.autoscript.ui.state.ConsoleLineState
import com.autoscript.ui.theme.ThemeColors

/**
 * 执行日志悬浮窗的**内容**（2026-10-13 用户令；两个宿主共用同一份）。
 *
 * 版式：标题行（标题 + 右上四颗图标钮，顺序固定）+ 日志列表（`LogLineRow`）。
 * 未绑定（执行还没进在途表 / 已结算）→ 空态「本次执行未开始」。
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
    onPauseToggle: () -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit,
    onFullscreenToggle: () -> Unit,
    onCopyLine: (String) -> Unit,
    /** 头部拖拽（系统宿主消费 → updateViewLayout；in-app 宿主传 `{}`）。 */
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
                text = state.title,
                color = ThemeColors.text,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            WindowIconButton(
                kind = if (state.paused) GlyphKind.RESUME else GlyphKind.PAUSE,
                description = if (state.paused) "继续执行" else "暂停执行",
                onClick = onPauseToggle,
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
                Text(
                    text = "本次执行未开始（启动失败或已被停止）",
                    color = ThemeColors.textSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.align(Alignment.Center).padding(16.dp),
                )
            } else if (lines.isEmpty()) {
                Text(
                    text = if (state.paused) "已暂停 —— 恢复后输出继续" else "等待输出…",
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

/** 悬浮窗标题行的一颗图标钮（28dp 触控格 + 按压缩放反馈走全仓 `pressable`）。 */
@Composable
private fun WindowIconButton(
    kind: GlyphKind,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(36.dp)
            .pressable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Glyph(kind = kind, tint = ThemeColors.textSecondary, size = 20.dp)
    }
}

/** 头部拖拽手势：位移增量交给宿主（系统窗 → `updateViewLayout` 改 x/y）。 */
private fun Modifier.dragHandle(onDrag: (Offset) -> Unit): Modifier = pointerInput(onDrag) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull() ?: continue
            if (change.pressed) {
                val delta = change.positionChange()
                if (delta != Offset.Zero) onDrag(delta)
            }
            event.changes.forEach { it.consume() }
        }
    }
}
