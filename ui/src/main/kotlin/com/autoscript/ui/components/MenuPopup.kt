package com.autoscript.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.autoscript.ui.theme.ThemeColors
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * TG 弹出菜单的壳：把 `ActionBarPopupWindow` 的**开/关动画、投影、定位**三件事搬进
 * Compose。M3 `DropdownMenu` 的进出场是硬编码在 `DropdownMenuContent` 里的 scale+fade
 * （进 120ms、出 75ms，外部改不了），动画与投影都不对，故自建 `Popup` —— 定位按 M3
 * provider 的思路简化，动画与投影逐项对 TG 源码。
 *
 * **打开**（`ActionBarPopupWindow.startAnimation()`：时长 `150 + 16 × 可见项` ms，线性）：
 * 1. 容器 `backScaleY` 0→1（pivot 顶边；翻转到 anchor 上方时 pivot 底边）+
 *    `backAlpha` 0→255；
 * 2. 子项瀑布：`AndroidUtilities.cascade(t, pos, count, 4)` → `translationY (1-at)×∓6dp`、
 *    `alpha = at × (enabled ? 1 : 0.5)` —— [MenuGap]（TG `GapView`）不参与，
 *    disabled 项的终值 alpha 停在 0.5（`onAnimationEnd` 同款）。
 *
 * **关闭**（`dismiss()`：`dismissAnimationDuration = 150` ms，线性）：容器整体
 * `translationY ∓5dp`（下方弹出上收、上方弹出下收）+ 淡出；进行中的打开动画先取消
 * （TG dismiss 里 cancel `windowAnimatorSet`）—— 这里由 `LaunchedEffect(expanded)`
 * 的 key 切换天然完成取消。
 *
 * **投影（描边）**：TG 菜单底是 9-patch（`popup_fixed_alert4.9.png`，MULTIPLY 上主题色），
 * 圆角外那圈"描边"实为**贴边 alpha 33/255（≈13%）、5dp 内衰减到 0 的渐变阴影环**
 * （xxhdpi 实测 15px 阶梯 1,1,2,3,4,5,6,8,9,11,14,16,33）—— 不是一条线。本仓没有
 * 9-patch，用 5 层圆角矩形阶梯（[MenuShadowRingAlphas]）逼近；不画硬 `border`，
 * 也不加 M3 `shadowElevation`（真投影强度/形态不可控，且 popup 会裁掉出界部分 ——
 * 故 popup 本体四周多留 [MenuShadowSpread] 空隙装环，provider 定位时已扣掉）。
 *
 * **定位**：横向上缘贴 anchor 右缘（本仓菜单入口都在右侧：⋮ 与行长按），靠左出界改
 * 贴 anchor 左缘，最后夹进窗口（[MenuScreenMargin]）；纵向先 anchor 下方、放不下翻
 * 上方、最后夹取。展开方向决定 scaleY 轴与开/关位移的方向（TG `shownFromBottom`）。
 */
