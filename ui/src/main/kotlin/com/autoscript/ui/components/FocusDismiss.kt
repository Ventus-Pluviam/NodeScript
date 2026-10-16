package com.autoscript.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo

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
 *
 * **跨窗口那一半见 [DismissFocusOnWindowBlur]**：本文件的两条修饰符都只在**同一棵
 * 组合**里生效，而悬浮窗是另一个窗口 —— 那个方向靠窗口焦点收，两条合起来才盖全。
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
 * 把**本面**的"清焦点"动作登记进 [FocusDismissRegistry]（别的窗口点一下就能清到这里）。
 *
 * 挂在每个有输入框的面的根上（与 [rememberFocusDismissState] 同一处调用点）：
 * 登记是**每个面自己**的事 —— 注册表不猜谁有输入框，只保管"怎么清我"这一个动作。
 * `DisposableEffect` 的键是那份 `FocusManager`：组合所有者换了（换窗口/重建）就重登记，
 * 离场即注销（不留悬空引用去清一棵已经不在的组合）。
 *
 * 只在**真的聚焦着**时才清：没聚焦的面被别的窗口点一下不该有任何动作
 * （清一个空的焦点是无害的，但会白跑一次 Compose 焦点事务）。
 */
@Composable
fun RegisterFocusDismiss(state: FocusDismissState, separateWindow: Boolean = false) {
    val focusManager = LocalFocusManager.current
    DisposableEffect(focusManager, separateWindow) {
        val unregister = FocusDismissRegistry.register(separateWindow) { except ->
            if (except !== focusManager && state.focused) focusManager.clearFocus()
        }
        onDispose { unregister() }
    }
}

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
 * **跨窗口**的焦点清理登记处（2026-10-16 用户报：悬浮窗输入框与应用内搜索框互相收不掉）。
 *
 * 病：两个窗口（应用 Activity 与悬浮窗）各有**自己那份** `FocusManager`
 * （`LocalFocusManager` 是每个组合所有者一份），而 [dismissFocusOnTapOutside] 那套只看得到
 * 自己这棵组合的指针事件 —— 于是「点 A 窗口」这件事，B 窗口**永远不知道**。
 * 用户看到的是两边光标各闪各的（点应用界面时悬浮窗还在闪；点悬浮窗时应用还在闪）。
 *
 * 药：把每个"有输入框的面"的"清焦点"动作登记进来，两件事各调各的入口：
 * - [clearSeparateWindows]：**应用窗口收到触摸**时调（`MainActivity.dispatchTouchEvent`）
 *   —— 只清**独立窗口**（系统悬浮窗）那份。系统窗是另一个窗口，它那棵组合**收不到**
 *   应用里的指针事件，这是唯一说得清"用户点在应用里"的地方；
 * - [clearAppFaces]：**悬浮窗被点 / 其输入框拿到焦点**时调 —— 清应用侧各面那份。
 *
 * **为什么不互相清全部（`clearAll`）**：清自己会打断正在进行的输入 —— 用户点一下输入框
 * 内部（放光标），先被清一次再被重新聚焦，输入法会藏一次再起一次（可见的闪）。
 * 于是两边都只清**对方**：[clearSeparateWindows] 不碰应用面，[clearAppFaces] 靠 `except`
 * 排除调用者自己。
 *
 * **in-app 兜底窗（无悬浮窗权限）不在"独立窗口"那一档**：它与应用同在 Activity 窗口里，
 * 自己那棵组合**收得到**应用里的点按（[dismissFocusOnTapOutside] 按 bounds 判），
 * 不需要也不该走 [clearSeparateWindows]（走了就会在自己输入框里点一下 = 清一次再聚焦，
 * 正是上面那条要避免的闪）。
 */
object FocusDismissRegistry {
    private class Entry(val separateWindow: Boolean, val clear: (FocusManager?) -> Unit)

    private val lock = Any()
    private val entries = LinkedHashSet<Entry>()

    /**
     * 登记一个面的"清焦点"动作；返回注销句柄（`DisposableEffect` 的 `onDispose` 用）。
     *
     * @param separateWindow 这个面住在**独立窗口**里（系统悬浮窗）—— 决定它归
     *   [clearSeparateWindows] 管还是归 [clearAppFaces] 管，两档互斥（见对象 KDoc）。
     */
    fun register(separateWindow: Boolean, clear: (FocusManager?) -> Unit): () -> Unit {
        val entry = Entry(separateWindow, clear)
        synchronized(lock) { entries += entry }
        return { synchronized(lock) { entries -= entry }; Unit }
    }

    /** 应用窗口收到触摸 → 清**独立窗口**（系统悬浮窗）那份焦点；应用面一个不碰。 */
    fun clearSeparateWindows() {
        synchronized(lock) { entries.filter { it.separateWindow } }.forEach { it.clear(null) }
    }

