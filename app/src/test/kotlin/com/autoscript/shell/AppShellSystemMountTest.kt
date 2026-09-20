package com.autoscript.shell

import com.autoscript.appservice.scheduler.core.InMemoryIntentLog
import com.autoscript.appservice.scheduler.core.SchedulerProvider
import com.autoscript.appservice.scheduler.core.TriggerHandle
import com.autoscript.domain.bridge.BridgeRequest
import com.autoscript.domain.bridge.BridgeResponse
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
 * 五个系统侧命名空间的挂载缝验证（§4.1/§6 + §12.2 接线现状表）。
 *
 * `:app` 不 new 具体实现（真实现住 `:platform:capabilities`，§6 禁直连），只按注入束
 * [SystemHandlers] 挂 Router。覆盖三件事：
 * 1. 注入后五个命名空间都可达，且与 console/engines/a11y/screen 共存不抢占；
 * 2. 不注入 → 五个命名空间一律如实 ERR_NOT_IMPLEMENTED（§7.5），**不伪造可用**；
 * 3. 注入束里单个字段为 null = 只缺那一个（其余照常），同 [AppShellCapabilityMountTest]
 *    的「只注入一个缝」口径。
 */
class AppShellSystemMountTest {

    private class MountFakeEngine(override val id: EngineId, override val pid: Int? = null) : ScriptEngine {
        override suspend fun execute(run: EngineRunRequest): EngineRunReceipt =
            EngineRunReceipt(runId = 1, handle = com.autoscript.domain.bridge.HandleRef(1, 1))
        override suspend fun stop(): StopResult = StopResult.Clean
        override suspend fun kill(): KillCause = KillCause.REQUESTED
        override suspend fun status(): EngineStatus = EngineStatus.STOPPED
    }

    private fun shell(system: SystemHandlers? = null): AppShell = AppShell.assemble(
        engineFactory = { id -> MountFakeEngine(id) },
        schedulerProvider = object : SchedulerProvider {
            override suspend fun registerTrigger(targetFireAtMillis: Long, taskId: String): TriggerHandle =
                TriggerHandle { }
            override suspend fun cancelTrigger(handle: TriggerHandle) = Unit
        },
        intentLog = InMemoryIntentLog(),
        systemHandlers = system,
    )

    /** 内存替身：五个命名空间各回一个可辨识的载荷（等价于真 handler 的最小形态）。 */
    private fun bundle(seen: MutableList<String>): SystemHandlers {
        fun fake(payload: String) = NamespaceHandler { r ->
            seen += r.method
            BridgeResponse.Ok(r.id, payload)
        }
        return SystemHandlers(
            dialogs = fake("""{"value":"ok","confirmed":true}"""),
            shell = fake("""{"code":0,"stdout":"","stderr":""}"""),
            device = fake(""""Pixel 8""""),
            app = fake("true"),
            floatingWindow = fake("""{"refId":1,"generation":1}"""),
        )
    }

    @Test
    fun `注入后五个命名空间均可达且不挤掉既有命名空间`() = runBlocking {
        val seen = mutableListOf<String>()
        val s = shell(bundle(seen))
        s.use {
            val cases = listOf(
                "dialogs" to "prompt",
                "shell" to "exec",
                "device" to "model",
                "app" to "currentPackage",
                "floatingWindow" to "create",
            )
            var id = 1L
            for ((ns, method) in cases) {
                val resp = s.router.dispatch(BridgeRequest(id++, ns, method, "{}", 5_000))
                assertInstanceOf(BridgeResponse.Ok::class.java, resp, "$ns.$method 应可达")
            }
            assertEquals(cases.map { it.second }, seen, "五个命名空间逐个命中所注入的 handler")

            // 既有命名空间不受影响（console/engines 在位）
            assertInstanceOf(
                BridgeResponse.Ok::class.java,
                s.router.dispatch(BridgeRequest(90, "console", "log", """{"level":"log","text":"hi"}""", 5_000)),
            )
            assertInstanceOf(
                BridgeResponse.Ok::class.java,
                s.router.dispatch(BridgeRequest(91, "engines", "poolStats", null, 5_000)),
            )
        }

        Unit  // 显式收尾：void 返回值才被 JUnit5 视为测试
    }

    @Test
    fun `未注入时五个命名空间如实 ERR_NOT_IMPLEMENTED`() = runBlocking {
        val s = shell()
        s.use {
            var id = 1L
            for (ns in listOf("dialogs", "shell", "device", "app", "floatingWindow")) {
                val resp = s.router.dispatch(BridgeRequest(id++, ns, "model", null, 5_000))
                val err = assertInstanceOf(BridgeResponse.Err::class.java, resp, "$ns 未接线必须报错")
                assertEquals("ERR_NOT_IMPLEMENTED", err.errorCode)
            }
        }

        Unit
    }

    @Test
    fun `注入束缺单个字段只影响那一个`() = runBlocking {
        val seen = mutableListOf<String>()
        val s = shell(
            SystemHandlers(
                dialogs = NamespaceHandler { r ->
                    seen += r.method
                    BridgeResponse.Ok(r.id, """{"value":"x","confirmed":false}""")
                },
                // shell/device/app/floatingWindow 一律缺
            ),
        )
        s.use {
            assertInstanceOf(
                BridgeResponse.Ok::class.java,
                s.router.dispatch(BridgeRequest(1, "dialogs", "prompt", "{}", 5_000)),
            )
            assertEquals(listOf("prompt"), seen)
            val shellResp = s.router.dispatch(BridgeRequest(2, "shell", "exec", "{}", 5_000))
            assertEquals("ERR_NOT_IMPLEMENTED", (shellResp as BridgeResponse.Err).errorCode)
            val devResp = s.router.dispatch(BridgeRequest(3, "device", "model", null, 5_000))
            assertEquals("ERR_NOT_IMPLEMENTED", (devResp as BridgeResponse.Err).errorCode)
        }

        Unit
    }
}
