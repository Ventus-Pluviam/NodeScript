package com.autoscript.domain.storage

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * datastore 契约锚定（§9.6；[InMemoryDataStore] 是被测的可执行规格）。
 *
 * 守四件事：
 * 1. **JSON 透传不解释**：存什么文本回什么文本 —— 任何重排/解析都在破坏
 *    「handler 搬运、实现存储、谁都不解释业务 JSON」的边界；
 * 2. **缺失 ≠ JSON null**：`get` 回 null 是键不在；`Json("null")` 是一个值，
 *    折叠了 JS 侧 `undefined` 与 `null` 就分不开；
 * 3. **Bytes 按内容相等**：同内容新数组必须相等（data class 默认引用比对会判不等）；
 * 4. **事务整批原子**：block 抛异常 → 一单不落、已存状态原样；合法提交 → 暂存全部可见。
 */
class DataStoreContractTest {

    private fun store() = InMemoryDataStore()

    // ── 基本 KV ────────────────────────────────────────────────────

    @Test
    fun `put get 往返——JSON 文本原样存取不重排`() = kotlinx.coroutines.runBlocking {
        val s = store()
        // 键序/空白故意不规范：契约是透传，不是 canonical 化
        val raw = """ { "b" : 2 , "a" : [ 1 , null ] } """
        s.put("cfg", StoredEntry.Json(raw))
        assertEquals(StoredEntry.Json(raw), s.get("cfg"), "存什么回什么：本层不解析不重排")
    }

    @Test
    fun `缺失键回 null 而存 JSON null 是值`() = kotlinx.coroutines.runBlocking {
        val s = store()
        assertNull(s.get("absent"), "键缺失 = get 回 null")
        s.put("explicit-null", StoredEntry.Json("null"))
        assertEquals(StoredEntry.Json("null"), s.get("explicit-null"), "JSON null 是值，与缺失是两回事")
        assertTrue(s.contains("explicit-null"))
        assertFalse(s.contains("absent"))
    }

    @Test
    fun `后写覆盖前值`() = kotlinx.coroutines.runBlocking {
        val s = store()
        s.put("k", StoredEntry.Json("1"))
        s.put("k", StoredEntry.Json("2"))
        assertEquals(StoredEntry.Json("2"), s.get("k"))
        assertEquals(listOf("k"), s.keys(), "覆盖不产生第二个键")
    }

    @Test
    fun `remove 回被移除值，缺失键幂等回 null`() = kotlinx.coroutines.runBlocking {
        val s = store()
        s.put("k", StoredEntry.Json("\"v\""))
        assertEquals(StoredEntry.Json("\"v\""), s.remove("k"), "删除顺带交出旧值")
        assertNull(s.get("k"))
        assertNull(s.remove("k"), "再删回 null：幂等不抛")
        assertFalse(s.contains("k"))
    }

    @Test
    fun `空白键写入被拒——直写与事务暂存同一口径`() = kotlinx.coroutines.runBlocking {
        val s = store()
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { s.put("  ", StoredEntry.Json("{}")) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { s.transaction { put("", StoredEntry.Json("{}")) } }
        }
        assertTrue(s.keys().isEmpty(), "被拒的写不留残迹")
    }

    @Test
    fun `keys 与 clear 的全量语义`() = kotlinx.coroutines.runBlocking {
        val s = store()
        s.put("a", StoredEntry.Json("1"))
        s.put("b", StoredEntry.Bytes(ByteArray(0)))
        assertEquals(setOf("a", "b"), s.keys().toSet(), "顺序不作契约保证，集合口径断言")
        s.clear()
        assertEquals(emptyList<String>(), s.keys())
        assertNull(s.get("a"))
    }

    // ── Bytes ──────────────────────────────────────────────────────

