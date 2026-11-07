package com.autoscript.ui.components

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * 文件类型头像的取色/取字形公式（任务中心那份旧拷贝 `TaskAvatar` 少了「+1」，
 * 同一个无扩展名脚本在项目页与任务中心取色不同 —— 归并后钉住唯一一份）。
 */
class FileTypeAvatarTest {

    @Test
    fun `取色槽位——同一扩展名恒同色且无扩展名退回名字首字符加一`() {
        // 扩展名首字符取模："js" → 'j'(106) % 8（槽数是主题表的长度，这里以 8 为例）。
        assertEquals(106 % 8, avatarColorSlot("js", "main", 8))
        // 同一扩展名换文件名不换色。
        assertEquals(avatarColorSlot("js", "a", 8), avatarColorSlot("js", "b", 8))
        // 无扩展名退回名字首字符 **+1**："deploy" → ('d'(100)+1) % 8 = 5。
        // 任务中心那份旧拷贝丢的就是这一笔（它算的是 100 % 8 = 4），同一个无扩展名
        // 脚本在项目页（+1）与任务中心（没 +1）取色不同。
        assertEquals((100 + 1) % 8, avatarColorSlot("", "deploy", 8))
        // 名字也没有 → 0 号槽；槽数非法也归 0（防除零）。
        assertEquals(0, avatarColorSlot("", "", 8))
        assertEquals(0, avatarColorSlot("js", "x", 0))
    }

    @Test
    fun `取字形——常见扩展名有专属字形其余画缩写字`() {
        assertEquals(GlyphKind.FILE_JS, avatarGlyph("js"))
        assertEquals(GlyphKind.FILE_JS, avatarGlyph("json"))
        assertEquals(GlyphKind.FILE_DOC, avatarGlyph("md"))
        assertEquals(GlyphKind.FILE_HTML, avatarGlyph("html"))
        assertEquals(GlyphKind.FILE_SH, avatarGlyph("sh"))
        assertNull(avatarGlyph("kts"))
        assertNull(avatarGlyph(""))
    }
}
