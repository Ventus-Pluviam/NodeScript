package com.autoscript.ui.state

import com.autoscript.domain.npm.NpmConsoleLine
import com.autoscript.domain.npm.NpmConsoleLineKind
import com.autoscript.domain.npm.NpmConsoleSnapshot
import com.autoscript.domain.npm.SequencedConsoleLine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.ZoneId

/**
 * 控制台（命令面）呈现态的用例：断的是"界面会怎么说"，不是宿主的行为（那在
 * `InstallCoordinatorTest`）。
 *
 * 重点三条**不许冒充**：读失败 ≠ 没有输出（行与游标都要留住）、换项目**必须**清行 +
 * 游标归 0（沿用旧游标会漏掉 seq 更小的行）、`running` 来自宿主而不是界面自己猜。
 */
class ConsoleCmdStateTest {

    private val zone: ZoneId = ZoneId.of("UTC")

    private fun line(
        kind: NpmConsoleLineKind,
        text: String,
        at: Long = 0L,
        ok: Boolean = true,
    ) = NpmConsoleLine(kind = kind, text = text, atMillis = at, ok = ok)

    private fun snap(
        first: Long,
        last: Long,
        lines: List<Pair<Long, NpmConsoleLine>>,
        running: Boolean = false,
    ) = NpmConsoleSnapshot(
        firstSeq = first,
        lastSeq = last,
        lines = lines.map { SequencedConsoleLine(it.first, it.second) },
        running = running,
    )

    @Test
    fun `首帧与读到的空分开`() {
        assertFalse(ConsoleCmdState.NOT_LOADED.load.isLoaded)
        assertFalse(ConsoleCmdState.NOT_LOADED.canRun, "没读到时不许敲")
        assertNull(ConsoleCmdState.NOT_LOADED.project)
    }

    @Test
    fun `行是累积的且按 seq 去重`() {
        val a = ConsoleCmdState.of(
            ConsoleCmdState.NOT_LOADED, "p1",
            snap(1, 2, listOf(1L to line(NpmConsoleLineKind.ECHO, "$ npm ls"), 2L to line(NpmConsoleLineKind.RESULT, "完成"))),
            sinceSeq = 0, maxLines = 256, nowMillis = 1L, zone = zone,
        )
        assertEquals(2, a.lines.size)
        assertEquals(2L, a.nextSeq)
        // 同一游标再拉一遍（并发刷新）：同一批行不得重复入列
        val again = ConsoleCmdState.of(
            a, "p1", snap(1, 2, listOf(1L to line(NpmConsoleLineKind.ECHO, "$ npm ls"), 2L to line(NpmConsoleLineKind.RESULT, "完成"))),
            sinceSeq = 0, maxLines = 256, nowMillis = 2L, zone = zone,
        )
        assertEquals(2, again.lines.size, "同一游标读两遍不该把同一句日志显示两次")
    }

    @Test
    fun `读失败保留已读到的行与游标`() {
        val loaded = ConsoleCmdState.of(
            ConsoleCmdState.NOT_LOADED, "p1",
            snap(1, 3, listOf(1L to line(NpmConsoleLineKind.ECHO, "$ npm install"))),
            sinceSeq = 0, maxLines = 256, nowMillis = 1L, zone = zone,
        ).copy(nextSeq = 3L)
        val failed = ConsoleCmdState.failed(java.io.IOException("boom"), loaded)
        assertFalse(failed.load.isLoaded)
        assertEquals("boom", failed.loadError)
        assertEquals(1, failed.lines.size, "一次瞬时失败不该抹掉用户刚看到的输出")
        assertEquals(3L, failed.nextSeq, "游标留住才续得上；清了下次会重放环里最旧那行")
    }

    @Test
    fun `换项目清行且游标归零——沿用旧游标会漏掉 seq 更小的行`() {
        val onP1 = ConsoleCmdState.of(
            ConsoleCmdState.NOT_LOADED, "p1",
            snap(5, 9, listOf(5L to line(NpmConsoleLineKind.OUTPUT, "p1 的输出"))),
            sinceSeq = 0, maxLines = 256, nowMillis = 1L, zone = zone,
        )
        assertEquals(9L, onP1.nextSeq)
        val onP2 = ConsoleCmdState.withProject(onP1, "p2")
        assertEquals("p2", onP2.project)
        assertTrue(onP2.lines.isEmpty(), "上一个项目的行不得留在屏幕上冒充这个项目的输出")
        assertEquals(0L, onP2.nextSeq, "归 0 才是「把这个项目还留在环里的行全取回来」")
        // 归零后拉一轮：seq 2 的行（对 p1 的游标 9 来说是「旧」的）必须取得到
        val after = ConsoleCmdState.of(
            onP2, "p2", snap(2, 2, listOf(2L to line(NpmConsoleLineKind.OUTPUT, "p2 早先的输出"))),
            sinceSeq = 0, maxLines = 256, nowMillis = 2L, zone = zone,
        )
        assertEquals(1, after.lines.size)
        assertEquals("p2 早先的输出", after.lines.single().text)
    }

