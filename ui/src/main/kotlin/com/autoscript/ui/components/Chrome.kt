package com.autoscript.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
 * 底部页签条（TG 2025+ 的悬浮胶囊底栏，`MainTabsActivity` + `MainTabsLayout` 语法）：
 *
 * **不再是贴边整条**，是一条浮在内容上的胶囊（`tabsViewBackground.setRadius(HEIGHT/2)`）：
 * 1. **胶囊外形**：高 56dp（`MAIN_TABS_HEIGHT`），距屏边 8dp（`MAIN_TABS_MARGIN`），
 *    圆角 = 高度一半（28dp），最大宽 344dp（`setMaxWidth(328 + MARGIN*2)`）居中；
 *    底色 = surface（`glass_targetMainTabs` 的语义位：浅色白、深色 #232324），
 *    下方 8dp 阴影把它从内容上托起来（blur 工厂在 Compose 里没有等价物，阴影是
 *    「浮在内容上」的最小表达）。
 * 2. **一格 = 图标 + 文字**（`GlassTabView`）：图标 24dp 在上（距顶 4dp），文字 12sp
 *    粗体在下（`textView.setTextSize(12f)` + `AndroidUtilities.bold()`）；格子宽度按
 *    文字宽自适应（`measureTextWidth` + 三档字号收窄），不走 weight 均分 —— 这正是
 *    TG 底栏与 Material `NavigationBar` 最大的版式区别。
 * 3. **选中语法 = 选中格整格染色 + 胶囊高亮**（`GlassTabView.dispatchDraw`）：
 *    选中格背后画一个 9% 透明度的强调色圆角块（`multAlpha(colorSelected, 0.09f)`），
 *    图标与文字 blend 到强调色；未选中 = 主文字色 63% 透明度（`key_glass_defaultIcon`
 *    = 0x991B2227，night = 0xA0FFFFFF）。
 * 4. **导航栏 inset 抬起整条胶囊**：外层先吃 `navigationBars` inset、再离屏边 8dp ——
 *    三键导航（三大金刚键）出现时胶囊悬在导航键上方；手势导航的 inset 只是一条细带，
 *    位置几乎不动。四屏内容让位走 [TabBarBottomClearance]，与胶囊消费同一份 inset。
 *
 * **只读 `pagerState.currentPage`，绝不读 `currentPageOffsetFraction`**（硬约束，
 * 见旧版注释：后者每帧变，在组合里读 = 重组风暴；跟手的观感由颜色/块位移动画补完）。
 */
@Composable
fun TabBar(
    tabs: List<TabItem>,
    pagerState: PagerState,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = ThemeColors
    Row(
        modifier = modifier
            .fillMaxWidth()
            // 先吃导航栏 inset、再离屏边 8dp（MAIN_TABS_MARGIN）：三键导航（三大金刚键）
            // 出现时整条胶囊抬到导航键之上；手势条那条 inset 很小，观感几乎不变。
            // 批 21 赌过「inset 在胶囊下面」，被三键导航实测打脸（胶囊被键位盖住）——
            // 两态都挂 inset，才是「浮、又不被吃」的摆法。
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = MainTabsMargin, vertical = MainTabsMargin),
        horizontalArrangement = Arrangement.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = MainTabsMaxWidth)
                .shadow(elevation = 10.dp, shape = MainTabsShape)
                .background(palette.surface, MainTabsShape),
        ) {
            Row(Modifier.height(TabBarHeight)) {
                tabs.forEachIndexed { index, tab ->
                    TabBarItem(
                        tab = tab,
                        // **只读 `currentPage`**：跨半页才变一次，颜色由 animateColorAsState
                        // 与高亮块位移补完中间过程 —— 观感是"划过去"，代价是零。
                        selected = pagerState.currentPage == index,
                        onClick = { onSelect(index) },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
            }
        }
    }
}

/** 胶囊圆角 = 高度一半（`tabsViewBackground.setRadius(MAIN_TABS_HEIGHT / 2f)`）。 */
private val MainTabsShape = RoundedCornerShape(28.dp)

