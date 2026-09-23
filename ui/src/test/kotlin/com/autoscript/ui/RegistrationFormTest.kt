package com.autoscript.ui

import com.autoscript.domain.host.ScheduleSpec
import com.autoscript.domain.host.ScreenRequirement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 登记表单的解析（[RegistrationForm.toRegistration]）：文本 → `:domain` [com.autoscript.domain.host.TaskRegistration]。
 *
 * 分工钉死：本层只管**形状**（数字格填了什么、空格 trim），语义校验（空串/cron/越界）
 * 归 `:app` 的 `TaskCenterOps` —— 与桥侧同一套闸门。表单再抄一份规则必然漂移
 * （本仓库 `wire 形状漂移` 反复吃亏的形态），所以这里**不测**空串拒绝：
 * 那条断言属于 [TaskCenterOpsTest]。
 */
class RegistrationFormTest {

    @Test
    fun `once 解析 —— 延迟秒数进 ScheduleSpec`() {
        val reg = RegistrationForm(
            name = "  任务  ",
            projectId = " p1 ",
            scriptPath = " a.js ",
            once = true,
            delaySecondsText = " 90 ",
        ).toRegistration()
        assertEquals("任务", reg.name, "trim：表格里顺手打的空格不该变成「name 不得为空」")
        assertEquals("p1", reg.projectId)
        assertEquals("a.js", reg.scriptPath)
        assertEquals(ScheduleSpec.Once(90), reg.schedule)
        assertEquals(ScreenRequirement.ANY, reg.screen, "缺省屏幕契约")
        assertEquals(null, reg.id, "id 缺省 = 服务端分配")
        assertEquals(true, reg.enabled)
    }

    @Test
    fun `daily 解析 —— 时分进 ScheduleSpec`() {
        val reg = RegistrationForm(
            once = false,
            hourText = "7",
            minuteText = "5",
            screen = ScreenRequirement.SCREEN_ON,
        ).toRegistration()
        assertEquals(ScheduleSpec.Daily(7, 5), reg.schedule)
        assertEquals(ScreenRequirement.SCREEN_ON, reg.screen)
    }

    @Test
    fun `形状非法逐格拒绝 —— 消息点名格子`() {
        val badDelay = assertThrows(IllegalArgumentException::class.java) {
            RegistrationForm(once = true, delaySecondsText = "1.5").toRegistration()
        }
        assertTrue(badDelay.message!!.contains("延迟秒数"), "点名格子：${badDelay.message}")

        val badHour = assertThrows(IllegalArgumentException::class.java) {
            RegistrationForm(once = false, hourText = "九点", minuteText = "0").toRegistration()
        }
        assertTrue(badHour.message!!.contains("小时"), "点名格子：${badHour.message}")

        val badMinute = assertThrows(IllegalArgumentException::class.java) {
            RegistrationForm(once = false, hourText = "9", minuteText = "").toRegistration()
        }
        assertTrue(badMinute.message!!.contains("分钟"), "点名格子：${badMinute.message}")
    }

    @Test
    fun `缺省表单是空名 —— 误提交过不了 app 闸门`() {
        val reg = RegistrationForm().toRegistration()
        assertEquals("", reg.name, "默认全空起步：空表单提交被 TaskCenterOps 拒（防误建）")
        assertEquals(ScheduleSpec.Once(60), reg.schedule, "缺省 once 60s（字段可改）")
    }
}
