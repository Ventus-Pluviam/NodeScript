package com.autoscript.appservice.npm

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Base64

/**
 * npm 缓存按 lock 闭包回收（§10.9 第 5 条「cache clean」的落地形态）。
 *
 * **为什么不是 `rm -rf npm-cache`**（用户 2026-10-09 裁定）：§10 整卷的离线能力
 * （`--prefer-offline`、精选种子首装、`offlineGap` 体检）全建在这个缓存上，全清等于
 * 把紧挨着的「按 lock 重装」那颗按钮变成**必须联网**。回收的语义是「删掉没有任何项目
 * lock 需要的那些」，而不是「把缓存清空」。
 *
 * **不碰 `index-v5` 的既有内容、只做修复**：实测（2026-10-09，npm 10.9.8 本机）——
 * - `npm ci --offline` 走 `cacache.get.stream.byDigest`，**不看 index**：content 在就命中，
 *   缺就 `ENOTCACHED`（与 `NpmCacheSeedDeployer.CACHE_HIT_NOTE` 同一条实测口径）；
 * - 但**在线**路径读 index：index 指向一份已消失的 content 时，`npm install` 报
 *   `ENOENT … Invalid response body while trying to fetch`（不是回源重下）——
 *   也就是说**悬空 index 会让缓存从「没用」变成「有害」**。
 *
 * 于是回收必须做两件事，缺一不可：删 content，**并把指向已消失 content 的 index 行摘掉**。
 * 第二件事对「本来就已经悬空的 index」（系统清缓存、上次崩在半路）同样有效 ——
 * 那种状态下连在线安装都是坏的，而它在界面上看不出来。
 *
 * 已知边界（如实写在这里，不假装没有）：index 的**行**是摘掉了，但 index 里那些
 * 「指向仍然存在、只是没人再需要」的条目（packument 索引等）不清理 —— 它们是几 KB 的
 * 小文件，清它们要重建整棵桶树，收益与风险不成比例。故 [Report.keptEntries] 会**大于**
 * 真正被 lock 引用的条目数，这不是漏删，是刻意留的。
 *
 * **调用方给哪个目录就只动哪个目录**（2026-10-09 批 86 已把四处读者合一到
 * [NpmCacheSeedDeployer.cacheRoot]，但这条纪律不变）：本对象不替调用方猜「npm 的缓存
 * 是不是在别处」—— 猜错会把「回收」变成「删掉别人正在用的东西」。合一之前那四处
 * 实测互不相同（`--cache` 拿的是 `cacheDir` 本身、[CacacheIndex] 读 `cacheDir/npm-cache`、
 * bundle 导入落第三个目录），后果是**静默失效**而非报错，见 `cacheRoot` 的 KDoc。
 */
object NpmCacheReclaim {

    /**
     * 回收结果。
     *
     * [keptEntries]/[keptBytes] 是**回收后缓存里还剩多少**（不是「本来有多少」）——
     * 界面要回答的是「点完了还占多大地方」，那才是用户下一步的依据。
     * [keepCount] 是本次的保留集大小（lock 闭包条目数），单列出来是为了让「一个 lock 都没有」
     * （保留集为空 = 缓存里能删的全删）与「lock 很大」在界面上可区分。
     *
     * **刻意不提供 `offlineUsable` 这类派生判断**：`keptEntries > 0` 不等于「离线可用」——
     * 认不出形状而留下的条目（sha1 目录、半截路径）一个都命中不了 `byDigest`。想下「还能不能
     * 离线装」的结论得看 `keepCount` 与具体 lock，那是调用方的事，不是这份账能替它拍的。
     */
    data class Report(
        val removedEntries: Int,
        val removedBytes: Long,
        val keptEntries: Int,
        val keptBytes: Long,
        val keepCount: Int,
        /** index-v5 是否被改写过（有悬空引用才需要；没改说明缓存本来就是干净的）。 */
        val indexRebuilt: Boolean,
    )

    /**
     * 回收：[keep] 里的 integrity 一律保留，其余 content 删除；随后修复 index-v5。
     *
     * [keep] 是**所有项目 lock 闭包的并集**（调用方算，见 `InstallCoordinator.reclaimCache`）——
     * 只按当前项目算会删掉别的项目离线重装要用的包，那种「省了空间、坏了别的项目」的
     * 后果用户在点按钮时完全看不见。
     *
     * 不认识的条目**一律保留**（非 sha512、路径段数不对、摘要非法）：本函数的职责是回收
     * 缓存，不是打扫看不懂的东西 —— 删掉一个读不懂的文件是「猜」，而猜错的代价是别人
     * 的离线能力，收益只是几个字节。
     */
    fun reclaim(cacheDir: Path, keep: Set<String>): Report {
        val contentRoot = NpmCacheSeedDeployer.cacacheDir(cacheDir).resolve("content-v2")
        var removedEntries = 0
        var removedBytes = 0L
        var keptEntries = 0
        var keptBytes = 0L

        if (Files.isDirectory(contentRoot)) {
            val doomed = ArrayList<Path>()
            Files.walk(contentRoot).use { s ->
                s.filter { Files.isRegularFile(it) }.forEach { f ->
                    val integrity = integrityOf(contentRoot, f)
                    if (integrity != null && integrity !in keep) {
                        doomed.add(f)
                    } else {
                        keptEntries++
                        keptBytes += runCatching { Files.size(f) }.getOrDefault(0L)
                    }
                }
            }
            for (f in doomed) {
                val size = runCatching { Files.size(f) }.getOrDefault(0L)
                // 删不掉（并发占用/权限）就不算进 removed：报出来的数字必须是真发生的事。
                if (runCatching { Files.deleteIfExists(f) }.getOrDefault(false)) {
                    removedEntries++
                    removedBytes += size
                } else {
                    keptEntries++
                    keptBytes += size
                }
            }
            // 空目录（含三段 hash 目录）顺手收掉：留着不影响正确性，但会让「缓存有多大」
            // 这类按目录数估的读数骗人。
            pruneEmptyDirs(contentRoot)
        }

        val indexRebuilt = repairIndex(cacheDir)
        return Report(
            removedEntries = removedEntries,
            removedBytes = removedBytes,
            keptEntries = keptEntries,
            keptBytes = keptBytes,
            keepCount = keep.size,
            indexRebuilt = indexRebuilt,
        )
    }

