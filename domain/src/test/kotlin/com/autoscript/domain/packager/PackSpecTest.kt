package com.autoscript.domain.packager

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PackSpecTest {

    @Test
    fun `ignore 规则子集语义`() {
        val rules = listOf("", "# 注释", "node_modules/", "*.log", "dist/bundle.tmp", "src/*.gen.js")
        assertTrue(BuildIgnore.matches("node_modules/axios/index.js", rules))
        assertTrue(BuildIgnore.matches("node_modules", rules))
        assertTrue(BuildIgnore.matches("a.log", rules))
        assertFalse(BuildIgnore.matches("a/b.log", rules), "* 不跨段")
        assertTrue(BuildIgnore.matches("dist/bundle.tmp", rules))
        assertFalse(BuildIgnore.matches("dist/other.tmp", rules))
        assertTrue(BuildIgnore.matches("src/a.gen.js", rules))
        assertFalse(BuildIgnore.matches("src/sub/a.gen.js", rules), "段数不同不匹配")
        assertFalse(BuildIgnore.matches("main.js", rules))
        assertFalse(BuildIgnore.matches("", rules))
    }

    @Test
    fun `manifest 摘要稳定且可校验`() {
        val spec = PackSpec(projectId = "demo", appName = "Demo")
        val assets = listOf(
            AssetEntry("b.js", "aa", 2),
            AssetEntry("a.js", "bb", 1),
        )
        val m1 = PackManifests.build(spec, assets)
        val m2 = PackManifests.build(spec, assets.reversed())
        assertEquals(m1.digest, m2.digest, "输入顺序不影响摘要")
        assertEquals(listOf("a.js", "b.js"), m1.assets.map { it.relPath })
        assertTrue(PackManifests.verify(m1))
        assertFalse(PackManifests.verify(m1.copy(digest = "0".repeat(64))))
    }

    @Test
    fun `spec 非法入参拒绝`() {
        assertThrows<IllegalArgumentException> { PackSpec("", "A") }
        assertThrows<IllegalArgumentException> { PackSpec("p", "") }
        assertThrows<IllegalArgumentException> { PackSpec("p", "A", versionCode = 0) }
        assertThrows<IllegalArgumentException> { PackSpec("p", "A", entryScript = "  ") }
    }
}
