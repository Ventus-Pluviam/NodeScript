package com.autoscript.appservice.packager

import com.autoscript.appservice.packager.axml.AxmlPatcher
import com.autoscript.appservice.packager.axml.ArscPatcher
import com.autoscript.appservice.packager.axml.FixtureAxml
import com.autoscript.appservice.packager.axml.ManifestAttrValue
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.packager.ApkIdentity
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * 打包闭环的**端到端**单测：真模板 APK → [IdentityTemplatePatch] 改写 → 回读校验。
 * 全程纯 JVM、零 Android、零外部二进制，本机与 CI 同一套绿。
 */
class IdentityTemplatePatchTest {

    @TempDir
    lateinit var tmp: Path

    private val identity = ApkIdentity(
        packageName = "com.example.repacked",
        appLabel = "重打包应用",
        versionName = "3.2.1",
        versionCode = 321,
    )

    private fun template(): Path {
        val target = tmp.resolve("template.apk")
        Files.copy(FixtureAxml.templateApk(), target)
        return target
    }

    private fun zipNames(apk: Path): List<String> =
        ZipFile(apk.toFile()).use { zf -> zf.entries().asSequence().map { it.name }.toList() }

    private fun entry(apk: Path, name: String): ByteArray? =
        ZipFile(apk.toFile()).use { zf ->
            val e = zf.getEntry(name) ?: return null
            zf.getInputStream(e).readBytes()
        }

    @Test
    fun `改写后包名版本显示名全部落到新身份`() {
        val apk = template()
        val out = IdentityTemplatePatch(identity).apply(apk)
        assertEquals(apk, out, "补丁就地改写并回指同一路径")

        val axml = AxmlPatcher.parse(entry(out, "AndroidManifest.xml")!!)
        assertEquals(ManifestAttrValue.Text("com.example.repacked"), axml.readAttr("manifest", "package"))
        assertEquals(ManifestAttrValue.Text("3.2.1"), axml.readAttr("manifest", "versionName"))
        assertEquals(ManifestAttrValue.Number(321), axml.readAttr("manifest", "versionCode"))
        // 模板 label 是 @string 资源引用：改写后**仍应是同一个资源 id**（改的是 ARSC 里的值）
        assertEquals(ManifestAttrValue.ResourceRef(0x7f010000), axml.readAttr("application", "label"))
    }

    @Test
    fun `label 走 ARSC 资源引用时改的是资源表里的值`() {
        val apk = template()
        assertEquals("AutoScript Template", readLabelResource(apk), "改写前是模板名")
        IdentityTemplatePatch(identity).apply(apk)
        assertEquals("重打包应用", readLabelResource(apk), "显示名必须落到新身份")
    }

    private fun readLabelResource(apk: Path): String? {
        val arsc = entry(apk, "resources.arsc") ?: return null
        return ArscPatcher.parse(arsc).readStringResource(0x7f010000)
    }

    @Test
    fun `未参与改写的条目逐字节保留`() {
        val apk = template()
        // 模板原本只有两个条目；塞一个"其余资产"进去，确认重写不动它
        addEntry(apk, "assets/keep.bin", byteArrayOf(1, 2, 3, 4, 5))
        val before = entry(apk, "assets/keep.bin")!!

        IdentityTemplatePatch(identity).apply(apk)
        assertArrayEquals(before, entry(apk, "assets/keep.bin"), "非改写条目必须原样保留")
        assertTrue(zipNames(apk).containsAll(listOf("AndroidManifest.xml", "resources.arsc")))
    }

    @Test
    fun `剔除旧 v1 签名条目（内容已变，留着验签必炸）`() {
        val apk = template()
        addEntry(apk, "META-INF/CERT.SF", "stale".toByteArray())
        addEntry(apk, "META-INF/CERT.RSA", "stale".toByteArray())
        addEntry(apk, "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n".toByteArray())

        IdentityTemplatePatch(identity).apply(apk)
        val names = zipNames(apk)
        assertFalse(names.contains("META-INF/CERT.SF"), "旧 SF 必须剔除")
        assertFalse(names.contains("META-INF/CERT.RSA"), "旧 RSA 必须剔除")
        assertFalse(names.contains("META-INF/MANIFEST.MF"), "旧 MANIFEST 必须剔除")
    }

