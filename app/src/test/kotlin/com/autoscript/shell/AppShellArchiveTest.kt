package com.autoscript.shell

import com.autoscript.appservice.scheduler.core.InMemoryIntentLog
import com.autoscript.appservice.scheduler.core.InMemoryRunArchive
import com.autoscript.appservice.scheduler.core.ScheduledTask
import com.autoscript.appservice.scheduler.core.TimedSchedule
import com.autoscript.appservice.scheduler.core.TriggerSource
import com.autoscript.domain.bridge.HandleRef
import com.autoscript.domain.engine.EngineId
import com.autoscript.domain.engine.EngineRunReceipt
import com.autoscript.domain.engine.EngineRunRequest
import com.autoscript.domain.engine.EngineStatus
import com.autoscript.domain.engine.KillCause
import com.autoscript.domain.engine.ScriptEngine
import com.autoscript.domain.engine.StopResult
import com.autoscript.domain.scripts.RunArchive
import com.autoscript.domain.scripts.RunRecord
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private val archiveRunIds = AtomicLong(20_000)

private class ArchiveFakeEngine(override val id: EngineId, override val pid: Int? = null) : ScriptEngine {
    val executed = mutableListOf<EngineRunRequest>()
    var statusToReturn: EngineStatus = EngineStatus.IDLE

    override suspend fun execute(run: EngineRunRequest): EngineRunReceipt {
        executed += run
        statusToReturn = EngineStatus.RUNNING
        val runId = archiveRunIds.getAndIncrement()
        Thread({
            try {
                Thread.sleep(50)
            } catch (_: InterruptedException) {
                return@Thread
            }
            if (statusToReturn == EngineStatus.RUNNING) statusToReturn = EngineStatus.STOPPED
        }, "archive-fake-autoexit-$runId").also { it.isDaemon = true }.start()
        return EngineRunReceipt(runId = runId, handle = HandleRef(refId = runId, generation = 1))
    }

    override suspend fun stop(): StopResult {
        statusToReturn = EngineStatus.STOPPED
        return StopResult.Clean
    }

    override suspend fun kill(): KillCause {
        statusToReturn = EngineStatus.CRASHED
        return KillCause.REQUESTED
    }

    override suspend fun status(): EngineStatus = statusToReturn
}

private class ArchiveFakeProvider : com.autoscript.appservice.scheduler.core.SchedulerProvider {
    override suspend fun registerTrigger(
        targetFireAtMillis: Long,
        taskId: String,
    ): com.autoscript.appservice.scheduler.core.TriggerHandle =
        com.autoscript.appservice.scheduler.core.TriggerHandle { }

    override suspend fun cancelTrigger(
        handle: com.autoscript.appservice.scheduler.core.TriggerHandle,
    ) = handle.cancel()
}

/**
 * §8.5 归档闭环（:app 装配层验证）：一次真实的「调度 → dispatcher → 意图日志 + 引擎档案」跑完，
 * 两侧身份必须成对可追 —— 档案按 intent runId 反查得到引擎记录，且记录带同一 runNonce；
 * 反向（引擎记录 → 意图日志行）同样成立。
 */
class AppShellArchiveTest {

    @Test
    fun `调度投递后意图日志与引擎档案成对可追`() = runBlocking {
        val engines = mutableListOf<ArchiveFakeEngine>()
        val log = InMemoryIntentLog()
        val archive = InMemoryRunArchive()
        val shell = AppShell.assemble(
            engineFactory = { id -> ArchiveFakeEngine(id).also { engines += it } },
            schedulerProvider = ArchiveFakeProvider(),
            intentLog = log,
            runArchive = archive,
        )
        shell.use {
            it.scheduler.schedule(
                ScheduledTask("t1", "demo", "p1", "a.js", TimedSchedule.Once(60)),
            )
            it.scheduler.onTrigger("t1", TriggerSource.USER_CLICK)

            val intentRun = log.all().single()
            assertEquals(1, engines.single().executed.size, "调度经 dispatcher 落到引擎")

            // 正查：任务中心按 IntentRun 追溯引擎记录（§8.5 归档入口）
            val records = archive.recordsOfIntent(intentRun.runId)
            assertEquals(1, records.size, "意图 runId ↔ 引擎 RunRecord 成对")
            assertEquals(intentRun.runNonce, records.single().runNonce, "同一 nonce 幂等锚点可追")
            assertEquals("p1", records.single().projectId)
            assertEquals("a.js", records.single().scriptPath)
            assertTrue(records.single().id > 0, "engineRunId = EngineRunReceipt.runId")

            // 反查对称：引擎记录 → 意图日志行
            val link = archive.link(records.single().id)!!
            assertEquals(intentRun.runId, link.intentRunId)
        }
    }

    @Test
    fun `外壳暴露同一归档器：装配层可注入自定义实现`() = runBlocking {
        val custom = InMemoryRunArchive()
        val shell = AppShell.assemble(
            engineFactory = { ArchiveFakeEngine(it) },
            schedulerProvider = ArchiveFakeProvider(),
            intentLog = InMemoryIntentLog(),
            runArchive = custom,
        )
        assertTrue(shell.runArchive === custom, "AppShell 暴露装配时注入的归档器（UI 直读不绕行）")
        assertTrue(shell.runArchive is RunArchive)
        assertEquals(emptyList<RunRecord>(), shell.runArchive.recordsOfProject("x"))
    }
}
