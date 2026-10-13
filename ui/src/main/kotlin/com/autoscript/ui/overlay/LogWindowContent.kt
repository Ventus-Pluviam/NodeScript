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
    /**
     * 头部拖拽：入参是本次的**本地**位移（px），返回值是**真正落位**的位移（px）。
     *
     * 为什么要返回值：窗跟着手指走时，本地坐标系被同向抵消 —— 不把"上一步已经挪了
     * 多少"补回去，窗只会以**一半**速度跟手（经典半速 bug，2026-10-13 用户报"不跟手"）。
     * 补的量必须是**真正落位**的那一份：拖到屏幕边缘被夹住时窗没动，此时补 0 ——
     * 补"想挪的量"会让手指回退后窗突然跳一段。
     */
    onDrag: (Offset) -> Offset,
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

/**
 * 头部拖拽手势：位移增量交给宿主（系统窗 → `updateViewLayout` 改 x/y）。
 *
 * **两个坑，2026-10-13 用户报"不跟手"时一次修掉**：
 *
 * 1. **手势检测不能带会变的 key**。拖动每帧都改状态 → 重喂内容 → 重组 →
 *    `pointerInput` 的 key 若是回调本身（每次都新建实例），检测协程**当场被取消重启**，
 *    手指还没抬起来手势就断了。故 key 恒为 [Unit]（检测器活过整场拖动），
 *    回调经 `rememberUpdatedState` 现取最新那份。
 * 2. **补偿半速**：`positionChange()` 给的是**本地**坐标的差，而窗一挪，本地坐标系
 *    就同向挪了同样多 —— 逐帧观察到的差值因此只有真实位移的一半（窗以半速跟手）。
 *    补法是把上一步**真正落位**的位移加回来：
 *    ```
 *    真实位移 = 本地位移 + 上一步落位位移
 *    ```
 *    用返回值而不是"想挪的量"：夹在屏幕边缘时窗没动，此刻补 0 才对
 *    （补想挪的量会让手指回退时窗跳一段）。
 */
@Composable
private fun Modifier.dragHandle(onDrag: (Offset) -> Offset): Modifier {
    val latest = rememberUpdatedState(onDrag)
    return pointerInput(Unit) {
        var applied = Offset.Zero
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull() ?: continue
                if (change.pressed) {
                    val delta = change.positionChange() + applied
                    if (delta != Offset.Zero) applied = latest.value(delta)
                } else {
                    // 抬手/取消：补偿量归零，下一次按下从零起算。
                    applied = Offset.Zero
                }
                event.changes.forEach { it.consume() }
            }
        }
    }
}
