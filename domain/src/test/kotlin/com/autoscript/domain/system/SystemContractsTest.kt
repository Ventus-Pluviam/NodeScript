package com.autoscript.domain.system

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `dialogs` 契约锚定 + 校验测试（§9.4，`extras.ts` 的 Kotlin 对偶）——
 * 拆分：shell/device/floatingWindow 面随契约迁
 * `:platform:system`（`ShellContractsTest`/`DeviceContractsTest`/`FloatingWindowContractsTest`（D3 前是同名的 `SystemHostContractsTest`）），对话框面留 `:domain`
 * （`DialogHost` 是装配层 `PlatformWiring` 生产读面的反例，grep 判定见步骤 6 提交信息）。
 *
 * 守两件事：
 * 1. **形状冻结**：DTO 字段与 JS facade 的返回体逐字对齐（`{value,confirmed}`、
 *    下标取消 -1）—— 桥只透传，任何一侧改名都会在另一侧变成 `undefined`，故在此锚死；
 * 2. **非法即拒**：构造期 `require` 覆盖空 title / 空选项，handler 据此折叠
 *    ERR_INVALID_PARAM，绝不把垃圾发往平台层。
 */
class SystemContractsTest {

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
        Unit
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
        Unit
    }

    // ── dialogs 模式枚举面 ─────────────────────────────────────────

    @Test
    fun `DialogMode 枚举名与 JS facade 的字面量一致`() {
        // extras.ts: mode?: 'auto' | 'overlay' | 'notification'
        assertEquals(listOf("AUTO", "OVERLAY", "NOTIFICATION"), DialogMode.entries.map { it.name })
        Unit
    }
}
