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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.ui.components.ActionBar
import com.autoscript.ui.components.ContextMenu
import com.autoscript.ui.components.MenuAction
import com.autoscript.ui.components.MenuGap
import com.autoscript.ui.components.Glyph
import com.autoscript.ui.components.GlyphKind
import com.autoscript.ui.components.RefreshableBox
import com.autoscript.ui.components.ScrollToTopButton
import com.autoscript.ui.components.TabBarBottomClearance
import com.autoscript.ui.components.ToneText
import com.autoscript.ui.components.pressable
import com.autoscript.ui.components.rememberRefreshAction
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
 *   覆盖 [ActionBar] 缺省标题样式的屏），右上角 `⋮`（本屏菜单，缺省只有"刷新"）；
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
    onRefresh: suspend () -> Unit,
    /** 主题两态切换（⋮ 第一格，TG 日/夜同款 —— 目标模式写菜单项上）。 */
    onSwitchTheme: () -> Unit,
    /** 新建文件/文件夹（FAB 展开的两个子项；落盘在宿主，本屏只收结论）。 */
    onCreate: (projectId: String, name: String, isFolder: Boolean) -> Unit,
    /** 排序档/逆向变更（写入 state —— 重读不重置呈现偏好）。 */
    onSortChange: (FileSort, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 搜索词/排序/逆向都是本屏私有的现值：过滤与排序是呈现（"仅应用于此文件夹"），
    // 不是读取 —— 不进读口。排序档在 state 里（⋮ 菜单写入，重读不重置）。
    var query by remember { mutableStateOf("") }
    val refresh = rememberRefreshAction(onRefresh)
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // 新建落在第一个项目（文件树按项目分目录，根级不可写）—— 有文件取第一行的
    // 项目，没文件回缺省目录名（首装用户先建项目文件夹）。
    val targetProject = state.files.firstOrNull()?.projectId ?: "demo"

    // 创建对话框是本屏私有的现值（state.creating 记的是"操作面开着"这一事实，
    // 对话框里的输入框内容不值得进状态类 —— 关掉即丢）。
    var creatingKind by remember { mutableStateOf<ProjectState.CreationKind?>(null) }

    // 顶栏副标题只留给「没读到 / 读失败」两句（诚实边界照旧）；读到了顶栏就只有标题
    // —— TG 主页顶栏没有第二行，「共 N 个文件」的计数批 25 起不再上顶栏（列表空态
    // 有 EmptyFilesHint 居中那句，信息不丢）。副标题让位后 ⋮ 与标题同行对齐。
    val load = state.load
    val subtitle = when (load) {
        LoadState.Loaded -> null
        LoadState.NotLoaded -> "尚未读取（点右上「⋮」现取）"
        is LoadState.Failed -> load.reason
    }

    Column(modifier.fillMaxSize().background(ThemeColors.background)) {
        ActionBar(
            title = "NodeScript",
            titleStyle = TextStyle(
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = NodeScriptBrandColor,
            ),
            subtitle = subtitle,
            subtitleTone = if (load is LoadState.Failed) StatusTone.PROBLEM else StatusTone.MUTED,
            actions = {
                ProjectMenu(
                    currentSort = state.sort,
                    reversed = state.reversed,
                    onRefresh = { scope.launch { refresh.trigger() } },
                    onSwitchTheme = onSwitchTheme,
                    onSelectAll = { /* 多选操作随后续批次接（TG 全选后顶栏变批量条） */ },
                    onSort = { sort, reversed -> onSortChange(sort, reversed) },
                )
            },
        )
        SearchField(
            query = query,
            onChange = { query = it },
        )
        Box(Modifier.weight(1f)) {
            RefreshableBox(refresh, Modifier.fillMaxSize()) {
                val visible = ScriptFileRowUi.sorted(
                    files = state.files.filter { it.matches(query) },
                    sort = state.sort,
                    reversed = state.reversed,
                )
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(top = 4.dp, bottom = TabBarBottomClearance()),
                ) {
                    items(visible, key = { "${it.projectId}/${it.relPath}" }) { file ->
                        FileRow(file)
                    }
                    if (state.load.isLoaded && visible.isEmpty()) {
                        item { EmptyFilesHint(filtered = query.isNotBlank()) }
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
            CreateFab(
                onCreateFile = { creatingKind = ProjectState.CreationKind.FILE },
                onCreateFolder = { creatingKind = ProjectState.CreationKind.FOLDER },
                modifier = Modifier.align(Alignment.BottomEnd)
                    .padding(end = 20.dp, bottom = TabBarBottomClearance(extra = 8.dp)),
            )
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
    onRefresh: () -> Unit,
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
                add(MenuAction(label = "刷新", onClick = onRefresh))
                // —— 8dp 间隙（TG addGap：分组的"小小的间距"）——
                add(MenuGap)
                add(MenuAction(label = "日间/夜间模式", onClick = onSwitchTheme))
                add(MenuAction(label = "全选", onClick = onSelectAll))
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
 */
@Composable
private fun SearchField(
    query: String,
    onChange: (String) -> Unit,
) {
    val palette = ThemeColors
    TextField(
        value = query,
        onValueChange = onChange,
        singleLine = true,
        placeholder = {
            Text(
                text = "搜索文件",
                color = palette.text.copy(alpha = 0.5f),
                style = MaterialTheme.typography.bodyLarge,
            )
        },
        leadingIcon = {
            Glyph(kind = GlyphKind.SEARCH, tint = palette.text.copy(alpha = 0.6f))
        },
        shape = RoundedCornerShape(20.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = palette.fieldBackground,
            unfocusedContainerColor = palette.fieldBackground,
            disabledContainerColor = palette.fieldBackground,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            focusedTextColor = palette.text,
            unfocusedTextColor = palette.text,
            cursorColor = palette.accent,
        ),
        textStyle = MaterialTheme.typography.bodyLarge,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 7.dp, vertical = 4.dp)
            .height(48.dp),
    )
}

/**
 * 一行文件（TG 会话行 / `SharedDocumentCell` 的合体读法）：
 * 52dp 圆形头像 = 文件类型底色 + 白字扩展名缩写（无扩展名 = 首字母），右侧两行文字。
 * 文件夹行：头像 = 文件夹字形（accent 色底白线），次行 = "N 项 · 时刻"（TG 文件页
 * 文件夹行不显示字节数的同一口径）。
 */
@Composable
private fun FileRow(file: ScriptFileRowUi) {
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(role = Role.Button, onClick = { /* 文件动作（运行/查看）随后续批次接 */ }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(12.dp))
        if (file.isDirectory) {
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

/**
 * 文件夹头像（TG 文件页目录行的同一读法：目录不是"一种文件类型"，给它一个
 * 专属字形而不是哈希取色 —— 恒 accent 色底，一眼与文件行分开）。
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

/** 列表空态（TG "No entries here" 的分寸：居中、次级色）。 */
@Composable
private fun EmptyFilesHint(filtered: Boolean) {
    ToneText(
        text = if (filtered) "没有匹配「搜索词」的文件" else "还没有脚本文件",
        tone = StatusTone.MUTED,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
    )
}

/**
 * 右下角悬浮创建按钮（TG `FragmentFloatingButton` 的逐字版式）：
 * 48dp 圆（`SIZE = 48`）、右下边距 20/14dp（`createDefaultLayoutParams` 的 left/right 20、
 * bottom 14）、TG FAB 蓝（`key_featuredStickers_addButton` = `#229AF0` 的 TELEGRAM_COLOR）、
 * 白铅笔、按下缩放（`ScaleStateListAnimator` ≈ 0.92 缩回）。
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
        animationSpec = tween(durationMillis = 200),
        label = "fabRotate",
    )
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        // 子项自下而上展开（TG：子按钮浮在主按钮上方，逐颗滑出）。
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + scaleIn(initialScale = 0.6f),
            exit = fadeOut() + scaleOut(targetScale = 0.6f),
        ) {
            FabSubItem(label = "新建文件夹", glyph = GlyphKind.FOLDER, onClick = {
                expanded = false
                onCreateFolder()
            })
        }
        Spacer(Modifier.height(8.dp))
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + scaleIn(initialScale = 0.6f),
            exit = fadeOut() + scaleOut(targetScale = 0.6f),
        ) {
            FabSubItem(label = "新建文件", glyph = GlyphKind.FILE_DOC, onClick = {
                expanded = false
                onCreateFile()
            })
        }
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier
                .size(48.dp)
                .graphicsLayer { shape = CircleShape }
                .background(FabBlue, CircleShape)
                .pressable(role = Role.Button, onClick = { expanded = !expanded }),
            contentAlignment = Alignment.Center,
        ) {
            Glyph(
                kind = GlyphKind.PENCIL,
                tint = Color.White,
                size = 24.dp,
                modifier = Modifier.rotate(rotation),
            )
        }
    }
}

/** TG FAB 蓝（`TELEGRAM_COLOR = 0xFF229AF0`；`key_featuredStickers_addButton` 的缺省）。 */
private val FabBlue = Color(0xFF229AF0)

/**
 * FAB 展开的子项：与主钮同规格的 48dp 圆徽章（`createSubButtonLayoutParams` 的 48×48），
 * **灰底**（批 25 口径；TG 子按钮底是 `key_dialogBackground` 的模糊背板，语义位对上
 * 本仓搜索栏同一块灰 [com.autoscript.ui.theme.Colors.fieldBackground]）+ 0.4dp 细描边
 * （TG 子按钮同款一道边），图标 = 常规图标色字形（`key_actionBarDefaultIcon` 的语义位）。
 * TG 子按钮同样只有图标没有字 —— 文案进 contentDescription 给读屏。
 */
@Composable
private fun FabSubItem(label: String, glyph: GlyphKind, onClick: () -> Unit) {
    val palette = ThemeColors
    Box(
        Modifier
            .size(48.dp)
            .background(palette.fieldBackground, CircleShape)
            .border(0.4.dp, palette.divider, CircleShape)
            .pressable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Glyph(kind = glyph, tint = palette.text, size = 24.dp)
    }
}

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
