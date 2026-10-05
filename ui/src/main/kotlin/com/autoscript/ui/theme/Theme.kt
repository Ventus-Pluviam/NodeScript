package com.autoscript.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Telegram 观感的调色板（深浅两套）与主题开关。
 *
 * **取色来源**：Telegram Android 客户端
 * `TMessagesProj/src/main/assets/day.attheme`、`night.attheme` 与
 * `ui/ActionBar/ThemeColors.java` 的默认值，逐个键抄下来（键名留在 KDoc 里），
 * 而不是凭印象调一版「像 TG 的蓝」。两份源各自负责一半，缺的那个键回退到另一半：
 * `day.attheme` 只有浅色键、`night.attheme` 只有深色键，合起来才是完整的一对。
 *
 * **为什么不用 Material3 的 ColorScheme 承载**：`ColorScheme` 没有「未读计数底/字」、
 * 「页签下划线」、「置顶遮罩」这类槽位，而这些正是 TG 观感里最认得出的几处（会话列表
 * 的蓝底白字计数、底部页签的 2dp 蓝线）。硬塞进 `primary/onPrimary` 只会让「主题里
 * 这个颜色到底是什么」变得不可读。故 [Colors] 是本仓的语义调色板，
 * `ColorScheme` 只作为 `MaterialTheme` 的最小适配（部分 M3 组件仍要它）。
 *
 * 「GPL-2.0-or-later」下**未抄任何 TG 源码**：这里只有颜色数值（事实）与版式约定
 * （顶栏 + 底页签 + 密排列表），代码是本仓自己的。
 */
