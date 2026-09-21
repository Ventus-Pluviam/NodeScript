package com.autoscript.shell

import com.autoscript.appservice.packager.NpmShellKit
import com.autoscript.appservice.scheduler.core.IntentLog
import com.autoscript.appservice.scheduler.persist.FileRunArchive
import com.autoscript.appservice.scheduler.core.RecoveryRecord
import com.autoscript.appservice.scheduler.core.SchedulerProvider
import com.autoscript.appservice.scheduler.core.TriggerHandle
import com.autoscript.appservice.scheduler.core.TriggerSource
import com.autoscript.appservice.scheduler.persist.JournalFileStore
import com.autoscript.appservice.scheduler.persist.PersistentIntentLog
import com.autoscript.domain.bridge.BridgeRequest
import com.autoscript.domain.bridge.BridgeResponse
import com.autoscript.domain.scripts.isTerminal
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * 生产接线验证（§8.5 持久意图日志 + §10.2/§12.2 npm 真装配）。
 *
 * 两件事，都是"装配产物可用"而非挂载缝本身的形状测试：
 * 1. `JournalFileStore` + `PersistentIntentLog` 喂 `assemble` —— 崩溃遗留
 *    （journal 里未 COMMIT 的行）经 `bootRecover` 真重投（不是只在 scheduler 单测里）；
 * 2. `NpmShellKit.assembleHandler` 喂 `assemble(npmHandler = …)` —— `npm.*`
 *    走到真 `NpmBridgeHandler`（轻操作可用、重操作诚实 `ERR_NOT_IMPLEMENTED`）。
 */
class AppShellProductionWiringTest {

    @TempDir
    lateinit var dir: Path

    private val provider = object : SchedulerProvider {
        override suspend fun registerTrigger(targetFireAtMillis: Long, taskId: String): TriggerHandle =
            TriggerHandle { }
        override suspend fun cancelTrigger(handle: TriggerHandle) = Unit
    }

    private fun shellWith(
        log: IntentLog,
        npmFiles: Path,
        npmCache: Path,
        archiveDir: Path,
    ): AppShell =
        AppShell.assemble(
            engineFactory = { id -> FakeEngineForDispatcher(id, pid = 4242) },
            schedulerProvider = provider,
            intentLog = log,
            // 归档同样走持久形态：重启后任务中心仍可按 IntentRun 追溯（§8.5 双寄存器都落盘）
            runArchive = FileRunArchive(archiveDir),
            heartbeatMillis = { 100L },
            npmHandler = NpmShellKit.assembleHandler(filesDir = npmFiles, cacheDir = npmCache),
        )

    @Test
    fun `持久日志遗留经 bootRecover 真重投，归档落盘可追溯`() = runBlocking {
        val journalDir = dir.resolve("journal")
        val first = PersistentIntentLog(JournalFileStore(journalDir))
        // 模拟崩溃遗留：START 已落盘、未 COMMIT（进程被杀时的样子）
        first.appendStart("p1", "a.js", "nonce-crash", TriggerSource.TIMED, System.currentTimeMillis())
        first.close()

        // 重启：新实例 replay 出同一条遗留
        val second = PersistentIntentLog(JournalFileStore(journalDir))
        assertEquals(1, second.uncommitted().size, "replay 必须找回崩溃遗留")

        val archiveDir = dir.resolve("archive1")
        val shellArchive = FileRunArchive(archiveDir)
        val s = shellWith(second, dir.resolve("files"), dir.resolve("cache"), archiveDir)
        // shell 持有的 archive 与 shellArchive 是两个实例但同目录：FileRunArchive 写即落盘，
        // 断言走 shell 自己的实例（s.runArchive），目录级复用不跨实例读内存。
        s.use {
            val recovered: List<RecoveryRecord> = it.bootRecover()
            assertEquals(1, recovered.size, "一条遗留 → 一条恢复记录")
            assertEquals("nonce-crash", recovered.single().runNonce)
            assertTrue(second.uncommitted().isEmpty(), "恢复后无悬挂意向")

            val newIntentId = recovered.single().newRunId
            val linked = it.runArchive.recordsOfIntent(newIntentId)
            assertEquals(1, linked.size, "恢复重投同样归档成对（新 runId 可追溯）")
        }
        shellArchive.close()
        second.close()

        // 归档落盘：重启后任务中心仍可按 IntentRun 追溯（§8.5 双寄存器都落盘）
        val reopened = FileRunArchive(archiveDir)
        try {
            val all = reopened.recordsOfProject("p1")
            assertEquals(1, all.size, "重启后执行历史不丢")
            assertTrue(all.single().state.isTerminal, "恢复投递落终态")
        } finally {
            reopened.close()
        }

        Unit
    }

    @Test
    fun `真 npm 装配挂上后 npm 轻操作可用重操作诚实`() = runBlocking {
        val archive2 = FileRunArchive(dir.resolve("archive2"))
        val s = shellWith(
            PersistentIntentLog(JournalFileStore(dir.resolve("journal2"))),
            dir.resolve("files2"),
            dir.resolve("cache2"),
            dir.resolve("archive2"),
        )
        s.use {
            val install = it.router.dispatch(
                BridgeRequest(1, "npm", "install", """{"spec":"lodash@4.17.21"}""", 10_000),
            )
            val err = assertInstanceOf(BridgeResponse.Err::class.java, install)
            assertEquals("ERR_NOT_IMPLEMENTED", err.errorCode, "缺省无真引擎：重操作诚实失败")

            val list = it.router.dispatch(
                BridgeRequest(2, "npm", "list", """{"projectId":"main"}""", 10_000),
            )
            val listOk = assertInstanceOf(BridgeResponse.Ok::class.java, list)
            assertEquals("[]", listOk.payload, "无 lockfile：空依赖闭包如实回空列表（不是报错）")
        }
        archive2.close()

        Unit
    }
}
