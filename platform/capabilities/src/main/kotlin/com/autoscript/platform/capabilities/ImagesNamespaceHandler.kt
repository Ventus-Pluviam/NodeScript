package com.autoscript.platform.capabilities

import com.autoscript.domain.automation.ColorHit
import com.autoscript.domain.automation.ImageAnalyzer
import com.autoscript.domain.automation.ImageMatch
import com.autoscript.domain.bridge.HandleRef
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

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
 * **四方法 + release**（照抄 `ImageAnalyzer`）：`decode`/`matchTemplate`/`findImage`/`release`
 * + P1 第一个算子 `findColor`。阈值键**统一叫 `threshold`**（[matchTemplate] 与
 * [findImage] 同一个 opencv 概念，facade 曾一个发 `tolerance` 一个发 `threshold`，
 * 两侧 mock 各自自洽所以漂移没被抓到）；域 `[0,1]`，越界 → `ERR_INVALID_PARAM` 且
 * **一次 SPI 调用都不发**。
 *
 * **帧句柄**：`decode` 发号（handler 自管：单调 refId + generation 恒 1，与
 * [ScreenshotSource] 同一套纪律），回包带 `width/height` 真值（脚本要拿它做坐标换算）；
 * **匹配方法不再要求回传尺寸**（那是对实现报它自己已知的值）。[release] 与
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
 * （`toGrayscale`/`crop`/`pixel` 这些 native 面的操作没开桥面，不猜）。
 */
