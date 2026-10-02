package com.autoscript.ui.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 一次刷新的**唯一入口**：顶栏那颗「刷新」和下拉手势共用同一份挂起状态。
 *
 * 为什么要有这么一层：四个屏各自"点刷新 → 起协程 → 拉数据"写过一遍之后，出现了三件
 * 各自为政的事 —— 转圈时长跟数据到没到没关系、连点两下会投两份并发读、下拉手势没法复用
 * 顶栏那套（因为它要求一个 `isRefreshing` 的布尔）。收在这里之后：
 * **转圈持续到读口返回**（不是定时器，也不是立即结束），**挂起中再触发直接丢弃**。
 *
 * 丢弃而不是排队：读口是"现取一份快照"的语义，连拉两下要的是最后那份，排队会把
 * 中间那次的结果也画一遍（列表闪两回）。这与 TG 下拉刷新的观感一致：手不停，圈不灭。
 */
@Stable
class RefreshAction internal constructor(
    private val refreshing: MutableState<Boolean>,
    private val scope: CoroutineScope,
    private val onRefresh: suspend () -> Unit,
) {
    /** 供下拉指示器读：**读口没返回就一直是 true**。 */
    val isRefreshing: Boolean get() = refreshing.value

    /** 顶栏按钮与下拉手势都调它（`::trigger` 直接当 `onClick`/`onRefresh` 传）。 */
    fun trigger() {
        if (refreshing.value) return
        scope.launch {
            refreshing.value = true
            try {
                onRefresh()
            } finally {
                // finally：读口抛了也得把圈收掉 —— 否则一次失败让本屏永远转圈、
                // 且再也触发不了刷新（`trigger` 见 true 就丢）。
                refreshing.value = false
            }
        }
    }
}

/**
 * 把「本屏怎么重读」交给 [RefreshAction] 托管（`onRefresh` 必须是**挂起**的：
 * 圈转到它返回为止；写成非挂起就没有"读完了"这个时刻可等）。
 */
@Composable
fun rememberRefreshAction(onRefresh: suspend () -> Unit): RefreshAction {
    val scope = rememberCoroutineScope()
    val state = remember { mutableStateOf(false) }
    // 传进来的 lambda 每次重组都是新的，但这份 RefreshAction 要跨重组留住；
    // 故经 rememberUpdatedState 取"最新那个"，而不是把首帧那个闭包钉死。
    val latest = rememberUpdatedState(onRefresh)
    return remember(scope, state) { RefreshAction(state, scope) { latest.value() } }
}

/**
 * 下拉刷新容器（M3 `PullToRefreshBox` + 本仓的 [RefreshAction]）。
 *
 * 四屏的列表都套它：TG 的列表就是"下拉即刷新"，而顶部那颗按钮在手指够不着的地方
 * （屏一大就得先滑到顶）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RefreshableBox(
    action: RefreshAction,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    PullToRefreshBox(
        isRefreshing = action.isRefreshing,
        onRefresh = action::trigger,
        modifier = modifier,
        content = content,
    )
}
