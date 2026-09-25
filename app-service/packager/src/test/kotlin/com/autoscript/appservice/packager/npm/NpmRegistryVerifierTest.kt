package com.autoscript.appservice.packager.npm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 多镜像交叉校验单测（§10.5-1）：一致才接受、不一致绝不接受、没验成不折成通过。
 *
 * 全部注入假 [NpmRegistryVerifier.RegistrySource]——**零网络**，可造「两个镜像声明
 * 不同」「副镜像宕」「版本只在一侧」这些网络上难复现的局面。反射取 source 只为
 * 断言「请求打到了哪里」——那正是这条链路的语义核心（第二意见不能自己跟自己比）。
 */
class NpmRegistryVerifierTest {

    private val I1 = "sha512-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    private val I2 = "sha512-BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB"

    /**
     * 造 packument。readme 里**故意**塞 `"integrity"` / `"dist-tags"` 字面量：
     * 这是给解析器的陷阱（朴素 indexOf 会撞进字符串内容），不是装饰。
     */
    private fun packument(latest: String?, versions: List<Pair<String, String>>): String {
        val dt = if (latest != null) "\"latest\":\"$latest\"," else ""
        // readme 里带**转义引号**的 "integrity"/"dist-tags"：合法 JSON、内容是字符串，
        // 但字面上正是解析器要找的键名——朴素 indexOf 会一头撞进去（本断言的靶心）。
        val readme = "avoid \\\"integrity\\\":\\\"sha512-FAKE\\\" traps and \\\"dist-tags\\\":\\\"fake\\\" inside readme"
        return "{\"name\":\"dayjs\",\"dist-tags\":{" + dt + "\"other\":\"1.0.0\"}," +
            "\"readme\":\"" + readme + "\"," +
            "\"versions\":{" +
            versions.joinToString(",") { (v, i) ->
                "\"$v\":{\"version\":\"$v\"," +
                    "\"dist\":{\"tarball\":\"https://registry.example/dayjs/-/dayjs-$v.tgz\",\"integrity\":\"$i\"}}"
            } +
            "}}"
    }

    /** 假源：按 host 取表；键为 escapedName。 */
    private class FakeSource(private val byHost: Map<String, Map<String, String>>) :
        NpmRegistryVerifier.RegistrySource {
        val requested = mutableListOf<String>()
        override fun packument(registryBase: String, escapedName: String): String? {
            requested += "$registryBase/$escapedName"
            val host = registryBase.removePrefix("https://").substringBefore('/')
            return byHost[host]?.get(escapedName)
        }
    }

    /** [p] = 首选的 packument、[s] = 第二意见的。出厂首选是官方（§18 第 7 项），
     *  于是 p 挂官方站、s 挂镜像站 —— 参数名跟着角色走，不跟着站名走。 */
    private fun verifier(p: String?, s: String?): NpmRegistryVerifier {
        val src = FakeSource(
            mapOf(
                "registry.npmjs.org" to (if (p != null) mapOf("dayjs" to p) else emptyMap()),
                "registry.npmmirror.com" to (if (s != null) mapOf("dayjs" to s) else emptyMap()),
            ),
        )
        return NpmRegistryVerifier(source = src)
    }

    private fun sourceOf(v: NpmRegistryVerifier): FakeSource =
        v.javaClass.getDeclaredField("source").apply { isAccessible = true }.get(v) as FakeSource

    @Test
    fun `一致 → Agreed（版本、摘要、tarball 都要，并如实标记以 latest 复核）`() {
        val r = verifier(packument("1.11.23", listOf("1.11.23" to I1)), packument("1.11.23", listOf("1.11.23" to I1)))
            .verify("dayjs", null, null) as NpmRegistryVerifier.Verdict.Agreed
        assertEquals("1.11.23", r.version)
        assertEquals(I1, r.integrity)
        assertEquals("https://registry.example/dayjs/-/dayjs-1.11.23.tgz", r.tarball)
        assertTrue(r.viaLatestTag, "未指定版本 = 按 latest 复核，须如实标记（漂移面）")
    }

