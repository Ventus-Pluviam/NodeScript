package com.autoscript.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.autoscript.ui.components.ActionBar
import com.autoscript.ui.components.ActionBarAction
import com.autoscript.ui.components.ContextMenu
import com.autoscript.ui.components.CollapseHeader
import com.autoscript.ui.components.EmptyHint
import com.autoscript.ui.components.Glyph
import com.autoscript.ui.components.GlyphKind
import com.autoscript.ui.components.MenuAction
import com.autoscript.ui.components.PillButton
import com.autoscript.ui.components.pressable
import com.autoscript.ui.components.TabBarBottomClearance
import com.autoscript.ui.components.ToneText
import com.autoscript.ui.components.rememberRefreshAction
import com.autoscript.ui.state.NpmRowState
import com.autoscript.ui.state.InstallProgressState
import com.autoscript.ui.state.NpmState
import com.autoscript.domain.npm.NpmMaintenanceAction
import com.autoscript.domain.npm.NpmProjectSnapshot
import com.autoscript.domain.npm.NpmTargetScope
import com.autoscript.ui.state.StatusTone
import com.autoscript.ui.theme.ThemeColors

/**
 * 依赖管理页（管理面板 → 依赖管理；§10.9.1 依赖面板）。
 *
 * 分段（自上而下）：依赖作用域 · 尺寸 · 安装 · 维护 ·
 * **依赖列表**（白卡，卡头可折叠、默认收起）· **日志大框**（最下面）。
 *
 * 页底是**任务中心那块灰**（[ThemeColors.surfaceMuted]），页上每一段都是一张白卡
 * （[PanelCard]）—— 与管理面板（本页的入口）同族，见 [PanelCard] 的说明。
 *
 * **2026-10-17 现场改的九处（用户口径）**：
 * 1. 「删除顶上的报错。不需要它」—— 顶栏下面那两组字（读失败 / 操作报错 / 回执）
 *    整段下线，页面上半部分**不再出现红字**；
 * 2. 「弄个依赖列表，把已装依赖收进去」—— 已装依赖从"直接铺在页面里的一段"变成
 *    [DependencyListCard]（白卡 + 高度自限 + 卡内自滚）；
 * 3. 「最下面弄个显示日志的大框。灰色背景，胶囊框」—— [LogCard]，内容按当场裁定
 *    「两者都显示」= 先本页这次操作的账、后 npm 输出（组装在 [NpmState.logLines]）；
 * 4. 「背景颜色改成任务中心背景一样的颜色」—— 页底 + 顶栏改 [ThemeColors.surfaceMuted]，
 *    页上的块（原来靠[ThemeColors.fieldBackground]在白页上浮起来）整批翻成白卡；
 * 5. 「依赖列表是弄成一个白色胶囊列表，参考任务中心的列表，点下可以伸展开，默认是收起来的」
 *    —— 卡头换成 [CollapseHeader]（与任务中心同一份几何），初值收起；
 * 6. 「把顶栏那个项目切换按钮换成一个列表，列表名字叫项目选择，然后点了用下拉框选取」——
 *    一排胶囊换成 [ProjectPickerCard]（一行 + `ContextMenu` 下拉）；
 * 7. 「node_modules 取消 512MB 限制…取消上限设置吧」—— 配额整个摘掉（源头是宿主的
 *    `InstallConfig` 512MB 硬编码），那一行只剩尺寸；宿主的 `DISK_QUOTA` 黄警改锚在
 *    磁盘预检上（唯一还会拒安装的资源闸）；
 * 8. 「npm 缓存…弄成列表，右侧弄成清理的按钮」+「安装（装进当前项目的 node_modules）
 *    描述改成安装就行」+「依赖列表取消横线分隔」+「没有日志时就直接显示暂无日志」；
 * 9. 「再加一个全局依赖的功能…优先读取项目依赖」—— 第二批**未做**（契约级改动），
 *    第三批补上，见下。
 *
 * **第三批（同一天的第二次现场改动，用户口径原文）**：
 * 10. 「项目选择改成依赖作用域，然后下拉框加个全局」—— [ProjectPickerCard] 改名，下拉里
 *     多一档「全局」（`files/scripts/node_modules`，见 [NpmTargetScope]）。那一档**永远在**，
 *     于是这一行也永远画得出来 —— 一个项目都没有时它仍然回答"这一屏在管哪棵 node_modules"；
 * 11. 「去除 npm 缓存下面的字，去掉右边的清理按钮」—— [StorageCard] 那两行现在**逐字同形**
 *     （名字 + 数字）。**代价**：「回收缓存」在本页从此没有入口（宿主与 `reclaimNpmCacheOp`
 *     都还在、也还测着，只是没人调）；
 * 12. 「安装这个小字标题去掉，改成输入框左侧，叫安装依赖，右边的安装按钮改成蓝色执行按钮」
 *     —— [InstallCard] 那一行小标题整行下线，省下的一行给了「安装依赖 [输入框]（执行）」；
 * 13. 「还有依赖列表取消横线分隔」（第二批的尾巴）。
 *
 * **顶栏原先那颗「审计」已下线**（2026-10-17 用户令「去除审计这个东西，把这个日志输出到
 * 系统日志中」）：那颗按钮进的子页 + 它背后那份只追加的落盘账
 * `files/.ns/install-history.jsonl` 一并去掉，本页这些操作（安装 / 卸载 / 镜像源变更…）
 * 的结局改打**系统日志**（管理面板 → 日志管理 → 系统日志段）。
 * 于是"这些操作发生过什么"不再需要一个从本页进的第二屏 —— 页面本身不再有第五个入口。
 *
 * 这一屏刻意**不做**的几件事（做了就是撒谎）：
 * - **不画百分比进度**：`InstallEvent.Progress.percent` 全仓从无赋值，npm 进程内的
 *   reify 是黑盒 —— 画一条会动的百分比条就是编一个拿不到的数。画的是六档**阶段条**
 *   （见 [InstallProgressState]）。§10.9 第 1 条原文写的「job 数」同理拿不到。
 * - 不画"依赖树"：`list(depth)` 的 depth 参数宿主侧目前只用 0（lockfile 是平铺的
 *   闭包，层级要真跑 `npm ls --all`）。画一棵假的树就是把平铺清单伪装成树。
 * - 不画 0% 配额条：尺寸没量到时显示「未量到」，不显示"这个项目不占地方"。
 * - **不画 registry 选择器**：镜像源是**全局**配置面（§10.9 第 8 条，管理面板 →
 *   镜像源管理），在这里再放一个就是第二份 registry 判据，还会让人以为"这次用这家、
 *   下次用那家"。
 * - **不画 `hasInstallScript` 前置告警**：那要 packument 解析面，今天没有 ——
 *   界面不假装自己知道装之前该警告什么（装完之后的 `SCRIPTS_SKIPPED` 是**事后**的）。
 *
 * **变更半边已落（2026-10-09 批 87）**：原文写的是「不画安装输入行/进度条」——
 * 那条口径的前提是"没有执行体"，而执行体（`runConsoleCommand`）批 84 已经落了。
 * 本批补上输入行 + 两颗旗标 + 阶段条 + 清单行「卸载」，走的是与控制台**同一条**宿主口
 * （门禁强度不取决于用户从哪个界面按下去）。
 *
 * 维护动作（§10.9 第 5 条的动作半边，2026-10-09 批 86）落在配额条下面一行：
 * `prune` / `dedupe` / `ci` 重装 / 缓存回收。在此之前配额满了那句提示把用户指去**控制台**
 * 敲 `npm prune` —— 那条路是通的，但「有路可走」不等于「有一键」，而这个页面的全部意义
 * 就是让用户在这一屏把地方腾出来。
 *
 * 读取与刷新由外壳驱动（进入本页/手动刷新）；本屏只画，状态原样来自 [NpmState]。
 */
