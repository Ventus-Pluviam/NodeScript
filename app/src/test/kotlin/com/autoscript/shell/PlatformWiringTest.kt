package com.autoscript.shell

import com.autoscript.appservice.scheduler.core.InMemoryIntentLog
import com.autoscript.appservice.scheduler.core.SchedulerProvider
import com.autoscript.appservice.scheduler.core.TriggerHandle
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
import com.autoscript.domain.storage.InMemoryDataStore
import com.autoscript.domain.storage.SystemSettings
import com.autoscript.domain.storage.ZipArchiver
import com.autoscript.domain.system.AppLauncher
import com.autoscript.domain.system.DeviceInfoProvider
import com.autoscript.domain.system.DeviceProfile
import com.autoscript.domain.system.FloatingWindowHost
import com.autoscript.domain.system.FloatingWindowSpec
import com.autoscript.domain.system.NotificationPoster
import com.autoscript.domain.system.NotificationSpec
import com.autoscript.domain.system.ShellExecutor
import com.autoscript.domain.system.ShellMode
import com.autoscript.domain.system.ShellResult
import com.autoscript.platform.system.SystemSpis
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * 生产能力装配验证（§12.2 接线现状 + §6 包级例外二）。
 *
 * [PlatformWiring.inject] 是 `SystemSpis.Bundle` → `AppShellKit.assemble` 注入束的
 * 纯转接（[PlatformWiring.of] 只多一步 `Context` → Bundle）。本测试用假 SPI 走**同一条
 * 拼装路径**，经 `AppShell.router` 真分发验三件事：
 * 1. 四条独立缝（datastore/zip/settings/notification）+ 五命名空间束接通，
 *    handler 是 `CapabilityNamespaces` 的真转接（协议解释权在平台侧，装配只挂载）；
 * 2. `dialogs` 恒 null（`DialogHost` 待 §14 P2）→ 如实 `ERR_NOT_IMPLEMENTED`，不伪造；
 * 3. `a11y`/`screen` 生产已接（都经 SystemA11yBridge）：测试进程无无障碍服务 →
 *    如实 `ERR_SERVICE_DISABLED`（不是 NOT_IMPLEMENTED —— namespace 已挂，差的是服务连接）。
 *
 * 真机路径的差异只有 `of(context)` 那一步（`SystemSpis.of` 造 Android 实现），
 * 由 platform/system 的契约测试覆盖；两条路径共用 [PlatformWiring.inject]，
 * 不给"测试走另一套装配"留门。
 */
class PlatformWiringTest {

    // ── 假 SPI（最小可辨识实现：记录调用 + 回固定值）────────────────────

    private class FakeShell : ShellExecutor {
        var lastCommand: String? = null
        override suspend fun exec(command: String, mode: ShellMode, timeoutMillis: Long): ShellResult {
            lastCommand = command
            return ShellResult(code = 0, stdout = "uid=0", stderr = null)
        }
    }

    private class FakeZip : ZipArchiver {
        var compressed: Pair<Path, Path>? = null
        override suspend fun compress(source: Path, archive: Path) {
            compressed = source to archive
        }
        override suspend fun extract(archive: Path, targetDir: Path) = Unit
    }

    private class FakeSettings : SystemSettings {
        private val map = mutableMapOf<String, Any>()
        override fun canWrite(): Boolean = true
        override fun getString(key: String): String? = map[key] as? String
        override fun getInt(key: String): Int? = map[key] as? Int
        override fun putString(key: String, value: String) {
            map[key] = value
        }
        override fun putInt(key: String, value: Int) {
            map[key] = value
        }
    }

    private class FakeNotification : NotificationPoster {
        val posted = mutableListOf<NotificationSpec>()
        override fun canPost(): Boolean = true
        override fun post(spec: NotificationSpec) {
            posted += spec
        }
        override fun cancel(id: Int) = Unit
    }

    private fun bundle(): SystemSpis.Bundle = SystemSpis.Bundle(
        shell = FakeShell(),
        device = object : DeviceInfoProvider {
            override fun profile(): DeviceProfile = DeviceProfile(model = "Pixel 8", sdkInt = 34)
        },
        app = object : AppLauncher {
            override suspend fun launch(packageName: String): Boolean = packageName == "com.example.target"
            override suspend fun currentPackage(): String? = "com.example.here"
        },
        floatingWindow = object : FloatingWindowHost {
            override suspend fun create(spec: FloatingWindowSpec): HandleRef = HandleRef(7, 1)
            override suspend fun close(ref: HandleRef) = Unit
        },
        datastore = InMemoryDataStore(),
        zip = FakeZip(),
        settings = FakeSettings(),
        notification = FakeNotification(),
    )

    // ── 装壳（与 AppShellSystemMountTest 同一骨架，注入束换成 PlatformWiring 的）──

    private class FakeEngine(override val id: EngineId, override val pid: Int? = null) : ScriptEngine {
        override suspend fun execute(run: EngineRunRequest): EngineRunReceipt =
            EngineRunReceipt(runId = 1, handle = HandleRef(1, 1))
        override suspend fun stop(): StopResult = StopResult.Clean
        override suspend fun kill(): KillCause = KillCause.REQUESTED
        override suspend fun status(): EngineStatus = EngineStatus.STOPPED
    }

