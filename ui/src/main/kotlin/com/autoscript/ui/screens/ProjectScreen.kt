package com.autoscript.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.domain.scripts.ScriptRunnability
import com.autoscript.ui.ManagementTransition
import com.autoscript.ui.components.ActionBar
import com.autoscript.ui.components.ActionBarAction
import com.autoscript.ui.components.ActionBottomSheet
import com.autoscript.ui.components.ActionBottomSheetItem
import com.autoscript.ui.components.BAR_MORE_DOTS_WEIGHT
import com.autoscript.ui.components.BarMoreDotsSize
import com.autoscript.ui.components.ContextMenu
import com.autoscript.ui.components.centerInRoot
import com.autoscript.ui.components.EaseOutQuint
import com.autoscript.ui.components.OvershootEasing
import com.autoscript.ui.components.rememberPressIndication
import com.autoscript.ui.theme.isDarkTheme
import com.autoscript.ui.components.MenuAction
import com.autoscript.ui.components.MenuGap
import com.autoscript.ui.components.Glyph
import com.autoscript.ui.components.GlyphKind
import com.autoscript.ui.components.FileTypeAvatar
import com.autoscript.ui.components.LocalToast
import com.autoscript.ui.components.LocalTabBarHidden
import com.autoscript.ui.components.ROW_MORE_DOTS_WEIGHT
import com.autoscript.ui.components.RefreshableBox
import com.autoscript.ui.components.RowMoreDotsSize
import com.autoscript.ui.components.ScrollToTopButton
import com.autoscript.ui.components.rememberScrollToTopVisible
import com.autoscript.ui.components.TabBarBottomClearance
import com.autoscript.ui.components.ToneText
import com.autoscript.ui.components.pressable
import com.autoscript.ui.components.pressableLongPress
import com.autoscript.ui.components.rememberCopyAction
import com.autoscript.ui.components.rememberLongPressFeedback
import com.autoscript.ui.state.FileSort
import com.autoscript.ui.state.LoadState
import com.autoscript.ui.state.opToastMessage
import com.autoscript.ui.state.ProjectState
import com.autoscript.ui.state.ScriptFileRowUi
import com.autoscript.ui.state.StatusTone
import com.autoscript.ui.theme.ThemeColors
import kotlinx.coroutines.launch

/**
 * 项目页（TG 主页签 `DialogsActivity` 的版式逐字复刻，内容从会话换成脚本文件）：
 *
 * - **顶栏**：品牌标题「NodeScript」—— 20sp 粗体 + TG 主页那格蓝（`createTitleTextView`
 *   的 bold 20dp + `key_telegram_color_dialogsLogo` = #168BDB 的口径；全仓唯一一个
 *   覆盖 [ActionBar] 缺省标题样式的屏），右上角 `⋮`（本屏菜单，缺省只有"日间/夜间模式"）；
 * - **搜索栏**：灰底圆框（`FragmentSearchField` 的 `DialogsActivity` 档：白底上
 *   `key_windowBackgroundWhiteBlackText @5%` 的灰、**无投影**、圆角 20dp 落在 40dp
 *   高上 = 两端全圆；批 43 按 TG 源码逐值对齐 —— 高 40dp、距屏边 12dp（= 屏宽 −24dp）、
 *   槽位 52dp、放大镜 [GlyphKind.SEARCH_FIELD]）+ 「搜索文件」提示词（TG 的 hint 位）；
 * - **文件列表 = 会话列表**：一行 = 52dp 圆形头像（TG `DialogCell` 的 avatar 52dp）
 *   + 文件名 16sp 粗体（`nameTextView`）+ 次行"大小 · 时刻"13sp（`dateTextView`），
 *   头像即文件类型图标（扩展名取色取字，`getThumbForNameOrMime` 的哈希取色 + `extTextView`
 *   的白字缩写）；
 * - **搜索**：输入词过滤列表（[ScriptFileRowUi.matches]）—— TG 搜索会话与消息的
 *   同一交互位，只是过滤范围是文件名/路径。
 *
 * 诚实边界（与其他屏同一条纪律）：没读到显示「尚未读取」，失败带原文；
 * 读到了且为空才说「还没有脚本文件」—— 那是真的没有（`files/scripts/` 为空）。
 */
