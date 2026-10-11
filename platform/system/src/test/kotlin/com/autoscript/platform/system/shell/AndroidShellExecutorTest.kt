package com.autoscript.platform.system.shell

import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * `shell` Android 实现的契约测试（docs §9.6）：超时、收尸、双流、argv 形态。
 *
 * 用**真** `Process` 替身（[FakeProcess]）而不是 mock 框架：这里要验的正是
 * "管道写满会不会死锁""超时到底杀没杀进程"这类与真实 IO 语义绑定的事，
 * mock 会把它们正好抽象掉。
 *
 * **替身的强度决定测试的强度**（2026-10-01 的教训，见 `docs/backlog.md` A2）：
 * 原先两条读流都是 `ByteArrayInputStream`（**立刻 EOF**），于是"读流不可取消会把
 * 超时路径挂死"这条真机病灶在测试里根本不存在 —— 四条超时/取消用例全绿，
 * 而真机上 `su -c` 把管道写端交给孙进程时是「设 1s 超时、实际卡 100s 且没杀」。
 * 现在用 [HeldStream]（吐完字节后永不 EOF，模拟写端被孙进程持有）复现该形态。
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
        // 注意：不能再写尾随 lambda —— 加了 logSink 之后它绑到的是**最后一个**参数。
        // 这里显式写 SAM 类型，让"注入的是启动缝"这件事在调用点看得见。
        val exec = AndroidShellExecutor(
            AndroidShellExecutor.ProcessLauncher { throw IOException("Cannot run program \"su\"") },
        )
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

    // ── 读流不可取消（2026-10-01 回归：写端被孙进程持有）─────────────
    // 病灶：超时/取消抛出后 coroutineScope 要等两条 readBytes 子协程结束才传播，
    // 而 destroyForcibly() 在那个作用域**外**的 finally 里 —— 读流不 EOF 就永远
    // 等不到，进程也没杀。下面三条用 [HeldStream] 把那个形态钉住。

    @Test
    fun `超时：写端被孙进程持有（读流永不 EOF）也立刻返回并强杀`() {
        val held = HeldStream("partial".toByteArray())
        val proc = FakeProcess(exitCode = 0, hangMillis = 60_000, outStream = held, errStream = HeldStream(ByteArray(0)))
        try {
            val r = runBlocking {
                withDeadline(5_000) { AndroidShellExecutor({ proc }).exec("su -c 'sleep 100'", ShellMode.ROOT, 150) }
            }
            val result = r ?: error("exec 没在 5s 内返回：读流不 EOF 把超时路径挂死了")
            val err = result.exceptionOrNull() as? AutojsException ?: error("期望 AutojsException，实际 $result")
            assertEquals(ErrorCode.ERR_TIMEOUT, err.error)
            assertTrue(proc.destroyed.get(), "超时路径必须强杀子进程（`su -c` 留下的是孤儿 su）")
        } finally {
            held.release()
        }
    }

    @Test
    fun `取消：写端被孙进程持有（读流永不 EOF）也立刻收尸返回`() {
        val held = HeldStream(ByteArray(0))
        val proc = FakeProcess(exitCode = 0, hangMillis = 60_000, outStream = held, errStream = HeldStream(ByteArray(0)))
        try {
            val exec = AndroidShellExecutor({ proc })
            // 独立 scope：泄漏的读线程不能挂在 runBlocking 的作业树上，否则测试自己也跟着挂。
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val job = scope.launch { runCatching { exec.exec("sleep 100", ShellMode.DEFAULT, 60_000) } }
                assertTrue(proc.waited.await(5, TimeUnit.SECONDS), "exec 没进到 waitFor")
                job.cancel()
                val joined = runBlocking { withTimeoutOrNull(5_000) { job.join() } }
                assertNotNull(joined, "取消后 exec 没在 5s 内返回：读流不 EOF 把取消路径挂死了")
                assertTrue(proc.destroyed.get(), "取消路径必须销毁子进程")
            } finally {
                scope.cancel()
            }
        } finally {
            held.release()
        }
    }

    @Test
    fun `成功路径：进程已退出但写端被孙进程持有，宽限到点带着已读字节返回`() {
        val held = HeldStream("done\n".toByteArray())
        val proc = FakeProcess(exitCode = 0, outStream = held, errStream = HeldStream(ByteArray(0)))
        try {
            val t0 = System.nanoTime()
            // 同样走 withDeadline：修复前这条会在 `out.await()` 上永久挂住，
            // 用 runBlocking 直写的话"挂住"就等于把整个测试 JVM 拖死，而不是给出一条红断言。
            val r = runBlocking {
                withDeadline(5_000) { AndroidShellExecutor({ proc }).exec("echo done &", ShellMode.DEFAULT, 5_000) }
            }
            val ms = (System.nanoTime() - t0) / 1_000_000
            val result = r?.getOrNull() ?: error("exec 没在 5s 内返回：读流永不 EOF 把成功路径也挂死了")
            assertEquals(0, result.code)
            assertEquals("done\n", result.stdout)   // 已读到的字节照常交回，不丢
            assertNull(result.stderr)
            assertTrue(ms < 3_000, "宽限必须有上界（铁律 3：不允许无限等待），实际 ${ms}ms")
        } finally {
            held.release()
        }
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

    // ── 捕获上限（2026-10-02，backlog A2b）────────────────────────────
    // 口径：静默截断 + 显式日志 Warning + 返回截断标志（design-decisions 第 21 项）。
    // 这里钉三件容易做漏的事：**到顶后仍在读**（否则子进程憋死在满管道上）、
    // **两条流各自计数**、**没超限就一个字节都不许多**（截断不能变成常态损耗）。

    @Test
    fun `输出超上限：留满上限 + 置 truncated + 末尾说明行，且进程仍被读干`() {
        val total = ShellCaptureLimit.MAX_CAPTURE_BYTES + 100_000
        val stream = CountingStream(ByteArray(total) { 'x'.code.toByte() })
        val proc = FakeProcess(exitCode = 0, outStream = stream)
        val warnings = ArrayList<String>()
        val r = runBlocking {
            AndroidShellExecutor({ proc }, ShellCaptureLimit.LogSink { warnings += it })
                .exec("cat big", ShellMode.DEFAULT, 30_000)
        }
        assertTrue(r.truncated, "超过上限必须置 truncated")
        assertEquals(0, r.code, "截断不改退出码")
        assertTrue(r.isSuccess, "截断不是命令失败")
        // 保留的正是"前 N 字节"+ 说明行（说明行加在文本层，不占字节额度）。
        assertEquals(ShellCaptureLimit.MAX_CAPTURE_BYTES + ShellCaptureLimit.TRUNCATION_MARK.length, r.stdout!!.length)
        assertTrue(r.stdout!!.endsWith(ShellCaptureLimit.TRUNCATION_MARK), "末尾必须有给人看的说明行")
        assertNull(r.stderr, "没超限的那条流仍是 null（没产出）")
        // **关键**：到顶之后必须继续读干。读循环要是停了，真机上子进程会阻塞在写满的
        // 管道上 —— 那比内存膨胀更坏（活锁 vs 有损）。这里以"读端把全部字节都取走了"为证。
        assertEquals(total, stream.readBytes, "到顶后必须继续读干（否则子进程憋死在满管道上）")
        assertEquals(1, warnings.size, "截断必须发一条 Warning")
        assertTrue(warnings[0].contains("cat big"), "Warning 要带命令本身，否则定位不到是谁被截了：${warnings[0]}")
        assertTrue(warnings[0].contains("100000"), "Warning 要报丢弃字节数：${warnings[0]}")
    }

    @Test
    fun `两条流各自计数：stdout 截断不吃 stderr 的额度`() {
        val proc = FakeProcess(
            exitCode = 0,
            outStream = CountingStream(ByteArray(ShellCaptureLimit.MAX_CAPTURE_BYTES + 1) { 'x'.code.toByte() }),
            errStream = ByteArrayInputStream("boom\n".toByteArray()),
        )
        val r = runBlocking { AndroidShellExecutor({ proc }).exec("x", ShellMode.DEFAULT, 30_000) }
        assertTrue(r.truncated)
        // stderr 短得很，必须原样留着 —— 错误信息恰恰是最该留住的那部分。
        assertEquals("boom\n", r.stderr)
    }

    @Test
    fun `没超限就一个字节都不动：不加说明行、不置位、不发日志`() {
        val payload = ByteArray(ShellCaptureLimit.MAX_CAPTURE_BYTES) { 'y'.code.toByte() }
        val proc = FakeProcess(exitCode = 0, stdout = payload)
        val warnings = ArrayList<String>()
        val r = runBlocking {
            AndroidShellExecutor({ proc }, ShellCaptureLimit.LogSink { warnings += it })
                .exec("cat exact", ShellMode.DEFAULT, 30_000)
        }
        // 恰好等于上限 = 没超（`>` 而非 `>=`）：截断的代价不能提前一格开始付。
        assertEquals(false, r.truncated)
        assertEquals(ShellCaptureLimit.MAX_CAPTURE_BYTES, r.stdout!!.length)
        assertTrue(!r.stdout!!.contains("[autoscript]"), "没截断就不许出现说明行")
        assertTrue(warnings.isEmpty(), "没截断就不许发 Warning（否则日志里全是噪音）")
    }

    @Test
    fun `上限按字节判：多字节字符被切在中间也不抛异常（替换字符收场）`() {
        // 每个汉字 3 字节；上限不是 3 的倍数 ⇒ 最后一刀落在字符中间。
        val cap = ShellCaptureLimit.MAX_CAPTURE_BYTES
        val text = "汉".repeat(cap / 3 + 10)
        val proc = FakeProcess(exitCode = 0, stdout = text.toByteArray(Charsets.UTF_8))
        val r = runBlocking { AndroidShellExecutor({ proc }).exec("x", ShellMode.DEFAULT, 30_000) }
        assertTrue(r.truncated)
        assertTrue(r.stdout!!.startsWith("汉"), "前半段照常解码")
        assertTrue(r.stdout!!.endsWith(ShellCaptureLimit.TRUNCATION_MARK))
        Unit
    }

    // ── 工具 ────────────────────────────────────────────────────────

    /**
     * 在**独立 scope** 里跑 [block]，最多等 [millis]；没返回就回 null。
     *
     * 不用裸 `runBlocking { withTimeoutOrNull { … } }`：runBlocking 收尾时会等自己的
     * 全部子协程，而被挂住的读流恰恰是不可取消的子协程 —— 那么"卡住"就表现为整个
     * 测试进程挂死，而不是一条红色断言（这正是修复前那条病灶的形状）。
     * 这里的 `scope.cancel()` 只标记取消、不 join，卡住的作业泄漏成守护线程后测试照常收场。
     */
    private suspend fun <T> withDeadline(millis: Long, block: suspend CoroutineScope.() -> T): Result<T>? {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        return try {
            val job = scope.async { runCatching { block() } }
            withTimeoutOrNull(millis) { job.await() }
        } finally {
            scope.cancel()
        }
    }

    /** 只回一次 argv 记录的空进程（无输出、立即退出）。 */
    private fun record(seen: MutableList<Array<String>>): AndroidShellExecutor.ProcessLauncher =
        AndroidShellExecutor.ProcessLauncher { argv ->
            seen += argv
            FakeProcess(exitCode = 0)
        }

    /**
     * 会记账的读端：把 [payload] 全部交出去，同时记下**被读走的字节数**。
     *
     * 存在的唯一理由是给"到顶后仍在读"一个可断言的证据 —— `ByteArrayInputStream`
     * 不读也不会阻塞谁，所以"读循环停了"在它身上看不出来。真管道的写端会被满缓冲区
     * 憋住，而本类用"读端取走了多少"把这个语义搬到单测里。
     */
    private class CountingStream(private val payload: ByteArray) : InputStream() {

        @Volatile
        var readBytes: Int = 0
            private set

        private var pos = 0

        override fun read(): Int {
            if (pos >= payload.size) return -1
            readBytes += 1
            return payload[pos++].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (pos >= payload.size) return -1
            val n = minOf(len, payload.size - pos)
            System.arraycopy(payload, pos, b, off, n)
            pos += n
            readBytes += n
            return n
        }
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
        outStream: InputStream = ByteArrayInputStream(stdout),
        errStream: InputStream = ByteArrayInputStream(stderr),
    ) : Process() {

        val destroyed = AtomicBoolean(false)
        val waited = CountDownLatch(1)

        private val out: InputStream = outStream
        private val err: InputStream = errStream

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

    /**
     * **不 EOF 的管道**：吐完 [payload] 就阻塞，直到 [release]。
     *
     * 模拟"写端被孙进程继承"（`su -c …` / `sh -c "… &"`）：直接子进程被杀掉也换不来
     * EOF，因为写端还握在孙进程手里 —— 真机上这就是那个"超时形同虚设"的现场。
     * 阻塞不响应中断：真管道的阻塞读就是这样（这也正是不能用协程取消来收场的理由）。
     */
    private class HeldStream(private val payload: ByteArray) : InputStream() {

        private val release = CountDownLatch(1)
        private var pos = 0

        fun release() = release.countDown()

        override fun read(): Int {
            if (pos < payload.size) return payload[pos++].toInt() and 0xff
            blockUntilReleased()
            return -1
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (pos < payload.size) {
                val n = minOf(len, payload.size - pos)
                System.arraycopy(payload, pos, b, off, n)
                pos += n
                return n
            }
            blockUntilReleased()
            return -1
        }

        private fun blockUntilReleased() {
            while (true) {
                try {
                    if (release.await(1, TimeUnit.SECONDS)) return
                } catch (_: InterruptedException) {
                    // 真管道的阻塞读不因中断而返回：这里也照旧等下去。
                }
            }
        }
    }
}

/**
 * 外审 M6（2026-10-11 批 99）：shell 面的两条 —— **杀树**与 **stdin 关闭**。
 *
 * 两条各自的现场：
 * - 杀树：`destroyForcibly()` 只杀 `sh`/`su` 直接子进程，孙进程（`sh -c "sleep 300 &"`）
 *   留在设备上。原 KDoc 写「不留孤儿进程」——那句话不成立。
 * - stdin：shell 面的契约是「只执行命令、不喂输入」（签名里根本没有 stdin 参数），
 *   不关写端的话 `cat`/`read x` 会**一直挂到超时**，用户看到「一条 cat 跑满超时返回空」。
 */
class AndroidShellExecutorReapingTest {

    @TempDir
    lateinit var dir: Path

    /** 记录式树面：第一轮后代集合与 kill 账都能断言。 */
    private class RecordingTreeOps(private val firstWave: Set<Long>) : TreeOps {
        val killed = mutableListOf<Pair<Set<Long>, Boolean>>()
        override fun descendantsOf(process: Process): Set<Long> = firstWave
        override fun kill(pids: Collection<Long>, force: Boolean) {
            if (pids.isNotEmpty()) killed += pids.toSet() to force
        }
    }

    /** 假进程：可断言 stdin 被关、`destroyForcibly` 被调、可选挂住 `waitFor`。 */
    private class FakeProcess(private val hangSeconds: Long = 0) : Process() {
        var stdinClosed = false
        var forciblyCalled = false
        private val done = CountDownLatch(1)
        private val stdin = ByteArrayOutputStream()

        override fun getOutputStream(): OutputStream = object : OutputStream() {
            override fun write(b: Int) = Unit
            override fun write(b: ByteArray, off: Int, len: Int) = Unit
            override fun close() {
                stdinClosed = true
                stdin.close()
            }
        }

        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int {
            done.await()
            return 0
        }

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            if (hangSeconds <= 0) {
                done.countDown()
                return true
            }
            return done.await(hangSeconds, unit)
        }

        override fun exitValue(): Int = 0
        override fun destroy() = Unit
        override fun destroyForcibly(): Process {
            forciblyCalled = true
            done.countDown()
            return this
        }

        override fun isAlive(): Boolean = done.count > 0
    }

    @Test
    fun `stdin 立即关闭——等输入的命令立刻读到 EOF 而不是挂到超时（外审 M6）`() = runBlocking {
        val proc = FakeProcess()
        val shell = AndroidShellExecutor(
            launcher = { proc },
            treeOps = RecordingTreeOps(emptySet()),
        )
        shell.exec("cat", ShellMode.DEFAULT, 5_000)

        assertTrue(proc.stdinClosed, "stdin 必须关：shell 面不喂输入，攥着写端=等输入的命令挂到超时")
    }

    @Test
    fun `超时收尸点名整棵树：后代 TERM 后补 KILL（外审 M6）`() = runBlocking {
        val proc = FakeProcess(hangSeconds = 30)
        val ops = RecordingTreeOps(firstWave = setOf(31L, 32L))
        val shell = AndroidShellExecutor(launcher = { proc }, treeOps = ops)

        val e = assertThrows(AutojsException::class.java) {
            runBlocking { shell.exec("sh -c 'sleep 300 & wait'", ShellMode.DEFAULT, 120) }
        }
        assertEquals(ErrorCode.ERR_TIMEOUT.code, e.error.code)

        assertTrue(proc.forciblyCalled, "主进程必须被强杀（destroyForcibly 只杀直接子进程那一半）")
        assertEquals(
            listOf(setOf(31L, 32L) to false, setOf(31L, 32L) to true),
            ops.killed,
            "孙进程必须被点名：TERM 一波 + 立刻补 KILL（收尸路径不等宽限）",
        )
    }

    @Test
    fun `pid 取不到时降级为只杀主进程 不炸收尸（外审 M6 的降级档）`() = runBlocking {
        val proc = FakeProcess(hangSeconds = 30)
        val ops = RecordingTreeOps(firstWave = emptySet())   // 模拟 pid 取不到 / /proc 不可读
        val shell = AndroidShellExecutor(launcher = { proc }, treeOps = ops)

        assertThrows(AutojsException::class.java) {
            runBlocking { shell.exec("sleep 300", ShellMode.DEFAULT, 100) }
        }
        assertTrue(proc.forciblyCalled, "降级也必须杀掉主进程（不更坏）")
        assertTrue(ops.killed.isEmpty(), "没有后代集合时不得凭空点名")
    }

    /**
     * 真 `/proc` + 真 `kill` 的那条（批 99 唯一一条不走替身的用例）—— 与批 95 npm 那侧
     * 「真进程树」用例同款分工：替身钉**顺序与点名**（[RecordingTreeOps]），
     * 这一条钉**`/proc` 面真的能数出孙子**。
     *
     * 为什么不经 [AndroidShellExecutor] 走一遍：executor 自己两条 [PipeReader] 会把
     * stdout 读干，`echo $!` 那行 pid 抓不到（抓 pid 的线程与读流互斥）。而「`/proc` 面
     * 数不对」与「executor 调它的顺序」是两个独立判据 —— 前者在这里，后者在上方替身用例。
     */
    @Test
    fun `真进程树：proc 面能数出 sh 的后台孙子并收掉（生产路径 ProcOps）`() {
        val sh = ProcessBuilder("sh", "-c", "sleep 300 & echo $!; wait").start()
        val out = StringBuilder()
        Thread {
            runCatching {
                sh.inputStream.bufferedReader().useLines { ls ->
                    ls.forEach { synchronized(out) { out.append(it).append('\n') } }
                }
            }
        }.apply { isDaemon = true }.start()

        val pid = waitForPid(out)
        val ops = ProcOps()
        try {
            assertTrue(Files.exists(Paths.get("/proc").resolve(pid.toString())), "前置：后台 sleep 此刻该活着")
            val root = ops.pidOf(sh)
            assertTrue(root != null && root > 0, "pid 取得到（真进程 + 两级反射阶梯）")
            val tree = ops.descendantsOf(sh)
            assertTrue(
                pid in tree,
                "proc 面必须数出后台 sleep（pid=$pid）：$tree",
            )
            ops.kill(tree, force = true)
            sh.destroyForcibly()
            sh.waitFor()
            val deadline = System.currentTimeMillis() + 5_000
            while (Files.exists(Paths.get("/proc").resolve(pid.toString())) && System.currentTimeMillis() < deadline) {
                Thread.sleep(50)
            }
            assertFalse(
                Files.exists(Paths.get("/proc").resolve(pid.toString())),
                "按 pid 杀掉之后后台 sleep 必须没了（pid=$pid）",
            )
        } finally {
            runCatching { ProcessBuilder("kill", "-9", pid.toString()).start().waitFor() }
            runCatching { sh.destroyForcibly() }
        }
        Unit
    }

    private fun waitForPid(stdout: StringBuilder): Long {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val text = synchronized(stdout) { stdout.toString() }
            val pid = text.trim().lines().firstOrNull { it.trim().toLongOrNull() != null }?.trim()?.toLongOrNull()
            if (pid != null) return pid
            Thread.sleep(50)
        }
        throw AssertionError("sh 没把后台 sleep 的 pid 打出来（前置失败）")
    }
}
