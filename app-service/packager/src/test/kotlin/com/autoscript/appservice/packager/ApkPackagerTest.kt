package com.autoscript.appservice.packager

import com.autoscript.appservice.packager.axml.AxmlPatcher
import com.autoscript.appservice.packager.axml.ArscPatcher
import com.autoscript.appservice.packager.axml.FixtureAxml
import com.autoscript.appservice.packager.axml.ManifestAttrValue
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.packager.ApkIdentity
import com.autoscript.domain.packager.AssetEntry
import com.autoscript.domain.packager.PackSpec
import com.autoscript.domain.packager.SignSpec
import com.autoscript.domain.packager.SigningKey
import com.autoscript.domain.packager.TemplateApkPlans
import com.autoscript.domain.packager.TemplateInfo
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * 打包全链编排的闭环（§14 P0）：plan → prepare → 身份改写 → assets/project 注入 →
 * zipalign → apksigner。两个外进程都走注入的假 runner（记录 argv + 抄文件），
 * 因此顺序、argv、产物、两道复验全在纯 JVM 上可判 —— 不赌二进制在不在。
 */
class ApkPackagerTest {

    @TempDir
    lateinit var tmp: Path

    private val identity = ApkIdentity(
        packageName = "com.example.repacked",
        appLabel = "重打包应用",
        versionName = "3.2.1",
        versionCode = 321,
    )
    private val templateInfo = TemplateInfo("24.21.0")

    /** 迷你项目：入口 + 一个子目录文件（批量注入要走嵌套路径）。 */
    private fun project(): Path {
        val root = tmp.resolve("project")
        Files.createDirectories(root.resolve("lib"))
        Files.writeString(root.resolve("main.js"), "console.log('entry')")
        Files.writeString(root.resolve("lib/util.js"), "module.exports = 1")
        return root
    }

    private fun spec() = PackSpec(
        projectId = "demo",
        appName = "Demo",
        entryScript = "main.js",
        includeNodeModules = false,
    )

    private fun workDir(): Path = tmp.resolve("work")

    /** 记录型 zipalign：argv 留证 + 输入抄成输出。 */
    private class AlignProbe {
        val calls = mutableListOf<List<String>>()
        val runner = ZipAlignRunner(listOf("/tools/zipalign"), ProcessLauncher { cmd, _ ->
            calls += cmd
            Files.copy(
                Path.of(cmd[cmd.size - 2]),
                Path.of(cmd[cmd.size - 1]),
                StandardCopyOption.REPLACE_EXISTING,
            )
            ProcessResult(0, "")
        })
    }

    /** 记录型 apksigner：argv 留证 + 往 --out 落一个可辨识产物。 */
    private class SignProbe {
        val calls = mutableListOf<List<String>>()
        val runner = ApkSignerRunner(listOf("/tools/apksigner"), ProcessLauncher { cmd, _ ->
            calls += cmd
            val out = Path.of(cmd[cmd.indexOf("--out") + 1])
            Files.write(out, "signed-by-fake".toByteArray())
            ProcessResult(0, "Signed")
        })
    }

    private fun entry(apk: Path, name: String): ByteArray? =
        ZipFile(apk.toFile()).use { zf ->
            val e = zf.getEntry(name) ?: return@use null
            zf.getInputStream(e).readBytes()
        }

    private fun entryOrFail(apk: Path, name: String): ByteArray =
        entry(apk, name) ?: error("包内缺条目 $name")

    @Test
    fun `两段式打包——身份落 manifest、资产进包、对齐产物即交付物`() {
        val align = AlignProbe()
        val packager = ApkPackager(workDir(), FixtureAxml.templateApk(), align.runner)

        val planned = packager.plan(spec(), project(), identity, templateInfo)
        assertTrue(TemplateApkPlans.verify(planned.plan, planned.manifest), "plan 出场即自洽")

        val result = packager.pack(planned, project(), signing = null)

        assertNull(result.signed, "未请求签名就不产签名包")
        assertEquals(result.unsignedAligned, result.apk, "未签名时交付物 = 对齐后的 unsigned 包")
        assertTrue(Files.isRegularFile(result.unsignedAligned))

        // 身份三件套 + label 资源值都落到新身份（AXML/ARSC 真改写穿过整条链）
        val axml = AxmlPatcher.parse(entryOrFail(result.apk, "AndroidManifest.xml"))
        assertEquals(ManifestAttrValue.Text("com.example.repacked"), axml.readAttr("manifest", "package"))
        assertEquals(ManifestAttrValue.Text("3.2.1"), axml.readAttr("manifest", "versionName"))
        assertEquals(ManifestAttrValue.Number(321), axml.readAttr("manifest", "versionCode"))
        val arsc = ArscPatcher.parse(entryOrFail(result.apk, "resources.arsc"))
        assertEquals("重打包应用", arsc.readStringResource(0x7f010000))

        // 资产按 assets/project/<relPath> 落位（含嵌套路径），字节与项目现场一致
        assertArrayEquals(
            Files.readAllBytes(project().resolve("main.js")),
            entryOrFail(result.apk, "assets/project/main.js"),
        )
        assertArrayEquals(
            Files.readAllBytes(project().resolve("lib/util.js")),
            entryOrFail(result.apk, "assets/project/lib/util.js"),
        )

        // zipalign 真被调了一次，argv 形态正确（-f -p 4 in out）
        assertEquals(1, align.calls.size)
        assertEquals(listOf("-f", "-p", "4"), align.calls[0].subList(1, 4))

        // 凭据原样交还（两段式向导拿它展示/归档）
        assertEquals(planned.plan, result.plan)
        assertEquals(planned.manifest, result.manifest)
        assertTrue(TemplateApkPlans.verify(result.plan, result.manifest))
    }

