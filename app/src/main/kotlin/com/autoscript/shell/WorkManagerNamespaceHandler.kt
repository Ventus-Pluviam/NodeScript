package com.autoscript.shell

import com.autoscript.appservice.scheduler.core.ScheduledTask
import com.autoscript.appservice.scheduler.core.Scheduler
import com.autoscript.appservice.scheduler.core.ScreenGuarantee
import com.autoscript.appservice.scheduler.core.TimedSchedule
import com.autoscript.domain.bridge.BridgeRequest
import com.autoscript.domain.bridge.BridgeResponse
import com.autoscript.domain.bridge.NamespaceHandler
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import java.time.ZoneId

/**
 * `workManager` 命名空间桥处理器（docs §8.6/§9.6 定时 API；JS `workManager.*` 的 Kotlin 对偶）。
 *
 * 归属：住 `:app` 装配包（不是 `:app-service:scheduler`）——它直接驱动 [Scheduler]
 * （登记/取消/列举），而 scheduler 模块的 arch 门禁只允许它依赖 `:domain`；
 * `:app` 本就可以见 scheduler（`AppShell.assemble` 已构造它），故 handler 落在这里，
 * 与 `engines` 经 `EnginesNamespaceHandler` 薄转接挂 Router 同一形态。
 * 与 a11y/screen 缝的区别：能力实现在 `:platform`（必须注入），调度器是本壳自建的，
 * 故本命名空间**恒挂载**，不经注入缝。
 *
 * 线格式（与 `bridge/js` workManager.ts 逐字段对齐，扁平 JSON，无嵌套数组之外的结构）：
 * - `create`：`{id?,name,projectId,scriptPath,schedule:{kind,...},screen?,args?,
 *   scriptTimeoutMillis?,timezone?,enabled?}` → Ok `{"id":"…"}`。
 *   `schedule.kind` = `once`（`delaySeconds`）/`daily`（`hourOfDay`+`minuteOfHour`）；
 *   `cron` → Err ERR_NOT_IMPLEMENTED（P1 未落地，如实拒绝不伪装成定时）；
 *   `screen` 缺省 `ANY`；`timezone` 缺省系统默认；`enabled` 缺省 true；
 *   `id` 缺省服务端分配（UUID）。
 * - `cancel`：`{id}` → Ok `true`（幂等：从未登记的 id 照样 true，与 `Scheduler.cancel` 一致）。
 * - `list`：无参 → Ok `[task,…]`（与 create 同一任务形状；按 id 排序）。
 * - 未知方法 → ERR_NOT_IMPLEMENTED；非法载荷 → ERR_INVALID_PARAM。
 */
class WorkManagerNamespaceHandler(private val scheduler: Scheduler) {

    suspend fun handle(request: BridgeRequest): BridgeResponse = try {
        BridgeResponse.Ok(request.id, dispatch(request))
    } catch (e: AutojsException) {
        BridgeResponse.Err(request.id, e.error.code, e.message)
    } catch (e: IllegalArgumentException) {
        BridgeResponse.Err(request.id, ErrorCode.ERR_INVALID_PARAM.code, e.message)
    }

    /** 挂载为桥 NamespaceHandler（`AppShell.assemble` 直接挂，不经注入缝）。 */
    fun mount(): NamespaceHandler = NamespaceHandler { req -> handle(req) }

    private suspend fun dispatch(request: BridgeRequest): String? {
        return when (request.method) {
            "create" -> create(WmJson.decodeObject(requirePayload(request)))
            "cancel" -> {
                val f = WmJson.decodeObject(requirePayload(request))
                scheduler.cancel(WmJson.reqStr(f, "id"))
                "true"
            }
            "list" -> WmJson.encode(scheduler.tasks().map { encodeTask(it) })
            else -> throw AutojsException(
                ErrorCode.ERR_NOT_IMPLEMENTED, "未知 workManager 方法: ${request.method}",
            )
        }
    }