    private fun shell(wiring: PlatformWiring.Injection): AppShell = AppShell.assemble(
        engineFactory = { id -> FakeEngine(id) },
        schedulerProvider = object : SchedulerProvider {
            override suspend fun registerTrigger(targetFireAtMillis: Long, taskId: String): TriggerHandle =
                TriggerHandle { }
            override suspend fun cancelTrigger(handle: TriggerHandle) = Unit
        },
        intentLog = InMemoryIntentLog(),
        systemHandlers = wiring.systemHandlers,
        datastoreHandler = wiring.datastoreHandler,
        zipHandler = wiring.zipHandler,
        settingsHandler = wiring.settingsHandler,
        notificationHandler = wiring.notificationHandler,
        a11yHandler = wiring.a11yHandler,
        screenHandler = wiring.screenHandler,
    )

    private suspend fun dispatch(
        s: AppShell,
        ns: String,
        method: String,
        payload: String?,
    ): BridgeResponse = s.router.dispatch(BridgeRequest(1, ns, method, payload, 5_000))

    private fun okPayload(r: BridgeResponse): String =
        assertInstanceOf(BridgeResponse.Ok::class.java, r).payload!!

    private fun errCode(r: BridgeResponse): String =
        assertInstanceOf(BridgeResponse.Err::class.java, r).errorCode

    // ── 用例 ────────────────────────────────────────────────────────────

    @Test
    fun `五命名空间束接通，dialogs 诚实缺位`() = runBlocking {
        val spis = bundle()
        val wiring = PlatformWiring.inject(spis)
        assertNull(wiring.systemHandlers.dialogs, "DialogHost 待 §14 P2：必须留 null 而不是假实现")

        shell(wiring).use { s ->
            // 载荷字段语义归 SystemNamespaces 测试；这里验「装配接通 + SPI 收到真命令」
            assertInstanceOf(
                BridgeResponse.Ok::class.java,
                dispatch(s, "shell", "exec", """{"cmd":"id"}"""),
            )
            assertEquals("id", (spis.shell as FakeShell).lastCommand, "SPI 必须收到真命令")

            assertInstanceOf(
                BridgeResponse.Ok::class.java,
                dispatch(s, "device", "model", null),
                "device 走 CapabilityNamespaces 真转接",
            )
            assertInstanceOf(
                BridgeResponse.Ok::class.java,
                dispatch(s, "app", "currentPackage", null),
            )
            assertInstanceOf(
                BridgeResponse.Ok::class.java,
                dispatch(s, "floatingWindow", "create", """{"title":"t"}"""),
            )
            assertEquals(
                "ERR_NOT_IMPLEMENTED",
                errCode(dispatch(s, "dialogs", "prompt", "{}")),
                "dialogs 缺位必须如实未实现（不伪造弹窗）",
            )
        }
        Unit
    }

    @Test
    fun `存储与通知四条独立缝经真 handler 落到假 SPI`() = runBlocking {
        val spis = bundle()
        val wiring = PlatformWiring.inject(spis)

        shell(wiring).use { s ->
            // datastore：put → get 往返（InMemoryDataStore 真参与，不是 mock 回包）
            assertEquals("true", okPayload(dispatch(s, "datastore", "put", """{"key":"cfg","value":{"a":1}}""")))
            assertEquals(
                """{"found":true,"value":{"a":1}}""",
                okPayload(dispatch(s, "datastore", "get", """{"key":"cfg"}""")),
            )

            // settings：写进假 SPI、读回同一份
            assertEquals("true", okPayload(dispatch(s, "settings", "putString", """{"key":"k","value":"v"}""")))
            assertEquals("\"v\"", okPayload(dispatch(s, "settings", "getString", """{"key":"k"}""")))

            // notification：canPost 探针 + post 真落到假 SPI
            assertEquals("true", okPayload(dispatch(s, "notification", "canPost", null)))
            assertEquals("true", okPayload(dispatch(s, "notification", "post", """{"id":7,"text":"跑完了"}""")))
            assertEquals(1, (spis.notification as FakeNotification).posted.size)
            assertEquals("跑完了", spis.notification.posted[0].text)

            // zip：compress 参数原样到假归档器
            assertInstanceOf(
                BridgeResponse.Ok::class.java,
                dispatch(s, "zip", "compress", """{"source":"/a/dir","archive":"/b/out.zip"}"""),
            )
            assertEquals(
                Path.of("/a/dir") to Path.of("/b/out.zip"),
                (spis.zip as FakeZip).compressed,
                "假归档器必须收到调用方给的两个路径",
            )
        }
        Unit
    }

    @Test
    fun `a11y 与 screen 生产已接但服务未连——双双如实 ERR_SERVICE_DISABLED`() = runBlocking {
        shell(PlatformWiring.inject(bundle())).use { s ->
            assertEquals(
                "ERR_SERVICE_DISABLED",
                errCode(
                    dispatch(s, "a11y", "findOne", """{"conditions":{}}"""),
                ),
                "namespace 已挂（AndroidUiTree 真转接）；测试进程无无障碍服务 → 差的是连接不是实现",
            )
            assertEquals(
                "ERR_SERVICE_DISABLED",
                errCode(dispatch(s, "screen", "capture", null)),
                "screen 同底（ScreenshotSource+AndroidFrameProducer 经 SystemA11yBridge）",
            )
        }
        Unit
    }
}