@Composable
fun NpmScreen(
    state: NpmState,
    onRefresh: suspend () -> Unit,
    onSelectProject: (String) -> Unit,
    onMaintenance: (NpmMaintenanceAction) -> Unit,
    onInstallDraft: (String) -> Unit,
    onToggleDev: (Boolean) -> Unit,
    onToggleOffline: (Boolean) -> Unit,
    onSubmitInstall: () -> Unit,
    onRemoveInstalled: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 「刷新」兼作进度续拉（`:ui` 没有常驻轮询循环，见 `pollInstallEvents` 的 KDoc）：
    // 宿主是入队即返回的，一次现取多半只拿到 `QUEUED`，再点一次就能看到下一步。
    // 续拉在 `MainActivity.reloadNpm()` 里与快照一起发生 —— 本屏不持有读口。
    val refresh = rememberRefreshAction { onRefresh() }
    // 页底与**任务中心/管理面板同一块灰**（2026-10-17 用户口径：「背景颜色改成任务中心
    // 背景一样的颜色」）：浅色 #F1F1F3、深色 #161E27（深色下 `surfaceMuted` 与
    // `background` 同值，所以这一改只动浅色档）。顶栏跟着传同一个色 —— 缺省是"与屏底
    // 同色"（见 `ActionBar.background` 的说明），页底换了而这里不传，就会出现
    // "白栏压灰页"那道缝（任务中心批 41 踩过同一个坑）。
    Column(modifier.fillMaxSize().background(ThemeColors.surfaceMuted)) {
        ActionBar(
            title = "依赖管理",
            background = ThemeColors.surfaceMuted,
            onBack = onBack,
            actions = {
                ActionBarAction("刷新", refresh::trigger)
            },
        )
        // 顶栏下面**不再有任何报错/回执**（2026-10-17 用户令：「删除顶上的报错。不需要它」
        // + 裁定「报错全部收进底部日志大框」）：读失败、[NpmState.opError]、[NpmState.opNotice]
        // 三样统统画在最下面那个日志大框里（[LogCard]）—— 页面上半部分只放读数与动作。
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(bottom = TabBarBottomClearance()),
        ) {
            // 项目选择（2026-10-17 用户口径：「把顶栏那个项目切换按钮换成一个列表，列表名字叫
            // 项目选择，然后点了用下拉框选取」）：一排胶囊换成一行 + 下拉菜单。
            // 一个项目都没有时**不画这张卡** —— 「项目选择 —」是句什么也没说的话。
            val projects = state.snapshot?.projects.orEmpty()
            if (projects.isNotEmpty()) {
                item {
                    ProjectPickerCard(projects, state.selectedProjectId, onSelectProject)
                }
            }
            item { StorageCard(state) }
            item {
                InstallCard(
                    state = state,
                    onDraft = onInstallDraft,
                    onToggleDev = onToggleDev,
                    onToggleOffline = onToggleOffline,
                    onSubmit = onSubmitInstall,
                )
            }
            item { MaintenanceCard(state, onMaintenance) }
            item { DependencyListCard(state, onRemoveInstalled) }
            // 日志大框在**最下面**（用户口径），上面是这一屏的读数与动作。
            item { LogCard(state) }
        }
    }
}