// 参数数在 2026-10-13 加了 [onRunFile] 之后跨过 detekt 的阈值（10），原来的 9 个离阈值不远；
// 全部是屏自己的读口/回调（与 SettingsScreen 那份 @Suppress 同型），不是值得继续拆分了才压下来。
@Suppress("LongParameterList")
@Composable
fun ProjectScreen(
    state: ProjectState,
    /**
     * 主题两态切换（⋮ 第一格，TG 日/夜同款 —— 目标模式写菜单项上）。
     *
     * 参数是**这颗 ⋮ 在根坐标里的中心**：外壳的圆形揭示从它长出来（见 `centerInRoot`）。
     */
    onSwitchTheme: (Offset) -> Unit,
    /**
     * 那一格的**文案** = 点它切到的那一档（TG `DialogsActivity` 的日夜项：
     * 当前深色写 "Day Mode"、当前浅色写 "Night Mode"）。
     *
     * 由外壳下发而不是本屏自己算：本屏拿不到 `ThemeMode`（那是 `:ui` 外壳的状态），
     * 而"当下是明是暗"这个事实在 `MainActivity` 里已经有了（`themeMode.isDark()`）。
     */
    themeSwitchLabel: String,
    /** 新建文件/文件夹（FAB 展开的两个子项；落盘在宿主，本屏只收结论）。 */
    onCreate: (projectId: String, name: String, isFolder: Boolean) -> Unit,
    /** 读一个脚本文件的文本（**点文件**进编辑面时调；失败原文抛，本屏如实显示）。 */
    onReadFile: suspend (projectId: String, relPath: String) -> String,
    /** 覆盖写一个脚本文件的文本（编辑器「保存」；**正常返回即已落盘**）。 */
    onSaveFile: suspend (projectId: String, relPath: String, content: String) -> Unit,
    /**
     * 跑一个脚本文件（文件行尾那颗 ▶；2026-10-13 用户口径）。
     *
     * **挂起**：宿主要挂到本次执行结算（与任务中心的「立即执行」同一条口径），
     * 调用方据此把那一行的 ▶ 换成"执行中"并禁用。
     */
    onRunFile: suspend (projectId: String, relPath: String) -> Unit,
    /** 排序档/逆向变更（写入 state —— 重读不重置呈现偏好）。 */
    onSortChange: (FileSort, Boolean) -> Unit,
    /**
     * 本页是否是 pager 当前停稳的那一页。pager 预组合邻页（`beyondViewportPageCount = 1`），
     * 本屏的返回键拦截若不看它，用户在任务页按返回会被邻页的项目屏吞掉（去退一层目录）。
     */
    active: Boolean = true,
    modifier: Modifier = Modifier,
) {
    // 搜索词/当前目录/多选集合都是本屏私有的现值：过滤、下钻、选中都是**呈现**
    // （TG 文件页"仅应用于此文件夹"），不是读取 —— 不进读口。
    var query by rememberSaveable { mutableStateOf("") }
    // 当前目录 = relPath 的**全路径前缀**（含项目名，如 "demo/"、"demo/lib/"）；
    // null = 全库根。目录行的 relPath 本身就是合格的前缀，进层就是直接赋值。
    var currentFolder by rememberSaveable { mutableStateOf<String?>(null) }
    // `Set` 不在 Bundle 认得的类型里（`rememberSaveable` 的 autoSaver 存不下它，
    // 配置变更/进程重建时会抛）—— 存成 List、回来再收成 Set。
    var selected by rememberSaveable(
        saver = listSaver(
            save = { state -> state.value.toList() },
            restore = { list -> mutableStateOf(list.toSet()) },
        ),
    ) { mutableStateOf(emptySet<String>()) }
    var sheetTarget by remember { mutableStateOf<ScriptFileRowUi?>(null) }
    val copy = rememberCopyAction()
    // 操作回执（新建的成败）走外壳浮层（批 44）：此前是列表里的一行，弹一条就把
    // 首行往下推一次。文案一个字没动（判读见 [opToastMessage]，纯层可测）；
    // 「刷新/换排序把上一次的回执清掉」仍由 MainActivity 的写路径负责。
    val toast = LocalToast.current
    val opToast = opToastMessage(state.opError, state.opNotice, inFlight = false)
    // 键是**解析出的那一句**：同一句连着出现不重弹（键没变），换了一句才弹。
    LaunchedEffect(opToast) { opToast?.let { toast?.show(it) } }
    val longPressFeedback = rememberLongPressFeedback()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // 悬浮底栏的隐藏开关（外壳供，[LocalTabBarHidden]）。编辑面占整屏时要把它整条收掉 ——
    // 写口在这一层而不是编辑面自己那一层，见 [openEditor] / [requestEditorClose] 的注释。
    val tabBarHidden = LocalTabBarHidden.current

    // 正在编辑的文件（null = 不在编辑）。编辑面**盖在整页之上**而不是「换掉整页」——
    // TG 打开文档也是走 ActionBarLayout 那一层切换（`presentFragment`），底下那份列表
    // 在动画期间仍在树上。进出各走 150ms 那套 + 跟手预测式返回，见文件尾那个
    // [ManagementTransition]。
    var editing by remember { mutableStateOf<ScriptFileRowUi?>(null) }
    // 动画进行中（TG `animationInProgress`）：动画期间点列表里的另一行，在 TG 里是
    // 什么都不发生。不吞的话 `editing` 两次变，切出的收尾会被取消。
    var editorAnimating by remember { mutableStateOf(false) }
    // 正在跑的脚本（键 = `ProjectState.keyOf` 那一份）。挂起期间那一行的 ▶ 换成转圈，
    // 其余行照旧可点 —— 引擎池容量是 1，但"这一颗在跑"与"别的文件能不能跑"是两件事
    // （排队那条路由宿主的分级表管，界面不替它拦）。
    var runningKey by remember { mutableStateOf<String?>(null) }

    /** 跑一个文件（点行尾 ▶）。挂起到本次执行结算，成败归宿主（回执 = 悬浮窗）。 */
    fun runFile(file: ScriptFileRowUi) {
        val key = ProjectState.keyOf(file)
        if (runningKey != null) return
        runningKey = key
        scope.launch {
            try {
                // 2026-10-13 起成功回执 = 悬浮窗（外壳在 onRunFile 里先开窗再跑）——
                // 这里不再弹「已触发…」toast（措辞不替宿主宣布结果那条纪律仍在：
                // 成败在控制台/任务日志/日志管理，窗里只看输出）。失败 catch 保留。
                onRunFile(file.projectId, file.relPath)
            } catch (t: kotlinx.coroutines.CancellationException) {
                throw t
            } catch (t: com.autoscript.domain.core.AutojsException) {
                toast?.show("执行失败：${t.message ?: t.javaClass.simpleName}")
            } catch (t: IllegalArgumentException) {
                toast?.show("执行失败：${t.message ?: t.javaClass.simpleName}")
            } catch (t: IllegalStateException) {
                // 宿主「■ 链」上的收口/判定异常（`error("壳未装配…")`、`check(!sinking)…`、
                // `IllegalStateException("任务不存在…")`）不收进「执行失败」toast 就没人接：
                // 悬浮窗失败路径不抛这些，抛的只可能是宿主没有把「跑不起来」折成
                // `AutojsException` 的缺口 —— 亮原文，但**不上抛**（闪退换不来任何信息，
                // 2026-10-14「点执行就闪退」那次就是这类没捂住的结果）。
                toast?.show("执行失败：${t.message ?: t.javaClass.simpleName}")
            } finally {
                runningKey = null
            }
        }
    }

    /** 开一个文件（动画期间吞掉，与面板入口同一口径）。 */
    fun openEditor(file: ScriptFileRowUi) {
        // 动画期间吞掉（与面板入口同一口径）。**底栏 flag 一起吞**：切出动画里 `editing`
        // 已经归 null、flag 已经复位，此时若只写 flag，底栏会在没有编辑面的时候又收起来。
        if (editorAnimating) return
        editing = file
    }

    // 编辑面递出来的「能不能退」判定（含未保存拦截）。**侧缘手势的提交路径走
    // `ManagementTransition.onBackRequest`，不经过编辑器自己那颗 `BackHandler`** ——
    // 不接这道门的话，滑手回来会静默丢掉未保存的改动。
    var editorCloseGuard by remember { mutableStateOf<(() -> Unit)?>(null) }
    /** 关掉编辑面（有未保存改动时先问一句，与返回键、‹ 同一个判定）。 */
    fun requestEditorClose() {
        (editorCloseGuard ?: { editing = null }).invoke()
        // 底栏的复位不写在这里：`fullScreen`（editing 非空 || 目录层）在 `editing` 归
        // null 的同一拍重算，`LaunchedEffect(fullScreen)` 据此放回底栏 —— 且从目录里
        // 打开的编辑面退出时不会错误放回（那会儿人还在目录里）。
    }

    // 可见集 = 一层（[ProjectState.childrenOf]）→ 排序。**搜索时层裁剪让位**：
    // TG 文件选择器的搜索是**另一份数据集**（`searchAdapter` 搜整棵树、含"最近"分区，
    // 展开搜索栏时列表整个换掉），不是"在当前目录里筛一下" —— 用户搜文件名时想找的是
    // "那个文件在哪"，锁在当前层就等于答非所问。搜索清空即回到当前层。
    val searching = query.isNotBlank()
    // **记忆化**：本屏每次重组都会走到这里，而 `poolFor + filter + sorted` 是"整层重排一遍"
    // —— 多选里每勾一行、浮层每弹一条回执都会触发重组，200 个文件就是 200 次白排序。
    // 键取真正的输入（池 + 词 + 排序档），敲搜索词本来就该重算，勾选/回执不该。
    val visible = remember(state.files, currentFolder, searching, query, state.sort, state.reversed) {
        ScriptFileRowUi.sorted(
            files = ProjectState.poolFor(state.files, currentFolder, searching).filter { it.matches(query) },
            sort = state.sort,
            reversed = state.reversed,
        )
    }
    val selectionMode = selected.isNotEmpty()
    val allSelected = ProjectState.allSelected(selected, visible)

    /**
     * 进一层目录（点目录行、长按面板的「进入文件夹」都走这里）。
     *
     * **底栏整条收掉**（用户口径 2026-10-13：「点进列表里面时，让底栏消失，退出来时才重新出现」）：
     * 进了层就是"在里面"，那四个页签既是别的页面、又压着列表最后几行 —— 与编辑面占整屏
     * 是同一条理由。写口收在这两个函数里（而不是每个 `currentFolder = …` 各写一遍）：
     * 三处入口漏一处就是"有时候收、有时候不收"。
     */
    fun enterFolder(relPath: String) {
        currentFolder = relPath
        selected = emptySet()
        scope.launch { listState.scrollToItem(0) }
    }

    /**
     * 退回上一层（返回键与顶栏 ‹ 共用一条路径）。
     */
    fun goUp() {
        currentFolder = ProjectState.parentFolder(currentFolder)
        selected = emptySet()
        scope.launch { listState.scrollToItem(0) }
    }

    // 底栏收放的**唯一**判据：**只有编辑面**（editing 非空）算占整屏，才收掉。
    //
    // **进目录不算**（用户口径 2026-10-13「点进文件列表不需要隐藏啊，进入编辑页面才隐藏」）：
    // 进目录只是列表换了一层内容，页面本身还是那个项目页 —— 底栏在它下面才是 TG 的读法。
    // 写成一条派生判据而不是各处散写 flag：从目录里打开编辑面再退出这种嵌套，
    // 散写会把旗标复位成 false 而编辑面还开着。
    val fullScreen = editing != null
    // 与"状态翻面"同一帧生效（`LaunchedEffect` 在重组应用后立即跑 —— 编辑面开始关的
    // 那一下底栏就放回来，不等 380ms 飞出动画走完；进编辑面的那一拍也立即收掉）。
    //
    // **`active` 门不能省**：pager 预组合邻页，邻页（它自己的 `fullScreen == false`）
    // 组合时若也写这条 flag，会把正在前台那个整屏面的 true 覆盖回 false ——
    // 底栏在人家全屏的时候冒出来。只有"我这一页停稳了"才许碰开关。
    LaunchedEffect(active, fullScreen) { if (active) tabBarHidden.value = fullScreen }

    // 返回键的退让次序照 TG：先收面板 → 再退选择模式 → 再清搜索 → 最后才退一层目录。
    // 每一档都只做一件事，且「还有下一档」才拦下返回 —— 于是根层没搜索时返回键
    // 照常退出应用（不吞），而每按一次都恰好撤回用户看到的上一步。
    BackHandler(enabled = active && (sheetTarget != null || selectionMode || query.isNotBlank() || currentFolder != null)) {
        when {
            sheetTarget != null -> sheetTarget = null
            selectionMode -> selected = emptySet()
            query.isNotBlank() -> query = ""
            else -> goUp()
        }
    }

    // 新建落在**当前所在项目**（文件树按项目分目录，全库根不可写）—— 有文件取第一行的
    // 项目，没文件回缺省目录名（首装用户先建项目文件夹）。
    val targetProject = currentFolder?.substringBefore('/')
        ?: state.files.firstOrNull()?.projectId
        ?: "demo"

    // 创建对话框是本屏私有的现值（state.creating 记的是"操作面开着"这一事实，
    // 对话框里的输入框内容不值得进状态类 —— 关掉即丢）。
    var creatingKind by remember { mutableStateOf<ProjectState.CreationKind?>(null) }

    // 顶栏副标题只留给「没读到 / 读失败」两句（诚实边界照旧）；读到了根层就只有标题
    // —— TG 主页顶栏没有第二行，「共 N 个文件」的计数批 25 起不再上顶栏（列表空态
    // 有 EmptyFilesHint 居中那句，信息不丢）。**进了目录才有第二行**：那是当前路径
    // （TG 文件页顶栏的副标题位就是路径），不是计数。
    val load = state.load
    val subtitle = when {
        load is LoadState.Failed -> load.reason
        load is LoadState.NotLoaded -> "尚未读取（点右上「⋮」现取）"
        // 搜索时列表是全库的，此时再显示"当前路径"就是指着别处说这里。
        searching -> "搜索全部文件"
        currentFolder != null -> currentFolder
        else -> null
    }

    // 底下那份：顶栏 + 搜索栏 + 文件列表 + FAB + 底部面板 + 创建对话框。
    // **编辑面开着时它整份照旧组合** —— 进出动画期间底下这份就是参照物（见里面
    // `RefreshableBox` 那段注释与 TG `:1170`）。
    //
    // @param showOverlays 只管**浮在上面那几个**（FAB、底部操作面板、创建对话框）——
    //   它们盖在内容之上，编辑期间不该还能点；顶栏/搜索栏/列表是要参与动画的那部分。
    @Composable
    fun FileListLayer(showOverlays: Boolean) {
            Column(Modifier.fillMaxSize().background(ThemeColors.background)) {
                if (selectionMode) {
                    // 选择模式顶栏（TG 的 action mode）：✕ 退出 / 已选计数 / 那一格是**同一个
                    // 开关** —— 选满了它自己变成"取消全选"（TG `SelectAll` / `DeselectAll`）。
                    ActionBar(
                        title = "已选 ${selected.size} 项",
                        onBack = { selected = emptySet() },
                        backGlyph = "✕",
                        actions = {
                            ActionBarAction(
                                text = if (allSelected) "取消全选" else "选择全部",
                                onClick = { selected = ProjectState.toggleSelectAll(selected, visible) },
                            )
                        },
                    )
                } else {
                    ActionBar(
                        // 根层是品牌位（TG `DialogsActivity` 的 20sp 粗体蓝）；进了目录换成目录名
                        // —— 顶栏标题说"我在哪"，与副标题的完整路径一短一长。
                        title = ProjectState.folderTitle(currentFolder) ?: "NodeScript",
                        titleStyle = if (currentFolder == null) {
                            TextStyle(
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = NodeScriptBrandColor,
                            )
                        } else {
                            null
                        },
                        subtitle = subtitle,
                        subtitleTone = if (load is LoadState.Failed) StatusTone.PROBLEM else StatusTone.MUTED,
                        // 进了目录才给 ‹（TG 文件页同款：根层没有"上一层"可退）。
                        onBack = if (currentFolder != null) ::goUp else null,
                        actions = {
                            ProjectMenu(
                                currentSort = state.sort,
                                reversed = state.reversed,
                                onSwitchTheme = onSwitchTheme,
                                themeSwitchLabel = themeSwitchLabel,
                                onSelectAll = { selected = ProjectState.toggleSelectAll(selected, visible) },
                                onSort = { sort, reversed -> onSortChange(sort, reversed) },
                            )
                        },
                    )
                }
                // 搜索栏与列表同进同出 —— 编辑面盖住时也留着，回退那一下不会看到布局跳变。
                SearchField(
                    query = query,
                    onChange = { query = it },
                )
                Box(Modifier.weight(1f)) {
                    // **列表全程都画**，编辑面盖住时也不省 —— 进出动画期间底下这份就是
                    // 参照物（48dp 横移 + 淡入 + 圆角缩放，都要有东西在底下才看得出层次）。
                    // TG `:1170` 同款：`if (!isTransitionAnimationInProgress()) canvas.clipRect(...)`
                    // —— 切换动画期间底层**根本不裁**。之前「被盖住就不画」，底下只剩一张
                    // 同色空底，动画看着「一点效果都没有」。
                    RefreshableBox(Modifier.fillMaxSize()) {
                        LazyColumn(
                            state = listState,
                            contentPadding = PaddingValues(top = 4.dp, bottom = TabBarBottomClearance()),
                        ) {
                            items(visible, key = { item -> ProjectState.keyOf(item) }) { file ->
                                val key = ProjectState.keyOf(file)
                                FileRow(
                                    file = file,
                                    selected = key in selected,
                                    showMore = !selectionMode,
                                    // ▶ 只在**可执行**（JS 家族）且不在选择模式时出现 ——
                                    // 判据的唯一一份在 `:domain` 的 `ScriptRunnability`。
                                    showRun = !selectionMode && ScriptRunnability.isRunnable(file.relPath),
                                    running = runningKey == key,
                                    onRun = { runFile(file) },
                                    onClick = {
                                        when {
                                            selectionMode -> selected = ProjectState.toggleSelection(selected, key)
                                            // 目录 = 进一层（TG 文件页点文件夹下钻）。
                                            // 从搜索结果进目录要**收起搜索**：不然进了层列表还是全库的
                                            // 搜索结果，等于"点了没反应"（TG 的 onSearchCollapse 同款）。
                                            file.isDirectory -> {
                                                // 从搜索结果进目录要**收起搜索**：不然进了层列表还是全库的
                                                // 搜索结果，等于"点了没反应"（TG 的 onSearchCollapse 同款）。
                                                query = ""
                                                enterFolder(file.relPath)
                                            }
                                            // 文件 = **直接进编辑面**（TG 文件页点文档就是打开，不弹菜单）；
                                            // 复制路径等其余动作走行尾的 ⋮ 与长按面板 —— 一行的主手势
                                            // 只留给"最常做的那件事"，两件事抢同一下点击就是猜。
                                            else -> openEditor(file)
                                        }
                                    },
                                    onMore = { sheetTarget = file },
                                    onLongClick = {
                                        longPressFeedback()
                                        // 长按进选择模式并选中这一行（TG 的选择模式入口就是长按）；
                                        // 已在模式里则只切这一行。
                                        selected = ProjectState.toggleSelection(selected, key)
                                    },
                                )
                                // 行间不画分割线（2026-10-07 用户口径：取消文件列表之间的横线）。
                            }
                            if (state.load.isLoaded && visible.isEmpty()) {
                                item { EmptyFilesHint(filtered = query.isNotBlank(), inFolder = currentFolder != null) }
                            }
                        }
                    }
                    ScrollToTopButton(
                        visible = rememberScrollToTopVisible(listState),
                        onClick = { scope.launch { listState.animateScrollToItem(0) } },
                        modifier = Modifier.align(Alignment.BottomEnd)
                            .padding(start = 16.dp, end = 16.dp, bottom = TabBarBottomClearance(extra = 64.dp)),
                    )
                    // FAB（TG FragmentFloatingButton：48dp 圆、品牌蓝、白铅笔）+ 展开的两个子项。
                    // 选择模式下收起：TG 的 action mode 里没有"新建"这一档，留着会和"已选 N 项"
                    // 的顶栏说两件不同的事。
                    if (!selectionMode && showOverlays) {
                        CreateFab(
                            onCreateFile = { creatingKind = ProjectState.CreationKind.FILE },
                            onCreateFolder = { creatingKind = ProjectState.CreationKind.FOLDER },
                            modifier = Modifier.align(Alignment.BottomEnd)
                                .padding(end = 20.dp, bottom = TabBarBottomClearance(extra = 8.dp)),
                        )
                    }
                }
            }
        // 一行的操作面板（TG 底部操作面板：长按/点文件行弹出，动作是大触控目标的整行）。
        // **只放今天真能做的**：复制路径（本屏自己就能做）+ 进文件夹（目录行才有）。
        // 运行/重命名/删除要宿主侧读口（`HostSummary` 目前只有 `createEntry`），
        // 没有的口不摆成按钮 —— 摆了就是点了没反应。
        if (showOverlays) sheetTarget?.let { target ->
            ActionBottomSheet(
                title = target.name,
                onDismiss = { sheetTarget = null },
            ) {
                ActionBottomSheetItem(
                    label = "复制路径",
                    onClick = {
                        copy.copy("已复制路径", target.relPath)
                        sheetTarget = null
                    },
                )
                if (target.isDirectory) {
                    ActionBottomSheetItem(
                        label = "进入文件夹",
                        onClick = {
                            query = ""
                            sheetTarget = null
                            enterFolder(target.relPath)
                        },
                    )
                }
            }
    }
        // 创建对话框在屏幕级（不在 Box 里 —— 它盖全屏，不该被列表的裁剪裁到）。
        if (showOverlays) CreateEntryDialogs(
            creating = creatingKind,
            targetProject = targetProject,
            onDismiss = { creatingKind = null },
            onConfirm = { name, isFolder ->
                creatingKind = null
                onCreate(targetProject, name, isFolder)
            },
        )
    }

    // 「文件列表 ⇄ 编辑面」那一层切换走 TG 那套（150ms 进出场 + 跟手预测式返回，
    // `ManagementTransition` 逐值对着 `ActionBarLayout`）。返回键整条归它 —— 它把
    // 非手势返回键与预测式手势合流（TG 是一个 `OnBackAnimationCallback` 管四态，
    // `LaunchActivity:738-818`），所以**不能再挂 `BackHandler`**（LIFO 会吃掉手势）。
    ManagementTransition(
        page = editing,
        active = active,
        onAnimatingChanged = { editorAnimating = it },
        onBackRequest = ::requestEditorClose,
        modifier = modifier,
        // 显式写 `content =`：尾随 lambda 只能绑到**签名里最后一个**形参，而这里最后
        // 一个是 `modifier`（不是函数类型）—— 写尾随 lambda 会被判成「实参过多」。
        content = { page ->
            FileListLayer(showOverlays = page == null)
            if (page != null) {
                ScriptEditorScreen(
                    projectId = page.projectId,
                    relPath = page.relPath,
                    displayName = page.name,
                    onRead = onReadFile,
                    onSave = onSaveFile,
                    // `onClose` = **立即关**（不复查）：确认框的「放弃修改」与「没改动直接回」
                    // 走这条。`requestEditorClose` 只留给手势那条路 —— 它要过守卫（有未
                    // 保存改动先问）。**别把两者接成一条**：`close()` → `onClose()` →
                    // `requestEditorClose()` → 守卫（`close()`）会无限递归（栈溢出，
                    // 表现就是「退出的时候直接软件都退出了」）。
                    onClose = { editing = null },
                    onRegisterCloseGuard = { editorCloseGuard = it },
                    modifier = Modifier,
                )
            }
        },
    )
}

