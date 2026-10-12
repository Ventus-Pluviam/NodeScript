package com.autoscript.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.launch

/**
 * 管理页里「面板 ⇄ 子页 / 子页 ⇄ 子页」那一层切换的动画，**逐值复刻 Telegram**
 * （`ActionBarLayout.startLayoutAnimation`，v12.10.6）。
 *
 * ## 抄了哪几个数
 *
 * | 维度 | TG 的值 | 源位置（`TMessagesProj/src/main/java/org/telegram/ui/ActionBar/ActionBarLayout.java`） |
 * |---|---|---|
 * | 时长 | **150ms** | `:1830` `float duration = preview && open ? 190.0f : 150.0f;` |
 * | 插值 | `DecelerateInterpolator(1.5f)` | `:614` —— 以 1.5 的张力减速，**不是** Material 的 `FastOutSlowIn` |
 * | 切入位移 | `48dp → 0` | `:1892` `containerView.setTranslationX(dp(48) * (1.0f - interpolated))` |
 * | 切入淡入 | `alpha 0 → 1` | `:1877` `setAlpha(clampedInterpolated)`，初值在 `:2215` `setAlpha(0.0f)` |
 * | 切出位移 | `0 → 48dp` | `:1907` `containerViewBack.setTranslationX(dp(48) * interpolated)` |
 *
 * **底层页只淡、不位移**（2026-10-12 用户口径）。TG 里底层是 `containerViewBack`，
 * 切入期间它的 `setAlpha` 根本没被调用 —— 变暗是我们加的这一档，取「盖住时压暗」
 * 这个可见效果，值 0.6。
 *
 * ## 两处**有意不等**于 TG（写在 KDoc 里，不藏着）
 *
 * 1. **插值用 Bezier 近似**而非 `DecelerateInterpolator(1.5f)` 本身。那是 View 体系的
 *    `Interpolator` 实现，Compose 侧无对等物；`(0f, 0f, 0.2f, 1f)` 是它的三次曲线近似。
 *    手感差在曲率中段、不差在形状 —— 与其自写一个插值器，不如用 Compose 原生的。
 * 2. **不做 TG 的逐帧 `dt` 钳制**（`:1824-1828`）。那是 View 体系手搓动画循环时的保护
 *    （首帧可能带着布局耗时）。Compose 的动画时钟不吃这种尖峰。
 *
 * ## 为什么是两层而不是一层
 *
 * 离场动画要求**旧页在动画期间仍在树上** —— 直接把 `page` 换掉，旧页当场被删，
 * 就没有「退出去」这回事了。所以常驻两层：[under] 是底下那页，[top] 是盖在上面那页
 * （`null` = 没有覆盖层），`progress` 是盖在上面的那层的**在场程度**（0 = 全透明，
 * 1 = 完全就位）。切入与切出复用同一条公式，因为 `progress` 走的方向相反：
 *
 * ```
 * 底层 alpha = 1 - 0.4 * progress
 * 顶层 alpha = progress ，translationX = (1 - progress) * 48dp
 * ```
 *
 * 深度不超过两层（面板 → 子页 → 子页，子页里没有第三层），所以两个槽位够用；
 * 真要三层，`under`/`top` 得换成栈。
 *
 * @param page 要显示的那一层。**动画的输入，不是状态**。
 * @param content 画一层。过渡期间会被调用两次（底 + 顶），**必须无副作用**。
 */
