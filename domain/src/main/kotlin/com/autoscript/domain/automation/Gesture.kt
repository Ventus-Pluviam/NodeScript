package com.autoscript.domain.automation

/**
 * 手势输入模型（docs/framework-design.md §9.1 `dispatchGesture` + §9.3 手势 DSL）。
 * 与 Android GestureDescription 语义对齐：多笔画，每笔画一起点 + 持续时长；
 * 坐标为逻辑像素（与 [UiBounds] 同口径）。
 *
 * 校验内建在构造器（非法即抛 IllegalArgumentException，桥 handler 折叠为
 * ERR_INVALID_PARAM，绝不把非法手势发往系统服务）。
 * touchDown/Move/Up 统一原语随 root `sendevent` / Shizuku-ADB 实现落地（P1/P2，
 * 见 §9.3）；P0 只有无障碍默认实现（[InputProvider] 单方法）。
 */
data class GesturePoint(val x: Int, val y: Int) {
    init {
        require(x >= 0 && y >= 0) { "手势坐标不得为负 x=$x y=$y" }
    }
}

data class GestureStroke(
    val points: List<GesturePoint>,
    val startDelayMillis: Long = 0,
    val durationMillis: Long = 100,
) {
    init {
        require(points.isNotEmpty()) { "笔画至少包含一个点" }
        require(startDelayMillis >= 0) { "startDelay 不得为负: $startDelayMillis" }
        require(durationMillis > 0) { "duration 必须 > 0: $durationMillis" }
    }
}

data class GestureInput(val strokes: List<GestureStroke>) {
    init {
        require(strokes.isNotEmpty()) { "手势至少包含一个笔画" }
    }
}

/**
 * 输入通道 SPI（§9.3）：无障碍手势（默认）/ root / Shizuku-ADB 三实现统一入口。
 * - [canPerformGestures] 即系统 `canPerformGestures()`：false 时调用方不得发手势，
 *   由能力中心引导用户启用（API 31+ 需启用手势）；
 * - [dispatchGesture] 回 false = 系统拒绝执行（非能力问题，不抛错，与 click 同口径）。
 */
interface InputProvider {
    val canPerformGestures: Boolean
    suspend fun dispatchGesture(gesture: GestureInput): Boolean
}
