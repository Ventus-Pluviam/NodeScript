package com.autoscript.appservice.npm

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
import com.autoscript.domain.npm.MissingPkg
import com.autoscript.domain.npm.NodeModulesStats
import com.autoscript.domain.npm.NpmConfigKey
import com.autoscript.domain.npm.NpmPanelSnapshot
import com.autoscript.domain.npm.NpmProjectSnapshot
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
     * 首选注册表解析（缺省读项目 `.npmrc`）。null ≠ 「永远不知道」：
     * 与 [NpmServices.registryOf] 同一约定——未传时用协调器自己的 npmrc 读取，
     * 测试显式关校验/换源时才注入。
     */
    registryOf: ((String) -> String?)? = null,
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
    private val registryOf: (String) -> String? = registryOf ?: ::readRegistryFromNpmrc

    /** 项目 `.npmrc` 的 registry=（§10.2 默认首选；缺文件/缺键/空值 → null，不猜镜像）。 */
    private fun readRegistryFromNpmrc(projectId: String): String? {
        val rc = layout.npmrc(projectId)   // projectId 合法性由 NpmProjectLayout 把着
        if (!Files.isRegularFile(rc)) return null
        return Files.readAllLines(rc).asReversed()
            .firstOrNull { it.startsWith("registry=") }
            ?.substringAfter("registry=")
            ?.trim()?.takeIf { it.isNotEmpty() }
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
        // 入口即拒：git: 依赖（§10.3 明确不可行）
        specs.firstOrNull { (it.version ?: "").startsWith("git") || it.name.startsWith("git:") }
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
        val lines = if (Files.exists(npmrc)) Files.readAllLines(npmrc).toMutableList() else mutableListOf()
        lines.removeIf { it.startsWith("$keyName=") }
        if (value != null) lines.add("$keyName=$value")
        Files.createDirectories(npmrc.parent)
        Files.write(npmrc, lines)
        history?.record(InstallHistory.Op.REGISTRY, projectId, true, "$keyName=$value")
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
        val projects = stats.keys.sorted().map { id ->
            NpmProjectSnapshot(
                projectId = id,
                installed = list(id, depth = 0),
                offlineGap = offlineGap(id),
                storage = stats[id],
                quotaBytes = config.projectQuotaBytes,
                quotaWarnRatio = config.quotaWarnRatio,
            )
        }
        return NpmPanelSnapshot(projects = projects, pendingApprovals = pending)
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
            "node_modules 里没有声明 bin「$bin」的包（项目 $projectId）",
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
                    " 未获人工批准（§10.5）：请求已入队，请到能力中心的审批卡确认后重试",
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
                            scriptExecutor.execute(op) { ev -> events.tryEmit(ev) }
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
            throw AutojsException(
                ErrorCode.ERR_DISK_FULL,
                "磁盘可用 ${free / 1024 / 1024}MB < 预检下限 ${config.minFreeBytes / 1024 / 1024}MB，拒绝安装",
            )
        }
        val used = nodeModulesBytes(projectId)
        if (used >= config.projectQuotaBytes) {
            throw AutojsException(ErrorCode.ERR_DISK_FULL, "项目 node_modules 已达配额 ${config.projectQuotaBytes / 1024 / 1024}MB")
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
            val summary = withTimeoutOrNull(timeoutMillis) {
                executor.execute(
                    HeavyOp(nonce, projectId, args, layout.projectRoot(projectId), stageDir, timeoutMillis),
                ) { ev -> events.tryEmit(ev) }
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
            history?.record(opName(args), projectId, true, summary)
            emit(InstallEvent.Finished(projectId, handle.id, success = true, detail = summary))
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
     * 之间差一层 `_cacache`。生产形态应是 `<App 数据根>/cache/npm-cache`（与 filesDir 平级，
     * §10.2「系统可自动清，损失可接受」）；但协调器只被喂了 projectsRoot，**没有 cacheDir
     * 这个真值**——反推 `<projectsRoot>/../../cache/npm-cache` 在 `files/scripts` 布局下才对，
     * 一旦 projectsRoot 不在这棵树下（测试/非常规布局）就静默指错地方。
     *
     * 处置：承认这是装配缺口而不是猜。缺省用 projectsRoot 同级的 `.npm-cache`（可预测、
     * 测试可断言），并保留 [npmCacheDir] 注入点供 Android 装配层传入真值。
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
        events.emit(e)
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
         * node_modules 尺寸缓存 TTL（见 [sizeCache]）。
         *
         * 60s 的取舍：短到「刚装完立刻再装」也顶多多走一两趟遍历、长到能吃掉
         * 连续安装/轮询查询里的绝大多数遍历。它同时是「外部（脚本自跑 npm）改动
         * 最多被看不见多久」的上界。
         */
        const val SIZE_CACHE_TTL_MILLIS = 60_000L
    }
}
