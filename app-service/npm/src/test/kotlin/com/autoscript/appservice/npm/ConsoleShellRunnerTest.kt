package com.autoscript.appservice.npm

import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.host.ShellConsoleResult
import com.autoscript.domain.npm.NpmConsoleLine
import com.autoscript.domain.npm.NpmConsoleLineKind
import com.autoscript.domain.npm.ShellConsoleMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * 控制台 shell 面（[ConsoleShellRunner]）的行为钉死。
 *
 * 这些用例存在的原因是**这个类的话术就是用户看到的东西**：模式拒绝要落一行解释、
 * 非零退出不许折成异常、超时不许渲染半截输出。三条都只有在这里才验得到 ——
 * 真执行体是反射调 Shizuku 的设备面（JVM 上跑不了），而本类**正是**为了把那三条
 * 判据从设备面里摘出来才拆的。
 *
 * 用例体一律写成**块体**（`fun x() { … }`）而不是 `= runBlocking { … }`：表达式体
 * 若以非 Unit 结尾，JUnit 会静默跳过这条用例（本仓踩过，见 `kotlin-nontest-void-skip`）。
 */
class ConsoleShellRunnerTest {

    private val projectId = "main"

    private fun runner(executor: ShellOpExecutor, ring: SeqRing<NpmConsoleLine>) =
        ConsoleShellRunner(executor, ring, clock = { 1_000L })

    /** 环里的行（按 seq 升序），只取类别与正文 —— 断言读的是判读结果，不是时间戳。 */
    private fun lines(ring: SeqRing<NpmConsoleLine>): List<Pair<NpmConsoleLineKind, String>> =
        ring.drain(projectId, 0L, 64).third.map { it.second.kind to it.second.text }

    /**
     * 末行的 ok 判别。只读最后一行（RESULT）—— [NpmConsoleLine.ok] 在其余四类上恒 true
     * （成败由类别本身表达，见该字段的 KDoc），所以「整列的 ok」不是这条链的判据。
     */
    private fun lastOk(ring: SeqRing<NpmConsoleLine>): Boolean =
        ring.drain(projectId, 0L, 64).third.last().second.ok

    @Test
    fun `默认模式一律拒：落一行解释再抛，执行体零调用`() {
        var called = false
        val ring = SeqRing<NpmConsoleLine>(capacity = 64)
        val r = runner(ShellOpExecutor { _, _, _ -> called = true; ShellConsoleResult(0, null, null) }, ring)

        val e = assertThrows<AutojsException> {
            runBlocking { r.run(projectId, "ls -la", ShellConsoleMode.DEFAULT, 5_000L) }
        }
        assertEquals(ErrorCode.ERR_PERMISSION_DENIED, e.error)
        assertFalse(called, "拒绝路径不许碰执行体（否则就是「先跑了再说」）")
        val ls = lines(ring)
        assertEquals(1, ls.size, "拒绝要落一行，否则用户敲完只看到 ECHO 后面什么都没有")
        assertEquals(NpmConsoleLineKind.RESULT, ls.single().first)
        assertTrue(ls.single().second.contains("su"), "话术要点名怎么进特权模式：${ls.single().second}")
    }

    @Test
    fun `跑通：stdout 进 OUTPUT，退出码 0 进 RESULT 且 ok`() {
        val ring = SeqRing<NpmConsoleLine>(capacity = 64)
        val r = runner(
            ShellOpExecutor { cmd, mode, _ ->
                assertEquals("id", cmd)
                assertEquals(ShellConsoleMode.ROOT, mode)
                ShellConsoleResult(0, "uid=0(root) gid=0(root)", null)
            },
            ring,
        )
        val result = runBlocking { r.run(projectId, "id", ShellConsoleMode.ROOT, 5_000L) }

        assertTrue(result.isSuccess)
        assertEquals(
            listOf(
                NpmConsoleLineKind.OUTPUT to "uid=0(root) gid=0(root)",
                NpmConsoleLineKind.RESULT to "退出码 0",
            ),
            lines(ring),
        )
        assertTrue(lastOk(ring))
    }