/** 主页品牌蓝（TG `key_telegram_color_dialogsLogo` 默认值 #168BDB；深浅主题同值）。 */
private val NodeScriptBrandColor = Color(0xFF168BDB)

/**
 * 主屏的 `⋮` 菜单（TG `DialogsActivity` 顶栏右侧三个点的对应位）。
 *
 * 项序与分组照 TG 新式弹出菜单（`ItemOptions.add…().addGap()…`，`ResaleGiftsFragment`
 * 同一语法）：功能项一组 → **8dp 间隙**（`GapView`，`MATCH_PARENT × 8`）→ 排序组。
 * 主题切换在第一格（TG 的日/夜切换是菜单第一项的同一占位）：
 * 菜单项文案 = **点它切到的那一档**（目标模式），不是当前模式。
 */
@Composable
private fun ProjectMenu(
    currentSort: FileSort,
    reversed: Boolean,
    themeSwitchLabel: String,
    onSwitchTheme: (Offset) -> Unit,
    onSelectAll: () -> Unit,
    onSort: (FileSort, Boolean) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    // 这颗 ⋮ 在根坐标里的中心：主题切换的圆形揭示从它长出来。菜单本体住独立 popup
    // 窗口、坐标不在主窗口的系里，所以量的是锚点而不是被点的那一行（见 centerInRoot）。
    val anchor = remember { mutableStateOf(Offset.Zero) }
    Box {
        Box(Modifier.onGloballyPositioned { anchor.value = it.centerInRoot() }) {
            // 三点是**画出来的**（[GlyphKind.MORE_VERT] + 最粗那档参数）：原先写成
            // `Text("⋮")`，粗细由系统字体决定、没法与列表行尾那颗比大小（见那组常量的 KDoc）。
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
            actions = buildList {
                // 标签 = **点它切到的那一档**（TG `DialogsActivity` 的日夜项：
                // 当前是深色就写 "Day Mode"，当前是浅色就写 "Night Mode"）。
                // `dismissOnClick = false`：切主题**不关菜单**（用户口径）—— 换的只是配色，
                // 菜单里其余项还得能接着点；收掉会逼用户为"再选一次排序"重开一遍。
                add(
                    MenuAction(
                        label = themeSwitchLabel,
                        dismissOnClick = false,
                        onClick = { onSwitchTheme(anchor.value) },
                    ),
                )
                add(MenuGap)
                add(MenuAction(label = "选择全部", onClick = onSelectAll))
                add(
                    MenuAction(label = "排序方式", onClick = {
                        open = false
                        sortMenuOpen = true
                    }),
                )
            },
        )
        // 排序子菜单（TG 的 swipeback 子菜单在本仓的简化：第二级 DropdownMenu，
        // 四档 + "仅应用于此文件夹"的逆向开关 —— 文案逐字对 TG `ReverseOrder`）。
        ContextMenu(
            expanded = sortMenuOpen,
            onDismiss = { sortMenuOpen = false },
            actions = buildList {
                FileSort.entries.forEach { sort ->
                    add(
                        MenuAction(
                            label = if (sort == currentSort) "✓ ${sort.label}" else sort.label,
                            onClick = { onSort(sort, reversed) },
                        ),
                    )
                }
                add(MenuGap)
                add(
                    MenuAction(
                        label = if (reversed) "✓ 逆向排序" else "逆向排序",
                        onClick = { onSort(currentSort, !reversed) },
                    ),
                )
            },
        )
    }
}

