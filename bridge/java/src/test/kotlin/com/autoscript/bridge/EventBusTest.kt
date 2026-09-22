package com.autoscript.bridge

import com.autoscript.bridge.EventBus.PublishResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EventBusTest {

    @Test
    fun `publish and drain with seq cursor`() {
        val bus = EventBus(capacityPerTopic = 100)
        val seqs = (1..5).map { (bus.publish("ui", "e$it") as PublishResult.Accepted).seq }
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), seqs)

        val (last, events) = bus.drain("ui", 0)
        assertEquals(5L, last)
        assertEquals(listOf("e1", "e2", "e3", "e4", "e5"), events.map { it.payload })
    }

    @Test
    fun `drain is incremental from cursor`() {
        val bus = EventBus(capacityPerTopic = 100)
        bus.publish("t", "a")
        bus.publish("t", "b")
        bus.publish("t", "c")

        val (cursor, first) = bus.drain("t", 0, max = 2)
        assertEquals(2L, cursor)
        assertEquals(listOf("a", "b"), first.map { it.payload })

        val (last, rest) = bus.drain("t", cursor)
        assertEquals(3L, last)
        assertEquals(listOf("c"), rest.map { it.payload })
    }

    @Test
    fun `drop-oldest on overflow`() {
        val bus = EventBus(capacityPerTopic = 2, overflow = EventBus.OverflowPolicy.DROP_OLDEST)
        bus.publish("t", "a")
        bus.publish("t", "b")
        val r = bus.publish("t", "c")
        assertTrue(r is PublishResult.Dropped, "队列满 + DROP_OLDEST 必须如实回报 Dropped（不可再静默 Accepted）")
        assertEquals(1L, (r as PublishResult.Dropped).evictedSeq, "被挤压的是最老的 seq=1")

        val (_, events) = bus.drain("t", 0)
        // 只保留最近 2 个；老的 a 被丢
        assertEquals(listOf("b", "c"), events.map { it.payload })
    }

    @Test
    fun `reject on overflow`() {
        val bus = EventBus(capacityPerTopic = 1, overflow = EventBus.OverflowPolicy.REJECT)
        bus.publish("t", "a")
        assertTrue(bus.publish("t", "b") is PublishResult.Rejected)
        val (_, keep) = bus.drain("t", 0)
        assertEquals(listOf("a"), keep.map { it.payload })
    }

    @Test
    fun `topics tracked`() {
        val bus = EventBus()
        bus.publish("x", "1")
        bus.publish("y", "2")
        assertEquals(setOf("x", "y"), bus.topics())
    }
}