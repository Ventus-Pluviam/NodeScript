package com.autoscript.shell

import com.autoscript.appservice.scheduler.core.RecoveryRecord
import com.autoscript.appservice.scheduler.core.RunOutcome
import com.autoscript.appservice.scheduler.core.ScheduledTask
import com.autoscript.appservice.scheduler.core.ScreenGuarantee
import com.autoscript.appservice.scheduler.core.TimedSchedule
import com.autoscript.domain.host.ScheduleSpec
import com.autoscript.domain.host.ScreenRequirement
import com.autoscript.domain.scripts.EngineRunLink
import com.autoscript.domain.scripts.RunRecord
import com.autoscript.domain.scripts.RunState
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 任务中心快照拼装（§8.6 排期 + §8.5 档案/恢复账）：任务中心那一屏的**全部**事实
 * 都出自这里，所以逐条钉死。
 *
 * 为什么值得测：这一屏的每一格错了都不会崩 —— 只会让用户看到一条任务"在正常排期"
 * （其实停用了/是算不出下一跳的 cron）、把上一进程没结算的档案当成"正在跑"、
 * 或把恢复失败显示成"恢复了 0 条"。三种都是静默地不对。
 */
class TaskCenterReadTest {

    private fun task(
        id: String = "t1",
        schedule: TimedSchedule = TimedSchedule.Daily(7, 5),
        screen: ScreenGuarantee = ScreenGuarantee.ANY,
        enabled: Boolean = true,
    ) = ScheduledTask(
        id = id, name = "任务$id", projectId = "p1", scriptPath = "a.js",
        schedule = schedule, screen = screen, enabled = enabled,
    )

    private fun record(
        id: Long = 11L,
        state: RunState = RunState.RUNNING,
        startedAtMillis: Long? = 1000L,
        finishedAtMillis: Long? = null,
    ) = RunRecord(
        id = id, projectId = "p1", scriptPath = "a.js", runNonce = "n$id",
        state = state, startedAtMillis = startedAtMillis, finishedAtMillis = finishedAtMillis,
    )

    private fun recoveryRecord(expired: Boolean) = RecoveryRecord(
        oldRunId = 1L, newRunId = 2L, runNonce = "n", outcome = RunOutcome.Cancelled,
        expired = expired,
    )

    @Test
    fun `排期三态一一映射 —— 不聚合不折算`() {
        assertEquals(ScheduleSpec.Once(90), TaskCenterRead.mapSchedule(TimedSchedule.Once(90)))
        assertEquals(ScheduleSpec.Daily(7, 5), TaskCenterRead.mapSchedule(TimedSchedule.Daily(7, 5)))
        val cron = "0 7 * * *"
        assertEquals(ScheduleSpec.Cron(cron), TaskCenterRead.mapSchedule(TimedSchedule.Cron(cron)))
    }

    /**
     * 逐名对表的守卫（[ScheduledTaskRow] KDoc 承诺过）：调度器将来在 `ScreenGuarantee`
     * 中间插一个值，这条测试立刻红 —— 而不是映射整体错位、UI 静默少一档后照样绿。
     */
    @Test
    fun `屏幕契约逐名对表 —— 调度器加值时这里先红`() {
        assertEquals(
            ScreenGuarantee.entries.map { it.name }.toSet(),
            ScreenRequirement.entries.map { it.name }.toSet(),
            "两边必须同名同集：新增一个 ScreenGuarantee 值就要同时进 domain 契约",
        )
        for (guarantee in ScreenGuarantee.entries) {
            assertEquals(guarantee.name, TaskCenterRead.mapScreen(guarantee).name)
        }
    }

    @Test
    fun `下一跳原样透传 —— 本层不重算`() = runBlocking {
        val snap = TaskCenterRead.snapshot(
            tasks = listOf(task()),
            nextFireAt = { 4242L },
            degradedTaskIds = emptySet(),
            runs = emptyList(),
        )
        assertEquals(
            4242L, snap.tasks.single().nextFireAtMillis,
            "调度数学的唯一出处是 TimedSchedule.nextFireAfter，这里只接结果",
        )
    }

    @Test
    fun `下一跳为 null 就是 null —— 不拿兜底时间填`() = runBlocking {
        val snap = TaskCenterRead.snapshot(
            tasks = listOf(task()),
            nextFireAt = { null }, // Cron 算不出 / 停用任务不问
            degradedTaskIds = emptySet(),
            runs = emptyList(),
        )
        assertNull(
            snap.tasks.single().nextFireAtMillis,
            "编一个 now+24h 就是把「不知道」说成「明天跑」",
        )
    }

    @Test
    fun `降级集只按 id 判 —— 不在集内就是没降级`() = runBlocking {
        val snap = TaskCenterRead.snapshot(
            tasks = listOf(task("t1"), task("t2")),
            nextFireAt = { null },
            degradedTaskIds = setOf("t2"),
            runs = emptyList(),
        )
        assertFalse(snap.tasks.first { it.id == "t1" }.degraded)
        assertTrue(snap.tasks.first { it.id == "t2" }.degraded, "降级是逐任务的事实，不整份快照共用一个标记")
    }

