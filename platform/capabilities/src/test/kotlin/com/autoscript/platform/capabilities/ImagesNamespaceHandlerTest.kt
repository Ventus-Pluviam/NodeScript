package com.autoscript.platform.capabilities

import com.autoscript.domain.automation.ImageAnalyzer
import com.autoscript.domain.automation.ImageFrame
import com.autoscript.domain.automation.ImageMatch
import com.autoscript.domain.bridge.BridgeRequest
import com.autoscript.domain.bridge.BridgeResponse
import com.autoscript.domain.bridge.HandleRef
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `images` 桥处理器测试（§9.2；JS 对偶 `bridge/js/src/images.ts` + `images.test.cjs`）。
 *
 * 判据七件（与 `:domain` `ImageAnalyzer` KDoc 逐条对齐）：
 * 1. **wire 形状**：`decode` 发 `{path}` → `{ref,width,height}`（宽高是文件真值）；
 *    `matchTemplate`/`findImage` 发 `{haystack,needle,threshold}` → `{x,y,width,height,confidence}`
 *    或裸 `null`；`release` 发 `{ref}` → `true`；
 * 2. **阈值一个键**：两方法都叫 `threshold`（facade 曾一个发 `tolerance` 一个发 `threshold`，
 *    同一 opencv 概念两个键名 = 两侧 mock 各自自洽的漂移面）；
 * 3. **阈值域 [0,1]**：越界 → `ERR_INVALID_PARAM` 且**一次 SPI 调用都不发**；
 * 4. **错误不折叠**：SPI 的 `ERR_FILE_NOT_FOUND`/`ERR_IO`/`ERR_STALE_HANDLE` 原码透传
 *    （"路径错了" vs "图里没有" vs "帧已释放" 是三种引导）；
 * 5. **未匹配是答案**：`null`（不编 `ERR_NOT_FOUND` —— 那是 UiSelector 的语义）；
 * 6. **帧句柄纪律**：release 幂等（脚本 finally 补一刀）、未知/跨代 → `ERR_STALE_HANDLE`
 *    （"已释放"与"从未存在"可分辨）；匹配用已释放的帧 → 同码；
 * 7. **不猜别名**：`toGrayscale`/`crop`/`pixel`/`fromFile` 等未开桥面的操作 →
 *    `ERR_NOT_IMPLEMENTED`（灰度/裁剪归 §9.2 native 面，P1 再说）。
 *
 * 像素语义归 `:bridge:image` 的 native 实现（还没到位）—— 这里只钉桥面形状与口径。
 */
class ImagesNamespaceHandlerTest {

    /** 假分析器：能看见"像素"的最小替身（匹配结果由用例指定，不是真遍历）。 */
    private class FakeAnalyzer : ImageAnalyzer {
        val decoded = mutableListOf<String>()
        val released = mutableListOf<HandleRef>()
        val matches = mutableListOf<Triple<HandleRef, HandleRef, Double>>()
        var nextWidth = 1080
        var nextHeight = 2400
        var decodeFail: AutojsException? = null
        var matchResult: ImageMatch? = null

        override suspend fun decode(path: String): ImageFrame {
            decodeFail?.let { throw it }
            decoded += path
            // 自己的 refId 空间与 handler 无关：handler 只凭 SPI 回包拿宽高
            return ImageFrame(HandleRef(999, 1), nextWidth, nextHeight)
        }

        override suspend fun release(handle: HandleRef) {
            released += handle
        }

        override suspend fun matchTemplate(
            haystack: HandleRef,
            needle: HandleRef,
            threshold: Double,
        ): ImageMatch? {
            matches += Triple(haystack, needle, threshold)
            return matchResult
        }

        override suspend fun findImage(
            haystack: HandleRef,
            needle: HandleRef,
            threshold: Double,
        ): ImageMatch? {
            matches += Triple(haystack, needle, threshold)
            return matchResult
        }
    }

    private val fake = FakeAnalyzer()
    private val handler = CapabilityNamespaces.images(fake)

    private suspend fun call(method: String, payload: String?): BridgeResponse =
        handler.handle(BridgeRequest(1, "images", method, payload, 5_000))

    private fun ok(r: BridgeResponse): String =
        assertInstanceOf(BridgeResponse.Ok::class.java, r).payload!!

    private fun errCode(r: BridgeResponse): String =
        assertInstanceOf(BridgeResponse.Err::class.java, r).errorCode