    private suspend fun create(f: Map<String, WmJson.Value>): String {
        val schedule = parseSchedule(WmJson.reqObj(f, "schedule"))
        val task = ScheduledTask(
            id = (f["id"] as? WmJson.Value.S)?.v?.takeIf { it.isNotBlank() }
                ?: java.util.UUID.randomUUID().toString(),
            name = WmJson.reqStr(f, "name").takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("name 不得为空串"),
            projectId = WmJson.reqStr(f, "projectId").takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("projectId 不得为空串"),
            scriptPath = WmJson.reqStr(f, "scriptPath").takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("scriptPath 不得为空串"),
            schedule = schedule,
            screen = when (val v = f["screen"]) {
                null, is WmJson.Value.Null -> ScreenGuarantee.ANY
                is WmJson.Value.S -> try {
                    ScreenGuarantee.valueOf(v.v)
                } catch (_: IllegalArgumentException) {
                    throw IllegalArgumentException("screen 非法: ${v.v}（SCREEN_ON/ANY/SCREEN_OFF）")
                }
                else -> throw IllegalArgumentException("screen 必须是字符串")
            },
            args = when (val v = f["args"]) {
                null, is WmJson.Value.Null -> emptyList()
                is WmJson.Value.Arr -> v.items.map {
                    (it as? WmJson.Value.S)?.v
                        ?: throw IllegalArgumentException("args 元素必须是字符串")
                }
                else -> throw IllegalArgumentException("args 必须是数组")
            },
            scriptTimeoutMillis = when (val v = f["scriptTimeoutMillis"]) {
                null, is WmJson.Value.Null -> null
                is WmJson.Value.N -> v.raw.toLongOrNull()?.takeIf { it > 0 }
                    ?: throw IllegalArgumentException("scriptTimeoutMillis 必须 > 0")
                else -> throw IllegalArgumentException("scriptTimeoutMillis 必须是数字")
            },
            timezone = when (val v = f["timezone"]) {
                null, is WmJson.Value.Null -> ZoneId.systemDefault()
                is WmJson.Value.S -> try {
                    ZoneId.of(v.v)
                } catch (_: Exception) {
                    throw IllegalArgumentException("timezone 非法: ${v.v}")
                }
                else -> throw IllegalArgumentException("timezone 必须是字符串")
            },
            enabled = when (val v = f["enabled"]) {
                null, is WmJson.Value.Null -> true
                is WmJson.Value.B -> v.v
                else -> throw IllegalArgumentException("enabled 必须是布尔")
            },
        )
        scheduler.schedule(task)
        return """{"id":${WmJson.quote(task.id)}}"""
    }

    private fun parseSchedule(f: Map<String, WmJson.Value>): TimedSchedule {
        return when (WmJson.reqStr(f, "kind")) {
            "once" -> TimedSchedule.Once(
                (f["delaySeconds"] as? WmJson.Value.N)?.raw?.toLongOrNull()?.takeIf { it >= 0 }
                    ?: throw IllegalArgumentException("once 需要非负 delaySeconds"),
            )
            "daily" -> TimedSchedule.Daily(
                (f["hourOfDay"] as? WmJson.Value.N)?.raw?.toIntOrNull()
                    ?: throw IllegalArgumentException("daily 需要 hourOfDay"),
                (f["minuteOfHour"] as? WmJson.Value.N)?.raw?.toIntOrNull()
                    ?: throw IllegalArgumentException("daily 需要 minuteOfHour"),
            )
            "cron" -> throw AutojsException(
                ErrorCode.ERR_NOT_IMPLEMENTED, "cron 排期 P1 未落地（见 §8.6）：只接受 once/daily",
            )
            else -> throw IllegalArgumentException("schedule.kind 非法（once/daily/cron）")
        }
        // TimedSchedule.Daily 的 init require 负责越界拒绝（hour 0..23/minute 0..59），
        // 抛出的 IllegalArgumentException 由 handle 折叠为 ERR_INVALID_PARAM。
    }

    private fun encodeTask(t: ScheduledTask): Map<String, Any?> = mapOf(
        "id" to t.id,
        "name" to t.name,
        "projectId" to t.projectId,
        "scriptPath" to t.scriptPath,
        "schedule" to when (val s = t.schedule) {
            is TimedSchedule.Once -> mapOf("kind" to "once", "delaySeconds" to s.delaySeconds)
            is TimedSchedule.Daily -> mapOf(
                "kind" to "daily", "hourOfDay" to s.hourOfDay, "minuteOfHour" to s.minuteOfHour,
            )
            is TimedSchedule.Cron -> mapOf("kind" to "cron", "expr" to s.expr)
        },
        "screen" to t.screen.name,
        "args" to t.args,
        "scriptTimeoutMillis" to t.scriptTimeoutMillis,
        "timezone" to t.timezone.id,
        "enabled" to t.enabled,
    )

    private fun requirePayload(request: BridgeRequest): String =
        request.payload ?: throw IllegalArgumentException("${request.method} 需要 payload 对象")
}

/**
 * 桥载荷极简 JSON（仅服务本 handler；`:app` 装配包禁直连 `:bridge:java` 之外的自有解析，
 * 而 TinyJson 是 internal 且只支持扁平对象 —— schedule/args 嵌套装不下，故此处自带值级编解码）。
 * string/number/bool/null/object/array；非法输入抛 IllegalArgumentException → ERR_INVALID_PARAM。
 */
internal object WmJson {

    sealed interface Value {
        data class S(val v: String) : Value
        data class N(val raw: String) : Value
        data class B(val v: Boolean) : Value
        data object Null : Value
        data class Obj(val fields: Map<String, Value>) : Value
        data class Arr(val items: List<Value>) : Value
    }

    fun decodeObject(text: String): Map<String, Value> {
        val v = try {
            decode(text)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("载荷非法 JSON: ${e.message}")
        }
        if (v !is Value.Obj) throw IllegalArgumentException("载荷必须是 JSON 对象")
        return v.fields
    }

