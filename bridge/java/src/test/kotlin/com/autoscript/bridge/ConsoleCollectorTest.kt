package com.autoscript.bridge

import com.autoscript.domain.bridge.AuthenticatedRunContext
import com.autoscript.domain.permission.CapabilityMask
import com.autoscript.domain.engine.EngineId
import com.autoscript.domain.bridge.BridgeRequest
import com.autoscript.domain.bridge.BridgeResponse
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.json.DomainJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ConsoleCollectorTest {

    private fun okPayload(level: String = "log", text: String = "hi") =
        """{"level":${DomainJson.encode(level)},"text":${DomainJson.encode(text)}}"""

    @Test
    fun `log 方法追加并回 Ok`() = runBlocking(AuthenticatedRunContext(EngineId(0), 42, 1, CapabilityMask.ALL)) {
        val c = ConsoleCollector()
        val resp = c.handle(BridgeRequest(1, "console", "log", okPayload(), 5_000))
        assertInstanceOf(BridgeResponse.Ok::class.java, resp)
        val (last, lines) = c.drain(0)
        assertEquals(1L, last)
        assertEquals(1, lines.size)
        assertEquals(42L, lines.single().runId)
        assertEquals("hi", lines.single().text)
        assertEquals("log", lines.single().level)
        assertEquals(1L, lines.single().seq)
    }

    @Test
    fun `未知方法返回 ERR_NOT_IMPLEMENTED`() = runBlocking(AuthenticatedRunContext(EngineId(0), 42, 1, CapabilityMask.ALL)) {
        val c = ConsoleCollector()
        val resp = c.handle(BridgeRequest(2, "console", "flush", null, 5_000))
        val err = assertInstanceOf(BridgeResponse.Err::class.java, resp)
        assertEquals(ErrorCode.ERR_NOT_IMPLEMENTED.code, err.errorCode)
    }

    @Test
    fun `无效载荷返回 ERR_INVALID_PARAM 且不追加`() = runBlocking(AuthenticatedRunContext(EngineId(0), 42, 1, CapabilityMask.ALL)) {
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
    fun `有界容量丢最老并计数`() = runBlocking(AuthenticatedRunContext(EngineId(0), 42, 1, CapabilityMask.ALL)) {
        val c = ConsoleCollector(capacity = 3)
        repeat(5) { c.append(runId = 0, level = "log", text = "l$it") }
        assertEquals(3, c.size())
        assertEquals(2L, c.droppedCount())
        val (_, lines) = c.drain(0)
        assertEquals(listOf("l2", "l3", "l4"), lines.map { it.text })
    }

    @Test
    fun `drain 游标拉取按序分页`() = runBlocking(AuthenticatedRunContext(EngineId(0), 42, 1, CapabilityMask.ALL)) {
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
    fun `并发追加不丢行`() = runBlocking(AuthenticatedRunContext(EngineId(0), 42, 1, CapabilityMask.ALL)) {
        val c = ConsoleCollector(capacity = 10_000)
        (1..200).map { i ->
            async(Dispatchers.Default) { c.append(runId = 0, level = "debug", text = "c$i") }
        }.awaitAll()
        assertEquals(200, c.size())
        assertEquals(0L, c.droppedCount())
        val (_, lines) = c.drain(0, max = 10_000)
        assertEquals(200, lines.size)
        // 发号与入队同锁，实际出队顺序（不只是集合）必须严格递增。
        assertEquals((1L..200L).toList(), lines.map { it.seq })
    }

    @Test
    fun `经 Router 注册后可被 dispatch`() = runBlocking(AuthenticatedRunContext(EngineId(0), 42, 1, CapabilityMask.ALL)) {
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

    @Test
    fun `非正容量拒绝 防止永不结束的裁剪循环`() {
        assertThrows(IllegalArgumentException::class.java) { ConsoleCollector(capacity = 0) }
        assertThrows(IllegalArgumentException::class.java) { ConsoleCollector(capacity = -1) }
    }

    @Test
    fun `回放原始时间不改变入队游标`() {
        val c = ConsoleCollector()
        c.append(0L, "info", "new", atMillis = 900L)
        c.append(0L, "info", "early", atMillis = 100L)
        val (cursor, lines) = c.drain(0)
        assertEquals(2L, cursor)
        assertEquals(listOf(900L, 100L), lines.map { it.atMillis })
        assertEquals(listOf(1L, 2L), lines.map { it.seq })
    }

    /**
     * 落盘缝（2026-10-13）：每行入队后 sink 收到同一行（缺省不落盘行为不变）。
     */
    @Test
    fun `lineSink 收到入队的每一行`() {
        val seen = mutableListOf<com.autoscript.bridge.ConsoleLine>()
        val c = ConsoleCollector(lineSink = { seen += it })
        val a = c.append(7L, "log", "one")
        val b = c.append(7L, "error", "two")
        assertEquals(listOf(a, b), seen)
    }

    /**
     * 落盘缝绝不掀翻桥的数据面：sink 抛了，append 照常入队、drain 照常读到。
     */
    @Test
    fun `lineSink 抛异常不逃逸且行已入队`() {
        val c = ConsoleCollector(lineSink = { throw IllegalStateException("盘坏了") })
        c.append(7L, "log", "still-here")
        val (_, lines) = c.drain(0)
        assertEquals(listOf("still-here"), lines.map { it.text })
    }

    /**
     * 回放（2026-10-17「系统日志不要每次进 app 都清除」）：预置进环、**不回调 sink**、
     * seq 重新分配。
     *
     * 三条都是**行为判据**，不是实现细节：① 不回调 sink —— 回调了就是每次启动把盘上
     * 那批再抄一遍，文件每次开机翻一倍；② seq 重新分配 —— 拉取方游标从 0 起，
     * 沿用盘上旧 seq 会让第一批行"游标已越过"而永远读不到；③ 预置的行照样被 drain 读到。
     */
    @Test
    fun `回放预置进环且不回调落盘缝`() {
        val sunk = mutableListOf<ConsoleLine>()
        val c = ConsoleCollector(
            lineSink = { sunk += it },
            restored = listOf(
                ConsoleLine(seq = 999L, runId = 0L, level = "info", text = "上一世的装配行", atMillis = 111L),
                ConsoleLine(seq = 1000L, runId = 0L, level = "warn", text = "上一世的告警", atMillis = 222L),
            ),
        )
        assertTrue(sunk.isEmpty(), "回放不许回调落盘缝（否则每次启动把盘上那批再抄一遍）")
        val (cursor, lines) = c.drain(0, 10)
        assertEquals(listOf("上一世的装配行", "上一世的告警"), lines.map { it.text })
        assertEquals(listOf(1L, 2L), lines.map { it.seq }, "seq 由本进程重新分配（游标从 0 起）")
        assertEquals(2L, cursor)
        assertEquals(listOf(111L, 222L), lines.map { it.atMillis }, "原始时刻要保住（日志页显示的是它）")
        // 之后的新行接着排下去，不与回放行撞号。
        assertEquals(3L, c.append(0L, "info", "本世的").seq)
    }

    @Test
    fun `回放超出容量时取最后那批且不算丢弃`() {
        val c = ConsoleCollector(
            capacity = 3,
            restored = (1..5).map { ConsoleLine(seq = 0L, runId = 0L, level = "info", text = "line-$it") },
        )
        assertEquals(3, c.size())
        assertEquals(listOf("line-3", "line-4", "line-5"), c.drain(0, 10).second.map { it.text })
        assertEquals(0L, c.droppedCount(), "丢的是上一世就没装下的那部分，不是本世的账")
    }

    @Test
    fun `并发写满时容量与丢弃数精确`() = runBlocking(AuthenticatedRunContext(EngineId(0), 42, 1, CapabilityMask.ALL)) {
        val c = ConsoleCollector(capacity = 19)
        (1..8).map { writer ->
            async(Dispatchers.Default) {
                repeat(250) { c.append(0L, "info", "$writer-$it") }
            }
        }.awaitAll()
        assertEquals(19, c.size())
        assertEquals(1_981L, c.droppedCount())
        val (cursor, lines) = c.drain(0, 100)
        assertEquals(2_000L, cursor)
        assertEquals((1_982L..2_000L).toList(), lines.map { it.seq })
    }

    @Test
    fun `边并发写入边分页 不跳过尚未入队的小序号`() {
        val total = 4_000
        val c = ConsoleCollector(capacity = total)
        val start = CountDownLatch(1)
        val finished = CountDownLatch(4)
        val pool = Executors.newFixedThreadPool(5)
        try {
            val writers = (1..4).map { writer ->
                pool.submit {
                    check(start.await(10, TimeUnit.SECONDS))
                    try {
                        repeat(1_000) { c.append(0L, "info", "$writer-$it") }
                    } finally {
                        finished.countDown()
                    }
                }
            }
            val reader = pool.submit<List<Long>> {
                check(start.await(10, TimeUnit.SECONDS))
                val read = ArrayList<Long>(total)
                var cursor = 0L
                while (!Thread.currentThread().isInterrupted) {
                    // 先取完成标志再拉末批，避免写线程恰好在空拉取之后写完导致尾行没读到。
                    val allWritten = finished.count == 0L
                    val (next, page) = c.drain(cursor, max = 7)
                    assertTrue(next >= cursor)
                    if (page.isNotEmpty()) {
                        assertEquals((cursor + 1..next).toList(), page.map { it.seq })
                        read.addAll(page.map { it.seq })
                    }
                    cursor = next
                    if (allWritten && page.isEmpty()) break
                    if (page.isEmpty()) Thread.yield()
                }
                read
            }
            start.countDown()
            writers.forEach { it.get(10, TimeUnit.SECONDS) }
            assertEquals((1L..total.toLong()).toList(), reader.get(10, TimeUnit.SECONDS))
            assertEquals(0L, c.droppedCount())
        } finally {
            start.countDown()
            pool.shutdownNow()
        }
    }
}
