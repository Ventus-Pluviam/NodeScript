package com.autoscript.shell

import com.autoscript.appservice.scheduler.core.InMemoryIntentLog
import com.autoscript.appservice.scheduler.core.InMemoryRunArchive
import com.autoscript.appservice.scheduler.core.IntentLog
import com.autoscript.appservice.scheduler.core.SchedulerProvider
import com.autoscript.appservice.scheduler.core.TriggerHandle
import com.autoscript.appservice.scheduler.core.TriggerSource
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 开机恢复接线（§8.5 崩溃恢复的装配层接线点）：`AppShell.bootRecover` 是
 * `Scheduler.recoverUncommitted` 的生产调用方 —— 壳就绪后把意图日志里未 COMMIT 的
 * 遗留意向重新入队（经 dispatcher 真投递、落引擎 + 写归档），而不是让恢复逻辑
 * 永远只活在 scheduler 单测里。
 */
class AppShellBootRecoverTest {

    private class RecoverFakeProvider : SchedulerProvider {
        override suspend fun registerTrigger(targetFireAtMillis: Long, taskId: String): TriggerHandle =
            TriggerHandle { }

        override suspend fun cancelTrigger(handle: TriggerHandle) = Unit
    }

    private fun shell(log: IntentLog, archive: InMemoryRunArchive = InMemoryRunArchive()): AppShell =
        AppShell.assemble(
            engineFactory = { id -> FakeEngineForDispatcher(id, pid = 4242) },
            schedulerProvider = RecoverFakeProvider(),
            intentLog = log,
            runArchive = archive,
            heartbeatMillis = { 100L },
        )

    @Test
    fun `bootRecover 把崩溃遗留意向重新入队并落引擎`() = runBlocking {
        val log = InMemoryIntentLog()
        // 模拟崩溃遗留：RUN_START 已写但未 COMMIT（进程被杀时的样子）
        val now = System.currentTimeMillis()
        log.appendStart("p1", "a.js", "nonce-orphan", TriggerSource.TIMED, now)
        val s = shell(log)
        s.use {
            // 恢复前：遗留行躺在 uncommitted 里
            assertEquals(1, log.uncommitted().size)

            val recovered = it.bootRecover()

            assertEquals(1, recovered.size, "一条遗留 → 一条恢复记录")
            assertEquals("nonce-orphan", recovered.single().runNonce, "恢复保留原 nonce")
            assertTrue(log.uncommitted().isEmpty(), "恢复后无悬挂意向")
        }

        Unit  // 显式收尾：void 返回值才被 JUnit5 视为测试
    }

    @Test
    fun `bootRecover 无遗留时空恢复不投递`() = runBlocking {
        val log = InMemoryIntentLog()
        val s = shell(log)
        s.use {
            assertTrue(it.bootRecover().isEmpty(), "无遗留：空恢复")
            assertTrue(log.all().isEmpty(), "无遗留：日志不添行")
        }

        Unit
    }

    @Test
    fun `bootRecover 过期意向封账不重投`() = runBlocking {
        val log = InMemoryIntentLog()
        val past = System.currentTimeMillis() - 10 * 60 * 1000L
        // TIMED 排队上限 120s：10 分钟前的排期重启后已过期
        log.appendStart(
            "p1", "a.js", "nonce-stale", TriggerSource.TIMED, past,
            deadlineMillis = past + 120_000L,
        )
        val s = shell(log)
        s.use {
            val recovered = it.bootRecover()
            assertEquals(1, recovered.size)
            assertTrue(recovered.single().expired, "过期意向如实标 expired")
            assertTrue(log.uncommitted().isEmpty(), "过期同样封账，不悬挂")
        }

        Unit
    }

    @Test
    fun `恢复重投同样走归档：意图与引擎记录成对`() = runBlocking {
        val log = InMemoryIntentLog()
        val archive = InMemoryRunArchive()
        log.appendStart("p1", "a.js", "nonce-link", TriggerSource.USER_CLICK, System.currentTimeMillis())
        val s = shell(log, archive)
        s.use {
            val recovered = it.bootRecover()
            assertEquals(1, recovered.size)
            val intentRunId = recovered.single().newRunId
            assertEquals(1, archive.recordsOfIntent(intentRunId).size, "恢复重投同样归档成对")
        }

        Unit
    }
}