/**
 * 安装输入行 + 旗标 + 阶段进度条（§10.9 第 1 条的变更半边，2026-10-09 批 87）。
 *
 * **两颗旗标只有两颗**（`-D` 与「离线优先」）：§10.9 第 1 条还提到「registry 选择器」，
 * 但镜像源已经是一个**全局**配置面（§10.9 第 8 条，管理面板 → 镜像源管理），
 * 在这里再放一个选择器就是第二份 registry 判据 —— 而且它会让人以为"这次安装用这家、
 * 下次用那家"，与"全局缺省"那条口径直接冲突。
 *
 * **进度条是六档阶段条，不是百分比**（见 [InstallProgressState] 的 KDoc）：
 * job 数拿不到，画百分比就是编一个数。
 *
 * **不画 `hasInstallScript` 前置告警**：那要 packument 解析面，今天没有。
 * 装完之后的 `SCRIPTS_SKIPPED` 警告在事件流里（控制台可见），那是**事后**的，不是事前的 ——
 * 界面不假装自己知道装之前该警告什么。
 */
@Composable
private fun InstallCard(
    state: NpmState,
    onDraft: (String) -> Unit,
    onToggleDev: (Boolean) -> Unit,
    onToggleOffline: (Boolean) -> Unit,
    onSubmit: () -> Unit,
) {
    PanelCard {
        Column(Modifier.fillMaxWidth().padding(CARD_PADDING)) {
            // **这一段没有小标题了**（2026-10-17 用户口径：「安装这个小字标题去掉，改成输入框
            // 左侧，叫安装依赖」）：原来那一行小字「安装」占掉一整行高度却什么也没说，
            // 换成输入框左边那三个字，读起来就是「安装依赖 [______]（执行）」。
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 标签不用 MUTED 小字：它现在与输入框同一行，是这一行的**主语**，
                // 用小灰字会被读成输入框的副说明。
                Text("安装依赖", color = ThemeColors.text, style = MaterialTheme.typography.bodyMedium)
                InstallField(
                    value = state.installDraft,
                    onChange = onDraft,
                    enabled = state.canInstall,
                    onSubmit = onSubmit,
                    modifier = Modifier.weight(1f),
                )
                // **蓝色执行按钮**（用户口径「右边的安装按钮改成蓝色执行按钮」）：
                // `PillButton` 的选中态就是那颗动作蓝（`featuredButton`，§主题）。
                // `selected` 跟着"现在能不能按"走而不是恒定 true —— 恒定 true 的话，
                // 禁用时那颗按钮会是**蓝底灰字**（`PillButton` 的底不看 `enabled`，
                // 只有字变灰），看着像坏掉了，而不是"现在不能按"。
                val runnable = state.canInstall && state.installDraft.isNotBlank()
                PillButton(
                    text = if (state.installing) "提交中…" else "执行",
                    selected = runnable,
                    enabled = runnable,
                    onClick = onSubmit,
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PillButton(
                    text = "-D（开发依赖）",
                    selected = state.installDev,
                    enabled = state.canInstall,
                    onClick = { onToggleDev(!state.installDev) },
                )
                PillButton(
                    text = "离线优先",
                    selected = state.installOffline,
                    enabled = state.canInstall,
                    onClick = { onToggleOffline(!state.installOffline) },
                )
            }
            // 「离线优先」不是「仅离线」—— 这条旗标让 npm 先查缓存、缺了仍会联网。
            // 用一个词把两件事混成一件，用户会在断网时以为"勾了就能装上"。
            ToneText(
                "「离线优先」= 先查本地缓存，缺的仍会联网；真正断网要装，靠的是缓存里恰好有全部依赖。",
                StatusTone.MUTED,
                style = MaterialTheme.typography.bodySmall,
            )
            state.installProgress?.let { p -> InstallProgressRow(p) }
        }
    }
}