@Composable
internal fun <T> ManagementTransition(
    page: T,
    content: @Composable (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    // `under` 是盖在底下的那一层，`top` 是盖在上面的（null = 没有覆盖层）。
    //
    // **为什么不能用「当前显示的那层」当底层**：从审计返回依赖管理时，`page` 变回
    // 依赖管理，而它此刻正是 `top`。若底层也画它，用户会看到同一个页面淡出又淡入 ——
    // 两次绘制同一个内容。`under` 存的是「`top` 之前那一层」，返回时它已经在那儿了。
    var under by remember { mutableStateOf(page) }
    var top by remember { mutableStateOf<T?>(null) }
    // 1 = 上层完全就位（初值：首帧没有覆盖层，直接就位，不做进场动画）。
    val progress = remember { Animatable(1f) }
    // 跟手返回手势的瞬时量（`ManagementBackGesture` 写、`graphicsLayer` 读）。
    val gesture = remember { BackGestureState() }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    var viewportHeight by remember { mutableFloatStateOf(0f) }

    // 侧缘滑动：手势期间只写 `gesture`（不重组），抬手后走与返回键同一条收尾路。
    ManagementBackGesture(
        state = gesture,
        enabled = top != null,
        onCommit = { closeTop(progress, gesture) { top = null } },
    )
    // **返回键**这一路。走 `BackHandler` 而不是继续用外壳那个 `ManagementBackHandler`：
    // 后者在切页动画期间会 `enabled = false`，而这里要保证「顶层开着就一定能退」。
    BackHandler(enabled = top != null) {
        scope.launch { closeTop(progress, gesture) { top = null } }
    }

    LaunchedEffect(page) {
        if (page == top) return@LaunchedEffect
        if (page == under) {
            // 切出（返回）：上层滑出去，底下那层原样回来。
            progress.snapTo(1f)
            progress.animateTo(0f, tween(DURATION_MS, easing = EASE))
            top = null
            progress.snapTo(1f)
        } else {
            // 切入：新页盖上来，原来显示的那层退到 `under`。
            under = top ?: under
            top = page
            progress.snapTo(0f)
            progress.animateTo(1f, tween(DURATION_MS, easing = EASE))
        }
        // 稳态下不留任何手势残留（TG 的 `animateBackEndAnimation` 收尾同样清零）。
        gesture.reset()
    }

    // 视口高（锚点退化时用）：`onSizeChanged` 给的是 px，与 `touchY` 同坐标系。
    //
    // `fillMaxSize` 不能省：两个子层都是 `matchParentSize`，而 `matchParentSize`
    // **不参与父级尺寸计算** —— 不显式撑满的话，这一层会塌成 0×0。
    Box(modifier.fillMaxSize().onSizeChanged { viewportHeight = it.height.toFloat() }) {
        // 没有覆盖层时 progress 恒为 1，所以**压暗要以「有没有 top」为前提** ——
        // 直接按 progress 算会让稳态下的页面一直是 0.6 透明度。
        val t = if (top == null) 0f else progress.value
        // 底层：**只淡**（不位移，用户口径照 TG 原文）。压暗 0.4 是「盖住时压暗」那一档。
        Box(Modifier.alpha(1f - DIM * t).matchParentSize()) { content(under) }
        top?.let { over ->
            Box(
                Modifier
                    .alpha(progress.value)
                    .graphicsLayer {
                        // **两段相加**：进出场那一段（48dp·(1-progress)）与跟手那一段
                        // （手势的 dx）互不干涉 —— 手势期间 `progress` 恒为 1，
                        // 动画期间 `dx` 恒为 0（`gesture.reset()` 在稳态清零）。
                        translationX = (1f - progress.value) * SLIDE_DP * density + gesture.dx
                        // 缩放 + 锚点：TG `:1119`/`:1133`，锚点 x 固定在左缘 82dp，
                        // y 取手指位置（没有触点时退到视口中线）。
                        val sc = backGestureScale(gesture.dx, progress.value, density)
                        if (sc != 1f) {
                            // 锚点用 `graphicsLayer` 作用域里的 `this`：这里 `density` 是
                            // `Density`，`size` 是该层像素尺寸 —— 与 `touchY` 同坐标系。
                            scaleX = sc
                            scaleY = sc
                            transformOrigin = TransformOrigin(
                                Density.toPx(SCALE_ANCHOR_X, density) / size.width,
                                if (viewportHeight > 0f) {
                                    backGestureAnchorY(gesture.touchY, viewportHeight) / viewportHeight
                                } else {
                                    0.5f
                                },
                            )
                        }
                    }
                    .matchParentSize(),
            ) {
                content(over)
            }
        }
    }
}

/**
 * 顶层退场：播 150ms 收尾后把 `top` 清掉。
 *
 * **无类型参数**：`T` 推不出来是因为这个函数根本不碰页面类型 —— 清空顶层由
 * 调用点的闭包 `clear` 做。带着一个用不到的 `<T>` 只会让三个调用点全部 `Cannot
 * infer type for T`。
 *
 * **松手时不是这里调的** —— 侧缘滑动那条路由 [ManagementBackGesture] 的流收尾，
 * 与 TG 一致（`animateBackEndAnimation`）。本函数是**返回键**与**编程切走**
 * （`page` 变回 `under`）共用的那条路。
 */
private suspend fun closeTop(
    progress: Animatable<Float, *>,
    gesture: BackGestureState,
    clear: () -> Unit,
) {
    progress.snapTo(1f)
    progress.animateTo(0f, tween(DURATION_MS, easing = EASE))
    clear()
    progress.snapTo(1f)
    gesture.reset()
}

private const val DURATION_MS = 150

/** 切入/切出的横向位移（TG `ActionBarLayout.java:1892` 的 `dp(48)`）。 */
private const val SLIDE_DP = 48

/** 底层页被盖住时压暗到 0.6（见 KDoc「底层页只淡、不位移」）。 */
private const val DIM = 0.4f

/** Android `DecelerateInterpolator(1.5f)` 的 Bezier 近似（见 KDoc「两处有意不等于 TG」）。 */
private val EASE = CubicBezierEasing(0f, 0f, 0.2f, 1f)
