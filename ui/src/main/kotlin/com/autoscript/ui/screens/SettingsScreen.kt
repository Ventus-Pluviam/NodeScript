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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.ui.ManagementTransition
import com.autoscript.ui.components.ActionBar
import com.autoscript.ui.components.BAR_MORE_DOTS_WEIGHT
import com.autoscript.ui.components.BarMoreDotsSize
import com.autoscript.ui.components.ContextMenu
import com.autoscript.ui.components.Glyph
import com.autoscript.ui.components.centerInRoot
import com.autoscript.ui.components.GlyphKind
import com.autoscript.ui.components.LocalToast
import com.autoscript.ui.components.MenuAction
import com.autoscript.ui.components.RefreshableBox
import com.autoscript.ui.components.LocalTabBarHidden
import com.autoscript.ui.components.ScrollToTopButton
import com.autoscript.ui.components.rememberScrollToTopVisible
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
import com.autoscript.ui.state.ScreenStatus
import com.autoscript.ui.state.ScriptLimitsState
import kotlinx.coroutines.launch

/**
 * 设置页（批 49 起「两组纯文字 + 权限列表子页」两层）：
 *
 * **顶页** = 头部标识区（软件图标 90dp 正圆 + `NodeScript` 22sp 粗体 —— TG 设置页
 * `topView` 的对应位，随列表滚动）+ 两张纯文字卡：①「权限列表」（点进子页）/
 *「语言切换」/「帮助文档」；②「反馈」/「关于」/「检查更新」；再加降级定时任务段。
 * 这些行**无图标、无副标题**（批 49 用户口径「都不需要图标，还有标题下面的小字」——
 * 连同批 48 挂在入口副标题位的读态一并撤下）；没实现的功能点按 toast 如实说「尚未开放」，
 * 不画成空页。顶栏只留右上 `⋮`（主题切换）。
 * **子页** = 点「权限列表」进入：顶栏换「‹ 返回 + 权限列表」，全部权限行同卡、
 * 无分隔线、行尾三态值，可授权行整行可点（权限行保留图标 —— 那是模块自己的脸）。
 * 系统返回先关子页再交外壳（[BackHandler]）。
 *
 * 为什么收进子页（批 48 用户口径「顶层入口进二级页」，TG 主设置页
 * 「Privacy and Security」的形态）：顶层要的是**命名的列表结构**，权限九行平铺会把
 * 入口与内容混在一层。
 *
 * 诚实边界（与其他屏同一条纪律）：
 * - 读态顶页不再展示（批 49 去掉标题下的小字）；子页没读到 / 读失败时卡内如实说一句
 *   （[Status] 的三态分派），**不铺空卡**冒充「一个权限都没有」；
 * - 行尾三态中文说法与逐态着色判读在可测的 [CapabilityRowState]，可授权的行整行可点
 *   （[CapabilityRowState.canRequestGrant] 的投影）；
 * - 引导文案**不渲染**（批 47 口径「去除各个权限的描述」）；安装体积**不渲染**
 *   （批 47 口径）—— 字段与换算仍在状态层，测试钉着；
 * - 降级中的定时任务在**顶页**单列一段（§8.6 承诺「可能偏差」的账，不是权限问题）。
 *
 * 刷新钮不设（批 41/47 同一口径）：`TabReloadEffect` 在切页签 / 回前台现取，
 * 子页开着时回前台同样现取（授权返回后的重读走这一条）。
 */