/** 输入框（与 `ConsoleScreen.CommandField` 同形；`:ui` 里两个地方各画一份，不抽公共件 —— 两者提示语与禁用条件都不同）。 */
@Composable
private fun InstallField(
    value: String,
    onChange: (String) -> Unit,
    enabled: Boolean,
    onSubmit: () -> Unit,
    modifier: Modifier,
) {
    val palette = ThemeColors
    Row(
        modifier
            .height(36.dp)
            .background(palette.fieldBackground, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(
                    text = "axios 或 axios@1.7.0",
                    color = palette.text.copy(alpha = 0.5f),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                enabled = enabled,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = if (enabled) palette.text else palette.text.copy(alpha = 0.5f),
                ),
                cursorBrush = SolidColor(palette.featuredButton),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (enabled) onSubmit() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * 阶段条：六档排开，点亮到当前那一档。
 *
 * 收尾之后（[InstallProgressState.done]）**保留一行结论**而不是整条消失：
 * 「装完了 / 失败了 + 病因」是这次操作唯一需要留在屏幕上的东西，抹掉它等于让用户
 * 自己去清单里找变化。成功时不再画阶段格子（那六格已经没有信息量）。
 */
@Composable
private fun InstallProgressRow(p: InstallProgressState) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        if (p.done) {
            ToneText(
                text = if (p.ok) (p.detail ?: "安装完成") else "失败：${p.detail ?: "（无详情）"}",
                tone = if (p.ok) StatusTone.MUTED else StatusTone.PROBLEM,
                style = MaterialTheme.typography.bodySmall,
            )
            return@Column
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val reached = InstallProgressState.ORDER.indexOf(p.phase)
            InstallProgressState.ORDER.forEachIndexed { i, _ ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(3.dp)
                        .background(
                            color = if (i <= reached) ThemeColors.featuredButton else ThemeColors.fieldBackground,
                            shape = RoundedCornerShape(2.dp),
                        ),
                )
            }
        }
        ToneText(
            text = p.label + (p.pkg?.let { "  $it" } ?: ""),
            tone = StatusTone.MUTED,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * 「依赖列表」：已装依赖收进一张**白卡**，卡头可点开可收起、**默认收起**。
 *
 * 2026-10-17 两改（同一屏、同一天、用户口径递进）：
 * 1. 「弄个依赖列表，把已装依赖收进去」—— 已装依赖从"直接铺在页面里的一段"变成一张卡；
 * 2. 「依赖列表是弄成一个白色胶囊列表，参考任务中心的列表，点下可以伸展开，默认是收起来的」
 *    —— 卡头换成任务中心那条 [CollapseHeader]（**同一份几何**，不是抄一份），
 *    初值改成收起。
 *
 * **为什么默认收起**：这一屏最常同时看的是"清单 + 下面那个日志大框"。清单默认摊开时，
 * 装到几十上百个包的机器上日志框会被顶出屏幕外；而收起态的卡头自己就写着条数
 * （「已装依赖（37）」），不看内容也知道装了多少。
 *
 * **为什么展开后仍然卡内自滚**：理由同上，只是推迟到用户主动摊开之后 —— 高度自限
 * （[DEP_LIST_MAX_HEIGHT]）且**内容少时不吃满**（`heightIn(max=)` 是上限不是固定高）：
 * 三个依赖的项目不该看到一张空荡荡的大卡。
 *
 * 行键仍是 `name@version`，但**它的唯一性现在由宿主读口保证** —— `list()` 按
 * `(name, version)` 归一出"包清单"（lock v3 的键是安装位置，同一个包同版本会在不同
 * 父级各落一份，2026-10-17 的那次闪退就是键重复）。守卫在
 * `InstallCoordinatorTest`「list 同一包同版本落两个位置 → 归一成一条」。
 */
@Composable
private fun DependencyListCard(state: NpmState, onRemove: (String) -> Unit) {
    // 初值 false = 默认收起（用户口径）。用 `rememberSaveable` 与任务中心那两个组头
    // 同一条：转屏、退到后台再回来，不该把用户刚点开的那张卡弹回去。
    var expanded by rememberSaveable { mutableStateOf(false) }
    PanelCard {
        CollapseHeader(
            // 条数只在**读到了**的时候写进卡头：没读到时的 0 不是"没有依赖"，是"还不知道"
            // （`LoadState` 那条纪律：三态各自只能说自己那一句）。
            title = if (state.load.isLoaded) "项目依赖（${state.installed.size}）" else "项目依赖",
            expanded = expanded,
            onClick = { expanded = !expanded },
        )
        if (!expanded) return@PanelCard
        when {
            // 三态各说各的：没读到不能说成"这个项目还没有依赖"。
            !state.load.isLoaded ->
                EmptyHint("还没读到依赖清单：点右上角「刷新」再试，或看下面日志里怎么说的")
            state.installed.isEmpty() ->
                EmptyHint("读到了，这个项目还没有依赖（先装点什么）")
            else -> LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = DEP_LIST_MAX_HEIGHT),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                // 行间**不画横线**（2026-10-17 用户口径「依赖列表取消横线分隔」）：
                // 与设置页/管理面板同一条 —— TG 的列表靠行距与左缩进分列，不靠分隔线
                // （见 `SettingsCard` 的 KDoc）。
                items(state.installed, key = { "${it.name}@${it.version}" }) { row ->
                    InstalledRow(row, enabled = state.canInstall, onRemove = onRemove)
                }
            }
        }
    }
}

/**
 * 日志大框（2026-10-17 用户令：「最下面弄个显示日志的大框。灰色背景，胶囊框」）。
 *
 * 内容按用户当场裁定「两者都显示」= 先本页这次操作的账（读失败 / 报错 / 回执），
 * 后 npm 的输出行 —— 组装在 [NpmState.logLines] 里（纯数据，JVM 可测），这里只画。
 *
 * **跟着滚到底**（与「控制台」页同一条）：装依赖时这个框是活的（每一跳刷新都会带回
 * 新行），不贴底的话用户看到的是几秒前那几行，得自己一直往下滑。
 * 用户往上翻看旧行时会被新行拽回底部 —— 这是"跟随"的代价，与那边一致，不做"只在贴底时
 * 跟随"的额外判据（那要读滚动位置，多一份状态就多一处能漂）。
 *
 * 空框说一句**怎么做才有**（不是"暂无日志"那种什么都不说的空壳）。
 */
@Composable
private fun LogCard(state: NpmState) {
    val lines = state.logLines
    // 贴底用的是**「控制台」页那一套**（`LazyListState` + `animateScrollToItem(lastIndex)`）：
    // 与 `ScrollState.maxValue` 不同，它在内容刚变、还没测量完的那一帧也不会滚到 0
    // —— 那个 0 的表现是"新行来了但框不跟"，而这一条正是本框存在的意义。
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex)
    }
    PanelCard {
        ToneText(
            "日志",
            StatusTone.MUTED,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = CARD_PADDING, end = CARD_PADDING, top = 10.dp),
        )
        if (lines.isEmpty()) {
            // 用户口径（2026-10-17）：「没有日志时就直接显示暂无日志。不要弄那么长」。
            // 原来那句"怎么做才有"的话挪进 [LogCard] 的 KDoc —— 界面上留一句就够。
            EmptyHint("暂无日志")
            return@PanelCard
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().heightIn(max = LOG_MAX_HEIGHT),
            contentPadding = PaddingValues(
                start = CARD_PADDING,
                end = CARD_PADDING,
                top = 6.dp,
                bottom = CARD_PADDING,
            ),
        ) {
            // 不给 key：这一列是"流水"，同一句话出现两次是常事（报错尤其），
            // 而键重复在本仓已经有过一次闪退（见 [DependencyListCard] 的 KDoc）。
            items(lines) { line ->
                ToneText(
                    text = line.text,
                    tone = line.tone,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                )
            }
        }
    }
}

