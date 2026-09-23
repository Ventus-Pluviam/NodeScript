package com.autoscript.ui

import com.autoscript.domain.engine.EngineStatus
import com.autoscript.domain.host.ActiveRunRow
import com.autoscript.domain.host.ConsoleLineRow
import com.autoscript.domain.host.ConsoleSnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * 控制台呈现态（纯逻辑，不碰 Compose —— :ui 的 JVM 门走 `:ui:testDebugUnitTest`）。
 *
 * 钉住的每一件都对应一种"控制台在撒谎"的形态：
 * - **没读到 ≠ 暂无日志**，失败 ≠ 没读到；
 * - **行是累积的、游标只进不退**，失败保留旧行（瞬时失败抹日志比报错更糟）；
 * - 同一批读两遍不重复入列（并发刷新）；
 * - 三样现值（拉满/丢包/在途）每次现取不累积；
 * - 宿主状态**读不到就是 null**，不渲染成某个状态。
 */
class ConsoleStateTest {

    private fun row(
        seq: Long,
        runId: Long = 7L,
        level: String = "log",
        text: String = "行$seq",
        atMillis: Long = 1_700_000_000_000L,
    ) = ConsoleLineRow(seq = seq, runId = runId, level = level, text = text, atMillis = atMillis)

    private fun snap(
        lines: List<ConsoleLineRow> = emptyList(),
        nextSeq: Long = 0L,
        pageFull: Boolean = false,
        droppedTotal: Long = 0L,
        activeRuns: List<ActiveRunRow> = emptyList(),
    ) = ConsoleSnapshot(
        lines = lines,
        nextSeq = nextSeq,
        pageFull = pageFull,
        droppedTotal = droppedTotal,
        activeRuns = activeRuns,
    )

    @Test
    fun `首帧哨兵是未读取 不是暂无日志`() {
        val s = ConsoleState.NOT_LOADED
        assertFalse(s.loaded)
        assertNull(s.loadError, "没读过 ≠ 读失败：两者分开，别拿一个句子盖住两种事实")
        assertTrue(s.lines.isEmpty())
        assertEquals(0L, s.nextSeq)
    }

    @Test
    fun `读失败带原异常文案 且保留旧行与游标`() {
        val previous = ConsoleState.of(
            previous = ConsoleState.NOT_LOADED,
            added = snap(lines = listOf(row(1), row(2)), nextSeq = 2L),
            nowMillis = 1L,
        )
        val failed = ConsoleState.failed(IllegalStateException("壳未装配"), previous)
        assertFalse(failed.loaded)
        assertEquals("壳未装配", failed.loadError, "原异常文案是区分「壳未装配」与「读崩了」的唯一线索")
        assertEquals(2, failed.lines.size, "瞬时失败不清缓冲：用户已经看到的日志不该被一次失败抹掉")
        assertEquals(2L, failed.nextSeq, "游标不清零 —— 下次从上次成功处续拉，不重读也不跳行")
        assertEquals(2L, failed.lines.last().seq)
    }

    @Test
    fun `异常无 message 时退到类名 不显示 null`() {
        val failed = ConsoleState.failed(RuntimeException(), ConsoleState.NOT_LOADED)
        assertEquals("RuntimeException", failed.loadError, "loadError=null 会被渲染成「尚未读取」，把失败说成没读")
    }

    @Test
    fun `读到的空批与未读取分得开`() {
        val empty = ConsoleState.of(ConsoleState.NOT_LOADED, snap(), nowMillis = 1L)
        assertTrue(empty.loaded, "读成功且真的没输出：用户该等脚本打日志，而不是重试读取")
        assertNull(empty.loadError)
        assertTrue(empty.lines.isEmpty())
        assertFalse(ConsoleState.NOT_LOADED.loaded)
    }

    @Test
    fun `行累积入列 游标推进`() {
        val first = ConsoleState.of(
            ConsoleState.NOT_LOADED,
            snap(lines = listOf(row(1), row(2)), nextSeq = 2L),
            nowMillis = 1L,
        )
        val second = ConsoleState.of(
            first,
            snap(lines = listOf(row(3)), nextSeq = 3L),
            nowMillis = 2L,
        )
        assertEquals(listOf(1L, 2L, 3L), second.lines.map { it.seq }, "控制台是累计事实：刷新 = 增量拉取，不是重画")
        assertEquals(3L, second.nextSeq)
        assertTrue(second.loaded)
        assertNull(second.loadError)
    }

    @Test
    fun `同一批读两遍按 seq 去重`() {
        val batch = snap(lines = listOf(row(1), row(2)), nextSeq = 2L)
        val once = ConsoleState.of(ConsoleState.NOT_LOADED, batch, nowMillis = 1L)
        // 并发刷新：两批从同一游标拉到同样的行。
        val twice = ConsoleState.of(once, batch, nowMillis = 2L)
        assertEquals(2, twice.lines.size, "重复入列 = 同一句日志在控制台出现两遍")
    }

