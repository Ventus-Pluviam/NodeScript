package com.autoscript.appservice.packager

import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.packager.ApkIdentity
import com.autoscript.domain.packager.ApkSignerArgs
import com.autoscript.domain.packager.AssetEntry
import com.autoscript.domain.packager.PackManifest
import com.autoscript.domain.packager.PackManifests
import com.autoscript.domain.packager.PackSpec
import com.autoscript.domain.packager.SignPlans
import com.autoscript.domain.packager.SignSpec
import com.autoscript.domain.packager.SigningKey
import com.autoscript.domain.packager.TemplateApkPlan
import com.autoscript.domain.packager.TemplateApkPlans
import com.autoscript.domain.packager.TemplateInfo
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * apksigner **起进程**那一段的闭环：参数表 → 环境变量 → 退出码 → 产物存在性。
 * 用记录型 [ProcessLauncher] 与假可执行体两条路各测一遍，
 * 于是这条链路在纯 JVM 上就验完，不依赖本机/CI 是否装了真 apksigner。
 */
class ApkSignerRunnerTest {

    @TempDir
    lateinit var tmp: Path

    private fun manifest(): PackManifest = PackManifests.build(
        PackSpec(projectId = "p", appName = "P"),
        listOf(AssetEntry("main.js", "aa", 5)),
    )

    private fun plan(): TemplateApkPlan {
        val m = manifest()
        return TemplateApkPlans.build(ApkIdentity("com.example.demo", "Demo"), TemplateInfo("24.21.0"), m)
    }

    private fun releaseRequest() = SignPlans.build(
        plan(), manifest(), "apk".repeat(20),
        SignSpec(SigningKey.ReleaseKeystore("/k/release.jks", "rel")),
    )

    @Test
    fun `记录型 launcher 收到的 argv 与领域参数表逐字一致`() {
        val seen = mutableListOf<List<String>>()
        var seenEnv: Map<String, String> = emptyMap()
        val launcher = ProcessLauncher { cmd, env ->
            seen.add(cmd); seenEnv = env
            // 产物必须落在 argv 的 --out 路径上：runner 校验的就是那一个文件。
            Files.write(outPath(cmd), byteArrayOf(1))
            ProcessResult(0, "Signed")
        }
        val unsigned = Files.write(tmp.resolve("u.apk"), byteArrayOf(1))
        val signed = tmp.resolve("s.apk")

        ApkSignerRunner(listOf("/tools/apksigner"), launcher)
            .sign(releaseRequest(), unsigned, signed, "/k/release.jks", "ks-secret", "key-secret")

        assertEquals(1, seen.size, "应恰好起一次进程")
        assertEquals(listOf("/tools/apksigner"), seen[0].subList(0, 1))
        assertEquals(
            ApkSignerArgs.build(releaseRequest(), unsigned.toString(), signed.toString(), "/k/release.jks"),
            seen[0].subList(1, seen[0].size),
            "argv 必须与领域层参数表逐字一致（防两侧漂移）",
        )
        assertEquals("ks-secret", seenEnv[ApkSignerArgs.KS_PASS_ENV], "口令必须经环境变量注入")
        assertEquals("key-secret", seenEnv[ApkSignerArgs.KEY_PASS_ENV])
        // 口令绝不能出现在 argv（ps 可见）
        assertTrue(seen[0].none { "ks-secret" in it || "key-secret" in it }, "口令不得进参数表")
    }

    @Test
    fun `非 0 退出码如实失败并带上 apksigner 输出`() {
        val launcher = ProcessLauncher { _, _ -> ProcessResult(1, "Failed to load keystore") }
        val unsigned = Files.write(tmp.resolve("u.apk"), byteArrayOf(1))
        val e = runCatching {
            ApkSignerRunner(listOf("apksigner"), launcher)
                .sign(releaseRequest(), unsigned, tmp.resolve("s.apk"), "/k.jks", "a", "b")
        }.exceptionOrNull()
        assertInstanceOf(AutojsException::class.java, e)
        e as AutojsException
        assertEquals(ErrorCode.ERR_IO, e.error)
        assertTrue("Failed to load keystore" in e.message!!, "失败详情必须带 apksigner 原文")
        assertTrue("1" in e.message!!, "退出码要进错误信息")
    }

    @Test
    fun `报成功但没产出签名包也算失败`() {
        val launcher = ProcessLauncher { _, _ -> ProcessResult(0, "ok") }
        val unsigned = Files.write(tmp.resolve("u.apk"), byteArrayOf(1))
        val e = runCatching {
            ApkSignerRunner(listOf("apksigner"), launcher)
                .sign(releaseRequest(), unsigned, tmp.resolve("missing.apk"), "/k.jks", "a", "b")
        }.exceptionOrNull()
        assertInstanceOf(AutojsException::class.java, e)
        assertEquals(ErrorCode.ERR_IO, (e as AutojsException).error)
        assertTrue("没产出签名包" in e.message!!)
    }