/**
 * 圆角搜索栏（`FragmentSearchField` 的 `DialogsActivity` 档逐值版式）：**52dp 槽位、
 * 槽内水平 12dp / 垂直 6dp → 高 40dp、宽 = 屏宽 −24dp、圆角 20dp**（40dp 高上 20dp 圆角
 * = 两端全圆）、灰底（白底压 5% 黑 —— 与任务中心那颗白药丸不是一个键）、**无投影**
 * （`createRoundRectDrawable`，不是 `…Shadowed`）、放大镜 24dp 距左 12dp
 * （[GlyphKind.SEARCH_FIELD]，`outline_search_1_24` 实测几何 —— 批 43 换掉旧的 SEARCH）、
 * 提示词 15sp 半透明、输入文字 15sp（`editText.setTextSize(15)`）。
 *
 * 不用 M3 `TextField`：它自带的 56dp 最小高与大内边距塞不进 40dp 的行高里
 * （批 25 实机：hint 文字被上下裁掉一截），而这页搜索框用不上它的 label/indicator
 * 那套装饰 —— 直接 `BasicTextField` + 自己摆 `Row`，内边距就是版式要的那几个 dp。
 */
@Composable
private fun SearchField(
    query: String,
    onChange: (String) -> Unit,
) {
    val palette = ThemeColors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .height(40.dp)
            .background(palette.fieldBackground, RoundedCornerShape(20.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(12.dp))
        Glyph(kind = GlyphKind.SEARCH_FIELD, tint = palette.text.copy(alpha = 0.6f))
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    text = "搜索文件",
                    color = palette.text.copy(alpha = 0.5f),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = palette.text),
                // 光标色是 `groupcreate_cursor`（[ThemeColors.cursor]），与强调色**不是一个键**。
                cursorBrush = SolidColor(palette.cursor),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.width(12.dp))
    }
}

