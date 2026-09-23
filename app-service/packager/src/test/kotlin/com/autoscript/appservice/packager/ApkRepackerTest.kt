package com.autoscript.appservice.packager

import com.autoscript.appservice.packager.axml.FixtureAxml
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import java.nio.file.Path
import java.util.zip.ZipFile
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** [ApkRepacker] 两条写路径的分工：[rewrite] 必须命中既有条目，[addEntries] 可以新增。 */
class ApkRepackerTest {

    @TempDir
    lateinit var tmp: Path

    private val repacker = ApkRepacker()

    private fun fixture(): Path {
        val target = tmp.resolve("t.apk")
        java.nio.file.Files.copy(FixtureAxml.templateApk(), target)
        return target
    }

    private fun entry(apk: Path, name: String): ByteArray? =
        ZipFile(apk.toFile()).use { zf ->
            val e = zf.getEntry(name) ?: return@use null
            zf.getInputStream(e).readBytes()
        }

    @Test
    fun `addEntries 追加模板没有的新条目，既有条目原样保留`() {
        val apk = fixture()
        val manifestBefore = entry(apk, "AndroidManifest.xml")!!

        repacker.addEntries(apk, linkedMapOf("assets/project/main.js" to "entry".toByteArray()), apk)

        assertArrayEquals("entry".toByteArray(), entry(apk, "assets/project/main.js"))
        assertArrayEquals(manifestBefore, entry(apk, "AndroidManifest.xml"), "既有条目字节不动")
        assertArrayEquals(
            FixtureAxml.entry(FixtureAxml.templateApk(), "resources.arsc"),
            entry(apk, "resources.arsc"),
        )
    }

    @Test
    fun `addEntries 覆盖同名既有条目`() {
        val apk = fixture()
        repacker.addEntries(apk, mapOf("assets/project/main.js" to "v1".toByteArray()), apk)
        repacker.addEntries(apk, mapOf("assets/project/main.js" to "v2".toByteArray()), apk)
        assertArrayEquals("v2".toByteArray(), entry(apk, "assets/project/main.js"))
    }

    @Test
    fun `rewrite 的既有条目必须存在——打错名早失败`() {
        val apk = fixture()
        val e = assertThrows(AutojsException::class.java) {
            repacker.rewrite(apk, mapOf("assets/nope.xml" to byteArrayOf(1)), apk)
        }
        assertEquals(ErrorCode.ERR_NOT_FOUND, e.error)
    }

    @Test
    fun `addEntries 拒绝穿越与绝对条目名（防御性双保险）`() {
        val apk = fixture()
        assertThrows(IllegalArgumentException::class.java) {
            repacker.addEntries(apk, mapOf("assets/../../evil" to byteArrayOf(1)), apk)
        }
        assertThrows(IllegalArgumentException::class.java) {
            repacker.addEntries(apk, mapOf("/abs" to byteArrayOf(1)), apk)
        }
        assertTrue(apkExists(apk), "被拒后原包不动")
    }

    @Test
    fun `写回过程剔除旧 v1 签名条目`() {
        val apk = fixture()
        // 先塞两个"旧签名"条目（走 addEntries：META-INF 名不在模板里），
        // 再任一写回一趟，确认它们被 isV1SignatureEntry 剔掉。
        repacker.addEntries(
            apk,
            linkedMapOf(
                "META-INF/CERT.SF" to "stale".toByteArray(),
                "META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\n".toByteArray(),
                "META-INF/NOTICE.TXT" to "kept".toByteArray(),   // 非签名附件应保留
            ),
            apk,
        )
        assertEquals("stale", String(entry(apk, "META-INF/CERT.SF")!!))
        repacker.addEntries(apk, mapOf("assets/project/x.js" to "x".toByteArray()), apk)
        assertNull(entry(apk, "META-INF/CERT.SF"), "旧 SF 必须剔除")
        assertNull(entry(apk, "META-INF/MANIFEST.MF"), "旧 MANIFEST 必须剔除")
        assertEquals("kept", String(entry(apk, "META-INF/NOTICE.TXT")!!), "非签名附件不误伤")
        assertEquals("x", String(entry(apk, "assets/project/x.js")!!))
    }

    @Test
    fun `rewrite 同趟替换与剔除——换图标那条路的条目级分工`() {
        val apk = fixture()
        // 造一对"密度图标 + 自适应 XML"，走一次 replace+remove 组合（生产即图标改写）。
        repacker.addEntries(
            apk,
            linkedMapOf(
                "res/mipmap-xhdpi-v4/ic_launcher.png" to byteArrayOf(1),
                "res/mipmap-anydpi-v26/ic_launcher.xml" to "<x/>".toByteArray(),
            ),
            apk,
        )
        val manifestBefore = entry(apk, "AndroidManifest.xml")!!

        repacker.rewrite(
            apk,
            mapOf("res/mipmap-xhdpi-v4/ic_launcher.png" to byteArrayOf(9, 9)),
            apk,
            removals = setOf("res/mipmap-anydpi-v26/ic_launcher.xml"),
        )

        assertArrayEquals(byteArrayOf(9, 9), entry(apk, "res/mipmap-xhdpi-v4/ic_launcher.png"))
        assertNull(entry(apk, "res/mipmap-anydpi-v26/ic_launcher.xml"), "删除集里的条目必须消失")
        assertArrayEquals(manifestBefore, entry(apk, "AndroidManifest.xml"), "未涉及的条目原样")
        assertTrue(repacker.entries(apk).containsAll(listOf("AndroidManifest.xml", "resources.arsc")))
    }

    @Test
    fun `rewrite 拒绝既替换又删除同一条目（调用方自相矛盾）`() {
        val apk = fixture()
        assertThrows(IllegalArgumentException::class.java) {
            repacker.rewrite(
                apk,
                mapOf("resources.arsc" to byteArrayOf(1)),
                apk,
                removals = setOf("resources.arsc"),
            )
        }
        assertTrue(apkExists(apk), "被拒后原包不动")
    }

    @Test
    fun `entries 按 zip 原序枚举全部条目名`() {
        assertEquals(listOf("AndroidManifest.xml", "resources.arsc"), repacker.entries(fixture()))
    }

    private fun apkExists(apk: Path): Boolean = java.nio.file.Files.isRegularFile(apk)
}
