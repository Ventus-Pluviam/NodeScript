package com.autoscript.shell

import com.autoscript.appservice.runtime.RuntimeController
import com.autoscript.bridge.ConsoleCollector
import com.autoscript.domain.engine.EngineStatus
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 控制台快照拼装（§7.3 seq 游标拉取 + §8.3 两端对照）：控制台那一屏的全部事实
 * 都出自这里，所以逐条钉死。
 *
 * 为什么值得测：这一屏的每一格错了都不会崩 —— 只会让用户看到重复的行（游标回退）、
 * 少行而不自知（拉满不提示 / 丢包被吞）、或把"宿主状态读不到"显示成"已停止"
 * （一个编出来的状态）。三种都是静默地不对。
 */
class ConsoleReadTest {

    private fun statuses(vararg status: RuntimeController.RunStatus): List<RuntimeController.RunStatus> =
        status.toList()

    private suspend fun read(
        collector: ConsoleCollector,
        sinceSeq: Long = 0,
        maxLines: Int = 16,
        runStatuses: suspend () -> List<RuntimeController.RunStatus> = { emptyList() },
    ) = ConsoleRead.snapshot(collector, sinceSeq, maxLines, runStatuses)

    @Test
    fun `游标拉取只回新行 且推到本批最大 seq`() = runBlocking {
        val c = ConsoleCollector()
        c.append(runId = 0, level = "log", text = "a")
        c.append(runId = 7, level = "warn", text = "b")
        c.append(runId = 7, level = "error", text = "c")

        val first = read(c, sinceSeq = 0)
        assertEquals(listOf("a", "b", "c"), first.lines.map { it.text })
        assertEquals(3L, first.nextSeq)
        assertFalse(first.pageFull)

        val again = read(c, sinceSeq = first.nextSeq)
        assertTrue(again.lines.isEmpty(), "已读过的行不重发 —— 累积呈现的前提")
        assertEquals(3L, again.nextSeq, "空批原样回传游标：游标只进不退，不在这里补算")
    }

    @Test
    fun `字段逐个投影 归属 0 标 external`() = runBlocking {
        val before = System.currentTimeMillis()
        val c = ConsoleCollector()
        c.append(runId = 0, level = "error", text = "宿主侧")
        c.append(runId = 42, level = "debug", text = "引擎侧")
        val after = System.currentTimeMillis()

        val snap = read(c)
        val external = snap.lines.first { it.text == "宿主侧" }
        val engine = snap.lines.first { it.text == "引擎侧" }
        assertTrue(external.external, "runId=0 是引擎外日志，不是「第 0 次执行」")
        assertFalse(engine.external)
        assertEquals(42L, engine.runId)
        assertEquals("error", external.level, "级别原值保留 —— 呈现层翻译但不丢现场")
        assertEquals("debug", engine.level)
        assertTrue(external.atMillis in before..after, "时刻是收集器盖的章，本层不改写")
    }

    @Test
    fun `拉满如实标 pageFull 没满不标`() = runBlocking {
        val c = ConsoleCollector()
        repeat(3) { c.append(runId = 0, level = "log", text = "t$it") }

        val full = read(c, maxLines = 2)
        assertEquals(2, full.lines.size, "本批上限由调用方给")
        assertEquals(2L, full.nextSeq, "游标停在本批最大 seq —— 下次从这里续拉，不跳行")
        assertTrue(full.pageFull, "拉满只说明这批装不下：呈现层提示「可能还有」，不假装到底")

        val roomy = read(c, maxLines = 10)
        assertFalse(roomy.pageFull, "没拉满就别提示还有 —— 提示本身也得是事实")
    }

    @Test
    fun `丢包数原样带上 —— 有界队列丢最老`() = runBlocking {
        val c = ConsoleCollector(capacity = 2)
        repeat(5) { c.append(runId = 0, level = "log", text = "l$it") }
        val snap = read(c)
        // 5 行进、2 行留（容量 2）→ 丢 3：丢包数是收集器的真账，本层一个字不改。
        assertEquals(3L, snap.droppedTotal, "UI 必须知道显示的不是全部 —— 吞掉就是把「没显示」说成「没发生」")
        assertEquals(listOf("l3", "l4"), snap.lines.map { it.text })
    }

    @Test
    fun `在途执行两端对照逐字段投影 读不到的宿主保持 null`() = runBlocking {
        val c = ConsoleCollector()
        val snap = read(
            c,
            runStatuses = {
                statuses(
                    RuntimeController.RunStatus(runId = 11L, host = null, pool = EngineStatus.IDLE, drift = false),
                    RuntimeController.RunStatus(runId = 12L, host = EngineStatus.RUNNING, pool = EngineStatus.RUNNING, drift = false),
                    RuntimeController.RunStatus(runId = 13L, host = EngineStatus.IDLE, pool = EngineStatus.RUNNING, drift = true),
                )
            },
        )
        assertEquals(3, snap.activeRuns.size)
        val unreadable = snap.activeRuns.first { it.runId == 11L }
        assertNull(unreadable.host, "读不到就是 null —— 映射层不许拿 IDLE/STOPPED 之类顶替")
        assertFalse(unreadable.drift, "host 读不到不判分歧（判据在 RuntimeController，本层不另写一套）")
        val agreed = snap.activeRuns.first { it.runId == 12L }
        assertEquals(EngineStatus.RUNNING, agreed.host)
        assertEquals(EngineStatus.RUNNING, agreed.pool)
        val drifted = snap.activeRuns.first { it.runId == 13L }
        assertTrue(drifted.drift, "分歧是校准事实，逐条带过去")
    }

    @Test
    fun `没有在途执行如实为空`() = runBlocking {
        val snap = read(ConsoleCollector())
        assertTrue(snap.activeRuns.isEmpty())
        assertTrue(snap.lines.isEmpty())
        assertEquals(0L, snap.nextSeq, "空控制台首读：游标留在 0")
        assertFalse(snap.pageFull)
        assertEquals(0L, snap.droppedTotal)
    }

    @Test
    fun `maxLines 非法不吞`() {
        val c = ConsoleCollector()
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { read(c, maxLines = 0) }
        }
    }

    @Test
    fun `失败的在途查询直接抛 不留半份快照`() {
        val c = ConsoleCollector()
        c.append(runId = 0, level = "log", text = "有行")
        val boom = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                read(c) { throw IllegalStateException("在途表读崩了") }
            }
        }
        assertEquals("在途表读崩了", boom.message, "吞掉异常 = 呈现层把「读崩了」渲染成「无在途执行」")
    }
}