/**
 * 一行文件（TG 会话行 / `SharedDocumentCell` 的合体读法）：
 * 52dp 圆形头像 = 文件类型底色 + 白字扩展名缩写（无扩展名 = 首字母），右侧两行文字。
 * 文件夹行：头像 = 文件夹字形（accent 色底白线），次行 = "N 项 · 时刻"（TG 文件页
 * 文件夹行不显示字节数的同一口径）。
 *
 * **三个手势**（TG 列表的读法，与任务中心同构）：
 * - **点**：目录 = 进一层；文件 = 进编辑面；选择模式里 = 勾/去勾这一行；
 * - **长按**：进选择模式并勾上这一行（TG 的选择模式入口就是长按）；
 * - **行尾 ⋮**（[showMore]）：开这一行的操作面板（复制路径 / 进文件夹）——
 *   与长按**不是**同一件事：长按是"选中"（TG 语义），操作面板要一个明确可点的入口。
 *
 * 选中态是**整行淡底 + 头像位换成勾**（TG 选择模式的读法：勾在最左那一格，
 * 头像让位）—— 只换行底色的话，勾选与"这行被按住了"看起来是一回事。
 */
@Composable
private fun FileRow(
    file: ScriptFileRowUi,
    selected: Boolean,
    /** 是否画行尾的 ⋮（选择模式里不画 —— 那时行尾留给"已选 N 项"那套语义）。 */
    showMore: Boolean,
    /** 是否画行尾的 ▶（只对可执行脚本；判据见调用点的 `ScriptRunnability`）。 */
    showRun: Boolean,
    /** 这个文件此刻在跑（▶ 换成转圈、按钮禁用）。 */
    running: Boolean,
    /** 行尾 ▶ 的落点（跑这个文件）。 */
    onRun: () -> Unit,
    onClick: () -> Unit,
    /** 行尾 ⋮ 的落点（开操作面板）。 */
    onMore: () -> Unit,
    onLongClick: () -> Unit,
) {
    val palette = ThemeColors
    Row(
        Modifier
            .fillMaxWidth()
            // 遮罩**通栏**（`DialogCell` 的 `rect.set(0, 0, getMeasuredWidth(), …)`），
            // 只有圆角是内缩的观感 —— 不加左右外边距。
            .clip(RoundedCornerShape(FileRowSelectedRadius))
            // 选中遮罩是**中性黑/白 6%**（`chats_tabletSelectedOverlay`，
            // [ThemeColors.rowSelectedOverlay]），不是强调色淡底 —— TG 的 `DialogCell`
            // 用 `dialogs_tabletSeletedPaint` 铺一层灰，蓝色只留给未读计数那些真·强调位。
            .background(if (selected) palette.rowSelectedOverlay else Color.Transparent)
            .pressableLongPress(role = Role.Button, onLongClick = onLongClick, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(12.dp))
        if (selected) {
            SelectionTick()
        } else if (file.isDirectory) {
            FolderAvatar(name = file.name)
        } else {
            FileTypeAvatar(ext = file.ext, name = file.name)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
            Text(
                text = file.name,
                color = ThemeColors.text,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = file.subtitle,
                color = ThemeColors.textTertiary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
            )
        }
        if (showRun) {
            // 行尾 ▶（跑这个文件；2026-10-13 用户口径「可执行文件在菜单栏左边加个执行图标按钮」）：
            // 与任务中心那颗「立即执行」同一形态（实心三角、accent 蓝、44dp 触控目标），
            // 只是它跑的是**这一行这个文件**、不需要先登记任务。
            //
            // 挂在 ⋮ **左边**：⋮ 是"这一行的其余动作"（TG 的溢出钮位），▶ 是主动作 ——
            // 主动作排在溢出钮前面，与顶栏「外壳动作在左、本屏动作在右」是同一条读法。
            //
            // 跑动中换成转圈（0.9s 一圈线性，与任务中心那颗同一节奏）：宿主是挂起到
            // 执行结算的，静止的 ▶ 会让人以为没点上。
            RunScriptButton(running = running, onClick = onRun)
        }
        if (showMore) {
            // 行尾 ⋮（TG 的溢出钮 `ic_ab_other`）：48dp 触控目标、居行右端。
            // 色取 `windowBackgroundWhiteBlackText`（[ThemeColors.text]：浅色下就是黑），
            // 与顶栏那颗 `⋮` 同档 —— 列表行里唯一一处"文字色"的图标。
            Box(
                Modifier
                    .size(FileRowMoreTouch)
                    .pressable(
                        role = Role.Button,
                        overlay = palette.menuSelector,
                        shape = CircleShape,
                        onClick = onMore,
                    )
                    .semantics { contentDescription = "更多（${file.name}）" },
                contentAlignment = Alignment.Center,
            ) {
                Glyph(
                    kind = GlyphKind.MORE_VERT,
                    tint = palette.text,
                    size = RowMoreDotsSize,
                    weight = ROW_MORE_DOTS_WEIGHT,
                )
            }
            Spacer(Modifier.width(4.dp))
        } else {
            Spacer(Modifier.width(16.dp))
        }
    }
}

