package com.autoscript.domain.system

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 契约锚定 + 校验测试（§9.4/§9.6，`extras.ts` 的 Kotlin 对偶）。
 *
 * 守两件事：
 * 1. **形状冻结**：DTO 字段与 JS facade 的返回体逐字对齐（`code/stdout/stderr`、
 *    `{value,confirmed}`、下标取消 -1）—— 桥只透传，任何一侧改名都会在另一侧变成
 *    `undefined`，故在此锚死；
 * 2. **非法即拒**：构造期 `require` 覆盖空 title / 空选项 / 负尺寸 / SDK 越界，
 *    handler 据此折叠 ERR_INVALID_PARAM，绝不把垃圾发往平台层。
 */
class SystemContractsTest {

    // ── shell ──────────────────────────────────────────────────────

    @Test
    fun `ShellResult 按退出码判成败，空流与空串是两回事`() {
        assertTrue(ShellResult(0, "", "").isSuccess)
        assertFalse(ShellResult(1, null, "boom").isSuccess)
        // null = 该流没产出（JS 侧 `stdout ?? null` 语义）；空串 = 有输出但为空。
        assertNull(ShellResult(0, null, null).stdout)
        assertEquals("", ShellResult(0, "", "").stdout)
    }

    // ── device ─────────────────────────────────────────────────────

    @Test
    fun `DeviceProfile 拒绝空型号与非法 SDK`() {
        assertThrows(IllegalArgumentException::class.java) { DeviceProfile(model = "", sdkInt = 34) }
        assertThrows(IllegalArgumentException::class.java) { DeviceProfile(model = "Pixel", sdkInt = 0) }
        assertEquals(34, DeviceProfile("Pixel 8", 34).sdkInt)
    }

    // ── dialogs ────────────────────────────────────────────────────

    @Test
    fun `对话框请求拒绝空标题与空选项`() {
        assertThrows(IllegalArgumentException::class.java) {
            DialogPromptRequest(title = "  ", placeholder = null, mode = DialogMode.AUTO)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DialogChooseRequest(title = "选一个", options = emptyList(), mode = DialogMode.AUTO)
        }
        // placeholder 可空（JS facade 显式传 null）
        assertEquals(null, DialogPromptRequest("名字", null, DialogMode.AUTO).placeholder)
    }

    @Test
    fun `取消语义在 Kotlin 与 JS 两侧逐字一致`() {
        // prompt：取消 = value null + confirmed false（JS `{value:null,confirmed:false}`）
        assertNull(DialogOutcome.CANCELLED.value)
        assertFalse(DialogOutcome.CANCELLED.confirmed)
        // choose：取消 = 下标 -1（JS facade `?? -1`）
        assertEquals(-1, DialogChoice.CANCELLED.index)
        assertTrue(DialogChoice.CANCELLED.isCancelled)
        assertFalse(DialogChoice(2).isCancelled)
    }

    // ── floatingWindow ─────────────────────────────────────────────

    @Test
    fun `悬浮窗尺寸拒绝非正值，null 即 wrap content`() {
        assertThrows(IllegalArgumentException::class.java) { FloatingWindowSpec("t", -1, 100) }
        assertThrows(IllegalArgumentException::class.java) { FloatingWindowSpec("t", 0, 200) }
        assertThrows(IllegalArgumentException::class.java) { FloatingWindowSpec("t", 200, 0) }
        // null = wrap content（JS facade 不传 width/height 时的形态）；标题可空
        assertNull(FloatingWindowSpec.DEFAULT.title)
        assertNull(FloatingWindowSpec.DEFAULT.width)
        assertNull(FloatingWindowSpec.DEFAULT.height)
        // 单边固定、另一边 wrap content 合法（§9.4 常见形态）
        assertEquals(200, FloatingWindowSpec("t", 200, null).width)
    }

    // ── 模式/通道枚举面 ────────────────────────────────────────────

    @Test
    fun `模式与通道枚举名与 JS facade 的字面量一致`() {
        // extras.ts: mode?: 'auto' | 'overlay' | 'notification'
        assertEquals(listOf("AUTO", "OVERLAY", "NOTIFICATION"), DialogMode.entries.map { it.name })
        // extras.ts 只有 exec（DEFAULT）与 shell(同一路径)；ROOT/ADB 是宿主侧扩展
        assertEquals(listOf("DEFAULT", "ROOT", "ADB"), ShellMode.entries.map { it.name })
    }
}
