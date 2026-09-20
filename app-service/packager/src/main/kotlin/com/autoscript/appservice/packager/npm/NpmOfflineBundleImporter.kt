package com.autoscript.appservice.packager.npm

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * 离线 bundle 导入（§10.2 存储布局 · `files/npm-import/`，§10.9 UX 4「离线包导入」）。
 *
 * 形态：一个 zip，内含 cacache 子树（`_cacache/content-v2/…`，可带 `index-v5/`）。
 * 导入 = 把这些条目按 cacache 布局合入 `cacheDir`，让随后的 `npm ci --offline`
 * 直接命中（命中语义见 [NpmCacheSeedDeployer.CACHE_HIT_NOTE]）。
 *
 * 信任口径（与种子不同的关键点）：
 * - **种子**信 assets（APK 内、随包签名、不可被第三方改），故侧车即可；
 * - **bundle** 是用户从 SAF 递进来的外部文件（桌面 `npm ci` 产物/别人导出的包），
 *   字节不保证忠于其声明的 integrity，故**导入时逐条目 sha512 复核**：
 *   `contentPath(integrity)` 与解出的内容字节不符 → 该条目不入 cache
 *   （否则就是往缓存里投放命名错误的内容，之后真装会 EINTEGRITY，报错还指不到源头）。
 *
 * 安全：先过路径检疫（`..`/绝对路径/反斜杠逃逸）再解包——zip slip 是这条路径的
 * 真实威胁面（用户文件不可信），不做等于给任意写。只接受**常规文件**条目，
 * 目录/符号链接形态一律忽略（cacache 不需要链接即成）。
 *
 * 与 [NpmSnapshot] 的关系：snapshot 是「高信任项目交付」（HMAC 签内容清单，导入侧验签
 * 后 reify 直接给 node_modules）；bundle 是「给缓存喂 tarball」，两者服务不同问句，
 * 信任门槛也按来源分级（SAF 外部文件 < APK assets < 签过快照）。
 */
object NpmOfflineBundleImporter {

    /** 导入结果（诚实报数：跳过/拒绝的条目数 != 0 时调用方该提示，别当无事）。 */
    data class Result(
        val imported: Int,
        val skipped: Int,
        val rejected: List<String>,
        val bytes: Long,
    ) {
        val clean: Boolean get() = rejected.isEmpty()
    }

    /**
     * 把 [zipFile] 里 `_cacache/content-v2` 下的条目合入 [cacheDir]。
     *
     * @return [Result]；`rejected` 非空表示有条目因路径非法/摘要不符被拒（调用方如实告知）。
     */
    fun import(zipFile: Path, cacheDir: Path): Result {
        if (!Files.isRegularFile(zipFile)) {
            throw IllegalArgumentException("离线 bundle 不存在：$zipFile")
        }
        var imported = 0
        var skipped = 0
        var bytes = 0L
        val rejected = mutableListOf<String>()
        Files.createDirectories(NpmCacheSeedDeployer.cacacheDir(cacheDir))
        ZipFile(zipFile.toFile()).use { zf ->
            val en = zf.entries()
            while (en.hasMoreElements()) {
                val e = en.nextElement()
                if (e.isDirectory) continue
                val name = e.name.replace('\\', '/')
                // 检疫顺序要紧：路径逃逸是威胁，与它声称在哪个子树无关——先拒再谈归属。
                if (isUnsafePath(name)) {
                    rejected += name
                    continue
                }
                if (!isCacacheContent(name)) continue   // index-v5/其他文件：不导入（见类注释）
                // 从路径反推 integrity：content-v2/<alg>/<xx>/<yy>/<rest(hex)>
                val seg = name.removePrefix("_cacache/content-v2/").split('/')
                if (seg.size != 4) {
                    rejected += name
                    continue
                }
                val (alg, a, b, rest) = seg
                val hex = a + b + rest
                val integrity = "$alg-${integrityBase64Of(hex)}"
                val target = NpmCacheSeedDeployer.contentPath(cacheDir, integrity)
                val data = zf.getInputStream(e).readBytes()
                val actual = hexOf(alg, data)
                if (!actual.equals(hex, ignoreCase = true)) {
                    rejected += name     // 内容与路径声明的摘要不符：不往缓存里投放冒名条目
                    continue
                }
                if (Files.isRegularFile(target) && Files.size(target) == data.size.toLong()) {
                    skipped++           // 内容寻址幂等
                    bytes += data.size
                    continue
                }
                Files.createDirectories(target.parent)
                val tmp = Files.createTempFile(NpmCacheSeedDeployer.cacacheDir(cacheDir), "bundle-", ".tmp")
                try {
                    Files.write(tmp, data)
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
                } finally {
                    Files.deleteIfExists(tmp)
                }
                imported++
                bytes += data.size
            }
        }
        return Result(imported, skipped, rejected, bytes)
    }

    /** 只收 content-v2 常规文件；index-v5 由 npm 自己按需生成（导索引 = 导出「缓存出现过的历史」）。 */
    internal fun isCacacheContent(name: String): Boolean = name.startsWith("_cacache/content-v2/")

    /** zip slip 检疫：`..` 段、绝对路径、盘符形态一律拒（cacheDir 之外一字节都不写）。 */
    internal fun isUnsafePath(name: String): Boolean {
        if (name.startsWith("/") || name.contains("\\")) return true
        return name.split('/').any { it == ".." }
    }

    private fun integrityBase64Of(hex: String): String {
        val raw = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        return java.util.Base64.getEncoder().encodeToString(raw)
    }

    private fun hexOf(alg: String, data: ByteArray): String =
        java.security.MessageDigest.getInstance(alg).digest(data)
            .joinToString("") { "%02x".format(it) }
}
