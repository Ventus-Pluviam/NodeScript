package com.autoscript.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.autoscript.ui.components.ActionBarAction
import com.autoscript.ui.components.Glyph
import com.autoscript.ui.components.GlyphKind
import com.autoscript.ui.components.ActionBar
import com.autoscript.ui.components.RefreshableBox
import com.autoscript.ui.components.ScrollToTopButton
import com.autoscript.ui.components.ToneText
import com.autoscript.ui.components.pressable
import com.autoscript.ui.components.rememberRefreshAction
import com.autoscript.ui.components.TabBarBottomClearance
import com.autoscript.ui.state.CapabilityCenterState
import com.autoscript.ui.state.CapabilityRowState
import com.autoscript.ui.state.Status
import com.autoscript.ui.state.StatusTone
import com.autoscript.ui.theme.ThemeColors
import com.autoscript.ui.theme.isDarkTheme
import com.autoscript.domain.permission.Capability
import kotlinx.coroutines.launch

/**
 * 设置页：权限（§9.5 三态 + 引导 + 一键跳系统页）为主，配「可能偏差」「安装体积」两段。
 *
 * 版式照 Telegram 设置页逐字复刻（`SettingsActivity` + `SettingCell`）：
 * **灰底上浮白色圆角分组卡片**（`windowBackgroundGray` 打底、`windowBackgroundWhite`
 * 卡片、圆角 16dp、左右各缩 12dp、卡间 4dp 空隙），一行 = 左侧 28dp 圆角方块
 * （彩色纵向渐变，`IconBackgroundColors` 那套）内嵌 24dp 线性图标 + 中间标题 16sp
 * （`windowBackgroundWhiteBlackText`）+ 副标题 13sp（`windowBackgroundWhiteGrayText`）
 * + 行尾文字值（`windowBackgroundWhiteBlueText`）。行高带副标题 60dp、不带 50dp
 * （`SettingCell.onMeasure` 的口径）。
 *
 * 诚实边界（与其他屏同一条纪律）：
 * - 没读到（首帧/失败）显示「尚未读取」/失败原因，**不冒充**「一个能力都没有」；
 * - 每行原样显示宿主给的引导文案（`:domain` 那份 `guideText`），呈现层不加工 ——
 *   加工过的引导文案会和系统里的真实路径漂移；
 * - 三态的说法逐态不同（可用/降级可用/被拒绝），因为"用户该做什么"逐态不同；
 *   着色档由 [CapabilityRowState.tone] 给（判读在可测的面，不在 Composable 里）；
 * - 「去授权」按钮的显隐直接读 [CapabilityRowState.canRequestGrant]，呈现层不做判断。
 * - 降级中的定时任务单列一段：那是 §8.6 承诺要标注「可能偏差」的账，不是权限问题。
 * - 安装体积单列一段（§15 E1「接受并明示」）：超支是既成事实，披露的时机是**装之前**
 *   用户能读到的那一屏，而不是装完才发现。
 *
 * 交互：整行可点（有引导可跳的行去系统页）+ 「回到顶部」（权限有十几项，滚到下面
 * 想回第一项时，手指要在系统手势区边缘往上蹭好几下）。**不做长按菜单** —— 这一屏
 * 每行的动作只有一个（去授权），摆成菜单反而是把唯一的动作藏起来。
 */
