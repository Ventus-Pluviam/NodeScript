package com.autoscript.appservice.runtime

import com.autoscript.domain.core.ErrorCode

/**
 * `engines` namespace 桥处理器（docs §8 / §12.3）：JS `engines.*` 面的 Kotlin 对偶。
 *
 * 归属说明：本类住在 `:app-service:runtime`（不是 `:bridge:java`），因为它直接驱动
 * [RuntimeController]/[EnginePool]；`:app` 装配层把它适配到 `BridgeRouter` 的
 * `RequestHandler`（两接口形状相同，薄转接，无逻辑）。
 * 载荷编解码用本模块内 [EngineBridgeJson]（见该文件注释：不碰 `:bridge:java` 的
 * internal TinyJson，架构门禁见 ArchitectureTest）。
 *
 * 方法表（与 `bridge/js` engines.ts 一一对应）：
 * - `exec`：payload `{projectId,scriptPath,args?,runNonce?,timeoutMillis?,waitTimeoutMillis?}` →
 *   [RuntimeController.start]；Started → Ok `{runId,handle:{refId,generation}}`，
 *   QueueTimeout → Err ERR_TIMEOUT，StartFailed → Err ERR_ENGINE_CRASHED；
 *   排队上限 = payload `waitTimeoutMillis` 优先，否则桥侧 [Request.ttlMillis]
 *   （§7.4 每次跨进程操作必有 TTL）——没有上限时满池即无限等，只能靠调用方取消兜底，
 *   那条路径无法诚实回 ERR_TIMEOUT，故必须把 TTL 递进池；
 * - `stop`：payload `{runId}` → StoppedClean → Ok `true`；
 *   StoppedTimeout → Err ERR_TIMEOUT（软停未干净完成，已 kill 兜底，如实报错不伪造成功）；
 *   AlreadyGone → Err ERR_NOT_FOUND（未知 runId 不静默吞掉）；
 * - `poolStats`：无参 → Ok `{capacity,free,busy}`；
 * - `channel`：payload `{name}` → 创建或复用命名通道 → Ok `{name,channelId}`；
 * - `channelEmit`：payload `{channelId,event,payload?}` → 记入通道事件缓冲 → Ok null；
 * - `channelDrain`：payload `{channelId,sinceSeq?,max?}` → 游标拉取（供 :app 层经
 *   EventBus/TSF 转发给订阅端，见领域注释：退出/事件推送走桥 EventBus）；
 * - `channelClose`：payload `{channelId}` → 关闭并丢弃缓冲 → Ok `true`；
 * - 未知方法 → Err ERR_NOT_IMPLEMENTED；非法载荷 → Err ERR_INVALID_PARAM。
 */