    /** 悬浮窗被点 / 其输入框拿到焦点 → 清**应用侧**各面那份；[except] 是调用者自己（跳过）。 */
    fun clearAppFaces(except: FocusManager? = null) {
        synchronized(lock) { entries.filter { !it.separateWindow } }.forEach { it.clear(except) }
    }
}

/**
 * 挂在**整块容器**（屏幕根、悬浮窗内容根）上：按下的点不在那个输入框里就清焦点。
 *
 * 在 **Initial 趟**读事件（父→子那一趟，子节点还没消费），并且**只读不消费** ——
 * 列表滚动、行长按、输入框自己的落光标全都照旧。命中输入框内则什么都不做
 * （点输入框别处 = 移动光标，本来就不该收键盘）。
 *
 * [clearAppFaces] = 这一下点在输入框外时，**顺带清应用侧各面**的焦点（走
 * [FocusDismissRegistry]，自动排除自己那份 `FocusManager`）。只有悬浮窗内容该开它 ——
 * 应用内的面开它，等于"点应用里任何一处都把悬浮窗的输入收掉"，而用户在应用里点来点去
 * 与悬浮窗没关系。
 */
@Composable
fun Modifier.dismissFocusOnTapOutside(
    state: FocusDismissState,
    clearAppFaces: Boolean = false,
): Modifier {
    val focusManager = LocalFocusManager.current
    val holder = remember { CoordinatesHolder() }
    return this
        .onGloballyPositioned { holder.value = it }
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val here = holder.value ?: return@awaitEachGesture
                // 判据分两半，**不能合成一个 `state.focused` 的早退**：
                // - 清自己：只在本面真聚焦着、且这一下点在输入框外时才做（见本修饰符 KDoc）；
                // - 清**别的窗口**：与"本面聚没聚焦"无关 —— 用户报的那条正是
                //   「点搜索框（应用）→ 再点悬浮窗」：那一刻**悬浮窗自己没聚焦**，
                //   若跟着早退，应用的搜索框就永远收不掉（2026-10-16 用户报的原症状）。
                val outside = !state.focusedBounds.contains(here.localToRoot(down.position))
                if (outside && state.focused) focusManager.clearFocus()
                if (outside && clearAppFaces) FocusDismissRegistry.clearAppFaces(except = focusManager)
            }
        }
}

/**
 * 「**窗口**失焦就收键盘」（2026-10-16 用户报：悬浮窗输入框与首页搜索框互相收不掉）。
 *
 * 病：[dismissFocusOnTapOutside] 只看得到**自己这棵组合**里的指针事件，而系统悬浮窗是
 * **另一个窗口**（`SystemLogWindowHost` 的 `TYPE_APPLICATION_OVERLAY` + 离屏 `ComposeView`，
 * 与 MainActivity 各自一棵 Compose 树）—— 指针事件不跨窗口，于是两个方向都收不掉：
 * 点应用界面时悬浮窗不知道、点搜索框时悬浮窗也不知道（设备实测：两边光标各闪各的）。
 *
 * 药：**窗口焦点是跨窗口唯一可见的那条信号**。`LocalWindowInfo.isWindowFocused` 由
 * `AndroidComposeView.onWindowFocusChanged` 推（`WindowInfoImpl.setWindowFocused`），
 * 每个窗口各自一份 —— 本效应在「本窗口刚失去焦点」这一跳上清一次焦点。
 *
 * 与 [dismissFocusOnTapOutside] 的分工：那条管**同窗口内**点空白处（窗口焦点不变），
 * 这条管**跨窗口**（点别的窗口 = 本窗口失焦）。两条都挂才盖全，且互不重复：
 * 同窗口点击不触发本效应，跨窗口点击不产生本窗口的指针事件。
 *
 * 挂在**每个有输入框的面**的根上（与 [dismissFocusOnTapOutside] 同一份 [state]）：
 * 项目页、任务中心、悬浮窗内容各一处。窗口焦点在系统弹窗/分屏/画中画下同样会变 ——
 * 那些场合收键盘也是对的（窗口不在前台了，光标不该继续闪）。
 *
 * `@OptIn` 是**必须**的：`WindowInfo` 在 Compose 1.7.3 仍标着
 * `@ExperimentalComposeUiApi`（反编译 `ui-release.aar` 实证：类级 RuntimeInvisibleAnnotations
 * 里就这一个），不 opt-in 是**编译错误**而不是警告。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun DismissFocusOnWindowBlur(state: FocusDismissState) {
    val windowInfo = LocalWindowInfo.current
    val focusManager = LocalFocusManager.current
    val focused = windowInfo.isWindowFocused
    // 只在「失焦」这一跳清一次（`LaunchedEffect` 的键就是它）——写成每帧读会让
    // 每次重组都清一遍焦点，输入框刚被点中就被清掉。
    LaunchedEffect(focused) {
        if (!focused && state.focused) focusManager.clearFocus()
    }
}
