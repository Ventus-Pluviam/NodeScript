package com.autoscript.platform.system.images

import com.autoscript.domain.json.DomainJson
import com.autoscript.domain.automation.ColorHit
import com.autoscript.domain.automation.FeatureHit
import com.autoscript.domain.automation.ImageAnalyzer
import com.autoscript.domain.automation.ImageFrame
import com.autoscript.domain.automation.ImageMatch
import com.autoscript.domain.automation.ScreenSnapshot
import com.autoscript.domain.bridge.BridgeRequest
import com.autoscript.domain.bridge.BridgeResponse
import com.autoscript.domain.bridge.HandleRef
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import com.autoscript.platform.system.SystemNamespaces
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
 *    `matchTemplate`/`findImage` 发 `{haystack,needle,threshold}`（+ 可选 `region`
 *    四元组，缺键/JSON null = 全帧 —— 与 findColor 同键同判据）→ `{x,y,width,height,confidence}`
 *    或裸 `null`；`release` 发 `{ref}` → `true`；
 * 2. **阈值一个键**：两方法都叫 `threshold`（facade 曾一个发 `tolerance` 一个发 `threshold`，
 *    同一 opencv 概念两个键名 = 两侧 mock 各自自洽的漂移面）；
 * 3. **阈值域 [0,1]**：越界 → `ERR_INVALID_PARAM` 且**一次 SPI 调用都不发**；
 * 4. **错误不折叠**：SPI 的 `ERR_FILE_NOT_FOUND`/`ERR_IO`/`ERR_STALE_HANDLE` 原码透传
 *    （"路径错了" vs "图里没有" vs "帧已释放" 是三种引导）；
 * 5. **未匹配是答案**：`null`（不编 `ERR_NOT_FOUND` —— 那是 UiSelector 的语义）；
 * 6. **帧句柄纪律**：release 幂等（脚本 finally 补一刀）、未知/跨代 → `ERR_STALE_HANDLE`
 *    （"已释放"与"从未存在"可分辨）；匹配用已释放的帧 → 同码；
 * 7. **不猜别名**：`pixel`/`fromFile`/`captureScreen` 等两侧都没有的名字 →
 *    `ERR_NOT_IMPLEMENTED`（`toGrayscale`/`crop`/`resize`/`rotate`/`findFeature`
 *    P1 图像桥消费方，见本文件"五算子"段）。
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
        class MatchCall(
            val haystack: HandleRef,
            val needle: HandleRef,
            val threshold: Double,
            val region: List<Int>?,
        )

        val matches = mutableListOf<MatchCall>()
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
            region: List<Int>?,
        ): ImageMatch? {
            requireLive(haystack)
            requireLive(needle)
            matches += MatchCall(haystack, needle, threshold, region)
            return matchResult
        }

        override suspend fun findImage(
            haystack: HandleRef,
            needle: HandleRef,
            threshold: Double,
            region: List<Int>?,
        ): ImageMatch? {
            requireLive(haystack)
            requireLive(needle)
            matches += MatchCall(haystack, needle, threshold, region)
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

        class TransformCall(
            val source: HandleRef,
            val region: List<Int>? = null,
            val width: Int = 0,
            val height: Int = 0,
            val degrees: Double = 0.0,
        )

        val transforms = mutableListOf<TransformCall>()
        var featureResult: FeatureHit? = null
        var transformFail: AutojsException? = null

        /** 产出帧发号与 decode 同一号段、同一条在场表（§18-8(b) 发号侧归一）。
         *  尺寸随调用形状：crop 跟 region 的 w/h，其余跟 [nextWidth]/[nextHeight]。 */
        private fun issueChild(parent: HandleRef, width: Int = nextWidth, height: Int = nextHeight): ImageFrame {
            requireLive(parent)
            val id = nextRefId++
            live += id
            return ImageFrame(HandleRef(id, 1), width, height)
        }

        // 判在场在前（与 NativeImageAnalyzer 同序）：死帧连调用记录都不留 ——
        // 计数即"真到了 SPI 的调用数"。

        override suspend fun toGrayscale(frame: HandleRef): ImageFrame {
            requireLive(frame)
            transformFail?.let { throw it }
            transforms += TransformCall(frame)
            return issueChild(frame)
        }

        override suspend fun crop(frame: HandleRef, region: List<Int>): ImageFrame {
            requireLive(frame)
            transformFail?.let { throw it }
            transforms += TransformCall(frame, region = region)
            return issueChild(frame, region[2], region[3])
        }

        override suspend fun resize(frame: HandleRef, width: Int, height: Int): ImageFrame {
            requireLive(frame)
            transformFail?.let { throw it }
            transforms += TransformCall(frame, width = width, height = height)
            return issueChild(frame)
        }

        override suspend fun rotate(frame: HandleRef, degrees: Double): ImageFrame {
            requireLive(frame)
            transformFail?.let { throw it }
            transforms += TransformCall(frame, degrees = degrees)
            return issueChild(frame)
        }

        override suspend fun findFeature(scene: HandleRef, template: HandleRef): FeatureHit? {
            requireLive(scene)
            requireLive(template)
            transformFail?.let { throw it }
            featureCalls += (scene to template)
            return featureResult
        }

        /** 特征匹配调用记录（scene, template）对。 */
        val featureCalls = mutableListOf<Pair<HandleRef, HandleRef>>()
    }

    private val fake = FakeAnalyzer()
    private val handler = SystemNamespaces.images(fake)

    private suspend fun call(method: String, payload: String?): BridgeResponse =
        handler.handle(BridgeRequest(1, "images", method, payload, 5_000))

    private fun ok(r: BridgeResponse): String =
        assertInstanceOf(BridgeResponse.Ok::class.java, r).payload!!

    private fun okay(r: BridgeResponse): BridgeResponse =
        assertInstanceOf(BridgeResponse.Ok::class.java, r)

    private fun errCode(r: BridgeResponse): String =
        assertInstanceOf(BridgeResponse.Err::class.java, r).errorCode

    /** decode 一帧，拿回脚本可见的 ref（发号侧归一后就是 SPI 自己的号段）。 */
    private suspend fun decodeFrame(path: String = "/sdcard/icon.png"): HandleRef {
        val payload = ok(call("decode", """{"path":"$path"}"""))
        val ref = (DomainJson.decodeObject(payload)["ref"] as DomainJson.Value.Obj).fields
        return HandleRef(
            (ref["refId"] as DomainJson.Value.N).raw.toLong(),
            (ref["generation"] as DomainJson.Value.N).raw.toLong(),
        )
    }

    /** SPI 帧句柄的 wire 信封（handler 的 `ref` 字段，§7.4）。 */
    private fun refJson(ref: HandleRef): String =
        """{"ref":{"refId":${ref.refId},"generation":${ref.generation}}}"""

    /** 匹配载荷（`haystack`/`needle` 各持一个 ref 信封 + 统一键 `threshold` + 可选
     *  `region` 尾巴 —— 语义同 findColorJson 的：null = 不发键，"null" 字面量 = 发 JSON null）。 */
    private fun matchJson(haystack: HandleRef, needle: HandleRef, threshold: String, region: String? = null): String {
        val tail = if (region == null) "" else ",\"region\":$region"
        return """{"haystack":{"refId":${haystack.refId},"generation":${haystack.generation}},"needle":{"refId":${needle.refId},"generation":${needle.generation}},"threshold":$threshold$tail}"""
    }

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
        val o = DomainJson.decodeObject(payload)
        val ref = (o["ref"] as DomainJson.Value.Obj).fields
        assertEquals("1", (ref["refId"] as DomainJson.Value.N).raw, "SPI 发号从 1 起（handler 不再自管表）")
        assertEquals("1", (ref["generation"] as DomainJson.Value.N).raw, "一个文件一个帧，generation 恒 1")
        assertEquals("1080", (o["width"] as DomainJson.Value.N).raw, "宽高取文件真值（对齐 :domain ImageFrame）")
        assertEquals("2400", (o["height"] as DomainJson.Value.N).raw)
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
        val last = fake.matches.last()
        assertEquals(haystack, last.haystack)
        assertEquals(needle, last.needle)
        assertEquals(0.9, last.threshold, 0.0)
        val o = DomainJson.decodeObject(a)
        assertEquals("10", (o["x"] as DomainJson.Value.N).raw)
        assertEquals("20", (o["y"] as DomainJson.Value.N).raw)
        assertEquals("100", (o["width"] as DomainJson.Value.N).raw)
        assertEquals("50", (o["height"] as DomainJson.Value.N).raw)
        assertTrue((o["confidence"] as DomainJson.Value.N).raw == "0.93", "置信度随帧走: $a")
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
        val o = DomainJson.decodeObject(payload)
        assertEquals("120", (o["x"] as DomainJson.Value.N).raw)
        assertEquals("340", (o["y"] as DomainJson.Value.N).raw)
        assertEquals("18", (o["r"] as DomainJson.Value.N).raw, "回的是实际像素值，不是请求的目标色")
        assertEquals("52", (o["g"] as DomainJson.Value.N).raw)
        assertEquals("86", (o["b"] as DomainJson.Value.N).raw)
        assertEquals("255", (o["a"] as DomainJson.Value.N).raw)
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
    fun `match 的 region 可选：缺键与 JSON null 都等于全帧，给了原样到 SPI`() = runBlocking {
        fake.matchResult = ImageMatch(10, 20, 100, 50, 0.93)
        val haystack = decodeFrame("/sdcard/screen.png")
        val needle = decodeFrame("/sdcard/icon.png")

        ok(call("matchTemplate", matchJson(haystack, needle, "0.9", null)))
        ok(call("findImage", matchJson(haystack, needle, "0.9", "null")))
        ok(call("matchTemplate", matchJson(haystack, needle, "0.9", "[10,20,30,40]")))

        assertEquals(3, fake.matches.size, "三次都该到 SPI：$fake.matches")
        assertEquals(null, fake.matches[0].region, "缺 region 键 = null（native 按全帧找）")
        assertEquals(null, fake.matches[1].region, "region:null 与缺键同义，不是参数错")
        assertEquals(listOf(10, 20, 30, 40), fake.matches[2].region, "给了就原样四元组到 SPI")
        Unit
    }

    @Test
    fun `match 的 region 非四元组先拒且不碰 SPI`() = runBlocking {
        fake.matchResult = ImageMatch(10, 20, 100, 50, 0.93)
        val haystack = decodeFrame("/sdcard/screen.png")
        val needle = decodeFrame("/sdcard/icon.png")

        val bad = listOf(
            "region 三元组" to matchJson(haystack, needle, "0.9", "[1,2,3]"),
            "region 五元组" to matchJson(haystack, needle, "0.9", "[1,2,3,4,5]"),
        )
        for ((why, payload) in bad) {
            assertEquals("ERR_INVALID_PARAM", errCode(call("matchTemplate", payload)), why)
        }
        assertTrue(fake.matches.isEmpty(), "region 形状错一次 SPI 调用都不发")
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
            "tolerance 0.9（小数不容忍静默截断）" to findColorJsonRaw(hj, "[1,2,3,4]", "0.9"),
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
        for (m in listOf("pixel", "fromFile", "captureScreen")) {
            assertEquals(
                "ERR_NOT_IMPLEMENTED",
                errCode(call(m, """{"path":"/sdcard/icon.png"}""")),
                "$m 两侧都没有（§12.3.2 第 7 条），接口期不猜",
            )
        }
        Unit
    }

    // ── P1 图像桥消费方五算子（开通，§9.2 末推演兑现）──────────
    // 判据：wire 形状（`{source}` 或 `{scene,template}`）、域校验零 SPI、产出帧
    // 与 decode 帧同一条表/同一条释放路、（findFeature）未命中是答案。

    private fun transformJson(source: HandleRef, extra: String = ""): String =
        """{"source":{"refId":${source.refId},"generation":${source.generation}}$extra}"""

    @Test
    fun `toGrayscale 发 source 回产出帧`() = runBlocking {
        val shot = decodeFrame("/sdcard/shot.png")
        fake.nextWidth = 540
        fake.nextHeight = 1200
        val payload = ok(call("toGrayscale", transformJson(shot)))
        val o = DomainJson.decodeObject(payload)
        val ref = (o["ref"] as DomainJson.Value.Obj).fields
        assertEquals("2", (ref["refId"] as DomainJson.Value.N).raw, "产出帧与 decode 同一号段")
        assertEquals("540", (o["width"] as DomainJson.Value.N).raw, "宽高随产出帧真值")
        assertEquals("1200", (o["height"] as DomainJson.Value.N).raw)
        val source = fake.transforms.single().source
        assertEquals(shot, source, "source 原样到 SPI，不拼接不解析")
        Unit
    }

    @Test
    fun `crop 的 region 必填四元组，越界形状先拒且不碰 SPI`() = runBlocking {
        val shot = decodeFrame()
        for (bad in listOf(
            """{"source":{"refId":1,"generation":1}}""",
            """{"source":{"refId":1,"generation":1},"region":[1,2,3]}""",
            """{"source":{"refId":1,"generation":1},"region":"x,y,w,h"}""",
        )) {
            assertEquals("ERR_INVALID_PARAM", errCode(call("crop", bad)), "缺 region/形状不成立都是参数错: $bad")
        }
        assertTrue(fake.transforms.isEmpty(), "形状错一次 SPI 调用都不发")
        Unit
    }

    @Test
    fun `crop 把 region 原样交给 SPI`() = runBlocking {
        val shot = decodeFrame()
        val payload = ok(call("crop", """{"source":{"refId":1,"generation":1},"region":[10,20,30,40]}"""))
        val o = DomainJson.decodeObject(payload)
        val ref = (o["ref"] as DomainJson.Value.Obj).fields
        assertEquals("2", (ref["refId"] as DomainJson.Value.N).raw, "产出帧与 decode 同一号段")
        assertEquals("30", (o["width"] as DomainJson.Value.N).raw, "宽高随产出帧（区域 w/h）")
        assertEquals("40", (o["height"] as DomainJson.Value.N).raw)
        val region = fake.transforms.single().region
        assertEquals(listOf(10, 20, 30, 40), region)
        Unit
    }

    @Test
    fun `resize 目标尺寸域校验先拒且不碰 SPI`() = runBlocking {
        val shot = decodeFrame()
        for (bad in listOf(
            """{"source":{"refId":1,"generation":1},"width":0,"height":10}""",
            """{"source":{"refId":1,"generation":1},"width":10,"height":-5}""",
            """{"source":{"refId":1,"generation":1},"width":16385,"height":10}""",
            """{"source":{"refId":1,"generation":1},"width":1.5,"height":10}""",
        )) {
            assertEquals("ERR_INVALID_PARAM", errCode(call("resize", bad)), "尺寸非正/超配额/非整都是参数错: $bad")
        }
        assertTrue(fake.transforms.isEmpty(), "域错一次 SPI 调用都不发")
        Unit
    }

    @Test
    fun `resize 把目标尺寸原样交给 SPI`() = runBlocking {
        val shot = decodeFrame()
        ok(call("resize", """{"source":{"refId":1,"generation":1},"width":270,"height":1200}"""))
        val w = fake.transforms.single().width
        val h = fake.transforms.single().height
        assertEquals(270, w)
        assertEquals(1200, h)
        Unit
    }

    @Test
    fun `rotate 的角度必须有限且原样交给 SPI`() = runBlocking {
        val shot = decodeFrame()
        for (bad in listOf("NaN", "Infinity", "-Infinity")) {
            assertEquals(
                "ERR_INVALID_PARAM",
                errCode(call("rotate", """{"source":{"refId":1,"generation":1},"degrees":$bad}""")),
                "非有限角度先拒（三角函数吃掉它们不报错）：$bad",
            )
        }
        assertTrue(fake.transforms.isEmpty(), "非有限角度一次 SPI 调用都不发")
        ok(call("rotate", """{"source":{"refId":1,"generation":1},"degrees":-90}"""))
        val deg = fake.transforms.single().degrees
        assertEquals(-90.0, deg, 0.0, "逆时针角度原样到 SPI")
        Unit
    }

    @Test
    fun `findFeature 发 scene+template，未命中回裸 null`() = runBlocking {
        val scene = decodeFrame()
        val templ = decodeFrame()
        fake.featureResult = FeatureHit(345, 678, 0.63)
        val payload = ok(call("findFeature", """{"scene":{"refId":1,"generation":1},"template":{"refId":2,"generation":1}}"""))
        val o = DomainJson.decodeObject(payload)
        assertEquals("345", (o["x"] as DomainJson.Value.N).raw)
        assertEquals("678", (o["y"] as DomainJson.Value.N).raw)
        assertEquals("0.63", (o["confidence"] as DomainJson.Value.N).raw)
        assertEquals(Pair(scene, templ), fake.featureCalls.single())

        fake.featureResult = null
        assertEquals("null", ok(call("findFeature", """{"scene":{"refId":1,"generation":1},"template":{"refId":2,"generation":1}}""")), "未匹配是答案，不是异常")
        Unit
    }

    @Test
    fun `产出帧与源帧同一条表——释放、STALE、再变换`() = runBlocking {
        val shot = decodeFrame()
        val payload = ok(call("toGrayscale", transformJson(shot)))
        val ref = (DomainJson.decodeObject(payload)["ref"] as DomainJson.Value.Obj).fields
        val child = HandleRef(
            (ref["refId"] as DomainJson.Value.N).raw.toLong(),
            (ref["generation"] as DomainJson.Value.N).raw.toLong(),
        )
        // 产出帧可释放、可再变换
        assertEquals("true", ok(call("release", refJson(child))))
        assertEquals(listOf(child), fake.released, "产出帧与 decode 帧同一条释放路")
        // 放掉的产出帧 → STALE（同源帧同一张表，判据只有在场性）
        assertEquals("ERR_STALE_HANDLE", errCode(call("toGrayscale", transformJson(child))))
        // 源帧不受影响，原帧照常可用
        okay(call("toGrayscale", transformJson(shot)))
        assertEquals(2, fake.transforms.size)
        Unit
    }

}
