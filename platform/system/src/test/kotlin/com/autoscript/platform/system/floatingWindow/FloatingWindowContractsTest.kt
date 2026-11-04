package com.autoscript.platform.system.floatingWindow

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * `floatingWindow` 契约（尺寸非正值拒收、null 即 wrap content）
 *
 * 形状冻结：DTO 字段与 JS facade（`extras.ts`）的返回体逐字对齐，桥只透传 ——
 * 任何一侧改名都会在另一侧变成 `undefined`，故在此锚死。非法即拒：构造期
 * `require` 的边界在下方逐条钉住，handler 据此折叠 ERR_INVALID_PARAM。
 *
 * **来历**：原 `SystemHostContractsTest`（shell/device/floatingWindow 三面合一份，
 * 自 `:domain` `SystemContractsTest` 拆入本模块）在
 * 按命名空间子包拆开，跟各自契约同包。
 */
class FloatingWindowContractsTest {

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
        Unit
    }
}
