package com.autoscript.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager

/**
 * 「点输入框外面就收键盘、撤光标」（2026-10-16 用户报：搜索框与输入框「焦点转移后
 * 输入指示条还在一闪一闪」）。
 *
 * **病不在闪烁动画，在焦点根本没转移。** 设备实测（`uiautomator` 读 `focused` +
 * `dumpsys input_method` 读 `mInputShown`）：点搜索框 → `focused=true`、输入法起来；
 * 接着点列表行 / 卡片 / 空白处 → 搜索框**仍然** `focused=true`、输入法仍开着 ——
 * 此时敲字**落进搜索框**（`input text ZZZ` 之后列表被过滤成「没有匹配…的文件」，
 * 而人是想点那一行）。用户看到的就是"点了别处，光标还在那儿一闪一闪"。
 *
 * **为什么会这样**：Compose 的 `clickable` 只装 `focusableInNonTouchMode`
 * （`Clickable.kt` 那句 `.focusableInNonTouchMode(enabled, …)`）—— **触屏模式下它压根
 * 不请求焦点**。这是有意的（否则点一下列表就会把输入框的焦点抢走），代价是
 * 「点空白处收键盘」这件事**没人做**：`BasicTextField` 只在别处**主动 `requestFocus`**
 * 时才失焦，而点一个不可聚焦的行不会。原生 `EditText` 那套由平台
 * （`View.onTouchEvent` → `clearFocus`）兜着，Compose 里得自己接。
 *
 * **做法**（[reportFocusBounds] + [dismissFocusOnTapOutside] 一对）：
 * - 输入框挂 [reportFocusBounds]：把自己的**根坐标 bounds** 与聚焦态报给一份
 *   [FocusDismissState]（`onGloballyPositioned` 报位置、`onFocusChanged` 报聚焦）；
 * - 容器挂 [dismissFocusOnTapOutside]：在 **Initial 趟**读按下点（那一趟父先于子，
 *   而我们**只读不消费**，列表滚动 / 点按照旧），把点换算到根坐标，落在输入框
 *   bounds 之外就 `clearFocus()`。
 *
 * **为什么按 bounds 判而不是"看谁消费了事件"**：消费分不出"点的是输入框"还是
 * "点的是行"—— 两边的 `detectTapAndPress` 都会 `consume()` 掉那个 down。
 *
 * **单槽位**：一份 state 只认**最近聚焦的那个**输入框（同一面里两个输入框时以
 * 后聚焦者为准）。用到的地方（项目页 / 任务中心各一个搜索框、悬浮窗一个输入行）
 * 都只有一个，如实记在这里，而不是先造一套多槽位的机制。
 */
class FocusDismissState {
    /**
     * 最近聚焦的输入框的根坐标 bounds。
     *
     * **不是 Compose 状态**：只有手势回调读它，读它不该触发重组 —— 用
     * `mutableStateOf` 会让每次布局变化（滚动、软键盘顶起）都把整棵子树标脏一次。
     */
    internal var focusedBounds: Rect = Rect.Zero

    /** 此刻有没有输入框拿着焦点（没有就什么都不用做）。 */
    internal var focused: Boolean = false
}

/** 记住一份 [FocusDismissState]（一个"面"一份：整屏、或悬浮窗内容）。 */
@Composable
fun rememberFocusDismissState(): FocusDismissState = remember { FocusDismissState() }

/**
 * 挂在**输入框**上：聚焦期间上报自己的根坐标 bounds（见 [FocusDismissState] 的 KDoc）。
 *
 * 两个回调各管一半、缺一不可：`onGloballyPositioned` 报**位置**（滚动 / 旋转 / 软键盘
 * 顶起都会触发），`onFocusChanged` 报**聚不聚焦**。只留前者的话，失焦之后 bounds 还
 * 留在那儿，下一次点空白处会被判成"点在输入框里"而不收键盘。
 */
fun Modifier.reportFocusBounds(state: FocusDismissState): Modifier =
    this
        .onGloballyPositioned { state.focusedBounds = it.boundsInRoot() }
        .onFocusChanged { state.focused = it.isFocused }

/** 容器自己的坐标（同样不进 Compose 状态，理由见 [FocusDismissState.focusedBounds]）。 */
private class CoordinatesHolder {
    var value: LayoutCoordinates? = null
}

/**
 * 挂在**整块容器**（屏幕根、悬浮窗内容根）上：按下的点不在那个输入框里就清焦点。
 *
 * 在 **Initial 趟**读事件（父→子那一趟，子节点还没消费），并且**只读不消费** ——
 * 列表滚动、行长按、输入框自己的落光标全都照旧。命中输入框内则什么都不做
 * （点输入框别处 = 移动光标，本来就不该收键盘）。
 */
@Composable
fun Modifier.dismissFocusOnTapOutside(state: FocusDismissState): Modifier {
    val focusManager = LocalFocusManager.current
    val holder = remember { CoordinatesHolder() }
    return this
        .onGloballyPositioned { holder.value = it }
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (!state.focused) return@awaitEachGesture
                val here = holder.value ?: return@awaitEachGesture
                if (!state.focusedBounds.contains(here.localToRoot(down.position))) {
                    focusManager.clearFocus()
                }
            }
        }
}
