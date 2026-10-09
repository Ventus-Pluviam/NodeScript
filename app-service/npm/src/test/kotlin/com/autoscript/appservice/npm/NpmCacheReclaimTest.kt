package com.autoscript.appservice.npm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64

/**
 * 缓存按 lock 闭包回收单测（§10.9 第 5 条「cache clean」的落地形态）。
 *
 * 三类断言，对应实现里那三条不肯让步的边界：
 *
 * - **只删能证明「没人需要」的**：保留集里的一个不动，认不出形状的一个不动
 *   （认错 = 删掉别人的离线能力，收益只是几个字节）；
 * - **index 必须一起修**：实测（npm 10.9.8）悬空 index 会让**在线**安装报
 *   `ENOENT … Invalid response body while trying to fetch` —— 缓存从「没用」变成「有害」，
 *   且界面上看不出来。摘行是本对象的第二件正事，不是附赠；
 * - **数字必须是真发生的事**：删不掉的不计入 removed（宁可少报），
 *   `keptEntries` 是回收后的现状（不是「本来有多少」）。
 */
class NpmCacheReclaimTest {

    @TempDir
    lateinit var dir: Path

    private val cacheDir get() = dir.resolve("cache")

    /** 造一份 content 并返回它的 integrity（`sha512-<base64>`，与主代码同口径）。 */
    private fun seedContent(payload: String): String {
        val bytes = payload.toByteArray()
        val integrity = "sha512-" + Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-512").digest(bytes),
        )
        val p = NpmCacheSeedDeployer.contentPath(cacheDir, integrity)
        Files.createDirectories(p.parent)
        Files.write(p, bytes)
        return integrity
    }

    private fun contentFile(integrity: String): Path = NpmCacheSeedDeployer.contentPath(cacheDir, integrity)

    /** 写一个 index 桶；每行 = (integrity?, key)。integrity=null 即 cacache 的删除标记。 */
    private fun writeBucket(name: String, vararg lines: Pair<String?, String>): Path {
        val bucket = cacheDir.resolve("_cacache/index-v5/aa/bb/$name")
        Files.createDirectories(bucket.parent)
        val text = lines.joinToString(separator = "\n", prefix = "\n") { (integ, key) ->
            val json = """{"key":"$key","integrity":${integ?.let { "\"$it\"" } ?: "null"},"time":1}"""
            "${sha1(json)}\t$json"
        }
        Files.write(bucket, text.toByteArray(Charsets.UTF_8))
        return bucket
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun reclaim(keep: Set<String>) = NpmCacheReclaim.reclaim(cacheDir, keep)

    // ═══ 删什么、留什么 ═══

    @Test
    fun `只删 lock 闭包之外的 content，闭包内的原样不动`() {
        val needed = seedContent("needed-tarball")
        val orphan = seedContent("orphan-tarball")

        val r = reclaim(setOf(needed))

        assertTrue(Files.isRegularFile(contentFile(needed)), "lock 闭包里的条目绝不能被删")
        assertFalse(Files.exists(contentFile(orphan)), "没人需要的条目必须真删掉")
        assertEquals(1, r.removedEntries)
        assertEquals(1, r.keptEntries)
        assertEquals(1, r.keepCount)
        assertEquals("orphan-tarball".toByteArray().size.toLong(), r.removedBytes)
    }

    @Test
    fun `保留集为空 → 能认出来的全删，认不出的留下`() {
        val a = seedContent("a")
        val b = seedContent("b")
        // 形状不认识：算法不是 sha512（cacache 支持 sha1/sha256，本对象的保留集只谈 sha512）
        val weird = cacheDir.resolve("_cacache/content-v2/sha1/ab/cd/rest")
        Files.createDirectories(weird.parent)
        Files.write(weird, "weak".toByteArray())

        val r = reclaim(emptySet())

        assertFalse(Files.exists(contentFile(a)))
        assertFalse(Files.exists(contentFile(b)))
        assertEquals(2, r.removedEntries)
        assertEquals(1, r.keptEntries, "认不出形状的必须留下（删读不懂的文件是猜）")
        assertTrue(Files.isRegularFile(weird))
        assertEquals(0, r.keepCount, "保留集为空 → 界面据此说「下次安装必须联网」")
    }

    @Test
    fun `路径段数不对的条目保留（不按半截路径去猜摘要）`() {
        val stray = cacheDir.resolve("_cacache/content-v2/stray.bin")
        Files.createDirectories(stray.parent)
        Files.write(stray, "junk".toByteArray())
        // 少一段（三段而非四段）—— 也认不出来
        val short = cacheDir.resolve("_cacache/content-v2/sha512/ab/cd")
        Files.createDirectories(short.parent)
        Files.write(short, "junk2".toByteArray())

        val r = reclaim(emptySet())

        assertEquals(0, r.removedEntries)
        assertEquals(2, r.keptEntries)
        assertTrue(Files.isRegularFile(stray) && Files.isRegularFile(short))
    }

    @Test
    fun `大写 hex 不认（cacache 只写小写，认错就是删错）`() {
        val upper = cacheDir.resolve("_cacache/content-v2/sha512/AB/CD/" + "E".repeat(124))
        Files.createDirectories(upper.parent)
        Files.write(upper, "x".toByteArray())

        val r = reclaim(emptySet())

        assertEquals(0, r.removedEntries)
        assertEquals(1, r.keptEntries)
    }

    @Test
    fun `缓存目录不存在 → 空账不抛（没缓存也是一种正常状态）`() {
        val r = reclaim(setOf("sha512-whatever"))
        assertEquals(0, r.removedEntries)
        assertEquals(0, r.keptEntries)
        assertFalse(r.indexRebuilt)
    }

    @Test
    fun `删空的 hash 目录被收掉（否则「缓存有多大」按目录数会骗人）`() {
        val orphan = seedContent("orphan")
        val parent = contentFile(orphan).parent

        reclaim(emptySet())

        assertFalse(Files.exists(parent), "删空的三段 hash 目录必须收掉：$parent")
        assertTrue(Files.isDirectory(cacheDir.resolve("_cacache/content-v2")), "根目录本身留着（下次播种直接用）")
    }

    // ═══ index 修复（在线安装的隐形杀手）═══

    @Test
    fun `悬空 index 行被摘掉，指向存活 content 的行原样留着`() {
        val alive = seedContent("alive")
        val gone = seedContent("gone")
        val bucket = writeBucket(
            "b1",
            alive to "make-fetch-happen:request-cache:https://registry.example/alive",
            gone to "make-fetch-happen:request-cache:https://registry.example/gone",
        )

        // 先制造悬空：content 直接抹掉，index 行留着（= 系统清缓存/上次崩在半路的形态）
        Files.delete(contentFile(gone))

        val r = reclaim(setOf(alive))

        assertTrue(r.indexRebuilt, "有悬空引用就必须改写 index")
        val text = String(Files.readAllBytes(bucket), Charsets.UTF_8)
        assertTrue(text.contains("registry.example/alive"), "指向存活 content 的行必须留着")
        assertFalse(text.contains("registry.example/gone"), "悬空行必须摘掉：$text")
    }

    @Test
    fun `index 全干净 → 一个字节都不改写（不重建桶树）`() {
        val alive = seedContent("alive")
        val bucket = writeBucket("b2", alive to "key-alive")
        val before = Files.readAllBytes(bucket)

        val r = reclaim(setOf(alive))

        assertFalse(r.indexRebuilt)
        assertTrue(before.contentEquals(Files.readAllBytes(bucket)), "干净缓存不该被重写")
    }

    @Test
    fun `integrity 为 null 的删除标记行保留（那不是悬空引用）`() {
        val alive = seedContent("alive")
        val bucket = writeBucket("b3", alive to "key-alive", null to "key-tombstone")

        val r = reclaim(setOf(alive))

        assertFalse(r.indexRebuilt, "删除标记不是悬空引用，不该触发改写")
        val text = String(Files.readAllBytes(bucket), Charsets.UTF_8)
        assertTrue(text.contains("key-tombstone"), "删除标记行必须留着：$text")
    }

    @Test
    fun `整桶都是悬空行 → 桶文件删掉（不留空壳）`() {
        val gone = seedContent("gone")
        val bucket = writeBucket("b4", gone to "key-gone")
        Files.delete(contentFile(gone))

        val r = reclaim(emptySet())

        assertTrue(r.indexRebuilt)
        assertFalse(Files.exists(bucket), "全悬空的桶留着只会让下次 find 多读一次空文件")
    }

    @Test
    fun `没有 index-v5 → 不炸也不谎报改写过`() {
        val orphan = seedContent("orphan")
        val r = reclaim(emptySet())
        assertEquals(1, r.removedEntries)
        assertFalse(r.indexRebuilt)
        assertFalse(Files.exists(contentFile(orphan)))
    }

    @Test
    fun `回收后 index 里不留指向本次删掉 content 的行（两件事必须一起做）`() {
        // 这条是整卷的痛点：只删 content 不修 index → 在线 npm install 报
        // ENOENT … Invalid response body while trying to fetch（实测 npm 10.9.8）
        val kept = seedContent("kept")
        val doomed = seedContent("doomed")
        val bucket = writeBucket("b5", kept to "key-kept", doomed to "key-doomed")

        val r = reclaim(setOf(kept))

        assertTrue(r.indexRebuilt)
        assertFalse(Files.exists(contentFile(doomed)))
        val text = String(Files.readAllBytes(bucket), Charsets.UTF_8)
        assertFalse(text.contains(doomed), "本次删掉的条目，index 里也不许再有指向它的行")
        assertTrue(text.contains("key-kept"))
    }
}

