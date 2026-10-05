package com.autoscript.ui.screens

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.ui.components.ActionBar
import com.autoscript.ui.components.ContextMenu
import com.autoscript.ui.components.GlyphKind
import com.autoscript.ui.components.MenuAction
import com.autoscript.ui.components.RefreshableBox
import com.autoscript.ui.components.ScrollToTopButton
import com.autoscript.ui.components.SettingIconColors
import com.autoscript.ui.components.SettingsCard
import com.autoscript.ui.components.SettingsCellRow
import com.autoscript.ui.components.TabBarBottomClearance
import com.autoscript.ui.components.ToneText
import com.autoscript.ui.components.pressable
import com.autoscript.ui.state.CapabilityCenterState
import com.autoscript.ui.state.CapabilityRowState
import com.autoscript.ui.state.Status
import com.autoscript.ui.state.StatusTone
import com.autoscript.ui.theme.ThemeColors
import com.autoscript.domain.permission.Capability
import kotlinx.coroutines.launch

/**
 * 设置页（批 47 按 TG 设置页重写）：顶栏**只留右上角 ⋮**（标题位空着），
 * 头部标识区 = 软件图标 90dp 正圆 + `NodeScript` 22sp 粗体居中（TG `topView` 的
 * 对应位：头像换软件图标、昵称换产品名），读态文案占 TG 副标题那格；
 * 全部权限合成**一张卡**、无分隔线、**不渲染描述**；「安装体积 xxx」整段撤下。
 *
 * 几何照 TG 实测落位：头部区高 188dp（列表第 0 项、随列表滚动）、图标顶 26dp、
 * 标题顶 ≈126dp（26 + 90 + 10）；卡片左右缩 12dp、圆角 16dp；行 50dp、
 * 28dp 渐变图标块 + 16sp 标题（`SettingCell.onMeasure` 的单行档）。
 *
 * 诚实边界（与其他屏同一条纪律）：
 * - 没读到 / 读失败在头部副标题位如实说（走 [Status] 的三态分派），**读到了就不说话**；
 * - 行尾保留三态的中文说法与逐态着色（判读在可测的 [CapabilityRowState]），
 *   可授权的行整行可点去系统页（[CapabilityRowState.canRequestGrant] 的投影）；
 * - 引导文案**不再渲染**（批 47 用户口径「去除各个权限的描述」）—— 字段仍在状态层
 *   原样透传，撤下的只是这一屏的渲染；
 * - 降级中的定时任务仍单列一段（§8.6 承诺「可能偏差」的账，用户本批未要求撤）。
 *
 * 顶栏刷新已删（批 47 当场拍板「直接删掉」）：`TabReloadEffect` 已在切页签 / 回前台
 * 现取，常驻钮无事可做（批 41 删任务中心刷新的同一口径）。⋮ 里放主题切换 ——
 * TG 设置页那格挂的是退出登录，本仓无登录，挂全局主题（与项目页 ⋮ 第一格同一项）。
 */
