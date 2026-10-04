package com.autoscript.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.ui.state.StatusTone
import com.autoscript.ui.theme.ThemeColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 长按上下文菜单（TG 的招牌交互：列表行长按弹出"对这一行能做什么"）。
 *
 * 本仓的列表行**不在行里摆满按钮**（那会让每行都长成工具栏），改成长按出菜单 ——
 * 但**已经摆出来的按钮不撤**：长按是"另一个入口"，不是唯一入口，两条路都通才不会
 * 出现"这功能藏得太深"。
 *
 * **版式逐项对着 TG 的 `ActionBarPopupWindowLayout` + `ActionBarMenuSubItem` 写**：
 *
 * 1. **容器**：圆角 [MenuCornerRadius]、底 [ThemeColors.menuBackground]
 *    （`actionBarDefaultSubmenuBackground`），壳与开/关动画在 [MenuPopup]
 *    （M3 `DropdownMenu` 的进出场硬编码且是 M3 那套 scale+fade，不是 TG 的）。
 * 2. **四周内边距 8dp**（`setPadding(dp(8), dp(8), dp(8), dp(8))`）：横纵向都是
 *    [MenuSidePadding] —— 自建壳后没有 M3 那层自带的 `DropdownMenuVerticalPadding`
 *    可搭车，四边都自己补。
 * 3. **条目高 48dp、左右内边距 18dp**（`itemHeight = 48` / `setPadding(dp(18), 0, …)`）、
 *    文字 **16sp 常规字重**（`setTextSize(COMPLEX_UNIT_DIP, 16)`，且全文没有
 *    `setTypeface` —— 菜单项不是 Medium）。
 * 4. **按下整块染色**（`selectorColor = key_dialogButtonSelector`，圆角 12dp）；
 *    M3 的水波不是 TG 的形态，故不用 `DropdownMenuItem` 自带的 ripple，改由
 *    [pressable] 的整块覆盖画（颜色单独传 [StatusTone.menuSelectorColor]）。
 * 5. **分组间隙 8dp**（`ItemOptions.addGap()` 的 `GapView` =
 *    `createLinear(MATCH_PARENT, 8)`）。TG 里它是**容器画的**：`drawChild` 跳过
 *    GapView，改在裁成圆角（12dp、四周缩 8dp）的画布上补画 —— 因为 GapView 要画出
 *    自身上下的投影（`greydivider`：上下各 ~5px 渐变，峰值 alpha 14/255），不裁的话
 *    投影会顶穿容器圆角。这里等价做法：间隙本体铺 [ThemeColors.menuSeparator]
 *    （`actionBarDefaultSubmenuSeparator`，深浅两档都比菜单底暗/亮一档），上下沿各画
 *    一条 [MenuGapShadowAlpha] 的渐变（[MenuGap] 的实现注释）。
 */
@Composable
fun ContextMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    actions: List<MenuEntry>,
) {
    // TG 的 cascade 只数"非 GapView 的可见子项"（startAnimation 里 GapView continue）。
    val visibleCount = actions.count { it is MenuAction }
    MenuPopup(
        expanded = expanded,
        visibleCount = visibleCount,
        onDismissRequest = onDismiss,
    ) {
        // 弹出序（cascade 的 position）只数 MenuAction，且与行的下标是两个序 ——
        // 混用一个计数器会把排在间隙后面的行顶没（下标超前于弹出序）。
        var actionPosition = 0
        actions.forEach { entry ->
            when (entry) {
                is MenuGap -> MenuGapRow()
                is MenuAction -> {
                    val at = actionPosition
                    actionPosition += 1
                    MenuItemRow(entry, at, onDismiss)
                }
            }
        }
    }
}

/** 菜单里的一行（带 TG 的 cascade 浮现：从 [MenuPopup] 的 local 读进度）。 */
@Composable
private fun MenuItemRow(action: MenuAction, position: Int, onDismiss: () -> Unit) {
    val palette = ThemeColors
    val anim = LocalMenuAnim.current
    val selector = action.tone.menuSelectorColor()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MenuSidePadding)
            .heightIn(min = MenuItemHeight)
            // cascade 进度在 draw 期读（graphicsLayer 块里）：动画每帧只重绘不重组。
            .graphicsLayer {
                val a = anim.itemProgress(position)
                translationY = (1f - a) * if (anim.belowMenu) -MenuItemShift.toPx() else MenuItemShift.toPx()
                alpha = a * if (action.enabled) 1f else 0.5f
            }
            .clip(RoundedCornerShape(MenuItemSelectorRadius))
            .pressable(
                enabled = action.enabled,
                role = Role.Button,
                overlay = selector,
                onClick = {
                    // 先关菜单再执行：动作里常带对话框/复制回执，菜单还开着会盖住它们。
                    onDismiss()
                    action.onClick()
                },
            )
            .padding(horizontal = MenuItemHorizontalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = action.label,
            color = if (action.enabled) action.tone.menuColor() else palette.textTertiary,
            style = MenuItemTextStyle,
        )
    }
}

