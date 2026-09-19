package com.autoscript.bridge

import com.autoscript.domain.bridge.BridgeRequest
import com.autoscript.domain.bridge.BridgeResponse
import com.autoscript.domain.core.ErrorCode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConsoleCollectorTest {

    private fun okPayload(level: String = "log", text: String = "hi") =
        """{"level":${TinyJson.quote(level)},"text":${TinyJson.quote(text)}}"""

    @Test
    fun `log 方法追加并回 Ok`() = runBlocking {
        val c = ConsoleCollector()
        val resp = c.handle(BridgeRequest(1, "console", "log", okPayload(), 5_000))
        assertInstanceOf(BridgeResponse.Ok::class.java, resp)
        val (last, lines) = c.drain(0)
        assertEquals(1L, last)
        assertEquals(1, lines.size)
        assertEquals("hi", lines.single().text)
        assertEquals("log", lines.single().level)
        assertEquals(1L, lines.single().seq)
    }

    @Test
    fun `未知方法返回 ERR_NOT_IMPLEMENTED`() = runBlocking {
        val c = ConsoleCollector()
        val resp = c.handle(BridgeRequest(2, "console", "flush", null, 5_000))
        val err = assertInstanceOf(BridgeResponse.Err::class.java, resp)
        assertEquals(ErrorCode.ERR_NOT_IMPLEMENTED.code, err.errorCode)
    }

    @Test
    fun `无效载荷返回 ERR_INVALID_PARAM 且不追加`() = runBlocking {
        val c = ConsoleCollector()
        val cases = listOf(
            null, // 缺 payload
            """{"level":"log"}""", // 缺 text
            """{"level":"trace","text":"x"}""", // 非法 level
            "不是json", // 非法 JSON
        )
        for ((i, p) in cases.withIndex()) {
            val resp = c.handle(BridgeRequest(10L + i, "console", "log", p, 5_000))
            val err = assertInstanceOf(BridgeResponse.Err::class.java, resp)
            assertEquals(ErrorCode.ERR_INVALID_PARAM.code, err.errorCode)
        }
        assertEquals(0, c.size())
    }

    @Test
    fun `有界容量丢最老并计数`() = runBlocking {
        val c = ConsoleCollector(capacity = 3)
        repeat(5) { c.append(runId = 0, level = "log", text = "l$it") }
        assertEquals(3, c.size())
        assertEquals(2L, c.droppedCount())
        val (_, lines) = c.drain(0)
        assertEquals(listOf("l2", "l3", "l4"), lines.map { it.text })
    }

    @Test
    fun `drain 游标拉取按序分页`() = runBlocking {
        val c = ConsoleCollector()
        repeat(5) { c.append(runId = 7, level = "info", text = "t$it") }
        val (last1, page1) = c.drain(0, max = 2)
        assertEquals(2, page1.size)
        assertEquals(2L, last1)
        val (last2, page2) = c.drain(last1, max = 10)
        assertEquals(3, page2.size)
        assertEquals(5L, last2)
        assertEquals(listOf("t2", "t3", "t4"), page2.map { it.text })
        // 空拉取：返回原游标
        val (last3, page3) = c.drain(last2)
        assertEquals(last2, last3)
        assertTrue(page3.isEmpty())
    }

    @Test
    fun `并发追加不丢行`() = runBlocking {
        val c = ConsoleCollector(capacity = 10_000)
        (1..200).map { i ->
            async { c.append(runId = 0, level = "debug", text = "c$i") }
        }.awaitAll()
        assertEquals(200, c.size())
        assertEquals(0L, c.droppedCount())
        val (_, lines) = c.drain(0, max = 10_000)
        assertEquals(200, lines.size)
        // 并发下 seq 分配与入队顺序可交错（非阻塞 append 的固有性质）：只断言 seq 集合完整
        assertEquals((1L..200L).toSortedSet(), lines.map { it.seq }.toSortedSet())
    }

    @Test
    fun `经 Router 注册后可被 dispatch`() = runBlocking {
        val registry = RequestRegistry()
        val router = BridgeRouter(registry)
        val collector = ConsoleCollector()
        assertTrue(router.register("console", collector))
        val resp = router.dispatch(BridgeRequest(9, "console", "log", okPayload("warn", "小心"), 5_000))
        assertInstanceOf(BridgeResponse.Ok::class.java, resp)
        val (_, lines) = collector.drain(0)
        assertEquals("小心", lines.single().text)
        assertEquals("warn", lines.single().level)
        router.close()
    }
}
