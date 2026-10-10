package com.autoscript.e2e

import com.autoscript.appservice.scheduler.core.RunOutcome
import com.autoscript.appservice.scheduler.core.ScheduledTask
import com.autoscript.appservice.scheduler.core.SchedulerProvider
import com.autoscript.appservice.scheduler.core.TimedSchedule
import com.autoscript.appservice.scheduler.core.TriggerHandle
import com.autoscript.appservice.scheduler.core.TriggerSource
import com.autoscript.appservice.scheduler.persist.FileRunArchive
import com.autoscript.domain.scripts.InMemoryIntentStore
import com.autoscript.appservice.scheduler.persist.PersistentIntentLog
import com.autoscript.bridge.NewlineFrameServer
import com.autoscript.domain.permission.CapabilityMask
import com.autoscript.domain.scripts.RunState
import com.autoscript.domain.scripts.isTerminal
import com.autoscript.engine.nodeprocess.NodeEngineConfig
import com.autoscript.engine.nodeprocess.NodeProcessEngine
import com.autoscript.shell.AppShellKit
import com.autoscript.shell.ScreenGate
import java.io.File
import java.net.StandardProtocolFamily
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
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
/**
 * unix 域服务器通道（仅本桌面 E2E 用）：android.jar 桩面没有
 * `ServerSocketChannel.open(ProtocolFamily)` 重载与 `UnixDomainSocketAddress`（Java16 API），
 * 直接引用会把 `testDebugUnitTest` 编译炸掉 —— 反射取，运行期在桌面 JDK 上恒有。
 */
private fun openUnixServerChannel(): ServerSocketChannel {
    val protocolFamily = Class.forName("java.net.ProtocolFamily")   // 实名：ProtocolFamily 住 java.net（记错成 nio.channels 会运行期 ClassNotFound）
    return ServerSocketChannel::class.java
        .getMethod("open", protocolFamily)
        .invoke(null, StandardProtocolFamily.UNIX) as ServerSocketChannel
}

private fun unixAddressOf(path: String): java.net.SocketAddress =
    Class.forName("java.net.UnixDomainSocketAddress")
        .getMethod("of", String::class.java)
        .invoke(null, path) as java.net.SocketAddress

/** Channels.newInputStream/newOutputStream 共享 blockingLock，阻塞 read 会饿死 ACK write。
 * SocketChannel 自身支持一读一写并发；适配层直接调它，不把双向协议降成半双工。 */
