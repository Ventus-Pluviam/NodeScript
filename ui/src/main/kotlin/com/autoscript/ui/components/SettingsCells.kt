package com.autoscript.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.autoscript.ui.state.StatusTone
import com.autoscript.ui.theme.ThemeColors
import com.autoscript.ui.theme.isDarkTheme

/**
 * TG 设置分组：左右各缩 12dp、圆角 16dp，无描边无阴影。
 *
 * 相邻行合进同一张卡，裁剪包住整组，首尾行按压时也守住圆角。
 * 设置页与管理面板共用这份几何，权限到图标/颜色的映射仍由设置页自己持有。
 * **卡内不画分隔线**（批 47）：TG 的 `SettingCell` 走 `Factory.bindView` 时压根不传
 * divider；用户口径「分组那不需要横线分隔」同向。
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

/**
 * 一行设置项（TG `SettingCell` 的几何，管理面板与设置页**共用这一份**）：
 * 28dp 渐变图标块 + 18dp + 标题 16sp（[MaterialTheme.typography.titleMedium]）+ 可选行尾槽位。
 *
 * 最小高按 [SettingCell.onMeasure] 的口径：单行 50dp、带 [subtitle] 60dp；
 * 内边距 18dp/9dp，[onClick] 非空即整行可按（触控区含内边距，不按内容缩）。
 * 行间不画分隔线 —— 分隔线是调用方的事，两屏都选择不画（见 [SettingsCard]）。
 *
 * @param subtitle 第二行（13sp）。批 48 起只有「权限」入口用它挂读态；
 *   权限行本身**没有**副标题（批 47 用户口径「去除各个权限的描述」）。
 * @param subtitleTone 副标题着色档（读失败走 PROBLEM，与行尾三态值同一套语义）。
 * @param trailing 行尾槽位（设置页子页放三态值；入口行没有就不传，不占位）。
 */
@Composable
internal fun SettingsCellRow(
    title: String,
    colors: SettingIconColors,
    glyph: GlyphKind,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    subtitleTone: StatusTone = StatusTone.MUTED,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) Modifier.pressable(role = Role.Button, onClick = onClick)
                else Modifier,
            )
            .heightIn(min = if (subtitle != null) 60.dp else 50.dp)
            .padding(horizontal = 18.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingIconBlock(colors = colors, glyph = glyph)
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                color = ThemeColors.text,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(4.dp))
                ToneText(
                    text = subtitle,
                    tone = subtitleTone,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.let {
            Spacer(Modifier.width(12.dp))
            it()
        }
    }
}
