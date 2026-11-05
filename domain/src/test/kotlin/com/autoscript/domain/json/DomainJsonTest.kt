package com.autoscript.domain.json

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * DomainJson（仓内唯一 codec，审查步骤 3 合一）round-trip 与读者族加固。
 *
 * 合并自原 A11yBridgeJson 测试面 + 各搬迁 codec 的口径断言：
 * - 转义族：b/f/u 是 DomainJson 相对老手写 codec 的超集，钉住不回退；
 * - 值域：非法输入一律 IllegalArgumentException（RpcNamespaceHandler 折 ERR_INVALID_PARAM）；
 * - 数字原文透传：Long 十进制原文不被 Double 化（1.50 / 大整数保持）；
 * - 读者族：缺键/类型错抛 IAE，opt* 对缺键与 null 宽容。
 */
class DomainJsonTest {

    private fun txt(vararg c: Char): String = c.joinToString("")

    private fun s(v: DomainJson.Value): String = (v as DomainJson.Value.S).v

    private fun n(v: DomainJson.Value): String = (v as DomainJson.Value.N).raw

    // —— round-trip ——

    @Test
    fun `字符串转义族 round-trip——超集 b f u 与控制符不回退`() {
        val samples = listOf(
            "plain 中文 🚀",
            Char(10).toString(), // \n
            Char(9).toString(),  // \t
            Char(13).toString(), // \r
            Char(8).toString(),  // \b
            Char(12).toString(), // \f
            Char(1).toString(),  // 裸控制符：编码必须落 \uXXXX（老手写 codec 直塞裸符，此处钉住超集）
            Char(34).toString(), // 双引号
            Char(92).toString(), // 反斜杠
        )
        for (raw in samples) {
            val text = DomainJson.encode(raw)
            val v = DomainJson.decode(text)
            assertTrue(v is DomainJson.Value.S, "期望字符串值: $text")
            assertEquals(raw, (v as DomainJson.Value.S).v, "round-trip 失败: $text")
        }
        assertTrue(DomainJson.encode(Char(1).toString()).contains("u0001"))
    }

    @Test
    fun `encode-decode 结构 round-trip——map list null bool number`() {
        val text = DomainJson.encode(
            linkedMapOf(
                "中" to "文",
                "arr" to listOf("a", "b"),
                "z" to null,
                "b" to true,
                "num" to 7L,
                "pi" to 3.5,
            ),
        )
        val m = DomainJson.decodeObject(text)
        assertEquals(6, m.size)
        assertEquals("文", DomainJson.reqStr(m, "中"))
        assertEquals(listOf("a", "b"), DomainJson.optStrList(m, "arr"))
        assertNull(DomainJson.optStr(m, "z"))
        assertEquals(true, DomainJson.optBool(m, "b"))
        assertEquals("7", n(m.getValue("num")))
        assertEquals("3.5", n(m.getValue("pi")))
    }

    // —— 读者族（步骤 3 合入的 req/opt 一族）——

    @Test
    fun `读者族——req 缺键类型错抛 IAE，opt 对缺键与 null 宽容`() {
        val o = DomainJson.decodeObject("""{"s":"v","n":5,"z":null,"l":["a"],"b":true,"p":{}}""")
        assertEquals("v", DomainJson.reqStr(o, "s"))
        assertEquals(emptyMap<String, DomainJson.Value>(), DomainJson.reqObj(o, "p"))
        assertThrows(IllegalArgumentException::class.java) { DomainJson.reqStr(o, "absent") }
        assertThrows(IllegalArgumentException::class.java) { DomainJson.reqStr(o, "n") }
        assertThrows(IllegalArgumentException::class.java) { DomainJson.reqObj(o, "s") }

        assertNull(DomainJson.optStr(o, "absent"))
        assertNull(DomainJson.optStr(o, "z"))
        assertThrows(IllegalArgumentException::class.java) { DomainJson.optStr(o, "n") }

        assertEquals(5L, DomainJson.optLong(o, "n"))
        assertNull(DomainJson.optLong(o, "absent"))
        assertNull(DomainJson.optLong(o, "z"))
        assertThrows(IllegalArgumentException::class.java) { DomainJson.optLong(o, "s") }
        assertThrows(IllegalArgumentException::class.java) {
            DomainJson.optLong(DomainJson.decodeObject("""{"f":1.5}"""), "f")
        }

        assertEquals(true, DomainJson.optBool(o, "b"))
        assertNull(DomainJson.optBool(o, "absent"))
        assertThrows(IllegalArgumentException::class.java) { DomainJson.optBool(o, "n") }

        assertEquals(listOf("a"), DomainJson.optStrList(o, "l"))
        assertEquals(emptyList<String>(), DomainJson.optStrList(o, "absent"))
        assertEquals(emptyList<String>(), DomainJson.optStrList(o, "z"))
        assertThrows(IllegalArgumentException::class.java) { DomainJson.optStrList(o, "s") }
        assertThrows(IllegalArgumentException::class.java) { DomainJson.optStrList(o, "n") }
        assertThrows(IllegalArgumentException::class.java) {
            DomainJson.optStrList(DomainJson.decodeObject("""{"x":[1]}"""), "x")
        }
    }