private fun serveUnix(server: NewlineFrameServer, channel: SocketChannel) = server.serveConnection(
    object : java.io.InputStream() {
        override fun read(): Int = ByteArray(1).let { if (read(it, 0, 1) < 0) -1 else it[0].toInt() and 255 }
        override fun read(bytes: ByteArray, off: Int, len: Int): Int = channel.read(ByteBuffer.wrap(bytes, off, len))
        override fun close() = channel.close()
    },
    object : java.io.OutputStream() {
        override fun write(value: Int) = write(byteArrayOf(value.toByte()))
        override fun write(bytes: ByteArray, off: Int, len: Int) {
            val buffer = ByteBuffer.wrap(bytes, off, len)
            while (buffer.hasRemaining()) channel.write(buffer)
        }
        override fun close() = channel.close()
    },
    closeConnection = { channel.close() },
)

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
        // dist 是构建产物（步骤 7 出库；CI jvm-tests 前置 npm build，本机 npm run build）：
        // 缺 = 没跑 npm build —— 该红不该 assume 跳（红了照报错文案跑一次 build 即可）。
        assertTrue(File(dist, "bootstrap.js").isFile && File(dist, "engines.js").isFile, "dist 缺件：$dist")

        // sun_path 上限 108B，且 Gradle 测试 tmpdir（build/tmp/workers/…）叠目录名易超 —— 固定 /tmp 短名。
        val sockPath = "/tmp/as-e2e-${System.nanoTime()}.sock"   // 唯一名：ProcessHandle 不在 android.jar 桩面
        Files.deleteIfExists(Path.of(sockPath))
        val serverChannel = openUnixServerChannel()
        serverChannel.bind(unixAddressOf(sockPath))

        val scriptBody = """
            const { connectBootstrap } = require('$dist/bootstrap.js');
            const { consoleSink } = require('$dist/console.js');
            const { startHeartbeat } = require('$dist/engines.js');
            const { runtimeBridge } = require('$dist/runtime.js');
            (async () => {
              try {
                await connectBootstrap();
                let dropped = 0;
                consoleSink.onQueueError(() => dropped++);
                await consoleSink.log(
                  'hello from spawn e2e run=' + process.env.AUTOSCRIPT_RUN_ID +
                  ' nonce=' + (process.env.AUTOSCRIPT_RUN_NONCE || 'MISSING')
                );
                startHeartbeat(+process.env.AUTOSCRIPT_RUN_ID, { periodMillis: 100 });
                await new Promise((r) => setTimeout(r, 600));
                await runtimeBridge.invoke('console', 'log', {level: 'log', text: 'last-acked-row'}, {ttl: 5000});
                if (dropped !== 0) throw new Error('console queueError=' + dropped);
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

        val intentStore = InMemoryIntentStore()
        val assembled = AppShellKit.assemble(
            // T1 桥 socket：桌面单测走 JDK unix domain socket（设备装配传 AndroidT1SocketBinder，见 assemble 的 KDoc）
            npmT1Binder = com.autoscript.appservice.npm.NpmT1Bridge.fileSystemBinder(),
            filesDir = files,
            cacheDir = cache,
            schedulerProvider = RecordingProvider(),
            screenGate = ScreenGate.AllowAll,
            intentStore = intentStore,
            engineFactory = { engineId, identities ->
                NodeProcessEngine(
                    engineId,
                    NodeEngineConfig(
                        filesDir = files,
                        hostBinary = Path.of("node"),   // PATH 名：桌面跑系统 node（跳过 jniLibs 预检）
                        hostSocketName = sockPath,      // "/" 开头 = 文件系统 unix（main.cpp 判别式）
                    ),
                    identityIssuer = identities,
                )
            },
        )

        val frameServer = assembled.shell.frameServer
        val running = AtomicBoolean(true)
        val acceptThread = Thread {
            try {
                while (running.get()) {
                    val ch = serverChannel.accept()
                    // 流版 serveConnection：与 socket 版同一读写路径，介质换成 unix 由本线程 accept
                    serveUnix(frameServer, ch)
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
                assertEquals(engineRunId, hello.runId, "归属取自认证连接，不只是文本里碰巧带 runId")
                assertEquals(engineRunId, lines.single { it.text == "last-acked-row" }.runId)
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
            acceptThread.join(2_000)
            Files.deleteIfExists(Path.of(sockPath))
        }

        // ④ 档案：锚点 runId = 档案 engineRunId = 同一次执行；SUCCEEDED + 双 id 关联 + 无悬挂
        val archive = FileRunArchive(files.resolve(".autojs"))
        try {
            val rec = archive.recordsOfProject("p9").single()
            assertEquals(engineRunId, rec.id, "锚点 runId 与档案记录必须是同一次执行")
            assertEquals(RunState.SUCCEEDED, rec.state, "脚本自退出 → SUCCEEDED：${rec.exitCode} ${rec.crashSummary}")
            assertTrue(rec.state.isTerminal, "落地即终态")
            assertNotNull(archive.link(rec.id), "双 id 关联成对写入")
            assertTrue(archive.unfinished().isEmpty(), "不得留未终态记录")
        } finally {
            archive.close()
        }

        // ⑤ 意图日志：Succeeded 已封账（任务中心按 runId 读得到"跑成了"）。
        // 读的是 ① 那个 store 实例的内存视图（本类改用 InMemoryIntentStore，无落盘可重开）——
        // 本测要证的是「引擎全链跑通后意图日志落终态」，不是"重启后还读得回来"
        // （那条由 §8.5 的 IntentStoreContract 崩溃重放用例守着）。
        val log = PersistentIntentLog(intentStore)
        assertEquals(RunOutcome.Succeeded, log.all().single { it.projectId == "p9" }.outcome)

        Unit   // 显式收尾：表达式体 @Test 返回非 Unit 会被 JUnit 静默跳过（假绿）
    }
    @Test
    fun `真实双脚本同reqId并发不串，同槽重跑和自停均回池`() = runBlocking {
        val socketPath = "/tmp/as-identity-${System.nanoTime()}.sock"
        val listener = openUnixServerChannel().also { it.bind(unixAddressOf(socketPath)) }
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val entered = java.util.concurrent.atomic.AtomicInteger()
        val bothEntered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val script = files.resolve("scripts/p1/id.js")
        Files.createDirectories(script.parent)
        Files.write(script, """
            const { connectBootstrap } = require('$dist/bootstrap.js');
            const { runtimeBridge } = require('$dist/runtime.js');
            (async () => {
              const boot = await connectBootstrap();
              const rid = +process.env.AUTOSCRIPT_RUN_ID;
              await runtimeBridge.invoke('console', 'log', {level:'log',text:'first:'+rid});
              await runtimeBridge.invoke('probe', 'barrier', null, {ttl:15000});
              if (process.argv[2] === 'self-stop') {
                setInterval(() => {}, 1000);
                try { await runtimeBridge.invoke('engines', 'stop', {runId:rid}); } catch (_) {}
                return;
              }
              await runtimeBridge.invoke('console', 'log', {level:'log',text:'last:'+rid});
              boot.close();
              process.exit(0);
            })().catch(e => { console.error(e); process.exit(1); });
        """.trimIndent().toByteArray())
        val assembled = AppShellKit.assemble(
            files, cache, RecordingProvider(), intentStore = InMemoryIntentStore(), poolCapacity = 2,
            // T1 桥 socket：桌面单测走 JDK unix domain socket（设备装配传 AndroidT1SocketBinder，见 assemble 的 KDoc）
            npmT1Binder = com.autoscript.appservice.npm.NpmT1Bridge.fileSystemBinder(),
            engineFactory = { id, issuer -> NodeProcessEngine(
                id, NodeEngineConfig(files, Path.of("node"), hostSocketName = socketPath), identityIssuer = issuer,
            ) },
            // 本条测的是「同 reqId 并发不串 + 同槽重跑 + 自停回池」，与授权分级无关。
            // 脚本会调一个**测试自造**的 `probe` 命名空间（见上面的 shell.mount），
            // 而 A5 的目录对未申报命名空间是**拒**（`UNKNOWN_NAMESPACE_REQUIRED = ALL`）——
            // 缺省 UNKNOWN 档的脚本因此会在触达 handler 前就被 Router 拒掉，卡在 barrier 上。
            // 这是目录的预期语义，不是缺陷：**给测试装配一个受信直投掩码**，
            // 而**不是**往生产目录里给 `probe` 开条目（那等于把测试脚手架写进策略）。
            capabilityMask = CapabilityMask.ALL,
        )
        val shell = assembled.shell
        shell.mount("probe") { request ->
            if (entered.incrementAndGet() == 2) bothEntered.complete(Unit)
            gate.await()
            com.autoscript.domain.bridge.BridgeResponse.Ok(request.id, null)
        }
        val serving = Thread {
            try {
                while (true) {
                    val ch = listener.accept()
                    serveUnix(shell.frameServer, ch)
                }
            } catch (_: java.io.IOException) { /* listener 关闭解除 accept */ }
        }.apply { isDaemon = true; start() }
        fun request(args: List<String> = emptyList()) = com.autoscript.appservice.runtime.PoolAcquireRequest(
            projectId = "p1", scriptPath = "id.js", args = args, waitTimeoutMillis = 5_000,
        )
        suspend fun start(args: List<String> = emptyList()): Long {
            val result = shell.controller.start(request(args))
            return (result as com.autoscript.appservice.runtime.RuntimeController.StartOutcome.Started).runId
        }
        try {
            val a = start()
            val b = start()
            kotlinx.coroutines.withTimeout(10_000) { bothEntered.await() }
            gate.complete(Unit)
            for (run in listOf(a, b)) {
                kotlinx.coroutines.withTimeout(10_000) { shell.controller.awaitCompletion(run, 10_000) }
            }
            val c = start() // 复用已归还的槽位，JS requestId 再次从 1 开始。
            kotlinx.coroutines.withTimeout(10_000) { shell.controller.awaitCompletion(c, 10_000) }
            shell.console.append(0, "info", "host")
            val lines = shell.console.drain(0, 100).second
            for (run in listOf(a, b, c)) {
                assertEquals(listOf("first:$run", "last:$run"), lines.filter { it.runId == run }.map { it.text })
            }
            assertEquals(listOf("host"), lines.filter { it.runId == 0L }.map { it.text })
            assertEquals(3, setOf(a, b, c).size)
            val self = start(listOf("self-stop"))
            await("自停归还两个槽位") { shell.controller.stats().takeIf { it.free == 2 } }
            assertTrue(shell.controller.watchAnchors().none { it.runId == self })
        } finally {
            gate.complete(Unit)
            shell.shutdown(com.autoscript.domain.engine.KillCause.REQUESTED)
            listener.close()
            serving.join(2_000)
            assembled.close()
            Files.deleteIfExists(Path.of(socketPath))
        }
        Unit
    }

}
