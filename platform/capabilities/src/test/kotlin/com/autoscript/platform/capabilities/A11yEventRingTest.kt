package com.autoscript.platform.capabilities

import com.autoscript.domain.automation.UiEvent
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** [A11yEventRing] 游标契约（与 InMemoryUiTree.nextEvents 同语义：seq > since、空增量回 since）。 */
class A11yEventRingTest {

    @Test
    fun `seq 游标 增量与空批`() {
        val ring = A11yEventRing()
        ring.push("windowStateChanged", "com.x.Main")
        ring.push("windowContentChanged", null)
        ring.push("viewScrolled", "com.x.List")

        val first = runBlocking { ring.next(0, 32) }
        assertEquals(1L, first.firstSeq)
        assertEquals(3L, last(first.events))
        assertEquals(listOf("windowStateChanged", "windowContentChanged", "viewScrolled"), first.events.map { it.type })
        assertEquals("com.x.Main", first.events[0].payload)

        val none = runBlocking { ring.next(3, 32) }
        assertEquals(3L, none.firstSeq, "空增量 first=since（调用方以游标为准，不以空数组终结）")
        assertEquals(3L, none.lastSeq)
        assertTrue(none.events.isEmpty())

        val tail = runBlocking { ring.next(1, 32) }
        assertEquals(listOf(2L, 3L), tail.events.map { it.seq })
    }

    @Test
    fun `batch 截断 与非法 batch 拒收`() {
        val ring = A11yEventRing()
        repeat(5) { ring.push("windowContentChanged", null) }
        val got = runBlocking { ring.next(0, 2) }
        assertEquals(2, got.events.size)
        assertEquals(2L, got.lastSeq, "batch 截断到 since+batch 条")
        assertThrows(IllegalArgumentException::class.java) { runBlocking { ring.next(0, 0) } }
    }

    @Test
    fun `有界 512 超界丢最旧 seq 空洞可见`() {
        val ring = A11yEventRing()
        repeat(A11yEventRing.MAX_EVENTS + 88) { ring.push("windowContentChanged", null) }
        val got = runBlocking { ring.next(0, Int.MAX_VALUE) }
        assertEquals(A11yEventRing.MAX_EVENTS, got.events.size, "环有界：只留最近 MAX_EVENTS")
        assertEquals((88L + 1), got.events.first().seq, "最旧的 88 条被丢，seq 从 89 起（空洞可见不静默断流）")
        // nodeHandle 恒 null：系统事件的源节点不在句柄注册表（不伪造 refId）
        assertTrue(got.events.all { it.nodeHandle == null })
    }

    private fun last(events: List<UiEvent>): Long = events.last().seq
}
