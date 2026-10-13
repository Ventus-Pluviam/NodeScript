package com.autoscript.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.overscroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 一次刷新的**唯一入口**：顶栏那颗「刷新」都走它（任务/管理/设置三屏）。
 *
 * 为什么要有这么一层：四个屏各自"点刷新 → 起协程 → 拉数据"写过一遍之后，出现了两件
 * 各自为政的事 —— 转圈时长跟数据到没到没关系、连点两下会投两份并发读。收在这里之后：
 * **挂起状态持续到读口返回**（不是定时器，也不是立即结束），**挂起中再触发直接丢弃**。
 *
 * 丢弃而不是排队：读口是"现取一份快照"的语义，连点两下要的是最后那份，排队会把
 * 中间那次的结果也画一遍（列表闪两回）。
 */
@Stable
class RefreshAction internal constructor(
    private val scope: CoroutineScope,
    private val onRefresh: suspend () -> Unit,
) {
    /**
     * 有没有一次读还在路上。**只有 [trigger] 读它**（丢弃判据）—— 本仓没有下拉手势，
     * 也就没有"刷新中"的可见指示：TG 的刷新是点一下就走，没有转圈位。
     */
    private var refreshing = false

    /** 顶栏那颗「刷新」调它（`::trigger` 直接当 `onClick` 传）。 */
    fun trigger() {
        if (refreshing) return
        // 标志**先立后 launch**：`launch` 的派发语义（immediate 与否）不该决定
        // "连点两下会不会投两份读"。
        refreshing = true
        scope.launch {
            try {
                onRefresh()
            } finally {
                // finally：读口抛了也得把标志清掉 —— 否则一次失败让本屏再也触发不了刷新。
                refreshing = false
            }
        }
    }
}

/**
 * 把「本屏怎么重读」交给 [RefreshAction] 托管（`onRefresh` 必须是**挂起**的：
 * 标志挂到它返回为止；写成非挂起就没有"读完了"这个时刻可等）。
 */
@Composable
fun rememberRefreshAction(onRefresh: suspend () -> Unit): RefreshAction {
    val scope = rememberCoroutineScope()
    // 传进来的 lambda 每次重组都是新的，但这份 RefreshAction 要跨重组留住；
    // 故经 rememberUpdatedState 取"最新那个"，而不是把首帧那个闭包钉死。
    val latest = rememberUpdatedState(onRefresh)
    return remember(scope) { RefreshAction(scope) { latest.value() } }
}

