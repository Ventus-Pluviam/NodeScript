package com.autoscript.appservice.packager.npm

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Base64

/**
 * 精选缓存种子部署器（docs/framework-design.md §10.2 存储布局 · `cacheDir/npm-cache-seed`，
 * §10.11 P0「精选缓存种子 + 离线首装 + `--prefer-offline`」）。
 *
 * **首启播种面**（§10.9 UX 6「原子部署 assets/npm CLI + 播种精选缓存」）：
 * 把 assets 里的 ~5MB 精选 tarball（axios/dayjs/lodash/cheerio…）解到 cacheDir 后，
 * 在内容寻址缓存 `_cacache/content-v2/<alg>/<xx>/<yy>/<rest>` 落位，让
 * `npm ci --offline` / `--prefer-offline` 直接命中（命中语义见 [CACHE_HIT_NOTE]）。
 *
 * 为什么**不**写 index-v5：实测（npm 10.9 本地 + 本仓库 e2e 红测）`npm ci --offline`
 * 只走 `cacache.get.stream.byDigest(cache, integrity)` —— 按 integrity 直取 content，
 * 索引条目（`pacote:tarball:<spec>`）是**下载后的写入路径**，不是离线命中路径。
 * 写半截索引反而制造「索引指向不存在的内容」的坏状态，故只写 content + 校验。
 *
 * 与 [NpmCliDeployer] 同构（幂等锚 + tmp 写 + 逐条目 sha512 校验 + rename），但两处不同：
 * - 键是 **integrity（sha512-…）** 而非内容哈希锚：种子的身份 = lock 里的 integrity，
 *   同一包不同版本 = 不同 integrity = 不同文件，天然去重；
 * - 落点在 cacheDir（**系统可自动清，损失可接受**）而非 filesDir：被清了下次播种重建，
 *   不需要墓碑回滚那套完整性保护，失败即整筐作废重来。
 *
 * 素材源为接缝（[SeedSource]）：Android 侧 `assets.list("npm-seed/…")`；
 * 测试/CI 侧文件系统目录树。本模块零 android.*（archUnit 守护）。
 */
object NpmCacheSeedDeployer {

    /**
     * 离线命中语义（实测口径，写死防回归）：
     * pacote 的 `tarballStream` 在 `preferOffline/offline && integrity && resolved` 时
     * 走 `#tarballFromCache()` = `cacache.get.stream.byDigest(cache, integrity)`，
     * **不需要 packument 索引**；`make-fetch-happen:request-cache:*` 只服务在线 packument。
     * 故本部署器只落 content-v2；`npm ci --offline` 金标准见
     * `app-service/packager/src/test/kotlin/.../HostNodeNpmSeedE2ETest.kt`。
     */
    const val CACHE_HIT_NOTE = "content-v2 byDigest(index 非命中路径)"

    /** 单个 tarball 种子的清单条目（`<name>-<version>.tgz` + integrity 行）。 */
    data class SeedEntry(val fileName: String, val integrity: String, val bytes: ByteArray) {
        override fun equals(other: Any?) = other is SeedEntry &&
            fileName == other.fileName && integrity == other.integrity && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = 31 * fileName.hashCode() + integrity.hashCode() + bytes.contentHashCode()
    }

    /** 种子包（一批精选 tarball + 计划清单，见 [plan]）。 */
    data class SeedBundle(val entries: List<SeedEntry>, val manifest: String)

    /** 素材源：枚举 + 读取种子目录（`<name>-<version>.tgz[.sha512]` 对）。 */
    interface SeedSource {
        /** 枚举源目录下全部文件（含 `<tgz>.sha512` sidecar）。 */
        fun list(): List<String>

        /** 读一个源文件；不存在返回 null。 */
        fun read(relPath: String): ByteArray?
    }

    /** 部署结果：ready=true 表示本次有内容落位；zero 表示全部已就位（幂等命中）。 */
    data class Outcome(val deployed: Int, val skipped: Int, val bytes: Long)