    @Test
    fun `精确版本命中时 viaLatestTag 为 false`() {
        val p = packument("9.9.9", listOf("1.11.23" to I1))
        val r = verifier(p, p).verify("dayjs", "1.11.23", null) as NpmRegistryVerifier.Verdict.Agreed
        assertEquals("1.11.23", r.version)
        assertTrue(!r.viaLatestTag, "精确复核不背漂移标记")
    }

    @Test
    fun `同一版本 integrity 不一致 → Disagreed（绝不折成通过）`() {
        val p = packument("1.11.23", listOf("1.11.23" to I1))
        val s = packument("1.11.23", listOf("1.11.23" to I2))
        val r = verifier(p, s).verify("dayjs", "1.11.23", null)
        assertTrue(r is NpmRegistryVerifier.Verdict.Disagreed, "声明不一致必须拒：$r")
        assertTrue((r as NpmRegistryVerifier.Verdict.Disagreed).reason.contains("integrity"))
    }

    @Test
    fun `latest 版本漂移 → Disagreed（两个最新不是同一个就不算数）`() {
        val p = packument("1.11.23", listOf("1.11.23" to I1))
        val s = packument("1.12.0", listOf("1.12.0" to I2))
        val r = verifier(p, s).verify("dayjs", null, null)
        assertTrue(r is NpmRegistryVerifier.Verdict.Disagreed, "漂移即分歧：$r")
        assertTrue((r as NpmRegistryVerifier.Verdict.Disagreed).reason.contains("漂移"))
    }

    @Test
    fun `副镜像不可达 → Unverifiable（不静默通过）`() {
        val r = verifier(packument("1.11.23", listOf("1.11.23" to I1)), null).verify("dayjs", null, null)
        assertTrue(r is NpmRegistryVerifier.Verdict.Unverifiable, "第二意见不在场不能算一致：$r")
    }

    @Test
    fun `主镜像不可达 → Unverifiable`() {
        val r = verifier(null, packument("1.11.23", listOf("1.11.23" to I1))).verify("dayjs", null, null)
        assertTrue(r is NpmRegistryVerifier.Verdict.Unverifiable, "$r")
    }

    @Test
    fun `副镜像缺该版本 → Unverifiable（同步窗口期不当分歧也不放行）`() {
        val p = packument("1.11.23", listOf("1.11.23" to I1))
        val s = packument("1.11.22", listOf("1.11.22" to I2))
        val r = verifier(p, s).verify("dayjs", "1.11.23", null)
        assertTrue(r is NpmRegistryVerifier.Verdict.Unverifiable, "只在一侧有的版本：$r")
    }

    @Test
    fun `缺 dist-integrity → Unverifiable（无交叉校验锚点）`() {
        val noIntegrity = """{"dist-tags":{"latest":"1.0.0"},"versions":{"1.0.0":{"version":"1.0.0","dist":{"tarball":"https://x/y.tgz"}}}}"""
        val r = verifier(noIntegrity, noIntegrity).verify("dayjs", "1.0.0", null)
        assertTrue(r is NpmRegistryVerifier.Verdict.Unverifiable, "无锚点不得算通过：$r")
        // 解析层能给出版本/URL（它确实在 packument 里），锚点缺失由 verify 折叠——故
        // parsePackument 此时是「解析成功、integrity=null」，不是解析失败。
        val parsed = NpmRegistryVerifier.parsePackument(noIntegrity, "1.0.0")
        assertNotNull(parsed, "有 tarball 无 integrity 的包仍可解析：$parsed")
        assertNull(parsed!!.integrity, "锚点缺失要如实反映在字段上（不是把整条吞掉）")
    }

    @Test
    fun `两个注册表都被打到（不许自己跟自己比，子路径与尾斜杠都要）`() {
        val v = verifier(packument("1.11.23", listOf("1.11.23" to I1)), packument("1.11.23", listOf("1.11.23" to I1)))
        v.verify("dayjs", "1.11.23", primary = "https://harbor.example.com/registry/")
        val req = sourceOf(v).requested
        assertTrue(req.contains("https://harbor.example.com/registry/dayjs"), "首选须连调用方给的：$req")
        assertTrue(req.contains("https://registry.npmjs.org/dayjs"), "首选是别家 → 第二意见由官方来比（不同站）：$req")
    }

