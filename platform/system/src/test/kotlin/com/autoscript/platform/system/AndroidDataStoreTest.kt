package com.autoscript.platform.system

import com.autoscript.domain.storage.StoredEntry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `datastore` Android 实现的契约测试（docs §9.6；口径与 `:domain`
 * `DataStoreContractTest` 同一套，被测对象换成 [AndroidDataStore] + 内存 [FakeKvOps]）。
 *
 * Android 面（[SqliteKvOps]）本机只编译不执行（README ops 表）；这里守的是缝
 * 这边的全部语义：空白键拒写不碰 ops、事务暂存后**恰好一次** [AndroidDataStore.KvOps.applyAll]、
 * block 抛错 → ops **零调用**（零调用即回滚，SQLite 事务是 applyAll 内部的事）。
 */
class AndroidDataStoreTest {

    /** 内存替身：按表语义落值 + 记录每次 applyAll（原子批协议的观察窗）。 */
    private class FakeKvOps : AndroidDataStore.KvOps {
        val map = LinkedHashMap<String, StoredEntry>()
        val batches = mutableListOf<List<AndroidDataStore.KvMutation>>()

        override fun put(key: String, value: StoredEntry) {
            map[key] = value
        }

        override fun get(key: String): StoredEntry? = map[key]

        override fun remove(key: String): Boolean = map.remove(key) != null

        override fun contains(key: String): Boolean = map.containsKey(key)

        override fun keys(): List<String> = map.keys.toList()

        override fun clear() = map.clear()

        override fun applyAll(ops: List<AndroidDataStore.KvMutation>) {
            batches += ops
            for (op in ops) {
                when (op) {
                    is AndroidDataStore.KvMutation.Put -> map[op.key] = op.value
                    is AndroidDataStore.KvMutation.Remove -> map.remove(op.key)
                }
            }
        }
    }

    private val ops = FakeKvOps()
    private val store = AndroidDataStore(ops)

    @Test
    fun `put get 往返——JSON 原文与字节内容都原样回`() = runBlocking {
        val raw = """ { "b" : 1 , "a" : [ null ] } """
        store.put("cfg", StoredEntry.Json(raw))
        assertEquals(StoredEntry.Json(raw), store.get("cfg"), "透传不重排")

        val payload = ByteArray(64) { it.toByte() }
        store.put("blob", StoredEntry.Bytes(payload))
        val back = store.get("blob") as StoredEntry.Bytes
        assertArrayEquals(payload, back.bytes)
    }

    @Test
    fun `空白键被拒且 ops 零调用——直写与事务暂存同一口径`() = runBlocking {
        assertThrows(IllegalArgumentException::class.java) { runBlocking { store.put("  ", StoredEntry.Json("1")) } }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { store.transaction { put("", StoredEntry.Json("1")) } }
        }
        assertTrue(ops.map.isEmpty(), "被拒的写不进表")
        assertTrue(ops.batches.isEmpty(), "被拒的事务不发起原子批")
    }

    @Test
    fun `事务提交——恰好一次 applyAll，按序带全量暂存`() = runBlocking {
        store.put("keep", StoredEntry.Json("\"old\""))
        store.transaction {
            put("new", StoredEntry.Json("1"))
            put("keep", StoredEntry.Json("\"new\""))
            remove("ghost")
        }

        assertEquals(1, ops.batches.size, "提交点唯一：恰好一次原子批")
        assertEquals(
            listOf(
                AndroidDataStore.KvMutation.Put("new", StoredEntry.Json("1")),
                AndroidDataStore.KvMutation.Put("keep", StoredEntry.Json("\"new\"")),
                AndroidDataStore.KvMutation.Remove("ghost"),
            ),
            ops.batches.single(),
            "暂存按发生序整批到达",
        )
        assertEquals(StoredEntry.Json("1"), store.get("new"))
        assertEquals(StoredEntry.Json("\"new\""), store.get("keep"))
        assertNull(store.get("ghost"))
    }

    @Test
    fun `事务抛错——applyAll 零调用，已存状态原样`() = runBlocking {
        store.put("keep", StoredEntry.Json("\"safe\""))
        val boom = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                store.transaction {
                    put("staged", StoredEntry.Json("1"))
                    remove("keep")
                    throw IllegalStateException("block 半途炸了")
                }
            }
        }
        assertEquals("block 半途炸了", boom.message)
        assertTrue(ops.batches.isEmpty(), "零调用即回滚：原子批一次都没发")
        assertNull(store.get("staged"), "暂存的写没有落地")
        assertEquals(StoredEntry.Json("\"safe\""), store.get("keep"))
    }

    @Test
    fun `空事务走同一协议——一次空批，不特判`() = runBlocking {
        store.put("k", StoredEntry.Json("1"))
        store.transaction { }
        assertEquals(1, ops.batches.size)
        assertEquals(emptyList<AndroidDataStore.KvMutation>(), ops.batches.single())
        assertEquals(StoredEntry.Json("1"), store.get("k"))
    }

    @Test
    fun `remove contains keys clear 语义`() = runBlocking {
        store.put("a", StoredEntry.Json("1"))
        store.put("b", StoredEntry.Bytes(byteArrayOf(9)))
        assertEquals(setOf("a", "b"), store.keys().toSet())
        assertTrue(store.contains("a"))
        assertEquals(StoredEntry.Json("1"), store.remove("a"), "删且回旧值")
        assertNull(store.remove("a"), "幂等再删回 null（SPI：回被移除值，没有东西可回）")
        assertFalse(store.contains("a"))
        store.clear()
        assertEquals(emptyList<String>(), store.keys())
        assertNull(store.get("b"))
    }
}
