package com.autoscript.shell

import com.autoscript.appservice.scheduler.core.InMemoryIntentLog
import com.autoscript.appservice.scheduler.core.SchedulerProvider
import com.autoscript.appservice.scheduler.core.TriggerHandle
import com.autoscript.domain.automation.ColorHit
import com.autoscript.domain.automation.FeatureHit
import com.autoscript.domain.automation.ImageAnalyzer
import com.autoscript.domain.automation.ImageFrame
import com.autoscript.domain.automation.ImageMatch
import com.autoscript.domain.automation.ScreenSnapshot
import com.autoscript.domain.json.DomainJson
import com.autoscript.domain.bridge.BridgeRequest
import com.autoscript.domain.bridge.BridgeResponse
import com.autoscript.domain.bridge.HandleRef
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.engine.EngineId
import com.autoscript.domain.engine.EngineRunReceipt
import com.autoscript.domain.engine.EngineRunRequest
import com.autoscript.domain.engine.EngineStatus
import com.autoscript.domain.engine.KillCause
import com.autoscript.domain.engine.ScriptEngine
import com.autoscript.domain.engine.StopResult
import com.autoscript.domain.storage.InMemoryDataStore
import com.autoscript.platform.system.settings.SystemSettings
import com.autoscript.platform.system.zip.ZipArchiver
import com.autoscript.platform.system.app.AppLauncher
import com.autoscript.platform.system.clipboard.Clipboard
import com.autoscript.platform.system.device.DeviceInfoProvider
import com.autoscript.platform.system.device.DeviceProfile
import com.autoscript.domain.system.DialogHost
import com.autoscript.platform.system.floatingWindow.FloatingWindowHost
import com.autoscript.platform.system.floatingWindow.FloatingWindowSpec
import com.autoscript.platform.system.notification.NotificationPoster
import com.autoscript.platform.system.notification.NotificationSpec
import com.autoscript.platform.system.sensors.SensorDelay
import com.autoscript.platform.system.sensors.SensorEventBatch
import com.autoscript.platform.system.sensors.SensorSource
import com.autoscript.platform.system.shell.ShellExecutor
import com.autoscript.platform.system.shell.ShellMode
import com.autoscript.platform.system.shell.ShellResult
import com.autoscript.platform.system.SystemSpis
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import com.autoscript.platform.system.SystemNamespaces
import com.autoscript.platform.capabilities.CapabilityNamespaces
import com.autoscript.platform.capabilities.a11y.AndroidUiTree
import com.autoscript.platform.capabilities.a11y.SystemA11yBridge
import com.autoscript.platform.capabilities.screen.AndroidFrameProducer
import com.autoscript.platform.capabilities.screen.ProducedFrame
import com.autoscript.platform.capabilities.screen.ScreenshotSource
import com.autoscript.domain.editor.SyntaxHighlighter
import com.autoscript.domain.npm.ShellConsoleMode

/**
 * 生产能力装配验证（§12.2 接线现状 + §6 包级例外二）。
 *
 * [PlatformWiring.inject] 是 `SystemSpis.Bundle` → `AppShellKit.assemble` 注入束的
 * 纯转接（[PlatformWiring.of] 只多一步 `Context` → Bundle）。本测试用假 SPI 走**同一条
 * 拼装路径**，经 `AppShell.router` 真分发验三件事：
 * 1. 七条独立缝（datastore/zip/settings/notification/clipboard/sensors/images）+ 五命名空间束接通，
 *    handler 是 `CapabilityNamespaces` 的真转接（协议解释权在平台侧，装配只挂载）；
 * 2. `dialogs`：inject 缺省不传 → null → 如实 `ERR_NOT_IMPLEMENTED`；传真宿主
 *    → 经 `CapabilityNamespaces.dialogs` 真转接到 `DialogHost`（生产 of() 传真宿主）；
 * 3. `a11y`/`screen` 生产已接（都经 SystemA11yBridge）：测试进程无无障碍服务 →
 *    如实 `ERR_SERVICE_DISABLED`（不是 NOT_IMPLEMENTED —— namespace 已挂，差的是服务连接）。
 *
 * 真机路径的差异只有 `of(context)` 那一步（`SystemSpis.of` 造 Android 实现），
 * 由 platform/system 的契约测试覆盖；两条路径共用 [PlatformWiring.inject]，
 * 不给"测试走另一套装配"留门。
 */
