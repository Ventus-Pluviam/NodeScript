package com.autoscript.ui.state

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * [opToastMessage]/[stopToastMessage] 的判读规则（批 44 回执搬进浮层后独立出来的一层）。
 *
 * 守两件事：**文案逐字**（搬家不是改写 —— 与搬走前的 `FeedbackLine` 行一致）与
 * **优先级**（失败压回执、回执压挂起）。
 */
class OpToastTest {

    @Test
    fun `三态都空时不弹`() {
        assertNull(opToastMessage(error = null, notice = null, inFlight = false))
        assertNull(stopToastMessage(error = null, notice = null, inFlight = false))
    }

    @Test
    fun `失败带前缀并保留原文`() {
        assertEquals("操作失败：壳未装配", opToastMessage(error = "壳未装配", notice = null, inFlight = false))
        assertEquals("停止失败：任务不存在", stopToastMessage(error = "任务不存在", notice = null, inFlight = false))
    }

    @Test
    fun `回执原样弹`() {
        assertEquals("已新建文件「x.js」", opToastMessage(error = null, notice = "已新建文件「x.js」", inFlight = false))
    }

    @Test
    fun `挂起说一句在做什么`() {
        assertEquals("执行中…（挂起期间按钮停用）", opToastMessage(error = null, notice = null, inFlight = true))
        assertEquals("正在停止…（挂起期间按钮停用）", stopToastMessage(error = null, notice = null, inFlight = true))
    }

    @Test
    fun `同时非空时 失败压回执 回执压挂起`() {
        assertEquals("操作失败：x", opToastMessage(error = "x", notice = "已受理", inFlight = true))
        assertEquals("已受理", opToastMessage(error = null, notice = "已受理", inFlight = true))
    }
}