/**
 * 分组间隙（TG `ItemOptions.addGap()` 的 `GapView`：MATCH_PARENT × 8dp）。
 *
 * `GapView` = 底色（`actionBarDefaultSubmenuSeparator`）+ `onDraw` 里叠一片
 * `greydivider`（MULTIPLY 上 `windowBackgroundGrayShadow` 纯黑）：xxhdpi 实测
 * 上下各 ~5px 的白色渐变、贴边 alpha 14/255 往中间衰减到 0 —— 是"凹槽"的投影，
 * 不是一条实线。裁剪与"谁画它"是容器的事（KDoc 第 5 条），间隙本体只负责把
 * 这两层画对：底色铺满、上下沿各一条 [MenuGapShadowAlpha] 渐变。
 */
@Composable
private fun MenuGapRow() {
    val palette = ThemeColors
    Box(
        Modifier
            .fillMaxWidth()
            .height(MenuGapHeight)
            .background(palette.menuSeparator)
            .drawBehind {
                val shadow = MenuGapShadowAlpha
                drawRect(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = shadow),
                        1f to Color.Black.copy(alpha = 0f),
                    ),
                    size = Size(size.width, size.height / 2),
                )
                drawRect(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0f),
                        1f to Color.Black.copy(alpha = shadow),
                    ),
                    topLeft = Offset(0f, size.height / 2),
                    size = Size(size.width, size.height / 2),
                )
            },
    )
}

/** 间隙上下沿的投影峰值（`greydivider.9.png` xxhdpi 实测 14/255）。 */
private const val MenuGapShadowAlpha = 14f / 255f

/**
 * 菜单容器**四周**的内边距（`ActionBarPopupWindowLayout` 的 `setPadding(dp(8), …)`）。
 *
 * 自建壳后不再有 M3 自带的 `DropdownMenuVerticalPadding` 可搭车（批 37 借的是它，
 * 现在还回来了）：四边都是这同一个数。
 */
private val MenuSidePadding = 8.dp

/** 分组间隙高（`ItemOptions.addGap()` 的 `createLinear(MATCH_PARENT, 8)`）。 */
private val MenuGapHeight = 8.dp

/** 菜单项的左右内边距（`ActionBarMenuSubItem.setPadding(dp(18), 0, dp(18), 0)`）。 */
private val MenuItemHorizontalPadding = 18.dp

/**
 * 菜单项的文字：**16sp 常规字重**（`ActionBarMenuSubItem` 只设 `setTextSize(16)`，
 * 全文没有 `setTypeface` —— 用 `titleMedium` 那种 Medium 会让菜单比 TG 重一档）。
 */
private val MenuItemTextStyle = TextStyle(fontSize = 16.sp)

/**
 * 菜单项高（`ActionBarMenuSubItem.itemHeight = 48`）。
 */
private val MenuItemHeight = 48.dp

/**
 * 菜单项按下色的圆角（`ItemOptions.setupSelectors()` 多数路径传的 **12**）。
 *
 * TG 有三条传圆角的地方，看着互相矛盾：`ItemOptions.setupSelectors` 传 12、
 * `ActionBarPopupWindow.setupRadialSelectors` 传 6、而 `ActionBarMenuSubItem.updateSelectorBackground`
 * **根本不传**（只用两个 boolean 判首/末项）。走第三条时字段保持默认值
 * `selectorRad = 12`（`ActionBarMenuSubItem.java:47`）—— 而 `ActionBarMenuItem.showPopup`
 * 走的正是第三条（`popupLayout.updateRadialSelectors()`），故 **12** 才是这条路要的值；
 * blur 那侧也独立写着 `setRadius(dp(12))`，两条旁证同一个数。
 */
private val MenuItemSelectorRadius = 12.dp

