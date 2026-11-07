package com.autoscript.domain.bridge

import com.autoscript.domain.json.DomainJson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * `Int` 域取值的**越界即拒**口径（判据在 [DomainJson.reqInt]/[DomainJson.optInt]，
 * request 挂点 [requiredInt]/[optInt] 是同一份实现的转发 —— 两侧各钉一遍不是重复，
 * 是钉「转发没退化成第二套判据」）。
 *
 * 这一条此前是**缺口**：wire 上所有 `Int` 域的量（`batch`/`max`/`width`/`height`/`depth`）
 * 都是 `optLong(...).toInt()` 收的，而 Kotlin 的 `Long.toInt()` 是静默的低 32 位截断 ——
 * `4294967297` 变 `1`、`4294967296` 变 `0`、`2147483648` 变 `-2147483648`。请求被当成
 * **另一个数**执行，而截断后的值多半仍是合法正数，于是没有任何一条错会报出来：
 * 调用方拿到的回执与它给的参数毫无关系，且无从发现。
 *
 * 仓里同一口径的两处手写先例（`SettingsNamespaceHandler.putInt`、
 * `NotificationNamespaceHandler.idOf`）说明这条判据是认下的，只是没被收成可复用的落点。
 * 各 handler 的 wire 路径由它们自己的测试各钉一条（本类只钉落点本身）。
 */
class DecodeIntRangeTest {

    private fun fields(json: String): Map<String, DomainJson.Value> = DomainJson.decodeObject(json)

    private fun req(json: String?): BridgeRequest = BridgeRequest(1, "ns", "method", json, 5_000)

    /** 截断后的值 —— 断言里点名它，是为了让「静默截断」这个具体形态在失败信息里可读。 */
    private fun truncated(raw: Long): Int = raw.toInt()

    // ── 域内：照常收，边界含两端 ──────────────────────────────────────

    @Test
    fun `域内取值照常——Int 两个边界与零负都收`() {
        val o = fields("""{"n":128,"lo":${Int.MIN_VALUE},"hi":${Int.MAX_VALUE},"zero":0}""")
        assertEquals(128, DomainJson.reqInt(o, "n"))
        assertEquals(Int.MIN_VALUE, DomainJson.reqInt(o, "lo"), "下界含")
        assertEquals(Int.MAX_VALUE, DomainJson.reqInt(o, "hi"), "上界含")
        assertEquals(0, DomainJson.reqInt(o, "zero"))
        // request 挂点是同一份实现（不是第二套判据）
        assertEquals(128, req("""{"n":128}""").requiredInt(fields("""{"n":128}"""), "n"))
        assertEquals(Int.MAX_VALUE, req("""{"n":${Int.MAX_VALUE}}""")
            .optInt(fields("""{"n":${Int.MAX_VALUE}}"""), "n"))
        Unit
    }

    // ── 越界：拒，不截断 ─────────────────────────────────────────────

    @Test
    fun `超出 Int 上界即拒——绝不静默截断成另一个数`() {
        // 4294967297 = 2^32+1：截断成 1（一个完全合法的 batch）。
        // 4294967296 = 2^32：截断成 0。2147483648 = 上界+1：截断成 Int.MIN_VALUE。
        for (raw in listOf(4294967297L, 4294967296L, 2147483648L, Long.MAX_VALUE)) {
            val o = fields("""{"n":$raw}""")
            val e = assertThrows<IllegalArgumentException> { DomainJson.reqInt(o, "n") }
            assertTrue(e.message!!.contains("Int"), "话术须点名 Int 域: ${e.message}")
            assertTrue(e.message!!.contains("$raw"), "话术须带原值（便于对账）: ${e.message}")
            assertThrows<IllegalArgumentException>("optInt 同一口径: $raw") { DomainJson.optInt(o, "n") }
            // 钉住「老路为什么一声不响」：截断确实换了值，而换出来的那个多半仍合法。
            assertNotEquals(raw, truncated(raw).toLong(), "老路的截断必须真改了值: $raw")
        }
        Unit
    }

    @Test
    fun `超出 Int 下界即拒——负侧同样不截断`() {
        for (raw in listOf(-2147483649L, -4294967295L, Long.MIN_VALUE)) {
            val o = fields("""{"n":$raw}""")
            assertThrows<IllegalArgumentException>("reqInt: $raw") { DomainJson.reqInt(o, "n") }
            assertThrows<IllegalArgumentException>("optInt: $raw") { DomainJson.optInt(o, "n") }
        }
        Unit
    }

    @Test
    fun `4294967297 的老路正是 1——这就是被吞掉的那条错`() {
        // 本条不改被测代码，只把「截断有多安静」写死在测试里：
        // 老路 `optLong(...).toInt()` 把 4294967297 收成 1，一个合法 batch。
        assertEquals(1, truncated(4294967297L))
        assertEquals(0, truncated(4294967296L))
        assertEquals(Int.MIN_VALUE, truncated(2147483648L))
        assertEquals(5000, truncated(4294972296L), "笔误多打一位 → 一个看着合理的 5000")
        // 新路：同一个值必须是错，不是 1。
        val o = fields("""{"max":4294967297}""")
        assertThrows<IllegalArgumentException> { DomainJson.reqInt(o, "max") }
        Unit
    }

    // ── 其它非法形态：与 optLong/requiredLong 同一条折叠路 ───────────────

    @Test
    fun `非整数、类型错、缺键、显式 null 的口径与 Long 族逐条一致`() {
        assertThrows<IllegalArgumentException>("1.5 不是整数") {
            DomainJson.reqInt(fields("""{"n":1.5}"""), "n")
        }
        assertThrows<IllegalArgumentException>("字符串不是数字") {
            DomainJson.reqInt(fields("""{"n":"128"}"""), "n")
        }
        assertThrows<IllegalArgumentException>("缺键即缺字段") {
            DomainJson.reqInt(fields("""{}"""), "n")
        }
        assertThrows<IllegalArgumentException>("显式 null 对 req 是参数错") {
            DomainJson.reqInt(fields("""{"n":null}"""), "n")
        }
        // opt 对缺键与 null 宽容（与 optLong 同一条），但在场即按域判
        assertNull(DomainJson.optInt(fields("""{}"""), "n"))
        assertNull(DomainJson.optInt(fields("""{"n":null}"""), "n"))
        assertThrows<IllegalArgumentException> { DomainJson.optInt(fields("""{"n":"x"}"""), "n") }
        assertThrows<IllegalArgumentException> { DomainJson.optInt(fields("""{"n":1.5}"""), "n") }
        Unit
    }

    @Test
    fun `Long 域不受影响——超 Int 的 long 值照旧收（回归钉）`() {
        // 这条防的是「顺手把 optLong 也收紧」：sinceSeq/timeoutMillis/offset 这类量
        // 本来就是 Long 域，收紧它们会把合法请求挡成参数错。
        val o = fields("""{"sinceSeq":4294967297,"timeoutMillis":9223372036854775807}""")
        assertEquals(4294967297L, DomainJson.optLong(o, "sinceSeq"))
        assertEquals(Long.MAX_VALUE, DomainJson.optLong(o, "timeoutMillis"))
        // request 挂点上的 Long 族同一口径（越界的 Long 仍是 Long 域的合法值）
        assertEquals(4294967297L, req(null).optLong(o, "sinceSeq"))
        assertEquals(Long.MAX_VALUE, req(null).requiredLong(o, "timeoutMillis"))
        Unit
    }
}
