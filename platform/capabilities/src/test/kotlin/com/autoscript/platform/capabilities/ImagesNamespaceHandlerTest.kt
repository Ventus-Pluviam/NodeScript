package com.autoscript.platform.capabilities

import com.autoscript.domain.automation.ColorHit
import com.autoscript.domain.automation.ImageAnalyzer
import com.autoscript.domain.automation.ImageFrame
import com.autoscript.domain.automation.ImageMatch
import com.autoscript.domain.automation.ScreenSnapshot
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
import org.junit.jupiter.api.assertThrows

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

    /**
     * 假分析器：能看见"像素"的最小替身（匹配结果由用例指定，不是真遍历）。
     *
     * **它是帧表本身**（§18-8(b) 发号侧归一后 handler 不再自管表，于是这里的
     * 在场/发号/STALE 判据就是被测契约）：`decode` 与 `ingest` 共用 `nextRefId`
     * 从 1 起，放掉即离场，未知/跨代/已放 → `ERR_STALE_HANDLE`。
     */
    private class FakeAnalyzer : ImageAnalyzer {
        val decoded = mutableListOf<String>()
        val released = mutableListOf<HandleRef>()
        val matches = mutableListOf<Triple<HandleRef, HandleRef, Double>>()
        var nextWidth = 1080
        var nextHeight = 2400
        var decodeFail: AutojsException? = null
        var matchResult: ImageMatch? = null

        private var nextRefId = 1L
        private val live = mutableSetOf<Long>()

        override suspend fun decode(path: String): ImageFrame {
            decodeFail?.let { throw it }
            decoded += path
            val id = nextRefId++
            live += id
            return ImageFrame(HandleRef(id, 1), nextWidth, nextHeight)
        }

        override suspend fun ingest(width: Int, height: Int, rgba: ByteArray): ImageFrame {
            require(width > 0 && height > 0) { "ingest 的宽高必须为正，实际 ${width}x$height" }
            require(rgba.size == width * height * 4) {
                "ingest 的像素必须紧密打包 RGBA：期望 ${width * height * 4} 字节，实际 ${rgba.size}"
            }
            val id = nextRefId++
            live += id
            return ImageFrame(HandleRef(id, 1), width, height)
        }

        /** 在场性唯一判据（与 `NativeImageAnalyzer.release` 同口径）。 */
        private fun requireLive(h: HandleRef) {
            if (h.generation != 1L || h.refId !in live) {
                throw AutojsException(ErrorCode.ERR_STALE_HANDLE, "帧 ${h.refId} 不在场")
            }
        }

        override suspend fun release(handle: HandleRef) {
            requireLive(handle)
            live -= handle.refId
            released += handle
        }

        override suspend fun matchTemplate(
            haystack: HandleRef,
            needle: HandleRef,
            threshold: Double,
        ): ImageMatch? {
            requireLive(haystack)
            requireLive(needle)
            matches += Triple(haystack, needle, threshold)
            return matchResult
        }

        override suspend fun findImage(
            haystack: HandleRef,
            needle: HandleRef,
            threshold: Double,
        ): ImageMatch? {
            requireLive(haystack)
            requireLive(needle)
            matches += Triple(haystack, needle, threshold)
            return matchResult
        }

        class ColorCall(
            val haystack: HandleRef,
            val color: List<Int>,
            val tolerance: Int,
            val region: List<Int>?,
        )

        val colorCalls = mutableListOf<ColorCall>()
        var colorResult: ColorHit? = null
        var colorFail: AutojsException? = null

        override suspend fun findColor(
            haystack: HandleRef,
            color: List<Int>,
            tolerance: Int,
            region: List<Int>?,
        ): ColorHit? {
            requireLive(haystack)
            colorFail?.let { throw it }
            colorCalls += ColorCall(haystack, color, tolerance, region)
            return colorResult
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

    /** decode 一帧，拿回脚本可见的 ref（发号侧归一后就是 SPI 自己的号段）。 */
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

    /** 找色载荷：`haystack` ref 信封 + 目标色 `color` + 容差 `tolerance` + 可选 `region`。
     *  `region` 形参传 Kotlin `null` = **不发这个键**，传 `"null"` = 发 JSON null
     *  （两者在契约里同义，用例里必须都能表达）。 */
    private fun findColorJson(
        haystack: HandleRef,
        color: String,
        tolerance: String,
        region: String? = null,
    ): String {
        val tail = if (region == null) "" else ",\"region\":" + region
        return """{"haystack":{"refId":${haystack.refId},"generation":${haystack.generation}},"color":$color,"tolerance":$tolerance$tail}"""
    }

    /** 域越界用例专用：自己拼信封（refId 1 不必真在场——域校验在帧号检查之前）。 */
    private fun findColorJsonRaw(refEnvelope: String, color: String, tolerance: String, region: String = ""): String =
        """{"haystack":$refEnvelope,"color":$color,"tolerance":$tolerance$region}"""

    @Test
    fun `decode 发 path 回帧句柄 + 文件真宽高`() = runBlocking {
        val payload = ok(call("decode", """{"path":"/sdcard/icon.png"}"""))
        val o = A11yBridgeJson.decodeObject(payload)
        val ref = (o["ref"] as A11yBridgeJson.Value.Obj).fields
        assertEquals("1", (ref["refId"] as A11yBridgeJson.Value.N).raw, "SPI 发号从 1 起（handler 不再自管表）")
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
        assertEquals(1, fake.released.size, "后三次都到了 SPI，但没一次落成成功释放（在场性唯一判据在帧表）")

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

    // ── findColor（§9.2 P1 第一个算子；§7.7/§15 承诺"找色 < 10ms"）────────────
    // 三条口径单独钉：wire 形状、域校验零 SPI、帧句柄。
    // 第四条（"扫过了没有" vs "区域扫过 0 像素"）在 native 层，见 imgnative.cpp。

    @Test
    fun `findColor 发 haystack+color+tolerance 回六字段命中`() = runBlocking {
        fake.colorResult = ColorHit(120, 340, 18, 52, 86, 255)
        val haystack = decodeFrame("/sdcard/screen.png")

        val payload = ok(call("findColor", findColorJson(haystack, "[18,52,86,255]", "10")))
        val o = A11yBridgeJson.decodeObject(payload)
        assertEquals("120", (o["x"] as A11yBridgeJson.Value.N).raw)
        assertEquals("340", (o["y"] as A11yBridgeJson.Value.N).raw)
        assertEquals("18", (o["r"] as A11yBridgeJson.Value.N).raw, "回的是实际像素值，不是请求的目标色")
        assertEquals("52", (o["g"] as A11yBridgeJson.Value.N).raw)
        assertEquals("86", (o["b"] as A11yBridgeJson.Value.N).raw)
        assertEquals("255", (o["a"] as A11yBridgeJson.Value.N).raw)
        assertEquals(1, fake.colorCalls.size, "命中了也如实记一次 SPI 调用")
        Unit
    }

    @Test
    fun `findColor 未命中回裸 null（扫过了、没有）`() = runBlocking {
        fake.colorResult = null
        val haystack = decodeFrame()
        assertEquals(
            "null",
            ok(call("findColor", findColorJson(haystack, "[0,0,0,255]", "0"))),
            "扫过一遍没这个色 = null（答案），不编 ERR_NOT_FOUND",
        )
        Unit
    }

    @Test
    fun `findColor 的 region 可选：缺键与 JSON null 都等于全帧`() = runBlocking {
        fake.colorResult = ColorHit(5, 6, 1, 2, 3, 4)
        val haystack = decodeFrame()

        ok(call("findColor", findColorJson(haystack, "[1,2,3,4]", "0", null)))
        ok(call("findColor", findColorJson(haystack, "[1,2,3,4]", "0", "null")))
        ok(call("findColor", findColorJson(haystack, "[1,2,3,4]", "0", "[10,20,30,40]")))

        assertEquals(3, fake.colorCalls.size, "三次都该到 SPI：$fake.colorCalls")
        assertEquals(null, fake.colorCalls[0].region, "缺 region 键 = null（native 按全帧扫）")
        assertEquals(null, fake.colorCalls[1].region, "region:null 与缺键同义，不是参数错")
        assertEquals(listOf(10, 20, 30, 40), fake.colorCalls[2].region, "给了就原样四元组到 SPI")
        Unit
    }

    @Test
    fun `findColor 参数域越界拒收且不碰 SPI`() = runBlocking {
        val hj = """{"refId":1,"generation":1}"""
        val bad = listOf(
            "color 三分量" to findColorJsonRaw(hj, "[1,2,3]", "0"),
            "color 五分量" to findColorJsonRaw(hj, "[1,2,3,4,5]", "0"),
            "分量 256" to findColorJsonRaw(hj, "[256,2,3,4]", "0"),
            "分量 -1" to findColorJsonRaw(hj, "[1,-1,3,4]", "0"),
            "tolerance 256" to findColorJsonRaw(hj, "[1,2,3,4]", "256"),
            "tolerance -1" to findColorJsonRaw(hj, "[1,2,3,4]", "-1"),
            "region 三元组" to findColorJsonRaw(hj, "[1,2,3,4]", "0", "[1,2,3]"),
            "缺 color" to """{"haystack":$hj,"tolerance":0}""",
            "缺 tolerance" to """{"haystack":$hj,"color":[1,2,3,4]}""",
            "缺 haystack" to """{"color":[1,2,3,4],"tolerance":0}""",
        )
        for ((why, payload) in bad) {
            assertEquals("ERR_INVALID_PARAM", errCode(call("findColor", payload)), why)
        }
        assertTrue(fake.colorCalls.isEmpty(), "参数错一次 SPI 调用都不发（域内/域外都先在本层判）")
        Unit
    }

    @Test
    fun `findColor 用已释放的帧 ERR_STALE_HANDLE`() = runBlocking {
        val haystack = decodeFrame("/sdcard/screen.png")
        assertEquals("true", ok(call("release", refJson(haystack))))
        assertEquals(
            "ERR_STALE_HANDLE",
            errCode(call("findColor", findColorJson(haystack, "[1,2,3,4]", "0"))),
        )
        assertTrue(fake.colorCalls.isEmpty(), "帧已死，一次找色都不发")
        Unit
    }

    @Test
    fun `findColor 的 SPI 错误原码透传`() = runBlocking {
        val haystack = decodeFrame()
        fake.colorFail = AutojsException(ErrorCode.ERR_STALE_HANDLE, "帧已释放")
        assertEquals(
            "ERR_STALE_HANDLE",
            errCode(call("findColor", findColorJson(haystack, "[1,2,3,4]", "0"))),
        )
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

    // ── 跨命名空间（§18-8(b) 2026-09-25 拍板："帧不通用"那条纪律取消）───────
    // 两个 handler 共用**同一个** fake = 生产侧 `PlatformWiring` 把同一个 analyzer
    // 同时喂给 `images` 与 `ScreenshotSource` 的形态。判据是"互认"：
    // 截屏帧当 haystack 能匹配、能被 `images.release` 放掉、放掉后再用 STALE。

    private fun rgbaProducer(w: Int, h: Int): ScreenshotSource.FrameProducer =
        object : ScreenshotSource.FrameProducer {
            override suspend fun snapshot(): ScreenSnapshot =
                ScreenSnapshot(locked = false, secureForeground = false, hasWindows = true)

            override suspend fun produce(width: Int, height: Int): ProducedFrame =
                ProducedFrame(ByteArray(w * h * 4), w, h)
        }

    @Test
    fun `截屏帧与 decode 帧同一张表——findImage 通、images 能放 screen 的帧`() = runBlocking {
        val shared = FakeAnalyzer()
        val images = CapabilityNamespaces.images(shared)
        // 可控时钟：capture 走 333ms 节流，别让用例撞在窗口上
        var now = 1_000L
        val screen = ScreenshotSource(rgbaProducer(4, 4), clock = { now }, analyzer = shared)

        val shot = screen.capture()
        assertEquals(1L, shot.handle.refId, "截屏帧进的是 images 那张表（号段从 1 起）")

        val iconPayload = ok(images.handle(BridgeRequest(2, "images", "decode", """{"path":"/sdcard/icon.png"}""", 5_000)))
        val iconFields = (A11yBridgeJson.decodeObject(iconPayload)["ref"] as A11yBridgeJson.Value.Obj).fields
        val icon = HandleRef(
            (iconFields["refId"] as A11yBridgeJson.Value.N).raw.toLong(),
            (iconFields["generation"] as A11yBridgeJson.Value.N).raw.toLong(),
        )
        assertEquals(2L, icon.refId, "decode 接着截屏帧往下发号 —— 同一段，不是两张表")

        // 互认的核心：截屏帧当 haystack 不是 ERR_STALE_HANDLE
        val matched = ok(
            images.handle(
                BridgeRequest(3, "images", "findImage", matchJson(shot.handle, icon, "0.9"), 5_000),
            ),
        )
        assertEquals("null", matched, "跨来源两帧都认得（fake 未设命中 → 裸 null，不是 STALE）")

        // `images.release` 放得掉一帧截屏（曾经：这张表里根本没有它）
        assertEquals("true", ok(images.handle(BridgeRequest(4, "images", "release", refJson(shot.handle), 5_000))))
        assertEquals(
            "ERR_STALE_HANDLE",
            errCode(images.handle(BridgeRequest(5, "images", "release", refJson(shot.handle), 5_000))),
            "放掉即离场：两边同一口径",
        )
        // screen 侧再 recycle 同一帧 → 同码（同一张表、同一个"已释放"事实）
        val e = assertThrows<AutojsException> { runBlocking { screen.recycle(shot.handle) } }
        assertEquals(ErrorCode.ERR_STALE_HANDLE, e.error)

        // 截屏帧放掉后，decode 帧照常在场可放（两帧互不牵连）
        assertEquals("true", ok(images.handle(BridgeRequest(6, "images", "release", refJson(icon), 5_000))))
        Unit
    }
}
