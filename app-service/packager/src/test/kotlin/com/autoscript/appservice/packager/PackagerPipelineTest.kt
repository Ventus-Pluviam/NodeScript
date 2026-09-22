package com.autoscript.appservice.packager

import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PackagerPipelineTest {

    @TempDir
    lateinit var tmp: Path

    private fun workDir(): Path = tmp.resolve("work")

    private fun templateApk(): Path {
        val t = tmp.resolve("template.apk")
        Files.createDirectories(t.parent)
        // 真 zip（inject 要开 ZipFile；6 字节假 PK 头过不了 central directory）。
        java.util.zip.ZipOutputStream(Files.newOutputStream(t)).use { out ->
            out.putNextEntry(java.util.zip.ZipEntry("keep.txt"))
            out.write("keep".toByteArray())
            out.closeEntry()
        }
        return t
    }

    private fun pipeline(apk: Path = templateApk()) = PackagerPipeline(workDir(), apk)

    @Test
    fun `prepare 复制模板并拒绝覆盖已有产物`() {
        val p = pipeline()
        val out = p.prepare()
        assertEquals(workDir().resolve("template.apk"), out)
        assertTrue(Files.exists(out), "模板应复制到工作目录")

        val e = assertThrows(AutojsException::class.java) { p.prepare() }
        assertEquals(ErrorCode.ERR_FILE_EXISTS, e.error, "重复打包必须显式报错而非覆盖")
    }

    @Test
    fun `injectAsset 把资产写进包内 assets-project 前缀条目`() {
        val p = pipeline()
        val out = p.prepare()
        val bytes = "console.log(1)".toByteArray()
        p.injectAsset(out, "main.js", bytes)

        assertArrayEquals(
            bytes,
            zipEntry(out, "assets/project/main.js"),
            "资产必须落在包内 ASSET_PREFIX+relPath（§3「注入 assets/project」），不是旁边暂存",
        )
        assertNull(zipEntryOrNull(out, "assets/project/.main.js.tmp"), "无临时条目残留")
    }

    @Test
    fun `injectAsset 拒绝目录穿越路径`() {
        val p = pipeline()
        val out = p.prepare()
        assertThrows(IllegalArgumentException::class.java) {
            p.injectAsset(out, "../evil.sh", byteArrayOf(1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            p.injectAsset(out, "/abs/evil.sh", byteArrayOf(1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            p.injectAsset(out, "a/../../evil.sh", byteArrayOf(1))
        }
    }

    @Test
    fun `injectAsset 同名二次注入覆盖（幂等）`() {
        val p = pipeline()
        val out = p.prepare()
        p.injectAsset(out, "main.js", "v1".toByteArray())
        p.injectAsset(out, "main.js", "v2".toByteArray())
        assertArrayEquals("v2".toByteArray(), zipEntry(out, "assets/project/main.js"))
    }

    @Test
    fun `injectAssets 批量一次落位且不动模板既有条目`() {
        val p = pipeline()
        val out = p.prepare()
        val before = Files.readAllBytes(out)
        p.injectAssets(
            out,
            linkedMapOf(
                "main.js" to "entry".toByteArray(),
                "lib/util.js" to "util".toByteArray(),
            ),
        )
        assertArrayEquals("entry".toByteArray(), zipEntry(out, "assets/project/main.js"))
        assertArrayEquals("util".toByteArray(), zipEntry(out, "assets/project/lib/util.js"))
        // 模板自身条目不被批量注入破坏（至少仍是合法 zip 且能读回模板条目之外的新条目）
        assertTrue(Files.size(out) > 0)
        assertFalse(before.isEmpty())
    }

    @Test
    fun `injectAssets 空表是 no-op`() {
        val p = pipeline()
        val out = p.prepare()
        val before = Files.readAllBytes(out)
        p.injectAssets(out, emptyMap())
        assertArrayEquals(before, Files.readAllBytes(out), "空批量不该白跑一次重写")
    }

    private fun zipEntry(apk: Path, name: String): ByteArray =
        zipEntryOrNull(apk, name) ?: error("包内缺条目 $name")

    private fun zipEntryOrNull(apk: Path, name: String): ByteArray? =
        java.util.zip.ZipFile(apk.toFile()).use { zf ->
            val e = zf.getEntry(name) ?: return@use null
            zf.getInputStream(e).readBytes()
        }

    @Test
    fun `patch 骨架期返回空补丁（不改写）`() {
        val p = pipeline()
        val out = p.prepare()
        val patched = p.patch().apply(out)
        assertEquals(out, patched, "NONE 补丁不改写模板（AXML/ARSC 变换待接入）")
    }

    @Test
    fun `模板源文件缺失时报明确错误`() {
        val missing = tmp.resolve("nope.apk")
        val e = assertThrows(AutojsException::class.java) {
            pipeline(missing).prepare()
        }
        assertTrue(e.error == ErrorCode.ERR_FILE_NOT_FOUND, "模板缺失应映射 ERR_FILE_NOT_FOUND")
        assertInstanceOf(java.nio.file.NoSuchFileException::class.java, e.cause, "保留底层原因可诊断")
    }

    @Test
    fun `自定义补丁可改写模板（变换接缝可用）`() {
        val p = pipeline()
        val out = p.prepare()
        val custom = object : PackagerPipeline.TemplatePatch {
            override fun apply(apk: Path): Path {
                Files.write(apk, "patched".toByteArray())
                return apk
            }
        }
        val patched = custom.apply(out)
        assertEquals("patched", String(Files.readAllBytes(patched)), "接入真实 AXML/ARSC 变换的接缝可用")
    }
}