    @Test
    fun `首选被调用方改成镜像 → 第二意见自动换官方（不许自比）`() {
        // 构造期缺省是官方，若第二意见在构造期就定死成镜像，这一步会变成镜像跟镜像比。
        val v = verifier(packument("1.11.23", listOf("1.11.23" to I1)), packument("1.11.23", listOf("1.11.23" to I1)))
        val r = v.verify("dayjs", "1.11.23", primary = NpmRegistryVerifier.MIRROR)
        assertTrue(r is NpmRegistryVerifier.Verdict.Agreed, "两侧都取到同一 integrity 才算过：$r")
        val req = sourceOf(v).requested
        assertTrue(req.contains("https://registry.npmmirror.com/dayjs"), "首选用调用方给的镜像：$req")
        assertTrue(req.contains("https://registry.npmjs.org/dayjs"), "第二意见自动换官方（两家不同站）：$req")
    }

    @Test
    fun `作用域包名走百分号转义（与 npm npa escapedName 同形）`() {
        val v = verifier(packument("1.0.0", listOf("1.0.0" to I1)), packument("1.0.0", listOf("1.0.0" to I1)))
        v.verify("@types/node", "1.0.0", null)
        assertTrue(sourceOf(v).requested.contains("https://registry.npmjs.org/@types%2fnode"))
    }

    @Test
    fun `非法包名（路径与查询注入）一律拒，请求一个都不发`() {
        val v = verifier(packument("1.0.0", listOf("1.0.0" to I1)), packument("1.0.0", listOf("1.0.0" to I1)))
        for (bad in listOf("../etc/passwd", "a/../../b", "https://evil.example/x", "pkg?x=1", "", "   ", "pkg/name/extra")) {
            assertThrows(IllegalArgumentException::class.java) { v.verify(bad, "1.0.0", null) }
        }
        assertTrue(sourceOf(v).requested.isEmpty(), "非法输入连请求都不该发出：${sourceOf(v).requested}")
    }

    // ═══ 解析器：字符串里的同名字段不许骗过它 ═══

    @Test
    fun `readme 里伪 integrity 不影响解析（词法级跳串，不是 indexOf）`() {
        val body = packument("1.0.0", listOf("1.0.0" to I1))
        val r = NpmRegistryVerifier.parsePackument(body, "1.0.0")
        assertNotNull(r)
        assertEquals(I1, r!!.integrity, "字符串内容里的 integrity 不许被当成真字段：$r")
        assertEquals("https://registry.example/dayjs/-/dayjs-1.0.0.tgz", r.tarball)
    }

    @Test
    fun `非 dist 对象的 integrity 不算`() {
        val body = """{"dist-tags":{"latest":"1.0.0"},"versions":{"1.0.0":{"integrity":"$I2","dist":{"integrity":"$I1","tarball":"https://x/y.tgz"}}}}"""
        val r = NpmRegistryVerifier.parsePackument(body, "1.0.0")
        assertNotNull(r)
        assertEquals(I1, r!!.integrity, "只认 dist 里的：$r")
    }

    @Test
    fun `畸形 packument → null（不可校验 = Unverifiable，不猜）`() {
        val bads = listOf(
            "", "not json", "{}",
            """{"dist-tags":{}}""",
            """{"versions":{}}""",
            """{"dist-tags":{"latest":"1.0.0"}}""",
            """{"dist-tags":{"latest":"1.0.0"},"versions":{"1.0.0":{""",
        )
        for (bad in bads) {
            assertNull(NpmRegistryVerifier.parsePackument(bad, null), "不可解析不得产出结果：$bad")
        }
    }

    @Test
    fun `转义键名解码后仍可匹配`() {
        assertEquals("@types%2fnode", NpmRegistryVerifier.escapeName("@types/node"))
        assertEquals("dayjs", NpmRegistryVerifier.escapeName("  dayjs  "))
    }

    @Test
    fun `范围请求按 latest 且标记 viaLatestTag`() {
        val p = packument("1.11.23", listOf("1.11.23" to I1))
        val r = verifier(p, p).verify("dayjs", "^1.0.0", null) as NpmRegistryVerifier.Verdict.Agreed
        assertTrue(r.viaLatestTag)
        assertEquals("1.11.23", r.version)
    }
}
