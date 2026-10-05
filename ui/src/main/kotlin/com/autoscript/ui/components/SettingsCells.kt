package com.autoscript.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.autoscript.ui.theme.ThemeColors
import com.autoscript.ui.theme.isDarkTheme

/**
 * TG 设置分组：左右各缩 12dp、圆角 16dp，无描边无阴影。
 *
 * 相邻行合进同一张卡，分隔线由调用方画；裁剪包住整组，首尾行按压时也守住圆角。
 * 设置页与管理面板共用这份几何，权限到图标/颜色的映射仍由设置页自己持有。
 */
@Composable
internal fun SettingsCard(content: @Composable () -> Unit) {
    Column(
        Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(ThemeColors.surface),
    ) {
        content()
    }
}

/** 设置图标方块的一对纵向渐变色（TG `IconBackgroundColors` 的色对）。 */
internal data class SettingIconColors(val top: Color, val bottom: Color)

/**
 * 28dp 圆角方块 + 居中的 24dp 白色线性图标（TG `SettingsActivity.SettingCell`）。
 *
 * 深色下加 1dp 半透明白描边，沿用设置页的做法，避免渐变块与深底粘连。
 */
@Composable
internal fun SettingIconBlock(colors: SettingIconColors, glyph: GlyphKind) {
    val dark = isDarkTheme()
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .size(28.dp)
            .background(Brush.verticalGradient(listOf(colors.top, colors.bottom)), shape)
            .then(
                if (dark) Modifier.border(1.dp, Color.White.copy(alpha = 0.10f), shape)
                else Modifier,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Glyph(kind = glyph, tint = Color.White, size = 24.dp)
    }
}
