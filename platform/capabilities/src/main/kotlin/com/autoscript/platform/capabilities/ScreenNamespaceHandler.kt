package com.autoscript.platform.capabilities

import com.autoscript.domain.automation.FrameSource
import com.autoscript.domain.automation.ImageFrame
import com.autoscript.domain.automation.ScreenCaptureSession
import com.autoscript.domain.bridge.HandleRef
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode

/**
 * `screen` namespace 桥处理器（docs §9.2 / §8.8 / §12.3）：JS `screen.*` 面的 Kotlin 对偶。
 *
 * 归属：住 `:platform:capabilities`；构造只收 `:domain` 的 [FrameSource] SPI
 *（含 `recycle` 帧释放）。Android 真实现（a11y takeScreenshot / MediaProjection）
 * 只需实现该 SPI 即可替换内存帧源 —— 本类会话记账/载荷逻辑不变（见
 * `CapabilityNamespaces.screen` 仍以内存实现装配：真实现到位 = 换调用处那一行）。
 *
 * `:app` 装配层薄转接挂 BridgeRouter。载荷复用本模块内 [A11yBridgeJson]（同模块 internal 可见）。
 *
 * 方法表（与 `bridge/js` images.ts `screen` 对应）：
 * - `capture`：无参 → Ok `{ref:{refId,generation},width,height}`；
 *   锁屏/FLAG_SECURE/无窗口/节流 → 分类 Err（§8.8：分类错误而非黑图）；
 * - `recycle`：payload `{ref}` → Ok `true`（幂等；未知句柄 → ERR_STALE_HANDLE）；
 * - `startCapturer`：无参 → Ok `{session:{refId,generation}}`（MediaProjection 会话；
 *   open 时即做策略判定，失败直接 Err，不发空会话）；
 * - `nextFrame`：payload `{session}` → Ok `{ref,width,height}`；
 * - `closeSession`：payload `{session}` → Ok `true`（幂等；未知会话 → ERR_NOT_FOUND）；
 * - 未知方法 → ERR_NOT_IMPLEMENTED；非法载荷 → ERR_INVALID_PARAM。
 */
class ScreenNamespaceHandler(
    private val source: FrameSource,
) {
    data class Request(val id: Long, val method: String, val payload: String?)
    sealed interface Response {
        data class Ok(val id: Long, val payload: String?) : Response
        data class Err(val id: Long, val code: String, val detail: String?) : Response
    }

    private val guard = Any()
    private val sessions = HashMap<Long, ScreenCaptureSession>()
    private var nextSessionId = 1L

    suspend fun handle(request: Request): Response = when (request.method) {
        "capture" -> capture(request)
        "recycle" -> recycle(request)
        "startCapturer" -> startCapturer(request)
        "nextFrame" -> nextFrame(request)
        "closeSession" -> closeSession(request)
        else -> err(request.id, ErrorCode.ERR_NOT_IMPLEMENTED, "未知 screen 方法: ${request.method}")
    }

    private suspend fun capture(request: Request): Response {
        return try {
            ok(request.id, framePayload(source.capture()))
        } catch (e: AutojsException) {
            err(request.id, e.error, e.message)
        }
    }

    private suspend fun recycle(request: Request): Response {
        val ref = try {
            requiredRef(decodePayload(request.payload), "ref")
        } catch (e: IllegalArgumentException) {
            return err(request.id, ErrorCode.ERR_INVALID_PARAM, e.message)
        }
        return try {
            source.recycle(ref)
            ok(request.id, "true")
        } catch (e: AutojsException) {
            err(request.id, e.error, e.message)
        }
    }

    private suspend fun startCapturer(request: Request): Response {
        return try {
            val session = source.openSession()
            val id = synchronized(guard) {
                val nid = nextSessionId++
                sessions[nid] = session
                nid
            }
            ok(request.id, A11yBridgeJson.encode(mapOf("session" to mapOf("refId" to id, "generation" to 1L))))
        } catch (e: AutojsException) {
            err(request.id, e.error, e.message)
        }
    }

    private suspend fun nextFrame(request: Request): Response {
        val sessionId = try {
            requiredRef(decodePayload(request.payload), "session").refId
        } catch (e: IllegalArgumentException) {
            return err(request.id, ErrorCode.ERR_INVALID_PARAM, e.message)
        }
        val session = synchronized(guard) { sessions[sessionId] }
            ?: return err(request.id, ErrorCode.ERR_NOT_FOUND, "未知截图会话 $sessionId")
        return try {
            ok(request.id, framePayload(session.nextFrame()))
        } catch (e: AutojsException) {
            err(request.id, e.error, e.message)
        }
    }

    private suspend fun closeSession(request: Request): Response {
        val sessionId = try {
            requiredRef(decodePayload(request.payload), "session").refId
        } catch (e: IllegalArgumentException) {
            return err(request.id, ErrorCode.ERR_INVALID_PARAM, e.message)
        }
        val session = synchronized(guard) { sessions.remove(sessionId) }
            ?: return err(request.id, ErrorCode.ERR_NOT_FOUND, "未知截图会话 $sessionId")
        session.close()
        return ok(request.id, "true")
    }

    // ── 载荷 ─────────────────────────────────────────────────────────

    private fun framePayload(f: ImageFrame): String =
        A11yBridgeJson.encode(
            mapOf(
                "ref" to mapOf("refId" to f.handle.refId, "generation" to f.handle.generation),
                "width" to f.width.toLong(),
                "height" to f.height.toLong(),
            ),
        )

    private fun decodePayload(payload: String?): Map<String, A11yBridgeJson.Value> {
        if (payload == null) throw IllegalArgumentException("缺 payload")
        return A11yBridgeJson.decodeObject(payload)
    }

    private fun requiredRef(o: Map<String, A11yBridgeJson.Value>, key: String): HandleRef {
        val v = o[key] ?: throw IllegalArgumentException("缺 $key 字段")
        if (v !is A11yBridgeJson.Value.Obj) throw IllegalArgumentException("$key 必须是对象")
        val refId = (v.fields["refId"] as? A11yBridgeJson.Value.N)?.raw?.toLongOrNull()
            ?: throw IllegalArgumentException("缺数字 $key.refId")
        val gen = (v.fields["generation"] as? A11yBridgeJson.Value.N)?.raw?.toLongOrNull()
            ?: throw IllegalArgumentException("缺数字 $key.generation")
        return HandleRef(refId, gen)
    }

    private fun ok(id: Long, payload: String?): Response = Response.Ok(id, payload)

    private fun err(id: Long, code: ErrorCode, detail: String?): Response =
        Response.Err(id, code.code, detail)
}
