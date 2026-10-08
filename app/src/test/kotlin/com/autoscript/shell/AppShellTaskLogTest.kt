package com.autoscript.shell

import com.autoscript.appservice.scheduler.core.ScheduledTask
import com.autoscript.appservice.scheduler.core.SchedulerProvider
import com.autoscript.appservice.scheduler.core.TimedSchedule
import com.autoscript.domain.bridge.HandleRef
import com.autoscript.domain.engine.EngineId
import com.autoscript.domain.engine.EngineRunReceipt
import com.autoscript.domain.engine.EngineRunRequest
import com.autoscript.domain.engine.EngineStatus
import com.autoscript.domain.engine.KillCause
import com.autoscript.domain.engine.RunSummary
import com.autoscript.domain.engine.ScriptEngine
import com.autoscript.domain.engine.StopResult
import com.autoscript.appservice.scheduler.core.TriggerHandle
import com.autoscript.domain.scripts.EngineRunLink
import com.autoscript.domain.scripts.InMemoryIntentStore
import com.autoscript.domain.scripts.RunRecord
import com.autoscript.domain.scripts.RunState
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong

/** 与 Application 同一路径，读取壳的持久档案，再次装配仍能回读诊断字段。 */
class AppShellTaskLogTest {
    @TempDir
    lateinit var dir: Path

    private fun kit(): AssembledShell = AppShellKit.assemble(
        filesDir = dir.resolve("files"),
        cacheDir = dir.resolve("cache"),
        intentStore = InMemoryIntentStore(),
        screenGate = ScreenGate.AllowAll,
        schedulerProvider = object : SchedulerProvider {
            override suspend fun registerTrigger(targetFireAtMillis: Long, taskId: String) = TriggerHandle { }
            override suspend fun cancelTrigger(handle: TriggerHandle) = handle.cancel()
        },
    )

    @Test
    fun `壳持有档案投影终态且重启回读完整诊断`() = runBlocking {
        val failed = RunRecord(41, "demo", "bad.js", "nonce41", RunState.CRASHED, 100, 200, 3, "stderr 原文")
        kit().use { assembled ->
            assembled.shell.runArchive.put(failed, EngineRunLink(11, 41))
            assembled.shell.runArchive.put(RunRecord(42, "demo", "other.js", "nonce42", RunState.RUNNING))
            assembled.shell.runArchive.put(RunRecord(43, "other", "main.js", "nonce43", RunState.SUCCEEDED, 300, 400))
            // 跨项目：demo 的 41 与 other 的 43 都在，未结算的 42 不在；最近结算的在前（同刻按 id 倒序）。
            assertEquals(listOf(43L, 41L), assembled.taskLog().runs.map { it.engineRunId })
        }
        kit().use { assembled ->
            val row = assembled.taskLog().runs.single { it.engineRunId == 41L }
            assertEquals(41L, row.engineRunId)
            assertEquals(11L, row.intentRunId)
            assertEquals(failed.state, row.state)
            assertEquals(3, row.exitCode)
            assertEquals("stderr 原文", row.crashSummary)
            assertEquals(100L, row.startedAtMillis)
            assertEquals(200L, row.finishedAtMillis)
        }
        Unit
    }

    @Test
    fun `调度执行与崩溃摘要经真实装配读口回读`() = runBlocking {
        val ids = AtomicLong(500L)
        AppShellKit.assemble(
            filesDir = dir.resolve("files"),
            cacheDir = dir.resolve("cache"),
            intentStore = InMemoryIntentStore(),
            screenGate = ScreenGate.AllowAll,
            schedulerProvider = object : SchedulerProvider {
                override suspend fun registerTrigger(targetFireAtMillis: Long, taskId: String) = TriggerHandle { }
                override suspend fun cancelTrigger(handle: TriggerHandle) = handle.cancel()
            },
            engineFactory = { id, _ -> HistoryEngine(id, ids) },
        ).use { assembled ->
            for ((taskId, path) in listOf("ok" to "ok.js", "bad" to "bad.js")) {
                assembled.shell.scheduler.schedule(ScheduledTask(taskId, taskId, "demo", path, TimedSchedule.Once(60)))
                assembled.runTaskNow(taskId)
            }
            val rows = assembled.taskLog().runs.associateBy { it.scriptPath }
            assertEquals(RunState.SUCCEEDED, rows.getValue("ok.js").state)
            val bad = rows.getValue("bad.js")
            assertEquals(RunState.CRASHED, bad.state)
            assertEquals(3, bad.exitCode)
            assertEquals("脚本抛错\n诊断原文", bad.crashSummary)
            assertTrue(bad.intentRunId != null)
        }
        kit().use { reopened ->
            val rows = reopened.taskLog().runs
            assertEquals(2, rows.size)
            assertEquals("脚本抛错\n诊断原文", rows.single { it.scriptPath == "bad.js" }.crashSummary)
        }
        Unit
    }

}


/** 无 OS pid、不借宿主 /proc；第一次采样保持 RUNNING，下一次自然结束。 */
private class HistoryEngine(override val id: EngineId, private val ids: AtomicLong) : ScriptEngine {
    override val pid: Int? = null
    private var crashed = false
    private var firstSample = true

    override suspend fun execute(run: EngineRunRequest): EngineRunReceipt {
        crashed = run.scriptPath == "bad.js"
        firstSample = true
        val runId = ids.getAndIncrement()
        return EngineRunReceipt(runId, HandleRef(runId, 1))
    }

    override suspend fun status(): EngineStatus = if (firstSample) {
        firstSample = false
        EngineStatus.RUNNING
    } else if (crashed) {
        EngineStatus.CRASHED
    } else {
        EngineStatus.STOPPED
    }

    override suspend fun lastRunSummary(): RunSummary? =
        if (crashed) RunSummary(3, "脚本抛错\n诊断原文") else null

    override suspend fun stop(): StopResult = StopResult.Clean
    override suspend fun kill(): KillCause = KillCause.REQUESTED
}
