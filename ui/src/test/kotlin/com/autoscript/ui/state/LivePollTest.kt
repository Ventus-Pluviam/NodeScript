package com.autoscript.ui.state

import com.autoscript.domain.npm.InstallEvent
import com.autoscript.domain.npm.InstallEventBatch
import com.autoscript.domain.npm.NpmConsoleHandle
import com.autoscript.domain.npm.NpmConsoleLine
import com.autoscript.domain.npm.NpmConsoleLineKind
import com.autoscript.domain.npm.NpmConsoleSnapshot
import com.autoscript.domain.npm.NpmPanelSnapshot
import com.autoscript.domain.npm.NpmProjectSnapshot
import com.autoscript.domain.npm.SequencedConsoleLine
import com.autoscript.domain.npm.SequencedInstallEvent
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 「有东西在跑就自己拉」那条循环（2026-10-10 批 90）。
 *
 * 断的是两件在真机上看得见的事：
 * - 控制台敲完一行之后，**跑到一半的输出会自己出现**（不必用户点刷新）；
 * - 依赖面板的阶段条会从 `QUEUED` **自己走到** `DONE`。
 *
 * 以及两条自我约束：**有界**（宿主永远说在跑也不能无限问下去）、
 * **不在看的页面不轮询**（那一条在 `MainActivity` 的效应键里，这里钉的是它的前提：
 * `shouldContinue` 读的是宿主给的事实，不是界面猜的）。
 */
class LivePollTest {

    /** 假宿主：输出/事件各喂一串，`running` 由用例控制（模拟句柄账）。 */
    private class LiveHost(
        private val consoleBatches: List<Pair<List<SequencedConsoleLine>, Boolean>>,
        private val eventBatches: List<List<InstallEvent>> = emptyList(),
    ) : FakeHost() {
        var outputCalls = 0
        var eventCalls = 0

        override suspend fun npmSnapshot(): NpmPanelSnapshot = NpmPanelSnapshot(
            projects = listOf(NpmProjectSnapshot("p1", emptyList(), emptyList(), null, 512L * 1024 * 1024, 0.8)),
            pendingApprovals = emptyList(),
        )

        override suspend fun consoleOutput(projectId: String, sinceSeq: Long, maxLines: Int): NpmConsoleSnapshot {
            val idx = outputCalls.coerceAtMost(consoleBatches.lastIndex)
            outputCalls++
            val (lines, running) = consoleBatches[idx]
            val fresh = lines.filter { it.seq > sinceSeq }
            return NpmConsoleSnapshot(
                firstSeq = fresh.firstOrNull()?.seq ?: sinceSeq,
                lastSeq = fresh.lastOrNull()?.seq ?: sinceSeq,
                lines = fresh,
                running = running,
            )
        }

        override suspend fun runNpmCommand(projectId: String, line: String): NpmConsoleHandle =
            NpmConsoleHandle("con-1", projectId, line, 0L)

        override suspend fun npmInstallEvents(projectId: String, sinceSeq: Long, maxBatch: Int): InstallEventBatch {
            if (eventBatches.isEmpty()) return InstallEventBatch(sinceSeq, sinceSeq, emptyList())
            val idx = eventCalls.coerceAtMost(eventBatches.lastIndex)
            eventCalls++
            val events = eventBatches[idx].mapIndexed { i, e -> SequencedInstallEvent(sinceSeq + i + 1, e) }
            val last = events.lastOrNull()?.seq ?: sinceSeq
            // 批次用完之后回空增量（`coerceAtMost` 把 idx 钉在最后一格）——
            // 「还跑不跑」由 `Finished` 事件自己带（见 `pollInstallWhileRunning` 的 KDoc：
            // 句柄账在 `NpmState.installing` 上，不由这个替身另编一个开关）。
            return InstallEventBatch(sinceSeq, last, events)
        }
    }

    private fun line(seq: Long, text: String, kind: NpmConsoleLineKind = NpmConsoleLineKind.OUTPUT) =
        SequencedConsoleLine(seq, NpmConsoleLine(kind, text, 0L))

    private val projects = NpmPanelSnapshot(
        projects = listOf(NpmProjectSnapshot("p1", emptyList(), emptyList(), null, 512L * 1024 * 1024, 0.8)),
        pendingApprovals = emptyList(),
    )