    /** 缓存根（`cacheDir/npm-cache`）；content 在 `_cacache/content-v2` 下。 */
    fun cacheRoot(cacheDir: Path): Path = cacheDir

    fun cacacheDir(cacheDir: Path): Path = cacheDir.resolve("_cacache")

    /** content-v2 路径：`_cacache/content-v2/<alg>/<hex[0:2]>/<hex[2:4]>/<hex[4:]>`（cacache hash-to-segments）。 */
    fun contentPath(cacheDir: Path, integrity: String): Path {
        val parsed = parseIntegrity(integrity)
        return cacacheDir(cacheDir).resolve("content-v2")
            .resolve(parsed.algorithm)
            .resolve(parsed.hex.substring(0, 2))
            .resolve(parsed.hex.substring(2, 4))
            .resolve(parsed.hex.substring(4))
    }

    /**
     * 从源目录解析出一个**计划**（[SeedBundle]）：不做任何 IO 写。
     *
     * 源布局二选一（都是 assets 侧顺手可生成的形态）：
     * 1. `<name>-<version>.tgz` + 同名 `.sha512` sidecar（内容 = `sha512-<base64>\n`）——**推荐**，
     *    侧车文件即信任锚，无需下载解压；sidecar 缺失/不符 → 整个 bundle 拒绝（不静默跳过条目，
     *    「少一个种子」比「坏一个种子」更难排查）。
     * 2. 单文件 `manifest.json`（`{"entries":[{"file":"…","integrity":"sha512-…"}]}`）+ 各 tarball。
     */
    fun plan(source: SeedSource): SeedBundle {
        val rels = source.list()
        val bundles = rels.filter { it.endsWith(".tgz") }
            .mapNotNull { rel ->
                val side = source.read("$rel.sha512")?.let { String(it, StandardCharsets.UTF_8).trim() }
                if (side.isNullOrEmpty()) {
                    // 无侧车：试单文件 manifest 声明（形态 2），否则如实失败
                    return@mapNotNull null
                }
                val bytes = source.read(rel) ?: return@mapNotNull null
                SeedEntry(rel, side, bytes)
            }
        if (bundles.isNotEmpty()) return SeedBundle(bundles, renderManifest(bundles))
        val dec = planFromManifest(source)
        if (dec != null) return dec
        throw IllegalStateException(
            "缓存种子素材为空或不可信：无 <tgz>.sha512 侧车且无 manifest.json（拒绝播种不可校验内容）",
        )
    }

    private fun planFromManifest(source: SeedSource): SeedBundle? {
        val raw = source.read("manifest.json") ?: return null
        val entries = mutableListOf<SeedEntry>()
        // 极简 JSON 清单解析：只认 entries:[{file,integrity}] 平铺形状（不引 JSON 库）
        val text = String(raw, StandardCharsets.UTF_8)
        val files = regexAll(text, "\"file\"\\s*:\\s*\"([^\"]+)\"")
        val ints = regexAll(text, "\"integrity\"\\s*:\\s*\"([^\"]+)\"")
        if (files.isEmpty() || files.size != ints.size) {
            throw IllegalStateException("缓存种子 manifest.json 形状非法：file/integrity 未成对（${files.size} vs ${ints.size}）")
        }
        for (i in files.indices) {
            val bytes = source.read(files[i]) ?: continue
            entries.add(SeedEntry(files[i], ints[i], bytes))
        }
        if (entries.isEmpty()) throw IllegalStateException("缓存种子 manifest.json 未指向任何存在的 tarball")
        return SeedBundle(entries, renderManifest(entries))
    }

    private fun renderManifest(entries: List<SeedEntry>): String = buildString {
        append("{\"entries\":[")
        entries.forEachIndexed { i, e ->
            if (i > 0) append(',')
            append("{\"file\":\"").append(e.fileName)
            append("\",\"integrity\":\"").append(e.integrity)
            append("\"}")
        }
        append("]}")
    }

