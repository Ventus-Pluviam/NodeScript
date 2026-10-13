package com.autoscript.ui.overlay

import com.autoscript.domain.engine.EngineStatus
import com.autoscript.ui.state.ActiveRunState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 悬浮窗纯状态（2026-10-13）：绑定 / 水位 / 标题归一。
 *
 * 钉的是"窗里看到的是谁" —— 绑错 run 等于把 A 脚本的输出说成 B 的，
 * 水位倒退等于"清空了又回来"，都是静默地不对。
 */
class LogWindowStateTest {

    private fun run(
        runId: Long,
        projectId: String = "demo",
        scriptPath: String = "data/test.js",
        paused: Boolean = false,
    ) = ActiveRunState(
        runId = runId,
        hostLabel = "运行中",
        poolLabel = "运行中",
        drift = false,
        projectId = projectId,
        scriptPath = scriptPath,
        paused = paused,
    )

    @Test
    fun `标题是斜杠加项目加路径`() {
        val s = LogWindowState(projectId = "demo", relPath = "data/test.js")
        assertEquals("/demo/data/test.js", s.title)
    }

    @Test
    fun `bind 按归属取最大 runId 并同步 paused`() {
        val s = LogWindowState(projectId = "demo", relPath = "data/test.js")
        val bound = s.bind(
            listOf(
                run(1L),
                run(2L, scriptPath = "other.js"),
                run(3L, paused = true),
            ),
        )
        assertEquals(3L, bound.boundRunId, "同归属取最大 = 最近一次触发")
        assertTrue(bound.paused)
    }

    @Test
    fun `bind 无同归属则解绑且 paused 回 false`() {
        val s = LogWindowState(projectId = "demo", relPath = "data/test.js")
            .bind(listOf(run(1L)))
        assertEquals(1L, s.boundRunId)
        val unbound = s.bind(listOf(run(2L, scriptPath = "other.js")))
        assertEquals(null, unbound.boundRunId, "启动失败/已结算 → 空态，不留僵尸绑定")
        assertFalse(unbound.paused)
    }

    @Test
    fun `visible 只留绑定 run 水位之上的行`() {
        val s = LogWindowState(projectId = "demo", relPath = "data/test.js", watermarkSeq = 5L)
            .bind(listOf(run(7L)))
        val lines = listOf(
            line(seq = 4L, runId = 7L),
            line(seq = 6L, runId = 7L),
            line(seq = 7L, runId = 7L),
            line(seq = 8L, runId = 9L),
        )
        assertEquals(listOf(6L, 7L), s.visible(lines).map { it.seq })
    }

    @Test
    fun `visible 未绑定回空表`() {
        val s = LogWindowState(projectId = "demo", relPath = "data/test.js")
        assertTrue(s.visible(listOf(line(1L, 7L))).isEmpty())
    }

    @Test
    fun `ext 取最后一段最后一个点之后小写`() {
        assertEquals("js", LogWindowState(projectId = "p", relPath = "a/b.C.JS".lowercase()).ext)
        assertEquals(
            "js",
            LogWindowState(projectId = "p", relPath = "lib/main.js").ext,
        )
        assertEquals("", LogWindowState(projectId = "p", relPath = "Makefile").ext)
    }

    private fun line(seq: Long, runId: Long) = com.autoscript.ui.state.ConsoleLineState(
        seq = seq,
        runId = runId,
        external = false,
        level = "log",
        levelLabel = "日志",
        text = "t$seq",
        timeText = "00:00:00",
    )

    @Suppress("UNUSED_EXPRESSION")
    private val engineStatusRef = EngineStatus.RUNNING
}
