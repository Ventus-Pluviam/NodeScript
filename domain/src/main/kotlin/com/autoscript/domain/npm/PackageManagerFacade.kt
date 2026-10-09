package com.autoscript.domain.npm

/**
 * npm 依赖管理契约（docs §10.7 PackageManagerFacade）。
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

/**
 * 拉取式事件流的一批（§9.1 a11y `events` 同形：`{first,last,events}` + `seq` 游标）。
 *
 * 为什么脚本侧是**拉**不是推：桥的入站面只有「按 requestId 结算的 ok/err」（§7.5），
 * 宿主没有主动推给脚本的通道（tsf_data 是脚本→宿主方向）。[PackageManagerFacade.progress]
 * 那条 Flow 只服务 :main 侧订阅者；脚本侧的 `onProgress`/`onWarning`/`onFinished`
 * 背后是节流轮询本方法 —— 语义与 a11y 事件流一致，不发明第二套。
 *
 * `first`/`last` = **本批**首/末条的序号；空增量回 `(sinceSeq, sinceSeq)` —— 调用方以
 * 游标为准，不以空数组为终结（事件是开放流）。缓冲**有界**（丢最旧）：`first > sinceSeq+1`
 * 即中间丢过，seq 空洞可见、不静默断流（与 `A11yEventRing` 同纪律）。
 */
data class InstallEventBatch(
    val firstSeq: Long,
    val lastSeq: Long,
    val events: List<SequencedInstallEvent>,
)

/** 带序号的安装事件（`seq` 上桥，脚本拿它当下一次 `sinceSeq`）。 */
data class SequencedInstallEvent(val seq: Long, val event: InstallEvent)

/** 审批入队事件的一批（对偶 [InstallEventBatch]，游标各自独立）。 */
data class ApprovalBatch(
    val firstSeq: Long,
    val lastSeq: Long,
    val requests: List<SequencedApproval>,
)

/** 带序号的审批请求。 */
data class SequencedApproval(val seq: Long, val request: ApprovalRequest)

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
 * 全局镜像源读数（§10.9 第 8 条；管理面板「镜像源管理」）。
 *
 * 三个字段都是**出厂缺省不写死在呈现层**的载体：界面上那句「出厂缺省：官方源」和
 * 「第二意见：npmmirror」都从 [defaultRegistry]/[secondaryRegistry] 读，而不是在
 * Compose 里再抄一遍 URL —— 抄了就会与 [NpmRegistryKeys] 漂。
 *
 * @property configured 用户设过的值（**去首尾空白后原样**）；null = 没设过，实际走 [defaultRegistry]。
 *   注意「没设过」与「读不到」是两句不同的话：后者由调用方抛异常表达。
 *
 *   **写入侧刻意不做规整化**（不去尾斜杠、不丢 query）：少数自建网关的地址带
 *   `?token=…`，规整化会**静默**把凭据削掉 —— 用户看到「保存成功」而此后每次安装都 401，
 *   这是比尾斜杠难看糟糕得多的失败形态。规整化只发生在**读的边界**
 *   （[NpmRegistryKeys.canonicalize]，拼 packument URL 前），那里丢 query 是安全的。
 * @property defaultRegistry 出厂缺省（[NpmRegistryKeys.OFFICIAL]，§18 第 7 项）。
 * @property secondaryRegistry 交叉校验的第二意见（官方 ↔ 镜像互补；与首选**不同运营主体**）。
 */
data class NpmRegistrySnapshot(
    val configured: String?,
    val defaultRegistry: String,
    val secondaryRegistry: String,
) {
    /** 实际生效的那一家（界面显示的「当前生效」就是它）。 */
    val effective: String get() = configured ?: defaultRegistry

    /** 是否被用户改过（界面据此决定「恢复出厂」按钮是否可用）。 */
    val customized: Boolean get() = configured != null
}

