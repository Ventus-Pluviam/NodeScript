package com.autoscript.shell

import com.autoscript.appservice.scheduler.core.ScreenGuarantee
import com.autoscript.appservice.scheduler.core.TimedSchedule
import com.autoscript.domain.host.ScheduleSpec
import com.autoscript.domain.host.ScreenRequirement
import com.autoscript.domain.host.TaskRegistration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.ZoneId

/**
 * 登记闸门（[TaskCenterOps.toScheduledTask]）：`:domain` [TaskRegistration] → 调度器
 * `ScheduledTask` 的映射与校验。与桥侧 [WorkManagerNamespaceHandler] **同一套规则、
 * 两侧各测** —— 任何一侧单改（放行 cron / 放宽空串）另一侧先红。
 *
 * 为什么值得测：每一格错了都不会崩 —— 只会登记出一条用户以为在排期、实际不跑的
 * 任务（空 scriptPath / 负延迟 / 越界钟点 / 非法 cron 表达式）。全是静默地不对。
 * 不可能日期（如 2 月 30 号）是**合法**表达式：登记放行、排期回 null 留名不续排，
 * 与停用任务同一诚实口径 —— 本测试钉住这条线。
 */
class TaskCenterOpsTest {

    private fun reg(
        name: String = "任务",
        projectId: String = "p1",
        scriptPath: String = "a.js",
        schedule: ScheduleSpec = ScheduleSpec.Daily(7, 5),
        screen: ScreenRequirement = ScreenRequirement.ANY,
        args: List<String> = emptyList(),
        scriptTimeoutMillis: Long? = null,
        timezoneId: String? = null,
        enabled: Boolean = true,
        id: String? = null,
    ) = TaskRegistration(
        name = name, projectId = projectId, scriptPath = scriptPath,
        schedule = schedule, screen = screen, args = args,
        scriptTimeoutMillis = scriptTimeoutMillis, timezoneId = timezoneId,
        enabled = enabled, id = id,
    )

    @Test
    fun `合法登记逐字段成任务 —— 不吞不改`() {
        val task = TaskCenterOps.toScheduledTask(
            reg(
                schedule = ScheduleSpec.Once(90),
                screen = ScreenRequirement.SCREEN_ON,
                args = listOf("x", "y"),
                scriptTimeoutMillis = 5_000L,
                timezoneId = "Asia/Shanghai",
                enabled = false,
                id = "fixed",
            ),
        )
        assertEquals("fixed", task.id)
        assertEquals("任务", task.name)
        assertEquals("p1", task.projectId)
        assertEquals("a.js", task.scriptPath)
        assertEquals(TimedSchedule.Once(90), task.schedule)
        assertEquals(ScreenGuarantee.SCREEN_ON, task.screen, "按名对表，不用 ordinal")
        assertEquals(listOf("x", "y"), task.args)
        assertEquals(5_000L, task.scriptTimeoutMillis)
        assertEquals(ZoneId.of("Asia/Shanghai"), task.timezone)
        assertEquals(false, task.enabled)
    }

    @Test
    fun `id 空缺或空白时服务端分配 —— 不留空串 id`() {
        val blank = TaskCenterOps.toScheduledTask(reg(id = "  "))
        assertTrue(blank.id.isNotBlank(), "空白 id 当没给：${blank.id}")
        val a = TaskCenterOps.toScheduledTask(reg(id = null))
        val b = TaskCenterOps.toScheduledTask(reg(id = null))
        assertTrue(a.id.isNotBlank(), "缺省 id 服务端分配（与桥侧 create 同一口径）")
        assertTrue(a.id != b.id, "两次分配是两个任务（UUID，不复用）")
    }

    @Test
    fun `空串三字段逐个拒绝 —— 消息点名字段`() {
        for ((field, bad) in listOf(
            "name" to reg(name = " "),
            "projectId" to reg(projectId = ""),
            "scriptPath" to reg(scriptPath = "  "),
        )) {
            val e = assertThrows(IllegalArgumentException::class.java) {
                TaskCenterOps.toScheduledTask(bad)
            }
            assertTrue(
                e.message!!.contains(field),
                "$field 空串要被拒且消息点名：${e.message}",
            )
        }
    }