    @Test
    fun `空命令前缀在构造期就拒绝`() {
        assertThrowsIllegal { ApkSignerRunner(emptyList()) }
    }

    @Test
    fun `真起进程：假可执行体走通全链路`() {
        // 与记录型互补：这条验证 ProcessBuilderLauncher 真的拼命令、注入 env、收输出、读退出码。
        val script = tmp.resolve("fake-apksigner.sh")
        writeString(
            script,
            """
            #!/bin/sh
            # 记录收到的 argv 与口令环境变量，供断言；再复制输入为输出冒充签名产物。
            printf '%s\n' "$*" > "${'$'}{FAKE_LOG:?}"
            printf '%s|%s\n' "${'$'}AUTOSCRIPT_KS_PASS" "${'$'}AUTOSCRIPT_KEY_PASS" > "${'$'}{FAKE_ENV_LOG:?}"
            out=""
            prev=""
            for a in "$@"; do
              if [ "${'$'}prev" = "--out" ]; then out="${'$'}a"; fi
              prev="${'$'}a"
            done
            cp "${'$'}(printf '%s\n' "$@" | tail -1)" "${'$'}out"
            exit 0
            """.trimIndent(),
        )
        script.toFile().setExecutable(true)
        val log = tmp.resolve("argv.log")
        val envLog = tmp.resolve("env.log")

        val unsigned = Files.write(tmp.resolve("u.apk"), "unsigned-bytes".toByteArray())
        val signed = tmp.resolve("s.apk")

        ApkSignerRunner(listOf(script.toString()), ProcessBuilderLauncher(), mapOf("FAKE_LOG" to log.toString(), "FAKE_ENV_LOG" to envLog.toString()))
            .sign(releaseRequest(), unsigned, signed, "/k/release.jks", "ks-pass-value", "key-pass-value")

        assertTrue(Files.isRegularFile(signed), "假 apksigner 产出了签名包")
        assertEquals("unsigned-bytes", readString(signed), "假体把输入原样抄成了输出")
        val argv = readString(log)
        assertTrue(argv.contains("--ks"), "应带上 --ks：$argv")
        assertTrue(argv.contains("env:${ApkSignerArgs.KS_PASS_ENV}"), "口令应以 env: 引用：$argv")
        assertFalse(argv.contains("ks-pass-value"), "口令不得出现在 argv：$argv")
        assertEquals("ks-pass-value|key-pass-value", readString(envLog).trim(), "口令必须经环境变量送达")
    }

    @Test
    fun `真起进程：非 0 退出码的假体照样失败`() {
        val script = tmp.resolve("failing-apksigner.sh")
        writeString(script, "#!/bin/sh\necho 'boom: bad keystore' >&2\nexit 3\n")
        script.toFile().setExecutable(true)
        val unsigned = Files.write(tmp.resolve("u2.apk"), byteArrayOf(1))
        val e = runCatching {
            ApkSignerRunner(listOf(script.toString()), ProcessBuilderLauncher())
                .sign(releaseRequest(), unsigned, tmp.resolve("s2.apk"), "/k.jks", "a", "b")
        }.exceptionOrNull()
        assertInstanceOf(AutojsException::class.java, e)
        assertTrue("boom: bad keystore" in (e as AutojsException).message!!, "stderr 必须被捕获进错误")
        assertTrue("3" in e.message!!)
    }

    /** 从 apksigner argv 里取 `--out` 的值 —— 记录型 launcher 按它落产物。 */
    private fun outPath(cmd: List<String>): Path {
        val i = cmd.indexOf("--out")
        assertTrue(i >= 0 && i + 1 < cmd.size, "argv 必须带 --out：$cmd")
        return Path.of(cmd[i + 1])
    }

    private fun assertThrowsIllegal(block: () -> Unit) {
        val e = runCatching(block).exceptionOrNull()
        assertInstanceOf(IllegalArgumentException::class.java, e)
    }

    @Test
    fun `调试密钥不带 --key-pass（口令仍走 ks 环境变量）`() {
        val req = SignPlans.build(plan(), manifest(), "apk".repeat(20), SignSpec(SigningKey.DebugEphemeral))
        var seen: List<String> = emptyList()
        val launcher = ProcessLauncher { cmd, _ ->
            seen = cmd; Files.write(outPath(cmd), byteArrayOf(1)); ProcessResult(0, "")
        }
        val unsigned = Files.write(tmp.resolve("du.apk"), byteArrayOf(1))
        ApkSignerRunner(listOf("apksigner"), launcher)
            .sign(req, unsigned, tmp.resolve("ds.apk"), "/k/debug.jks", "android", "unused")
        assertFalse("--key-pass" in seen, "调试密钥不该带 --key-pass")
        assertTrue("androiddebugkey" in seen)
        assertArrayEquals(
            ApkSignerArgs.build(req, unsigned.toString(), tmp.resolve("ds.apk").toString(), "/k/debug.jks").toTypedArray(),
            seen.drop(1).toTypedArray(),
        )
    }
}