// 10 个参数压着 detekt `LongParameterList` 的线（9）。**刻意不打包**：五个限额回调本来
// 就已收成一个 [ScriptLimitsDraft] 回调（见 [ScriptLimitsScreen.onDraft]），再往上打���
// `LimitsCallbacks` 只会让「改一个回调要开另一个类型的定义」——那正是本仓点名的
// 「打包省了参数数、丢了可读性」。
@Suppress("LongParameterList")
@Composable
fun SettingsScreen(
    state: CapabilityCenterState,
    onOpenSettings: (Capability) -> Unit,
    /** 脚本设置子页的状态与回调（批 107；§8.1）。本屏不持有读口，只透传。 */
    limitsState: ScriptLimitsState,
    onLimitsRefresh: suspend () -> Unit,
    onLimitsDraft: (field: ScriptLimitsDraft, value: String) -> Unit,
    onLimitsSave: () -> Unit,
    onLimitsReset: () -> Unit,
    themeSwitchLabel: String,
    onSwitchTheme: (Offset) -> Unit,
    /**
     * 本页是不是 pager 当前停稳的那一页。pager 预组合邻页（`beyondViewportPageCount = 1`），
     * 邻页里那个手势返回处理器会替前台页签吞返回键（`OnBackPressedDispatcher` 是 LIFO，
     * 顺序由组合顺序定）—— 不看 `active` 就是「在任务页按返回、设置页自己退了一层」。
     */
    active: Boolean = true,
    modifier: Modifier = Modifier,
) {
    // 两个子页，故用**枚举而不是 Boolean**：布尔只能表示「开着哪个」，而本页已有两个里页，
    // 用布尔就得再加一个互斥判据 —— 那正是「按下去发现两个同时开着」那类 bug 的形状。
    var subPage by rememberSaveable { mutableStateOf(SettingsSubPage.NONE) }
    // 动画进行中（TG `animationInProgress`）—— 底下那份的入口照旧吞点击，与管理面板
    // 同一口径：退出动画没播完就点新入口，在 TG 里是什么都不发生。
    var animating by remember { mutableStateOf(false) }
    // 悬浮底栏的隐藏开关（外壳供）。**进了子页底栏整条收掉**（用户口径 2026-10-13：
    // 「点进列表里面时让底栏消失，退出来时才重新出现」）—— 子页与项目页的目录层同一条
    // 理由：那四个页签既是别的页面、又压着内容底部。
    val tabBarHidden = LocalTabBarHidden.current
    // 底栏收放的**唯一**判据（与 `ProjectScreen` 那条 `fullScreen` 同一写法）：子页在前台
    // = 占整屏 = 收掉；回顶页才放回来。单一判据保证"收/放"永远跟着状态走。
    val fullScreen = subPage != SettingsSubPage.NONE
    LaunchedEffect(fullScreen) { tabBarHidden.value = fullScreen }

    // 读态三态分派走 Status（`LoadState` 的纪律：界面不自己 when 它）。
    // 顶页不再挂它（批 49 去掉标题下的小字）—— 只在子页没读到 / 读失败时如实说。
    val status = Status.of(
        load = state.load,
        notLoadedText = "尚未读取",
        loadedText = "${state.rows.size} 项",
    )
    val toast = LocalToast.current
    // 一处包住全部入口：TG 是 `checkTransitionAnimation()` 在**进入栈的那一个方法**开头
    // 统一判的，不是每个入口各自判。
    fun open(target: SettingsSubPage) {
        if (animating) return
        subPage = target
    }
    val topListState = rememberLazyListState()
    val pageListState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // **返回键整个交给 [ManagementTransition]**（它把「子页 ⇄ 顶页」与跟手手势合走
    // 一条路，TG 也是一个 `OnBackAnimationCallback` 管四态，`LaunchActivity:738-818`）。
    // 所以**不再挂 `BackHandler`** —— `OnBackPressedDispatcher` 是 LIFO，它排在后面
    // 注册就把预测式手势整个吃掉（管理面板踩过这个坑）。
    ManagementTransition(
        page = subPage,
        active = active,
        onAnimatingChanged = { animating = it },
        onBackRequest = { subPage = SettingsSubPage.NONE },
        modifier = modifier,
        // 显式写 `content =`：尾随 lambda 只能绑到**签名里最后一个**形参，而这里最后
        // 一个是 `modifier`（不是函数类型）—— 写尾随 lambda 会被判成「实参过多」。
        content = { page ->
            // 底下那份 = 顶栏 + 列表 + 回顶钮。**子页开着时它照旧组合** —— 离场动画要求旧页
            // 在动画期间仍在树上；列表本身不画（被盖住了，白组合一遍纯浪费）。
            @Composable
            fun TopLevel() {
                Column(Modifier.fillMaxSize().background(ThemeColors.surfaceMuted)) {
                    ActionBar(
                        title = null,
                        background = ThemeColors.surfaceMuted,
                        actions = {
                            SettingsMenu(themeSwitchLabel = themeSwitchLabel, onSwitchTheme = onSwitchTheme)
                        },
                    )
                    Box(Modifier.weight(1f)) {
                        // **列表全程都画**，子页盖住时也不省 —— 离场/入场动画期间底下这份
                        // **就是参照物**：48dp 的横移、淡入与圆角缩放都得有个东西在底下才
                        // 看得出层次。之前「被盖住就不画」，底下只剩一张同色空底，动画看着
                        // 「一点效果都没有」。TG `:1170` 同款：
                        // `if (!isTransitionAnimationInProgress()) canvas.clipRect(...)`
                        // —— 切换动画期间底层**根本不裁**。
                        RefreshableBox(Modifier.fillMaxSize()) {
                            LazyColumn(
                                state = topListState,
                                // 顶部不留：头部区自己让 26dp；底部让出悬浮底栏与导航 inset。
                                contentPadding = PaddingValues(bottom = TabBarBottomClearance()),
                            ) {
                                item { IdentityHeader() }
                                item {
                                    // 第一组：权限列表（唯一有里页的行）+ 两个未开放入口。
                                    // 全部纯文字（批 49：无图标、无副标题）。
                                    SettingsCard {
                                        SettingsCellRow(
                                            title = "权限列表",
                                            onClick = { open(SettingsSubPage.PERMISSIONS) },
                                        )
                                        SettingsCellRow(
                                            title = "脚本设置",
                                            onClick = { open(SettingsSubPage.SCRIPT_LIMITS) },
                                        )
                                        SettingsCellRow(
                                            title = "语言切换",
                                            onClick = { toast?.show("语言切换尚未开放") },
                                        )
                                        SettingsCellRow(
                                            title = "帮助文档",
                                            onClick = { toast?.show("帮助文档尚未开放") },
                                        )
                                    }
                                }
                                item { SectionGap() }
                                item {
                                    // 第二组：三个未开放入口，同一档纯文字（用户口径整页不要图标）。
                                    SettingsCard {
                                        SettingsCellRow(
                                            title = "反馈",
                                            onClick = { toast?.show("反馈尚未开放") },
                                        )
                                        SettingsCellRow(
                                            title = "关于",
                                            onClick = { toast?.show("关于尚未开放") },
                                        )
                                        SettingsCellRow(
                                            title = "检查更新",
                                            onClick = { toast?.show("检查更新尚未开放") },
                                        )
                                    }
                                }
                                if (state.degradedAlarmTaskIds.isNotEmpty()) {
                                    item { SectionGap() }
                                    item {
                                        // 非空不藏：精确闹钟被收回时这些任务降级成了 setWindow，
                                        // 排期**可能偏差**（§8.6 的承诺）。它是任务的账，不进权限子页。
                                        SettingsCard {
                                            CardCaptionRow(
                                                text = "以下定时任务已降级（可能偏差）：" +
                                                    state.degradedAlarmTaskIds.joinToString("、"),
                                                tone = StatusTone.ATTENTION,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        // 回顶钮跟着当前这一层走：顶页在时看顶页列表，子页在时看子页列表。
                        val listState = if (page == SettingsSubPage.NONE) topListState else pageListState
                        ScrollToTopButton(
                            visible = rememberScrollToTopVisible(listState),
                            onClick = { scope.launch { listState.animateScrollToItem(0) } },
                            // 回顶钮抬到悬浮底栏上方（胶囊占位 + 导航 inset，另加 8dp 呼吸）。
                            modifier = Modifier.align(Alignment.BottomEnd)
                                .padding(start = 16.dp, end = 16.dp, bottom = TabBarBottomClearance(extra = 8.dp)),
                        )
                    }
                }
            }

            when (page) {
                // `NONE` = 面板本身（它也占一个槽位，见 `ManagementTransition` 的 KDoc）。
                SettingsSubPage.NONE -> TopLevel()
                SettingsSubPage.PERMISSIONS -> SettingsSubPageLayer(
                    title = page.title,
                    onBack = { subPage = SettingsSubPage.NONE },
                ) {
                    PermissionsPage(state, status, pageListState, onOpenSettings)
                }
                // 脚本设置子页（批 107；§8.1 脚本资源限额）：状态与回调整份从外面进来，
                // 本屏不持有读口 —— 设置页此前只收 `CapabilityCenterState`，接第一个
                // 持久化读口时把那份 `HostSummary` 依赖挡在外面（`:ui` 只依赖 `:domain`）。
                SettingsSubPage.SCRIPT_LIMITS -> SettingsSubPageLayer(
                    title = page.title,
                    onBack = { subPage = SettingsSubPage.NONE },
                ) {
                    ScriptLimitsScreen(
                        state = limitsState,
                        onRefresh = onLimitsRefresh,
                        onDraft = onLimitsDraft,
                        onSave = onLimitsSave,
                        onReset = onLimitsReset,
                        onBack = { subPage = SettingsSubPage.NONE },
                        modifier = Modifier,
                    )
                }
            }
        },
    )
}

/**
 * 一个设置子页的外壳（批 115）：顶栏 + 本体。
 *
 * **不挂 `BackHandler`** —— 与 [ManagementTransition] 那个普通返回键同源：外壳的
 * `PredictiveBackHandler` 在**非手势**返回键（系统三键/手势导航的返回按钮）上同样会
 * 走流结束 → `onCommit`（`handleOnBackPressed`），所以子页返回键归外层那条。
 * 这里若再挂一颗普通 `OnBackPressedCallback`，它排在 `PredictiveBackHandler` 之后
 * （LIFO）就会把预测式手势**整条退化**成普通返回，跟手动画就不存在了 ——
 * 这正是「设置页还是没有预返回动画」的原因之一。
 *
 * @param onBack 顶栏 ‹（立即关，不复查）。
 */
@Composable
private fun SettingsSubPageLayer(
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(ThemeColors.surfaceMuted)) {
        ActionBar(title = title, onBack = onBack, background = ThemeColors.surfaceMuted)
        Box(Modifier.weight(1f)) {
            RefreshableBox(Modifier.fillMaxSize()) { content() }
        }
    }
}

/**
 * 权限子页本体（批 107 自 [SettingsScreen] 抽出）。
 *
 * **为什么抽**：加了脚本设置子页后 [SettingsScreen] 顶到 detekt `LongMethod` 的线，而那一段
 * 是**另一个页的完整实现** —— 顶页只做「选哪个子页」，各子页各画各的。留在里面会让
 * 「改权限行」的人得在一个混着两个子页的函数里找。
 */
@Composable
private fun PermissionsPage(
    state: CapabilityCenterState,
    status: ScreenStatus,
    pageListState: LazyListState,
    onOpenSettings: (Capability) -> Unit,
) {
    LazyColumn(
        state = pageListState,
        // 子页第一张卡离顶栏一小段灰（管理面板同款 8dp）。
        contentPadding = PaddingValues(top = 8.dp, bottom = TabBarBottomClearance()),
    ) {
        if (state.rows.isNotEmpty()) {
            item {
                SettingsCard {
                    // 全部权限同一张卡、行间无分隔线（TG `SettingCell`
                    // 的 `Factory.bindView` 本就不传分隔线）。
                    state.rows.forEach { row -> PermissionRow(row, onOpenSettings) }
                }
            }
        } else {
            // 没读到 / 读失败：卡里如实说一句，不冒充「一个权限都没有」。
            item {
                SettingsCard { CardCaptionRow(text = status.text, tone = status.tone) }
            }
        }
    }
}

/** 两张卡片之间的灰底空隙（TG `ShadowSectionCell` 缺省高 12dp —— 批 44 实测口径）。 */
@Composable
private fun SectionGap() {
    Spacer(Modifier.height(12.dp))
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
 * 软件图标 90dp 正圆 → 10dp → `NodeScript` 22sp 粗体，全部居中。
 *
 * 落位按 TG 实测三个数：图标顶 26dp、标题顶 ≈126dp（26 + 90 + 10）、整区高 188dp。
 * **读态不在这里**（批 48 挪到「权限」入口的副标题位）—— 它描述的是权限清单，
 * 不是这张脸；头部只留 TG 副标题那格的静默（本仓没有"电话号码"可填）。
 */
@Composable
private fun IdentityHeader() {
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
        }
    }
}

/**
 * 软件图标（TG 头像位的对应物）：90dp 正圆。
 *
 * 图标 = 本应用当前的 launcher 图标（`PackageManager.getApplicationIcon`）——
 * 仓库现在没有 `android:icon` 声明，取到的就是**系统默认图标先占个位置**
 * （批 47 用户口径「就先用系统默认的占位置」）；将来补上真图标即自动生效，不用改这里。
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
 * 子页里的一行权限（TG `SettingCell` 单行档：50dp、无副标题、无分隔线）。
 * 行尾 = 三态的中文说法（可点的行 LINK 蓝，与「点得动」读成一条；
 * GRANTED 灰、降级/被拒逐态警示色）。
 * 引导文案不渲染（批 47 用户口径「去除各个权限的描述」）—— 去授权靠整行可点。
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
 * 顶页 `⋮`（TG 设置页顶栏右侧三个点的对应位，`ic_ab_other`）。
 *
 * TG 那格挂的是退出登录（`LogoutActivity`），本仓无登录概念 —— 挂全局的主题切换
 * （与项目页 ⋮ 第一格同一项、同一份「标签 = 目标模式」文案口径；那一格的
 * `dismissOnClick = false` 也同一份口径：切主题不关菜单）。
 * 刷新钮批 47 已整颗删掉，不再挪进菜单；子页顶栏没有这一格（返回即全部）。
 *
 * 参数 `onSwitchTheme` 收**这颗 ⋮ 在根坐标里的中心**：外壳的圆形揭示从它长出来
 * （见 `centerInRoot` —— 菜单本体住独立 popup 窗口，量不到被点那一行的坐标）。
 */
@Composable
private fun SettingsMenu(themeSwitchLabel: String, onSwitchTheme: (Offset) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val anchor = remember { mutableStateOf(Offset.Zero) }
    Box {
        Box(Modifier.onGloballyPositioned { anchor.value = it.centerInRoot() }) {
            // 与项目页顶栏同一颗（画出来的三点 + 最粗那档参数，见那组常量的 KDoc）。
            Glyph(
                kind = GlyphKind.MORE_VERT,
                tint = ThemeColors.text,
                modifier = Modifier
                    .pressable(role = Role.Button, onClick = { open = true })
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                size = BarMoreDotsSize,
                weight = BAR_MORE_DOTS_WEIGHT,
            )
        }
        ContextMenu(
            expanded = open,
            onDismiss = { open = false },
            actions = listOf(
                MenuAction(
                    label = themeSwitchLabel,
                    dismissOnClick = false,
                    onClick = { onSwitchTheme(anchor.value) },
                ),
            ),
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
    Capability.USAGE_ACCESS -> SettingIconColors(Color(0xFFF06292), Color(0xFFDD4A80))
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
    Capability.USAGE_ACCESS -> GlyphKind.CHART
}


/**
 * 设置页的两个子页（批 107；此前只有一个「权限列表」，用 Boolean 表示）。
 *
 * **改成枚举是因为多了一个子页**：布尔只能表示「开着没有」，第二个子页就得再加一个互斥
 * 判据 —— 那正是「按下去两个同时开着」那类 bug 的形状。`NONE` 显式存在而不是靠「两个都
 * false」表示顶页：`when` 穷举到它，新增子页时漏一个分支**编译期**就红。
 */
enum class SettingsSubPage(val title: String) {
    NONE(""),
    PERMISSIONS("权限列表"),
    SCRIPT_LIMITS("脚本设置"),
}

/**
 * 五个草稿里是哪一个（批 107）。
 *
 * 五个 [String] 字段用「位置」传是错的（编译器不检查顺序），用 `copy` 又要在 `MainActivity`
 * 里抄五遍字段名 —— 那正是「加一项要改六处」的形状。枚举让 `copy` 的选择在一个地方做完。
 */
enum class ScriptLimitsDraft { HEAP, UV, INFLIGHT, BYTES, THREADS }
