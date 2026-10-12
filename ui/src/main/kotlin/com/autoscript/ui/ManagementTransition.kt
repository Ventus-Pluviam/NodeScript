package com.autoscript.ui

import android.os.Build
import android.view.RoundedCorner
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.math.min

/**
 * 管理页里「面板 ⇄ 子页 / 子页 ⇄ 子页」那一层切换动画，**逐值复刻 Telegram**
 * （`ActionBarLayout.drawChild` + `onBackStarted/onBackProgress`，v12.10.6）。
 *
 * ## 这件事最容易搞反的一点：**跟手时窗口横向根本不位移**
 *
 * TG 每一帧都在画 `containerView` 之前 `canvas.translate(-dx, 0)`，其中
 * `dx = Utilities.clamp(translationX, dp(56), 0)`（`:1123`，注意 TG 的
 * `clamp(value, max, min)` 是 **(value, 上限, 下限)**）。
 * 手势期间 `translationX = dp(56)·StandardDecelerate(t)`（`:1571`）本身就 `≤ dp(56)`，
 * 于是 `dx == translationX`，**净位移恒为 0**。你在屏幕上看到的「窗口缩小、旁边空出
 * 一条缝」是三样东西叠出来的，跟「滑动」没关系：
 *
 * | 观感 | 来源 |
 * |---|---|
 * | 窗口变小 | `canvas.scale(scale, …)`，`:1119` |
 * | 缝在哪一侧 | `transformOrigin` 的 x 取**手指那条边**的 `dp(82)`，`:1133` |
 * | 缝里发灰 | 底层页面上的 `scrimPaint`，`:1204-1208` |
 *
 * 左缘滑 → 锚点 `rect.right - dp(82)`（靠右）→ 窗向右缩 → **左边**留缝；
 * 右缘滑 → 锚点 `rect.left + dp(82)`（靠左）→ **右边**留缝。
 *
 * **只有松手后的飞出阶段**净位移才真正起作用（`translationX > dp(56)`），本文件按你
 * 2026-10-13 的口径给它加上方向：**左缘滑往右飞出、右缘滑往左飞出**（TG 恒往右，
 * 见「有意不等于 TG」第 3 条）。方向由 [netLayerX] 的符号给出，底层的裁剪区间与
 * 灰罩宽度随之镜像。
 *
 * ## 底层那一层在手势期间**不裁**（`:1163-1166`）
 *
 * `newBackTransitions()` 为真时 `clipRight = width + getPaddingLeft()`：底层铺满全屏，
 * 灰罩按 `120·clamp01((width - translationX)/width)`（上限 0.8）铺在**整段**上。
 * 只有**非手势**的编程式切换才裁到 `translationX + dp(1)`（`:1100-1104`）。
 * 之前这里裁错，导致缝里露出的是没罩住的原始底层。
 *
 * ## `layer_shadow` 在 API 31+ **根本不画**（`:1200`）
 *
 * 那段 drawable 被 `if (SDK_INT < S || isSheet)` 包着。本项目 minSdk 26 但目标机
 * 都在 31+，所以它对我们**不存在** —— 之前照抄成一条横向渐变窄条，正是你看到的
 * 「一条线」。已删除；缝的明暗全部由上面那个 `scrimPaint` 负责。
 *
 * ## 圆角取 `WindowInsets` 而不是 `Display`（`:1136-1143`）
 *
 * TG 取 `getRootWindowInsets().getRoundedCorner(POSITION_*)`。之前取的是
 * `view.display.getRoundedCorner(...)` —— 窗口不铺满屏时它给 0，圆角因此从来没生效过。
 *
 * ## 其余抄的数
 *
 * | 维度 | TG 的值 | 源 |
 * |---|---|---|
 * | 跟手位移 | `dp(56)·StandardDecelerate(t)` | `:1571` |
 * | 手势缩放 | `lerp(1.00, lerp(0.90, 0.85, 1 - alpha), clamp01(x/dp(56)))` | `:1119` |
 * | 非手势缩放 | `1.00 - min(0.25, 0.05·x/dp(56))` | `:1121` |
 * | 锚点 x | 手指那条边 `dp(82)`；非手势按中线 | `:1133` |
 * | 锚点 y | `predictiveBackY`（手指 Y）；非手势 `centerY()` | `:1133` |
 * | 起步死区 | `LAZY_START = 0.015f` | `LaunchActivity:786` |
 * | 切入/切出时长 | 150ms，`DecelerateInterpolator(1.5f)` | `:1830` / `:614` |
 * | 切入位移量 | `dp(48)·(1 - t)`（进 `translationX`，不是净位移） | `:1892` |
 * | 飞出时长 | `max(200/width·dist, 380)` | `:1608` |
 *
 * ## 三处**有意不等**于 TG（写在 KDoc 里，不藏着）
 *
 * 1. **插值用 Bezier 近似**而非 `DecelerateInterpolator(1.5f)` 本身 —— View 体系的
 *    `Interpolator` 在 Compose 侧无对等物，`(0f, 0f, 0.2f, 1f)` 是它的三次曲线近似。
 * 2. **不做 TG 的逐帧 `dt` 钳制**（`:1824-1828`）。那是 View 体系手搓动画循环时的保护。
 * 3. **飞出方向按手指那一侧镜像**（2026-10-13 你给的口径：「手势在左边时，窗口往右边
 *    滑动消失；手势在右边时，窗口往左边滑动消失」）。TG 只有右一个方向 —— 它恒
 *    `setTranslationX(+dx)`（`:1571`），右缘滑也往右退。跟手阶段两者一致（净位移恒 0），
 *    只有松手后的飞出阶段不同。
 *
 * ## 为什么是两层而不是一层
 *
 * 离场动画要求旧页在动画期间仍在树上。常驻两层：[under] 是底下那页，[top] 是盖在
 * 上面那页（`null` = 没有覆盖层），`progress` 是盖层的**在场程度**（0 → 1）。
 *
 * ## 飞出不淡出
 *
 * TG `animateBackEndAnimation(false)`（`:1606-1618`）的 AnimatorSet 里只有两路：
 * `containerView.TRANSLATION_X` 与 `innerTranslationX`，**没有 alpha**（收尾还专门
 * `setAlpha(1.0f)`，`:1651`）。窗口靠滑出屏幕消失。本文件照此 —— 顶层透明度只跟
 * `progress`（那是 150ms 切入/返回键那条路），飞出段不动它。
 *
 * ## 深度不超过两层
 *
 * （面板 → 子页 → 子页，子页里没有第三层），所以两个槽位够用；真要三层，
 * `under`/`top` 得换成栈。
 *
 * @param page 要显示的那一层。**动画的输入，不是状态**。
 * @param active 管理页是不是 pager 当前停稳的那一页。**预组合的邻页必须传 false** ——
 *   否则邻页里那个 `PredictiveBackHandler` 会替前台页签吞掉返回键（`OnBackPressedDispatcher`
 *   是 LIFO，顺序由组合顺序定，管不了「哪一页真在前台」）。
 * @param onAnimatingChanged 动画进行中 —— TG `animationInProgress`，面板侧用它吞新入口点击。
 * @param onBackRequest 请宿主把当前子页关掉。手势提交后由本组件调一次 —— **不自己改 `top`**，
 *   否则 `top` 与 `page` 脱节、上层凭空消失。
 * @param content 画一层。过渡期间会被调用两次（底 + 顶），**必须无副作用**。
 */
