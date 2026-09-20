package com.autoscript.appservice.packager.npm

import java.nio.file.Files
import java.nio.file.Path

/**
 * 内容寻址缓存索引（§10.2 `cacheDir/npm-cache` · cacache `content-v2` 的本地实现）。
 *
 * 存在的意义：[CacheIndex] 是接缝，测试里可以造假；但 `offlineGap` 要能回答真问题——
 * 「按这份 lock 离线装，缺哪些 tarball」。这类问句只能由**磁盘上的真实 content-v2** 回答，
 * 故给一份生产实现，规则与 [NpmCacheSeedDeployer] 同源（同一条 [NpmCacheSeedDeployer.contentPath]，
 * 播与查永不漂移）。
 *
 * 语义纪律：
 * - `has(null/空/畸形) = false` —— 不可校验即不可信（§10.5：没有 integrity 的条目不进可信缓存）；
 * - 只看**文件存在**，不验字节：调用方是 `offlineGap` 这类「离线前体检」而非安全边界，
 *   真正的内容校验由 npm 在 reify 时按 integrity 做（EINTEGRITY 即失败）；
 * - 被系统清理（cacheDir 可自动清）后如实报缺口，由上层提示「重播种子/联网」，不假装有缓存。
 */
class CacacheIndex(private val cacheDir: Path) : CacheIndex {

    override fun has(integrity: String): Boolean {
        val text = integrity.trim()
        if (text.isEmpty()) return false
        return try {
            Files.isRegularFile(NpmCacheSeedDeployer.contentPath(cacheDir, text))
        } catch (e: IllegalArgumentException) {
            false   // 畸形 integrity：不是「有缓存」，是「不可校验」
        }
    }

    /** 缺口明细（可指名）。null/空 integrity 不入列——那类条目该由调用方按 lock 原文展示。 */
    fun missing(integrities: List<String?>): List<String> =
        NpmCacheSeedDeployer.missingIntegrities(cacheDir, integrities)

    /** 闭包命中率（0f..1f）。空闭包 = 1f（离线可装，也确实不用装）。 */
    fun coverage(integrities: List<String?>): Float {
        val known = integrities.filter { !it.isNullOrBlank() }
        if (known.isEmpty()) return 1f
        val hit = known.count { has(it!!) }
        return hit.toFloat() / known.size
    }

    /** 缓存内容条目数（content-v2 下常规文件数；体积由上层 Kotlin 遍历算，§10.6）。 */
    fun contentCount(): Int {
        val root = NpmCacheSeedDeployer.cacacheDir(cacheDir).resolve("content-v2")
        if (!Files.isDirectory(root)) return 0
        var n = 0
        Files.walk(root).use { s -> s.filter { Files.isRegularFile(it) }.forEach { n++ } }
        return n
    }

    /** 缓存总字节（UI「包大小管理页」的 npm-cache 一栏）。 */
    fun contentBytes(): Long {
        val root = NpmCacheSeedDeployer.cacacheDir(cacheDir).resolve("content-v2")
        if (!Files.isDirectory(root)) return 0
        var total = 0L
        Files.walk(root).use { s -> s.filter { Files.isRegularFile(it) }.forEach { total += Files.size(it) } }
        return total
    }
}
