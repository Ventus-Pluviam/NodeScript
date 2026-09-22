package com.autoscript.appservice.packager

import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * `zipalign` 起进程的闭环：argv 形态 → 退出码 → 产物存在性。
 * 与 [ApkSignerRunnerTest] 同一配方：记录型 launcher 钉 argv，假可执行体走真进程，
 * 不依赖本机/CI 是否装了 zipalign。
 */
class ZipAlignRunnerTest {

    @TempDir
    lateinit var tmp: Path

    /** 记录型：把输入抄成输出（冒充对齐产物），argv 原样留证。 */
    private fun recording(calls: MutableList<List<String>>) = ProcessLauncher { cmd, _ ->
        calls += cmd
        Files.copy(
            Path.of(cmd[cmd.size - 2]),
            Path.of(cmd[cmd.size - 1]),
            StandardCopyOption.REPLACE_EXISTING,
        )
        ProcessResult(0, "")
    }

    @Test
    fun `argv 与 Usage 行逐字一致——f p 4 输入 输出`() {
        val calls = mutableListOf<List<String>>()
        val input = Files.write(tmp.resolve("u.apk"), byteArrayOf(1, 2, 3))
        val output = tmp.resolve("a.apk")

        ZipAlignRunner(listOf("/tools/zipalign"), recording(calls)).align(input, output)

        assertEquals(1, calls.size, "应恰好起一次进程")
        assertEquals(
            listOf("/tools/zipalign", "-f", "-p", "4", input.toString(), output.toString()),
            calls[0],
            "输出是位置参数（无 --out）；-p 页对齐 .so；align 缺省 4",
        )
        assertTrue(Files.isRegularFile(output), "对齐产物必须存在")
        assertArrayEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(output))
    }

    @Test
    fun `自定义对齐字节数进 argv`() {
        val calls = mutableListOf<List<String>>()
        val input = Files.write(tmp.resolve("u.apk"), byteArrayOf(1))
        ZipAlignRunner(listOf("zipalign"), recording(calls), alignment = 16)
            .align(input, tmp.resolve("a.apk"))
        assertEquals(listOf("zipalign", "-f", "-p", "16", input.toString(), tmp.resolve("a.apk").toString()), calls[0])
    }

    @Test
    fun `非 0 退出码如实失败并带上 zipalign 输出`() {
        val launcher = ProcessLauncher { _, _ -> ProcessResult(1, "invalid file`)") }
        val input = Files.write(tmp.resolve("u.apk"), byteArrayOf(1))
        val e = runCatching {
            ZipAlignRunner(listOf("zipalign"), launcher).align(input, tmp.resolve("a.apk"))
        }.exceptionOrNull()
        assertInstanceOf(AutojsException::class.java, e)
        e as AutojsException
        assertEquals(ErrorCode.ERR_IO, e.error)
        assertTrue("invalid file" in e.message!!, "失败详情必须带 zipalign 原文")
        assertTrue("1" in e.message!!, "退出码要进错误信息")
    }

    @Test
    fun `报成功但没产出文件也算失败`() {
        val launcher = ProcessLauncher { _, _ -> ProcessResult(0, "ok") }
        val input = Files.write(tmp.resolve("u.apk"), byteArrayOf(1))
        val e = runCatching {
            ZipAlignRunner(listOf("zipalign"), launcher).align(input, tmp.resolve("missing.apk"))
        }.exceptionOrNull()
        assertInstanceOf(AutojsException::class.java, e)
        assertTrue("没产出对齐包" in (e as AutojsException).message!!)
    }

    @Test
    fun `空命令前缀与非正对齐数在构造期就拒绝`() {
        assertThrowsIllegal { ZipAlignRunner(emptyList()) }
        assertThrowsIllegal { ZipAlignRunner(listOf("zipalign"), alignment = 0) }
    }

    @Test
    fun `真起进程——假可执行体按位置参数抄文件`() {
        val script = tmp.resolve("fake-zipalign.sh")
        Files.writeString(
            script,
            """
            #!/bin/sh
            # 形态：zipalign -f -p <align> <in> <out>。
            # shift 掉前三个参数，$1=in、$2=out（$ 后跟数字不是 Kotlin 字符串模板）。
            shift 3
            cp "$1" "$2"
            exit 0
            """.trimIndent(),
        )
        script.toFile().setExecutable(true)

        val input = Files.write(tmp.resolve("u.apk"), "bytes".toByteArray())
        val output = tmp.resolve("a.apk")
        ZipAlignRunner(listOf(script.toString()), ProcessBuilderLauncher()).align(input, output)

        assertEquals("bytes", Files.readString(output), "假体把输入原样抄成输出 = 全链路真跑通")
    }

    private fun assertThrowsIllegal(block: () -> Unit) {
        val e = runCatching(block).exceptionOrNull()
        assertInstanceOf(IllegalArgumentException::class.java, e)
    }
}