/**
 * 列表容器 —— 内容宿主，外加**下拉形变的落点**。
 *
 * **参考项目没有下拉刷新手势**（M3 的 `PullToRefreshBox` 已拆掉）。TG 的
 * 列表（`RecyclerListView`）只有滚动，刷新一律是显式动作 —— `ActionBar` 上那颗，
 * 或者 `DialogsActivity` 菜单里的那一项；`ChatAttachAlertDocumentLayout` 的文件列表
 * 连刷新项都没有（它读的是本地目录，进目录即重读）。本仓照此：**刷新入口 = 顶栏
 * 那颗「刷新」**，列表上没有第二条路径。
 *
 * 项目页**没有**刷新入口：它读的也是本地目录，与 TG 文件页同款
 * —— 进目录即重读，切回该页签也会重取（`TabReloadEffect`），不需要一颗手动刷新。
 *
 * 壳为什么还留着（而不是把四屏的 `RefreshableBox { … }` 拆成裸 `Box { … }`）：
 * 四屏的 content lambda 收的是 `BoxScope`，且"填满"由调用方传的 modifier 决定 ——
 * 壳在这里只是给"内容宿主"一个名字，四屏一行不用动。**它也是下拉形变的落点**
 * （见下），所以三屏一处接上、三处生效。
 *
 * ## 下拉形变（TG 的 `EdgeEffect`，2026-10-13 用户口径）
 *
 * 用户口径：「文件列表、任务列表、管理面板这些下拉时要有和 TG 一样的形变」。
 *
 * **TG 用的是什么**（`/tmp/tg` 12.10.6 实读，不是推测）：**就是 AOSP `EdgeEffect`
 * 本身，形变一个字没改**。`RecyclerListView.java:2160` 起全部相关代码只有这么一段 ——
 *
 * ```
 * public void setAdaptiveOverScroll() {
 *     adaptiveOverScroll = true;
 *     setOverScrollMode(OVER_SCROLL_NEVER);
 * }
 * // dispatchTouchEvent 里：
 * //   ACTION_DOWN   且 activeTouches 归零 → setOverScrollMode(OVER_SCROLL_ALWAYS)
 * //   ACTION_UP/CANCEL 且 activeTouches 归零 → setOverScrollMode(OVER_SCROLL_NEVER)
 * ```
 *
 * 即「**手指按着的时候才接形变**，其余时间一律关掉」，没有别的。另一个
 * `EdgeEffectFactory` 那条路（`EdgeEffectTrackerFactory`）TG 只是包了个
 * `TrackingEdgeEffect` 监听**可见性**（给 Blur3 判定要不要关模糊），
 * `super.onPull/onRelease` 原样转发 —— 同样没碰形变本身。
 *
 * 于是本屏照抄这两件事，各对应下面两行：
 * 1. **形变本体用平台那份** [ScrollableDefaults.overscrollEffect] —— 它就是
 *    `AndroidEdgeEffectOverscrollEffect`，内部持有 AOSP `EdgeEffect`（API 31+ 是
 *    `RenderNode` stretch，与 TG 跑的是**同一个平台件**），观感天然一致；
 * 2. **手指按着才接**（[StretchConnection] 的 [touching] 门 + [touchState] 那条
 *    `pointerInput`）—— 对应 TG 的 `OVER_SCROLL_ALWAYS`/`OVER_SCROLL_NEVER` 切换。
 *    只在这一段里把到边余量喂给它，其余时间喂了也白喂。
 *
 * **为什么不直接让 `LazyColumn` 自己那份形变管**（那才是"标准做法"）：Compose 只在
 * `shouldDispatchOverscroll = canScrollForward || canScrollBackward` 时才把它接上
 * （`ScrollingLogic.scrollByWithOverscroll`，字节码核过）—— **内容不满一屏的列表两个
 * 都是 false，一次都不触发**。项目页（几个文件）、设置顶页（两张卡）正是这种。
 * AOSP 与 TG 都没有这道门（`EdgeEffect` 是无条件接到边余量上的），所以 TG 的设置页
 * 不满一屏照样有形变 —— 这道门是 Compose 自己加的，不能拿它当 TG 的口径。
 *
 * 反过来把**列表自己那份**关掉（[LocalOverscrollConfiguration] 给 null）：
 * 长列表上它本来就会触发，与这里这一层叠着走就成了两层形变。不满一屏的列表它本来
 * 一次都不触发，关掉对它们没有影响。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RefreshableBox(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    // **先取平台那份形变、再关掉列表自己那份**：顺序不能反 ——
    // `ScrollableDefaults.overscrollEffect()` 读的就是 `LocalOverscrollConfiguration`，
    // 在 null 的下面调它拿到的是 `NoOpOverscrollEffect`（等于什么都没接上）。
    val effect = ScrollableDefaults.overscrollEffect()
    val touching = remember { mutableStateOf(false) }
    val connection = remember(effect, touching) { StretchConnection(effect, touching) }
    CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
        Box(
            modifier = modifier
                // TG 的 `OVER_SCROLL_ALWAYS` / `OVER_SCROLL_NEVER` 切换，就是这一条。
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            // 每一拍都重算「还有没有手指按着」：`changes.any { pressed }`
                            // 自己就含了 DOWN（有一根按下）与 UP/CANCEL（全部抬起）两种，
                            // 不用分别认 action —— 与 TG 那两行 `ACTION_*` 分支同义。
                            val event = awaitPointerEvent()
                            touching.value = event.changes.any { it.pressed }
                        }
                    }
                }
                .nestedScroll(connection)
                // 形变本体挂在这儿（API 31+ 是 `DrawStretchOverscrollModifier`，
                // 绘制时顺带 `updateSize`，尺寸由这一层自己的 bounds 给）。
                .overscroll(effect),
            content = content,
        )
    }
}

/**
 * 把**子节点没吃掉的到边余量**喂给平台那份 `EdgeEffect`（见 [RefreshableBox] 的 KDoc）。
 *
 * 三个方法与 `ScrollingLogic` 里那条内置路径同义，差别只有一处：
 * **不查 `canScrollForward/canScrollBackward`**（TG 也不查），且只在 [touching]
 * 为真时动手 —— 对应 TG 只在 `ACTION_DOWN` 之后开着 `OVER_SCROLL_ALWAYS`。
 */
@OptIn(ExperimentalFoundationApi::class)
private class StretchConnection(
    private val effect: OverscrollEffect,
    private val touching: MutableState<Boolean>,
) : NestedScrollConnection {

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.UserInput || available.y == 0f) return Offset.Zero
        // 手指已经抬起来了：形变交给 `onPostFling` 的 `applyToFling` 收尾（内部
        // `EdgeEffect.onRelease()`），这里不再喂。
        if (!touching.value) return Offset.Zero
        // `performScroll` 给 `{ Offset.Zero }`：滚动已经发生过了（这里拿的是 post-scroll
        // 的余量），再滚一次就是同一段位移走两遍。这与平台那条内置路径同义。
        val consumed = effect.applyToScroll(Offset(0f, available.y), source) { Offset.Zero }
        return Offset(0f, consumed.y)
    }

    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource,
    ): Offset {
        if (source != NestedScrollSource.UserInput || available.y == 0f) return Offset.Zero
        if (!touching.value) return Offset.Zero
        val taken = effect.applyToScroll(available, source) { Offset.Zero }
        return Offset(0f, taken.y)
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        // 松手/甩尾 → 弹回。内部走 `EdgeEffect.onRelease()`，与 TG 的 `onRelease()` 同一个。
        effect.applyToFling(available) { Velocity.Zero }
        return Velocity.Zero
    }
}
