package com.autoscript.ui.state

import com.autoscript.domain.npm.NpmConsoleHandle
import com.autoscript.domain.npm.NpmConsoleLine
import com.autoscript.domain.npm.NpmConsoleLineKind
import com.autoscript.domain.npm.NpmConsoleSnapshot
import com.autoscript.domain.npm.NpmPanelSnapshot
import com.autoscript.domain.npm.NpmProjectSnapshot
import com.autoscript.domain.npm.SequencedConsoleLine
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 控制台命令面的操作用例：断的是"界面会怎么做"，不是宿主的行为（那在 `InstallCoordinatorTest`）。
 *
 * 重点四条**不许冒充**：敲错的行**当场拒且不发**（判据与宿主同一份）、读失败保留已读行、
 * 读口未接线 ≠ 没有输出、执行失败**也拉一次**（宿主先落 ECHO 行再抛，不拉就只剩一句错误）。
 */
class ConsoleCmdOpsTest {

    /** 只实现控制台那几条的替身；其余成员继承 [FakeHost] 的响亮失败。 */
    private class CmdHost(
        private val projects: List<String> = listOf("p1"),
        private val failSnapshot: Exception? = null,
        private val failOutput: Exception? = null,
        private val failRun: Exception? = null,
    ) : FakeHost() {
        /** 每次 run 之后往输出环里补的行（模拟宿主的"先落 ECHO 行再抛"）。 */
        var lines: List<SequencedConsoleLine> = emptyList()
        var lastSeq: Long = 0L
        val ran = mutableListOf<Pair<String, String>>()
        var outputCalls = 0

        override suspend fun npmSnapshot(): NpmPanelSnapshot {
            failSnapshot?.let { throw it }
            return NpmPanelSnapshot(
                projects = projects.map {
                    NpmProjectSnapshot(it, emptyList(), emptyList(), null, 512L * 1024 * 1024, 0.8)
                },
                pendingApprovals = emptyList(),
            )
        }

        override suspend fun consoleOutput(projectId: String, sinceSeq: Long, maxLines: Int): NpmConsoleSnapshot {
            outputCalls++
            failOutput?.let { throw it }
            val fresh = lines.filter { it.seq > sinceSeq }
            return NpmConsoleSnapshot(
                firstSeq = fresh.firstOrNull()?.seq ?: sinceSeq,
                lastSeq = fresh.lastOrNull()?.seq ?: sinceSeq,
                lines = fresh,
                running = false,
            )
        }

        override suspend fun runNpmCommand(projectId: String, line: String): NpmConsoleHandle {
            ran += projectId to line
            failRun?.let { throw it }
            return NpmConsoleHandle("con-1", projectId, line, 0L)
        }
    }

    private fun echo(seq: Long, text: String) =
        SequencedConsoleLine(seq, NpmConsoleLine(NpmConsoleLineKind.ECHO, text, 0L))

    @Test
    fun `现取一轮：项目清单与输出来自同一次现取`() = runBlocking {
        val host = CmdHost(projects = listOf("p1", "p2"))
        host.lines = listOf(echo(1L, "$ npm ls"))
        val s = loadConsoleCmd(host, ConsoleCmdState.NOT_LOADED)
        assertTrue(s.load.isLoaded)
        assertEquals("p1", s.project, "默认选第一个项目（面板总要有个主体）")
        assertEquals(listOf("p1", "p2"), s.projects)
        assertEquals(1, s.lines.size)
    }

    @Test
    fun `刷新不把用户正在看的项目换掉`() = runBlocking {
        val host = CmdHost(projects = listOf("p1", "p2"))
        val onP2 = ConsoleCmdState.withProject(loadConsoleCmd(host, ConsoleCmdState.NOT_LOADED), "p2")
        assertEquals("p2", loadConsoleCmd(host, onP2).project)
    }

    @Test
    fun `一个项目都没有时清干净而不是留着上一个项目的行`() = runBlocking {
        val host = CmdHost(projects = emptyList())
        val previous = ConsoleCmdState.NOT_LOADED.copy(
            selectedProjectId = "p1",
            lines = listOf(ConsoleCmdLineState.of(1L, NpmConsoleLine(NpmConsoleLineKind.OUTPUT, "旧输出", 0L), java.time.ZoneId.of("UTC"))),
            nextSeq = 1L,
        )
        val s = loadConsoleCmd(host, previous)
        assertTrue(s.load.isLoaded, "读到了，只是没有项目 —— 这不是失败")
        assertNull(s.project)
        assertTrue(s.lines.isEmpty(), "上一个项目的行不得留着冒充这个项目的输出")
        assertFalse(s.canRun)
    }

