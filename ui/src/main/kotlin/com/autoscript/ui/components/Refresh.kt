package com.autoscript.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.overscroll
import androidx.compose.runtime.Composable
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
 * 壳在这里只是给"内容宿主"一个名字，四屏一行不用动。**它也是下拉形变的代管层**
 * （见下），所以四屏一处接上、四处生效。
 *
 * ## 下拉形变：代管一份平台 `EdgeEffect`（2026-10-13 用户口径，第 4 次尝试）
 *
 * 用户口径：「文件列表、任务列表、管理面板这些下拉时要有和 TG 一样的形变」。
 *
 * **TG 用的是什么**（`/tmp/tg` 12.10.6 实读）：就是 AOSP `EdgeEffect` 本身，
 * 形变一个字没改 —— `RecyclerListView.java:2160` 的 `setAdaptiveOverScroll()` +
 * `dispatchTouchEvent` 那两行 `OVER_SCROLL_ALWAYS`/`NEVER` 切换，即「**手指按着的
 * 时候才接形变**」。`EdgeEffect` 在 View 体系里是**无条件**接到边余量上的，
 * AOSP 与 TG 都没有"能不能滚"的门。
 *
 * **Compose 的那道门**（`ScrollingLogic.scrollByWithOverscroll` 只在
 * `shouldDispatchOverscroll = canScrollForward || canScrollBackward` 时才接形变，
 * 字节码核过）：**内容不满一屏的列表两个都是 false，一次都不触发**。项目页（几个文件）、
 * 设置顶页（两张卡）正是这种。View 体系没有这道门 —— 这是 Compose 自己加的。
 *
 * 前三次为什么空转（`22a6fd0` / `b5f2084` / `e193c4d`，如实记账）：
 * 全死在 [OverscrollEffect.applyToScroll] 内部第一行 ——
 * `if (containerSize.isEmpty()) return performScroll(delta)`。`containerSize`
 * 只有挂在**会画东西的 modifier**上才会被 `updateSize` 填上（`DrawStretch…`/
 * `DrawGlow…` 的 `draw()` 里那一行）。自接的那份没有绘制节点，尺寸恒 `Zero`，
 * 喂多少都早退 —— 不是门的问题，是**尺寸从来没给出去**。
 *
 * 做法只有一句：**余量经 nested-scroll 链漏到壳，由 [FeedConnection] 喂给壳自己的
 * 那份平台形变** ——
 * 1. 行上按着拖：内层 `LazyColumn` 赢了手势但一像素不滚（内容不满一屏），
 *    到边余量经链漏下来，[FeedConnection] 喂给 `effect`；
 * 2. 空白处按下：列表没有手势节点参战，拖拽位移原样经链落到壳，同一条路照喂 ——
 *    所以 `86f16c1` 那版的假 state `scrollable` 是多余的，已删（见下）；
 * 3. 形变是平台那份 `EdgeEffect`（API 31+ 是 `RenderNode` stretch，与 TG 同一个平台件），
 *    绘制与尺寸都由壳自己的 `overscroll(effect)` 节点给 —— 不依赖列表容器；
 * 4. 长列表自己那份形变**不关**：行上是列表自己接、空白处是这层接，各管各的手势，
 *    不叠加 —— `e193c4d` 那次 `provides null` 把任务中心本来能用的那份关掉了，
 *    那是净负收益，这次不重犯。
 *
 * `86f16c1` 那版还挂了一个自己的 `scrollable`（假 state 骗开门，管空白处），
 * 它与 [FeedConnection] 共用同一份 `effect` —— 两份 `ScrollingLogic` 各自记
 * `scrollCycleInProgress`、各自调 `finish()`，手动归零走哪条、周期标志留在哪条
 * 全看运气，「回弹时快时慢轮换」正是这么来的。本版已把 `scrollable` 连同假 state
 * 一起删掉，只剩 [FeedConnection] 一条路。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RefreshableBox(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    // 平台那份形变（`AndroidEdgeEffectOverscrollEffect`）：绘制层由下面的
    // `overscroll(effect)` 提供，尺寸由壳自己的 bounds 给 —— 不依赖列表容器。
    // 这一行是前三版（`22a6fd0`/`b5f2084`/`e193c4d`）全灭的根因：自接的那份没有绘制节点，
    // `updateSize` 从没人调过，`applyToScroll` 见 `containerSize.isEmpty()` 就早退。
    // 平台那份形变：绘制与尺寸都由壳自己的 `overscroll` 节点给，不依赖列表容器。
    val effect = ScrollableDefaults.overscrollEffect()
    val feed = remember(effect) { FeedConnection(effect) }
    Box(
        modifier = modifier
            .overscroll(effect)
            .nestedScroll(feed),
        content = content,
    )
}

