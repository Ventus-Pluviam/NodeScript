package com.autoscript.platform.capabilities

import com.autoscript.domain.automation.GestureInput
import com.autoscript.domain.automation.GesturePoint
import com.autoscript.domain.automation.GestureStroke
import com.autoscript.domain.automation.ScrollDirection
import com.autoscript.domain.automation.UiBounds
import com.autoscript.domain.automation.UiSelectorDsl
import com.autoscript.domain.bridge.HandleRef
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode

/**
 * `a11y` namespace 桥处理器（docs §9.1 / §12.3）：JS `a11y.*` 面的 Kotlin 对偶。
 *
 * 归属：住 `:platform:capabilities`（直接驱动 UiNodeTreeReader/UiActionExecutor；
 * `:app` 装配层薄转接挂 BridgeRouter）。载荷用本模块内 [A11yBridgeJson]
 *（:bridge:java 的 TinyJson 是 internal，跨模块不可见；见 runtime 的 EngineBridgeJson 同例）。
 *
 * 方法表（与 `bridge/js` a11y.ts 一一对应）：
 * - `findOne`：payload `{conditions:{text?,desc?,id?,className?,packageName?,clickable?},
 *   timeout?,interval?}` → 首个匹配 `Ok {ref:{refId,generation},...attrs}`；
 *   无匹配 → Err ERR_NOT_FOUND（JS findOne 抛 NotFoundError，findOneOrNull 收 null——
 *   JS 侧按"Err NOT_FOUND → null"折叠，见 facade 注释）；
 * - `findAll`：payload `{conditions,max?}` → Ok `[{ref,...},...]`（max 截断，缺省全量）；
 * - `waitFor`：payload `{selector:{...条件},timeout?,interval?}` → 同 findOne 语义
 *   （轮询是宿主责任：Android 侧监听事件流；内存树是单次快照——timeout/interval
 *   透传回显，不伪造等待）；
 * - `click/longClick/scroll/copy/paste/setText/bounds/text/desc/children/parent/dispose`：
 *   payload `{ref:{refId,generation},...}` → 句柄动作；跨代/已释放 →
 *   Err ERR_STALE_HANDLE；非法载荷 → Err ERR_INVALID_PARAM；未知方法 →
 *   Err ERR_NOT_IMPLEMENTED；
 * - `events`：payload `{sinceSeq?,batch?}` → Ok `{first,last,events:[{seq,type,
 *   node:{refId,generation}|null,payload}]}`（节流拉取式事件流，seq 游标；
 *   batch 缺省 32，必须 > 0，否则 ERR_INVALID_PARAM）；
 * - `gesture`：payload `{strokes:[{points:[{x,y}],startDelayMillis?,durationMillis?}]}`
 *   → Ok `"true"/"false"`（关门 canPerformGestures=false → false，调用方走能力中心引导；
 *   非法手势 → ERR_INVALID_PARAM，绝不发往系统服务）；
 * - `canPerformGestures`：无参 → Ok `"true"/"false"`。
 */
