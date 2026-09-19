package com.autoscript.bridge

/**
 * 极简 JSON —— 仅服务桥信封（扁平对象、string/number/null）。
 * 不追求通用性；任何超出本形状的输入按 [IllegalArgumentException] 拒绝，便于尽早暴露。
 */
internal object TinyJson {

    sealed interface Field {
        data class S(val v: String) : Field
        data class N(val v: String) : Field
        data object Null : Field
    }

    fun encode(fields: List<Pair<String, Field>>): String = buildString {
        append('{')
        for ((indexed, pair) in fields.withIndex()) {
            val (k, f) = pair
            if (indexed > 0) append(',')
            append(quote(k)).append(':').append(encodeValue(f))
        }
        append('}')
    }

    private fun encodeValue(f: Field): String = when (f) {
        is Field.S -> quote(f.v)
        is Field.N -> f.v
        Field.Null -> "null"
    }

    /** 解码扁平对象。key 必须在 [allowed] 白名单内（防乱码注入）；值还原为 Field。 */
    fun decode(text: String, allowed: Set<String>): Map<String, Field> {
        var p = 0
        p = skipWs(text, p)
        expect(text, p, '{'); p++
        val out = LinkedHashMap<String, Field>()
        while (true) {
            p = skipWs(text, p)
            if (p >= text.length) throw IllegalArgumentException("意外结束 @$p")
            if (text[p] == '}') { p++; break }
            val (key, q) = readString(text, p)
            p = q
            if (key !in allowed) throw IllegalArgumentException("未知字段: $key")
            p = skipWs(text, p)
            expect(text, p, ':'); p++
            p = skipWs(text, p)
            val (field, q2) = readValue(text, p)
            p = q2
            out[key] = field
            p = skipWs(text, p)
            when (text.getOrNull(p)) {
                ',' -> p++
                '}' -> { p++; break }
                else -> throw IllegalArgumentException("非法对象分隔符 @$p: ${text.getOrNull(p) ?: "EOF"}")
            }
        }
        return out
    }

    private fun readValue(text: String, p0: Int): Pair<Field, Int> {
        var p = p0
        return when {
            text[p] == '"' -> {
                val (s, q) = readString(text, p)
                Field.S(s) to q
            }
            text[p] == 'n' -> {
                checkTail(text, p, "null"); Field.Null to (p + 4)
            }
            text[p] == '-' || text[p] in '0'..'9' -> {
                val start = p
                while (p < text.length && (text[p] in '0'..'9' || text[p] in "-.eE+")) p++
                Field.N(text.substring(start, p)) to p
            }
            else -> throw IllegalArgumentException("非法值 @$p: ${text[p] ?: "EOF"}")
        }
    }

    private fun checkTail(text: String, p: Int, literal: String) {
        for (i in literal.indices) {
            if (text.getOrNull(p + i) != literal[i]) {
                throw IllegalArgumentException("非法字面量 @$p（期望 $literal）")
            }
        }
    }

    private fun readString(text: String, p0: Int): Pair<String, Int> {
        require(text[p0] == '"') { "期望字符串 @$p0" }
        val sb = StringBuilder()
        var p = p0 + 1
        while (true) {
            val c = text[p]
            when (c) {
                '"' -> return sb.toString() to (p + 1)
                '\\' -> {
                    val e = text[p + 1]
                    when (e) {
                        '"' -> { sb.append('"'); p += 2 }
                        '\\' -> { sb.append('\\'); p += 2 }
                        '/' -> { sb.append('/'); p += 2 }
                        'b' -> { sb.append('\b'); p += 2 }
                        // Kotlin 无 \f 转义（Java 特有）：form feed 用码点显式表达
                        'f' -> { sb.append('\u000C'); p += 2 }
                        'n' -> { sb.append('\n'); p += 2 }
                        'r' -> { sb.append('\r'); p += 2 }
                        't' -> { sb.append('\t'); p += 2 }
                        'u' -> {
                            val hex = text.substring(p + 2, p + 6)
                            sb.append(hex.toInt(16).toChar())
                            p += 6
                        }
                        else -> throw IllegalArgumentException("非法转义 \\$e @$p")
                    }
                }
                else -> {
                    if (c.code < 0x20) throw IllegalArgumentException("未转义控制字符 @$p")
                    sb.append(c); p++
                }
            }
        }
    }

    fun quote(s: String): String = buildString {
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

    private fun skipWs(text: String, from: Int): Int {
        var p = from
        while (p < text.length && text[p].isWhitespace()) p++
        return p
    }

    private fun expect(text: String, p: Int, c: Char) {
        if (text.getOrNull(p) != c) throw IllegalArgumentException("期望 '$c' @$p，实为 ${text.getOrNull(p) ?: "EOF"}")
    }
}