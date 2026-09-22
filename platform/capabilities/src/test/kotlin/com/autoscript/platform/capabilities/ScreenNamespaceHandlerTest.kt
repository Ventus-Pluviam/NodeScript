package com.autoscript.platform.capabilities

import com.autoscript.domain.automation.ScreenSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ScreenNamespaceHandlerTest {

    private fun source(snapshot: ScreenSnapshot = ScreenSnapshot(false, false, true)): ScreenshotSource {
        var now = 1_000L
        return ScreenshotSource(
            object : ScreenshotSource.FrameProducer {
                override suspend fun snapshot(): ScreenSnapshot = snapshot
                override suspend fun produce(width: Int, height: Int): ProducedFrame =
                    ProducedFrame(byteArrayOf(7, 7, 7), 1080, 2400)
            },
            clock = { now += 1_000; now },
        )
    }

    private lateinit var handler: ScreenNamespaceHandler

    @BeforeEach
    fun setup() {
        handler = ScreenNamespaceHandler(source())
    }

    @Test
    fun `capture 回帧句柄三字段`() = runBlocking {
        val resp = assertInstanceOf(
            ScreenNamespaceHandler.Response.Ok::class.java,
            handler.handle(ScreenNamespaceHandler.Request(1, "capture", null)),
        )
        val o = A11yBridgeJson.decodeObject(resp.payload!!)
        val ref = (o["ref"] as A11yBridgeJson.Value.Obj).fields
        assertEquals("1", (ref["refId"] as A11yBridgeJson.Value.N).raw)
        assertTrue((o["width"] as A11yBridgeJson.Value.N).raw.toLong() > 0)
    }

    @Test
    fun `锁屏 capture 回 ERR_SCREEN_LOCKED`() = runBlocking {
        val h = ScreenNamespaceHandler(
            source(ScreenSnapshot(locked = true, secureForeground = false, hasWindows = true)),
        )
        val resp = assertInstanceOf(
            ScreenNamespaceHandler.Response.Err::class.java,
            h.handle(ScreenNamespaceHandler.Request(2, "capture", null)),
        )
        assertEquals("ERR_SCREEN_LOCKED", resp.code)
    }

    @Test
    fun `capture-recycle 全链路`() = runBlocking {
        val cap = assertInstanceOf(
            ScreenNamespaceHandler.Response.Ok::class.java,
            handler.handle(ScreenNamespaceHandler.Request(10, "capture", null)),
        )
        val o = A11yBridgeJson.decodeObject(cap.payload!!)
        val ref = (o["ref"] as A11yBridgeJson.Value.Obj).fields
        val refJson =
            """{"refId":${(ref["refId"] as A11yBridgeJson.Value.N).raw},"generation":${(ref["generation"] as A11yBridgeJson.Value.N).raw}}"""
        val rec = assertInstanceOf(
            ScreenNamespaceHandler.Response.Ok::class.java,
            handler.handle(ScreenNamespaceHandler.Request(11, "recycle", """{"ref":$refJson}""")),
        )
        assertEquals("true", rec.payload)
        val stale = assertInstanceOf(
            ScreenNamespaceHandler.Response.Err::class.java,
            handler.handle(ScreenNamespaceHandler.Request(12, "recycle", """{"ref":{"refId":999,"generation":1}}""")),
        )
        assertEquals("ERR_STALE_HANDLE", stale.code)
    }

    @Test
    fun `会话 startCapturer-nextFrame-closeSession 全链路`() = runBlocking {
        val start = assertInstanceOf(
            ScreenNamespaceHandler.Response.Ok::class.java,
            handler.handle(ScreenNamespaceHandler.Request(20, "startCapturer", null)),
        )
        val sessionId = ((A11yBridgeJson.decodeObject(start.payload!!)["session"] as A11yBridgeJson.Value.Obj).fields["refId"] as A11yBridgeJson.Value.N).raw
        val frame = assertInstanceOf(
            ScreenNamespaceHandler.Response.Ok::class.java,
            handler.handle(ScreenNamespaceHandler.Request(21, "nextFrame", """{"session":{"refId":$sessionId,"generation":1}}""")),
        )
        assertTrue(frame.payload!!.contains(""""width""""))
        val close = assertInstanceOf(
            ScreenNamespaceHandler.Response.Ok::class.java,
            handler.handle(ScreenNamespaceHandler.Request(22, "closeSession", """{"session":{"refId":$sessionId,"generation":1}}""")),
        )
        assertEquals("true", close.payload)
        // 关闭后 nextFrame → 未知会话
        val gone = assertInstanceOf(
            ScreenNamespaceHandler.Response.Err::class.java,
            handler.handle(ScreenNamespaceHandler.Request(23, "nextFrame", """{"session":{"refId":$sessionId,"generation":1}}""")),
        )
        assertEquals("ERR_NOT_FOUND", gone.code)
        // 重复 close → 未知会话（幂等不适用会话：会话是连接态，二次关如实报失）
        val close2 = assertInstanceOf(
            ScreenNamespaceHandler.Response.Err::class.java,
            handler.handle(ScreenNamespaceHandler.Request(24, "closeSession", """{"session":{"refId":$sessionId,"generation":1}}""")),
        )
        assertEquals("ERR_NOT_FOUND", close2.code)
    }

    @Test
    fun `锁屏 open 会话直接 Err 不发空会话`() = runBlocking {
        val h = ScreenNamespaceHandler(
            source(ScreenSnapshot(locked = true, secureForeground = false, hasWindows = true)),
        )
        val resp = assertInstanceOf(
            ScreenNamespaceHandler.Response.Err::class.java,
            h.handle(ScreenNamespaceHandler.Request(30, "startCapturer", null)),
        )
        assertEquals("ERR_SCREEN_LOCKED", resp.code)
    }

    @Test
    fun `未知方法与非法载荷`() = runBlocking {
        val unknown = assertInstanceOf(
            ScreenNamespaceHandler.Response.Err::class.java,
            handler.handle(ScreenNamespaceHandler.Request(40, "rotate", null)),
        )
        assertEquals("ERR_NOT_IMPLEMENTED", unknown.code)
        val bad = assertInstanceOf(
            ScreenNamespaceHandler.Response.Err::class.java,
            handler.handle(ScreenNamespaceHandler.Request(41, "recycle", """{"noref":1}""")),
        )
        assertEquals("ERR_INVALID_PARAM", bad.code)
    }
}
