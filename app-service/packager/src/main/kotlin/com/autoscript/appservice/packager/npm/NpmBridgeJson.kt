package com.autoscript.appservice.packager.npm

/**
 * 桥载荷极简 JSON 编解码（npm 命名空间 handler 的请求/响应体）。
 *
 * 为什么不复用 `:bridge:java` 的 TinyJson：packager 架构门禁 `com.autoscript.bridge..`
 *（见 ArchitectureTest，严于设计表），且 TinyJson 是 internal + 只支持扁平对象
 *（装不下 `handle:{refId,generation}` 嵌套与 `args:[...]` 数组）。
 * 本编解码只做 JSON 值级往返（string/number/bool/null/object/array），不支持注释、
 * 不做数字精度保证（Long 按十进制原文透传）；非法输入抛 IllegalArgumentException，
 * 由调用方折叠为 ERR_INVALID_PARAM（桥的诚实上报，不伪造成功）。
 */
internal object NpmBridgeJson {

    sealed interface Value {
        data class S(val v: String) : Value
        data class N(val raw: String) : Value
        data class B(val v: Boolean) : Value
        data object Null : Value
        data class Obj(val fields: Map<String, Value>) : Value
        data class Arr(val items: List<Value>) : Value
    }

    fun decode(text: String): Value {
        val p = Parser(text)
        val v = p.parseValue()
        p.skipWs()
        if (!p.atEnd()) throw IllegalArgumentException("尾部多余字符 @${p.pos}")
        return v
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

    fun encode(v: Any?): String = buildString { appendValue(v) }

    fun reqStr(o: Map<String, Value>, key: String): String =
        (o[key] as? Value.S)?.v ?: throw IllegalArgumentException("缺字符串字段 $key")

    fun optStr(o: Map<String, Value>, key: String): String? = when (val v = o[key]) {
        null, is Value.Null -> null
        is Value.S -> v.v
        else -> throw IllegalArgumentException("字段 $key 必须是字符串")
    }

    /**
     * 可选字符串数组（缺省/显式 null → 空表）。
     *
     * 为什么不是「丢了就当没有」：宿主不认的字段会被静默丢弃，而调用方已经显式声明过它
     * （如 `requestApprove` 的 `scripts`）——静默丢比报错更糟。故数组形态不对即抛。
     */
    fun optStrList(o: Map<String, Value>, key: String): List<String> = when (val v = o[key]) {
        null, is Value.Null -> emptyList()
        is Value.Arr -> v.items.map {
            (it as? Value.S)?.v ?: throw IllegalArgumentException("字段 $key 数组元素必须是字符串")
        }
        else -> throw IllegalArgumentException("字段 $key 必须是数组")
    }

    fun optBool(o: Map<String, Value>, key: String): Boolean? = when (val v = o[key]) {
        null, is Value.Null -> null
        is Value.B -> v.v
        else -> throw IllegalArgumentException("字段 $key 必须是布尔")
    }

    fun optLong(o: Map<String, Value>, key: String): Long? = when (val v = o[key]) {
        null, is Value.Null -> null
        is Value.N -> v.raw.toLongOrNull() ?: throw IllegalArgumentException("字段 $key 必须是整数")
        else -> throw IllegalArgumentException("字段 $key 必须是数字")
    }

    private fun StringBuilder.appendValue(v: Any?) {
        when (v) {
            null -> append("null")
            is String -> appendQuoted(v)
            is Boolean -> append(if (v) "true" else "false")
            is Number -> append(v.toString())
            is Map<*, *> -> {
                append('{')
                var first = true
                for ((k, item) in v) {
                    if (k !is String) throw IllegalArgumentException("对象键必须是字符串")
                    if (!first) append(',')
                    first = false
                    appendQuoted(k)
                    append(':')
                    appendValue(item)
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
            else -> throw IllegalArgumentException("不支持的 JSON 值类型: ${v::class.simpleName}")
        }
    }

    private fun StringBuilder.appendQuoted(s: String) {
        append('"')
        for (c in s) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c == '\b' -> append("\\b")
                c.code < 0x20 -> append("\\u").append(String.format("%04x", c.code))
                else -> append(c)
            }
        }
        append('"')
    }

    private class Parser(val text: String) {
        var pos = 0

        fun atEnd(): Boolean = pos >= text.length

        fun skipWs() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }

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
            pos++ // {
            val out = LinkedHashMap<String, Value>()
            skipWs()
            if (pos < text.length && text[pos] == '}') {
                pos++
                return Value.Obj(out)
            }
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
                    '}' -> {
                        pos++
                        return Value.Obj(out)
                    }
                    else -> throw IllegalArgumentException("非法对象分隔符 @$pos")
                }
            }
        }

        private fun parseArray(): Value.Arr {
            pos++ // [
            val out = ArrayList<Value>()
            skipWs()
            if (pos < text.length && text[pos] == ']') {
                pos++
                return Value.Arr(out)
            }
            while (true) {
                out.add(parseValue())
                skipWs()
                if (pos >= text.length) throw IllegalArgumentException("数组意外结束")
                when (text[pos]) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return Value.Arr(out)
                    }
                    else -> throw IllegalArgumentException("非法数组分隔符 @$pos")
                }
            }
        }

        private fun parseString(): String {
            pos++ // opening "
            val sb = StringBuilder()
            while (true) {
                if (pos >= text.length) throw IllegalArgumentException("字符串意外结束")
                val c = text[pos]
                when (c) {
                    '"' -> {
                        pos++
                        return sb.toString()
                    }
                    '\\' -> {
                        if (pos + 1 >= text.length) throw IllegalArgumentException("转义意外结束")
                        when (val e = text[pos + 1]) {
                            '"' -> { sb.append('"'); pos += 2 }
                            '\\' -> { sb.append('\\'); pos += 2 }
                            '/' -> { sb.append('/'); pos += 2 }
                            'b' -> { sb.append('\b'); pos += 2 }
                            'f' -> { sb.append(0x0c.toChar()); pos += 2 }
                            'n' -> { sb.append('\n'); pos += 2 }
                            'r' -> { sb.append('\r'); pos += 2 }
                            't' -> { sb.append('\t'); pos += 2 }
                            'u' -> {
                                if (pos + 5 >= text.length) throw IllegalArgumentException("\\u 转义截断")
                                val hex = text.substring(pos + 2, pos + 6)
                                sb.append(hex.toIntOrNull(16)?.toChar() ?: throw IllegalArgumentException("非法 \\u 转义: $hex"))
                                pos += 6
                            }
                            else -> throw IllegalArgumentException("非法转义 \\$e @$pos")
                        }
                    }
                    else -> {
                        if (c.code < 0x20) throw IllegalArgumentException("未转义控制字符 @$pos")
                        sb.append(c)
                        pos++
                    }
                }
            }
        }

        private fun parseNumber(): String {
            val start = pos
            if (pos < text.length && text[pos] == '-') pos++
            while (pos < text.length && (text[pos].isDigit() || text[pos] in ".eE+-")) pos++
            if (pos == start) throw IllegalArgumentException("非法数字 @$start")
            return text.substring(start, pos)
        }

        private fun expect(literal: String) {
            if (!text.startsWith(literal, pos)) throw IllegalArgumentException("非法字面量 @$pos（期望 $literal）")
            pos += literal.length
        }
    }
}