@Composable
fun SettingsScreen(
    state: CapabilityCenterState,
    onRefresh: suspend () -> Unit,
    onOpenSettings: (Capability) -> Unit,
    modifier: Modifier = Modifier,
) {
    val status = Status.of(
        load = state.load,
        notLoadedText = "尚未读取（点右上「刷新」现问系统）",
        loadedText = "${state.rows.size} 项权限（三态现问系统，不缓存）",
    )
    val refresh = rememberRefreshAction(onRefresh)
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    Column(modifier.fillMaxWidth().background(ThemeColors.surfaceMuted)) {
        ActionBar(
            title = "设置",
            subtitle = status.text,
            subtitleTone = status.tone,
            actions = { ActionBarAction("刷新", refresh::trigger) },
        )
        Box(Modifier.weight(1f)) {
            RefreshableBox(Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    // TG 设置页的节奏：卡片离顶栏一小段灰、离底部一小段灰。
                    // 底部这份要盖过悬浮底栏（胶囊占位 + 导航 inset）：最后一张卡
                    // 滚到底不能被胶囊或三键导航压住。
                    contentPadding = PaddingValues(top = 8.dp, bottom = TabBarBottomClearance()),
                ) {
                    state.installSize?.let { size ->
                        item {
                            SettingsCard {
                                CardCaptionRow(
                                    text = size.text(),
                                    tone = if (size.engineFilesPresent) StatusTone.MUTED else StatusTone.ATTENTION,
                                )
                            }
                            CardGap()
                        }
                    }
                    if (state.degradedAlarmTaskIds.isNotEmpty()) {
                        item {
                            // 非空不藏：精确闹钟被收回时这些任务降级成了 setWindow，
                            // 排期**可能偏差**（§8.6 的承诺）。
                            SettingsCard {
                                CardCaptionRow(
                                    text = "以下定时任务已降级（可能偏差）：${state.degradedAlarmTaskIds.joinToString("、")}",
                                    tone = StatusTone.ATTENTION,
                                )
                            }
                            CardGap()
                        }
                    }
                    items(state.rows, key = { it.capability.name }) { row ->
                        SettingRow(row, onOpenSettings)
                        CardGap()
                    }
                }
            }
            ScrollToTopButton(
                visible = listState.firstVisibleItemIndex > 0,
                onClick = { scope.launch { listState.animateScrollToItem(0) } },
                // 回顶钮抬到悬浮底栏上方（胶囊占位 + 导航 inset，另加 8dp 呼吸 —— TG 的 FAB 同款让位）。
                modifier = Modifier.align(Alignment.BottomEnd)
                    .padding(start = 16.dp, end = 16.dp, bottom = TabBarBottomClearance(extra = 8.dp)),
            )
        }
    }
}

/**
 * 白色圆角分组卡片（TG `setSections` 的逐字版式）：
 * 左右各缩 [SectionHPad]，圆角 16dp、白底（`windowBackgroundWhite`）、无描边无阴影
 * （`SharedConfig.shadowsInSections` 缺省 false —— TG 的卡片是靠灰底衬出来的）。
 *
 * 相邻行合进同一张卡：卡片内行间不空隙，行间分隔线由行自己画。
 */
@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Column(
        Modifier
            .padding(horizontal = SectionHPad)
            .fillMaxWidth()
            .background(ThemeColors.surface, RoundedCornerShape(16.dp)),
    ) {
        content()
    }
}

/** 两张卡片之间的灰底空隙（TG 卡片间距 4dp 的投影；`asShadow` 的空白段）。 */
@Composable
private fun CardGap() {
    Spacer(Modifier.height(4.dp))
}

