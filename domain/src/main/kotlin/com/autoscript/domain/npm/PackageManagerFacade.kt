package com.autoscript.domain.npm

/**
 * npm 依赖管理契约（docs/framework-design.md §10.7 PackageManagerFacade）。
 *
 * 定位：:domain 纯 Kotlin 接口（零 Android/零桥），由 :app-service 安装协调器实现；
 * 轻操作（list/config/storage/offlineGap）实现侧 Kotlin 直读，重操作（install/ci/…）
 * 路由到全局唯一安装会话（§10.6 轻/重拆分 + 全局互斥 + per-project 串行）。
 *
 * 信任分级（§10.5）：
 * - [requestApprove] 是脚本/内部唯一入口——只入队，永不执行；
 * - [resolveApproval] 仅 UI 审批卡回调可携人工决定调用（人机分离）；
 * - [runScript]/[exec]（P1）在执行前由实现侧校验 ledger 中存在 APPROVED 记录，
 *   版本升级（versionHash 变化）必须重新审批。
 */

/** 包规格（install 入参）。 */
data class PackageSpec(
    val name: String,
    val version: String? = null,         // semver range；null = latest
    val saveType: SaveType = SaveType.PRODUCTION,
)

enum class SaveType { PRODUCTION, DEV, OPTIONAL, PEER }

data class InstallFlags(
    val offline: Boolean = false,
    val save: Boolean = true,
    val timeoutMillis: Long = 120_000,   // 会话 TTL（§7.5 异步+TTL 铁律）
)

/** 安装句柄：排队中即可取消（TTL/取消 → quiesce 安装会话，§10.7）。 */
data class InstallHandle(
    val id: String,
    val projectId: String,
    val enqueuedAtMillis: Long,
)

/**
 * 安装**结果**（装了哪些包、什么版本、什么摘要）。
 *
 * ⚠ 今天没有任何实现方产出它：[PackageManagerFacade] 的重操作入口返回的是
 * [InstallHandle]（排队即返回），产物详情要走 progress 流的 `Finished` 事件或
 * `list()` 直读 lockfile。保留这个 DTO 是因为它描述的是「一次安装的结果」这个真实概念，
 * 不是摆设——但**别把它当成 `install()` 的回包形状**：
 * `bridge/js` 曾据此把 `npm.install` 声明成 `Promise<InstallResult>`，而宿主回的是句柄，
 * 于是 `pkg.version` 恒 undefined（§10.8 文档同批改正）。谁先接上产物回传，谁再填它。
 */
data class InstallResult(
    val handleId: String,
    val installed: List<ResolvedPkg>,
    val warnings: List<String> = emptyList(),   // scripts-skipped 等（§10.5-3 禁止静默）
)

/** 单个已解析包（[InstallResult] 的条目；`linkedBins` = 该包声明的 bin 链接名）。 */
data class ResolvedPkg(
    val name: String,
    val version: String,
    val integrity: String? = null,       // lockfile v3 integrity（sha512-…）
    val linkedBins: List<String> = emptyList(),
)

/** 轻操作直读结果：包节点树（depth 截断由实现侧控制）。 */
data class PkgNode(
    val name: String,
    val version: String,
    val sizeBytes: Long = 0,
    val dependencies: List<PkgNode> = emptyList(),
)

data class MissingPkg(
    val name: String,
    val version: String,
    val sizeBytes: Long,
)

data class AuditReport(
    val vulnerabilities: List<Vulnerability>,
    val level: Severity,
    /** true = OSV 离线库结果；false = 在线 audit(+签名)。签名端点不可用绝不静默降级（§10.5-3）。 */
    val offline: Boolean,
) {
    data class Vulnerability(val id: String, val severity: Severity, val pkgName: String)
    enum class Severity { NONE, LOW, MODERATE, HIGH, CRITICAL }
}

/** 审批请求（人机分离 §10.5）：记录绑定 pkg+版本+脚本内容哈希；版本升级必须重新审批。 */
data class ApprovalRequest(
    val id: String,
    val projectId: String,
    val pkg: String,
    val versionHash: String,
    val action: ApprovalAction,
    val requestedAtMillis: Long,
)

enum class ApprovalAction { INSTALL_SCRIPT, RUN_SCRIPT, EXEC }

enum class ApprovalDecision { APPROVE, REJECT }

/** 审批票（查询/审计用）。 */
data class ApprovalTicket(
    val requestId: String,
    val status: ApprovalStatus,
    val decidedAtMillis: Long? = null,
)

enum class ApprovalStatus { PENDING, APPROVED, REJECTED, EXPIRED }

