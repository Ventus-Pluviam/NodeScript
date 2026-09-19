package com.autoscript.bridge

import com.autoscript.domain.bridge.BridgeRequest
import com.autoscript.domain.bridge.BridgeResponse
import com.autoscript.domain.core.ErrorCode
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

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
 * console 数据面收集器：Kotlin Router 侧注册 `console` namespace 的 [RequestHandler]。
 *
 * - `log` 方法：payload JSON `{"level":"log|info|warn|error|debug","text":"..."}`，
 *   `side.runId` 透传执行归属（无则 0 = 引擎外日志）→ 有界追加 → `Ok(id, null)`；
 * - 未知方法 → ERR_NOT_IMPLEMENTED（桥的诚实上报，不伪造成功）；
 * - 无效载荷（非法 JSON / 缺 text）→ ERR_INVALID_PARAM。
 * 事件式消费走 [drain]（seq 游标拉取，对齐 EventBus 节流拉取语义，不做回调推送）。
 */
class ConsoleCollector(
    private val capacity: Int = DEFAULT_CAPACITY,
) : RequestHandler {

    private val seq = AtomicLong(1)
    private val lines = ConcurrentLinkedQueue<ConsoleLine>()
    @Volatile private var dropped = 0L

    override suspend fun handle(request: BridgeRequest): BridgeResponse {
        if (request.method != "log") {
            return BridgeResponse.Err(request.id, ErrorCode.ERR_NOT_IMPLEMENTED.code, "未知 console 方法: ${request.method}")
        }
        val params = try {
            parseLogParams(request.payload)
        } catch (e: IllegalArgumentException) {
            return BridgeResponse.Err(request.id, ErrorCode.ERR_INVALID_PARAM.code, e.message)
        }
        append(runId = 0L, level = params.level, text = params.text)
        return BridgeResponse.Ok(request.id, null)
    }

    /** 宿主直写（带归属 runId）：addon/socket 面已有解析时用。 */
    fun append(runId: Long, level: String, text: String): ConsoleLine {
        val line = ConsoleLine(seq = seq.getAndIncrement(), runId = runId, level = level, text = text)
        lines.add(line)
        while (lines.size > capacity) {
            if (lines.poll() != null) dropped++
        }
        return line
    }

    /** 游标拉取（seq > sinceSeq，按序，最多 max 条）。返回（最大 seq，本批）。 */
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

    fun droppedCount(): Long = dropped
    fun size(): Int = lines.size

    private data class LogParams(val level: String, val text: String)

    private fun parseLogParams(payload: String?): LogParams {
        if (payload == null) throw IllegalArgumentException("console.log 缺 payload")
        val m = try {
            TinyJson.decode(payload, setOf("level", "text"))
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("console.log 载荷非法: ${e.message}")
        }
        val level = (m["level"] as? TinyJson.Field.S)?.v ?: throw IllegalArgumentException("console.log 缺 level")
        if (level !in VALID_LEVELS) throw IllegalArgumentException("console.log 非法 level: $level")
        val text = (m["text"] as? TinyJson.Field.S)?.v ?: throw IllegalArgumentException("console.log 缺 text")
        return LogParams(level, text)
    }

    companion object {
        const val DEFAULT_CAPACITY = 2_000
        val VALID_LEVELS: Set<String> = setOf("log", "info", "warn", "error", "debug")
    }
}