    /**
     * 播种：把 [bundle] 的每个 tarball 按 integrity 落位 content-v2。
     *
     * 可信链：**内存里先 sha512 校验**（integrity 与字节不符 → 整体拒绝，绝不半播）
     * → 目标已存在且大小一致视为就位（内容寻址下同 integrity = 同内容，幂等）
     * → 写 tmp → 磁盘复核 → rename（同 cacache 的 tmp+rename 纪律）。
     */
    fun deploy(cacheDir: Path, bundle: SeedBundle): Outcome {
        require(bundle.entries.isNotEmpty()) { "种子包为空（无可播种内容）" }
        var deployed = 0
        var skipped = 0
        var bytes = 0L
        Files.createDirectories(cacacheDir(cacheDir))
        val tmpRoot = Files.createDirectories(
            cacacheDir(cacheDir).resolve("tmp-seed-" + System.nanoTime().toString(36)),
        )
        try {
            for (e in bundle.entries) {
                val parsed = parseIntegrity(e.integrity)
                require(parsed.algorithm == "sha512") { "种子 integrity 只支持 sha512：$e.fileName 为 ${parsed.algorithm}" }
                require(e.bytes.size > 0) { "种子 tarball 为空：${e.fileName}" }
                val actual = DirSizer.sha512(e.bytes)
                require(actual.equals(parsed.hex, ignoreCase = true)) {
                    "种子 integrity 与内容不符（拒绝播种）：${e.fileName} 声明 " +
                        parsed.hex.take(16) + "… 实为 " + actual.take(16) + "…"
                }
                val target = contentPath(cacheDir, e.integrity)
                if (Files.isRegularFile(target) && Files.size(target) == e.bytes.size.toLong()) {
                    skipped++
                    bytes += e.bytes.size
                    continue
                }
                Files.createDirectories(target.parent)
                val tmp = tmpRoot.resolve(target.fileName.toString())
                Files.write(tmp, e.bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
                require(DirSizer.sha512(Files.readAllBytes(tmp)).equals(actual, ignoreCase = true)) {
                    "种子落盘复核失败：${e.fileName}"
                }
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
                deployed++
                bytes += e.bytes.size
            }
        } finally {
            tmpRoot.toFile().deleteRecursively()
        }
        return Outcome(deployed = deployed, skipped = skipped, bytes = bytes)
    }

    /** 便利重载：源目录 → 计划 → 播种（首启路径一把梭）。 */
    fun deploy(cacheDir: Path, source: SeedSource): Outcome =
        deploy(cacheDir, plan(source))

    /**
     * 离线缺口核对（`offlineGap` 的 cache 侧实现底座）：给定 lock 闭包 integrity 列表，
     * 返回**可指名**的缺失项。null/空 integrity 由调用方按 §10 口径判「不可校验 = 不可信」
     * 并展示原始（name,version）——此处只回答「有 integrity 但 cache 里没有」，
     * 故 null 不占位（否则 UI 会显示一条没有名字的缺口）。
     */
    fun missingIntegrities(cacheDir: Path, integrities: List<String?>): List<String> =
        integrities.filter { !it.isNullOrBlank() }.map { it!! }
            .filter { !Files.isRegularFile(contentPath(cacheDir, it)) }

    /** `sha512-<base64>` → (算法, hex)。非该形状抛 IllegalArgumentException（调用方折叠 ERR_INVALID_PARAM）。 */
    internal data class ParsedIntegrity(val algorithm: String, val hex: String)

    internal fun parseIntegrity(integrity: String): ParsedIntegrity {
        val dash = integrity.indexOf('-')
        require(dash > 0) { "integrity 形状非法（缺算法前缀）：$integrity" }
        val alg = integrity.substring(0, dash)
        val rest = integrity.substring(dash + 1)
        require(rest.isNotEmpty()) { "integrity 形状非法（缺摘要）：$integrity" }
        val hex = try {
            Base64.getDecoder().decode(rest).joinToString("") { "%02x".format(it) }
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("integrity 摘要非 base64：$integrity")
        }
        return ParsedIntegrity(alg, hex)
    }

    private fun regexAll(text: String, pattern: String): List<String> =
        Regex(pattern).findAll(text).map { it.groupValues[1] }.toList()
}