    @Test
    fun `命令跑着的时候自己拉：跑到一半的输出会自己出现`() = runBlocking {
        val host = LiveHost(
            consoleBatches = listOf(
                listOf(line(1, "$ npm install axios", NpmConsoleLineKind.ECHO)) to true,
                listOf(line(2, "added 1 package", NpmConsoleLineKind.OUTPUT)) to true,
                listOf(line(3, "npm install 完成", NpmConsoleLineKind.RESULT)) to false,
            ),
        )
        val state = ConsoleCmdState(
            load = LoadState.Loaded,
            selectedProjectId = "p1",
            projects = listOf("p1"),
        )
        // 第一次现取由调用方做（`runConsoleCmd` 里就是 `loadConsoleCmd` 那一下）——
        // 轮询本身只负责「已经知道在跑之后继续拉」。
        val out = pollConsoleWhileRunning(host, loadConsoleCmd(host, state), intervalMillis = 1L, maxTicks = 5)
        assertEquals(
            listOf("$ npm install axios", "added 1 package", "npm install 完成"),
            out.lines.map { it.text },
            "三轮都要被拉到（用户不点刷新也看得到）",
        )
        assertFalse(out.running, "停下来的事实来自宿主（最后一轮 running=false）")
        assertTrue(host.outputCalls >= 3, "拉了三轮以上（实为 ${host.outputCalls}）")
    }

    @Test
    fun `提交之后跟到跑完：不需要用户点刷新`() = runBlocking {
        val host = LiveHost(
            consoleBatches = listOf(
                listOf(line(1, "$ npm install axios", NpmConsoleLineKind.ECHO)) to true,
                listOf(line(2, "npm install 完成", NpmConsoleLineKind.RESULT)) to false,
            ),
        )
        val state = ConsoleCmdState(
            load = LoadState.Loaded,
            selectedProjectId = "p1",
            projects = listOf("p1"),
            draft = "npm install axios",
        )
        val out = runConsoleCmd(host, state, intervalMillis = 1L, maxTicks = 8)
        assertTrue(
            out.lines.any { it.text == "npm install 完成" },
            "终态行必须在这一次调用里就拉到（实为 ${out.lines.map { it.text }}）",
        )
        assertEquals("", out.draft, "成功才清草稿")
    }

    @Test
    fun `宿主永远说在跑时循环有界（不会一直问下去）`() = runBlocking {
        val host = LiveHost(consoleBatches = listOf(emptyList<SequencedConsoleLine>() to true))
        val state = ConsoleCmdState(load = LoadState.Loaded, selectedProjectId = "p1", projects = listOf("p1"))
        val out = pollConsoleWhileRunning(host, loadConsoleCmd(host, state), intervalMillis = 1L, maxTicks = 5)
        assertEquals(
            6,
            host.outputCalls,
            "首轮一次 + 循环 maxTicks(5) 次（实为 ${host.outputCalls}）—— 有界是硬约束",
        )
        assertTrue(out.running, "停是因为到上限，不是因为宿主说停了 —— 状态如实保留")
    }

    @Test
    fun `阶段条自己从 QUEUED 走到 DONE`() = runBlocking {
        val host = LiveHost(
            consoleBatches = listOf(emptyList<SequencedConsoleLine>() to false),
            eventBatches = listOf(
                listOf(InstallEvent.Progress("p1", "h1", InstallEvent.Phase.QUEUED)),
                listOf(InstallEvent.Progress("p1", "h1", InstallEvent.Phase.DOWNLOAD)),
                listOf(InstallEvent.Progress("p1", "h1", InstallEvent.Phase.REIFY)),
                listOf(InstallEvent.Finished("p1", "h1", success = true, detail = "ok")),
            ),
        )
        val state = NpmState(
            load = LoadState.Loaded,
            selectedProjectId = "p1",
            installing = true,
            installProgress = InstallProgressState(InstallEvent.Phase.QUEUED),
        )
        val out = pollInstallWhileRunning(host, state, "p1", intervalMillis = 1L, maxTicks = 16)
        assertEquals(InstallEvent.Phase.REIFY, out.installProgress?.phase, "阶段条走到了 REIFY")
        assertTrue(out.installProgress?.done == true, "Finished 到了就收条（不再显示「进行中」）")
        assertTrue(out.installProgress?.ok == true)
        assertFalse(
            out.installing,
            "「在途」随 Finished 一起落 —— 否则轮询会一直转到上限，输入行一直是灰的",
        )
    }
}
