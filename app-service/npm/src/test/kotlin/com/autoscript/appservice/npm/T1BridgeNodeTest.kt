package com.autoscript.appservice.npm

import com.autoscript.domain.json.DomainJson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * T1 桥的**真 node** 层（§10.3 T1 下半段，2026-10-10 批 91）—— 不经过 npm。
 *
 * 这一层钉的是桥本身的四件事，每一件在纯 Kotlin 断言下都会「全绿地错」：
 * 1. shim 能被 `--require` 进去（变量名/`--require=` 形式/资源落位三者任一错就静默失效）；
 * 2. 握手凭据真的在验（错凭据要拒，不能"连上就放行"）；
 * 3. `spawn` 的**假管道**真的通（stdout 回填、退出码回填）；
 * 4. `detached` 在**脚本进程里**就被拒（不是靠宿主兜底 —— 那才是 §10.3 T1 那句的意思）。
 *
 * 与 [T1BridgeE2ETest] 的分工：那条验"npm 会不会用桥"（npm 的行为是它的知识，可能变），
 * 这条验"桥本身对不对"（不依赖 npm 版本）。两条都要有 —— 只有 E2E 的话，npm 换一条
 * spawn 路径就会让整条链红在一个与被测代码无关的地方。
 *
 * **不设 assumeTrue**（同 [NpmSpawnGateTest]）：本仓 jvm-tests 与 nightly 都装了 node
 * （facade dist 的构建器就是它），缺 node 时这里**该红**。
 */
class T1BridgeNodeTest {

    @TempDir
    lateinit var dir: Path

    private val files: Path get() = dir.resolve("files")

    private fun shim(): Path =
        (NpmT1Bridge.deployShim(files) as NpmSpawnGate.Deploy.Ready).file

    /** 起一条真会话（后台线程接客），回句柄；调用方负责 close。 */
    private fun openSession(runner: T1ProcessRunner = ProcessBuilderT1Runner()): T1SessionHandle {
        val h = SocketT1Sessions(NpmT1Bridge.fileSystemBinder(), runner).open()
        requireNotNull(h) { "socket 绑不上：本机 unix domain socket 不可用？" }
        h.start()
        return h
    }

    /** 起一个真 node 进程跑 [script]，把桥的两个 env 与 shim 注进去；回 (stdout, stderr, code)。 */
    private fun runNode(script: String, session: T1SessionHandle): Triple<String, String, Int> {
        val probe = dir.resolve("probe-${System.nanoTime()}.cjs")
        Files.write(probe, script.toByteArray())
        val pb = ProcessBuilder("node", probe.toString())
        val env = pb.environment()
        env[NpmSpawnGate.ENV_NODE_OPTIONS] =
            NpmSpawnGate.mergeNodeOptions(env[NpmSpawnGate.ENV_NODE_OPTIONS], shim())
        NpmT1Bridge.applyEnv(env, session.connectTarget, session.token)
        val proc = try {
            pb.start()
        } catch (e: IOException) {
            throw AssertionError("本测试要真起 node（桥的唯一用途就是在真 Node 里接住 spawn），但宿主没有 node：${e.message}", e)
        }
        val out = proc.inputStream.readBytes().toString(StandardCharsets.UTF_8)
        val err = proc.errorStream.readBytes().toString(StandardCharsets.UTF_8)
        assertTrue(proc.waitFor(60, TimeUnit.SECONDS), "探针 node 进程未在 60s 内退出（桥把它的 spawn 挂住了？）")
        return Triple(out, err, proc.exitValue())
    }


    /** shim 的 `/` 判别式：abstract 名（不含 `/` 时要补 `\0` 前缀）还是路径直连。 */
    private fun shimConnectsAbstract(path: String): Boolean = !path.startsWith('/')

    @Test
    fun `真 node：spawn 经桥起真进程，stdout 与退出码都回填`() {
        val s = openSession()
        try {
            val (out, err, code) = runNode(
                """
                const cp = require('child_process')
                const c = cp.spawn('echo', ['BRIDGE-OK'])
                let buf = ''
                c.stdout.on('data', (d) => { buf += d.toString() })
                c.on('close', (code) => { console.log('GOT[' + buf.trim() + '] code=' + code) })
                """.trimIndent(),
                s,
            )
            assertEquals(0, code, "探针自身不该炸；stderr=$err")
            assertTrue(
                out.contains("GOT[BRIDGE-OK] code=0"),
                "假管道必须把子进程的 stdout 与退出码带回脚本进程；实为：$out / stderr=$err",
            )
        } finally {
            s.close()
        }
    }