    @Test
    fun `读失败保留已读到的行`() = runBlocking {
        val ok = CmdHost()
        ok.lines = listOf(echo(1L, "$ npm install axios"))
        val loaded = loadConsoleCmd(ok, ConsoleCmdState.NOT_LOADED)
        val failed = loadConsoleCmd(CmdHost(failOutput = java.io.IOException("boom")), loaded)
        assertEquals(1, failed.lines.size, "一次瞬时失败不该抹掉用户刚看到的输出")
        assertEquals("boom", failed.loadError)
    }

    @Test
    fun `读口未接线是失败态而不是没有输出`() = runBlocking {
        val s = loadConsoleCmd(null, ConsoleCmdState.NOT_LOADED)
        assertFalse(s.load.isLoaded, "未接线不得画成「读到了，还没有输出」")
        assertTrue("未接线" in (s.loadError ?: ""), "原因要能读出来：${s.loadError}")
    }

    @Test
    fun `敲错的行当场拒且不发`() = runBlocking {
        val host = CmdHost()
        var s = loadConsoleCmd(host, ConsoleCmdState.NOT_LOADED).copy(draft = "rm -rf /")
        s = runConsoleCmd(host, s)
        assertTrue(host.ran.isEmpty(), "解析不过的行一个字节都不该发给宿主")
        assertTrue("只认 npm 与 npx" in (s.opError ?: ""), "拒收原文要点名为什么：${s.opError}")
        assertEquals("rm -rf /", s.draft, "被拒时草稿要留着 —— 清掉等于把用户敲的字吞了")
        assertNull(s.opNotice)
    }

    @Test
    fun `执行成功后清草稿并回执，输出已拉回`() = runBlocking {
        val host = CmdHost()
        val s0 = loadConsoleCmd(host, ConsoleCmdState.NOT_LOADED).copy(draft = "npm ls")
        host.lines = listOf(echo(1L, "$ npm ls"))
        val s = runConsoleCmd(host, s0)
        assertEquals(listOf("p1" to "npm ls"), host.ran)
        assertEquals("", s.draft, "敲过的行已在上方回显，输入框里再留一份会让人以为没执行")
        assertNull(s.opError)
        assertTrue("结果见上方" in (s.opNotice ?: ""), "轻操作的回执要说结果在哪：${s.opNotice}")
        assertEquals(1, s.lines.size, "执行完必须拉一次：宿主是先把 ECHO 行落进环的")
    }

    @Test
    fun `执行失败也拉一次——宿主先落 ECHO 行再抛`() = runBlocking {
        val host = CmdHost(failRun = IllegalStateException("ERR_PERMISSION_DENIED: 未获人工批准（§10.5）：请求已入队，请到管理面板 → 依赖管理的审批卡确认后重试"))
        val s0 = loadConsoleCmd(host, ConsoleCmdState.NOT_LOADED).copy(draft = "npm run build")
        host.lines = listOf(echo(1L, "$ npm run build"))
        val s = runConsoleCmd(host, s0)
        assertEquals(1, s.lines.size, "不拉这一次，用户就只看到一句错误、看不到自己敲的那行")
        assertTrue("审批卡" in (s.opError ?: ""), "失败原文原样透传，界面不另译一遍：${s.opError}")
        assertEquals("npm run build", s.draft, "失败不清草稿：用户多半要改一改再敲")
        assertNull(s.opNotice, "失败不得给回执")
    }

    @Test
    fun `没有项目时拒绝执行并说清为什么`() = runBlocking {
        val host = CmdHost(projects = emptyList())
        val s0 = loadConsoleCmd(host, ConsoleCmdState.NOT_LOADED).copy(draft = "npm ls")
        val s = runConsoleCmd(host, s0)
        assertTrue(host.ran.isEmpty())
        assertTrue("先建一个项目" in (s.opError ?: ""), "要说清下一步：${s.opError}")
    }

    @Test
    fun `换项目走清行归零再拉一轮`() = runBlocking {
        val host = CmdHost(projects = listOf("p1", "p2"))
        host.lines = listOf(echo(9L, "p1 的输出"))
        val onP1 = loadConsoleCmd(host, ConsoleCmdState.NOT_LOADED)
        assertEquals(9L, onP1.nextSeq)

        host.lines = listOf(echo(2L, "p2 早先的输出"))
        val onP2 = selectConsoleProject(host, onP1, "p2")
        assertEquals("p2", onP2.project)
        assertEquals(1, onP2.lines.size)
        assertEquals("p2 早先的输出", onP2.lines.single().text, "归 0 才取得到 seq 更小的那些行")
    }
}