    @Test
    fun `三样现值每次现取 不拿旧值当现状`() {
        val loud = ConsoleState.of(
            ConsoleState.NOT_LOADED,
            snap(
                lines = listOf(row(1)),
                nextSeq = 1L,
                pageFull = true,
                droppedTotal = 9L,
                activeRuns = listOf(ActiveRunRow(1L, null, EngineStatus.RUNNING, true)),
            ),
            nowMillis = 1L,
        )
        assertTrue(loud.pageFull)
        assertEquals(9L, loud.droppedTotal)
        assertEquals(1, loud.activeRuns.size)

        val quiet = ConsoleState.of(loud, snap(), nowMillis = 2L)
        assertFalse(quiet.pageFull, "拉满/丢包/在途答的是「此刻」—— 留旧值就是把过期事实当现状")
        assertEquals(0L, quiet.droppedTotal)
        assertTrue(quiet.activeRuns.isEmpty())
        assertTrue(quiet.lines.isNotEmpty(), "行是累积的（与三样现值相反）：两种语义不许混")
    }

    @Test
    fun `注入的时刻进状态 类内不现取时间`() {
        val zone = ZoneId.of("UTC")
        val s = ConsoleState.of(ConsoleState.NOT_LOADED, snap(), nowMillis = 1234L, zone = zone)
        assertEquals(1234L, s.nowMillis, "类内读 currentTimeMillis = 相对时间不可测")
        assertEquals(zone, s.zone)
    }

    @Test
    fun `行投影 归属 0 显示为引擎外 时间带秒`() {
        val zone = ZoneId.of("UTC")
        val at = ZonedDateTime.of(2026, 1, 2, 3, 4, 5, 0, ZoneOffset.UTC).toInstant().toEpochMilli()
        val s = ConsoleState.of(
            ConsoleState.NOT_LOADED,
            snap(
                lines = listOf(
                    row(1, runId = 0L, level = "error", text = "宿主侧", atMillis = at),
                    row(2, runId = 42L, level = "log", text = "引擎侧", atMillis = at),
                ),
                nextSeq = 2L,
            ),
            nowMillis = 1L,
            zone = zone,
        )
        val external = s.lines.first { it.text == "宿主侧" }
        assertTrue(external.external, "runId=0 是引擎外日志，不是「第 0 次执行」—— 呈现层显示「引擎外」")
        assertEquals("错误", external.levelLabel)
        assertEquals("error", external.level, "原值保留 —— 未知级别不翻译也不丢")
        assertEquals("03:04:05", external.timeText, "控制台行以秒为粒度（MM-dd HH:mm 分辨不出同分钟先后）")
        val engine = s.lines.first { it.text == "引擎侧" }
        assertFalse(engine.external)
        assertEquals(42L, engine.runId)
    }

    @Test
    fun `级别文案逐级 未知原样`() {
        assertEquals("错误", LevelText.describe("error"))
        assertEquals("警告", LevelText.describe("warn"))
        assertEquals("信息", LevelText.describe("info"))
        assertEquals("日志", LevelText.describe("log"))
        assertEquals("日志", LevelText.describe("debug"))
        assertEquals("trace", LevelText.describe("trace"), "未知级别原样：编一个「日志」标签会抹掉现场值")
    }

    @Test
    fun `引擎状态六态各一句 当值穷尽`() {
        assertEquals("空闲", EngineStatusText.describe(EngineStatus.IDLE))
        assertEquals("启动中", EngineStatusText.describe(EngineStatus.BOOTING))
        assertEquals("运行中", EngineStatusText.describe(EngineStatus.RUNNING))
        assertEquals("排空中", EngineStatusText.describe(EngineStatus.QUIESCING))
        assertEquals("已停止", EngineStatusText.describe(EngineStatus.STOPPED))
        assertEquals("已崩溃", EngineStatusText.describe(EngineStatus.CRASHED))
    }

    @Test
    fun `宿主状态读不到就是 null 不是某个状态`() {
        val s = ConsoleState.of(
            ConsoleState.NOT_LOADED,
            snap(activeRuns = listOf(ActiveRunRow(11L, host = null, pool = EngineStatus.IDLE, drift = false))),
            nowMillis = 1L,
        )
        val run = s.activeRuns.single()
        assertNull(run.hostLabel, "读不到 = null，呈现层说「读不到」—— 拿 IDLE/STOPPED 顶替就是编的")
        assertEquals("空闲", run.poolLabel, "池侧是权威的一侧：host 读不到时它还在")
        assertFalse(run.drift)
    }

    @Test
    fun `状态分歧如实带到`() {
        val s = ConsoleState.of(
            ConsoleState.NOT_LOADED,
            snap(
                activeRuns = listOf(
                    ActiveRunRow(13L, host = EngineStatus.IDLE, pool = EngineStatus.RUNNING, drift = true),
                ),
            ),
            nowMillis = 1L,
        )
        val run = s.activeRuns.single()
        assertTrue(run.drift, "分歧是 §8.3 校准的事实，呈现层只画不判")
        assertEquals("空闲", run.hostLabel)
        assertEquals("运行中", run.poolLabel)
    }

    @Test
    fun `停止三字段缺省空闲 刷新现取归零`() {
        val s = ConsoleState.NOT_LOADED
        assertNull(s.stopError)
        assertNull(s.stopNotice)
        assertFalse(s.stopInFlight)
        val busy = s.copy(stopInFlight = true, stopNotice = "已请求停止 #7")
        val refreshed = ConsoleState.of(busy, snap(), nowMillis = 2L)
        assertNull(refreshed.stopError, "现取纪律：回执不缓存，刷新即清")
        assertNull(refreshed.stopNotice, "现取纪律：回执不缓存，刷新即清")
        assertFalse(refreshed.stopInFlight, "挂起态不跨刷新：刷新回来按钮恢复可用")
        assertTrue(refreshed.loaded)
    }
}
