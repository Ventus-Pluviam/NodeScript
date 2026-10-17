package com.autoscript.ui.screens

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.ui.components.ActionBar
import com.autoscript.ui.components.CapsuleInputField
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
 * 分段（自上而下）：**作用域与尺寸**（一张卡）· 安装 · **依赖列表**（白卡，卡头可折叠、
 * 默认收起）· **日志大框** · **三个维护按钮**（整页最下面，[OptionsCard]）。
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
 *    一排胶囊换成一行 + `ContextMenu` 下拉（今天那一行住在 [ScopeCard] 里）；
 * 7. 「node_modules 取消 512MB 限制…取消上限设置吧」—— 配额整个摘掉（源头是宿主的
 *    `InstallConfig` 512MB 硬编码），那一行只剩尺寸；宿主的 `DISK_QUOTA` 黄警改锚在
 *    磁盘预检上（唯一还会拒安装的资源闸）；
 * 8. 「npm 缓存…弄成列表，右侧弄成清理的按钮」+「安装（装进当前项目的 node_modules）
 *    描述改成安装就行」+「依赖列表取消横线分隔」+「没有日志时就直接显示暂无日志」；
 * 9. 「再加一个全局依赖的功能…优先读取项目依赖」—— 第二批**未做**（契约级改动），
 *    第三批补上，见下。
 *
 * **第三批（同一天的第二次现场改动，用户口径原文）**：
 * 10. 「项目选择改成依赖作用域，然后下拉框加个全局」—— 那一行改名（今天它是 [ScopeCard]
 *     的第一行），下拉里
 *     多一档「全局」（`files/scripts/node_modules`，见 [NpmTargetScope]）。那一档**永远在**，
 *     于是这一行也永远画得出来 —— 一个项目都没有时它仍然回答"这一屏在管哪棵 node_modules"；
 * 11. 「去除 npm 缓存下面的字，去掉右边的清理按钮」—— 尺寸那两行现在**逐字同形**
 *     （名字 + 数字）。**代价**：「回收缓存」在本页从此没有入口（宿主与 `reclaimNpmCacheOp`
 *     都还在、也还测着，只是没人调）；
 * 12. 「安装这个小字标题去掉，改成输入框左侧，叫安装依赖，右边的安装按钮改成蓝色执行按钮」
 *     —— [InstallCard] 那一行小标题整行下线，省下的一行给了「安装依赖 [输入框]（执行）」；
 * 13. 「还有依赖列表取消横线分隔」（第二批的尾巴）。
 *
 * **第四批（同一天的第四次现场改动，用户口径原文）**：
 * 14. 「去掉开发依赖，和离线优先。默认开启离线优先」—— [InstallCard] 那两颗旗标**整行下线**，
 *     `--prefer-offline` 变成**写死**的行为（见 `NpmInstallOps.INSTALL_FLAGS`）。刻意**不留**
 *     一颗"默认点亮、可以关掉"的旗标：能关掉的默认值就是两次行为，而 §10 的离线能力全建在
 *     npm 缓存上，"先查缓存"没有任何理由关。`-D` 同理下线（它装的是**开发依赖**，
 *     而这一屏回答的是"跑起来要什么"）；
 * 15. 「清理多余包名字改成清理未声明依赖」—— 文案改口（`prune` 删的就是 lockfile 里
 *     没有声明的那些，旧名字把"多余"说成了主观判断）。~~这个名字被第 23 条改回短名~~；
 * 16. 「依赖去重执行后让它把依赖自动放进全局作用域中」—— 维护那颗「依赖去重」
 *     改走 `runDedupeAndPromoteOp`（去重当前作用域 → 把项目声明的依赖装一份到全局）；
 * 17. 「去除按lock重装」—— `ci` 那颗按钮下线（**代价**：`NpmMaintenanceAction.CI` 在本页
 *     没有入口了，宿主那半边与单测都还在 —— 想放回来随时可以）；
 * 18. 「在依赖列表里，加一个重装的图标按钮，依赖列表显示一下各个依赖占用的空间大小」
 *     —— [InstalledRow] 每行多一颗 ⟳（重装这一行那个包，与「卸载」并排）与一个尺寸读数。
 *
 * **第五批（同一天的第五次现场改动，用户口径原文）**：
 * 19. 「全局为什么没有显示node_modules」—— 这是**问句，不是改法**：全局那条作用域在
 *     `files/scripts/node_modules` 还没建时给的是 `storage = null`，界面按"没量到就不画"
 *     把那一行整个藏了，而项目那条**永远画**（尺寸 0 B）。同一个物理事实在两档作用域上
 *     画出两种界面，缺省又正要改成全局 —— 于是这一页一进来就少一行。修法在**宿主侧**：
 *     全局那条与项目同口径（`InstallCoordinator.globalSnapshot` 不再给 null），界面不动；
 * 20. 「安装依赖的输入框长度短一点（右对齐），样式抄一下首页的搜索框」—— 输入框换成
 *     [CapsuleInputField]（**与首页搜索框、新建对话框同一份**，2026-10-17 从
 *     `ProjectScreen` 搬到 `components/`），宽度收成定宽、右侧对齐：左边那根"弹簧"是标签
 *     自己的 `weight(1f)`，不是写死的 `Spacer`；
 * 21. 「依赖作用域，默认为全局，放进和 node_modules 和 npm 缓存同一个列表分组」——
 *     [ScopeCard]（作用域 + 两行尺寸一张卡）与 [NpmState.of] 的缺省回落一起改；
 * 22. 「去除维护列表，清理依赖，依赖去重，清理缓存弄成3个选项。这3个样式参考tg的…
 *     点击动效也参考tg的。选项放在最下面」—— 维护卡整张下线，换成页底的 [OptionsCard]
 *     （三个 TG 子菜单式的选项行，按下走 TG 的水波）。**「清理缓存」因此重新有了入口**
 *     （第 11 条记的那个"没有入口"的代价在本条撤销）。~~本条那版样式被下一批整条推翻~~，
 *     见第 24 条。
 *
 * **第六批（同一天的第六次现场改动，用户口径原文）**：
 * 23. 「清理未声明依赖改名啊，改成清理依赖」—— 改回短名（第 15 条那次改口撤销）；
 * 24. 「样式是图片中这样，3个按键。重新做」+「不要加多余的描述啊」—— 页底那三格从
 *     "一张白卡里的三行列表 + 卡底一句小字"翻成**三张等宽白卡**（图标在上、文字在下，
 *     [OptionTile]），那句说明整段下线。
 *
 * **第七批（2026-10-18，用户口径原文）**：
 * 25. 「你去看tg源码啊，/tmp/tg」「你对着图做干嘛」—— 第 24 条那版几何是**从截图上量的**，
 *     作废。改成照 TG `ProfileActionsView` 的源码取原值（54dp / 16dp / 24dp / 11dp 粗体 /
 *     7dp 缝），按压从 M3 水波换成 TG 的 `ButtonBounce` 缩放回弹（0.96、60ms 按下、
 *     350ms 过冲回弹）—— 参考图就是 TG「我的资料」那一行的三颗
 *     （`KEY_SET_PHOTO` / `KEY_EDIT_INFO` / `KEY_SETTINGS`）。`Modifier.pressableRipple`
 *     随之**整件删除**（它只为这三格而来）。见 [OptionTile] 的 KDoc。
 *
 * **第八批（2026-10-18，用户口径原文）**：
 * 26. 「撤掉，实现触感反馈」—— 撤掉 [OptionTile] 上那颗「进行中…」文字（TG 这三颗本来就没有
 *     loading 态：`supportsLoading` 为 false，那套扫光只挂在 gift / call / … 上），
 *     补上 TG 那条**长按触感**：`ACTION_UP` 时按住超过 250ms 且手指仍在格内 → `LONG_PRESS`。
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
 * 本批补上输入行 + 阶段条 + 清单行「卸载」，走的是与控制台**同一条**宿主口
 * （门禁强度不取决于用户从哪个界面按下去）。
 *
 * 维护动作（§10.9 第 5 条的动作半边，2026-10-09 批 86）今天落在**页底**那三个选项上：
 * `prune` / `dedupe`（+ 提升到全局）/ 缓存回收。在此之前配额满了那句提示把用户指去
 * **控制台**敲 `npm prune` —— 那条路是通的，但「有路可走」不等于「有一键」，
 * 而这个页面的全部意义就是让用户在这一屏把地方腾出来。
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
    onSubmitInstall: () -> Unit,
    onRemoveInstalled: (String) -> Unit,
    onBack: () -> Unit,
    onDedupePromote: () -> Unit,
    onReinstall: (NpmRowState) -> Unit,
    onReclaimCache: () -> Unit,
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
            // 依赖作用域与两行尺寸**同一张卡**（2026-10-17 第二批用户口径：「依赖作用域，
            // 默认为全局，放进和 node_modules 和 npm 缓存同一个列表分组」）。三行回答的是
            // 同一个问题——"这一屏在管哪一棵 node_modules、它有多大"——分成两张卡时那几个
            // 数字之间隔着一条灰缝，读起来像两件事。
            item { ScopeCard(state, onSelectProject) }
            item {
                InstallCard(
                    state = state,
                    onDraft = onInstallDraft,
                    onSubmit = onSubmitInstall,
                )
            }
            item { DependencyListCard(state, onRemoveInstalled, onReinstall) }
            // 日志大框在依赖列表下面。
            item { LogCard(state) }
            // 三个维护选项在**整页最下面**（2026-10-17 第二批用户口径「选项放在最下面」）。
            // 它们夹在日志框**之后**是刻意的：这一屏的动作都落在"当前作用域那棵树"上，
            // 而日志框正是看那棵树刚才发生了什么的地方 —— 按下去、往上看结果。
            item { OptionsCard(state, onMaintenance, onDedupePromote, onReclaimCache) }
        }
    }
}

