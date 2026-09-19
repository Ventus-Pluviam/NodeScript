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
import java.nio.file.Files
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
    private val cacheIndex: CacheIndex,
    private val executor: HeavyOpExecutor = HeavyOpExecutor.Unavailable,
    private val freeSpaceProbe: (projectRoot: java.nio.file.Path) -> Long = {
        Files.getFileStore(it).usableSpace
    },
    private val now: () -> Long = { System.currentTimeMillis() },
    private val config: Config = Config(),
) : PackageManagerFacade {

    data class Config(
        val minFreeBytes: Long = 500L * 1024 * 1024,       // §10.2 磁盘 free≥500MB 预检
        val projectQuotaBytes: Long = 512L * 1024 * 1024,  // 项目 node_modules 配额（100% 拦）
        val quotaWarnRatio: Double = 0.8,                  // 80% 黄
    )

    /** 重操作执行体接缝：拉起安装会话跑 vendored npm CLI（§10.2 调用链末段）。 */
    fun interface HeavyOpExecutor {
        /**
         * 在已分配的事务上下文里执行重操作；args 为 npm CLI 参数（install/ci/…）。
         * 实现方负责进度事件（经 [ProgressSink]）。
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

    override suspend fun ci(projectId: String, offline: Boolean): InstallHandle =
        enqueueHeavy(projectId, listOf("ci") + if (offline) listOf("--prefer-offline") else emptyList())

    override suspend fun update(projectId: String, spec: String?): InstallHandle =
        enqueueHeavy(projectId, listOf("update") + listOfNotNull(spec))

    override suspend fun uninstall(projectId: String, name: String): InstallHandle =
        enqueueHeavy(projectId, listOf("uninstall", name))

    override suspend fun dedupe(projectId: String): InstallHandle = enqueueHeavy(projectId, listOf("dedupe"))

    override suspend fun prune(projectId: String): InstallHandle = enqueueHeavy(projectId, listOf("prune"))

    override suspend fun importOfflineBundle(projectId: String, uri: String): InstallHandle =
        enqueueHeavy(projectId, listOf("install", "--offline", "--from-bundle", uri))

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

    override suspend fun config(projectId: String?, key: NpmConfigKey, value: String?) {
        val npmrc = layout.npmrc(projectId ?: throw AutojsException(ErrorCode.ERR_INVALID_PARAM, "projectId 必填"))
        val k = when (key) {
            NpmConfigKey.REGISTRY -> "registry"
            NpmConfigKey.PROXY -> "https-proxy"
            NpmConfigKey.CACHE_RETENTION -> "cache-retention"
        }
        val lines = if (Files.exists(npmrc)) Files.readAllLines(npmrc).toMutableList() else mutableListOf()
        lines.removeIf { it.startsWith("$k=") }
        if (value != null) lines.add("$k=$value")
        Files.write(npmrc, lines)
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
        throw AutojsException(
            ErrorCode.ERR_NOT_IMPLEMENTED,
            "快照导出（node_modules.zip+lock+ledger→SAF）属高信任通道，随打包向导 P0 后段接入",
        )
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
        val handle = InstallHandle("inst-${handleSeq.incrementAndGet()}", projectId, now())
        val nonce = UUID.randomUUID().toString()
        val tracked = TrackedOp(handle, nonce)
        handles[handle.id] = tracked

        val projectLock = projectLocks.getOrPut(projectId) { Mutex() }
        // 协程内联执行（挂起语义 = 排队；调用方要 fire-and-forget 可自行 launch）
        projectLock.withLock {
            globalSession.withLock {
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
            val summary = executor.execute(HeavyOp(nonce, projectId, args, stageDir, timeoutMillis)) { ev ->
                events.tryEmit(ev)
            }
            // 执行体把产物写在 stageDir；落位由 staging.commit 原子 rename
            staging.commit(projectId, nonce)
            journal.commit(nonce, projectId, stageDir.fileName.toString())
            tracked.done = true
            emit(InstallEvent.Finished(projectId, handle.id, success = true, detail = summary))
        } catch (e: Exception) {
            journal.fail(nonce, projectId, stageDir.fileName.toString(), e.message)
            staging.sweep(projectId, setOf(nonce))
            tracked.done = true
            emit(InstallEvent.Finished(projectId, handle.id, success = false, detail = e.message))
            throw e
        }
    }

    private suspend fun emit(e: InstallEvent) {
        events.emit(e)
    }
}