class EnginesNamespaceHandler(
    private val controller: RuntimeController,
    private val channelCapacity: Int = DEFAULT_CHANNEL_CAPACITY,
) {
    data class Request(
        val id: Long,
        val method: String,
        val payload: String?,
        /** 请求侧 TTL（§7.4）：`exec` 据此推导排队上限；null = 不设上限（无限等）。 */
        val ttlMillis: Long? = null,
    )
    sealed interface Response {
        data class Ok(val id: Long, val payload: String?) : Response
        data class Err(val id: Long, val code: String, val detail: String?) : Response
    }

    suspend fun handle(request: Request): Response {
        return when (request.method) {
            "exec" -> exec(request)
            "stop" -> stop(request)
            "poolStats" -> ok(request.id, EngineBridgeJson.encode(poolStatsPayload()))
            "channel" -> channel(request)
            "channelEmit" -> channelEmit(request)
            "channelDrain" -> channelDrain(request)
            "channelClose" -> channelClose(request)
            else -> err(request.id, ErrorCode.ERR_NOT_IMPLEMENTED, "未知 engines 方法: ${request.method}")
        }
    }

    // ── exec / stop / poolStats ──────────────────────────────────────────

    private suspend fun exec(request: Request): Response {
        val p = try {
            parseExec(request.payload)
        } catch (e: IllegalArgumentException) {
            return err(request.id, ErrorCode.ERR_INVALID_PARAM, e.message)
        }
        val bounded = p.copy(waitTimeoutMillis = p.waitTimeoutMillis ?: request.ttlMillis)
        return when (val outcome = controller.start(bounded)) {
            is RuntimeController.StartOutcome.Started -> ok(
                request.id,
                EngineBridgeJson.encode(
                    mapOf(
                        "runId" to outcome.runId,
                        // 会话句柄身份 = runId（单次 exec→stop 生命周期，无 re-acquire，
                        // 故 generation 恒 1；与 bridge HandleRegistry 的跨代语义不冲突——
                        // 本句柄从不复用，旧引用无"新资源"可误操作）。
                        "handle" to mapOf("refId" to outcome.runId, "generation" to 1L),
                    ),
                ),
            )
            RuntimeController.StartOutcome.QueueTimeout ->
                err(request.id, ErrorCode.ERR_TIMEOUT, "引擎池排队超时")
            is RuntimeController.StartOutcome.StartFailed ->
                err(request.id, ErrorCode.ERR_ENGINE_CRASHED, outcome.message)
        }
    }

    private suspend fun stop(request: Request): Response {
        val runId = try {
            requiredLong(decodePayload(request.payload), "runId")
        } catch (e: IllegalArgumentException) {
            return err(request.id, ErrorCode.ERR_INVALID_PARAM, e.message)
        }
        return when (val outcome = controller.stop(runId)) {
            RuntimeController.StopOutcome.StoppedClean -> ok(request.id, "true")
            is RuntimeController.StopOutcome.StoppedTimeout ->
                err(request.id, ErrorCode.ERR_TIMEOUT, "软停超时(partial=${outcome.partial})，已 kill 兜底")
            RuntimeController.StopOutcome.AlreadyGone ->
                err(request.id, ErrorCode.ERR_NOT_FOUND, "未知 runId: $runId")
        }
    }

    private fun poolStatsPayload(): Map<String, Any?> {
        val s = controller.stats()
        return mapOf("capacity" to s.capacity.toLong(), "free" to s.free.toLong(), "busy" to s.busy.toLong())
    }

    private fun parseExec(payload: String?): PoolAcquireRequest {
        val o = decodePayload(payload)
        val projectId = requiredStr(o, "projectId")
        val scriptPath = requiredStr(o, "scriptPath")
        if (projectId.isBlank()) throw IllegalArgumentException("projectId 不得为空")
        if (scriptPath.isBlank()) throw IllegalArgumentException("scriptPath 不得为空")
        return PoolAcquireRequest(
            projectId = projectId,
            scriptPath = scriptPath,
            args = optStrList(o, "args"),
            runNonce = optStr(o, "runNonce"),
            scriptTimeoutMillis = optLong(o, "timeoutMillis"),
            waitTimeoutMillis = optLong(o, "waitTimeoutMillis"),
        )
    }

    // ── 命名通道 ─────────────────────────────────────────────────────────
    //
    // 通道是脚本↔宿主 JSON 事件的命名缓冲（§8 RuntimeChannel）。订阅推送（host→script）
    // 走桥 EventBus/TSF（:app 层经 channelDrain 拉取后转发）；本层只做缓冲 + 游标，
    // 不做回调推送（与 ConsoleCollector/EventBus 的"节流拉取"语义一致）。

    private data class ChannelState(
        val channelId: Long,
        val name: String,
        val events: ArrayDeque<ChannelEvent> = ArrayDeque(),
        var nextSeq: Long = 1,
        var dropped: Long = 0,
        var closed: Boolean = false,
    )

    data class ChannelEvent(val seq: Long, val event: String, val payload: String?)

    private val channels = HashMap<Long, ChannelState>()
    private val byName = HashMap<String, Long>()
    private var nextChannelId = 1L
    private val channelGuard = Any()

    private fun channel(request: Request): Response {
        val o = try {
            decodePayload(request.payload)
        } catch (e: IllegalArgumentException) {
            return err(request.id, ErrorCode.ERR_INVALID_PARAM, e.message)
        }
        val name = try {
            requiredStr(o, "name")
        } catch (e: IllegalArgumentException) {
            return err(request.id, ErrorCode.ERR_INVALID_PARAM, e.message)
        }
        if (name.isBlank()) return err(request.id, ErrorCode.ERR_INVALID_PARAM, "通道名不得为空")
        val id = synchronized(channelGuard) {
            val existing = byName[name]
            if (existing != null && channels[existing]?.closed == false) {
                existing
            } else {
                val nid = nextChannelId++
                channels[nid] = ChannelState(channelId = nid, name = name)
                byName[name] = nid
                nid
            }
        }
        return ok(request.id, EngineBridgeJson.encode(mapOf("name" to name, "channelId" to id)))
    }

    private fun channelEmit(request: Request): Response {
        val o = try {
            decodePayload(request.payload)
        } catch (e: IllegalArgumentException) {
            return err(request.id, ErrorCode.ERR_INVALID_PARAM, e.message)
        }
        val channelId: Long
        val event: String
        val payload: String?
        try {
            channelId = requiredLong(o, "channelId")
            event = requiredStr(o, "event")
            payload = optStr(o, "payload")
        } catch (e: IllegalArgumentException) {
            return err(request.id, ErrorCode.ERR_INVALID_PARAM, e.message)
        }
        if (event.isBlank()) return err(request.id, ErrorCode.ERR_INVALID_PARAM, "事件名不得为空")
        synchronized(channelGuard) {
            val state = channels[channelId]
                ?: return err(request.id, ErrorCode.ERR_NOT_FOUND, "未知 channelId: $channelId")
            if (state.closed) return err(request.id, ErrorCode.ERR_NOT_FOUND, "通道已关闭: $channelId")
            state.events.addLast(ChannelEvent(seq = state.nextSeq++, event = event, payload = payload))
            while (state.events.size > channelCapacity) {
                state.events.removeFirst()
                state.dropped++
            }
        }
        return ok(request.id, null)
    }

    private fun channelDrain(request: Request): Response {
        val o = try {
            decodePayload(request.payload)
        } catch (e: IllegalArgumentException) {
            return err(request.id, ErrorCode.ERR_INVALID_PARAM, e.message)
        }
        val channelId: Long
        val sinceSeq: Long
        val max: Int
        try {
            channelId = requiredLong(o, "channelId")
            sinceSeq = optLong(o, "sinceSeq") ?: 0L
            max = (optLong(o, "max") ?: 128L).toInt()
            require(max > 0) { "max 必须 > 0" }
        } catch (e: IllegalArgumentException) {
            return err(request.id, ErrorCode.ERR_INVALID_PARAM, e.message)
        }
        val picked: List<ChannelEvent>
        var last = sinceSeq
        synchronized(channelGuard) {
            val state = channels[channelId]
                ?: return err(request.id, ErrorCode.ERR_NOT_FOUND, "未知 channelId: $channelId")
            picked = state.events.filter { it.seq > sinceSeq }.take(max)
            for (e in picked) last = e.seq
        }
        return ok(
            request.id,
            EngineBridgeJson.encode(
                mapOf(
                    "last" to last,
                    "events" to picked.map {
                        mapOf("seq" to it.seq, "event" to it.event, "payload" to it.payload)
                    },
                ),
            ),
        )
    }

    private fun channelClose(request: Request): Response {
        val channelId = try {
            requiredLong(decodePayload(request.payload), "channelId")
        } catch (e: IllegalArgumentException) {
            return err(request.id, ErrorCode.ERR_INVALID_PARAM, e.message)
        }
        synchronized(channelGuard) {
            val state = channels.remove(channelId)
                ?: return err(request.id, ErrorCode.ERR_NOT_FOUND, "未知 channelId: $channelId")
            state.closed = true
            byName.remove(state.name)
        }
        return ok(request.id, "true")
    }

    // ── 载荷读取 ─────────────────────────────────────────────────────────

    private fun decodePayload(payload: String?): Map<String, EngineBridgeJson.Value> {
        if (payload == null) throw IllegalArgumentException("缺 payload")
        return EngineBridgeJson.decodeObject(payload)
    }

    private fun requiredStr(o: Map<String, EngineBridgeJson.Value>, key: String): String =
        (o[key] as? EngineBridgeJson.Value.S)?.v ?: throw IllegalArgumentException("缺字符串字段 $key")

    private fun optStr(o: Map<String, EngineBridgeJson.Value>, key: String): String? {
        val v = o[key] ?: return null
        if (v is EngineBridgeJson.Value.Null) return null
        return (v as? EngineBridgeJson.Value.S)?.v ?: throw IllegalArgumentException("字段 $key 必须是字符串")
    }

    private fun requiredLong(o: Map<String, EngineBridgeJson.Value>, key: String): Long =
        (o[key] as? EngineBridgeJson.Value.N)?.raw?.toLongOrNull()
            ?: throw IllegalArgumentException("缺数字字段 $key")

    private fun optLong(o: Map<String, EngineBridgeJson.Value>, key: String): Long? {
        val v = o[key] ?: return null
        if (v is EngineBridgeJson.Value.Null) return null
        return (v as? EngineBridgeJson.Value.N)?.raw?.toLongOrNull()
            ?: throw IllegalArgumentException("字段 $key 必须是数字")
    }

    private fun optStrList(o: Map<String, EngineBridgeJson.Value>, key: String): List<String> {
        val v = o[key] ?: return emptyList()
        if (v is EngineBridgeJson.Value.Null) return emptyList()
        if (v !is EngineBridgeJson.Value.Arr) throw IllegalArgumentException("字段 $key 必须是数组")
        return v.items.map {
            (it as? EngineBridgeJson.Value.S)?.v ?: throw IllegalArgumentException("字段 $key 数组元素必须是字符串")
        }
    }

    private fun ok(id: Long, payload: String?): Response = Response.Ok(id, payload)

    private fun err(id: Long, code: ErrorCode, detail: String?): Response =
        Response.Err(id, code.code, detail)

    companion object {
        const val DEFAULT_CHANNEL_CAPACITY = 256
    }
}
