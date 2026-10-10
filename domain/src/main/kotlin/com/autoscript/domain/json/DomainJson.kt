package com.autoscript.domain.json

/**
 * 仓内唯一的 JSON 值族编解码（原 `A11yBridgeJson`，移入 `:domain` 后其余四个手写
 * codec 全部迁到本类型上 —— 选它不选 kotlinx.serialization 的论证见
 * `docs/design-decisions.md`）。
 *
 * 只做 JSON 值级往返（string/number/bool/null/object/array），不支持注释、不做数字精度保证
 * （Long 按十进制原文透传）；非法输入抛 IllegalArgumentException，由 `RpcNamespaceHandler`
 * 折叠为 ERR_INVALID_PARAM（桥的诚实上报，不伪造成功）。
 */
object DomainJson {

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

    // ── 字段读取（原 NpmBridgeJson/WmJson 的 reqStr 一族）────────────────────────────
    // 与 `bridge.Decode` 的 `BridgeRequest` 扩展同口径（那套挂 request 只为 decodeObject
    // 调用面顺手；没有 request 可挂的 persist/handler 内部函数走这里）。缺键/类型错一律
    // 抛 IAE —— 由 `RpcNamespaceHandler` 统一折 ERR_INVALID_PARAM。

    fun reqStr(o: Map<String, Value>, key: String): String =
        (o[key] as? Value.S)?.v ?: throw IllegalArgumentException("缺字符串字段 $key")

    fun reqObj(o: Map<String, Value>, key: String): Map<String, Value> =
        (o[key] as? Value.Obj)?.fields ?: throw IllegalArgumentException("缺对象字段 $key")

    fun optStr(o: Map<String, Value>, key: String): String? = when (val v = o[key]) {
        null, is Value.Null -> null
        is Value.S -> v.v
        else -> throw IllegalArgumentException("字段 $key 必须是字符串")
    }

    fun optLong(o: Map<String, Value>, key: String): Long? = when (val v = o[key]) {
        null, is Value.Null -> null
        is Value.N -> v.raw.toLongOrNull() ?: throw IllegalArgumentException("字段 $key 必须是整数")
        else -> throw IllegalArgumentException("字段 $key 必须是数字")
    }

    fun optBool(o: Map<String, Value>, key: String): Boolean? = when (val v = o[key]) {
        null, is Value.Null -> null
        is Value.B -> v.v
        else -> throw IllegalArgumentException("字段 $key 必须是布尔")
    }

    /**
     * 可选字符串数组（缺省/显式 `null` → 空表）。
     *
     * 为什么不是「丢了就当没有」：宿主不认的字段会被静默丢弃，而调用方已经显式声明过它
     * ——静默丢比报错更糟。故数组形态不对即抛。
     */
    fun optStrList(o: Map<String, Value>, key: String): List<String> = when (val v = o[key]) {
        null, is Value.Null -> emptyList()
        is Value.Arr -> v.items.map {
            (it as? Value.S)?.v ?: throw IllegalArgumentException("字段 $key 数组元素必须是字符串")
        }
        else -> throw IllegalArgumentException("字段 $key 必须是数组")
    }

    /**
     * 把已解析的 [Value] 树编回 JSON 文本（datastore `put` 的 value 子树专用）。
     * 与 [encode] 的分工：这里处理的是**已解析树** —— 数字走 [Value.N.raw] 原文
     * 透传（`1.50` 不被 Double 化掉尾零）、对象保持解析序（解码侧 LinkedHashMap）。
     * 字符串经「解码→再转义」是规范化而非逐字节透传（结构等价；空白/转义形态不作承诺）。
     */
    fun encodeParsed(v: Value): String = buildString { appendParsed(v) }

    private fun StringBuilder.appendParsed(v: Value) {
        when (v) {
            is Value.S -> appendQuoted(v.v)
            is Value.N -> append(v.raw)
            is Value.B -> append(if (v.v) "true" else "false")
            is Value.Null -> append("null")
            is Value.Obj -> {
                append('{')
                var first = true
                for ((k, item) in v.fields) {
                    if (!first) append(',')
                    first = false
                    appendQuoted(k)
                    append(':')
                    appendParsed(item)
                }
                append('}')
            }
            is Value.Arr -> {
                append('[')
                v.items.forEachIndexed { i, item ->
                    if (i > 0) append(',')
                    appendParsed(item)
                }
                append(']')
            }
        }
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
