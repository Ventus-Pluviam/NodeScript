package com.autoscript.e2e

import com.autoscript.appservice.scheduler.core.RunOutcome
import com.autoscript.appservice.scheduler.core.ScheduledTask
import com.autoscript.appservice.scheduler.core.SchedulerProvider
import com.autoscript.appservice.scheduler.core.TimedSchedule
import com.autoscript.appservice.scheduler.core.TriggerHandle
import com.autoscript.appservice.scheduler.core.TriggerSource
import com.autoscript.appservice.scheduler.persist.FileRunArchive
import com.autoscript.appservice.scheduler.persist.JournalFileStore
import com.autoscript.appservice.scheduler.persist.PersistentIntentLog
import com.autoscript.bridge.NewlineFrameServer
import com.autoscript.domain.scripts.RunState
import com.autoscript.domain.scripts.isTerminal
import com.autoscript.engine.nodeprocess.NodeEngineConfig
import com.autoscript.engine.nodeprocess.NodeProcessEngine
import com.autoscript.shell.AppShellKit
import com.autoscript.shell.ScreenGate
import java.io.File
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * 垂直切片（§19 / §7.8 / §8.4 的 JVM 侧全链）：Kotlin spawn → 真 node 进程 →
 * unix socket 桥 → console 上送 + 心跳落账 → 自然退出 → `SUCCEEDED` 归档 + 双 id 关联。
 *
 * **为什么是 unix socket（不是 TCP）**：设备面 `AUTOSCRIPT_HOST_SOCKET` 是 abstract unix 名，
 * 本测用文件系统路径（`/` 开头 = main.cpp 的判别式同款）走 `SocketBootstrap` 的
 * `net.connect(path)` 同一条读写路径 —— 介质语义对齐；localhost TCP 测不到这条。
 *
 * **为什么包是 `com.autoscript.e2e`**：`:app` ArchitectureTest 只禁 shell 装配包碰
 * engine、根包 `com.autoscript`（精确包）碰 bridge —— e2e 包不在其列，可同时握
 * shell 装配 + engine 实现 + bridge 传输，全链测试要的就是这个交汇点。
 * **为什么心跳在此显式 `startHeartbeat`**：kBootstrap 自动心跳住 `main.cpp`（设备
 * noden 宿主）；桌面跑系统 node（无 main.cpp）→ 按 §12.2 显式调用补半边，同一条
 * `engines.heartbeat` 契约（runId 来自 env；宿主只认在途 runId）。
 */
class NodeProcessSpawnE2ETest {

    @TempDir
    lateinit var dir: Path

    private val files: Path get() = dir.resolve("files")
    private val cache: Path get() = dir.resolve("cache")

    /** 上溯首个含 `settings.gradle.kts` 的目录 = 仓库根（与 SocketE2EHostTest 同一惯例）。 */
    private val repoRoot: String = run {
        var d = File(System.getProperty("user.dir")).absoluteFile
        while (d != null && !File(d, "settings.gradle.kts").isFile) d = d.parentFile
        requireNotNull(d) { "未找到仓库根（上溯 ${System.getProperty("user.dir")} 未见 settings.gradle.kts）" }
        d.absolutePath
    }
    private val dist = "$repoRoot/bridge/js/dist"

    private class RecordingProvider : SchedulerProvider {
        val registered = mutableListOf<String>()
        override suspend fun registerTrigger(targetFireAtMillis: Long, taskId: String): TriggerHandle {
            registered += taskId
            return TriggerHandle { }
        }

        override suspend fun cancelTrigger(handle: TriggerHandle) = handle.cancel()
    }

