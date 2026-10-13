package com.autoscript.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.ui.components.ActionBar
import com.autoscript.ui.components.GlyphKind
import com.autoscript.ui.components.LocalTabBarHidden
import com.autoscript.ui.components.RefreshableBox
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
 *
 * **日志管理 / 控制台的分工**（2026-10-09 批 84，用户口径「控制台不是放系统日志的地方，
 * 是用来执行命令的」）：控制台是**命令面**（跑 npm 命令与依赖提供的命令，§10.9 第 3 条），
 * 日志（宿主事件 / 脚本输出 / 任务日志三段）全在日志管理 —— 两者从此没有重叠。
 */
@Composable
fun ManagementScreen(
    onOpenConsole: () -> Unit,
    onOpenLogManagement: () -> Unit,
    onOpenNpm: () -> Unit,
    onOpenEnv: () -> Unit,
    onOpenRegistry: () -> Unit,
    /**
     * 切入/切出动画进行中 —— **为 true 时所有入口点击都不响应**（TG `animationInProgress`：
     * 退出动画没播完就点新入口，TG 里是什么都不发生，`:1952` 的
     * `presentFragmentInternalRemoveOld` 直接 `return false`）。
     *
     * 不吞的话 `page` 在同一帧内两次变 → `LaunchedEffect(page)` 被取消，
     * 被取消协程里 `top = null` 没执行完 → `top` 卡死。
     */
    entriesEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    // 悬浮底栏的隐藏开关（外壳供）。**进了子页底栏整条收掉**（用户口径 2026-10-13：
    // 「点进列表里面时让底栏消失，退出来时才重新出现」）—— 四个页签既是别的页面、
    // 又压着子页内容底部，与项目页进目录层、设置页进子页是同一条理由。
    //
    // **为什么这里只写 true、复位在 `MainActivity` 的 `onBackRequest` 里**：本屏拿不到
    // `subPage`（那是外壳的层级状态），"什么时候该放回来"只有外壳知道。收的那一侧
    // 必须写在这里 —— 本屏是唯一知道"用户点了要进子页"的地方（外壳只看到 `npmOpen` 变了）。
    val tabBarHidden = LocalTabBarHidden.current
    // 一处包住全部入口：TG 是 `checkTransitionAnimation()` 在**进入栈的那一个方法**开头
    // 统一判的，不是每个 fragment 各自判 —— 这里同理，六个回调共用一道门。
    fun open(block: () -> Unit) {
        if (!entriesEnabled) return
        block()
        // 收底栏与"子页就位"同一帧（`block()` 就是 `npmOpen = true` 那一句）。
        tabBarHidden.value = true
    }
    Column(modifier.fillMaxSize().background(ThemeColors.surfaceMuted)) {
        ActionBar(
            title = "管理面板",
            // 顶栏标题与任务中心同款 20sp Medium（TG `createTitleTextView` 的 portrait 档）
            // —— 两个页签顶栏标题不同字号会读成两种语义：像一行里混了正文与标题。
            titleStyle = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Medium),
            background = ThemeColors.surfaceMuted,
        )
        // 下拉形变落点与其余三屏同款（`RefreshableBox`，见其 KDoc）—— 本屏只有五个入口、
        // 内容恒不满一屏，正是平台那份 `EdgeEffect` 一次都不触发的形态。
        Box(Modifier.weight(1f)) {
            RefreshableBox(Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 8.dp, bottom = TabBarBottomClearance()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        SettingsCard {
                            SettingsCellRow(
                                title = "依赖管理",
                                colors = DependencyColors,
                                glyph = GlyphKind.FILE_GENERIC,
                                onClick = { open(onOpenNpm) },
                            )
                            SettingsCellRow(
                                title = "环境变量",
                                colors = EnvironmentColors,
                                glyph = GlyphKind.SETTINGS,
                                onClick = { open(onOpenEnv) },
                            )
                            SettingsCellRow(
                                title = "日志管理",
                                colors = LogColors,
                                glyph = GlyphKind.FILE_DOC,
                                onClick = { open(onOpenLogManagement) },
                            )
                            SettingsCellRow(
                                title = "镜像源管理",
                                colors = RegistryColors,
                                glyph = GlyphKind.FILE_HTML,
                                onClick = { open(onOpenRegistry) },
                            )
                        }
                    }
                    item {
                        SettingsCard {
                            SettingsCellRow(
                                title = "控制台",
                                colors = ConsoleColors,
                                glyph = GlyphKind.CONSOLE,
                                onClick = { open(onOpenConsole) },
                            )
                        }
                    }
                }
            }
        }
    }
}