/** 底栏距屏边（`MAIN_TABS_MARGIN = 8`）。 */
private val MainTabsMargin = 8.dp

/** 胶囊最大宽（`tabsView.setMaxWidth(dp(328 + MAIN_TABS_MARGIN * 2))`）。 */
private val MainTabsMaxWidth = 344.dp

/** 底栏高度（`MAIN_TABS_HEIGHT = 56`，不含距屏边）。 */
private val TabBarHeight = 56.dp

/** 胶囊连自身上下屏边距的占位高度（56 + 8×2 = 72dp），**不含**导航栏 inset。 */
private val TabBarClearance: Dp = TabBarHeight + MainTabsMargin * 2

/**
 * 悬浮底栏要求内容让出的**总**底部留白 = [TabBarClearance] + 导航栏 inset（[extra] 加呼吸）。
 *
 * 胶囊外层吃掉多少 `navigationBars` inset（三键导航 ≈48dp、手势条更小甚至 0），整条
 * 胶囊就被抬高多少；列表末项、回顶钮若仍按固定 dp 让位，三键一出现就又被盖住
 * （批 21 的固定 72dp 就是这么被实测打脸的）。各屏让位一律走这里 —— 与胶囊消费
 * 同一份 inset，两态导航都成立；别在屏里抄数字。
 */
@Composable
fun TabBarBottomClearance(extra: Dp = 0.dp): Dp =
    TabBarClearance + extra + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

/**
 * 底栏的一格（`GlassTabView`）：图标在上、12sp 粗体文字在下，选中格背后画高亮块。
 *
 * 图标 24dp 距顶 4dp、文字贴着图标下缘（`imageView` top margin 4 / `textView` top 28.33
 * 的等价摆法，用 Arrangement 而不是绝对偏移 —— 字形行高与 TG 的 TextView 不必逐像素对齐）。
 */
@Composable
private fun TabBarItem(
    tab: TabItem,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = ThemeColors
    // 选中色**渐变**而不是硬切：切页时手指还在划，颜色正在路上，与 pager 的滚动
    // 是同一条时间线 —— 硬切会让人觉得"点了才变"。（GlassTabView 是
    // blendARGB(colorDefault, colorSelected, factor) 同一条时间线。）
    val tint by animateColorAsState(
        targetValue = if (selected) palette.accent else palette.tabIdle,
        animationSpec = tween(durationMillis = 180),
        label = "tabTint",
    )
    // 高亮块跟随选中格淡入（TG 用 SpringAnimation 拖 selector 中心；这里格子等宽，
    // 各格自己的 0→1 淡入 + 缩放就是同一条视觉轨迹）。
    val highlightFraction by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(durationMillis = 220),
        label = "tabHighlight",
    )
    Column(
        modifier = modifier
            .pressableSelectable(selected = selected, role = Role.Tab, onClick = onClick)
            .drawBehind {
                if (highlightFraction > 0f) {
                    // 选中格背后的高亮块（GlassTabView.dispatchDraw）：强调色 9% 透明度
                    // （multAlpha(colorSelected, 0.09f)），缩放 0.6→1（lerp(0.6f, 1, factor)），
                    // 胶囊形（r = min(w,h)/2），上下各缩 6dp —— 不顶着外层胶囊的边。
                    val blockHeight = size.height - 12.dp.toPx()
                    val s = 0.6f + 0.4f * highlightFraction
                    withTransform({
                        scale(s, s, pivot = Offset(size.width / 2f, size.height / 2f))
                    }) {
                        drawRoundRect(
                            color = palette.accent.copy(alpha = 0.09f * highlightFraction),
                            topLeft = Offset(0f, (size.height - blockHeight) / 2f),
                            size = Size(size.width, blockHeight),
                            cornerRadius = CornerRadius(blockHeight / 2f),
                        )
                    }
                }
            },
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
            fontWeight = FontWeight.Bold,
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
