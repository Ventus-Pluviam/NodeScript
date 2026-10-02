package com.autoscript.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.autoscript.ui.state.StatusTone
import com.autoscript.ui.theme.ThemeColors

/**
 * Telegram 式顶栏（`ActionBar`）：56dp 高、标题左对齐、副标题小一号次级色、右侧动作区。
 *
 * 为什么顶栏自带 [subtitle] 这一档：TG 的 ActionBar 大量用副标题（在线人数、连接状态、
 * "正在输入…"），而副标题一多，"把状态塞进标题"的老写法就露馅了。这里给标题下方
 * 留一个固定位置，各屏爱用不用，但不用就得把 [subtitle] 传 null 而不是塞进标题。
 *
 * 高度固定 56dp 而非 `TopAppBar` 的自适应：四屏都不是可滚动标题，且固定高度让
 * "顶栏 → 内容" 的起点在四屏完全一致（滚动时内容整体位移不会顶穿标题）。
 */
@Composable
fun ActionBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    subtitleTone: StatusTone = StatusTone.MUTED,
    onBack: (() -> Unit)? = null,
    actions: (@Composable () -> Unit)? = null,
) {
    val palette = ThemeColors
    // 副标题色**渐变**而不是硬切：副标题就是"壳/连接/授权"的当前结论，结论翻面时
    // 从绿滑到红，比一帧变色更容易被眼睛接住（TG 的状态行同样是渐变过去的）。
    val subtitleColor by animateColorAsState(
        targetValue = subtitleTone.color(),
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "barSubtitle",
    )
    Column(
        modifier
            .fillMaxWidth()
            .background(palette.surface),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = if (onBack == null) 16.dp else 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            onBack?.let {
                // 返回按钮：44dp 触控目标（Material 最小可达性），图标用字形 "‹" ——
                // 不引图标依赖，那会为了三个箭头多拉一个包。
                Box(
                    Modifier
                        .size(44.dp)
                        .pressable(role = Role.Button, onClick = it),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("‹", color = palette.accent, style = MaterialTheme.typography.titleLarge)
                }
                Spacer(Modifier.width(4.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = palette.text,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        color = subtitleColor,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            actions?.let {
                Spacer(Modifier.width(8.dp))
                it()
            }
        }
        Separator(indentDp = 0)
    }
}

/** 顶栏右侧那个文字动作（刷新 / 提交）。 */
@Composable
fun ActionBarAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tone: StatusTone = StatusTone.LINK,
) {
    Box(
        modifier = modifier
            .pressable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        ToneText(
            text = text,
            tone = if (enabled) tone else StatusTone.MUTED,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/**
 * 底部页签条（TG 的底部导航：图标 + 文字，选中项蓝字并带一条 2dp 指示线）。
 *
 * 不引 `NavigationBar`：M3 那条自带 Material 的高度与配色，与 TG 的观感差得远，
 * 而且它的选中指示是胶囊不是下划线。四个页签只有字形（本仓不引图标字体），
 * 选中点标与旧版一致（`·` 前缀 → 这里换成真正的那条蓝线）。
 *
 * **指示线是一条在整条栏上平移的横线**，不是每格各画一条：每格各画就只能"瞬移"
 * （旧实现做的是 20dp ↔ 0dp 的宽度硬切），而 TG 的观感恰恰来自那条线**滑**过去。
 * 选中的标签色同步渐变，两者一起完成"切换"这件事。
 *
 * @property label 短标签（顶栏标题给全称，这里给两字以内的短名 —— 窄屏四等分塞不下全称）。
 * @property badge 计数（未读那种蓝底白字）；null/空不画。
 */
@Composable
fun TabBar(
    tabs: List<TabItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = ThemeColors
    Column(modifier.fillMaxWidth().background(palette.surface)) {
        Separator(indentDp = 0)
        BoxWithConstraints(Modifier.fillMaxWidth().height(56.dp)) {
            Row(Modifier.fillMaxSize()) {
                tabs.forEachIndexed { index, tab ->
                    val active = index == selected
                    val labelColor by animateColorAsState(
                        targetValue = if (active) palette.accent else palette.tabIdle,
                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                        label = "tabLabel",
                    )
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .pressableSelectable(selected = active, role = Role.Tab) { onSelect(index) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        CountBadge(tab.badge)
                        Text(
                            text = tab.label,
                            color = labelColor,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                        )
                    }
                }
            }
            if (tabs.isNotEmpty()) {
                // 四格等宽，所以目标位置是纯算术：第 selected 格的中心减去半条线宽。
                // 首次组合时 animateDpAsState 直接落在目标值（不从 0 滑过来），
                // 故开屏不会出现"指示线从最左飞过去"。
                val tabWidth = maxWidth / tabs.size
                val targetX = tabWidth * selected + (tabWidth - TabIndicatorWidth) / 2
                val x by animateDpAsState(
                    targetValue = targetX,
                    animationSpec = spring(
                        // 略带回弹（dampingRatio < 1）：TG 的指示线到位时有一点"刹车"感，
                        // 纯 tween 是匀速停，看着发木。
                        dampingRatio = 0.82f,
                        stiffness = Spring.StiffnessMedium,
                    ),
                    label = "tabIndicator",
                )
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .offset(x = x)
                        .padding(bottom = 8.dp)
                        .width(TabIndicatorWidth)
                        .height(2.dp)
                        .background(palette.tabIndicator, RoundedCornerShape(1.dp)),
                )
            }
        }
    }
}

/** 页签指示线宽度（TG 是短横线，不铺满整格）。 */
private val TabIndicatorWidth = 20.dp

/** 页签条的一项。 */
data class TabItem(
    val label: String,
    val badge: String? = null,
)

/**
 * 整屏骨架：顶栏 + 内容 + （可选）底页签条。
 *
 * 四屏过去各自 `Column { Text(标题, headlineMedium); … }` 起手，标题样式与内边距
 * 各写一遍。这里统一之后，"标题在顶栏、内容从分隔线下开始"这条式在四屏必然一致，
 * 也让页签条落在内容滚动区之外（不会跟着列表滚走）。
 */
@Composable
fun ScaffoldScreen(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    subtitleTone: StatusTone = StatusTone.MUTED,
    action: (@Composable () -> Unit)? = null,
    tabs: List<TabItem>? = null,
    selectedTab: Int = 0,
    onSelectTab: (Int) -> Unit = {},
    content: @Composable (Modifier) -> Unit,
) {
    Column(modifier.fillMaxSize().background(ThemeColors.background)) {
        ActionBar(title = title, subtitle = subtitle, subtitleTone = subtitleTone, actions = action)
        Box(Modifier.weight(1f)) {
            content(Modifier.fillMaxSize())
        }
        if (tabs != null) {
            TabBar(tabs = tabs, selected = selectedTab, onSelect = onSelectTab)
        }
    }
}
