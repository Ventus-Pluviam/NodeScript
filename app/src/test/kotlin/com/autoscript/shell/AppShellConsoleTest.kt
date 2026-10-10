package com.autoscript.shell

import com.autoscript.appservice.scheduler.core.SchedulerProvider
import com.autoscript.appservice.scheduler.core.TriggerHandle
import com.autoscript.domain.bridge.BridgeRequest
import com.autoscript.domain.bridge.BridgeResponse
import com.autoscript.domain.bridge.AuthenticatedRunContext
import com.autoscript.domain.permission.CapabilityMask
import com.autoscript.domain.engine.EngineId
import com.autoscript.domain.scripts.InMemoryIntentStore
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * 控制台读口的**装配侧**验证（[AssembledShell.consoleView]）——
 * 也就是 `AppShellApplication.console()` 在真机上会走的那条路。
 *
 * 单元级的游标/投影规则在 [ConsoleReadTest]；这里钉的只有装配层才定的一件事：
 * 读的是**壳自己持有的那一个收集器**（桥上注册的那一个）。另开一份收集器也能"读成功"，
 * 只是永远空 —— 那种假绿只有走真装配才暴露。
 */
class AppShellConsoleTest {

    @TempDir
    lateinit var dir: Path

    private val files: Path get() = dir.resolve("files")
    private val cache: Path get() = dir.resolve("cache")

    private class NoopProvider : SchedulerProvider {
        override suspend fun registerTrigger(targetFireAtMillis: Long, taskId: String): TriggerHandle =
            TriggerHandle { }

        override suspend fun cancelTrigger(handle: TriggerHandle) = handle.cancel()
    }

    private fun kit(): AssembledShell = AppShellKit.assemble(
        // T1 桥 socket：桌面单测走 JDK unix domain socket（设备装配传 AndroidT1SocketBinder，见 assemble 的 KDoc）
        npmT1Binder = com.autoscript.appservice.npm.NpmT1Bridge.fileSystemBinder(),
        filesDir = files,
        cacheDir = cache,
        intentStore = InMemoryIntentStore(),
        schedulerProvider = NoopProvider(),
        screenGate = ScreenGate.AllowAll,
    )

    @Test
    fun `收集器是壳持有的那一个 游标续拉不重不漏`() = runBlocking(AuthenticatedRunContext(EngineId(0), 42, 1, CapabilityMask.ALL)) {
        val s = kit()
        s.use { assembled ->
            // 首读：空壳（没起过引擎、没打过日志）—— 空是事实，不是没读到。
            val empty = assembled.consoleView(sinceSeq = 0, maxLines = 100)
            assertTrue(empty.lines.isEmpty())
            assertEquals(0L, empty.nextSeq)
            assertTrue(empty.activeRuns.isEmpty(), "没起引擎就没有在途 —— 空表是权威事实")

            // 往**桥上注册的那一个**收集器写两行（脚本 console.log 落点即此）。
            assembled.shell.console.append(runId = 0, level = "log", text = "hello")
            assembled.shell.console.append(runId = 7, level = "error", text = "boom")

            val first = assembled.consoleView(sinceSeq = empty.nextSeq, maxLines = 100)
            assertEquals(listOf("hello", "boom"), first.lines.map { it.text })
            assertEquals(2L, first.nextSeq)
            assertEquals(0L, first.droppedTotal)

            // 新输出只增量回来：游标续拉，旧行不重发。
            assembled.shell.console.append(runId = 7, level = "warn", text = "later")
            val second = assembled.consoleView(sinceSeq = first.nextSeq, maxLines = 100)
            assertEquals(listOf("later"), second.lines.map { it.text }, "游标只进不退：旧行不重发、新行不漏")
            assertEquals(3L, second.nextSeq)
        }
        Unit
    }

    @Test
    fun `单批上限由调用方定 拉满标 pageFull`() = runBlocking(AuthenticatedRunContext(EngineId(0), 42, 1, CapabilityMask.ALL)) {
        val s = kit()
        s.use { assembled ->
            repeat(3) { assembled.shell.console.append(runId = 0, level = "log", text = "t$it") }
            val page = assembled.consoleView(sinceSeq = 0, maxLines = 2)
            assertEquals(2, page.lines.size)
            assertTrue(page.pageFull)
            assertEquals(2L, page.nextSeq)
        }
        Unit
    }

    @Test
    fun `启动日志与桥日志落在同一读口 激活之前构造新壳不抢接线`() = runBlocking(AuthenticatedRunContext(EngineId(0), 42, 1, CapabilityMask.ALL)) {
        val writer = HostLogWriter(logcat = { _, _, _, _ -> })
        writer.i("Host", "装配开始")
        kit().use { first ->
            first.shell.connectHostLog(writer)
            val boot = first.consoleView(0, 100)
            assertEquals(listOf("Host: 装配开始"), boot.lines.map { it.text })
            assertTrue(boot.lines.all { it.external })

            val reply = first.shell.router.dispatch(
                BridgeRequest(11, "console", "log", """{"level":"log","text":"script"}""", 5_000),
            )
            assertTrue(reply is BridgeResponse.Ok)
            writer.w("Host", "闹钟异常")
            val next = first.consoleView(boot.nextSeq, 100)
            assertEquals(listOf("script", "Host: 闹钟异常"), next.lines.map { it.text })
            assertEquals(listOf(2L, 3L), next.lines.map { it.seq })
            assertEquals(listOf(42L, 0L), next.lines.map { it.runId })
            assertEquals(listOf(false, true), next.lines.map { it.external })

            AppShellKit.assemble(
                // T1 桥 socket：桌面单测走 JDK unix domain socket（设备装配传 AndroidT1SocketBinder，见 assemble 的 KDoc）
                npmT1Binder = com.autoscript.appservice.npm.NpmT1Bridge.fileSystemBinder(),
                filesDir = dir.resolve("other-files"),
                cacheDir = dir.resolve("other-cache"),
                intentStore = InMemoryIntentStore(),
                schedulerProvider = NoopProvider(),
            ).use { second ->
                writer.i("Host", "尚未换壳")
                assertEquals(listOf("Host: 尚未换壳"), first.consoleView(next.nextSeq, 100).lines.map { it.text })
                assertTrue(second.consoleView(0, 100).lines.isEmpty(), "assemble 不改变日志去向")
                second.shell.connectHostLog(writer)
                first.shell.close()  // 旧壳延迟关闭，不得摘掉新壳刚接上的连接。
                writer.i("Host", "新壳已激活")
                assertEquals(listOf("Host: 新壳已激活"), second.consoleView(0, 100).lines.map { it.text })
            }
        }
        Unit
    }
}
