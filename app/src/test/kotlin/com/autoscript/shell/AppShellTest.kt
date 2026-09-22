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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private val shellRunIds = AtomicLong(10_000)

private class ShellFakeEngine(override val id: EngineId, override val pid: Int? = null) : ScriptEngine {
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
        return EngineRunReceipt(runId = runId, handle = HandleRef(refId = runId, generation = 1), pid = pid)
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

    private fun shell(
        log: IntentLog = InMemoryIntentLog(),
        heartbeatMillis: ((Long) -> Long?)? = null,
    ): Triple<AppShell, MutableList<ShellFakeEngine>, ShellFakeProvider> {
        val engines = mutableListOf<ShellFakeEngine>()
        val provider = ShellFakeProvider()
        val s = AppShell.assemble(
            engineFactory = { id -> ShellFakeEngine(id, pid = 4242).also { engines += it } },
            schedulerProvider = provider,
            intentLog = log,
            heartbeatMillis = heartbeatMillis,
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

        Unit  // 显式收尾：void 返回值才被 JUnit5 视为测试
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

        Unit  // 显式收尾：void 返回值才被 JUnit5 视为测试
    }

    @Test
    fun `装配交出看门狗实例但不自行轮转`() = runBlocking {
        val (s, _, _) = shell()
        s.use {
            // 生命周期归调用方（Application 的 SupervisorJob 域）：assemble 只交出实例
            assertFalse(s.watchdog.isRunning(), "assemble 不自行启动轮转")
            val tick = s.watchdog.tick()
            assertEquals(0, tick.sampled, "无在途 run：本轮不采样")
        }

        Unit  // 显式收尾：void 返回值才被 JUnit5 视为测试
    }

    @Test
    fun `心跳经桥打点后看门狗问得到，run 终结即遗忘`() = runBlocking {
        // 不显式指定 → 装配接上生产心跳账本（controller 持有的 HeartbeatLedger）
        val (s, _, _) = shell(heartbeatMillis = null)
        s.use {
            val resp = s.router.dispatch(
                BridgeRequest(1, "engines", "exec", """{"projectId":"p1","scriptPath":"a.js"}""", 5_000),
            )
            val ok = assertInstanceOf(BridgeResponse.Ok::class.java, resp)
            val runId = Regex("""runId"\s*:\s*(\d+)""").find(ok.payload!!)!!.groupValues[1].toLong()

            // 没打过点 → 看门狗如实记 noHeartbeat（不猜 0，也不拿轮转周期冒充心跳）
            assertTrue(s.watchdog.tick().noHeartbeat.contains(runId), "未打点：心跳一路不判死")

            // 打点（JS engines.heartbeat 的 Kotlin 落点）后同一轮就量得到
            val beat = s.router.dispatch(
                BridgeRequest(2, "engines", "heartbeat", """{"runId":$runId,"seq":1}""", 5_000),
            )
            assertEquals("true", (assertInstanceOf(BridgeResponse.Ok::class.java, beat)).payload)
            val tick = s.watchdog.tick()
            assertTrue(tick.noHeartbeat.isEmpty(), "打点后心跳一路已接线")
            assertTrue(tick.killed.isEmpty())

            s.controller.stop(runId)
            assertNull(s.controller.heartbeatMillis(runId), "run 终结即遗忘：不给复用 runId 留假年轻")
        }

        Unit  // 显式收尾：void 返回值才被 JUnit5 视为测试
    }

    @Test
    fun `看门狗监督经桥启动的在途 run`() = runBlocking {
        // 显式替身心跳来源（§8.4 的生产缺省是 controller 的 HeartbeatLedger）
        val (s, _, _) = shell(heartbeatMillis = { 100L })
        s.use {
            val resp = s.router.dispatch(
                BridgeRequest(1, "engines", "exec", """{"projectId":"p1","scriptPath":"a.js"}""", 5_000),
            )
            assertInstanceOf(BridgeResponse.Ok::class.java, resp)
            val tick = s.watchdog.tick()
            assertEquals(1, tick.sampled, "在途一个 run → 采一次")
            assertTrue(tick.killed.isEmpty(), "健康样本不杀")
            assertTrue(tick.noHeartbeat.isEmpty(), "心跳来源已注入")
        }

        Unit  // 显式收尾：void 返回值才被 JUnit5 视为测试
    }

    @Test
    fun `shutdown 先停调度再急停执行：投递停了，槽位还了`() = runBlocking {
        val log = InMemoryIntentLog()
        val (shell, engines, _) = shell(log)
        shell.use {
            shell.scheduler.schedule(
                ScheduledTask(
                    id = "t1",
                    name = "demo",
                    projectId = "p1",
                    scriptPath = "a.js",
                    schedule = TimedSchedule.Once(60),
                ),
            )
            shell.scheduler.onTrigger("t1", TriggerSource.USER_CLICK)
            assertEquals(1, engines.single().executed.size, "先有一次真实投递")

            val stopped = shell.shutdown(KillCause.REQUESTED)

            assertEquals(1, stopped.size, "调度侧停掉最近一次 run，句柄供归档")
            assertTrue(shell.scheduler.sinking, "调度已 sink：不再接收新投递")
            assertTrue(shell.controller.activeRunIds().isEmpty(), "执行侧在途清空")
            val stats = shell.controller.stats()
            assertEquals(stats.capacity, stats.free, "槽位 + 许可证成对归还")
        }

        Unit  // 显式收尾：void 返回值才被 JUnit5 视为测试
    }

    @Test
    fun `shutdown 无投递时如实空收口但仍清池`() = runBlocking {
        val (shell, _, _) = shell()
        shell.use {
            val stopped = shell.shutdown(KillCause.REQUESTED)

            assertTrue(stopped.isEmpty(), "无句柄：不假装停过")
            assertTrue(shell.scheduler.sinking, "sink 照常置位")
            assertTrue(shell.controller.activeRunIds().isEmpty())
        }

        Unit  // 显式收尾：void 返回值才被 JUnit5 视为测试
    }

    @Test
    fun `mount 薄转接自定义命名空间`() = runBlocking {
        val (s, _, _) = shell()
        s.use {
            assertTrue(s.mount("echo", com.autoscript.bridge.RequestHandler { r -> BridgeResponse.Ok(r.id, r.payload) }))
            val resp = s.router.dispatch(BridgeRequest(9, "echo", "ping", """"x"""", 5_000))
            assertEquals(""""x"""", (resp as BridgeResponse.Ok).payload)
        }

        Unit  // 显式收尾：void 返回值才被 JUnit5 视为测试
    }
}