    @Test
    fun `Bytes 按内容相等——同内容新数组不是不等的陌生人`() {
        val a = StoredEntry.Bytes(byteArrayOf(1, 2, 3))
        val b = StoredEntry.Bytes(byteArrayOf(1, 2, 3))
        val c = StoredEntry.Bytes(byteArrayOf(9, 9))
        assertEquals(a, b, "内容相等即相等（data class 默认引用比对会在这里判不等）")
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, c)
    }

    @Test
    fun `Bytes 往返——字节原样取回`() = kotlinx.coroutines.runBlocking {
        val s = store()
        val payload = ByteArray(256) { it.toByte() }
        s.put("blob", StoredEntry.Bytes(payload))
        val back = s.get("blob") as StoredEntry.Bytes
        assertArrayEquals(payload, back.bytes, "BLOB 面一个字节都不能变")
    }

    // ── 事务 ───────────────────────────────────────────────────────

    @Test
    fun `事务提交——暂存的写在提交后全部可见`() = kotlinx.coroutines.runBlocking {
        val s = store()
        s.put("old", StoredEntry.Json("\"keep\""))
        s.transaction {
            put("new", StoredEntry.Json("1"))
            put("old", StoredEntry.Json("\"replaced\""))
            remove("ghost")
        }
        assertEquals(StoredEntry.Json("1"), s.get("new"))
        assertEquals(StoredEntry.Json("\"replaced\""), s.get("old"))
        assertNull(s.remove("ghost"), "暂存里删一个本就不存在的键：提交后依旧不存在")
    }

    @Test
    fun `事务抛错——整批不落，已存状态原样`() = kotlinx.coroutines.runBlocking {
        val s = store()
        s.put("keep", StoredEntry.Json("\"safe\""))
        val boom = assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking {
                s.transaction {
                    put("staged", StoredEntry.Json("1"))
                    remove("keep")
                    throw IllegalStateException("block 半途炸了")
                }
            }
        }
        assertEquals("block 半途炸了", boom.message, "异常照常抛出，不吞")
        assertNull(s.get("staged"), "已暂存的写随异常整批丢弃")
        assertEquals(StoredEntry.Json("\"safe\""), s.get("keep"), "被暂存 remove 的键原样还在")
    }

    @Test
    fun `空事务是合法 no-op`() = kotlinx.coroutines.runBlocking {
        val s = store()
        s.put("k", StoredEntry.Json("1"))
        s.transaction { }
        assertEquals(StoredEntry.Json("1"), s.get("k"), "空 block 提交不动任何键")
    }

    @Test
    fun `事务暂存提交前对外不可见——块执行中外部读者只见旧值`() {
        val s = kotlinx.coroutines.runBlocking { store().also { it.put("k", StoredEntry.Json("\"before\"")) } }
        val inBlock = java.util.concurrent.CountDownLatch(1)
        val readerDone = java.util.concurrent.CountDownLatch(1)
        val seen = java.util.concurrent.atomic.AtomicReference<StoredEntry?>()

        // 独立读者线程：等 block 进入暂存阶段后抢读一次。staging 不持锁
        // （锁只罩 apply），所以读得到 —— 读到的必须仍是旧值。
        val reader = Thread {
            assertTrue(inBlock.await(2, java.util.concurrent.TimeUnit.SECONDS))
            seen.set(kotlinx.coroutines.runBlocking { s.get("k") })
            readerDone.countDown()
        }
        reader.start()

        kotlinx.coroutines.runBlocking {
            s.transaction {
                put("k", StoredEntry.Json("\"after\""))
                inBlock.countDown()
                assertTrue(
                    readerDone.await(2, java.util.concurrent.TimeUnit.SECONDS),
                    "读者必须在提交前完成读取（否则本测退化成恒真）",
                )
            }
        }

        assertEquals(StoredEntry.Json("\"before\""), seen.get(), "暂存阶段对外不可见：读者拿到旧值")
        assertEquals(StoredEntry.Json("\"after\""), kotlinx.coroutines.runBlocking { s.get("k") }, "提交后才是新值")
        reader.join(2_000)
    }
}
