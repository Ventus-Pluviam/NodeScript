package com.autoscript.appservice.npm

import com.autoscript.domain.host.ShellConsoleResult
import com.autoscript.domain.npm.ShellConsoleMode
import com.autoscript.domain.npm.ApprovalAction
import com.autoscript.domain.npm.ApprovalDecision
import com.autoscript.domain.npm.ApprovalRequest
import com.autoscript.domain.npm.ApprovalStatus
import com.autoscript.domain.npm.ApprovalTicket
import com.autoscript.domain.npm.AuditReport
import com.autoscript.domain.npm.ApprovalBatch
import com.autoscript.domain.npm.InstallEvent
import com.autoscript.domain.npm.InstallEventBatch
import com.autoscript.domain.npm.SequencedApproval
import com.autoscript.domain.npm.SequencedInstallEvent
import com.autoscript.domain.npm.InstallFlags
import com.autoscript.domain.npm.InstallHandle
import com.autoscript.domain.npm.InstallHistoryEntry
import com.autoscript.domain.npm.InstallHistoryOp
import com.autoscript.domain.npm.NpmCacheReclaimReport
import com.autoscript.domain.npm.MissingPkg
import com.autoscript.domain.npm.NodeModulesStats
import com.autoscript.domain.npm.NpmConfigKey
import com.autoscript.domain.npm.NpmConsoleCommand
import com.autoscript.domain.npm.NpmConsoleHandle
import com.autoscript.domain.npm.NpmConsoleKeys
import com.autoscript.domain.npm.NpmConsoleLine
import com.autoscript.domain.npm.NpmConsoleLineKind
import com.autoscript.domain.npm.NpmConsoleSnapshot
import com.autoscript.domain.npm.SequencedConsoleLine
import com.autoscript.domain.npm.NpmPanelSnapshot
import com.autoscript.domain.npm.NpmProjectSnapshot
import com.autoscript.domain.npm.NpmRegistryKeys
import com.autoscript.domain.npm.NpmRegistrySnapshot
import com.autoscript.domain.npm.PackageManagerFacade
import com.autoscript.domain.npm.PackageSpec
import com.autoscript.domain.npm.PkgNode
import com.autoscript.domain.npm.SnapshotRef
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 安装协调器（docs §10.2 InstallCoordinator）：全局唯一安装调度器。
 *
 * P0 边界（诚实口径）：
 * - **门禁/队列/事务/journal/轻操作/审批** 全部落地且 JVM 可测；
 * - **重操作执行**（vendored npm CLI 进安装会话）依赖引擎进程存在：本实现把执行体抽象为
 *   [HeavyOpExecutor] 接缝——真引擎未接前默认实现走「门禁 + journal + staging 编排 +
 *   ERR_NOT_IMPLEMENTED 如实失败」，绝不假装装上了（§1 诚实原则）。
 *
 * 门禁（§10.2）：per-project 互斥锁 + 全局会话互斥 + 磁盘 free≥[minFreeBytes] 预检 +
 * 项目配额（0.8 黄 / 1.0 拦）+ git: 依赖入口即拒（ERR_NOT_SUPPORTED）。
 */
