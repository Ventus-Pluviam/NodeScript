package com.autoscript.appservice.scheduler.recovery

import com.autoscript.domain.scripts.ScriptPaths
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * 装配期的脚本补部署（docs/framework-design.md §9.6：`files/scripts/<projectId>/` 标准化）。
 *
 * **它补的是哪条缝**：调度侧的持久化只覆盖「任务排期」与「未完成意向」两个寄存器，
 * 都不含**脚本内容**。而 `filesDir` 在真机上会被清（用户"清除数据"、系统回收空间、
 * 预装包升级替换），排期与意向却可能存活在别处（如系统侧闹钟）。缺了这一步的形态是：
 * 排期还在、意图还能投、每次执行都以「脚本文件不存在」告终 —— 而任务中心看到的只是
 * 一个又一个 `CRASHED`，**没有任何一处说得出"是因为文件没了"**。
 *
 * **它不做什么**（三条诚实边界，都写在这里而不是留给现场猜）：
 * 1. **不覆盖已有文件**。本类不是部署器（覆盖是 `:app-service:script-repo` 的
 *    `AtomicDeployer` + `InstallCoordinator` 的职责，它们有 sha256/journal/审批链路）。
 *    这里的补法只有一条判据 —— 「目标不存在就写」。因此**已存在的文件内容本类不做校验**：
 *    用户手改过的脚本、旧版本残留，一律原样留着（宁可跑旧版，也不静默覆盖用户的东西）。
 * 2. **没有清单就是没得补**。`sources` 是"projectId → (项目内相对路径 → 字节)"，
 *    而框架**没有内置脚本模板**（assets 里没有 `scripts/<id>/` 这类东西）。
 *    调用方给空映射 = 本次没补任何东西；[deployed] 会如实是 0，**不粉饰成"已恢复"**。
 *    `script-repo` 的 `AndroidAssetsSource` 是 `sources` 的生产提供方之一（首批内置脚本），
 *    但补部署的判据与它无关 —— 谁来喂、喂多少，本类都只做同一件事。
 * 3. **失败不投毒**。与 `ProjectDeployer` 的两阶段提交相反（那里一组文件是**一个版本**，
 *    半组就位 = 跨版本混合态，必须整体回滚），补部署的每个文件彼此独立：一个文件写失败
 *    **不影响其他文件**，已补的照常算数。`failures` 如实列出失败项（路径 + 原因），
 *    装配层据此呈现"有脚本没补上"，绝不把失败吞成"一切正常"。
 *
 * 写入纪律：**临时文件 + 同目录 rename**（与 `AtomicDeployer.finalize` 同一手法）——
 * 直接写目标路径在写入中途崩溃会留下半截脚本，而半截脚本会被引擎读成一个语法错误，
 * 把"这次没补上"变成"脚本坏了"，是更难查的形态。失败时清理临时文件，不留垃圾。
 *
 * 线程与时机：**只在装配期调用一次**（`AppShellKit.assemble`），不与运行期的写入并发；
 * 因此本类无锁 —— 加了锁只会掩盖"调用点用错了时机"这件事。
 */
// public（非 internal）：装配层 `:app` 的 `AppShellKit.assemble` 是唯一的生产调用方。
// Kotlin internal 只在模块内可见 —— 若标 internal，装配层就调不动这条缝，只能另起重复实现。
class ScriptDeployRecovery(
    private val filesDir: Path,
    private val sources: Map<String, Map<String, ByteArray>>,
) {

    /** 一个补上的文件。 */
    data class Deployed(val projectId: String, val relPath: String, val target: Path)

    /** 一个没补上的文件（[reason] 是原因原文，不折叠成布尔）。 */
    data class Failure(val projectId: String, val relPath: String, val reason: String)

    /** 补部署结果（空 map + 空列表 = 没有来源可补，不是"恢复成功"）。 */
    data class Report(
        val deployed: List<Deployed> = emptyList(),
        val failures: List<Failure> = emptyList(),
    ) {
        /** 是否真的补了至少一个文件（装配层日志据此说人话）。 */
        val changed: Boolean get() = deployed.isNotEmpty()
    }

    /**
     * 执行补部署。按 projectId / relPath 的**字典序**遍历 ——
     * 顺序只影响日志可读性，不影响结果（每个文件彼此独立）；但稳定顺序让失败清单
     * 在两次装配之间可比对（同一批失败不会因 map 迭代序不同而看起来"变了"）。
     */
    fun run(): Report {
        val deployed = mutableListOf<Deployed>()
        val failures = mutableListOf<Failure>()
        for (projectId in sources.keys.sorted()) {
            val files = sources.getValue(projectId)
            for (relPath in files.keys.sorted()) {
                val bytes = files.getValue(relPath)
                val target = targetOf(projectId, relPath)
                if (target == null) {
                    // 非法相对路径：如实记一笔，跳过（不抛 —— 一个坏条目不该带走整批补部署）
                    failures += Failure(projectId, relPath, "非法项目内相对路径（绝对路径/空段/.. 逃逸）")
                    continue
                }
                try {
                    if (Files.exists(target)) continue        // 只补缺，绝不覆盖（见 KDoc 边界 1）
                    if (bytes.isEmpty()) {
                        failures += Failure(projectId, relPath, "来源字节为空（空脚本不落盘，见诚实边界）")
                        continue
                    }
                    writeAtomic(target, bytes)
                    deployed += Deployed(projectId, relPath, target)
                } catch (t: Throwable) {
                    failures += Failure(projectId, relPath, t.message ?: t::class.simpleName ?: "未知失败")
                }
            }
        }
        return Report(deployed, failures)
    }

    /** 目标路径；非法相对路径回 null（判据见 [isSafeRelPath]）。 */
    private fun targetOf(projectId: String, relPath: String): Path? =
        if (isSafeRelPath(relPath)) ScriptPaths.projectRoot(filesDir, projectId).resolve(relPath) else null

    /**
     * 写入临时文件 + 同目录原子 rename（`StandardCopyOption.ATOMIC_MOVE`）。
     * 临时文件与目标同目录：跨目录 rename 不是原子的，跨分区更是直接退化成复制。
     */
    private fun writeAtomic(target: Path, bytes: ByteArray) {
        val dir = target.parent
        Files.createDirectories(dir)
        val tmp = Files.createTempFile(dir, ".recover-", ".tmp")
        try {
            Files.write(tmp, bytes)
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                // 文件系统不支持原子 rename（少数 FAT/exFAT 外置存储）：退化成 REPLACE_EXISTING。
                // 退化的代价是"rename 非原子"这一条，而不是"不写" —— 静默不写才是更坏的选择。
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (t: Throwable) {
            runCatching { Files.deleteIfExists(tmp) }     // 失败不留半截临时文件
            throw t
        }
    }

    private companion object {
        /**
         * 项目内相对路径判据（与 `:app-service:script-repo` 的 `DeployPath.isSafeRelPath` 同规约）。
         *
         * 这里**刻意重写一份而不是引用**：那份住 script-repo 模块，而 scheduler 的架构门禁
         * 禁止依赖同级 app-service 模块（见 `ArchitectureTest`）。规约是同一套，
         * 差异只在这份还要求"非空字节"的判定由调用处承担。
         */
        fun isSafeRelPath(relPath: String): Boolean {
            if (relPath.isBlank()) return false
            if (relPath.startsWith("/") || relPath.startsWith("\\")) return false
            for (seg in relPath.split('/')) {
                if (seg == ".." || seg == "." || seg.isEmpty() || seg.contains('\\')) return false
            }
            return true
        }
    }
}