@Composable
internal fun MenuPopup(
    expanded: Boolean,
    visibleCount: Int,
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    // 关闭动画跑完才拆 popup（expanded=false 先播 150ms，动画完 mounted 才翻 false）。
    var mounted by remember { mutableStateOf(false) }
    if (expanded) {
        mounted = true
    }
    if (!mounted) return

    // 容器三支 + 子项 cascade 共一条时间线（TG windowAnimatorSet.playTogether）。
    val openScale = remember { Animatable(0f) }
    val openAlpha = remember { Animatable(0f) }
    val itemsProgress = remember { Animatable(0f) }
    val dismissProgress = remember { Animatable(0f) }
    val below = remember { mutableStateOf(true) }
    val density = LocalDensity.current
    val spreadPx = with(density) { MenuShadowSpread.toPx().roundToInt() }
    val marginPx = with(density) { MenuScreenMargin.toPx().roundToInt() }

    LaunchedEffect(expanded) {
        if (expanded) {
            // key 切换已取消进行中的关闭动画（TG dismiss 的对偶：show 前 cancel）。
            dismissProgress.snapTo(0f)
            openScale.snapTo(0f)
            openAlpha.snapTo(0f)
            itemsProgress.snapTo(0f)
            val duration = MenuOpenBaseMillis + MenuOpenPerItemMillis * visibleCount
            coroutineScope {
                launch { openScale.animateTo(1f, tween(duration, easing = LinearEasing)) }
                launch { openAlpha.animateTo(1f, tween(duration, easing = LinearEasing)) }
                itemsProgress.animateTo(1f, tween(duration, easing = LinearEasing))
            }
        } else {
            dismissProgress.animateTo(1f, tween(MenuDismissMillis, easing = LinearEasing))
            mounted = false
        }
    }

    Popup(
        onDismissRequest = onDismissRequest,
        popupPositionProvider = remember(spreadPx, marginPx) {
            MenuPopupPositionProvider(spreadPx, marginPx) { below.value = it }
        },
        properties = PopupProperties(focusable = true),
    ) {
        val palette = ThemeColors
        CompositionLocalProvider(
            LocalMenuAnim provides MenuAnim(itemsProgress, below, visibleCount),
        ) {
            Box(
                Modifier
                    // 动画层最外：投影环与菜单本体一起动（TG dismiss 动的是整个 viewGroup）。
                    .graphicsLayer {
                        val d = dismissProgress.value
                        alpha = openAlpha.value * (1f - d)
                        scaleY = openScale.value
                        translationY =
                            d * if (below.value) -MenuDismissShift.toPx() else MenuDismissShift.toPx()
                        transformOrigin =
                            TransformOrigin(0.5f, if (below.value) 0f else 1f)
                    }
                    .drawBehind {
                        // 投影环：由外向内 alpha 递增的圆角矩形阶梯，贴菜单边那层最深。
                        // 各层是叠画的（source-over），所以每层画的是"与上一层的差值"
                        // —— 叠出来的复合 alpha 才是 9-patch 实测的那条 ramp（见下）。
                        val spread = MenuShadowSpread.toPx()
                        val radius = MenuCornerRadius.toPx()
                        MenuShadowRingAlphas.forEachIndexed { index, a255 ->
                            val e = spread * (MenuShadowRingAlphas.size - index) / MenuShadowRingAlphas.size
                            drawRoundRect(
                                color = Color.Black.copy(alpha = a255 / 255f),
                                topLeft = Offset(spread - e, spread - e),
                                size = Size(size.width - 2 * (spread - e), size.height - 2 * (spread - e)),
                                cornerRadius = CornerRadius(radius + e),
                            )
                        }
                    }
                    .padding(MenuShadowSpread),
            ) {
                Column(
                    // IntrinsicSize.Max：Column 宽 = 最宽行的固有宽，fillMaxWidth 的间隙
                    // 与其余行因此同宽（M3 DropdownMenuContent 同款手法）。
                    Modifier
                        .width(IntrinsicSize.Max)
                        .background(palette.menuBackground, RoundedCornerShape(MenuCornerRadius)),
                    content = content,
                )
            }
        }
    }
}

/**
 * 子项 cascade 动画的读口：`CompositionLocal` 发给菜单内容，每行自己画自己的浮现。
 *
 * 走 local 而不是给行组件加参数：动画上下文是 [MenuPopup] 的内部事，泄漏进签名会让
 * 调用方以为要自己管动画。读值发生在 `graphicsLayer` 的 draw 期 lambda 里 ——
 * 进度每帧变化只重绘、不重组。
 */
@Stable
class MenuAnim internal constructor(
    private val progress: Animatable<Float, AnimationVector1D>,
    below: MutableState<Boolean>,
    private val visibleCount: Int,
) {
    private val belowState = below

    /** 第 [position] 个非间隙项此刻的浮现进度（0 隐藏 → 1 就位）。 */
    fun itemProgress(position: Int): Float =
        cascade(progress.value, position, visibleCount, MenuCascadeWave)

    /** 菜单是否在 anchor 下方展开（TG `!shownFromBottom`）—— 决定子项位移方向。 */
    val belowMenu: Boolean get() = belowState.value
}

/** 发 [MenuAnim] 的 local；只有 [MenuPopup] 内容里能读到。 */
internal val LocalMenuAnim = compositionLocalOf<MenuAnim> {
    error("MenuAnim 只在 MenuPopup 的内容里可用")
}

