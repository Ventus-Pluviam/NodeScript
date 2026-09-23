package com.autoscript.engine.nodeprocess

import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.engine.EngineId
import com.autoscript.domain.engine.EngineRunRequest
import com.autoscript.domain.engine.EngineStatus
import com.autoscript.domain.engine.KillCause
import com.autoscript.domain.engine.StopResult
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * `NodeProcessEngine` 契约（docs §8.1/§8.3/§19 Kotlin spawn）：
 * 预检点名绝对路径、env 契约逐键对齐 main.cpp、状态语义按「进程事实」推导、
 * stop/kill 两级终止 + 池侧 TimedOut 兜底、pid 诚实口径、runId 跨实例唯一。
 * 全部走假 [ProcessLauncher] —— argv/env/cwd 可精确断言，不起真进程。
 */
class NodeProcessEngineTest {

    @TempDir
    lateinit var dir: Path

    private val files: Path get() = dir.resolve("files")

    /** 记录 spawn 入参的假缝；进程生命周期由测试逐案脚本化。 */
    private class FakeLauncher : ProcessLauncher {
        var lastCommand: List<String>? = null
        var lastEnv: Map<String, String>? = null
        var lastCwd: Path? = null
        var spawnCount = 0
        var failWith: java.io.IOException? = null
        /** 每次 spawn 返回的进程（测试在 execute 前换引用；execute 后改字段驱动状态推导）。 */
        var nextProcess: FakeProcess = FakeProcess()

        override fun spawn(command: List<String>, env: Map<String, String>, workingDir: Path): SpawnedProcess {
            spawnCount++
            if (failWith != null) throw failWith!!
            lastCommand = command
            lastEnv = env
            lastCwd = workingDir
            return nextProcess
        }
    }

    /** 可脚本化的假进程：存活/退出码/终止调用全部由测试驱动。 */
    private class FakeProcess(
        override val pid: Int? = 4242,
        var alive: Boolean = true,
        var exitCode: Int? = null,
    ) : SpawnedProcess {
        var destroyCalls = 0
        var destroyForciblyCalls = 0
        /** destroy() 后是否退（false = 忽略 SIGTERM，用于 TimedOut 案）。 */
        var exitsOnDestroy = true
        var exitsOnForcibly = true

        override val isAlive: Boolean get() = alive

        override fun exitValue(): Int? = if (alive) null else exitCode

        override fun destroy() {
            destroyCalls++
            if (exitsOnDestroy) {
                alive = false
                exitCode = exitCode ?: 143
            }
        }

        override fun destroyForcibly() {
            destroyForciblyCalls++
            if (exitsOnForcibly) {
                alive = false
                exitCode = exitCode ?: 137
            }
        }

        override fun waitFor(timeoutMillis: Long): Boolean = !alive
    }

    private fun writeScript(rel: String = "a.js", body: String = "process.exit(0)"): Path {
        val p = files.resolve("scripts").resolve("p1").resolve(rel)
        Files.createDirectories(p.parent)
        Files.write(p, body.toByteArray())
        return p
    }

    /** 默认宿主 = 临时目录里的哑文件（绝对路径存在性预检要过；真起进程的是假缝）。 */
    private fun defaultHostBinary(): Path = files.resolve("host").resolve("libnoden.so")

    private fun engine(
        launcher: ProcessLauncher,
        hostBinary: Path = defaultHostBinary(),
        libnode: Path? = null,
        addon: Path? = null,
        socket: String? = null,
        grace: Long = 3_000,
    ): NodeProcessEngine {
        // 只有走默认才铺哑文件：预检缺位案显式传"不存在的路径"、PATH 名案传相对名 —— 都原样不动。
        if (hostBinary == defaultHostBinary()) {
            Files.createDirectories(hostBinary.parent)
            if (!Files.exists(hostBinary)) Files.write(hostBinary, ByteArray(0))
        }
        return NodeProcessEngine(
            EngineId(0),
            NodeEngineConfig(
                filesDir = files,
                hostBinary = hostBinary,
                libnodePath = libnode,
                addonPath = addon,
                hostSocketName = socket,
                stopGraceMillis = grace,
            ),
            launcher,
        )
    }

    private fun request(nonce: String? = null, args: List<String> = emptyList()) =
        EngineRunRequest(projectId = "p1", scriptPath = "a.js", args = args, runNonce = nonce)

    @Test
    fun `预检点名绝对路径——脚本缺位不起进程`() {
        val launcher = FakeLauncher()
        val e = engine(launcher)
        val ex = assertThrows(AutojsException::class.java) { runBlocking { e.execute(request()) } }
        assertEquals(ErrorCode.ERR_FILE_NOT_FOUND, ex.error)
        assertTrue(ex.message!!.contains("scripts/p1/a.js"), "消息必须点名绝对路径：${ex.message}")
        assertEquals(0, launcher.spawnCount, "预检失败绝不 spawn")
    }

