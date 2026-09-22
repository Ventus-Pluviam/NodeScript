package com.autoscript.shell

import com.autoscript.appservice.scheduler.core.RunOutcome
import com.autoscript.appservice.scheduler.core.ScheduledTask
import com.autoscript.appservice.scheduler.core.ScreenGuarantee
import com.autoscript.appservice.scheduler.core.SchedulerProvider
import com.autoscript.appservice.scheduler.core.TimedSchedule
import com.autoscript.appservice.scheduler.core.TriggerHandle
import com.autoscript.appservice.scheduler.core.TriggerSource
import com.autoscript.appservice.scheduler.persist.FileRunArchive
import com.autoscript.appservice.scheduler.persist.JournalFileStore
import com.autoscript.appservice.scheduler.persist.PersistentIntentLog
import com.autoscript.domain.bridge.BridgeRequest
import com.autoscript.domain.bridge.BridgeResponse
import com.autoscript.domain.bridge.NamespaceHandler
import com.autoscript.domain.scripts.RunState
import com.autoscript.domain.scripts.isTerminal
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * 装壳配方验证（§4.1 Composition Root 的生产调用方）。
 *
 * 这里跑的是 [AppShellKit.assemble] —— 也就是 `AppShellApplication.onCreate` 走的那条路，
 * 所以它证明的是"真机上开机之后会发生什么"，而不是"挂载缝的形状"：
 * 1. 目录约定落位（`files/.autojs` 两个持久寄存器、`files/scripts` 项目根）；
 * 2. `schedulerProvider` 来自调用方（闹钟真实现），`screenGate` 真是注入的那一个
 *    —— 门禁拒绝时**不投递**；
 * 3. 缺省 engineFactory（`UnavailableEngine`）下，一次触发如实落 `CRASHED` + 真原因，
 *    并**成对写进两个持久寄存器**（意图日志 COMMIT 行 + 运行档案终态记录）；
 * 4. 能力 handler 缺省不挂 → 桥如实 `ERR_NOT_IMPLEMENTED`；注入则可达真逻辑
 *    （这里用 `:platform:capabilities` 的真 handler 转接，与真机注入的是同一个函数）。
 */
class AppShellKitTest {

    @TempDir
    lateinit var dir: Path

    private val files: Path get() = dir.resolve("files")
    private val cache: Path get() = dir.resolve("cache")

    private class RecordingProvider : SchedulerProvider {
        val registered = mutableListOf<String>()
        override suspend fun registerTrigger(targetFireAtMillis: Long, taskId: String): TriggerHandle {
            registered += taskId
            return TriggerHandle { }
        }

        override suspend fun cancelTrigger(handle: TriggerHandle) = handle.cancel()
    }

    private fun kit(
        provider: SchedulerProvider = RecordingProvider(),
        screenGate: ScreenGate = ScreenGate.AllowAll,
        a11yHandler: NamespaceHandler? = null,
        screenHandler: NamespaceHandler? = null,
        systemHandlers: SystemHandlers? = null,
        scriptSources: Map<String, Map<String, ByteArray>> = emptyMap(),
        scriptProjects: List<String> = emptyList(),
        assetReader: ((String) -> Map<String, ByteArray>)? = null,
    ): AppShellKit.AssembledShell = AppShellKit.assemble(
        filesDir = files,
        cacheDir = cache,
        schedulerProvider = provider,
        screenGate = screenGate,
        a11yHandler = a11yHandler,
        screenHandler = screenHandler,
        systemHandlers = systemHandlers,
        scriptSources = scriptSources,
        scriptProjects = scriptProjects,
        assetReader = assetReader,
    )

    @Test
    fun `装配落位目录约定并挂上四个命名空间`() {
        kit().use { assembled ->
            assertTrue(Files.isDirectory(files.resolve(".autojs")), "§10.2：.autojs 是意图日志/档案/审批账本的家")
            assertTrue(Files.isDirectory(files.resolve("scripts")), "§10.2：项目根 = files/scripts")
            assertNotNull(assembled.npmHandler, "npm 命名空间由配方自建并挂上")
        }
    }

