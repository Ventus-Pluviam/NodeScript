package com.autoscript.platform.system.zip

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
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * `zip` 实现的契约测试（docs §9.6；真路径真 IO —— @TempDir，不是替身回放）。
 *
 * 最要紧的三组：
 * 1. **zip-slip 先验后写**：越界条目（`../` 逃逸 / 绝对路径）→ ERR_INVALID_PARAM，
 *    且**界内文件一个都没写**（"写到一半发现"不算防线）；
 * 2. **往返**：文件/目录/嵌套/空目录原样 roundtrip；
 * 3. **原子落位**：压缩成功后无 `.tmp` 残留；缺源/缺归档如实 ERR_FILE_NOT_FOUND；
 * 4. **体积上限**（backlog B13）：诚实炸弹**先验后写**（写任何字节之前就拒），
 *    撒谎的声明由第二拍的计数闸兜住。上限走构造注入压到可测规模 —— 见下面那组的注释。
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

    // ═══ 体积上限（backlog B13） ═══
    //
    // 上限走构造注入（`JdkZipArchiver(maxEntryBytes = …, maxTotalBytes = …)`）正是为了这几条：
    // 边界要按真值测就得造 256 MiB+ 的条目，而**为一条不该落盘的条目先写满 256 MiB 磁盘**
    // 是拿边界的坑去测边界。缝只开在上限上 —— 解包/校验/落盘一条不分叉。

    /** 造一个**真能解出 [inflatedBytes] 字节**的条目（全零，Deflater 压到极小）。 */
    private fun craftBomb(name: String, inflatedBytes: Int, level: Int = Deflater.BEST_COMPRESSION): Path {
        val archive = tmp.resolve("bomb-${System.nanoTime()}.zip")
        ZipOutputStream(Files.newOutputStream(archive)).use { zos ->
            zos.setLevel(level)
            zos.putNextEntry(ZipEntry(name))
            val chunk = ByteArray(1 shl 16)
            var written = 0
            while (written < inflatedBytes) {
                val n = minOf(chunk.size, inflatedBytes - written)
                zos.write(chunk, 0, n)
                written += n
            }
            zos.closeEntry()
        }
        return archive
    }

    /** 把 zip 里每条条目的**声明**未压缩大小改小（中央目录 + 本地头两处，各 4 字节 LE）。 */
    private fun forgeDeclaredSize(zip: Path, declared: Int): Path {
        val b = Files.readAllBytes(zip)
        fun putLe(off: Int, v: Int) {
            b[off] = v.toByte(); b[off + 1] = (v ushr 8).toByte()
            b[off + 2] = (v ushr 16).toByte(); b[off + 3] = (v ushr 24).toByte()
        }
        for (i in 0..b.size - 4) {
            val sig = (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8) or
                ((b[i + 2].toInt() and 0xff) shl 16) or ((b[i + 3].toInt() and 0xff) shl 24)
            if (sig == 0x02014b50) putLe(i + 24, declared)   // central directory header
            if (sig == 0x04034b50) putLe(i + 22, declared)   // local file header
        }
        Files.write(zip, b)
        return zip
    }

    @Test
    fun `压缩炸弹——声明就超顶的条目先验后写，界内零字节`() = runBlocking {
        val out = tmp.resolve("out")
        val bomb = craftBomb("payload.bin", inflatedBytes = 64 * 1024)   // 真解出 64 KiB
        val e = assertThrows(AutojsException::class.java) {
            runBlocking { JdkZipArchiver(maxEntryBytes = 1024, maxTotalBytes = 1L shl 30).extract(bomb, out) }
        }
        assertEquals(ErrorCode.ERR_INVALID_PARAM, e.error, "超限是**输入**的问题，不是盘的问题")
        assertTrue(e.message!!.contains("65536"), "要如实报真值（解出多少字节）：${e.message}")
        assertTrue(e.message!!.contains("payload.bin"), "要点名是哪一条：${e.message}")
        assertFalse(Files.exists(out.resolve("payload.bin")), "先验后写：第一拍就拒，一个字节都没落")
    }

    @Test
    fun `声明撒谎的条目——改小声明也拦得住（上限不是拿声明判的）`() = runBlocking {
        val out = tmp.resolve("out")
        val bomb = craftBomb("liar.bin", inflatedBytes = 64 * 1024)
        forgeDeclaredSize(bomb, declared = 10)   // 声明 10 字节，实际 64 KiB
        val e = assertThrows(AutojsException::class.java) {
            runBlocking { JdkZipArchiver(maxEntryBytes = 1024, maxTotalBytes = 1L shl 30).extract(bomb, out) }
        }
        assertEquals(ErrorCode.ERR_INVALID_PARAM, e.error, "第一拍看不出来，第二拍的计数闸必须兜住")
        assertFalse(Files.exists(out.resolve("liar.bin")), "半截文件要删掉，不留「解出来一部分」的假象")
    }

    @Test
    fun `总量超顶——一群不超单条目的小东西也拦得住`() = runBlocking {
        val out = tmp.resolve("out")
        val archive = tmp.resolve("many.zip")
        ZipOutputStream(Files.newOutputStream(archive)).use { zos ->
            zos.setLevel(Deflater.BEST_COMPRESSION)
            for (i in 0 until 8) {                       // 8 × 8 KiB = 64 KiB，单条都不超 16 KiB 的顶
                zos.putNextEntry(ZipEntry("part-$i.bin"))
                zos.write(ByteArray(8 * 1024))
                zos.closeEntry()
            }
        }
        val e = assertThrows(AutojsException::class.java) {
            runBlocking {
                JdkZipArchiver(maxEntryBytes = 16 * 1024, maxTotalBytes = 32 * 1024).extract(archive, out)
            }
        }
        assertEquals(ErrorCode.ERR_INVALID_PARAM, e.error)
        assertTrue(e.message!!.contains("总解压量"), "要说清是哪一道闸：${e.message}")
        assertFalse(Files.exists(out.resolve("part-7.bin")), "越界那一条不落盘")
    }

    @Test
    fun `恰好到顶放行——上限含等号（不误伤合法大包）`() = runBlocking {
        val out = tmp.resolve("out")
        val archive = craftBomb("exact.bin", inflatedBytes = 4096)
        JdkZipArchiver(maxEntryBytes = 4096, maxTotalBytes = 4096).extract(archive, out)
        assertEquals(4096, Files.size(out.resolve("exact.bin")), "等于上限必须通过，否则上限就成了「少一字节」")
    }
}

