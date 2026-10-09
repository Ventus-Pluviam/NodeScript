package com.autoscript.appservice.npm

import com.autoscript.appservice.npm.ApprovalLedger
import com.autoscript.appservice.npm.CacacheIndex
import com.autoscript.appservice.npm.InstallCoordinator
import com.autoscript.appservice.npm.InstallHistory
import com.autoscript.appservice.npm.InstallJournal
import com.autoscript.appservice.npm.InstallStaging
import com.autoscript.appservice.npm.LockSigner
import com.autoscript.appservice.npm.NpmBridgeHandler
import com.autoscript.appservice.npm.NpmOfflineBundleImporter
import com.autoscript.appservice.npm.NpmProjectLayout
import com.autoscript.appservice.npm.NpmRegistryVerifier
import com.autoscript.appservice.npm.NpmServices
import com.autoscript.appservice.npm.NpmSnapshot
import com.autoscript.domain.bridge.NamespaceHandler
import com.autoscript.domain.scripts.ScriptPaths
import java.nio.file.Files
import java.nio.file.Path

/**
 * npm 生产装配（docs §10.2 存储布局 + §12.2 接线现状）。
 *
 * 把散在各处的目录约定收到一处（调用方只给 `filesDir`/`cacheDir`，不再逐个拼路径），
 * 产出直接喂 `AppShell.assemble(npmHandler = …)` 的挂载缝；返回具体类型（而非
 * [NamespaceHandler]）是为了让装配层还能拿到 [NpmBridgeHandler.facade] —— 呈现面的
 * 依赖面板/审批卡读口走它，不经桥（§10.9.1）。纯 JVM、无 Android，
 * archUnit 允许（本包只见 `:domain` + 自家 `npm` 子包）。
 *
 * 目录映射（§10 存储布局）：
 * - `filesDir/scripts` → [NpmProjectLayout.projectsRoot]（项目根）；
 * - `filesDir/.autojs` → journal（`install.journal`）/ history（`install-history.jsonl`）/
 *   lock 签名（`lock.sig`）/ 快照账本来源；
 * - `cacheDir/npm-cache` → [CacacheIndex]（cacache `content-v2` 真查；被系统清掉如实报缺口）。
 *
 * 可空即未接线（与 [NpmServices] 同一诚实口径）：
 * - [executor] 缺省 [HeavyOpExecutor.Unavailable] —— 编排照走，
 *   重操作如实 `ERR_NOT_IMPLEMENTED`（真引擎/真 npm CLI 到了再换）；
 * - [scriptExecutor] 缺省 [ScriptOpExecutor.Unavailable] —— T1 lifecycle
 *   门禁照走（解析/哈希/审批自请入队都在协调器内，纯 Kotlin 不依赖执行体），但**已获批也跑不起来**
 *   如实 `ERR_NOT_IMPLEMENTED`（spawn 桥本体未接，§10.3 T1 下半段）；
 * - [approvalStore] 缺省 = 落盘账本（`filesDir/.autojs/approve-ledger.jsonl`，§10.2 存储布局）；
 *   传 null = 不落盘（只给"重启即蒸发"的用例用，生产不许走这条 —— 审批是信任决策，
 *   重启蒸发等于让用户重批，而且 requestId 会从 `apr-1` 重来、与历史票碰撞）；
 * - [lockKey] 缺省 null → 不带 lockSigner：ci 不验签直接走（不假装验过）；
 * - [registryVerifier] 缺省接真 [NpmRegistryVerifier]（纯 JVM + Http 源；只在 install 被调时
 *   发请求，装配本身零网络）。测试要静默跳过校验时显式传 null。
 *
 * 全局镜像源**不经参数**：它恒为 `files/.npmrc`（[NpmGlobalConfig]），与传给 npm 的
 * `--userconfig` 同一路径。做成可注入只会制造「协调器读 A、npm 读 B」这种分家
 * —— 而本批修的正是这类分家。
 */
object NpmShellKit {

