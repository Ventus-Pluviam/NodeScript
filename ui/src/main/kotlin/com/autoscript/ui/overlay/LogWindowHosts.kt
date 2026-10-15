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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.autoscript.ui.components.TabBarBottomClearance
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
 * （与 toast 同一条让位口径）。内容与系统窗同一份 `LogWindowContent`。
 * **复制不由宿主接**（2026-10-15 起）：日志块包在 `SelectionContainer` 里，
 * 复制走系统的选择菜单 —— 两个宿主因此都不需要剪贴板回调。
 */
@Composable
fun LogWindowInAppHost(
    state: LogWindowState,
    lines: List<ConsoleLineState>,
    onFirstAction: () -> Unit,
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
                    onFirstAction = onFirstAction,
                    onClear = { onClear(shown.maxOfOrNull { it.seq } ?: state.watermarkSeq) },
                    onClose = onClose,
                    onFullscreenToggle = onFullscreenToggle,
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
 * 系统窗同步效应（无 UI）：state 非空 + 走系统窗 → `show` 同一份内容。
 *
 * **`show` 只在「开窗」与「改画态」时跑，日志行刷新不再经过它**（2026-10-14
 * 「点执行就闪退」）：`show` 对已存在的 view 走的是 `ComposeView.setContent`，
 * 而轮询 400ms 一轮、每轮 `ConsoleState.of` 都产出**新的 `List` 实例** —— 把它
 * 算进效应键就等于每 400ms 重建一次离屏组合（`setContent` 期间抛出的任何东西
 * 都会从组合作用域冒到进程）。行数据改走 `controller.lines`（Compose 状态，
 * 窗内**现读**），刷新是普通重组，`setContent` 的执行次数 = 开窗次数。
 *
 * **位置与内容分两条效应**（2026-10-13）：拖动只改 `x/y`，那条只调
 * `updateViewLayout`；若把位置也算进内容键，拖动每一帧都会重跑一遍 `setContent`
 * （整列日志白重组一次）—— 窗越大越卡，表现为"拖起来一顿一顿"。
 *
 * 暂停按钮的协程入口由调用方包（`togglePause` 是挂起的，效应里 `launch`）。
 * **复制不经本效应**：日志块的选择/复制由 `SelectionContainer` 自己管（见
 * `LogWindowContent` 的「日志可拖选」），离屏 `ComposeView` 里同样能用 ——
 * 系统的选择菜单是平台面，不依赖 `LocalToast`/`LocalClipboardManager`。
 */
@Composable
fun SystemWindowSync(
    controller: LogWindowController,
    onFirstAction: () -> Unit,
) {
    val state = controller.state
    val host = controller.systemHost
    val density = LocalDensity.current
    // **内容与位置分两条效应**：内容键里**没有位置** —— 拖动每帧都改 x/y，
    // 若算进内容键就会每帧重跑 `setContent`（整列日志白重组一次），窗越大越卡。
    // 位置那条只调 `updateViewLayout`（便宜）。
    //
    // 内容效应里落位时读的是 `controller.state`（**现取**）而不是本轮捕获的那一份：
    // 键里没有位置，捕获值可能停在拖动之前 —— 拿它去 `show` 会把窗拽回旧位置，
    // 与位置效应打架（表现为"拖一下弹回去"）。
    //
    // **键里也没有 `lines`**（见本函数 KDoc）：行刷新由窗内组合现读 `controller.lines`。
    LaunchedEffect(state?.boundRunId, state?.watermarkSeq, state?.paused, state?.fullscreen, controller.usingSystemWindow) {
        if (state == null || host == null || !controller.usingSystemWindow) return@LaunchedEffect
        val now = controller.state ?: return@LaunchedEffect
        val ok = host.show(
            {
                // **窗内现读**（不是把本轮的行快照喂进来）：`lines` 是 Compose 状态，
                // 轮询推新值时这里自动重组 —— 于是 `show` 只需在开窗/改画态时跑一次。
                //
                // **`state` 也必须现读**（2026-10-15 用户报「执行完毕暂停键没变执行键」）：
                // 外面那个 `val state` 是**捕获值**，只在上面那条效应的**键变了**时才会更新
                // —— 而「脚本跑完」恰好一个键都不变（`boundRunId` 结算不解绑、
                // `paused`/`watermarkSeq`/`fullscreen` 都不动），于是离屏窗永远停在旧态：
                // 标题不缀「· 已结束」、第一颗钮还画着暂停。窗内**现读** `controller.state`
                // 把它变成一次 Compose 状态读，任何字段变（含 `running`）都会让离屏组合重组。
                // 与 `lines` 同一条纪律，也与上面位置效应那句「落位时现取」同一理由。
                val live = controller.state ?: state
                val shown = live.visible(controller.lines)
                LogWindowContent(
                    state = live,
                    lines = shown,
                    onFirstAction = onFirstAction,
                    onClear = { controller.clearLogs(shown.maxOfOrNull { it.seq } ?: live.watermarkSeq) },
                    onClose = { controller.close() },
                    onFullscreenToggle = { controller.toggleFullscreen() },
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

/** 轮询间隔（与命令面 `LIVE_POLL_INTERVAL_MILLIS` 同值：手感与成本的折中）。 */
private const val LOG_WINDOW_POLL_MILLIS = 400L

/** 轮询上限（与 `LIVE_POLL_MAX_TICKS` 同值 ≈ 4 分钟；宿主句柄泄漏时界面不无限问）。 */
private const val LOG_WINDOW_MAX_TICKS = 600
