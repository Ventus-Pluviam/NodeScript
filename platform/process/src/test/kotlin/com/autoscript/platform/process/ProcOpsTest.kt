package com.autoscript.platform.process

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * [ProcOps] 的 `/proc` 面（批 105 从 `:app-service:npm` 与 `:platform:system` 两份合并到
 * 本模块的**唯一一份**测试 —— 合并前 npm 那侧有 [ProcOpsTest]、platform 那侧只有一条
 * 走真进程的用例，合进来的判据取两边并集）。
 *
 * **喂的是合成的 `/proc` 树**（@TempDir），不借宿主 `/proc` 判状态 —— 借宿主的判据在
 * CI 上是假绿（本仓有过教训：pid=4242 借宿主 `/proc` 判死，本机全绿 CI 红）。
 * 真 `/proc` 那条由两侧调用方自己的「真进程树」用例钉（那里起的是自己生的进程，
 * 判据是自己那棵树，不借别人的）。
 *
 * 「真进程树」那条为什么留在调用方而不搬来：它们要连同**调用方的收尸判据**
 * （TERM→KILL 的顺序、二次收割）一起验，搬过来就只剩 `/proc` 面自己，而那已经被
 * 合成树的三例钉住了。
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
            ("$pid ($comm) $state $ppid 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0\n").toByteArray(),
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

    /**
     * 二次收割的判据（批 95 的 T1 用，批 99 的 shell 不用 —— 它没有宽限期这一档）。
     *
     * 形态照抄 T1 那条真实判据：第一轮扫到 101；**然后** 101 在宽限期内新起 103（合成树里
     * 就是多写一个 stat 文件），第二轮必须从**仍活着的 101** 捡到它 —— 从主进程 100 再扫
     * 也能捡到，那不叫二次收割、那叫第一轮没扫干净。
     */
    @Test
    fun `二次收割：从仍活着的成员再扫一轮，捡到宽限期内新生的后代`() {
        stat(100L, "root", 'S', 1L)
        stat(101L, "child", 'S', 100L)

        // 第一轮按 rootPid 走的是 [ProcOps]（[ProcTreeOps] 的 Process 重载只在有 Process 对象时用）
        val ops = ProcTreeOps(ProcOps(dir))
        assertEquals(setOf(101L), ProcOps(dir).descendantsOf(100L), "第一轮只有 101（此刻 103 还没起）")
        assertTrue(ops.descendantsOf(setOf(101L)).isEmpty(), "第二轮此刻也无新东西")

        stat(103L, "grandchild", 'S', 101L)   // 宽限期内 101 新起了 103

        assertEquals(setOf(103L), ops.descendantsOf(setOf(101L)), "从活着的 101 再扫一轮必须捡到 103")
        assertTrue(ops.alive(101L), "101 在合成树里就是活着的（alive 走注入的 procDir，不查宿主）")
        assertTrue(!ops.alive(4242L), "不在树里的 pid 判死（与「借宿主 /proc 判状态」同一条纪律）")
    }
}
