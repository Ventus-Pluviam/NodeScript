package com.autoscript.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.ui.state.StatusTone
import com.autoscript.ui.state.TabBarMeasure
import com.autoscript.ui.theme.ThemeColors
import kotlin.math.min

/**
 * Telegram 式顶栏（`ActionBar`）：56dp 高、标题左对齐、副标题小一号次级色、右侧动作区。
 *
 * 为什么顶栏自带 [subtitle] 这一档：TG 的 ActionBar 大量用副标题（在线人数、连接状态、
 * "正在输入…"），而副标题一多，"把状态塞进标题"的老写法就露馅了。这里给标题下方
 * 留一个固定位置，各屏爱用不用，但不用就得把 [subtitle] 传 null 而不是塞进标题。
 *
 * 高度固定 56dp 而非 `TopAppBar` 的自适应：四屏都不是可滚动标题，且固定高度让
 * "顶栏 → 内容" 的起点在四屏完全一致（滚动时内容整体位移不会顶穿标题）。
 *
 * **底色可换**（[background]）：TG 的 `actionBarDefault` 本来就是逐屏可覆盖的键
 * （BaseFragment.createActionBar 统一上色，特殊页自己换底）—— 任务中心要跟
 * 页面同灰（批 41），其余屏不传就是原样，互不影响。
 */