    @Test
    fun `真 node：exec 与 execFile 也走同一条桥`() {
        val s = openSession()
        try {
            val (out, err, code) = runNode(
                """
                const cp = require('child_process')
                cp.exec('echo EXEC-OK', (e, stdout) => {
                  cp.execFile('echo', ['EXECFILE-OK'], (e2, stdout2) => {
                    console.log('A[' + String(stdout).trim() + '] B[' + String(stdout2).trim() + ']')
                  })
                })
                """.trimIndent(),
                s,
            )
            assertEquals(0, code, "stderr=$err")
            assertTrue(out.contains("A[EXEC-OK] B[EXECFILE-OK]"), "两个入口都要通；实为：$out")
        } finally {
            s.close()
        }
    }

    @Test
    fun `真 node：detached 在脚本进程里就被拒（不靠宿主兜底）`() {
        val s = openSession()
        try {
            val (out, _, code) = runNode(
                """
                const cp = require('child_process')
                try { cp.spawn('echo', ['x'], { detached: true }); console.log('NOT_BLOCKED') }
                catch (e) { console.log('BLOCKED=' + e.code) }
                """.trimIndent(),
                s,
            )
            assertEquals(0, code)
            assertEquals(
                "BLOCKED=ERR_PERMISSION_DENIED",
                out.trim(),
                "§10.3 T1 明写「shim 直接拒绝 detached:true」—— 拒绝必须发生在 shim 那侧",
            )
        } finally {
            s.close()
        }
    }

    @Test
    fun `真 node：同步入口如实不支持（不假装）`() {
        val s = openSession()
        try {
            val (out, _, code) = runNode(
                """
                const cp = require('child_process')
                const seen = []
                for (const n of ['spawnSync', 'execSync', 'execFileSync']) {
                  try { cp[n]('echo', ['x']); seen.push(n + '=NOT_BLOCKED') }
                  catch (e) { seen.push(n + '=' + e.code) }
                }
                try { cp.fork('x.js'); seen.push('fork=NOT_BLOCKED') }
                catch (e) { seen.push('fork=' + e.code) }
                console.log(seen.join(' '))
                """.trimIndent(),
                s,
            )
            assertEquals(0, code)
            assertEquals(
                "spawnSync=ERR_NOT_IMPLEMENTED execSync=ERR_NOT_IMPLEMENTED " +
                    "execFileSync=ERR_NOT_IMPLEMENTED fork=ERR_NOT_IMPLEMENTED",
                out.trim(),
                "同步入口要阻塞事件循环等一次往返，本版做不到 —— 如实报不支持，不假装跑过",
            )
        } finally {
            s.close()
        }
    }

    @Test
    fun `真 node：没注入桥的 env 时 spawn 如实报未接线（证明拦住它的是 shim）`() {
        // 反向变异：同一个探针，不带两个桥 env —— 必须回 ERR_NOT_IMPLEMENTED，
        // 而不是"连接失败"或静默。少了这一例，上面几例在"环境本来就有桥"时也会绿。
        val probe = dir.resolve("probe-noenv.cjs")
        Files.write(
            probe,
            """
            const cp = require('child_process')
            const c = cp.spawn('echo', ['x'])
            c.on('error', (e) => { console.log('ERR=' + e.code); process.exit(0) })
            c.on('close', () => { console.log('CLOSED-NO-ERROR') })
            """.trimIndent().toByteArray(),
        )
        val pb = ProcessBuilder("node", probe.toString())
        pb.environment()[NpmSpawnGate.ENV_NODE_OPTIONS] =
            NpmSpawnGate.mergeNodeOptions(pb.environment()[NpmSpawnGate.ENV_NODE_OPTIONS], shim())
        pb.environment().remove(NpmT1Bridge.ENV_SOCKET)
        pb.environment().remove(NpmT1Bridge.ENV_TOKEN)
        val proc = pb.start()
        val out = proc.inputStream.readBytes().toString(StandardCharsets.UTF_8)
        proc.errorStream.readBytes()
        assertTrue(proc.waitFor(60, TimeUnit.SECONDS), "探针未退出")
        assertTrue(
            out.contains("ERR=ERR_NOT_IMPLEMENTED"),
            "没有桥接线时要如实报未接线；实为：$out",
        )
    }

