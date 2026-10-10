package com.autoscript.appservice.npm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import com.autoscript.domain.core.ErrorCode
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit

/**
 * `child_process` 拦截 shim 的落位 / 注入串 / 播报解析，以及**真起 node 的拦截验证**
 * （§10.3 T0 末行「强制注入 child_process 拦截 shim」/ §10.12 末行「零 spawn 不变量漂移」）。
 *
 * 为什么值得真起 node：这份 shim 是**要被 Node 真加载的代码**，它唯一的用途就是「在真的
 * Node 里拦住真的 spawn」。只对 Kotlin 侧断言「我写了 NODE_OPTIONS」等于什么都没验 ——
 * 变量名拼错、`--require` 形式写错（`--require x` vs `--require=x`）、shim 里少替换一个
 * 入口，三种错法在纯 Kotlin 断言下**全绿**。故末两例真起 node 跑一遍。
 *
 * **不设 assumeTrue**（与三个 E2E 类的环境门禁相反）：本仓 jvm-tests 与 nightly 都装了
 * node（facade dist 的构建器就是它），缺 node 时这里**该红** —— 一个「没有 node 就静默跳过」
 * 的守卫等于把「门没跑起来」画成绿勾（TestGuard 的整条纪律）。
 */
class NpmSpawnGateTest {

    @TempDir
    lateinit var dir: Path

    private val files: Path get() = dir.resolve("files")

    // ── 落位 ────────────────────────────────────────────────────────────────

    @Test
    fun `落位到宿主内务目录，且重复落位字节一致时不动盘`() {
        val first = NpmSpawnGate.deploy(files)
        val ready = assertInstanceOf(NpmSpawnGate.Deploy.Ready::class.java, first)
        assertEquals(NpmSpawnGate.gateFile(files), ready.file, "落点 = files/.autojs/npm-spawn-gate.cjs")
        assertTrue(Files.isRegularFile(ready.file), "shim 必须真落盘")
        assertTrue(
            ready.file.parent.fileName.toString() == ".autojs",
            "住宿主内务目录（与 journal / lock.sig 同处），不混进 npm 素材树：${ready.file}",
        )
        val mtime = Files.getLastModifiedTime(ready.file)
        assertInstanceOf(NpmSpawnGate.Deploy.Ready::class.java, NpmSpawnGate.deploy(files))
        assertEquals(mtime, Files.getLastModifiedTime(ready.file), "字节一致 = 不动盘")
    }

    @Test
    fun `落位内容非空且含七个入口的替换`() {
        val ready = assertInstanceOf(
            NpmSpawnGate.Deploy.Ready::class.java,
            NpmSpawnGate.deploy(files),
        )
        val text = String(Files.readAllBytes(ready.file))
        for (name in listOf("spawn", "spawnSync", "exec", "execSync", "execFile", "execFileSync", "fork")) {
            assertTrue(text.contains("'$name'"), "shim 必须替换 child_process.$name")
        }
        assertTrue(text.contains("ERR_NPM_SPAWN_BLOCKED"), "码与 :domain / JS 目录逐字同源")
        assertTrue(text.contains("detached"), "detached 拒绝是 §10.3 T1 明写的一条")
    }

    // ── NODE_OPTIONS 注入串 ────────────────────────────────────────────────

    @Test
    fun `NODE_OPTIONS 追加不覆盖：父环境里那份别人的设置保留`() {
        val gate = NpmSpawnGate.gateFile(files)
        assertEquals("--require=$gate", NpmSpawnGate.mergeNodeOptions(null, gate))
        assertEquals("--require=$gate", NpmSpawnGate.mergeNodeOptions("   ", gate))
        assertEquals("--max-old-space-size=192 --require=$gate", NpmSpawnGate.mergeNodeOptions("--max-old-space-size=192", gate))
    }

    // ── 播报解析 ────────────────────────────────────────────────────────────

    @Test
    fun `播报解析：无播报回 null，多条取最后一条`() {
        assertNull(NpmSpawnGate.blockedIn("npm ERR! 一堆常规输出\nadded 3 packages"), "没有门禁播报 = 这次失败与门禁无关")

        val one = NpmSpawnGate.blockedIn(
            "npm ERR! code 1\n" +
                "${NpmSpawnGate.MARKER} ERR_NPM_SPAWN_BLOCKED: 非批准 spawn 被拦截：child_process.execSync\n" +
                "npm ERR! command failed\n",
        )
        assertEquals("ERR_NPM_SPAWN_BLOCKED", one?.code)
        assertTrue(one!!.detail.contains("execSync"), "详情要点名是哪个入口：${one.detail}")

        val two = NpmSpawnGate.blockedIn(
            "${NpmSpawnGate.MARKER} ERR_NPM_SPAWN_BLOCKED: 第一次\n" +
                "${NpmSpawnGate.MARKER} ERR_PERMISSION_DENIED: 第二次（detached）\n",
        )
        assertEquals("ERR_PERMISSION_DENIED", two?.code, "取最后一条：杀死会话的那次才是病因")
    }

