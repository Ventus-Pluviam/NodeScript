package com.autoscript.ui

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.CancellationException

/**
 * 管理子页的**跟手返回手势** —— 逐值复刻 Telegram 的预测式返回
 * （`LaunchActivity.OnBackAnimationCallback` → `ActionBarLayout.onBackStarted` /
 * `onBackProgress` / `draw`，v12.10.6）。
 *
 * 用户 2026-10-12 口径：要**跟手**（手指滑动过程中窗口就变小），不是松手后才播动画。
 *
 * ## 抄的数
 *
 * | 维度 | 值 | 源 |
 * |---|---|---|
 * | 跟手位移 | `dp(56) · StandardDecelerate(t)` | `ActionBarLayout.java:1570` |
 * | `StandardDecelerate` | `PathInterpolator(0,0,0,1)` | `CubicBezierInterpolator.java:22` |
 * | 缩放 | `lerp(1.00, lerp(0.90, 0.85, 1 - alpha), clamp01(dx / dpf2(56)))` | `:1119` |
 * | 缩放锚点 x | 左缘 `dp(82)` | `:1133` |
 * | 缩放锚点 y | `predictiveBackY`（**手指的 Y**） | `:1133` |
 * | 起步死区 | `LAZY_START = 0.015f`，之后按 `(p - 0.015) / 0.985` 重标 | `LaunchActivity.java:786-789` |
 *
 * `dpf2(x)` 就是 `density · x`（`AndroidUtilities.java:2740`）；`lerp(a,b,f)` 就是
 * `a + f·(b - a)`（`:5061`）。两处都在 [backGestureScale] 里展开成算术 —— 不引 TG 的工具类。
 *
 * **dp 常量存的是裸 `Float` 而不是 `Dp`**：这条路径全程只在 px 之间算（`touchY` /
 * `size.width` / `dx` 都是 px），不需要 `Dp` 类型的封装。真要用 `Dp` 就得引
 * `Density.toPx` —— 它在 Kotlin 侧是内联扩展（javap 只看到名字修饰过的接口默认成员），
 * 顶层根本调不到。存 dp 数、乘一次 `density`，更直白。
 *
 * ## 与 [ManagementTransition] 的分工
 *
 * 那一位管**返回键 / 子页之间互切**的 150ms 进出场；这里只管**侧缘滑动手势**，
 * **只写状态，不碰动画时钟**。松手后的收尾仍由 `ManagementTransition` 播
 * （TG 那边也是另算时长、不复用 `startLayoutAnimation`，见 `:1608`）。
 *
 * ## 前置条件（缺一个就整个不生效）
 *
 * 1. `AndroidManifest` 的 `<application>` 要有 `android:enableOnBackInvokedCallback="true"`
 *    —— TG 在 `TMessagesProj/src/main/AndroidManifest.xml:112` 正是这么写的。缺了它
 *    应用走的是旧的 `onBackPressed` 通路，本文件一次都不会被回调（**不报错、静默不生效**）。
 * 2. 系统导航方式必须是**手势导航**（三键导航没有预测式返回）。
 * 3. 注册优先级：`OnBackPressedDispatcher` 是 LIFO，**后注册的优先**。所以外壳那个
 *    `BackHandler` 必须排在 `ManagementTransition` 内部那个**之前**组合，否则它会把
 *    手势整个吃掉 —— 见 `MainActivity` 里那段注释。
 *
 * @param state 手势瞬时量，由 `ManagementTransition` `remember` 并传进来。
 * @param enabled 顶层有可退的那一层，**且管理页在前台**（预组合的邻页不许吞返回）。
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
        try {
            flow.collect { event ->
                // 系统已判过边缘（`BackEventCompat.swipeEdge`），这里只取进度与触点。
                // 起步死区照 TG：前 1.5% 的进度丢掉再重标，避免刚碰到边缘就跳一下。
                val p = (event.progress - LAZY_START).coerceAtLeast(0f) / (1f - LAZY_START)
                state.dx = BACK_GESTURE_DP * density * BACK_EASE.transform(p)
                state.touchY = event.touchY
                // TG `predictiveBackHasProgress = t > 0`（`:1572`），`newBackTransitions()`
                // 靠它才成立（`:1591`）。
                state.hasProgress = p > 0f
                // 从**哪一侧**滑的（`EDGE_LEFT`/`EDGE_RIGHT`）—— TG 的 `predictiveBackLeft`
                // 用触点 X 对屏幕中线判（`ActionBarLayout.onBackStarted:1548`）。它**只**用来
                // 镜像缩放锚点（`:1133`）：窗口恒朝右让（`setTranslationX(+dx)`），但锚点是
                // 「离**手指那条边** dp(82)」—— 右缘滑时若仍按离左边 82dp 取，缩放中心会跑到
                // 窗口外面，看着就是「右边空了一大片、窗也不对」。
                state.edge = event.swipeEdge
            }
            // 流正常结束 = 手势被**提交**（松手时越过了返回阈值）。此时**不清 `dx`**：
            // 顶层要从当前位置飞出去，`closeTop` 的进度动画叠在它上面 —— 与 TG
            // `animateBackEndAnimation(false)` 从当前 `translationX` 飞到屏宽同构。
            onCommit()
        } catch (e: CancellationException) {
            // 流被取消 = 手势**撤回**（没越过阈值就松手，或系统判负）。
            // TG 这里播 320ms 回弹（`animateBackEndAnimation(true)`），本仓直接归零 ——
            // 少一段回弹，但状态是对的；补回弹要在 `dx` 上再跑一条动画，见 KDoc「有意不等」。
            state.reset()
            throw e
        }
    }
}

/**
 * 手势驱动的那几个瞬时量。由 `ManagementTransition` `remember` 一次，只被
 * `graphicsLayer` 的 lambda 读。
 *
 * **必须是 `mutableFloatStateOf` 而不是普通 `var`**：`graphicsLayer {}` 的 block 只在
 * 它读到的快照状态变化时才重跑。普通 `var` 不产生失效通知 —— 手机会照常收到
 * `onBackProgressed`、`dx` 也确实被写了，但**画面上什么都不动**（不报错、静默不生效）。
 * 而只在这一个 lambda 里读它们，失效范围就只有那一层，**不会**让整页内容每帧重组。
 */
