package com.autoscript.ui.overlay

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.autoscript.ui.components.TabBarBottomClearance
import com.autoscript.ui.components.rememberCopyAction
import com.autoscript.ui.state.ConsoleLineState
import kotlinx.coroutines.delay

/**
 * 执行日志悬浮窗的**两个宿主**（2026-10-13 用户令）。
 *
 * - **[LogWindowInAppHost]**：`MainShell` 里 ToastHost 旁的 Box 浮层（pager 外，
 *   过导航存活；无权限时的兜底）。**也跟手拖**（2026-10-13 用户反馈"不跟手"）：
 *   它是"窗"不是"面板"，位置与系统窗共用同一份状态（`LogWindowState.x/y`），
 *   切宿主不跳位。边界取它自己量到的 Box 尺寸（应用内容区，不是屏幕）。
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
 * 位置：紧凑态 = 左上起算的 `state.x/y`（**可拖**，跟手）；全屏态 = 铺满内容区。
 * 让位与夹取同一套坐标：外层先让出顶栏与底栏/导航栏，位置与边界都算在让完位的内容区里
 * （与 toast 同一条让位口径）。内容与系统窗同一份 `LogWindowContent`
 * （复制走 `rememberCopyAction` 那套）。
 */
@Composable
fun LogWindowInAppHost(
    state: LogWindowState,
    lines: List<ConsoleLineState>,
    onPauseToggle: () -> Unit,
    onClear: (Long) -> Unit,
    onClose: () -> Unit,
    onFullscreenToggle: () -> Unit,
    /**
     * 拖动位移（dp；**屏幕/内容坐标系里的真实位移**）+ 本宿主量到的可视边界（dp；0 = 未知）。
     *
     * 边界由**宿主**给（它才知道自己的坐标系多大：系统窗 = 屏幕可用区、in-app = 内容区），
     * 夹取由状态层做（它才知道窗多宽）—— 两边各答一半，谁都不抄对方的数。
     */
    onDrag: (dx: Float, dy: Float, boundsW: Float, boundsH: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val copy = rememberCopyAction()
    val shown = state.visible(lines)
    val density = LocalDensity.current
    // 兜底窗也**跟手**（2026-10-13 用户反馈"不跟手"）：它是"窗"不是"面板"，
    // 无权限时用户同样要能把它挪开。
    //
    // **让位与夹取用同一个坐标系**：外层 Box 先让出状态栏与底栏/导航栏
    // （`TabBarBottomClearance`），窗的位置与边界都算在**这块让完位的内容区**里 ——
    // 于是"夹在可视区内"与"不让底栏盖住"是同一件事，不会一个按屏幕算、一个按让位后算。
    //
    // 顶边让的是 `WindowInsets.statusBars` 而**不是**写死的 56dp：系统窗那边的
    // `y = 0` 就是"可用区顶边"（见 `SystemLogWindowHost.topOffsetPx`），两边差一个
    // 状态栏高度就会在切宿主时跳一下。底边那一条不用 inset 而用 `TabBarBottomClearance`
    // —— 它已经把导航栏算进去了（见其 KDoc），再加一次就是双份。
    // `align` 是 `BoxScope` 的扩展，只能在 Box 内部的**子项**上调 —— 不能拼在传给
    // `Box(...)` 的 modifier 链上（编译期抓到）。
    var boxW by remember { mutableFloatStateOf(0f) }
    var boxH by remember { mutableFloatStateOf(0f) }
    Box(modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(bottom = TabBarBottomClearance())
                .onSizeChanged {
                    with(density) {
                        boxW = it.width.toDp().value
                        boxH = it.height.toDp().value
                    }
                },
        ) {
            Box(
                if (state.fullscreen) {
                    Modifier.fillMaxSize()
                } else {
                    Modifier
                        .width(LogWindowState.COMPACT_WIDTH_DP.dp)
                        .height(LogWindowState.COMPACT_HEIGHT_DP.dp)
                        .align(Alignment.TopStart)
                        .offset(x = state.x.dp, y = state.y.dp)
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
                    onDrag = { d ->
                        // 手势层给的是屏幕上的真实位移（见 `LogWindowContent.dragHandle`
                        // 的 KDoc），这里只换成 dp 交给状态层；夹取由状态层按本宿主
                        // 量到的边界做。
                        with(density) { onDrag(d.x.toDp().value, d.y.toDp().value, boxW, boxH) }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/**
 * 系统窗同步效应（无 UI）：state 非空 + 走系统窗 → `show` 同一份内容；
 * 改画的字段变 → 重 `show`（内容层无状态，全由参数进，重喂即刷新）。
 *
 * **位置与内容分两条效应**（2026-10-13）：拖动只改 `x/y`，那条只调
 * `updateViewLayout`；若把位置也算进内容键，拖动每一帧都会重跑一遍 `setContent`
 * （整列日志白重组一次）—— 窗越大越卡，表现为"拖起来一顿一顿"。
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
    val put = rememberCopyPut()
    // **内容与位置分两条效应**：内容键里**没有位置** —— 拖动每帧都改 x/y，
    // 若算进内容键就会每帧重跑 `setContent`（整列日志白重组一次），窗越大越卡。
    // 位置那条只调 `updateViewLayout`（便宜）。
    //
    // 内容效应里落位时读的是 `controller.state`（**现取**）而不是本轮捕获的那一份：
    // 键里没有位置，捕获值可能停在拖动之前 —— 拿它去 `show` 会把窗拽回旧位置，
    // 与位置效应打架（表现为"拖一下弹回去"）。
    LaunchedEffect(state?.boundRunId, state?.watermarkSeq, state?.paused, state?.fullscreen, lines, controller.usingSystemWindow) {
        if (state == null || host == null || !controller.usingSystemWindow) return@LaunchedEffect
        val shown = state.visible(lines)
        val now = controller.state ?: return@LaunchedEffect
        val ok = host.show(
            {
                LogWindowContent(
                    state = state,
                    lines = shown,
                    onPauseToggle = onPauseToggle,
                    onClear = { controller.clearLogs(shown.maxOfOrNull { it.seq } ?: state.watermarkSeq) },
                    onClose = { controller.close() },
                    onFullscreenToggle = { controller.toggleFullscreen() },
                    onCopyLine = { put(it) },
                    // 拖动走**状态层**（`controller.drag`）而不是直连宿主：状态层知道窗多大、
                    // 屏幕多宽，夹取只有在那里做才不用把尺寸抄两份；宿主只把算好的坐标落到窗口上。
                    // 窗内的坐标是 px（离屏 ComposeView 的密度与主界面同一台设备，故换算同源）。
                    onDrag = { d ->
                        with(density) { controller.drag(d.x.toDp().value, d.y.toDp().value) }
                    },
                )
            },
            now.x,
            now.y,
        )
        if (!ok) controller.fallbackToInApp()
    }
    // 位置效应：拖动与"沿用上次位置"都从这里落位（开窗那一下内容效应已经摆好了，
    // 这里是后续每一次移动）。
    LaunchedEffect(state?.x, state?.y, controller.usingSystemWindow) {
        val s0 = state ?: return@LaunchedEffect
        if (host == null || !controller.usingSystemWindow) return@LaunchedEffect
        host.moveTo(s0.x, s0.y)
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
