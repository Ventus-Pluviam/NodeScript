package com.autoscript.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
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
    val shellAction = LocalBarAction.current
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
                // 顶栏底色**画到状态栏后面**，内容让开那一条（边到边，§6）：
                // padding 加在 56dp **之外**（顺序不可反 —— 反了就是把内容压进 56dp 里，
                // 标题会被挤成两行）。这样"文字不顶到状态栏"与"状态栏那一条也是顶栏色"
                // 同时成立，后者正是 TG 的样子：状态栏与顶栏是同一块底。
                // targetSdk 35 起 Android 15 强制边到边，这条不是可选修饰。
                .windowInsetsPadding(WindowInsets.statusBars)
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
            // 外壳级动作（主题切换）在左、本屏动作在右：TG 的顶栏把最右一格留给
            // 本屏的主动作（刷新/登记），全局的那一档排在它前面，不抢主动作的位置。
            shellAction?.let {
                Spacer(Modifier.width(4.dp))
                it()
            }
            actions?.let {
                Spacer(Modifier.width(8.dp))
                it()
            }
        }
        Separator(indentDp = 0)
    }
}

/**
 * 外壳往各屏顶栏追加的动作位（当前只有主题切换）。
 *
 * 为什么不给四屏各加一个参数：主题是**全局**设置，四屏的顶栏只是它的显示位 ——
 * 加参数就得把"主题"这个概念塞进四个屏的签名里，而它们既不读也不写它。
 * 缺省 null = 没有外壳时顶栏照旧（单屏预览/组件测试拿到的就是这个缺省）。
 */
val LocalBarAction: ProvidableCompositionLocal<(@Composable () -> Unit)?> =
    staticCompositionLocalOf { null }

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
 * 底部页签条（TG 的底部导航：**图标 + 文字，整格染色，没有指示线**）。
 *
 * 三处对齐 TG 的语法（2026-10-02 重做，旧版对着"顶栏页签"抄错了地方）：
 * 1. **图标 + 文字两行**，图标 24dp、文字 12sp —— 底栏的辨识靠图标，文字只是补一句；
 *    旧版只有文字，四格两字并排看起来像分段控件，不像底栏。
 * 2. **没有指示线**。旧版那条 2dp 蓝线是 TG **顶栏**文件夹页签的下划线
 *    （`actionBarTabLine`），搬到顶栏以外的地方就不是 TG 了。
 * 3. **选中 = 整格染色**（图标与文字同色渐变到强调色），未选中 = 次级灰。
 */
@Composable
fun TabBar(
    tabs: List<TabItem>,
    pagerState: PagerState,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = ThemeColors
    Column(
        modifier
            .fillMaxWidth()
            // 底栏底色**铺到屏幕最底**（含系统导航栏/手势条那一条）：TG 的底栏一直画到
            // 屏幕边缘，导航栏区跟着底栏上色，而不是在底栏下面留一条系统黑带。
            // 同 ActionBar：background 在前、inset padding 在后（padding 加在实高之外）。
            .background(palette.surface)
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        Separator(indentDp = 0)
        Row(Modifier.fillMaxWidth().height(TabBarHeight)) {
            tabs.forEachIndexed { index, tab ->
                TabBarItem(
                    tab = tab,
                    // **只读 `currentPage`，绝不读 `currentPageOffsetFraction`**：
                    // 后者每帧都变，在组合里读它 = 横划时每帧重组整条栏 + 外壳 + 页内容
                    // （旧版卡顿的根因）。`currentPage` 只在跨过半页时变一次，颜色由
                    // animateColorAsState 补完中间过程 —— 观感上仍是"划过去"，代价是零。
                    selected = pagerState.currentPage == index,
                    onClick = { onSelect(index) },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }
    }
}

/** 底栏高度（不含系统导航栏那一条）。TG 的底栏就是 56dp。 */
private val TabBarHeight = 56.dp

/** 底栏的一格：图标 + 文字，整格可点、整格染色。 */
@Composable
private fun TabBarItem(
    tab: TabItem,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = ThemeColors
    // 选中色**渐变**而不是硬切：切页时手指还在划，颜色正在路上，与 pager 的滚动
    // 是同一条时间线 —— 硬切会让人觉得"点了才变"。
    val tint by animateColorAsState(
        targetValue = if (selected) palette.accent else palette.tabIdle,
        animationSpec = tween(durationMillis = 180),
        label = "tabTint",
    )
    Column(
        modifier = modifier.pressableSelectable(selected = selected, role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Glyph(
                kind = tab.glyph,
                tint = tint,
                // 线性图标的"选中加重"：线宽略加粗（同色同形，只是更实）。
                weight = if (selected) 1.3f else 1f,
            )
            // 计数叠在图标右上角（TG 的未读计数位置），不是另起一行 —— 另起一行会把
            // 图标与文字挤开，四格的高度就对不齐了。本仓四个页签恒不挂徽标
            // （见 MainShell 的注释），这条留给将来真有"未读"语义的页签。
            if (!tab.badge.isNullOrEmpty()) {
                CountBadge(
                    text = tab.badge,
                    modifier = Modifier.align(Alignment.TopEnd).offset(x = 10.dp, y = (-6).dp),
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = tab.label,
            color = tint,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}

/**
 * 页签条的一项。
 *
 * @property label 短标签（顶栏标题给全称，这里给两字以内的短名 —— 窄屏四等分塞不下全称）。
 * @property glyph 图标形状（[GlyphKind]，画出来的，见 `Glyphs.kt`）。
 * @property badge 计数（未读那种蓝底白字）；null/空不画。
 */
data class TabItem(
    val label: String,
    val glyph: GlyphKind,
    val badge: String? = null,
)

/**
 * 整屏骨架：顶栏 + 内容。
 *
 * **底页签条不在本组件里**：页签条归外壳（`MainShell`）一处画 —— 它是全 App 唯一的一条，
 * 而"每屏自己画一条"会让横划切页时四屏各滑各的页签条（本仓四屏都在 pager 里，2026-10-02 前
 * 就是这样：切页时底栏跟着内容一起滑，像换了一整个界面）。这里只保证"标题在顶栏、
 * 内容从分隔线下开始"这条式四屏一致。
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
    content: @Composable (Modifier) -> Unit,
) {
    Column(modifier.fillMaxSize().background(ThemeColors.background)) {
        ActionBar(title = title, subtitle = subtitle, subtitleTone = subtitleTone, actions = action)
        Box(Modifier.weight(1f)) {
            content(Modifier.fillMaxSize())
        }
    }
}
