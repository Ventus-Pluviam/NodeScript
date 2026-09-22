package com.autoscript.platform.system

import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import kotlinx.coroutines.runBlocking
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
 * `zip` 实现的契约测试（docs §9.6；真路径真 IO —— @TempDir，不是替身回放）。
 *
 * 最要紧的三组：
 * 1. **zip-slip 先验后写**：越界条目（`../` 逃逸 / 绝对路径）→ ERR_INVALID_PARAM，
 *    且**界内文件一个都没写**（"写到一半发现"不算防线）；
 * 2. **往返**：文件/目录/嵌套/空目录原样 roundtrip；
 * 3. **原子落位**：压缩成功后无 `.tmp` 残留；缺源/缺归档如实 ERR_FILE_NOT_FOUND。
 */
class JdkZipArchiverTest {

    @TempDir
    lateinit var tmp: Path

    private val zip = JdkZipArchiver()

    private fun write(p: Path, text: String) {
        Files.createDirectories(p.parent)
        Files.write(p, text.toByteArray())
    }

    /** 手搓含任意条目的 zip（造恶意包用；不走被测 compress）。 */
    private fun craft(entries: List<Pair<String, ByteArray?>>): Path {
        val archive = tmp.resolve("crafted.zip")
        ZipOutputStream(Files.newOutputStream(archive)).use { zos ->
            for ((name, body) in entries) {
                zos.putNextEntry(ZipEntry(name))
                if (body != null) zos.write(body)
                zos.closeEntry()
            }
        }
        return archive
    }

    @Test
    fun `往返——单文件压缩解压内容原样`() = runBlocking {
        val src = tmp.resolve("a.txt"); write(src, "hello zip")
        val archive = tmp.resolve("out.zip")
        zip.compress(src, archive)

        val target = tmp.resolve("extracted")
        zip.extract(archive, target)
        assertEquals("hello zip", String(Files.readAllBytes(target.resolve("a.txt"))), "文件名即条目名")
    }

    @Test
    fun `往返——目录递归嵌套与空目录都保留`() = runBlocking {
        val root = tmp.resolve("proj")
        write(root.resolve("main.js"), "console.log(1)")
        write(root.resolve("lib/util.js"), "module.exports = 1")
        Files.createDirectories(root.resolve("empty-dir"))
        val archive = tmp.resolve("proj.zip")
        zip.compress(root, archive)

        val target = tmp.resolve("out")
        zip.extract(archive, target)
        assertEquals("console.log(1)", String(Files.readAllBytes(target.resolve("main.js"))))
        assertEquals("module.exports = 1", String(Files.readAllBytes(target.resolve("lib/util.js"))))
        assertTrue(Files.isDirectory(target.resolve("empty-dir")), "空目录条目不得丢（目录条目显式落位）")
        assertFalse(Files.exists(target.resolve("proj")), "条目相对源根：解出来是内容不是再套一层 proj/")
    }

    @Test
    fun `压缩目标原子落位——成功后无 tmp 残留，重打包直接替换`() = runBlocking {
        val src = tmp.resolve("a.txt"); write(src, "v1")
        val archive = tmp.resolve("deep/nested/out.zip")
        zip.compress(src, archive)
        assertTrue(Files.exists(archive), "父目录自动创建")
        assertEquals(1, Files.list(tmp.resolve("deep/nested")).use { it.count() }, "只剩成品，无 .tmp")

        write(src, "v2")
        zip.compress(src, archive)                     // 已存在 → 替换，不报 ERR_FILE_EXISTS
        val target = tmp.resolve("x")
        zip.extract(archive, target)
        assertEquals("v2", String(Files.readAllBytes(target.resolve("a.txt"))))
    }

    @Test
    fun `缺源与缺归档如实 ERR_FILE_NOT_FOUND`() = runBlocking {
        val e1 = assertThrows(AutojsException::class.java) {
            runBlocking { zip.compress(tmp.resolve("nope.txt"), tmp.resolve("o.zip")) }
        }
        assertEquals(ErrorCode.ERR_FILE_NOT_FOUND, e1.error)

        val e2 = assertThrows(AutojsException::class.java) {
            runBlocking { zip.extract(tmp.resolve("nope.zip"), tmp.resolve("out")) }
        }
        assertEquals(ErrorCode.ERR_FILE_NOT_FOUND, e2.error)
    }

    @Test
    fun `zip-slip——点点逃逸条目整次拒绝且界内零写入`() = runBlocking {
        val archive = craft(
            listOf(
                "safe.txt" to "innocent".toByteArray(),
                "../escaped.txt" to "evil".toByteArray(),
            ),
        )
        val target = tmp.resolve("victim")
        val e = assertThrows(AutojsException::class.java) { runBlocking { zip.extract(archive, target) } }
        assertEquals(ErrorCode.ERR_INVALID_PARAM, e.error)
        assertTrue(e.message!!.contains("zip-slip"), "报错点名 zip-slip：现场可诊断")

        // 先验后写的全部意义在这里：safe.txt 排在越界条目前面，也**不许**已经落下去
        assertFalse(Files.exists(target.resolve("safe.txt")), "全包校验前零字节 —— 安全条目也不先写")
        assertFalse(Files.exists(tmp.resolve("escaped.txt")), "逃逸目标没有被创建")
    }

    @Test
    fun `zip-slip——绝对路径条目拒在目标根之外`() = runBlocking {
        val evilAbs = Path.of("/tmp/zip-slip-abs-evil.txt")
        Files.deleteIfExists(evilAbs)   // 前置自清：反证注入时本用例真会写出它，测完不靠运气
        val archive = craft(listOf(evilAbs.toString() to "evil".toByteArray()))
        val target = tmp.resolve("victim-abs")
        val e = assertThrows(AutojsException::class.java) { runBlocking { zip.extract(archive, target) } }
        assertEquals(ErrorCode.ERR_INVALID_PARAM, e.error)
        assertFalse(Files.exists(evilAbs), "绝对路径条目没有落到系统路径")
    }

    @Test
    fun `非法 zip 如实 ERR_IO——不装作解压成功`() = runBlocking {
        val garbage = tmp.resolve("garbage.zip")
        Files.write(garbage, "this is not a zip at all".toByteArray())
        val e = assertThrows(AutojsException::class.java) {
            runBlocking { zip.extract(garbage, tmp.resolve("out")) }
        }
        assertEquals(ErrorCode.ERR_IO, e.error)
    }
}
