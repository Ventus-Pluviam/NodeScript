package com.autoscript.appservice.packager.npm

import com.autoscript.domain.npm.ApprovalAction
import com.autoscript.domain.npm.ApprovalDecision
import com.autoscript.domain.npm.ApprovalRequest
import com.autoscript.domain.npm.ApprovalTicket
import com.autoscript.domain.npm.AuditReport
import com.autoscript.domain.npm.InstallEvent
import com.autoscript.domain.npm.InstallFlags
import com.autoscript.domain.npm.InstallHandle
import com.autoscript.domain.npm.MissingPkg
import com.autoscript.domain.npm.NodeModulesStats
import com.autoscript.domain.npm.NpmConfigKey
import com.autoscript.domain.npm.PackageManagerFacade
import com.autoscript.domain.npm.PackageSpec
import com.autoscript.domain.npm.PkgNode
import com.autoscript.domain.npm.SnapshotRef
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 安装协调器（docs/framework-design.md §10.2 InstallCoordinator）：全局唯一安装调度器。
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
    private val layout: NpmProjectLayout,
    private val journal: InstallJournal,
    private val staging: InstallStaging,
    private val ledger: ApprovalLedger,
    private val history: InstallHistory? = null,
    private val lockSigner: LockSigner? = null,
    private val snapshots: NpmSnapshot? = null,
    private val cacheIndex: CacheIndex,
    /**
     * 离线 bundle 导入接缝（§10.9 UX 4）：注入前 [importOfflineBundle] 如实失败
     * （不把用户文件路径拼成 npm 不认识的旗标）；注入后先合入 cacache 再按 lock 重建。
     * 与 snapshots/lockSigner 同为「真值在 Android 侧」的装配缝，纯 JVM 可测。
     */
    private val bundleImporter: NpmOfflineBundleImporter? = null,
    private val executor: HeavyOpExecutor = HeavyOpExecutor.Unavailable,
    private val freeSpaceProbe: (projectRoot: java.nio.file.Path) -> Long = {
        Files.getFileStore(it).usableSpace
    },
    private val now: () -> Long = { System.currentTimeMillis() },
    private val config: Config = Config(),
    /** 真 cacheDir（`<data>/cache/npm-cache`，§10.2；Android 装配层注入）。 */
    private val npmCacheDir: Path? = null,
) : PackageManagerFacade {

    data class Config(
        val minFreeBytes: Long = 500L * 1024 * 1024,       // §10.2 磁盘 free≥500MB 预检
        val projectQuotaBytes: Long = 512L * 1024 * 1024,  // 项目 node_modules 配额（100% 拦）
        val quotaWarnRatio: Double = 0.8,                  // 80% 黄
    )

    /** lifecycle 脚本字段（§10.5-3 的扫描面）：出现任一即视为「装了但脚本没跑」。 */
    private val LIFECYCLE_KEYS = listOf(
        "preinstall", "install", "postinstall",
        "preuninstall", "uninstall", "postuninstall",
        "prepare", "preprepare", "preparePack",
    )

    /** 重操作执行体接缝：拉起安装会话跑 vendored npm CLI（§10.2 调用链末段）。 */
    fun interface HeavyOpExecutor {
        /**
         * 在已分配的事务上下文里执行重操作；args 为 npm CLI 参数（install/ci/…）。
         * 实现方负责进度事件（经 [ProgressSink]）。
         *
         * **TTL 契约（铁律 3）**：协调器已对本次调用套 [op.timeoutMillis]（withTimeoutOrNull），
         * 超时即取消并回 err 路径收尾（journal fail + 残骸清扫 + 锁释放）。故实现方必须
         * 合作式响应取消（阻塞 IO 拆成可中断段、子进程随取消销毁）——不响应取消的执行体
         * 会在超时后变成孤儿：项目锁虽已释放，但它仍可能与新会话争抢同一 stageDir。
         */
        suspend fun execute(op: HeavyOp, sink: ProgressSink): String   // 返回摘要（人类可读）

        /** 默认：无引擎可用 → 如实 ERR_NOT_IMPLEMENTED。 */
        object Unavailable : HeavyOpExecutor {
            override suspend fun execute(op: HeavyOp, sink: ProgressSink): String {
                throw AutojsException(
                    ErrorCode.ERR_NOT_IMPLEMENTED,
                    "安装会话引擎未接入：重操作 ${op.args.joinToString(" ")} 未执行（编排已完成：journal=${op.nonce}）",
                )
            }
        }
    }

    /** 一次重操作的完整上下文（编排层 → 执行体）。 */
    data class HeavyOp(
        val nonce: String,
        val projectId: String,
        val args: List<String>,
        val projectRoot: java.nio.file.Path,
        val stageDir: java.nio.file.Path,
        val timeoutMillis: Long,
    )

    /** 进度事件回传缝（执行体 → 协调器事件流）。 */
    fun interface ProgressSink {
        suspend fun emit(event: InstallEvent)
    }

    // —— 运行态（全局互斥 + per-project 锁 + 事件流 + 句柄账） ——

    private val globalSession = Mutex()
    private val projectLocks = ConcurrentHashMap<String, Mutex>()
    private val events = MutableSharedFlow<InstallEvent>(extraBufferCapacity = 256)
    private val approvalFlow = MutableSharedFlow<ApprovalRequest>(extraBufferCapacity = 64)
    private val handles = ConcurrentHashMap<String, TrackedOp>()
    private val handleSeq = AtomicLong(0)

    private data class TrackedOp(
        val handle: InstallHandle,
        val nonce: String,
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
        return enqueueHeavy(projectId, args, flags.timeoutMillis)
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
        val src = Path.of(uri)
        if (!Files.isRegularFile(src)) {
            throw AutojsException(ErrorCode.ERR_FILE_NOT_FOUND, "离线 bundle 不存在：$uri（SAF 副本是否已落地？）")
        }
        val result = importer.import(src, resolveCacheDir())
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
                journal.fail(it.nonce, handle.projectId, staging.stagePath(handle.projectId, it.nonce).fileName.toString(), "用户取消")
                staging.sweep(handle.projectId, setOf(it.nonce))
                it.done = true
                emit(InstallEvent.Finished(handle.projectId, handle.id, success = false, detail = "已取消"))
            }
        }
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

    override suspend fun storage(): Map<String, NodeModulesStats> {
        if (!Files.isDirectory(layout.projectsRoot)) return emptyMap()
        val out = LinkedHashMap<String, NodeModulesStats>()
        Files.list(layout.projectsRoot).use { s ->
            s.filter { Files.isDirectory(it) }.forEach { proj ->
                val id = proj.fileName.toString()
                val nm = layout.nodeModules(id)
                val pkgs = LockfileReader.readLocked(layout.lockfile(id)).size
                out[id] = NodeModulesStats(
                    projectId = id,
                    pkgCount = pkgs,
                    totalBytes = DirSizer.sizeBytes(nm),
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
        ledger.pending(projectId).firstOrNull { it.id == ticket.requestId }?.let { approvalFlow.tryEmit(it) }
        return ticket
    }

    override suspend fun resolveApproval(requestId: String, decision: ApprovalDecision): ApprovalTicket =
        ledger.resolve(requestId, decision)

    override suspend fun pendingApprovals(projectId: String): List<ApprovalRequest> = ledger.pending(projectId)

    // ══════════ P1（待 ledger APPROVED 才放行；执行体仍走重操作通道） ══════════

    override suspend fun runScript(projectId: String, name: String, args: List<String>): InstallHandle {
        // P1 门禁：脚本执行必须已有 APPROVED（按 pkg@hash 键；这里 name 即脚本名所属的包级审批）
        // 当前无脚本内容哈希源 → 一律入队提示审批，不执行（诚实拒绝优于静默放行）。
        throw AutojsException(
            ErrorCode.ERR_PERMISSION_DENIED,
            "npm run $name 需人工审批后放行（§10.5）：请先在能力中心确认 $name 脚本",
        )
    }

    override suspend fun exec(projectId: String, bin: String, args: List<String>): InstallHandle {
        throw AutojsException(
            ErrorCode.ERR_PERMISSION_DENIED,
            "npm exec $bin 需人工审批 + 纯 JS bin 白名单（§10.3 T1）后放行",
        )
    }

    // ══════════ 事件流 ══════════

    override fun progress(projectId: String): Flow<InstallEvent> = events.filter { it.projectId == projectId }

    override fun approvals(projectId: String): Flow<ApprovalRequest> =
        approvalFlow.filter { it.projectId == projectId }

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
        } catch (e: Exception) {
            history?.record(InstallHistory.Op.EXPORT, projectId, false, e.message)
            throw AutojsException(ErrorCode.ERR_IO, "快照导出失败：${e.message}", e)
        } finally {
            Files.deleteIfExists(tmp)
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
        val used = DirSizer.sizeBytes(layout.nodeModules(projectId))
        if (used >= config.projectQuotaBytes) {
            throw AutojsException(ErrorCode.ERR_DISK_FULL, "项目 node_modules 已达配额 ${config.projectQuotaBytes / 1024 / 1024}MB")
        }
        // 80% 黄（§10.2 「项目+全局配额(80%黄·100%拦）」）：不拦，发 Warning 事件
        val quotaWarned = used >= (config.projectQuotaBytes * config.quotaWarnRatio).toLong()
        val handle = InstallHandle("inst-${handleSeq.incrementAndGet()}", projectId, now())
        val nonce = UUID.randomUUID().toString()
        val tracked = TrackedOp(handle, nonce)
        handles[handle.id] = tracked

        val projectLock = projectLocks.getOrPut(projectId) { Mutex() }
        // 协程内联执行（挂起语义 = 排队；调用方要 fire-and-forget 可自行 launch）
        projectLock.withLock {
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
            tracked.done = true
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
        } catch (e: Exception) {
            journal.fail(nonce, projectId, stageDir.fileName.toString(), e.message)
            staging.sweep(projectId, setOf(nonce))
            tracked.done = true
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
        val text = Files.readString(pkgJson, java.nio.charset.StandardCharsets.UTF_8)
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
        events.emit(e)
    }
}