    @Test
    fun `缺 AndroidManifest 明确失败`() {
        val apk = tmp.resolve("broken.apk")
        ZipOutputStream(Files.newOutputStream(apk)).use { out ->
            out.putNextEntry(ZipEntry("resources.arsc")); out.write(byteArrayOf(0)); out.closeEntry()
        }
        val e = runCatching { IdentityTemplatePatch(identity).apply(apk) }.exceptionOrNull()
        assertInstanceOf(AutojsException::class.java, e)
        assertEquals(ErrorCode.ERR_NOT_FOUND, (e as AutojsException).error)
    }

    @Test
    fun `缺 label 属性明确失败（不产出没有显示名的包）`() {
        // 直接在 AXML 层面造"模板没写显示名"这一支：把 label 属性整个换成一个
        // 读不到的名字（属性改名后 readAttr 返回 null），再走补丁，必须早失败。
        val axml = AxmlPatcher.parse(FixtureAxml.entry(FixtureAxml.templateApk(), "AndroidManifest.xml"))
        renameAttr(axml, "application", "label", "label_removed")

        val apk = tmp.resolve("nolabel.apk")
        java.util.zip.ZipOutputStream(Files.newOutputStream(apk)).use { out ->
            out.putNextEntry(java.util.zip.ZipEntry("AndroidManifest.xml"))
            out.write(axml.toByteArray())
            out.closeEntry()
        }
        val e = runCatching { IdentityTemplatePatch(identity).apply(apk) }.exceptionOrNull()
        assertInstanceOf(AutojsException::class.java, e)
        assertEquals(ErrorCode.ERR_INVALID_PARAM, (e as AutojsException).error)
    }

    /**
     * 把 [element] 上名为 [from] 的属性名改成 [to]（只改属性名那一个池串的引用，
     * 不动其它引用同串的地方 —— 故 forName 必须是元素内独占的名字）。
     * 这是测试专用的"造缺属性"手段：[AxmlPatcher] 不提供删除属性的生产 API。
     */
    private fun renameAttr(axml: AxmlPatcher, element: String, from: String, to: String) {
        // AxmlPatcher 未暴露节点字节；用"写一个同名新值再靠 readAttr 读不到"绕不过去，
        // 所以这里走一条更直接的路：把属性值改成读起来仍叫 from 的占位不成立 ——
        // 改用反射太脆。真正稳的做法是让 AxmlPatcher 支持改属性名，这里以最小面补上。
        axml.renameAttrForTest(element, from, to)
    }

    @Test
    fun `planDigest 复验可挡住清单错配（调用方契约）`() {
        // 本测试锚定"改写前必须复验"这条调用方义务：Patch 自己不复验（它没有清单），
        // 复验归 TemplateApkPlans.verify —— 这里确认领域层那道门真的会关。
        val plan = com.autoscript.domain.packager.TemplateApkPlans.build(
            identity,
            com.autoscript.domain.packager.TemplateInfo("24.21.0"),
            com.autoscript.domain.packager.PackManifests.build(
                com.autoscript.domain.packager.PackSpec(projectId = "p", appName = "P"),
                listOf(com.autoscript.domain.packager.AssetEntry("main.js", "aa", 5)),
            ),
        )
        val tampered = com.autoscript.domain.packager.PackManifests.build(
            com.autoscript.domain.packager.PackSpec(projectId = "p", appName = "P"),
            listOf(com.autoscript.domain.packager.AssetEntry("main.js", "bb", 5)),
        )
        assertFalse(
            com.autoscript.domain.packager.TemplateApkPlans.verify(plan, tampered),
            "清单被换过就必须复验不过",
        )
    }

    /** 完整夹具（组件 + 图标资源俱全）拷进 @TempDir 后使用。 */
    private fun fullTemplate(): Path {
        val target = tmp.resolve("full.apk")
        Files.copy(FixtureAxml.templateFullApk(), target)
        return target
    }

