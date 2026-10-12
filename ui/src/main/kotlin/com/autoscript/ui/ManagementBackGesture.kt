package com.autoscript.ui

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity

/**
 * 管理子页的**跟手返回手势** —— 逐值复刻 Telegram 的预测式返回
 * （`ActionBarLayout.onBackStarted` / `onBackProgress` / `draw`，v12.10.6）。
 *
 * 用户 2026-10-12 口径：要**跟手**（手指滑动过程中窗口就变小），不是松手后才播动画。
 *
 * ## 抄的数
 *
 * | 维度 | 值 | 源（`ActionBarLayout.java`） |
 * |---|---|---|
 * | 跟手位移 | `dp(56) · StandardDecelerate(t)` | `:1570` |
 * | `StandardDecelerate` | `PathInterpolator(0,0,0,1)` | `CubicBezierInterpolator.java:22` |
 * | 缩放 | `lerp(1.00, lerp(0.90, 0.85, 1 - alpha), clamp01(dx / dpf2(56)))` | `:1119` |
 * | 缩放锚点 x | 左缘 `dp(82)` | `:1133` |
 * | 缩放锚点 y | `predictiveBackY`（**手指按下的 Y**） | `:1133` |
 * | 启动门 | 栈深 > 1 且 `canBeginSlide()`，且当前无动画 | `:1526-1552` |
 *
 * `dpf2(x)` 就是 `density · x`（`AndroidUtilities.java:2740`）；`lerp(a,b,f)` 就是
 * `a + f·(b - a)`（`:5061`）。两处都在 [backGestureScale] 里展开成算术 —— 不引 TG 的工具类。
 *
 * **dp 常量存的是裸 `Float` 而不是 `Dp`**：这条路径全程只在 px 之间算（`touchY` /
 * `size.width` / `dx` 都是 px），不需要 `Dp` 类型带来的换算封装；真要用 `Dp` 就得引
 * `Density.toPx` —— 它在 Kotlin 侧是**内联扩展**（javap 看到的是 `toPx-0680j_4` 那个
 * 名字修饰的接口默认成员，顶层根本调不到）。存 `dp` 数、乘一次 `density`，更直白。
 *
 * ## 与 [ManagementTransition] 的分工
 *
 * 那一位管**点返回键 / 子页之间互切**的 150ms 进出场；这里只管**侧缘滑动手势**，
 * **只写状态，不碰动画时钟**。松手后的收尾仍由 `ManagementTransition` 播 150ms
 * （TG 那边也是另算时长、不复用 `startLayoutAnimation`，见 `:1608`）。
 *
 * @param state 手势瞬时量，由 `ManagementTransition` `remember` 并传进来。
 * @param enabled 顶层有没有可退的那一层。
 * @param onCommit 抬手后调一次（挂起）——播 150ms 收尾并清掉顶层。
 */
@Composable
internal fun ManagementBackGesture(
    state: BackGestureState,
    enabled: Boolean,
    onCommit: suspend () -> Unit,
) {
    val density = LocalDensity.current.density
    PredictiveBackHandler(enabled = enabled) { flow ->
        flow.collect { event ->
            // 系统已判过 edge（`BackEventCompat.swipeEdge`），这里只取进度与触点。
            state.dx = BACK_GESTURE_DP * density * BACK_EASE.transform(event.progress)
            state.touchY = event.touchY
            state.swiping = true
        }
        // 流结束 = 抬手。此后交给 `ManagementTransition` 的 150ms 收尾
        // （TG 那边对应 `animateBackEndAnimation`，`:1596`），本文件不再写瞬时量。
        state.swiping = false
        onCommit()
    }
}

/**
 * 手势驱动的那几个瞬时量。由 `ManagementTransition` `remember` 一次，读进 `graphicsLayer`。
 *
 * **`dx`/`touchY` 是普通 `var`，`swiping` 才是状态**：前两个每帧都改，改的是
 * `graphicsLayer` 的 lambda —— 绘制阶段重读即可，**不需要重组**。做成 `mutableStateOf`
 * 会让整页内容每帧重组一遍。
 */
internal class BackGestureState {
    /** 跟手位移（px），= `dp(56) · StandardDecelerate(t)`（TG `:1570`）。 */
    var dx: Float = 0f

    /** 手指 Y（px）—— TG 的 `predictiveBackY`，缩放锚点用它（`:1133`）。 */
    var touchY: Float = Float.NaN

    /** 手势进行中（流未结束）。稳态与收尾动画期间为 false。 */
    var swiping by mutableStateOf(false)

    /** 松手/取消/进出切换后把瞬时量清零，回到稳态。 */
    fun reset() {
        dx = 0f
        touchY = Float.NaN
        swiping = false
    }
}

/** 跟手位移的满量程，单位 dp（TG `:1570` 的 `dp(56)`）。 */
private val BACK_GESTURE_DP = 56f

/** `CubicBezierInterpolator.StandardDecelerate` = `PathInterpolator(0f, 0f, 0f, 1f)`。 */
private val BACK_EASE = androidx.compose.animation.core.CubicBezierEasing(0f, 0f, 0f, 1f)

/** 缩放公式里的两个端点（TG `:1119` 的 `0.90f` / `0.85f`）。 */
private const val SCALE_MID = 0.90f
private const val SCALE_FAR = 0.85f

/** 缩放锚点距左缘的距离，单位 dp（TG `:1133` 的 `dp(82)`）。 */
internal const val SCALE_ANCHOR_X = 82f

/**
 * TG 的缩放公式（`ActionBarLayout.java:1119`）逐值展开：
 * `lerp(1.00, lerp(0.90, 0.85, 1 - alpha), clamp01(dx / dpf2(56)))`。
 *
 * @param dxPx 跟手位移（px）
 * @param alpha 当前页透明度（1 = 完全就位）
 * @param density 屏幕密度（`dpf2` 要的就是它）
 */
internal fun backGestureScale(dxPx: Float, alpha: Float, density: Float): Float {
    val t = (dxPx / (56 * density)).coerceIn(0f, 1f)
    val inner = SCALE_MID + (SCALE_FAR - SCALE_MID) * (1f - alpha)
    return 1f + (inner - 1f) * t
}

/** 缩放锚点的 Y（px）：TG 用手指位置；没有触点时退到垂直居中。 */
internal fun backGestureAnchorY(touchY: Float, viewportHeight: Float): Float =
    if (touchY.isNaN()) viewportHeight / 2f else touchY
