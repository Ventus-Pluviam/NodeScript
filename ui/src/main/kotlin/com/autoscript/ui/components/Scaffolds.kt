package com.autoscript.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.autoscript.ui.state.StatusTone
import com.autoscript.ui.theme.ThemeColors

/**
 * 状态色 → 主题色的**唯一**映射（[StatusTone] 的语义出口）。
 *
 * 旧版每个屏各自写 `when (state) { GRANTED -> colors.primary; DEGRADED -> colors.tertiary; … }`，
 * 抄了 N 遍、色值还各叫各的。这里集中之后，"DEGRADED 是 ATTENTION 不是 PROBLEM"
 * 这条约定在一个函数里看得见，改配色也只动这一处。
 *
 * @Composable：色值来自 CompositionLocal（跟随主题切换），故不能是纯函数。
 */
@Composable
fun StatusTone.color(): Color {
    val palette = ThemeColors
    return when (this) {
        StatusTone.OK -> palette.success
        StatusTone.ATTENTION -> palette.warning
        StatusTone.PROBLEM -> palette.error
        StatusTone.LINK -> palette.accent
        StatusTone.NEUTRAL -> palette.text
        StatusTone.MUTED -> palette.textTertiary
    }
}

/**
 * **菜单/面板**里的状态色（与 [color] 分开的一档）。
 *
 * TG 的菜单项不是"列表里那种红"：列表用 `chats_sentError`，菜单里的删除项用
 * `text_RedRegular`（`ItemOptions.add(… isRed)` 那条路径，浅 `#FFCC2929` /
 * 深 `#FFEE686F`）。两者深浅两套都差一档，合成一个红就是抄错。
 *
 * 另一处不同：TG 的菜单项**默认不是蓝的**（`key_actionBarDefaultSubmenuItem` = 正文色），
 * 所以 [StatusTone.LINK] 在这一档里落到正文色 —— 蓝色只属于"可点的链接"，
 * 菜单里每一项都可点，全蓝等于没强调。
 */
@Composable
fun StatusTone.menuColor(): Color {
    val palette = ThemeColors
    return when (this) {
        StatusTone.PROBLEM -> palette.dangerText
        StatusTone.LINK, StatusTone.NEUTRAL -> palette.text
        StatusTone.OK -> palette.success
        StatusTone.ATTENTION -> palette.warning
        StatusTone.MUTED -> palette.textTertiary
    }
}

/**
 * 菜单项按下时的整块底色（TG `ActionBarMenuSubItem` 的 `selectorColor`）。
 *
 * 带后果的项（红字）在 TG 里连选择器都是红的：`DialogsActivity` 的删除项
 * `setSelectorColor(multAlpha(getThemedColor(key_text_RedBold), .12f))` ——
 * 按压反馈跟着文字色走，不是恒定的中性灰。
 */
@Composable
fun StatusTone.menuSelectorColor(): Color {
    val palette = ThemeColors
    // `Theme.multAlpha(color, .12f)` = 保留原 alpha 再乘 0.12（不是直接设 0.12）。
    return if (this == StatusTone.PROBLEM) palette.dangerText.copy(alpha = palette.dangerText.alpha * 0.12f)
    else palette.menuSelector
}

/**
 * 一段着色的文字。
 *
 * **不要**用它写「错误」以外的重要信息就靠改色区分 —— 色只是辅助，文案本身
 * 必须自足（三条纪律全靠文案，不靠色）。
 */
@Composable
fun ToneText(
    text: String,
    tone: StatusTone,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Ellipsis,
) {
    Text(
        text = text,
        color = tone.color(),
        style = style,
        modifier = modifier,
        maxLines = maxLines,
        overflow = overflow,
    )
}

/**
 * 段落小标题（TG 的 section header：小号、次级色、左右跟正文对齐）。
 *
 * 一屏里超过一处同样的次级标题时用它 —— 它比 M3 的 `titleSmall` 弱一档，
 * 正是"分组标签"而不是"标题"的分寸。
 */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = ThemeColors.textTertiary,
        style = MaterialTheme.typography.labelMedium,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

/**
 * 一条分隔线（1dp，[ThemeColors.divider]）。
 *
 * TG 的分隔线是**贴着内容的**（列表行底部一条，而不是每行上下都夹一条），
 * 所以默认只画 [indentDp] 左边距 —— 让它从正文列起点开始，跟左边的图标列错开。
 */
@Composable
fun Separator(indentDp: Int = 16, modifier: Modifier = Modifier) {
    Spacer(
        modifier
            .fillMaxWidth()
            .padding(start = indentDp.dp)
            .height(1.dp)
            .background(color = ThemeColors.divider),
    )
}

/**
 * 空态（TG 的 "No entries here"：居中、次级色、竖向留白）。
 *
 * **只有 `LoadState.Loaded` 且真的为空才允许用** —— 「没读到」和「真的没有」
 * 必须长得不一样（见 `LoadState`）。这里不替调用方判断，用法上的约束写在这一句。
 */