    @Test
    fun `换到同一个项目不清行`() {
        val s = ConsoleCmdState.of(
            ConsoleCmdState.NOT_LOADED, "p1",
            snap(1, 1, listOf(1L to line(NpmConsoleLineKind.ECHO, "$ npm ls"))),
            sinceSeq = 0, maxLines = 256, nowMillis = 1L, zone = zone,
        )
        assertTrue(ConsoleCmdState.withProject(s, "p1").lines.isNotEmpty())
    }

    @Test
    fun `丢包留洞可见：first 大于发请求前游标加一`() {
        val s = ConsoleCmdState.of(
            ConsoleCmdState.NOT_LOADED, "p1",
            snap(40, 41, listOf(40L to line(NpmConsoleLineKind.OUTPUT, "x"), 41L to line(NpmConsoleLineKind.OUTPUT, "y"))),
            sinceSeq = 10, maxLines = 256, nowMillis = 1L, zone = zone,
        )
        assertTrue(s.gap, "中间丢过（40 > 10+1）必须说出来，不静默断流")
        assertFalse(s.pageFull)
    }

    @Test
    fun `首读也判洞：环里最旧那行之前的东西已经没了`() {
        val s = ConsoleCmdState.of(
            ConsoleCmdState.NOT_LOADED, "p1",
            snap(3, 4, listOf(3L to line(NpmConsoleLineKind.OUTPUT, "x"))),
            sinceSeq = 0, maxLines = 256, nowMillis = 1L, zone = zone,
        )
        assertTrue(s.gap, "首读 first=3 说明前两行已滚出环，那不是「从头开始」")
    }

    @Test
    fun `拉满提示按本次要的条数判，不猜常量`() {
        val lines = (1L..4L).map { it to line(NpmConsoleLineKind.OUTPUT, "行 $it") }
        val full = ConsoleCmdState.of(
            ConsoleCmdState.NOT_LOADED, "p1", snap(1, 4, lines),
            sinceSeq = 0, maxLines = 4, nowMillis = 1L, zone = zone,
        )
        assertTrue(full.pageFull)
        val notFull = ConsoleCmdState.of(
            ConsoleCmdState.NOT_LOADED, "p1", snap(1, 4, lines),
            sinceSeq = 0, maxLines = 256, nowMillis = 1L, zone = zone,
        )
        assertFalse(notFull.pageFull, "4 条远没到 256，不该说「可能还有」")
    }

    @Test
    fun `running 来自宿主——界面不猜`() {
        val idle = ConsoleCmdState.of(
            ConsoleCmdState.NOT_LOADED, "p1", snap(1, 1, listOf(1L to line(NpmConsoleLineKind.ECHO, "$ npm ci"))),
            sinceSeq = 0, maxLines = 256, nowMillis = 1L, zone = zone,
        )
        assertFalse(idle.running)
        assertTrue(idle.canRun, "没在跑就能敲下一行")

        val busy = ConsoleCmdState.of(
            idle, "p1", snap(1, 1, listOf(1L to line(NpmConsoleLineKind.ECHO, "$ npm ci")), running = true),
            sinceSeq = 1, maxLines = 256, nowMillis = 2L, zone = zone,
        )
        assertTrue(busy.running)
        assertFalse(busy.canRun, "宿主说在跑就禁用输入行：灰按钮说明「现在不能敲」")
    }

    @Test
    fun `着色由 kind 与成败定，不按文本猜`() {
        fun tone(kind: NpmConsoleLineKind, ok: Boolean = true) =
            ConsoleCmdLineState.of(1L, line(kind, "任意文本", ok = ok), zone).tone
        assertEquals(StatusTone.MUTED, tone(NpmConsoleLineKind.ECHO))
        assertEquals(StatusTone.MUTED, tone(NpmConsoleLineKind.PHASE))
        assertEquals(StatusTone.NEUTRAL, tone(NpmConsoleLineKind.OUTPUT))
        assertEquals(StatusTone.ATTENTION, tone(NpmConsoleLineKind.WARNING))
        assertEquals(StatusTone.NEUTRAL, tone(NpmConsoleLineKind.RESULT, ok = true))
        assertEquals(StatusTone.PROBLEM, tone(NpmConsoleLineKind.RESULT, ok = false))
    }

    @Test
    fun `时间戳在这一层格式化`() {
        val s = ConsoleCmdLineState.of(
            1L,
            line(NpmConsoleLineKind.OUTPUT, "x", at = 0L),
            ZoneId.of("UTC"),
        )
        assertEquals("00:00:00", s.timeText)
    }
}
