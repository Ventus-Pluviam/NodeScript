package com.autoscript.bridge
import com.autoscript.domain.bridge.generated.WireMethods

import com.autoscript.domain.bridge.AuthenticatedRunContext
import kotlin.coroutines.coroutineContext
import com.autoscript.domain.bridge.BridgeRequest
import com.autoscript.domain.bridge.BridgeResponse
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.json.DomainJson
import java.time.Instant
import java.util.ArrayDeque

/**
 * 控制台日志行（§8 ScriptEngine.console 数据面契约的内存形态）。
 *
 * 数据面语义：可丢包、有界（[capacity] 满时丢最老并计数）；[dropped] 累计丢包数
 *（宿主可经队列统计回传 JS 层 queueError，对齐 §7.3）。
 * 线程安全：发布方（addon/socket/脚本 console.*）与消费者（:main 控制台 UI）并发。
 */
data class ConsoleLine(
    val seq: Long,
    val runId: Long,
    val level: String,
    val text: String,
    val atMillis: Long = Instant.now().toEpochMilli(),
)

/**
 * console 数据面收集器：Kotlin Router 侧注册 `console` namespace 的 [NamespaceHandler]。
 *
 * - `log` 方法：payload JSON `{"level":"log|info|warn|error|debug","text":"..."}`，
 *   连接认证上下文提供 engineRunId（缺身份拒绝，不信任 payload）→ 有界追加 → `Ok(id, null)`；
 * - 未知方法 → ERR_NOT_IMPLEMENTED（桥的诚实上报，不伪造成功）；
 * - 无效载荷（非法 JSON / 缺 text）→ ERR_INVALID_PARAM。
 * 事件式消费走 [drain]（seq 游标拉取，对齐 EventBus 节流拉取语义，不做回调推送）。
 */
class ConsoleCollector(
    private val capacity: Int = DEFAULT_CAPACITY,
    /**
     * 落盘缝（2026-10-13 执行日志持久化）：每行入队后在**锁外**调用一次。
     *
     * 锁外是刻意的：sink 做文件 IO，持收集器锁做 IO 会让桥 log 调用全部排队；
     * 抛异常由本类 `runCatching` 吞掉 —— 诊断面绝不掀翻桥的数据面（与
     * `runCatching` 容忍纪律的 `ConsoleHistory` 同口径，见 `:app-service:npm`）。
     * 缺省 = 不落盘（单测/替身进程各自隔离，行为与此前一致）。
     */
    private val lineSink: (ConsoleLine) -> Unit = {},
) : RequestHandler {

    init {
        require(capacity > 0) { "capacity 必须 > 0" }
    }

    /** 申报方法表（wire-schema 对账挂点）：单源指向生成物 [WireMethods.BY_NS]，不手抄。 */
    override fun methods(): Set<String> = WireMethods.BY_NS.getValue("console")

    // seq 分配、入队、淘汰和读取必须同锁：否则游标先越过尚未入队的小 seq，那行将永远读不到。
    private var seq = 1L
    private val lines = ArrayDeque<ConsoleLine>()
    private var dropped = 0L

    override suspend fun handle(request: BridgeRequest): BridgeResponse {
        if (request.method != "log") {
            return BridgeResponse.Err(request.id, ErrorCode.ERR_NOT_IMPLEMENTED.code, "未知 console 方法: ${request.method}")
        }
        val params = try {
            parseLogParams(request.payload)
        } catch (e: IllegalArgumentException) {
            return BridgeResponse.Err(request.id, ErrorCode.ERR_INVALID_PARAM.code, e.message)
        }
        val caller = coroutineContext[AuthenticatedRunContext]
            ?: return BridgeResponse.Err(request.id, ErrorCode.ERR_PERMISSION_DENIED.code, "console 缺执行身份")
        append(runId = caller.engineRunId, level = params.level, text = params.text)
        return BridgeResponse.Ok(request.id, null)
    }

    /** 宿主直写；回放启动缓冲时可保留原始时间，seq 仍由收集器在入队时统一分配。 */
    fun append(runId: Long, level: String, text: String, atMillis: Long = Instant.now().toEpochMilli()): ConsoleLine {
        val line = synchronized(this) {
            val built = ConsoleLine(seq = seq++, runId = runId, level = level, text = text, atMillis = atMillis)
            if (lines.size == capacity) {
                lines.removeFirst()
                dropped++
            }
            lines.addLast(built)
            built
        }
        // 落盘在锁外（见 [lineSink]）：IO 慢不挡桥，抛了也不逃逸。
        runCatching { lineSink(line) }
        return line
    }

    /** 游标拉取（seq > sinceSeq，按序，最多 max 条）。返回（最大 seq，本批）。 */
    @Synchronized
    fun drain(sinceSeq: Long, max: Int = 128): Pair<Long, List<ConsoleLine>> {
        require(max > 0) { "max 必须 > 0" }
        val picked = ArrayList<ConsoleLine>(minOf(max, lines.size))
        var last = sinceSeq
        for (line in lines) {
            if (line.seq > sinceSeq) {
                picked.add(line)
                last = line.seq
                if (picked.size >= max) break
            }
        }
        return last to picked
    }

    @Synchronized
    fun droppedCount(): Long = dropped

    @Synchronized
    fun size(): Int = lines.size

    private data class LogParams(val level: String, val text: String)

    private fun parseLogParams(payload: String?): LogParams {
        if (payload == null) throw IllegalArgumentException("console.log 缺 payload")
        val m = try {
            decodeFlat(payload, setOf("level", "text"))
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("console.log 载荷非法: ${e.message}")
        }
        val level = (m["level"] as? DomainJson.Value.S)?.v ?: throw IllegalArgumentException("console.log 缺 level")
        if (level !in VALID_LEVELS) throw IllegalArgumentException("console.log 非法 level: $level")
        val text = (m["text"] as? DomainJson.Value.S)?.v ?: throw IllegalArgumentException("console.log 缺 text")
        return LogParams(level, text)
    }

    companion object {
        const val DEFAULT_CAPACITY = 2_000
        val VALID_LEVELS: Set<String> = setOf("log", "info", "warn", "error", "debug")
    }
}