@Composable
fun ActionBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    subtitleTone: StatusTone = StatusTone.MUTED,
    onBack: (() -> Unit)? = null,
    /**
     * 左侧那颗按钮的字形。缺省 `‹` = "退回上一层"；选择模式（action mode）传 `✕`
     * —— TG 的选择模式那颗是**关闭**不是返回（按它退的是模式，不是页面），
     * 同一个位置画同一个箭头会让两种语义读成一件事。
     */
    backGlyph: String = "‹",
    actions: (@Composable () -> Unit)? = null,
    /**
     * 标题样式覆盖（合并到 `titleLarge` 之上）。缺省 null = 全仓统一的 17sp Medium；
     * 主页（TG `DialogsActivity`）的标题是**品牌位**：20sp 粗体 + `telegram_color_dialogsLogo`
     * 蓝（createTitleTextView 的 bold 20dp 口径），只有它有权覆盖。
     */
    titleStyle: TextStyle? = null,
    /**
     * 顶栏底色覆盖（批 41：任务中心要跟页面同灰）。缺省 null = `actionBarDefault`
     * （[ThemeColors.surface]，白/夜间 0xFF232326）；传色即整栏（含状态栏那一条）
     * 换底。要"栏与内容连成一块"的屏用它，别去改 [ThemeColors.surface] —— 那是
     * 四屏共用的键。
     */
    background: Color? = null,
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
            .background(background ?: palette.surface),
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
                        .pressable(
                            role = Role.Button,
                            // 顶栏按钮的按下档（`actionBarDefaultSelector`）。
                            overlay = palette.pressedOverlay,
                            onClick = it,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    // 顶栏图标色是 `actionBarDefaultIcon`（[ThemeColors.barIcon]：浅色偏冷深灰），
                    // 不是强调蓝 —— 返回箭头在 TG 里与标题同档，不是"链接"。
                    Text(backGlyph, color = palette.barIcon, style = MaterialTheme.typography.titleLarge)
                }
                Spacer(Modifier.width(4.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = titleStyle?.color ?: palette.text,
                    style = MaterialTheme.typography.titleLarge.merge(titleStyle),
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
            .pressable(
                enabled = enabled,
                role = Role.Button,
                // 顶栏按钮的按下档是 `actionBarDefaultSelector`（[ThemeColors.pressedOverlay]）
                // —— 与列表行同一档，菜单/面板那两处才是别的键。
                overlay = ThemeColors.pressedOverlay,
                onClick = onClick,
            )
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
 * 底部页签条（TG 2025+ 的悬浮胶囊底栏，`MainTabsActivity` + `MainTabsLayout` + `GlassTabView`）：
 *
 * **不是贴边整条**，是一条浮在内容上的胶囊（`tabsViewBackground.setRadius(HEIGHT/2)`）。
 * 几何逐项对着 TG 的三个数写，**不是估的**：
 *
 * 1. **胶囊 328×56dp，圆角 28dp**（`setMaxWidth(dp(328 + MAIN_TABS_MARGIN * 2))` 的
 *    328 是**胶囊**宽，344 是外层 View 宽 —— 那 16dp 是给阴影留的余量，背景 drawable
 *    自己 `setPadding(dp(MARGIN - 0.334f))` 内缩后才画圆角矩形）。高度 = `MAIN_TABS_HEIGHT`。
 * 2. **内容区 320×48dp，四周留 4dp**：`MainTabsLayout.setPadding(dp(MARGIN + 4))` = 12dp
 *    是**相对外层 View** 的，扣掉上面那 8dp 余量，内容离胶囊边 4dp；
 *    `tabHeight = height - paddingTop - paddingBottom` = 72 − 24 = 48dp。
 *    内容区宽上限 320dp **正好等于**宽度分配的下限 `min(dp(320), …)` —— 四格短标签时
 *    两者相等，于是胶囊恒为 328dp、内容恒铺满 320dp。这不是巧合，是那两个数互为倒影。
 *    另一条同样的倒影：本仓外层 8dp 边距 + 内层 4dp 内边距 = 12dp，**正是** TG 那个
 *    `setPadding(dp(MARGIN + 4))` —— 于是 `maxTotalWidthForTabs` 与 TG 逐值相等
 *    （360dp 屏上两边都是 320），分配结果也就逐值相等。
 * 3. **一格 = 图标 + 文字**（`GlassTabView`）：图标 24dp 距格顶 4dp
 *    （`createMainTab` 里 `imageView` 的 top margin 4），文字 12sp 紧随其下
 *    （`textView` top margin 28.33f）。格子宽度**按文字宽自适应**（[TabBarMeasure]），
 *    不走 weight 均分 —— 这正是 TG 底栏与 Material `NavigationBar` 最大的版式区别。
 * 4. **选中语法 = 选中格整格染色**（`GlassTabView.dispatchDraw`）：选中格背后画一个
 *    **纯 `colorSelected`（蓝）** 的圆角块，透明度 `0.09 × DECELERATE(f)` ——
 *    色相**不随选中因子走**（`multAlpha` 只改 alpha 通道），淡入途中是"同一块蓝更淡"，
 *    不是"换了个颜色"。铺满**整格**（48dp 高，r = min(w,h)/2），缩放 0.6→1
 *    （`lerp(0.6f, 1, factor)`）；图标与文字各自 blend 到自己的选中色（见 [TabBarItem]）。
 * 5. **导航栏 inset 抬起整条胶囊**：外层先吃 `navigationBars` inset、再离屏边 8dp ——
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
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val labels = remember(tabs) { tabs.map { it.label } }
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
        // 宽度分配要**先知道可用宽度**：胶囊被 `setMaxWidth` 夹在 328dp，内容区再扣掉
        // 左右各 4dp 的内边距 —— 这两步在 [TabBarMeasure] 里按 TG 的顺序做，这里只把
        // 屏宽喂进去。`BoxWithConstraints` 是取"屏宽"而不触发额外布局趟的标准做法。
        BoxWithConstraints {
            val plan = remember(labels, constraints.maxWidth, density, measurer) {
                TabBarMeasure.measure(
                    labels = labels,
                    density = density.density,
                    availableWidthPx = with(density) { maxWidth.toPx() },
                    maxWidthPx = with(density) { MainTabsMaxWidth.toPx() },
                    paddingPx = with(density) { MainTabsPadding.toPx() },
                    textWidthAt = { label, sizeSp ->
                        // **量的是 Medium（500）**：TG 的 `defaultTextPaint` 复制自
                        // `textView.getPaint()`，而 textView 的字体是 `bold()` ——
                        // 在本仓等价于 `FontWeight.Medium`（见 TabBarItem）。
                        // 拿 Bold 去量会系统性偏宽，四格的宽窄就全错了。
                        measurer.measure(
                            text = label,
                            style = TextStyle(
                                fontSize = sizeSp.sp,
                                fontWeight = FontWeight.Medium,
                            ),
                        ).size.width.toFloat()
                    },
                )
            }
            Column(
                Modifier
                    // 胶囊宽 = 内容宽 + 左右内边距（`MainTabsLayout` 的
                    // `setMeasuredDimension(l + paddingLeft + paddingRight, …)`）。
                    .width(with(density) { plan.capsuleWidthPx.toDp() })
                    .shadow(elevation = 10.dp, shape = MainTabsShape)
                    .background(palette.surface, MainTabsShape)
                    // 内容离胶囊边 4dp（见 KDoc 第 2 条）。纵向同理：48 + 4×2 = 56。
                    .padding(MainTabsPadding),
            ) {
                Row(Modifier.height(MainTabsContentHeight)) {
                    tabs.forEachIndexed { index, tab ->
                        TabBarItem(
                            tab = tab,
                            // **只读 `currentPage`**：跨半页才变一次，颜色由 animateFloatAsState
                            // 与高亮块缩放补完中间过程 —— 观感是"划过去"，代价是零。
                            selected = pagerState.currentPage == index,
                            onClick = { onSelect(index) },
                            // 宽度**由测量给出**（不是 weight）：TG 底栏四格不等宽。
                            width = with(density) { plan.tabWidthsPx[index].toDp() },
                            textSizeSp = plan.textSizeSp,
                            modifier = Modifier.fillMaxHeight(),
                        )
                    }
                }
            }
        }
    }
}

