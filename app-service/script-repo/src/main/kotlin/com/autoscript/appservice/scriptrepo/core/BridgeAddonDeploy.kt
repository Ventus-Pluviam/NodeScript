package com.autoscript.appservice.scriptrepo.core

import com.autoscript.domain.scripts.ScriptPaths
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * bridge addon 的装配期落位（docs §19 jniLibs 交付轨：`assets/bridge-addon/` →
 * `filesDir/lib/bridge_native.node`，让 `AUTOSCRIPT_BRIDGE_ADDON` 指得到真文件、
 * main.cpp 的 `require(env)` 才能 dlopen 预载）。
 *
 * 与 [BridgeDistDeploy]（同轨的 facade dist，多文件平铺 + 孤儿清理）的分野：
 * 1. **单文件，不 claim 整个目录**：`filesDir/lib/` 可能住别的东西，清孤儿会误伤 ——
 *    这里只对 `ScriptPaths.bridgeAddonFile` 这一个路径负责，没有 pruned。
 * 2. **覆盖语义相同**：应用自有资产，**字节即版本**（异则原子替换）—— addon 跨版本
 *    ABI/符号面不配对时 dlopen 直接崩，留旧比覆盖更糟。
 * 3. **空来源 = 没货（null），空字节 = 失败**：`bytes == null` 是"这次没带货"
 *    （[Report.changed] false，引擎侧 `addonPath` 缺文件即降级不注入）；非 null 但
 *    0 字节是"来源坏了"（[Report.failure] 点名）—— 0 字节 `.node` 被 require 是
 *    SyntaxError/dlopen 失败，比"没部署上"难查。
 *
 * 写入纪律与 [BridgeDistDeploy] 同手法：临时文件 + 同目录 rename（半截 `.node`
 * 被 require 比缺文件难查得多）。只在装配期调一次，不与运行期并发，故无锁。
 */
class BridgeAddonDeploy(
    private val filesDir: Path,
    private val bytes: ByteArray?,
) {

    /**
     * 落位结果。[deployed] 是真写了的（缺位或内容不同）；[unchanged] 是字节已一致的
     * （不动盘也不算 changed）；[failure] 非空 = 这次没落上（原因原文，不折叠成布尔）。
     * `bytes == null` 且无 failure = 本次没货可落（**不是**"addon 已就位"）。
     */
    data class Report(
        val deployed: Boolean = false,
        val unchanged: Boolean = false,
        val failure: String? = null,
    ) {
        /** 本次真的动了盘 —— false = 没货 / 已就位 / 失败，三种都不是"失败地变了"。 */
        val changed: Boolean get() = deployed
    }

    fun run(): Report {
        // 没货：不动盘（连目录都不建）——"没货"与"落好了"是两件事，与 BridgeDistDeploy 同边界。
        if (bytes == null) return Report()
        if (bytes.isEmpty()) return Report(failure = "来源字节为空（空 .node 不落盘，见 KDoc）")

        val dest = ScriptPaths.bridgeAddonFile(filesDir)
        return try {
            if (Files.isRegularFile(dest) && Files.readAllBytes(dest).contentEquals(bytes)) {
                Report(unchanged = true)
            } else {
                writeAtomic(dest, bytes)
                Report(deployed = true)
            }
        } catch (t: Throwable) {
            Report(failure = t.message ?: t::class.simpleName ?: "未知失败")
        }
    }

    /**
     * 写入临时文件 + 同目录原子 rename（与 [BridgeDistDeploy.writeAtomic] 同手法；
     * 不抽公共件是因为两处各 15 行且跨类，抽了要动已落的 dist 轨，不值当）。
     */
    private fun writeAtomic(target: Path, bytes: ByteArray) {
        val dir = target.parent
        Files.createDirectories(dir)
        val tmp = Files.createTempFile(dir, ".bridge-addon-", ".tmp")
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
