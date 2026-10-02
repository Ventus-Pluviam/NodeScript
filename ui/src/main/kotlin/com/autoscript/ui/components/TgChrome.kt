package com.autoscript.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.autoscript.ui.state.StatusTone
import com.autoscript.ui.theme.TgTheme

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
fun TgActionBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    subtitleTone: StatusTone = StatusTone.MUTED,
    onBack: (() -> Unit)? = null,
    actions: (@Composable () -> Unit)? = null,
) {
    val tg = TgTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .background(tg.surface),
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
                        .clickable(onClick = it),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("‹", color = tg.accent, style = MaterialTheme.typography.titleLarge)
                }
                Spacer(Modifier.width(4.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = tg.text,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        color = subtitleTone.color(),
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
        TgDivider(indentDp = 0)
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
            .clickable(enabled = enabled, onClick = onClick)
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
 * @property label 短标签（顶栏标题给全称，这里给两字以内的短名 —— 窄屏四等分塞不下全称）。
 * @property badge 计数（未读那种蓝底白字）；null/空不画。
 */
@Composable
fun TgTabBar(
    tabs: List<TgTab>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tg = TgTheme.colors
    Column(modifier.fillMaxWidth().background(tg.surface)) {
        TgDivider(indentDp = 0)
        Row(Modifier.fillMaxWidth().height(56.dp)) {
            tabs.forEachIndexed { index, tab ->
                val active = index == selected
                Column(
                    Modifier
                        .weight(1f)
                        .clickable { onSelect(index) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CountBadge(tab.badge)
                    Text(
                        text = tab.label,
                        color = if (active) tg.accent else tg.tabIdle,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(3.dp))
                    Box(
                        Modifier
                            .width(if (active) 20.dp else 0.dp)
                            .height(2.dp)
                            .background(
                                if (active) tg.tabIndicator else tg.surface,
                                androidx.compose.foundation.shape.RoundedCornerShape(1.dp),
                            ),
                    )
                }
            }
        }
    }
}

/** 页签条的一项。 */
data class TgTab(
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
fun TgScaffoldScreen(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    subtitleTone: StatusTone = StatusTone.MUTED,
    action: (@Composable () -> Unit)? = null,
    tabs: List<TgTab>? = null,
    selectedTab: Int = 0,
    onSelectTab: (Int) -> Unit = {},
    content: @Composable (Modifier) -> Unit,
) {
    Column(modifier.fillMaxSize().background(TgTheme.colors.background)) {
        TgActionBar(title = title, subtitle = subtitle, subtitleTone = subtitleTone, actions = action)
        Box(Modifier.weight(1f)) {
            content(Modifier.fillMaxSize())
        }
        if (tabs != null) {
            TgTabBar(tabs = tabs, selected = selectedTab, onSelect = onSelectTab)
        }
    }
}