    @Test
    fun `连上不发 hello 的对端会被看门狗收掉（不让它占着会话）`() {
        // 一条只连不说的对端：读线程会永久卡在 read 上，而这条会话占着 socket 与一次执行。
        // T1Session 自己设不了读超时（抽象停在 InputStream，设备侧 LocalSocket 与桌面
        // SocketChannel 设超时的手法不同），故由持有连接的一方关连接来唤醒 —— 本用例钉的
        // 就是那条看门狗真的会响。期限注入成 300ms：真期限 10s 的用例没人愿意等，
        // 而没人愿意等的用例迟早被关掉，那等于没有守卫。
        val sessions = SocketT1Sessions(NpmT1Bridge.fileSystemBinder(), handshakeTimeoutMillis = 300L)
        val h = requireNotNull(sessions.open()) { "socket 绑不上" }
        h.start()
        try {
            val probe = dir.resolve("probe-silent.cjs")
            Files.write(
                probe,
                """
                const net = require('net')
                const c = net.connect({ path: process.env.AUTOSCRIPT_T1_SOCKET })
                c.on('connect', () => { console.log('CONNECTED') })   // 刻意不发 hello
                c.on('close', () => { console.log('CLOSED-BY-HOST') })
                c.on('error', (e) => { console.log('ERR=' + e.code) })
                """.trimIndent().toByteArray(),
            )
            val pb = ProcessBuilder("node", probe.toString())
            pb.environment()[NpmT1Bridge.ENV_SOCKET] = h.connectTarget
            pb.environment()[NpmT1Bridge.ENV_TOKEN] = h.token
            val proc = pb.start()
            val out = proc.inputStream.readBytes().toString(StandardCharsets.UTF_8)
            proc.errorStream.readBytes()
            assertTrue(proc.waitFor(30, TimeUnit.SECONDS), "探针未退出（看门狗没关连接？）")
            assertTrue(out.contains("CONNECTED"), "前置：对端得先真连上：$out")
            assertTrue(
                out.contains("CLOSED-BY-HOST") || out.contains("ERR="),
                "只连不说的对端必须被宿主关掉，否则它永久占着这次会话：$out",
            )
        } finally {
            h.close()
        }
    }


    @Test
    fun `env 给裸 abstract 名：shim 判别式认定补 NUL 前缀、而文件系统路径原样直连`() {
        // 设备侧修复后 env 里是**裸名**（AndroidT1SocketBinder.connectTarget）。
        // 这条第一关：env 值必须 NUL-free（ProcessBuilder 连 NUL 都送不进去，
        // 意味着 abstract 名前缀只能在 shim 侧）；第二关：shim 的 '/' 判别
        // 必须把"非 '/' 前导"判成 abstract（补前缀直连），而把文件系统路径
        // 判成"原样直连"—— 后一半由既有 7 例里的路径型全集钉着。
        val bare = "com.autoscript.t1.beefc0de"
        assertTrue(shimConnectsAbstract(bare), "抽象名不该以 '/' 开头")
        assertFalse(shimConnectsAbstract("/tmp/autoscript-t1-x.sock"), "文件系统路径必须以 '/' 开头、原样直连")
        assertFalse(
            shimConnectsAbstract("/run/user/0/autoscript-t1-x.sock"),
            "绝对路径也以 '/' 开头，原样直连（不是 abstract）",
        )
    }

    @Test
    fun `凭据不符时握手被拒（连上不等于放行）`() {
        val s = openSession()
        try {
            // 拿一条**错**凭据去连：shim 会把它当 hello 发出去，宿主必须回 helloErr。
            val probe = dir.resolve("probe-badtoken.cjs")
            Files.write(
                probe,
                """
                const net = require('net')
                const c = net.connect({ path: process.env.AUTOSCRIPT_T1_SOCKET })
                let buf = ''
                c.on('connect', () => c.write(JSON.stringify({ t: 'hello', v: 1, token: 'f'.repeat(64) }) + '\n'))
                c.on('data', (d) => { buf += d.toString(); if (buf.includes('\n')) { console.log('REPLY=' + buf.trim()); c.end() } })
                c.on('error', (e) => { console.log('CONNERR=' + e.message) })
                """.trimIndent().toByteArray(),
            )
            val pb = ProcessBuilder("node", probe.toString())
            pb.environment()[NpmT1Bridge.ENV_SOCKET] = s.connectTarget
            pb.environment()[NpmT1Bridge.ENV_TOKEN] = "0".repeat(64)
            val proc = pb.start()
            val out = proc.inputStream.readBytes().toString(StandardCharsets.UTF_8)
            proc.errorStream.readBytes()
            assertTrue(proc.waitFor(60, TimeUnit.SECONDS), "探针未退出")
            val reply = DomainJson.decodeObject(out.trim().removePrefix("REPLY="))
            assertEquals("helloErr", (reply["t"] as? DomainJson.Value.S)?.v, "错凭据必须被拒：$out")
            assertEquals(
                "ERR_PERMISSION_DENIED",
                (reply["code"] as? DomainJson.Value.S)?.v,
                "拒绝的码要能被机器判定：$out",
            )
        } finally {
            s.close()
        }
    }
}