internal class BackGestureState {
    /** 跟手位移（px），= `dp(56) · StandardDecelerate(t)`（TG `:1570`）。 */
    var dx by mutableFloatStateOf(0f)

    /** 手指 Y（px）—— TG 的 `predictiveBackY`，缩放锚点用它（`:1133`）。 */
    var touchY by mutableFloatStateOf(Float.NaN)

    /** 从哪一侧滑的（`BackEventCompat.EDGE_LEFT`/`EDGE_RIGHT`）。**必须是 `Int`** ——
     *  `swipeEdge` 是 Int，这里若用 `mutableFloatStateOf` 收，赋值处直接类型不匹配。 */
    var edge by mutableIntStateOf(BackEventCompat.EDGE_LEFT)

    /**
     * 手势**已经推进过**（TG `predictiveBackHasProgress = t > 0`，`:1572`）。
     *
     * TG 用它 + `predictiveBackInProgress` 合出 `newBackTransitions()`（`:1591`），
     * 而这个布尔**决定两件差很大的事**：走哪条缩放公式（`:1119` vs `:1121`）、底层
     * 裁不裁（`:1163-1166`）。刚按下还没动的那一下 `t == 0`，此时 TG 用的仍是**旧的**
     * 编程式那条路，所以不能只看「有没有在滑」。
     */
    var hasProgress by mutableStateOf(false)

    /** 松手/取消/进出切换后把瞬时量清零，回到稳态。 */
    fun reset() {
        dx = 0f
        touchY = Float.NaN
        edge = BackEventCompat.EDGE_LEFT
        hasProgress = false
    }
}

/** 跟手位移的满量程，单位 dp（TG `:1570` 的 `dp(56)`）。 */
private val BACK_GESTURE_DP = 56f

/** `CubicBezierInterpolator.StandardDecelerate` = `PathInterpolator(0f, 0f, 0f, 1f)`。 */
private val BACK_EASE = CubicBezierEasing(0f, 0f, 0f, 1f)

/** 起步死区（TG `LaunchActivity.java:786` 的 `LAZY_START`）。 */
private const val LAZY_START = 0.015f

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