class A11yNamespaceHandler(
    private val tree: InMemoryUiTree,
    private val input: InMemoryInputProvider = InMemoryInputProvider(),
) {
    data class Request(val id: Long, val method: String, val payload: String?)
    sealed interface Response {
        data class Ok(val id: Long, val payload: String?) : Response
        data class Err(val id: Long, val code: String, val detail: String?) : Response
    }

    suspend fun handle(request: Request): Response = when (request.method) {
        "findOne" -> findOne(request, single = true)
        "findOneOrNull" -> findOne(request, single = true)
        "findAll" -> findAll(request)
        "waitFor" -> findOne(request, single = true)
        "click" -> boolAction(request) { ref -> tree.click(ref) }
        "longClick" -> boolAction(request) { ref -> tree.longClick(ref) }
        "scroll" -> scroll(request)
        "copy" -> boolAction(request) { ref -> tree.copy(ref) }
        "paste" -> boolAction(request) { ref -> tree.paste(ref) }
        "setText" -> setText(request)
        "bounds" -> bounds(request)
        "text" -> attr(request, "text")
        "desc" -> attr(request, "desc")
        "children" -> children(request)
        "parent" -> parent(request)
        "dispose" -> dispose(request)
        "events" -> events(request)
        "gesture" -> gesture(request)
        "canPerformGestures" -> ok(request.id, if (input.canPerformGestures) "true" else "false")
        else -> err(request.id, ErrorCode.ERR_NOT_IMPLEMENTED, "未知 a11y 方法: ${request.method}")
    }

    // ── 查找 ─────────────────────────────────────────────────────────

    private suspend fun findOne(request: Request, single: Boolean): Response {
        val o = try {
            decodePayload(request.payload)
        } catch (e: IllegalArgumentException) {
            return err(request.id, ErrorCode.ERR_INVALID_PARAM, e.message)
        }
        val selector = try {
            selectorOf(o["conditions"])
        } catch (e: IllegalArgumentException) {
            return err(request.id, ErrorCode.ERR_INVALID_PARAM, e.message)
        }
        val matched = try {
            tree.findBySelector(selector)
        } catch (e: AutojsException) {
            return err(request.id, e.error, e.message)
        }
        if (matched.isEmpty()) {
            return err(request.id, ErrorCode.ERR_NOT_FOUND, "选择器无匹配")
        }
        if (!single) {
            return ok(request.id, A11yBridgeJson.encode(matched.map { nodePayload(it.handle, null) }))
        }
        val first = matched.first()
        return ok(request.id, A11yBridgeJson.encode(nodePayload(first.handle, null)))
    }

    private suspend fun findAll(request: Request): Response {
        val o = try {
            decodePayload(request.payload)
        } catch (e: IllegalArgumentException) {
            return err(request.id, ErrorCode.ERR_INVALID_PARAM, e.message)
        }
        val selector = try {
            selectorOf(o["conditions"])
        } catch (e: IllegalArgumentException) {
            return err(request.id, ErrorCode.ERR_INVALID_PARAM, e.message)
        }
        val max = try {
            optLong(o, "max")?.toInt() ?: Int.MAX_VALUE
        } catch (e: IllegalArgumentException) {
            return err(request.id, ErrorCode.ERR_INVALID_PARAM, e.message)
        }
        if (max < 0) return err(request.id, ErrorCode.ERR_INVALID_PARAM, "max 不得为负")
        val matched = try {
            tree.findBySelector(selector)
        } catch (e: AutojsException) {
            return err(request.id, e.error, e.message)
        }
        return ok(request.id, A11yBridgeJson.encode(matched.take(max).map { nodePayload(it.handle, null) }))
    }

    // ── 动作 ─────────────────────────────────────────────────────────

    private suspend fun boolAction(request: Request, run: suspend (HandleRef) -> Boolean): Response {
        val ref = try {
            requiredRef(decodePayload(request.payload))
        } catch (e: IllegalArgumentException) {
            return err(request.id, ErrorCode.ERR_INVALID_PARAM, e.message)
        }
        return try {
            ok(request.id, if (run(ref)) "true" else "false")
        } catch (e: AutojsException) {
            err(request.id, e.error, e.message)
        }
    }

    private suspend fun setText(request: Request): Response {
        val o = try {
            decodePayload(request.payload)
        } catch (e: IllegalArgumentException) {
            return err(request.id, ErrorCode.ERR_INVALID_PARAM, e.message)
        }
        val ref: HandleRef
        val text: String
        try {
            ref = requiredRef(o)
            text = requiredStr(o, "text")
        } catch (e: IllegalArgumentException) {
            return err(request.id, ErrorCode.ERR_INVALID_PARAM, e.message)
        }
        return try {
            ok(request.id, if (tree.setText(ref, text)) "true" else "false")
        } catch (e: AutojsException) {
            err(request.id, e.error, e.message)
        }
    }

    /**
     * 滚动：payload `{ref,direction?}`（direction 缺省 FORWARD；非法方向名 →
     * ERR_INVALID_PARAM）。不可滚动容器回 `"false"`（不抛错，与 click 同口径）。
     */
    private suspend fun scroll(request: Request): Response {
        val o = try {
            decodePayload(request.payload)
        } catch (e: IllegalArgumentException) {
            return err(request.id, ErrorCode.ERR_INVALID_PARAM, e.message)
        }
        val ref: HandleRef
        val direction: ScrollDirection
        try {
            ref = requiredRef(o)
            direction = optDirection(o, "direction") ?: ScrollDirection.FORWARD
        } catch (e: IllegalArgumentException) {
            return err(request.id, ErrorCode.ERR_INVALID_PARAM, e.message)
        }
        return try {
            ok(request.id, if (tree.scroll(ref, direction)) "true" else "false")
        } catch (e: AutojsException) {
            err(request.id, e.error, e.message)
        }
    }

    private suspend fun bounds(request: Request): Response {
        val ref = try {
            requiredRef(decodePayload(request.payload))
        } catch (e: IllegalArgumentException) {
            return err(request.id, ErrorCode.ERR_INVALID_PARAM, e.message)
        }
        val b: UiBounds? = try {
            tree.bounds(ref)
        } catch (e: AutojsException) {
            return err(request.id, e.error, e.message)
        }
        if (b == null) return ok(request.id, null)
        return ok(
            request.id,
            A11yBridgeJson.encode(mapOf("left" to b.left.toLong(), "top" to b.top.toLong(), "right" to b.right.toLong(), "bottom" to b.bottom.toLong())),
        )
    }

    private suspend fun attr(request: Request, name: String): Response {
        val ref = try {
            requiredRef(decodePayload(request.payload))
        } catch (e: IllegalArgumentException) {
            return err(request.id, ErrorCode.ERR_INVALID_PARAM, e.message)
        }
        val v = try {
            tree.attribute(ref, name)
        } catch (e: AutojsException) {
            return err(request.id, e.error, e.message)
        }
        return ok(request.id, if (v == null) null else A11yBridgeJson.encode(v))
    }

    private suspend fun children(request: Request): Response {
        val ref = try {
            requiredRef(decodePayload(request.payload))
        } catch (e: IllegalArgumentException) {
            return err(request.id, ErrorCode.ERR_INVALID_PARAM, e.message)
        }
        val kids = try {
            tree.children(ref)
        } catch (e: AutojsException) {
            return err(request.id, e.error, e.message)
        }
        return ok(request.id, A11yBridgeJson.encode(kids.map { nodePayload(it.handle, null) }))
    }

    private suspend fun parent(request: Request): Response {
        val ref = try {
            requiredRef(decodePayload(request.payload))
        } catch (e: IllegalArgumentException) {
            return err(request.id, ErrorCode.ERR_INVALID_PARAM, e.message)
        }
        val p = try {
            tree.parent(ref)
        } catch (e: AutojsException) {
            return err(request.id, e.error, e.message)
        }
        return ok(request.id, if (p == null) null else A11yBridgeJson.encode(nodePayload(p.handle, null)))
    }

    private suspend fun dispose(request: Request): Response {
        val ref = try {
            requiredRef(decodePayload(request.payload))
        } catch (e: IllegalArgumentException) {
            return err(request.id, ErrorCode.ERR_INVALID_PARAM, e.message)
        }
        return try {
            tree.dispose(ref)
            ok(request.id, "true")
        } catch (e: AutojsException) {
            err(request.id, e.error, e.message)
        }
    }

    /**
     * 事件流拉取（§9.1 节流拉取式，seq 游标）：payload `{sinceSeq?,batch?}`。
     * 空增量回 `{first:sinceSeq,last:sinceSeq,events:[]}`（调用方以前进游标为准，
     * 不以空数组为终结——事件是开放流）。
     */
    private suspend fun events(request: Request): Response {
        val o = try {
            if (request.payload == null) emptyMap() else decodePayload(request.payload)
        } catch (e: IllegalArgumentException) {
            return err(request.id, ErrorCode.ERR_INVALID_PARAM, e.message)
        }
        val sinceSeq: Long
        val batch: Int
        try {
            sinceSeq = optLong(o, "sinceSeq") ?: 0L
            batch = (optLong(o, "batch") ?: 32L).toInt()
            require(batch > 0) { "batch 必须 > 0" }
        } catch (e: IllegalArgumentException) {
            return err(request.id, ErrorCode.ERR_INVALID_PARAM, e.message)
        }
        val got = tree.nextEvents(sinceSeq, batch)
        return ok(
            request.id,
            A11yBridgeJson.encode(
                mapOf(
                    "first" to got.firstSeq,
                    "last" to got.lastSeq,
                    "events" to got.events.map {
                        mapOf(
                            "seq" to it.seq,
                            "type" to it.type,
                            "node" to (it.nodeHandle?.let { h ->
                                mapOf("refId" to h.refId, "generation" to h.generation)
                            }),
                            "payload" to it.payload,
                        )
                    },
                ),
            ),
        )
    }

    /**
     * 手势派发：payload `{strokes:[{points:[{x,y}],startDelayMillis?,durationMillis?}]}`。
     * 构造器校验非法（空笔画/负坐标/非正 duration）→ ERR_INVALID_PARAM；
     * 关门（canPerformGestures=false）→ `"false"`（不抛错，走能力中心引导）。
     */
    private suspend fun gesture(request: Request): Response {
        val o = try {
            decodePayload(request.payload)
        } catch (e: IllegalArgumentException) {
            return err(request.id, ErrorCode.ERR_INVALID_PARAM, e.message)
        }
        val gesture = try {
            gestureOf(o["strokes"])
        } catch (e: IllegalArgumentException) {
            return err(request.id, ErrorCode.ERR_INVALID_PARAM, e.message)
        }
        return ok(request.id, if (input.dispatchGesture(gesture)) "true" else "false")
    }

    private fun gestureOf(v: A11yBridgeJson.Value?): GestureInput {
        if (v !is A11yBridgeJson.Value.Arr) throw IllegalArgumentException("strokes 必须是数组")
        return GestureInput(
            v.items.map { s ->
                val o = (s as? A11yBridgeJson.Value.Obj)?.fields
                    ?: throw IllegalArgumentException("stroke 必须是对象")
                val points = (o["points"] as? A11yBridgeJson.Value.Arr)
                    ?: throw IllegalArgumentException("stroke.points 必须是数组")
                GestureStroke(
                    points = points.items.map { p ->
                        val f = (p as? A11yBridgeJson.Value.Obj)?.fields
                            ?: throw IllegalArgumentException("point 必须是 {x,y} 对象")
                        val x = (f["x"] as? A11yBridgeJson.Value.N)?.raw?.toIntOrNull()
                            ?: throw IllegalArgumentException("point.x 必须是非负整数")
                        val y = (f["y"] as? A11yBridgeJson.Value.N)?.raw?.toIntOrNull()
                            ?: throw IllegalArgumentException("point.y 必须是非负整数")
                        GesturePoint(x, y)
                    },
                    startDelayMillis = optLong(o, "startDelayMillis") ?: 0L,
                    durationMillis = optLong(o, "durationMillis") ?: 100L,
                )
            },
        )
    }

    // ── 载荷 ─────────────────────────────────────────────────────────

    private fun nodePayload(ref: HandleRef, extra: Map<String, Any?>?): Map<String, Any?> =
        mapOf("ref" to mapOf("refId" to ref.refId, "generation" to ref.generation))

    private fun decodePayload(payload: String?): Map<String, A11yBridgeJson.Value> {
        if (payload == null) throw IllegalArgumentException("缺 payload")
        return A11yBridgeJson.decodeObject(payload)
    }

    private fun selectorOf(v: A11yBridgeJson.Value?): UiSelectorDsl {
        if (v == null || v is A11yBridgeJson.Value.Null) return UiSelectorDsl.builder()
        if (v !is A11yBridgeJson.Value.Obj) throw IllegalArgumentException("conditions 必须是对象")
        val o = v.fields
        // 白名单字段：未知键如实拒绝（防拼写错误静默变全量匹配）。
        for (k in o.keys) {
            if (k !in SELECTOR_KEYS) throw IllegalArgumentException("未知选择器条件 $k")
        }
        return UiSelectorDsl.builder().copyWith(
            text = optStr(o, "text"),
            desc = optStr(o, "desc"),
            className = optStr(o, "className"),
            packageName = optStr(o, "packageName"),
            id = optStr(o, "id"),
            clickable = optBool(o, "clickable"),
        )
    }

    private fun requiredRef(o: Map<String, A11yBridgeJson.Value>): HandleRef {
        val v = o["ref"] ?: throw IllegalArgumentException("缺 ref 字段")
        if (v !is A11yBridgeJson.Value.Obj) throw IllegalArgumentException("ref 必须是对象")
        val refId = (v.fields["refId"] as? A11yBridgeJson.Value.N)?.raw?.toLongOrNull()
            ?: throw IllegalArgumentException("缺数字 ref.refId")
        val gen = (v.fields["generation"] as? A11yBridgeJson.Value.N)?.raw?.toLongOrNull()
            ?: throw IllegalArgumentException("缺数字 ref.generation")
        return HandleRef(refId, gen)
    }

    private fun requiredStr(o: Map<String, A11yBridgeJson.Value>, key: String): String =
        (o[key] as? A11yBridgeJson.Value.S)?.v ?: throw IllegalArgumentException("缺字符串字段 $key")

    private fun optStr(o: Map<String, A11yBridgeJson.Value>, key: String): String? {
        val v = o[key] ?: return null
        if (v is A11yBridgeJson.Value.Null) return null
        return (v as? A11yBridgeJson.Value.S)?.v ?: throw IllegalArgumentException("字段 $key 必须是字符串")
    }

    private fun optBool(o: Map<String, A11yBridgeJson.Value>, key: String): Boolean? {
        val v = o[key] ?: return null
        if (v is A11yBridgeJson.Value.Null) return null
        return (v as? A11yBridgeJson.Value.B)?.v ?: throw IllegalArgumentException("字段 $key 必须是布尔")
    }

    private fun optLong(o: Map<String, A11yBridgeJson.Value>, key: String): Long? {
        val v = o[key] ?: return null
        if (v is A11yBridgeJson.Value.Null) return null
        return (v as? A11yBridgeJson.Value.N)?.raw?.toLongOrNull()
            ?: throw IllegalArgumentException("字段 $key 必须是数字")
    }

    private fun optDirection(o: Map<String, A11yBridgeJson.Value>, key: String): ScrollDirection? {
        val v = o[key] ?: return null
        if (v is A11yBridgeJson.Value.Null) return null
        val name = (v as? A11yBridgeJson.Value.S)?.v
            ?: throw IllegalArgumentException("字段 $key 必须是方向名字符串")
        return try {
            ScrollDirection.valueOf(name.uppercase())
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("未知滚动方向 $name（FORWARD/BACKWARD/UP/DOWN/LEFT/RIGHT）")
        }
    }

    private fun ok(id: Long, payload: String?): Response = Response.Ok(id, payload)

    private fun err(id: Long, code: ErrorCode, detail: String?): Response =
        Response.Err(id, code.code, detail)

    companion object {
        val SELECTOR_KEYS: Set<String> = setOf("text", "desc", "id", "className", "packageName", "clickable")
    }
}