/**
 * 白卡外壳（本页每一段共用一种皮）。
 *
 * **2026-10-17 第二改：这一页从"白页 + 灰块"翻成"灰页 + 白卡"。** 起因是用户口径
 * 「背景颜色改成任务中心背景一样的颜色」—— 页底从 [ThemeColors.background] 换成
 * [ThemeColors.surfaceMuted] 之后，页上那些灰块（[ThemeColors.fieldBackground] 的
 * 胶囊、输入框）就跟页底糊在一起了（浅色档 #F4F4F4 压在 #F1F1F3 上，差 3/255），
 * 等于控件消失。于是这一页的块整批翻白，两者恰好是对调的：**原来是"白页上的灰块"，
 * 现在是"灰页上的白卡"**，卡内那些灰块（安装输入框、未点亮的进度格）读法与原来一致。
 *
 * 几何取任务中心那张 `Card`：左右缩 12dp、圆角 16dp、无描边无阴影；底色也照它取
 * [ThemeColors.background]（浅色 = 白，深色 = #161E27）。**深色下卡与页同值、整页一色**
 * —— 这是任务中心本来的样子（批 67/68 把"夜间的黑"统一定成 #161E27），不是漏改；
 * 换成比屏底亮一档的 [ThemeColors.surface] 反而会坏掉卡里的两处灰块：
 * [ThemeColors.fieldBackground]（安装输入框、未点亮的进度格）**是按 `background` 配的**
 * （"白 7% 叠 [ThemeColors.background]"），卡一变成 #232326，那两处 #262E36 就跟卡糊在
 * 一起了 —— 跟这次要修的"灰块压灰页"是同一个错，只是换到了深色档。
 *
 * **卡内不统一加内边距**：依赖列表那张卡的头是 [CollapseHeader]（46dp 通栏，文字自带
 * 21dp 缩进），统一加边距会把那条通栏切开。各卡自己用 [CARD_PADDING]。
 *
 * `clip` 先于 `background`：圆角要裁住内层（列表行、分隔线），只给 `background` 传
 * shape 只裁底不裁内容。
 */