/**
 * 行尾那颗 ▶（跑这个文件）。跑动中换成开口弧转圈 —— 与任务中心那颗「立即执行」
 * （[com.autoscript.ui.screens.TaskCenterScreen] 的 `PlayButton`）同一形态、同一节奏：
 * 同一件事在两屏长得不一样，用户就得学两遍。
 *
 * 触控目标 44dp（与那颗一致），图标 20dp、accent 蓝（[ThemeColors.accent]）。
 */
@Composable
private fun RunScriptButton(running: Boolean, onClick: () -> Unit) {
    val accent = ThemeColors.accent
    Box(
        Modifier
            .size(RunScriptTouch)
            .semantics { contentDescription = if (running) "执行中" else "运行" }
            .pressable(enabled = !running, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (running) {
            // 色值在组合期取好再进绘制 lambda（`ThemeColors` 是 CompositionLocal 读口，
            // 绘制帧里读不到组合环境）—— 与任务中心那颗逐字同一条理由。
            val spin = rememberInfiniteTransition(label = "runSpin")
            val angle by spin.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(tween(durationMillis = 900, easing = LinearEasing)),
                label = "runSpinAngle",
            )
            Canvas(Modifier.size(RunScriptIcon)) {
                drawArc(
                    color = accent,
                    startAngle = angle,
                    sweepAngle = 280f,
                    useCenter = false,
                    style = Stroke(width = this.size.minDimension * 0.16f, cap = StrokeCap.Round),
                )
            }
        } else {
            Glyph(kind = GlyphKind.RUN, tint = accent, size = RunScriptIcon)
        }
    }
}

/** 行尾 ▶ 的触控目标（与任务中心那颗「立即执行」同值）。 */
private val RunScriptTouch = 44.dp

/** 行尾 ▶ 的图标边长（同上）。 */
private val RunScriptIcon = 20.dp

/** 行尾 ⋮ 的触控目标（Material 最小可达性 48dp）。 */
private val FileRowMoreTouch = 48.dp

/** 选中遮罩的圆角（`DialogCell` 的 `cornersRadius = dp(8) * cornerProgress`）。 */
private val FileRowSelectedRadius = 8.dp

