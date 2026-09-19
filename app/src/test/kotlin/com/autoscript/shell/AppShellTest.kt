package com.autoscript.shell

import com.autoscript.appservice.scheduler.core.InMemoryIntentLog
import com.autoscript.appservice.scheduler.core.IntentLog
import com.autoscript.appservice.scheduler.core.ScheduledTask
import com.autoscript.appservice.scheduler.core.SchedulerProvider
import com.autoscript.appservice.scheduler.core.TimedSchedule
import com.autoscript.appservice.scheduler.core.TriggerHandle
import com.autoscript.appservice.scheduler.core.TriggerSource
import com.autoscript.domain.bridge.BridgeRequest
import com.autoscript.domain.bridge.BridgeResponse
import com.autoscript.domain.bridge.HandleRef
import com.autoscript.domain.engine.EngineId
import com.autoscript.domain.engine.EngineRunReceipt
import com.autoscript.domain.engine.EngineRunRequest
import com.autoscript.domain.engine.EngineStatus
import com.autoscript.domain.engine.KillCause
import com.autoscript.domain.engine.ScriptEngine
import com.autoscript.domain.engine.StopResult
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private val shellRunIds = AtomicLong(10_000)

private class ShellFakeEngine(override val id: EngineId) : ScriptEngine {
    val executed = mutableListOf<EngineRunRequest>()
    var statusToReturn: EngineStatus = EngineStatus.IDLE

    override suspend fun execute(run: EngineRunRequest): EngineRunReceipt {
        executed += run
        statusToReturn = EngineStatus.RUNNING
        val runId = shellRunIds.getAndIncrement()
        Thread({
            try {
                Thread.sleep(50)
            } catch (_: InterruptedException) {
                return@Thread
            }
            if (statusToReturn == EngineStatus.RUNNING) statusToReturn = EngineStatus.STOPPED
        }).also { it.isDaemon = true }.start()
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

private class ShellFakeProvider : SchedulerProvider {
    val registered = mutableListOf<String>()
    override suspend fun registerTrigger(targetFireAtMillis: Long, taskId: String): TriggerHandle {
        registered += taskId
        return TriggerHandle { }
    }

    override suspend fun cancelTrigger(handle: TriggerHandle) = Unit
}

class AppShellTest {

    private fun shell(log: IntentLog = InMemoryIntentLog()): Triple<AppShell, MutableList<ShellFakeEngine>, ShellFakeProvider> {
        val engines = mutableListOf<ShellFakeEngine>()
        val provider = ShellFakeProvider()
        val s = AppShell.assemble(
            engineFactory = { id -> ShellFakeEngine(id).also { engines += it } },
            schedulerProvider = provider,
            intentLog = log,
        )
        return Triple(s, engines, provider)
    }

    @Test
    fun `装配挂载 console 与 engines 命名空间`() = runBlocking {
        val (s, _, _) = shell()
        s.use {
            val logResp = s.router.dispatch(
                BridgeRequest(1, "console", "log", """{"level":"log","text":"hi"}""", 5_000),
            )
            assertInstanceOf(BridgeResponse.Ok::class.java, logResp)
            assertEquals(1, s.console.size())

            val execResp = s.router.dispatch(
                BridgeRequest(2, "engines", "exec", """{"projectId":"p1","scriptPath":"a.js"}""", 5_000),
            )
            val ok = assertInstanceOf(BridgeResponse.Ok::class.java, execResp)
            assertTrue(ok.payload!!.contains("runId"))
        }
    }

    @Test
    fun `调度经 dispatcher 落到 controller 并 COMMIT`() = runBlocking {
        val log = InMemoryIntentLog()
        val (s, engines, provider) = shell(log)
        s.use {
            val task = ScheduledTask(
                id = "t1",
                name = "demo",
                projectId = "p1",
                scriptPath = "a.js",
                schedule = TimedSchedule.Once(60),
            )
            s.scheduler.schedule(task)
            assertEquals(listOf("t1"), provider.registered)
            s.scheduler.onTrigger("t1", TriggerSource.USER_CLICK)
            assertEquals(1, engines.single().executed.size, "调度投递到引擎")
            val runs = log.all()
            assertEquals(1, runs.size)
            assertTrue(runs[0].outcome != null, "dispatcher 对偶后 scheduler 统一 COMMIT")
            assertEquals(log.all()[0].runNonce, engines.single().executed.single().runNonce)
        }
    }

    @Test
    fun `mount 薄转接自定义命名空间`() = runBlocking {
        val (s, _, _) = shell()
        s.use {
            assertTrue(s.mount("echo", com.autoscript.bridge.RequestHandler { r -> BridgeResponse.Ok(r.id, r.payload) }))
            val resp = s.router.dispatch(BridgeRequest(9, "echo", "ping", """"x"""", 5_000))
            assertEquals(""""x"""", (resp as BridgeResponse.Ok).payload)
        }
    }
}
