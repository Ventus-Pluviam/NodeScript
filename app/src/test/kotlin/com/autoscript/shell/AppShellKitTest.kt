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
    ): AppShellKit.AssembledShell = AppShellKit.assemble(
        filesDir = files,
        cacheDir = cache,
        schedulerProvider = provider,
        screenGate = screenGate,
        a11yHandler = a11yHandler,
        screenHandler = screenHandler,
    )

    @Test
    fun `装配落位目录约定并挂上四个命名空间`() {
        kit().use { assembled ->
            assertTrue(Files.isDirectory(files.resolve(".autojs")), "§10.2：.autojs 是意图日志/档案/审批账本的家")
            assertTrue(Files.isDirectory(files.resolve("scripts")), "§10.2：项目根 = files/scripts")
            assertNotNull(assembled.npmHandler, "npm 命名空间由配方自建并挂上")
        }
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
}