@Immutable
data class Colors(
    /** 屏底（`windowBackgroundWhite`）。 */
    val background: Color,
    /** 卡片/顶栏底（`actionBarDefault`）。 */
    val surface: Color,
    /** 设置页底（灰底上浮白卡片的那层灰；`windowBackgroundGray`）。 */
    val surfaceMuted: Color,
    /** 分隔线（`divider`）。 */
    val divider: Color,
    /** 正文主色（`chats_name` / `actionBarDefaultTitle`）。 */
    val text: Color,
    /** 次级文字：摘要行（`chats_message`）。 */
    val textSecondary: Color,
    /** 三级文字：时间、提示（`chats_date` / `windowBackgroundWhiteHintText`）。 */
    val textTertiary: Color,
    /** 强调色：链接、选中态（`windowBackgroundWhiteBlueText` / `actionBarTabActiveText`）。 */
    val accent: Color,
    /** 顶栏副标题淡色（`actionBarDefaultSubtitle`）。 */
    val barSubtitle: Color,
    /** 错误（`chats_sentError`）。 */
    val error: Color,
    /** 「降级/可用但受限」这类提醒（`avatar_backgroundOrange` 的文字版：橙底太亮，取深一档）。 */
    val warning: Color,
    /** 成功/在线（`chats_onlineCircle`）。 */
    val success: Color,
    /** 未读计数底（`chats_unreadCounter`）。 */
    val badge: Color,
    /** 未读计数字（`chats_unreadCounterText` = 白）。 */
    val onBadge: Color,
    /**
     * 底页签**未选中**档的前景（`glass_tabUnselected`）：图标与文字**共用**这个起点。
     *
     * **取值要连回退链一起看**（`Theme.java` 的 `fallbackKeys`）：这个键在
     * `day.attheme` 与 `night.attheme` 里**都没设**，所以两套主题走的都是回退 ——
     * 浅色 → `ThemeColors.java` 的默认值 `0xFF1A1D21`（≈ 主文字色）；
     * 深色 → `windowBackgroundWhiteBlackText`（night = **纯白**）。
     * 拿一套主题的数当"TG 的未选中色"抄进另一套，就是下面那个 [tabSelected] 踩过的坑。
     *
     * 这里刻意**没有**「选中线」这个 token：TG 那条 2dp 线是**顶栏**文件夹页签的下划线，
     * 底栏的选中语法是**整格染色**，2026-10-02 重做底栏时一并删掉了照抄过来的
     * `tabIndicator` —— 留一个没有调用方的颜色只会让下一个人以为底栏该有线。
     */
    val tabUnselected: Color,
    /**
     * 底页签**选中**档的**图标**色（`glass_tabSelected`），也是选中格背后那块高亮块的
     * **唯一**取色（`multAlpha(colorSelected, 0.09f * alpha)`）。
     *
     * 图标与文字**不是一个值**（见下条）：`GlassTabView.updateColors` 是两条独立的
     * blend，合成一个 tint 会让文字比图标暗或亮一档 —— 那是抄错，不是简化。
     *
     * **别把浅色的值当成"TG 的选中蓝"往深色里搬**：这个键两套 attheme 都没设，
     * 浅色走 `ThemeColors.java` 默认值 `0xFF1A91E6`，深色走回退键
     * `chat_messagePanelSend` = **`0xFF229AF0`**（night.attheme 实测值）。
     * 两者差得不小（hue 202° vs 205°、明度差一档），搬过去会让深色底栏偏暗。
     */
    val tabSelected: Color,
    /**
     * 底页签**选中**档的**文字**色（`glass_tabSelectedText`）。
     *
     * 浅色下比 [tabSelected] 深一档（0xFF0D7FCF vs 0xFF1A91E6）；深色下两者**同值**
     * —— 因为 night 主题两个键都没设，一起回退到 `chat_messagePanelSend`。
     */
    val tabSelectedText: Color,
    /** 按下态遮罩（`actionBarDefaultSelector` 那种半透明压暗）。 */
    val pressedOverlay: Color,
    /**
     * 输入框/搜索底（TG `FragmentSearchField.updateColors` 的配方：把
     * `windowBackgroundWhiteBlackText` 按**浅色 5% / 深色 7%** 压上去）。
     *
     * 存的是**合成本屏底之后的实色**（TG 那边是 alpha 色叠在顶栏上，本仓的搜索栏不在
     * 顶栏里，叠的是屏底）：浅色 = 5% 的 `0xFF1A1D21` 叠白 → `0xFFF4F4F4`；
     * 深色 = 7% 的白叠 [background]（`0xFF181819`）→ `0xFF282829`。
     * **深色下它比屏底亮**，浅色下才比屏底暗一档 —— 原先两套都写成"比 [surface] 深"
     * 是把深色那半抄反了（TG 的 tint 是白，叠上去只会更亮）。
     */
    val fieldBackground: Color,
    /**
     * 分组头底（TG `graySection`：GraySectionCell 那条 32dp 的分组条底色）。
     * 浅色在白卡上比卡底浅一档灰、深色比屏底微亮（`0xFF0B0B0C` vs `0xFF181819` 有意更暗 ——
     * 深色下 TG 的 graySection 本来就是"近黑的条"，`Theme.java` 深色缺省即 `0xff0b0b0c`）。
     */
    val graySection: Color,
    /** 分组头文字（TG `graySectionText`，14sp Medium 的分组标签色）。 */
    val graySectionText: Color,
    /** 弹出菜单底（`actionBarDefaultSubmenuBackground`）。 */
    val menuBackground: Color,
    /** 弹出菜单的分组间隙（`actionBarDefaultSubmenuSeparator`，TG `GapView` 的底色）。 */
    val menuSeparator: Color,
    /** 底部操作面板底（`dialogBackground`，TG `sheet_shadow_round` 被它 color-filter）。 */
    val sheetBackground: Color,
    /** 主按钮/胶囊底（`featuredStickers_addButton`，TG 的"动作蓝"，与 [accent] 不是一个键）。 */
    val featuredButton: Color,
    /** 带后果的文字（`text_RedRegular`，TG 菜单里的"删除/停止"那一档）。 */
    val dangerText: Color,
    /** 输入光标（`groupcreate_cursor`）。 */
    val cursor: Color,
    /** 悬浮圆钮底（`chat_messagePanelBackground`，TG 回底那颗圆钮的 blur 背板取色）。 */
    val roundButtonBackground: Color,
    /** 悬浮圆钮的图标（`glass_defaultIcon`，**半透明**：浅色 60% 黑、深色 63% 白）。 */
    val roundButtonIcon: Color,
    /**
     * 列表行**选中**遮罩（`chats_tabletSelectedOverlay`，TG `DialogCell` 的
     * `dialogs_tabletSeletedPaint`）：中性黑/白 6%，**不是**强调色淡底。
     */
    val rowSelectedOverlay: Color,
    /**
     * 常规图标色（`actionBarDefaultIcon`）：顶栏图标、FAB 子钮字形都取它。
     *
     * 浅色不是主文字色而是一档偏冷的深灰（`0xFF404E56`）—— TG 的图标比正文**轻**
     * 半档，把它写成 [text] 会让图标跟标题一样重。
     */
    val barIcon: Color,
    /**
     * **实心强调底上的前景**（`windowBackgroundCheckText`，缺省 `0xFFFFFFFF`）。
     *
     * 一个键盖两处 TG 键：FAB 图标（`chats_actionIcon`）与选中 chip 的字
     * （`windowBackgroundCheckText`）—— 两个键在两套 attheme 里**都没被覆盖**，
     * 走的都是 `ThemeColors.java` 的白，故合成一个语义位「压在 [featuredButton] 上」。
     */
    val featuredButtonText: Color,
    /**
     * 未选中 chip 的字（`windowBackgroundWhiteGrayText2`）。
     *
     * 深色下是**半透明白**（night.attheme `#6EFDFDFF`）而不是实色灰：TG 的 chip 未选中
     * 态没有底，字直接叠在屏底上，半透明才读得出"它比选中那格轻"。
     */
    val chipText: Color,
    /** 底部面板标题（`dialogTextGray2`，TG 非 bigTitle 那档：16dp 常规字重）。 */
    val sheetTitleText: Color,
    /** 底部面板条目文字（`dialogTextBlack`，TG `BottomSheetCell` type 0）。 */
    val sheetItemText: Color,
    /**
     * 菜单项的按下态（`dialogButtonSelector`，TG `ActionBarMenuSubItem` 的选择器色）。
     *
     * 与 [pressedOverlay]（`actionBarDefaultSelector`）**不是一个键**：TG 的列表行用前者、
     * 菜单/对话框按钮用后者，深浅两套都差一档（浅 6% vs 8% 黑、深 10% vs 10% 白）。
     */
    val menuSelector: Color,
    /**
     * 勾选框的填充（`checkbox` = `0xFF5EC245`）。
     *
     * **深浅两套同值**：这个键在 `day.attheme` 与 `night.attheme` 里**都没设**，
     * 两份走的都是 `ThemeColors.java` 的默认值 —— 所以它不是"浅色的绿"，没有第二档可抄。
     * TG 的勾选框本体（`CheckBox2`）不带描边，环是"未选中"态才画的（`drawUnchecked`），
     * 而列表里的选择框一律 `setDrawUnchecked(false)`：选中就是**一颗实心绿圆 + 白勾**。
     */
    val checkboxFill: Color,
    /** 勾选框的勾（`checkboxCheck` = 白；两套 attheme 都没覆盖）。 */
    val checkboxCheck: Color,

    /**
     * 浮层提示（toast）底（TG `key_undo_background` → 回退 `key_chat_gifSaveHintBackground`）。
     *
     * 浅色取 TG 的 `0xE21F2B38`（深蓝灰、89% 不透明）；**深色不照抄**：TG night 的
     * `undo_background` = `0xF5181818`，与本仓深色屏底 [background]（`0xFF181819`）几乎
     * 同值 —— 原样贴上去就是"深灰浮层压深灰页"，等于看不见，故取 [surface]（`0xFF232326`）
     * 这一档，明度差与 TG night 里 `0xFF181818` 压在 `windowBackgroundWhite` 上的关系一致。
     */
    val toastBackground: Color,
    /** 浮层提示的字（`key_undo_infoColor` → 回退 `key_chat_gifSaveHintText` = 白；深浅同值）。 */
    val toastText: Color,

    /**
     * 文件类型头像的底色表（TG `AvatarDrawable` 的 `avatar_background*` 色序）：
     * 扩展名哈希取槽位（`getColorIndex(id)` 的读法），同一扩展名恒同色。
     */
    val fileAvatarColors: List<Color>,
)

