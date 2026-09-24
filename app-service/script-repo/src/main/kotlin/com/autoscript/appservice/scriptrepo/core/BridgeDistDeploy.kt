package com.autoscript.appservice.scriptrepo.core

import com.autoscript.domain.scripts.ScriptPaths
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * facade dist 的装配期落位（docs §12.4 资产交付轨：`assets/bridge-dist/` →
 * `filesDir/node_modules/auto/`，让每个脚本 `require('auto')` 都解析得到）。
 *
 * 与 [ScriptDeployRecovery]（scheduler 侧，补**用户脚本**）的三条分野，先说清楚：
 * 1. **覆盖语义相反**：那边是用户内容 —— 只补缺、绝不覆盖；这边是**应用自有资产** ——
 *    部署内容与 assets 逐字节不同就替换（app 升级换新 facade 靠的就是这条；
 *    留着旧 dist 比覆盖更糟：bootstrap 与 errors 等模块跨版本形状不配对会当场炸）。
 *    判据不需要版本号文件：**字节即版本**（读得到就能比，省一个会说谎的元数据位）。
 * 2. **孤儿要清**：assets 里没有、盘上却有的文件（旧版残留）在全量读成功后删掉 ——
 *    留着的孤儿可能被 require 解析到（shadow 新版）。**任何一条读/写失败就不清**
 *    （怕把"刚读失败的那个文件"当孤儿清掉）：失败宁可留旧，不可丢件。
 * 3. **空来源 = 没货，不是"已部署"**：[Report.deployed] 空且 [Report.pruned] 空时
 *    `changed == false` —— 装配层不得粉饰成 facade 就位。
 *
 * 输入是**扁平** map（assets 枚举出的文件名 → 字节）：dist 单层无子目录；名字含路径
 * 分隔符/`..` 一律拒绝（逃逸防线与 [DeployPath] 同一条，这里不复用它的嵌套拼接是因为
 * 落位根下本就该是平铺的模块文件）。空字节不落盘（同 ScriptDeployRecovery：空文件
 * 比缺文件更难查 —— require 到一个 0 字节模块是 SyntaxError，把"没部署上"变成"模块坏了"）。
 *
 * 写入纪律：**临时文件 + 同目录 rename**（与 [AtomicDeployer] 同一手法）——
 * 半截 facade 被 require 是语法错误，比"没部署上"难查。时机同 ScriptDeployRecovery：
 * **只在装配期调一次**，不与运行期并发，故无锁。
 */
class BridgeDistDeploy(
    private val filesDir: Path,
    private val files: Map<String, ByteArray>,
) {

    /** 一个没落上的文件（原因原文，不折叠成布尔）。 */
    data class Failure(val relPath: String, val reason: String)

    /**
     * 落位结果。[deployed] 是真写了的（缺位或内容不同）；[unchanged] 是字节已一致的
     * （不动盘也不算 changed）；[pruned] 是清掉的孤儿；[failures] 非空时**一定没清孤儿**
     * （见 KDoc 边界 2）。
     */
    data class Report(
        val deployed: List<String> = emptyList(),
        val unchanged: Int = 0,
        val pruned: List<String> = emptyList(),
        val failures: List<Failure> = emptyList(),
    ) {
        /** 本次真的动了盘（落位或清孤儿）—— false = 没货或全部已就位，不是"失败"。 */
        val changed: Boolean get() = deployed.isNotEmpty() || pruned.isNotEmpty()
    }

    fun run(): Report {
        // 空来源：不动盘（连目录都不建）——"没货"与"落好了"是两件事。
        if (files.isEmpty()) return Report()

        val target = ScriptPaths.autoModuleRoot(filesDir)
        val deployed = mutableListOf<String>()
        val failures = mutableListOf<Failure>()
        var unchanged = 0

        for (name in files.keys.sorted()) {
            if (!isFlatFileName(name)) {
                failures += Failure(name, "非法落位文件名（含路径分隔符/..：dist 必须平铺）")
                continue
            }
            val bytes = files.getValue(name)
            try {
                if (bytes.isEmpty()) {
                    failures += Failure(name, "来源字节为空（空文件不落盘，见 KDoc）")
                    continue
                }
                val dest = target.resolve(name)
                if (Files.isRegularFile(dest) && Files.readAllBytes(dest).contentEquals(bytes)) {
                    unchanged++
                    continue
                }
                writeAtomic(dest, bytes)
                deployed += name
            } catch (t: Throwable) {
                failures += Failure(name, t.message ?: t::class.simpleName ?: "未知失败")
            }
        }

        // 孤儿清理：只在**全部成功**时做（半量读入时把没读到的当孤儿清 = 丢件）。
        val pruned = mutableListOf<String>()
        if (failures.isEmpty()) {
            if (Files.isDirectory(target)) {
                Files.list(target).use { stream ->
                    stream.forEach { p ->
                        val name = p.fileName.toString()
                        if (name in files) return@forEach
                        try {
                            Files.deleteIfExists(p)
                            pruned += name
                        } catch (t: Throwable) {
                            failures += Failure(name, "孤儿清理失败：${t.message ?: t::class.simpleName}")
                        }
                    }
                }
            }
        }
        return Report(deployed, unchanged, pruned, failures)
    }

    /** 平铺判据：非空、不含 `/` `\\`、不是 `.`/`..`（逃逸防线，同 DeployPath 的精神）。 */
    private fun isFlatFileName(name: String): Boolean =
        name.isNotEmpty() && name != "." && name != ".." &&
            !name.contains('/') && !name.contains('\\') && !name.contains('\u0000')

    /**
     * 写入临时文件 + 同目录原子 rename（与 ScriptDeployRecovery.writeAtomic 同手法；
     * 不复用是因为那是 private —— 两处各 15 行，抽公共件要跨模块，不值当）。
     */
    private fun writeAtomic(target: Path, bytes: ByteArray) {
        val dir = target.parent
        Files.createDirectories(dir)
        val tmp = Files.createTempFile(dir, ".bridge-dist-", ".tmp")
        try {
            Files.write(tmp, bytes)
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (t: Throwable) {
            runCatching { Files.deleteIfExists(tmp) }
            throw t
        }
    }
}
