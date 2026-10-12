package com.autoscript.appservice.runtime
import com.autoscript.domain.scripts.ProjectId
import com.autoscript.domain.scripts.RelPath
import com.autoscript.domain.bridge.generated.WireMethods

import com.autoscript.domain.bridge.AuthenticatedRunContext
import kotlin.coroutines.coroutineContext
import com.autoscript.domain.bridge.BridgeRequest
import com.autoscript.domain.bridge.BridgeResponse
import com.autoscript.domain.bridge.RpcNamespaceHandler
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.json.DomainJson
import com.autoscript.domain.permission.BridgeCapability

/**
 * `engines` namespace 桥处理器（docs §8 / §12.3）：JS `engines.*` 面的 Kotlin 对偶。
 *
 * 归属说明：本类住在 `:app-service:runtime`（不是 `:bridge:java`），因为它直接驱动
 * [RuntimeController]/[EnginePool]；本类即 `NamespaceHandler`（承 [RpcNamespaceHandler]），
 * `:app` 装配层 `router.register("engines", it)` 直挂 `BridgeRouter`。
 * 载荷编解码用 `:domain` 的 [DomainJson]（仓内唯一 codec ——
 * 原 EngineBridgeJson 已删，不再有「各模块自带一份」的形状）。
 *
 * 方法表（与 `bridge/js` engines.ts 一一对应）：
 * - `exec`：payload `{projectId,scriptPath,args?,runNonce?,timeoutMillis?,waitTimeoutMillis?}` →
 *   [RuntimeController.start]；Started → Ok `{runId,handle:{refId,generation}}`，
 *   QueueTimeout → Err ERR_TIMEOUT，StartFailed → Err ERR_ENGINE_CRASHED；
 *   排队上限 = payload `waitTimeoutMillis` 优先，否则桥侧 [BridgeRequest.ttlMillis]
 *   （§7.4 每次跨进程操作必有 TTL）——没有上限时满池即无限等，只能靠调用方取消兜底，
 *   那条路径无法诚实回 ERR_TIMEOUT，故必须把 TTL 递进池；
 *   **执行时限必须显式声明**：桥这条路拿到句柄就返回，没人 await 终结，所以
 *   `timeoutMillis` 缺席不是"用缺省"而是**构造即拒**（[ErrorCode.ERR_INVALID_PARAM]）——
 *   否则这条 run 落回"没人收尾"那一类，只能等看门狗按心跳/CPU/RSS 判死，而心跳正常的
 *   长跑脚本永远判不到。声明了期限的走 [TimeoutEnforcer.WATCHDOG]：到点由看门狗落
 *   `KillCause.TIMEOUT`（§8.6「没人 await 的必须自带期限」）；
 * - `stop`：payload `{runId}` → StoppedClean → Ok `true`；
 *   StoppedTimeout → Err ERR_TIMEOUT（软停未干净完成，已 kill 兜底，如实报错不伪造成功）；
 *   AlreadyGone → Err ERR_NOT_FOUND（未知 runId 不静默吞掉）；
 * - `poolStats`：无参 → Ok `{capacity,free,busy}`；
 * - `status`：payload `{runId}` → 在途则 Ok 引擎状态名字符串（`"RUNNING"` 等，
 *   与 `:domain EngineStatus` 枚举名逐字一致，JS `EngineStatus` 字面量对齐）；
 *   不在途（已结算/从未存在）→ Err ERR_NOT_FOUND —— 与 `stop` 的 AlreadyGone 同一条
 *   诚实口径：结算后无状态可读，不得伪造一个 `"STOPPED"`（那会把"查不到"伪装成
 *   "正常结束"，`onExit` 的终态判断会因此错过 CRASHED）。JS `onExit` 的轮询地基；
 * - `heartbeat`：payload `{runId,seq}` → [RuntimeController.heartbeat]（§8.4 缺口②的
 *   宿主侧收单方；JS 侧定时打点）。先验认证连接的 engineRunId，缺身份/冒用别的 run →
 *   ERR_PERMISSION_DENIED；合法身份同/旧 seq 不刷时间戳 → Ok `false`（如实告知未被采纳，
 *   不是错误）；未知 runId（不在途/已结算/从未存在）→ 仍 Ok `false` **且不记账** ——
 *   "不知道这个 run"本身不是调用方错误，但也不得把它伪装成一次有效心跳，更不得让无主
 *   条目堆积顶出活 run 的账（[RuntimeController.heartbeat] 先验在途再落账本）；
 * - `channel`：payload `{name}` → 创建或复用**本执行名下**的命名通道 → Ok `{name,channelId}`
 *   （所有者 = 认证连接的 engineRunId；不同执行取同名通道各拿各的 id，见「命名通道」段注释）；
 * - `channelEmit`：payload `{channelId,event,payload?}` → 记入通道事件缓冲 → Ok null；
 *   通道不存在/已关闭/「不归本执行」 → ERR_NOT_FOUND；
 * - `channelDrain`：payload `{channelId,sinceSeq?,max?}` → 游标拉取（供 :app 层经
 *   EventBus/TSF 转发给订阅端，见领域注释：退出/事件推送走桥 EventBus）；同上按所有者校验；
 * - `channelClose`：payload `{channelId}` → 关闭并丢弃缓冲 → Ok `true`；同上按所有者校验；
 * - 未知方法 → Err ERR_NOT_IMPLEMENTED；非法载荷 → Err ERR_INVALID_PARAM。
 */
