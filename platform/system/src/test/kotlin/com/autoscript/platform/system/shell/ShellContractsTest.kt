package com.autoscript.platform.system.shell

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `shell` 契约（`ShellResult` 成败判据 + `ShellMode` 字面量）
 *
 * 形状冻结：DTO 字段与 JS facade（`extras.ts`）的返回体逐字对齐，桥只透传 ——
 * 任何一侧改名都会在另一侧变成 `undefined`，故在此锚死。非法即拒：构造期
 * `require` 的边界在下方逐条钉住，handler 据此折叠 ERR_INVALID_PARAM。
 *
 * **来历**：原 `SystemHostContractsTest`（shell/device/floatingWindow 三面合一份，
 * 自 `:domain` `SystemContractsTest` 拆入本模块）在
 * 按命名空间子包拆开，跟各自契约同包。
 */
class ShellContractsTest {

    // ── shell ──────────────────────────────────────────────────────

    @Test
    fun `ShellResult 按退出码判成败，空流与空串是两回事`() {
        assertTrue(ShellResult(0, "", "").isSuccess)
        assertFalse(ShellResult(1, null, "boom").isSuccess)
        // null = 该流没产出（JS 侧 `stdout ?? null` 语义）；空串 = 有输出但为空。
        assertNull(ShellResult(0, null, null).stdout)
        assertEquals("", ShellResult(0, "", "").stdout)
        Unit
    }

    // ── shell 模式枚举面 ───────────────────────────────────────────

    @Test
    fun `ShellMode 枚举名与 JS facade 的字面量一致`() {
        // extras.ts 只有 exec（DEFAULT）与 shell(同一路径)；ROOT/ADB 是宿主侧扩展
        assertEquals(listOf("DEFAULT", "ROOT", "ADB"), ShellMode.entries.map { it.name })
        Unit
    }
}
