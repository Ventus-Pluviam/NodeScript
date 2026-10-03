package com.autoscript.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Indication
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.semantics.Role
import com.autoscript.ui.theme.ThemeColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 按压反馈 = **整块纯色覆盖**，不是 Material 的圆形水波。
 *
 * TG 的列表行按下时是"整行变暗"（选择器是一层纯色 drawable），页签按下是"整格变暗"。
 * 与 M3 水波的区别不在好看与否，而在**它画的是不是"你按的那一行"**：水波从指尖扩散、
 * 波纹圆未必落在行边界内（`bounded` 只是把它裁掉，不是让它贴行），而行高只有 40dp 上下时，
 * 圆没铺满就已经松手了。纯色覆盖是"按下即整行亮起"，在小行高、快速滑动点按的场景里更跟手。
 *
 * 实现走 `IndicationNodeFactory`（foundation 1.7 起的正路）：节点只在**挂载期**订阅
 * 交互流，按下/松开改一个 `mutableStateOf`，绘制期读它 —— 于是每次按下只失效这一层的
 * 绘制，不触发重组（旧的 `rememberUpdatedInstance` + `Modifier.drawBehind` 写法会把
 * 整个 Composable 拖进重组）。
 */
private class FlatPressIndication(private val overlay: Color) : IndicationNodeFactory {

    override fun create(interactionSource: InteractionSource): DelegatableNode =
        FlatPressNode(interactionSource, overlay)

    // 这两个不是礼节性实现：`IndicationNodeFactory` 要参与 Modifier 的相等判断，
    // 缺了它每次重组都会被当成"新的手势修饰符"，节点被拆掉重建、订阅丢失。
    override fun equals(other: Any?): Boolean =
        other is FlatPressIndication && other.overlay == overlay

    override fun hashCode(): Int = overlay.hashCode()
}

private class FlatPressNode(
    private val interactionSource: InteractionSource,
    private val overlay: Color,
) : Modifier.Node(), DrawModifierNode {

    private var pressed by mutableStateOf(false)
    private var job: Job? = null

    override fun onAttach() {
        job = coroutineScope.launch {
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> pressed = true
                    is PressInteraction.Release, is PressInteraction.Cancel -> pressed = false
                }
            }
        }
    }

    override fun onDetach() {
        job?.cancel()
        job = null
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        // 覆盖画在内容**之上**（TG 的按下变暗是盖住文字与图标的，不是垫在底下）。
        if (pressed) drawRect(overlay)
    }
}

/** 当前主题下的按压反馈（跟随深浅主题换色：浅色加深、深色提亮）。 */
@Composable
fun rememberPressIndication(): Indication {
    val overlay = ThemeColors.pressedOverlay
    return remember(overlay) { FlatPressIndication(overlay) }
}

/**
 * 带 TG 按压反馈的 `Modifier.clickable`。
 *
 * 全仓的可点区域都走这里 —— 不直接调 `clickable`：Compose 在没给 `indication` 时
 * **什么都不画**（不是"默认水波"），这正是重构后"能点但按下去没反应"的来源。
 *
 * @param role 无障碍角色；页签传 [Role.Tab]、按钮传 [Role.Button]，读屏据此改念法。
 */
@Composable
fun Modifier.pressable(
    enabled: Boolean = true,
    role: Role? = null,
    onClick: () -> Unit,
): Modifier {
    val indication = rememberPressIndication()
    val source = remember { MutableInteractionSource() }
    return clickable(
        interactionSource = source,
        indication = indication,
        enabled = enabled,
        role = role,
        onClick = onClick,
    )
}

/**
 * 带 TG 按压反馈的 `Modifier.combinedClickable` —— 给"长按出菜单"用。
 *
 * 长按是本仓从 TG 借的**招牌交互**：会话列表长按出上下文菜单，而不是把每个操作都摆成
 * 一行按钮。放在 [Interaction] 这一层是因为它必须和点击共享同一套按压反馈与语义角色 ——
 * 分开写两份 `combinedClickable` 迟早会出现"点有反馈、长按没反馈"。
 */
@OptIn(ExperimentalFoundationApi::class) // combinedClickable 在 foundation 1.7 仍是实验 API
@Composable
fun Modifier.pressableLongPress(
    enabled: Boolean = true,
    role: Role? = null,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
): Modifier {
    val indication = rememberPressIndication()
    val source = remember { MutableInteractionSource() }
    return combinedClickable(
        interactionSource = source,
        indication = indication,
        enabled = enabled,
        role = role,
        onLongClick = onLongClick,
        onClick = onClick,
    )
}
