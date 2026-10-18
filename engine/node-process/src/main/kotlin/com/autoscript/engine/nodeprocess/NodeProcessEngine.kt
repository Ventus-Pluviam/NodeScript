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
import com.autoscript.domain.scripts.ProjectId
import com.autoscript.domain.scripts.RelPath
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
    /**
     * **单脚本 JS 堆上限（MiB）**（§8.1「脚本资源限额」，批 107）：用户在设置页 → 脚本设置里
     * 填的那个数，落成 `--max-old-space-size=<n>` 插在脚本路径**之前**。
     *
     * **是 `() -> Int?` 而不是 `Int`，且每次 execute 现读** —— 与 [scriptEnv] 同一条纪律
     * （装配期定死 = 用户改完要重启 App 才生效，而「保存了却没生效」正是本仓反复点名的坑）。
     *
     * **回 null = 不加这个标记**：那不是「上限 0」，而是**完全交给 Node 的默认值**（设备上
     * 约等于「按物理内存的一半取」）。区分二者是本参数的契约 —— `0` 传下去会让脚本一启动
     * 就 OOM，而那与「不限制」相差几个数量级。
     *
     * **为什么走 argv 而不走 `NODE_OPTIONS`**：`NODE_OPTIONS` 是**父环境里别人的设置**，
     * 本仓对它的既有纪律是「追加不覆盖」（`NpmSpawnGate.mergeNodeOptions`），而堆上限是
     * **策略**不是增强 —— 追加到一个已存在的 `--max-old-space-size` 后面，Node 取最后一个，
     * 「哪个赢」取决于父子环境谁带了它，那不是确定性。argv 是确定性的：谁设的谁赢。
     * 生产实现捕获限额 store；缺省 `{ null }` = 不注入（本仓的离线/单测路径行为不变）。
     */
    val maxHeapMb: () -> Int? = { null },
    /**
     * **单脚本 libuv 后台线程池大小**（§8.1「脚本资源限额」，批 107）：落成
     * `UV_THREADPOOL_SIZE` 环境变量。
     *
     * **为什么只能是环境变量**：Node 没有 argv 形式（`node --uv-threadpool-size` 不存在），
     * libuv 的线程池大小在初始化时由 env 决定，argv 无从表达。
     *
     * 同 [maxHeapMb]：`() -> Int?` + 每次现读，null = 不注入 = **Node 自己的默认 4** ——
     * 那一项的缺省与现状逐位相同（一份没配过的配置不该改变任何行为）。
     */
    val uvThreadPoolSize: () -> Int? = { null },
    /**
     * **runId 分配器的初始水位**（2026-10-14 真机 bug）：装配期可把分配器直接建在档案
     * 高水位之后。取值形态与 `RunArchive.maxEngineRunId()` 同源（"已用过的最大号"），
     * 本类保证**下一个发出的号严格大于它** —— 两处取"下界"而不是"起点"会让调用点各差
     * 一次一，那正是这类缝最容易错的地方。缺省 `0` = 从 1 数（单测/离线路径行为不变）。
     */
    val runIdHighWater: Long = 0,
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
 * **跨进程纪律（2026-10-14 真机 bug）**：这个计数器是**进程内**的 —— App 每次重启都从 1
 * 重新数。档案却是持久的（上一进程的终态行还在库里），于是新进程第一次执行会把上一进程
 * 用掉的 engineRunId 再发一遍，`SqliteRunArchive.put` 撞「终态记录不可改写」响亮失败。
 * 装配期必须把档案高水位喂进 [NodeEngineConfig.runIdHighWater]（`RunArchive.maxEngineRunId`）——
 * 缺了这一步的形态：设备上 `run_archive` 只有 2 行而意图日志 6 行，`script-logs/index.jsonl`
 * 里 `{"run":1}` 出现三次，新进程第一次执行报 `终态记录不可改写: engineRunId=1 state=CRASHED`。
 *
 * @param launcher spawn 缝：单测注入假实现断言 argv/env/cwd，集成测试换 [ProcessBuilderLauncher] 真起进程。
 */
