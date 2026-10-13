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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import kotlin.math.abs
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
 * 壳在这里只是给"内容宿主"一个名字，四屏一行不用动。**它现在也是下拉形变的落点**
 * （见下），所以四屏一处接上、四处生效。
 *
 * ## 下拉形变（TG 的 overscroll stretch，2026-10-13 用户口径）
 *
 * 用户口径：「文件列表、设置列表这些下拉时要有和 TG 一样的形变效果（编辑页下拉就有）」。
 * 编辑页有而列表没有，**不是两套实现**，是同一套被 Compose 的一道门卡住了：
 * `ScrollingLogic.shouldDispatchOverscroll = canScrollForward || canScrollBackward`
 * （`Scrollable.kt`）—— **内容不满一屏的列表两个都是 false**，于是平台形变一次都不触发。
 * 编辑页的正文恒 ≥ 一屏（`heightIn(min = viewportHeight)` + 长文件），所以它有。
 * 项目页（几个文件）、设置顶页（两张卡）恰恰都是"不满一屏"的那种列表。
 *
 * 做法（**用平台自己的那份形变**，不自己写变换）：
 * 1. [ScrollableDefaults.overscrollEffect] 拿到的就是 AOSP 的 `EdgeEffect` 形变
 *    （API 31+ 是 stretch、以下是 glow）—— 与 TG 的 `RecyclerView` 默认 overscroll
 *    是**同一个东西**，观感天然一致；
 * 2. `LocalOverscrollConfiguration provides null` 把**列表自己那一份**关掉
 *    （`rememberOverscrollEffect` 见 null 回 `NoOpOverscrollEffect`）—— 不关就是两层
 *    形变叠着走（长列表上尤其明显）；
 * 3. [StretchConnection] 在**子节点没吃掉的余量**上喂这份形变（`onPostScroll`），
 *    并在反向时先把它拉回去（`onPreScroll`，与平台那条 pre-scroll 同义）；
 *    松手时 `onPostFling` 交给 `EdgeEffect.onRelease()` 弹回。
 *    这条缝**不经过** `shouldDispatchOverscroll` 那道门，所以不满一屏的列表照样有形变。
 *
 * 边界（如实记账）：形变量由 [StretchConnection] 自己估（平台不给读距离的公开口，
 * `displacement`/`distance` 都是 internal），估偏的后果只是"反向那一下少拉一点/多拉一点"，
 * 不会卡住滚动 —— 反向消费**永远不超过**估算的形变量。
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
    val connection = remember(effect) { StretchConnection(effect) }
    CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
        Box(modifier = modifier.nestedScroll(connection).overscroll(effect), content = content)
    }
}

/**
 * 把**子节点没吃掉的滚动余量**喂给平台形变（见 [RefreshableBox] 的 KDoc）。
 *
 * 三段各司其职，与 `ScrollingLogic` 里那条内置路径同义：
 * - `onPreScroll`：**反向**时先把已有的形变拉回去（`toRelax` 夹在估算量之内），
 *   正向继续拉时一个像素都不碰 —— 碰了就成了"越拉越紧还滚不动"；
 * - `onPostScroll`：余量（列表到边之后剩下的那截）转成形变；
 * - `onPostFling`：松手/甩尾结束 → `applyToFling` 内部 `EdgeEffect.onRelease()` 弹回。
 *
 * `performScroll` 一律给 `{ Offset.Zero }`：**滚动已经发生过了**（我们拿的是 post-scroll
 * 的余量），这里再滚一次就是同一段位移走两遍。
 */
// `OverscrollEffect` 及其三个成员都是 `@ExperimentalFoundationApi` —— 用它的类型/调它的方法
// 都要在这一层显式 opt-in（`RefreshableBox` 那个注解管不到这个类）。
@OptIn(ExperimentalFoundationApi::class)
private class StretchConnection(private val effect: OverscrollEffect) : NestedScrollConnection {

    /** 当前形变量的**估算**（px，带符号：正 = 顶部被下拉）。见 KDoc 的边界那条。 */
    private var stretched = 0f

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.UserInput || available.y == 0f) return Offset.Zero
        // 没有形变可拉：顺手把估算清零（进程重建、被别处清过都会走到这里）。
        if (!effect.isInProgress) {
            stretched = 0f
            return Offset.Zero
        }
        // 同向 = 还在往同一边拉，不归这一段管（留给 onPostScroll 继续加）。
        if (available.y * stretched >= 0f) return Offset.Zero
        val toRelax = available.y.coerceIn(-abs(stretched), abs(stretched))
        if (toRelax == 0f) return Offset.Zero
        val consumed = effect.applyToScroll(Offset(0f, toRelax), source) { Offset.Zero }
        stretched += consumed.y
        if (!effect.isInProgress) stretched = 0f
        return Offset(0f, consumed.y)
    }

    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource,
    ): Offset {
        if (source != NestedScrollSource.UserInput || available.y == 0f) return Offset.Zero
        val taken = effect.applyToScroll(available, source) { Offset.Zero }
        if (taken.y != 0f) stretched += taken.y
        return Offset(0f, taken.y)
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        effect.applyToFling(available) { Velocity.Zero }
        stretched = 0f
        return Velocity.Zero
    }
}