class InstallCoordinator(
    private val services: NpmServices,
    private val executor: HeavyOpExecutor = HeavyOpExecutor.Unavailable,
    /**
     * T1 lifecycle 脚本执行体（§10.3 T1 下半段：spawn 桥 → 临时引擎）。
     * 缺省 [ScriptOpExecutor.Unavailable] = 装配缺口 → 有审批票也如实 ERR_NOT_IMPLEMENTED。
     */
    private val scriptExecutor: ScriptOpExecutor = ScriptOpExecutor.Unavailable,
    /**
     * 控制台 **shell 面**的执行缝（2026-10-09）。缺省 [ShellOpExecutor.Unavailable] =
     * 未接线 → 如实 ERR_NOT_IMPLEMENTED。真实现由 `:app` 的 `PlatformWiring` 注入
     * （见 [ShellOpExecutor] 的 KDoc：本模块看不到 `:platform:*`）。
     */
    private val shellExecutor: ShellOpExecutor = ShellOpExecutor.Unavailable,
    /**
     * 控制台 shell 命令的 TTL（毫秒，2026-10-09）。
     *
     * **为什么做成构造参数而不是常量**：`install`/`ci` 那条重操作链要 120 秒级
     * （[HeavyOp.timeoutMillis]），而 shell 命令 30 秒就该掐 —— 但**掐多少是宿主策略，
     * 不是本模块的知识**。装配层（`:app`）按自己的口径给，测试注入毫秒级值来验超时路径。
     * 常量会把「想改超时」变成「改 `:app-service:npm` 再发版」。
     */
    private val consoleShellTimeoutMillis: Long = DEFAULT_CONSOLE_SHELL_TIMEOUT_MILLIS,
    /**
     * 首选注册表解析（缺省读项目 `.npmrc`）。null ≠ 「永远不知道」：
     * 与 [NpmServices.registryOf] 同一约定——未传时用协调器自己的 npmrc 读取，
     * 测试显式关校验/换源时才注入。
     */
    registryOf: ((String) -> String?)? = null,
    /**
     * 全局镜像源（§10.2 registry 三层链的 userconfig 层，2026-10-09 批 83）。
     *
     * null = 未接线 → [resolveRegistry] 只读项目 `.npmrc`，再退到出厂官方。
     * 与 [registryOf] 同为「解析来源」，故并列在构造面上而**不进 [NpmServices]** ——
     * 那会把它推到 detekt `LongParameterList` 的构造阈值线上。
     */
    private val globalConfig: NpmGlobalConfig? = null,
    freeSpaceProbe: (projectRoot: java.nio.file.Path) -> Long = {
        Files.getFileStore(it).usableSpace
    },
    now: () -> Long = { System.currentTimeMillis() },
    config: InstallConfig = InstallConfig(),
    /** 真 cacheDir（`<data>/cache/npm-cache`，§10.2；Android 装配层注入）。 */
    npmCacheDir: Path? = null,
) : PackageManagerFacade {


    // 探针/时钟/配额不走 [NpmServices]：它们不是「外部协作者」而是本类行为参数，
    // 收进 services 会让「换一份 layout 就顺手换掉时钟」变得合法——那是两回事。
    // 保留为构造参数 + 就地私有化，测试按需注入（时钟/磁盘探针是门禁断言的输入）。
    private val freeSpaceProbe = freeSpaceProbe
    private val now = now
    private val config = config
    private val npmCacheDir = npmCacheDir

    // —— services 成员的本类私有别名 ——
    // 十一个协作者全部经 [NpmServices] 供给；这些别名把「构造面上是分组」与
    // 「使用处仍是扁平的」隔开——不然每个用法都要写 `services.xxx`，读起来像
    // 委托壳而不是协调器自己的状态。零运行时开销（构造期一次性解引用）。
    private val layout: NpmProjectLayout = services.layout
    private val journal: InstallJournal = services.journal
    private val staging: InstallStaging = services.staging
    private val ledger: ApprovalLedger = services.ledger
    private val history: InstallHistory? = services.history
    private val lockSigner: LockSigner? = services.lockSigner
    private val snapshots: NpmSnapshot? = services.snapshots
    private val cacheIndex: CacheIndex = services.cacheIndex
    private val bundleImporter: NpmOfflineBundleImporter? = services.bundleImporter
    private val registryVerifier: RegistryVerifier? = services.registryVerifier
    private val registryOf: (String) -> String? = registryOf ?: ::resolveRegistry

    /**
     * 本次首选注册表的**两层解析**（§10.2）：项目 `.npmrc` → 全局 `files/.npmrc` → null。
     *
     * 返回 null 不是「永远不知道」，而是「两层都没设」—— 调用方（校验器）据此用出厂官方，
     * 与 `HostNodeExecutor` 不注入 `--registry` 时 npm 自己解析到的**同一家**。
     *
     * **这一条链就是批 83 的交付物**：此前项目 `.npmrc` 只被校验器读到、没被 npm 读到
     * （`HostNodeExecutor` 无条件注入 `--registry` 官方 + workDir 里没有 `.npmrc`），
     * 于是「校验的首选」与「实际安装的那家」可以不是同一家。
     */
    private fun resolveRegistry(projectId: String): String? =
        readRegistryFromNpmrc(projectId) ?: globalConfig?.readRegistry()

    /** 项目 `.npmrc` 的 registry=（§10.2 第一层；缺文件/缺键/空值 → null，不猜镜像）。 */
    private fun readRegistryFromNpmrc(projectId: String): String? {
        val rc = layout.npmrc(projectId)   // projectId 合法性由 NpmProjectLayout 把着
        return NpmrcFile.readKey(rc, NpmGlobalConfig.REGISTRY_KEY)
    }

    /** lifecycle 脚本字段（§10.5-3 的扫描面）：出现任一即视为「装了但脚本没跑」。 */
    private val LIFECYCLE_KEYS = listOf(
        "preinstall", "install", "postinstall",
        "preuninstall", "uninstall", "postuninstall",
        "prepare", "preprepare", "preparePack",
    )

    // —— 运行态（全局互斥 + per-project 锁 + 事件流 + 句柄账） ——

    private val globalSession = Mutex()

    /**
     * per-project 串行锁，**用完即逐出**（曾经是只增不减的 `ConcurrentHashMap<String, Mutex>`）。
     *
     * 两件事在这里一起修：
     * - 逐出：map 随 projectId 单调增长，长跑应用里等于一份不回收的小泄漏。计数归零才摘，
     *   「有人在等/在持」时摘掉会让后来者拿到新 Mutex，per-project 串行当场失效；
     * - 原子入表：原 `getOrPut` 在 ConcurrentHashMap 上**不是**原子操作 —— 两个并发安装
     *   可能各造一个 Mutex 各持一把，两个 npm 会话同写一个项目目录。改用 `compute`：
     *   取/造与计数自增在同一把 key 锁内完成。
     */
    private val projectLocks = ConcurrentHashMap<String, ProjectLock>()

    private class ProjectLock {
        val mutex = Mutex()
        val users = java.util.concurrent.atomic.AtomicInteger(0)
    }
    private val events = MutableSharedFlow<InstallEvent>(extraBufferCapacity = 256)
    private val approvalFlow = MutableSharedFlow<ApprovalRequest>(extraBufferCapacity = 64)
    // 脚本侧拉取口的宿主缓冲（[progress]/[approvals] 那两条 Flow 无重放、只服务 :main；
    // 桥没有主动推面，脚本只能带游标来取 —— 两个环与两条 Flow 在同一批投递点一起写）。
    private val installEventRing = SeqRing<InstallEvent>(capacity = RING_CAPACITY)
    private val approvalRing = SeqRing<ApprovalRequest>(capacity = RING_CAPACITY)
    /**
     * 控制台输出环（§10.9 第 3 条，2026-10-09 批 84）。
     *
     * **与 [installEventRing] 分开而不是复用它**：事件环装的是 [InstallEvent]（脚本侧的
     * 契约形状，`bridge/js` 逐字对齐），控制台还要显示**不是事件**的东西 ——
     * 用户敲的那行（ECHO）、`ls`/`audit` 这类轻操作的渲染结果（OUTPUT）。把 ECHO 塞进
     * 事件环等于为宿主的一个界面去改脚本侧的事件契约。
     *
     * 按 projectId 过滤与事件环同款（[SeqRing.drain]）——控制台一次只看一个项目，
     * 而「一个项目刚跑完的命令」正是它要显示的东西。
     */
    private val consoleRing = SeqRing<NpmConsoleLine>(capacity = RING_CAPACITY)
    /**
     * 控制台 shell 面的执行与渲染（2026-10-09 拆出，见 [ConsoleShellRunner]）。
     *
     * 与依赖树无关的那条链（不建事务、不占安装会话、不碰项目锁）**整体**住在那一个类里：
     * 留在这里会让「敲一条 `ls`」看起来像是走了 npm 的编排。
     */
    private val shellRunner = ConsoleShellRunner(shellExecutor, consoleRing, now)
    private val handles = ConcurrentHashMap<String, TrackedOp>()
    private val handleSeq = AtomicLong(0)

    /**
     * 项目 node_modules 的已测尺寸（键 = projectId），[SIZE_CACHE_TTL_MILLIS] 内直接复用。
     *
     * 为什么缓存：配额预检原本**每次安装**都全量 `Files.walk` 一遍 node_modules
     * （大项目十万级文件、秒级），而它跑在拿全局会话锁**之前** —— 直接顶在
     * 「点安装 → 有反应」之间；`storage()` 读口同理（能力中心轮询会反复问）。
     *
     * 为什么敢缓存：这条配额是**项目的礼貌上限**，不是磁盘满的真防线 —— 真防线是
     * [freeSpaceProbe]，每次安装都真读文件系统可用空间，不受这里影响。代价是
     * **最多 [SIZE_CACHE_TTL_MILLIS] 的陈旧**：本进程装完**不失效**（一失效就等于
     * 每次安装照旧全量遍历，正是要修的东西），脚本自己跑 npm 装的那部分更看不见 ——
     * 期间可能放行一次把项目顶过配额的安装，表现是 npm 自己报 ENOSPC 或下次预检拦下，
     * 不会静默写坏数据（尺寸只决定「让不让开始」，不参与任何写路径决策）。
     *
     * 条目数 = 见过的项目数（每个一条小记录，随项目数有界，不随安装次数增长）——
     * 与 [projectLocks] 的逐出不同，这里**不能**用完即摘：摘了就等于每次都重算。
     */
    private val sizeCache = ConcurrentHashMap<String, MeasuredSize>()

    private data class MeasuredSize(val bytes: Long, val atMillis: Long)

    private data class TrackedOp(
        val handle: InstallHandle,
        val nonce: String,
        /**
         * 本次操作**有没有** journal 事务（重操作有：begin→commit/fail；T1 lifecycle 没有：
         * 它不改依赖树，见 [ScriptOpExecutor] 上方那段为什么不能借道事务链）。
         *
         * 取消路径按它分流：给没有事务的操作写一条 journal.fail 就是在事务状态机里塞一条
         * 「从未 begin 却已 fail」的记录（§10.4 的 journal 决定残骸清扫，状态机不容无源之记）。
         */
        val journaled: Boolean = true,
        @Volatile var journalStarted: Boolean = false,
        @Volatile var cancelled: Boolean = false,
        @Volatile var done: Boolean = false,
    )

    // ══════════ 重操作 ══════════

    override suspend fun install(projectId: String, specs: List<PackageSpec>, flags: InstallFlags): InstallHandle {
        // 入口即拒：git: 依赖（§10.3 明确不可行）。判据是 [NpmConsoleKeys.isGitSpec] 的
        // **唯一一份** —— 控制台那条路（`npm install git+…`）读的是同一个函数，
        // 抄第二份必然漂，而漂的那份正好是用户看到的那句话。
        specs.firstOrNull { NpmConsoleKeys.isGitSpec(it.version ?: "") || NpmConsoleKeys.isGitSpec(it.name) }
            ?.let {
                throw AutojsException(
                    ErrorCode.ERR_NOT_SUPPORTED,
                    "git: 依赖不支持（${it.name}）：请引导本地 tarball 导入（auto.npm.importTarball）",
                )
            }
        val args = buildList {
            add("install")
            specs.forEach { add(if (it.version != null) "${it.name}@${it.version}" else it.name) }
            if (!flags.save) add("--no-save")
            if (flags.offline) add("--prefer-offline")
        }
        crossCheckRegistry(projectId, specs, args)
        return enqueueHeavy(projectId, args, flags.timeoutMillis)
    }

    /**
     * §10.5-1 多镜像 integrity 交叉校验（install 前预检，不挡住后面的门禁）。
     *
     * 摆在 [enqueueHeavy] **之前**是刻意的：交叉校验要联网取两份 packument，是最慢的一步，
     * 若放在门禁之后，磁盘不够也会先付这次的网络代价；而「先校验再排队」让用户在等锁时
     * 拿到的就是最终结论，不会出现「排到队了才被告知镜像声明不一致」。
     *
     * 逐 spec 而非整批：一批里某个包被投毒不该连带另外几个清白的包也说不了话——
     * 单个包要么明确拒、要么明确降信任，报错要点名（[NpmRegistryVerifier.Verdict.Disagreed.name]）。
     *
     * [IllegalArgumentException]（包名形态非法）不在这层兜：调用方拿它当 ERR_INVALID_PARAM
     * （§7），与 git: 依赖的入口即拒同一路径。
     */
    private suspend fun crossCheckRegistry(projectId: String, specs: List<PackageSpec>, args: List<String>) {
        val verifier = registryVerifier ?: return
        val primary = registryOf(projectId)
        val downgraded = ArrayList<String>()
        for (spec in specs) {
            val verdict = verifier.verify(spec.name, spec.version, primary)
            when (verdict) {
                is NpmRegistryVerifier.Verdict.Agreed -> Unit   // 一致即放行（不广播成功）
                is NpmRegistryVerifier.Verdict.Disagreed -> {
                    // 「不一致即拒」：消息带两家的版本与摘要，否则用户/日志无从判断是谁的问题。
                    // reason 已点名包名（verifier 侧保证），这里补上两边的具体值。
                    val p = verdict.primary
                    val s = verdict.secondary
                    val detail = buildString {
                        append(verdict.reason)
                        if (p != null) append("；首选 ${p.version} ${p.integrity}")
                        if (s != null) append("；第二 ${s.version} ${s.integrity}")
                    }
                    history?.record(opName(args), projectId, false, "交叉校验不一致: $detail")
                    throw AutojsException(ErrorCode.ERR_REGISTRY_UNAVAILABLE, detail)
                }
                is NpmRegistryVerifier.Verdict.Unverifiable -> {
                    // 没验成 ≠ 有问题，但必须显式：降信任 + UI 明示，禁止静默按「通过」处理。
                    // pkgs 只放包名（与 SCRIPTS_SKIPPED 同口径：UI 拿这些名字去渲染列表），
                    // 「到底想问的是 latest 还是范围」写进 message，不塞进 pkgs。
                    downgraded += spec.name
                    val asked = "${spec.name}@${spec.version ?: "latest"}"
                    history?.record(opName(args), projectId, true, "来源未校验: $asked —— ${verdict.reason}")
                }
            }
        }
        if (downgraded.isEmpty()) return
        emit(
            InstallEvent.Warning(
                projectId = projectId,
                // 尚无 InstallHandle（还没过门禁）→ handleId 空串。为空是刻意的：拿一个
                // 还没分配的 id 去填，会让 cancel()/handles 账与事件流对不上
                handleId = "",
                kind = InstallEvent.Kind.TRUST_DOWNGRADED,
                pkgs = downgraded,
                message = "以下包的来源未能多镜像交叉校验，已标记『来源未校验』并降信任级（§10.5-1）：" +
                    "${downgraded.joinToString(", ")}。安装仍可继续，但这与『两镜像声明一致』不是同一级保证。",
            ),
        )
    }

    override suspend fun ci(projectId: String, offline: Boolean): InstallHandle {
        // §10.5-1：npm ci 严格按 lock 重建，故 ci 前必须验签——lock 被改/被换/跨项目搬运
        // 一律 ERR_PERMISSION_DENIED，绝不「没签就跳过」（TOFU 自签正是被批判的形态）。
        lockSigner?.verifyOrThrow(projectId, layout.lockfile(projectId))
        return enqueueHeavy(projectId, listOf("ci") + if (offline) listOf("--prefer-offline") else emptyList())
    }

    override suspend fun update(projectId: String, spec: String?): InstallHandle =
        enqueueHeavy(projectId, listOf("update") + listOfNotNull(spec))

    override suspend fun uninstall(projectId: String, name: String): InstallHandle =
        enqueueHeavy(projectId, listOf("uninstall", name))

    override suspend fun dedupe(projectId: String): InstallHandle = enqueueHeavy(projectId, listOf("dedupe"))

    override suspend fun prune(projectId: String): InstallHandle = enqueueHeavy(projectId, listOf("prune"))

    /**
     * 离线 bundle 导入（§10.9 UX 4：SAF 选「lock+cacache bundle」→ 合入缓存 → 按 lock 重建）。
     *
     * 链路：bundle → [NpmOfflineBundleImporter] 合入 cacache（逐条目复核 + zip slip 检疫）
     * → `npm ci --offline` 按当前 lock 离线重建。设计要点：**导入不等于安装**——
     * 合入缓存后仍走正规事务链（journal/门禁/落位），绝不「解压即算装上」。
     *
     * 未注入导入件时如实 ERR_NOT_IMPLEMENTED：把 uri 拼成 `--from-bundle` 传给 npm
     * 只会得到一个必然失败的假命令（npm 无此旗标）——那比报错更糟。
     */
    override suspend fun importOfflineBundle(projectId: String, uri: String): InstallHandle {
        val importer = bundleImporter ?: throw AutojsException(
            ErrorCode.ERR_NOT_IMPLEMENTED,
            "离线 bundle 导入未接线（NpmOfflineBundleImporter 未注入）：无法把 $uri 合入 npm 缓存",
        )
        val root = layout.projectRoot(projectId)   // projectId 合法性先过（防路径逃逸）
        val src = Paths.get(uri)
        if (!Files.isRegularFile(src)) {
            throw AutojsException(ErrorCode.ERR_FILE_NOT_FOUND, "离线 bundle 不存在：$uri（SAF 副本是否已落地？）")
        }
        val result = try {
            importer.import(src, resolveCacheDir())
        } catch (e: NpmOfflineBundleImporter.BundleTooLargeException) {
            // **超限是参数问题**（选错了文件 → 去重选），不是「文件不见了」（→ 去查 SAF
            // 副本有没有落地）—— 两者对用户的下一步动作完全不同，所以分错误码。原文
            // （实际字节数与上限）照原样带给用户；超限同样入史，不留痕等于没发生。
            history?.record(InstallHistory.Op.IMPORT, projectId, false, e.message ?: "")
            throw AutojsException(ErrorCode.ERR_INVALID_PARAM, e.message ?: "离线 bundle 超过上限", e)
        } catch (e: IllegalArgumentException) {
            // 其余 IllegalArgumentException = 导入件对「文件不在那儿」的既有口径（§10.2）。
            throw AutojsException(ErrorCode.ERR_FILE_NOT_FOUND, e.message ?: "离线 bundle 读取失败：$uri", e)
        }
        if (!result.clean) {
            // §10.5-3 禁止静默：有拒收条目必须说，否则用户以为整包都进来了
            history?.record(
                InstallHistory.Op.IMPORT, projectId, false,
                "bundle 有 ${result.rejected.size} 个条目被拒（路径非法或摘要不符）：${result.rejected.take(3)}",
            )
        }
        history?.record(
            InstallHistory.Op.IMPORT, projectId, true,
            "bundle 合入缓存 imported=${result.imported} skipped=${result.skipped} rejected=${result.rejected.size} bytes=${result.bytes}",
        )
        emit(
            InstallEvent.Warning(
                projectId = projectId,
                handleId = "bundle",
                kind = InstallEvent.Kind.REGISTRY_FALLBACK,
                pkgs = emptyList(),
                message = "离线 bundle 已合入缓存（${result.imported} 条新 / ${result.skipped} 条已存在）；随后按 lock 离线重建",
            ),
        )
        return enqueueHeavy(projectId, listOf("ci", "--offline"))
    }

    override suspend fun importTarball(projectId: String, path: String): InstallHandle =
        enqueueHeavy(projectId, listOf("install", path))

    override suspend fun audit(projectId: String, offline: Boolean): AuditReport {
        // §10.5-3：离线 = OSV 库（P1 才接）；P0 如实返回空报告并标注 offline，绝不假装扫过
        return AuditReport(vulnerabilities = emptyList(), level = AuditReport.Severity.NONE, offline = offline)
    }

    override suspend fun cancel(handle: InstallHandle) {
        handles[handle.id]?.let {
            it.cancelled = true
            if (!it.done) {
                if (it.journaled && it.journalStarted) {
                    journal.fail(
                        it.nonce, handle.projectId,
                        staging.stagePath(handle.projectId, it.nonce).fileName.toString(), "用户取消",
                    )
                    staging.sweep(handle.projectId, setOf(it.nonce))
                }
                it.finish()
                emit(InstallEvent.Finished(handle.projectId, handle.id, success = false, detail = "已取消"))
            }
        }
    }

    /**
     * per-project 串行段的唯一入口：入表 → 计数 → 执行 → 计数归零即逐出（见 [projectLocks]）。
     */
    private suspend fun <T> withProjectLock(projectId: String, block: suspend () -> T): T {
        val lock = projectLocks.compute(projectId) { _, cur ->
            (cur ?: ProjectLock()).also { it.users.incrementAndGet() }
        }!!
        try {
            return lock.mutex.withLock { block() }
        } finally {
            lock.users.decrementAndGet()
            // 归零才摘；`compute` 里再确认一次，与并发的入表互斥（不肯让「有人刚拿到」被摘走）。
            projectLocks.compute(projectId) { _, cur ->
                if (cur === lock && cur.users.get() == 0) null else cur
            }
        }
    }

    /**
     * 句柄进入终态：置位 + **从 [handles] 摘除**。
     *
     * 摘除是必须的：`handles` 原本只增不减，长跑应用里每装一次就留一条 `TrackedOp`。
     * 语义零改 —— `cancel()` 对已终态句柄本来就只做 no-op（`if (!it.done)` 那层），
     * 摘掉之后同样什么都不做，只是不再留记录。
     */
    private fun TrackedOp.finish() {
        done = true
        handles.remove(handle.id)
    }

    // ══════════ 轻操作（Kotlin 直读，零 Node 进程） ══════════

    override suspend fun list(projectId: String, depth: Int): List<PkgNode> {
        val locked = LockfileReader.readLocked(layout.lockfile(projectId))
        return locked.map { PkgNode(name = it.name, version = it.version) }
    }

    override suspend fun offlineGap(projectId: String): List<MissingPkg> =
        LockfileReader.readLocked(layout.lockfile(projectId))
            .filter { it.integrity == null || !cacheIndex.has(it.integrity) }
            .map { MissingPkg(it.name, it.version, it.resolvedSize) }

    override suspend fun config(projectId: String?, key: NpmConfigKey, value: String?, scope: String?) {
        val npmrc = layout.npmrc(projectId ?: throw AutojsException(ErrorCode.ERR_INVALID_PARAM, "projectId 必填"))
        val k = when (key) {
            NpmConfigKey.REGISTRY -> "registry"
            NpmConfigKey.PROXY -> "https-proxy"
            NpmConfigKey.CACHE_RETENTION -> "cache-retention"
        }
        // §10.2 registry 三层：项目 .npmrc 支持 `<scope>:registry`（npm 官方键形）；
        // scope 缺省/null = 全局 registry 键。作用域键与全局键互为一对一替换，不叠加。
        val scoped = scope?.takeIf { it.isNotBlank() }
        val keyName = if (scoped != null) "$scoped:$k" else k
        // 写盘走 NpmrcFile（与全局那份**同一份**实现）：两处各写一份「读全行 → removeIf
        // → 写回」必然漂，而漂的方向是「一处认 `registry = x` 带空格的写法、另一处不认」。
        NpmrcFile.writeKey(npmrc, keyName, value)
        history?.record(InstallHistory.Op.REGISTRY, projectId, true, "$keyName=$value")
    }

    /**
     * 全局镜像源读数（§10.9 第 8 条；管理面板「镜像源管理」的读口）。
     *
     * 未接线（[globalConfig] 为 null）时**如实**回「没设过 + 出厂缺省」——
     * 这是「本装配没接这一层」的诚实表达，而不是假装读到了一个空配置。
     */
    override suspend fun globalRegistry(): NpmRegistrySnapshot = NpmRegistrySnapshot(
        configured = globalConfig?.readRegistry(),
        defaultRegistry = NpmRegistryKeys.OFFICIAL,
        secondaryRegistry = NpmRegistryKeys.MIRROR,
    )

    /**
     * 设 / 清全局镜像源（§10.9 第 8 条）。
     *
     * 校验在**写盘之前**（[NpmRegistryKeys.reject]，判据的唯一一份）：不过就抛，
     * 原文点名用户输入的那个串 —— 静默收下一个坏地址，后果是此后每次安装都失败，
     * 而用户不知道自己刚才那一步就是病因。
     *
     * 审计行的 `projectId` 传**空串**，这不是笔误：全局变更没有项目维度，
     * 而 [InstallHistory.Entry.projectId] 是非空 String。改行格式会动审计契约，
     * 空串在审计页上正好读作「全局」。
     */
    override suspend fun setGlobalRegistry(raw: String?) {
        val cfg = globalConfig ?: throw AutojsException(
            ErrorCode.ERR_NOT_IMPLEMENTED,
            "全局镜像源未接线（装配层未注入 files/.npmrc）：写下去也无处生效",
        )
        val trimmed = raw?.trim().orEmpty()
        NpmRegistryKeys.reject(trimmed)?.let { throw IllegalArgumentException(it) }
        val value = trimmed.takeIf { it.isNotEmpty() }
        cfg.writeRegistry(value)
        history?.record(InstallHistory.Op.REGISTRY, "", true, "registry=${value ?: "(恢复出厂)"}")
    }

    /**
     * 依赖面板读数（§10.9.1）：全部项目的已装清单 + 离线缺口 + 目录尺寸 + **全局**待审队列。
     *
     * 待审取 `ledger.all()` 里仍是 PENDING 的那些（**跨项目**）：审批卡是全局队列，
     * 按项目筛会让用户漏掉别的项目上等着的那张。`pending(projectId)` 那条口子服务的是
     * 脚本侧 `drainApprovals`（脚本只看自己项目），两者语义不同，不要互相替换。
     *
     * 项目列表 = `storage()` 的键（= 项目根下的目录），**不是** lockfile 的键：
     * 「装了依赖但还没落 lock」的项目也要出现在面板上（它有一棵 node_modules 要管），
     * 而只有 lock 的项目反倒没有可管的东西。两者取并集是过度设计 —— 目录是超集。
     */
    override suspend fun snapshot(): NpmPanelSnapshot {
        val stats = storage()
        val pending = ledger.all()
            .filter { it.second.status == ApprovalStatus.PENDING }
            .map { it.first }
        // 缓存体积**全机一份**，故在循环外量一次（循环里量就是同一个数字抄 N 遍，
        // 而每遍都是一次全目录遍历 —— 见 cacheStorage 的 KDoc）。
        val cache = cacheStorage()
        val projects = stats.keys.sorted().map { id ->
            NpmProjectSnapshot(
                projectId = id,
                installed = list(id, depth = 0),
                offlineGap = offlineGap(id),
                storage = stats[id],
                quotaBytes = config.projectQuotaBytes,
                quotaWarnRatio = config.quotaWarnRatio,
                cache = cache,
            )
        }
        return NpmPanelSnapshot(projects = projects, pendingApprovals = pending)
    }

    /**
     * 安装审计史读数（§10.5-2；`:ui` 审计页，2026-10-09 批 85）。
     *
     * **为什么是 `snapshot()` 之外的第二条读口**：两者问的是两件事。`snapshot()` 答
     * 「此刻装了什么」（当前事实，每次现算）；本口答「过去发生过什么」（历史事实，
     * 落盘即定）。并进快照会让每次刷依赖面板都重读全部历史，而依赖面板根本不显示它。
     *
     * **无参**（与 `snapshot()` 里的待审队列同一取舍）：全量 + 呈现层筛，不按项目问 ——
     * 按项目筛会让 `Op.REGISTRY` 那条（`projectId` 空串，全局变更）从任何一次筛选里
     * 掉出去，而它恰恰是审计最该看见的。
     *
     * **未注入 history 时回空表**（不抛）：与 [snapshot] 的 `history` 用法一致 ——
     * `history` 在构造里是可空的（测试替身不注入），审计史的「没有」与「读不到」在这里
     * 合成一句「一条都没有」。这**不是**撒谎：`history?.record(...)` 在同一条路上写不进去时
     * 也是静默的（可空注入的既有语义），读侧如实回它写下的那本账。
     *
     * 返回**按写入序**（最新在最后）：那是文件里真实的顺序，读口不替呈现层决定怎么排。
     */
    override suspend fun history(): List<InstallHistoryEntry> =
        history?.all().orEmpty().map {
            InstallHistoryEntry(
                op = it.op,
                projectId = it.projectId,
                success = it.success,
                detail = it.detail,
                atMillis = it.atMillis,
            )
        }

    /**
     * 按 lock 闭包回收 npm 缓存（§10.9 第 5 条那颗 cache clean 按钮）。
     *
     * **保留集 = 全部项目 lock 闭包的并集**（不是当前项目的）：只按当前项目算会删掉别的
     * 项目离线重装要用的包，而那个后果用户在点按钮时完全看不见 —— 他只看到「省了 80MB」。
     * 一个项目都读不到 lock 时保留集为空，那时缓存里能删的全删（也正是这个按钮最该被
     * 按下去的场景：装了又删、缓存里全是没人要的 tarball）。
     *
     * 读 lock 失败的**单个**项目按「没有闭包」处理（不因一个坏 lock 让整次回收失败），
     * 但那是**如实**的：它的包会被删掉。所以保留集里还要塞进「读不到的项目目录名」——
     * 见下方 `unreadable`，宁可少删不可错删。
     *
     * 回收失败（目录被占/权限）抛 [AutojsException] 原文：这个动作的产物是**磁盘上少了
     * 东西**，静默失败会让用户以为清了其实没清（下一次点才发现还是满的）。
     */
    override suspend fun reclaimCache(): NpmCacheReclaimReport {
        val cacheDir = resolveCacheDir()
        val keep = LinkedHashSet<String>()
        val unreadable = ArrayList<String>()
        if (Files.isDirectory(layout.projectsRoot)) {
            Files.list(layout.projectsRoot).use { s ->
                s.filter { Files.isDirectory(it) }.forEach { proj ->
                    val id = proj.fileName.toString()
                    val lock = layout.lockfile(id)
                    // 没 lockfile = 「这个项目还没装过」，正常，不点名；
                    // lockfile **在**但读不出来（目录冒充、损坏到读不动、半路被删）= 点名：
                    // 那种项目不是「不需要保护」，是「想保护但保护不了」，用户有权知道。
                    if (!Files.exists(lock)) return@forEach
                    val locked = try {
                        LockfileReader.readLocked(lock)
                    } catch (e: Exception) {
                        unreadable += id
                        return@forEach
                    }
                    locked.mapNotNullTo(keep) { it.integrity?.takeIf { i -> i.isNotBlank() } }
                }
            }
        }
        val report = try {
            NpmCacheReclaim.reclaim(cacheDir, keep)
        } catch (e: java.io.IOException) {
            throw AutojsException(ErrorCode.ERR_IO, "缓存回收失败（${cacheDir}）：${e.message}", e)
        }
        // 入史：与 install/prune 同一条纪律 —— 「清了多少」是用户回看时唯一能对上的凭据。
        // detail 里带上读不到 lock 的项目名：那些项目的包**没有**被保护，用户有权知道。
        val why = buildString {
            append("removed=${report.removedEntries} entries/")
            append(report.removedBytes / 1024 / 1024).append("MB；保留 ").append(report.keptEntries)
            append(" 条（lock 闭包 ").append(report.keepCount).append(" 项）")
            if (report.indexRebuilt) append("；顺带修复了缓存索引里的悬空引用")
            if (unreadable.isNotEmpty()) append("；以下项目 lock 读不出来，其依赖未被保护：").append(unreadable.sorted())
        }
        history?.record(InstallHistoryOp.CACHE_RECLAIM, "", true, why)
        return NpmCacheReclaimReport(
            removedEntries = report.removedEntries,
            removedBytes = report.removedBytes,
            keptEntries = report.keptEntries,
            keptBytes = report.keptBytes,
            keepCount = report.keepCount,
            indexRebuilt = report.indexRebuilt,
        )
    }

    override suspend fun storage(): Map<String, NodeModulesStats> {
        if (!Files.isDirectory(layout.projectsRoot)) return emptyMap()
        val out = LinkedHashMap<String, NodeModulesStats>()
        Files.list(layout.projectsRoot).use { s ->
            s.filter { Files.isDirectory(it) }.forEach { proj ->
                val id = proj.fileName.toString()
                val pkgs = LockfileReader.readLocked(layout.lockfile(id)).size
                out[id] = NodeModulesStats(
                    projectId = id,
                    pkgCount = pkgs,
                    totalBytes = nodeModulesBytes(id),
                )
            }
        }
        return out
    }

    /**
     * npm 缓存体积（§10.9 第 5 条的 `npm-cache` 尺寸栏，2026-10-09 批 87）。
     *
     * 量的是 `content-v2`（[CacacheIndex.contentBytes]）而不是整个缓存目录：这个数字的
     * 用途是回答「回收缓存能腾出多少」，而回收动的正是 content-v2 —— 把 `index-v5`
     * （几 KB 级的索引）算进来，配额条上的数字就会与回收回执里的删/留对不上，
     * 而那两个数字摆在同一个屏幕上。
     *
     * 量不到（缓存目录还不存在）如实报 0：目录不存在就是「这个缓存是空的」。
     */
    override suspend fun cacheStorage(): NodeModulesStats =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            NodeModulesStats(projectId = "", pkgCount = 0, totalBytes = cacheIndex.contentBytes(), cacheBytes = 0)
        }

    // ══════════ 控制台命令面（§10.9 第 3 条，2026-10-09 批 84） ══════════

    /**
     * 在控制台执行一行命令。
     *
     * 三段式：**解析 → 回显 → 派发**。解析用 [NpmConsoleKeys.parse]（判据的唯一一份，
     * 与界面侧读的是同一个结论）；回显是**先**落 ECHO 行**再**派发 —— 于是
     * 「我敲了什么」与「为什么没跑成」在控制台里同一处看得见，而不是靠界面去猜
     * `runScript` 抛出来的异常是「已入队」还是「跑不起来」。
     *
     * 派发按 §10.6 的轻/重拆分：
     * - 轻操作（`ls`/`list`/`audit`）**同步现取**、不占安装会话（与 [list] 同纪律），
     *   结果渲染成 OUTPUT 行 —— 这正是「控制台是 npm 终端」在 P0 上能立刻兑现的那一半；
     * - 重操作（`install`/`uninstall`/`ci`/`prune`/`dedupe`）走 [enqueueHeavy]，
     *   磁盘预检/配额/项目锁/全局会话**一道不少**（复用，不另起一套门禁）；
     * - `npm run` / `npx` 走 T1 门禁（[runScript]/[exec]）：未获批 → `ERR_PERMISSION_DENIED`
     *   且请求已入队；获批但 spawn 桥未接 → `ERR_NOT_IMPLEMENTED`。两条都**如实**，
     *   且都在 ECHO 行之后抛出（用户看得见自己敲的那行）。
     *
     * 解析不过抛 [IllegalArgumentException] 原文；项目号不合法同样抛。
     */
    override suspend fun runConsoleCommand(projectId: String, line: String): NpmConsoleHandle {
        val handleId = "con-${handleSeq.incrementAndGet()}"
        val at = now()
        // 项目号判据与落盘侧同源（[NpmConsoleKeys.rejectProjectId] → `ScriptPaths.PROJECT_ID`）。
        NpmConsoleKeys.rejectProjectId(projectId)?.let { throw IllegalArgumentException(it) }
        val cmd = NpmConsoleKeys.parse(line)
        if (cmd is NpmConsoleCommand.Rejected) throw IllegalArgumentException(cmd.reason)
        when (cmd) {
            is NpmConsoleCommand.Rejected -> throw IllegalArgumentException(cmd.reason)   // 到不了这里（上面已拦），穷尽 when 而已
            is NpmConsoleCommand.Npm -> {
                consoleLine(projectId, echoLine(line, at))
                if (cmd.sub in NpmConsoleKeys.LIGHT_SUBCOMMANDS) {
                    runLightConsoleCommand(projectId, cmd)
                } else {
                    // 装前多镜像交叉校验（§10.5-1）**不能**只在 [install] 那条路上：
                    // 控制台敲 `npm install axios` 若绕过它，同一个动作就会因为入口不同
                    // 而受不同程度的保护 —— 门禁的强度不该取决于用户从哪个界面按下去。
                    // 包说明符由 [NpmConsoleKeys.packageSpecsIn] 从原样透传的 argv 里取
                    // （argv 本身一个字不改，装的东西与手敲 npm 完全一致）。
                    //
                    // 入史那一栏只给 `listOf(cmd.sub)` 而不是完整 argv：审计表要长期留存，
                    // 而用户手敲的 argv 可能夹着凭据形态的参数（`--//registry/:_authToken=…`、
                    // `--otp`）—— 存原文等于把用户手滑敲进来的东西永久写进盘。真正的 argv
                    // 一个字不改地交给 npm（见下一行的 `listOf(cmd.sub) + cmd.args`）。
                    if (cmd.sub == "install") {
                        crossCheckRegistry(projectId, NpmConsoleKeys.packageSpecsIn(cmd.args), listOf(cmd.sub))
                    }
                    enqueueHeavy(projectId, listOf(cmd.sub) + cmd.args)
                }
            }
            is NpmConsoleCommand.Run -> {
                consoleLine(projectId, echoLine(line, at))
                runScript(projectId, cmd.script, cmd.args)
            }
            is NpmConsoleCommand.Exec -> {
                consoleLine(projectId, echoLine(line, at))
                exec(projectId, cmd.bin, cmd.args)
            }
            is NpmConsoleCommand.Shell -> {
                consoleLine(projectId, echoLine(line, at))
                shellRunner.run(projectId, cmd.command, cmd.mode, consoleShellTimeoutMillis)
            }
            // 进/退特权模式是**界面侧的会话状态**（`:ui` 的 ConsoleCmdState.mode），
            // 宿主侧没有可做的事 —— 但**不是静默忽略**：走到这里说明界面把一条它该
            // 自己消化的命令发下来了，如实报出来（不假装执行过）。
            is NpmConsoleCommand.EnterMode -> throw IllegalArgumentException(
                "进特权模式（${cmd.mode}）由控制台界面处理，不经宿主执行入口：$line",
            )
            NpmConsoleCommand.ExitMode -> throw IllegalArgumentException(
                "exit 由控制台界面处理，不经宿主执行入口：$line",
            )
        }
        return NpmConsoleHandle(handleId = handleId, projectId = projectId, line = line, enqueuedAtMillis = at)
    }

    /**
     * 控制台输出读数（seq 游标拉取）。
     *
     * [running] 的判据是**句柄账**（不是「有没有新行」）：正在排队的重操作也算在跑 ——
     * 呈现层据此禁用输入行，而「排队中」正是用户最需要看到「它还没结束」的那一段。
     */
    override suspend fun consoleOutput(projectId: String, sinceSeq: Long, maxLines: Int): NpmConsoleSnapshot {
        val (first, last, picked) = consoleRing.drain(projectId, sinceSeq, maxLines)
        return NpmConsoleSnapshot(
            firstSeq = first,
            lastSeq = last,
            lines = picked.map { SequencedConsoleLine(it.first, it.second) },
            running = handles.values.any { it.handle.projectId == projectId && !it.done },
        )
    }

    /**
     * 命令历史读数（§10.9 第 3 条，2026-10-10 批 90）：[projectId] 下最近敲过的若干条。
     *
     * 与 [consoleOutput] 的差别不只是"另一份数据"：**那个是环、这个是盘** ——
     * 环随进程消失，历史要跨重启还在（这正是它存在的理由）。
     *
     * 按项目分开（见 [ConsoleHistory.recent] 的 KDoc）：控制台的命令跑在某个项目上，
     * 历史跟着同一个作用域走。
     */
    override suspend fun consoleHistory(projectId: String): List<String> =
        services.consoleHistory?.recent(projectId) ?: emptyList()

    /**
     * 记一条命令历史（写口，2026-10-10 批 90）。
     *
     * **为什么写口在调用方（`:ui` 的派发点）而不在本类的执行入口**：历史要记的是
     * **用户敲的那行原文**，而执行入口拿到的是**已经定形**的东西 —— shell 面那条
     * 只收得到剥掉入口词的正文（`su id` 变成 `id`），记下来再点一次就会在默认模式里
     * 被当成 npm bin 解析。派发点手上才有原文。
     *
     * 副作用是「拒收的行与进/退模式不进历史」变成了**自然结果**而不是一条特判：
     * 那些分支在派发之前就返回了。
     */
    override suspend fun recordConsoleHistory(projectId: String, line: String) {
        services.consoleHistory?.record(projectId, line, now())
    }

    /** 用户敲的那行（原文回显；`$ ` 前缀是控制台的读法，不是命令的一部分）。 */
    private fun echoLine(line: String, atMillis: Long) = NpmConsoleLine(
        kind = NpmConsoleLineKind.ECHO,
        text = "$ " + line.trim(),
        atMillis = atMillis,
    )

    /**
     * 轻操作在控制台里的**同步**执行与渲染（零 Node 进程，§10.6）。
     *
     * 项目目录不存在时如实说 —— 不 `createDirectories`：控制台敲 `npm ls` 不该
     * 顺手造出一个空项目（那是部署/新建脚本的事）。
     */
    private suspend fun runLightConsoleCommand(projectId: String, cmd: NpmConsoleCommand.Npm) {
        val at = now()
        val root = layout.projectRoot(projectId)
        if (!Files.isDirectory(root)) {
            consoleLine(
                projectId,
                NpmConsoleLine(
                    NpmConsoleLineKind.RESULT,
                    "项目 $projectId 的目录不存在（$root）：先在项目页建一个项目再装依赖",
                    at,
                    ok = false,
                ),
            )
            return
        }
        val text = when (cmd.sub) {
            "ls", "list" -> {
                val locked = LockfileReader.readLocked(layout.lockfile(projectId))
                if (locked.isEmpty()) "（没有已装的依赖：package-lock.json 不存在或为空）"
                else locked.sortedBy { it.name }.joinToString("\n") { "${it.name}@${it.version}" }
            }
            "audit" -> {
                val report = audit(projectId, offline = true)
                if (report.vulnerabilities.isEmpty()) "离线 OSV 库：未发现已知漏洞（offline=${report.offline}）"
                else report.vulnerabilities.joinToString("\n") {
                    "${it.severity} ${it.pkgName} ${it.id}"
                }
            }
            else -> "（内部错误：$cmd 不在轻操作白名单里）"
        }
        consoleLine(projectId, NpmConsoleLine(NpmConsoleLineKind.OUTPUT, text, at))
        consoleLine(
            projectId,
            NpmConsoleLine(NpmConsoleLineKind.RESULT, "npm ${cmd.sub} 完成（轻操作：Kotlin 直读，零 Node 进程）", now()),
        )
    }

    /**
     * 控制台的 **shell 面**（2026-10-09 用户口径：控制台要能执行 shell）。
     *
     * 与 [runLightConsoleCommand] 同形：**同步现取**、跑完才返回，结果渲染成
     * OUTPUT（stdout/stderr）+ RESULT（退出码）两行。**不建事务、不占安装会话、不碰项目锁**
     * —— shell 命令与依赖树无关，把它塞进 npm 的编排链只会让「敲一条 `ls`」占住全局安装会话。
     *
     * 执行与渲染整体委托 [ConsoleShellRunner]（三条纪律写在那里：DEFAULT 一律拒、
     * 非零退出是结果不是异常、执行体抛错才走异常）。本方法只做一件事：**把项目号判据
     * 与落盘侧对齐**（与 [runConsoleCommand] 同一条），再交给它。
     */
    override suspend fun runShellCommand(
        projectId: String,
        command: String,
        mode: ShellConsoleMode,
        timeoutMillis: Long,
    ): ShellConsoleResult {
        // 项目号判据与落盘侧同源（与 [runConsoleCommand] 同一条）。
        NpmConsoleKeys.rejectProjectId(projectId)?.let { throw IllegalArgumentException(it) }
        return shellRunner.run(projectId, command, mode, timeoutMillis)
    }

    // ══════════ 审批（人机分离 §10.5） ══════════

    override suspend fun requestApprove(
        projectId: String, pkg: String, versionHash: String, action: ApprovalAction,
    ): ApprovalTicket {
        val ticket = ledger.submit(projectId, pkg, versionHash, action)
        ledger.pending(projectId).firstOrNull { it.id == ticket.requestId }?.let {
            approvalRing.push(it.projectId, it)
            approvalFlow.tryEmit(it)
        }
        return ticket
    }

    override suspend fun resolveApproval(requestId: String, decision: ApprovalDecision): ApprovalTicket =
        ledger.resolve(requestId, decision)

    override suspend fun pendingApprovals(projectId: String): List<ApprovalRequest> = ledger.pending(projectId)

    // ══════════ P1 T1 lifecycle 脚本（§18 第 7 项口径：安装时让用户自己选） ══════════

    /**
     * 未获批时的**自请**（§18 第 7 项：装包时让用户自己选，故选择面必须自己浮出来）。
     *
     * 键为什么由门禁自己算、而不是复用桥面 [requestApprove] 提交的那份：门禁的判据是
     * 「**盘上此刻**的那份脚本/bin」，哈希由宿主从 manifest 重算（[NpmScriptResolver]），
     * 而脚本既不知道这个哈希、也不该有资格编一个（带自选哈希来审批、落账键与盘上现状
     * 无关，改完脚本照样命中 —— 那正是这一层要防的事）。所以 run/exec 的 APPROVED 票
     * **只能**由这条自请路径产生，且它与放行判据用的是同一处重算，两边天然对得上。
     *
     * 桥面 [requestApprove] 仍在（服务 `INSTALL_SCRIPT`：审的是**依赖包**的安装脚本，
     * 宿主无从从自己项目的 manifest 算出那个包的内容哈希），两条路投进同一个账本、
     * 同一条 approvals 流、同一张 UI 审批卡。
     *
     * [ApprovalLedger.submit] 对同 `projectId+pkg+versionHash+action` 的 PENDING 请求幂等复用，
     * 故脚本反复调 runScript 不会刷出一排重复卡片。
     */
    private suspend fun requestGateApproval(
        projectId: String, subject: String, versionHash: String, action: ApprovalAction,
    ) {
        val ticket = ledger.submit(projectId, subject, versionHash, action)
        ledger.pending(projectId).firstOrNull { it.id == ticket.requestId }?.let {
            approvalRing.push(it.projectId, it)
            approvalFlow.tryEmit(it)
        }
    }

    override suspend fun runScript(projectId: String, name: String, args: List<String>): InstallHandle {
        val root = layout.projectRoot(projectId)   // projectId 合法性先过（防路径逃逸）
        val s = NpmScriptResolver.projectScripts(root, projectId) ?: throw AutojsException(
            ErrorCode.ERR_FILE_NOT_FOUND,
            "项目 $projectId 没有 package.json（$root），无 lifecycle 脚本可跑",
        )
        if (!s.scripts.containsKey(name)) {
            // 报出**可操作**的差集：npm 自己的报法是 "Missing script"，这里给同等的量。
            throw AutojsException(
                ErrorCode.ERR_NOT_FOUND,
                "项目 ${s.pkg} 没有名为「$name」的 script（现有：${s.scripts.keys.sorted().joinToString(", ").ifEmpty { "（无）" }}）",
            )
        }
        // npm 的分隔符在**参数之前**（`npm run build -- --watch`）：少了它，脚本名后的
        // `--watch` 会被 npm 自己吃掉而不是传给脚本，等于静默丢用户显式给的参数。
        val npmArgs = if (args.isEmpty()) listOf("run", name) else listOf("run", name, "--") + args
        return runScriptOps(projectId, ApprovalAction.RUN_SCRIPT, name, args, s.pkg, s.versionHash, npmArgs)
    }

    override suspend fun exec(projectId: String, bin: String, args: List<String>): InstallHandle {
        val root = layout.projectRoot(projectId)
        // 纯 JS 白名单在解析层就拒（ERR_NOT_SUPPORTED），不在这里另写一份判据。
        val b = NpmScriptResolver.binTarget(root, bin) ?: throw AutojsException(
            ErrorCode.ERR_NOT_FOUND,
            // 指路那半句是**契约要求**的（见 `NpmConsoleKeys.parse` 的 KDoc：默认模式下
            // 裸首词按 bin 解析，查不到时宿主给的话术要带 su/shizuku）。少了它，用户在
            // 默认模式敲 `ls -la` 拿到的是一句「没有声明 bin「ls」的包」—— 那句话是
            // **对的但没用**：他想要的从来不是某个叫 ls 的包。
            "node_modules 里没有声明 bin「$bin」的包（项目 $projectId）。" +
                "如果这是想跑的 shell 命令，请先敲 su（root）或 shizuku（adb）进入特权模式",
        )
        // 同上：`npm exec <args> -- <bin>`，分隔符必须在 bin 名之前，否则 bin 会被当 args 的一员。
        val npmArgs = if (args.isEmpty()) listOf("exec", "--", bin) else listOf("exec") + args + listOf("--", bin)
        return runScriptOps(projectId, ApprovalAction.EXEC, bin, args, b.pkg, b.versionHash, npmArgs)
    }

    /**
     * T1 的**放行门禁 + 执行**（§10.3 T1）。
     *
     * 放行判据：[ApprovalLedger.isApproved] 按 `projectId + "<pkg>|<name>" + versionHash + action`
     * 命中 APPROVED 票。versionHash 是**盘上此刻**的重算值（[NpmScriptResolver]），
     * 不是调用方给的 —— 用户批过的是他当时看到的那份脚本，脚本一改哈希就变、票失配、
     * 重新弹卡。这与「版本升级必须重新审批」是同一条纪律。
     *
     * 执行体是 [scriptExecutor] 而不是 [executor]：npm CLI 走重操作通道（事务/staging/落位），
     * 而 lifecycle 脚本**不改依赖树**（跑一次 postinstall 只做它该做的事）—— 走事务链
     * 会为一个不改 node_modules 的操作凭空造出 stageDir + commit 记录，journal 里全是
     * 没有产物的假事务。两条通道共用 TTL/取消/事件，差异只在「产物要不要落位」。
     *
     * ⚠ **批准之后要重敲那一行**（2026-10-09 批 84 如实登记）：本层只**入队**请求就抛，
     * 不替用户把命令排下去 —— 于是控制台里「已入队」与「真的跑了」是两次动作。
     * 这样做是因为排队会让「批准」这个动作**顺带执行一段任意代码**，而人机分离的
     * 全部意义就是让人在按下批准之前看清楚他要放行的是什么。重敲一行的代价，
     * 换的是「批准 ≠ 执行」这条边界不模糊。
     *
     * ⚠ 这一层是**接缝**：[ScriptOpExecutor.Unavailable] 是缺省 → ERR_NOT_IMPLEMENTED。
     * spawn 桥（child_process shim → 临时引擎，§10.3 T1 下半段）未接之前，
     * 有审批票也跑不起来 —— 门禁是诚实的，失败点被如实标出来而不是假装跑过。
     */
    private suspend fun runScriptOps(
        projectId: String,
        action: ApprovalAction,
        what: String,
        args: List<String>,
        pkg: String,
        versionHash: String,
        npmArgs: List<String>,
    ): InstallHandle {
        val subject = "$pkg|$what"
        if (!ledger.isApproved(projectId, subject, versionHash, action)) {
            requestGateApproval(projectId, subject, versionHash, action)
            history?.record(action.name.lowercase(), projectId, false, "未获批放行: $subject")
            throw AutojsException(
                ErrorCode.ERR_PERMISSION_DENIED,
                (if (action == ApprovalAction.EXEC) "npm exec $what" else "npm run $what") +
                    " 未获人工批准（§10.5）：请求已入队，请到管理面板 → 依赖管理的审批卡确认后重试",
            )
        }
        val handle = InstallHandle("inst-${handleSeq.incrementAndGet()}", projectId, now())
        val op = ScriptOp(
            handleId = handle.id,
            projectId = projectId,
            action = action,
            pkg = pkg,
            what = what,
            args = args,
            projectRoot = layout.projectRoot(projectId),
            npmArgs = npmArgs,
            versionHash = versionHash,
            timeoutMillis = SCRIPT_TIMEOUT_MILLIS,
        )
        val tracked = TrackedOp(handle, "script-${handle.id}", journaled = false)
        handles[handle.id] = tracked
        try {
            withProjectLock(projectId) {
                globalSession.withLock {
                    emit(InstallEvent.Progress(projectId, handle.id, InstallEvent.Phase.QUEUED))
                    try {
                        if (tracked.cancelled) throw AutojsException(ErrorCode.ERR_ENGINE_STOPPED, "脚本执行已取消")
                        val summary = withTimeoutOrNull(op.timeoutMillis) {
                            // 同 runHeavy：经 `emit` 才进环（见那处的注释）。
                            scriptExecutor.execute(op) { ev -> emit(ev) }
                        } ?: throw AutojsException(
                            ErrorCode.ERR_TIMEOUT,
                            "脚本执行超时（${op.timeoutMillis}ms）：${what}（执行体未在 TTL 内收尾）",
                        )
                        // 取消与执行是竞态：cancel() 可能在本执行体挂起期间已发过
                        // Finished(success=false,「已取消」)。若此处不查，脚本跑完还会再发一条
                        // Finished(success=true) —— 同一句柄两个终态事件，订阅方无从裁决。
                        if (tracked.cancelled) {
                            throw AutojsException(ErrorCode.ERR_ENGINE_STOPPED, "脚本执行已取消（执行体已收尾，结果不采纳）")
                        }
                        tracked.finish()
                        history?.record(action.name.lowercase(), projectId, true, summary)
                        emit(InstallEvent.Finished(projectId, handle.id, success = true, detail = summary))
                    } catch (e: CancellationException) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { cancel(handle) }
                        throw e
                    } catch (e: Exception) {
                        // cancel() 可能已就地把 done 置位并发过终态（见上面那段竞态说明）。
                        // 一个句柄只能有一个终态事件 —— 两条会让订阅方无从裁决「那次到底成没成」。
                        val firstTerminal = !tracked.done
                        tracked.finish()
                        if (firstTerminal) {
                            history?.record(action.name.lowercase(), projectId, false, e.message)
                            emit(InstallEvent.Finished(projectId, handle.id, success = false, detail = e.message))
                        } else {
                            // 终态已发过：只把这次失败入史（审计要看到），不再重复发事件。
                            history?.record(action.name.lowercase(), projectId, false, "取消后收尾失败: ${e.message}")
                        }
                        throw e
                    }
                }
            }
        } catch (e: CancellationException) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { cancel(handle) }
            throw e
        }
        return handle
    }

    // ══════════ 事件流 ══════════

    override fun progress(projectId: String): Flow<InstallEvent> = events.filter { it.projectId == projectId }

    override fun approvals(projectId: String): Flow<ApprovalRequest> =
        approvalFlow.filter { it.projectId == projectId }

    override suspend fun drainEvents(projectId: String, sinceSeq: Long, batch: Int): InstallEventBatch {
        val (first, last, picked) = installEventRing.drain(projectId, sinceSeq, batch)
        return InstallEventBatch(first, last, picked.map { SequencedInstallEvent(it.first, it.second) })
    }

    override suspend fun drainApprovals(projectId: String, sinceSeq: Long, batch: Int): ApprovalBatch {
        val (first, last, picked) = approvalRing.drain(projectId, sinceSeq, batch)
        return ApprovalBatch(first, last, picked.map { SequencedApproval(it.first, it.second) })
    }

    // ══════════ 快照 ══════════

    override suspend fun exportSnapshot(projectId: String, uri: String): SnapshotRef {
        // §10.9.4 高信任通道：node_modules.zip + manifest 链 + ledger + lock.sig，
        // 外加 snapshot.sig（HMAC(应用密钥, 内容清单)）。无执行体/无快照件 = 诚实失败，
        // 不交付未签名的包（那会让「导入侧验签」变成空转的门）。
        val snapper = snapshots ?: throw AutojsException(
            ErrorCode.ERR_NOT_IMPLEMENTED,
            "快照导出未接线（NpmSnapshot 未注入，缺应用密钥接缝）",
        )
        val root = layout.projectRoot(projectId)
        if (!Files.isDirectory(layout.nodeModules(projectId))) {
            throw AutojsException(
                ErrorCode.ERR_FILE_NOT_FOUND,
                "项目 $projectId 无 node_modules，无可导出的快照（先 install/ci）",
            )
        }
        val tmp = Files.createTempFile(root.parent, "npm-snapshot-", ".zip")
        try {
            val built = snapper.export(projectId, tmp)
            snapper.sync(built, uri)
            history?.record(InstallHistory.Op.EXPORT, projectId, true, "entries=${built.entries} bytes=${built.contentBytes}")
            return SnapshotRef(
                uri = uri,
                sizeBytes = built.sizeBytes,
                sha256 = built.archiveSha256,
            )
        } catch (e: AutojsException) {
            history?.record(InstallHistory.Op.EXPORT, projectId, false, e.message)
            throw e
        } catch (e: CancellationException) {
            throw e        // 取消不是"快照导出失败"：不记历史、不折成 ERR_IO
        } catch (e: Exception) {
            history?.record(InstallHistory.Op.EXPORT, projectId, false, e.message)
            throw AutojsException(ErrorCode.ERR_IO, "快照导出失败：${e.message}", e)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    /** 项目 node_modules 尺寸：缓存优先，过期才真遍历（见 [sizeCache]；只服务配额预检与 [storage]）。 */
    private fun nodeModulesBytes(projectId: String): Long {
        val at = now()
        sizeCache[projectId]?.let { if (at - it.atMillis < SIZE_CACHE_TTL_MILLIS) return it.bytes }
        return DirSizer.sizeBytes(layout.nodeModules(projectId)).also {
            sizeCache[projectId] = MeasuredSize(it, at)
        }
    }

    // ══════════ 编排核心 ══════════

    /**
     * 排队 → 门禁 → 事务（journal begin → 执行 → commit/fail）→ 事件归档。
     * 全局同时至多一个安装会话（globalSession）；per-project 串行（projectLocks）。
     */
    private suspend fun enqueueHeavy(
        projectId: String,
        args: List<String>,
        timeoutMillis: Long = 120_000,
    ): InstallHandle {
        val root = layout.projectRoot(projectId)   // projectId 合法性在此校验（防路径逃逸）
        val free = freeSpaceProbe(root)
        if (free < config.minFreeBytes) {
            val why = "磁盘可用 ${free / 1024 / 1024}MB < 预检下限 ${config.minFreeBytes / 1024 / 1024}MB，拒绝安装"
            // 预检拒绝也入史（2026-10-09 批 85）：审计要能回答「用户当时看到成功了吗」，
            // 而「被配额/磁盘挡住」正是最该被看见的那类失败 —— 它不留痕的话，用户在审计页
            // 上看到的是「什么都没发生」，与他屏幕上那句报错对不上。与 `crossCheckRegistry`
            // 的预检拒绝同一条口径（那条一直在记，这两条是漏的）。
            history?.record(opName(args), projectId, false, why)
            throw AutojsException(ErrorCode.ERR_DISK_FULL, why)
        }
        val used = nodeModulesBytes(projectId)
        if (used >= config.projectQuotaBytes) {
            val why = "项目 node_modules 已达配额 ${config.projectQuotaBytes / 1024 / 1024}MB"
            history?.record(opName(args), projectId, false, why)
            throw AutojsException(ErrorCode.ERR_DISK_FULL, why)
        }
        // 80% 黄（§10.2 「项目+全局配额(80%黄·100%拦）」）：不拦，发 Warning 事件
        val quotaWarned = used >= (config.projectQuotaBytes * config.quotaWarnRatio).toLong()
        val handle = InstallHandle("inst-${handleSeq.incrementAndGet()}", projectId, now())
        val nonce = UUID.randomUUID().toString()
        val tracked = TrackedOp(handle, nonce)
        handles[handle.id] = tracked

        // 协程内联执行（挂起语义 = 排队；调用方要 fire-and-forget 可自行 launch）
        try {
            withProjectLock(projectId) {
                globalSession.withLock {
                    if (quotaWarned) {
                        emit(
                            InstallEvent.Warning(
                                projectId = projectId,
                                handleId = handle.id,
                                kind = InstallEvent.Kind.DISK_QUOTA,
                                message = "项目 node_modules 已用 ${used / 1024 / 1024}MB ≥ 配额 80%（${config.projectQuotaBytes / 1024 / 1024}MB）",
                            ),
                        )
                    }
                    runHeavy(tracked, args, timeoutMillis)
                }
            }
        } catch (e: CancellationException) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { cancel(handle) }
            throw e
        }
        return handle
    }

    private suspend fun runHeavy(tracked: TrackedOp, args: List<String>, timeoutMillis: Long) {
        val handle = tracked.handle
        val projectId = handle.projectId
        val nonce = tracked.nonce
        val stageDir = staging.stagePath(projectId, nonce)
        // §10.4 前置：扫描并清扫上次崩溃残骸
        val stale = journal.unfinished().map { it.nonce }.toSet()
        if (stale.isNotEmpty()) staging.sweep(projectId, stale)

        emit(InstallEvent.Progress(projectId, handle.id, InstallEvent.Phase.QUEUED))
        journal.begin(nonce, projectId, stageDir.fileName.toString())
        tracked.journalStarted = true
        staging.begin(projectId, nonce)
        try {
            if (tracked.cancelled) throw AutojsException(ErrorCode.ERR_ENGINE_STOPPED, "安装已取消")
            emit(InstallEvent.Progress(projectId, handle.id, InstallEvent.Phase.RESOLVE))
            // 铁律 3「每次操作必有 TTL，zombie RUNNING 不可构造」：执行体必须被时限终结。
            // 不用 withTimeout 而用 withTimeoutOrNull：后者只在**本次**超时时返回 null，
            // 不会把外层协程的取消（用户取消/UI 销毁）吞成异常再往下传。
            // 无此时限的后果是具体故障而非理论风险：npm 会话卡死 → projectLock 与
            // globalSession 双双不释放 → 此后所有 npm 操作排队到天荒地老。
            // 执行体有没有真的报过流（见下方「只在没报过流时才补 outputTail」）。
            var streamed = false
            val outcome = withTimeoutOrNull(timeoutMillis) {
                executor.execute(
                    HeavyOp(nonce, projectId, args, layout.projectRoot(projectId), stageDir, timeoutMillis),
                    // 执行体报的阶段**必须经 `emit`**（2026-10-10 批 90 修）：
                    // 原来这里是 `events.tryEmit(ev)` —— 只喂了那条 SharedFlow，
                    // 而 `installEventRing`（`drainEvents` 读的就是它）与 `consoleRing`
                    // 只在 `emit` 里写。后果是执行体独有的 DOWNLOAD/REIFY 两格
                    // **生产里从来没亮过**：脚本侧 `onProgress` 收不到，控制台阶段条
                    // 也停在 RESOLVE。这与 `emit` 自己的 KDoc（"全部阶段都经这里"）
                    // 直接矛盾，是记账与实现对不上，不是设计。
                    { ev -> emit(ev) },
                    // 真流式（2026-10-10 批 90）：执行体边读边报，这里逐行落控制台环。
                    // 与 `emit` 那条路分开 —— 那些是**事件**（脚本侧契约形状），
                    // 这条是 npm 吐的原文（只喂控制台）。批 90 之前这条数据是被丢掉的：
                    // 执行体经 ProgressSink 报的阶段**从来不进事件环**（环只由 emit 写），
                    // 于是控制台的 DOWNLOAD/REIFY 两格在生产里永远不亮。
                    { text ->
                        streamed = true
                        consoleLine(projectId, NpmConsoleLine(NpmConsoleLineKind.OUTPUT, text, now()))
                    },
                )
            } ?: throw AutojsException(
                ErrorCode.ERR_TIMEOUT,
                "安装会话超时（${timeoutMillis}ms）：npm ${args.joinToString(" ")}（执行体未在 TTL 内收尾）",
            )
            // §10.2 调用链末段：post-check（lock/产物就位校验）→ 落位 → 归档
            emit(InstallEvent.Progress(projectId, handle.id, InstallEvent.Phase.POST_CHECK))
            warnScriptsSkipped(projectId, handle.id, stageDir)
            // 执行体把产物写在 stageDir；落位由 staging.commit 原子 rename
            staging.commit(projectId, nonce)
            journal.commit(nonce, projectId, stageDir.fileName.toString())
            tracked.finish()
            // §10.5-1：install 会重写项目 lock（执行体 harvest 写回），签要跟着更新——
            // 用旧签会导致紧随其后的 ci 验签失败。签名失败 = 不谎称成功（回滚太重，
            // 改为中止本次安装：journal 已 commit 但 UI 拿到的是失败事件，用户可重试）。
            try {
                lockSigner?.sign(projectId, layout.lockfile(projectId))
            } catch (e: Exception) {
                history?.record(opName(args), projectId, false, "lock 签名失败: ${e.message}")
                emit(InstallEvent.Finished(projectId, handle.id, success = false, detail = "lock 签名失败: ${e.message}"))
                throw AutojsException(
                    ErrorCode.ERR_PERMISSION_DENIED,
                    "lock 签名失败（应用密钥不可用？§10.5-1 安全降级须显式）：${e.message}",
                )
            }
            // 命令自己的输出尾部先进控制台环（§10.9 第 3 条，2026-10-09 批 84），再发终态：
            // 顺序反了会看到「完成」压在输出上面。执行体给不出（null）时**如实说**，
            // 不拿摘要冒充输出。
            //
            // **报了流就不再补这一行**（2026-10-10 批 90）：`outputTail` 是「整条流的最后
            // 8000 字符」，而流已经把它逐行报过了 —— 再补一遍就是同一段话在控制台里出现
            // 两次，用户会以为 npm 跑了两遍。没报过流（老执行体 / [OutputSink.None] /
            // 输出为空）时才走这条老路：那时它是**唯一**的输出来源。
            //
            // 代价如实记账：流是**尽力而为**的，环满会丢最旧 —— 极端情况下（一次几万行
            // 的安装）用户可能只看到尾部，而这一行不再兜底。可接受，因为丢的正好是
            // 最不重要的开头，而「跑没跑成」由紧随其后的 RESULT 行答。
            if (!streamed) {
                consoleLine(
                    projectId,
                    NpmConsoleLine(
                        kind = NpmConsoleLineKind.OUTPUT,
                        text = outcome.outputTail ?: "（本次没有捕获到命令输出）",
                        atMillis = now(),
                    ),
                )
            }
            history?.record(opName(args), projectId, true, outcome.summary)
            emit(InstallEvent.Finished(projectId, handle.id, success = true, detail = outcome.summary))
        } catch (e: CancellationException) {
            // 事务与句柄须先收尾，再原样传播调用方取消。
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { cancel(handle) }
            throw e
        } catch (e: Exception) {
            journal.fail(nonce, projectId, stageDir.fileName.toString(), e.message)
            staging.sweep(projectId, setOf(nonce))
            tracked.finish()
            history?.record(opName(args), projectId, false, e.message)
            emit(InstallEvent.Finished(projectId, handle.id, success = false, detail = e.message))
            throw e
        }
    }

    /**
     * §10.5-3「禁止静默」：T0 主路径全程 `--ignore-scripts`（HostNodeExecutor 参数面），
     * 所以带 lifecycle 脚本的包必然有脚本**没跑**。落位前扫一遍暂存树，命中即发
     * [InstallEvent.Warning]SCRIPTS_SKIPPED + 包名清单——不阻塞安装（T0 契约就是这样），
     * 但绝不假装无事发生；UI 依赖该事件显式告知「脚本未运行」。
     *
     * 扫描面：各顶层依赖 package.json 的 install-scripts 字段（pre/post install，
     * preuninstall/uninstall，prepare/preparePack）。只读顶层（深度 1 的 node_modules/＊）
     * —— 传递依赖同属这些包自己的声明，按顶层包汇总即可覆盖。
     *
     * **与 `child_process` 门禁（[NpmSpawnGate]，2026-10-09）是两件事，别合并**：本警告管
     * 「本该跑却没跑的 lifecycle 脚本」（信息面，不阻塞安装）；门禁管「**任何** spawn 入口
     * 一律拒绝」（不变量面，硬失败）。前者对 `--ignore-scripts` 的后果负责，后者对
     * 「npm 或某个包在背地里起进程而没人知道」负责 —— 两者覆盖的失败模式不重叠。
     */
    private suspend fun warnScriptsSkipped(projectId: String, handleId: String, stageDir: java.nio.file.Path) {
        val marked = ArrayList<String>()
        if (Files.isDirectory(stageDir)) {
            Files.list(stageDir).use { s ->
                s.filter { Files.isDirectory(it) && it.fileName.toString().let { n -> !n.startsWith(".") && n != "node_modules" } }
                    .forEach { pkgDir ->
                        if (hasLifecycleScript(pkgDir.resolve("package.json"))) marked += pkgDir.fileName.toString()
                    }
            }
        }
        if (marked.isEmpty()) return
        emit(
            InstallEvent.Warning(
                projectId = projectId,
                handleId = handleId,
                kind = InstallEvent.Kind.SCRIPTS_SKIPPED,
                pkgs = marked.sorted(),
                message = "以下包的安装脚本未运行（零 spawn 契约 --ignore-scripts）：${marked.sorted().joinToString(", ")}",
            ),
        )
    }

    /**
     * cacheDir（npm 的 `--cache` 值，cacache 根）解析。
     *
     * 布局：npm cache 目录下**直接**就是 `_cacache/`（`cacache(cache)` = `<cache>/_cacache`，
     * 见 cacache `contentDir`），故 `--cache <dir>` 与 [NpmCacheSeedDeployer.cacacheDir]
     * 之间差一层 `_cacache`。真值是 `<App 数据根>/cache/npm-cache`（与 filesDir 平级，
     * §10.2「系统可自动清，损失可接受」），判据的唯一一份是
     * [NpmCacheSeedDeployer.cacheRoot]。
     *
     * 协调器只被喂了 projectsRoot，**没有 cacheDir 这个真值**——反推
     * `<projectsRoot>/../../cache/npm-cache` 在 `files/scripts` 布局下才对，一旦 projectsRoot
     * 不在这棵树下（测试/非常规布局）就静默指错地方。故仍保留 [npmCacheDir] 注入点。
     *
     * **生产装配已接上（2026-10-09 批 86）**：`NpmShellKit` 传
     * `NpmCacheSeedDeployer.cacheRoot(cacheDir)` —— 此前不传，于是「喂给 npm 的 `--cache`」
     * 与「bundle 导入落点」是两个目录，导入完 `ci --offline` 照样不命中（两边都不报错）。
     * 缺省（未注入）仍退到 projectsRoot 同级的 `.npm-cache`（可预测、测试可断言）。
     */
    private fun resolveCacheDir(): Path =
        npmCacheDir ?: layout.projectsRoot.resolveSibling(".npm-cache")

    /** 该包的 package.json 是否声明 install-scripts 字段（不解析脚本内容）。 */
    private fun hasLifecycleScript(pkgJson: java.nio.file.Path): Boolean {
        if (!Files.isRegularFile(pkgJson)) return false
        val text = String(Files.readAllBytes(pkgJson), java.nio.charset.StandardCharsets.UTF_8)
        return LIFECYCLE_KEYS.any { "\"$it\"" in text }
    }

    /**
     * 审计 op 名：取 npm CLI 子命令（install/ci/uninstall/prune/dedupe）；tarball/bundle 导入
     * 统一归 [InstallHistory.Op.IMPORT]（args 里的路径是用户数据，不入 op 名——明细走 detail）。
     */
    private fun opName(args: List<String>): String {
        val sub = args.firstOrNull() ?: "unknown"
        return if (sub == "install" && args.any { it == "--from-bundle" || it.endsWith(".tgz") || it.endsWith(".tar.gz") })
            InstallHistory.Op.IMPORT
        else sub
    }

    private suspend fun emit(e: InstallEvent) {
        // 先落环再进 Flow：两条路是同一批事件的两个视图（拉取侧有界重放、订阅侧即收即走），
        // 顺序反了会出现「Flow 已发、环还没记」的窗口 —— 拉取方在同一刻会拿到旧批次。
        installEventRing.push(e.projectId, e)
        // 控制台投影在**唯一一处**（2026-10-09 批 84）：runHeavy 与 runScriptOps 的全部
        // 阶段/警告/终态都经这里，两条执行路径不必各写一遍，也就不会有一条忘了投影。
        consoleLine(e.projectId, projectConsoleLine(e))
        events.emit(e)
    }

    /** 推一行进控制台输出环（唯一投递点，与 [emit] 同纪律）。 */
    private fun consoleLine(projectId: String, line: NpmConsoleLine) {
        consoleRing.push(projectId, line)
    }

    /**
     * [InstallEvent] → 控制台行的投影（判读只在这一处，呈现层按 [NpmConsoleLineKind] 着色）。
     *
     * 事件环与控制台环是**两个视图**，谁也不替代谁：脚本侧的 `onProgress`/`onWarning`
     * 形状一个字不改（那是 §10.8 的契约），控制台只是宿主自己的界面。
     */
    private fun projectConsoleLine(e: InstallEvent): NpmConsoleLine = when (e) {
        is InstallEvent.Progress -> NpmConsoleLine(
            kind = NpmConsoleLineKind.PHASE,
            text = phaseText(e.phase) + (e.pkg?.let { "  $it" } ?: ""),
            atMillis = now(),
        )
        is InstallEvent.Warning -> NpmConsoleLine(
            kind = NpmConsoleLineKind.WARNING,
            text = e.message,
            atMillis = now(),
        )
        is InstallEvent.Finished -> NpmConsoleLine(
            kind = NpmConsoleLineKind.RESULT,
            text = if (e.success) (e.detail ?: "完成") else "失败：${e.detail ?: "（无详情）"}",
            atMillis = now(),
            // 成败是**判读**，在宿主侧定：呈现层据此着色，不去猜那句中文怎么写。
            ok = e.success,
        )
    }

    /** 阶段的中文说法（控制台是给人看的；`bridge/js` 那边仍走 `phaseWire` 的连字符口径）。 */
    private fun phaseText(p: InstallEvent.Phase): String = when (p) {
        InstallEvent.Phase.QUEUED -> "排队中"
        InstallEvent.Phase.RESOLVE -> "解析依赖"
        InstallEvent.Phase.DOWNLOAD -> "下载"
        InstallEvent.Phase.REIFY -> "写入 node_modules"
        InstallEvent.Phase.POST_CHECK -> "收尾校验"
        InstallEvent.Phase.DONE -> "完成"
    }


    companion object {
        /** 事件环容量（与 `A11yEventRing.MAX_EVENTS` 同值同纪律）。 */
        const val RING_CAPACITY = 512

        /**
         * T1 单次脚本执行的 TTL（§7 铁律 3：每次操作必有 TTL）。
         *
         * 比安装会话短：脚本不下载依赖，跑的是已物化的代码；给满安装会话的 120s 是浪费，
         * 而卡死的脚本会占着 per-project 锁让整条 npm 链排队。60s 之后仍未收尾即
         * ERR_TIMEOUT 并由执行体走 TERM→SIGKILL 回收。
         */
        const val SCRIPT_TIMEOUT_MILLIS = 60_000L

        /**
         * 控制台 shell 命令 TTL 的**缺省值**（真值走构造参数 [consoleShellTimeoutMillis]）。
         *
         * 铁律 3「每次操作必有 TTL」：shell 命令在设备上可能挂死（等输入、等锁），
         * 而控制台的输入行会一直灰着 —— 到点即 ERR_TIMEOUT，不留无限等待。
         * 30 秒的理由：交互式命令（`ls`/`id`/`getprop`）在一秒内回，而 30 秒还没回的
         * 多半是挂死了（等输入、等锁），继续等下去只是让输入行一直灰着。
         */
        const val DEFAULT_CONSOLE_SHELL_TIMEOUT_MILLIS = 30_000L

        /**
         * node_modules 尺寸缓存 TTL（见 [sizeCache]）。
         *
         * 60s 的取舍：短到「刚装完立刻再装」也顶多多走一两趟遍历、长到能吃掉
         * 连续安装/轮询查询里的绝大多数遍历。它同时是「外部（脚本自跑 npm）改动
         * 最多被看不见多久」的上界。
         */
        const val SIZE_CACHE_TTL_MILLIS = 60_000L
    }
}