/** 安装进度事件（progress 流）。 */
sealed interface InstallEvent {
    val projectId: String
    val handleId: String

    data class Progress(
        override val projectId: String, override val handleId: String,
        val phase: Phase, val pkg: String? = null, val percent: Float? = null,
    ) : InstallEvent

    data class Warning(
        override val projectId: String, override val handleId: String,
        val kind: Kind, val pkgs: List<String> = emptyList(), val message: String,
    ) : InstallEvent

    data class Finished(
        override val projectId: String, override val handleId: String,
        val success: Boolean, val detail: String? = null,
    ) : InstallEvent

    enum class Phase { QUEUED, RESOLVE, DOWNLOAD, REIFY, POST_CHECK, DONE }
    enum class Kind { SCRIPTS_SKIPPED, TRUST_DOWNGRADED, LOW_MEMORY, REGISTRY_FALLBACK, DISK_QUOTA }
}

/** node_modules 体积统计（storage() 轻操作，Kotlin 目录遍历算尺寸）。 */
data class NodeModulesStats(
    val projectId: String,
    val pkgCount: Int,
    val totalBytes: Long,
    val cacheBytes: Long = 0,
    val lastInstallAtMillis: Long? = null,
)

/**
 * 快照导出引用（§10.9.4 高信任通道：node_modules.zip + manifest 链 + ledger + lock.sig → SAF）。
 *
 * [sha256] 是**归档字节**的摘要，供调用方在落盘/传输后自校验文件没坏；它与
 * `snapshot.sig` 签的内容清单是两回事——导入侧验信用的是签，不用这个。
 */
data class SnapshotRef(
    val uri: String,
    val sizeBytes: Long,
    val sha256: String,
)

/** registry 配置项（config() 经 :main 卡可配列表 + 审计，§10.5-3）。 */
enum class NpmConfigKey { REGISTRY, PROXY, CACHE_RETENTION }

/**
 * npm 包管理门面（§10.7）。实现侧：全局唯一安装调度器 + 每项目互斥锁；
 * 所有重操作可取消（[cancel]），进度经 [progress] 流式回传。
 */
interface PackageManagerFacade {
    // —— 重操作（安装会话，全局互斥 + 排队）——
    suspend fun install(projectId: String, specs: List<PackageSpec>, flags: InstallFlags = InstallFlags()): InstallHandle
    suspend fun ci(projectId: String, offline: Boolean = true): InstallHandle
    suspend fun update(projectId: String, spec: String? = null): InstallHandle
    suspend fun uninstall(projectId: String, name: String): InstallHandle
    suspend fun dedupe(projectId: String): InstallHandle
    suspend fun prune(projectId: String): InstallHandle
    suspend fun audit(projectId: String, offline: Boolean): AuditReport
    suspend fun importOfflineBundle(projectId: String, uri: String): InstallHandle
    suspend fun importTarball(projectId: String, path: String): InstallHandle
    suspend fun cancel(handle: InstallHandle)

    // —— 轻操作（:main Kotlin 直读，零 Node 进程）——
    suspend fun list(projectId: String, depth: Int = 0): List<PkgNode>
    suspend fun offlineGap(projectId: String): List<MissingPkg>
    suspend fun config(projectId: String?, key: NpmConfigKey, value: String?, scope: String? = null)
    suspend fun storage(): Map<String, NodeModulesStats>

    // —— 审批（人机分离 §10.5）——
    /** 脚本/内部唯一入口：只入队，返回票；永不在此执行。 */
    suspend fun requestApprove(projectId: String, pkg: String, versionHash: String, action: ApprovalAction): ApprovalTicket

    /** UI 审批卡回调专用：携人工决定落账（ledger）。 */
    suspend fun resolveApproval(requestId: String, decision: ApprovalDecision): ApprovalTicket

    suspend fun pendingApprovals(projectId: String): List<ApprovalRequest>

    // —— P1：仅人工确认后放行，纯 JS bin 白名单（§10.3 T1）——
    suspend fun runScript(projectId: String, name: String, args: List<String> = emptyList()): InstallHandle
    suspend fun exec(projectId: String, bin: String, args: List<String> = emptyList()): InstallHandle

    // —— 事件流 ——
    fun progress(projectId: String): kotlinx.coroutines.flow.Flow<InstallEvent>
    fun approvals(projectId: String): kotlinx.coroutines.flow.Flow<ApprovalRequest>

    // —— 快照（高信任通道）——
    suspend fun exportSnapshot(projectId: String, uri: String): SnapshotRef
}
