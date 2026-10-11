package com.autoscript.shell

import com.autoscript.appservice.scheduler.core.InMemoryIntentLog
import com.autoscript.appservice.scheduler.core.IntentLog
import com.autoscript.domain.scripts.ScheduledTask
import com.autoscript.appservice.scheduler.core.SchedulerProvider
import com.autoscript.domain.scripts.TimedSchedule
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
import com.autoscript.domain.bridge.AuthenticatedRunContext
import com.autoscript.domain.permission.BridgeCapability
import com.autoscript.domain.permission.CapabilityMask
import com.autoscript.domain.permission.ScriptAuthorizationPolicy
import com.autoscript.domain.permission.TrustTier
import com.autoscript.domain.permission.TrustTierMasks
import com.autoscript.domain.permission.TrustTierResolver
import kotlinx.coroutines.withContext
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
            engineFactory = { id, _ -> ShellFakeEngine(id, pid = 4242).also { engines += it } },
            schedulerProvider = provider,
            intentLog = log,
            heartbeatMillis = heartbeatMillis,
            monitor = fakeProcMonitor(),
        )
        return Triple(s, engines, provider)
    }

    @Test
    fun `装配挂载 console 与 engines 命名空间`() = runBlocking(AuthenticatedRunContext(EngineId(0), 42, 1, CapabilityMask.ALL)) {
        val (s, _, _) = shell()
        s.use {
            val logResp = s.router.dispatch(
                BridgeRequest(1, "console", "log", """{"level":"log","text":"hi"}""", 5_000),
            )
            assertInstanceOf(BridgeResponse.Ok::class.java, logResp)
            assertEquals(1, s.console.size())

            val execResp = s.router.dispatch(
                BridgeRequest(2, "engines", "exec", """{"projectId":"p1","scriptPath":"a.js","timeoutMillis":60000}""", 5_000),
            )
            val ok = assertInstanceOf(BridgeResponse.Ok::class.java, execResp)
            assertTrue(ok.payload!!.contains("runId"))
        }

        Unit  // 显式收尾：void 返回值才被 JUnit5 视为测试
    }

    @Test
    fun `按项目来源分级——同一壳里不同项目拿到不同掩码`() = runBlocking {
        // 这条是「authorization 是注入缝、不是全局开关」的**唯一**证据：
        // 同一个壳、同一次装配，两个 projectId 拿到不同掩码。
        // 若哪天有人把策略退回成"三个全局布尔"或"装配内部 new 一份"，
        // 注入的 resolver 就进不来 —— 这条会红。
        val engines = mutableListOf<ShellFakeEngine>()
        val policy = ScriptAuthorizationPolicy(
            resolver = TrustTierResolver { projectId ->
                // 只有内置项目有来源证据；其余项目"查不到" → 走 UNKNOWN 档。
                if (projectId == "builtin-app") TrustTier.BUILT_IN else null
            },
        )
        val s = AppShell.assemble(
            engineFactory = { id, _ -> ShellFakeEngine(id, pid = 4242).also { engines += it } },
            schedulerProvider = ShellFakeProvider(),
            intentLog = InMemoryIntentLog(),
            monitor = fakeProcMonitor(),
            poolCapacity = 2,
            authorization = policy,
        )
        s.use {
            for ((reqId, projectId) in listOf(1L to "builtin-app", 2L to "other-app")) {
                val resp = s.router.dispatch(
                    BridgeRequest(
                        reqId, "engines", "exec",
                        """{"projectId":"$projectId","scriptPath":"a.js","timeoutMillis":60000}""",
                        5_000,
                    ),
                )
                assertInstanceOf(BridgeResponse.Ok::class.java, resp)
            }
            // 走到引擎请求的那份快照 = 会交给身份签发的那一份（同一实例，见 AppShell.assemble）。
            val byProject = engines.flatMap { it.executed }
                .associate { it.projectId to it.authorization!!.mask }
            assertEquals(CapabilityMask.ALL, byProject["builtin-app"], "有来源证据的项目按 §11 矩阵取全量")
            assertEquals(
                TrustTierMasks.UNKNOWN_DEFAULT,
                byProject["other-app"],
                "查不到来源的项目走保守档，**不**因为同壳里另一个项目是全量而被带上去",
            )
            assertFalse(
                byProject["other-app"]!!.contains(BridgeCapability.CROSS_SCRIPT_CONTROL),
                "保守档不含跨脚本控制位",
            )
            // controller 的派生授权判据读的是**同一份**策略实例（两处同源，改一处不会静默不一致）。
            // 走 authorizeStart(caller = null, …)：宿主发起 = 链的根，不做调用方判据，
            // 但仍然**经同一份** authorization 算掩码 —— 这条路径本来就存在（调度链路在用），
            // 不需要为断言另开一个只算不判的孪生读口。
            assertEquals(CapabilityMask.ALL, s.controller.authorizeStart(null, "builtin-app").mask)
            assertEquals(
                TrustTierMasks.UNKNOWN_DEFAULT,
                s.controller.authorizeStart(null, "other-app").mask,
            )
        }

        Unit  // 显式收尾：void 返回值才被 JUnit5 视为测试
    }

    @Test
    fun `engines 自我 stop-status 过路由放行，跨脚本仍拒——目录 NONE 不是开洞`() = runBlocking {
        // 这条钉的是**路由层与 handler 的分工**（A5 整改第 1 条）：
        // `engines.stop`/`engines.status` 的**目录级**要求被降到 `NONE`，好让任何掩码都能
        // 停/看**自己**；"是不是别的执行"这个判据下沉到 handler。
        // 于是必须验两件相反的事都成立，否则降档就成了开洞：
        //   (a) 生产缺省档（UNKNOWN_DEFAULT，无任何跨脚本位）经**真 Router** 停/看自己 → 放行；
        //   (b) 同一个掩码停**别人** → 仍拒，且目标不受影响。
        // 走的是生产缺省装配（不传 capabilityMask/authorization）—— 验的就是现场那条路。
        val (s, _, _) = shell()
        s.use {
            val execResp = s.router.dispatch(
                BridgeRequest(1, "engines", "exec", """{"projectId":"p1","scriptPath":"a.js","timeoutMillis":60000}""", 5_000),
            )
            val runId = Regex("""runId"\s*:\s*(\d+)""")
                .find(assertInstanceOf(BridgeResponse.Ok::class.java, execResp).payload!!)!!
                .groupValues[1].toLong()
            val self = AuthenticatedRunContext(EngineId(0), runId, 7, TrustTierMasks.UNKNOWN_DEFAULT)

            val statusResp = withContext(self) {
                s.router.dispatch(BridgeRequest(2, "engines", "status", """{"runId":$runId}""", 5_000))
            }
            val statusOk = assertInstanceOf(
                BridgeResponse.Ok::class.java,
                statusResp,
                "自我 status 必须过路由：目录级 NONE 就是为了这条（否则窄掩码脚本读不到自己）",
            )
            assertTrue(
                statusOk.payload == "\"RUNNING\"" || statusOk.payload == "\"STOPPED\"",
                "状态名原样回（夹具引擎可能已自然收敛为 STOPPED）：${statusOk.payload}",
            )

            // (b) 同一个缺省掩码去停**别人**：路由放行（NONE），handler 拒（无 CROSS_SCRIPT_CONTROL）。
            val other = AuthenticatedRunContext(EngineId(0), runId + 1_000, 8, TrustTierMasks.UNKNOWN_DEFAULT)
            val denied = withContext(other) {
                s.router.dispatch(BridgeRequest(3, "engines", "stop", """{"runId":$runId}""", 5_000))
            }
            assertEquals(
                "ERR_PERMISSION_DENIED",
                assertInstanceOf(BridgeResponse.Err::class.java, denied).errorCode,
                "跨脚本仍要控制位 —— 目录降档不等于放行别人的执行",
            )
            assertTrue(s.controller.activeRunIds().contains(runId), "被拒的 stop 不得产生副作用")

            // (a') 自我 stop：同一缺省掩码，放行。
            val selfStop = withContext(self) {
                s.router.dispatch(BridgeRequest(4, "engines", "stop", """{"runId":$runId}""", 5_000))
            }
            assertInstanceOf(BridgeResponse.Ok::class.java, selfStop)
            assertFalse(s.controller.activeRunIds().contains(runId), "自我 stop 生效")
        }

        Unit  // 显式收尾：void 返回值才被 JUnit5 视为测试
    }

    @Test
    fun `调度经 dispatcher 落到 controller 并 COMMIT`() = runBlocking(AuthenticatedRunContext(EngineId(0), 42, 1, CapabilityMask.ALL)) {
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
    fun `装配交出看门狗实例但不自行轮转`() = runBlocking(AuthenticatedRunContext(EngineId(0), 42, 1, CapabilityMask.ALL)) {
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
    fun `心跳经桥打点后看门狗问得到，run 终结即遗忘`() = runBlocking(AuthenticatedRunContext(EngineId(0), 42, 1, CapabilityMask.ALL)) {
        // 不显式指定 → 装配接上生产心跳账本（controller 持有的 HeartbeatLedger）
        val (s, _, _) = shell(heartbeatMillis = null)
        s.use {
            val resp = s.router.dispatch(
                BridgeRequest(1, "engines", "exec", """{"projectId":"p1","scriptPath":"a.js","timeoutMillis":60000}""", 5_000),
            )
            val ok = assertInstanceOf(BridgeResponse.Ok::class.java, resp)
            val runId = Regex("""runId"\s*:\s*(\d+)""").find(ok.payload!!)!!.groupValues[1].toLong()

            // 没打过点 → 看门狗如实记 noHeartbeat（不猜 0，也不拿轮转周期冒充心跳）
            assertTrue(s.watchdog.tick().noHeartbeat.contains(runId), "未打点：心跳一路不判死")

            // 打点（JS engines.heartbeat 的 Kotlin 落点）后同一轮就量得到
            val beat = withContext(AuthenticatedRunContext(EngineId(0), runId, 2, CapabilityMask.ALL)) { s.router.dispatch(
                BridgeRequest(2, "engines", "heartbeat", """{"runId":$runId,"seq":1}""", 5_000),
            ) }
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
    fun `看门狗监督经桥启动的在途 run`() = runBlocking(AuthenticatedRunContext(EngineId(0), 42, 1, CapabilityMask.ALL)) {
        // 显式替身心跳来源（§8.4 的生产缺省是 controller 的 HeartbeatLedger）
        val (s, _, _) = shell(heartbeatMillis = { 100L })
        s.use {
            val resp = s.router.dispatch(
                BridgeRequest(1, "engines", "exec", """{"projectId":"p1","scriptPath":"a.js","timeoutMillis":60000}""", 5_000),
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
    fun `shutdown 先停调度再急停执行：投递停了，槽位还了`() = runBlocking(AuthenticatedRunContext(EngineId(0), 42, 1, CapabilityMask.ALL)) {
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
    fun `shutdown 无投递时如实空收口但仍清池`() = runBlocking(AuthenticatedRunContext(EngineId(0), 42, 1, CapabilityMask.ALL)) {
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
    fun `mount 薄转接自定义命名空间`() = runBlocking(AuthenticatedRunContext(EngineId(0), 42, 1, CapabilityMask.ALL)) {
        val (s, _, _) = shell()
        s.use {
            assertTrue(s.mount("echo", com.autoscript.bridge.RequestHandler { r -> BridgeResponse.Ok(r.id, r.payload) }))
            val resp = s.router.dispatch(BridgeRequest(9, "echo", "ping", """"x"""", 5_000))
            assertEquals(""""x"""", (resp as BridgeResponse.Ok).payload)
        }

        Unit  // 显式收尾：void 返回值才被 JUnit5 视为测试
    }
}