@Composable
private fun PanelCard(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = CARD_MARGIN, vertical = CARD_SPACING)
            .clip(RoundedCornerShape(CARD_CORNER))
            .background(ThemeColors.background),
    ) {
        content()
    }
}

/** 依赖列表的高度上限（约 8 行）：再多就在卡内滚，别把下面的日志大框挤出屏幕。 */
private val DEP_LIST_MAX_HEIGHT = 300.dp

/** 日志大框的高度上限（约 6~7 行）。 */
private val LOG_MAX_HEIGHT = 200.dp

/** 卡片圆角（`SettingsCard` / 任务中心 `Card` 同一档 16dp）。 */
private val CARD_CORNER = 16.dp

/** 卡片左右外边距（设置页/管理面板/任务中心三处都是 12dp）。 */
private val CARD_MARGIN = 12.dp

/** 卡片之间的竖向间距（上下各 6dp → 卡间 12dp，与任务中心两张卡之间那条灰缝同宽）。 */
private val CARD_SPACING = 6.dp

/** 卡片内边距（依赖列表那张卡除外：它的头是通栏的 [CollapseHeader]）。 */
private val CARD_PADDING = 12.dp

/** 列表行的最小高（TG 设置行是 50dp；本页卡内更密，取 44dp 保住触控高度）。 */
private val ROW_MIN_HEIGHT = 44.dp

/**
 * 尺寸读数两行（§10.9.5）：node_modules 与 npm 缓存。
 *
 * **2026-10-17 三改（同一天的用户口径，第三批）**：
 * - 「node_modules 取消 512MB 限制」—— 那一行**只剩尺寸**，配额条与 80%/100% 两档提示
 *   整个下线（配额源头是 `InstallConfig` 的 512MB 硬编码，已摘；判据换锚的事见
 *   `InstallCoordinator.enqueueHeavy` 的 KDoc）；
 * - 「npm 缓存…右侧弄成清理的按钮」—— 「回收缓存」从「维护」搬到这里（同一天早些时候）；
 * - 「去除 npm 缓存下面的字，去掉右边的清理按钮」—— **两样又都去掉了**，
 *   于是这一行现在只剩「名字 + 数字」，与上面 node_modules 那行**逐字同形**。
 *
 * **代价如实记**：「回收缓存」这个动作在依赖管理页上**没有入口了**。宿主那条路
 * （`HostSummary.reclaimNpmCache` → 协调器 `reclaimCache`）与呈现层的 `reclaimNpmCacheOp`
 * 都还在、也还测着，只是本屏不再调用 —— 想放回来（或放到别处）随时可以。
 *
 * 两行**各自独立判空**：量不到 node_modules 不该把缓存那一行也吞掉（反之亦然）——
 * 缓存是全局读数，作用域目录出问题时它照样是有效的。两行都量不到时整张卡不画。
 */
