package com.autoscript.engine.nodeprocess

import com.autoscript.domain.bridge.HandleRef
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.engine.EngineId
import com.autoscript.domain.engine.EngineRunReceipt
import com.autoscript.domain.engine.EngineRunRequest
import com.autoscript.domain.engine.EngineStatus
import com.autoscript.domain.engine.KillCause
import com.autoscript.domain.engine.ScriptEngine
import com.autoscript.domain.engine.StopResult
import com.autoscript.domain.scripts.ScriptPaths
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
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
     * `AUTOSCRIPT_BRIDGE_DIST`（§12.4 资产交付轨，选填）：facade dist 落位根
     * （生产 = `ScriptPaths.autoModuleRoot(filesDir)`）。给则**仅当** `bootstrap.js` 在位
     * 才注入 —— 与 addon 同一条选填纪律：配置了但资产没落位 = 降级为不注入，
     * main.cpp 打 stderr 如实说「facade 未接入」，脚本本体照跑（不为锦上添花杀执行）。
     * 缺省 null = 不注入（单测/桌面不经资产部署的路径）。
     */
    val bridgeDistPath: Path? = null,
    /** 四步 quiesce 的排空窗口（§8.3）：`stop()` SIGTERM 后等这么久，未退则 TimedOut 交池 kill 兜底。 */
    val stopGraceMillis: Long = 3_000,
)

/**
 * `ScriptEngine` 的进程池实现（docs/framework-design.md §8.1「实现在 :engine:node-process」/
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
) : ScriptEngine {

    @Volatile
    private var process: SpawnedProcess? = null

    /** `stop()` 已请求退出：存活期宿主自报 QUIESCING，退净后报 STOPPED（SIGTERM 的 143 不算崩溃）。 */
    @Volatile
    private var stopRequested: Boolean = false

    /**
     * `kill()` 已执行（SIGKILL 后退出码的 128+sig 约定因平台而异，不可作终态判据）——
     * 强杀钉死 CRASHED，与 `FakeEngine.kill` 同口径；优先级高于 stopRequested。
     */
    @Volatile
    private var killRequested: Boolean = false

    /** 只在子进程存活时给真 pid；未启动/已退出回 null（§8.4 诚实口径，绝不 0/自身）。 */
    override val pid: Int?
        get() = process?.takeIf { it.isAlive }?.pid

    override suspend fun execute(run: EngineRunRequest): EngineRunReceipt = withContext(Dispatchers.IO) {
        // 同引擎一次一脚本（§8.2）：池纪律保证槽位排他，走到这说明上一执行体没被 quiesce 净 ——
        // 先强杀防泄漏再如实失败（留着它 = 无人监管的野进程）。
        process?.takeIf { it.isAlive }?.let { alive ->
            val leaked = alive.pid
            alive.destroyForcibly()
            alive.waitFor(KILL_REAP_MILLIS)
            throw IllegalStateException(
                "同引擎一次一脚本被破坏：上一执行体仍在运行（pid=$leaked），已强杀防泄漏后拒绝本次启动",
            )
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
        val addonEffective = config.addonPath?.takeIf { Files.isRegularFile(it) }

        val runId = nodeEngineRunIds.getAndIncrement()
        val env = linkedMapOf<String, String>()
        config.libnodePath?.let { env[ENV_LIBNODE] = it.toString() }
        addonEffective?.let { env[ENV_BRIDGE_ADDON] = it.toString() }
        config.hostSocketName?.let { env[ENV_HOST_SOCKET] = it }
        // facade dist 同 addon 的选填纪律：配置了但 bootstrap.js 没落位 = 不注入
        // （main.cpp 由此得知"没 dist 可 attach"并打 stderr，而不是注入一个坏路径）。
        config.bridgeDistPath?.takeIf { Files.isRegularFile(it.resolve("bootstrap.js")) }
            ?.let { env[ENV_BRIDGE_DIST] = it.toString() }
        // 执行体身份（§8.5 幂等键 + §8.4 心跳打点）：runId/runNonce 随 env 下传，
        // JS 侧（bootstrap/脚本）读 process.env 即可 startHeartbeat(runId) —— 不再另造 argv 通道。
        env[ENV_RUN_ID] = runId.toString()
        run.runNonce?.let { env[ENV_RUN_NONCE] = it }

        val command = listOf(config.hostBinary.toString(), scriptAbs.toString()) + run.args
        val cwd = ScriptPaths.projectRoot(config.filesDir, run.projectId)
        stopRequested = false
        killRequested = false
        val spawned = try {
            launcher.spawn(command, env, cwd)
        } catch (e: IOException) {
            throw AutojsException(
                ErrorCode.ERR_IO,
                "拉起引擎宿主失败：cmd=[${command.joinToString(" ")}] —— ${e.message}",
                e,
            )
        }
        process = spawned
        EngineRunReceipt(
            runId = runId,
            handle = HandleRef(refId = runId, generation = 1),
            pid = spawned.pid,
        )
    }

    /**
     * 四步 quiesce 的「请求退出」步（§8.3）：SIGTERM → [NodeEngineConfig.stopGraceMillis] 内退净
     * 回 [StopResult.Clean]；超时回 [StopResult.TimedOut]（partial=true = 尚有残留），
     * 池侧 `PoolSlot.quiesce` 据此 kill 兜底。从未启动/已退净 → 如实 Clean（无事可停）。
     */
    override suspend fun stop(): StopResult = withContext(Dispatchers.IO) {
        val p = process
        if (p == null || !p.isAlive) return@withContext StopResult.Clean
        stopRequested = true
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
        killRequested = true
        process?.let {
            if (it.isAlive) {
                it.destroyForcibly()
                it.waitFor(KILL_REAP_MILLIS)
            }
        }
        KillCause.REQUESTED
    }

    /** 进程事实推导（见类 KDoc 状态语义表）；纯读易失字段，不阻塞、不抛。 */
    override suspend fun status(): EngineStatus {
        val p = process ?: return EngineStatus.IDLE
        if (p.isAlive) return if (stopRequested) EngineStatus.QUIESCING else EngineStatus.RUNNING
        if (killRequested) return EngineStatus.CRASHED      // 强杀钉死：退出码不作判据
        if (stopRequested) return EngineStatus.STOPPED      // 请求过停止：143 不是崩溃
        return if (p.exitValue() == 0) EngineStatus.STOPPED else EngineStatus.CRASHED
    }

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
        const val ENV_RUN_ID = "AUTOSCRIPT_RUN_ID"
        const val ENV_RUN_NONCE = "AUTOSCRIPT_RUN_NONCE"
        const val ENV_BRIDGE_DIST = "AUTOSCRIPT_BRIDGE_DIST"

        /** 强杀后的收尸等待：只防僵尸残留，不承担语义（语义在 kill 已发即完成）。 */
        private const val KILL_REAP_MILLIS = 1_000L

        /** 跨全部引擎实例的 runId 序列（见类 KDoc「runId 全局唯一」）。 */
        private val nodeEngineRunIds = AtomicLong(1)
    }
}
