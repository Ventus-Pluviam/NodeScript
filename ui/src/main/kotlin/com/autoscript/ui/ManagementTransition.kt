package com.autoscript.ui

import android.os.Build
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.coroutineScope
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
 * ## 顶层位移的三个来源（互不干涉，直接相加）
 *
 * | 来源 | 表达式 | 什么时候非零 |
 * |---|---|---|
 * | 进出场 | `(1 - progress) · 48dp` | 切入 / 返回键切出 |
 * | 跟手 | `gesture.dx` | 侧缘滑动期间 |
 * | 提交后飞出 | `dismiss · (屏宽 - dx)` | 手势越过阈值松手后 |
 *
 * 三者不会同时非零：跟手期间 `progress` 恒为 1、`dismiss` 恒为 0；进出场期间
 * `dx` 与 `dismiss` 都是 0。飞出那一项减掉 `dx`，终点正好落在屏宽 —— 与 TG
 * `animateBackEndAnimation(false)` 里 `TRANSLATION_X → measuredWidth` 同构
 * （`:1607` 的 `distToMove = measuredWidth - x`）。
 *
 * 深度不超过两层（面板 → 子页 → 子页，子页里没有第三层），所以两个槽位够用；
 * 真要三层，`under`/`top` 得换成栈。
 *
 * @param page 要显示的那一层。**动画的输入，不是状态**。
 * @param active 管理页是不是 pager 当前停稳的那一页。**预组合的邻页必须传 false** ——
 *   否则邻页里那个 `BackHandler` 会替前台页签吞掉返回键（pager 预组合两页，
 *   `BackHandler` 的 LIFO 顺序由组合顺序定，管不了「哪一页真在前台」）。
 * @param onBackRequest 请宿主把当前子页关掉（把 `page` 置回上一层）。手势提交后由本组件
 *   调一次 —— 本组件**不自己改 `top`**，否则 `top` 会与 `page` 脱节、上层凭空消失。
 * @param content 画一层。过渡期间会被调用两次（底 + 顶），**必须无副作用**。
 */
