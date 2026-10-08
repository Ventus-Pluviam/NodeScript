package com.autoscript.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.autoscript.ui.components.ActionBar
import com.autoscript.ui.components.GlyphKind
import com.autoscript.ui.components.SettingIconColors
import com.autoscript.ui.components.SettingsCard
import com.autoscript.ui.components.SettingsCellRow
import com.autoscript.ui.components.TabBarBottomClearance
import com.autoscript.ui.theme.ThemeColors

/**
 * 管理面板：四项管理入口同卡，控制台单独成卡（批 46）。
 *
 * 行沿用 TG `SettingsActivity.SettingCell` 的单行档（50dp、28dp 渐变图标块、
 * 图标与标题间 18dp），几何走共用的 [SettingsCellRow]（批 47 抽出，与设置页同一份）；
 * 两组间 12dp 灰缝来自 `ShadowSectionCell` 缺省高。
 * **组内不画横线**（批 47）：TG `SettingCell` 的 `Factory.bindView` 不传分隔线，
 * 用户口径「分组那不需要横线分隔」同向。
 *
 * 入口现状：四项**全部落地**，不再有占位 —— **依赖管理**（2026-10-09 批 81）、
 * **环境变量**（同日批 82）、**镜像源管理**（同日批 83）、**日志管理**与**控制台**（更早）。
 * 面板不持有宿主读口，也不把未实现的功能画成空数据或保存成功。
 */
@Composable
fun ManagementScreen(
    onOpenConsole: () -> Unit,
    onOpenLogManagement: () -> Unit,
    onOpenNpm: () -> Unit,
    onOpenEnv: () -> Unit,
    onOpenRegistry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().background(ThemeColors.surfaceMuted)) {
        ActionBar(title = "管理面板", background = ThemeColors.surfaceMuted)
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(top = 8.dp, bottom = TabBarBottomClearance()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SettingsCard {
                    SettingsCellRow(
                        title = "依赖管理",
                        colors = DependencyColors,
                        glyph = GlyphKind.FILE_GENERIC,
                        onClick = onOpenNpm,
                    )
                    SettingsCellRow(
                        title = "环境变量",
                        colors = EnvironmentColors,
                        glyph = GlyphKind.SETTINGS,
                        onClick = onOpenEnv,
                    )
                    SettingsCellRow(
                        title = "日志管理",
                        colors = LogColors,
                        glyph = GlyphKind.FILE_DOC,
                        onClick = onOpenLogManagement,
                    )
                    SettingsCellRow(
                        title = "镜像源管理",
                        colors = RegistryColors,
                        glyph = GlyphKind.FILE_HTML,
                        onClick = onOpenRegistry,
                    )
                }
            }
            item {
                SettingsCard {
                    SettingsCellRow(
                        title = "控制台",
                        colors = ConsoleColors,
                        glyph = GlyphKind.CONSOLE,
                        onClick = onOpenConsole,
                    )
                }
            }
        }
    }
}

// 与设置页相同的 TG 渐变色对；图标语义由各屏自己选，不把业务映射放进共用组件。
private val DependencyColors = SettingIconColors(Color(0xFFF28B31), Color(0xFFE26314))
private val EnvironmentColors = SettingIconColors(Color(0xFFC46EF4), Color(0xFF9F55DF))
private val LogColors = SettingIconColors(Color(0xFF4F85F6), Color(0xFF3568E8))
private val RegistryColors = SettingIconColors(Color(0xFF32C0CE), Color(0xFF1D9CC6))
private val ConsoleColors = SettingIconColors(Color(0xFF55CA47), Color(0xFF27B434))