class EnginesNamespaceHandler(
    private val controller: RuntimeController,
    private val channelCapacity: Int = DEFAULT_CHANNEL_CAPACITY,
) : RpcNamespaceHandler() {

    /** 申报方法表（wire-schema 对账挂点）：单源指向生成物 [WireMethods.BY_NS]，不手抄。 */
    override fun methods(): Set<String> = WireMethods.BY_NS.getValue("engines")

    override suspend fun dispatch(request: BridgeRequest): BridgeResponse = when (request.method) {
        "exec" -> exec(request)
        "stop" -> stop(request)
        "poolStats" -> ok(request, DomainJson.encode(poolStatsPayload()))
        "status" -> status(request)
        "heartbeat" -> heartbeat(request)
        "channel" -> channel(request)
        "channelEmit" -> channelEmit(request)
        "channelDrain" -> channelDrain(request)
        "channelClose" -> channelClose(request)
        else -> err(request, ErrorCode.ERR_NOT_IMPLEMENTED, "未知 engines 方法: ${request.method}")
    }

    // ── exec / stop / poolStats ──────────────────────────────────────────

    private suspend fun exec(request: BridgeRequest): BridgeResponse {
        val p = parseExec(request.payload)
        val bounded = p.copy(waitTimeoutMillis = p.waitTimeoutMillis ?: request.ttlMillis)
        // 跨脚本派生授权（A5，§11）：脚本经 exec 拉起新执行要先过闸（缺 CROSS_SCRIPT_CONTROL
        // 或会造成提权 → ERR_PERMISSION_DENIED）；宿主/UI 发起的调用没有认证上下文，不受此限。
        // 返回的**子授权快照**原样放进请求：下游（池 → 引擎 → 身份签发）搬的是同一份，
        // 不存在「这里校验 A 掩码、那边签发 B 掩码」的窗口（A5 整改第 4 条）。
        val caller = coroutineContext[AuthenticatedRunContext]
        val childAuthorization = try {
            controller.authorizeStart(caller, bounded.projectId)
        } catch (e: AutojsException) {
            return err(request, e.error, e.message)
        }
        val authorized = bounded.copy(authorization = childAuthorization)
        return when (val outcome = controller.start(authorized)) {
            is RuntimeController.StartOutcome.Started -> ok(
                request,
                DomainJson.encode(
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
                err(request, ErrorCode.ERR_TIMEOUT, "引擎池排队超时")
            is RuntimeController.StartOutcome.StartFailed ->
                err(request, ErrorCode.ERR_ENGINE_CRASHED, outcome.message)
        }
    }

    private suspend fun stop(request: BridgeRequest): BridgeResponse {
        val runId = requiredLong(decodePayload(request.payload), "runId")
        // 跨脚本目标授权（A5，§11）：停**自己**任何掩码都放行（`EngineSessionImpl.cancel`
        // 靠它）；停别的执行要 CROSS_SCRIPT_CONTROL 且不得触达授权不低于自己的执行。
        // 目录级要求是 NONE（见 BridgeCapabilityCatalog 的 METHOD_REQUIRED 说明）——
        // 「是不是别的执行」只有这里看得到（要 runId），所以分档判据落在 handler。
        // 不在途 → ERR_NOT_FOUND（既有口径）。
        coroutineContext[AuthenticatedRunContext]?.let { caller ->
            try {
                controller.authorizeTarget(caller, runId, BridgeCapability.CROSS_SCRIPT_CONTROL)
            } catch (e: AutojsException) {
                return err(request, e.error, e.message)
            }
        }
        return when (val outcome = controller.stop(runId)) {
            RuntimeController.StopOutcome.StoppedClean -> ok(request, "true")
            is RuntimeController.StopOutcome.StoppedTimeout ->
                err(request, ErrorCode.ERR_TIMEOUT, "软停超时(partial=${outcome.partial})，已 kill 兜底")
            RuntimeController.StopOutcome.AlreadyGone ->
                err(request, ErrorCode.ERR_NOT_FOUND, "未知 runId: $runId")
        }
    }

    /**
     * 引擎侧状态快照（JS `onExit` 轮询的地基，见方法表 `status` 条）。
     *
     * 只读在途表（[RuntimeController.probeStatus]）：不在途 → null → 如实 NOT_FOUND。
     * 宿主探针抛错同样落 null（引擎已死/实现未接线）—— 那是"量不到"，与"已结算"
     * 在本方法不区分：两者都意味着"没有可读的活状态"，调用方按"本次轮询无结果、
     * 下一轮再问"处理，不得把 null 翻译成任何终态。
     */
    private suspend fun status(request: BridgeRequest): BridgeResponse {
        val runId = requiredLong(decodePayload(request.payload), "runId")
        // 同上：看自己的状态任何掩码放行；看别的执行要 CROSS_SCRIPT_OBSERVE。
        coroutineContext[AuthenticatedRunContext]?.let { caller ->
            try {
                controller.authorizeTarget(caller, runId, BridgeCapability.CROSS_SCRIPT_OBSERVE)
            } catch (e: AutojsException) {
                return err(request, e.error, e.message)
            }
        }
        val st = controller.probeStatus(runId)
            ?: return err(request, ErrorCode.ERR_NOT_FOUND, "未知 runId: $runId")
        return ok(request, DomainJson.encode(st.name))
    }

    private fun poolStatsPayload(): Map<String, Any?> {
        val s = controller.stats()
        return mapOf("capacity" to s.capacity.toLong(), "free" to s.free.toLong(), "busy" to s.busy.toLong())
    }

    /**
     * 心跳打点（§8.4 缺口②）。seq 由引擎侧自增：落后/重复的帧被账本拒收
     * （不刷时间戳），否则宿主张力下积压的旧心跳会让死掉的 run 一直"活着"。
     * 不在途 runId（已结算/从未存在）→ 同样 Ok `false` 且不记账（见
     * [RuntimeController.heartbeat]）：那不是调用方错误，但也不得伪装成有效心跳。
     */
    private suspend fun heartbeat(request: BridgeRequest): BridgeResponse {
        val o = decodePayload(request.payload)
        val runId = requiredLong(o, "runId")
        val seq = requiredLong(o, "seq")
        val caller = coroutineContext[AuthenticatedRunContext]
        if (caller == null || caller.engineRunId != runId) {
            return err(request, ErrorCode.ERR_PERMISSION_DENIED, "心跳只能来自对应执行的连接")
        }
        val accepted = controller.heartbeat(runId, seq)
        return ok(request, DomainJson.encode(accepted))
    }

    private fun parseExec(payload: String?): PoolAcquireRequest {
        val o = decodePayload(payload)
        val rawProjectId = requiredStr(o, "projectId")
        val rawScriptPath = requiredStr(o, "scriptPath")
        if (rawProjectId.isBlank()) throw IllegalArgumentException("projectId 不得为空")
        if (rawScriptPath.isBlank()) throw IllegalArgumentException("scriptPath 不得为空")
        // **判据进值类**（2026-10-12 批 109，typed ids）：这两个值是**脚本给的**，而
        // scriptPath 会一路进 `node <路径>` 的 argv。裸 String 传下去时，「这是个项目号」
        // 与「这是个项目内相对路径」只靠下游各自的 require 兜着，而 `a/b` 对两者都合法 ——
        // 换句话说，一次串错位既不会被类型拦下，也不会被下游的 require 拦下。
        // 判不过在此就地折 ERR_INVALID_PARAM，**话术与既有那条非法参数保持同款**。
        val projectId = ProjectId.createOrNull(rawProjectId)
            ?: throw IllegalArgumentException("projectId 非法：$rawProjectId")
        val scriptPath = RelPath.createOrNull(rawScriptPath)
            ?: throw IllegalArgumentException("scriptPath 非法：$rawScriptPath")
        // 桥这条路的执行时限**必须显式给**（见方法表 `exec` 条）：拿到句柄就返回、没人 await
        // 终结，缺省值在这里没有诚实来源 —— 编一个（30s？5min？）等于替脚本决定它能跑多久，
        // 而且是静默的。与 §8.6 queueTimeoutMillis 传 0 视为漏配同一条纪律：响亮失败。
        val timeout = optLong(o, "timeoutMillis")
            ?: throw IllegalArgumentException("exec 必须显式给 timeoutMillis（桥路径无人 await 终结，期限须由调用方声明；§8.6）")
        if (timeout <= 0) throw IllegalArgumentException("timeoutMillis 必须 > 0：$timeout")
        return PoolAcquireRequest(
            // 解包回 String 是**DTO 边界**（批 109 的范围只到两端，DTO 字段仍是 String）：
            // 判据已经在上面那道 `createOrNull` 过了，这里存的只是一个过了判据的值。
            projectId = projectId.value,
            scriptPath = scriptPath.value,
            args = optStrList(o, "args"),
            runNonce = optStr(o, "runNonce"),
            scriptTimeoutMillis = timeout,
            waitTimeoutMillis = optLong(o, "waitTimeoutMillis"),
            timeoutEnforcer = TimeoutEnforcer.WATCHDOG,
        )
    }

    // ── 命名通道 ─────────────────────────────────────────────────────────
    //
    // 通道是脚本↔宿主 JSON 事件的命名缓冲（§8 RuntimeChannel）。订阅推送（host→script）
    // 走桥 EventBus/TSF（:app 层经 channelDrain 拉取后转发）；本层只做缓冲 + 游标，
    // 不做回调推送（与 ConsoleCollector/EventBus 的"节流拉取"语义一致）。
    //
    // **所有者口径（A5，§11）**：通道**按发起执行私有**，不是全局共享 ——
    // 所有者 = 认证连接的 engineRunId（[AuthenticatedRunContext.engineRunId]）。
    // 名字索引也按所有者分桶（[channelIds]），因此：
    // - 不同执行取同名通道 → **各拿各的 id**，互不可见（不是"复用同一个全局通道"）；
    // - 猜别人的 channelId → `ERR_NOT_FOUND`（通道**存在但不归你**，与"不存在"回同一个码，
    //   不给"这个号存在过"的探测面）；
    // - 宿主直投（无认证上下文）→ 归属"宿主"（[HOST_OWNER]），与任何脚本都不共享。
    //
    // 为什么必须这样：通道事件是脚本↔宿主的数据面，若全局共享，任何 UNKNOWN 只要猜到
    // channelId（或先取一个同名通道）就能读写甚至关闭**高信任执行**的通道 —— 那就是
    // 一条绕过掩码的跨脚本数据通道。按所有者隔离后，通道不再是跨执行面，
    // `engines.channel*` 目录项要 NONE 才自洽（见 `BridgeCapabilityCatalog`）。
    //
    // 边界：这**不是**沙箱，只约束桥面通道。同 UID 代码仍可绕开桥直接读写文件（§11.3 第 1 条）。

    private data class ChannelState(
        val channelId: Long,
        /** 所有者：发起执行的 engineRunId；[HOST_OWNER] = 宿主直投。 */
        val owner: Long,
        val name: String,
        val events: ArrayDeque<ChannelEvent> = ArrayDeque(),
        var nextSeq: Long = 1,
        var dropped: Long = 0,
        var closed: Boolean = false,
    )

    data class ChannelEvent(val seq: Long, val event: String, val payload: String?)

    private val channels = HashMap<Long, ChannelState>()

    /** 名字索引「按所有者分桶」：`owner → (name → channelId)`，同名不同主各是各的。 */
    private val channelIds = HashMap<Long, HashMap<String, Long>>()
    private var nextChannelId = 1L
    private val channelGuard = Any()

    /**
     * 当前调用的通道所有者。
     *
     * 无认证上下文 = 宿主直投（装配/UI/测试）→ [HOST_OWNER]；有身份 → 它的 engineRunId。
     * 这是通道归属的**唯一**判据，不从 payload 读（脚本自报不算）。
     */
    private suspend fun ownerOf(): Long = coroutineContext[AuthenticatedRunContext]?.engineRunId ?: HOST_OWNER

    /**
     * 取本所有者名下的通道；不存在、已关闭、或**不属于本所有者**一律 null
     * （调用方统一折成 `ERR_NOT_FOUND`：存在但不归你 与 不存在 回同一个码）。
     *
     * 调用方必须已持 [channelGuard]。
     */
    private fun ownedLocked(channelId: Long, owner: Long): ChannelState? =
        channels[channelId]?.takeIf { it.owner == owner && !it.closed }

    private suspend fun channel(request: BridgeRequest): BridgeResponse {
        val o = decodePayload(request.payload)
        val name = requiredStr(o, "name")
        if (name.isBlank()) return err(request, ErrorCode.ERR_INVALID_PARAM, "通道名不得为空")
        val owner = ownerOf()
        val id = synchronized(channelGuard) {
            val mine = channelIds.getOrPut(owner) { HashMap() }
            val existing = mine[name]?.let { channels[it] }?.takeIf { !it.closed }
            if (existing != null) {
                existing.channelId
            } else {
                val nid = nextChannelId++
                channels[nid] = ChannelState(channelId = nid, owner = owner, name = name)
                mine[name] = nid
                nid
            }
        }
        return ok(request, DomainJson.encode(mapOf("name" to name, "channelId" to id)))
    }

    private suspend fun channelEmit(request: BridgeRequest): BridgeResponse {
        val o = decodePayload(request.payload)
        val channelId = requiredLong(o, "channelId")
        val event = requiredStr(o, "event")
        val payload = optStr(o, "payload")
        if (event.isBlank()) return err(request, ErrorCode.ERR_INVALID_PARAM, "事件名不得为空")
        val owner = ownerOf()
        synchronized(channelGuard) {
            val state = ownedLocked(channelId, owner)
                ?: return err(request, ErrorCode.ERR_NOT_FOUND, "未知 channelId: $channelId")
            state.events.addLast(ChannelEvent(seq = state.nextSeq++, event = event, payload = payload))
            while (state.events.size > channelCapacity) {
                state.events.removeFirst()
                state.dropped++
            }
        }
        return ok(request, null)
    }

    private suspend fun channelDrain(request: BridgeRequest): BridgeResponse {
        val o = decodePayload(request.payload)
        val channelId = requiredLong(o, "channelId")
        val sinceSeq = optLong(o, "sinceSeq") ?: 0L
        val max = (optLong(o, "max") ?: 128L).toInt()
        require(max > 0) { "max 必须 > 0" }
        val owner = ownerOf()
        val picked: List<ChannelEvent>
        var last = sinceSeq
        synchronized(channelGuard) {
            val state = ownedLocked(channelId, owner)
                ?: return err(request, ErrorCode.ERR_NOT_FOUND, "未知 channelId: $channelId")
            picked = state.events.filter { it.seq > sinceSeq }.take(max)
            for (e in picked) last = e.seq
        }
        return ok(
            request,
            DomainJson.encode(
                mapOf(
                    "last" to last,
                    "events" to picked.map {
                        mapOf("seq" to it.seq, "event" to it.event, "payload" to it.payload)
                    },
                ),
            ),
        )
    }

    private suspend fun channelClose(request: BridgeRequest): BridgeResponse {
        val channelId = requiredLong(decodePayload(request.payload), "channelId")
        val owner = ownerOf()
        synchronized(channelGuard) {
            val state = ownedLocked(channelId, owner)
                ?: return err(request, ErrorCode.ERR_NOT_FOUND, "未知 channelId: $channelId")
            channels.remove(channelId)
            state.closed = true
            channelIds[owner]?.remove(state.name)
        }
        return ok(request, "true")
    }

    // ── 载荷读取 ─────────────────────────────────────────────────────────

    private fun decodePayload(payload: String?): Map<String, DomainJson.Value> {
        if (payload == null) throw IllegalArgumentException("缺 payload")
        return DomainJson.decodeObject(payload)
    }

    private fun requiredStr(o: Map<String, DomainJson.Value>, key: String): String =
        (o[key] as? DomainJson.Value.S)?.v ?: throw IllegalArgumentException("缺字符串字段 $key")

    private fun optStr(o: Map<String, DomainJson.Value>, key: String): String? {
        val v = o[key] ?: return null
        if (v is DomainJson.Value.Null) return null
        return (v as? DomainJson.Value.S)?.v ?: throw IllegalArgumentException("字段 $key 必须是字符串")
    }

    private fun requiredLong(o: Map<String, DomainJson.Value>, key: String): Long =
        (o[key] as? DomainJson.Value.N)?.raw?.toLongOrNull()
            ?: throw IllegalArgumentException("缺数字字段 $key")

    private fun optLong(o: Map<String, DomainJson.Value>, key: String): Long? {
        val v = o[key] ?: return null
        if (v is DomainJson.Value.Null) return null
        return (v as? DomainJson.Value.N)?.raw?.toLongOrNull()
            ?: throw IllegalArgumentException("字段 $key 必须是数字")
    }

    private fun optStrList(o: Map<String, DomainJson.Value>, key: String): List<String> {
        val v = o[key] ?: return emptyList()
        if (v is DomainJson.Value.Null) return emptyList()
        if (v !is DomainJson.Value.Arr) throw IllegalArgumentException("字段 $key 必须是数组")
        return v.items.map {
            (it as? DomainJson.Value.S)?.v ?: throw IllegalArgumentException("字段 $key 数组元素必须是字符串")
        }
    }

    companion object {
        const val DEFAULT_CHANNEL_CAPACITY = 256

        /**
         * 宿主直投（无认证上下文）的通道所有者标识。
         *
         * 用 `-1` 而不是 `0`：`AuthenticatedRunContext` 已 require `engineRunId > 0`，
         * 0 是保留给"宿主直写日志"的哨兵（见其 KDoc）—— 通道归属另用一个明确的负值，
         * 免得两处哨兵含义打架。宿主通道与任何脚本通道都不共享（见「命名通道」段注释）。
         */
        const val HOST_OWNER: Long = -1L
    }
}
