package com.autoscript.platform.system.images
import com.autoscript.domain.bridge.generated.WireMethods

import com.autoscript.domain.bridge.decodeObject
import com.autoscript.domain.bridge.requiredStr
import com.autoscript.domain.bridge.requiredInt
import com.autoscript.domain.bridge.requiredDouble
import com.autoscript.domain.bridge.requiredRef
import com.autoscript.domain.bridge.requiredIntList
import com.autoscript.domain.bridge.optIntList
import com.autoscript.domain.bridge.RpcNamespaceHandler
import com.autoscript.domain.bridge.BridgeResponse
import com.autoscript.domain.bridge.BridgeRequest
import com.autoscript.domain.json.DomainJson
import com.autoscript.domain.automation.ColorHit
import com.autoscript.domain.automation.FeatureHit
import com.autoscript.domain.automation.ImageAnalyzer
import com.autoscript.domain.automation.ImageFrame
import com.autoscript.domain.automation.ImageMatch
import com.autoscript.domain.bridge.HandleRef
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode

/**
 * `images` 命名空间的桥处理器（docs §9.2 / §12.2；JS 对偶 `bridge/js/src/images.ts`；
 * SPI 见 `:domain` 的 `ImageAnalyzer`）。
 *
 * 与 [ClipboardNamespaceHandler] / [SensorsNamespaceHandler] 同属系统面：**独立注入缝**
 * （`AppShell.assemble` 的 `imagesHandler`），不入 `SystemHandlers` 束 —— 图像面没有共担
 * 门禁（读图是应用私有目录内的 IO，匹配是纯计算；`ERR_FILE_NOT_FOUND`/`ERR_STALE_HANDLE`
 * 判据在 SPI 自己身上，§12.2 接线表）。单独成文件同前六者，且**刻意不住**
 * `SystemNamespaces.kt`：那五个共享一套 OVERLAY/ROOT/ADB_INPUT 门禁组，图像面没有门禁，
 * 混进去只会让「接就五个一起接」的束语义变浑。
 *
 * **十方法**（照抄 `ImageAnalyzer`）：`decode`/`matchTemplate`/`findImage`/`findColor`/`release`
 * + P1 桥消费方五算子 `toGrayscale`/`crop`/`resize`/`rotate`/`findFeature`
 * （§9.2 末"桥面刻意不开"的推演兑现 —— 计算核与 host 语义门已落，消费方已到，
 * 同批开）。阈值键**统一叫 `threshold`**（[matchTemplate] 与
 * [findImage] 同一个 opencv 概念，facade 曾一个发 `tolerance` 一个发 `threshold`，
 * 两侧 mock 各自自洽所以漂移没被抓到）；域 `[0,1]`，越界 → `ERR_INVALID_PARAM` 且
 * **一次 SPI 调用都不发**。两匹配方法另有**可选 `region`**（缺键/JSON null = 全帧；
 * 给了必须 `[x,y,w,h]` 四元组，与 findColor 同键同判据；命中坐标恒**全帧口径**，
 * region 比模板小由 SPI/native 判 `ERR_IO`）。
 *
 * **帧句柄：发号侧归一到 [ImageAnalyzer]（§18 第 8 项 (b) 拍板）**。
 * 本 handler **不再自管帧表**（曾经有 `ids`/`live`/`sizes` 三张本地图 —— 与 SPI 的
 * native 帧表**双写**，靠"两个计数器各自从 1 起、每次 decode 各加一"的隐式不变式
 * 对齐，一处失败分岔就错位）：`decode` 回包直接用 SPI 回的 [HandleRef]（单调 refId +
 * generation 恒 1），`matchTemplate`/`findImage`/`findColor`/`release` 一律把 wire 上的
 * 句柄**原样交给 SPI** 判在场与释放 —— 在场性、发号、像素所有权三件事的唯一事实源
 * 是 SPI 自己。回包仍带 `width/height` 真值（脚本拿它做坐标换算）；**匹配方法不要求
 * 回传尺寸**（那是对实现报它自己已知的值）。
 *
 * 由此 **`screen` 与 `images` 的句柄在同一个号段上**（截屏帧经 `ImageAnalyzer.ingest`
 * 进同一张表）："帧不通用"那条纪律已取消 —— 拿 `screen.capture()` 的帧当
 * `findImage` 的 haystack 不再是 `ERR_STALE_HANDLE`。[release] 与
 * `ScreenshotSource.recycle` 逐字同口径：放掉的帧当场从在场面表移除 —— 未知/跨代/
 * **放过的帧再放**一律 `ERR_STALE_HANDLE`（不提供静默成功的第二次；脚本 `finally`
 * 里补一刀不会炸，是因为帧没放时怎么放都回 `true`）；匹配时任一帧已死 → 同码。
 *
 * **未匹配是答案不是异常**：`matchTemplate`/`findImage`/`findColor` 回 `null`（屏上图里
 * 没有达到阈值/容差的位置），**不编** `ERR_NOT_FOUND`（那是 UiSelector 的语义）；文件
 * 缺失是分类错误（`ERR_FILE_NOT_FOUND`），不是 null。`findColor` 另有一条要分开的口径：
 * **"扫过了、没有"（null）** 与 **"区域扫过 0 像素"（`ERR_INVALID_PARAM`）** 不是
 * 一回事 —— 后者连"找过"都算不上，混同会让脚本把空区域当成搜过一遍。
 *
 * SPI 抛的 `AutojsException` 原码透传（不折叠成 `ERR_INVALID_PARAM`）—— 调用方要能
 * 分辨「路径错了」与「图里没有」与「帧已释放」。未知方法 → `ERR_NOT_IMPLEMENTED`
 * （`pixel`/`captureScreen` 这些 native 面都没有的操作不猜）。
 */
