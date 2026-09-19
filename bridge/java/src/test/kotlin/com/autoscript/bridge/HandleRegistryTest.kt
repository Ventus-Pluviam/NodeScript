package com.autoscript.bridge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HandleRegistryTest {

    private val resource = "native-resource"

    @Test
    fun `register and validate`() {
        val h = HandleRegistry()
        val ref = h.register(resource)
        assertTrue(h.validate(ref))
        assertEquals(resource, h.resourceOf(ref))
    }

    @Test
    fun `acquire bumps generation invalidating old proxy`() {
        val h = HandleRegistry()
        val ref = h.register(resource)
        val next = h.acquire(ref.refId)!!
        assertNotNull(next)
        assertTrue(next.generation > ref.generation)
        // 旧代理失效
        assertFalse(h.validate(ref))
        assertTrue(h.validate(next))
    }

    @Test
    fun `acquire on missing id returns null`() {
        assertNull(HandleRegistry().acquire(999))
    }

    @Test
    fun `release tombstones and blocks later ops`() {
        val h = HandleRegistry()
        val ref = h.register(resource)
        assertTrue(h.release(ref))
        assertFalse(h.validate(ref))
        assertNull(h.resourceOf(ref))
        assertNull(h.acquire(ref.refId))
        // 二次释放不生效
        assertFalse(h.release(ref))
    }

    @Test
    fun `generation mismatch blocks release`() {
        val h = HandleRegistry()
        val ref = h.register(resource)
        val stale = ref.copy(generation = ref.generation + 5)
        assertFalse(h.release(stale))
        assertTrue(h.validate(ref))
    }

    @Test
    fun `sweep clears selected and keeps others`() {
        val h = HandleRegistry()
        val a = h.register("a")
        val b = h.register("b")
        val swept = h.sweep { id -> id == a.refId }
        assertEquals(listOf(a.refId), swept)
        assertFalse(h.validate(a))
        assertTrue(h.validate(b))
        assertEquals(2, h.size())   // forget 前仍在册（tombstone 态）
    }

    @Test
    fun `forget physically removes`() {
        val h = HandleRegistry()
        val ref = h.register(resource)
        assertTrue(h.forget(ref.refId))
        assertFalse(h.validate(ref))
        assertEquals(0, h.size())
    }

    @Test
    fun `并发 acquire 各得唯一代次 无重复句柄`() {
        val h = HandleRegistry()
        val first = h.register(resource)
        val results = mutableListOf<com.autoscript.domain.bridge.HandleRef>()
        val threads = (1..64).map {
            Thread {
                h.acquire(first.refId)?.let { r -> synchronized(results) { results += r } }
            }.apply { start() }
        }
        threads.forEach { it.join() }
        val generations = results.map { it.generation }
        assertEquals(generations.size, generations.distinct().size, "并发 acquire 不得发出重复代次句柄")
    }

    @Test
    fun `并发 release 与 acquire 交错 只有一个胜者`() {
        // check-then-act 竞态回归：release 校验通过后、落 tombstone 前若被 acquire 插进新代次，
        // 旧实现会 tombstone 掉新代理资源。修复后两操作在同一 holder 锁内串行，结果必居其一：
        // 要么 release 先到（acquire 拒绝），要么 acquire 先到（release 代次不匹配被拒）。
        repeat(200) {
            val h = HandleRegistry()
            val ref = h.register(resource)
            val outcomes = mutableListOf<String>()
            val t1 = Thread { if (h.release(ref)) synchronized(outcomes) { outcomes += "released" } }
            val t2 = Thread { if (h.acquire(ref.refId) != null) synchronized(outcomes) { outcomes += "acquired" } }
            t1.start(); t2.start(); t1.join(); t2.join()
            assertEquals(1, outcomes.size, "release 与 acquire 必须互斥出唯一胜者: $outcomes")
            assertFalse(h.validate(ref), "无论谁胜出，旧代次句柄都必须失效")
        }
    }
}
