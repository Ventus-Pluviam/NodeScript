package com.autoscript.appservice.packager.npm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 离线 bundle 导入单测（§10.2 §10.9 UX 4）：
 * 合入 content-v2 → 离线 ci 命中；冒名条目拒收；zip slip 先拒再谈信任。
 */
class NpmOfflineBundleImporterTest {

    @TempDir
    lateinit var dir: Path

    private val cacheDir get() = dir.resolve("cache")

    private fun digest(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-512").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    /** 造一个内含 cacache content-v2 条目的 bundle。 */
    private fun bundle(vararg payloads: ByteArray, tamper: Boolean = false, extraEntry: Pair<String, ByteArray>? = null): Path {
        val zip = dir.resolve("bundle-${System.nanoTime()}.zip")
        ZipOutputStream(Files.newOutputStream(zip)).use { z ->
            for (p in payloads) {
                val hex = digest(p)
                val name = "_cacache/content-v2/sha512/${hex.substring(0, 2)}/${hex.substring(2, 4)}/${hex.substring(4)}"
                z.putNextEntry(ZipEntry(name))
                z.write(if (tamper) p + "x".toByteArray() else p)
                z.closeEntry()
            }
            extraEntry?.let { (name, data) ->
                z.putNextEntry(ZipEntry(name)); z.write(data); z.closeEntry()
            }
        }
        return zip
    }

    @Test
    fun `合入 content-v2 后离线 ci 可命中`() {
        val payload = "tarball-bytes-1".toByteArray()
        val zip = bundle(payload)
        val r = NpmOfflineBundleImporter.import(zip, cacheDir)
        assertEquals(1, r.imported)
        assertTrue(r.clean, "干净 bundle 不该有拒绝项：${r.rejected}")
        val hex = digest(payload)
        val target = cacheDir.resolve("_cacache/content-v2/sha512/${hex.substring(0, 2)}/${hex.substring(2, 4)}/${hex.substring(4)}")
        assertTrue(Files.isRegularFile(target))
        assertTrue(payload.contentEquals(Files.readAllBytes(target)), "导入的必须原字节，不重编码")
    }

    @Test
    fun `幂等：二次导入零重写`() {
        val payload = "tarball-bytes-2".toByteArray()
        val zip = bundle(payload)
        NpmOfflineBundleImporter.import(zip, cacheDir)
        val r = NpmOfflineBundleImporter.import(zip, cacheDir)
        assertEquals(0, r.imported)
        assertEquals(1, r.skipped, "内容寻址幂等：已就位跳过")
    }

    @Test
    fun `冒名条目（内容与路径声明不符）拒收，不进缓存`() {
        val payload = "honest".toByteArray()
        val zip = bundle(payload, tamper = true)
        val r = NpmOfflineBundleImporter.import(zip, cacheDir)
        assertEquals(0, r.imported, "冒名条目绝不可进 cache")
        assertEquals(1, r.rejected.size)
        assertFalse(r.clean)
        // 缓存里一条都不该有
        val idx = CacacheIndex(cacheDir)
        assertEquals(0, idx.contentCount(), "拒收必须彻底：缓存零条目")
    }

    @Test
    fun `zip slip 路径段先拒（cacheDir 之外一字节不写）`() {
        val payload = "x".toByteArray()
        val hex = digest(payload)
        val evil = "../escape-${System.nanoTime()}.bin"
        val zip = bundle(payload, extraEntry = evil to "pwned".toByteArray())
        val r = NpmOfflineBundleImporter.import(zip, cacheDir)
        assertTrue(r.rejected.any { it.contains("..") }, "逃逸条目必须被点名拒绝：${r.rejected}")
        assertEquals(1, r.imported, "合法条目照常导入（不被一个坏条目带崩整批）")
        val escaped = dir.parent.resolve(evil.substring(3))
        assertFalse(Files.exists(escaped), "绝不允许写到 bundle 之外的路径：$escaped")
    }

    @Test
    fun `非 content-v2 条目忽略（index-v5 不导入）`() {
        val payload = "y".toByteArray()
        val zip = bundle(payload, extraEntry = "_cacache/index-v5/de/ad/beef" to "index-entry".toByteArray())
        val r = NpmOfflineBundleImporter.import(zip, cacheDir)
        assertEquals(1, r.imported)
        assertTrue(r.rejected.isEmpty(), "index 条目不是「拒绝」，是不适用：${r.rejected}")
    }

    @Test
    fun `不存在的 bundle → 如实失败`() {
        assertThrows(IllegalArgumentException::class.java) {
            NpmOfflineBundleImporter.import(dir.resolve("nope.zip"), cacheDir)
        }
    }

    @Test
    fun `导入后 CacacheIndex 看得见（bundle → 离线体检闭环）`() {
        val payload = "tarball-bytes-3".toByteArray()
        val integ = "sha512-" + java.util.Base64.getEncoder().encodeToString(
            java.security.MessageDigest.getInstance("SHA-512").digest(payload),
        )
        NpmOfflineBundleImporter.import(bundle(payload), cacheDir)
        val idx = CacacheIndex(cacheDir)
        assertTrue(idx.has(integ), "导入的条目必须被离线体检看见")
        assertEquals(1f, idx.coverage(listOf(integ)))
    }
}