/**
 * `android.animation.DecelerateInterpolator`（factor 1.0）的等价物：`1 - (1 - t)²`。
 *
 * 不拿 `FastOutLinearInEasing` 顶替 —— 那条是三次贝塞尔 `(0.4, 0, 1, 1)`，与本式在
 * 中段差得看得见。TG 的 `BoolAnimator` 把插值器交给 `ValueAnimator.setInterpolator`，
 * 于是**每个回调拿到的 factor 已经是插值后的值**，`dispatchDraw` 里那个
 * `DECELERATE_INTERPOLATOR.getInterpolation(selectedFactor)` 再插一次是恒等操作。
 * 本仓照此：`f` 本身即缓动后的值，绘制处直接用。
 */
private val DecelerateEasing = Easing { t -> 1f - (1f - t) * (1f - t) }

/** 选中态动画时长（`BoolAnimator(…, DECELERATE_INTERPOLATOR, 320)`）。 */
private const val TAB_SELECT_DURATION_MILLIS = 320

/** 胶囊圆角 = 高度一半（`tabsViewBackground.setRadius(MAIN_TABS_HEIGHT / 2f)`）。 */
private val MainTabsShape = RoundedCornerShape(28.dp)

/** 胶囊距屏边（`MAIN_TABS_MARGIN = 8`）。 */
private val MainTabsMargin = 8.dp

/**
 * 胶囊宽上限（`setMaxWidth(dp(328 + MAIN_TABS_MARGIN * 2))` 里的 **328**）。
 *
 * 那行写的是 344 —— 因为 `setMaxWidth` 夹的是**外层 View**，而背景 drawable 自己
 * 内缩 8dp 才画圆角矩形，344 − 16 才是胶囊。照抄 344 会把胶囊画宽 16dp。
 */
private val MainTabsMaxWidth = 328.dp

/** 格高（`tabHeight = height - paddingTop - paddingBottom` = 72 − 12×2）。 */
private val MainTabsContentHeight = 48.dp

/** 胶囊内边距：内容离胶囊四边各 4dp（见 [TabBar] KDoc 第 2 条）。 */
private val MainTabsPadding = 4.dp

/** 胶囊高（`MAIN_TABS_HEIGHT`）= 格高 + 上下内边距。 */
private val TabBarHeight: Dp = MainTabsContentHeight + MainTabsPadding * 2

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
 * 底栏的一格（`GlassTabView`）：图标在上、文字在下，选中格背后画高亮块。
 *
 * **三个颜色各自走一条时间线**，这是 `GlassTabView.updateColors` 的原样：
 * ```
 * color     = blendARGB(colorDefault, colorSelected,     f)   → 图标
 * colorText = blendARGB(colorDefault, colorSelectedText, f)   → 文字
 * selector  = multAlpha(colorSelected, 0.09f * f)             → 高亮块
 * ```
 * 图标与文字**不是一个值**（浅色下 0xFF1A91E6 vs 0xFF0D7FCF），合成一个 tint 会让
 * 文字比图标亮一档 —— 那是抄错，不是简化。
 *
 * **没有按压态**：TG 的 `GlassTabView` 全文没有 `setPressed` / ripple / selector，
 * 按住的反馈就是 `clickHelper` 那条长按路径（另一件事）。本仓此前那套
 * 「按住整枚向强调色走一段」是自加的，本批删掉。
 *
 * **选中态换字重**（`setSelected`：`selected ? TYPEFACE_ROBOTO_EXTRA_BOLD : bold()`）：
 * 未选中 = `bold()`，而 `bold()` 在本仓等价于 **Medium(500)**（TG 走
 * `Typeface.create(null, 500, false)` 或 `rmedium.ttf`）；选中 = `rextrabold.ttf`
 * = **ExtraBold(800)**。本仓原先恒为 Bold(700)，两档都不对。
 */