    fun reqStr(f: Map<String, Value>, key: String): String =
        (f[key] as? Value.S)?.v ?: throw IllegalArgumentException("缺字符串字段 $key")

    fun reqObj(f: Map<String, Value>, key: String): Map<String, Value> =
        (f[key] as? Value.Obj)?.fields ?: throw IllegalArgumentException("缺对象字段 $key")

    fun quote(s: String): String = buildString {
        append('"')
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
        append('"')
    }

    fun encode(v: Any?): String = buildString { appendValue(v) }

    private fun StringBuilder.appendValue(v: Any?) {
        when (v) {
            null -> append("null")
            is String -> append(quote(v))
            is Boolean -> append(if (v) "true" else "false")
            is Number -> append(v.toString())
            is Map<*, *> -> {
                append('{')
                var first = true
                for ((k, item) in v) {
                    if (k !is String) throw IllegalArgumentException("对象键必须是字符串")
                    if (!first) append(',')
                    first = false
                    append(quote(k)); append(':'); appendValue(item)
                }
                append('}')
            }
            is List<*> -> {
                append('[')
                v.forEachIndexed { i, item ->
                    if (i > 0) append(',')
                    appendValue(item)
                }
                append(']')
            }
            else -> throw IllegalArgumentException("不支持的 JSON 值类型")
        }
    }

    private fun decode(text: String): Value {
        val p = Parser(text)
        val v = p.parseValue()
        p.skipWs()
        if (!p.atEnd()) throw IllegalArgumentException("尾部多余字符 @${p.pos}")
        return v
    }

    private class Parser(val text: String) {
        var pos = 0
        fun atEnd(): Boolean = pos >= text.length
        fun skipWs() { while (pos < text.length && text[pos].isWhitespace()) pos++ }
        fun parseValue(): Value {
            skipWs()
            if (pos >= text.length) throw IllegalArgumentException("意外结束")
            return when (val c = text[pos]) {
                '"' -> Value.S(parseString())
                '{' -> parseObject()
                '[' -> parseArray()
                't' -> { expect("true"); Value.B(true) }
                'f' -> { expect("false"); Value.B(false) }
                'n' -> { expect("null"); Value.Null }
                '-', in '0'..'9' -> Value.N(parseNumber())
                else -> throw IllegalArgumentException("非法值 @$pos: $c")
            }
        }
        private fun parseObject(): Value.Obj {
            pos++
            val out = LinkedHashMap<String, Value>()
            skipWs()
            if (pos < text.length && text[pos] == '}') { pos++; return Value.Obj(out) }
            while (true) {
                skipWs()
                if (pos >= text.length || text[pos] != '"') throw IllegalArgumentException("对象键必须是字符串 @$pos")
                val key = parseString()
                skipWs()
                if (pos >= text.length || text[pos] != ':') throw IllegalArgumentException("期望 ':' @$pos")
                pos++
                out[key] = parseValue()
                skipWs()
                if (pos >= text.length) throw IllegalArgumentException("对象意外结束")
                when (text[pos]) {
                    ',' -> pos++
                    '}' -> { pos++; return Value.Obj(out) }
                    else -> throw IllegalArgumentException("非法对象分隔符 @$pos")
                }
            }
        }
        private fun parseArray(): Value.Arr {
            pos++
            val out = ArrayList<Value>()
            skipWs()
            if (pos < text.length && text[pos] == ']') { pos++; return Value.Arr(out) }
            while (true) {
                out += parseValue()
                skipWs()
                if (pos >= text.length) throw IllegalArgumentException("数组意外结束")
                when (text[pos]) {
                    ',' -> pos++
                    ']' -> { pos++; return Value.Arr(out) }
                    else -> throw IllegalArgumentException("非法数组分隔符 @$pos")
                }
            }
        }
        private fun parseString(): String {
            pos++
            val sb = StringBuilder()
            while (true) {
                if (pos >= text.length) throw IllegalArgumentException("串未闭合")
                val c = text[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= text.length) throw IllegalArgumentException("转义截断")
                        when (val e = text[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > text.length) throw IllegalArgumentException("\\u 截断")
                                sb.append(text.substring(pos, pos + 4).toInt(16).toChar())
                                pos += 4
                            }
                            else -> throw IllegalArgumentException("未知转义 \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }
        private fun parseNumber(): String {
            val start = pos
            if (pos < text.length && text[pos] == '-') pos++
            while (pos < text.length && (text[pos].isDigit() || text[pos] in ".eE+-")) pos++
            if (start == pos) throw IllegalArgumentException("期望数字 @$pos")
            return text.substring(start, pos)
        }
        private fun expect(lit: String) {
            if (!text.startsWith(lit, pos)) throw IllegalArgumentException("期望 $lit @$pos")
            pos += lit.length
        }
    }
}
