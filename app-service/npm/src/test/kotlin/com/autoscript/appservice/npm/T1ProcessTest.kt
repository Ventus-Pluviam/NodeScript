package com.autoscript.appservice.npm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * T1 子进程那一层（[T1Child]）的确定性用例（2026-10-10 批 91）。
 *
 * 走 [T1ProcessRunner] 替身 —— 那条缝的 KDoc 写的就是「真能起真进程由 E2E 钉，起了之后的
 * 四件（泵输出 / 等退出 / 两侧各拒一次 / 杀树）用替身跑成确定性用例」。本类就是那个承诺的
 * 兑现方；没有它，那条缝只是「看起来可测」。
 *
 * 真进程那侧见 [T1BridgeNodeTest] 与 [T1BridgeE2ETest]。
 */
class T1ProcessTest {

    /** 一条已经跑完的假进程：两条流是给定字节，`waitFor` 立即返回给定退出码。 */
    private class DoneProcess(
        stdout: ByteArray = ByteArray(0),
        stderr: ByteArray = ByteArray(0),
        private val exitCode: Int = 0,
    ) : Process() {
        private val out = ByteArrayInputStream(stdout)
        private val err = ByteArrayInputStream(stderr)

        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = out
        override fun getErrorStream(): InputStream = err
        override fun waitFor(): Int = exitCode
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = true
        override fun exitValue(): Int = exitCode
        override fun destroy() = Unit
        override fun destroyForcibly(): Process = this
        override fun isAlive(): Boolean = false
        override fun pid(): Long = -1L
    }

    /** 一条**不理会 TERM** 的假进程：`destroy()` 只置位，只有 `destroyForcibly()` 才真结束。 */
    private class StubbornProcess : Process() {
        val destroyed = AtomicBoolean(false)
        val forcibly = AtomicBoolean(false)
        private val done = CountDownLatch(1)

        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int {
            done.await()
            return 0
        }

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = done.await(timeout, unit)
        override fun exitValue(): Int = 0
        override fun destroy() {
            destroyed.set(true) // 刻意不 countDown：模拟"TERM 不理会"
        }

        override fun destroyForcibly(): Process {
            forcibly.set(true)
            done.countDown()
            return this
        }

        override fun isAlive(): Boolean = done.count > 0
        override fun pid(): Long = -1L
    }

    /** 收帧替身（[T1Child] 只要求"往会话 socket 写一帧"这一件事）。 */
    private class Frames {
        private val all = mutableListOf<Map<String, Any?>>()

        fun emit(frame: Map<String, Any?>): Boolean {
            synchronized(all) { all += frame }
            return true
        }

        fun of(type: String): List<Map<String, Any?>> = synchronized(all) { all.filter { it["t"] == type } }
    }

    private fun child(
        proc: Process?,
        frames: Frames,
        detached: Boolean = false,
        cmd: String = "echo",
    ) = T1Child(
        id = 1L,
        cmd = cmd,
        args = listOf("x"),
        cwd = null,
        env = null,
        detached = detached,
        shell = false,
        runner = { spec -> proc ?: throw IOException("本测试里没有这条命令：${spec.cmd}") },
        emit = frames::emit,
    )

    private fun decode(frame: Map<String, Any?>): String =
        String(Base64.getDecoder().decode(frame["data"] as String))

    @Test
    fun `stdout 与 stderr 各按 fd 泵回，退出码进 exit 帧`() {
        val frames = Frames()
        child(
            DoneProcess(stdout = "hello".toByteArray(), stderr = "oops".toByteArray(), exitCode = 7),
            frames,
        ).run()

        val out = frames.of("out")
        assertEquals(2, out.size, "stdout 与 stderr 各一条：$out")
        assertEquals(setOf(1, 2), out.map { it["fd"] }.toSet(), "fd 要分开（合成一条等于把 stderr 冒充成 stdout）")
        val byFd = out.associate { it["fd"] to decode(it) }
        assertEquals("hello", byFd[1])
        assertEquals("oops", byFd[2])

        val exit = frames.of("exit").single()
        assertEquals(7, exit["code"], "退出码必须如实回填（npm 靠它判脚本成败）")
    }

    @Test
    fun `detached 在宿主侧也被拒（不靠 shim 那一层）`() {
        // 双侧各拒一次是刻意的：shim 是"送信的"，它被绕过（delete require.cache）时
        // 这条判据不能跟着消失。这里验的正是"宿主侧那一份还在"。
        val frames = Frames()
        child(DoneProcess(), frames, detached = true).run()

        val err = frames.of("err").single()
        assertEquals("ERR_PERMISSION_DENIED", err["code"])
        assertTrue(frames.of("exit").isEmpty(), "拒绝不是退出：不许同时回 exit 帧")
    }

    @Test
    fun `起不来是结果不是崩溃：折成带码的 err 帧`() {
        val frames = Frames()
        child(proc = null, frames = frames, cmd = "no-such-bin").run()

        val err = frames.of("err").single()
        assertEquals("ERR_NPM_SPAWN_BLOCKED", err["code"])
        assertTrue((err["detail"] as String).contains("no-such-bin"), "话术里要有那条命令：$err")
    }

    @Test
    fun `没起过进程时收尸是空操作（幂等）`() {
        val frames = Frames()
        child(DoneProcess(), frames).reap()
        assertTrue(frames.of("exit").isEmpty() && frames.of("err").isEmpty(), "收尸不该产生任何帧")
    }

    @Test
    fun `收尸先 TERM，不退才 KILL`() {
        val frames = Frames()
        val proc = StubbornProcess()
        val c = child(proc, frames)
        // run() 会阻塞在 waitFor 上，故在另一条线程里跑；等它真进了 waitFor 再收尸。
        val t = Thread { c.run() }.apply { isDaemon = true; start() }
        Thread.sleep(100)
        assertTrue(proc.isAlive(), "前置：假进程这时该还活着")
        c.reap()
        t.join(5_000)

        assertTrue(proc.destroyed.get(), "收尸必须先 TERM")
        assertTrue(
            proc.forcibly.get(),
            "TERM 不退就要 KILL —— 只 TERM 不 KILL 留下的正是不理会 TERM 的孤儿",
        )
    }
}
