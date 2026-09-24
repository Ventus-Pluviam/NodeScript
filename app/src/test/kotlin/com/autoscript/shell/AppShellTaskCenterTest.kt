package com.autoscript.shell

import com.autoscript.appservice.scheduler.core.RecoveryRecord
import com.autoscript.appservice.scheduler.core.RunOutcome
import com.autoscript.appservice.scheduler.core.ScheduledTask
import com.autoscript.appservice.scheduler.core.SchedulerProvider
import com.autoscript.appservice.scheduler.core.TimedSchedule
import com.autoscript.appservice.scheduler.core.TriggerHandle
import com.autoscript.domain.host.ScheduleSpec
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.ZoneId

/**
 * 任务中心读口的**装配侧**验证（[AppShellKit.AssembledShell.taskCenter]）——
 * 也就是 `AppShellApplication.taskCenter()` 在真机上会走的那条路。
 *
 * 单元级的映射规则在 [TaskCenterReadTest]；这里钉的是只有装配层才定的三件事：
 * 1. 取数来源是壳自己的寄存器（`scheduler.tasks()` + `archive`），停用任务**不问下一跳**；
 * 2. Cron 下一跳走调度数学（合法表达式算得出；不可能日期/坏行才如实 null，不兜底）；
 * 3. 恢复账由调用方经参数给入（`BootRecovery` 的账），没跑过就是 null。
 */
class AppShellTaskCenterTest {

    @TempDir
    lateinit var dir: Path

    private val files: Path get() = dir.resolve("files")
    private val cache: Path get() = dir.resolve("cache")

    private class RecordingProvider : SchedulerProvider {
        override suspend fun registerTrigger(targetFireAtMillis: Long, taskId: String): TriggerHandle =
            TriggerHandle { }

        override suspend fun cancelTrigger(handle: TriggerHandle) = handle.cancel()
    }

    private fun kit(): AppShellKit.AssembledShell = AppShellKit.assemble(
        filesDir = files,
        cacheDir = cache,
        schedulerProvider = RecordingProvider(),
        screenGate = ScreenGate.AllowAll,
    )

    @Test
    fun `已登记任务逐字段成行且停用的不给下一跳`() = runBlocking {
        val before = System.currentTimeMillis()
        val s = kit()
        s.use { assembled ->
            val scheduler = assembled.shell.scheduler
            scheduler.schedule(ScheduledTask("on", "开着的", "p1", "a.js", TimedSchedule.Once(60)))
            scheduler.schedule(
                ScheduledTask("off", "停用的", "p1", "b.js", TimedSchedule.Once(60), enabled = false),
            )
            val snap = assembled.taskCenter()
            val after = System.currentTimeMillis()
            assertEquals(2, snap.tasks.size, "停用任务留在列表里（用户要能看到它停了），不是从列表里消失")

            val on = snap.tasks.first { it.id == "on" }
            val off = snap.tasks.first { it.id == "off" }
            assertTrue(on.enabled)
            assertFalse(off.enabled)
            assertEquals(ScheduleSpec.Once(60), on.schedule)
            assertEquals("a.js", on.scriptPath)

            val next = on.nextFireAtMillis
            requireNotNull(next) { "已启用的 Once 必须算得出下一跳" }
            assertTrue(
                next >= before + 60_000L && next <= after + 60_000L,
                "下一跳 = 问的那一刻 + 60s（唯一时序来源是 nextFireAfter），实测 $next",
            )
            assertNull(
                off.nextFireAtMillis,
                "停用任务不问下一跳 —— 给了时间就是暗示它还会跑",
            )
            assertTrue(snap.runs.isEmpty(), "没投递过就没有未结算执行")
            assertNull(snap.recovery, "没传恢复参数 = 本次还没跑过恢复，不是「恢复了 0 条」")
        }
        Unit
    }

    @Test
    fun `Cron 合法算出下一跳 —— 不可能日期才如实 null`() = runBlocking {
        val s = kit()
        s.use { assembled ->
            // 周日 1970-01-11 之后最近的周一 09:00：合法 cron 与调度数学同值。
            val before = System.currentTimeMillis()
            assembled.shell.scheduler.schedule(
                ScheduledTask("c", "cron 任务", "p1", "a.js", TimedSchedule.Cron("0 9 * * 1"), timezone = ZoneId.of("UTC")),
            )
            val row = assembled.taskCenter().tasks.single()
            assertTrue(row.enabled)
            assertEquals(ScheduleSpec.Cron("0 9 * * 1"), row.schedule)
            val next = row.nextFireAtMillis
            requireNotNull(next) { "合法 cron 必须算得出下一跳" }
            assertTrue(next > before, "下一跳严格晚于登记时刻：$next")

            assembled.shell.scheduler.schedule(
                ScheduledTask("impossible", "二月三十", "p1", "b.js", TimedSchedule.Cron("0 0 30 2 *"), timezone = ZoneId.of("UTC")),
            )
            val rows = assembled.taskCenter().tasks.associateBy { it.id }
            assertNull(rows["impossible"]!!.nextFireAtMillis, "不可能日期：不编一个时间（留名不续排）")
            assertEquals(ScheduleSpec.Cron("0 0 30 2 *"), rows["impossible"]!!.schedule, "任务不因算不出下一跳就从列表消失")
        }
        Unit
    }

    @Test
    fun `恢复账经参数入快照 —— 失败原文与三笔数都在`() = runBlocking {
        val s = kit()
        s.use { assembled ->
            val snap = assembled.taskCenter {
                RecoverySnapshot(
                    records = listOf(
                        RecoveryRecord(1L, 2L, "n1", RunOutcome.Cancelled, expired = false),
                        RecoveryRecord(3L, 4L, "n2", RunOutcome.Cancelled, expired = true),
                    ),
                    failure = IllegalStateException("恢复崩了"),
                )
            }
            val row = requireNotNull(snap.recovery)
            assertEquals(2, row.total)
            assertEquals(1, row.expired)
            assertEquals("恢复崩了", row.failureText)
            assertTrue(row.failed)
            assertEquals(0, row.retried, "恢复失败时一条都没投出去，不能按 total-expired 报成功")
        }
        Unit
    }
}
