package com.autoscript.platform.capabilities

import com.autoscript.domain.automation.GestureInput
import com.autoscript.domain.automation.InputProvider
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 内存手势输入（docs §9.1 `dispatchGesture` + §9.3 默认实现位的 JVM 可测形态）。
 *
 * Android 真实现（AccessibilityService.dispatchGesture + canDispatchGesture 回调）
 * 替换本类，江苏契约不变：
 * - 关门（[canPerformGestures]=false）→ dispatch 回 false（不抛错，调用方走
 *   能力中心引导；与 click-on-unclickable 同口径）；
 * - 开门 → 记录手势并记 `gestureDispatched` 事件（笔画数/点数进 payload 摘要），回 true。
 */
class InMemoryInputProvider(
    override val canPerformGestures: Boolean = true,
) : InputProvider {

    private val guard = Mutex()
    private val _dispatched = mutableListOf<GestureInput>()
    val dispatched: List<GestureInput> get() = _dispatched.toList()

    override suspend fun dispatchGesture(gesture: GestureInput): Boolean {
        if (!canPerformGestures) return false
        guard.withLock { _dispatched += gesture }
        return true
    }
}