/** 浅色（`day.attheme`）。 */
val LightColors = Colors(
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    surfaceMuted = Color(0xFFF1F1F3),
    divider = Color(0xFFE6E6E6),
    text = Color(0xFF1A1D21),
    textSecondary = Color(0xFF75787A),
    textTertiary = Color(0xFF848688),
    accent = Color(0xFF238AE3),
    barSubtitle = Color(0xFF79817E),
    error = Color(0xFFD55252),
    warning = Color(0xFFC07818),
    success = Color(0xFF4BCB1C),
    badge = Color(0xFF24AEF7),
    onBadge = Color(0xFFFFFFFF),
    // 底页签三键（`glass_tab*`）：day.attheme 里**一个都没设**，走 ThemeColors.java
    // 的默认值（0xFF1a91e6 / 0xFF0d7fcf / 0xFF1A1D21）。
    tabUnselected = Color(0xFF1A1D21),
    tabSelected = Color(0xFF1A91E6),
    tabSelectedText = Color(0xFF0D7FCF),
    pressedOverlay = Color(0x14000000),
    // `windowBackgroundWhiteBlackText`(0xFF1A1D21) 5% 叠白（TG 的 5%）。
    fieldBackground = Color(0xFFF4F4F4),
    graySection = Color(0xFFF6F6F6),
    graySectionText = Color(0xFF84878A),
    // 这三个键 day.attheme **都没设** → 走 ThemeColors.java 的默认值。
    menuBackground = Color(0xFFFFFFFF),
    menuSeparator = Color(0xFFF5F5F5),
    sheetBackground = Color(0xFFFFFFFF),
    // day.attheme 设了 `featuredStickers_addButton`；night 没设 → TELEGRAM_COLOR。
    featuredButton = Color(0xFF4DA0EB),
    // `text_RedRegular` / `groupcreate_cursor` 两个键 day.attheme 都设了。
    dangerText = Color(0xFFCC2929),
    cursor = Color(0xFF329FED),
    // `chat_messagePanelBackground` / `glass_defaultIcon` / `chats_tabletSelectedOverlay`
    // day.attheme 都没设 → ThemeColors.java 默认值。
    roundButtonBackground = Color(0xFFFFFFFF),
    roundButtonIcon = Color(0x991B2227),
    rowSelectedOverlay = Color(0x0F000000),
    barIcon = Color(0xFF404E56),
    featuredButtonText = Color(0xFFFFFFFF),
    chipText = Color(0xFF82868A),
    sheetTitleText = Color(0xFF757575),
    sheetItemText = Color(0xFF1A1D21),
    menuSelector = Color(0x0F000000),
    // `checkbox` / `checkboxCheck`：两套 attheme 都没设 → ThemeColors.java 的默认值。
    checkboxFill = Color(0xFF5EC245),
    checkboxCheck = Color(0xFFFFFFFF),
    toastBackground = Color(0xE21F2B38),
    toastText = Color(0xFFFFFFFF),
    // avatar_background{Red,Orange,Violet,Cyan,Blue,Pink} + Green（ThemeColors.java 默认值）。
    fileAvatarColors = listOf(
        Color(0xFFFF845E),
        Color(0xFFFEBB5B),
        Color(0xFFB694F9),
        Color(0xFF9AD164),
        Color(0xFF5BCBE3),
        Color(0xFF5CAFFA),
        Color(0xFFFF8AAC),
    ),
)