@Composable
fun SettingsScreen(
    state: CapabilityCenterState,
    onOpenSettings: (Capability) -> Unit,
    themeSwitchLabel: String,
    onSwitchTheme: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    Column(modifier.fillMaxWidth().background(ThemeColors.surfaceMuted)) {
        ActionBar(
            title = null,
            background = ThemeColors.surfaceMuted,
            actions = {
                SettingsMenu(themeSwitchLabel = themeSwitchLabel, onSwitchTheme = onSwitchTheme)
            },
        )
        Box(Modifier.weight(1f)) {
            RefreshableBox(Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    // 底部让出悬浮底栏（胶囊占位 + 导航 inset）：最后一张卡滚到底
                    // 不能被胶囊或三键导航压住。顶部不留 —— 头部区自己让 26dp。
                    contentPadding = PaddingValues(bottom = TabBarBottomClearance()),
                ) {
                    item { IdentityHeader(state) }
                    if (state.degradedAlarmTaskIds.isNotEmpty()) {
                        item {
                            // 非空不藏：精确闹钟被收回时这些任务降级成了 setWindow，
                            // 排期**可能偏差**（§8.6 的承诺）。
                            SettingsCard {
                                CardCaptionRow(
                                    text = "以下定时任务已降级（可能偏差）：" +
                                        state.degradedAlarmTaskIds.joinToString("、"),
                                    tone = StatusTone.ATTENTION,
                                )
                            }
                            CardGap()
                        }
                    }
                    // 行为空（未读 / 读失败 / 装配异常）时不画空卡 —— 一张空卡会被
                    // 读成「一个权限都没有」，那正是 LoadState 要防的冒充；读态由
                    // 头部如实说。
                    if (state.rows.isNotEmpty()) {
                        item {
                            SettingsCard {
                                // 全部权限同一张卡、行间无分隔线（TG `SettingCell`
                                // 的 `Factory.bindView` 本就不传 divider）。
                                state.rows.forEach { row -> PermissionRow(row, onOpenSettings) }
                            }
                        }
                    }
                }
            }
            ScrollToTopButton(
                visible = listState.firstVisibleItemIndex > 0,
                onClick = { scope.launch { listState.animateScrollToItem(0) } },
                // 回顶钮抬到悬浮底栏上方（胶囊占位 + 导航 inset，另加 8dp 呼吸）。
                modifier = Modifier.align(Alignment.BottomEnd)
                    .padding(start = 16.dp, end = 16.dp, bottom = TabBarBottomClearance(extra = 8.dp)),
            )
        }
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
 * 头部标识区（TG 设置页 `topView` 的对应位，列表第 0 项、随列表滚动）：
 * 软件图标 90dp 正圆 → 10dp → `NodeScript` 22sp 粗体，全部居中；
 * 读态文案占 TG 副标题那格（13sp 居中，紧贴标题下 ≈156dp 起位）——读到了就不画。
 *
 * 落位按 TG 实测三个数：图标顶 26dp、标题顶 126dp（26 + 90 + 10）、整区高 188dp。
 */
@Composable
private fun IdentityHeader(state: CapabilityCenterState) {
    // 三态各自说一句，走 Status 的 when（`LoadState` 的纪律：界面不自己 when 它）。
    // loadedText 传空串 = 读到了不说话 —— 头部标识区不是横幅位，读成功时挤一句
    // 「N 项权限」反而抢 `NodeScript` 的位（批 45 摘任务中心副标题的同一理由）。
    val status = Status.of(
        load = state.load,
        notLoadedText = "尚未读取",
        loadedText = "",
    )
    Box(Modifier.fillMaxWidth().height(188.dp)) {
        Column(
            Modifier.align(Alignment.TopCenter).padding(top = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AppIcon()
            Spacer(Modifier.height(10.dp))
            Text(
                text = "NodeScript",
                color = ThemeColors.text,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                ),
                textAlign = TextAlign.Center,
            )
            if (status.text.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                ToneText(
                    text = status.text,
                    tone = status.tone,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * 软件图标（TG 头像位的对应物）：90dp 正圆。
 *
 * 图标 = 本应用当前的 launcher 图标（`PackageManager.getApplicationIcon`）——
 * 仓库现在没有 `android:icon` 声明，取到的就是**系统默认图标先占个位置**
 * （用户口径「就先用系统默认的占位置」）；将来补上真图标即自动生效，不用改这里。
 * 取不到时回落成同尺寸的占位圆，不留 90dp 空洞。
 */
@Composable
private fun AppIcon() {
    val context = LocalContext.current
    val icon = remember(context) {
        runCatching { context.packageManager.getApplicationIcon(context.packageName) }
            .getOrNull()
            ?.toImageBitmapOrNull()
    }
    Box(Modifier.size(90.dp).clip(CircleShape)) {
        if (icon != null) {
            Image(
                bitmap = icon,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(Modifier.fillMaxSize().background(ThemeColors.surface))
        }
    }
}

/**
 * launcher 图标 → [ImageBitmap]（一次性转换，之后走 [remember] 缓存）。
 *
 * `setBounds` 不能省：`getApplicationIcon` 拿到的 Drawable 没有 bounds，
 * 不先设就按 0×0 画 —— 图标会是一张空图。
 */
private fun Drawable.toImageBitmapOrNull(): ImageBitmap? = runCatching {
    val width = intrinsicWidth.takeIf { it > 0 } ?: 128
    val height = intrinsicHeight.takeIf { it > 0 } ?: 128
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    setBounds(0, 0, width, height)
    draw(Canvas(bitmap))
    bitmap.asImageBitmap()
}.getOrNull()

/**
 * 一行权限（TG `SettingCell` 单行档：50dp、无副标题、无分隔线）。
 * 行尾 = 三态的中文说法（可点的行 LINK 蓝，与「点得动」读成一条；
 * GRANTED 灰、降级/被拒逐态警示色）。
 * 引导文案不再渲染（批 47 用户口径「去除各个权限的描述」）—— 去授权靠整行可点。
 */
@Composable
private fun PermissionRow(row: CapabilityRowState, onOpenSettings: (Capability) -> Unit) {
    SettingsCellRow(
        title = row.title,
        colors = row.capability.iconColors(),
        glyph = row.capability.glyph(),
        onClick = if (row.canRequestGrant) {
            { onOpenSettings(row.capability) }
        } else {
            null
        },
        trailing = {
            ToneText(
                text = row.stateLabel,
                tone = when {
                    row.canRequestGrant -> StatusTone.LINK
                    row.tone == StatusTone.OK -> StatusTone.MUTED
                    else -> row.tone
                },
                style = MaterialTheme.typography.labelMedium,
            )
        },
    )
}

/**
 * 设置页 `⋮`（TG 设置页顶栏右侧三个点的对应位，`ic_ab_other`）。
 *
 * TG 那格挂的是退出登录（`LogoutActivity`），本仓无登录概念 —— 挂全局的主题切换
 * （与项目页 ⋮ 第一格同一项、同一份「标签 = 目标模式」文案口径）。
 * 刷新钮批 47 已整颗删掉，不再挪进菜单。
 */
@Composable
private fun SettingsMenu(themeSwitchLabel: String, onSwitchTheme: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Text(
            text = "⋮",
            color = ThemeColors.text,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .pressable(role = Role.Button, onClick = { open = true })
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
        ContextMenu(
            expanded = open,
            onDismiss = { open = false },
            actions = listOf(MenuAction(label = themeSwitchLabel, onClick = onSwitchTheme)),
        )
    }
}

/** 逐能力配色（TG `IconBackgroundColors` 的色对）；业务映射留在设置页。 */
private fun Capability.iconColors(): SettingIconColors = when (this) {
    Capability.ACCESSIBILITY -> SettingIconColors(Color(0xFF1CA5ED), Color(0xFF1488E1))
    Capability.SCREEN_CAPTURE -> SettingIconColors(Color(0xFF4F85F6), Color(0xFF3568E8))
    Capability.OVERLAY -> SettingIconColors(Color(0xFF32C0CE), Color(0xFF1D9CC6))
    Capability.NOTIFICATION -> SettingIconColors(Color(0xFFF45255), Color(0xFFDF3955))
    Capability.SCHEDULE_EXACT_ALARM -> SettingIconColors(Color(0xFFF09F1B), Color(0xFFE18A11))
    Capability.ROOT -> SettingIconColors(Color(0xFFF28B31), Color(0xFFE26314))
    Capability.ADB_INPUT -> SettingIconColors(Color(0xFF55CA47), Color(0xFF27B434))
    Capability.POST_NOTIFICATIONS -> SettingIconColors(Color(0xFFC46EF4), Color(0xFF9F55DF))
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