    @Test
    fun `预检点名绝对路径——宿主二进制与 libnode 缺位`() {
        writeScript()
        val launcher = FakeLauncher()
        val noBin = engine(launcher, hostBinary = dir.resolve("no-such-noden"))
        val ex1 = assertThrows(AutojsException::class.java) { runBlocking { noBin.execute(request()) } }
        assertEquals(ErrorCode.ERR_FILE_NOT_FOUND, ex1.error)
        assertTrue(ex1.message!!.contains("no-such-noden"), "${ex1.message}")

        val noLib = engine(launcher, libnode = dir.resolve("no-such-libnode.so"))
        val ex2 = assertThrows(AutojsException::class.java) { runBlocking { noLib.execute(request()) } }
        assertEquals(ErrorCode.ERR_FILE_NOT_FOUND, ex2.error)
        assertTrue(ex2.message!!.contains("no-such-libnode.so"), "${ex2.message}")
        assertEquals(0, launcher.spawnCount, "两条预检都不许 spawn")
    }

    @Test
    fun `env 契约与 argv cwd 逐项对齐 main 契约`() {
        writeScript()
        val launcher = FakeLauncher()
        val lib = dir.resolve("libnode.so"); Files.write(lib, ByteArray(0))
        val addon = dir.resolve("addon.node"); Files.write(addon, ByteArray(0))
        val e = engine(launcher, libnode = lib, addon = addon, socket = "as-sock-1")
        val receipt = runBlocking { e.execute(request(nonce = "n-7", args = listOf("x", "y"))) }

        val scriptAbs = files.resolve("scripts/p1/a.js")
        assertEquals(listOf(defaultHostBinary().toString(), scriptAbs.toString(), "x", "y"), launcher.lastCommand)
        assertEquals(
            files.resolve("scripts/p1"),
            launcher.lastCwd,
            "cwd = 项目根（ScriptPaths KDoc：引擎进程 cwd 由 :engine:node-process 决定）",
        )
        val env = launcher.lastEnv!!
        assertEquals(lib.toString(), env[NodeProcessEngine.ENV_LIBNODE])
        assertEquals(addon.toString(), env[NodeProcessEngine.ENV_BRIDGE_ADDON])
        assertEquals("as-sock-1", env[NodeProcessEngine.ENV_HOST_SOCKET])
        assertEquals("n-7", env[NodeProcessEngine.ENV_RUN_NONCE])
        assertEquals(receipt.runId.toString(), env[NodeProcessEngine.ENV_RUN_ID], "runId 随 env 下传（§8.4 心跳身份）")
        assertEquals(4242, receipt.pid, "pid 快照 = spawn 瞬间子进程 pid（§8.4 看门狗锚点）")
        assertEquals(receipt.runId, receipt.handle.refId)
        assertEquals(1, receipt.handle.generation)
    }

    @Test
    fun `离线缺省——不注入 socket libnode addon，RUN_ID 恒注入`() {
        writeScript()
        val launcher = FakeLauncher()
        val e = engine(launcher)
        runBlocking { e.execute(request()) }
        val env = launcher.lastEnv!!
        assertFalse(NodeProcessEngine.ENV_HOST_SOCKET in env, "socket 缺省 = 离线模式（main.cpp stderr 提示，不悬挂）")
        assertFalse(NodeProcessEngine.ENV_RUN_NONCE in env, "nonce 缺省不注入")
        assertFalse(NodeProcessEngine.ENV_LIBNODE in env)
        assertFalse(NodeProcessEngine.ENV_BRIDGE_ADDON in env)
        assertTrue(NodeProcessEngine.ENV_RUN_ID in env, "RUN_ID 恒注入")
    }

    @Test
    fun `状态语义——未启动 RUNNING 与两种自然终态`() {
        writeScript()
        val launcher = FakeLauncher()
        val e = engine(launcher)
        assertEquals(EngineStatus.IDLE, runBlocking { e.status() }, "未启动 = IDLE")
        assertNull(e.pid, "未启动 pid = null（绝不 0/自身）")

        runBlocking { e.execute(request()) }
        assertEquals(EngineStatus.RUNNING, runBlocking { e.status() }, "存活未请求停止 = RUNNING")
        assertEquals(4242, e.pid, "存活期 pid 给真值")

        // 自然退出码 0 → STOPPED；pid 随退出回 null（receipt 快照另存，见 §8.4）
        launcher.nextProcess.alive = false
        launcher.nextProcess.exitCode = 0
        assertEquals(EngineStatus.STOPPED, runBlocking { e.status() })
        assertNull(e.pid, "已退出 pid 回 null（§8.4 契约）")

        // 自然退出码 ≠0 → CRASHED（脚本抛错/宿主 exit 2/3/4 都走这条）
        val l2 = FakeLauncher().also { it.nextProcess = FakeProcess(pid = 9, alive = false, exitCode = 3) }
        val e2 = engine(l2)
        runBlocking { e2.execute(request()) }
        assertEquals(EngineStatus.CRASHED, runBlocking { e2.status() }, "自然非零退出 = CRASHED")
    }

