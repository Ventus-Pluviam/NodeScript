package com.autoscript.ui

import android.os.Build
import android.view.View
import androidx.activity.BackEventCompat
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        // 只有**切出**走完才允许清掉覆盖层。切入走完后 `top` 要留着 —— 子页就位了、
        // 还在前台。上一版 `finally` 无条件 `top = null`，每次点进子页都是
        // 「闪一下动画然后自动退出」（切入动画播完 → finally → 顶层被清）。
        var exitFinished = false
        // **切入、切出都要置位**。上一版只在 `framed` 翻真时（也就是切入那一刻）喊过一次
        // `true`，而 `finally` 在切入收尾就喊了 `false` —— 于是**切出期间 `animating` 是
        // false**，面板入口全部可点，用户在退出动画里点新入口就又回到卡死那条路。
        // TG 的 `animationInProgress` 在整个动画窗口内恒为真（`ActionBarLayout:1660` 设、
        // 收尾清），这里对齐：进这段就置真、`finally` 里清。
        onAnimatingChanged(true)
        try {
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
                progress.snapTo(1f)
                exitFinished = true
            } else {
                // 切入：新页盖上来，原来显示的那层退到 `under`。
                under = top ?: under
                top = page
                progress.snapTo(0f)
                progress.animateTo(1f, tween(DURATION_MS, easing = EASE))
            }
        } finally {
            // **收尾一律不可取消** —— 这就是卡死的根因所在。
            //
            // `LaunchedEffect(page)` 在 `page` 再次变化时被**取消**；取消会丢掉 `finally`
            // 之后的代码，于是 `top` 留在旧值、`page` 已经指向别的页 —— 两者脱节后
            // `if (page == under)` / `else` 两支都不再命中，后续任何进出都推不动动画，
            // 表现就是**卡死**。
            //
            // TG 不会出这个事：它的 `animateBackEndAnimation` 回调（`ActionBarLayout:1660`）
            // 与栈变更是**同一个 AnimatorSet**里的相邻步骤，不存在「动画被外力掐断、
            // 栈没跟上」这个中间态。这里用 `NonCancellable` 把两边焊在一起。
            withContext(NonCancellable) {
                // 切出走完才清覆盖层（协程被取消也会顺路走到这：`page` 已经换了，
                // 新的 `LaunchedEffect(page)` 会按新 `page` 重画）。切入走完不清 ——
                // 否则就是「闪一下自动退出」。
                if (exitFinished) {
                    top = null
                    progress.snapTo(1f)
                }
                // 稳态下不留任何手势残留（TG 的 `animateBackEndAnimation` 收尾同样清零）。
                gesture.reset()
                dismiss.snapTo(0f)
                committed.value = false
                onAnimatingChanged(false)
            }
        }
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
        //
        // **底层要跟着窗口一起挪 + 被裁**（TG `drawChild`，`:1100-1104` 与 `:1158-1166`）：
        // 窗口朝右让出 `translationX`，底层只在 `[0, translationX]` 那一段露出来，
        // **且被裁到 `translationX + dp(1)`**。TG 另在 `:1203-1212` 给这段画一层
        // **scrim**（灰遮罩）：`Color.argb(120 · clamp01(widthOffset / width), 0, 0.8)`，
        // 盖住整段露出来的底层 —— 这才是你说的「旁边的灰色遮罩」，**不是那条窄边阴影**
        // （`layer_shadow` 只画在窗口左缘 8dp 内，是另一回事、上一版画成它所以不对）。
        //
        // 之前只做了 `alpha` 压暗：底层满宽铺着、不裁也不画 scrim，所以看到的是
        // 「窗缩在一边、另一边一大片压暗的底」而不是「旁边一层灰罩」。
        val underAlpha = 1f - DIM * progress.value
        // 顶层在父坐标系里的 x —— 底层裁剪、顶层位移、侧缘阴影**三处共用同一个数**
        // （TG 里它们都源自同一个 `innerTranslationX`，各写一遍必然漂）。
        val layerX = topLayerX(progress.value, gesture.dx, dismiss.value, viewportWidth, density)
        if (top == null) {
            Box(Modifier.matchParentSize()) { content(under) }
        } else {
            Box(
                Modifier
                    .matchParentSize()
                    .alpha(underAlpha)
                    // **底层不位移，只裁**（TG：动的是 `containerView`，`containerViewBack`
                    // 原地不动、被 `clipRight = translationX + dp(1)` 裁出那一段）。
                    .drawWithContent {
                        // `clipRect { }` 的接收者是 `DrawScope`，会把外层 `ContentDrawScope`
                        // 的 `drawContent()` 遮住 —— 必须带显式接收者，否则报
                        // 「cannot be called in this context with an implicit receiver」。
                        clipRect(right = layerX) {
                            this@drawWithContent.drawContent()
                            // 露出来的那一段铺满灰罩（TG `:1204-1212`），按让出的宽度走 ——
                            // TG 是 `120·clamp01(widthOffset/width)`、上限 0.8。
                            // `viewportWidth == 0` 时（首帧未布局）不画，避免 0/0 = NaN。
                            val width = viewportWidth
                            val widthOffset = width - layerX
                            if (width > 0f && widthOffset > 0f) {
                                drawRect(
                                    color = SCRIM.copy(
                                        alpha = SCRIM_ALPHA * (widthOffset / width).coerceIn(0f, 1f)
                                    )
                                )
                            }
                        }
                    },
            ) {
                content(under)
            }
        }
        top?.let { over ->
            // 从**哪一侧**滑的（`BackEventCompat.swipeEdge`）—— TG 的 `predictiveBackLeft`
            //（`ActionBarLayout.onBackStarted:1548` 用触点 X 对中线判）只用来**镜像缩放锚点**
            // 与被裁剪那一段矩形（`:1125-1133`）：窗口**永远朝右让**（`setTranslationX(+dx)`），
            // 留出的间隙恒在左侧。之前我没记这一侧，右缘滑的时候锚点仍按「离左边 82dp」
            // 取，缩放中心跑到窗口外面 —— 看着就是「右边留了一大片 + 窗口不对」。
            val fromRight = gesture.edge == BackEventCompat.EDGE_RIGHT
            Box(
                Modifier
                    .alpha(progress.value * (1f - dismiss.value))
                    .graphicsLayer {
                        // 三段相加，互不干涉（见 KDoc「三个来源」）—— 与底层裁剪共用
                        // [topLayerX]，保证「裁的」与「移的」永远是同一个数。
                        translationX = layerX
                        // 缩放：TG 在预测式返回期间 `containerView.getAlpha()` 恒为 1
                        // （`:1119` 的内层 `lerp(0.90, 0.85, 1 - alpha)` 因此取 0.90），
                        // 所以这里 alpha 传常量 1，不跟 `progress`/`dismiss` 走。
                        val sc = backGestureScale(gesture.dx, alpha = 1f, density)
                        if (sc != 1f) {
                            scaleX = sc
                            scaleY = sc
                            // 锚点 X 是「**离手指那条边** dp(82)」，不是固定离左边 82dp
                            // （TG `:1133`：左缘滑取 `rectTmp.right - dp(82)`，
                            // 右缘滑取 `rectTmp.left + dp(82)`）。
                            val fromEdge = SCALE_ANCHOR_X * density / size.width
                            transformOrigin = TransformOrigin(
                                if (fromRight) fromEdge else 1f - fromEdge,
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
            val shadowX = layerX
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        // 阴影恒在窗口**左缘外侧** `[x - shadowW, x]` —— 窗口朝右让，
                        // 左边让出来的那一截就是它。TG 也只在这一侧画（`:1194-1201` 的
                        // `child == containerView` 分支），右缘滑时不换边。
                        translationX = shadowX - EDGE_DP * density
                        alpha = if (shadowX != 0f) {
                            ((viewportWidth - shadowX) / (EDGE_ALPHA_DP * density)).coerceIn(0f, 1f)
                        } else {
                            0f
                        }
                    }
                    .drawWithContent {
                        // 渐变横向：从**窗口那一侧**透明、往**露出来那侧**渐深 ——
                        // 资源条水平铺开后正是这个形状（最外一格 ≈ 满 alpha）。
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

/** 盖层被遮住时的侧缘阴影终点色（TG `layer_shadow` 最右一格的黑）。 */
private val EDGE = Color(0x33000000)

/**
 * 侧缘阴影的渐变：TG `layer_shadow` webp 的横向 alpha 递增（xhdpi 8×4 是
 * 4→14→26→34→48，xxhdpi 12×6 是 4→14→26→36→50）。起点透明、终点 [EDGE]。
 * **声明必须在 [EDGE] 之后** —— 顶层 `val` 按源码顺序初始化，反了就拿到未初始化的 0。
 */
private val EDGE_BRUSH = Brush.horizontalGradient(
    colors = listOf(Color.Transparent, EDGE),
)

/** 盖层侧缘阴影宽度（TG `layer_shadow` 的固有宽度）。 */
private const val EDGE_DP = 8f

/** 阴影淡出的分母：TG `clamp(255·widthOffset/dp(20))`（`ActionBarLayout.java:1192`）。 */
private const val EDGE_ALPHA_DP = 20f

private const val DURATION_MS = 150

/** 手势提交后的飞出时长（TG `ActionBarLayout.java:1608` 的 `newBackTransitions() ? 380`）。 */
private const val FLY_OUT_MS = 380

/** 切入/切出的横向位移（TG `ActionBarLayout.java:1892` 的 `dp(48)`）。 */
private const val SLIDE_DP = 48

/**
 * 顶层在**父坐标系**里的 x（px）—— 三段相加，见 KDoc「顶层位移的三个来源」：
 * `(1-progress)·48dp + gesture.dx + dismiss·(屏宽 - dx)`。
 *
 * **抽成纯函数**，因为底层裁剪（TG 的 `clipRight`）与顶层位移用的是**同一个**数，
 * 两处各写一遍公式就会漂。底下层裁剪与顶层阴影都调它。
 */
private fun topLayerX(
    progress: Float,
    dx: Float,
    dismiss: Float,
    viewportWidth: Float,
    density: Float,
): Float = (1f - progress) * SLIDE_DP * density + dx + dismiss * (viewportWidth - dx)

/**
 * 露出来那一段的灰遮罩（TG `scrimPaint`，`ActionBarLayout.java:1204-1208`）：
 * `Color.argb(120 · clamp01(widthOffset/width), 0, 0, 0)`。**这是用户说的「灰色遮罩」**，
 * 盖住整段露出来的底层页面；与只画窗口左缘 8dp 的 `layer_shadow` 是两回事。
 */
private val SCRIM = Color.Black

/** 灰遮罩的峰值 alpha（TG `:1206` 的 `120`）。 */
private const val SCRIM_ALPHA = 120f / 255f

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