class NodeProcessEngine(
    override val id: EngineId,
    private val config: NodeEngineConfig,
    private val launcher: ProcessLauncher = ProcessBuilderLauncher(),
    private val identityIssuer: RunIdentityIssuer? = null,
) : ScriptEngine {

    init {
        // 构造即顶水位（2026-10-14）：配置里带了历史高水位的话，本实例一存在就已经
        // 站在历史之后 —— 不依赖装配层"记得在建完之后、第一次 execute 之前调一次"。
        raiseFloor(config.runIdHighWater)
    }

    /** 回调始终捕获本次执行，旧尾帧/旧清理不得按槽位找到新 run 的身份。 */
    private class Execution(val process: SpawnedProcess, val lease: RunIdentityLease?) {
        @Volatile var stopRequested = false
        @Volatile var killRequested = false
        @Volatile var summary: RunSummary? = null
    }

    @Volatile private var execution: Execution? = null

    /**
     * 脚本资源限额的注入面（批 107；§8.1）—— 单独成函数是为了让 [execute] 少两个分支
     * （它正好压在 detekt `CyclomaticComplexMethod` 的线上）。
     *
     * 两项的落地形态不同，理由都在各自配置项的 KDoc 里：堆上限走 **argv**（`NODE_OPTIONS`
     * 是「追加不覆盖」的别人的设置，而堆上限是策略），IO 线程池只能走 **env**
     * （libuv 初始化时读，Node 没有 argv 形式）。
     */
    private fun applyRuntimeLimits(env: MutableMap<String, String>) {
        config.uvThreadPoolSize()?.let { env[ENV_UV_THREADPOOL_SIZE] = it.toString() }
    }

    /**
     * 拼 spawn 命令行：**堆上限标志位插在脚本路径之前**。
     *
     * 位置是契约不是排版：`main.cpp` 按 node argv 规则**两段拼**（2026-10-14 修）——
     * `node_argv = {argv[0], [选项…], [-e BOOTSTRAP --], script, args...}`，选项落在
     * `--` **之前**（node 先吃选项、遇 `--` 收手）；标志位若排在 `--` 之后会被当成脚本的
     * 第一个位置参数（设备实测 `Cannot find module '--max-old-space-size=192'`，
     * 退出码 1，引擎判 CRASHED）。没配堆上限时**一个标记都不加** ——
     * 那不是「上限 0」而是完全交给 Node 的默认。
     */
    private fun spawnCommand(scriptAbs: Path, args: List<String>): List<String> {
        val heapFlag = config.maxHeapMb()?.let { "--max-old-space-size=$it" }
        return buildList {
            add(config.hostBinary.toString())
            if (heapFlag != null) add(heapFlag)
            add(scriptAbs.toString())
            addAll(args)
        }
    }

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
                // scriptPath/projectId 的词法闸在 ScriptPaths.scriptFile 内（外审 M1 收口，
                // 2026-10-12）。这里把它折成 ERR_INVALID_PARAM 而不是让 IllegalArgumentException
                // 裸穿出去：这条路径两个调用方语义不同 —— 桥侧 RPC 本来就把 IAE 折成
                // ERR_INVALID_PARAM（RpcNamespaceHandler），而调度侧会把裸 IAE 记成宿主内部错。
                // 判据「参数不对」只有一个错误码，两条路才对得上。
                val scriptAbs = try {
                    // 判据在**构造值类**那一刻（批 109）：`EngineRunRequest` 的两个字段仍是
                    // `String`（DTO 边界），到这里才折成 `ProjectId`/`RelPath` ——
                    // 折不出就是「这俩不是合法项目号/项目内相对路径」，与外层那个 catch 同一语义。
                    ScriptPaths.scriptFile(
                        config.filesDir,
                        ProjectId(run.projectId),
                        RelPath(run.scriptPath),
                    )
                } catch (e: IllegalArgumentException) {
                    throw AutojsException(
                        ErrorCode.ERR_INVALID_PARAM,
                        "脚本路径非法：${e.message}（projectId=${run.projectId}）",
                        e,
                    )
                }
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

                applyRuntimeLimits(env)
                val command = spawnCommand(scriptAbs, run.args)
                val cwd = ScriptPaths.projectRoot(config.filesDir, ProjectId(run.projectId))
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
     * 冻住这次执行（2026-10-13；SPI [ScriptEngine.pause] 的真实现）。
     *
     * 委托当前 execution 的进程（SIGSTOP）。false = 没有在跑的执行 / 进程侧冻不住
     * （pid 拿不到 / `kill` 二进制缺席 —— `SpawnedProcess.pauseExecution` 如实报）。
     * **status() 保持 RUNNING**：暂停是 `RuntimeController` 旁账里的事（`paused` 集），
     * 引擎状态机不加 SUSPENDED（触点太多，见 RuntimeController 的 KDoc）。
     */
    override suspend fun pause(): Boolean = withContext(Dispatchers.IO) {
        val current = execution ?: return@withContext false
        if (!current.process.isAlive) return@withContext false
        current.process.pauseExecution()
    }

    /** 解冻 [pause] 冻住的执行（SIGCONT；语义对偶）。 */
    override suspend fun resume(): Boolean = withContext(Dispatchers.IO) {
        val current = execution ?: return@withContext false
        if (!current.process.isAlive) return@withContext false
        current.process.resumeExecution()
    }

    /**
     * 往这次执行的脚本进程 stdin 送一行（2026-10-16 用户令；SPI [ScriptEngine.sendInput]
     * 的真实现）。
     *
     * 与 [pause]/[resume] 同一条"当前执行体"口径：没有在跑的执行 → false。
     * 管道写本身可能阻塞（脚本不读且缓冲满），故落在 `Dispatchers.IO` —— 与
     * [stop]/[kill] 同一条纪律，不让它占住调用方的线程。
     *
     * **不检查 `stopRequested`**：正在收尸的执行写 stdin 只会拿到 false（管道已关），
     * 多一个判据就多一处会漂的状态；真实判据是"管道还在不在"，那由写本身回答。
     */
    override suspend fun sendInput(line: String): Boolean = withContext(Dispatchers.IO) {
        val current = execution ?: return@withContext false
        current.process.writeStdin(line)
    }

    /**
     * 关掉这次执行的 stdin 写端发 EOF（2026-10-16 用户令「答完自己退」；SPI
     * [ScriptEngine.closeStdin] 的真实现）。宿主侧那条链的终点：脚本答完最后一个问题、
     * 关掉 readline 之后，`stdin-idle.ts` 报上来的"没有读者了"在这里落成真 EOF。
     *
     * 与 [sendInput] 同一条"当前执行体"口径（没有在跑的执行 → false），也同一条
     * `Dispatchers.IO` 纪律：关流可能等内核缓冲落地，不该占调用方的线程。
     *
     * **不检查 `stopRequested`**：正在收尸的执行关 stdin 只会拿到 false（进程没了/管道已关），
     * 与 [sendInput] 同 —— 真实判据是"管道还在不在"，那由关流本身回答。
     */
    override suspend fun closeStdin(): Boolean = withContext(Dispatchers.IO) {
        val current = execution ?: return@withContext false
        current.process.closeStdin()
    }

    /**
     * 四步 quiesce 的「请求退出」步（§8.3）：SIGTERM → [NodeEngineConfig.stopGraceMillis] 内退净
     * 回 [StopResult.Clean]；超时回 [StopResult.TimedOut]（partial=true = 尚有残留），
     * 池侧 `PoolSlot.quiesce` 据此 kill 兜底。从未启动/已退净 → 如实 Clean（无事可停）。
     *
     * **先解冻再停**（2026-10-13）：SIGTERM 发给 SIGSTOP 冻住的进程不会被处理 ——
     * 不解冻就 3s 必 TimedOut 降级 SIGKILL，丢掉干净退出语义与排水尾部。解冻失败
     * （冻不住的进程本来也没被冻住）不阻断停止。
     */
    override suspend fun stop(): StopResult = withContext(Dispatchers.IO) {
        val current = execution ?: return@withContext StopResult.Clean
        val p = current.process
        if (!p.isAlive) {
            current.lease?.naturalExit()
            return@withContext StopResult.Clean
        }
        current.process.resumeExecution()
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

        /**
         * `UV_THREADPOOL_SIZE`：libuv 后台线程池大小（§8.1「脚本资源限额」，批 107）。
         *
         * **刻意不是 `AUTOSCRIPT_` 前缀** —— 那一族是本仓与 `main.cpp` 的内部约定
         * （[ScriptEnvKeys] 据此拒收用户同前缀的键，避免覆盖导致越权）。而这一条**本来就是
         * Node 自己的环境变量**：叫 `UV_THREADPOOL_SIZE` 才能被 Node 读，加前缀等于
         * 没人读。它不是「用户不能配」的那一族 —— 用户正是通过本仓的设置页配它，
         * 只是不经过 `process.env` 那条自由输入的路。
         */
        const val ENV_UV_THREADPOOL_SIZE = "UV_THREADPOOL_SIZE"

        /** 强杀后的收尸等待：只防僵尸残留，不承担语义（语义在 kill 已发即完成）。 */
        private const val KILL_REAP_MILLIS = 1_000L

        /**
         * 快照摘要前 join 排水线程的上限（backlog B11 竞态修复）：够读完管道尾部，
         * 又不让「孙进程继承 stderr 不放」把 [status] 拖成无限等 —— 超时用当前快照。
         */
        private const val STDERR_DRAIN_JOIN_MILLIS = 500L

        /** 跨全部引擎实例的 runId 序列（见类 KDoc「runId 全局唯一」）。 */
        private val nodeEngineRunIds = AtomicLong(1)

        /** 把共享序列顶到 `usedThrough` 之后（返回下一个待发号；`<=0` 只读现值）。 */
        private fun raiseFloor(usedThrough: Long): Long {
            if (usedThrough <= 0) return nodeEngineRunIds.get()
            val next = if (usedThrough == Long.MAX_VALUE) Long.MAX_VALUE else usedThrough + 1
            return nodeEngineRunIds.updateAndGet { cur -> if (cur >= next) cur else next }
        }
    }
}