/**
 * 选中标记（占头像那一格，52dp 见方）：实心圆 + 白勾。
 *
 * **配色抄 TG 的 `CheckBox2`**（`DialogCell` 的 `new CheckBox2(context, 21)` +
 * `setColor(-1, key_windowBackgroundWhite, key_checkboxCheck)` + `setDrawUnchecked(false)`）：
 * 环不画、底不画，**选中就是一颗实心绿圆 + 白勾** —— 填充是 `key_checkbox`
 * （[ThemeColors.checkboxFill] = `0xFF5EC245`，两套 attheme 都不覆盖），
 * 勾是 `key_checkboxCheck`（白）。
 *
 * **诚实边界**：TG 的勾选框是 **21dp**（`CheckBox2(context, 21)`）的小方块位，
 * 挂在头像**左下角**（`chekBoxPaddingTop = 42`）；本仓把整格头像换成勾（52dp 圆），
 * 是**本仓的取舍**（勾与头像同格同位，勾选时行内其余内容一格都不动），
 * 尺寸与位置都不是 TG 的值 —— 只有配色与"实心圆 + 白勾"这个形态是抄的。
 *
 * 与头像**同格同位**（不是挤在行尾）：这样勾选时行内其余内容一格都不动 ——
 * 位移会让"我勾的是哪一行"变得需要重新确认。
 */