    /** 魔数过检即算 PNG（本补丁不解码）：8 字节签名 + 随便什么负载。 */
    private fun pngish(marker: Int = 0x42): ByteArray =
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, marker.toByte(), 1, 2, 3)

    @Test
    fun `组件类名按旧包绝对化——相对与裸名改写，绝对名与外部类不动`() {
        val apk = fullTemplate()
        IdentityTemplatePatch(identity).apply(apk)

        val axml = AxmlPatcher.parse(entry(apk, "AndroidManifest.xml")!!)
        assertEquals(
            listOf(
                "com.autoscript.template.AppTemplate",   // .AppTemplate → 旧包绝对
                "com.autoscript.template.MainActivity",  // .MainActivity → 同上
                "com.autoscript.template.FqActivity",    // 本就绝对：原样（dex 命名空间）
                "com.autoscript.template.LauncherAlias", // alias 自己的 name 同规则
                "com.autoscript.template.BareService",   // 裸名 → 旧包绝对
                "com.other.KeepReceiver",                // 外部类：原样
                "com.autoscript.template.TplProvider",
            ),
            listOf("application", "activity", "activity-alias", "service", "receiver", "provider")
                .flatMap { axml.readStringAttrs(it, "name") },
            "新身份包是 com.example.repacked —— 断言里出现旧包才说明按旧包绝对化了",
        )
        assertEquals(
            listOf("com.autoscript.template.MainActivity"),
            axml.readStringAttrs("activity-alias", "targetActivity"),
            "alias 的 targetActivity 同规则（相对 → 旧包绝对）",
        )
        assertEquals(ManifestAttrValue.Text("com.example.repacked"), axml.readAttr("manifest", "package"))
    }

    @Test
    fun `换图标——密度 PNG 全换、自适应 XML 剔除、身份同趟落地`() {
        val apk = fullTemplate()
        val fgBefore = entry(apk, "res/drawable-xxhdpi-v4/ic_launcher_foreground.png")!!
        val icon = pngish()

        IdentityTemplatePatch(identity, iconPng = icon).apply(apk)

        val names = zipNames(apk)
        for (d in listOf("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi")) {
            assertArrayEquals(icon, entry(apk, "res/mipmap-$d-v4/ic_launcher.png"), "密度 $d 的图标必须换成用户字节")
        }
        assertArrayEquals(icon, entry(apk, "res/mipmap-xxxhdpi-v4/ic_launcher_round.png"), "round 条目同样换")
        assertFalse(names.contains("res/mipmap-anydpi-v26/ic_launcher.xml"), "自适应 XML 必须剔除（API26+ 会遮住新图标）")
        assertFalse(names.contains("res/mipmap-anydpi-v26/ic_launcher_round.xml"))
        assertArrayEquals(fgBefore, entry(apk, "res/drawable-xxhdpi-v4/ic_launcher_foreground.png"), "前景图不归换图标管")
        assertEquals(
            ManifestAttrValue.Text("com.example.repacked"),
            AxmlPatcher.parse(entry(apk, "AndroidManifest.xml")!!).readAttr("manifest", "package"),
            "图标与身份必须同一趟 rewrite 落地",
        )
    }

    @Test
    fun `模板没有密度 PNG——换图标如实拒绝，原包一字节不动`() {
        val apk = template()          // 最小夹具：没有 res/ 条目
        val before = Files.readAllBytes(apk)

        val e = runCatching { IdentityTemplatePatch(identity, iconPng = pngish()).apply(apk) }.exceptionOrNull()

        assertInstanceOf(AutojsException::class.java, e)
        assertEquals(ErrorCode.ERR_NOT_FOUND, (e as AutojsException).error)
        assertTrue("密度图标" in e.message!!)
        assertArrayEquals(before, Files.readAllBytes(apk), "拒绝发生在 rewrite 之前，原包不得被动过")
    }

    @Test
    fun `非 PNG 图标——构造期就拒绝`() {
        val e = runCatching { IdentityTemplatePatch(identity, iconPng = byteArrayOf(1, 2, 3)) }.exceptionOrNull()
        assertInstanceOf(AutojsException::class.java, e)
        assertEquals(ErrorCode.ERR_INVALID_PARAM, (e as AutojsException).error)
        assertTrue("PNG" in e.message!!)
    }

    private fun addEntry(apk: Path, name: String, bytes: ByteArray) {
        val tmpOut = apk.resolveSibling("rezip.tmp")
        ZipOutputStream(Files.newOutputStream(tmpOut)).use { out ->
            ZipFile(apk.toFile()).use { zf ->
                val entries = zf.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    out.putNextEntry(ZipEntry(e.name))
                    out.write(zf.getInputStream(e).readBytes())
                    out.closeEntry()
                }
            }
            out.putNextEntry(ZipEntry(name)); out.write(bytes); out.closeEntry()
        }
        Files.move(tmpOut, apk, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }
}