@Composable
private fun TabBarItem(
    tab: TabItem,
    selected: Boolean,
    onClick: () -> Unit,
    width: Dp,
    textSizeSp: Float,
    modifier: Modifier = Modifier,
) {
    val palette = ThemeColors
    // 选中因子：**一个**动画喂三个颜色（TG 也只有一个 `isSelectedAnimator`）。
    // 读在组合里 = 动画期间逐帧重组这一格 —— 与旧版三条 `animateColorAsState` 同一代价，
    // 但只有一条时间线，颜色之间不会互相错位。
    val f by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(durationMillis = TAB_SELECT_DURATION_MILLIS, easing = DecelerateEasing),
        label = "tabSelectedFraction",
    )
    val iconTint = lerp(palette.tabUnselected, palette.tabSelected, f)
    val textTint = lerp(palette.tabUnselected, palette.tabSelectedText, f)
    Column(
        modifier = modifier
            .width(width)
            .selectable(
                selected = selected,
                interactionSource = null,
                // TG 的页签没有波纹（`GlassTabView` 里零 ripple）—— 默认指示的矩形灰块
                // 是"自加的"，批 25 用户点名过，这里显式关掉。
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            )
            .drawBehind {
                // 选中格背后的高亮块（GlassTabView.dispatchDraw）：**铺满整格**
                // （0,0,viewWidth,getHeight()），r = min(w,h)/2，缩放 lerp(0.6,1,f) 绕格心。
                //
                // **底色恒为 `colorSelected`（纯蓝），只有透明度随 f 变**：
                // `paintCounterBackground.setColor(Theme.multAlpha(colorSelected, 0.09f * alpha))`
                // —— `multAlpha` 是 `ColorUtils.setAlphaComponent(color, alpha(color) * multiply)`，
                // 只改 alpha 通道，**一个色相都不动**。
                // 本仓原先写成 `lerp(selected, unselected, f)` 是错的：那会让 f=0.5 时
                // 底色变成"蓝混近黑"的一坨灰（选中色 0xFF1A91E6 与未选中色 0xFF1A1D21 的
                // 中间值），于是淡入途中先看见灰再变蓝 —— 用户实测点名的"灰色背景"就是这个。
                // 正确形态：f 小 = 同一块蓝更淡，不是同一块地方换了个颜色。
                if (f > 0f) {
                    // 透明度那一路**过一遍 DECELERATE**：TG 的 factor 本身已是缓动值，
                    // 而 `dispatchDraw` 又拿它喂了一次 `getInterpolation` —— 于是
                    // `0.09f * f²` 型（f=1-(1-t)²）才是 TG 的实际曲线，不是线性的 `0.09f * f`。
                    val a = 1f - (1f - f) * (1f - f)
                    val r = min(size.width, size.height) / 2f
                    val s = 0.6f + 0.4f * f
                    withTransform({
                        scale(s, s, pivot = Offset(size.width / 2f, size.height / 2f))
                    }) {
                        drawRoundRect(
                            color = palette.tabSelected.copy(alpha = 0.09f * a),
                            topLeft = Offset.Zero,
                            size = Size(size.width, size.height),
                            cornerRadius = CornerRadius(r),
                        )
                    }
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 图标 24dp 距格顶 4dp、文字紧随其下（`createMainTab` 的 imageView top 4 /
        // textView top 28.33）。用 Top 对齐而不是 Center：格高 48dp、内容 24 + 文字 ≈ 40dp，
        // 居中会把图标推到 top 8，与 TG 差 4dp。
        Spacer(Modifier.height(4.dp))
        Glyph(
            kind = tab.glyph,
            tint = iconTint,
            // 线性图标的"选中加重"：线宽略加粗（同色同形，只是更实）。
            weight = if (selected) 1.3f else 1f,
        )
        Text(
            text = tab.label,
            color = textTint,
            style = MaterialTheme.typography.labelSmall,
            // 字号由三趟试排选定（12 / 12 / 10sp），字重按选中态换档（见本函数 KDoc）。
            fontSize = textSizeSp.sp,
            fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 页签条的一项。
 *
 * @property label 短标签。**宽度由它决定**（[TabBarMeasure] 按文字实际宽度分格），
 *   所以长短不一的标签会得到宽窄不一的格子 —— 这是 TG 的样子，不是没对齐。
 * @property glyph 图标形状（[GlyphKind]，画出来的，见 `Glyphs.kt`）。
 */
data class TabItem(
    val label: String,
    val glyph: GlyphKind,
)