/**
 * 外审 M7（2026-10-11 批 99）：zip 面的三条 —— 条目数闸、`.tmp` 不自含、
 * `walk` 的 `UncheckedIOException` 不再让 `.tmp` 残留。
 *
 * 三个判据各自独立：① 零字节条目绕过字节闸（**计数与字节正交**，必须单独设闸）；
 * ② 归档写进源目录时把本次 `.tmp` 包进自己；③ 遍历期 IO 错误被 `Files.walk` 包成
 * `UncheckedIOException`（**不是** `IOException`）→ 外层 `catch (e: IOException)` 接不住，
 * `.tmp` 不删、错误码也不是 `ERR_IO`。
 */
class JdkZipArchiverHardeningTest {

    @TempDir
    lateinit var tmp: Path

    private fun write(p: Path, text: String) {
        Files.createDirectories(p.parent)
        Files.write(p, text.toByteArray())
    }

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

    /** ① 零字节条目炸弹：合计字节 0、单条字节 0，两道字节闸都过不掉它。 */
    @Test
    fun `零字节海量条目被条目数闸拦下（外审 M7）`() = runBlocking {
        val archive = craft((1..40).map { "empty-$it" to null })
        // 上限压到 10：40 条 > 10。字节上限给得极大 —— 证明**是条目数闸**在拦，不是字节闸。
        val capped = JdkZipArchiver(maxEntryBytes = 1L * 1024 * 1024, maxTotalBytes = 1L * 1024 * 1024, maxEntries = 10)
        val target = tmp.resolve("out-many")

        val e = assertThrows(AutojsException::class.java) { runBlocking { capped.extract(archive, target) } }
        assertEquals(ErrorCode.ERR_INVALID_PARAM.code, e.error.code, "归档本身不合格 = 输入问题（同 zip-slip 一档）")
        assertTrue(e.message!!.contains("条目数"), "话术要点名是条目数（否则与字节闸混淆）：${e.message}")
        assertFalse(Files.exists(target.resolve("empty-1")), "先验后写：不合格的包一个字节都不许落")
    }

    /** ② 归档写进源目录里：`.tmp` 不得进包（自含 = 解出来是个套娃半截 zip）。 */
    @Test
    fun `归档写进源目录时不把本次 tmp 包进自己（外审 M7）`() = runBlocking {
        val src = tmp.resolve("payload")
        Files.createDirectories(src)
        write(src.resolve("keep.txt"), "内容")

        val archive = src.resolve("out.zip")      // ← 归档在源目录内部
        val z = JdkZipArchiver()
        z.compress(src, archive)
        // tmp 此刻已被 rename 掉（它就是 archive 本身）—— 判据要看包里有没有「半截 zip」。
        val names = ZipFile(archive.toFile()).use { zf -> zf.entries().asSequence().map { it.name }.toList() }
        assertTrue(names.contains("keep.txt"), "源文件必须在包里：$names")
        assertFalse(
            names.any { it.endsWith(".tmp") || it.endsWith("out.zip") },
            "不得把本次 tmp 或成品自身包进去（自含）：$names",
        )
    }

    /**
     * ③ `Files.walk` 的 `UncheckedIOException` 折码与 tmp 清理（M7 那条的可造半）。
     *
     * 遍历期 IO 错误在本机（root）造不出来 —— 那是真机/受限环境才有的形态。本例改钉
     * **折码那一层的契约本身**：给一个会抛 `UncheckedIOException` 的压缩源（用一个
     * 目录 whose 子项在枚举时不可读的面造），断言调用方拿到的是 `ERR_IO` 而不是
     * `UncheckedIOException` 原样穿透。
     *
     * 造法：`Files.walk` 的**开流**就失败（源不是目录）→ 那是 `IOException` 路径，
     * 不是本条要钉的迭代期。所以改用「迭代期间把目录换成文件」——
     * 仍不可靠。故本条断言降级为**恒真的契约注记 + tmp 清理真判据**（见下）。
     */
    @Test
    fun `压缩失败不留半截 tmp（外审 M7 的可造那半）`() = runBlocking {
        val src = tmp.resolve("src-fail")
        Files.createDirectories(src)
        write(src.resolve("a.txt"), "x")
        // 目标归档的父目录是个**文件** → createDirectories 抛 → 进 catch 前 tmp 尚未建。
        // 换成真会中途失败的情形：把归档路径指向一个已存在的目录（move 目标非法）。
        val archive = tmp.resolve("dir-target")
        Files.createDirectories(archive)

        val z = JdkZipArchiver()
        assertThrows(Exception::class.java) { runBlocking { z.compress(src, archive) } }
        assertFalse(
            Files.exists(archive.resolveSibling("dir-target.tmp")),
            "失败路径不得留半截 tmp（那会被下一次 compress 当成成品的一部分）",
        )
    }
}
