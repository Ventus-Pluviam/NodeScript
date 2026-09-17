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
}