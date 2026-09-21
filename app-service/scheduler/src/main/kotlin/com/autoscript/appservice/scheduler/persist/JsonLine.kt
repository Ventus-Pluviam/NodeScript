package com.autoscript.appservice.scheduler.persist

import java.io.IOException

/**
 * jsonl 行级极简解析/转义（persist 包内共享）。
 *
 * 服务的是"冻结行格式"：键为字符串，值为字符串/整数/null 三种。不引入 JSON 库
 * 是刻意的 —— persist 层零第三方依赖（只有 :domain + JDK），单测与生产同一份解析，
 * 格式漂移在编译期可见而非运行时爆炸。
 */
internal object JsonLine {

    /** 解析一行 `{...}` 为字段表（值仅为 String/Long/null 三种）。 */
    fun parse(line: String): Map<String, Any?> {
        val m = Parser(line)
        m.expect('{')
        val fields = HashMap<String, Any?>()
        var nFields = 0
        while (true) {
            m.ws()
            if (m.peek() == '}') { m.pos++; break }
            if (nFields > 0) {
                m.expect(',')
                m.ws()
            }
            val key = m.string()
            nFields++
            m.ws(); m.expect(':'); m.ws()
            val v: Any? = when {
                m.peek() == '"' -> m.string()
                m.peek() == 'n' -> { m.expectLit("null"); null }
                else -> m.number()
            }
            fields[key] = v
            m.ws()
        }
        return fields
    }

    fun quote(s: String): String = buildString(s.length + 2) {
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

    /** 递归下降解析器（仅服务本冻结行格式）。 */
    private class Parser(val s: String) {
        var pos = 0
        fun peek(): Char {
            if (pos >= s.length) throw IOException("journal 行损坏：行意外结束")
            return s[pos]
        }
        fun ws() { while (pos < s.length && s[pos] == ' ') pos++ }
        fun expect(c: Char) { if (pos >= s.length || s[pos] != c) throw IOException("journal 行损坏 @$pos 期望 $c"); pos++ }
        fun expectLit(lit: String) { if (!s.startsWith(lit, pos)) throw IOException("journal 行损坏 @$pos 期望 $lit"); pos += lit.length }

        fun string(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (pos >= s.length) throw IOException("journal 行损坏：串未闭合")
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= s.length) throw IOException("journal 行损坏：转义截断")
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            else -> throw IOException("journal 行损坏：未知转义 \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun number(): Long {
            val start = pos
            if (pos < s.length && s[pos] == '-') pos++
            while (pos < s.length && s[pos].isDigit()) pos++
            if (start == pos) throw IOException("journal 行损坏 @$pos 期望数字")
            return s.substring(start, pos).toLong()
        }
    }
}

/** 字段表强类型读取（缺键/类型错 = 行损坏，响亮失败）。 */
internal fun Map<String, Any?>.str(key: String): String =
    this[key] as? String ?: throw IOException("journal 行损坏：缺字符串字段 $key")

internal fun Map<String, Any?>.long(key: String): Long =
    (this[key] as? Long) ?: throw IOException("journal 行损坏：缺数字字段 $key")

internal fun Map<String, Any?>.optLong(key: String): Long? =
    when (val v = this[key]) {
        null -> null
        is Long -> v
        else -> throw IOException("journal 行损坏：字段 $key 非数字")
    }

internal fun Map<String, Any?>.optStr(key: String): String? =
    when (val v = this[key]) {
        null -> null
        is String -> v
        else -> throw IOException("journal 行损坏：字段 $key 非字符串")
    }
