package com.autoscript.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.ui.components.ActionBar
import com.autoscript.ui.components.ActionBarAction
import com.autoscript.ui.components.Glyph
import com.autoscript.ui.components.GlyphKind
import com.autoscript.ui.components.RefreshableBox
import com.autoscript.ui.components.ScrollToTopButton
import com.autoscript.ui.components.TabBarBottomClearance
import com.autoscript.ui.components.ToneText
import com.autoscript.ui.components.pressable
import com.autoscript.ui.components.rememberRefreshAction
import com.autoscript.ui.state.LoadState
import com.autoscript.ui.state.ProjectState
import com.autoscript.ui.state.ScriptFileRowUi
import com.autoscript.ui.state.Status
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
    modifier: Modifier = Modifier,
) {
    // 搜索词是本屏私有的现值：过滤是呈现，不是读取 —— 不进 ProjectState。
    var query by remember { mutableStateOf("") }
    val refresh = rememberRefreshAction(onRefresh)
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val status = Status.count(
        load = state.load,
        notLoadedText = "尚未读取（点右上「⋮」现取）",
        total = state.files.size,
        emptyText = "读到了，还没有脚本文件（files/scripts/ 为空）",
        unit = "个文件",
    )

    Column(modifier.fillMaxSize().background(ThemeColors.background)) {
        ActionBar(
            title = "NodeScript",
            titleStyle = TextStyle(
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = NodeScriptBrandColor,
            ),
            subtitle = status.text,
            subtitleTone = status.tone,
            actions = { ProjectMenu { scope.launch { refresh.trigger() } } },
        )
        SearchField(
            query = query,
            onChange = { query = it },
        )
        Box(Modifier.weight(1f)) {
            RefreshableBox(refresh, Modifier.fillMaxSize()) {
                val visible = state.files.filter { it.matches(query) }
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
                    .padding(start = 16.dp, end = 16.dp, bottom = TabBarBottomClearance(extra = 8.dp)),
            )
        }
    }
}

/** 主页品牌蓝（TG `key_telegram_color_dialogsLogo` 默认值 #168BDB；深浅主题同值）。 */
private val NodeScriptBrandColor = Color(0xFF168BDB)

/**
 * 主屏的 `⋮` 菜单（TG `DialogsActivity` 顶栏右侧三个点的对应位）。
 * 现在只有「刷新」一项 —— 文件操作（查看/运行/删除）随各自批次进这里或长按菜单。
 */
@Composable
private fun ProjectMenu(onRefresh: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Text(
            text = "⋮",
            color = ThemeColors.text,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier
                .pressable(role = Role.Button, onClick = { open = true })
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
        com.autoscript.ui.components.ContextMenu(
            expanded = open,
            onDismiss = { open = false },
            actions = listOf(
                com.autoscript.ui.components.MenuAction(label = "刷新", onClick = onRefresh),
            ),
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
        FileTypeAvatar(ext = file.ext, name = file.name)
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
            .graphicsLayer { shape = androidx.compose.foundation.shape.CircleShape }
            .background(bg, androidx.compose.foundation.shape.CircleShape),
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
