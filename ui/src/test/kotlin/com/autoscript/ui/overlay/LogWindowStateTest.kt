package com.autoscript.ui.overlay

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

    /**
     * 拖动：位移相加 + 夹取（2026-10-13 用户报"不跟手"时补的）。
     *
     * 钉的是**夹取后不许把窗推出可视区** —— 拖丢了标题栏就再也拖不回来，
     * 而窗外没有任何补救入口。
     */
    @Test
    fun `movedTo 位移相加`() {
        val s = LogWindowState(projectId = "demo", relPath = "a.js")
            .movedTo(10f, 20f)
            .movedTo(5f, -4f)
        assertEquals(15f, s.x)
        assertEquals(16f, s.y)
    }

    @Test
    fun `movedTo 夹在可视区内`() {
        val s = LogWindowState(projectId = "demo", relPath = "a.js")
        // 边界 400x500，窗 340x420 → x 最多 60、y 最多 80。
        val far = s.movedTo(999f, 999f, boundsWidth = 400f, boundsHeight = 500f)
        assertEquals(60f, far.x)
        assertEquals(80f, far.y)
        val back = far.movedTo(-999f, -999f, boundsWidth = 400f, boundsHeight = 500f)
        assertEquals(0f, back.x)
        assertEquals(0f, back.y)
    }

    /** 边界未知（拿不到屏幕尺寸）→ 不夹：不假装知道屏幕多大。 */
    @Test
    fun `movedTo 无边界时不夹`() {
        val s = LogWindowState(projectId = "demo", relPath = "a.js").movedTo(9999f, 9999f)
        assertEquals(9999f, s.x)
        assertEquals(9999f, s.y)
    }

    /** 边界比窗还小（极端小屏）→ 夹到 0 而不是负数（负数会把窗推出左上角）。 */
    @Test
    fun `movedTo 边界小于窗时夹到零`() {
        val s = LogWindowState(projectId = "demo", relPath = "a.js")
            .movedTo(50f, 50f, boundsWidth = 100f, boundsHeight = 100f)
        assertEquals(0f, s.x)
        assertEquals(0f, s.y)
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

}