@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth().padding(vertical = 40.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = ThemeColors.textTertiary,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/**
 * 小圆点（列表行左侧的那一列标记）。
 *
 * TG 会话列表的"未读蓝点 / 置顶图标 / 静音图标"都在最左边一小列，
 * 这个圆点就是它的最小实现：直径 8dp，颜色即 [tone]。
 */
@Composable
fun Dot(tone: StatusTone, modifier: Modifier = Modifier, size: Int = 8) {
    Spacer(
        modifier
            .size(size.dp)
            .background(color = tone.color(), shape = RoundedCornerShape(percent = 50)),
    )
}

/**
 * 计数徽标（未读计数那种蓝底白字的小圆角块）。
 *
 * 计数**变化时弹一下**（先胀到 1.18 再回落）：这是 TG 里唯一一处"数字会动"的地方，
 * 用处不是好看 —— 控制台/任务中心的计数都在页面底部或顶栏，不弹这一下，用户根本
 * 注意不到"刚才多了一条"。动画只作用在 `graphicsLayer` 上（缩放不参与布局，
 * 不会把旁边的文字挤来挤去）；计数没变时 `LaunchedEffect` 不重跑，故不会每帧抖。
 *
 * @property text 计数本身；null 或空串不画 —— 「0」不是一件事，藏起来。
 */
@Composable
fun CountBadge(text: String?, modifier: Modifier = Modifier) {
    if (text.isNullOrEmpty()) return
    val palette = ThemeColors
    val pop = remember { Animatable(1f) }
    LaunchedEffect(text) {
        pop.snapTo(1f)
        pop.animateTo(1.18f, tween(durationMillis = 90))
        pop.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 900f))
    }
    Box(
        modifier = modifier
            // 最小宽度 12dp（`countWidth = max(dp(12), ceil(measureText))`）：
            // 一位数的计数在 TG 里是一颗**比字宽**的胶囊，不是贴着字的圆。
            .widthIn(min = CountBadgeMinWidth)
            .graphicsLayer {
                scaleX = pop.value
                scaleY = pop.value
            }
            .background(palette.badge, RoundedCornerShape(CountBadgeRadius))
            // 水平内边距 = radius − 0.5dp（`rectF` 的右边界比 `countWidth` 多出
            // `dp(radius - 0.5f)`，左右对称后就是这半个 dp）。
            .padding(horizontal = CountBadgeRadius - 0.5.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = palette.onBadge,
            // 13dp **加粗**（`CounterView` 的 `textPaint.setTypeface(AndroidUtilities.bold())`
            // + `setTextSize(dp(13))`）—— TG 的 `bold()` = Medium(500)，不是 Bold(700)。
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

/** 计数胶囊圆角（`CounterView.CounterDrawable.radius = 11.5f`）。 */
private val CountBadgeRadius = 11.5.dp

/** 计数胶囊最小宽度（`countWidth = Math.max(dp(12), …)`）。 */
private val CountBadgeMinWidth = 12.dp

/**
 * 一行「文字 + 尾部动作」的列表行骨架（TG `RowCell` 那种两栏结构）。
 *
 * 抽它不是为了少写两行，而是为了让四屏的行**左右内边距一致** —— 这是 Telegram
 * 观感里最显眼的一致性来源（内容左边距统一 16dp，右侧动作贴右）。
 */
@Composable
fun Cell(
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val row = Row(
        modifier = modifier
            .fillMaxWidth()
            .let { m -> if (onClick == null) m else m.pressable(onClick = onClick) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.let {
            Spacer(Modifier.width(12.dp))
            it()
        }
        Box(Modifier.weight(1f).padding(contentPadding)) { content() }
        trailing?.let {
            Spacer(Modifier.width(12.dp))
            it()
        }
    }
    row
}

/**
 * 一个"胶囊"选择按钮（TG 的 chip：`EnableTopicsActivity.TopicsLayoutSwitcher` 那两颗）。
 *
 * **几何与配色逐项抄 TG**：
 * - 圆角 **13dp**（`Theme.createRoundRectDrawable(dp(13), …)`）、高 **26dp**
 *   （`createFrame(WRAP_CONTENT, 26, CENTER)`）、水平内边距 **12dp**；
 * - 选中：底 `featuredStickers_addButton`（[ThemeColors.featuredButton]）、
 *   字 `windowBackgroundCheckText`（[ThemeColors.featuredButtonText] = 白）；
 *   未选中：**没有底**，字 `windowBackgroundWhiteGrayText2`（[ThemeColors.chipText]）；
 * - 字 14dp **Medium**（`makeTextView(…, 14, key, bold = true)`，`bold` 在本仓 = Medium）。
 *
 * **选中态是"两片各自缩放淡入淡出"**（TG 是两个 FrameLayout 各自
 * `scaleX/scaleY 0↔1` + alpha，`EASE_OUT_QUINT` 320ms），不是同一片变色 ——
 * 本仓只有一层，故把它读成"底与字各自渐变"，曲线与时长照抄 320ms / EASE_OUT_QUINT。
 *
 * @property selected 选中态（见上：底色 + 白字）。
 */
@Composable
fun PillButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val palette = ThemeColors
    // 选中态**渐变**而不是硬切：分段控件（如页签式的排期选择）连点几下时，
    // 底色/字色一帧一跳很像"没点上"，渐变过去才读得出"选中跑到这一格了"。
    val bg by animateColorAsState(
        targetValue = if (selected) palette.featuredButton else Color.Transparent,
        animationSpec = tween(durationMillis = ChipSwitchDurationMillis, easing = EaseOutQuint),
        label = "pillBg",
    )
    val fg by animateColorAsState(
        targetValue = when {
            !enabled -> palette.textTertiary
            selected -> palette.featuredButtonText
            else -> palette.chipText
        },
        animationSpec = tween(durationMillis = ChipSwitchDurationMillis, easing = EaseOutQuint),
        label = "pillFg",
    )
    Box(
        modifier = modifier
            .height(ChipHeight)
            .background(bg, RoundedCornerShape(ChipCornerRadius))
            .pressable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = ChipHorizontalPadding)
            .wrapContentHeight(Alignment.CenterVertically),
    ) {
        Text(text, color = fg, style = MaterialTheme.typography.labelLarge)
    }
}

/** chip 圆角（`createRoundRectDrawable(dp(13), …)`）。 */
private val ChipCornerRadius = 13.dp

/** chip 高（`createFrame(WRAP_CONTENT, 26, Gravity.CENTER)`）。 */
private val ChipHeight = 26.dp

/** chip 水平内边距（`setPadding(dp(12), 0, dp(12), 0)`）。 */
private val ChipHorizontalPadding = 12.dp

/** chip 选中态切换时长（TG 那两条 `setDuration(320)`）。 */
private const val ChipSwitchDurationMillis = 320

/**
 * 两栏对齐的键值行（「壳状态    已就绪」）。
 *
 * 参数名用 [label]/[value] 而不是左/右：横向排列的两栏在这屏一律是「项 / 值」，
 * 反过来会读错（老版把「已停用」这类**警告**也塞右边，跟值混在一起）。
 */
@Composable
fun LabeledRow(
    label: String,
    value: String,
    tone: StatusTone = StatusTone.NEUTRAL,
    modifier: Modifier = Modifier,
) {
    val palette = ThemeColors
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = label,
            color = palette.textTertiary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(88.dp),
        )
        ToneText(
            text = value,
            tone = tone,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 「回到顶部」浮动按钮。
 *
 * **诚实边界**：TG 的会话列表里**没有**这颗按钮（它靠长按拖拽/点状态栏回顶），
 * 所以这里没有"逐字抄"的对象。本仓取的是 TG 里**最近的那颗悬浮圆钮**的规格 ——
 * `ChatActivityBlurredRoundButton`（聊天页那颗浮在内容上的圆形按钮）：
 *
 * - **44dp 圆**（`BUTTON_SIZE = 44`，半径 `dp(BUTTON_SIZE / 2f)`）；
 * - 图标色 `glass_defaultIcon`（[ThemeColors.roundButtonIcon]，**半透明**：
 *   浅色 60% 黑、深色 63% 白 —— 不是强调色）；
 * - 底 `chat_messagePanelBackground`（[ThemeColors.roundButtonBackground]）+
 *   按下 `multAlpha(icon, .15f)`、圆角 inset 6dp；
 * - 状态切换走 `EASE_OUT_QUINT` **320ms**。
 *
 * 出现/消失仍是淡入 + 轻微上浮：TG 那颗的显隐也是淡入淡出（`animatorIsEnabled`
 * 那条 BoolAnimator），不是硬切。
 *
 * @param visible 由调用方按滚动位置判定（`firstVisibleItemIndex > 0`）。
 */
@Composable
fun ScrollToTopButton(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = ThemeColors
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(ScrollToTopDurationMillis, easing = EaseOutQuint)) +
            slideInVertically(tween(ScrollToTopDurationMillis, easing = EaseOutQuint)) { it / 2 },
        exit = fadeOut(tween(ScrollToTopDurationMillis, easing = EaseOutQuint)) +
            slideOutVertically(tween(ScrollToTopDurationMillis, easing = EaseOutQuint)) { it / 2 },
        modifier = modifier,
    ) {
        Box(
            Modifier
                .size(ScrollToTopSize)
                .background(palette.roundButtonBackground, CircleShape)
                .pressable(
                    role = Role.Button,
                    // `multAlpha(color, .15f)`：图标色压到 15% 当按下底。
                    overlay = palette.roundButtonIcon.copy(alpha = palette.roundButtonIcon.alpha * 0.15f),
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            // 字形当图标（同顶栏的返回箭头）：不引图标依赖，不为一个箭头多拉一个包。
            Text("↑", color = palette.roundButtonIcon, style = MaterialTheme.typography.titleLarge)
        }
    }
}

/** 回顶钮直径（`ChatActivityBlurredRoundButton.BUTTON_SIZE = 44`）。 */
private val ScrollToTopSize = 44.dp

/** 回顶钮的显隐时长（`BoolAnimator(…, EASE_OUT_QUINT, 320)`）。 */
private const val ScrollToTopDurationMillis = 320
