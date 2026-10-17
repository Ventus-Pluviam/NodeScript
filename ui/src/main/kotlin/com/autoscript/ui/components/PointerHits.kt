package com.autoscript.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/**
 * **让本层在命中测试里"有主"**：一次触摸只要落在这一层的地界里，就在这一层记下一次
 * 直接命中 —— 于是 Compose **不再往下层（后面的兄弟子树）继续找**。
 *
 * 出处（2026-10-16 用户报）：管理面板的子页在前台时，点子页里**没有手势管的空白处**，
 * 点击会穿透到底下面板那层 —— 同一坐标恰是面板的「依赖管理」入口行，点空白就打开了
 * 依赖管理；退出来之后子页的返回路径乱了，日志管理退不出来。
 *
 * **病**（Compose UI 1.7.3 的口径，`InnerNodeCoordinator.hitTestChild` + `HitTestResult.hit`）：
 * 命中测试在每一层对子节点**从最上面那个开始**试（`zSortedChildren.reversedAny`），
 * 只有在"这一个子节点没命中任何 pointerInput 节点"，或"命中的节点声明了
 * `sharePointerInputWithSiblings() == true`"时才继续试它下面的兄弟。而
 * **`Modifier.pointerInput` 的默认是 `false`**（不共享，见 `PointerInputModifierNode` 的
 * KDoc「If false, then this PointerInputModifierNode will not allow siblings under it to
 * respond to events」）——也就是说：**只要子页在这一点上有一个 pointerInput 节点，
 * 下面的面板就不会被命中**；而子页的空白处一个都没有（列表只装滚动、行只装各自的
 * pressable），`hasHit()` 于是为 false，循环继续往下走，面板那层的入口行照收这次触摸。
 * 修法因此是"给上层补一个占位的手势节点"，不是"去下层挡"。
 *
 * **它不消费任何事件**（`awaitPointerEvent()` 拿到就丢）：挡穿透的作用完全来自
 * "这一层被命中了"这个事实，所以本层自己的按压、滚动、文本框一字未动。
 *
 * **为什么不改成"把下层的事件吃掉"**（本文件上一版就是那么写的，写错了）：
 * `Modifier.clickable` 的 `detectTapAndPress` 用的是 `awaitFirstDown(requireUnconsumed = true)`
 * （foundation 1.7.3 源码；那支 KDoc 的原话是"if the first down event is consumed somewhere
 * else, the entire gesture will be skipped"）——同一次触摸里，**任何一个节点在 Initial 趟
 * 消费掉 down，都会把上层自己的点击一起废掉**（Initial 趟整体先于 Main 趟派发，谁先谁后
 * 都一样）。给顶层加消费节点同样不行：它只让顶层自己进命中列表，拦不住下层兄弟子树
 * 那次独立命中。断点只能在命中测试这一层，而这一层给的正是"命中即挡住"。
 */
fun Modifier.claimPointerHits(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            // 只为"在命中列表里占位"：拿到事件什么都不做，更不 consume。
            awaitPointerEvent()
        }
    }
}