    @Test
    fun `停用任务留行且 enabled 如实`() = runBlocking {
        val snap = TaskCenterRead.snapshot(
            tasks = listOf(task(enabled = false)),
            nextFireAt = { null },
            degradedTaskIds = emptySet(),
            runs = emptyList(),
        )
        val row = snap.tasks.single()
        assertFalse(row.enabled, "停用任务留在列表里（用户要能看到它停了），enabled 如实")
        assertEquals("任务t1", row.name)
        assertEquals("a.js", row.scriptPath)
        assertEquals("p1", row.projectId)
    }

    @Test
    fun `双 id 关联读到就带上 读不到如实 null`() = runBlocking {
        val links = mapOf(11L to EngineRunLink(intentRunId = 7L, engineRunId = 11L))
        val snap = TaskCenterRead.snapshot(
            tasks = emptyList(),
            nextFireAt = { null },
            degradedTaskIds = emptySet(),
            runs = listOf(record(id = 11L), record(id = 12L)),
            linkOf = { links[it] },
        )
        assertEquals(7L, snap.runs.first { it.engineRunId == 11L }.intentRunId)
        assertNull(
            snap.runs.first { it.engineRunId == 12L }.intentRunId,
            "「读了没有」与独立执行都如实 null —— 不拿 runNonce 之类凑一个 id",
        )
    }

    @Test
    fun `不传读口时关联一律 null`() = runBlocking {
        val snap = TaskCenterRead.snapshot(
            tasks = emptyList(),
            nextFireAt = { null },
            degradedTaskIds = emptySet(),
            runs = listOf(record()),
        )
        assertNull(snap.runs.single().intentRunId)
    }

    @Test
    fun `执行行逐字段投影 —— 状态与起止时刻原样`() = runBlocking {
        val snap = TaskCenterRead.snapshot(
            tasks = emptyList(),
            nextFireAt = { null },
            degradedTaskIds = emptySet(),
            runs = listOf(
                record(state = RunState.SUCCEEDED, startedAtMillis = 1000L, finishedAtMillis = 2000L),
            ),
        )
        val run = snap.runs.single()
        assertEquals(RunState.SUCCEEDED, run.state, "状态直接复用 domain 的 RunState，呈现层不再造一套")
        assertEquals(1000L, run.startedAtMillis)
        assertEquals(2000L, run.finishedAtMillis)
        assertEquals("p1", run.projectId)
        assertEquals("a.js", run.scriptPath)
    }

    @Test
    fun `恢复账三笔分开 —— 重投数是减出来的不是数出来的`() {
        val ok = TaskCenterRead.recoveryRow(
            records = listOf(recoveryRecord(expired = false), recoveryRecord(expired = true), recoveryRecord(expired = false)),
            failure = null,
        )
        assertEquals(3, ok.total)
        assertEquals(1, ok.expired)
        assertEquals(2, ok.retried, "重投 = total - expired：过期的封了口没投出去")
        assertTrue(ok.ok)

        val failed = TaskCenterRead.recoveryRow(
            records = listOf(recoveryRecord(expired = false)),
            failure = IllegalStateException("恢复崩了"),
        )
        assertEquals("恢复崩了", failed.failureText, "失败原因原文是现场唯一线索")
        assertTrue(failed.failed)
        assertEquals(0, failed.retried, "失败时谁都没投出去 —— total-expired 会把失败说成成功")
    }

    @Test
    fun `恢复无 message 时退到类名 —— 不显示 null`() {
        val row = TaskCenterRead.recoveryRow(emptyList(), RuntimeException())
        assertEquals("RuntimeException", row.failureText, "failureText=null 会被渲染成「没失败」")
        assertTrue(row.failed)
    }

    @Test
    fun `恢复账为 null 就是本次没跑过 —— 不是零条`() = runBlocking {
        val snap = TaskCenterRead.snapshot(
            tasks = emptyList(),
            nextFireAt = { null },
            degradedTaskIds = emptySet(),
            runs = emptyList(),
            recovery = null,
        )
        assertNull(snap.recovery, "「还没跑过恢复」与「恢复了 0 条」是两件事")
    }

    @Test
    fun `恢复快照原样入账`() = runBlocking {
        val snap = TaskCenterRead.snapshot(
            tasks = emptyList(),
            nextFireAt = { null },
            degradedTaskIds = emptySet(),
            runs = emptyList(),
            recovery = RecoverySnapshot(
                records = listOf(recoveryRecord(expired = false), recoveryRecord(expired = true)),
                failure = null,
            ),
        )
        assertEquals(2, snap.recovery!!.total)
        assertEquals(1, snap.recovery!!.expired)
        assertEquals(1, snap.recovery!!.retried)
    }
}