class PlatformWiringTest {

    /**
     * 装配期真会落盘的落点根（录屏腿的 `filesDir`）。
     *
     * **不能用 `/data/app/files` 这类真设备路径**：`MediaProjectionRecorder` 会
     * `Files.createDirectories` 真的去建目录，本机跑（root）会**在宿主根上建出 `/data`**，
     * CI 的 runner 上非 root 建不动 → `ERR_IO` → 用例红。两种结果都不是这条用例要测的东西
     * （它测的是**接线**：装配层把 `projection as? ScreenRecordingSessions` 接成
     * `MediaProjectionRecorder`、落点按 `ScriptPaths` 算），所以落点根必须是可写的临时目录。
     * 断言用 `dir` 现算期望值，不写死任何绝对路径。
     */
    @TempDir
    lateinit var dir: Path

    // ── 假 SPI（最小可辨识实现：记录调用 + 回固定值）────────────────────

    private class FakeShell : ShellExecutor {
        var lastCommand: String? = null
        var lastMode: ShellMode? = null
        override suspend fun exec(command: String, mode: ShellMode, timeoutMillis: Long): ShellResult {
            lastCommand = command
            lastMode = mode
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

    private class FakeClipboard : Clipboard {
        var stored: String? = null
        override fun getText(): String? = stored
        override fun setText(text: String) {
            stored = text
        }
    }

    private class FakeSensors : SensorSource {
        val live = mutableSetOf<Long>()
        var nextId = 1L
        override fun isSupported(name: String): Boolean = name == "accelerometer"
        override suspend fun register(name: String, delay: SensorDelay): HandleRef {
            val id = nextId++
            live += id
            return HandleRef(id, 1)
        }
        override suspend fun unregister(ref: HandleRef) {
            live -= ref.refId
        }
        override suspend fun unregisterAll() {
            live.clear()
        }
        override suspend fun drain(ref: HandleRef, sinceSeq: Long, max: Int): SensorEventBatch =
            SensorEventBatch(sinceSeq, sinceSeq, emptyList())
    }

    /** 假分析器：decode 回一帧（宽高固定），匹配结果由用例指定。 */
    private class FakeImageAnalyzer(
        var hit: ImageMatch? = ImageMatch(12, 34, 100, 50, 0.97),
        var failWith: AutojsException? = null,
    ) : ImageAnalyzer {
        val decoded = mutableListOf<String>()
        val released = mutableListOf<HandleRef>()
        private var nextRefId = 1L
        private val live = mutableSetOf<Long>()
        private fun requireLive(h: HandleRef) {
            if (h.generation != 1L || h.refId !in live) {
                throw AutojsException(ErrorCode.ERR_STALE_HANDLE, "帧 ${h.refId} 不在场")
            }
        }
        override suspend fun decode(path: String): ImageFrame {
            failWith?.let { throw it }
            decoded += path
            val id = nextRefId++
            live += id
            return ImageFrame(HandleRef(id, 1), 640, 480)
        }
        // §18-8(b)：inject 把同一个 analyzer 同时喂给 images 与 screen，
        // 截屏帧从这条口进同一张表（这里只验转接，不碰像素）。
        override suspend fun ingest(width: Int, height: Int, rgba: ByteArray): ImageFrame {
            val id = nextRefId++
            live += id
            return ImageFrame(HandleRef(id, 1), width, height)
        }
        override suspend fun release(handle: HandleRef) {
            if (handle.generation != 1L || handle.refId !in live) {
                throw AutojsException(ErrorCode.ERR_STALE_HANDLE, "帧 ${handle.refId} 不在场")
            }
            live -= handle.refId
            released += handle
        }
        override suspend fun matchTemplate(
            haystack: HandleRef,
            needle: HandleRef,
            threshold: Double,
            region: List<Int>?,
        ): ImageMatch? = hit
        override suspend fun findImage(
            haystack: HandleRef,
            needle: HandleRef,
            threshold: Double,
            region: List<Int>?,
        ): ImageMatch? = hit

        var colorHit: ColorHit? = ColorHit(7, 8, 10, 20, 30, 255)
        val colorCalls = mutableListOf<List<Int>>()
        override suspend fun findColor(
            haystack: HandleRef,
            color: List<Int>,
            tolerance: Int,
            region: List<Int>?,
        ): ColorHit? {
            colorCalls += color
            return colorHit
        }

        override suspend fun toGrayscale(frame: HandleRef): ImageFrame {
            requireLive(frame)
            val id = nextRefId++
            live += id
            return ImageFrame(HandleRef(id, 1), 640, 480)
        }

        override suspend fun crop(frame: HandleRef, region: List<Int>): ImageFrame {
            requireLive(frame)
            val id = nextRefId++
            live += id
            return ImageFrame(HandleRef(id, 1), 640, 480)
        }

        override suspend fun resize(frame: HandleRef, width: Int, height: Int): ImageFrame {
            requireLive(frame)
            val id = nextRefId++
            live += id
            return ImageFrame(HandleRef(id, 1), width, height)
        }

        override suspend fun rotate(frame: HandleRef, degrees: Double): ImageFrame {
            requireLive(frame)
            val id = nextRefId++
            live += id
            return ImageFrame(HandleRef(id, 1), 640, 480)
        }

        override suspend fun findFeature(scene: HandleRef, template: HandleRef): FeatureHit? {
            requireLive(scene)
            requireLive(template)
            return null
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
        clipboard = FakeClipboard(),
        sensors = FakeSensors(),
    )

    // ── 装壳（与 AppShellSystemMountTest 同一骨架，注入束换成 PlatformWiring 的）──

    private class FakeEngine(override val id: EngineId, override val pid: Int? = null) : ScriptEngine {
        override suspend fun execute(run: EngineRunRequest): EngineRunReceipt =
            EngineRunReceipt(runId = 1, handle = HandleRef(1, 1))
        override suspend fun stop(): StopResult = StopResult.Clean
        override suspend fun kill(): KillCause = KillCause.REQUESTED
        override suspend fun status(): EngineStatus = EngineStatus.STOPPED
    }

    /**
     * 录屏腿的设备面替身（§9.2）：只记"被要求做了什么"。
     *
     * 真 `MediaRecorder`/`VirtualDisplay` 是 Android 运行时（JVM 上复现不了），所以这里
     * 验的是**装配**这一层：`screen.startRecording` 有没有被接到真语义层
     * （`MediaProjectionRecorder`）上、落点有没有按 `filesDir` 算。编码器那条路
     * **未在本机验证**（无设备），见 `AndroidMediaProjectionSessions` 的录屏分支。
     */
    private class FakeRecordingSessions :
        com.autoscript.domain.automation.ScreenRecordingSessions,
        com.autoscript.domain.automation.MediaProjectionSessions {

        // ── 取帧腿（本替身不测那条；装配层按 `projection as? ScreenRecordingSessions` 分岔，
        // 真设备层是**同一个对象**实现两张方法表，替身照这个形状来）──
        override val state: com.autoscript.domain.automation.MediaProjectionSessionState
            get() = com.autoscript.domain.automation.MediaProjectionSessionState.IDLE

        override val owner: com.autoscript.domain.automation.MediaProjectionSessionOwner? get() = null

        override suspend fun open(
            consent: com.autoscript.domain.automation.ScreenConsentToken?,
            owner: com.autoscript.domain.automation.MediaProjectionSessionOwner,
        ): com.autoscript.domain.automation.LeasedMediaProjectionSession = error("本测试不走取帧腿")

        override fun close(lease: com.autoscript.domain.automation.SessionResourceLease): Boolean = false
        override fun closeCurrent(): Boolean = false
        override fun revokeOwner(owner: com.autoscript.domain.automation.MediaProjectionSessionOwner): Boolean = false

        val startCalls = mutableListOf<Pair<com.autoscript.domain.automation.MediaProjectionSessionOwner, String>>()
        var current: FakeRec? = null
            private set

        override val recordingState: com.autoscript.domain.automation.MediaProjectionSessionState
            get() = if (current == null) {
                com.autoscript.domain.automation.MediaProjectionSessionState.IDLE
            } else {
                com.autoscript.domain.automation.MediaProjectionSessionState.ACTIVE
            }

        override val recordingOwner: com.autoscript.domain.automation.MediaProjectionSessionOwner?
            get() = current?.owner

        override suspend fun startRecording(
            consent: com.autoscript.domain.automation.ScreenConsentToken?,
            owner: com.autoscript.domain.automation.MediaProjectionSessionOwner,
            spec: com.autoscript.domain.automation.ScreenRecordingSpec,
        ): com.autoscript.domain.automation.LeasedScreenRecording {
            startCalls += owner to spec.path
            val s = FakeRec(owner, spec.path)
            current = s
            return s
        }

        override fun closeCurrentRecording(): Boolean {
            val c = current ?: return false
            c.finalizeIt()
            current = null
            return true
        }

        override fun revokeRecordingOwner(owner: com.autoscript.domain.automation.MediaProjectionSessionOwner): Boolean {
            val c = current ?: return false
            if (c.owner != owner) return false
            c.finalizeIt()
            current = null
            return true
        }
    }

    private class FakeRec(
        override val owner: com.autoscript.domain.automation.MediaProjectionSessionOwner,
        override val path: String,
    ) : com.autoscript.domain.automation.LeasedScreenRecording {
        override val lease: com.autoscript.domain.automation.SessionResourceLease =
            object : com.autoscript.domain.automation.SessionResourceLease {
                override val leaseId: Long = 1L
                override val owner: com.autoscript.domain.automation.MediaProjectionSessionOwner = this@FakeRec.owner
            }
        private var outcome: com.autoscript.domain.automation.RecordingOutcome? = null

        override val state: com.autoscript.domain.automation.MediaProjectionSessionState
            get() = if (outcome == null) {
                com.autoscript.domain.automation.MediaProjectionSessionState.ACTIVE
            } else {
                com.autoscript.domain.automation.MediaProjectionSessionState.STOPPED
            }

        override fun stop(): com.autoscript.domain.automation.RecordingOutcome {
            finalizeIt()
            return outcome!!
        }

        fun finalizeIt() {
            if (outcome == null) {
                outcome = com.autoscript.domain.automation.RecordingOutcome(path, 1_024L, completed = true)
            }
        }
    }

    private fun shell(wiring: PlatformWiring.Injection): AppShell = AppShell.assemble(
        engineFactory = { id, _ -> FakeEngine(id) },
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
        clipboardHandler = wiring.clipboardHandler,
        sensorsHandler = wiring.sensorsHandler,
        imagesHandler = wiring.imagesHandler,
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
        assertInstanceOf(BridgeResponse.Ok::class.java, r, "响应须 Ok，实际：$r").payload!!

    private fun errCode(r: BridgeResponse): String =
        assertInstanceOf(BridgeResponse.Err::class.java, r).errorCode

    // ── 用例 ────────────────────────────────────────────────────────────

    @Test
    fun `五命名空间束接通，dialogs 诚实缺位`() = runBlocking {
        val spis = bundle()
        val wiring = PlatformWiring.inject(spis)
        assertNull(wiring.systemHandlers.dialogs, "inject 未传 dialogs → 留 null 而不是假实现")

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

    /**
     * 控制台 shell 面的接线（2026-10-09）：**ADB 档必须走 Shizuku，其余走平台 shell**。
     *
     * 这条用例守的是一个**只在真机上才会暴露**的错接：`ConsoleShellExecutor` 若把
     * `ShellConsoleMode.ADB` 也转给 `platform.exec`，JVM 单测与编译全都绿，而真机上
     * 用户敲 `shizuku ls` 拿到的是**应用 uid** 的结果 —— 与「没进特权模式」不可分辨。
     * 断言的是**派发去向**（ADB 一次都不碰平台 SPI、ROOT 必须碰），不是输出文本。
     *
     * Shizuku 缺席时（本机、CI 都是）ADB 档如实 `ERR_PERMISSION_DENIED` ——
     * 那正是「没装 Shizuku」该给用户的话；不断言具体文案（措辞属平台层）。
     */
    @Test
    fun `控制台 shell 面接线：ROOT 走平台 shell，ADB 不落到应用 uid`() {
        val spis = bundle()
        val wiring = PlatformWiring.inject(spis)
        val op = requireNotNull(wiring.shellExecutor).asShellOpExecutor()
        val fake = spis.shell as FakeShell

        // ROOT：真命令 + 真模式（平台 SPI 侧收 `ShellMode.ROOT`，不是 DEFAULT）。
        fake.lastCommand = null
        val root = runBlocking { op.execute("id", ShellConsoleMode.ROOT, 5_000L) }
        assertEquals("id", fake.lastCommand, "ROOT 档必须落到平台 shell SPI")
        assertEquals(ShellMode.ROOT, fake.lastMode)
        assertEquals(0, root.code)

        // DEFAULT：也不许静默升级成特权 —— 与 ROOT 同路（拒绝发生在更上一层）。
        fake.lastCommand = null
        runBlocking { op.execute("id", ShellConsoleMode.DEFAULT, 5_000L) }
        assertEquals(ShellMode.DEFAULT, fake.lastMode)

        // ADB：**一次都不许碰平台 SPI**（碰了就是拿应用 uid 冒充 shell uid）。
        fake.lastCommand = null
        val e = assertThrows<AutojsException> {
            runBlocking { op.execute("id", ShellConsoleMode.ADB, 5_000L) }
        }
        assertEquals(ErrorCode.ERR_PERMISSION_DENIED, e.error)
        assertNull(fake.lastCommand, "ADB 档落到平台 shell = 拿应用 uid 冒充 shell uid")
    }

    @Test
    fun `存储通知剪贴板传感图像七条独立缝经真 handler 落到假 SPI`() = runBlocking {
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
            // 落局部：SystemSpis 来自 :domain（跨模块的 public 属性不给 smart cast）。
            val notifier = spis.notification as FakeNotification
            assertEquals(1, notifier.posted.size)
            assertEquals("跑完了", notifier.posted[0].text)

            // clipboard：set 到假 SPI、get 读回同一份（空串是真值）
            assertEquals("true", okPayload(dispatch(s, "clipboard", "setText", "{\"text\":\"hello\"}")))
            assertEquals("\"hello\"", okPayload(dispatch(s, "clipboard", "getText", null)))
            assertEquals("true", okPayload(dispatch(s, "clipboard", "setText", "{\"text\":\"\"}")))
            assertEquals("\"\"", okPayload(dispatch(s, "clipboard", "getText", null)))

            // sensors：register 发号 + drain 空增量游标回显（语义归 platform:system 侧测）
            val regPayload = okPayload(dispatch(s, "sensors", "register", "{\"name\":\"accelerometer\"}"))
            assertTrue(regPayload.contains("refId"), "register 回 ref 体，实际 $regPayload")
            assertEquals("true", okPayload(dispatch(s, "sensors", "isSupported", "{\"name\":\"accelerometer\"}")))
            val drainPayload = okPayload(
                dispatch(s, "sensors", "drain", "{\"ref\":{\"refId\":1,\"generation\":1},\"sinceSeq\":0}"),
            )
            assertTrue(
                drainPayload.contains("\"first\":0") && drainPayload.contains("\"events\":[]"),
                "空增量游标回显，实际 $drainPayload",
            )
            assertEquals("true", okPayload(dispatch(s, "sensors", "unregisterAll", null)))

            // images：inject 收假分析器 → 独立缝接通（decode 真宽高 + 命中体），
            // 未注入时桥对 images.* 如实 ERR_NOT_IMPLEMENTED（生产侧刻意不喂）
            val analyzer = FakeImageAnalyzer()
            val wired = PlatformWiring.inject(bundle(), images = analyzer)
            shell(wired).use { s ->
                val frame = okPayload(dispatch(s, "images", "decode", """{"path":"/sdcard/icon.png"}"""))
                assertTrue(
                    frame.contains("\"refId\":1") && frame.contains("\"width\":640") && frame.contains("\"height\":480"),
                    "decode 回帧三字段（宽高是文件真值），实际 $frame",
                )
                assertEquals(listOf("/sdcard/icon.png"), analyzer.decoded)
                // findColor：同一独立缝第五方法的端到端（见缝即通，假分析器给出固定命中）。
                // 必须在 release 之前打 —— 帧放了就打不到了（帧纪律）
                val hitPayload = okPayload(
                    dispatch(
                        s, "images", "findColor",
                        """{"haystack":{"refId":1,"generation":1},"color":[10,20,30,255],"tolerance":5}""",
                    ),
                )
                assertTrue(
                    hitPayload.contains("\"x\":7") && hitPayload.contains("\"r\":10"),
                    "findColor 回 {x,y,r,g,b,a}，实际 $hitPayload",
                )
                assertEquals(listOf(listOf(10, 20, 30, 255)), analyzer.colorCalls)
                assertEquals(
                    "true",
                    okPayload(dispatch(s, "images", "release", """{"ref":{"refId":1,"generation":1}}""")),
                )
                assertEquals(listOf<Long>(1L), analyzer.released.map { it.refId })
            }


            assertEquals(
                "ERR_NOT_IMPLEMENTED",
                errCode(dispatch(shell(PlatformWiring.inject(bundle())), "images", "decode", "{}")),
                "生产 inject 不喂分析器 → 独立缝缺省同样不伪造（真实现等 :bridge:image，P1）",
            )

            // screen 录屏腿（§9.2）：装配层把 `projection as? ScreenRecordingSessions` 接成
            // `MediaProjectionRecorder`，落点按 `filesDir` 算 —— 这条验的是**接线**，
            // 不是编码器（真 MediaRecorder 未在本机验证，无设备）。
            val recSessions = FakeRecordingSessions()
            val recWiring = PlatformWiring.inject(
                bundle(),
                projection = recSessions,
                filesDir = dir,
                // 投屏同意口只在 projection 非 null 时才接（见 screenHandler）；
                // 生产链上它是 AndroidScreenConsentBroker，JVM 上给一个恒同意的替身。
                consent = com.autoscript.domain.automation.ScreenConsentBroker {
                    object : com.autoscript.domain.automation.ScreenConsentToken {}
                },
            )
            shell(recWiring).use { s ->
                val ctx = com.autoscript.domain.bridge.AuthenticatedRunContext(
                    EngineId(0), 7L, 1L, com.autoscript.domain.permission.CapabilityMask.ALL,
                ).also { it.projectId = "demo" }
                val started = kotlinx.coroutines.withContext(ctx) {
                    s.router.dispatch(BridgeRequest(1, "screen", "startRecording", null, 5_000))
                }
                val payload = okPayload(started)
                // 期望值**现算**（与生产同一条 `ScriptPaths`），不写死绝对路径：
                // 写死的那版在 CI 上因建不动 /data 而红，测的却不是接线。
                val expectedDir = com.autoscript.domain.scripts.ScriptPaths
                    .recordingsDir(dir, "demo")
                assertTrue(
                    payload.contains("$expectedDir/") && payload.contains(".mp4"),
                    "落点必须按 ScriptPaths.recordingsDir(filesDir, projectId) 算，实际 $payload",
                )
                assertEquals(
                    expectedDir.toAbsolutePath().toString(),
                    Path.of(recSessions.startCalls.single().second).parent.toString(),
                    "设备层拿到的必须是语义层算好的那条路径",
                )
            }

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
    fun `dialogs 传真宿主即接通 wire 形状与 extras 契约一致`() = runBlocking {
        val host = object : DialogHost {
            override suspend fun prompt(request: com.autoscript.domain.system.DialogPromptRequest) =
                com.autoscript.domain.system.DialogOutcome("张三", confirmed = true)

            override suspend fun choose(request: com.autoscript.domain.system.DialogChooseRequest) =
                com.autoscript.domain.system.DialogChoice(1)
        }
        val wiring = PlatformWiring.inject(bundle(), dialogs = host)
        shell(wiring).use { s ->
            val prompt = okPayload(
                dispatch(s, "dialogs", "prompt", """{"title":"名字"}"""),
            )
            assertTrue(
                prompt.contains(""""value":"张三"""") && prompt.contains("confirmed"),
                "prompt 回 value/confirmed 两字段（extras.ts 契约），实际 $prompt",
            )
            assertEquals("1", okPayload(dispatch(s, "dialogs", "choose", """{"title":"选","options":["a","b"]}""")),
                "choose 裸下标直出（取消才是 -1）")
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

    @Test
    fun `语法高亮转接非 JS 扩展与 JVM 无 so 两路都降级 NONE`() {
        // 非 JS 扩展：扩展名筛选在 EditorHighlighters 里，连 so 都不问。
        assertSame(
            SyntaxHighlighter.NONE,
            PlatformWiring.syntaxHighlighter("demo/main.txt"),
            "非 JS 扩展必须返回 NONE（不做任何原生加载尝试）",
        )
        assertSame(SyntaxHighlighter.NONE, PlatformWiring.syntaxHighlighter("demo/readme.md"))
        assertSame(SyntaxHighlighter.NONE, PlatformWiring.syntaxHighlighter("demo/main.ts"))
        // JVM 下 JS 扩展：三个 so 都不在（System.loadLibrary 抛 UnsatisfiedLinkError，
        // 是 LinkageError 子类）→ 同样降级 NONE，编辑器保留纯文本能力。
        // 这条同时钉住「加载失败降级」而不是抛给调用方。
        assertSame(
            SyntaxHighlighter.NONE,
            PlatformWiring.syntaxHighlighter("demo/main.js"),
            "JVM 无 so：JS 扩展也必须降级 NONE（不抛 UnsatisfiedLinkError 给编辑器）",
        )
        assertSame(SyntaxHighlighter.NONE, PlatformWiring.syntaxHighlighter("sub/dir/app.mjs"))
        assertSame(SyntaxHighlighter.NONE, PlatformWiring.syntaxHighlighter("win\\path\\app.cjs"))
        Unit
    }

    // ── 跨命名空间帧表（§18-8(b)；2026-09-30 自 capabilities ImagesNamespaceHandlerTest 迁入：
    // images handler 已随步骤 6 迁 :platform:system，跨 system×capabilities 的互认测试只有
    // 同时依赖两者的 :app 能住 —— 生产侧也正是 PlatformWiring 把同一个 analyzer 同时喂给两边) ──

    private fun rgbaProducer(w: Int, h: Int): ScreenshotSource.FrameProducer =
        object : ScreenshotSource.FrameProducer {
            override suspend fun snapshot(): ScreenSnapshot =
                ScreenSnapshot(locked = false, secureForeground = false, hasWindows = true)

            override suspend fun produce(width: Int, height: Int): ProducedFrame =
                ProducedFrame(ByteArray(w * h * 4), w, h)
        }

    private fun refJson(ref: HandleRef): String =
        """{"ref":{"refId":${ref.refId},"generation":${ref.generation}}}"""

    private fun matchJson(haystack: HandleRef, needle: HandleRef, threshold: String): String =
        """{"haystack":{"refId":${haystack.refId},"generation":${haystack.generation}},"needle":{"refId":${needle.refId},"generation":${needle.generation}},"threshold":$threshold}"""

    @Test
    fun `截屏帧与 decode 帧同一张表——findImage 通、images 能放 screen 的帧`() = runBlocking {
        val shared = FakeImageAnalyzer(hit = null)
        val images = SystemNamespaces.images(shared)
        // 可控时钟：capture 走 333ms 节流，别让用例撞在窗口上
        var now = 1_000L
        val screen = ScreenshotSource(rgbaProducer(4, 4), clock = { now }, analyzer = shared)

        val shot = screen.capture()
        assertEquals(1L, shot.handle.refId, "截屏帧进的是 images 那张表（号段从 1 起）")

        val iconPayload = okPayload(images.handle(BridgeRequest(2, "images", "decode", """{"path":"/sdcard/icon.png"}""", 5_000)))
        val iconFields = (DomainJson.decodeObject(iconPayload)["ref"] as DomainJson.Value.Obj).fields
        val icon = HandleRef(
            (iconFields["refId"] as DomainJson.Value.N).raw.toLong(),
            (iconFields["generation"] as DomainJson.Value.N).raw.toLong(),
        )
        assertEquals(2L, icon.refId, "decode 接着截屏帧往下发号 —— 同一段，不是两张表")

        // 互认的核心：截屏帧当 haystack 不是 ERR_STALE_HANDLE
        val matched = okPayload(
            images.handle(
                BridgeRequest(3, "images", "findImage", matchJson(shot.handle, icon, "0.9"), 5_000),
            ),
        )
        assertEquals("null", matched, "跨来源两帧都认得（fake 未设命中 → 裸 null，不是 STALE）")

        // `images.release` 放得掉一帧截屏（曾经：这张表里根本没有它）
        assertEquals("true", okPayload(images.handle(BridgeRequest(4, "images", "release", refJson(shot.handle), 5_000))))
        assertEquals(
            "ERR_STALE_HANDLE",
            errCode(images.handle(BridgeRequest(5, "images", "release", refJson(shot.handle), 5_000))),
            "放掉即离场：两边同一口径",
        )
        // screen 侧再 recycle 同一帧 → 同码（同一张表、同一个"已释放"事实）
        val e = assertThrows<AutojsException> { runBlocking { screen.recycle(shot.handle) } }
        assertEquals(ErrorCode.ERR_STALE_HANDLE, e.error)

        // 截屏帧放掉后，decode 帧照常在场可放（两帧互不牵连）
        assertEquals("true", okPayload(images.handle(BridgeRequest(6, "images", "release", refJson(icon), 5_000))))
        Unit
    }
}
