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
 * 列表容器 —— **只有内容宿主这一件事，没有手势**。
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
 * 壳在这里只是给"内容宿主"一个名字，四屏一行不用动。
 *
 * ## 下拉形变：这里**什么都不做**（2026-10-13，三次尝试全撤）
 *
 * 形变由 `LazyColumn`/`verticalScroll` **自带的那份平台形变**负责，本屏不插手
 * —— 这就是"平台缺省"的行为，也是前两次绕路之前的状态。
 *
 * 撤掉的两次与失败原因（如实记账，不删）：
 * - `22a6fd0` 在壳上自接 `OverscrollEffect`：`applyToScroll` 见
 *   `containerSize.isEmpty()` 就早退，且 `applyToFling` 不发信号 —— 空转。
 * - `e193c4d` 改成平台 `EdgeEffect` + 手指按着才接：仍然空转（`onPostScroll`
 *   不见效果），而 `LocalOverscrollConfiguration provides null` 把**本来能用**的
 *   长列表那份关掉了 —— 这是净负收益，用户口径「任务中心拉了没反应」正是这一条。
 *
 * **不满一屏的列表为什么没有形变**（项目页几个文件、管理面板五个入口、设置顶页两张卡）：
 * Compose 只在 `shouldDispatchOverscroll = canScrollForward || canScrollBackward` 时
 * 才把形变接上（`ScrollingLogic.scrollByWithOverscroll`，字节码核过）。AOSP 与 TG
 * **都没有这道门** —— `EdgeEffect` 是无条件接到边余量上的，所以 TG 的设置页不满一屏
 * 照样有形变。这道门在 Compose 内部，自绘/自喂两条路都绕不开（要绕只能改
 * `ScrollingLogic` 本身，那要 fork Compose 源码）。这条路值不值得走是产品决定，
 * 不该由实现细节顺带带过去。
 */
@Composable
fun RefreshableBox(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier = modifier, content = content)
}
