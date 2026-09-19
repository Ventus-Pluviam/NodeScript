package com.autoscript.shell

import com.autoscript.appservice.scheduler.core.InMemoryIntentLog
import com.autoscript.appservice.scheduler.core.SchedulerProvider
import com.autoscript.appservice.scheduler.core.TriggerHandle
import com.autoscript.domain.bridge.BridgeRequest
import com.autoscript.domain.bridge.BridgeResponse
import com.autoscript.domain.bridge.HandleRef
import com.autoscript.domain.bridge.NamespaceHandler
import com.autoscript.domain.engine.EngineId
import com.autoscript.domain.engine.EngineRunReceipt
import com.autoscript.domain.engine.EngineRunRequest
import com.autoscript.domain.engine.EngineStatus
import com.autoscript.domain.engine.KillCause
import com.autoscript.domain.engine.ScriptEngine
import com.autoscript.domain.engine.StopResult
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

/**
 * 能力命名空间挂载缝验证（§4.1/§6 + `:app` ArchitectureTest「禁直连 :platform」）：
 * `:app` 只按注入缝拿 [NamespaceHandler] 挂 Router，不 new 具体实现。
 *
 * 覆盖三件事：
 * 1. 注入 `a11y`/`screen`/`npm` 缝 → 请求可达真实逻辑（这里用内存假实现，等价于
 *    `:platform:capabilities` 的真实现与 `:app-service:packager` 的 npm 真实现）；
 * 2. 不注入 → 桥对 `a11y.*`/`screen.*`/`npm.*` 如实回 ERR_NOT_IMPLEMENTED（§7.5 Router 契约），
 *    **绝不伪造可用**；
 * 3. `console`/`engines` 与能力缝共存，互不抢占 namespace。
 */
class AppShellCapabilityMountTest {

    private class MountFakeEngine(override val id: EngineId, override val pid: Int? = null) : ScriptEngine {
        override suspend fun execute(run: EngineRunRequest): EngineRunReceipt {
            return EngineRunReceipt(runId = 1, handle = HandleRef(1, 1))
        }
        override suspend fun stop(): StopResult = StopResult.Clean
        override suspend fun kill(): KillCause = KillCause.REQUESTED
        override suspend fun status(): EngineStatus = EngineStatus.STOPPED
    }

    private fun shell(vararg handlers: Pair<String, NamespaceHandler>): AppShell {
        val map = handlers.toMap()
        return AppShell.assemble(
            engineFactory = { id -> MountFakeEngine(id) },
            schedulerProvider = object : SchedulerProvider {
                override suspend fun registerTrigger(targetFireAtMillis: Long, taskId: String): TriggerHandle =
                    TriggerHandle { }
                override suspend fun cancelTrigger(handle: TriggerHandle) = Unit
            },
            intentLog = InMemoryIntentLog(),
            a11yHandler = map["a11y"],
            screenHandler = map["screen"],
            npmHandler = map["npm"],
        )
    }

    /** 内存假 a11y：一个可点节点，findOne 命中回 ref 体（与真实现同构的最小替身）。 */
    private val fakeA11y = NamespaceHandler { request ->
        if (request.method != "findOne") {
            BridgeResponse.Err(request.id, "ERR_NOT_IMPLEMENTED", "假 a11y 只实现 findOne")
        } else {
            BridgeResponse.Ok(
                request.id,
                """{"ref":{"refId":1,"generation":1}}""",
            )
        }
    }

    private val fakeScreen = NamespaceHandler { request ->
        if (request.method != "capture") {
            BridgeResponse.Err(request.id, "ERR_NOT_FOUND", "假 screen 无会话")
        } else {
            BridgeResponse.Ok(request.id, """{"ref":{"refId":9,"generation":1},"width":1080,"height":2400}""")
        }
    }