class ImagesNamespaceHandler(
    private val analyzer: ImageAnalyzer,
) {
    /** decode 发号（单调 refId，generation 恒 1 —— 一个文件一个帧，不复用不缓存）。 */
    private val ids = AtomicLong(1)

    /** 已发放的帧句柄集合（release/匹配的在场判据；§7.4「关掉的帧」与「没见过的帧」可分辨；只经 [guard] 读写）。 */
    private val live = HashMap<Long, HandleRef>()

    /** ref → 帧宽高（decode 时登记；匹配只凭 ref 调 SPI，不回显尺寸）。 */
    private val sizes = HashMap<Long, Pair<Int, Int>>()

    /** 帧表（[live]/[sizes]）的串行闸：handle 是 suspend，多请求可重入。 */
    private val guard = Mutex()

    suspend fun handle(request: BridgeRequestLite): ResponseLite = when (request.method) {
        "decode" -> decode(request)
        "matchTemplate" -> matchTemplate(request)
        "findImage" -> findImage(request)
        "findColor" -> findColor(request)
        "release" -> release(request)
        else -> ResponseLite.err(
            request.id,
            ErrorCode.ERR_NOT_IMPLEMENTED,
            "未知 images 方法: ${request.method}",
        )
    }

    private suspend fun decode(request: BridgeRequestLite): ResponseLite {
        val fields = try {
            request.decodeObject()
        } catch (e: IllegalArgumentException) {
            return ResponseLite.err(request.id, ErrorCode.ERR_INVALID_PARAM, e.message)
        }
        val path = try {
            request.requiredStr(fields, "path")
        } catch (e: IllegalArgumentException) {
            return ResponseLite.err(request.id, ErrorCode.ERR_INVALID_PARAM, e.message)
        }
        if (path.isBlank()) {
            return ResponseLite.err(request.id, ErrorCode.ERR_INVALID_PARAM, "images decode 的 path 不得为空白")
        }
        return try {
            val frame = analyzer.decode(path)
            val ref = guard.withLock {
                val r = HandleRef(ids.getAndIncrement(), 1L)
                live[r.refId] = r
                sizes[r.refId] = frame.width to frame.height
                r
            }
            ResponseLite.Ok(
                request.id,
                A11yBridgeJson.encode(
                    mapOf(
                        "ref" to mapOf("refId" to ref.refId, "generation" to ref.generation),
                        "width" to frame.width.toLong(),
                        "height" to frame.height.toLong(),
                    ),
                ),
            )
        } catch (e: IllegalArgumentException) {
            ResponseLite.err(request.id, ErrorCode.ERR_INVALID_PARAM, e.message)
        } catch (e: AutojsException) {
            ResponseLite.err(request.id, e.error, e.message)
        }
    }

    private suspend fun matchTemplate(request: BridgeRequestLite): ResponseLite =
        match(request, "matchTemplate")

    private suspend fun findImage(request: BridgeRequestLite): ResponseLite =
        match(request, "findImage")

    private suspend fun match(request: BridgeRequestLite, method: String): ResponseLite {
        val fields = try {
            request.decodeObject()
        } catch (e: IllegalArgumentException) {
            return ResponseLite.err(request.id, ErrorCode.ERR_INVALID_PARAM, e.message)
        }
        val refs: Pair<HandleRef, HandleRef>
        val threshold: Double
        try {
            refs = request.requiredRef(fields, "haystack") to request.requiredRef(fields, "needle")
            threshold = request.requiredDouble(fields, "threshold")
        } catch (e: IllegalArgumentException) {
            return ResponseLite.err(request.id, ErrorCode.ERR_INVALID_PARAM, e.message)
        }
        // 域 [0,1]：域外阈值在任何实现上都命中不了/恒命中，先拒（不发 SPI 调用）。
        if (threshold < 0.0 || threshold > 1.0) {
            return ResponseLite.err(
                request.id,
                ErrorCode.ERR_INVALID_PARAM,
                "images $method 的 threshold 必须在 [0,1]，实际 $threshold",
            )
        }
        val (haystack, needle) = refs
        if (guard.withLock { !live.containsKey(haystack.refId) || !live.containsKey(needle.refId) }) {
            return ResponseLite.err(
                request.id,
                ErrorCode.ERR_STALE_HANDLE,
                "images $method 的帧句柄已释放（haystack=${haystack.refId}, needle=${needle.refId}）",
            )
        }
        val hit = try {
            if (method == "matchTemplate") {
                analyzer.matchTemplate(haystack, needle, threshold)
            } else {
                analyzer.findImage(haystack, needle, threshold)
            }
        } catch (e: AutojsException) {
            return ResponseLite.err(request.id, e.error, e.message)
        }
        return ResponseLite.Ok(request.id, matchPayload(hit))
    }

    private suspend fun findColor(request: BridgeRequestLite): ResponseLite {
        val fields = try {
            request.decodeObject()
        } catch (e: IllegalArgumentException) {
            return ResponseLite.err(request.id, ErrorCode.ERR_INVALID_PARAM, e.message)
        }
        val ref: HandleRef
        val color: List<Int>
        val tolerance: Int
        val region: List<Int>?
        try {
            ref = request.requiredRef(fields, "haystack")
            color = request.requiredIntList(fields, "color")
            tolerance = request.requiredDouble(fields, "tolerance").toInt()
            // region 可选：缺键/JSON null = 全帧；给了就必须四元组（不是就参数错）。
            region = request.optIntList(fields, "region")
        } catch (e: IllegalArgumentException) {
            return ResponseLite.err(request.id, ErrorCode.ERR_INVALID_PARAM, e.message)
        }
        // 域校验（与 :domain ImageAnalyzer.findColor KDoc 逐条对齐）：
        //   color 恒四分量 [r,g,b,a]、各 [0,255]；tolerance [0,255]；region 四元组。
        // 全部先拒，**一次 SPI 调用都不发**（与阈值域同一条纪律）。
        if (color.size != 4 || color.any { it < 0 || it > 255 }) {
            return ResponseLite.err(
                request.id,
                ErrorCode.ERR_INVALID_PARAM,
                "images findColor 的 color 必须 r,g,b,a 四分量且各在 [0,255]，实际 $color",
            )
        }
        if (tolerance < 0 || tolerance > 255) {
            return ResponseLite.err(
                request.id,
                ErrorCode.ERR_INVALID_PARAM,
                "images findColor 的 tolerance 必须在 [0,255]，实际 $tolerance",
            )
        }
        region?.let { box ->
            if (box.size != 4) {
                return ResponseLite.err(
                    request.id,
                    ErrorCode.ERR_INVALID_PARAM,
                    "images findColor 的 region 必须 x,y,w,h 四元组，实际 $box",
                )
            }
        }
        if (guard.withLock { !live.containsKey(ref.refId) }) {
            return ResponseLite.err(
                request.id,
                ErrorCode.ERR_STALE_HANDLE,
                "images findColor 的帧句柄已释放（haystack=${ref.refId}）",
            )
        }
        val hit = try {
            analyzer.findColor(ref, color, tolerance, region)
        } catch (e: AutojsException) {
            return ResponseLite.err(request.id, e.error, e.message)
        }
        return ResponseLite.Ok(request.id, colorPayload(hit))
    }

    private suspend fun release(request: BridgeRequestLite): ResponseLite {
        val fields = try {
            request.decodeObject()
        } catch (e: IllegalArgumentException) {
            return ResponseLite.err(request.id, ErrorCode.ERR_INVALID_PARAM, e.message)
        }
        val ref = try {
            request.requiredRef(fields, "ref")
        } catch (e: IllegalArgumentException) {
            return ResponseLite.err(request.id, ErrorCode.ERR_INVALID_PARAM, e.message)
        }
        // 未知/跨代 → ERR_STALE_HANDLE（与 FrameSource.recycle 同口径）："已释放"与
        // "从未存在"必须能分辨。已知帧才放，sizes 同步清。
        val known = guard.withLock {
            if (live[ref.refId]?.generation != ref.generation) {
                false
            } else {
                sizes.remove(ref.refId)
                live.remove(ref.refId)
                true
            }
        }
        if (!known) {
            return ResponseLite.err(
                request.id,
                ErrorCode.ERR_STALE_HANDLE,
                "未知帧句柄 ${ref.refId} gen=${ref.generation}",
            )
        }
        return try {
            analyzer.release(ref)
            ResponseLite.Ok(request.id, "true")
        } catch (e: AutojsException) {
            ResponseLite.err(request.id, e.error, e.message)
        }
    }

        /** 命中 → `{x,y,r,g,b,a}`；未命中 → 裸 `null`（答案，不是异常）。 */
    private fun colorPayload(m: ColorHit?): String =
        if (m == null) {
            "null"
        } else {
            A11yBridgeJson.encode(
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
            A11yBridgeJson.encode(
                mapOf(
                    "x" to m.x.toLong(),
                    "y" to m.y.toLong(),
                    "width" to m.width.toLong(),
                    "height" to m.height.toLong(),
                    "confidence" to m.confidence,
                ),
            )
        }
}