/**
 * 安装输入行 + 阶段进度条（§10.9 第 1 条的变更半边，2026-10-09 批 87）。
 *
 * **一颗旗标都没有了**（2026-10-17 用户口径「去掉开发依赖，和离线优先。默认开启离线优先」）。
 * 原先两颗（`-D` 与「离线优先」）各自下线的理由不同：
 * - 「离线优先」**不是删掉这个能力**，而是把它变成写死的缺省（`--prefer-offline`，
 *   见 `NpmInstallOps.INSTALL_FLAGS`）。做成一颗可关的旗标等于说"还有另一种装法"，
 *   而 §10 的离线能力全建在 npm 缓存上 —— 先查缓存没有理由关；
 * - `-D` 是**语义**上的下线：它装的东西属于"开发这个包的人才要"的那一档，而这一屏
 *   回答的是"这个脚本跑起来要什么"。
 *
 * §10.9 第 1 条还提到「registry 选择器」，同样不放：镜像源是一个**全局**配置面
 * （§10.9 第 8 条，管理面板 → 镜像源管理），在这里再放一个选择器就是第二份 registry
 * 判据 —— 而且它会让人以为"这次安装用这家、下次用那家"，与"全局缺省"那条口径直接冲突。
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
            ) {
                // 标签不用 MUTED 小字：它现在与输入框同一行，是这一行的**主语**，
                // 用小灰字会被读成输入框的副说明。
                //
                // **它现在是那根"弹簧"**（2026-10-17 第二批用户口径：「安装依赖的输入框
                // 长度短一点（右对齐）」）：输入框改成定宽之后，左边多出来的空间全归标签，
                // 于是「标签贴左、框与执行钮贴右」这条版式是 Row 自己算出来的，不是靠
                // 一个写死的 `Spacer(width)` 撑 —— 后者在窄屏上会把这一行顶出屏幕
                // （标签不会让位），而 `weight(1f)` 只会把标签压窄。
                Text(
                    "安装依赖",
                    color = ThemeColors.text,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                // 版式抄首页搜索框（[CapsuleInputField]，两个屏共用同一份），只把宽度收窄。
                // **定宽而不是 `weight`**：`weight` 会把它拉回"占满剩余"的老样子，
                // 而这条口径要的正是"短一点"。
                CapsuleInputField(
                    value = state.installDraft,
                    onValueChange = onDraft,
                    placeholder = "如 axios@1.7.0",
                    modifier = Modifier.width(INSTALL_FIELD_WIDTH),
                    enabled = state.canInstall,
                    onSubmit = onSubmit,
                )
                Spacer(Modifier.width(8.dp))
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
            // 这里原来还有一行两颗旗标（`-D` / 「离线优先」）与它下面那句解释 ——
            // 2026-10-17 用户口径「去掉开发依赖，和离线优先。默认开启离线优先」整段下线，
            // 理由见本函数的 KDoc。**空了就是空了**：不拿一句"离线优先已开启"去填这个位置
            // —— 那是把"没有开关"说成"有个开关正开着"。
            state.installProgress?.let { p -> InstallProgressRow(p) }
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
 * **卡头与空态措辞跟着作用域走**（2026-10-17 第二批）：作用域是「全局」时写"项目依赖"
 * 就是一句错话 —— 全局那棵 node_modules 不属于任何项目。
 *
 * 行键仍是 `name@version`，但**它的唯一性现在由宿主读口保证** —— `list()` 按
 * `(name, version)` 归一出"包清单"（lock v3 的键是安装位置，同一个包同版本会在不同
 * 父级各落一份，2026-10-17 的那次闪退就是键重复）。守卫在
 * `InstallCoordinatorTest`「list 同一包同版本落两个位置 → 归一成一条」。
 */
