package com.autoscript.engine.nodeprocess

import com.autoscript.domain.bridge.HandleRef
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.engine.EngineId
import com.autoscript.domain.engine.EngineRunReceipt
import com.autoscript.domain.engine.EngineRunRequest
import com.autoscript.domain.engine.EngineStatus
import com.autoscript.domain.engine.KillCause
import com.autoscript.domain.engine.RunIdentityIssuer
import com.autoscript.domain.engine.RunIdentityLease
import com.autoscript.domain.engine.RunSummary
import com.autoscript.domain.engine.ScriptEngine
import com.autoscript.domain.engine.StopResult
import com.autoscript.domain.scripts.ScriptPaths
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** spawn 出的 :nodeN 宿主的静态配置（装配期一次定死；paths 错位由 execute 预检点名报出）。 */
data class NodeEngineConfig(
    /** App 私有根（`filesDir`）：脚本绝对路径经 [ScriptPaths] 从这里拼出。 */
    val filesDir: Path,
    /**
     * 宿主可执行（设备 = jniLibs 交付的 PIE，如 `nativeLibraryDir/libnoden.so`；命名随打包管线定案，
     * 缺位/错位由 execute 预检点名）。测试可用 PATH 名（如 `node`）——非绝对路径跳过存在性预检，
     * 交由 spawn 的 IOException 如实报 "Cannot run program"。
     */
    val hostBinary: Path,
    /**
     * `AUTOSCRIPT_LIBNODE`：main.cpp **必填**（缺则 exit 2，§7.8）。设备装配必传；
     * 测试跑系统 node 可传 null（系统 node 不读它）。
     */
    val libnodePath: Path? = null,
    /**
     * `AUTOSCRIPT_BRIDGE_ADDON`：选填（§7.8）。缺省 null = 不注入 → main.cpp 直跑脚本、
     * 不预载 addon（桥数据面不可用但脚本照跑）——addon 的交付与 JS 消费面未落地前的诚实缺省。
     */
    val addonPath: Path? = null,
    /**
     * `AUTOSCRIPT_HOST_SOCKET`：`:main` 桥监听名（设备 = abstract unix socket 名，由
     * `BridgeSocketListener` 生成并注入；null = 离线模式：main.cpp 打 stderr 提示、
     * 桥调用如实 `ERR_ENGINE_STOPPED`，不悬挂）。
     */
    val hostSocketName: String? = null,
    /**
     * `AUTOSCRIPT_BRIDGE_DIST`（§12.4 资产交付轨）：facade dist 落位根
     * （生产 = `ScriptPaths.autoModuleRoot(filesDir)`）。**与 addon 绑定**（§7.8 契约，
     * main.cpp kExitDist=5）：给 addon 未给 dist / dist 顶层不存在 = 宿主 exit 5 ——
     * "资源不齐"是 launch 期裁决，不推到运行期 ERR_ENGINE_STOPPED。
     * 本进程侧**只注入确认落位的 dist**（bootstrap.js 在位），缺落位 = 不注入 dist 也不
     * 注入 addon（两者绑定成对）—— 避免把坏路径喂给宿主（exit 5 是防"配置了没落位"，
     * 不是防"路径不存在"，后者这里就拦掉）。
     * 缺省 null = 不注入（单测/桌面不经资产部署的路径）。
     */
    val bridgeDistPath: Path? = null,
    /**
     * **脚本环境变量**（§8.1，宿主侧全局配置）：用户在管理面板 → 环境变量里编的那组 KV，
     * 每次 spawn 注入脚本进程的 `process.env`。
     *
     * **是 `() -> Map` 而不是 `Map`，且每次 execute 现读** —— 这是契约不是风格：
     * 装配期读一次塞进来，用户在界面改完就得重启 App 才生效，而"保存了却没生效"
     * 正是本仓反复点名的坑（与 `HostSummary` 各读口"现取不缓存"同一条纪律）。
     * 生产实现是 `FileScriptEnvStore.all()` 的投影（`AppShellApplication` 的 engineFactory
     * 闭包里捕获 store）；缺省 `{ emptyMap() }` = 不注入任何用户变量。
     *
     * **注入顺序即契约**：本表先写、宿主键（`AUTOSCRIPT_*`）后写，故宿主键**永远胜出**。
     * 用户在界面上设不了这些键（`ScriptEnvKeys.reject` 拒收保留前缀），这里的顺序是
     * **第二道兜底** —— 盘上若已有历史脏行，或将来有人绕过写入闸直写 store，
     * 引擎侧仍不会被覆盖成"脚本去连别的 socket"。
     */
    val scriptEnv: () -> Map<String, String> = { emptyMap() },
    /** 四步 quiesce 的排空窗口（§8.3）：`stop()` SIGTERM 后等这么久，未退则 TimedOut 交池 kill 兜底。 */
    val stopGraceMillis: Long = 3_000,
)