/**
 * `AndroidUtilities.cascade` 逐字移植（Telegram/AndroidUtilities.java:5215）：
 * 把总进度 [t] 按 [position]/[count] 错相成一波一波的浮现，[waveLength] 控制同屏
 * 波峰数 —— 项越多每波的启动间隔越短，总时长不随项数失控。
 */
private fun cascade(t: Float, position: Int, count: Int, waveLength: Float): Float {
    if (count <= 0) return t
    val waveDuration = 1f / count * min(waveLength, count.toFloat())
    val waveOffset = position / count * (1f - waveDuration)
    return ((t - waveOffset) / waveDuration).coerceIn(0f, 1f)
}

/**
 * 定位（M3 `DropdownMenuPositionProvider` 的简化版，只留本仓用得到的路径）。
 *
 * 横向：右缘贴 anchor 右缘 → 贴不住（出左界）改 anchor 左缘 → 夹进窗口；
 * 纵向：先 anchor 下方 → 下方放不下且上方放得下则翻上方 → 夹进窗口。
 * 返回值要减回 [spreadPx]：popup 内容比菜单四周各大一圈投影空隙。
 */
private class MenuPopupPositionProvider(
    private val spreadPx: Int,
    private val marginPx: Int,
    private val onPositioned: (below: Boolean) -> Unit,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val menuWidth = (popupContentSize.width - 2 * spreadPx).coerceAtLeast(0)
        val menuHeight = (popupContentSize.height - 2 * spreadPx).coerceAtLeast(0)
        var x = anchorBounds.right - menuWidth
        if (x < marginPx) {
            x = anchorBounds.left
        }
        x = x.coerceIn(marginPx, (windowSize.width - marginPx - menuWidth).coerceAtLeast(marginPx))
        val below =
            anchorBounds.bottom + menuHeight <= windowSize.height - marginPx ||
                anchorBounds.top - menuHeight < marginPx
        val y = (if (below) anchorBounds.bottom else anchorBounds.top - menuHeight)
            .coerceIn(marginPx, (windowSize.height - marginPx - menuHeight).coerceAtLeast(marginPx))
        onPositioned(below)
        return IntOffset(x - spreadPx, y - spreadPx)
    }
}

/** 弹出菜单的圆角（`popup_fixed_alert4.9.png` 实测：11.90 / 11.90 / 11.93 / 11.88dp，取 12）。 */
internal val MenuCornerRadius = 12.dp

/** 打开动画的基长（TG `150 + 16 × visibleCount` 的 150）。 */
private const val MenuOpenBaseMillis = 150

/** 每个可见项的追加时长（TG 同一条式子的 16）。 */
private const val MenuOpenPerItemMillis = 16

/** 关闭动画时长（TG `dismissAnimationDuration = 150`）。 */
private const val MenuDismissMillis = 150

/** 子项 cascade 的波长（TG `cascade(t, pos, count, 4)` 的 4）。 */
private const val MenuCascadeWave = 4f

/** 打开时子项的初始位移（TG `dp(-6)`；菜单在 anchor 上方时反向）。 */
internal val MenuItemShift = 6.dp

/** 关闭时容器的位移（TG `dp(±5)`；下方弹出取负 = 上收）。 */
private val MenuDismissShift = 5.dp

/** 投影环的展开宽（9-patch 实测半透明环 ~15px@xxhdpi ≈ 5dp）。 */
private val MenuShadowSpread = 5.dp

/** 投影环各层的 alpha（/255，由外到内）。9-patch 实测的 ramp 是 1,1,2,3,4,5,6,8,9,11,14,16,33 ——
 * 离散成 5 层时取的是各层的**叠加差值**（source-over 下复合 ≈ 求和）：2+4+5+5+17 = 33。
 * 贴边那层最深 ≈13%，向外 5dp 衰减到 0。 */
private val MenuShadowRingAlphas = intArrayOf(2, 4, 5, 5, 17)

/** 定位夹取时距窗口边缘的最小距离（TG 无此约束、M3 是 48dp —— 取个不顶死屏幕的值）。 */
private val MenuScreenMargin = 8.dp