/**
 * 把**列表漏下来的到边余量**喂给壳那份 `effect`（见 [RefreshableBox] 的 KDoc）。
 *
 * 与 `ScrollingLogic` 里那条内置路径同义，差别只有一处：**不查
 * `canScrollForward/canScrollBackward`**（TG 也不查 —— `EdgeEffect` 无条件接余量）。
 * `performScroll` 给 `{ Offset.Zero }`：滚动已经发生过了（拿的是 post-scroll 的余量），
 * 再滚一次就是同一段位移走两遍。
 */
@OptIn(ExperimentalFoundationApi::class)
private class FeedConnection(
    private val effect: OverscrollEffect,
) : NestedScrollConnection {

    /** 当前被拉出去多远（px，有符号:正=顶边被下拉），onPostFling归零用。 */
    private var lastPull = 0f

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.UserInput || available.y == 0f) return Offset.Zero
        // 没形变时一像素不碰：pre-scroll 是内层列表滚动**之前**，这里吃了列表就没得滚
        // —— 长列表正常滑动时抽搐一下正是这么来的（壳先把位移变形成变，列表再滚半截）。
        // 只有已有形变时才动手，且只做回缩（反向拉回去），绝不新拉。
        if (!effect.isInProgress) return Offset.Zero
        val consumed = effect.applyToScroll(Offset(0f, available.y), source) { Offset.Zero }
        lastPull += consumed.y
        if (!effect.isInProgress) lastPull = 0f
        return Offset(0f, consumed.y)
    }

    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource,
    ): Offset {
        if (source != NestedScrollSource.UserInput || available.y == 0f) return Offset.Zero
        val taken = effect.applyToScroll(available, source) { Offset.Zero }
        lastPull += taken.y
        if (!effect.isInProgress) lastPull = 0f
        return Offset(0f, taken.y)
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        // pulled 记的是被拉出去多远（px，有符号）。
        // 归零要还的 = -pulled（正好抵消），再多给
        // RELEASE_OVERSHOOT_PX 确保 distance 归零触发 finish()。
        // 还完之后 lastPull 清零 —— 下一次 onPreScroll
        // 同向会重新累。
        val pulled = lastPull
        if (pulled != 0f) {
            lastPull = 0f
            val back = -pulled + (if (pulled > 0f) -RELEASE_OVERSHOOT_PX else RELEASE_OVERSHOOT_PX)
            effect.applyToScroll(Offset(0f, back), NestedScrollSource.UserInput) { Offset.Zero }
            // 必须再跟一次 applyToFling：它内部会把
            // scrollCycleInProgress 置回 false（上面那次 applyToScroll
            // 又把它置成了 true）。不清的话下一次拖拽
            // 的“catch”逻辑被跳过，动画在“直接拉回”
            // 和“正常弹回”之间轮换 —— 用户口径
            // “一下猛的收回去，一下又正常慢慢收”正是这条。
            // 顺便把剩下的交给 animateToReleaseIfNeeded
            // （内部 EdgeEffect.onRelease()，与 TG 同一个）带动画收尾。
            effect.applyToFling(Velocity.Zero) { Velocity.Zero }
        }
        return Velocity.Zero
    }
}

/** 回零那一下多还的量（px）：确保 distance 归零触发 finish()。见 onPostFling。 */
private const val RELEASE_OVERSHOOT_PX = 2f
