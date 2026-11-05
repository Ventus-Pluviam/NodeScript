package com.autoscript.shell

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [KeyStableHash] 的两条不变量：稳定（同一 taskId 永远同号，`FLAG_UPDATE_CURRENT`
 * 的替换语义靠它）与**用满 31 位正数空间**（掩码若退回 16 位，百级任务在生日界下
 * 就有数个百分点的撞号率 —— 撞号 = 两个任务共用一个闹钟，后 arm 的顶掉先 arm 的）。
 */
class KeyStableHashTest {

    @Test
    fun `同一 taskId 永远同一 requestCode`() {
        assertEquals(KeyStableHash.requestCode("daily-backup"), KeyStableHash.requestCode("daily-backup"))
        Unit
    }

    @Test
    fun `requestCode 用满 31 位正数空间`() {
        // "task-100".hashCode() = -411361879（高位非零）：钉死具体值，掩码改窄即红。
        assertEquals("task-100".hashCode() and 0x7FFFFFFF, KeyStableHash.requestCode("task-100"))
        assertEquals(1736121769, KeyStableHash.requestCode("task-100"))
        assertTrue(KeyStableHash.requestCode("task-100") > 0xFFFF, "高位被掩掉 = 16 位空间，百级任务即撞号")
        assertTrue(KeyStableHash.requestCode("any") >= 0, "requestCode 必须非负（负号位被掩掉）")
        Unit
    }
}
