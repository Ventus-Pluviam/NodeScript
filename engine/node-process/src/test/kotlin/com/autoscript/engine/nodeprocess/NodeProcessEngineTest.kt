package com.autoscript.engine.nodeprocess

import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.engine.EngineId
import com.autoscript.domain.engine.EngineRunRequest
import com.autoscript.domain.engine.EngineStatus
import com.autoscript.domain.engine.KillCause
import com.autoscript.domain.engine.RunSummary
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

    /**
     * 可脚本化的假进程：存活/退出码/终止调用全部由测试驱动。
     *
     * **`open`**：暂停链那条用例要一个"冻得住"的替身（`SpawnedProcess.pauseExecution`
     * 缺省 false —— 那正是"本设备冻不住"的如实形状），故留给用例继承覆写。
     */
    private open class FakeProcess(
        override val pid: Int? = 4242,
        var alive: Boolean = true,
        var exitCode: Int? = null,
    ) : SpawnedProcess {
        /** 假缝捕获的 stderr 尾部（`SpawnedProcess.stderrTail` 的返回值，测试驱动态字段）。 */
        var capturedStderrTail: String = ""
        var destroyCalls = 0
        var destroyForciblyCalls = 0
        /** destroy() 后是否退（false = 忽略 SIGTERM，用于 TimedOut 案）。 */
        var exitsOnDestroy = true
        var exitsOnForcibly = true
        /** [awaitStderrDrained] 的调用记录（断言「只有自然退出路径等排水」）。 */
        val drainWaits = mutableListOf<Long>()

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

        override val stderrTail: String get() = capturedStderrTail

        override fun awaitStderrDrained(timeoutMillis: Long): Boolean {
            drainWaits += timeoutMillis
            return true    // 无真排水线程：capturedStderrTail 本身即最终态
        }
    }

    /**
     * 「已收尸、排水线程还没读完管道」的确定性模型（B11 竞态回归）：进程一出生就已退出，
     * 但 stderr 尾部只有前半截；剩下的字节**只在** [awaitStderrDrained] 被调用（= join 排水线程）
     * 且 [drainCompletes] 时才落进尾部。不靠 sleep/时序 —— 修复前（不 join 直接快照）恒拿半截，
     * 修复后恒拿全量；[drainCompletes] = false 模拟 join 超时（孙进程霸着管道）。
     */
    private class LaggingDrainProcess(
        private val exit: Int,
        private val before: String,
        private val after: String,
        private val drainCompletes: Boolean = true,
    ) : SpawnedProcess {
        val drainWaits = mutableListOf<Long>()
        private var tail = before

        override val pid: Int? = 77
        override val isAlive: Boolean get() = false
        override fun exitValue(): Int? = exit
        override fun destroy() = Unit
        override fun destroyForcibly() = Unit
        override fun waitFor(timeoutMillis: Long): Boolean = true
        override val stderrTail: String get() = tail

        override fun awaitStderrDrained(timeoutMillis: Long): Boolean {
            drainWaits += timeoutMillis
            if (drainCompletes) tail = before + after
            return drainCompletes
        }
    }

    private fun writeScript(rel: String = "a.js", body: String = "process.exit(0)"): Path {
        val p = files.resolve("scripts").resolve("p1").resolve(rel)
        Files.createDirectories(p.parent)
        Files.write(p, body.toByteArray())
        return p
    }

    /** 两个限额缝打包一个参数：这两个是同一件事（脚本资源限额），拆开只会让 `engine()` 顶破 detekt 的参数线。 */
    private data class ScriptLimits(
        val maxHeapMb: () -> Int? = { null },
        val uvThreadPoolSize: () -> Int? = { null },
    )

    /** 默认宿主 = 临时目录里的哑文件（绝对路径存在性预检要过；真起进程的是假缝）。 */
    private fun defaultHostBinary(): Path = files.resolve("host").resolve("libnoden.so")

    private fun engine(
        launcher: ProcessLauncher,
        hostBinary: Path = defaultHostBinary(),
        libnode: Path? = null,
        addon: Path? = null,
        socket: String? = null,
        bridgeDist: Path? = null,
        scriptEnv: () -> Map<String, String> = { emptyMap() },
        limits: ScriptLimits = ScriptLimits(),
        grace: Long = 3_000,
        runIdHighWater: Long = 0,
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
                bridgeDistPath = bridgeDist,
                scriptEnv = scriptEnv,
                maxHeapMb = limits.maxHeapMb,
                uvThreadPoolSize = limits.uvThreadPoolSize,
                runIdHighWater = runIdHighWater,
                stopGraceMillis = grace,
            ),
            launcher,
            identityIssuer = { _, _, _, _ -> object : com.autoscript.domain.engine.RunIdentityLease {
                override val token = "a".repeat(64)
                override fun confirmSpawn(pid: Int?, isAlive: () -> Boolean) = Unit
                override fun naturalExit() = Unit
                override fun revoke() = Unit
            } },
        )
    }

    /**
     * 默认带授权快照：在线引擎（`socket` 非 null）在 A5 起**必须**有它才能 spawn
     * （fail-closed，见 [NodeProcessEngine]）；离线路径不看这个字段。
     */
    private fun request(
        nonce: String? = null,
        args: List<String> = emptyList(),
        authorization: com.autoscript.domain.permission.ScriptAuthorizationSnapshot? =
            com.autoscript.domain.permission.ScriptAuthorizationSnapshot(
                mask = com.autoscript.domain.permission.CapabilityMask.ALL,
            ),
    ) = EngineRunRequest(
        projectId = "p1", scriptPath = "a.js", args = args, runNonce = nonce,
        authorization = authorization,
    )

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
    fun `脚本路径越界——ERR_INVALID_PARAM 且绝不 spawn`() {
        // 外审 M1 收口：scriptFile 的读侧词法闸在这里透出成参数错。
        // 关键点是「绝不 spawn」—— 越界路径若真拼进了 argv，那一刻就已经是越权执行。
        writeScript("a.js")
        val sibling = files.resolve("scripts").resolve("p2")
        Files.createDirectories(sibling)
        Files.write(sibling.resolve("secret.js"), "{}".toByteArray())

        val launcher = FakeLauncher()
        val e = engine(launcher)
        val ex = assertThrows(AutojsException::class.java) {
            runBlocking { e.execute(request().copy(scriptPath = "../../p2/secret.js")) }
        }
        assertEquals(
            ErrorCode.ERR_INVALID_PARAM, ex.error,
            "越界脚本路径是参数错，不是「文件不存在」——后者会把部署时序问题错报成权限问题：${ex.message}",
        )
        assertEquals(0, launcher.spawnCount, "越界路径绝不进 argv")
    }

    @Test
    fun `env 契约与 argv cwd 逐项对齐 main 契约`() {
        writeScript()
        val launcher = FakeLauncher()
        val lib = dir.resolve("libnode.so"); Files.write(lib, ByteArray(0))
        val addon = dir.resolve("addon.node"); Files.write(addon, ByteArray(0))
        val dist = dir.resolve("bridge-dist"); Files.createDirectories(dist)
        Files.write(dist.resolve("bootstrap.js"), "boot".toByteArray())
        val e = engine(launcher, libnode = lib, addon = addon, socket = "as-sock-1", bridgeDist = dist)
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
        assertEquals("a".repeat(64), env[NodeProcessEngine.ENV_BRIDGE_TOKEN])
        assertEquals("n-7", env[NodeProcessEngine.ENV_RUN_NONCE])
        assertEquals(receipt.runId.toString(), env[NodeProcessEngine.ENV_RUN_ID], "runId 随 env 下传（§8.4 心跳身份）")
        assertEquals(dist.toString(), env[NodeProcessEngine.ENV_BRIDGE_DIST], "dist 落位根随 env 下传（§12.4 打包入口 attachNative 的 dist 来源）")
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
        assertFalse(NodeProcessEngine.ENV_BRIDGE_DIST in env, "dist 缺省不注入（同 addon 选填纪律）")
        assertTrue(NodeProcessEngine.ENV_RUN_ID in env, "RUN_ID 恒注入")
    }

    @Test
    fun `脚本环境变量注入——用户键进 env，宿主键仍胜出（顺序即契约）`() {
        writeScript()
        val launcher = FakeLauncher()
        // 用户键 + 一个**故意与宿主键同名**的键：后者必须被宿主值盖掉。
        // 这条是 [NodeEngineConfig.scriptEnv] KDoc 里"注入顺序即契约"的唯一钉子 ——
        // 顺序写反（宿主键先写）时本用例红，而那时用户就能把脚本的桥 socket 改掉。
        val e = engine(
            launcher,
            socket = "as-sock-real",
            scriptEnv = { mapOf("MY_TOKEN" to "t-1", "MY_EMPTY" to "", NodeProcessEngine.ENV_HOST_SOCKET to "as-sock-forged") },
        )
        runBlocking { e.execute(request()) }
        val env = launcher.lastEnv!!
        assertEquals("t-1", env["MY_TOKEN"], "用户设的键原样进 env")
        assertEquals("", env["MY_EMPTY"], "空串是合法值，不得被当成\"没设\"丢掉")
        assertEquals(
            "as-sock-real",
            env[NodeProcessEngine.ENV_HOST_SOCKET],
            "宿主键胜出：用户覆盖不了桥 socket（ScriptEnvKeys 拒收保留前缀之外的第二道兜底）",
        )
    }

    @Test
    fun `脚本环境变量缺省不注入任何键`() {
        writeScript()
        val launcher = FakeLauncher()
        runBlocking { engine(launcher).execute(request()) }
        // 缺省 `{ emptyMap() }`：env 里只该有宿主那几键（RUN_ID 恒有），
        // 多出任何键都说明注入缝在缺省路径上也动了 env。
        assertEquals(
            setOf(NodeProcessEngine.ENV_RUN_ID),
            launcher.lastEnv!!.keys,
            "离线缺省下 env 只该有 RUN_ID",
        )
    }

    @Test
    fun `脚本环境变量每次 spawn 现读——两次 execute 之间改了表，第二次拿到新值`() {
        writeScript()
        val launcher = FakeLauncher()
        var table = mapOf("K" to "v1")
        val e = engine(launcher, scriptEnv = { table })
        runBlocking { e.execute(request()) }
        assertEquals("v1", launcher.lastEnv!!["K"])
        table = mapOf("K" to "v2")
        // 上一次的进程必须**真的退净**：引擎对"同引擎一次一脚本被破坏"是硬拒
        // （execute 里那条 IllegalStateException），换个新 FakeProcess 引用不够 ——
        // 旧引用的 alive 还是 true。
        launcher.nextProcess.alive = false
        launcher.nextProcess.exitCode = 0
        launcher.nextProcess = FakeProcess(pid = 4343)
        runBlocking { e.execute(request()) }
        assertEquals("v2", launcher.lastEnv!!["K"], "装配期定死会让这条红 —— 用户改完必须下次执行就生效")
    }

    // ── 脚本资源限额（批 107；§8.1）────────────────────────────────────────

    @Test
    fun `堆上限走 argv 且排在脚本路径之前`() {
        writeScript()
        val launcher = FakeLauncher()
        val e = engine(launcher, limits = ScriptLimits(maxHeapMb = { 96 }))
        runBlocking { e.execute(request(args = listOf("x"))) }

        val cmd = launcher.lastCommand!!
        val flag = "--max-old-space-size=96"
        val scriptAbs = files.resolve("scripts/p1/a.js").toString()
        assertEquals(listOf(defaultHostBinary().toString(), flag, scriptAbs, "x"), cmd)
        assertTrue(
            cmd.indexOf(flag) < cmd.indexOf(scriptAbs),
            "标志位必须在脚本路径**之前** —— 否则被当成脚本的第一个位置参数（§7.8 argv 契约）",
        )
    }

    @Test
    fun `IO 线程池走 env——Node 没有 argv 形式`() {
        writeScript()
        val launcher = FakeLauncher()
        val e = engine(launcher, limits = ScriptLimits(uvThreadPoolSize = { 16 }))
        runBlocking { e.execute(request()) }
        assertEquals(
            "16",
            launcher.lastEnv!![NodeProcessEngine.ENV_UV_THREADPOOL_SIZE],
            "libuv 的线程池大小只由 env 决定（libuv 初始化时读），argv 无从表达",
        )
    }

    @Test
    fun `两个缝回 null 时一个标记都不加——缺省不得改变任何行为`() {
        writeScript()
        val launcher = FakeLauncher()
        runBlocking { engine(launcher, limits = ScriptLimits(maxHeapMb = { null }, uvThreadPoolSize = { null })).execute(request()) }

        assertEquals(
            listOf(defaultHostBinary().toString(), files.resolve("scripts/p1/a.js").toString()),
            launcher.lastCommand,
            "没配堆上限 = 交给 Node 的默认，不是加一个 0",
        )
        assertTrue(
            NodeProcessEngine.ENV_UV_THREADPOOL_SIZE !in launcher.lastEnv!!,
            "没配线程池 = 交给 Node 的默认 4，显式写进 env 反而把「没配」与「配成 4」混成一样",
        )
    }

    @Test
    fun `两个缝每次 spawn 现读——两次 execute 之间改了设置，第二次拿到新值`() {
        writeScript()
        val launcher = FakeLauncher()
        var heap = 96
        var pool = 4
        val e = engine(launcher, limits = ScriptLimits(maxHeapMb = { heap }, uvThreadPoolSize = { pool }))
        runBlocking { e.execute(request()) }
        assertTrue("--max-old-space-size=96" in launcher.lastCommand!!)

        launcher.nextProcess.alive = false
        launcher.nextProcess.exitCode = 0
        launcher.nextProcess = FakeProcess(pid = 4344)
        heap = 256
        pool = 16
        runBlocking { e.execute(request()) }
        assertTrue("--max-old-space-size=256" in launcher.lastCommand!!, "改完必须下次执行就生效（不重启宿主）")
        assertEquals("16", launcher.lastEnv!![NodeProcessEngine.ENV_UV_THREADPOOL_SIZE])
    }

    @Test
    fun `dist 注入按 bootstrap 在位与否降级——配置了但没落位不注入（含 addon 成对）`() {
        writeScript()
        val addon = dir.resolve("addon.node"); Files.write(addon, ByteArray(0))
        // 有 bootstrap.js → 注入（addon+dist 成对：都就位才都注入）
        val withBoot = dir.resolve("dist-ok"); Files.createDirectories(withBoot)
        Files.write(withBoot.resolve("bootstrap.js"), "x".toByteArray())
        val l1 = FakeLauncher()
        runBlocking { engine(l1, addon = addon, bridgeDist = withBoot).execute(request()) }
        assertEquals(withBoot.toString(), l1.lastEnv!![NodeProcessEngine.ENV_BRIDGE_DIST])
        assertEquals(addon.toString(), l1.lastEnv!![NodeProcessEngine.ENV_BRIDGE_ADDON])

        // 目录在但缺 bootstrap.js（半量部署/坏资产）→ 不注入 dist **也不注入 addon**
        // （否则 main.cpp 会因"addon 在 dist 缺" exit 5 杀整轮 —— dist 与 addon 绑定，
        // 见 NodeEngineConfig KDoc / main.cpp kExitDist=5）
        val noBoot = dir.resolve("dist-empty"); Files.createDirectories(noBoot)
        val l2 = FakeLauncher()
        runBlocking { engine(l2, addon = addon, bridgeDist = noBoot).execute(request()) }
        assertFalse(
            NodeProcessEngine.ENV_BRIDGE_DIST in l2.lastEnv!!,
            "缺 bootstrap.js = dist 不注入（半量部署不拿坏路径喂宿主）",
        )
        assertFalse(
            NodeProcessEngine.ENV_BRIDGE_ADDON in l2.lastEnv!!,
            "缺 dist = addon 也不注入（成对：不把“addon 在 dist 缺”的 exit 5 形态交给 main.cpp）",
        )
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
        launcher.nextProcess.capturedStderrTail = "启动噪声，不是病因\n"
        assertEquals(EngineStatus.STOPPED, runBlocking { e.status() })
        assertNull(e.pid, "已退出 pid 回 null（§8.4 契约）")
        // exit 0 也快照摘要（RunSummary(0, tail) 无害）——只有「无事实」才回 null（backlog B11）
        assertEquals(
            RunSummary(exitCode = 0, stderrTail = "启动噪声，不是病因\n"),
            runBlocking { e.lastRunSummary() },
            "干净退出也留摘要：诊断读口要看「上次跑了什么」，状态归类不依赖它",
        )

        // 自然退出码 ≠0 → CRASHED（脚本抛错/宿主 exit 2/3/4 都走这条）；带 stderr 尾部摘要
        val l2 = FakeLauncher()
            .also { it.nextProcess = FakeProcess(pid = 9, alive = false, exitCode = 3) }
            .also { it.nextProcess.capturedStderrTail = "TypeError: x is not a function" }
        val e2 = engine(l2)
        runBlocking { e2.execute(request()) }
        assertEquals(EngineStatus.CRASHED, runBlocking { e2.status() }, "自然非零退出 = CRASHED")
        assertEquals(
            RunSummary(exitCode = 3, stderrTail = "TypeError: x is not a function"),
            runBlocking { e2.lastRunSummary() },
            "CRASHED 时 lastRunSummary 带退出码 + stderr 尾部（病因的权威落点）",
        )
        // 同一引擎复用时也必须清空旧摘要：新执行体尚未自然退出，不能继承上一轮病因。
        val l3 = FakeLauncher()
        val e3 = engine(l3)
        runBlocking { e3.execute(request()) }
        assertNull(runBlocking { e3.lastRunSummary() }, "同一引擎复用后未退净 = 无事实，旧摘要清空")
        l3.nextProcess.alive = false
        l3.nextProcess.exitCode = 0
        l3.nextProcess.capturedStderrTail = "second run\n"
        assertEquals(EngineStatus.STOPPED, runBlocking { e3.status() })
        assertEquals(
            RunSummary(exitCode = 0, stderrTail = "second run\n"),
            runBlocking { e3.lastRunSummary() },
            "第二轮自然退出后只能发布第二轮摘要，不能复用上一轮事实",
        )
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

    /**
     * 2026-10-14 真机 bug 的引擎侧回归：**进程重启**后分配器从 1 重新数，会重发上一进程
     * 用掉的号 —— 档案撞「终态记录不可改写」。装配期把档案高水位喂进
     * [NodeEngineConfig.runIdHighWater]，构造即站在历史之后。
     *
     * **断言全用相对量**：分配器是**跨实例共享**的（类 KDoc「runId 全局唯一」），
     * 本类其它用例也在推进它 —— 写死数字会变成与执行顺序耦合的假断言。
     * 水位**只前移不后退**（同一个共享计数器：新实例带更小的水位构造，不得把已发出的号调小）。
     */
    @Test
    fun `构造水位后 runId 接在历史之后 更小的水位不后退`() {
        writeScript()
        val used1 = runBlocking { engine(FakeLauncher()).execute(request()) }.runId
        assertTrue(used1 > 0, "空档不顶时行为不变（仍从当前序列发号）")

        // 模拟"上一进程用掉了 used1"：新实例带着水位构造。
        val restarted = engine(FakeLauncher(), runIdHighWater = used1)
        val issued = runBlocking { restarted.execute(request()) }.runId
        assertTrue(issued > used1, "执行真的拿到历史之后的号（不再撞档案终态行）：$issued")

        // 更小的水位（历史更浅 / 0 = 无历史）不得把共享计数器调小 —— 调小就与本进程
        // 已发出的号相撞，比原病更糟。
        engine(FakeLauncher(), runIdHighWater = 1L)
        val afterShallow = runBlocking { engine(FakeLauncher()).execute(request()) }.runId
        assertTrue(afterShallow > issued, "带更小水位的实例不得让序列回退：$afterShallow vs $issued")
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

    @Test
    fun `addon 注入按文件在位与否降级——配置了但没落位不注入`() {
        writeScript()
        val launcher = FakeLauncher()
        val e = engine(launcher, addon = dir.resolve("never-written.node"))
        runBlocking { e.execute(request()) }
        val env = launcher.lastEnv!!
        assertFalse(
            NodeProcessEngine.ENV_BRIDGE_ADDON in env,
            "缺文件 = 降级不注入（与 bridgeDistPath 同一条选填纪律；main.cpp 直跑脚本）",
        )
    }

    /** 每次 spawn 都交出同一个预制进程（排水滞后模型用）。 */
    private fun launcherOf(proc: SpawnedProcess) = object : ProcessLauncher {
        override fun spawn(command: List<String>, env: Map<String, String>, workingDir: Path) = proc
    }

    @Test
    fun `自然退出先等排水再快照——已收尸但管道没读完时不丢病因尾巴（B11 竞态）`() {
        writeScript()
        val proc = LaggingDrainProcess(exit = 3, before = "at main.js:1\n", after = "TypeError: boom\n")
        val e = engine(launcherOf(proc))
        runBlocking { e.execute(request()) }
        assertEquals(EngineStatus.CRASHED, runBlocking { e.status() })
        assertEquals(listOf(500L), proc.drainWaits, "快照前 join 排水线程一次，上限 500ms")
        assertEquals(
            RunSummary(exitCode = 3, stderrTail = "at main.js:1\nTypeError: boom\n"),
            runBlocking { e.lastRunSummary() },
            "修复前直接快照只拿到前半截；join 后必须是全量尾部",
        )
    }

    @Test
    fun `排水 join 超时——用当前快照兜底，不无限阻塞`() {
        writeScript()
        val proc = LaggingDrainProcess(exit = 1, before = "partial\n", after = "never\n", drainCompletes = false)
        val e = engine(launcherOf(proc))
        runBlocking { e.execute(request()) }
        assertEquals(EngineStatus.CRASHED, runBlocking { e.status() })
        assertEquals(
            RunSummary(exitCode = 1, stderrTail = "partial\n"),
            runBlocking { e.lastRunSummary() },
            "孙进程霸着管道时 join 超时，摘要取当前快照",
        )
    }

    @Test
    fun `stop 与 kill 路径不等排水——终止手段的产物不填摘要也不付等待`() {
        writeScript()
        val launcher = FakeLauncher()
        val e = engine(launcher, grace = 50)
        runBlocking { e.execute(request()) }
        val stopped = launcher.nextProcess
        assertEquals(StopResult.Clean, runBlocking { e.stop() })
        assertEquals(EngineStatus.STOPPED, runBlocking { e.status() })
        assertTrue(stopped.drainWaits.isEmpty(), "请求停止后的 status 不 join 排水")

        val killed = FakeProcess(pid = 4646)
        launcher.nextProcess = killed
        runBlocking { e.execute(request()) }
        runBlocking { e.kill() }
        assertEquals(EngineStatus.CRASHED, runBlocking { e.status() })
        assertTrue(killed.drainWaits.isEmpty(), "强杀后的 status 不 join 排水")
    }

    /**
     * 暂停/恢复（2026-10-13）：委托当前 execution 的进程；stop 入口先解冻。
     *
     * FakeProcess 缺省 `pauseExecution/resumeExecution` = false（`SpawnedProcess`
     * 接口缺省）—— 这里用可冻的替身；真 `kill -STOP` 由设备验证，单测只钉委托关系。
     */
    @Test
    fun `pause 委托进程 resume 解冻 stop 先解冻再停`() {
        val launcher = FakeLauncher()
        val proc = object : FakeProcess() {
            var paused = false
            override fun pauseExecution(): Boolean = true.also { paused = true }
            override fun resumeExecution(): Boolean = true.also { paused = false }
        }
        launcher.nextProcess = proc
        val e = engine(launcher)
        writeScript()
        runBlocking { e.execute(request()) }
        assertTrue(runBlocking { e.pause() })
        assertTrue(proc.paused)
        assertTrue(runBlocking { e.resume() })
        assertFalse(proc.paused)
        // 冻住后 stop：先解冻（resume 调用）再 SIGTERM
        runBlocking { e.pause() }
        var resumes = 0
        val proc2 = object : FakeProcess() {
            override fun pauseExecution(): Boolean = true
            override fun resumeExecution(): Boolean = true.also { resumes++ }
        }
        // 同一 execution 的进程已换不掉 —— 用新引擎验证 stop 路径的解冻调用：
        val launcher2 = FakeLauncher()
        launcher2.nextProcess = proc2
        val e2 = engine(launcher2)
        runBlocking { e2.execute(request()) }
        runBlocking { e2.pause() }
        runBlocking { e2.stop() }
        assertTrue(resumes >= 1, "stop 前必须先解冻，否则 SIGTERM 发给冻住的进程不被处理")
    }

    @Test
    fun `pause 无执行或冻不住回 false`() {
        val e = engine(FakeLauncher())
        assertFalse(runBlocking { e.pause() }, "从未启动 → false，不抛")
        assertFalse(runBlocking { e.resume() })
        // FakeProcess 缺省冻不住
        val launcher = FakeLauncher()
        launcher.nextProcess = FakeProcess()
        val e2 = engine(launcher)
        writeScript()
        runBlocking { e2.execute(request()) }
        assertFalse(runBlocking { e2.pause() })
    }
}
