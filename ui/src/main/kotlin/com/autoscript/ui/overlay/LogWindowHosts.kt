package com.autoscript.ui.overlay

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.autoscript.ui.components.TabBarBottomClearance
import com.autoscript.ui.components.rememberCopyAction
import com.autoscript.ui.state.ConsoleLineState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 执行日志悬浮窗的**两个宿主**（2026-10-13 用户令）。
 *
 * - **[LogWindowInAppHost]**：`MainShell` 里 ToastHost 旁的 Box 浮层（pager 外，
 *   过导航存活；无权限时的兜底）。固定右上（全屏时铺满），in-app 拖拽 P0 不做
 *   （位置固定即够用 —— 系统窗才需要拖）。
 * - **[SystemWindowSync]**：无 UI 的效应 —— `controller.state` 非空且走系统窗时，
 *   把同一份 `LogWindowContent` 喂给离屏 `ComposeView`（`SystemLogWindowHost.show`）；
 *   state 变即重喂（内容层无状态，全由参数进）。show 失败 → `fallbackToInApp()`。
 *
 * **轮询**（[LogWindowPoll]）：窗开期间 400ms 拉一次 `onTick`（= reloadConsole + bind）
 * —— 与控制台命令面的 `pollConsoleWhileRunning` 同一条 `pollWhile` 纪律
 * （有界 600 tick ≈ 4 分钟；关窗即停）。行过滤（归属 + 水位）走
 * `LogWindowState.visible`，不过第二遍。
 */

/** 窗开期间的拉取循环（窗关即停；与命令面轮询同纪律：有界 + 只在看的时候问）。 */
@Composable
fun LogWindowPoll(
    open: Boolean,
    onTick: suspend () -> Unit,
) {
    LaunchedEffect(open) {
        if (!open) return@LaunchedEffect
        repeat(LOG_WINDOW_MAX_TICKS) {
            delay(LOG_WINDOW_POLL_MILLIS)
            onTick()
        }
    }
}

/**
 * in-app 兜底窗（无悬浮窗权限时；与 ToastHost 同型 Box 浮层）。
 *
 * 位置：右上（紧凑）/ 铺满（全屏），让出底栏与导航栏（与 toast 同一条让位口径）。
 * 内容与系统窗同一份 `LogWindowContent`（复制走 `rememberCopyAction` 那套）。
 */
@Composable
fun LogWindowInAppHost(
    state: LogWindowState,
    lines: List<ConsoleLineState>,
    onPauseToggle: () -> Unit,
    onClear: (Long) -> Unit,
    onClose: () -> Unit,
    onFullscreenToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val copy = rememberCopyAction()
    val shown = state.visible(lines)
    // `align` 是 `BoxScope` 的扩展，只能在 Box 内部的**子项**上调 —— 不能拼在传给
    // `Box(...)` 的 modifier 链上（编译期抓到）。外层 Box 铺满吃掉尺寸，内层定尺寸/对齐。
    Box(modifier.fillMaxSize()) {
        Box(
            if (state.fullscreen) {
                Modifier
                    .fillMaxSize()
                    .padding(top = 56.dp, bottom = TabBarBottomClearance())
            } else {
                Modifier
                    .width(340.dp)
                    .height(420.dp)
                    .align(Alignment.TopEnd)
                    .padding(top = 56.dp, end = 8.dp, bottom = TabBarBottomClearance())
            },
        ) {
            LogWindowContent(
                state = state,
                lines = shown,
                onPauseToggle = onPauseToggle,
                onClear = { onClear(shown.maxOfOrNull { it.seq } ?: state.watermarkSeq) },
                onClose = onClose,
                onFullscreenToggle = onFullscreenToggle,
                onCopyLine = { copy.copy("已复制该行", it) },
                onDrag = {},
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * 系统窗同步效应（无 UI）：state 非空 + 走系统窗 → `show` 同一份内容；
 * state 任一字段变 → 重 `show`（内容层无状态，全由参数进，重喂即刷新）。
 *
 * 点行复制在系统窗里走直连剪贴板（离屏拿不到 `LocalToast`，故复制成功不弹 toast ——
 * 窗内无浮层口；见 `LogWindowContent` KDoc）。暂停按钮的协程入口由调用方包
 * （`togglePause` 是挂起的，效应里 `launch`）。
 */
@Composable
fun SystemWindowSync(
    controller: LogWindowController,
    lines: List<ConsoleLineState>,
    onPauseToggle: () -> Unit,
) {
    val state = controller.state
    val host = controller.systemHost
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val put = rememberCopyPut()
    LaunchedEffect(state, lines, controller.usingSystemWindow) {
        if (state == null || host == null || !controller.usingSystemWindow) return@LaunchedEffect
        val shown = state.visible(lines)
        val ok = host.show {
            LogWindowContent(
                state = state,
                lines = shown,
                onPauseToggle = onPauseToggle,
                onClear = { controller.clearLogs(shown.maxOfOrNull { it.seq } ?: state.watermarkSeq) },
                onClose = { controller.close() },
                onFullscreenToggle = { controller.toggleFullscreen() },
                onCopyLine = { put(it) },
                onDrag = { delta ->
                    with(density) {
                        scope.launch { host.moveBy(delta.x.dp.roundToPx(), delta.y.dp.roundToPx()) }
                    }
                },
            )
        }
        if (!ok) controller.fallbackToInApp()
    }
}

/** 剪贴板直写（系统窗内用：不要 toast 回执，只要进剪贴板）。 */
@Composable
private fun rememberCopyPut(): (String) -> Unit {
    val clipboard = LocalClipboardManager.current
    val latest = rememberUpdatedState(clipboard)
    return { latest.value.setText(AnnotatedString(it)) }
}

/** 轮询间隔（与命令面 `LIVE_POLL_INTERVAL_MILLIS` 同值：手感与成本的折中）。 */
private const val LOG_WINDOW_POLL_MILLIS = 400L

/** 轮询上限（与 `LIVE_POLL_MAX_TICKS` 同值 ≈ 4 分钟；宿主句柄泄漏时界面不无限问）。 */
private const val LOG_WINDOW_MAX_TICKS = 600