@Composable
internal fun <T> ManagementTransition(
    page: T,
    active: Boolean,
    onAnimatingChanged: (Boolean) -> Unit = {},
    onBackRequest: () -> Unit,
    content: @Composable (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    // `under` 是盖在底下的那一层，`top` 是盖在上面的（null = 没有覆盖层）。
    //
    // **为什么不能用「当前显示的那层」当底层**：从审计返回依赖管理时 `page` 变回依赖管理，
    // 而它此刻正是 `top`。若底层也画它，用户会看到同一页淡出又淡入。`under` 存的是
    // 「`top` 之前那一层」，返回时它已经在那儿了。
    var under by remember { mutableStateOf(page) }
    var top by remember { mutableStateOf<T?>(null) }
    // 1 = 上层完全就位（初值：首帧没有覆盖层，直接就位，不做进场动画）。
    val progress = remember { Animatable(1f) }
    // 手势提交后「飞出去」的进度 0→1（TG `animateBackEndAnimation(false)`，`:1606-1618`）。
    val dismiss = remember { Animatable(0f) }
    // 跟手返回手势的瞬时量（`ManagementBackGesture` 写、`graphicsLayer` 读）。
    val gesture = remember { BackGestureState() }
    // 本次切出是不是「手势提交」造成的 —— 决定切出走 380ms 飞出还是 150ms 滑出。
    // 不在组合里读，只在 `LaunchedEffect` 里读，所以不引发重组。
    val committed = remember { mutableStateOf(false) }
    val density = LocalDensity.current.density
    var viewportHeight by remember { mutableFloatStateOf(0f) }
    var viewportWidth by remember { mutableFloatStateOf(0f) }
    // 视口尺寸：`onSizeChanged` 给的是 px，与 `touchY` 同坐标系。
    //
    // `fillMaxSize` 不能省：两个子层都是 `matchParentSize`，而 `matchParentSize`
    // **不参与父级尺寸计算** —— 不显式撑满的话这一层会塌成 0×0。
    //
    // 圆角半径 —— TG 在 `drawChild` 里取**窗口**的四角 `RoundedCorner` 做 round-rect 裁剪
    // （`:1136-1143`）。`LocalView.current` 是 @Composable 取子，必须在 `remember` **外面**
    // 读（里面调用会报「只能在 @Composable 上下文里调」）；布局没到（`viewportWidth == 0`）
    // 先按 0 兜底，一到就换 key 重算。
    val localView = LocalView.current
    val corners = remember(viewportWidth, viewportHeight) {
        if (viewportWidth <= 0f || viewportHeight <= 0f) {
            ScreenCorners()
        } else {
            screenCorners(localView)
        }
    }
    val cornerShape = remember(corners) {
        // `CornerSize(float)` 是 **PxCornerSize**（像素，不是 dp）—— `toPx` 原样返回。
        RoundedCornerShape(
            topStart = CornerSize(corners.topStart),
            topEnd = CornerSize(corners.topEnd),
            bottomEnd = CornerSize(corners.bottomEnd),
            bottomStart = CornerSize(corners.bottomStart),
        )
    }

    // **返回键与侧缘手势合走一条路**：TG 也是一个 `OnBackAnimationCallback` 管
    // started / progressed / cancelled / invoked 四态（`LaunchActivity:738-818`）。
    // `PredictiveBackHandler` 的流在「手势提交」与「普通返回键」两种情况下都会结束，
    // 所以**不要再挂一个 `BackHandler`** —— 它排在后面注册就会把手势整个吃掉
    // （`OnBackPressedDispatcher` 是 LIFO，后注册的优先）。
    //
    // `active` 这道门不能省：pager 预组合邻页，邻页里这个 handler 会替前台页签吞返回。
    ManagementBackGesture(
        state = gesture,
        enabled = active && top != null,
        onCommit = {
            // **不在这里自己清 `top`**：那会让 `top` 与 `page` 脱节。改成「标一个旗标 +
            // 请宿主关掉子页」——`page` 随之变成 `under`，`LaunchedEffect` 走切出分支。
            // 与 TG「`animateBackEndAnimation` 动画结束后才 `onSlideAnimationEnd` 改栈」
            // 是同一种次序。
            committed.value = true
            onBackRequest()
        },
    )

    LaunchedEffect(page) {
        if (page == top) return@LaunchedEffect
        // 只有**切出**走完才允许清掉覆盖层。切入走完后 `top` 要留着 —— 子页就位了、
        // 还在前台。`finally` 无条件 `top = null` 的话，每次点进子页都是
        // 「闪一下动画然后自动退出」。
        var exitFinished = false
        // **切入、切出都要置位**。只在切入那一刻喊一次 `true` 的话，切出期间
        // `animating` 是 false、面板入口照旧可点，退出动画里点新入口就会卡死。
        // TG 的 `animationInProgress` 在整个动画窗口内恒真（`ActionBarLayout:1660` 设、
        // 收尾清），这里对齐。
        onAnimatingChanged(true)
        try {
            if (page == under) {
                if (committed.value) {
                    // 手势提交：从手指当前位置继续飞出，380ms
                    // （TG `animateBackEndAnimation(false)`，`:1606-1618`）。
                    //
                    // **只动 `dismiss`，不碰 `progress`** —— TG 的那个 AnimatorSet 里只有
                    // 两路：`containerView.TRANSLATION_X` 与 `innerTranslationX`（`:1611-1612`），
                    // **没有 alpha**（收尾时它还专门 `setAlpha(1.0f)`，`:1651`）。窗口是
                    // 「滑出屏幕」消失的，不是「淡出」消失的。
                    //
                    // 上一版两条一起跑、alpha 又写成 `progress * (1 - dismiss)`，等于透明度
                    // 被折了两次 —— 视觉上就是「消失得太快」（用户 2026-10-13 报的那个）。
                    // 顺带一提：`progress` 若在这段归零，`layerScale` / `backClipRight`
                    // 也会跟着走偏，而 TG 那两路在这段是**冻结**的。
                    dismiss.snapTo(0f)
                    dismiss.animateTo(1f, tween(FLY_OUT_MS, easing = EASE_OUT_QUINT))
                } else {
                    // 返回键 / 编程切走：150ms（TG `startLayoutAnimation` 那一套）。
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
            // `LaunchedEffect(page)` 在 `page` 再次变化时被**取消**，取消会丢掉收尾里
            // `top = null` 那行，于是 `top` 留在旧值、`page` 已经指向别的页 —— 两者脱节后
            // `if (page == under)` / `else` 两支都不再命中，后续任何进出都推不动动画，
            // 表现就是**卡死**。
            //
            // TG 不会出这个事：它的 `animateBackEndAnimation` 回调（`ActionBarLayout:1660`）
            // 与栈变更是**同一个 AnimatorSet** 里的相邻步骤，不存在「动画被外力掐断、
            // 栈没跟上」这个中间态。这里用 `NonCancellable` 把两边焊在一起。
            withContext(NonCancellable) {
                // 切出走完才清覆盖层（协程被取消也会顺路走到这：`page` 已经换了，
                // 新的 `LaunchedEffect(page)` 会按新 `page` 重画）。
                if (exitFinished) {
                    top = null
                    progress.snapTo(1f)
                }
                // 稳态下不留任何手势残留（TG 的 `animateBackEndAnimation` 收尾同样清零，
                // `:1650`/`:1658` 把 `predictiveBackInProgress` 清成 false）。
                gesture.reset()
                dismiss.snapTo(0f)
                committed.value = false
                onAnimatingChanged(false)
            }
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged {
                viewportHeight = it.height.toFloat()
                viewportWidth = it.width.toFloat()
            },
    ) {
        // 三个量共用同一份推导，顺序即 TG `drawChild` 的顺序（`:1095` 起）：
        //
        // - `translationX` ≡ TG 的 `innerTranslationX`（进出场 + 跟手 + 飞出三段相加）。
        // - `netX` ≡ TG 那个 `canvas.translate(-dx, 0)` 抵消后的**净位移**。
        // - `backClipRight` ≡ TG 的 `clipRight`（两种情形不同，见文件头「底层不裁」）。
        //
        // 各写一遍必然漂，所以抽成 [topLayerX] / [netLayerX] 两个纯函数共用。
        val innerTranslationX = topLayerX(progress.value, gesture.dx, dismiss.value, viewportWidth, density)
        val predictive = gesture.hasProgress
        // 从哪一侧滑的 —— TG 的 `predictiveBackLeft`（`onBackStarted:1558` 用触点 X
        // 对中线判）。它决定两件事：**净位移的符号**（飞出方向）与**缩放锚点的左右**
        // （`:1133`）。缝隙在哪一侧完全由锚点决定，窗口往哪边退由符号决定。
        val fromLeft = gesture.edge == BackEventCompat.EDGE_LEFT
        val netX = netLayerX(innerTranslationX, density, fromLeft)
        // 底层的裁剪区间 —— TG 的 `clipLeft` / `clipRight`（`:1100-1104`、`:1163-1166`）。
        // 窗口往右退时露出左侧一段，往左退时露出右侧一段，所以两端都得算。
        val backClipLeft: Float
        val backClipRight: Float
        if (predictive) {
            // `newBackTransitions()`：底层铺满全屏，灰罩才盖得住整段（`:1163-1166`）。
            backClipLeft = 0f
            backClipRight = viewportWidth
        } else if (netX >= 0f) {
            // 窗口往右退 → 露出左侧那一段（TG `clipRight = translationX + dp(1)`，`:1102`）。
            backClipLeft = 0f
            backClipRight = innerTranslationX + density
        } else {
            // 窗口往左退 → 露出右侧那一段，镜像 `clipRight` 那一支。
            backClipLeft = viewportWidth + netX - density
            backClipRight = viewportWidth
        }

        if (top == null) {
            Box(Modifier.matchParentSize()) { content(under) }
        } else {
            Box(
                Modifier
                    .matchParentSize()
                    .drawWithContent {
                        // `clipRect { }` 的接收者是 `DrawScope`，会把外层 `ContentDrawScope`
                        // 的 `drawContent()` 遮住 —— 必须带显式接收者，否则报
                        // 「cannot be called in this context with an implicit receiver」。
                        clipRect(left = backClipLeft, right = backClipRight) {
                            this@drawWithContent.drawContent()
                            // 裁出来的这段铺灰罩（TG `scrimPaint`，`:1204-1208`）：
                            // `Color.argb(120 · clamp(widthOffset / width, 0, 0.8), 0, 0, 0)`。
                            //
                            // **`widthOffset` 取 TG 那个 `width - innerTranslationX`
                            // （`:1192`），不是净位移**（`:1095` 的 `translationX` ≡
                            // `innerTranslationX`）。差别就是「跟手期间为什么没遮罩」：
                            // 那时 `innerTranslationX = dx = dp(56)·StandardDecelerate(t)`
                            // 已经大于 0，于是 `widthOffset` 从满屏一路降到 `width - dp(56)`
                            // —— 缝一露出来就是**满档遮罩**。我上一版错取了 `|净位移|`
                            // （跟手期间恒为 0），遮罩要等松手后 `translationX` 越过 `dp(56)`
                            // 才开始出现，正是你报的「松开后才出现了阴影」。
                            //
                            // 右缘滑时 `innerTranslationX` 仍是正的（TG 那边恒正，
                            // `:1571`），所以公式不用改。
                            val opacity = scrimOpacity(innerTranslationX, viewportWidth)
                            if (opacity > 0f) {
                                drawRect(color = SCRIM.copy(alpha = opacity))
                            }
                        }
                    },
            ) {
                content(under)
            }
        }
        top?.let { over ->
            Box(
                Modifier
                    // 透明度只跟 `progress`（切入/返回键那条 150ms 路径用）。手势提交的飞出
            // 段**不淡出** —— TG `animateBackEndAnimation(false)` 根本没有 alpha 那一路，
            // 窗口靠滑出屏幕消失（见 KDoc「飞出不淡出」）。
            .alpha(progress.value)
                    .graphicsLayer {
                        // 净位移 ≡ TG `canvas.translate(-dx, 0)` 抵消之后的那份（`:1124`）。
                        translationX = netX
                        val sc = layerScale(innerTranslationX, predictive, density)
                        if (sc != 1f) {
                            scaleX = sc
                            scaleY = sc
                            transformOrigin = TransformOrigin(
                                anchorPivotX(fromLeft, predictive, size.width, density),
                                anchorPivotY(
                                    predictive = predictive,
                                    touchY = gesture.touchY,
                                    height = if (viewportHeight > 0f) viewportHeight else size.height,
                                ),
                            )
                        }
                    }
                    .matchParentSize(),
            ) {
                // 子页内容 + **窗口圆角**裁剪（TG `:1136-1155` 的 round-rect）。
                // 内容不是 `matchParentSize`（它不参与父级尺寸计算），直接用 `fillMaxSize`。
                Box(Modifier.fillMaxSize().clip(cornerShape)) {
                    content(over)
                }
            }
        }
    }
}

/** 四角圆角半径（px）。零值 = 该角不圆（API 31 以下、或窗口不铺满屏时）。 */
private data class ScreenCorners(
    val topStart: Float = 0f,
    val topEnd: Float = 0f,
    val bottomEnd: Float = 0f,
    val bottomStart: Float = 0f,
)

/**
 * 窗口四角的系统圆角（px）。TG 用的就是
 * `getRootWindowInsets().getRoundedCorner(POSITION_*)`（`ActionBarLayout.java:1136-1143`）——
 * 这是**窗口**的四角。之前取的是 `view.display.getRoundedCorner(...)`：窗口不铺满屏时
 * 它给 0，圆角因此从来没生效过。API 31+ 才有 `WindowInsets.getRoundedCorner`，
 * 旧版本与拿不到 insets 时按 0 兜底（`RoundedCorner?` 为 null 也当 0）。
 */
private fun screenCorners(view: View): ScreenCorners {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return ScreenCorners()
    val insets = runCatching { view.rootWindowInsets }.getOrNull() ?: return ScreenCorners()
    fun radius(position: Int): Float = runCatching {
        insets.getRoundedCorner(position)?.radius ?: 0
    }.getOrDefault(0).toFloat()
    return ScreenCorners(
        topStart = radius(RoundedCorner.POSITION_TOP_LEFT),
        topEnd = radius(RoundedCorner.POSITION_TOP_RIGHT),
        bottomEnd = radius(RoundedCorner.POSITION_BOTTOM_RIGHT),
        bottomStart = radius(RoundedCorner.POSITION_BOTTOM_LEFT),
    )
}

/**
 * 缩放因子。TG 有两条分支（`:1119-1121`）：
 *
 * ```
 * predictiveBackInProgress → lerp(1.00, lerp(0.90, 0.85, 1 - alpha), clamp01(x/dp(56)))
 * 否则                    → 1.00 - min(0.25, 0.05 · x/dp(56))
 * ```
 *
 * 内层 `lerp(0.90, 0.85, 1 - alpha)` 用的是 `containerView.getAlpha()`：预测式返回期间
 * 它恒为 1，所以内层恒取 **0.90**，写死即可。
 */
internal fun layerScale(translationX: Float, predictive: Boolean, density: Float): Float {
    if (predictive) {
        // 复用手势那一份公式（`backGestureScale`），别把 `lerp` 抄第二遍。
        return backGestureScale(translationX, alpha = 1f, density)
    }
    val unit = BACK_GESTURE_DP * density
    return 1f - min(SCALE_SLIDE_MAX, SCALE_SLIDE_RATE * translationX / unit)
}

/**
 * 缩放锚点的 x（TG `:1133`）：
 * `predictiveBackLeft ? rect.right - dp(82) : rect.left + dp(82)`。
 *
 * **这一处就是「缝在哪一侧」的全部原因**：左缘滑 → 锚点靠右 → 窗向右缩 → 左边留缝；
 * 右缘滑 → 锚点靠左 → 右边留缝。窗口本身**不左右平移**（见 [netLayerX]）。
 *
 * 非手势时回到中线 —— TG 那一刻 `rectTmp` 仍是满屏，按中线取等价。视口没量出来
 * （`width <= 0`）也回中线：不拿 0 当分母。
 *
 * @param fromLeft 手指从**左边**滑的（TG `predictiveBackLeft`）。
 */
internal fun anchorPivotX(
    fromLeft: Boolean,
    predictive: Boolean,
    width: Float,
    density: Float,
): Float {
    if (!predictive || width <= 0f) return 0.5f
    val fromEdge = SCALE_ANCHOR_X * density / width
    return if (fromLeft) 1f - fromEdge else fromEdge
}

/**
 * 缩放锚点的 y（TG `:1133` 的 `predictiveBackY`）—— 手指位置；没有触点时退到垂直居中。
 */
internal fun anchorPivotY(predictive: Boolean, touchY: Float, height: Float): Float {
    if (!predictive || height <= 0f || touchY.isNaN()) return 0.5f
    return (touchY / height).coerceIn(0f, 1f)
}

/**
 * 灰遮罩的不透明度（TG `:1204` 的 `clamp(widthOffset / width, 0, 0.8f)`），
 * **乘进峰值 alpha 之后**（`120/255`，TG `:1206`）。
 *
 * @param innerTranslationX TG 的 `innerTranslationX`（`:1095` 的 `translationX`）——
 *   跟手期间就已经是正数（`dp(56)·StandardDecelerate(t)`），所以**遮罩从缝一露出来
 *   就是满档**，不必等松手。
 * @param viewportWidth 视口宽（TG 的 `width`，已扣掉 padding）。
 */
internal fun scrimOpacity(innerTranslationX: Float, viewportWidth: Float): Float {
    if (viewportWidth <= 0f) return 0f
    val widthOffset = (viewportWidth - innerTranslationX).coerceAtLeast(0f)
    val opacity = (widthOffset / viewportWidth).coerceIn(0f, SCRIM_MAX_OPACITY)
    return SCRIM_ALPHA * opacity
}

/** 灰遮罩的峰值 alpha（TG `:1206` 的 `120`）。 */
private const val SCRIM_ALPHA = 120f / 255f

/** 灰遮罩的不透明度上限（TG `:1204` 的 `clamp(widthOffset / width, 0, 0.8f)`）。 */
private const val SCRIM_MAX_OPACITY = 0.8f

private const val DURATION_MS = 150

/**
 * 手势提交后的飞出时长，ms。
 *
 * TG `:1608` 是 `max((int)(200f / measuredWidth · distToMove), newBackTransitions() ? 380 : 50)`。
 * `newBackTransitions()` 为真时那 380 是**恒定的下限** —— 第一项最大也只有 200
 * （`distToMove ≤ measuredWidth`），钳不住。所以这里直接写死 380。
 */
private const val FLY_OUT_MS = 380

// `BACK_GESTURE_DP`（dp(56)）在 `ManagementBackGesture.kt` 里那一份 —— 同名同值，
// 别在这里再抄一遍（重复顶层声明直接编译错）。

/** 非手势缩放的两个系数（TG `:1121` 的 `min(0.25f, 0.05f · x / dp(56))`）。 */
private const val SCALE_SLIDE_MAX = 0.25f
private const val SCALE_SLIDE_RATE = 0.05f

/**
 * 切入/切出的 `translationX` 位移量，单位 dp（TG `:1892` 的 `dp(48)`）。
 *
 * **注意它进的是 `translationX`（`innerTranslationX`）而不是净位移** —— 手势之外的
 * 编程式切换里 `translationX ≤ 48dp < dp(56)`，于是被 [netLayerX] 全额抵消，屏幕上
 * 看不到横向滑动，只剩缩放（见文件头那段「窗口横向根本不位移」）。
 */
private const val SLIDE_DP = 48

/**
 * TG 的 `innerTranslationX`（px），三段相加：
 * `(1 - progress)·dp(48) + gesture.dx + dismiss·(屏宽 + dp(56) - dx)`。
 *
 * 飞出那一项的终点是 `屏宽 + dp(56)`：TG 的 `animateBackEndAnimation(false)` 把
 * `innerTranslationX` 送到 `measuredWidth`、同时把 `containerView` 自己的
 * `TRANSLATION_X` 送到 `measuredWidth + dp(56)`，两边一抵消，**净位移**正好落在屏宽
 * （`:1611-1612`）。
 */
internal fun topLayerX(
    progress: Float,
    dx: Float,
    dismiss: Float,
    viewportWidth: Float,
    density: Float,
): Float =
    (1f - progress) * SLIDE_DP * density +
        dx +
        dismiss * (viewportWidth + BACK_GESTURE_DP * density - dx)

/**
 * 净位移（px）≡ TG 每帧那个 `canvas.translate(-dx, 0)` 抵消之后剩下的量（`:1123-1124`），
 * **再按滑动那一侧取符号**：
 *
 * ```
 * dx = Utilities.clamp(translationX, dp(56), 0)     // 注意 TG 的 clamp 是 (value, 上限, 下限)
 * 幅度 = translationX - dx
 * 净位移 = (左缘滑 ? +1 : -1) · 幅度
 * ```
 *
 * 手势期间 `translationX ≤ dp(56)`，所以幅度恒为 **0** —— 跟手时窗口不左右动，只缩放
 * （与 TG 一致）。切出飞行阶段 `translationX > dp(56)`，幅度才增长，符号把它送向手指
 * 那一侧：**左缘滑 → 往右滑出屏；右缘滑 → 往左滑出屏**（用户 2026-10-13 口径）。
 *
 * TG 只有一个方向 —— 它恒 `setTranslationX(+dx)`（`:1571`），右缘滑也一样往右退；
 * 那是 TG 自己的取舍。见文件头「有意不等于 TG」第 3 条。
 *
 * @param fromLeft 手指从**左边**滑的（TG `predictiveBackLeft` 的镜像语义）。
 */
internal fun netLayerX(translationX: Float, density: Float, fromLeft: Boolean): Float {
    val magnitude = translationX - translationX.coerceIn(0f, BACK_GESTURE_DP * density)
    return if (fromLeft) magnitude else -magnitude
}

/** 露出那段的灰遮罩色（TG `scrimPaint` 的 `0x00000000`，即纯黑）。 */
private val SCRIM = Color.Black

/** Android `DecelerateInterpolator(1.5f)` 的 Bezier 近似（TG `:614`）。 */
private val EASE = CubicBezierEasing(0f, 0f, 0.2f, 1f)

/** `CubicBezierInterpolator.EASE_OUT_QUINT`（TG `:1613`、`:1623` 的手势收尾插值）。 */
private val EASE_OUT_QUINT = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