    // —— 值域与错误面 ——

    @Test
    fun `非法输入一律 IAE——非对象、尾部多余、坏字面量、未转义控制符`() {
        assertThrows(IllegalArgumentException::class.java) { DomainJson.decodeObject("[1,2]") }
        assertThrows(IllegalArgumentException::class.java) { DomainJson.decodeObject(txt('"', 's', 't', 'r', '"')) }
        assertThrows(IllegalArgumentException::class.java) { DomainJson.decodeObject("""{"a":1} x""") }
        assertThrows(IllegalArgumentException::class.java) { DomainJson.decodeObject("") }
        assertThrows(IllegalArgumentException::class.java) { DomainJson.decodeObject("{") }
        assertThrows(IllegalArgumentException::class.java) { DomainJson.decodeObject("tru") }
        // 老行兼容边：裸控制符在值里 → 拒（persist 层包成 IOException，codec 层就是 IAE）
        val ctrl = txt('{', '"', 'a', '"', ':', '"', 'x', Char(1), 'y', '"', '}')
        assertThrows(IllegalArgumentException::class.java) { DomainJson.decodeObject(ctrl) }
    }

    @Test
    fun `encode 吃标准类型、拒 Value 与非字符串键`() {
        assertThrows(IllegalArgumentException::class.java) { DomainJson.encode(DomainJson.Value.S("x")) }
        assertThrows(IllegalArgumentException::class.java) { DomainJson.encode(mapOf<Any?, Any?>(1 to "x")) }
        assertEquals("""[1,"a",null]""", DomainJson.encode(listOf(1, "a", null)))
    }

    @Test
    fun `数字走真文法——孤负号 双负号 多点 空指数 前导零一律拒`() {
        // 旧宽松扫描把这些收进 N.raw；KDoc 承诺「非法输入抛 IAE」，数字这道门也得守。
        for (bad in listOf("-", "--5", "1.2.3", "12e", "01", "1.", ".5", "+1", "1e+")) {
            assertThrows(
                IllegalArgumentException::class.java,
                { DomainJson.decode(bad) },
                "非 JSON 数字必须拒：$bad",
            )
        }
        // 合法形态不受影响（回归钉住）。
        assertEquals("1.50", n(DomainJson.decode("1.50")))
        assertEquals("-9223372036854775808", n(DomainJson.decode("-9223372036854775808")))
        assertEquals("-0.5e+10", n(DomainJson.decode("-0.5e+10")))
        assertEquals("0", n(DomainJson.decode("0")))
        assertEquals("0.5", n(DomainJson.decode("0.5")))
    }

    @Test
    fun `嵌套深度有顶——超深拒成 IAE 而不是栈溢出打穿桥连接`() {
        // 解析是自递归：8 MiB 帧能装四百万层 `[`，SOE 不是 Exception，路由层折叠接不住。
        val tooDeep = "[".repeat(600) + "]".repeat(600)
        assertThrows(IllegalArgumentException::class.java) { DomainJson.decode(tooDeep) }
        val ok = "[".repeat(500) + "1" + "]".repeat(500)
        assertTrue(DomainJson.decode(ok) is DomainJson.Value.Arr, "512 层以内的正常嵌套照常解析")
    }

    @Test
    fun `encodeParsed 数字原文透传不 Double 化`() {
        val tree = DomainJson.decode("""{"a":1.50,"b":-9223372036854775808}""")
        assertEquals("""{"a":1.50,"b":-9223372036854775808}""", DomainJson.encodeParsed(tree))
        val m = DomainJson.decodeObject("""{"a":1.50}""")
        assertEquals("1.50", n(m.getValue("a")))
    }
}
