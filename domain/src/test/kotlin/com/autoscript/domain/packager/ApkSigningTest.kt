package com.autoscript.domain.packager

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ApkSigningTest {

    private fun manifest() = PackManifests.build(
        PackSpec(projectId = "p", appName = "P"),
        listOf(AssetEntry("main.js", "aa", 5)),
    )

    private fun plan(): TemplateApkPlan {
        val m = manifest()
        return TemplateApkPlans.build(ApkIdentity("com.example.demo", "Demo"), TemplateInfo("24.21.0"), m)
    }

    @Test
    fun `组装与复验闭环`() {
        val m = manifest()
        val p = plan()
        val req = SignPlans.build(p, m, "apk".repeat(20), SignSpec(SigningKey.DebugEphemeral))
        assertTrue(SignPlans.verify(req, p, m))
    }

    @Test
    fun `清单错配拒绝签名`() {
        val p = plan()
        val other = PackManifests.build(
            PackSpec(projectId = "p", appName = "P"),
            listOf(AssetEntry("main.js", "bb", 5)),
        )
        assertThrows<IllegalArgumentException> {
            SignPlans.build(p, other, "apk".repeat(20), SignSpec(SigningKey.DebugEphemeral))
        }
        // 复验侧同样拒绝：篡改后的请求对不上计划
        val m = manifest()
        val req = SignPlans.build(p, m, "apk".repeat(20), SignSpec(SigningKey.DebugEphemeral))
        assertFalse(SignPlans.verify(req, p, other))
    }

    @Test
    fun `规格非法拒绝`() {
        assertThrows<IllegalArgumentException> { SignSpec(SigningKey.DebugEphemeral, false, false) }
        assertThrows<IllegalArgumentException> { SigningKey.ReleaseKeystore("", "a") }
        assertThrows<IllegalArgumentException> { SigningKey.ReleaseKeystore("/k.jks", "  ") }
        assertThrows<IllegalArgumentException> {
            SignPlans.build(plan(), manifest(), "  ", SignSpec(SigningKey.DebugEphemeral))
        }
    }

    @Test
    fun `apksigner 参数表形状`() {
        val req = SignPlans.build(
            plan(), manifest(), "apk".repeat(20),
            SignSpec(SigningKey.ReleaseKeystore("/k/release.jks", "rel")),
        )
        val args = ApkSignerArgs.build(req, "/out/unsigned.apk", "/out/signed.apk", "/k/release.jks")
        assertEquals(
            listOf(
                "sign",
                "--ks", "/k/release.jks",
                "--ks-pass", "env:AUTOSCRIPT_KS_PASS",
                "--ks-key-alias", "rel",
                "--key-pass", "env:AUTOSCRIPT_KEY_PASS",
                "--v1-signing-enabled", "true",
                "--v2-signing-enabled", "true",
                "--out", "/out/signed.apk",
                "/out/unsigned.apk",
            ),
            args,
        )
        assertTrue(args.none { it.contains("password", ignoreCase = true) }, "口令不得进参数表")
    }

    @Test
    fun `调试密钥参数表无key口令项`() {
        val req = SignPlans.build(
            plan(), manifest(), "apk".repeat(20), SignSpec(SigningKey.DebugEphemeral),
        )
        val args = ApkSignerArgs.build(req, "/out/u.apk", "/out/s.apk", "/k/debug.jks")
        assertTrue(args.none { it.startsWith("--key-pass") })
        assertTrue("androiddebugkey" in args)
    }
}
