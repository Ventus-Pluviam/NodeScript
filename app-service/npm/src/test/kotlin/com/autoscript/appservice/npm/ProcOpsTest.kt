package com.autoscript.appservice.npm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * [ProcOps] 的 /proc 面（批 95）：stat 解析与 ppid 图下行遍历。
 *
 * **喂的是合成的 /proc 树**（@TempDir），不借宿主 `/proc` 判状态 —— 借宿主的判据在
 * CI 上是假绿（本仓有过教训：pid=4242 借宿主 /proc 判死，本机全绿 CI 红）。
 * 真 /proc 那条由 T1ProcessTest 的「真进程树」用例钉（那里起的是自己生的进程，
 * 判据是自己那棵树，不借别人的）。
 */
class ProcOpsTest {

    @TempDir
    lateinit var dir: Path

    /** 造一条 `/proc/<pid>/stat`（<pid> 是目录，stat 是它里面的文件 —— 与真 /proc 同形）。 */
    private fun stat(pid: Long, comm: String, state: Char, ppid: Long) {
        val dirPid = dir.resolve(pid.toString())
        Files.createDirectories(dirPid)
        Files.write(
            dirPid.resolve("stat"),
            ("$pid ($comm) $state $ppid 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0\n").toByteArray(),
        )
    }

    private fun ops() = ProcOps(dir)

    @Test
    fun `stat 解析：pid state ppid 三件`() {
        stat(100L, "sh", 'S', 1L)
        val s = ops().readStat(dir.resolve("100"))!!
        assertEquals(100L, s.pid)
        assertEquals('S', s.state)
        assertEquals(1L, s.ppid)
    }

    @Test
    fun `stat 解析：comm 里带空格与右括号也切得对（找最后一个右括号）`() {
        // 进程可以改自己的名字（prctl PR_SET_NAME）—— 唯一稳的切法是最后一个 ')'。
        stat(101L, "weird ) name ( with parens", 'R', 100L)
        val s = ops().readStat(dir.resolve("101"))!!
        assertEquals(100L, s.ppid, "定长字段区从最后一个 ')' 之后数：state 第一、ppid 第二")
        assertEquals('R', s.state)
    }

    @Test
    fun `stat 解析：坏行与非 pid 目录一律跳过不炸`() {
        stat(100L, "ok", 'S', 1L)
        Files.createDirectories(dir.resolve("notapid"))
        Files.write(dir.resolve("notapid").resolve("stat"), "garbage".toByteArray())
        Files.createDirectories(dir.resolve("102"))
        Files.write(dir.resolve("102").resolve("stat"), "102 no-parens-here\n".toByteArray())
        assertNull(ops().readStat(dir.resolve("notapid")), "非数字目录名直接跳")
        assertNull(ops().readStat(dir.resolve("102")), "没有 ')' 的坏行跳过")
    }

    @Test
    fun `后代遍历：ppid 图下行，跨代可达，不含根自己`() {
        // 100 → 101 → 103；100 → 102；200 → 201（无关的树不掺进来）
        stat(100L, "root", 'S', 1L)
        stat(101L, "child", 'S', 100L)
        stat(102L, "child2", 'S', 100L)
        stat(103L, "grandchild", 'S', 101L)
        stat(200L, "other-root", 'S', 1L)
        stat(201L, "other-child", 'S', 200L)

        assertEquals(
            setOf(101L, 102L, 103L),
            ops().descendantsOf(100L),
            "跨代可达（103 经 101），无关树（200/201）不掺进来，根自己不在集合里",
        )
        assertTrue(ops().descendantsOf(103L).isEmpty(), "叶子没有后代")
        assertTrue(ops().descendantsOf(999L).isEmpty(), "不存在的根回空集而不是抛")
    }

    @Test
    fun `pid 取不到时后代为空集（降级为只杀主进程，不炸收尸）`() {
        val fake = object : Process() {
            override fun getOutputStream() = java.io.ByteArrayOutputStream()
            override fun getInputStream() = java.io.ByteArrayInputStream(ByteArray(0))
            override fun getErrorStream() = java.io.ByteArrayInputStream(ByteArray(0))
            override fun waitFor() = 0
            override fun exitValue() = 0
            override fun destroy() = Unit
            override fun destroyForcibly(): Process = this
            override fun isAlive() = false
            // 没有 pid() 方法也没有 pid 字段 —— 模拟旧运行时（Android 13 的 tier 都探不到）
        }
        assertTrue(ops().descendantsOf(fake).isEmpty())
    }

    @Test
    fun `signalName 表：1-31 有名字，0 与 32+ 不猜`() {
        assertEquals("SIGTERM", signalName(15))
        assertEquals("SIGKILL", signalName(9))
        assertEquals("SIGPIPE", signalName(13))
        assertNull(signalName(0))
        assertNull(signalName(32), "32+ 是实时信号，编号各家不同 —— 不猜")
        assertNull(signalName(143 - 128 + 100), "查不到就回 null，调用方拿 code 原样")
    }
}
