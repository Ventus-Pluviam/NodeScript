package com.autoscript.platform.system

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * `clipboard` Android 实现的契约测试（docs §12.2；假 [AndroidClipboard.Ops]）。
 *
 * 守三件事（真实现是系统服务调用，只能编译不能跑 —— 语义全在这边）：
 * 1. **读侧原样透传**：ops 的 null 即系统的 null 答案（空剪贴板 / 后台受限），
 *    不编错误码、不拿空串冒充；
 * 2. **写侧无门禁**：无 canWrite 探针，空串原样下发 —— 读受限、写不受限；
 * 3. **空串是真值**：写空串 → ops 收到空串 → 读回空串，不与 null 混淆。
 */
class AndroidClipboardTest {

    private class FakeOps(
        var stored: String? = null,
    ) : AndroidClipboard.Ops {
        val written = mutableListOf<String>()
        override fun getText(): String? = stored
        override fun setText(text: String) {
            written += text
            stored = text
        }
    }

    private val ops = FakeOps()
    private val clipboard = AndroidClipboard(ops)

    @Test
    fun `读空原样 null——不编错误码`() {
        ops.stored = null
        assertNull(clipboard.getText(), "空剪贴板 = null（常态答案，不是错误）")
    }

    @Test
    fun `读写往返——空串是真值`() {
        clipboard.setText("")
        assertEquals(listOf(""), ops.written)
        assertEquals("", clipboard.getText(), "空串是真值，不是缺失")

        clipboard.setText("hello")
        assertEquals("hello", clipboard.getText())
    }

    @Test
    fun `写侧无门禁——原样下发不预检`() {
        // 本用例自包含（JUnit 每用例新实例，不依赖前两个用例的写入）。
        clipboard.setText("后台也能写")
        assertEquals(listOf("后台也能写"), ops.written)
        assertEquals("后台也能写", clipboard.getText())
    }
}