    /** decode 一帧，拿回脚本可见的 ref（handler 自管发号，与假分析器的 999 无关）。 */
    private suspend fun decodeFrame(path: String = "/sdcard/icon.png"): HandleRef {
        val payload = ok(call("decode", """{"path":"$path"}"""))
        val ref = (A11yBridgeJson.decodeObject(payload)["ref"] as A11yBridgeJson.Value.Obj).fields
        return HandleRef(
            (ref["refId"] as A11yBridgeJson.Value.N).raw.toLong(),
            (ref["generation"] as A11yBridgeJson.Value.N).raw.toLong(),
        )
    }

    /** SPI 帧句柄的 wire 信封（handler 的 `ref` 字段，§7.4）。 */
    private fun refJson(ref: HandleRef): String =
        """{"ref":{"refId":${ref.refId},"generation":${ref.generation}}}"""

    /** 匹配载荷（`haystack`/`needle` 各持一个 ref 信封 + 统一键 `threshold`）。 */
    private fun matchJson(haystack: HandleRef, needle: HandleRef, threshold: String): String =
        """{"haystack":{"refId":${haystack.refId},"generation":${haystack.generation}},"needle":{"refId":${needle.refId},"generation":${needle.generation}},"threshold":$threshold}"""

    @Test
    fun `decode 发 path 回帧句柄 + 文件真宽高`() = runBlocking {
        val payload = ok(call("decode", """{"path":"/sdcard/icon.png"}"""))
        val o = A11yBridgeJson.decodeObject(payload)
        val ref = (o["ref"] as A11yBridgeJson.Value.Obj).fields
        assertEquals("1", (ref["refId"] as A11yBridgeJson.Value.N).raw, "handler 自管发号从 1 起")
        assertEquals("1", (ref["generation"] as A11yBridgeJson.Value.N).raw, "一个文件一个帧，generation 恒 1")
        assertEquals("1080", (o["width"] as A11yBridgeJson.Value.N).raw, "宽高取文件真值（对齐 :domain ImageFrame）")
        assertEquals("2400", (o["height"] as A11yBridgeJson.Value.N).raw)
        assertEquals(listOf("/sdcard/icon.png"), fake.decoded, "path 原样到 SPI，不拼接不解析")
        Unit
    }

    @Test
    fun `阈值一个键 threshold，两方法同形`() = runBlocking {
        fake.matchResult = ImageMatch(10, 20, 100, 50, 0.93)
        val haystack = decodeFrame("/sdcard/screen.png")
        val needle = decodeFrame("/sdcard/icon.png")

        val both = matchJson(haystack, needle, "0.9")
        val a = ok(call("matchTemplate", both))
        val b = ok(call("findImage", both))
        assertEquals(a, b, "两个方法同一个 wire 形状（facade 曾一个发 tolerance 一个发 threshold）")

        assertEquals(2, fake.matches.size)
        val (h, n, t) = fake.matches.last()
        assertEquals(haystack, h)
        assertEquals(needle, n)
        assertEquals(0.9, t, 0.0)
        val o = A11yBridgeJson.decodeObject(a)
        assertEquals("10", (o["x"] as A11yBridgeJson.Value.N).raw)
        assertEquals("20", (o["y"] as A11yBridgeJson.Value.N).raw)
        assertEquals("100", (o["width"] as A11yBridgeJson.Value.N).raw)
        assertEquals("50", (o["height"] as A11yBridgeJson.Value.N).raw)
        assertTrue((o["confidence"] as A11yBridgeJson.Value.N).raw == "0.93", "置信度随帧走: $a")
        Unit
    }

    @Test
    fun `未匹配回裸 null（答案不是异常）`() = runBlocking {
        fake.matchResult = null
        val haystack = decodeFrame()
        val needle = decodeFrame()
        assertEquals(
            "null",
            ok(call("findImage", matchJson(haystack, needle, "0.5"))),
            "图里没有达到阈值的位置 = null，不编 ERR_NOT_FOUND",
        )
        Unit
    }

    @Test
    fun `阈值越界拒收且不碰 SPI`() = runBlocking {
        val haystack = decodeFrame()
        val needle = decodeFrame()
        for (bad in listOf("-0.1", "1.1")) {
            assertEquals(
                "ERR_INVALID_PARAM",
                errCode(call("findImage", matchJson(haystack, needle, bad))),
                "阈值 $bad 在域外",
            )
        }
        assertTrue(fake.matches.isEmpty(), "域外阈值一次 SPI 调用都不发")
        Unit
    }

