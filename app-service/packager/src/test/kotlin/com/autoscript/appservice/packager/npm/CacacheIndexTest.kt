package com.autoscript.appservice.packager.npm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * CacacheIndex 单测（§10.2 §10.6 轻操作底座）：
 * 播进来的种子必须被 `has` 看见（播查同源），没播的如实报缺口。
 */
class CacacheIndexTest {

    @TempDir
    lateinit var dir: Path

    private val cacheDir get() = dir.resolve("cache")

    private fun integrityOf(payload: String): String {
        val bytes = payload.toByteArray()
        return "sha512-" + java.util.Base64.getEncoder()
            .encodeToString(java.security.MessageDigest.getInstance("SHA-512").digest(bytes))
    }

    private fun seed(vararg payloads: String): List<String> {
        val src = Files.createDirectories(dir.resolve("seed"))
        val out = mutableListOf<String>()
        for (p in payloads) {
            val bytes = p.toByteArray()
            Files.write(src.resolve("$p.tgz"), bytes)
            Files.write(src.resolve("$p.tgz.sha512"), (integrityOf(p) + "\n").toByteArray())
            out += integrityOf(p)
        }
        NpmCacheSeedDeployer.deploy(cacheDir, object : NpmCacheSeedDeployer.SeedSource {
            override fun list(): List<String> =
                if (Files.isDirectory(src)) {
                    Files.walk(src).use { s ->
                        s.filter { Files.isRegularFile(it) }
                            .map { src.relativize(it).toString().replace('\\', '/') }
                            .toList()
                    }
                } else emptyList()

            override fun read(relPath: String): ByteArray? {
                val f = src.resolve(relPath)
                return if (Files.isRegularFile(f)) Files.readAllBytes(f) else null
            }
        })
        return out
    }

    @Test
    fun `播进来的种子被 has 看见（播查同源）`() {
        val (a, b) = seed("pkg-a", "pkg-b")
        val idx = CacacheIndex(cacheDir)
        assertTrue(idx.has(a))
        assertTrue(idx.has(b), "第二个条目同样要能被查到")
        assertFalse(idx.has(integrityOf("never-seeded")))
        assertEquals(2, idx.contentCount())
        assertTrue(idx.contentBytes() > 0, "content 字节必须如实累计")
    }

    @Test
    fun `残缺 integrity 一律 false（不可校验 = 不可信）`() {
        seed("pkg-a")
        val idx = CacacheIndex(cacheDir)
        for (bad in listOf("", "   ", "sha512", "sha512-notbase64!!", "sha1-AAAA", "nonsense")) {
            assertFalse(idx.has(bad), "畸形 integrity 不得算命中：$bad")
        }
    }

    @Test
    fun `空缓存：闭包全缺口，coverage 诚实为 0`() {
        val idx = CacacheIndex(cacheDir)
        val integs = listOf(integrityOf("x"), integrityOf("y"))
        assertEquals(integs, idx.missing(integrities = integs), "空缓存下闭包里每个包都是缺口")
        assertEquals(0f, idx.coverage(integrities = integs))
        assertEquals(1f, idx.coverage(emptyList()), "空闭包 = 离线可装（也确实不用装）")
    }

    @Test
    fun `全命中时缺口为空、coverage 为 1（离线装肚子里有数）`() {
        val (a, b) = seed("pkg-a", "pkg-b")
        val idx = CacacheIndex(cacheDir)
        val integs = listOf(a, b)
        assertTrue(idx.missing(integrities = integs).isEmpty())
        assertEquals(1f, idx.coverage(integrities = integs))
    }

    @Test
    fun `部分命中：missing 指名缺的那几个`() {
        val a = seed("pkg-a").single()
        val gone = integrityOf("pkg-z")
        val idx = CacacheIndex(cacheDir)
        assertEquals(listOf(gone), idx.missing(integrities = listOf(a, gone, null)))
        assertEquals(0.5f, idx.coverage(integrities = listOf(a, gone)))
    }

    @Test
    fun `系统清掉 cacheDir 后如实报缺口（不假装有缓存）`() {
        val a = seed("pkg-a").single()
        val idx = CacacheIndex(cacheDir)
        assertTrue(idx.has(a))
        cacheDir.toFile().deleteRecursively()
        assertFalse(idx.has(a), "cacheDir 可被系统清理，清理后必须如实报缺口")
        assertEquals(0, idx.contentCount())
        assertEquals(0L, idx.contentBytes())
    }
}