    @Test
    fun `播报码折成领域错误码：三个已知码一一对应，认不出的折回 SPAWN_BLOCKED`() {
        assertEquals(ErrorCode.ERR_NPM_SPAWN_BLOCKED, NpmSpawnGate.errorCodeOf("ERR_NPM_SPAWN_BLOCKED"))
        assertEquals(ErrorCode.ERR_PERMISSION_DENIED, NpmSpawnGate.errorCodeOf("ERR_PERMISSION_DENIED"))
        assertEquals(ErrorCode.ERR_NOT_IMPLEMENTED, NpmSpawnGate.errorCodeOf("ERR_NOT_IMPLEMENTED"))
        assertEquals(
            ErrorCode.ERR_NPM_SPAWN_BLOCKED,
            NpmSpawnGate.errorCodeOf("ERR_SOMETHING_NEW"),
            "shim 与这里漂了：标题仍该是「spawn 被拦了」，原文码由调用方放进 detail",
        )
    }

    // ── 真起 node（shim 的唯一用途就是「在真 Node 里拦住真的 spawn」）──────────

    /** 真 node 跑一段探针，回 (stdout, stderr, exitCode)。node 不在 = 红（见类 KDoc）。 */
    private fun runNode(script: String, env: Map<String, String> = emptyMap()): Triple<String, String, Int> {
        val probe = dir.resolve("probe-${System.nanoTime()}.cjs")
        Files.write(probe, script.toByteArray())
        val pb = ProcessBuilder("node", probe.toString())
            .redirectErrorStream(false)
        pb.environment().putAll(env)
        val proc = try {
            pb.start()
        } catch (e: java.io.IOException) {
            throw AssertionError(
                "本测试要真起 node 验拦截（门禁 shim 的唯一用途），但宿主没有 node：${e.message}",
                e,
            )
        }
        val out = proc.inputStream.readBytes().toString(Charsets.UTF_8)
        val err = proc.errorStream.readBytes().toString(Charsets.UTF_8)
        assertTrue(proc.waitFor(30, TimeUnit.SECONDS), "探针 node 进程未在 30s 内退出")
        return Triple(out, err, proc.exitValue())
    }

    @Test
    fun `真 node：七个入口全被拦，码与拒绝语义正确，stderr 有可识别播报`() {
        val ready = assertInstanceOf(
            NpmSpawnGate.Deploy.Ready::class.java,
            NpmSpawnGate.deploy(files),
        )
        val probe = """
            const cp = require('child_process')
            const out = []
            const tryIt = (label, fn) => {
              try { fn(); out.push(label + '=NOT_BLOCKED') }
              catch (e) { out.push(label + '=' + e.code) }
            }
            tryIt('spawn', () => cp.spawn('echo', ['hi']))
            tryIt('spawnSync', () => cp.spawnSync('echo', ['hi']))
            tryIt('exec', () => cp.exec('echo hi'))
            tryIt('execSync', () => cp.execSync('echo hi'))
            tryIt('execFile', () => cp.execFile('echo', ['hi']))
            tryIt('execFileSync', () => cp.execFileSync('echo', ['hi']))
            tryIt('fork', () => cp.fork('x.js'))
            tryIt('detached', () => cp.spawn('echo', ['hi'], { detached: true }))
            console.log(out.join('\n'))
        """.trimIndent()
        val (out, err, code) = runNode(
            probe,
            mapOf(NpmSpawnGate.ENV_NODE_OPTIONS to NpmSpawnGate.mergeNodeOptions(null, ready.file)),
        )

        assertEquals(0, code, "探针自身不该炸；stderr=$err")
        val seen = out.trim().lines().associate { it.substringBefore('=') to it.substringAfter('=') }
        for (name in listOf("spawn", "spawnSync", "exec", "execSync", "execFile", "execFileSync")) {
            assertEquals("ERR_NPM_SPAWN_BLOCKED", seen[name], "child_process.$name 必须被拦")
        }
        assertEquals("ERR_NOT_IMPLEMENTED", seen["fork"], "fork 是「明确不可行」，不是「非批准」")
        assertEquals("ERR_PERMISSION_DENIED", seen["detached"], "detached:true 是权限面拒绝（§10.3 T1）")

        val blocked = NpmSpawnGate.blockedIn(err)
        assertEquals("ERR_PERMISSION_DENIED", blocked?.code, "真输出的最后一条播报（detached 那次）必须能被捞出来；stderr=$err")
        assertTrue(err.contains(NpmSpawnGate.MARKER), "播报带可识别前缀")
    }