@Composable
internal fun <T> ManagementTransition(
    page: T,
    active: Boolean,
    /** 动画进行中（切入/切出）—— TG `animationInProgress`，面板侧用它吞新入口点击。 */
    onAnimatingChanged: (Boolean) -> Unit = {},
    onBackRequest: () -> Unit,
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
    // 手势提交后「飞出去」的进度 0→1（TG `animateBackEndAnimation(false)`，`:1606-1618`）。
    val dismiss = remember { Animatable(0f) }
    // 跟手返回手势的瞬时量（`ManagementBackGesture` 写、`graphicsLayer` 读）。
    val gesture = remember { BackGestureState() }
    // 本次切出是不是「手势提交」造成的 —— 决定切出动画走 380ms 飞出还是 150ms 滑出。
    // 不在组合里读，只在 `LaunchedEffect` 里读，所以不引发重组。
    val committed = remember { mutableStateOf(false) }
    val density = LocalDensity.current.density
    var viewportHeight by remember { mutableFloatStateOf(0f) }
    var viewportWidth by remember { mutableFloatStateOf(0f) }
    // 顶层要不要画圆角裁剪/阴影。**只在「有没有盖层」这个布尔上推导**（快照态），
    // 手势推进度时不动 —— 否则每帧重组合去算一个不变的值，正是「首次点击卡」的形状。
    val framed = remember { derivedStateOf { top != null } }.value
    // 圆角半径（px）—— TG 在 `drawChild` 里取屏幕四角 `RoundedCorner` 半径做 round-rect
    // 裁剪（`ActionBarLayout.java:1136-1155`），这是**屏幕圆角**不是我们自己定的数。
    // 布局跑过一遍之后（`viewportWidth` 非 0）用真实半径；布局没到先 0 兜底，
    // 布局一到 `remember` 换 key 重算。`LocalView.current` 是 @Composable 取子，
    // 必须在 `remember` **外面**读（里面调用会报「只能在 @Composable 上下文里调」）。
    val localView = LocalView.current
    val cornerShape = remember(viewportWidth, viewportHeight, density) {
        val r = if (viewportWidth <= 0f || viewportHeight <= 0f) {
            0f
        } else {
            screenCornerRadiusPx(localView)
        }
        // `CornerSize(float)` 是 **PxCornerSize**（像素，不是 dp）—— `toPx` 原样返回。
        RoundedCornerShape(CornerSize(r))
    }

    // **返回键与侧缘手势合走一条路**：TG 也是一个 `OnBackAnimationCallback` 管
    // started / progressed / cancelled / invoked 四态（`LaunchActivity.java:736-818`）。
    // `PredictiveBackHandler` 的流在「手势提交」与「普通返回键」两种情况下都会结束，
    // 所以**不要再挂一个 `BackHandler`** —— 它排在后面注册就会把手势整个吃掉
    // （`OnBackPressedDispatcher` 是 LIFO，后注册的优先）。这正是本批修的主因：
    // 外壳那个 `ManagementBackHandler` 组合在 `ManagementTransition` 之后，
    // 于是 `PredictiveBackHandler` 一次都没被回调过。
    //
    // `active` 这道门不能省：pager 预组合邻页，邻页里这个 handler 会替前台页签吞返回。
    ManagementBackGesture(
        state = gesture,
        enabled = active && top != null,
        onCommit = {
            // **不在这里自己清 `top`**：那会让 `top` 与 `page` 脱节（`page` 还指着子页、
            // `top` 已经是 null），下一帧的画面上层突然消失。改成「标一个旗标 + 请宿主
            // 关掉子页」——`page` 随之变成 `under`，`LaunchedEffect` 走切出分支，
            // 在那里按旗标选飞出还是 150ms 滑出。这与 TG「`animateBackEndAnimation`
            // 动画结束后才 `onSlideAnimationEnd` 改栈」是同一种次序。
            committed.value = true
            onBackRequest()
        },
    )

    LaunchedEffect(page) {
        if (page == top) return@LaunchedEffect
        if (page == under) {
            // 切出。**手势提交**与**返回键**走两条不同的路：
            if (committed.value) {
                // 手势提交：从手指当前位置继续飞到屏宽 + 淡出，380ms
                // （TG `animateBackEndAnimation(false)`，`:1606-1618`；
                // `:1608` 的 `newBackTransitions() ? 380 : 50` 取 380）。
                coroutineScope {
                    launch {
                        dismiss.snapTo(0f)
                        dismiss.animateTo(1f, tween(FLY_OUT_MS, easing = EASE))
                    }
                    launch {
                        progress.snapTo(1f)
                        progress.animateTo(0f, tween(FLY_OUT_MS, easing = EASE))
                    }
                }
            } else {
                // 返回键 / 编程切走：150ms 滑出（TG `startLayoutAnimation` 那一套）。
                progress.snapTo(1f)
                progress.animateTo(0f, tween(DURATION_MS, easing = EASE))
            }
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
        dismiss.snapTo(0f)
        committed.value = false
        onAnimatingChanged(false)
    }

    // 视口尺寸：`onSizeChanged` 给的是 px，与 `touchY` 同坐标系。
    //
    // `fillMaxSize` 不能省：两个子层都是 `matchParentSize`，而 `matchParentSize`
    // **不参与父级尺寸计算** —— 不显式撑满的话，这一层会塌成 0×0。
    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged {
                viewportHeight = it.height.toFloat()
                viewportWidth = it.width.toFloat()
            },
    ) {
        // 没有覆盖层时 progress 恒为 1，所以**压暗要以「有没有 top」为前提** ——
        // 直接按 progress 算会让稳态下的页面一直是 0.6 透明度。
        val t = if (top == null) 0f else progress.value
        // 底层：**只淡**（不位移，用户口径照 TG 原文）。压暗 0.4 是「盖住时压暗」那一档。
        Box(Modifier.alpha(1f - DIM * t).matchParentSize()) { content(under) }
        top?.let { over ->
            Box(
                Modifier
                    .alpha(progress.value * (1f - dismiss.value))
                    .graphicsLayer {
                        // 三段相加，互不干涉（见 KDoc「三个来源」）：
                        //   ① 进出场：(1-progress)·48dp
                        //   ② 跟手：gesture.dx
                        //   ③ 提交后的飞出：dismiss·(屏宽 - dx) —— 终点正好是屏宽，
                        //      与 TG 的 `TRANSLATION_X → measuredWidth` 一致。
                        translationX = (1f - progress.value) * SLIDE_DP * density +
                            gesture.dx +
                            dismiss.value * (viewportWidth - gesture.dx)
                        // 缩放：TG 在预测式返回期间 `containerView.getAlpha()` 恒为 1
                        // （`:1119` 的内层 `lerp(0.90, 0.85, 1 - alpha)` 因此取 0.90），
                        // 所以这里 alpha 传常量 1，不跟 `progress`/`dismiss` 走。
                        val sc = backGestureScale(gesture.dx, alpha = 1f, density)
                        if (sc != 1f) {
                            scaleX = sc
                            scaleY = sc
                            transformOrigin = TransformOrigin(
                                SCALE_ANCHOR_X * density / size.width,
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
                // 子页内容 + 屏幕圆角裁剪。裁剪用 [cornerShape] 盖住四角 —— 内容不是
                // `matchParentSize`（它不参与父级尺寸计算），直接用 `fillMaxSize`。
                Box(Modifier.fillMaxSize().clip(cornerShape)) {
                    content(over)
                }
            }
        }
        // 侧缘阴影（TG `layer_shadow`，`ActionBarLayout.java:1194-1201`）：画在**顶层左缘
        // 外侧**那一缕（`[x - shadowW, x]`），宽度固定。alpha 随顶层位移淡出 —— TG 是
        // `clamp(255·widthOffset/dp(20))`（`widthOffset = 屏宽 - x`）；顶层就位
        // （`x == 0`）时不画（TG 的 `translationX != 0` 门）。**画在两层之后**，
        // 与 TG「`super.drawChild` 之后画 shadow」同序。全程只在 graphicsLayer 的
        // lambda 里读瞬时量 —— 图层级失效，不引发重组。
        //
        // **为什么是「一条线」**：上一版把 `layer_shadow` 当成「一条不透明的灰条」画。
        // TG 的资源其实是**一张横向从透明渐到不透明的窄条**（webp：xhdpi 8×4 的
        // alpha 是 4→14→26→34→48，xxhdpi 12×6 是 4→14→26→36→50），单色实条画出来
        // 就是一条线。这里用 Compose 横向渐变复刻那张资源：起点透明、终点
        // `0x33000000`（~51/255，与 xxhdpi 右缘 50 同量级）。
        if (framed) {
            // 动画跑起来的**那一瞬间**就往上喊「开始动画」（`LaunchedEffect` 一进就设，
            // 首帧也就位了）。用 `LaunchedEffect` 而不是靠 `onAnimatingChanged` 每帧调：
            // 它只在 `framed` 翻成 true 时跑一次，不随进度重入。
            LaunchedEffect(Unit) {
                onAnimatingChanged(true)
            }
            val shadowX = (1f - progress.value) * SLIDE_DP * density + gesture.dx +
                dismiss.value * (viewportWidth - gesture.dx)
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = shadowX - EDGE_DP * density
                        alpha = if (shadowX != 0f) {
                            ((viewportWidth - shadowX) / (EDGE_ALPHA_DP * density)).coerceIn(0f, 1f)
                        } else {
                            0f
                        }
                    }
                    .drawWithContent {
                        // 渐变贴满这一层的宽，视觉上渐隐向右侧 —— 资源条水平铺开后正是
                        // 这个形状（最右一格 ≈ 满 alpha，左移一格就开始淡）。
                        drawRect(
                            brush = EDGE_BRUSH,
                            size = Size(EDGE_DP * density, size.height),
                            style = Fill,
                        )
                    },
            )
        }
    }
}

/**
 * 侧缘阴影的渐变：TG `layer_shadow` webp 的横向 alpha 递增（xhdpi 8×4 是
 * 4→14→26→34→48，xxhdpi 12×6 是 4→14→26→36→50）。起点透明、终点 `0x33000000`
 * （约 51/255，与 xxhdpi 右缘 50 同量级）。
 */
private val EDGE_BRUSH = Brush.horizontalGradient(
    colors = listOf(Color.Transparent, EDGE),
)

/** 盖层被遮住时的侧缘阴影终点色（TG `layer_shadow` 最右一格的黑）。 */
private val EDGE = Color(0x33000000)

/** 盖层侧缘阴影宽度（TG `layer_shadow` 的固有宽度）。 */
private const val EDGE_DP = 8f

/** 阴影淡出的分母：TG `clamp(255·widthOffset/dp(20))`（`ActionBarLayout.java:1192`）。 */
private const val EDGE_ALPHA_DP = 20f

private const val DURATION_MS = 150

/** 手势提交后的飞出时长（TG `ActionBarLayout.java:1608` 的 `newBackTransitions() ? 380`）。 */
private const val FLY_OUT_MS = 380

/** 切入/切出的横向位移（TG `ActionBarLayout.java:1892` 的 `dp(48)`）。 */
private const val SLIDE_DP = 48

/** 底层页被盖住时压暗到 0.6（见 KDoc「底层页只淡、不位移」）。 */
private const val DIM = 0.4f

/** Android `DecelerateInterpolator(1.5f)` 的 Bezier 近似（见 KDoc「两处有意不等于 TG」）。 */
private val EASE = CubicBezierEasing(0f, 0f, 0.2f, 1f)

/**
 * 屏幕四角的系统圆角半径（px）。TG 的裁剪用的就是
 * `insets.getRoundedCorner(POSITION_*)`（`ActionBarLayout.java:1136`）—— 这是
 * **设备的物理圆角**，不是我们自定数。API 31+ 才能问
 * `Display.getRoundedCorner(POSITION_).getRadius()`（比 `getRoundedCornerSize`
 * 更稳：窗口不在前台时那个 API 会抛），旧版本与拿不到 Display 时按 0 兜底。
 */
private fun screenCornerRadiusPx(view: View): Float {
    val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        runCatching { view.display }.getOrNull()
    } else {
        null
    }
    if (display == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return 0f
    // `getRoundedCorner(int)` 返回 `RoundedCorner?` —— 没圆角的屏/角返回 null，
    // 安全调用取半径，null 当 0。
    return runCatching {
        maxOf(
            display.getRoundedCorner(0)?.getRadius() ?: 0,
            display.getRoundedCorner(1)?.getRadius() ?: 0,
            display.getRoundedCorner(2)?.getRadius() ?: 0,
            display.getRoundedCorner(3)?.getRadius() ?: 0,
        )
    }.getOrDefault(0).toFloat()
}
