package com.autoscript.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.ui.components.ActionBar
import com.autoscript.ui.components.ActionBarAction
import com.autoscript.ui.components.ActionBottomSheet
import com.autoscript.ui.components.ActionBottomSheetItem
import com.autoscript.ui.components.ContextMenu
import com.autoscript.ui.components.EaseOutQuint
import com.autoscript.ui.components.OvershootEasing
import com.autoscript.ui.components.rememberPressIndication
import com.autoscript.ui.theme.isDarkTheme
import com.autoscript.ui.components.CopyNotice
import com.autoscript.ui.components.MenuAction
import com.autoscript.ui.components.MenuGap
import com.autoscript.ui.components.Glyph
import com.autoscript.ui.components.GlyphKind
import com.autoscript.ui.components.RefreshableBox
import com.autoscript.ui.components.ScrollToTopButton
import com.autoscript.ui.components.TabBarBottomClearance
import com.autoscript.ui.components.ToneText
import com.autoscript.ui.components.pressable
import com.autoscript.ui.components.pressableLongPress
import com.autoscript.ui.components.rememberCopyAction
import com.autoscript.ui.components.rememberLongPressFeedback
import com.autoscript.ui.state.FileSort
import com.autoscript.ui.state.LoadState
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
 * - **搜索栏**：灰底圆框（`FragmentSearchField`：圆角 20dp、左右图标 12dp 内缩、
 *   提示词半透明）+ 放大镜 + 「搜索文件」提示词（TG 的 hint 位）；
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
@Composable
fun ProjectScreen(
    state: ProjectState,
    /** 主题两态切换（⋮ 第一格，TG 日/夜同款 —— 目标模式写菜单项上）。 */
    onSwitchTheme: () -> Unit,
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
    /** 排序档/逆向变更（写入 state —— 重读不重置呈现偏好）。 */
    onSortChange: (FileSort, Boolean) -> Unit,
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
    val longPressFeedback = rememberLongPressFeedback()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // 可见集 = 一层（[ProjectState.childrenOf]）→ 排序。**搜索时层裁剪让位**：
    // TG 文件选择器的搜索是**另一份数据集**（`searchAdapter` 搜整棵树、含"最近"分区，
    // 展开搜索栏时列表整个换掉），不是"在当前目录里筛一下" —— 用户搜文件名时想找的是
    // "那个文件在哪"，锁在当前层就等于答非所问。搜索清空即回到当前层。
    val searching = query.isNotBlank()
    val visible = ScriptFileRowUi.sorted(
        files = ProjectState.poolFor(state.files, currentFolder, searching).filter { it.matches(query) },
        sort = state.sort,
        reversed = state.reversed,
    )
    val selectionMode = selected.isNotEmpty()
    val allSelected = ProjectState.allSelected(selected, visible)

    /** 退回上一层（返回键与顶栏 ‹ 共用一条路径）。 */
    fun goUp() {
        currentFolder = ProjectState.parentFolder(currentFolder)
        selected = emptySet()
        scope.launch { listState.scrollToItem(0) }
    }

    // 返回键的退让次序照 TG：先收面板 → 再退选择模式 → 再清搜索 → 最后才退一层目录。
    // 每一档都只做一件事，且「还有下一档」才拦下返回 —— 于是根层没搜索时返回键
    // 照常退出应用（不吞），而每按一次都恰好撤回用户看到的上一步。
    BackHandler(enabled = sheetTarget != null || selectionMode || query.isNotBlank() || currentFolder != null) {
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

    Column(modifier.fillMaxSize().background(ThemeColors.background)) {
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
        SearchField(
            query = query,
            onChange = { query = it },
        )
        Box(Modifier.weight(1f)) {
            RefreshableBox(Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(top = 4.dp, bottom = TabBarBottomClearance()),
                ) {
                    item { CopyNotice(copy) }
                    // 操作回执（新建的成败）在这里说：失败**带原文**（区分"名字非法"与
                    // "项目不存在"的唯一线索），成功只说"已新建"。
                    state.opError?.let { item { FeedbackLine("操作失败：$it", StatusTone.PROBLEM) } }
                    state.opNotice?.let { item { FeedbackLine(it, StatusTone.OK) } }
                    items(visible, key = { ProjectState.keyOf(it) }) { file ->
                        val key = ProjectState.keyOf(file)
                        FileRow(
                            file = file,
                            selected = key in selected,
                            onClick = {
                                when {
                                    selectionMode -> selected = ProjectState.toggleSelection(selected, key)
                                    // 目录 = 进一层（TG 文件页点文件夹下钻）；文件 = 开操作面板。
                                    // 从搜索结果进目录要**收起搜索**：不然进了层列表还是全库的
                                    // 搜索结果，等于"点了没反应"（TG 的 onSearchCollapse 同款）。
                                    file.isDirectory -> {
                                        currentFolder = file.relPath
                                        query = ""
                                        selected = emptySet()
                                        scope.launch { listState.scrollToItem(0) }
                                    }
                                    else -> sheetTarget = file
                                }
                            },
                            onLongClick = {
                                longPressFeedback()
                                // 长按进选择模式并选中这一行（TG 的选择模式入口就是长按）；
                                // 已在模式里则只切这一行。
                                selected = ProjectState.toggleSelection(selected, key)
                            },
                        )
                    }
                    if (state.load.isLoaded && visible.isEmpty()) {
                        item { EmptyFilesHint(filtered = query.isNotBlank(), inFolder = currentFolder != null) }
                    }
                }
            }
            ScrollToTopButton(
                visible = listState.firstVisibleItemIndex > 0,
                onClick = { scope.launch { listState.animateScrollToItem(0) } },
                modifier = Modifier.align(Alignment.BottomEnd)
                    .padding(start = 16.dp, end = 16.dp, bottom = TabBarBottomClearance(extra = 64.dp)),
            )
            // FAB（TG FragmentFloatingButton：48dp 圆、品牌蓝、白铅笔）+ 展开的两个子项。
            // 选择模式下收起：TG 的 action mode 里没有"新建"这一档，留着会和"已选 N 项"
            // 的顶栏说两件不同的事。
            if (!selectionMode) {
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
    sheetTarget?.let { target ->
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
                        currentFolder = target.relPath
                        query = ""
                        selected = emptySet()
                        sheetTarget = null
                        scope.launch { listState.scrollToItem(0) }
                    },
                )
            }
        }
    }
    // 创建对话框在屏幕级（不在 Box 里 —— 它盖全屏，不该被列表的裁剪裁到）。
    CreateEntryDialogs(
        creating = creatingKind,
        targetProject = targetProject,
        onDismiss = { creatingKind = null },
        onConfirm = { name, isFolder ->
            creatingKind = null
            onCreate(targetProject, name, isFolder)
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
    onSwitchTheme: () -> Unit,
    onSelectAll: () -> Unit,
    onSort: (FileSort, Boolean) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var sortMenuOpen by remember { mutableStateOf(false) }
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
            actions = buildList {
                // 标签 = **点它切到的那一档**（TG `DialogsActivity` 的日夜项：
                // 当前是深色就写 "Day Mode"，当前是浅色就写 "Night Mode"）。
                add(MenuAction(label = themeSwitchLabel, onClick = onSwitchTheme))
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
 * 圆角搜索栏（`FragmentSearchField` 的逐字版式）：高 48dp、圆角 20dp、灰底、
 * 放大镜 24dp 距左 12dp、提示词 15sp 半透明、输入文字 15sp（`editText.setTextSize(15)`）。
 *
 * 不用 M3 `TextField`：它自带的 56dp 最小高与大内边距塞不进 48dp 的行高里
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
            .padding(horizontal = 7.dp, vertical = 4.dp)
            .height(48.dp)
            .background(palette.fieldBackground, RoundedCornerShape(20.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(12.dp))
        Glyph(kind = GlyphKind.SEARCH, tint = palette.text.copy(alpha = 0.6f))
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
 * **两个手势**（TG 列表的读法，与任务中心同构）：
 * - **点**：目录 = 进一层；文件 = 开操作面板；选择模式里 = 勾/去勾这一行；
 * - **长按**：进选择模式并勾上这一行（TG 的选择模式入口就是长按）。
 *
 * 选中态是**整行淡底 + 头像位换成勾**（TG 选择模式的读法：勾在最左那一格，
 * 头像让位）—— 只换行底色的话，勾选与"这行被按住了"看起来是一回事。
 */
@Composable
private fun FileRow(
    file: ScriptFileRowUi,
    selected: Boolean,
    onClick: () -> Unit,
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
        Spacer(Modifier.width(16.dp))
    }
}

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
 * 文件类型头像（TG `AvatarDrawable` 的取色 + `SharedDocumentCell.extTextView` 的白字）：
 * 底色 = 扩展名哈希取色表槽位（同一扩展名恒同色），字 = 扩展名（最多 4 字符），
 * JS/HTML/SH/MD 等常见类型换专属字形（白线画在色底上）。
 */
@Composable
private fun FileTypeAvatar(ext: String, name: String) {
    val palette = ThemeColors
    val colors = palette.fileAvatarColors
    val slot = if (ext.isNotEmpty()) {
        ext.first().code % colors.size
    } else {
        name.firstOrNull()?.code?.plus(1)?.mod(colors.size) ?: 0
    }
    val bg = colors[slot]
    val knownGlyph = when (ext) {
        "js", "mjs", "cjs", "ts", "json" -> GlyphKind.FILE_JS
        "md", "txt", "log", "doc" -> GlyphKind.FILE_DOC
        "html", "htm", "css" -> GlyphKind.FILE_HTML
        "sh", "bash", "zsh" -> GlyphKind.FILE_SH
        else -> null
    }
    Box(
        Modifier
            .size(52.dp)
            .graphicsLayer { shape = CircleShape }
            .background(bg, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (knownGlyph != null) {
            // 常见类型：白线字形（比缩写字更一眼可辨 —— TG 文件页对图片/视频也是图标优先）。
            Glyph(kind = knownGlyph, tint = Color.White, size = 26.dp, weight = 1.15f)
        } else {
            Text(
                text = ext.take(4).ifEmpty { "?" },
                color = Color.White,
                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold),
            )
        }
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

/** 操作回执行（与任务中心同一条分寸：失败带原文、成功只说做了什么）。 */
@Composable
private fun FeedbackLine(text: String, tone: StatusTone) {
    ToneText(
        text = text,
        tone = tone,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
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
 * 按住主钮时子项滑入，点空白/主钮收起。子项与主钮**同规格**的 48dp 圆徽章（批 25：
 * 圆标 + 灰底，不再是文字胶囊；见 [FabSubItem]）。
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
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
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
                    indication = rememberPressIndication(),
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
 * FAB 展开的子项：与主钮同规格的 48dp 圆徽章（`createSubButtonLayoutParams` 的 48×48）。
 *
 * 背板 = TG 的 `iBlur3Background`（`BlurredBackgroundDrawable`）：
 * - **圆角 18dp**（`setRadius(dp(18))`）—— 在 48dp 的圆徽章上即"接近圆"的方角，
 *   不是正圆；
 * - **描边 0.4dp**（`setStrokeWidth(dpf2(0.4f), dpf2(0.4f))`），色随深浅：
 *   上边浅色 `0x20000000` / 深色 `0x11FFFFFF`（[FabSubStrokeTop]）；
 * - 底是**模糊背板**（把身后的内容模糊后上浮），本仓没有实时模糊，
 *   取 `key_windowBackgroundWhite` 的实色近似 —— 浅色下就是白，深色下是 `#181819`
 *   （[ThemeColors.surfaceMuted] 的浅色档不适用，故直接用 [ThemeColors.background]）。
 * - 图标 = `key_actionBarDefaultIcon`（[ThemeColors.barIcon]：浅色偏冷深灰 `#FF404E56`，
 *   **不是**正文黑）；按下底 = `key_listSelector`（[ThemeColors.menuSelector] 同档）。
 *
 * TG 子按钮同样只有图标没有字 —— 文案进 contentDescription 给读屏。
 */
@Composable
private fun FabSubItem(label: String, glyph: GlyphKind, onClick: () -> Unit) {
    val palette = ThemeColors
    Box(
        Modifier
            .size(FabSize)
            .background(palette.background, RoundedCornerShape(FabSubCornerRadius))
            .border(0.4.dp, FabSubStrokeTop(), RoundedCornerShape(FabSubCornerRadius))
            .pressable(
                role = Role.Button,
                overlay = palette.menuSelector,
                onClick = onClick,
            )
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Glyph(kind = glyph, tint = palette.barIcon, size = 24.dp)
    }
}

/** 子钮背板圆角（`iBlur3Background.setRadius(dp(18))`）。 */
private val FabSubCornerRadius = 18.dp

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