/**
 * 控制台一次命令的句柄（§10.9 第 3 条「npm 终端视图」）。
 *
 * 与 [InstallHandle] 分开是刻意的：那个是「一次安装会话」的账，这个是「用户敲了一行」
 * 的账 —— 一行 `npm run build` 走的是 T1 通道（无事务），一行 `npm ls` 甚至不起进程，
 * 把它们塞进同一个句柄类型会让 `cancel()`/journal 的语义含糊。
 *
 * @property line 用户敲的那行**原文**（回显用）。刻意不规整化：回显要与他敲的一致，
 *   否则「我明明写的是 X，屏幕上显示 Y」本身就是一条假账。
 */
data class NpmConsoleHandle(
    val handleId: String,
    val projectId: String,
    val line: String,
    val enqueuedAtMillis: Long,
)

/**
 * 控制台输出读数（seq 游标拉取，与 [InstallEventBatch] 同形；§10.9 第 3 条）。
 *
 * **空洞判据不在这里**：`first > sinceSeq + 1` 里的 `sinceSeq` 是**调用方**发请求时
 * 用的那个值，快照自己不知道。呈现层拿它发请求前的游标判「中间丢过」——
 * 与 `ConsoleSnapshot` 把 `droppedTotal`/`pageFull` 原样带上、由呈现层下结论同一条分工。
 *
 * @property running 该项目此刻有没有在跑的命令。判据在协调器（它持有句柄账），
 *   呈现层**不猜** —— 猜出来的「在跑」会让输入行在命令早就结束时仍然禁用。
 */
data class NpmConsoleSnapshot(
    val firstSeq: Long,
    val lastSeq: Long,
    val lines: List<SequencedConsoleLine>,
    val running: Boolean,
)

/** 带序号的控制台行（`seq` 即下一次拉取的游标，与 [SequencedInstallEvent] 同形）。 */
data class SequencedConsoleLine(val seq: Long, val line: NpmConsoleLine)

/**
 * 控制台一行。
 *
 * 由安装事件（[InstallEvent]）与命令回显投影而来 —— 呈现层只画，不重新判读。
 * 为什么不让呈现层直接消费 [InstallEvent]：控制台还要显示**不是事件**的东西
 * （用户敲的那行、轻操作 `ls`/`audit` 的渲染结果、npm 自己的输出尾部），
 * 而且脚本侧的事件契约（`bridge/js` 的 `onProgress` 等）不能因为宿主多了一个界面
 * 而改形状。投影发生在宿主侧，一处。
 */
data class NpmConsoleLine(
    val kind: NpmConsoleLineKind,
    val text: String,
    val atMillis: Long,
)

/**
 * 控制台一行的种类（呈现层据此着色；**判据在宿主侧**，界面不按文本猜）。
 */
enum class NpmConsoleLineKind {
    /** 用户敲的那行（`$ npm install axios`）。 */
    ECHO,

    /** 阶段进度（queued/resolve/download/reify/post-check/done）。 */
    PHASE,

    /** 命令自己的输出（npm 输出尾部 / `ls` 与 `audit` 的渲染结果）。 */
    OUTPUT,

    /** 警告（`scripts-skipped` / `disk-quota` / `registry-fallback` 等）。 */
    WARNING,

