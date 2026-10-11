package com.autoscript.appservice.npm

import com.autoscript.platform.process.ProcTreeOps
import com.autoscript.platform.process.TreeOps
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
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
        treeOps: TreeOps = NoopTreeOps(),
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
        treeOps = treeOps,
    )

    /** 记录式杀树替身（默认零后代、零动作 —— 既有用例的 reap 行为不变）。 */
    private open class NoopTreeOps : TreeOps {
        override fun descendantsOf(process: Process): Set<Long> = emptySet()
        override fun descendantsOf(alivePids: Set<Long>): Set<Long> = emptySet()
        override fun kill(pids: Collection<Long>, force: Boolean) = Unit
        override fun alive(pid: Long): Boolean = false
    }

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

    /** 被 kill 的假进程：destroy(Forcibly) 后按 128+n 的信号约定值退出。 */
    private class SignalKilledProcess : Process() {
        val destroyed = AtomicBoolean(false)
        val forcibly = AtomicBoolean(false)
        private val done = CountDownLatch(1)

        @Volatile
        private var exitCode: Int = 0

        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int {
            done.await()
            return exitCode
        }

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = done.await(timeout, unit)
        override fun exitValue(): Int = exitCode
        override fun destroy() {
            destroyed.set(true)
            exitCode = 143 // 128 + SIGTERM
            done.countDown()
        }

        override fun destroyForcibly(): Process {
            forcibly.set(true)
            exitCode = 137 // 128 + SIGKILL
            done.countDown()
            return this
        }

        override fun isAlive(): Boolean = done.count > 0
        override fun pid(): Long = -1L
    }

    @Test
    fun `kill(SIGTERM) 真发 destroy，exit 帧按 child_process 口径报信号`() {
        val frames = Frames()
        val proc = SignalKilledProcess()
        val c = child(proc, frames)
        val t = Thread { c.run() }.apply { isDaemon = true; start() }
        Thread.sleep(100)
        c.kill("SIGTERM")
        t.join(5_000)

        assertTrue(proc.destroyed.get(), "SIGTERM 必须落到 destroy（TERM）上")
        assertFalse(proc.forcibly.get(), "SIGTERM 不该升级成 destroyForcibly")
        val exit = frames.of("exit").single()
        assertEquals(null, exit["code"], "信号终止按真 child_process 口径：code=null")
        assertEquals("SIGTERM", exit["signal"], "信号名是我们发出去的那个，如实回填")
    }

    @Test
    fun `kill(SIGKILL) 走 destroyForcibly`() {
        val frames = Frames()
        val proc = SignalKilledProcess()
        val c = child(proc, frames)
        val t = Thread { c.run() }.apply { isDaemon = true; start() }
        Thread.sleep(100)
        c.kill("SIGKILL")
        t.join(5_000)

        assertTrue(proc.forcibly.get(), "SIGKILL 必须落到 destroyForcibly 上")
        val exit = frames.of("exit").single()
        assertEquals(null, exit["code"])
        assertEquals("SIGKILL", exit["signal"])
    }

    @Test
    fun `kill 先于进程起：起完立刻补杀（kill 帧合法时序）`() {
        // shim 发完 spawn 帧就能发 kill —— 宿主侧 spawn 帧的处理与 kill 帧的处理在同一条
        // 读线程上串行，但进程 start() 可能还没返回；这一例钉"那条缝不丢杀"。
        val frames = Frames()
        val proc = SignalKilledProcess()
        val c = child(proc, frames)
        c.kill("SIGTERM") // 进程还没起
        c.run()           // run 里 start 之后必须补杀

        assertTrue(proc.destroyed.get(), "起完要立刻补杀（pending 位不能丢）")
        val exit = frames.of("exit").single()
        assertEquals("SIGTERM", exit["signal"])
    }

    @Test
    fun `自然退出不带信号名（exit 小于 128 时 code 照给 signal=null）`() {
        val frames = Frames()
        val c = child(DoneProcess(exitCode = 7), frames)
        c.kill("SIGTERM") // 赛跑里自然退出先到：exitValue=7 < 128 → 不算被杀
        c.run()

        val exit = frames.of("exit").single()
        assertEquals(7, exit["code"], "先退成 0/7 的那次按自然退出报，不把'恰好要杀'写成'被杀'")
        assertEquals(null, exit["signal"])
    }

    @Test
    fun `没人点名但 128+n：按 shell 惯例反查信号名（批 95 补的那半）`() {
        // OOM killer / 别人 kill -TERM —— 没有 kill 帧，JVM 只给 128+15=143。
        // 批 94 之前这一档恒 null；批 95 起按 POSIX 惯例反查（143 → SIGTERM）。
        val frames = Frames()
        child(DoneProcess(exitCode = 143), frames).run()

        val exit = frames.of("exit").single()
        assertEquals(null, exit["code"], "有信号名时 code=null（真 child_process 同款）")
        assertEquals("SIGTERM", exit["signal"], "128+15 的编码本身就是「被 SIGTERM 杀死」")
    }

    @Test
    fun `没人点名且 128+n 查不到名字：照旧 code=128+n signal=null（不编）`() {
        val frames = Frames()
        child(DoneProcess(exitCode = 128 + 50), frames).run() // 50 不在 1–31 表里

        val exit = frames.of("exit").single()
        assertEquals(128 + 50, exit["code"], "实时信号/未知档不猜名字，编码原样给")
        assertEquals(null, exit["signal"])
    }

    /** 记录式杀树替身：第一轮/二次收割的后代集合与 kill 账都能断言。 */
    private class RecordingTreeOps(
        private val firstWave: Set<Long>,
        private val lateWave: Map<Long, Set<Long>>, // 仍活着的第一轮成员 → 它们的后代
        private val alivePids: Set<Long>,
    ) : TreeOps {
        val killed = mutableListOf<Pair<Set<Long>, Boolean>>()
        var firstScanProcess: Process? = null

        override fun descendantsOf(process: Process): Set<Long> {
            firstScanProcess = process
            return firstWave
        }

        override fun descendantsOf(alivePids: Set<Long>): Set<Long> =
            alivePids.flatMap { lateWave[it] ?: emptySet() }.toSet()

        override fun kill(pids: Collection<Long>, force: Boolean) {
            if (pids.isNotEmpty()) killed += pids.toSet() to force
        }

        override fun alive(pid: Long): Boolean = pid in alivePids
    }

    @Test
    fun `收尸扫在杀之前，TERM 后代再 KILL，二次收割捡 reparent 的漏网`() {
        val frames = Frames()
        val proc = StubbornProcess() // TERM 不理会 → 走到 KILL 与二次收割
        // 第一轮后代 11、12；宽限后 11 还活着，它的后代里有新生的 21（12 已死、无新生）。
        val ops = RecordingTreeOps(
            firstWave = setOf(11L, 12L),
            lateWave = mapOf(11L to setOf(21L)),
            alivePids = setOf(11L),
        )
        val c = child(proc, frames, treeOps = ops)
        val t = Thread { c.run() }.apply { isDaemon = true; start() }
        Thread.sleep(100)
        c.reap()
        t.join(5_000)

        assertTrue(ops.firstScanProcess === proc, "第一轮扫描必须拿主进程当根（扫在杀之前）")
        assertEquals(
            listOf(setOf(11L, 12L) to false, setOf(11L) to true, setOf(21L) to true),
            ops.killed,
            "TERM 第一波（11/12）→ 宽限后只 KILL 还活着的（11）→ KILL 二次收割捡到的 21" +
                "（reparent 后从主进程走不到的那条）",
        )
    }

    /**
     * 真 /proc + 真 `kill` 的进程树收尸（批 95 的生产路径，唯一一条不走替身的用例）。
     *
     * 起一棵真树（sh → 后台 sleep），reap 之后**孙子必须也没了** —— 这正是批 91 那版
     * 在设备上静默失效的那一段（ProcessHandle 缺席 → 后代恒空）。判据借的是**自己这棵
     * 树**的 pid（/proc 里查还在不在），不是宿主的任何既有进程 —— 与"借宿主 /proc 判死"
     * 那条教训不冲突。
     */
    @Test
    fun `真进程树：sh 的后台孙子也收得掉（生产路径 ProcTreeOps）`() {
        val frames = Frames()
        val c = T1Child(
            id = 1L,
            cmd = "sh",
            args = listOf("-c", "sleep 300 & echo $!; wait"),
            cwd = null,
            env = null,
            detached = false,
            shell = false,
            runner = ProcessBuilderT1Runner(),
            emit = frames::emit,
            treeOps = ProcTreeOps(),
        )
        val t = Thread { c.run() }.apply { isDaemon = true; start() }
        // 等 sh 真把后台 sleep 起起来（stdout 里那行 pid 就是孙子）。
        val deadline = System.currentTimeMillis() + 10_000
        var grandPid: Long? = null
        while (System.currentTimeMillis() < deadline && grandPid == null) {
            synchronized(frames) {
                frames.of("out").firstOrNull()?.let { grandPid = (decode(it).trim().toLongOrNull()) }
            }
            if (grandPid == null) Thread.sleep(50)
        }
        val pid = requireNotNull(grandPid) { "sh 没把后台 sleep 的 pid 打出来（前置失败）" }
        val procDir = java.nio.file.Paths.get("/proc")
        assertTrue(Files.exists(procDir.resolve(pid.toString())), "前置：孙子此刻该活着")
        try {
            c.reap()
            t.join(10_000)
            // KILL 是异步送达的：给内核一个短窗，但必须收敛到"没了"。
            val reapDeadline = System.currentTimeMillis() + 5_000
            while (Files.exists(procDir.resolve(pid.toString())) &&
                System.currentTimeMillis() < reapDeadline
            ) {
                Thread.sleep(50)
            }
            assertFalse(
                Files.exists(procDir.resolve(pid.toString())),
                "reap 之后后台 sleep（pid=$pid）必须没了吧 —— 孙子漏收正是批 91 的静默空转",
            )
        } finally {
            runCatching { ProcessBuilder("kill", "-9", pid.toString()).start().waitFor() }
        }
    }
}