/** 深色（`night.attheme`）。 */
val DarkColors = Colors(
    background = Color(0xFF181819),
    surface = Color(0xFF232326),
    // TG 的设置页灰底（night.attheme `windowBackgroundGray` = #000000，纯黑）：
    // 深色下白卡片浮在黑底上，卡片与底的对比来自明度差，不靠描边。
    surfaceMuted = Color(0xFF000000),
    divider = Color(0xFF2F2F33),
    text = Color(0xFFFFFFFF),
    textSecondary = Color(0xFF828282),
    textTertiary = Color(0xFF787878),
    accent = Color(0xFF5CC1FF),
    barSubtitle = Color(0x99F2F2F2),
    error = Color(0xFFE06C6C),
    warning = Color(0xFFF0B429),
    success = Color(0xFF71D756),
    badge = Color(0xFF399EF3),
    onBadge = Color(0xFFFFFFFF),
    // night.attheme 只设了 glass_defaultIcon / glass_defaultText / glass_targetMainTabs，
    // 底页签这三个键**一个都没设**，于是全部走 fallbackKeys：
    // tabUnselected → windowBackgroundWhiteBlackText（night = 白）、
    // tabSelected / tabSelectedText → chat_messagePanelSend（night = 0xFF229AF0）。
    tabUnselected = Color(0xFFFFFFFF),
    tabSelected = Color(0xFF229AF0),
    tabSelectedText = Color(0xFF229AF0),
    pressedOverlay = Color(0x1AFFFFFF),
    // 白 7% 叠 [background](0xFF181819) —— 与下面 menuBackground 同值纯属巧合（TG 的
    // 深色子菜单底也是"比底亮一档的深灰"），两个键在 TG 里各是各的，别合并。
    fieldBackground = Color(0xFF282829),
    graySection = Color(0xFF0B0B0C),
    graySectionText = Color(0xFF838384),
    // 这三个键 night.attheme 都设了。
    menuBackground = Color(0xFF282829),
    menuSeparator = Color(0xFF1E1E1F),
    sheetBackground = Color(0xFF1E1E1E),
    // night.attheme 没设 → ThemeColors.java 默认值 TELEGRAM_COLOR。
    featuredButton = Color(0xFF229AF0),
    dangerText = Color(0xFFEE686F),
    cursor = Color(0xFF64B5EF),
    roundButtonBackground = Color(0xFF1E1E1F),
    roundButtonIcon = Color(0xA0FFFFFF),
    rowSelectedOverlay = Color(0x0FFFFFFF),
    barIcon = Color(0xFFFFFFFF),
    featuredButtonText = Color(0xFFFFFFFF),
    chipText = Color(0x6EFDFDFF),
    sheetTitleText = Color(0xFF7D7D7D),
    sheetItemText = Color(0xFFF6F6F6),
    menuSelector = Color(0x19FFFFFF),
    checkboxFill = Color(0xFF5EC245),
    checkboxCheck = Color(0xFFFFFFFF),
    // TG night 的 `undo_background`（0xF5181818）与本仓深色屏底同值 → 会看不见，
    // 上抬到 [surface] 那一档（见 [Colors.toastBackground] 的说明）。
    toastBackground = Color(0xF5232326),
    toastText = Color(0xFFFFFFFF),
    fileAvatarColors = listOf(
        Color(0xFFFF845E),
        Color(0xFFFEBB5B),
        Color(0xFFB694F9),
        Color(0xFF9AD164),
        Color(0xFF5BCBE3),
        Color(0xFF5CAFFA),
        Color(0xFFFF8AAC),
    ),
)

