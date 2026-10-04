package com.autoscript.ui.components

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing

/**
 * TG 的两条缓动曲线（`CubicBezierInterpolator` 的常量，逐字抄控制点）。
 *
 * 放在这里而不是各文件各写一份：同一个 `EASE_OUT_QUINT` 在 chip 切换（320ms）、
 * FAB 按压回弹、悬浮圆钮三处都用，抄错一个小数点就是三种"手感"。
 */

/**
 * `CubicBezierInterpolator.EASE_OUT_QUINT = (0.23, 1, 0.32, 1)`。
 *
 * TG 的"快起慢收"档：起步极快、尾段极缓。chip 选中态切换（`TopicsLayoutSwitcher`）、
 * 聊天页悬浮圆钮的按下反馈都用它。
 */
val EaseOutQuint: Easing = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)

/**
 * `android.animation.OvershootInterpolator(tension)` 的等价物。
 *
 * 公式取自 `OvershootInterpolator.getInterpolation` 本身（不是拟合）：
 * ```
 * t -= 1;  return t * t * ((tension + 1) * t + tension) + 1;
 * ```
 * `ScaleStateListAnimator` 松手回弹用它（`new OvershootInterpolator(tension)`），
 * 按下那一段**没有**插值器（线性 80ms）—— 回弹才过冲，按下不过冲。
 */
fun OvershootEasing(tension: Float): Easing = Easing { t ->
    val u = t - 1f
    u * u * ((tension + 1f) * u + tension) + 1f
}