    @Test
    fun `stop 语义——SIGTERM 退净回 Clean，忽略信号回 TimedOut partial`() {
        writeScript()
        val launcher = FakeLauncher()
        val e = engine(launcher, grace = 50)
        runBlocking { e.execute(request()) }
        val proc = launcher.nextProcess

        // 案 1：礼貌终止成功
        assertEquals(StopResult.Clean, runBlocking { e.stop() })
        assertEquals(1, proc.destroyCalls, "stop = destroy(SIGTERM)，不许直接 SIGKILL")
        assertEquals(0, proc.destroyForciblyCalls, "stop 阶段绝不触 SIGKILL（kill 兜底归池侧 quiesce）")
        assertEquals(EngineStatus.STOPPED, runBlocking { e.status() }, "请求过停止后退净 → STOPPED（SIGTERM 的 143 不算崩溃）")
        assertNull(e.pid, "已退出 pid 回 null（§8.4）")
        assertEquals(StopResult.Clean, runBlocking { e.stop() }, "已退净再 stop = 无事可停，如实 Clean")

        // 案 2：忽略 SIGTERM → TimedOut(partial=true)，交池 kill 兜底（§8.3 四步）
        val p2 = FakeProcess(pid = 4343).also { it.exitsOnDestroy = false }
        launcher.nextProcess = p2
        runBlocking { e.execute(request()) }
        val timedOut = runBlocking { e.stop() }
        assertTrue(timedOut is StopResult.TimedOut && timedOut.partial, "排空超时 = TimedOut(partial=true)：$timedOut")
        assertEquals(EngineStatus.QUIESCING, runBlocking { e.status() }, "请求停止且仍存活 = QUIESCING")
        assertEquals(KillCause.REQUESTED, runBlocking { e.kill() }, "kill 返回只供诊断（归因在调用方 recycle(cause)）")
        assertEquals(1, p2.destroyForciblyCalls, "kill = destroyForcibly(SIGKILL)")
        assertEquals(EngineStatus.CRASHED, runBlocking { e.status() }, "强杀后终态 CRASHED")
    }

    @Test
    fun `kill 无执行体——回 REQUESTED 不抛（与 UnavailableEngine 同口径）`() {
        val e = engine(FakeLauncher())
        assertEquals(KillCause.REQUESTED, runBlocking { e.kill() })
        assertEquals(StopResult.Clean, runBlocking { e.stop() })
        assertEquals(EngineStatus.IDLE, runBlocking { e.status() })
    }

    @Test
    fun `spawn 失败——IOException 折叠为 ERR_IO 点名 cmd`() {
        writeScript()
        val launcher = FakeLauncher()
        launcher.failWith = java.io.IOException("Cannot run program \"noden\": No such file or directory")
        val e = engine(launcher, hostBinary = Path.of("noden"))   // 非绝对路径 = PATH 名，跳过存在性预检
        val ex = assertThrows(AutojsException::class.java) { runBlocking { e.execute(request()) } }
        assertEquals(ErrorCode.ERR_IO, ex.error)
        assertTrue(ex.message!!.contains("noden"), "${ex.message}")
        assertEquals(EngineStatus.IDLE, runBlocking { e.status() }, "spawn 失败无进程 → 仍 IDLE")
    }

    @Test
    fun `runId 跨实例全局唯一且单调——在途表不许互相覆盖`() {
        writeScript()
        val r1 = runBlocking { engine(FakeLauncher()).execute(request()) }
        val r2 = runBlocking { engine(FakeLauncher()).execute(request()) }
        val r3 = runBlocking { engine(FakeLauncher()).execute(request()) }
        val ids = listOf(r1.runId, r2.runId, r3.runId)
        assertEquals(ids.size, ids.toSet().size, "三个独立引擎实例的 runId 不得相撞：$ids")
        assertEquals(ids, ids.sorted(), "runId 单调递增")
        assertTrue(r1.runId > 0)
    }

    @Test
    fun `同引擎一次一脚本——上一执行体仍活时先强杀再拒绝`() {
        writeScript()
        val launcher = FakeLauncher()
        val zombie = FakeProcess(pid = 5555).also { it.exitsOnForcibly = false }  // SIGKILL 也杀不掉（假缝脚本化）
        launcher.nextProcess = zombie
        val e = engine(launcher)
        runBlocking { e.execute(request()) }
        val ex = assertThrows(IllegalStateException::class.java) { runBlocking { e.execute(request()) } }
        assertTrue(ex.message!!.contains("5555"), "${ex.message}")
        assertEquals(1, zombie.destroyForciblyCalls, "拒绝前先强杀，不留野进程")
        assertEquals(1, launcher.spawnCount, "拒绝的那次绝不 spawn 第二个进程")
    }
}
