package com.autoscript.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.autoscript.ui.state.StatusTone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 长按上下文菜单（TG 的招牌交互：列表行长按弹出"对这一行能做什么"）。
 *
 * 本仓的列表行**不在行里摆满按钮**（那会让每行都长成工具栏），改成长按出菜单 ——
 * 但**已经摆出来的按钮不撤**：长按是"另一个入口"，不是唯一入口，两条路都通才不会
 * 出现"这功能藏得太深"。
 */
@Composable
fun ContextMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    actions: List<MenuAction>,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        for (action in actions) {
            DropdownMenuItem(
                text = {
                    ToneText(
                        text = action.label,
                        tone = action.tone,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                enabled = action.enabled,
                onClick = {
                    // 先关菜单再执行：动作里常带对话框/复制回执，菜单还开着会盖住它们。
                    onDismiss()
                    action.onClick()
                },
            )
        }
    }
}

/**
 * 菜单里的一项。
 *
 * @property tone 让"取消/停止"这类带后果的项着色（TG 的删除项是红的）。
 * @property enabled 挂起中（操作在途）时置 false —— 与行里那些胶囊**同一条口径**，
 *   否则会出现"胶囊灰着、菜单里还能点"的漏口。
 */
data class MenuAction(
    val label: String,
    val onClick: () -> Unit,
    val tone: StatusTone = StatusTone.LINK,
    val enabled: Boolean = true,
)

/**
 * 长按触感（`HapticFeedbackType.LongPress`）。
 *
 * 为什么得自己调：foundation 的 `combinedClickable` **不做触感反馈**（1.7.3 的
 * `ClickableNode` 里没有任何 haptic 引用）—— 不自己调，长按就是"按下去只有视觉高亮、
 * 手感上什么都没发生"，而长按恰恰是最需要确认"我按到了"的手势。
 */
@Composable
fun rememberLongPressFeedback(): () -> Unit {
    val haptics = LocalHapticFeedback.current
    return remember(haptics) {
        { haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
    }
}

/**
 * 复制到剪贴板 + 一句会自己消失的回执。
 *
 * 回执不是装饰：控制台那一屏**整行都是可复制的**（点一下即复制），而"复制成功"在
 * 系统里是静默的 —— 没有回执，用户只能靠粘贴来确认自己点没点中。TG 的做法也是弹一句
 * "已复制"再自己消失，这里照那个节奏（1.6s）。
 */
@Stable
class CopyAction internal constructor(
    private val message: MutableState<String?>,
    private val visible: MutableState<Boolean>,
    private val scope: CoroutineScope,
    private val put: (String) -> Unit,
) {
    /** 最近一次复制的那句（**不清空**：回执淡出时还要画它，见 [CopyNotice]）。 */
    val text: String? get() = message.value

    /** 回执此刻在不在。 */
    val noticeVisible: Boolean get() = visible.value

    /** @param label 回执文案（如"已复制该行"）；@param content 真正进剪贴板的原文。 */
    fun copy(label: String, content: String) {
        put(content)
        message.value = label
        visible.value = true
        // 令牌：连点两下时，先起的那次不许把后起的那句提前收掉。
        val mine = ++token
        scope.launch {
            delay(NOTICE_MILLIS)
            if (token == mine) visible.value = false
        }
    }

    private var token = 0

    private companion object {
        const val NOTICE_MILLIS = 1600L
    }
}

/** 取一份复制口（一屏一份，跨重组留住）。 */
@Composable
fun rememberCopyAction(): CopyAction {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val message = remember { mutableStateOf<String?>(null) }
    val visible = remember { mutableStateOf(false) }
    val latest = rememberUpdatedState(clipboard)
    return remember(scope, message, visible) {
        CopyAction(message, visible, scope) { latest.value.setText(AnnotatedString(it)) }
    }
}

/** 复制回执那一行（顶部滑入、淡出）。列表里当一条 item 用。 */
@Composable
fun CopyNotice(action: CopyAction, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = action.noticeVisible,
        enter = fadeIn() + slideInVertically { -it / 2 },
        exit = fadeOut(),
        modifier = modifier,
    ) {
        // 画的是 [CopyAction.text]（不清空），故淡出动画期间不会变成空白。
        ToneText(
            text = action.text.orEmpty(),
            tone = StatusTone.OK,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}