@Composable
private fun StorageCard(state: NpmState) {
    val size = state.sizeLabel
    val cacheSize = state.cacheSizeLabel
    if (size == null && cacheSize == null) return
    PanelCard {
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            size?.let {
                PanelRow(label = "node_modules", trailing = { ToneText(it, StatusTone.MUTED) })
            }
            cacheSize?.let {
                // 行标题写「npm 缓存」而不是「本项目缓存」：缓存按内容寻址、全机一份
                // （§10.2），用户会以为删掉这个作用域就能腾出这些字节。
                PanelRow(label = "npm 缓存", trailing = { ToneText(it, StatusTone.MUTED) })
            }
        }
    }
}

/**
 * 卡内一行：左标签（可带一行副说明）+ 行尾槽位，整行可按。
 *
 * 不复用设置页那条 `SettingsCellRow`：那是"设置项"的几何（18dp 缩进 / 16sp 标题 /
 * 最小 50dp / 可选图标块），本页的卡内边距是 12dp，两套缩进叠起来会缩到 30dp 去。
 * 与 `InstallField` vs `ConsoleScreen.CommandField` 同一条：形状像不等于同一个东西。
 */
@Composable
private fun PanelRow(
    label: String,
    hint: String? = null,
    trailing: @Composable () -> Unit,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) Modifier.pressable(role = Role.Button, onClick = onClick)
                else Modifier,
            )
            .heightIn(min = ROW_MIN_HEIGHT)
            .padding(horizontal = CARD_PADDING, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = ThemeColors.text, style = MaterialTheme.typography.bodyLarge)
            hint?.let {
                ToneText(it, StatusTone.MUTED, style = MaterialTheme.typography.bodySmall)
            }
        }
        trailing()
    }
}

/**
 * 「依赖作用域」一行 + 下拉（2026-10-17 用户口径，第三批改名：
 * 「项目选择改成依赖作用域，然后下拉框加个全局」）。
 *
 * 第一版是「项目选择」，换掉原先那一排胶囊（`PillButton`）—— 项目多起来时那一排会换行、
 * 把页面往下顶。改名是因为**这一行管的已经不只是项目**：全局依赖（`files/scripts/node_modules`，
 * 见 [NpmTargetScope]）也是同一类东西的一个取值 —— 它回答的是"这一屏在管哪一棵 node_modules"，
 * 而不是"哪个项目"。名字与它真正回答的问题对上了，下拉里那个「全局」才不显得是硬塞的。
 *
 * **只有一个选项时不开下拉**：菜单里只列它自己，等于暗示"还有别的可以选"。行本身照画 ——
 * 它回答的是"现在管的是哪一棵"，与可选项多少无关。
 *
 * 当前那一项在菜单里标「（当前）」：菜单里不打勾的话，下拉一开就分不出自己在哪一项上。
 * 全局那一项显示成「全局」而不是空串（[NpmTargetScope.label]）—— 菜单里出现一行空白，
 * 用户只会读成"这一项坏了"。
 */