@Composable
private fun SelectionTick() {
    val palette = ThemeColors
    Box(
        Modifier
            .size(52.dp)
            .background(palette.checkboxFill, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "✓",
            color = palette.checkboxCheck,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * 文件夹头像（TG 文件页目录行的同一读法：目录不是"一种文件类型"，给它**专属字形**
 * 而不是扩展名缩写 —— 底色仍按名字哈希取色，与文件行共用同一套色板）。
 */
@Composable
private fun FolderAvatar(name: String) {
    val palette = ThemeColors
    val slot = name.firstOrNull()?.code?.plus(1)?.mod(palette.fileAvatarColors.size) ?: 0
    Box(
        Modifier
            .size(52.dp)
            .graphicsLayer { shape = CircleShape }
            .background(palette.fileAvatarColors[slot], CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Glyph(kind = GlyphKind.FOLDER, tint = Color.White, size = 26.dp, weight = 1.15f)
    }
}

/**
 * 列表空态（TG "No entries here" 的分寸：居中、次级色）。
 *
 * 三句不同的话对应三种不同的"空"：搜出来的空（改搜索词）、目录里的空（这层真没有）、
 * 全库的空（还没部署过项目）。说成同一句会让人去改搜索词，而问题在根本没文件。
 */
@Composable
private fun EmptyFilesHint(filtered: Boolean, inFolder: Boolean) {
    ToneText(
        text = when {
            filtered -> "没有匹配「搜索词」的文件"
            inFolder -> "这个文件夹是空的"
            else -> "还没有脚本文件"
        },
        tone = StatusTone.MUTED,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
    )
}

/**
 * 右下角悬浮创建按钮（TG `FragmentFloatingButton` 的逐字版式）：
 * 48dp 圆（`SIZE = 48`）、右下边距 20/14dp（`createDefaultLayoutParams` 的 left/right 20、
 * bottom 14）、底 `featuredStickers_addButton`（[ThemeColors.featuredButton]，
 * 浅色 `#FF4DA0EB` / 深色 `#FF229AF0` —— 原先写死的 `#229AF0` 只有深色那半对）、
 * 图标 `chats_actionIcon`（[ThemeColors.featuredButtonText]，白）。
 *
 * **按下反馈**（`ScaleStateListAnimator.apply(this)` = `apply(view, .1f, 1.5f)`）：
 * 按下 → 缩到 **0.9**、**80ms 线性**；松开 → 回 1、**350ms**
 * `OvershootInterpolator(1.5)`（过冲到约 1.05 再收）。底色的按下档
 * （`featuredStickers_addButtonPressed`）在 TG 的 `createSimpleSelectorCircleDrawable`
 * 里是**水波**的目标色，本仓没有水波，故只用缩放这一路。
 *
 * 点击展开两个子项（新建文件/新建文件夹）：TG 的子按钮是另一颗 48dp 圆浮在主按钮上方
 * （`createSubButtonLayoutParams` 同位、blur3 背板），这里用同一语法的动画展开 ——
 * 按住主钮时子项滑入，点空白/主钮收起。子项是**圆角长方形**（高同主钮、宽随文案，
 * 文案在左、图标靠右；见 [FabSubItem]）。
 */
@Composable
private fun CreateFab(
    onCreateFile: () -> Unit,
    onCreateFolder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 45f else 0f,
        // TG 的主钮在"展开/收起"之间是**换 lottie 动画**（`setAnimation`），
        // 不是旋转图标；本仓没有 lottie，用 45° 旋转表达同一件事，
        // 时长与曲线取 TG 那颗钮自己的 `EASE_OUT_QUINT` 380ms。
        animationSpec = tween(durationMillis = FabAnimDurationMillis, easing = EaseOutQuint),
        label = "fabRotate",
    )
    // 按下缩放：`ScaleStateListAnimator.apply(this)` —— 按下 80ms 线性缩到 0.9，
    // 松开 350ms `OvershootInterpolator(1.5)` 弹回（过冲约 1.05 再收）。
    // 这条状态要自己订阅（`pressable` 内部自建 source，外面读不到按下与否）。
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val fabScale by animateFloatAsState(
        targetValue = if (pressed) FabPressedScale else 1f,
        animationSpec = if (pressed) {
            tween(durationMillis = FabPressDurationMillis)
        } else {
            tween(durationMillis = FabReleaseDurationMillis, easing = OvershootEasing(FabReleaseTension))
        },
        label = "fabScale",
    )
    val subOffsetPx = with(LocalDensity.current) { FabSubRise.roundToPx() }
    val subEnter = fadeIn(tween(FabAnimDurationMillis, easing = EaseOutQuint)) +
        // `setAnimatedVisibility`：alpha = f、scale = lerp(0.4, 1, f)；
        // `setAdditionalTranslationY(dp(64) * (1 - f))` 是子钮"从主钮里长出来"那一段。
        scaleIn(initialScale = FabSubInitialScale, animationSpec = tween(FabAnimDurationMillis, easing = EaseOutQuint)) +
        slideInVertically(tween(FabAnimDurationMillis, easing = EaseOutQuint)) { subOffsetPx }
    val subExit = fadeOut(tween(FabAnimDurationMillis, easing = EaseOutQuint)) +
        scaleOut(targetScale = FabSubInitialScale, animationSpec = tween(FabAnimDurationMillis, easing = EaseOutQuint)) +
        slideOutVertically(tween(FabAnimDurationMillis, easing = EaseOutQuint)) { subOffsetPx }
    // 子项与主钮同尺寸（48dp），列宽 = 主钮宽；**右对齐**是让"子钮与主钮同心"这条
    // 在任何列宽下都成立（居中一旦遇上更宽的子项就会把主钮往左推）。
    Column(modifier, horizontalAlignment = Alignment.End) {
        // 子项自下而上展开（TG：子按钮浮在主按钮上方，逐颗滑出）。
        AnimatedVisibility(visible = expanded, enter = subEnter, exit = subExit) {
            FabSubItem(label = "新建文件夹", glyph = GlyphKind.FOLDER, onClick = {
                expanded = false
                onCreateFolder()
            })
        }
        Spacer(Modifier.height(8.dp))
        AnimatedVisibility(visible = expanded, enter = subEnter, exit = subExit) {
            FabSubItem(label = "新建文件", glyph = GlyphKind.FILE_DOC, onClick = {
                expanded = false
                onCreateFile()
            })
        }
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier
                .size(FabSize)
                .graphicsLayer {
                    // `ScaleStateListAnimator.apply(this)`：按下 0.9、松开过冲回 1。
                    shape = CircleShape
                    scaleX = fabScale
                    scaleY = fabScale
                }
                .background(ThemeColors.featuredButton, CircleShape)
                .clickable(
                    interactionSource = source,
                    // 形状要一起给：按压遮罩按矩形画，不给形状就会在圆的四个角上露出
                    // 方块灰影（见 `FlatPressIndication`）。
                    indication = rememberPressIndication(shape = CircleShape),
                    role = Role.Button,
                    onClick = { expanded = !expanded },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Glyph(
                kind = GlyphKind.PENCIL,
                tint = ThemeColors.featuredButtonText,
                size = 24.dp,
                modifier = Modifier.rotate(rotation),
            )
        }
    }
}

/** FAB 直径（`FragmentFloatingButton.SIZE = 48`）。 */
private val FabSize = 48.dp

/** 按下缩放到的档（`ScaleStateListAnimator.apply(this)` = `apply(view, .1f, 1.5f)` → 1 − 0.1）。 */
private const val FabPressedScale = 0.9f

/** 按下那一段的时长（`pressedAnimator.setDuration(80)`，**无插值器** = 线性）。 */
private const val FabPressDurationMillis = 80

/** 松开回弹的时长（`defaultAnimator.setDuration(350)`）。 */
private const val FabReleaseDurationMillis = 350

/** 回弹的张力（`new OvershootInterpolator(tension)`，`apply(view)` 缺省 1.5）。 */
private const val FabReleaseTension = 1.5f

/** 子钮显隐时长（`BoolAnimator(…, EASE_OUT_QUINT, 380)`）。 */
private const val FabAnimDurationMillis = 380

/** 子钮入场缩放的起点（`setAnimatedVisibility`：`lerp(0.4f, 1f, f)`）。 */
private const val FabSubInitialScale = 0.4f

/** 子钮入场的纵向位移（`setAdditionalTranslationY(dp(isSubButton ? 64 : 40) * (1 - f))`）。 */
private val FabSubRise = 64.dp

/**
 * FAB 展开的子项：**与主钮同尺寸的圆徽章（[FabSize] = 48dp）、只有图标** ——
 * TG 的 `FragmentFloatingButton` 子按钮就是这个形态（一颗同尺寸的圆钮 + 一枚字形）。
 *
 * **不带文案**：这两档动作（新建文件 / 新建文件夹）一度做成"圆角长方形 + 文案"，
 * 但两颗子钮并排时文字把主钮上方那一列撑得很宽、也把 48dp 的节奏打散；
 * 图标（文档 / 文件夹）+ 点完立刻出现的命名对话框标题已经足够分辨。
 * 无文案的代价是读屏认不出，故 [label] 仍进 contentDescription。
 *
 * 背板沿用 TG 的 `iBlur3Background`（`BlurredBackgroundDrawable`）那一套：
 * - **正圆**（TG 的 `setRadius(dp(18))` 落在 48dp 上本来就近乎正圆；用户口径：与主钮同形）；
 * - **描边 0.4dp**（`setStrokeWidth(dpf2(0.4f), dpf2(0.4f))`），色随深浅：
 *   上边浅色 `0x20000000` / 深色 `0x11FFFFFF`（[FabSubStrokeTop]）；
 * - 底是**模糊背板**（把身后的内容模糊后上浮），本仓没有实时模糊，
 *   取 `key_windowBackgroundWhite` 的实色近似 —— 浅色下就是白，深色下是 `#161E27`
 *   （[ThemeColors.background]，批 67 的用户口径值）。
 * - 图标取 `key_actionBarDefaultIcon`（[ThemeColors.barIcon]：浅色偏冷深灰
 *   `#FF404E56`，**不是**正文黑）；按下底 = `key_listSelector`
 *   （[ThemeColors.menuSelector] 同档）。形状要一起给 [pressable]：按压遮罩按矩形画，
 *   不给形状就会在圆的四个角上露出方块灰影（见 `FlatPressIndication`）。
 */
@Composable
private fun FabSubItem(label: String, glyph: GlyphKind, onClick: () -> Unit) {
    val palette = ThemeColors
    // 正圆：与主钮同形（TG 那颗 `setRadius(dp(18))` 落在 48dp 上本来就近乎正圆）。
    val shape = CircleShape
    Box(
        Modifier
            .size(FabSize)
            .background(palette.background, shape)
            .border(0.4.dp, FabSubStrokeTop(), shape)
            .pressable(
                role = Role.Button,
                overlay = palette.menuSelector,
                shape = shape,
                onClick = onClick,
            )
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Glyph(kind = glyph, tint = palette.barIcon, size = 24.dp)
    }
}

/**
 * 子钮那圈 0.4dp 描边（`BlurredBackgroundDrawable.getStrokeColorTop()`：
 * 浅色 `0x20000000`、深色 `0x11FFFFFF`）。
 */
@Composable
private fun FabSubStrokeTop(): Color =
    if (isDarkTheme()) Color(0x11FFFFFF) else Color(0x20000000)

/**
 * 新建文件/文件夹对话框（TG 的 Alert 命名框分寸：标题点破建什么、输入框占位"名称"）。
 * 名字合法性在宿主侧裁决（`ScriptFileOps`），这里不预校验 —— 错误原文走 opError 行。
 */
@Composable
private fun CreateEntryDialogs(
    creating: ProjectState.CreationKind?,
    targetProject: String,
    onDismiss: () -> Unit,
    onConfirm: (name: String, isFolder: Boolean) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    // creating 为 null 时对话框不在组合里，name 随 remember 的 key 重置。
    remember(creating) { name = ""; true }
    if (creating == null) return
    val title = when (creating) {
        ProjectState.CreationKind.FILE -> "新建文件"
        ProjectState.CreationKind.FOLDER -> "新建文件夹"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(
                    text = "将创建在项目「$targetProject」下",
                    color = ThemeColors.textTertiary,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onConfirm(name.trim(), creating == ProjectState.CreationKind.FOLDER) },
            ) { Text("创建") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