    @Test
    fun `cron 合法放行 —— 表达式 trim 后进调度器`() {
        val task = TaskCenterOps.toScheduledTask(reg(schedule = ScheduleSpec.Cron("  0 7 * * 1  ")))
        assertEquals(TimedSchedule.Cron("0 7 * * 1"), task.schedule)
    }

    @Test
    fun `cron 非法拒绝 —— 消息点名哪一段`() {
        for (bad in listOf("61 9 * * *", "0 9 * *", "0 9 * * FOO", "0 24 * * *", "")) {
            val e = assertThrows(IllegalArgumentException::class.java) {
                TaskCenterOps.toScheduledTask(reg(schedule = ScheduleSpec.Cron(bad)))
            }
            assertTrue(e.message!!.contains("cron"), "「$bad」消息点名 cron：${e.message}")
        }
    }

    @Test
    fun `cron 不可能日期是合法表达式 —— 登记放行，下一跳留名不续排`() {
        // 2 月没有 30 号：CronTab.parse 放行（形状合法），nextFireAfter 回 null。
        val task = TaskCenterOps.toScheduledTask(reg(schedule = ScheduleSpec.Cron("0 0 30 2 *")))
        assertEquals(TimedSchedule.Cron("0 0 30 2 *"), task.schedule, "登记放行：不是形状错")
        assertEquals(
            null, task.schedule.nextFireAfter(1_000_000L, ZoneId.systemDefault()),
            "排期算不出：rearmFor 按留名不续排处理（与停用任务同一口径）",
        )
    }

    @Test
    fun `cron 与桥侧同口径 —— 两侧非法样本一致拒绝`() {
        // 与 WorkManagerNamespaceHandlerTest 的 cron 样本同源：任一侧单改先红。
        for (bad in listOf("61 9 * * *", "0 9 * * FOO")) {
            assertThrows(IllegalArgumentException::class.java) {
                TaskCenterOps.toScheduledTask(reg(schedule = ScheduleSpec.Cron(bad)))
            }
        }
    }

    @Test
    fun `once 负延迟与 daily 越界逐个拒绝 —— 与桥侧同口径`() {
        val neg = assertThrows(IllegalArgumentException::class.java) {
            TaskCenterOps.toScheduledTask(reg(schedule = ScheduleSpec.Once(-1)))
        }
        assertTrue(neg.message!!.contains("delaySeconds"), "负延迟消息：${neg.message}")

        val hour = assertThrows(IllegalArgumentException::class.java) {
            TaskCenterOps.toScheduledTask(reg(schedule = ScheduleSpec.Daily(99, 0)))
        }
        assertTrue(hour.message!!.contains("hourOfDay"), "越界钟点消息：${hour.message}")

        assertThrows(IllegalArgumentException::class.java) {
            TaskCenterOps.toScheduledTask(reg(schedule = ScheduleSpec.Daily(0, 60)))
        }
    }

    @Test
    fun `timeout 非正与非法时区拒绝 —— 缺省走系统时区`() {
        assertThrows(IllegalArgumentException::class.java) {
            TaskCenterOps.toScheduledTask(reg(scriptTimeoutMillis = 0L))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TaskCenterOps.toScheduledTask(reg(scriptTimeoutMillis = -5L))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TaskCenterOps.toScheduledTask(reg(timezoneId = "not/a/zone"))
        }
        val dflt = TaskCenterOps.toScheduledTask(reg())
        assertEquals(ZoneId.systemDefault(), dflt.timezone, "缺省 = 系统时区（与桥侧一致）")
        assertEquals(null, dflt.scriptTimeoutMillis, "缺省不编超时")
    }

    /**
     * 逐名对表的反向守卫（[TaskCenterReadTest] 有正向那半）：`:domain` 加一个
     * `ScreenRequirement` 值而调度器没跟上，这条先红 —— 映射不整体错位。
     */
    @Test
    fun `屏幕契约逐名对表 —— domain 加值时这里先红`() {
        assertEquals(
            ScreenGuarantee.entries.map { it.name }.toSet(),
            ScreenRequirement.entries.map { it.name }.toSet(),
            "两边必须同名同集：新增 ScreenRequirement 值就要同时进调度器契约",
        )
        for (screen in ScreenRequirement.entries) {
            assertEquals(screen.name, TaskCenterOps.toScheduledTask(reg(screen = screen)).screen.name)
        }
    }
}
