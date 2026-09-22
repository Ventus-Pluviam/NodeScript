package com.autoscript.shell

import com.autoscript.appservice.scheduler.core.RecoveryRecord
import com.autoscript.appservice.scheduler.core.RunOutcome
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 开机恢复验证（§8.5/§8.6，§19 缺口清单里"`recoverUncommitted` 接线点"的那一半）。
 *
 * 壳只恢复一次、失败不外抛、过期条目不过滤 —— 三条都是"错了不会崩、只会静默重复/静默丢失"
 * 的判断，所以必须单测钉住。真 [com.autoscript.appservice.scheduler.core.Scheduler] 的
 * reopen/不重投语义由 scheduler 侧自己的单测覆盖，这里只验**接线**。
 */
class BootRecoveryTest {

    /** 造一个不碰 Android 的壳（恢复只读 `shell.scheduler`，引擎永不起动）。 */
    private fun shell(): AppShell = AppShell.assemble(
        engineFactory = { id -> FakeEngineForDispatcher(id, autoExitAfterMillis = null) },
        schedulerProvider = object : com.autoscript.appservice.scheduler.core.SchedulerProvider {
            override suspend fun registerTrigger(targetFireAtMillis: Long, taskId: String) =
                com.autoscript.appservice.scheduler.core.TriggerHandle { }
            override suspend fun cancelTrigger(
                handle: com.autoscript.appservice.scheduler.core.TriggerHandle,
            ) = Unit
        },
        intentLog = com.autoscript.appservice.scheduler.core.InMemoryIntentLog(),
    )

    private fun recovery(
        oldRunId: Long,
        newRunId: Long,
        expired: Boolean = false,
    ) = RecoveryRecord(
        oldRunId = oldRunId,
        newRunId = newRunId,
        runNonce = "nonce-$oldRunId",
        outcome = if (expired) RunOutcome.Cancelled else RunOutcome.Succeeded,
        expired = expired,
    )

    @Test
    fun `同一壳只恢复一次 —— 重装壳才再跑`() = runBlocking {
        val shell = shell()
        var calls = 0
        val boot = BootRecovery(recover = { calls++; listOf(recovery(1, 2)) })

        val first = boot.recoverOnce(shell)
        val second = boot.recoverOnce(shell)
        val third = boot.recoverOnce(shell)

        assertEquals(1, calls, "重复 install 不得让同一批意向被 reopen 两次")
        assertEquals(1, first.total)
        assertEquals(1, second.total, "第二次拿到的是同一份结果，不是空")
        assertEquals(third.total, third.total)
    }

    @Test
    fun `换壳即重跑 —— 恢复窗口跟着新壳`() = runBlocking {
        var calls = 0
        val boot = BootRecovery(recover = { calls++; listOf(recovery(7, 8)) })

        boot.recoverOnce(shell())
        val again = boot.recoverOnce(shell())

        assertEquals(2, calls, "装的是另一个壳，就该为它再恢复一次")
        assertEquals(1, again.total)
        assertEquals(7L, again.records.single().oldRunId)
    }

    @Test
    fun `恢复失败不外抛 —— 装壳流程不能被它打断`() = runBlocking {
        val boot = BootRecovery(recover = { throw IllegalStateException("意图日志读不出来") })

        val snap = boot.recoverOnce(shell())   // 不抛

        assertTrue(snap.failure is IllegalStateException, "失败要记账（可查），不是吞掉")
        assertTrue(snap.describe().contains("恢复失败"), "失败要说出来：${snap.describe()}")
        assertEquals("恢复失败：IllegalStateException: 意图日志读不出来", snap.describe())
        assertEquals(0, snap.total, "失败的那一次没有造出恢复记录")
    }

    @Test
    fun `过期封口的条目不过滤 —— 任务中心要能读到为何没跑`() = runBlocking {
        val boot = BootRecovery(
            recover = {
                listOf(
                    recovery(1, 2, expired = false),
                    recovery(3, 4, expired = true),
                    recovery(5, 6, expired = true),
                )
            },
        )

        val snap = boot.recoverOnce(shell())

        assertEquals(3, snap.total, "过期未投的也算恢复账上的一行（§8.6 不静默跳过）")
        assertEquals(2, snap.expired)
        assertTrue(snap.ok)
        assertTrue(
            snap.describe().contains("其中 2 条已过约定时刻"),
            "文案要把两件事分开说：${snap.describe()}",
        )
    }

    @Test
    fun `没有未完成意向时的文案`() = runBlocking {
        val boot = BootRecovery(recover = { emptyList() })
        val snap = boot.recoverOnce(shell())
        assertEquals(0, snap.total)
        assertEquals(0, snap.expired)
        assertEquals("无可恢复的未完成意向", snap.describe())
    }

    @Test
    fun `全部过期时文案不说成成功重投`() = runBlocking {
        val boot = BootRecovery(recover = { listOf(recovery(1, 2, expired = true)) })
        val snap = boot.recoverOnce(shell())
        assertEquals(1, snap.total)
        assertEquals(1, snap.expired)
        assertEquals("恢复 1 条，全部已过约定时刻（封口不重投）", snap.describe())
    }
}
