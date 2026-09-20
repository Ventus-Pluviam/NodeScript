package com.autoscript.platform.system

import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.system.ShellMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * `shell` Android 实现的契约测试（docs §9.6）：超时、收尸、双流、argv 形态。
 *
 * 用**真** `Process` 替身（[FakeProcess]）而不是 mock 框架：这里要验的正是
 * "管道写满会不会死锁""超时到底杀没杀进程"这类与真实 IO 语义绑定的事，
 * mock 会把它们正好抽象掉。
 */
class AndroidShellExecutorTest {

    // ── argv 形态（§9.6 三通道）─────────────────────────────────────

    @Test
    fun `三种模式的 argv 形态：ROOT 走 su，其余走 sh -c`() {
        val seen = ArrayList<Array<String>>()
        val exec = AndroidShellExecutor(record(seen))
        runBlocking {
            exec.exec("echo hi", ShellMode.DEFAULT, 5_000)
            exec.exec("echo hi", ShellMode.ADB, 5_000)
            exec.exec("id", ShellMode.ROOT, 5_000)
        }
        assertEquals(listOf("sh", "-c", "echo hi"), seen[0].toList())
        assertEquals(listOf("sh", "-c", "echo hi"), seen[1].toList())
        assertEquals(listOf("su", "-c", "id"), seen[2].toList())
    }

    @Test
    fun `命令里的引号与美元符原样进 argv，不经过第二层拼接`() {
        val seen = ArrayList<Array<String>>()
        val cmd = "echo \"\$HOME\" && echo 'a b'"
        runBlocking { AndroidShellExecutor(record(seen)).exec(cmd, ShellMode.DEFAULT, 5_000) }
        // 命令作为**单个** argv 元素：拼接转义的问题不存在，内核直接收参数。
        assertEquals(3, seen[0].size)
        assertEquals(cmd, seen[0][2])
    }

    // ── 结果语义 ────────────────────────────────────────────────────

    @Test
    fun `成功路径：退出码与双流原样回，尾换行不裁剪`() {
        val proc = FakeProcess(exitCode = 0, stdout = "out\n".toByteArray(), stderr = "".toByteArray())
        val r = runBlocking { AndroidShellExecutor({ proc }).exec("x", ShellMode.DEFAULT, 5_000) }
        assertEquals(0, r.code)
        assertEquals("out\n", r.stdout)   // 不 trim：尾换行是有信息的字节
        assertNull(r.stderr)              // 零字节 = 该流没产出（≠ 空串）
        assertTrue(r.isSuccess)
    }

    @Test
    fun `非零退出码不是异常：原样交给 JS 侧判`() {
        val proc = FakeProcess(exitCode = 127, stdout = ByteArray(0), stderr = "not found\n".toByteArray())
        val r = runBlocking { AndroidShellExecutor({ proc }).exec("nope", ShellMode.DEFAULT, 5_000) }
        assertEquals(127, r.code)
        assertNull(r.stdout)
        assertEquals("not found\n", r.stderr)
        assertTrue(!r.isSuccess)
    }

    // ── 超时与收尸（铁律 3）─────────────────────────────────────────

    @Test
    fun `超时抛 ERR_TIMEOUT 且进程被强杀`() {
        val proc = FakeProcess(exitCode = 0, hangMillis = 60_000)
        val e = runBlocking {
            runCatching { AndroidShellExecutor({ proc }).exec("sleep 60", ShellMode.DEFAULT, 150) }.exceptionOrNull()
        }
        val err = e as? AutojsException ?: error("期望 AutojsException，实际 $e")
        assertEquals(ErrorCode.ERR_TIMEOUT, err.error)
        assertTrue(proc.destroyed.get(), "超时路径必须销毁子进程（否则留下孤儿 su/sh）")
    }