    fun assembleHandler(
        filesDir: Path,
        cacheDir: Path,
        executor: HeavyOpExecutor = HeavyOpExecutor.Unavailable,
        /** T1 lifecycle 执行体（spawn 桥接上后注入；缺省即"门禁过但跑不起来"）。 */
        scriptExecutor: ScriptOpExecutor = ScriptOpExecutor.Unavailable,
        registryVerifier: NpmRegistryVerifier? = NpmRegistryVerifier(),
        /**
         * 审批账本持久化（§10.2 `files/.autojs/approve-ledger.jsonl`）。缺省即落盘 ——
         * 生产路径**不该**传 null：审批是信任决策，重启蒸发 = 用户重批 + requestId
         * 从 `apr-1` 重来与历史票碰撞（[ApprovalLedger] 的 seq 由 store 的 lastSeq 起算）。
         */
        approvalStore: ApprovalStore? = FileApprovalStore(filesDir.resolve(".autojs")),
        lockKey: LockSigner.KeyProvider? = null,
        snapshots: Boolean = true,
        freeSpaceProbe: (Path) -> Long = defaultFreeSpaceProbe(filesDir),
    ): NpmBridgeHandler {
        // 项目根来自契约层（§9.6 单一事实来源）：与 script-repo/调度恢复/装配层同一个函数，
        // 拼错目录名不再可能（曾经这里与 AppShellKit 各写一份字面量）。
        val layout = NpmProjectLayout(ScriptPaths.projectsRoot(filesDir))
        val autojsDir = filesDir.resolve(".autojs")
        // 装配即建目录（生产 filesDir 本来就要落盘；探针/部署读不存在的路径只会炸，
        // 建空目录不伪造任何"已安装"事实 —— 项目内容仍以 lockfile/node_modules 为准）。
        Files.createDirectories(layout.projectsRoot)
        Files.createDirectories(autojsDir)
        val services = NpmServices(
            layout = layout,
            journal = InstallJournal(autojsDir),
            staging = InstallStaging(layout),
            // 审批账本落盘（§10.2）：不落盘时 seq 从 0 起，重启后新票会与旧票同 id。
            ledger = ApprovalLedger(approvalStore),
            history = InstallHistory(autojsDir),
            lockSigner = lockKey?.let { LockSigner(autojsDir, it) },
            snapshots = if (snapshots && lockKey != null) NpmSnapshot(layout, autojsDir, lockKey) else null,
            cacheIndex = CacacheIndex(NpmCacheSeedDeployer.cacheRoot(cacheDir)),
            bundleImporter = NpmOfflineBundleImporter,
            registryVerifier = registryVerifier,
        )
        return NpmBridgeHandler(
            InstallCoordinator(
                services = services,
                executor = executor,
                scriptExecutor = scriptExecutor,
                // 全局镜像源（§10.2 userconfig 层，2026-10-09 批 83）：与喂给 npm 的
                // `--userconfig` 是**同一个文件** —— 解析链读到的与 npm 读到的必须是同一份，
                // 否则「界面显示生效了」与「npm 真去哪家」又会分家。
                globalConfig = NpmGlobalConfig(filesDir),
                // 离线 bundle 导入的落点此前是第三个目录（协调器的 projectsRoot 同级兜底）：
                // 导入说「合入缓存了」、`ci --offline` 却查 `cacheDir/npm-cache` —— 两处都
                // 不报错，只是不命中。这里把同一份判据喂进去（2026-10-09 批 86）。
                npmCacheDir = NpmCacheSeedDeployer.cacheRoot(cacheDir),
                freeSpaceProbe = freeSpaceProbe,
            ),
        )
    }

    /** 缺省磁盘探针：项目目录在 install 前本来就不存在（stat 会炸），退到 filesDir ——
     * 同分区同答案（§10.2 磁盘预检问的是分区余量，不是"项目目录在不在"）。可注入覆盖：
     * 单测 @TempDir 落在 tmpfs 上（CI/沙箱 /tmp 常被占满），真探会让"重操作诚实
     * ERR_NOT_IMPLEMENTED"的用例先撞 ERR_DISK_FULL —— 探针是环境敏感缝，测试固定值。 */
    private fun defaultFreeSpaceProbe(filesDir: Path): (Path) -> Long = { p ->
        try {
            java.nio.file.Files.getFileStore(p).usableSpace
        } catch (e: java.io.IOException) {
            java.nio.file.Files.getFileStore(filesDir).usableSpace
        }
    }
}