/**
 * `ScriptEngine` 的进程池实现（docs §8.1「实现在 :engine:node-process」/
 * §19「Kotlin spawn」的落地）：每 execute 起一个宿主进程（§8.2 每脚本一进程），env 契约
 * 与 `main.cpp`（§7.8）逐键对齐。
 *
 * **状态语义（`status()` 是唯一事实源，池侧投影由 `EngineStateMachine` 并行维护，分歧走 drift）**：
 * - 未 spawn → `IDLE`；
 * - 进程存活且未请求停止 → `RUNNING`；请求停止（SIGTERM 已发、未退）→ `QUIESCING`；
 * - 退净：**请求过停止**（无论退出码，SIGTERM 后的 143 不是崩溃）→ `STOPPED`；
 *   自然退出码 0 → `STOPPED`；自然退出码 ≠0（脚本抛错/宿主 exit 2/3/4）→ `CRASHED`。
 *
 * **pid 诚实口径（§8.4）**：[pid] 只在子进程**存活**时给真 pid，未启动/已退出回 null（绝不给
 * 0/自身）；启动瞬间的快照随 [EngineRunReceipt.pid] 出去供看门狗 `/proc` 采样（那是外带锚点，
 * 与本属性的"当前"语义不同源，见 §8.4 调度循环）。宿主运行时缺 `Process.pid()`（旧 API）时
 * 快照如实 null → 看门狗走 noPid 清单，不是崩溃。
 *
 * **预检先于 spawn**：**必填件**（脚本、绝对路径的宿主二进制、配置了的 libnode）缺位一律
 * [AutojsException] `ERR_FILE_NOT_FOUND` 点名**绝对路径** → 池 `Failed` → dispatcher `StartFailed`
 * → 意图日志 COMMIT 行带真原因（与 `UnavailableEngine` 同一条诚实通道，但路径级可操作：
 * 打包缺件时任务中心直接告诉运维"缺哪个文件、期望在哪"）。**选填件不走这条路**：addon 缺位
 * 降级为不注入（见下方 execute 注释）—— 为可选特性杀整轮执行是把"锦上添花缺了"报成"雪崩"。
 *
 * **超时不在此计时**：`EngineRunRequest.timeoutMillis` 的收口方是
 * `RuntimeController.awaitCompletion`（§8.6 dispatcher 传入）—— 引擎不设第二只钟，
 * 免得两套超时口径互相打架；本实现忽略该字段（KDoc 即契约）。
 *
 * **runId 全局唯一**：文件级计数器跨全部实例递增 —— `RuntimeController.active` 以 runId 为键，
 * 池内多槽实例各自从 1 数会在在途表互相覆盖（与 `FakeEngine` 的共享序列同一语义）。
 *
 * @param launcher spawn 缝：单测注入假实现断言 argv/env/cwd，集成测试换 [ProcessBuilderLauncher] 真起进程。
 */
