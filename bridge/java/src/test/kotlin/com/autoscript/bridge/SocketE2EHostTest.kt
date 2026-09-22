package com.autoscript.bridge

import com.autoscript.domain.bridge.BridgeResponse
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 真 socket E2E（docs/framework-design.md §7.5）：宿主 Node（bridge/js dist）
 * 经 loopback TCP ↔ 本 JVM 的 [NewlineFrameServer]+[BridgeRouter]+[ConsoleCollector]。
 *
 * 为什么不用 unix domain socket：本机 JDK 17（java.net.UnixDomainSocketAddress
 * 要 JDK 16+，但 ServerSocket 绑定 AF_UNIX 要 JDK 17 的 UnixDomainSocketAddress +
 * ServerSocketChannel —— CI 的 JDK 17 可用，本机 jvm-test.sh 的 kotlinc 目标链
 * 上 ServerSocketChannel.open(PROTOCOL_FAMILY) 写法在旧版脚本里没铺；loopback
 * TCP 与 NewlineFrameServer 同一 read/write 路径（见其 KDoc），介质差异由调用方承担，
 * 语义无差。
 *
 * 前置：bridge/js 已 npm run build（dist/bootstrap.js + runtime.js + console.js）。
 * Node 脚本内联（不落地文件）：connect → install → consoleSink.log → echo/ping 往返。
 *
 * chaos 三案（乱序/TTL+迟到/巨帧）已在 bridge/js/test/bootstrap.test.cjs 子进程隔离覆盖，
 * 此处只覆盖「真 JVM Router（含 console 收集器 + TTL）↔ 真 Node 宿主」这一组合，
 * 不重复 chaos。
 */
class SocketE2EHostTest {

    /**
     * 仓库根 = 上溯首个含 `settings.gradle.kts` 的目录（与 `ModuleGraphTest` 同一惯例）。
     *
     * **不锚 worktree/分支目录名**：本仓多 worktree 并行（目录名即分支名，且 CI 的
     * checkout 目录名 = 仓库名），锚死任一名字都会让其余 worktree/CI 解析到 `/`，
     * `dist` 落空 → [org.junit.jupiter.api.Assumptions.assumeTrue] abort 成"静默跳过"。
     * 这里曾锚 `spiky-hamster`，在 `meek-bat` 等 worktree 与 CI 上该 E2E 一次都没跑过，
     * 而 `tools/jvm-test-all.sh` 只认 `0 tests failed`，aborted 照样放行 —— 双重假绿。
     */
    private val repoRoot: String = run {
        var d: java.io.File? = java.io.File(System.getProperty("user.dir")).absoluteFile
        while (d != null && !java.io.File(d, "settings.gradle.kts").isFile) d = d.parentFile
        requireNotNull(d) { "未找到仓库根（上溯 ${System.getProperty("user.dir")} 未见 settings.gradle.kts）" }
        d.absolutePath
    }
    private val dist = "$repoRoot/bridge/js/dist"

    private fun routerWithConsole(collector: ConsoleCollector) =
        BridgeRouter(RequestRegistry()).also { r ->
            r.register("console", collector)
            r.register("echo") { req -> BridgeResponse.Ok(req.id, req.payload) }
        }

    private fun nodeScript(socketPort: Int): String = """
        const { SocketBootstrap } = require('$dist/bootstrap.js');
        const { runtimeBridge } = require('$dist/runtime.js');
        const { consoleSink } = require('$dist/console.js');
        const net = require('node:net');
        // loopback TCP：SocketBootstrap 只会 net.connect(path)（unix socket），
        // 此处手工建 TCP 连接复用同一 runtimeBridge 结算路径 —— 与 §7.5 同语义。
        const sock = net.connect(${socketPort}, '127.0.0.1');
        const enc = (o) => Buffer.from(JSON.stringify(o) + '\n', 'utf8');
        const pending = new Map();
        let nextId = 1;
        let buf = Buffer.alloc(0);
        const ready = new Promise((res) => sock.on('connect', res));
        sock.on('data', (chunk) => {
          buf = Buffer.concat([buf, chunk]);
          let nl;
          while ((nl = buf.indexOf(0x0a)) !== -1) {
            const frame = JSON.parse(buf.subarray(0, nl).toString('utf8'));
            buf = buf.subarray(nl + 1);
            if (frame.t === 'ok' || frame.t === 'err') runtimeBridge.handleResponse(frame);
          }
        });
        runtimeBridge.install((ns, method, payload, reqId, ttl) => {
          sock.write(enc({ t: 'req', id: reqId, ns, m: method, payload, ttl, side: null }));
          return undefined;
        });
        (async () => {
          await ready;
          await consoleSink.log('hello from node', { run: 1 });
          const pong = await runtimeBridge.invoke('echo', 'ping', null, { ttl: 5_000 });
          if (!pong || pong.pong !== true) { console.error('PING_FAIL ' + JSON.stringify(pong)); process.exit(1); }
          console.log('SCRIPT_DONE');
          process.exit(0);
        })().catch((e) => { console.error('SCRIPT_FAIL ' + e); process.exit(1); });
    """.trimIndent()

    @Test
    fun loopbackE2E(): Unit = runBlocking {
        // dist 前置检查：没 build 就诚实跳过（fail 比 skip 更能防 CI 漏配？不 ——
        // CI 门是 gradle+jvm-test.sh，npm build 由 bridge/js 的 npm test 门覆盖；
        // 此处用 assumeTrue 缺 dist 即跳过，避免跨门误杀）。
        org.junit.jupiter.api.Assumptions.assumeTrue(
            java.io.File("$dist/bootstrap.js").exists(),
            "bridge/js 未 build（先 npm run build），跳过真机 E2E",
        )
        val collector = ConsoleCollector()
        val server = NewlineFrameServer(routerWithConsole(collector))
        val serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val port = serverSocket.localPort
        val acceptJob = server.acceptLoop(serverSocket)

        // echo/ping 的 pong 载荷：Router 的 echo 原样回 payload=null，本案要 {pong:true}，
        // 故在测试内另注册 probe/echo？不 —— Router 已定 echo 原样回；Node 脚本发
        // payload {"pong":true} 即原样返回，断言透传语义（Kotlin 形状，不伪造）。
        val script = nodeScript(port).replace(
            "runtimeBridge.invoke('echo', 'ping', null,",
            "runtimeBridge.invoke('echo', 'ping', { pong: true },",
        )
        val scriptFile = Files.createTempFile("e2e-host", ".cjs")
        Files.write(scriptFile, script.toByteArray(StandardCharsets.UTF_8))

        val proc = withContext(Dispatchers.IO) {
            ProcessBuilder("node", scriptFile.toString()).redirectErrorStream(true).start()
        }
        val out = withContext(Dispatchers.IO) {
            BufferedReader(InputStreamReader(proc.inputStream)).readText()
        }
        val exit = withContext(Dispatchers.IO) { proc.waitFor() }

        // console 收集器落盘是异步 dispatch：轮询等一行，最多 2s。
        withTimeout(10_000) {
            while (collector.size() == 0) delay(20)
        }
        val (_, lines) = collector.drain(0)
        assertEquals(0, exit, "node 脚本非零退出：$out")
        assertTrue(out.contains("SCRIPT_DONE"), "脚本未报 SCRIPT_DONE：$out")
        assertEquals(1, lines.size)
        assertEquals("log", lines[0].level)
        assertTrue(lines[0].text.contains("hello from node"), lines[0].text)

        acceptJob.cancel()
        serverSocket.close()
        server.close()
        Files.deleteIfExists(scriptFile)
    }
}