    @Test
    fun `上层取消（桥 TTL 到点）也收尸`() = runBlocking {
        val proc = FakeProcess(exitCode = 0, hangMillis = 60_000)
        val exec = AndroidShellExecutor({ proc })
        // launch(Dispatchers.Default)：runBlocking 的单线程接下来要被 [FakeProcess.waited].await
        // 占住，协程若排在同一条线程上就永远轮不到它跑（那就变成"测了个没启动的任务"）。
        val job = launch(Dispatchers.Default) { exec.exec("sleep 60", ShellMode.DEFAULT, 60_000) }
        // 等它真的进到 waitFor，再取消 —— 否则测的是"还没起进程就取消"。
        assertTrue(proc.waited.await(5, TimeUnit.SECONDS), "exec 没进到 waitFor")
        job.cancel()
        withTimeoutOrNull(5_000) { job.join() }
        assertTrue(proc.destroyed.get(), "取消路径必须销毁子进程")
    }

    @Test
    fun `进程起不来（su 不存在）折成 ERR_SERVICE_DISABLED`() {
        val exec = AndroidShellExecutor { throw IOException("Cannot run program \"su\"") }
        val e = runBlocking {
            runCatching { exec.exec("id", ShellMode.ROOT, 5_000) }.exceptionOrNull()
        }
        val err = e as? AutojsException ?: error("期望 AutojsException，实际 $e")
        assertEquals(ErrorCode.ERR_SERVICE_DISABLED, err.error)
    }

    @Test
    fun `非正超时被构造期拒绝（铁律 3：不允许无限等待）`() {
        val exec = AndroidShellExecutor(record(ArrayList()))
        val e = runBlocking { runCatching { exec.exec("x", ShellMode.DEFAULT, 0) }.exceptionOrNull() }
        assertTrue(e is IllegalArgumentException, "0 超时应当场拒绝，实际 $e")
    }

    // ── 双流并发读干（死锁回归）─────────────────────────────────────

    @Test
    fun `stdout 超过管道缓冲区也不会死锁`() {
        // 256KB > Linux 管道默认 64KB：串行读（先 stdout 读干再读 stderr）会在这里挂住。
        val big = ByteArray(256 * 1024) { 'x'.code.toByte() }
        val proc = FakeProcess(exitCode = 0, stdout = big, stderr = "err".toByteArray())
        val r = runBlocking { AndroidShellExecutor({ proc }).exec("cat big", ShellMode.DEFAULT, 30_000) }
        assertEquals(big.size, r.stdout!!.length)
        assertEquals("err", r.stderr)
    }

    // ── 替身 ────────────────────────────────────────────────────────

    /** 只回一次 argv 记录的空进程（无输出、立即退出）。 */
    private fun record(seen: MutableList<Array<String>>): AndroidShellExecutor.ProcessLauncher =
        AndroidShellExecutor.ProcessLauncher { argv ->
            seen += argv
            FakeProcess(exitCode = 0)
        }

    /**
     * 真 `Process` 的最小替身：可控退出码、可控双流、可控"卡住不退出"。
     * [hangMillis] > 0 时 [waitFor] 会一直阻塞（除非被中断/销毁），用来验超时与取消。
     */
    private class FakeProcess(
        private val exitCode: Int,
        stdout: ByteArray = ByteArray(0),
        stderr: ByteArray = ByteArray(0),
        private val hangMillis: Long = 0,
    ) : Process() {

        val destroyed = AtomicBoolean(false)
        val waited = CountDownLatch(1)

        private val out: InputStream = ByteArrayInputStream(stdout)
        private val err: InputStream = ByteArrayInputStream(stderr)

        override fun getInputStream(): InputStream = out
        override fun getErrorStream(): InputStream = err
        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

        override fun waitFor(): Int {
            waited.countDown()
            if (hangMillis > 0) CountDownLatch(1).await(hangMillis, TimeUnit.MILLISECONDS)
            return exitCode
        }

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            waited.countDown()
            if (hangMillis <= 0) return true
            // 真进程语义：到点没退出就回 false（且**不**自己退出）。
            CountDownLatch(1).await(timeout, unit)
            return false
        }

        override fun exitValue(): Int = exitCode
        override fun isAlive(): Boolean = hangMillis > 0 && !destroyed.get()
        override fun destroy() { destroyed.set(true) }
        override fun destroyForcibly(): Process {
            destroyed.set(true)
            return this
        }
    }
}