/** 卡片内一段说明文字（无图标的整卡文本行；TG `TextInfoPrivacyCell` 在卡内的读法）。 */
@Composable
private fun CardCaptionRow(text: String, tone: StatusTone) {
    ToneText(
        text = text,
        tone = tone,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

/**
 * 一行设置项（TG `SettingCell` 的复刻）：
 * 左 28dp 渐变圆角方块（圆角 10dp，深色下 1dp 描边 —— `Background.draw` 的口径）
 * 内嵌 24dp 线性图标 + 标题 16sp + 副标题 13sp + 行尾文字值（蓝）。
 *
 * 副标题（引导文案）**永远**显示（含 GRANTED 时那句"当前可用"）—— 不按三态去猜
 * 该不该显示；行尾值是三态的中文说法，可跳系统页的行整行可点。
 */
@Composable
private fun SettingRow(row: CapabilityRowState, onOpenSettings: (Capability) -> Unit) {
    val dark = isDarkTheme()
    val colors = SettingIconColors.of(row.capability)
    val clickable = if (row.canRequestGrant) {
        Modifier.pressable(role = Role.Button, onClick = { onOpenSettings(row.capability) })
    } else {
        Modifier
    }
    Row(
        modifier = clickable
            .fillMaxWidth()
            .background(ThemeColors.surface)
            .padding(horizontal = 18.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingIconBlock(colors = colors, dark = dark, glyph = row.capability.glyph())
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = row.title,
                color = ThemeColors.text,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = row.guide,
                color = ThemeColors.textTertiary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.width(12.dp))
        // 行尾值：三态的中文说法。可用 = 灰（TG 的 value 位多数时候是灰的），
        // 降级/被拒 = 逐态警示色；可点的行是 LINK 蓝 —— 与"点得动"读成一条。
        ToneText(
            text = row.stateLabel,
            tone = when {
                row.canRequestGrant -> StatusTone.LINK
                row.tone == StatusTone.OK -> StatusTone.MUTED
                else -> row.tone
            },
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

/** 卡片左右缩进（`ListSectionsDecoration` 的 padding 12dp）。 */
private val SectionHPad = 12.dp

/** 设置图标 28dp 方块的一对纵向渐变色（`IconBackgroundColors` 的色对）。 */
private data class SettingIconColors(val top: Color, val bottom: Color) {
    companion object {
        /** 逐能力配色（`IconBackgroundColors` 那套色值：蓝/橙/绿/红/青/紫）。 */
        fun of(capability: Capability): SettingIconColors = when (capability) {
            Capability.ACCESSIBILITY -> SettingIconColors(Color(0xFF1CA5ED), Color(0xFF1488E1))
            Capability.SCREEN_CAPTURE -> SettingIconColors(Color(0xFF4F85F6), Color(0xFF3568E8))
            Capability.OVERLAY -> SettingIconColors(Color(0xFF32C0CE), Color(0xFF1D9CC6))
            Capability.NOTIFICATION -> SettingIconColors(Color(0xFFF45255), Color(0xFFDF3955))
            Capability.SCHEDULE_EXACT_ALARM -> SettingIconColors(Color(0xFFF09F1B), Color(0xFFE18A11))
            Capability.ROOT -> SettingIconColors(Color(0xFFF28B31), Color(0xFFE26314))
            Capability.ADB_INPUT -> SettingIconColors(Color(0xFF55CA47), Color(0xFF27B434))
            Capability.POST_NOTIFICATIONS -> SettingIconColors(Color(0xFFC46EF4), Color(0xFF9F55DF))
        }
    }
}

/**
 * 28dp 圆角方块 + 居中的 24dp 线性图标。
 *
 * 纵向渐变从 [SettingIconColors] 取；深色下加 1dp 半透明白描边
 * （`SettingCell.Background.draw` 里 `border` 的口径：暗主题下渐变块与深底粘连，
 * TG 用描边把轮廓提出来）。
 */
@Composable
private fun SettingIconBlock(colors: SettingIconColors, dark: Boolean, glyph: GlyphKind) {
    val brush = Brush.verticalGradient(listOf(colors.top, colors.bottom))
    Box(
        Modifier
            .size(28.dp)
            .background(brush, RoundedCornerShape(10.dp))
            .then(
                if (dark) {
                    Modifier.border(
                        1.dp,
                        Color.White.copy(alpha = 0.10f),
                        RoundedCornerShape(10.dp),
                    )
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        // 图标画在 24dp 里、白色：彩色渐变块上的白色线性图标是 TG 设置页的识别点。
        Glyph(kind = glyph, tint = Color.White, size = 24.dp)
    }
}

/** 每个能力一个图标形（复用底栏那套画出来的线性字形，不引图标依赖）。 */
private fun Capability.glyph(): GlyphKind = when (this) {
    Capability.ACCESSIBILITY -> GlyphKind.ACCESSIBILITY
    Capability.SCREEN_CAPTURE -> GlyphKind.SCREEN
    Capability.OVERLAY -> GlyphKind.OVERLAY
    Capability.NOTIFICATION, Capability.POST_NOTIFICATIONS -> GlyphKind.BELL
    Capability.SCHEDULE_EXACT_ALARM -> GlyphKind.TASKS
    Capability.ROOT -> GlyphKind.SHIELD
    Capability.ADB_INPUT -> GlyphKind.CONSOLE
}