    /**
     * content 文件 → integrity（[NpmCacheSeedDeployer.contentPath] 的逆）。
     *
     * 形状不认识就回 null（= 保留）：这个函数是**删东西的依据**，它认不出来时唯一安全的
     * 答案是「不动」。
     */
    private fun integrityOf(contentRoot: Path, file: Path): String? {
        val rel = contentRoot.relativize(file)
        if (rel.nameCount != 4) return null
        val parts = (0 until 4).map { rel.getName(it).toString() }
        if (parts[0] != "sha512") return null
        val hex = parts[1] + parts[2] + parts[3]
        if (hex.length != 128 || !hex.all { it in "0123456789abcdef" }) return null
        return "sha512-" + Base64.getEncoder().encodeToString(hexToBytes(hex))
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { ((hex[it * 2].digitToInt(16) shl 4) or hex[it * 2 + 1].digitToInt(16)).toByte() }

    /**
     * 摘掉指向已消失 content 的 index 行（返回是否改写过）。
     *
     * 桶文件是**追加式**的（`\n<sha1>\t<json>`），一个键一行；`cacache.find` 取最后一条匹配，
     * 所以「同一键的多行」是正常形态，本函数逐行判、不做去重。
     *
     * 行内的 `integrity` 为 null 是 cacache 的**删除标记**（`compact` 里那段注释），
     * 保留 —— 它不是悬空引用，是「这个键被删过」的记法。
     */
    private fun repairIndex(cacheDir: Path): Boolean {
        val indexRoot = NpmCacheSeedDeployer.cacacheDir(cacheDir).resolve("index-v5")
        if (!Files.isDirectory(indexRoot)) return false
        var changed = false
        val buckets = ArrayList<Path>()
        Files.walk(indexRoot).use { s -> s.filter { Files.isRegularFile(it) }.forEach { buckets.add(it) } }
        for (bucket in buckets) {
            val text = runCatching { String(Files.readAllBytes(bucket), Charsets.UTF_8) }.getOrNull() ?: continue
            val kept = text.split('\n').filter { line ->
                if (line.isEmpty()) return@filter false
                val tab = line.indexOf('\t')
                if (tab < 0) return@filter false
                val json = line.substring(tab + 1)
                val integrity = jsonIntegrity(json) ?: return@filter true   // null/认不出 = 删除标记/别的形状，保留
                runCatching { Files.isRegularFile(NpmCacheSeedDeployer.contentPath(cacheDir, integrity)) }
                    .getOrDefault(true)
            }
            val rebuilt = kept.joinToString(separator = "\n", prefix = "\n")
            if (rebuilt == text) continue
            changed = true
            if (kept.isEmpty()) {
                runCatching { Files.deleteIfExists(bucket) }
                continue
            }
            // 与 cacache 同纪律：写临时文件再原子 rename（半截桶 = 整个键读不出来）。
            val tmp = Files.createTempFile(bucket.parent, "index-", ".tmp")
            Files.write(tmp, rebuilt.toByteArray(Charsets.UTF_8))
            runCatching { Files.move(tmp, bucket, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
                .onFailure { runCatching { Files.deleteIfExists(tmp) } }
        }
        return changed
    }

    /** 从 index 行里取 `"integrity"`（**只认本函数需要的形状**，不引 JSON 库，与 [LockfileReader] 同口径）。 */
    private fun jsonIntegrity(json: String): String? {
        val key = "\"integrity\":"
        val i = json.indexOf(key)
        if (i < 0) return null
        val q1 = json.indexOf('"', i + key.length)
        if (q1 < 0) return null
        val q2 = json.indexOf('"', q1 + 1)
        if (q2 < 0) return null
        return json.substring(q1 + 1, q2)
    }

    /** 收掉 content-v2 下的空目录（自底向上，只删空的不删有内容的）。 */
    private fun pruneEmptyDirs(root: Path) {
        val dirs = ArrayList<Path>()
        Files.walk(root).use { s -> s.filter { Files.isDirectory(it) }.forEach { dirs.add(it) } }
        for (d in dirs.sortedByDescending { it.nameCount }) {
            if (d == root) continue
            runCatching {
                Files.newDirectoryStream(d).use { if (!it.iterator().hasNext()) Files.deleteIfExists(d) }
            }
        }
    }
}
