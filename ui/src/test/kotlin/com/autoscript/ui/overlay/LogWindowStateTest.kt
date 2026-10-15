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
        assertEquals("/demo/data/test.js", s.titleText)
    }

    @Test
    fun `bind 按归属取最大 runId 并同步 paused 与 running`() {
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
        assertTrue(bound.running, "在途 = 正在跑")
    }

    @Test
    fun `从没绑上过（启动失败）→ 空态且 paused 回 false`() {
        val s = LogWindowState(projectId = "demo", relPath = "data/test.js")
        val unbound = s.bind(listOf(run(2L, scriptPath = "other.js")))
        assertEquals(null, unbound.boundRunId, "启动失败/门禁拒（从开窗起一次都没绑上）→ 空态")
        assertFalse(unbound.paused)
    }

    /**
     * 2026-10-15 用户令「有时点执行日志不显示在悬浮窗上」：**起跑即结算的快速脚本**。
     *
     * 脚本整个生命周期落在两轮轮询（400ms）之间 —— 在途表从开窗起一次都没见过它，
     * 「结算不解绑」救不到（它从没绑上过）。兜底补绑从行里摘归属：[fallbackRunId]
     * 只在「没绑上过」时补位；已绑过的 keep 不被行摘顶掉（同归属重复触发时行按实际归属画）。
     */
    @Test
    fun `bind 没绑上过但行里有归属 兜底补绑`() {
        val s = LogWindowState(projectId = "demo", relPath = "data/test.js")
        val bound = s.bind(emptyList(), fallbackRunId = 12L)
        assertEquals(12L, bound.boundRunId, "行里摘出的归属补位，窗口不落空态")
        assertFalse(bound.running, "补绑不代表在途")
        assertFalse(bound.paused)
        // 补绑后同归属行照常可见；标题**只写路径**（2026-10-15 用户令去掉「· 已结束」）。
        val lines = listOf(line(seq = 1L, runId = 12L), line(seq = 2L, runId = 12L))
        assertEquals(listOf(1L, 2L), bound.visible(lines).map { it.seq })
        assertEquals("/demo/data/test.js", bound.titleText)
    }

    @Test
    fun `bind 已绑过时行摘不顶掉 keep`() {
        val s = LogWindowState(projectId = "demo", relPath = "data/test.js")
            .bind(listOf(run(7L)), fallbackRunId = 12L)
        val next = s.bind(emptyList(), fallbackRunId = 12L)
        assertEquals(7L, next.boundRunId, "结算不解绑优先于行摘补绑")
        assertFalse(next.running)
    }

    @Test
    fun `bind 引擎外行号不进补位 仍空态`() {
        val s = LogWindowState(projectId = "demo", relPath = "data/test.js")
        val bound = s.bind(emptyList(), fallbackRunId = 0L)
        assertEquals(null, bound.boundRunId, "引擎外行（runId 0）不是可补的归属")
    }

    @Test
    fun `bind 兜底补位号不假充在途 runId`() {
        val s = LogWindowState(projectId = "demo", relPath = "data/test.js")
        val bound = s.bind(listOf(run(5L, scriptPath = "other.js")), fallbackRunId = 12L)
        assertEquals(12L, bound.boundRunId)
        assertFalse(bound.running, "补位号只画日志，不冒充在途")
        // 下一轮同归属在途出现 → 换到真在途（补位不挡真实绑定）。
        val next = bound.bind(listOf(run(13L)))
        assertEquals(13L, next.boundRunId)
        assertTrue(next.running)
    }

    @Test
    fun `bind 未绑上且无补位 落空态`() {
        val s = LogWindowState(projectId = "demo", relPath = "data/test.js")
        val unbound = s.bind(emptyList(), fallbackRunId = null)
        assertEquals(null, unbound.boundRunId)
    }

    /**
     * 2026-10-15 用户令「脚本执行完不要自动清理输出」：**结算不解绑**。
     *
     * 在途表是"正在跑的"这一句 —— run 跑完离开在途表是执行完成，不是「没有这回事」；
     * 一结算就把窗口切回「本次执行未开始」空态，等于把刚跑完的日志在用户眼前自动清掉。
     * 窗口保持绑在最后一条同归属 run 上，输出照常可见。只有「从开窗起一次都没绑上过」
     * （启动失败/门禁拒）才落空态。
     */
    @Test
    fun `bind 结算后保持绑定 输出照常可见`() {
        val s = LogWindowState(projectId = "demo", relPath = "data/test.js")
            .bind(listOf(run(7L)))
        assertEquals(7L, s.boundRunId)
        assertTrue(s.running)
        // 7 号跑完离开在途表（同归属的空在途表 / 别的归属）：绑定保持、running 回 false。
        val done = s.bind(emptyList())
        assertEquals(7L, done.boundRunId, "结算不解绑：窗口仍是刚跑完的那条")
        assertFalse(done.running, "已结算如实标记")
        assertFalse(done.paused)
        // 别的归属出现也不抢绑定（不是新触发）；已在途的 keep 仍会被选回（绑定不丢）。
        val done2 = done.bind(listOf(run(8L, scriptPath = "other.js"), run(7L)))
        assertEquals(7L, done2.boundRunId, "别的归属出现也不抢绑定；已在途的 keep 仍绑回")
        // 有行照常可见（水位之上）；没有同归属也不画空态。
        val lines = listOf(line(seq = 1L, runId = 7L), line(seq = 2L, runId = 7L))
        assertEquals(listOf(1L, 2L), done.visible(lines).map { it.seq })
        // 标题**恒为路径**（2026-10-15 用户令）：跑完/在途/未开始三种态标题一样 ——
        // 「在不在跑」由第一颗钮（暂停/继续/重跑三态）自己说，不在路径栏重复。
        assertEquals("/demo/data/test.js", done.titleText)
        assertEquals("/demo/data/test.js", s.titleText)
    }

    @Test
    fun `bind 结算后同归属新 run 换绑到新那条`() {
        val s = LogWindowState(projectId = "demo", relPath = "data/test.js")
            .bind(listOf(run(7L)))
            .bind(emptyList()) // 7 号跑完
        val next = s.bind(listOf(run(9L)))
        assertEquals(9L, next.boundRunId, "同归属新起一条 → 绑到新的（最近一次触发）")
        assertTrue(next.running)
    }

    @Test
    fun `标题恒为路径——三态都不缀后缀（用户令）`() {
        val s = LogWindowState(projectId = "demo", relPath = "data/test.js")
        assertEquals("/demo/data/test.js", s.titleText, "未开始")
        assertEquals("/demo/data/test.js", s.bind(listOf(run(3L))).titleText, "在途")
        assertEquals(
            "/demo/data/test.js",
            s.bind(listOf(run(3L))).bind(emptyList()).titleText,
            "已结算：路径栏只写路径（用户令「不需要加已结束三个字」）",
        )
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