    /** 轮询到观测值（真进程有启动/连桥/打点时延；超时抛 AssertionError 点名没等到什么）。 */
    private suspend fun <T> await(what: String, timeoutMillis: Long = 20_000, probe: suspend () -> T?): T {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            probe()?.let { return it }
            delay(25)
        }
        throw AssertionError("超时（${timeoutMillis}ms）未观测到：$what")
    }

    @Test
    fun `spawn 全链——console 与心跳经 unix 桥上送，档案 SUCCEEDED 双 id 关联`() = runBlocking {
        // dist 是 git 跟踪的（CI 无 npm build 也在）：缺 = 仓库破损，该红不该 assume 跳。
        assertTrue(File(dist, "bootstrap.js").isFile && File(dist, "engines.js").isFile, "dist 缺件：$dist")

        // sun_path 上限 108B，且 Gradle 测试 tmpdir（build/tmp/workers/…）叠目录名易超 —— 固定 /tmp 短名。
        val sockPath = "/tmp/as-e2e-${ProcessHandle.current().pid()}.sock"
        Files.deleteIfExists(Path.of(sockPath))
        val serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        serverChannel.bind(UnixDomainSocketAddress.of(sockPath))

        val scriptBody = """
            const { connectBootstrap } = require('$dist/bootstrap.js');
            const { consoleSink } = require('$dist/console.js');
            const { startHeartbeat } = require('$dist/engines.js');
            (async () => {
              try {
                await connectBootstrap();
                await consoleSink.log(
                  'hello from spawn e2e run=' + process.env.AUTOSCRIPT_RUN_ID +
                  ' nonce=' + (process.env.AUTOSCRIPT_RUN_NONCE || 'MISSING')
                );
                startHeartbeat(+process.env.AUTOSCRIPT_RUN_ID, { periodMillis: 100 });
                await new Promise((r) => setTimeout(r, 600));
                process.exit(0);
              } catch (e) {
                process.stderr.write('e2e script failed: ' + ((e && e.stack) || e) + '\n');
                process.exit(1);
              }
            })();
        """.trimIndent()
        val scriptFile = files.resolve("scripts").resolve("p9").resolve("e2e.js")
        Files.createDirectories(scriptFile.parent)
        Files.write(scriptFile, scriptBody.toByteArray())

        val assembled = AppShellKit.assemble(
            filesDir = files,
            cacheDir = cache,
            schedulerProvider = RecordingProvider(),
            screenGate = ScreenGate.AllowAll,
            engineFactory = { engineId ->
                NodeProcessEngine(
                    engineId,
                    NodeEngineConfig(
                        filesDir = files,
                        hostBinary = Path.of("node"),   // PATH 名：桌面跑系统 node（跳过 jniLibs 预检）
                        hostSocketName = sockPath,      // "/" 开头 = 文件系统 unix（main.cpp 判别式）
                    ),
                )
            },
        )

        val frameServer = NewlineFrameServer(assembled.shell.router)
        val running = AtomicBoolean(true)
        val acceptThread = Thread {
            try {
                while (running.get()) {
                    val ch = serverChannel.accept()
                    // 流版 serveConnection：与 socket 版同一读写路径，介质换成 unix 由本线程 accept
                    frameServer.serveConnection(Channels.newInputStream(ch), Channels.newOutputStream(ch))
                }
            } catch (_: Exception) {
                // 收口路径：关 serverChannel 让 accept 抛出 → 线程退（不吞业务错误——业务在断言面）
            }
        }.apply {
            isDaemon = true
            start()
        }

        var engineRunId: Long = -1
        try {
            assembled.use { shellBundle ->
                val shell = shellBundle.shell
                shell.scheduler.schedule(ScheduledTask("te2e", "全链", "p9", "e2e.js", TimedSchedule.Once(0)))
                val job = launch {
                    shell.scheduler.onTrigger("te2e", TriggerSource.TIMED, System.currentTimeMillis())
                }

                // ① pid 锚点在途：EngineRunReceipt.pid = spawn 瞬间真子进程 pid（§8.4）
                val a = await("watchAnchor(pid>0)") {
                    shell.controller.watchAnchors().firstOrNull { it.pid != null && it.pid!! > 0 }
                }
                engineRunId = a.runId

                // ② 心跳落账：脚本 100ms 一拍 → engines.heartbeat → 只有在途 runId 才进账本；
                //    从未打点回 null —— 拿到非 null 即证明 socket 链 + env RUN_ID + 记账三段通
                val beatAge = await("heartbeatMillis(runId) 非 null") {
                    shell.controller.heartbeatMillis(a.runId)
                }
                assertTrue(beatAge < 1_500, "心跳年龄应在周期量级（看门狗失联线 1500ms）：$beatAge")

                job.join()   // 脚本自然退出 → StoppedClean → Succeeded

                // ③ console 行经 socket 上送（drain 游标 0 = 全量）
                val lines = shell.console.drain(0).second
                val hello = lines.firstOrNull { it.text.contains("hello from spawn e2e") }
                    ?: throw AssertionError("console 行未上送（实际=${lines.map { it.text }}）")
                assertTrue(hello.text.contains("run=$engineRunId"), "env RUN_ID 与锚点/档案同源：${hello.text}")
                assertFalse(hello.text.contains("nonce=MISSING"), "RUN_NONCE 随 spawn env 下传：${hello.text}")
            }
        } finally {
            running.set(false)
            try {
                serverChannel.close()
            } catch (_: Exception) {
            }
            frameServer.close()
            Files.deleteIfExists(Path.of(sockPath))
        }

        // ④ 档案：锚点 runId = 档案 engineRunId = 同一次执行；SUCCEEDED + 双 id 关联 + 无悬挂
        val archive = FileRunArchive(files.resolve(".autojs"))
        try {
            val rec = archive.recordsOfProject("p9").single()
            assertEquals(engineRunId, rec.id, "锚点 runId 与档案记录必须是同一次执行")
            assertEquals(RunState.SUCCEEDED, rec.state, "脚本自退出 → SUCCEEDED")
            assertTrue(rec.state.isTerminal, "落地即终态")
            assertNotNull(archive.link(rec.id), "双 id 关联成对写入")
            assertTrue(archive.unfinished().isEmpty(), "不得留未终态记录")
        } finally {
            archive.close()
        }

        // ⑤ 意图日志：Succeeded 已封账（任务中心按 runId 读得到"跑成了"）
        val log = PersistentIntentLog(JournalFileStore(files.resolve(".autojs")))
        try {
            assertEquals(RunOutcome.Succeeded, log.all().single { it.projectId == "p9" }.outcome)
        } finally {
            log.close()
        }

        Unit   // 显式收尾：表达式体 @Test 返回非 Unit 会被 JUnit 静默跳过（假绿）
    }
}