/**
 * TG 式底部操作面板（`BottomSheet` + `BottomSheetCell`）：用于一条内容的上下文操作，
 * 不把一串按钮硬塞进列表行。
 *
 * 顶栏或锚点附近的短菜单继续使用 [ContextMenu]；文件详情和多步操作提升成底部
 * 面板，触控目标更大、上下文也更清楚。组件只负责壳，调用方仍决定动作和副作用。
 *
 * **版式逐项对着 TG 写**（`BottomSheet.java` / `BottomSheetCell`）：
 *
 * 1. **圆角 24dp**：底图是 `sheet_shadow_round.9.png`，实测四档密度的顶角半径
 *    24.47 / 24.20 / 23.55 / 23.43dp —— 不是 M3 默认那 28dp。
 * 2. **没有抓取条**：`BottomSheet` 全文没有任何 drag handle（下拉是靠面板本体拖动），
 *    故这里**不画** `BottomSheetDefaults.DragHandle()` —— 那是自加的。
 * 3. **标题行高 48dp、16sp、`dialogTextGray2`**（非 bigTitle 档：
 *    `setTextSize(16)` + `setPadding(dp(16), 0, dp(16), dp(8))` + `CENTER_VERTICAL`）。
 * 4. **条目行高 48dp、内边距 16dp、16sp `dialogTextBlack`**
 *    （`BottomSheetCell.onMeasure` 的 `height = currentType == 2 ? 80 : 48`）。
 * 5. **遮罩 alpha 51/255**（`dimBehindAlpha = 51`）—— M3 默认 32% 更深，要压到 20%。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionBottomSheet(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = ThemeColors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = SheetCornerRadius, topEnd = SheetCornerRadius),
        containerColor = palette.sheetBackground,
        scrimColor = Color.Black.copy(alpha = SheetScrimAlpha),
        // TG 没有抓取条（见 KDoc 第 2 条）：给它一个空实现，而不是默认那根把手。
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                // TG 的容器底部内边距：applyBottomPadding ? dp(8) : 0。
                .padding(bottom = SheetBottomPadding),
        ) {
            Text(
                text = title,
                color = palette.sheetTitleText,
                style = MaterialTheme.typography.titleMedium,
                // 非 bigTitle 档：16dp 左右、上 0、下 8dp（见 KDoc 第 3 条）。
                modifier = Modifier
                    .fillMaxWidth()
                    .height(SheetRowHeight)
                    .wrapContentHeight(Alignment.CenterVertically)
                    .padding(start = SheetSidePadding, end = SheetSidePadding, bottom = SheetTitleBottomPadding),
            )
            content()
        }
    }
}

/** 面板顶角（`sheet_shadow_round.9.png` 实测 ≈23.4–24.5dp）。 */
private val SheetCornerRadius = 24.dp

/** 面板遮罩（`dimBehindAlpha = 51`）。 */
private const val SheetScrimAlpha = 51f / 255f

/** 面板行的统一高（`BottomSheetCell.onMeasure`：type 0 与标题行都是 48）。 */
private val SheetRowHeight = 48.dp

/** 面板左右内边距（`BottomSheetCell` 与标题行都是 16dp）。 */
private val SheetSidePadding = 16.dp

/** 标题行下内边距（`titleView.setPadding(dp(16), 0, dp(16), dp(8))`）。 */
private val SheetTitleBottomPadding = 8.dp

/** 面板底部内边距（`containerView.setPadding(…, applyBottomPadding ? dp(8) : 0)`）。 */
private val SheetBottomPadding = 8.dp

/**
 * 底部面板的一条大触控目标（TG `BottomSheetCell` type 0）。
 *
 * 高 48dp、左右内边距 16dp、16sp、正文色 —— 不是"更大的 bodyLarge + 24dp 内边距"。
 * 按下反馈走 TG 的选择器色（`getSelectorDrawable(false)` = `key_listSelector`），
 * 与菜单项的 `dialogButtonSelector` **不是同一个键**。
 */
@Composable
fun ActionBottomSheetItem(
    label: String,
    tone: StatusTone = StatusTone.NEUTRAL,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val palette = ThemeColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SheetRowHeight)
            .pressable(
                enabled = enabled,
                role = Role.Button,
                overlay = palette.menuSelector,
                onClick = onClick,
            )
            .padding(horizontal = SheetSidePadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = when {
                !enabled -> palette.textTertiary
                // TG 的 `BottomSheetCell` 默认是 `dialogTextBlack`（正文色），
                // 红字是 `setTextColor(key_text_RedRegular)` 那条显式路径。
                tone == StatusTone.PROBLEM -> palette.dangerText
                else -> palette.sheetItemText
            },
            style = SheetItemTextStyle,
        )
    }
}

/** 面板条目文字（`BottomSheetCell` type 0：`setTextSize(COMPLEX_UNIT_DIP, 16)`）。 */
private val SheetItemTextStyle = TextStyle(fontSize = 16.sp)

/**
 * 菜单里的一项。
 *
 * @property tone 让"取消/停止"这类带后果的项着色（TG 的删除项是红的：`text_RedRegular`）。
 *   **缺省不是蓝的** —— TG 的菜单项默认走 `actionBarDefaultSubmenuItem`（正文色），
 *   着色只留给"带后果"那一档（见 [StatusTone.menuColor]）。
 * @property enabled 挂起中（操作在途）时置 false —— 与行里那些胶囊**同一条口径**，
 *   否则会出现"胶囊灰着、菜单里还能点"的漏口。
 */
data class MenuAction(
    val label: String,
    val onClick: () -> Unit,
    val tone: StatusTone = StatusTone.NEUTRAL,
    val enabled: Boolean = true,
) : MenuEntry

/**
 * 菜单内的分组间隙（TG `ItemOptions.addGap()`：`GapView` = MATCH_PARENT × 8dp）。
 *
 * 放进 [MenuAction] 同一张表（而不是调用方在项之间插 Divider）：动作序列与间隙
 * 是同一份菜单定义，拆开就会出现"加一项忘了挪间隙"的漂移。
 */
data object MenuGap : MenuEntry

/** 菜单表的一项：正常动作或分组间隙（[MenuGap]）。 */
sealed interface MenuEntry

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