    /** 终态（成功摘要 / 失败原文）。 */
    RESULT,
}

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

    /**
     * 全局镜像源读数（§10.9 第 8 条；管理面板「镜像源管理」的读口）。
     *
     * 缺省实现如实回「没设过 + 出厂缺省」：老替身（测试里那些只关心别的面的假门面）
     * 零改动即可编译，而**不假装**读过盘 —— 这一条与 `HostSummary` 其余读口同纪律。
     */
    suspend fun globalRegistry(): NpmRegistrySnapshot =
        NpmRegistrySnapshot(null, NpmRegistryKeys.OFFICIAL, NpmRegistryKeys.MIRROR)

    /**
     * 设 / 清全局镜像源（§10.9 第 8 条）。
     *
     * `null` 或全空白 = **恢复出厂缺省**（删键，不是写一个空值）—— 与界面「清空输入框
     * 即恢复出厂」同一条语义。校验不过**抛** [IllegalArgumentException]，原文点名
     * （判据的唯一一份在 [NpmRegistryKeys.reject]）。
     *
     * 缺省实现是空操作：未接线的替身不落任何账，也不假装成功（调用方按返回值/异常判定）。
     */
    suspend fun setGlobalRegistry(raw: String?) {}

    /**
     * 在控制台执行一行命令（§10.9 第 3 条）。
     *
     * 解析不过**抛** [IllegalArgumentException]（原文点名，判据的唯一一份在
     * [NpmConsoleKeys.parse]）；项目号不合法同样抛。合法时立刻返回句柄 ——
     * 重操作是**入队即返回**（与 [install] 同语义），输出走 [consoleOutput] 拉。
     *
     * 缺省实现如实抛 [com.autoscript.domain.core.AutojsException] `ERR_NOT_IMPLEMENTED`：
     * 未接线的替身**不假装跑过**，也不返回一个永远没有输出的句柄。
     */
    suspend fun runConsoleCommand(projectId: String, line: String): NpmConsoleHandle =
        throw com.autoscript.domain.core.AutojsException(
            com.autoscript.domain.core.ErrorCode.ERR_NOT_IMPLEMENTED,
            "控制台命令面未接线：本实现没有接上 npm 命令执行入口",
        )

    /**
     * 控制台输出读数（seq 游标拉取，§10.9 第 3 条）。
     *
     * 缺省实现回**空增量 + 没在跑**：老替身（只关心别的面的假门面）零改动即可编译，
     * 且空增量是「没有新行」的诚实表达，不是「读不到」—— 后者由调用方抛异常表达。
     */
    suspend fun consoleOutput(projectId: String, sinceSeq: Long, maxLines: Int = 256): NpmConsoleSnapshot =
        NpmConsoleSnapshot(firstSeq = sinceSeq, lastSeq = sinceSeq, lines = emptyList(), running = false)

    /**
     * 依赖面板读数（§10.9.1）：一次现取**全部项目**的已装清单 + 离线缺口 + 尺寸配额，
     * 外加**全局**待审队列。
     *
     * 与上面几条轻操作的关系是「合成」不是「替代」：它内部就是 `list + offlineGap +
     * storage + ledger`，存在只为让呈现层少一次拼装、少一套失败语义分叉，
     * 也避免"按项目问四次 = 四次 IO + 四份可以互相矛盾的结论"。
     */
    suspend fun snapshot(): NpmPanelSnapshot

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
    /** :main 侧订阅用（Flow，无重放：没在收就错过）。脚本侧走 [drainEvents]。 */
    fun progress(projectId: String): kotlinx.coroutines.flow.Flow<InstallEvent>
    fun approvals(projectId: String): kotlinx.coroutines.flow.Flow<ApprovalRequest>

    /**
     * 拉取式安装事件（脚本侧 `onProgress`/`onWarning`/`onFinished` 的取数口）。
     *
     * `sinceSeq` 从 0 起 = 不漏仍在有界缓冲里的历史（晚订阅不丢 `scripts-skipped`
     * 这类必须被看见的警告）；语义与形状见 [InstallEventBatch]。
     */
    suspend fun drainEvents(projectId: String, sinceSeq: Long, batch: Int = 32): InstallEventBatch

    /** 拉取式审批入队事件（脚本侧 `onApproval` 的取数口；与 [drainEvents] 游标独立）。 */
    suspend fun drainApprovals(projectId: String, sinceSeq: Long, batch: Int = 32): ApprovalBatch

    // —— 快照（高信任通道）——
    suspend fun exportSnapshot(projectId: String, uri: String): SnapshotRef
}

