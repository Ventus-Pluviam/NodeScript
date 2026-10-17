package com.autoscript.appservice.npm

import com.autoscript.domain.scripts.ProjectId
import com.autoscript.domain.host.ShellConsoleResult
import com.autoscript.domain.npm.ShellConsoleMode
import com.autoscript.domain.npm.AuditReport
import com.autoscript.domain.npm.InstallEvent
import com.autoscript.domain.npm.InstallEventBatch
import com.autoscript.domain.npm.SequencedInstallEvent
import com.autoscript.domain.npm.InstallFlags
import com.autoscript.domain.npm.InstallHandle
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
 * - **门禁/队列/事务/journal/轻操作** 全部落地且 JVM 可测（审批 2026-10-11 拆除）；
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
     * 缺省 [ScriptOpExecutor.Unavailable] = 装配缺口 → 如实 ERR_NOT_IMPLEMENTED。
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
    /**
     * 磁盘可用字节探针。缺省走 [FreeSpace]（**不用 `Files.getFileStore`** —— Android 上那格
     * 没实现，见 [FreeSpace] 的类 KDoc）；**生产永不用这个缺省**：[NpmShellKit.assembleHandler]
     * 恒传一个带 filesDir 兜底的探针（项目目录可能还不存在），本缺省只服务"裸造一个
     * InstallCoordinator"的场合（本仓只有测试这么干，而测试全都显式注入固定值）。
     */
    freeSpaceProbe: (projectRoot: java.nio.file.Path) -> Long = { FreeSpace.usableBytes(it) },
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
    /**
     * 操作事实的去向（[NpmServices.opLog]）。
     *
     * 2026-10-17 用户令「去掉审计、改打系统日志」：这里原先是 `services.history`
     * （一本落盘账）。现在协调器只把**事实**交出去（[OpLogSink]），去哪儿由装配层定。
     */
    private val opLog: OpLogSink = services.opLog
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
        val rc = layout.npmrc(ProjectId(projectId))   // projectId 合法性由 NpmProjectLayout 把着
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
    // 脚本侧拉取口的宿主缓冲（[progress] 那条 Flow 无重放、只服务 :main；
    // 桥没有主动推面，脚本只能带游标来取 —— 环与 Flow 在同一批投递点一起写）。
    private val installEventRing = SeqRing<InstallEvent>(capacity = RING_CAPACITY)
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
    private val sizeCache = ConcurrentHashMap<ProjectId, MeasuredSize>()

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
    ) : OpCancelSignal {
        // 执行体经 ScriptOp.cancelSignal 读的就是这一位（批 94：「停止」按下去 → 这里置位
        // → 执行体在 waitFor 切片间隙看到 → 杀进程树）。
        override fun isCancelled(): Boolean = cancelled
    }

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
     *
     * **取 packument 必须离开调用线程**（2026-10-17 设备实测的 `NetworkOnMainThreadException`）：
     * [NpmRegistryVerifier.verify] 是**同步阻塞**的 `HttpURLConnection`（每个包两趟 packument，
     * 5s + 8s 超时），而这条链从界面/控制台一路 suspend 下来 —— suspend 只给"可挂起"的许可，
     * **不搬线程**。界面那条协程是 `MainActivity` 的 `rememberCoroutineScope()`（= Main），
     * 于是取 packument 落在主线程上，Android 当场抛：装依赖一个包都还没发出去就没了。
     * 包在 `Dispatchers.IO` 上是本文件既有的写法（见 [cacheStorage]），这条只是先前漏了。
     */
    private suspend fun crossCheckRegistry(projectId: String, specs: List<PackageSpec>, args: List<String>) {
        val verifier = registryVerifier ?: return
        val primary = registryOf(projectId)
        val downgraded = ArrayList<String>()
        for (spec in specs) {
            // 离开调用线程（理由见 KDoc：主线程禁用网络）。每个包两趟往返都可能阻塞
            // 十几秒，所以整趟都放 IO 上，不只是"发请求那一行"。
            val verdict = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                verifier.verify(spec.name, spec.version, primary)
            }
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
                    opLog.log(opName(args), projectId, false, "交叉校验不一致: $detail")
                    throw AutojsException(ErrorCode.ERR_REGISTRY_UNAVAILABLE, detail)
                }
                is NpmRegistryVerifier.Verdict.Unverifiable -> {
                    // 没验成 ≠ 有问题，但必须显式：降信任 + UI 明示，禁止静默按「通过」处理。
                    // pkgs 只放包名（与 SCRIPTS_SKIPPED 同口径：UI 拿这些名字去渲染列表），
                    // 「到底想问的是 latest 还是范围」写进 message，不塞进 pkgs。
                    downgraded += spec.name
                    val asked = "${spec.name}@${spec.version ?: "latest"}"
                    opLog.log(opName(args), projectId, true, "来源未校验: $asked —— ${verdict.reason}")
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
        lockSigner?.verifyOrThrow(projectId, layout.lockfile(ProjectId(projectId)))
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
        val root = layout.projectRoot(ProjectId(projectId))   // projectId 合法性先过（防路径逃逸）
        val src = Paths.get(uri)
        if (!Files.isRegularFile(src)) {
            throw AutojsException(ErrorCode.ERR_FILE_NOT_FOUND, "离线 bundle 不存在：$uri（SAF 副本是否已落地？）")
        }
        val result = try {
            importer.import(src, resolveCacheDir())
        } catch (e: NpmOfflineBundleImporter.BundleTooLargeException) {
            // **超限是参数问题**（选错了文件 → 去重选），不是「文件不见了」（→ 去查 SAF
            // 副本有没有落地）—— 两者对用户的下一步动作完全不同，所以分错误码。原文
            // （实际字节数与上限）照原样带给用户；超限同样记一条，不留痕等于没发生。
            opLog.log(OpName.IMPORT, projectId, false, e.message ?: "")
            throw AutojsException(ErrorCode.ERR_INVALID_PARAM, e.message ?: "离线 bundle 超过上限", e)
        } catch (e: IllegalArgumentException) {
            // 其余 IllegalArgumentException = 导入件对「文件不在那儿」的既有口径（§10.2）。
            throw AutojsException(ErrorCode.ERR_FILE_NOT_FOUND, e.message ?: "离线 bundle 读取失败：$uri", e)
        }
        if (!result.clean) {
            // §10.5-3 禁止静默：有拒收条目必须说，否则用户以为整包都进来了
            opLog.log(
                OpName.IMPORT, projectId, false,
                "bundle 有 ${result.rejected.size} 个条目被拒（路径非法或摘要不符）：${result.rejected.take(3)}",
            )
        }
        opLog.log(
            OpName.IMPORT, projectId, true,
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
     * 控制台「停止」（批 94）：取消该项目此刻全部未终态句柄。
     *
     * 快照 values 再逐个 [cancel] —— `cancel` 会把句柄从 [handles] 摘掉（`finish()`），
     * 边遍历边摘会漏。覆盖面（T1 真杀 / 重操作弃结果）见 facade 方法的 KDoc，
     * 本方法不另判通道：**取消的判据只有一处**（句柄账），通道差异在执行体那侧兑现。
     */
    override suspend fun cancelConsoleCommand(projectId: String): Boolean {
        val running = handles.values.filter { it.handle.projectId == projectId && !it.done }
        running.forEach { cancel(it.handle) }
        return running.isNotEmpty()
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

    /**
     * 已装清单（§10.9.1）：lockfile v3 的 `packages` 段逐键读出来的**平铺闭包**。
     *
     * **按 (name, version) 归一**（2026-10-17 设备实测「依赖装多了，查看的时候容易闪退」）：
     * lock v3 的键是**安装位置**（`node_modules/c`、`node_modules/a/node_modules/c`…），不是包的
     * 身份 —— 同一个包同一个版本可以在不同父级各落一份（peer 冲突时最常见：根上是 c@2，
     * a 与 b 各嵌一份 c@1）。不归一的后果不是"多画一行"那么轻：依赖管理页的清单用
     * `items(key = "<name>@<version>")` 铺，**键重复时 Compose 当场抛**（
     * `androidx.compose.ui.layout.LayoutNodeSubcompositionsState` 那句
     * `Key "c@1.0.0" was already used`）——用户看到的就是"依赖装多了，一进这一页就闪退"。
     * 装得越多，两份同名同版越可能撞上。
     *
     * 归一之后这份清单是**包清单**而不是**位置清单**：页面本来不画路径，两行一模一样的字
     * 配两个「卸载」对用户没有任何信息量（`npm uninstall` 认的也是名字，不是位置）。
     *
     * **整趟落 IO**：[LockfileReader.readLocked] 是"读整份 lock 再手写解析"（依赖多时几百 KB），
     * 而界面那条链（`rememberCoroutineScope()` = Main）是直接 await 它的 —— suspend 只给
     * "可挂起"的许可，**不搬线程**；安装期间界面还每 400ms 拉一次快照。包 IO 是本文件既有写法
     * （见 [cacheStorage] 与 [crossCheckRegistry] 的 KDoc）。
     */
    override suspend fun list(projectId: String, depth: Int): List<PkgNode> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            LockfileReader.readLocked(layout.lockfile(ProjectId(projectId)))
                .distinctBy { it.name to it.version }
                .map { PkgNode(name = it.name, version = it.version) }
        }

    /** 离线闭包缺口（读法与 [list] 同一份 lock、同一趟 IO，故一并挪出调用线程）。 */
    override suspend fun offlineGap(projectId: String): List<MissingPkg> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            LockfileReader.readLocked(layout.lockfile(ProjectId(projectId)))
                .filter { it.integrity == null || !cacheIndex.has(it.integrity) }
                .map { MissingPkg(it.name, it.version, it.resolvedSize) }
        }

    override suspend fun config(projectId: String?, key: NpmConfigKey, value: String?, scope: String?) {
        val npmrc = layout.npmrc(
            ProjectId.createOrNull(projectId ?: throw AutojsException(ErrorCode.ERR_INVALID_PARAM, "projectId 必填"))
                ?: throw AutojsException(ErrorCode.ERR_INVALID_PARAM, "projectId 非法：$projectId"),
        )
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
        opLog.log(OpName.REGISTRY, projectId, true, "$keyName=$value")
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
     * 记给去向的那条的 `projectId` 传**空串**，这不是笔误：全局变更没有项目维度，
     * 而 [OpLogSink.log] 的 `projectId` 是非空 String。空串由去向侧（`HostLog`）
     * 读作「全局」—— 判据只有那一条，不在写侧另造一个哨兵值。
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
        opLog.log(OpName.REGISTRY, "", true, "registry=${value ?: "(恢复出厂)"}")
    }

    /**
     * 依赖面板读数（§10.9.1）：全部项目的已装清单 + 离线缺口 + 目录尺寸。
     *
     * 项目列表 = `storage()` 的键（= 项目根下的目录），**不是** lockfile 的键：
     * 「装了依赖但还没落 lock」的项目也要出现在面板上（它有一棵 node_modules 要管），
     * 而只有 lock 的项目反倒没有可管的东西。两者取并集是过度设计 —— 目录是超集。
     */
    override suspend fun snapshot(): NpmPanelSnapshot =
        // 整趟落 IO（2026-10-17 设备实测「依赖装多了，查看的时候容易闪退」）：这一趟是
        // 「全项目目录遍历 + 每项目两份 lock 解析 + 每棵 node_modules 全树算尺寸 + 缓存目录遍历」，
        // 全在**同一次调用**里；而进这一页、点刷新、以及安装期间每 400ms 一次的续拉，
        // 都是主线程那条协程直接 await 它的。依赖装到几十上百个包时，这就是主线程上一次
        // 几百毫秒的阻塞 —— Android 上表现为 ANR，用户看到的就是"一查看就闪退"。
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val stats = storage()
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
            NpmPanelSnapshot(projects = projects)
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
    override suspend fun reclaimCache(): NpmCacheReclaimReport =
        // 落 IO 的理由与 [snapshot] 同族（2026-10-17）：这一趟要遍历全部项目的 lock、
        // 遍历整个缓存目录并真删文件，而这颗按钮是**主线程那条协程**直接 await 的
        // （`reclaimNpmCacheOp`）。包一层 `withContext` 是"做阻塞调用的那一层自己声明它是 IO"
        // 在别处（[cacheStorage]）已有的写法。
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val cacheDir = resolveCacheDir()
            val keep = LinkedHashSet<String>()
            val unreadable = ArrayList<String>()
            if (Files.isDirectory(layout.projectsRoot)) {
                Files.list(layout.projectsRoot).use { s ->
                    s.filter { Files.isDirectory(it) }.forEach { proj ->
                        // 盘上读回的目录名：判不过判据就跳过（与 `storage()` 同款纪律 ——
                        // 一个别人留下的怪目录不该让「回收缓存」整个失败）。
                        val pid = ProjectId.createOrNull(proj.fileName.toString()) ?: return@forEach
                        val lock = layout.lockfile(pid)
                        // 没 lockfile = 「这个项目还没装过」，正常，不点名；
                        // lockfile **在**但读不出来（目录冒充、损坏到读不动、半路被删）= 点名：
                        // 那种项目不是「不需要保护」，是「想保护但保护不了」，用户有权知道。
                        if (!Files.exists(lock)) return@forEach
                        val locked = try {
                            LockfileReader.readLocked(lock)
                        } catch (e: Exception) {
                            unreadable += pid.value
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
            // 记一条：与 install/prune 同一条纪律 —— 「清了多少」是用户回看时唯一能对上的凭据。
            // detail 里带上读不到 lock 的项目名：那些项目的包**没有**被保护，用户有权知道。
            val why = buildString {
                append("removed=${report.removedEntries} entries/")
                append(report.removedBytes / 1024 / 1024).append("MB；保留 ").append(report.keptEntries)
                append(" 条（lock 闭包 ").append(report.keepCount).append(" 项）")
                if (report.indexRebuilt) append("；顺带修复了缓存索引里的悬空引用")
                if (unreadable.isNotEmpty()) append("；以下项目 lock 读不出来，其依赖未被保护：").append(unreadable.sorted())
            }
            opLog.log(OpName.CACHE_RECLAIM, "", true, why)
            NpmCacheReclaimReport(
                removedEntries = report.removedEntries,
                removedBytes = report.removedBytes,
                keptEntries = report.keptEntries,
                keptBytes = report.keptBytes,
                keepCount = report.keepCount,
                indexRebuilt = report.indexRebuilt,
            )
        }

    override suspend fun storage(): Map<String, NodeModulesStats> =
        // 落 IO 的理由与 [snapshot] 同族（2026-10-17）：这一趟逐项目算 node_modules 全树尺寸。
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            if (!Files.isDirectory(layout.projectsRoot)) return@withContext emptyMap()
            // 盘上的项目目录名先收成一个 list：`Files.list` 的 `use` 块里**调不了挂起函数**
            // （`Stream.forEach` 收的是 Java 的 `Consumer`，不是内联 lambda），而下面那一趟
            // 尺寸（挂起、自己落 IO）必须按项目逐个走。收名字这一步只是一次目录列举。
            val ids = ArrayList<ProjectId>()
            Files.list(layout.projectsRoot).use { s ->
                s.filter { Files.isDirectory(it) }.forEach { proj ->
                    val pid = ProjectId.createOrNull(proj.fileName.toString())
                    if (pid != null) ids.add(pid)
                }
            }
            val out = LinkedHashMap<String, NodeModulesStats>()
            for (pid in ids) {
                out[pid.value] = NodeModulesStats(
                    projectId = pid.value,
                    pkgCount = LockfileReader.readLocked(layout.lockfile(pid)).size,
                    totalBytes = nodeModulesBytes(pid),
                )
            }
            out
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
                    // 记给去向的 op 段只给 `listOf(cmd.sub)` 而不是完整 argv：系统日志要长期留存，
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
        val root = layout.projectRoot(ProjectId(projectId))
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
        // 取数的两档都落 IO（2026-10-17）：`ls`/`list` 是"读整份 lock 再解析"，`audit` 是
        // "读整份 lock 再查 OSV 库" —— 控制台那一屏同样是主线程在 await 这条链。
        // 两行 consoleLine 留在**外面**发：`SeqRing` 本身任意线程可用（见其 KDoc），
        // 但"取数在 IO、投递回调用线程"让这段的顺序仍然只有一处说了算。
        val text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            when (cmd.sub) {
                "ls", "list" -> {
                    val locked = LockfileReader.readLocked(layout.lockfile(ProjectId(projectId)))
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

    // ══════════ P1 T1 lifecycle 脚本（§18 第 7 项口径：安装时让用户自己选） ══════════

    override suspend fun runScript(projectId: String, name: String, args: List<String>): InstallHandle {
        val root = layout.projectRoot(ProjectId(projectId))   // projectId 合法性先过（防路径逃逸）
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
        return runScriptOps(projectId, OpName.RUN_SCRIPT, name, args, s.pkg, s.versionHash, npmArgs)
    }

    override suspend fun exec(projectId: String, bin: String, args: List<String>): InstallHandle {
        val root = layout.projectRoot(ProjectId(projectId))
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
        return runScriptOps(projectId, OpName.EXEC, bin, args, b.pkg, b.versionHash, npmArgs)
    }

    /**
     * T1 的**执行**（§10.3 T1）。
     *
     * **没有审批**（2026-10-10 裁定，原「人机分离」门禁已删 —— 见
     * `docs/design-decisions.md`）。判据在**解析层**：`NpmScriptResolver` 的纯 JS bin
     * 白名单、路径不许逃出包目录、同名 bin 多包声明即拒 —— 那几条是「这条命令能不能跑」
     * 的真判据，与「谁点的」无关。控制台与依赖面板都是**宿主自己的界面**，敲下这一行的
     * 人就是用户本人；要拦的是**脚本自己发起**的路径，而桥面根本没有 `runScript`/`exec`
     * 这两个方法（见 [NpmBridgeHandler.methods]），脚本到不了这里。
     *
     * [versionHash] 仍然带上，但**不再是放行判据**：它随 [ScriptOp] 走到操作事实，
     * 让「我当时跑的是哪一份脚本」有据可查（脚本改了哈希就变）。
     *
     * 执行体是 [scriptExecutor] 而不是 [executor]：npm CLI 走重操作通道（事务/staging/落位），
     * 而 lifecycle 脚本**不改依赖树**（跑一次 postinstall 只做它该做的事）—— 走事务链
     * 会为一个不改 node_modules 的操作凭空造出 stageDir + commit 记录，journal 里全是
     * 没有产物的假事务。两条通道共用 TTL/取消/事件，差异只在「产物要不要落位」。
     *
     * ⚠ 这一层是**接缝**：[ScriptOpExecutor.Unavailable] 是缺省 → ERR_NOT_IMPLEMENTED。
     */
    private suspend fun runScriptOps(
        projectId: String,
        action: String,
        what: String,
        args: List<String>,
        pkg: String,
        versionHash: String,
        npmArgs: List<String>,
    ): InstallHandle {
        val handle = InstallHandle("inst-${handleSeq.incrementAndGet()}", projectId, now())
        val tracked = TrackedOp(handle, "script-${handle.id}", journaled = false)
        val op = ScriptOp(
            handleId = handle.id,
            projectId = projectId,
            action = action,
            pkg = pkg,
            what = what,
            args = args,
            projectRoot = layout.projectRoot(ProjectId(projectId)),
            npmArgs = npmArgs,
            versionHash = versionHash,
            timeoutMillis = SCRIPT_TIMEOUT_MILLIS,
            // 批 94：执行体经它看到「停止」—— 句柄账在协调器手里，这是唯一的观测缝。
            cancelSignal = tracked,
        )
        handles[handle.id] = tracked
        try {
            withProjectLock(projectId) {
                globalSession.withLock {
                    emit(InstallEvent.Progress(projectId, handle.id, InstallEvent.Phase.QUEUED))
                    try {
                        if (tracked.cancelled) throw AutojsException(ErrorCode.ERR_ENGINE_STOPPED, "脚本执行已取消")
                        // 批 94：脚本会话的输出真流式 —— 执行体边读边报，这里逐行落控制台环
                        // （与 runHeavy 的那条缝同形；失败详情里要不要重复尾部由执行体判，
                        // 见 NpmScriptExecutor.execute 的注释）。
                        val summary = withTimeoutOrNull(op.timeoutMillis) {
                            // 同 runHeavy：经 `emit` 才进环（见那处的注释）。
                            scriptExecutor.execute(
                                op,
                                { ev -> emit(ev) },
                                { text ->
                                    consoleLine(
                                        projectId,
                                        NpmConsoleLine(NpmConsoleLineKind.OUTPUT, text, now()),
                                    )
                                },
                            )
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
                        opLog.log(action, projectId, true, summary)
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
                            opLog.log(action, projectId, false, e.message)
                            emit(InstallEvent.Finished(projectId, handle.id, success = false, detail = e.message))
                        } else {
                            // 终态已发过：只把这次失败记一条（日志里要能看到），不再重复发事件。
                            opLog.log(action, projectId, false, "取消后收尾失败: ${e.message}")
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

    override suspend fun drainEvents(projectId: String, sinceSeq: Long, batch: Int): InstallEventBatch {
        val (first, last, picked) = installEventRing.drain(projectId, sinceSeq, batch)
        return InstallEventBatch(first, last, picked.map { SequencedInstallEvent(it.first, it.second) })
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
        val root = layout.projectRoot(ProjectId(projectId))
        if (!Files.isDirectory(layout.nodeModules(ProjectId(projectId)))) {
            throw AutojsException(
                ErrorCode.ERR_FILE_NOT_FOUND,
                "项目 $projectId 无 node_modules，无可导出的快照（先 install/ci）",
            )
        }
        val tmp = Files.createTempFile(root.parent, "npm-snapshot-", ".zip")
        try {
            val built = snapper.export(projectId, tmp)
            snapper.sync(built, uri)
            opLog.log(OpName.EXPORT, projectId, true, "entries=${built.entries} bytes=${built.contentBytes}")
            return SnapshotRef(
                uri = uri,
                sizeBytes = built.sizeBytes,
                sha256 = built.archiveSha256,
            )
        } catch (e: AutojsException) {
            opLog.log(OpName.EXPORT, projectId, false, e.message)
            throw e
        } catch (e: CancellationException) {
            throw e        // 取消不是"快照导出失败"：不记历史、不折成 ERR_IO
        } catch (e: Exception) {
            opLog.log(OpName.EXPORT, projectId, false, e.message)
            throw AutojsException(ErrorCode.ERR_IO, "快照导出失败：${e.message}", e)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    /**
     * 项目 node_modules 尺寸：缓存优先，过期才真遍历（见 [sizeCache]；只服务配额预检与 [storage]）。
     *
     * **这是挂起函数，且整趟落 IO**（2026-10-17）：缓存命中时它一次 `now()` 就返回，
     * **未命中时它全树遍历** node_modules —— 那是几万个文件的 stat。两个调用方
     * （[storage] 与 [enqueueHeavy] 的配额预检）都在主线程那条链上（进页面 / 点「安装」），
     * 而 60s 的 TTL 意味着每隔一分钟就会在主线程上重演一次。声明在这里而不是两个调用点
     * 各包一层：**做阻塞调用的那一层自己声明它是 IO**（与 [cacheStorage] 同一条）。
     */
    private suspend fun nodeModulesBytes(projectId: ProjectId): Long =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val at = now()
            sizeCache[projectId]?.let {
                if (at - it.atMillis < SIZE_CACHE_TTL_MILLIS) return@withContext it.bytes
            }
            DirSizer.sizeBytes(layout.nodeModules(projectId)).also {
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
        val root = layout.projectRoot(ProjectId(projectId))   // projectId 合法性在此校验（防路径逃逸）
        val free = freeSpaceProbe(root)
        if (free < config.minFreeBytes) {
            val why = "磁盘可用 ${free / 1024 / 1024}MB < 预检下限 ${config.minFreeBytes / 1024 / 1024}MB，拒绝安装"
            // 预检拒绝也记一条（2026-10-09 批 85）：日志要能回答「用户当时看到成功了吗」，
            // 而「被配额/磁盘挡住」正是最该被看见的那类失败 —— 它不留痕的话，用户在系统日志
            // 里看到的是「什么都没发生」，与他屏幕上那句报错对不上。与 `crossCheckRegistry`
            // 的预检拒绝同一条口径（那条一直在记，这两条是漏的）。
            opLog.log(opName(args), projectId, false, why)
            throw AutojsException(ErrorCode.ERR_DISK_FULL, why)
        }
        val used = nodeModulesBytes(ProjectId(projectId))
        if (used >= config.projectQuotaBytes) {
            val why = "项目 node_modules 已达配额 ${config.projectQuotaBytes / 1024 / 1024}MB"
            opLog.log(opName(args), projectId, false, why)
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
                    HeavyOp(nonce, projectId, args, layout.projectRoot(ProjectId(projectId)), stageDir, timeoutMillis),
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
                lockSigner?.sign(projectId, layout.lockfile(ProjectId(projectId)))
            } catch (e: Exception) {
                opLog.log(opName(args), projectId, false, "lock 签名失败: ${e.message}")
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
            opLog.log(opName(args), projectId, true, outcome.summary)
            emit(InstallEvent.Finished(projectId, handle.id, success = true, detail = outcome.summary))
        } catch (e: CancellationException) {
            // 事务与句柄须先收尾，再原样传播调用方取消。
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { cancel(handle) }
            throw e
        } catch (e: Exception) {
            journal.fail(nonce, projectId, stageDir.fileName.toString(), e.message)
            staging.sweep(projectId, setOf(nonce))
            tracked.finish()
            opLog.log(opName(args), projectId, false, e.message)
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
     * 操作名（[OpLogSink.log] 的 op 段）：取 npm CLI 子命令（install/ci/uninstall/prune/dedupe）；tarball/bundle 导入
     * 统一归 [OpName.IMPORT]（args 里的路径是用户数据，不入 op 名——明细走 detail）。
     */
    private fun opName(args: List<String>): String {
        val sub = args.firstOrNull() ?: "unknown"
        return if (sub == "install" && args.any { it == "--from-bundle" || it.endsWith(".tgz") || it.endsWith(".tar.gz") })
            OpName.IMPORT
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
