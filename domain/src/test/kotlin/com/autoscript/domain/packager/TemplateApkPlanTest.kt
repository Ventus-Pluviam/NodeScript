package com.autoscript.domain.packager

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TemplateApkPlanTest {

    private fun manifest() = PackManifests.build(
        PackSpec(projectId = "p", appName = "P"),
        listOf(AssetEntry("main.js", "aa", 5)),
    )

    @Test
    fun `组装计划摘要稳定且可复验`() {
        val plan = TemplateApkPlans.build(
            ApkIdentity("com.example.demo", "Demo"),
            TemplateInfo("24.21.0"),
            manifest(),
        )
        assertTrue(TemplateApkPlans.verify(plan, manifest()))
        assertEquals(64, plan.planDigest.length)
    }

    @Test
    fun `清单被换掉则复验失败`() {
        val plan = TemplateApkPlans.build(
            ApkIdentity("com.example.demo", "Demo"),
            TemplateInfo("24.21.0"),
            manifest(),
        )
        val other = PackManifests.build(
            PackSpec(projectId = "p", appName = "P"),
            listOf(AssetEntry("main.js", "bb", 5)),
        )
        assertFalse(TemplateApkPlans.verify(plan, other), "拿 A 项目的清单装进 B 包必须拒绝")
    }

    @Test
    fun `篡改后的清单拒绝组装`() {
        val bad = manifest().copy(digest = "0".repeat(64))
        assertThrows<IllegalArgumentException> {
            TemplateApkPlans.build(ApkIdentity("com.example.demo", "Demo"), TemplateInfo("24.21.0"), bad)
        }
    }

    @Test
    fun `变体与引擎版本参与摘要`() {
        val m = manifest()
        val base = TemplateApkPlans.build(ApkIdentity("com.example.demo", "Demo"), TemplateInfo("24.21.0"), m)
        val offline = TemplateApkPlans.build(
            ApkIdentity("com.example.demo", "Demo"), TemplateInfo("24.21.0"), m, offlineVariant = true,
        )
        val newer = TemplateApkPlans.build(
            ApkIdentity("com.example.demo", "Demo"), TemplateInfo("25.0.0"), m,
        )
        assertNotEquals(base.planDigest, offline.planDigest)
        assertNotEquals(base.planDigest, newer.planDigest)
        assertTrue(TemplateApkPlans.verify(base, m))
        assertTrue(TemplateApkPlans.verify(offline, m))
    }

    @Test
    fun `包名非法拒绝`() {
        assertThrows<IllegalArgumentException> { ApkIdentity("single", "A") }
        assertThrows<IllegalArgumentException> { ApkIdentity("com.class.demo", "A") }
        assertThrows<IllegalArgumentException> { ApkIdentity("com.example.demo", "  ") }
        assertThrows<IllegalArgumentException> { ApkIdentity("com.example.demo", "A", versionCode = 0) }
        assertTrue(ApkIdentity.isValidPackageName("com.example.demo"))
    }
}
