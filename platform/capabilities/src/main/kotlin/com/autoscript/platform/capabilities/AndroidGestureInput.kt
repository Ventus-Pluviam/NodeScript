package com.autoscript.platform.capabilities

import com.autoscript.domain.automation.GestureInput
import com.autoscript.domain.automation.InputProvider

/**
 * 无障碍手势输入（docs §9.1 `dispatchGesture` + §9.3 默认通道的 Android 实现）。
 *
 * 契约（[InputProvider] KDoc 的兑现，三条各有归宿）：
 * - 服务未连 → [canPerformGestures]/[dispatchGesture] 经 [SystemA11yBridge] 抛
 *   ERR_SERVICE_DISABLED（"没服务"不折成 false 混进"关门" —— handler 侧
 *   `catch AutojsException` 原码回桥）；
 * - 关门（服务在、`CAPABILITY_CAN_PERFORM_GESTURES` 未授予）→ dispatch 回 false
 *   不发系统（调用方走能力中心引导）——系统没有 `AccessibilityManager.canPerformGestures()`
 *   这个方法（AOSP 主源码实证），运行期事实读服务自身 capability 位；
 * - 开门 → 系统返回值原样透传（false = 未派发成功，非能力问题；回调级"动画跑完了吗"
 *   属完成态，P1 接 GestureResultCallback，当前 null 回调即交即返）。
 *
 * 纯转接零逻辑：可测性由假 [A11yBridge] 注入（`AndroidGestureInputTest`）。
 */
class AndroidGestureInput(
    private val bridge: A11yBridge = SystemA11yBridge,
) : InputProvider {

    override val canPerformGestures: Boolean
        get() = bridge.canPerformGestures

    override suspend fun dispatchGesture(gesture: GestureInput): Boolean {
        // 先问关门（服务未连在这一问就抛，不往下发）；关门 = false，不开空头派发。
        if (!bridge.canPerformGestures) return false
        return bridge.dispatchGesture(gesture)
    }
}
