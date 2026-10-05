package com.autoscript.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.autoscript.ui.components.ActionBar
import com.autoscript.ui.components.GlyphKind
import com.autoscript.ui.components.LocalToast
import com.autoscript.ui.components.Separator
import com.autoscript.ui.components.SettingIconBlock
import com.autoscript.ui.components.SettingIconColors
import com.autoscript.ui.components.SettingsCard
import com.autoscript.ui.components.TabBarBottomClearance
import com.autoscript.ui.components.pressable
import com.autoscript.ui.theme.ThemeColors

/**
 * 管理面板：四项管理入口同卡，控制台单独成卡（批 46）。
 *
 * 行沿用 TG `SettingsActivity.SettingCell` 的单行档：50dp、28dp 渐变图标块、
 * 图标与标题间 18dp；两组间 12dp 灰缝来自 `ShadowSectionCell` 缺省高。
 * 卡片与图标块和设置页共用，主题变化时保持同一套颜色与圆角。
 *
 * 本批只做入口：四项管理页尚未实现，点击直接弹未开放提示；控制台进入已有页面，
 * 面板不持有宿主读口，也不把未实现的功能画成空数据或保存成功。
 */
@Composable
fun ManagementScreen(onOpenConsole: () -> Unit, modifier: Modifier = Modifier) {
    val toast = LocalToast.current
    Column(modifier.fillMaxSize().background(ThemeColors.surfaceMuted)) {
        ActionBar(title = "管理面板", background = ThemeColors.surfaceMuted)
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(top = 8.dp, bottom = TabBarBottomClearance()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SettingsCard {
                    ManagementEntry("依赖管理", GlyphKind.FILE_GENERIC, DependencyColors) {
                        toast?.show("依赖管理尚未开放")
                    }
                    Separator(indentDp = 64)
                    ManagementEntry("环境变量", GlyphKind.SETTINGS, EnvironmentColors) {
                        toast?.show("环境变量尚未开放")
                    }
                    Separator(indentDp = 64)
                    ManagementEntry("日志管理", GlyphKind.FILE_DOC, LogColors) {
                        toast?.show("日志管理尚未开放")
                    }
                    Separator(indentDp = 64)
                    ManagementEntry("镜像源管理", GlyphKind.FILE_HTML, RegistryColors) {
                        toast?.show("镜像源管理尚未开放")
                    }
                }
            }
            item {
                SettingsCard {
                    ManagementEntry("控制台", GlyphKind.CONSOLE, ConsoleColors, onOpenConsole)
                }
            }
        }
    }
}

/** 无副标题的设置行；最小高 50dp，大字体时允许自然长高，触控区覆盖整行。 */
@Composable
private fun ManagementEntry(
    title: String,
    glyph: GlyphKind,
    colors: SettingIconColors,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressable(role = Role.Button, onClick = onClick)
            .heightIn(min = 50.dp)
            .padding(horizontal = 18.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingIconBlock(colors = colors, glyph = glyph)
        Spacer(Modifier.width(18.dp))
        Text(
            text = title,
            color = ThemeColors.text,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

// 与设置页相同的 TG 渐变色对；图标语义由各屏自己选，不把业务映射放进共用组件。
private val DependencyColors = SettingIconColors(Color(0xFFF28B31), Color(0xFFE26314))
private val EnvironmentColors = SettingIconColors(Color(0xFFC46EF4), Color(0xFF9F55DF))
private val LogColors = SettingIconColors(Color(0xFF4F85F6), Color(0xFF3568E8))
private val RegistryColors = SettingIconColors(Color(0xFF32C0CE), Color(0xFF1D9CC6))
private val ConsoleColors = SettingIconColors(Color(0xFF55CA47), Color(0xFF27B434))