/**
 * 缓存体积读数（§10.9 第 5 条的 `npm-cache` 尺寸栏，2026-10-09 批 87）。
 *
 * 断三件事，都对应实现里那条不肯让步的口径：
 *
 * - **只量 content-v2**：这个数字的用途是回答「回收缓存能腾出多少」，而回收动的正是
 *   content-v2。把 `index-v5`（几 KB 级索引）算进来，配额条上的数字就会与回收回执里
 *   的删/留对不上 —— 而那两个数字摆在同一个屏幕上；
 * - **目录不存在 = 0 字节，不是"没量到"**：缓存空着与量不到是两件事
 *   （与 `NodeModulesStats` 那条口径同源）；
 * - **`has` 与体积各管各的**：`CacheIndex` 的缺省 `contentBytes()` 是给替身用的，
 *   真实现必须真遍历 —— 否则依赖面板上那栏永远显示 0。
 */
class CacacheIndexBytesTest {

    @TempDir
    lateinit var dir: Path

    private val cacheDir get() = dir.resolve("cache")

    private fun seedContent(payload: String): String {
        val bytes = payload.toByteArray()
        val integrity = "sha512-" + Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-512").digest(bytes),
        )
        val p = NpmCacheSeedDeployer.contentPath(cacheDir, integrity)
        Files.createDirectories(p.parent)
        Files.write(p, bytes)
        return integrity
    }

    @Test
    fun `缓存目录还不存在 → 0 字节（是"空"不是"没量到"）`() {
        assertEquals(0L, CacacheIndex(cacheDir).contentBytes())
    }

    @Test
    fun `量的是 content-v2 的实际字节，且 index-v5 不计入`() {
        seedContent("a".repeat(1000))
        seedContent("b".repeat(250))
        // index-v5 是几 KB 级的索引，不进这个数字（回收不动它）
        val bucket = cacheDir.resolve("_cacache/index-v5/aa/bb/bucket")
        Files.createDirectories(bucket.parent)
        Files.write(bucket, ByteArray(4096))

        assertEquals(1250L, CacacheIndex(cacheDir).contentBytes())
    }

    @Test
    fun `同一份内容被删掉之后如实变小（数字随盘走，不是算出来的）`() {
        val integrity = seedContent("x".repeat(700))
        val index = CacacheIndex(cacheDir)
        assertEquals(700L, index.contentBytes())

        Files.delete(NpmCacheSeedDeployer.contentPath(cacheDir, integrity))
        assertEquals(0L, index.contentBytes())
    }

    @Test
    fun `缺省 contentBytes 是 0 —— 替身不必写"我量不到体积"`() {
        val fake = object : CacheIndex {
            override fun has(integrity: String) = true
        }
        assertEquals(0L, fake.contentBytes())
    }
}