    @Test
    fun `签名段——先对齐后签名，签名输入就是对齐输出`() {
        val align = AlignProbe()
        val sign = SignProbe()
        val packager = ApkPackager(workDir(), FixtureAxml.templateApk(), align.runner, sign.runner)

        val result = packager.pack(
            spec(), project(), identity, templateInfo,
            signing = ApkPackager.Signing(
                spec = SignSpec(SigningKey.ReleaseKeystore("/k/release.jks", "rel")),
                keystorePath = "/k/release.jks",
                ksPass = "ks-secret",
                keyPass = "key-secret",
            ),
        )

        assertEquals(1, align.calls.size, "对齐恰好一次")
        assertEquals(1, sign.calls.size, "签名恰好一次")
        assertEquals("signed-by-fake", Files.readString(result.signed!!))
        assertEquals(result.signed, result.apk, "签名后交付物 = 签名包")
        assertTrue(Files.isRegularFile(result.unsignedAligned), "中间产物对齐包仍在（可复查）")

        // 顺序铁律：apksigner 的位置参数（最后一项）= 对齐产物 —— 先对齐后签名
        val signArgv = sign.calls[0]
        assertEquals(result.unsignedAligned.toString(), signArgv.last(), "签名输入必须是对齐输出")
        assertTrue("env:AUTOSCRIPT_KS_PASS" in signArgv, "口令走 env 引用不进 argv")
        assertTrue(signArgv.none { "ks-secret" in it || "key-secret" in it }, "口令不得出现在 argv")
    }

    @Test
    fun `清单在两段之间被换——进场复验拒绝`() {
        val packager = ApkPackager(workDir(), FixtureAxml.templateApk(), AlignProbe().runner)
        val planned = packager.plan(spec(), project(), identity, templateInfo)
        val tampered = planned.copy(
            manifest = planned.manifest.copy(
                assets = planned.manifest.assets + AssetEntry("evil.js", "00".repeat(32), 1),
            ),
        )
        val e = runCatching { packager.pack(tampered, project()) }.exceptionOrNull()
        assertInstanceOf(AutojsException::class.java, e)
        assertEquals(ErrorCode.ERR_INVALID_PARAM, (e as AutojsException).error)
        assertTrue("对不上" in e.message!!)
    }

    @Test
    fun `计划后文件被改——逐文件哈希在动模板之前拒绝`() {
        val root = project()   // 只建一次：project() 会重写文件，调两次就把篡改复原了
        val packager = ApkPackager(workDir(), FixtureAxml.templateApk(), AlignProbe().runner)
        val planned = packager.plan(spec(), root, identity, templateInfo)
        Files.writeString(root.resolve("main.js"), "TAMPERED")
        val e = runCatching { packager.pack(planned, root) }.exceptionOrNull()
        assertInstanceOf(AutojsException::class.java, e)
        assertEquals(ErrorCode.ERR_INVALID_PARAM, (e as AutojsException).error)
        assertTrue("main.js" in e.message!!)
        assertTrue(!Files.exists(workDir().resolve("fixture-template.apk")), "拒绝必须发生在动模板之前")
    }

    @Test
    fun `计划后文件被删——明确 ERR_NOT_FOUND`() {
        val root = project()   // 同上：只建一次，别让第二次 project() 把文件造回来
        val packager = ApkPackager(workDir(), FixtureAxml.templateApk(), AlignProbe().runner)
        val planned = packager.plan(spec(), root, identity, templateInfo)
        Files.delete(root.resolve("lib/util.js"))
        val e = runCatching { packager.pack(planned, root) }.exceptionOrNull()
        assertInstanceOf(AutojsException::class.java, e)
        assertEquals(ErrorCode.ERR_NOT_FOUND, (e as AutojsException).error)
        assertTrue("util.js" in e.message!!)
    }

    @Test
    fun `请求签名但装配器没带签名器——明确拒绝不静默降级`() {
        val packager = ApkPackager(workDir(), FixtureAxml.templateApk(), AlignProbe().runner, signer = null)
        val planned = packager.plan(spec(), project(), identity, templateInfo)
        val e = runCatching {
            packager.pack(
                planned, project(),
                signing = ApkPackager.Signing(
                    SignSpec(SigningKey.DebugEphemeral), "/k.jks", "a", "b",
                ),
            )
        }.exceptionOrNull()
        assertInstanceOf(AutojsException::class.java, e)
        assertEquals(ErrorCode.ERR_INVALID_PARAM, (e as AutojsException).error)
        assertTrue("未装 ApkSignerRunner" in e.message!!)
    }
}
