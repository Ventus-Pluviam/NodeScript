package com.autoscript.appservice.packager

import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
        Files.write(t, byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x0A, 0x00))  // PK\x03\x04
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
    fun `injectAsset 注入资产并原子就位`() {
        val p = pipeline()
        val out = p.prepare()
        val bytes = "project/main.js".toByteArray()
        p.injectAsset(out, "project/main.js", bytes)

        val target = workDir().resolve("unpacked/project/main.js")
        assertArrayEquals(bytes, Files.readAllBytes(target), "资产字节应原样就位")
        assertFalse(workDir().resolve("unpacked/project/.main.js.tmp").toFile().exists(), "无临时文件残留")
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
    fun `injectAsset 替换已存在资产（幂等）`() {
        val p = pipeline()
        val out = p.prepare()
        p.injectAsset(out, "project/main.js", "v1".toByteArray())
        p.injectAsset(out, "project/main.js", "v2".toByteArray())
        assertArrayEquals(
            "v2".toByteArray(),
            Files.readAllBytes(workDir().resolve("unpacked/project/main.js")),
        )
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