class NodeProcessEngine(
    override val id: EngineId,
    private val config: NodeEngineConfig,
    private val launcher: ProcessLauncher = ProcessBuilderLauncher(),
    private val identityIssuer: RunIdentityIssuer? = null,
) : ScriptEngine {

    /** 回调始终捕获本次执行，旧尾帧/旧清理不得按槽位找到新 run 的身份。 */
    private class Execution(val process: SpawnedProcess, val lease: RunIdentityLease?) {
        @Volatile var stopRequested = false
        @Volatile var killRequested = false
        @Volatile var summary: RunSummary? = null
    }

    @Volatile private var execution: Execution? = null

    /** 仅存活时给真 pid；receipt 中的启动快照不受此属性的变化影响。 */
    override val pid: Int?
        get() = execution?.process?.takeIf { it.isAlive }?.pid

    override suspend fun execute(run: EngineRunRequest): EngineRunReceipt {
        var lease: RunIdentityLease? = null
        var started: SpawnedProcess? = null
        var delivered = false
        try {
            return withContext(Dispatchers.IO) {
                execution?.let { old ->
                    if (old.process.isAlive) {
                        old.lease?.revoke()
                        old.process.destroyForcibly()
                        old.process.waitFor(KILL_REAP_MILLIS)
                        error("同引擎一次一脚本被破坏：上一执行体仍存活（pid=${old.process.pid}），已强杀后拒绝本次启动")
                    }
                    old.lease?.naturalExit()
                }
                val scriptAbs = ScriptPaths.scriptFile(config.filesDir, run.projectId, run.scriptPath)
                if (!Files.isRegularFile(scriptAbs)) {
                    throw AutojsException(
                        ErrorCode.ERR_FILE_NOT_FOUND,
                        "脚本不存在：$scriptAbs（projectId=${run.projectId}；装配期补部署见 ScriptDeployRecovery）",
                    )
                }
                // 非绝对路径 = PATH 查找名（测试用 "node"），存在性交给 spawn 报；绝对路径缺位 = 打包缺件，点名。
                if (config.hostBinary.isAbsolute && !Files.isRegularFile(config.hostBinary)) {
                    throw AutojsException(
                        ErrorCode.ERR_FILE_NOT_FOUND,
                        "引擎宿主二进制未随 APK 落位：${config.hostBinary}（jniLibs 交付，node-runtime-build CI 产物，§19）",
                    )
                }
                config.libnodePath?.let { lib ->
                    if (lib.isAbsolute && !Files.isRegularFile(lib)) {
                        throw AutojsException(
                            ErrorCode.ERR_FILE_NOT_FOUND,
                            "libnode.so 未随 APK 落位：$lib（node-runtime-build 产物，§3；main.cpp 缺它 exit 2）",
                        )
                    }
                }
                // addon 是**选填**特性（main.cpp 合同：缺它 = 不预载、脚本直跑、桥调用在调用点如实
                // ERR_ENGINE_STOPPED）—— 所以"配置了但文件还没落位"降级为不注入，绝不为可选件杀整轮执行；
                // 真正的缺件感由宿主二进制/libnode 两条**必填**预检承担（它们缺了 main.cpp 必然 exit 2/4）。
                // **dist 与 addon 成对**（§7.8 kExitDist=5）：dist 配置了却没落位 = addon 也不注入
                // （否则 main.cpp 会因"addon 在 dist 缺" exit 5 杀整轮）。pairedDist 在两边都就位时
                // 才成立；它同时决定 addon 注入与 dist env 注入。
                val pairedDist = config.addonPath
                    ?.takeIf { Files.isRegularFile(it) }
                    ?.let { config.bridgeDistPath?.takeIf { d -> Files.isRegularFile(d.resolve("bootstrap.js")) } }
                val addonEffective = if (pairedDist != null) config.addonPath?.takeIf { Files.isRegularFile(it) } else null

                val runId = nodeEngineRunIds.getAndIncrement()
                val env = linkedMapOf<String, String>()
                // 用户环境变量**先**写（§8.1）：下面的宿主键后写，故宿主键胜出。
                // 顺序是契约，不是巧合 —— 见 NodeEngineConfig.scriptEnv 的 KDoc。
                config.scriptEnv().forEach { (k, v) -> env[k] = v }
                config.libnodePath?.let { env[ENV_LIBNODE] = it.toString() }
                addonEffective?.let { env[ENV_BRIDGE_ADDON] = it.toString() }
                config.hostSocketName?.let { env[ENV_HOST_SOCKET] = it }
                // dist env 同 addon 一起注入（pairedDist 已确认 bootstrap.js 在位）。
                pairedDist?.let { env[ENV_BRIDGE_DIST] = it.toString() }
                // 执行体身份（§8.5 幂等键 + §8.4 心跳打点）：runId/runNonce 随 env 下传，
                // JS 侧（bootstrap/脚本）读 process.env 即可 startHeartbeat(runId) —— 不再另造 argv 通道。
                env[ENV_RUN_ID] = runId.toString()
                run.runNonce?.let { env[ENV_RUN_NONCE] = it }

                val command = listOf(config.hostBinary.toString(), scriptAbs.toString()) + run.args
                val cwd = ScriptPaths.projectRoot(config.filesDir, run.projectId)
                // 在线必须与宿主同一本身份账；离线不签发、不凭空制造匿名桥身份。
                // 授权快照由宿主一侧**上游**算好（RuntimeController.start → 池请求 → 本请求），
                // 引擎只搬运、不重算（见 RunIdentityIssuer KDoc）。离线没有快照就不签发，
                // 也不编一个 —— 那种路径本来就没有桥身份。
                lease = config.hostSocketName?.let {
                    val issuer = identityIssuer ?: throw AutojsException(ErrorCode.ERR_ENGINE_STOPPED, "在线引擎缺身份签发入口")
                    val authorization = run.authorization
                        ?: throw AutojsException(
                            ErrorCode.ERR_ENGINE_STOPPED,
                            "在线引擎缺授权快照（run.authorization == null）：宿主装配未把授权决策传下来",
                        )
                    issuer.issue(id, runId, run.projectId, authorization)
                }
                lease?.let { env[ENV_BRIDGE_TOKEN] = it.token }
                val spawned = try {
                    launcher.spawn(command, env, cwd)
                } catch (e: IOException) {
                    throw AutojsException(ErrorCode.ERR_IO, "拉起引擎宿主失败：cmd=[${command.joinToString(" ")}] —— ${e.message}", e)
                }
                started = spawned
                val current = Execution(spawned, lease)
                execution = current
                lease?.confirmSpawn(spawned.pid) { spawned.isAlive }
                EngineRunReceipt(runId, HandleRef(refId = runId, generation = 1), spawned.pid)
            }.also { delivered = true }
        } finally {
            // withContext 返回时也可能因调用方取消抛错；只清本次已取得的 lease/process。
            if (!delivered) withContext(NonCancellable + Dispatchers.IO) {
                lease?.revoke()
                started?.let { child ->
                    if (child.isAlive) child.destroyForcibly()
                    child.waitFor(KILL_REAP_MILLIS)
                }
            }
        }
    }

    /**
     * 四步 quiesce 的「请求退出」步（§8.3）：SIGTERM → [NodeEngineConfig.stopGraceMillis] 内退净
     * 回 [StopResult.Clean]；超时回 [StopResult.TimedOut]（partial=true = 尚有残留），
     * 池侧 `PoolSlot.quiesce` 据此 kill 兜底。从未启动/已退净 → 如实 Clean（无事可停）。
     */
    override suspend fun stop(): StopResult = withContext(Dispatchers.IO) {
        val current = execution ?: return@withContext StopResult.Clean
        val p = current.process
        if (!p.isAlive) {
            current.lease?.naturalExit()
            return@withContext StopResult.Clean
        }
        current.stopRequested = true
        current.lease?.revoke()
        p.destroy()
        if (p.waitFor(config.stopGraceMillis)) StopResult.Clean
        else StopResult.TimedOut(partial = true)
    }

    /**
     * 强制手段（仅 kill 权威 §4.1 触达）：SIGKILL。返回恒 [KillCause.REQUESTED] ——
     * 终态归因用的是**调用方**传给 `EnginePool.recycle` 的 cause，引擎不知道调用方是谁，
     * 本返回只供诊断（与 `UnavailableEngine`/`FakeEngine` 同口径）。
     */
    override suspend fun kill(): KillCause = withContext(Dispatchers.IO) {
        execution?.let { current ->
            current.killRequested = true
            current.lease?.revoke()
            if (current.process.isAlive) {
                current.process.destroyForcibly()
                current.process.waitFor(KILL_REAP_MILLIS)
            }
        }
        KillCause.REQUESTED
    }

    /**
     * 进程事实推导（见类 KDoc 状态语义表）；纯读易失字段，不抛。
     * 判出「自然退出且 exit≠0」（=CRASHED）时顺带把进程侧摘要快照进 [lastSummary]。
     *
     * **只有自然退出那两条分支需要阻塞**：`isAlive=false`（已收尸）不代表排水线程已读到 EOF，
     * 不等就快照会丢掉最后几行病因 —— 故这两条分支切 [Dispatchers.IO] 并在快照前
     * [SpawnedProcess.awaitStderrDrained]（有界 500ms，超时用当前快照，绝不无限阻塞；
     * stop/kill 已在途时开头的两个 return 就短路，不付这份等待）。
     */
    override suspend fun status(): EngineStatus {
        val current = execution ?: return EngineStatus.IDLE
        val p = current.process
        if (p.isAlive) return if (current.stopRequested) EngineStatus.QUIESCING else EngineStatus.RUNNING
        if (current.killRequested) return EngineStatus.CRASHED      // 强杀钉死：退出码不作判据（也不填摘要）
        if (current.stopRequested) return EngineStatus.STOPPED      // 请求过停止：143 不是崩溃
        current.lease?.naturalExit()
        return withContext(Dispatchers.IO) {
            p.awaitStderrDrained(STDERR_DRAIN_JOIN_MILLIS)
            if (p.exitValue() == 0) {
                // exit 0 = 干净退出（STOPPED）——摘要仍快照（RunSummary(0, tail) 无害），
                // 只供「上次跑了什么」的诊断读口，状态归类不依赖它。
                snapshotSummary(current)
                EngineStatus.STOPPED
            } else {
                snapshotSummary(current)
                EngineStatus.CRASHED
            }
        }
    }

    /** 快照进程侧事实（退出码 + 捕获的 stderr 尾部）。调用方已 [SpawnedProcess.awaitStderrDrained]。 */
    private fun snapshotSummary(current: Execution) {
        val p = current.process
        current.summary = RunSummary(
            exitCode = p.exitValue(),
            stderrTail = p.stderrTail.takeIf { it.isNotBlank() },
        )
    }

    /**
     * 上次执行的进程侧事实（[ScriptEngine.lastRunSummary] 的宿主实现）：
     * 未执行 / 尚未退净 / 强杀与请求停止路径都如实回 null（那些退出码不是病因）。
     */
    override suspend fun lastRunSummary(): RunSummary? = execution?.summary

    companion object {
        /**
         * `AUTOSCRIPT_LIBNODE`（§7.8 main.cpp 必填）/ `AUTOSCRIPT_BRIDGE_ADDON`（选填）/
         * `AUTOSCRIPT_HOST_SOCKET`（选填，离线缺省）/ `AUTOSCRIPT_RUN_ID`（§8.4 心跳打点身份）/
         * `AUTOSCRIPT_RUN_NONCE`（§8.5 执行体幂等键）/ `AUTOSCRIPT_BRIDGE_DIST`（§12.4
         * facade 落位根，选填）—— 与 main.cpp 头注释、docs §7.8 同名三处，
         * 改名必须三处同批（与 ErrCode 目录同一漂移纪律）。
         */
        const val ENV_LIBNODE = "AUTOSCRIPT_LIBNODE"
        const val ENV_BRIDGE_ADDON = "AUTOSCRIPT_BRIDGE_ADDON"
        const val ENV_HOST_SOCKET = "AUTOSCRIPT_HOST_SOCKET"
        const val ENV_BRIDGE_TOKEN = "AUTOSCRIPT_BRIDGE_TOKEN"
        const val ENV_RUN_ID = "AUTOSCRIPT_RUN_ID"
        const val ENV_RUN_NONCE = "AUTOSCRIPT_RUN_NONCE"
        const val ENV_BRIDGE_DIST = "AUTOSCRIPT_BRIDGE_DIST"

        /** 强杀后的收尸等待：只防僵尸残留，不承担语义（语义在 kill 已发即完成）。 */
        private const val KILL_REAP_MILLIS = 1_000L

        /**
         * 快照摘要前 join 排水线程的上限（backlog B11 竞态修复）：够读完管道尾部，
         * 又不让「孙进程继承 stderr 不放」把 [status] 拖成无限等 —— 超时用当前快照。
         */
        private const val STDERR_DRAIN_JOIN_MILLIS = 500L

        /** 跨全部引擎实例的 runId 序列（见类 KDoc「runId 全局唯一」）。 */
        private val nodeEngineRunIds = AtomicLong(1)
    }
}
