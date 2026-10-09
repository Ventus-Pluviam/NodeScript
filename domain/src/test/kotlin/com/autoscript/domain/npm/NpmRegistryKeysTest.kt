package com.autoscript.domain.npm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [NpmRegistryKeys] 的判据（§10.2 / §10.9 第 8 条）。
 *
 * 两条主线，各自都有反向变异能红的用例：
 * - **拒收面**：非 https / 无 host / 形态非法 / 含空白各自命中，且**原文点名那个串**；
 * - **误拒面**：带子路径、尾斜杠、端口、query 的 https 地址一个都不许被拒 ——
 *   误拒比漏拒更难查（用户看不出为什么一个正常的自建 registry 存不进去）。
 */
class NpmRegistryKeysTest {

    @Test
    fun `明文与其它 scheme 被拒且点名原文`() {
        for (bad in listOf("http://registry.npmjs.org", "ftp://x.example.com", "registry.npmjs.org")) {
            val why = NpmRegistryKeys.reject(bad)
            assertTrue(why != null && bad in why, "拒收原文要点名输入：$why")
            assertTrue("https" in (why ?: ""), "还要说清为什么：$why")
        }
    }

    @Test
    fun `解析不出主机名被拒`() {
        val why = NpmRegistryKeys.reject("https://")
        assertTrue(why != null && "https://" in why, "点名原文：$why")
    }

    @Test
    fun `串中间的空白被拒——地址里不该有空格`() {
        for (bad in listOf("https://a b.example.com", "https://x.example.com\t/y", "https://x.example.com/a b")) {
            assertTrue(NpmRegistryKeys.reject(bad) != null, "中间含空白应被拒：$bad")
        }
    }

    @Test
    fun `首尾空白是 trim 不是拒——粘贴带换行是常事`() {
        // 「trim」与「拒」的边界要钉住：粘贴一行末尾带 \n 是用户最常见的输入形态，
        // 拒掉它是把一次正常粘贴说成错误；而中间有空格才是真打错了。
        for (ok in listOf("  https://registry.npmjs.org  ", "https://registry.npmjs.org\n", "\thttps://registry.npmjs.org\t")) {
            assertNull(NpmRegistryKeys.reject(ok), "首尾空白应被 trim 掉而不是拒：${ok.replace("\n", "\\n").replace("\t", "\\t")}")
        }
    }

    @Test
    fun `空白输入是恢复出厂而不是错误`() {
        // 「清空输入框 = 恢复出厂」这条路必须在判据这一层就放行，
        // 否则界面只能靠「先判空再判合法性」把同一个语义抄两遍。
        for (blank in listOf(null, "", "   ", "\t")) assertNull(NpmRegistryKeys.reject(blank))
    }

    @Test
    fun `常见合法地址不被误拒——子路径 尾斜杠 端口 query 都要放行`() {
        for (ok in listOf(
            "https://registry.npmjs.org",
            "https://registry.npmmirror.com/",
            "https://harbor.example.com/registry/",
            "https://nexus.corp:8443/repository/npm-group/",
            "https://gw.example.com/npm?token=abc",
            "HTTPS://Registry.NPMJS.ORG",
        )) {
            assertNull(NpmRegistryKeys.reject(ok), "合法地址被误拒：$ok")
        }
    }

    @Test
    fun `规整化去尾斜杠但保留子路径`() {
        assertEquals("https://harbor.example.com/registry", NpmRegistryKeys.canonicalize("https://harbor.example.com/registry/"))
        assertEquals("https://registry.npmjs.org", NpmRegistryKeys.canonicalize("  https://registry.npmjs.org/  "))
        assertEquals("https://x.example.com", NpmRegistryKeys.canonicalize("https://x.example.com"))
    }

    @Test
    fun `规整化对非 https 与无主机回 null`() {
        assertNull(NpmRegistryKeys.canonicalize("http://registry.npmjs.org"))
        assertNull(NpmRegistryKeys.canonicalize("https://"))
        assertNull(NpmRegistryKeys.canonicalize("不是地址"))
    }

    @Test
    fun `出厂常量就是这两家`() {
        assertEquals("https://registry.npmjs.org", NpmRegistryKeys.OFFICIAL)
        assertEquals("https://registry.npmmirror.com", NpmRegistryKeys.MIRROR)
        assertNull(NpmRegistryKeys.reject(NpmRegistryKeys.OFFICIAL))
        assertNull(NpmRegistryKeys.reject(NpmRegistryKeys.MIRROR))
    }
}