    @Test
    fun `真 node：未装门禁时 spawn 照常（证明拦住它的是 shim，不是环境）`() {
        // 反向变异：同一个探针、不带 NODE_OPTIONS —— 必须**没有**被拦。
        // 少了这一例，上面那例在「node 本来就不让 spawn」的环境里也会绿。
        val (out, _, code) = runNode(
            """
            const cp = require('child_process')
            try {
              const r = cp.spawnSync('echo', ['hi'], { encoding: 'utf8' })
              console.log('NOT_BLOCKED status=' + r.status + ' out=' + r.stdout.trim())
            } catch (e) { console.log('BLOCKED=' + e.code) }
            """.trimIndent(),
        )
        assertEquals(0, code)
        assertTrue(out.startsWith("NOT_BLOCKED status=0"), "无门禁时必须真跑起来：$out")
    }

    // ── 执行体注入（门禁要真到会话进程里才算装上）────────────────────────────

    /**
     * 真起一次 [HostNodeExecutor]：门禁必须经 `NODE_OPTIONS` 出现在**子进程**的环境里。
     *
     * 探针故意 `exit(1)` 并把 `NODE_OPTIONS` 打到 stderr —— 走的是执行体的**失败分支**
     * （成功分支只回一句摘要，读不到子进程环境），顺带把「非门禁失败仍如实报退出码」
     * 这条也钉住：没有 `[npm-spawn-gate]` 播报时不许栽赃给门禁。
     */
    @Test
    fun `执行体把门禁经 NODE_OPTIONS 注入会话进程，且无播报时不栽赃给门禁`() {
        val gate = assertInstanceOf(
            NpmSpawnGate.Deploy.Ready::class.java,
            NpmSpawnGate.deploy(files),
        ).file
        val probe = files.resolve("probe-cli.js")
        Files.createDirectories(probe.parent)
        Files.write(
            probe,
            ("console.error('NODE_OPTIONS=' + (process.env.NODE_OPTIONS || ''));" +
                "console.error('argv=' + process.argv.slice(2).join(','));" +
                "process.exit(1);\n").toByteArray(),
        )
        val projectRoot = dir.resolve("proj")
        Files.createDirectories(projectRoot)
        Files.write(projectRoot.resolve("package.json"), """{"name":"proj","version":"0.0.1"}""".toByteArray())

        val executor = HostNodeExecutor(
            probe,
            dir.resolve("cache"),
            nodeBin = "node",
            spawnGateFile = gate,
        )
        val err = try {
            runBlocking {
                executor.execute(
                    HeavyOp(
                        nonce = "n1",
                        projectId = "proj",
                        args = listOf("install"),
                        projectRoot = projectRoot,
                        stageDir = dir.resolve("stage"),
                        timeoutMillis = 30_000,
                    ),
                    {},
                    OutputSink.None,
                )
            }
            throw AssertionError("探针 exit(1)，执行体必须如实失败")
        } catch (e: RuntimeException) {
            e.message ?: ""
        }

        assertTrue(
            err.contains("--require=${gate.toAbsolutePath()}"),
            "门禁必须以 --require 出现在子进程的 NODE_OPTIONS 里；实为：$err",
        )
        assertTrue(err.contains("退出码 1"), "非门禁失败仍如实报退出码：$err")
        assertTrue(
            !err.contains(NpmSpawnGate.MARKER),
            "没有门禁播报时不许栽赃给门禁：$err",
        )
    }

    /** 反向：不传 [HostNodeExecutor.spawnGateFile] 时不注入（证明上面那例的变量是它）。 */
    @Test
    fun `不传门禁时执行体不注入 NODE_OPTIONS`() {
        val probe = files.resolve("probe-cli2.js")
        Files.createDirectories(probe.parent)
        Files.write(probe, "console.error('NODE_OPTIONS=' + (process.env.NODE_OPTIONS || ''));process.exit(1);\n".toByteArray())
        val projectRoot = dir.resolve("proj2")
        Files.createDirectories(projectRoot)
        Files.write(projectRoot.resolve("package.json"), """{"name":"proj2","version":"0.0.1"}""".toByteArray())

        val executor = HostNodeExecutor(probe, dir.resolve("cache2"), nodeBin = "node")
        val err = try {
            runBlocking {
                executor.execute(
                    HeavyOp("n2", "proj2", listOf("install"), projectRoot, dir.resolve("stage2"), 30_000),
                    {},
                    OutputSink.None,
                )
            }
            throw AssertionError("探针 exit(1)，执行体必须如实失败")
        } catch (e: RuntimeException) {
            e.message ?: ""
        }
        assertTrue(err.contains("NODE_OPTIONS="), "探针该把环境打出来：$err")
        assertFalse(err.contains("--require="), "没传门禁就不该有 --require：$err")
    }
}
