package com.autoscript.platform.system

import com.autoscript.domain.storage.StoredEntry
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * 行编解码测试（§9.6 serializer 适配）：kind 显式裁定 + 访问器按 kind 惰性。
 *
 * 最要紧的一条是**惰性**：游标对错误类型的列取值会抛/给脏数据，所以 `J` 行
 * 绝不能碰 blob 访问器、`B` 行绝不能碰文本访问器 —— 用「碰了就炸」的访问器
 * 把这个分支纪律钉死。
 */
class KvRowCodecTest {

    @Test
    fun `kindOf 两形态显式标记`() {
        assertEquals(KvRowCodec.KIND_JSON, KvRowCodec.kindOf(StoredEntry.Json("{}")))
        assertEquals(KvRowCodec.KIND_BYTES, KvRowCodec.kindOf(StoredEntry.Bytes(byteArrayOf(1))))
    }

    @Test
    fun `J 行只走文本访问器——blob 访问器碰了就炸`() {
        val entry = KvRowCodec.decode(
            KvRowCodec.KIND_JSON,
            text = { """{"a":1}""" },
            blob = { throw AssertionError("J 行不得碰 blob 访问器") },
        )
        assertEquals(StoredEntry.Json("""{"a":1}"""), entry)
    }

    @Test
    fun `B 行只走字节访问器——文本访问器碰了就炸`() {
        val payload = byteArrayOf(1, 2, 3)
        val entry = KvRowCodec.decode(
            KvRowCodec.KIND_BYTES,
            text = { throw AssertionError("B 行不得碰文本访问器") },
            blob = { payload },
        )
        assertArrayEquals(payload, (entry as StoredEntry.Bytes).bytes)
    }

    @Test
    fun `kind 与可空性对不上即抛——不静默降级`() {
        assertThrows(IllegalStateException::class.java) {
            KvRowCodec.decode(KvRowCodec.KIND_JSON, text = { null }, blob = { ByteArray(0) })
        }
        assertThrows(IllegalStateException::class.java) {
            KvRowCodec.decode(KvRowCodec.KIND_BYTES, text = { "x" }, blob = { null })
        }
        assertThrows(IllegalStateException::class.java) {
            KvRowCodec.decode("X", text = { "x" }, blob = { null })
        }
    }
}
