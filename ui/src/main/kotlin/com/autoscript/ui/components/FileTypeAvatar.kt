package com.autoscript.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.ui.theme.ThemeColors

/**
 * 文件类型头像（TG `AvatarDrawable` 的取色 + `SharedDocumentCell.extTextView` 的白字）：
 * 底色 = 扩展名哈希取色表槽位（同一扩展名恒同色），字 = 扩展名（最多 4 字符），
 * JS/HTML/SH/MD 等常见类型换专属字形（白线画在色底上）。
 *
 * 2026-10-13 自 `ProjectScreen` 上提为共享组件（原先 private 钉死 52dp，
 * 任务中心抄了第二份 44dp 的 `TaskAvatar` —— 两份必然漂）。调用方只给尺寸，
 * 图标/字号按比例派生（52dp 档 = 项目页文件行，44dp 档 = TG 联系人行/日志列表行）。
 */
@Composable
fun FileTypeAvatar(
    ext: String,
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
) {
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
    val ratio = size / 52.dp
    Box(
        modifier
            .size(size)
            .graphicsLayer { shape = CircleShape }
            .background(bg, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (knownGlyph != null) {
            // 常见类型：白线字形（比缩写字更一眼可辨 —— TG 文件页对图片/视频也是图标优先）。
            Glyph(kind = knownGlyph, tint = Color.White, size = 26.dp * ratio, weight = 1.15f)
        } else {
            Text(
                text = ext.take(4).ifEmpty { "?" },
                color = Color.White,
                style = TextStyle(fontSize = 14.sp * ratio, fontWeight = FontWeight.Bold),
            )
        }
    }
}
