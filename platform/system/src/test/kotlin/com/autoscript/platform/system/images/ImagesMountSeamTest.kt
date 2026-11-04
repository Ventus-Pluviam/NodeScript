package com.autoscript.platform.system.images

import com.autoscript.domain.automation.ColorHit
import com.autoscript.domain.automation.FeatureHit
import com.autoscript.domain.automation.ImageAnalyzer
import com.autoscript.domain.automation.ImageFrame
import com.autoscript.domain.automation.ImageMatch
import com.autoscript.domain.bridge.BridgeRequest
import com.autoscript.domain.bridge.BridgeResponse
import com.autoscript.domain.bridge.HandleRef
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.json.DomainJson
import com.autoscript.platform.system.SystemNamespaces
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

/**
 * `images` 挂载缝透传测试（§9.2）—— 自 capabilities 的
 * CapabilityNamespacesTest 拆出（images handler 随迁 :platform:system，工厂在
 * [SystemNamespaces.images]）。
 *
 * 判据：Ok 载荷逐字段透传（命中体 x/y/w/h/confidence）、未匹配是裸 null 不折叠成
 * ERR_NOT_FOUND、未知桥面操作如实 ERR_NOT_IMPLEMENTED、id 回显请求侧 id。
 */
class ImagesMountSeamTest {

    @Test
    fun `images 挂载缝透传命中体与 null（不折叠成 NOT_FOUND）`() = runBlocking {
        val handler = SystemNamespaces.images(FakeImageAnalyzer())

        // 先 decode 一帧拿真句柄（发号侧归一后是 SPI 的号段：refId 从 1 起）
        val decoded = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            handler.handle(BridgeRequest(8, "images", "decode", """{"path":"/sdcard/icon.png"}""", 5_000)),
        )
        val ref = (DomainJson.decodeObject(decoded.payload!!)["ref"] as DomainJson.Value.Obj).fields
        val haystack = """{"refId":${(ref["refId"] as DomainJson.Value.N).raw},"generation":1}"""
        val needle = haystack

        val hit = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            handler.handle(
                BridgeRequest(9, "images", "findImage", """{"haystack":$haystack,"needle":$needle,"threshold":0.9}""", 5_000),
            ),
        )
        val o = DomainJson.decodeObject(hit.payload!!)
        assertEquals("12", (o["x"] as DomainJson.Value.N).raw, "命中体逐字段透传")
        assertEquals(9L, hit.id)

        // 换一个恒未命中的假分析器：未匹配是答案 —— 裸 null，不折叠成 ERR_NOT_FOUND
        // （新 handler 配一个新 fake = 新的帧表，同一 ref 数字要重新 decode 才在场）
        val missing = SystemNamespaces.images(FakeImageAnalyzer(result = null))
        missing.handle(
            BridgeRequest(9, "images", "decode", """{"path":"/sdcard/screen.png"}""", 5_000),
        )
        val miss = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            missing.handle(
                BridgeRequest(10, "images", "matchTemplate", """{"haystack":$haystack,"needle":$needle,"threshold":0.9}""", 5_000),
            ),
        )
        assertEquals("null", miss.payload, "未匹配是答案：裸 null，不折叠成 ERR_NOT_FOUND")

        val unknown = assertInstanceOf(
            BridgeResponse.Err::class.java,
            handler.handle(BridgeRequest(11, "images", "captureScreen", "{}", 5_000)),
        )
        assertEquals(ErrorCode.ERR_NOT_IMPLEMENTED.code, unknown.errorCode, "未开桥面的操作不猜")
        assertEquals(11L, unknown.id)
        Unit
    }


    /** 假图像分析器：两帧恒命中 / 恒未命中由用例指定（像素语义归 :bridge:image，这里只测转接）。 */
    private class FakeImageAnalyzer(
        private val result: ImageMatch? = ImageMatch(12, 34, 100, 50, 0.97),
    ) : ImageAnalyzer {
        override suspend fun decode(path: String): ImageFrame = ImageFrame(HandleRef(1, 1), 1080, 2400)

        // §18-8(b)：截屏帧走同一个发号口（本 fake 只测转接，不校验像素）
        override suspend fun ingest(width: Int, height: Int, rgba: ByteArray): ImageFrame =
            ImageFrame(HandleRef(1, 1), width, height)

        override suspend fun release(handle: HandleRef) = Unit

        override suspend fun matchTemplate(haystack: HandleRef, needle: HandleRef, threshold: Double, region: List<Int>?): ImageMatch? = result

        override suspend fun findImage(haystack: HandleRef, needle: HandleRef, threshold: Double, region: List<Int>?): ImageMatch? = result

        override suspend fun findColor(
            haystack: HandleRef,
            color: List<Int>,
            tolerance: Int,
            region: List<Int>?,
        ): ColorHit? = null

        override suspend fun toGrayscale(frame: HandleRef): ImageFrame = ImageFrame(HandleRef(1, 1), 1080, 2400)

        override suspend fun crop(frame: HandleRef, region: List<Int>): ImageFrame =
            ImageFrame(HandleRef(1, 1), 1080, 2400)

        override suspend fun resize(frame: HandleRef, width: Int, height: Int): ImageFrame =
            ImageFrame(HandleRef(1, 1), 1080, 2400)

        override suspend fun rotate(frame: HandleRef, degrees: Double): ImageFrame =
            ImageFrame(HandleRef(1, 1), 1080, 2400)

        override suspend fun findFeature(scene: HandleRef, template: HandleRef): FeatureHit? = null
    }
}