    /** 内存假 npm：实现 list 一个轻操作 + install 回 Ok（真实现语义的最小替身）。 */
    private val fakeNpm = NamespaceHandler { request ->
        when (request.method) {
            "list" -> BridgeResponse.Ok(request.id, """[{"name":"axios","version":"1.7.0"}]""")
            else -> BridgeResponse.Err(request.id, "ERR_NOT_IMPLEMENTED", "FakeNpm only implements list")
        }
    }

    @Test
    fun `注入能力缝后 a11y screen 可达`() = runBlocking {
        val s = shell("a11y" to fakeA11y, "screen" to fakeScreen, "npm" to fakeNpm)
        s.use {
            val a11yResp = s.router.dispatch(
                BridgeRequest(1, "a11y", "findOne", """{"conditions":{"text":"启动"}}""", 5_000),
            )
            val ok = assertInstanceOf(BridgeResponse.Ok::class.java, a11yResp)
            // 载荷原样透传（桥侧不解译 payload）：ref 体原封不动到达
            assertEquals("""{"ref":{"refId":1,"generation":1}}""", ok.payload)

            val screenResp = s.router.dispatch(
                BridgeRequest(2, "screen", "capture", null, 5_000),
            )
            assertInstanceOf(BridgeResponse.Ok::class.java, screenResp)

            val npmResp = s.router.dispatch(
                BridgeRequest(5, "npm", "list", """{"projectId":"p1"}""", 5_000),
            )
            assertEquals("""[{"name":"axios","version":"1.7.0"}]""",
                (npmResp as BridgeResponse.Ok).payload)

            // 能力缝接入不影响既有命名空间：console/engines 仍在位
            val consoleResp = s.router.dispatch(
                BridgeRequest(3, "console", "log", """{"level":"log","text":"hi"}""", 5_000),
            )
            assertInstanceOf(BridgeResponse.Ok::class.java, consoleResp)
            assertEquals(1, s.console.size())
            val stats = s.router.dispatch(BridgeRequest(4, "engines", "poolStats", null, 5_000))
            assertInstanceOf(BridgeResponse.Ok::class.java, stats)
        }

        Unit  // 显式收尾：void 返回值才被 JUnit5 视为测试
    }

    @Test
    fun `未注入能力缝时如实回 ERR_NOT_IMPLEMENTED`() = runBlocking {
        val s = shell()
        s.use {
            val a11yResp = s.router.dispatch(
                BridgeRequest(1, "a11y", "findOne", """{"conditions":{"text":"启动"}}""", 5_000),
            )
            val err = assertInstanceOf(BridgeResponse.Err::class.java, a11yResp)
            assertEquals("ERR_NOT_IMPLEMENTED", err.errorCode)
            assertEquals(1L, err.id)

            val screenResp = s.router.dispatch(BridgeRequest(2, "screen", "capture", null, 5_000))
            assertEquals("ERR_NOT_IMPLEMENTED", (screenResp as BridgeResponse.Err).errorCode)

            val npmResp = s.router.dispatch(BridgeRequest(3, "npm", "list", null, 5_000))
            assertEquals("ERR_NOT_IMPLEMENTED", (npmResp as BridgeResponse.Err).errorCode)
        }

        Unit  // 显式收尾：void 返回值才被 JUnit5 视为测试
    }

    @Test
    fun `只注入一个缝，另一个仍如实未实现`() = runBlocking {
        val s = shell("a11y" to fakeA11y)
        s.use {
            assertInstanceOf(
                BridgeResponse.Ok::class.java,
                s.router.dispatch(BridgeRequest(1, "a11y", "findOne", "{}", 5_000)),
            )
            val screenResp = s.router.dispatch(BridgeRequest(2, "screen", "capture", null, 5_000))
            assertEquals("ERR_NOT_IMPLEMENTED", (screenResp as BridgeResponse.Err).errorCode)

            val npmResp = s.router.dispatch(BridgeRequest(3, "npm", "list", null, 5_000))
            assertEquals("ERR_NOT_IMPLEMENTED", (npmResp as BridgeResponse.Err).errorCode)
        }

        Unit  // 显式收尾：void 返回值才被 JUnit5 视为测试
    }
}