@Composable
private fun DependencyListCard(
    state: NpmState,
    onRemove: (String) -> Unit,
    onReinstall: (NpmRowState) -> Unit,
) {
    // 初值 false = 默认收起（用户口径）。用 `rememberSaveable` 与任务中心那两个组头
    // 同一条：转屏、退到后台再回来，不该把用户刚点开的那张卡弹回去。
    var expanded by rememberSaveable { mutableStateOf(false) }
    PanelCard {
        CollapseHeader(
            // 条数只在**读到了**的时候写进卡头：没读到时的 0 不是"没有依赖"，是"还不知道"
            // （`LoadState` 那条纪律：三态各自只能说自己那一句）。
            //
            // 称呼跟着作用域走（2026-10-17 第二批）：缺省作用域已是「全局」（[NpmState.of]），
            // 那时写死"项目依赖"就是**天天在说错话** —— 全局那棵 node_modules 不属于任何项目。
            title = when {
                !state.load.isLoaded -> "依赖列表"
                state.scopeIsGlobal -> "全局依赖（${state.installed.size}）"
                else -> "项目依赖（${state.installed.size}）"
            },
            expanded = expanded,
            onClick = { expanded = !expanded },
        )
        if (!expanded) return@PanelCard
        when {
            // 三态各说各的：没读到不能说成"这个项目还没有依赖"。
            !state.load.isLoaded ->
                EmptyHint("还没读到依赖清单：点右上角「刷新」再试，或看下面日志里怎么说的")
            state.installed.isEmpty() ->
                EmptyHint(
                    if (state.scopeIsGlobal) "读到了，全局还没有依赖（先装点什么）"
                    else "读到了，这个项目还没有依赖（先装点什么）",
                )
            else -> LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = DEP_LIST_MAX_HEIGHT),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                // 行间**不画横线**（2026-10-17 用户口径「依赖列表取消横线分隔」）：
                // 与设置页/管理面板同一条 —— TG 的列表靠行距与左缩进分列，不靠分隔线
                // （见 `SettingsCard` 的 KDoc）。
                items(state.installed, key = { "${it.name}@${it.version}" }) { row ->
                    InstalledRow(
                        row = row,
                        enabled = state.canInstall,
                        onRemove = onRemove,
                        onReinstall = onReinstall,
                    )
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
 * 「安装依赖」输入框的宽度（2026-10-17 第二批用户口径：「长度短一点（右对齐）」）。
 *
 * 168dp 是这么算出来的：窄屏（320dp）那档减掉卡外 12+12、卡内 12、标签与按钮的固有宽度
 * 之后还剩 172dp —— 再宽一点，行里就没有"标签贴左、框贴右"这条缝了。提示语
 * 「如 axios@1.7.0」在 15sp 下约 101dp，框内去掉左右各 12dp 还余 144dp，放得下。
 */
private val INSTALL_FIELD_WIDTH = 168.dp

/**
 * **依赖作用域 + 两行尺寸**（§10.9.5 的尺寸栏 + 第三批的「依赖作用域」那一行，
 * 2026-10-17 第二批**并成一张卡**）。
 *
 * 用户口径：「依赖作用域，默认为全局，放进和 node_modules 和 npm 缓存同一个列表分组」。
 * 并卡不是排版偏好：三行回答的是同一个问题（"这一屏在管哪一棵 node_modules、它有多大"），
 * 分成两张卡时"作用域写着全局、尺寸写着别的"要跨一条灰缝才读得出来。
 *
 * 三行各自的来路与代价：
 * - **依赖作用域**（2026-10-17 第三批改名，原名「项目选择」）：一排胶囊换成一行 + 下拉菜单
 *   （[ContextMenu]）。名字与它真正回答的问题对上了 —— 全局依赖
 *   （`files/scripts/node_modules`，见 [NpmTargetScope]）也是同一类东西的一个取值。
 *   **只有一个选项时不开下拉**：菜单里只列它自己，等于暗示"还有别的可以选"。
 *   行本身照画 —— 它回答的是"现在管的是哪一棵"，与可选项多少无关。当前那一项在菜单里
 *   标「（当前）」：不打勾的话，下拉一开就分不出自己在哪一项上。全局显示成「全局」而不是
 *   空串（[NpmTargetScope.label]）—— 菜单里出现一行空白，用户只会读成"这一项坏了"。
 * - **node_modules**（2026-10-17 三改）：配额条与 80%/100% 两档提示整个下线，只剩尺寸
 *   （配额源头是 `InstallConfig` 的 512MB 硬编码，已摘；判据换锚的事见
 *   `InstallCoordinator.enqueueHeavy` 的 KDoc）。
 * - **npm 缓存**：行标题写「npm 缓存」而不是「本项目缓存」：缓存按内容寻址、全机一份
 *   （§10.2），写成"本项目的"会让用户以为删掉这个作用域就能腾出那些字节。
 *
 * **两行尺寸各自独立判空**：量不到 node_modules 不该把缓存那一行也吞掉（反之亦然）——
 * 缓存是全局读数，作用域目录出问题时它照样是有效的。
 * 「依赖作用域」那一行**永远画**（它是这个分组的话题），哪怕一个作用域都还没读到。
 */
@Composable
private fun ScopeCard(state: NpmState, onSelect: (String) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val projects = state.snapshot?.projects.orEmpty()
    val pickable = projects.size > 1
    val size = state.sizeLabel
    val cacheSize = state.cacheSizeLabel
    Box {
        PanelCard {
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                PanelRow(
                    label = "依赖作用域",
                    trailing = {
                        // 措辞来自 [NpmTargetScope.label]（空串 → 「全局」），**不在界面里另写
                        // 一份**：宿主写给事件/日志的 projectId 也是空串，两边对同一个值必须
                        // 是一种说法。还没读到（`scopeLabel` = "—"）时不冒充「全局」。
                        ToneText(state.scopeLabel, StatusTone.MUTED)
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
                size?.let {
                    PanelRow(label = "node_modules", trailing = { ToneText(it, StatusTone.MUTED) })
                }
                cacheSize?.let {
                    PanelRow(label = "npm 缓存", trailing = { ToneText(it, StatusTone.MUTED) })
                }
            }
        }
        ContextMenu(
            expanded = open,
            onDismiss = { open = false },
            actions = projects.map { p ->
                val name = NpmTargetScope.label(p.projectId)
                MenuAction(
                    label = if (p.projectId == state.selectedProjectId) "$name（当前）" else name,
                    onClick = { onSelect(p.projectId) },
                )
            },
        )
    }
}

/**
 * 卡内一行：左标签（可带一行副说明）+ 行尾槽位，整行可按。
 *
 * 不复用设置页那条 `SettingsCellRow`：那是"设置项"的几何（18dp 缩进 / 16sp 标题 /
 * 最小 50dp / 可选图标块），本页的卡内边距是 12dp，两套缩进叠起来会缩到 30dp 去 ——
 * 差别真在几何上。本页另一处"形状像而语义不同"的对照是页底那三格「动作格」[OptionTile]。
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
 * 页底三个维护动作（2026-10-17 第六批用户口径：「清理未声明依赖改名啊，改成清理依赖」+
 * 「样式是图片中这样，3个按键。重新做」+「不要加多余的描述」）。
 *
 * 三件事各自说清它动的是什么（不靠用户猜 `dedupe` 是什么意思）：
 * - 「清理依赖」= `prune`（用户 2026-10-17 口径改名，此前叫「清理未声明依赖」）；
 * - 「依赖去重」= `dedupe` **+ 提升到全局**（用户口径「依赖去重执行后让它把依赖自动放进
 *   全局作用域中」），走 `runDedupeAndPromoteOp`：两段、两个作用域，各自的成败分开说；
 * - 「清理缓存」= 按各作用域 lock 的并集回收 npm 缓存（`reclaimNpmCacheOp`）。
 *   **这一颗是"回家"的**：2026-10-17 早些时候它随「去除 npm 缓存下面的字，去掉右边的
 *   清理按钮」下线过一次（当时本页没有入口），现在按用户口径做成第三颗。
 *
 * 样式照 TG `ProfileActionsView`（「我的资料」那一行的三颗）：**三张等宽白卡**横排，
 * 图标在上、文字在下，按下是缩放回弹而不是水波 —— 取值与出处见 [OptionTile]。
 * 上一版那张「维护」卡里的三行列表连同卡底那句说明一起下线，理由就是用户那句
 * 「不要加多余的描述」。
 *
 * 两处必须一起看：
 * - 「按 lock 重装」（`ci`）**仍然没有入口**（用户 2026-10-17 口径「去除按lock重装」），
 *   宿主那半边与 [NpmMaintenanceAction.CI] 都还在、也还测着；
 * - `prune` 仍然走 [NpmMaintenanceAction] 那条**按枚举转手**的通用路
 *   （`runNpmMaintenanceOp`）；另两颗各有自己的入口，理由见它们各自的 KDoc。
 *
 * 禁用判据：两个重操作（cleaning / dedupe）跟 [NpmState.canInstall] 走，缓存那一颗跟
 * [NpmState.canReclaimCache] 走 —— 两条都是"安装会话全局互斥"这一个事实的两种说法：
 * 安装、维护、回收三者互相排斥。只禁被按下的那一颗会让另两颗看起来还能按，按下去是排队，
 * 用户以为卡了。**跑起来没有「进行中」文字**（2026-10-18 用户口径「撤掉」）—— 三颗一起
 * 变灰就是状态本身；TG 这三颗也没有 loading 态（见 [OptionTile]）。
 *
 * 「依赖去重」按下时记的仍是 [NpmMaintenanceAction.DEDUPE]：提升是它的**后半段**，
 * 不是另一件并列的事（见 `runDedupeAndPromoteOp`）。
 */
@Composable
private fun OptionsCard(
    state: NpmState,
    onMaintenance: (NpmMaintenanceAction) -> Unit,
    onDedupePromote: () -> Unit,
    onReclaimCache: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = CARD_MARGIN, vertical = CARD_SPACING),
        horizontalArrangement = Arrangement.spacedBy(OPTION_TILE_GAP),
    ) {
        OptionTile(
            label = "清理依赖",
            glyph = GlyphKind.CLEAR,
            enabled = state.canInstall,
            onClick = { onMaintenance(NpmMaintenanceAction.PRUNE) },
            modifier = Modifier.weight(1f),
        )
        OptionTile(
            label = "依赖去重",
            glyph = GlyphKind.LAYERS,
            enabled = state.canInstall,
            onClick = onDedupePromote,
            modifier = Modifier.weight(1f),
        )
        OptionTile(
            label = "清理缓存",
            glyph = GlyphKind.CACHE,
            enabled = state.canReclaimCache,
            onClick = onReclaimCache,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 一颗底部动作按钮 —— **照 TG `ProfileActionsView` 的源码做**（2026-10-18 用户口径
 * 「你去看tg源码啊，/tmp/tg 你对着图做干嘛」）。参考图就是 TG 的「我的资料」那一行
 * （`MODE_MY_PROFILE` 的三颗：`KEY_SET_PHOTO` / `KEY_EDIT_INFO` / `KEY_SETTINGS`），
 * 所以取值一律以 `TMessagesProj/src/main/java/org/telegram/ui/Components/ProfileActionsView.java`
 * 为准，不再从截图上量。
 *
 * **几何（源码原值）**：`ProfileActionsView(context, dp(74))` 传进来 74dp，构造函数里
 * `targetHeight = 74 - ypadding(12) - top(8)` = **54dp** 就是按钮矩形的高；圆角
 * `getRoundRadius()` = `dp(16)`；图标 `updateBounds()` 里 `drawableSize = dp(24)`；
 * 文字 `new Text(cs, 11, AndroidUtilities.bold())` —— 11dp、粗体。
 *
 * **按压是缩放回弹，不是水波**（`ButtonBounce`）：按下 `setPressed(true)` → `pressedT` 走
 * **60ms**、`CubicBezierInterpolator.DEFAULT`（`cubic-bezier(0.25, 0.1, 0.25, 1)`）到 1；
 * 松手 → **350ms**、`OvershootInterpolator(5f)` 回到 0。缩放量
 * `getScale(0.04f)` = `0.96 + 0.04 * (1 - pressedT)`，即按下时整格缩到 **0.96**、松手带一点过冲。
 * 之前那版走的 `ripple`（水波）**已整件删掉**：TG 这一行本来就不是水波。
 *
 * **底色取 [ThemeColors.surface]**：TG 那里是 `btnColor`，跟着资料页头部的主题色算
 * （`ProfileActivity.setBackgroundColorId`：有 peer 色就取 `patternColor` 45%、否则按
 * actionBar 明度分三档），本应用没有那层头部，照搬公式只会算出一个没有由来的颜色；
 * 本页的读法是"灰页上的白卡"，参考图里也是白。深色下不能取 `background`
 * （本页其他卡那个）：它与页底 `surfaceMuted` 同值 #161E27，这三格会整块消失。
 *
 * `clip` 先于 `background`（同 [PanelCard]）：缩放与圆角要先裁住底色。
 *
 * **长按触感**（`onTouchEvent` 的 `ACTION_UP` 分支）：按住超过 **250ms** 且手指仍在格内，
 * 松手时给一次 `HapticFeedbackConstants.LONG_PRESS`。注意它**不是**在按住的过程中震，
 * 而是在**松手那一刻**按"按了多久"决定震不震 —— 本件照这个口径：从 `PressInteraction.Press`
 * 记下按下时刻，`Release` 时比 250ms，`Cancel`（滑走/取消）不震。
 *
 * **没有「进行中」态**：TG 这三颗 `supportsLoading` 是 false（那套横向扫光的 `LoadingDrawable`
 * 只挂在 gift / call / video / join / report / leave / voice / stream / stop 上）。跑起来时
 * 三颗一起变灰就是状态（[NpmState.canInstall] / [NpmState.canReclaimCache] 在会话期间为 false）。
 */
@Composable
private fun OptionTile(
    label: String,
    glyph: GlyphKind,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = ThemeColors
    val haptics = LocalHapticFeedback.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    LaunchedEffect(source, enabled) {
        if (!enabled) return@LaunchedEffect
        var downAt = 0L
        source.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> downAt = System.currentTimeMillis()
                is PressInteraction.Release -> {
                    val held = System.currentTimeMillis() - downAt
                    if (downAt != 0L && held > OPTION_TILE_HAPTIC_DELAY_MS) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                    downAt = 0L
                }
                is PressInteraction.Cancel -> downAt = 0L
            }
        }
    }
    val pressedT by animateFloatAsState(
        targetValue = if (pressed && enabled) 1f else 0f,
        animationSpec = if (pressed && enabled) {
            // 按下：60ms，CubicBezierInterpolator.DEFAULT。
            tween(durationMillis = 60, easing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f))
        } else {
            // 松手：350ms 回弹带过冲（TG 用 OvershootInterpolator(5f)，Compose 这边对应
            // 一条高回弹的弹簧）。
            spring(
                dampingRatio = Spring.DampingRatioHighBouncy,
                stiffness = Spring.StiffnessMediumLow,
            )
        },
        label = "optionTilePress",
    )
    val scale = OPTION_TILE_PRESS_SCALE + (1f - OPTION_TILE_PRESS_SCALE) * (1f - pressedT)
    Column(
        modifier
            .height(OPTION_TILE_HEIGHT)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(OPTION_TILE_CORNER))
            .background(palette.surface)
            .clickable(
                interactionSource = source,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Glyph(
            kind = glyph,
            tint = if (enabled) palette.barIcon else palette.textTertiary,
            size = OPTION_TILE_ICON,
        )
        Spacer(Modifier.height(OPTION_TILE_ICON_GAP))
        Text(
            text = label,
            color = if (enabled) palette.text else palette.textTertiary,
            fontSize = OPTION_TILE_TEXT_SIZE,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

/**
 * 动作格的几何 —— **全部是 TG `ProfileActionsView` 的源码原值**（2026-10-18；此前那版是从
 * 用户给的截图上量的，用户口径「你去看tg源码啊 /tmp/tg 你对着图做干嘛」，量出来的值已作废）。
 *
 * | 常量 | 值 | 出处 |
 * |---|---|---|
 * | [OPTION_TILE_HEIGHT] | 54dp | `ProfileActionsView(context, dp(74))` − `ypadding(12)` − `top(8)` |
 * | [OPTION_TILE_CORNER] | 16dp | `getRoundRadius()` = `dp(16)` |
 * | [OPTION_TILE_ICON] | 24dp | `updateBounds()` 里的 `drawableSize = dp(24)` |
 * | [OPTION_TILE_TEXT_SIZE] | 11sp | `new Text(cs, 11, AndroidUtilities.bold())` |
 * | [OPTION_TILE_GAP] | 7dp | `betweenPadding = xpadding / 2f`，`xpadding = dpf2(14)` |
 * | [OPTION_TILE_PRESS_SCALE] | 0.96 | `getScale(0.04f)` 在按下末态的值 |
 *
 * 两处**刻意与源码不同**，都是为了跟本页对齐：
 * - 左右外边距用 [CARD_MARGIN]（12dp）而不是 TG 的 `xpadding` 14dp —— 这一行上下的卡都从
 *   12dp 起手，差 2dp 会看成没对齐；
 * - 竖向间距用 [CARD_SPACING]（6dp）而不是 TG 的 `top 8dp` + `ypadding 12dp` —— 本页的卡间缝
 *   就是这条，同一页不该有两种缝。
 *
 * 文字 11sp 与源码的 `dp(11)` 在默认字体缩放下逐像素相同；用 sp 是为了跟随系统字体大小
 * （TG 的 `Text` 类一律用 dp，不跟随）。
 */
private val OPTION_TILE_HEIGHT = 54.dp

/** 动作格圆角（`ProfileActionsView.getRoundRadius()` = `dp(16)`）。 */
private val OPTION_TILE_CORNER = 16.dp

/** 动作格之间的横缝（`betweenPadding = xpadding / 2f` = 7dp）。 */
private val OPTION_TILE_GAP = 7.dp

/** 动作格图标边长（`updateBounds()` 的 `drawableSize = dp(24)`）。 */
private val OPTION_TILE_ICON = 24.dp

/** 图标与文字之间那道缝（`textPadding = dpf2(4)`）。 */
private val OPTION_TILE_ICON_GAP = 4.dp

/** 按钮文字字号（`new Text(cs, 11, bold)`）。 */
private val OPTION_TILE_TEXT_SIZE = 11.sp

/** 按下末态的缩放（`ButtonBounce.getScale(0.04f)` → 0.96）。 */
private const val OPTION_TILE_PRESS_SCALE = 0.96f

/** 长按触感的门槛（`ProfileActionsView.onTouchEvent`：`System.currentTimeMillis() - downTime > 250`）。 */
private const val OPTION_TILE_HAPTIC_DELAY_MS = 250L

/**
 * 已装包一行：名字 + 版本 + 尺寸 + 「重装 ⟳」+ 「卸载」。
 *
 * **两个动作都走命令通道**（`npm uninstall <name>` / `npm install <name>@<version> --no-save`
 * → `runNpmPanelCommand`），不是另开 `facade.uninstall` / `facade.reinstall` 的宿主口 ——
 * 门禁强度不该取决于用户从哪个界面按下去（与安装输入行同一条纪律）。包名与版本都来自
 * 这份清单（宿主读 lockfile 的结果），不是用户敲的。
 *
 * **尺寸（2026-10-17 用户口径「依赖列表显示一下各个依赖占用的空间大小」）**：宿主
 * 走一遍 `node_modules` 量出来（`NodeModulesSizing.kt` 的 `packageSizesOf`），lockfile 里没有字节数
 * —— 在此之前 `PkgNode.sizeBytes` 恒为 0，这一格就永远画不出来。
 * 量不到（包在 lock 里、盘上却没有）→ **不画**（[NpmRowState.sizeLabel] 为 null），
 * 不写 "0 B"：与这一页另两处尺寸同一条纪律，0 B 是在说"这个包不占地方"。
 *
 * 尺寸用[StatusTone.MUTED]小字、贴着版本号后面：它是**读数**不是动作，
 * 与右边那两颗按钮拉开字形差别，否则一行里四个东西分不出哪些能按。
 */
@Composable
private fun InstalledRow(
    row: NpmRowState,
    enabled: Boolean,
    onRemove: (String) -> Unit,
    onReinstall: (NpmRowState) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(row.name, color = ThemeColors.text, modifier = Modifier.weight(1f))
        ToneText(row.version, StatusTone.MUTED)
        row.sizeLabel?.let {
            ToneText(
                it,
                StatusTone.MUTED,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        // 重装排在「卸载」**前面**：「卸载」是不可逆的那一颗（这一屏没有确认框），
        // 主动作排在外侧等于把手滑的落点放在破坏性按键上（与项目页「▶ 在 ⋮ 左边」同一条读法）。
        ReinstallButton(name = row.name, enabled = enabled, onClick = { onReinstall(row) })
        PillButton(
            text = "卸载",
            selected = false,
            enabled = enabled,
            onClick = { onRemove(row.name) },
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/**
 * 行尾那颗 ⟳（重装这一行那个包）—— **图标按钮**，与[InstalledRow]的「卸载」并排
 * （用户口径 2026-10-17：「在依赖列表里，加一个重装的图标按钮」，追问后定为「每一行一个
 * （和「卸载」并排）」）。
 *
 * **为什么是图标而不是第二颗胶囊**：这一行已经有两个读数（版本、尺寸），再放一颗带字的
 * 胶囊会把包名挤到一格；重装的语义（同一件事再来一遍）正好是 ⟳ 这个字形在说的事。
 *
 * 触控 36dp 是有算术的：行内上下各 4dp 内边距，36 + 4 + 4 = 44 = [ROW_MIN_HEIGHT]
 * —— 再大这颗按钮会把**每一行**都撑高，一张 8 行的列表就多出半行的高度。
 *
 * 位置绑定（[contentDescription]）**带上包名**：一颗只说"重装"的按钮在无障碍朗读里
 * 与另一行的同名按钮分不开（与项目页行尾那颗 ⋮ 的「更多（<文件名>）」同一条）。
 */
@Composable
private fun ReinstallButton(name: String, enabled: Boolean, onClick: () -> Unit) {
    val palette = ThemeColors
    Box(
        Modifier
            .size(REINSTALL_TOUCH)
            .pressable(
                enabled = enabled,
                role = Role.Button,
                overlay = palette.menuSelector,
                shape = CircleShape,
                onClick = onClick,
            )
            .semantics { contentDescription = "重装（$name）" },
        contentAlignment = Alignment.Center,
    ) {
        // 色取次级灰而不是黑：它是行尾的第二颗按钮，不是这一行的主动作
        // （主动作是「卸载」，那颗是带字的胶囊，本身就重）。
        Glyph(kind = GlyphKind.REFRESH, tint = palette.textTertiary, size = REINSTALL_ICON)
    }
}

/** 重装那颗 ⟳ 的触控目标（见 [ReinstallButton] 里那道算术）。 */
private val REINSTALL_TOUCH = 36.dp

/** 重装那颗 ⟳ 的图标边长。 */
private val REINSTALL_ICON = 18.dp
