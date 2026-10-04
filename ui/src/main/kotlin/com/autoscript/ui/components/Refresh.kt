package com.autoscript.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
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
     * 有没有一次读还在路上。**只有 [trigger] 读它**（丢弃判据）—— 2026-10-04 拆掉
     * 下拉手势后，本仓没有"刷新中"的可见指示：TG 的刷新是点一下就走，没有转圈位。
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
 * 2026-10-04 拆掉下拉刷新（M3 `PullToRefreshBox`）：**参考项目没有这个手势**。TG 的
 * 列表（`RecyclerListView`）只有滚动，刷新一律是显式动作 —— `ActionBar` 上那颗，
 * 或者 `DialogsActivity` 菜单里的那一项；`ChatAttachAlertDocumentLayout` 的文件列表
 * 连刷新项都没有（它读的是本地目录，进目录即重读）。本仓照此：**刷新入口 = 顶栏
 * 那颗「刷新」**，列表上没有第二条路径。
 *
 * 项目页**没有**刷新入口（2026-10-04 批 37）：它读的也是本地目录，与 TG 文件页同款
 * —— 进目录即重读，切回该页签也会重取（`TabReloadEffect`），不需要一颗手动刷新。
 *
 * 壳为什么还留着（而不是把四屏的 `RefreshableBox { … }` 拆成裸 `Box { … }`）：
 * 四屏的 content lambda 收的是 `BoxScope`，且"填满"由调用方传的 modifier 决定 ——
 * 壳在这里只是给"内容宿主"一个名字，四屏一行不用动。
 */
@Composable
fun RefreshableBox(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier = modifier, content = content)
}
