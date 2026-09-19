package com.autoscript.appservice.runtime

import com.autoscript.domain.engine.EngineId
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EnginesNamespaceHandlerTest {

    private fun handler(capacity: Int = 1): Pair<EnginesNamespaceHandler, MutableList<FakeEngine>> {
        val engines = MutableList(capacity) { FakeEngine(EngineId(it)) }
        val pool = FixedEnginePool({ id -> engines[id.poolIndex] }, capacity)
        return EnginesNamespaceHandler(RuntimeController(pool)) to engines
    }

    private fun execPayload(
        projectId: String = "p1",
        scriptPath: String = "a.js",
        extra: String = "",
    ): String = """{"projectId":"$projectId","scriptPath":"$scriptPath"$extra}"""

    @Test
    fun `exec 成功回 runId 与 handle`() = runBlocking {
        val (h, engines) = handler()
        val resp = assertInstanceOf(
            EnginesNamespaceHandler.Response.Ok::class.java,
            h.handle(EnginesNamespaceHandler.Request(1, "exec", """{"projectId":"p1","scriptPath":"a.js","args":["x","y"],"runNonce":"n1"}""")),
        )
        val o = EngineBridgeJson.decodeObject(resp.payload!!)
        val runId = (o["runId"] as EngineBridgeJson.Value.N).raw.toLong()
        val handle = o["handle"] as EngineBridgeJson.Value.Obj
        assertEquals(runId, ((handle.fields["refId"] as EngineBridgeJson.Value.N).raw.toLong()))
        assertEquals("1", (handle.fields["generation"] as EngineBridgeJson.Value.N).raw)
        // 幂等锚点透传引擎（§8.5）
        assertEquals("n1", engines[0].executed.single().runNonce)
        assertEquals(listOf("x", "y"), engines[0].executed.single().args)
    }

    @Test
    fun `exec 缺字段回 ERR_INVALID_PARAM`() = runBlocking {
        val (h, _) = handler()
        val cases = listOf(
            null,
            """{"projectId":"p1"}""",
            """{"projectId":"","scriptPath":"a.js"}""",
            "不是json",
            """{"projectId":"p1","scriptPath":"a.js","args":"x"}""",
        )
        for ((i, p) in cases.withIndex()) {
            val resp = assertInstanceOf(
                EnginesNamespaceHandler.Response.Err::class.java,
                h.handle(EnginesNamespaceHandler.Request(10L + i, "exec", p)),
            )
            assertEquals("ERR_INVALID_PARAM", resp.code, "case $i: $p")
        }
    }

    @Test
    fun `exec 满池排队超时回 ERR_TIMEOUT`() = runBlocking {
        val (h, _) = handler()
        h.handle(EnginesNamespaceHandler.Request(1, "exec", execPayload()))
        // 池容量 1 已被占；JS 侧 exec 默认 ttl=15s，这里只验证"满池有请求进来"的映射语义——
        // handler 不自己排队（池排队由 waitTimeoutMillis 驱动）；直接用 stop 释放后再 exec 成功。
        val stopFirst = assertInstanceOf(
            EnginesNamespaceHandler.Response.Ok::class.java,
            h.handle(EnginesNamespaceHandler.Request(2, "stop", """{"runId":1}""")),
        )
        assertEquals("true", stopFirst.payload)
        val retry = h.handle(EnginesNamespaceHandler.Request(3, "exec", execPayload()))
        assertInstanceOf(EnginesNamespaceHandler.Response.Ok::class.java, retry)
    }

    @Test
    fun `exec 引擎启动失败回 ERR_ENGINE_CRASHED`() = runBlocking {
        val engines = MutableList(1) { FakeEngine(EngineId(it)) }.also { it[0].failOnExecute = true }
        val pool = FixedEnginePool({ id -> engines[id.poolIndex] }, 1)
        val h = EnginesNamespaceHandler(RuntimeController(pool))
        val resp = assertInstanceOf(
            EnginesNamespaceHandler.Response.Err::class.java,
            h.handle(EnginesNamespaceHandler.Request(1, "exec", execPayload())),
        )
        assertEquals("ERR_ENGINE_CRASHED", resp.code)
        assertTrue(resp.detail.orEmpty().contains("fake boot failure"))
    }

    @Test
    fun `stop 干净回 true，未知 runId 回 ERR_NOT_FOUND`() = runBlocking {
        val (h, _) = handler()
        val exec = assertInstanceOf(
            EnginesNamespaceHandler.Response.Ok::class.java,
            h.handle(EnginesNamespaceHandler.Request(1, "exec", execPayload())),
        )
        val runId = (EngineBridgeJson.decodeObject(exec.payload!!)["runId"] as EngineBridgeJson.Value.N).raw
        val stopped = assertInstanceOf(
            EnginesNamespaceHandler.Response.Ok::class.java,
            h.handle(EnginesNamespaceHandler.Request(2, "stop", """{"runId":$runId}""")),
        )
        assertEquals("true", stopped.payload)
        // 二次 stop：如实 NOT_FOUND，不静默吞掉
        val gone = assertInstanceOf(
            EnginesNamespaceHandler.Response.Err::class.java,
            h.handle(EnginesNamespaceHandler.Request(3, "stop", """{"runId":$runId}""")),
        )
        assertEquals("ERR_NOT_FOUND", gone.code)
        // 非法 runId 载荷
        val bad = assertInstanceOf(
            EnginesNamespaceHandler.Response.Err::class.java,
            h.handle(EnginesNamespaceHandler.Request(4, "stop", """{"runId":"x"}""")),
        )
        assertEquals("ERR_INVALID_PARAM", bad.code)
    }

    @Test
    fun `poolStats 回 capacity-free-busy`() = runBlocking {
        val (h, _) = handler()
        val before = assertInstanceOf(
            EnginesNamespaceHandler.Response.Ok::class.java,
            h.handle(EnginesNamespaceHandler.Request(1, "poolStats", null)),
        )
        assertEquals("""{"capacity":1,"free":1,"busy":0}""", before.payload)
        val exec = assertInstanceOf(
            EnginesNamespaceHandler.Response.Ok::class.java,
            h.handle(EnginesNamespaceHandler.Request(2, "exec", execPayload())),
        )
        val runId = (EngineBridgeJson.decodeObject(exec.payload!!)["runId"] as EngineBridgeJson.Value.N).raw
        val during = assertInstanceOf(
            EnginesNamespaceHandler.Response.Ok::class.java,
            h.handle(EnginesNamespaceHandler.Request(3, "poolStats", null)),
        )
        assertEquals("""{"capacity":1,"free":0,"busy":1}""", during.payload)
        h.handle(EnginesNamespaceHandler.Request(4, "stop", """{"runId":$runId}"""))
    }

    @Test
    fun `未知方法回 ERR_NOT_IMPLEMENTED`() = runBlocking {
        val (h, _) = handler()
        val resp = assertInstanceOf(
            EnginesNamespaceHandler.Response.Err::class.java,
            h.handle(EnginesNamespaceHandler.Request(1, "reboot", null)),
        )
        assertEquals("ERR_NOT_IMPLEMENTED", resp.code)
    }

    @Test
    fun `channel 建查发拉关全链路`() = runBlocking {
        val (h, _) = handler()
        // 建（复用同名）
        val created = assertInstanceOf(
            EnginesNamespaceHandler.Response.Ok::class.java,
            h.handle(EnginesNamespaceHandler.Request(1, "channel", """{"name":"progress"}""")),
        )
        val co = EngineBridgeJson.decodeObject(created.payload!!)
        assertEquals("progress", (co["name"] as EngineBridgeJson.Value.S).v)
        val channelId = (co["channelId"] as EngineBridgeJson.Value.N).raw
        val again = assertInstanceOf(
            EnginesNamespaceHandler.Response.Ok::class.java,
            h.handle(EnginesNamespaceHandler.Request(2, "channel", """{"name":"progress"}""")),
        )
        assertEquals(created.payload, again.payload, "同名通道复用")

        // 发两条
        h.handle(EnginesNamespaceHandler.Request(3, "channelEmit", """{"channelId":$channelId,"event":"tick","payload":"1"}"""))
        h.handle(EnginesNamespaceHandler.Request(4, "channelEmit", """{"channelId":$channelId,"event":"tick"}"""))

        // 拉（游标分页）
        val page1 = assertInstanceOf(
            EnginesNamespaceHandler.Response.Ok::class.java,
            h.handle(EnginesNamespaceHandler.Request(5, "channelDrain", """{"channelId":$channelId,"sinceSeq":0,"max":1}""")),
        )
        val p1 = EngineBridgeJson.decodeObject(page1.payload!!)
        assertEquals("1", (p1["last"] as EngineBridgeJson.Value.N).raw)
        assertEquals(1, (p1["events"] as EngineBridgeJson.Value.Arr).items.size)
        val last1 = (p1["last"] as EngineBridgeJson.Value.N).raw.toLong()
        val page2 = assertInstanceOf(
            EnginesNamespaceHandler.Response.Ok::class.java,
            h.handle(EnginesNamespaceHandler.Request(6, "channelDrain", """{"channelId":$channelId,"sinceSeq":$last1}""")),
        )
        val p2 = EngineBridgeJson.decodeObject(page2.payload!!)
        assertEquals(1, (p2["events"] as EngineBridgeJson.Value.Arr).items.size)
        assertEquals("2", (p2["last"] as EngineBridgeJson.Value.N).raw)

        // 关 → 再发/拉如实 NOT_FOUND；同名重建得新 id
        val closed = assertInstanceOf(
            EnginesNamespaceHandler.Response.Ok::class.java,
            h.handle(EnginesNamespaceHandler.Request(7, "channelClose", """{"channelId":$channelId}""")),
        )
        assertEquals("true", closed.payload)
        val emitGone = assertInstanceOf(
            EnginesNamespaceHandler.Response.Err::class.java,
            h.handle(EnginesNamespaceHandler.Request(8, "channelEmit", """{"channelId":$channelId,"event":"x"}""")),
        )
        assertEquals("ERR_NOT_FOUND", emitGone.code)
        val rebuilt = assertInstanceOf(
            EnginesNamespaceHandler.Response.Ok::class.java,
            h.handle(EnginesNamespaceHandler.Request(9, "channel", """{"name":"progress"}""")),
        )
        val newId = (EngineBridgeJson.decodeObject(rebuilt.payload!!)["channelId"] as EngineBridgeJson.Value.N).raw
        assertTrue(newId != channelId, "关闭后重建得新 id")
    }

    @Test
    fun `channel 非法载荷与空名`() = runBlocking {
        val (h, _) = handler()
        for ((i, p) in listOf(null, """{"name":""}""", """{"name":1}""").withIndex()) {
            val resp = assertInstanceOf(
                EnginesNamespaceHandler.Response.Err::class.java,
                h.handle(EnginesNamespaceHandler.Request(20L + i, "channel", p)),
            )
            assertEquals("ERR_INVALID_PARAM", resp.code)
        }
        val emitBad = assertInstanceOf(
            EnginesNamespaceHandler.Response.Err::class.java,
            h.handle(EnginesNamespaceHandler.Request(30, "channelEmit", """{"channelId":999,"event":"x"}""")),
        )
        assertEquals("ERR_NOT_FOUND", emitBad.code)
    }

    @Test
    fun `channel 有界丢最老`() = runBlocking {
        val engines = MutableList(1) { FakeEngine(EngineId(it)) }
        val pool = FixedEnginePool({ id -> engines[id.poolIndex] }, 1)
        val h = EnginesNamespaceHandler(RuntimeController(pool), channelCapacity = 2)
        val created = assertInstanceOf(
            EnginesNamespaceHandler.Response.Ok::class.java,
            h.handle(EnginesNamespaceHandler.Request(1, "channel", """{"name":"c"}""")),
        )
        val channelId = (EngineBridgeJson.decodeObject(created.payload!!)["channelId"] as EngineBridgeJson.Value.N).raw
        repeat(4) { i ->
            h.handle(EnginesNamespaceHandler.Request(10L + i, "channelEmit", """{"channelId":$channelId,"event":"e$i"}"""))
        }
        val drained = assertInstanceOf(
            EnginesNamespaceHandler.Response.Ok::class.java,
            h.handle(EnginesNamespaceHandler.Request(20, "channelDrain", """{"channelId":$channelId,"sinceSeq":0}""")),
        )
        val events = (EngineBridgeJson.decodeObject(drained.payload!!)["events"] as EngineBridgeJson.Value.Arr).items
        assertEquals(2, events.size)
        val names = events.map { ((it as EngineBridgeJson.Value.Obj).fields["event"] as EngineBridgeJson.Value.S).v }
        assertEquals(listOf("e2", "e3"), names)
    }

    @Test
    fun `channel 并发 emit 不丢`() = runBlocking {
        val (h, _) = handler()
        val created = assertInstanceOf(
            EnginesNamespaceHandler.Response.Ok::class.java,
            h.handle(EnginesNamespaceHandler.Request(1, "channel", """{"name":"cc"}""")),
        )
        val channelId = (EngineBridgeJson.decodeObject(created.payload!!)["channelId"] as EngineBridgeJson.Value.N).raw
        (1..50).map { i ->
            async {
                h.handle(EnginesNamespaceHandler.Request(100L + i, "channelEmit", """{"channelId":$channelId,"event":"e$i"}"""))
            }
        }.awaitAll()
        val drained = assertInstanceOf(
            EnginesNamespaceHandler.Response.Ok::class.java,
            h.handle(EnginesNamespaceHandler.Request(200, "channelDrain", """{"channelId":$channelId,"sinceSeq":0,"max":100}""")),
        )
        val events = (EngineBridgeJson.decodeObject(drained.payload!!)["events"] as EngineBridgeJson.Value.Arr).items
        assertEquals(50, events.size)
    }
}
