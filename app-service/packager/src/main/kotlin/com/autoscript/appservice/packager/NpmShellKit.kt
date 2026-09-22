package com.autoscript.appservice.packager

import com.autoscript.appservice.packager.npm.ApprovalLedger
import com.autoscript.appservice.packager.npm.CacacheIndex
import com.autoscript.appservice.packager.npm.InstallCoordinator
import com.autoscript.appservice.packager.npm.InstallHistory
import com.autoscript.appservice.packager.npm.InstallJournal
import com.autoscript.appservice.packager.npm.InstallStaging
import com.autoscript.appservice.packager.npm.LockSigner
import com.autoscript.appservice.packager.npm.NpmBridgeHandler
import com.autoscript.appservice.packager.npm.NpmOfflineBundleImporter
import com.autoscript.appservice.packager.npm.NpmProjectLayout
import com.autoscript.appservice.packager.npm.NpmRegistryVerifier
import com.autoscript.appservice.packager.npm.NpmServices
import com.autoscript.appservice.packager.npm.NpmSnapshot
import com.autoscript.domain.bridge.NamespaceHandler
import com.autoscript.domain.scripts.ScriptPaths
import java.nio.file.Files
import java.nio.file.Path

/**
 * npm 生产装配（docs/framework-design.md §10.2 存储布局 + §12.2 接线现状）。
 *
 * 把散在各处的目录约定收到一处（调用方只给 `filesDir`/`cacheDir`，不再逐个拼路径），
 * 产出直接喂 `AppShell.assemble(npmHandler = …)` 的挂载缝。纯 JVM、无 Android，
 * archUnit 允许（本包只见 `:domain` + 自家 `npm` 子包）。
 *
 * 目录映射（§10 存储布局）：
 * - `filesDir/scripts` → [NpmProjectLayout.projectsRoot]（项目根）；
 * - `filesDir/.autojs` → journal（`install.journal`）/ history（`install-history.jsonl`）/
 *   lock 签名（`lock.sig`）/ 快照账本来源；
 * - `cacheDir/npm-cache` → [CacacheIndex]（cacache `content-v2` 真查；被系统清掉如实报缺口）。
 *
 * 可空即未接线（与 [NpmServices] 同一诚实口径）：
 * - [executor] 缺省 [InstallCoordinator.HeavyOpExecutor.Unavailable] —— 编排照走，
 *   重操作如实 `ERR_NOT_IMPLEMENTED`（真引擎/真 npm CLI 到了再换）；
 * - [lockKey] 缺省 null → 不带 lockSigner：ci 不验签直接走（不假装验过）；
 * - [registryVerifier] 缺省接真 [NpmRegistryVerifier]（纯 JVM + Http 源；只在 install 被调时
 *   发请求，装配本身零网络）。测试要静默跳过校验时显式传 null。
 */
object NpmShellKit {

    fun assembleHandler(
        filesDir: Path,
        cacheDir: Path,
        executor: InstallCoordinator.HeavyOpExecutor = InstallCoordinator.HeavyOpExecutor.Unavailable,
        registryVerifier: NpmRegistryVerifier? = NpmRegistryVerifier(),
        lockKey: LockSigner.KeyProvider? = null,
        snapshots: Boolean = true,
    ): NamespaceHandler {
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
            ledger = ApprovalLedger(),
            history = InstallHistory(autojsDir),
            lockSigner = lockKey?.let { LockSigner(autojsDir, it) },
            snapshots = if (snapshots && lockKey != null) NpmSnapshot(layout, autojsDir, lockKey) else null,
            cacheIndex = CacacheIndex(cacheDir.resolve("npm-cache")),
            bundleImporter = NpmOfflineBundleImporter,
            registryVerifier = registryVerifier,
        )
        // 磁盘探针：项目目录在 install 前本来就不存在（stat 会炸），退到 filesDir ——
        // 同分区同答案（§10.2 磁盘预检问的是分区余量，不是"项目目录在不在"）。
        val probe: (Path) -> Long = { p ->
            try {
                java.nio.file.Files.getFileStore(p).usableSpace
            } catch (e: java.io.IOException) {
                java.nio.file.Files.getFileStore(filesDir).usableSpace
            }
        }
        return NpmBridgeHandler(
            InstallCoordinator(services = services, executor = executor, freeSpaceProbe = probe),
        ).mount()
    }
}