    @Test
    fun `参数错不碰 SPI——空白路径缺阈值缺帧`() = runBlocking {
        assertEquals("ERR_INVALID_PARAM", errCode(call("decode", """{"path":"   "}""")))
        assertEquals("ERR_INVALID_PARAM", errCode(call("decode", "{}")))
        assertEquals(
            "ERR_INVALID_PARAM",
            errCode(call("findImage", """{"haystack":{"refId":1,"generation":1},"needle":{"refId":1,"generation":1}}""")),
            "缺 threshold",
        )
        assertEquals(
            "ERR_INVALID_PARAM",
            errCode(call("matchTemplate", """{"needle":{"refId":1,"generation":1},"threshold":0.5}""")),
            "缺 haystack",
        )
        assertTrue(fake.decoded.isEmpty() && fake.matches.isEmpty(), "参数错一次 SPI 调用都不发")
        Unit
    }

    @Test
    fun `文件缺失与解码失败原码透传（不折叠成 INVALID_PARAM）`() = runBlocking {
        fake.decodeFail = AutojsException(ErrorCode.ERR_FILE_NOT_FOUND, "图标不在应用可读路径内")
        assertEquals("ERR_FILE_NOT_FOUND", errCode(call("decode", """{"path":"/nope.png"}""")))
        fake.decodeFail = AutojsException(ErrorCode.ERR_IO, "不是合法图片")
        assertEquals("ERR_IO", errCode(call("decode", """{"path":"/nope.png"}""")))
        Unit
    }

    @Test
    fun `release 幂等；未知跨代帧 ERR_STALE_HANDLE`() = runBlocking {
        val ref = decodeFrame()
        // 首次释放真落 SPI，回 true
        assertEquals("true", ok(call("release", refJson(ref))))
        assertEquals(listOf(ref), fake.released, "释放真落到 SPI")

        // 已释放 → STALE（放掉即不在场；与 ScreenshotSource.recycle 逐字同口径，§7.4）
        assertEquals("ERR_STALE_HANDLE", errCode(call("release", refJson(ref))))
        // 从未存在 → 同码
        assertEquals(
            "ERR_STALE_HANDLE",
            errCode(call("release", """{"ref":{"refId":4242,"generation":1}}""")),
        )
        // 跨代 → 同码
        assertEquals(
            "ERR_STALE_HANDLE",
            errCode(call("release", """{"ref":{"refId":1,"generation":7}}""")),
        )
        assertEquals(1, fake.released.size, "后三次一次 SPI 释放都没发")

        // 再 decode 一帧（refId 推进到 2）：释放仍可达 —— 脚本 finally 的补刀不会炸，
        // 是因为帧没放时怎么放都回 true；放过的就是不在场（上面那个 STALE）
        val second = decodeFrame("/sdcard/other.png")
        assertEquals(2L, second.refId, "refId 单调递增，不复用不缓存")
        val again = call("release", refJson(second))
        assertEquals(
            "true",
            assertInstanceOf(BridgeResponse.Ok::class.java, again).payload,
            "第二个帧照常可释放（幂等的是重复放同一个，不是 handler 从此放不动）",
        )
        assertEquals(2, fake.released.size)
        Unit
    }

    @Test
    fun `匹配用已释放的帧 ERR_STALE_HANDLE`() = runBlocking {
        val haystack = decodeFrame("/sdcard/screen.png")
        val needle = decodeFrame("/sdcard/icon.png")
        assertEquals(
            "true",
            assertInstanceOf(BridgeResponse.Ok::class.java, call("release", refJson(needle))).payload,
        )
        assertEquals(
            "ERR_STALE_HANDLE",
            errCode(call("findImage", matchJson(haystack, needle, "0.9"))),
        )
        assertTrue(fake.matches.isEmpty(), "帧已死，一次匹配都不发")
        Unit
    }

    @Test
    fun `未开桥面的图像操作如实 ERR_NOT_IMPLEMENTED`() = runBlocking {
        for (m in listOf("toGrayscale", "crop", "pixel", "fromFile", "captureScreen", "rotate")) {
            assertEquals(
                "ERR_NOT_IMPLEMENTED",
                errCode(call(m, """{"path":"/sdcard/icon.png"}""")),
                "灰度/裁剪/取像素/旋转归 §9.2 native 面（P1），接口期不猜",
            )
        }
        Unit
    }
}
