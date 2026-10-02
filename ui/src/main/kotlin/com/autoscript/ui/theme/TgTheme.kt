package com.autoscript.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
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
 * 这个颜色到底是什么」变得不可读。故 [TgColors] 是本仓的语义调色板，
 * `ColorScheme` 只作为 `MaterialTheme` 的最小适配（部分 M3 组件仍要它）。
 *
 * 「GPL-2.0-or-later」下**未抄任何 TG 源码**：这里只有颜色数值（事实）与版式约定
 * （顶栏 + 底页签 + 密排列表），代码是本仓自己的。
 */
@Immutable
data class TgColors(
    /** 屏底（`windowBackgroundWhite`）。 */
    val background: Color,
    /** 卡片/顶栏底（`actionBarDefault`）。 */
    val surface: Color,
    /** 次级底：分组头、输入框底（`windowBackgroundGray`）。 */
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
    /** 底页签选中线（`actionBarTabLine`）。 */
    val tabIndicator: Color,
    /** 底页签未选中文字（`actionBarTabUnactiveText`）。 */
    val tabIdle: Color,
    /** 按下态遮罩（`actionBarDefaultSelector` 那种半透明压暗）。 */
    val pressedOverlay: Color,
    /** 输入框/搜索底：比 [surface] 略深一档，浅色下用灰、深色下用黑。 */
    val fieldBackground: Color,
)

/** 浅色（`day.attheme`）。 */
val TgLightColors = TgColors(
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
    tabIndicator = Color(0xFF298ACF),
    tabIdle = Color(0xFF777C7F),
    pressedOverlay = Color(0x14000000),
    fieldBackground = Color(0xFFF1F1F3),
)

/** 深色（`night.attheme`）。 */
val TgDarkColors = TgColors(
    background = Color(0xFF181819),
    surface = Color(0xFF232326),
    surfaceMuted = Color(0xFF1C1C1E),
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
    tabIndicator = Color(0xFF43B7FF),
    tabIdle = Color(0xFF8E8E8F),
    pressedOverlay = Color(0x1AFFFFFF),
    fieldBackground = Color(0xFF0F0F11),
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

val LocalTgColors: ProvidableCompositionLocal<TgColors> = staticCompositionLocalOf { TgLightColors }

/** 便捷取色：`TgTheme.colors`。 */
object TgTheme {
    val colors: TgColors
        @Composable get() = LocalTgColors.current
}

/**
 * 版式：TG 的排版节奏是「标题 17sp 500、次级 15sp、时间 13sp」，比 M3 默认略小一号
 * —— 会话列表之所以显密，靠的是这个而不是留白。这里照那三档写死。
 */
val TgTypography = Typography(
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
 * 应用主题：把 [TgColors] 与一份最小 [MaterialTheme] 一起下发。
 *
 * M3 那份 `ColorScheme` 是**适配产物**（`OutlinedTextField`/`AlertDialog` 等仍按它取色），
 * 不是本仓的语义色来源 —— 界面里的颜色一律走 [TgTheme.colors]。
 */
@Composable
fun TgTheme(
    mode: ThemeMode,
    content: @Composable () -> Unit,
) {
    val dark = mode.isDark()
    val tg = if (dark) TgDarkColors else TgLightColors
    val m3 = if (dark) {
        darkColorScheme(
            primary = tg.accent,
            background = tg.background,
            surface = tg.surface,
            error = tg.error,
        )
    } else {
        lightColorScheme(
            primary = tg.accent,
            background = tg.background,
            surface = tg.surface,
            error = tg.error,
        )
    }
    CompositionLocalProvider(LocalTgColors provides tg) {
        MaterialTheme(colorScheme = m3, typography = TgTypography, content = content)
    }
}