@Composable
private fun ProjectPickerCard(
    projects: List<NpmProjectSnapshot>,
    selectedId: String?,
    onSelect: (String) -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val pickable = projects.size > 1
    Box {
        PanelCard {
            PanelRow(
                label = "依赖作用域",
                trailing = {
                    ToneText(selectedId?.let { NpmTargetScope.label(it) } ?: "—", StatusTone.MUTED)
                    if (pickable) {
                        Glyph(
                            kind = GlyphKind.CHEVRON,
                            tint = ThemeColors.textTertiary,
                            size = 14.dp,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                },
                onClick = if (pickable) ({ open = true }) else null,
            )
        }
        ContextMenu(
            expanded = open,
            onDismiss = { open = false },
            actions = projects.map { p ->
                val name = NpmTargetScope.label(p.projectId)
                MenuAction(
                    label = if (p.projectId == selectedId) "$name（当前）" else name,
                    onClick = { onSelect(p.projectId) },
                )
            },
        )
    }
}

/**
 * 维护动作一行（§10.9 第 5 条「包大小管理页」的动作半边）。
 *
 * 四颗按钮，**每颗都写清它改的是什么**（不靠用户猜 `dedupe` 是什么意思）：
 * `prune`/`dedupe`/`ci` 入队走安装会话（几十秒量级），缓存回收是瞬间的本地删除 ——
 * 后者的回执是**一次算出来的读数**而不是"入队了"，两者在界面上不共用一套措辞。
 *
 * **不做「cache clean 全清」**（用户 2026-10-09 裁定）：§10 整卷的离线能力全建在
 * npm 缓存上，全清等于把紧挨着的「按 lock 重装」变成必须联网。这颗按钮删的是
 * 「没有任何项目 lock 需要的那些」，按钮文案因此写「回收」而不是「清空」。
 *
 * 三颗维护按钮在任一动作进行中**整体禁用**（`globalSession` 全局互斥，同时只跑一个
 * 安装会话 —— 只禁被按下的那颗会让另外两颗看起来还能按，按下去是排队，用户以为卡了）。
 * 被按下的那颗显示「进行中」，所以 [NpmState.maintenance] 记的是**哪一个**。
 */
@Composable
private fun MaintenanceCard(
    state: NpmState,
    onMaintenance: (NpmMaintenanceAction) -> Unit,
) {
    val busy = state.maintenance
    PanelCard {
        Column(Modifier.fillMaxWidth().padding(CARD_PADDING)) {
            ToneText(
                // 措辞跟着作用域走（2026-10-17）：作用域可以是「全局」，那时说"当前项目"
                // 就是一句错话 —— 维护动的是当前作用域那棵 node_modules。
                "维护（都在「${state.scopeLabel}」的 node_modules 上）",
                StatusTone.MUTED,
                style = MaterialTheme.typography.bodySmall,
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 三颗维护按钮在**任一动作**进行中整体禁用（`globalSession` 全局互斥，同时只跑一个
                // 安装会话）—— 包括安装输入行那次：只禁被按下的那颗会让另外几颗看起来还能按，
                // 按下去是排队，用户以为卡了。
                val enabled = state.canInstall || busy != null
                MaintenanceButton("清理多余包", NpmMaintenanceAction.PRUNE, busy, enabled, onMaintenance)
                MaintenanceButton("依赖去重", NpmMaintenanceAction.DEDUPE, busy, enabled, onMaintenance)
                MaintenanceButton("按 lock 重装", NpmMaintenanceAction.CI, busy, enabled, onMaintenance)
            }
            // 「回收缓存」那颗按钮**不在这儿**（2026-10-17 用户口径：缓存那一行「右侧弄成清理
            // 的按钮」）—— 它搬到了 [StorageCard] 的缓存行里，与它要腾的那份读数同一行：
            // 用户按下去图的就是看那个数字变没变。
            // 这一句不是装饰：「按 lock 重装」会先验 lock 签名，签名不对会被拒，
            // 用户有权在按之前知道门禁是什么。
            ToneText(
                "「按 lock 重装」会先验 lock 签名，签名不对会被拒。",
                StatusTone.MUTED,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** 一颗维护按钮：进行中的那颗显示「…中」，其余禁用（见 [MaintenanceCard] 的 KDoc）。 */
@Composable
private fun MaintenanceButton(
    label: String,
    action: NpmMaintenanceAction,
    busy: NpmMaintenanceAction?,
    enabled: Boolean,
    onClick: (NpmMaintenanceAction) -> Unit,
) {
    PillButton(
        text = if (busy == action) "$label…" else label,
        selected = busy == action,
        enabled = enabled,
        onClick = { onClick(action) },
    )
}

/**
 * 已装包一行：名字 + 版本 + 「卸载」。
 *
 * **卸载那颗按钮走的是命令通道**（`npm uninstall <name>` → `runNpmPanelCommand`），
 * 不是另开一条 `facade.uninstall` 的宿主口 —— 门禁强度不该取决于用户从哪个界面按下去
 * （与安装输入行同一条纪律）。包名来自这份清单（宿主读 lockfile 的结果），不是用户敲的。
 */
@Composable
private fun InstalledRow(row: NpmRowState, enabled: Boolean, onRemove: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(row.name, color = ThemeColors.text, modifier = Modifier.weight(1f))
        ToneText(row.version, StatusTone.MUTED)
        PillButton(
            text = "卸载",
            selected = false,
            enabled = enabled,
            onClick = { onRemove(row.name) },
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}