/** 主题档位。`SYSTEM` 跟随系统，另两档由用户在顶栏手动切。 */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** `ThemeMode` 的判读：**唯一**一处「这一档到底是明是暗」，别处问 `isDark()`。 */
@Composable
fun ThemeMode.isDark(): Boolean = when (this) {
    // `isSystemInDarkTheme()` 是 @Composable（要读配置），故本函数也是 @Composable。
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

val LocalColors: ProvidableCompositionLocal<Colors> = staticCompositionLocalOf { LightColors }

/**
 * 便捷取色：界面里的颜色一律走 `ThemeColors.accent` 这样读。
 *
 * 不直接暴露 [LocalColors]：取色口只有这一个，换主题时才不会有"某处绕过了它"。
 */
val ThemeColors: Colors
    @Composable @ReadOnlyComposable get() = LocalColors.current

/**
 * 「当前主题是不是深色」的组合内读口。
 *
 * 设置页的图标方块要用它决定画不画那圈 1dp 描边（`SettingCell.Background` 的
 * `border` 口径）：这个事实属于主题而不属于任何一屏，收在这里才不会各屏自己
 * 再写一份 `themeMode.isDark()`。
 */
@Composable
fun isDarkTheme(): Boolean = ThemeColors == DarkColors

/**
 * 版式：TG 的排版节奏是「标题 17sp 500、次级 15sp、时间 13sp」，比 M3 默认略小一号
 * —— 会话列表之所以显密，靠的是这个而不是留白。这里照那三档写死。
 */
val TextStyles = Typography(
    titleLarge = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 15.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 13.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 12.sp),
)

/**
 * 应用主题：把 [Colors] 与一份最小 [MaterialTheme] 一起下发。
 *
 * M3 那份 `ColorScheme` 是**适配产物**（`OutlinedTextField`/`AlertDialog` 等仍按它取色），
 * 不是本仓的语义色来源 —— 界面里的颜色一律走 [ThemeColors]。
 */
@Composable
fun Theme(
    mode: ThemeMode,
    content: @Composable () -> Unit,
) {
    val dark = mode.isDark()
    val palette = if (dark) DarkColors else LightColors
    val m3 = if (dark) {
        darkColorScheme(
            primary = palette.accent,
            background = palette.background,
            surface = palette.surface,
            error = palette.error,
        )
    } else {
        lightColorScheme(
            primary = palette.accent,
            background = palette.background,
            surface = palette.surface,
            error = palette.error,
        )
    }
    CompositionLocalProvider(LocalColors provides palette) {
        MaterialTheme(colorScheme = m3, typography = TextStyles, content = content)
    }
}
