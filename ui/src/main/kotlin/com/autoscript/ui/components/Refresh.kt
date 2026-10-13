package com.autoscript.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
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
 * ## 下拉形变（TG 的橡皮筋，2026-10-13 用户口径）
 *
 * 用户口径：「文件列表、任务列表、管理面板这些下拉时要有和 TG 一样的形变」。
 *
 * **为什么不能交给平台那份**（上一版接的就是它，红了）：Compose 只在
 * `shouldDispatchOverscroll = canScrollForward || canScrollBackward` 时才把形变接上
 * （`ScrollingLogic.scrollByWithOverscroll`）—— **内容不满一屏的列表两个都是 false，
 * 一次都不触发**。项目页（几个文件）、管理面板（五个入口）恰恰全是"不满一屏"的那种。
 * 想在壳上自己喂一份 `OverscrollEffect` 也补不上：那份只有被 `scrollable` 接住才会被
 * `updateSize`，容器尺寸恒为 `Zero`，`applyToScroll` 第一行就早退。
 *
 * 所以这里自己画，链路只有两段：
 * 1. [StretchConnection] 在 `nestedScroll` 里把「列表到边之后剩下的那截余量」记成一个数
 *    （`snapTo`，随手写不启动动画）；反向滚动优先把这份余量还给列表，**永远不多还**；
 * 2. [Modifier.graphicsLayer] 把内容按这个数沿纵向撑开 —— 锚点在**顶端**，内容顶边不动、
 *    整张内容被拉长（TG 的 `EdgeEffect` stretch 就是这个观感），底部溢出的那截落到屏幕外。
 *    形变量按比例封顶（[STRETCH_RATIO]），拉到底也不会夸张。
 *
 * 松手走 [STRETCH_SPEC] 那条弹簧弹回；弹的过程中重新按下，[snapTo] 与 `animate` 的取消
 * 语义互相接手，不另记状态。形变量是**自估**的（平台不给读拉伸距离的公开口，`distance` /
 * `displacement` 都是 internal），估偏的后果只是回弹快一点/慢一点，不会卡滚动。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RefreshableBox(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    // 当前被拉长出去多少 px（正 = 顶边被拉住往下拽）。graphicsLayer 的 lambda 在**绘制期**
    // 读它 —— 形变因此只重绘、不重组。
    val stretch = remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val connection = remember(stretch) { StretchConnection(stretch, scope) }
    // 把**列表自己那份**平台形变关掉（null → `NoOpOverscrollEffect`）：长列表上它本来就会
    // 触发，与这一层叠着走就成了两层形变。不满一屏的列表它本来一次都不触发，关掉无影响。
    CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
        Box(
            modifier = modifier
                .nestedScroll(connection)
                .graphicsLayer {
                    val pulled = stretch.floatValue
                    if (pulled != 0f && size.height > 0f) {
                        // 顶端为原点：内容顶边不动、整张被拉长（TG stretch 同款观感）。
                        transformOrigin = TransformOrigin(0.5f, 0f)
                        scaleY = 1f + minOf(abs(pulled) / size.height, STRETCH_RATIO)
                    }
                },
            content = content,
        )
    }
}

/** 纵向最多撑到几倍（满量下拉时）。见 [RefreshableBox] 的 KDoc。 */
private const val STRETCH_RATIO = 0.22f

/** 弹回用：欠阻尼一点、手感干脆（与 TG `EdgeEffect.onRelease()` 的回缩同一档）。 */
private val STRETCH_SPEC = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)

/**
 * 把**子节点没吃掉的滚动余量**记成形变量（见 [RefreshableBox] 的 KDoc）。
 *
 * 三段各司其职：
 * - `onPreScroll`：**反向**时先把已经拉出去的那截还给列表（夹在余量之内，绝不多还），
 *   同向继续拉不碰 —— 碰了就成"越拉越紧还滚不动"；
 * - `onPostScroll`：余量（列表到边之后剩下的那截）转成形变；
 * - `onPostFling`：松手 → 弹簧弹回 `0f`（列表没被拉过就直接返回，不起协程）。
 */
private class StretchConnection(
    private val stretch: MutableFloatState,
    private val scope: CoroutineScope,
) : NestedScrollConnection {

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.UserInput || available.y == 0f) return Offset.Zero
        val pulled = stretch.floatValue
        // 同向 = 还在往同一边拉，不归这一段管（留给 onPostScroll 继续加）。
        if (pulled == 0f || available.y * pulled >= 0f) return Offset.Zero
        val toRelax = available.y.coerceIn(-abs(pulled), abs(pulled))
        if (toRelax == 0f) return Offset.Zero
        stretch.floatValue = pulled + toRelax
        return Offset(0f, toRelax)
    }

    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource,
    ): Offset {
        if (source != NestedScrollSource.UserInput || available.y == 0f) return Offset.Zero
        stretch.floatValue += available.y
        return Offset(0f, available.y)
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        val from = stretch.floatValue
        // 没拉过（长列表甩到边而已）就别起协程。
        if (from == 0f) return Velocity.Zero
        stretch.floatValue = 0f
        scope.launch {
            animate(initialValue = from, targetValue = 0f, animationSpec = STRETCH_SPEC) { value, _ ->
                stretch.floatValue = value
            }
        }
        // 甩尾速度**不再往下传**：形变是这一层自己画的，传下去只会被外层拿去滚别的东西。
        return Velocity.Zero
    }
}