    /**
     * 看门狗开机即转（§8.4）：不转的话三路判据只是"可以转"，在途 run 的出格行为
     * （心跳停摆/CPU 风暴/状态分歧）没有一个周期性观察者；且关壳必须停掉轮转
     * —— 否则轮转会继续去问一个已经关掉的池。
     */
    @Test
    fun `看门狗随装配开转，关壳即停`() {
        val s = kit()
        assertTrue(s.shell.watchdog.isRunning(), "装壳即开始轮转（无需调用方再记一步）")
        s.close()
        assertFalse(s.shell.watchdog.isRunning(), "关壳必须停轮转：池/持久句柄都在它底下")
    }

    /** 调用方自带域时不夺所有权：关壳不停别人的域（谁给域谁负责停）。 */
    @Test
    fun `调用方给域时关壳不动它`() {
        val mine = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default,
        )
        val s = AppShellKit.assemble(
            filesDir = files,
            cacheDir = cache,
            schedulerProvider = RecordingProvider(),
            screenGate = ScreenGate.AllowAll,
            watchdogScope = mine,
        )
        assertTrue(s.shell.watchdog.isRunning())
        s.close()
        assertTrue(s.shell.watchdog.isRunning(), "壳不取消不属于它的域")
        (mine.coroutineContext[kotlinx.coroutines.Job])!!.cancel()
    }

    @Test
    fun `触发经调度落到诚实引擎：CRASHED + 真原因成对写进两个持久寄存器`() = runBlocking {
        val provider = RecordingProvider()
        val s = kit(provider = provider)
        s.use { assembled ->
            val shell = assembled.shell
            shell.scheduler.schedule(
                ScheduledTask("t1", "任务", "p1", "a.js", TimedSchedule.Once(0)),
            )
            assertTrue("t1" in provider.registered, "排期经注入的 provider 注册触发")

            shell.scheduler.onTrigger("t1", TriggerSource.TIMED, System.currentTimeMillis())
        }

        // 意图日志：COMMIT 行落盘，outcome = Crashed(真原因)
        val log = PersistentIntentLog(JournalFileStore(files.resolve(".autojs")))
        try {
            val row = log.all().single { it.projectId == "p1" }
            val outcome = assertInstanceOf(
                RunOutcome.Crashed::class.java,
                row.outcome,
            )
            assertTrue(
                outcome.message!!.contains("ERR_NOT_IMPLEMENTED"),
                "真原因进日志（任务中心按 runId 读得到为何没跑）：${outcome.message}",
            )
        } finally {
            log.close()
        }

        // 运行档案：**如实无记录** —— §8.5 纪律「link 为 null（门禁拒绝/排队超时/启动失败）
        // 绝不写孤儿档案」。启动从未成功 = 没有 engineRunId = 没有执行可归档；
        // 这次未执行的事实由意图日志那条 COMMIT 行（上面）承担，两处不重复记账。
        val archive = FileRunArchive(files.resolve(".autojs"))
        try {
            assertTrue(
                archive.recordsOfProject("p1").isEmpty(),
                "启动失败不写档案（没有执行，绝不造孤儿记录）",
            )
            assertTrue(archive.unfinished().isEmpty(), "档案里不得留未终态记录")
        } finally {
            archive.close()
        }

        Unit
    }

    @Test
    fun `真起引擎时档案落终态记录 + 双 id 关联`() = runBlocking {
        val s = AppShellKit.assemble(
            filesDir = files,
            cacheDir = cache,
            schedulerProvider = RecordingProvider(),
            screenGate = ScreenGate.AllowAll,
            engineFactory = { id -> FakeEngineForDispatcher(id, pid = 4242, autoExitAfterMillis = 20) },
        )
        s.use { assembled ->
            val shell = assembled.shell
            shell.scheduler.schedule(ScheduledTask("t9", "任务", "p9", "a.js", TimedSchedule.Once(0)))
            shell.scheduler.onTrigger("t9", TriggerSource.TIMED, System.currentTimeMillis())
        }

        val archive = FileRunArchive(files.resolve(".autojs"))
        try {
            val rec = archive.recordsOfProject("p9").single()
            assertEquals(RunState.SUCCEEDED, rec.state, "脚本自退出 → Succeeded")
            assertTrue(rec.state.isTerminal, "档案落地即终态（无 RUNNING 残留）")
            assertNotNull(archive.link(rec.id), "双 id 关联成对写入（可按 IntentRun 追溯）")
            assertTrue(archive.unfinished().isEmpty(), "不得留未终态记录")
        } finally {
            archive.close()
        }

        Unit
    }

    @Test
    fun `屏幕门禁拒绝时不投递：日志无悬挂、档案无记录`() = runBlocking {
        val s = kit(screenGate = ScreenGate { ScreenGateDecision.Deny("测试：亮屏条件不满足") })
        s.use { assembled ->
            val shell = assembled.shell
            shell.scheduler.schedule(
                ScheduledTask(
                    "t2", "任务", "p2", "a.js",
                    TimedSchedule.Once(0),
                    screen = ScreenGuarantee.SCREEN_ON,
                ),
            )
            shell.scheduler.onTrigger("t2", TriggerSource.TIMED, System.currentTimeMillis())
        }

        val archive = FileRunArchive(files.resolve(".autojs"))
        try {
            assertTrue(archive.recordsOfProject("p2").isEmpty(), "门禁拒绝 = 没有引擎执行 = 不写档案")
        } finally {
            archive.close()
        }
        val log = PersistentIntentLog(JournalFileStore(files.resolve(".autojs")))
        try {
            log.all().forEach {
                assertEquals(
                    RunOutcome.Failed, it.outcome,
                    "门禁拒绝 → Failed 且已封账（不留悬挂意向）",
                )
            }
        } finally {
            log.close()
        }

        Unit
    }

    @Test
    fun `能力缝缺省不挂则如实 ERR_NOT_IMPLEMENTED，注入真 handler 则可达`() = runBlocking {
        kit().use { bare ->
            val resp = bare.shell.router.dispatch(BridgeRequest(1, "a11y", "canPerformGestures", null, 5_000))
            val err = assertInstanceOf(BridgeResponse.Err::class.java, resp)
            assertEquals("ERR_NOT_IMPLEMENTED", err.errorCode, "未接线就如实回 NOT_IMPLEMENTED，绝不伪造可用")
        }

        // 注入 handler（真机由 Activity 从 :platform:capabilities 取了真实现走同一条缝；
        // :app 的 test 源集看不到 :platform（§6），故这里用同形状的替身证明"缝是通的"）
        val injected = NamespaceHandler { request ->
            BridgeResponse.Ok(request.id, """{"ref":{"refId":7,"generation":1}}""")
        }
        kit(a11yHandler = injected).use { wired ->
            val findOne = wired.shell.router.dispatch(
                BridgeRequest(2, "a11y", "findOne", """{"conditions":{"text":"确定"}}""", 5_000),
            )
            val ok = assertInstanceOf(BridgeResponse.Ok::class.java, findOne)
            assertEquals("""{"ref":{"refId":7,"generation":1}}""", ok.payload, "注入的 handler 直达桥面")
        }

        Unit
    }

    @Test
    fun `重启后 bootRecover 从落盘的悬挂意向重投（自装配路径同样成立）`() = runBlocking {
        // 上一次进程：START 落盘、未 COMMIT（崩溃的样子）
        val first = PersistentIntentLog(JournalFileStore(files.resolve(".autojs")))
        first.appendStart("p3", "a.js", "nonce-crash", TriggerSource.TIMED, System.currentTimeMillis())
        first.close()

        val s = kit()
        s.use { assembled ->
            val recovered = assembled.shell.bootRecover()
            assertEquals(1, recovered.size, "落盘的悬挂意向必须被重投")
            assertEquals("nonce-crash", recovered.single().runNonce, "nonce 保留（幂等锚点）")
            assertInstanceOf(
                RunOutcome.Crashed::class.java,
                recovered.single().outcome,
                "重投仍走诚实引擎：CRASHED（真原因在 message 里）",
            )
        }

        Unit
    }

    /**
     * 装配期脚本补部署（§9.6）：`files/scripts` 被清掉但装配来源还在时，
     * 缺的脚本在装配时补上 —— 否则投递出去的 run 只会以"文件不存在"告终。
     */
    @Test
    fun `装配时缺的脚本被补上，已有文件不覆盖`() {
        // 先手写一个用户改过的脚本：补部署不得盖掉它
        val edited = files.resolve("scripts").resolve("p1").resolve("main.js")
        val sources = mapOf(
            "p1" to mapOf("main.js" to "console.log(2)".toByteArray()),
            "p2" to mapOf("a.js" to "console.log(9)".toByteArray()),
        )
        AppShellKit.assemble(
            filesDir = files,
            cacheDir = cache,
            schedulerProvider = RecordingProvider(),
            scriptSources = mapOf("p1" to mapOf("main.js" to "// 用户手改".toByteArray())),
        ).use { first ->
            assertEquals(
                listOf("p1/main.js"),
                first.deployReport.deployed.map { "${it.projectId}/${it.relPath}" },
                "第一次装配：缺 main.js 即补上（这是它的唯一职责）",
            )
        }
        kit(scriptSources = sources).use { assembled ->
            val filled = assembled.deployReport.deployed.map { "${it.projectId}/${it.relPath}" }
            assertTrue("p2/a.js" in filled, "缺的脚本被补上：$filled")
            assertTrue("p1/main.js" !in filled, "已有的不覆盖")
            assertTrue(assembled.deployFailures().isEmpty(), "本批无失败")
            assertEquals(
                "// 用户手改",
                Files.readAllBytes(edited).toString(Charsets.UTF_8),
                "用户手改的内容原样留着",
            )
        }

        Unit
    }

    /**
     * 注册表持久（§8.6 调度持久性）：schedule 落 `tasks.jsonl`，重启后新壳
     * `bootRecover` 先续排（闹钟重新注册）—— AlarmManager 里还响的闹钟有人接，
     * Daily 任务不会因进程一退就永久停排。
     */
    @Test
    fun `重启后注册表恢复：任务仍在且闹钟续排`() = runBlocking {
        val first = RecordingProvider()
        kit(provider = first).use { assembled ->
            assembled.shell.scheduler.schedule(
                ScheduledTask("t7", "每日", "p7", "a.js", TimedSchedule.Daily(9, 30)),
            )
            assertTrue("t7" in first.registered, "首次登记注册闹钟")
        }

        val second = RecordingProvider()
        kit(provider = second).use { assembled ->
            assertTrue(assembled.shell.scheduler.tasks().isEmpty(), "新壳内存是空的（恢复前）")
            assembled.shell.bootRecover()
            assertEquals(
                listOf("t7"),
                assembled.shell.scheduler.tasks().map { it.id },
                "bootRecover 先续排：注册表从 tasks.jsonl 重建",
            )
            assertTrue("t7" in second.registered, "闹钟续排到新 provider（旧句柄随关壳失效）")
            // 投递仍可用：续排不是摆设
            assembled.shell.scheduler.onTrigger("t7", TriggerSource.TIMED, System.currentTimeMillis())
        }

        val log = PersistentIntentLog(JournalFileStore(files.resolve(".autojs")))
        try {
            assertTrue(log.all().any { it.projectId == "p7" }, "续排后的任务可正常投递落日志")
        } finally {
            log.close()
        }

        Unit
    }

    /**
     * 资产来源合并（§9.6 `assets/scripts/<projectId>/` 生产接线）：
     * 显式 scriptSources 优先，assets 按 projectId 补缺的项目；单项目读失败不带走整批。
     */
    @Test
    fun `资产来源按项目补缺：显式优先、读失败跳过不炸`() {
        val requested = mutableListOf<String>()
        kit(
            scriptSources = mapOf("p1" to mapOf("main.js" to "// 显式".toByteArray())),
            scriptProjects = listOf("p1", "p2", "p3"),
            assetReader = { projectId ->
                requested += projectId
                when (projectId) {
                    "p1" -> mapOf("main.js" to "// 资产（必须被显式盖住不读都行）".toByteArray())
                    "p2" -> mapOf("a.js" to "// 资产补".toByteArray())
                    else -> throw java.io.IOException("p3 资产损坏")
                }
            },
        ).use { assembled ->
            assertTrue("p1" !in requested, "显式有的项目不读资产：$requested")
            assertEquals(
                "// 显式",
                Files.readAllBytes(files.resolve("scripts").resolve("p1").resolve("main.js"))
                    .toString(Charsets.UTF_8),
                "同项目以显式为准（资产不覆盖）",
            )
            assertEquals(
                "// 资产补",
                Files.readAllBytes(files.resolve("scripts").resolve("p2").resolve("a.js"))
                    .toString(Charsets.UTF_8),
                "缺的项目从资产补",
            )
            assertTrue(assembled.deployFailures().isEmpty(), "p3 读失败跳过，不进失败账（无此项目可补）")
        }

        Unit
    }

    /**
     * systemHandlers 透传缝（§9.4/§9.6）：配方只搬运不 new 实现——注入则桥面可达，缺省则如实 ERR_NOT_IMPLEMENTED。
     *
     * 为什么这条需要单独验配方而不是只验 AppShell：AppShellSystemMountTest 验的是“给了就挂上”，
     * 这条验的是“给了配方也真得递到”——配方若漏传这个束，生产调用处传了也白传（静默丢缺）。
     * 用同形状替身证明“缝是通的”（§6，:test 源集看不到 :platform）。
     */
    @Test
    fun `systemHandlers 缺省不挂桥面如实未实现，注入则透传到位`() = runBlocking {
        kit().use { bare ->
            val resp = bare.shell.router.dispatch(BridgeRequest(1, "device", "model", null, 5_000))
            val err = assertInstanceOf(BridgeResponse.Err::class.java, resp)
            assertEquals("ERR_NOT_IMPLEMENTED", err.errorCode, "配方缺省不挂：不伪造可用")
        }

        val device = NamespaceHandler { request ->
            BridgeResponse.Ok(request.id, "\"Pixel 8\"")
        }
        kit(systemHandlers = SystemHandlers(device = device)).use { wired ->
            val ok = assertInstanceOf(
                BridgeResponse.Ok::class.java,
                wired.shell.router.dispatch(BridgeRequest(2, "device", "model", null, 5_000)),
            )
            assertEquals("\"Pixel 8\"", ok.payload, "配方透传的 handler 直达桥面")

            // 束里没给的命名空间仍如实未实现——透传不拉上整束伪装。
            val missing = wired.shell.router.dispatch(BridgeRequest(3, "shell", "exec", "{}", 5_000))
            assertEquals("ERR_NOT_IMPLEMENTED", (missing as BridgeResponse.Err).errorCode)
        }

        Unit
    }

    @Test
    fun `无来源时补部署报告如实为空，不粉饰恢复成功`() {
        kit().use { assembled ->
            assertFalse(assembled.deployReport.changed, "没有来源 = 什么都没补，不得报成恢复成功")
            assertTrue(assembled.deployFailures().isEmpty(), "空清单不是失败，是没得补")
        }

        Unit
    }
}