// ══════════════════════════════════════════════════════════════════════════════
// 呈现面只读快照（`:ui` 依赖面板 / 审批卡；2026-10-09 批 81）
//
// 为什么住 `:domain` 而不是让 `:ui` 直接读 facade：`:ui` 只依赖 `:domain`，
// 而 `PackageManagerFacade` 的实现住 `:app-service:npm` —— 呈现层够不到实现类。
// 与 `HostSummary` 的其余读口同一条分工：快照 DTO 住中间层，两侧各只认它。
//
// 为什么这些是**读口**而不是桥面方法：桥面（§12.2 的 npm 命名空间）是**脚本侧**的面，
// 受 §10.5 人机分离约束；IDE 的依赖面板是**宿主自己的界面**，不是脚本。
// 两者共用同一个 `InstallCoordinator`，但入口不同、可达性判据也不同
// （`resolveApproval` 只能从 UI 回调进来，这正是「人机分离」那句话的落点）。
// ══════════════════════════════════════════════════════════════════════════════

/**
 * 依赖面板的**全量**读数（一次现取，不缓存 —— 与 `capabilityCenter()` 同纪律：
 * 缓存 = 撒谎的开始）。
 *
 * 为什么是"全量"而不是"按项目问一次"：依赖面板要能回答"我到底有哪些项目、
 * 各自装了什么"，只列一个项目会让人以为其余项目不存在（与 §9.5 能力中心
 * "列全量能力"同一条理由）。审批队列同理是**全局**的 —— 按项目筛会让用户
 * 漏掉别的项目上等着的那张卡。
 *
 * @property projects 按项目号排序（顺序稳定，界面不用再排）；**空 = 一个项目都没有**
 *   （还没部署过任何项目），与「没读到」是两句不同的话，后者由调用方抛异常表达。
 * @property pendingApprovals 待人工决定的审批票（跨项目；`ApprovalStatus.PENDING`）。
 */
data class NpmPanelSnapshot(
    val projects: List<NpmProjectSnapshot>,
    val pendingApprovals: List<ApprovalRequest>,
)

/**
 * 单个项目的依赖面板读数。
 *
 * @property installed 已装清单（`list()` 直读 lockfile 的权威结果；**空 = 真的没装**）。
 * @property offlineGap 离线闭包缺口（lock 闭包 − 缓存），带尺寸；空 = 离线可重建。
 * @property storage null = 本项目没量到尺寸（不该发生 —— 项目在列表里就说明目录存在；
 *   非 null 但 `totalBytes=0` 才是「量到了、就是 0 字节」）。
 * @property quotaBytes / [quotaWarnRatio] 配额口径（来自 `InstallConfig`，**呈现层不自己写死
 *   512MB** —— 判据只有一处，写第二份就会与真拦人的那份漂移）。
 */
data class NpmProjectSnapshot(
    val projectId: String,
    val installed: List<PkgNode>,
    val offlineGap: List<MissingPkg>,
    val storage: NodeModulesStats?,
    val quotaBytes: Long,
    val quotaWarnRatio: Double,
) {
    /** 已用 ≥ 配额（与 `InstallCoordinator` 那道 100% 拦的判据同源）。 */
    val overQuota: Boolean get() = (storage?.totalBytes ?: 0L) >= quotaBytes

    /** 已用 ≥ 80% 黄线（与发 `DISK_QUOTA` 警告的那道判据同源）。 */
    val quotaWarned: Boolean
        get() = (storage?.totalBytes ?: 0L) >= (quotaBytes * quotaWarnRatio).toLong()

    /** 已用 / 配额，**只在量到尺寸时**有值（没量到画成 0% 就是在说「这个项目不占地方」）。 */
    val quotaFraction: Float?
        get() = storage?.let { (it.totalBytes.toDouble() / quotaBytes.toDouble()).toFloat().coerceIn(0f, 1f) }
}