class ImagesNamespaceHandler(
    private val analyzer: ImageAnalyzer,
) : RpcNamespaceHandler() {

    /** 申报方法表（wire-schema 对账挂点）：单源指向生成物 [WireMethods.BY_NS]，不手抄。 */
    override fun methods(): Set<String> = WireMethods.BY_NS.getValue("images")

    /** 本类**无状态**：帧表（在场/发号/像素）全在 [analyzer] 里（§18-8(b) 发号侧归一）。 */

    override suspend fun dispatch(request: BridgeRequest): BridgeResponse = when (request.method) {
        "decode" -> decode(request)
        "matchTemplate" -> matchTemplate(request)
        "findImage" -> findImage(request)
        "findColor" -> findColor(request)
        "toGrayscale" -> toGrayscale(request)
        "crop" -> crop(request)
        "resize" -> resize(request)
        "rotate" -> rotate(request)
        "findFeature" -> findFeature(request)
        "release" -> release(request)
        else -> err(request, ErrorCode.ERR_NOT_IMPLEMENTED,
            "未知 images 方法: ${request.method}",
        )
    }

    private suspend fun decode(request: BridgeRequest): BridgeResponse {
        val fields = request.decodeObject()
        val path = request.requiredStr(fields, "path")
        if (path.isBlank()) {
            return err(request, ErrorCode.ERR_INVALID_PARAM, "images decode 的 path 不得为空白")
        }
        return run {
            val frame = analyzer.decode(path)
            // 发号归 SPI：wire 上的 refId 就是帧表的键（handler 不再另起一套号）。
            val ref = frame.handle
            ok(request, DomainJson.encode(
                    mapOf(
                        "ref" to mapOf("refId" to ref.refId, "generation" to ref.generation),
                        "width" to frame.width.toLong(),
                        "height" to frame.height.toLong(),
                    ),
                ),
            )
        }
    }

    private suspend fun matchTemplate(request: BridgeRequest): BridgeResponse =
        match(request, "matchTemplate")

    private suspend fun findImage(request: BridgeRequest): BridgeResponse =
        match(request, "findImage")

    private suspend fun match(request: BridgeRequest, method: String): BridgeResponse {
        val fields = request.decodeObject()
        val refs: Pair<HandleRef, HandleRef>
        val threshold: Double
        val region: List<Int>?
        run {
            refs = request.requiredRef(fields, "haystack") to request.requiredRef(fields, "needle")
            threshold = request.requiredDouble(fields, "threshold")
            // region 可选：缺键/JSON null = 全帧；给了就必须四元组（不是参数错）——
            // 与 findColor 是**同一个键、同一条判据**（两张表分写就是漂移面）。
            region = request.optIntList(fields, "region")
        }
        // 域 [0,1]：域外阈值在任何实现上都命中不了/恒命中，先拒（不发 SPI 调用）。
        if (threshold < 0.0 || threshold > 1.0) {
            return err(request, ErrorCode.ERR_INVALID_PARAM,
                "images $method 的 threshold 必须在 [0,1]，实际 $threshold",
            )
        }
        region?.let { box ->
            if (box.size != 4) {
                return err(request, ErrorCode.ERR_INVALID_PARAM,
                    "images $method 的 region 必须 x,y,w,h 四元组，实际 $box",
                )
            }
        }
        val (haystack, needle) = refs
        // 在场性不在这层判（§18-8(b)：帧表唯一事实源是 SPI）—— 任一帧已死由 SPI 抛
        // ERR_STALE_HANDLE，这里 catch 后原码透传；这样"截屏帧"与"decode 帧"都由
        // 同一张表回答，不存在 handler 的表认识、SPI 的表不认识的分岔。
        val hit = run {
            if (method == "matchTemplate") {
                analyzer.matchTemplate(haystack, needle, threshold, region)
            } else {
                analyzer.findImage(haystack, needle, threshold, region)
            }
        }
        return ok(request, matchPayload(hit))
    }

    private suspend fun findColor(request: BridgeRequest): BridgeResponse {
        val fields = request.decodeObject()
        val ref: HandleRef
        val color: List<Int>
        val tolerance: Double
        val region: List<Int>?
        run {
            ref = request.requiredRef(fields, "haystack")
            color = request.requiredIntList(fields, "color")
            // 先按 Double 收、整数性在域校验那步核 —— 不在此 .toInt() 截断：
            // 0.9 截成 0 等于把「不容差」悄悄改成「精确色」，与 requiredIntList
            // KDoc「不把 1.5 这种值悄悄截给 native」同一条纪律，这条原本犯同款。
            tolerance = request.requiredDouble(fields, "tolerance")
            // region 可选：缺键/JSON null = 全帧；给了就必须四元组（不是就参数错）。
            region = request.optIntList(fields, "region")
        }
        // 域校验（与 :domain ImageAnalyzer.findColor KDoc 逐条对齐）：
        //   color 恒四分量 [r,g,b,a]、各 [0,255]；tolerance [0,255] 整数；region 四元组。
        // 全部先拒，**一次 SPI 调用都不发**（与阈值域同一条纪律）。
        if (color.size != 4 || color.any { it < 0 || it > 255 }) {
            return err(request, ErrorCode.ERR_INVALID_PARAM,
                "images findColor 的 color 必须 r,g,b,a 四分量且各在 [0,255]，实际 $color",
            )
        }
        if (tolerance < 0 || tolerance > 255 || tolerance % 1.0 != 0.0) {
            return err(request, ErrorCode.ERR_INVALID_PARAM,
                "images findColor 的 tolerance 必须是 [0,255] 的整数，实际 $tolerance",
            )
        }
        region?.let { box ->
            if (box.size != 4) {
                return err(request, ErrorCode.ERR_INVALID_PARAM,
                    "images findColor 的 region 必须 x,y,w,h 四元组，实际 $box",
                )
            }
        }
        // 在场性同 match：SPI 判、原码透传（见 match 处的说明）。
        val hit = analyzer.findColor(ref, color, tolerance.toInt(), region)
        return ok(request, colorPayload(hit))
    }

    private suspend fun release(request: BridgeRequest): BridgeResponse {
        val fields = request.decodeObject()
        val ref = request.requiredRef(fields, "ref")
        // 未知/跨代/放过的帧再放 → 一律 ERR_STALE_HANDLE，判据在 SPI 的帧表里
        // （与 ScreenshotSource.recycle 同一张表、同一口径 —— §18-8(b) 发号侧归一的
        // 直接后果：screen 的帧也能在这里放，反之亦然）。原码透传。
        return run {
            analyzer.release(ref)
            ok(request, "true")
        }
    }

    // ── P1 图像桥消费方五算子（§9.2 末 —— 计算核与 host 语义门已全绿，
    // 消费方已到，桥面同批开）─────────────────────────────
    // 形状统一：`{source}` → `{ref,width,height}`（回包与 decode 同形 —— 新帧也落
    // 进 SPI 同一张表，宽高随产出帧回真值）；域校验照 findColor 同一条纪律
    // （越界先拒，**一次 SPI 调用都不发**）。

    private suspend fun toGrayscale(request: BridgeRequest): BridgeResponse =
        singleRefFrame(request, "toGrayscale") { ref -> analyzer.toGrayscale(ref) }

    private suspend fun crop(request: BridgeRequest): BridgeResponse {
        val fields = request.decodeObject()
        val ref: HandleRef
        val region: List<Int>
        run {
            ref = request.requiredRef(fields, "source")
            region = request.requiredIntList(fields, "region")
        }
        // 域：region 必须 [x,y,w,h] 四元组（整体落在帧内由 SPI/native 判 —— 帧
        // 尺寸真值不在本层，只把"形状必须成立"挡在桥面）。越界先拒，零 SPI。
        if (region.size != 4) {
            return err(request, ErrorCode.ERR_INVALID_PARAM,
                "images crop 的 region 必须 x,y,w,h 四元组，实际 $region",
            )
        }
        return ok(request, framePayload(analyzer.crop(ref, region)))
    }

    private suspend fun resize(request: BridgeRequest): BridgeResponse {
        val fields = request.decodeObject()
        val ref: HandleRef
        val width: Int
        val height: Int
        run {
            ref = request.requiredRef(fields, "source")
            // 目标尺寸是 Int 域：requiredInt 把非整数（1.5）与**超 Int 域的值**都挡成参数错
            // —— 后者此前是 `requiredLong(...).toInt()`，4294967297 静默截成 1（一个合法宽）。
            width = request.requiredInt(fields, "width")
            height = request.requiredInt(fields, "height")
        }
        // 目标尺寸域：正整数 + 单边配额 16384（16384²×4≈1GB，再往上是笔误把字节数
        // 当宽高 —— 配额与尺寸不合法同码，native/SPI 同样先拒，这里挡在桥面）。
        if (width <= 0 || height <= 0 || width > 16384 || height > 16384) {
            return err(request, ErrorCode.ERR_INVALID_PARAM,
                "images resize 的目标尺寸必须 (0,16384] 正整数，实际 ${width}x$height",
            )
        }
        return ok(request, framePayload(analyzer.resize(ref, width, height)))
    }

    private suspend fun rotate(request: BridgeRequest): BridgeResponse {
        val fields = request.decodeObject()
        val ref: HandleRef
        val degrees: Double
        run {
            ref = request.requiredRef(fields, "source")
            degrees = request.requiredDouble(fields, "degrees")
        }
        // 角度域：必须有限（NaN/Inf → 参数错 —— 三角函数吃掉它们不报错）。原生侧
        // 还有 16384 画布配额（包络公式随角度涨），那层拒收原码透传（INVALID_PARAM）。
        if (!degrees.isFinite()) {
            return err(request, ErrorCode.ERR_INVALID_PARAM,
                "images rotate 的 degrees 必须是有限数字，实际 $degrees",
            )
        }
        return ok(request, framePayload(analyzer.rotate(ref, degrees)))
    }

    private suspend fun findFeature(request: BridgeRequest): BridgeResponse {
        val fields = request.decodeObject()
        val refs: Pair<HandleRef, HandleRef>
        run {
            refs = request.requiredRef(fields, "scene") to request.requiredRef(fields, "template")
        }
        val hit = analyzer.findFeature(refs.first, refs.second)
        return ok(request, featurePayload(hit))
    }

    /** 产出帧回包（与 decode 同形：ref + 宽高真值，随产出帧走）。 */
    private fun framePayload(frame: ImageFrame): String =
        DomainJson.encode(
            mapOf(
                "ref" to mapOf("refId" to frame.handle.refId, "generation" to frame.handle.generation),
                "width" to frame.width.toLong(),
                "height" to frame.height.toLong(),
            ),
        )

    /** 单帧变换通用骨架：`{source}` 信封 → SPI 调用 → 产出帧回包（toGrayscale 用）。 */
    private suspend fun singleRefFrame(
        request: BridgeRequest,
        methodName: String,
        call: suspend (HandleRef) -> ImageFrame,
    ): BridgeResponse {
        val fields = request.decodeObject()
        val ref = request.requiredRef(fields, "source")
        return ok(request, framePayload(call(ref)))
    }

        /** 命中 → `{x,y,r,g,b,a}`；未命中 → 裸 `null`（答案，不是异常）。 */
    private fun colorPayload(m: ColorHit?): String =
        if (m == null) {
            "null"
        } else {
            DomainJson.encode(
                mapOf(
                    "x" to m.x.toLong(),
                    "y" to m.y.toLong(),
                    "r" to m.r.toLong(),
                    "g" to m.g.toLong(),
                    "b" to m.b.toLong(),
                    "a" to m.a.toLong(),
                ),
            )
        }

/** 命中 → `{x,y,width,height,confidence}`；未命中 → 裸 `null`（答案，不是异常）。 */
    private fun matchPayload(m: ImageMatch?): String =
        if (m == null) {
            "null"
        } else {
            DomainJson.encode(
                mapOf(
                    "x" to m.x.toLong(),
                    "y" to m.y.toLong(),
                    "width" to m.width.toLong(),
                    "height" to m.height.toLong(),
                    "confidence" to m.confidence,
                ),
            )
        }

    /** 特征命中 → `{x,y,confidence}`（模板中心，无尺寸概念）；未命中 → 裸 `null`。 */
    private fun featurePayload(h: FeatureHit?): String =
        if (h == null) {
            "null"
        } else {
            DomainJson.encode(
                mapOf(
                    "x" to h.x.toLong(),
                    "y" to h.y.toLong(),
                    "confidence" to h.confidence,
                ),
            )
        }
}