    @Test
    fun `非零退出是结果不是异常：不抛，RESULT 行 ok=false`() {
        val ring = SeqRing<NpmConsoleLine>(capacity = 64)
        val r = runner(ShellOpExecutor { _, _, _ -> ShellConsoleResult(1, null, "no such file") }, ring)
        val result = runBlocking { r.run(projectId, "cat /nope", ShellConsoleMode.ADB, 5_000L) }

        assertFalse(result.isSuccess)
        assertEquals(1, result.code)
        assertEquals(
            listOf(
                NpmConsoleLineKind.WARNING to "no such file",
                NpmConsoleLineKind.RESULT to "退出码 1",
            ),
            lines(ring),
        )
        assertFalse(lastOk(ring))
    }

    @Test
    fun `空流不画行：null 与全空白都不冒出一行空输出`() {
        val ring = SeqRing<NpmConsoleLine>(capacity = 64)
        val r = runner(ShellOpExecutor { _, _, _ -> ShellConsoleResult(0, "   \n", "") }, ring)
        runBlocking { r.run(projectId, "true", ShellConsoleMode.ROOT, 5_000L) }

        assertEquals(listOf(NpmConsoleLineKind.RESULT to "退出码 0"), lines(ring))
    }

    @Test
    fun `截断如实标注：truncated 时 RESULT 行点名`() {
        val ring = SeqRing<NpmConsoleLine>(capacity = 64)
        val r = runner(ShellOpExecutor { _, _, _ -> ShellConsoleResult(0, "x", null, truncated = true) }, ring)
        runBlocking { r.run(projectId, "dmesg", ShellConsoleMode.ROOT, 5_000L) }

        assertTrue(lines(ring).last().second.contains("已截断"))
    }

    @Test
    fun `执行体抛错：原文进 RESULT 行，原异常上抛`() {
        val ring = SeqRing<NpmConsoleLine>(capacity = 64)
        val r = runner(
            ShellOpExecutor { _, _, _ ->
                throw AutojsException(ErrorCode.ERR_PERMISSION_DENIED, "Shizuku 服务未运行")
            },
            ring,
        )
        val e = assertThrows<AutojsException> {
            runBlocking { r.run(projectId, "id", ShellConsoleMode.ADB, 5_000L) }
        }
        // 抛回去的是原异常（`AutojsException` 的 message 带错误码前缀），环里那行才是
        // 给用户看的原文 —— 两者都断言：改一处不该让另一处静默漂。
        assertEquals(ErrorCode.ERR_PERMISSION_DENIED, e.error)
        assertTrue(e.message!!.contains("Shizuku 服务未运行"), "原异常上抛：${e.message}")
        // 行文是「失败：」+ 异常原文 —— 异常原文本身带错误码前缀（`AutojsException` 的
        // message 口径），故只钉前缀与真因都在，不逐字钉整句（措辞属平台层，不是本类）。
        val line = lines(ring).single()
        assertEquals(NpmConsoleLineKind.RESULT, line.first)
        assertTrue(line.second.startsWith("失败："), line.second)
        assertTrue(line.second.contains("Shizuku 服务未运行"), line.second)
    }

    @Test
    fun `超时：ERR_TIMEOUT，且不渲染半截输出`() {
        val ring = SeqRing<NpmConsoleLine>(capacity = 64)
        val r = runner(
            ShellOpExecutor { _, _, _ ->
                delay(10_000L)   // 远超下面的 TTL；合作式取消（delay 可中断）
                ShellConsoleResult(0, "不该出现", null)
            },
            ring,
        )
        val e = assertThrows<AutojsException> {
            runBlocking { r.run(projectId, "sleep 999", ShellConsoleMode.ROOT, 20L) }
        }
        assertEquals(ErrorCode.ERR_TIMEOUT, e.error)
        assertEquals(
            emptyList<Pair<NpmConsoleLineKind, String>>(),
            lines(ring),
            "超时不渲染：命令没跑完，画一行「退出码 N」就是编一个没发生过的退出",
        )
    }

    @Test
    fun `未接线执行体：原样上抛 ERR_NOT_IMPLEMENTED，不假装跑过`() {
        val ring = SeqRing<NpmConsoleLine>(capacity = 64)
        val r = runner(ShellOpExecutor.Unavailable, ring)

        val e = assertThrows<AutojsException> {
            runBlocking { r.run(projectId, "id", ShellConsoleMode.ROOT, 5_000L) }
        }
        assertEquals(ErrorCode.ERR_NOT_IMPLEMENTED, e.error)
        assertEquals(1, lines(ring).size, "只落一行「失败：…」，不冒充有一条退出码")
        assertFalse(lines(ring).single().second.contains("退出码"))
    }
}
