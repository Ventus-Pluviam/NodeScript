package com.autoscript.appservice.packager.axml

import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AxmlPatcherTest {

    private fun fixtureManifest(): ByteArray =
        FixtureAxml.entry(FixtureAxml.templateApk(), "AndroidManifest.xml")

    private fun fixtureArsc(): ByteArray =
        FixtureAxml.entry(FixtureAxml.templateApk(), "resources.arsc")

    @Test
    fun `夹具是 aapt2 产出的合法二进制 XML`() {
        val bytes = fixtureManifest()
        assertTrue(bytes.size > 100, "夹具不应是空壳")
        val type = (bytes[0].toInt() and 0xff) or ((bytes[1].toInt() and 0xff) shl 8)
        assertEquals(0x0003, type, "外层应为 RES_XML_TYPE")
    }

    @Test
    fun `读出模板身份三件套与 label 资源引用`() {
        val axml = AxmlPatcher.parse(fixtureManifest())
        assertEquals(ManifestAttrValue.Text("com.autoscript.template"), axml.readAttr("manifest", "package"))
        assertEquals(ManifestAttrValue.Text("1.0.0"), axml.readAttr("manifest", "versionName"))
        assertEquals(ManifestAttrValue.Number(1), axml.readAttr("manifest", "versionCode"))
        // label 是 @string/app_name：REF 型，data 即资源 id —— 这条决定了必须走 ARSC。
        assertEquals(ManifestAttrValue.ResourceRef(0x7f010000), axml.readAttr("application", "label"))
        assertNull(axml.readAttr("application", "不存在的属性"))
    }

    @Test
    fun `未改动时逐字节回吐（往返无损）`() {
        val bytes = fixtureManifest()
        assertArrayEquals(bytes, AxmlPatcher.parse(bytes).toByteArray(), "没追加新串就必须原样回吐")
    }

    @Test
    fun `改包名与版本后重解析读回新值且其余属性不受影响`() {
        val axml = AxmlPatcher.parse(fixtureManifest())
        axml.setStringAttr("manifest", "package", "com.example.rewritten")
        axml.setStringAttr("manifest", "versionName", "9.9.9")
        axml.setIntAttr("manifest", "versionCode", 4242)

        val back = AxmlPatcher.parse(axml.toByteArray())
        assertEquals(ManifestAttrValue.Text("com.example.rewritten"), back.readAttr("manifest", "package"))
        assertEquals(ManifestAttrValue.Text("9.9.9"), back.readAttr("manifest", "versionName"))
        assertEquals(ManifestAttrValue.Number(4242), back.readAttr("manifest", "versionCode"))
        // label 不该被上面三次改写碰到
        assertEquals(ManifestAttrValue.ResourceRef(0x7f010000), back.readAttr("application", "label"))
        // 兄弟元素的属性同样原样
        assertEquals(ManifestAttrValue.Number(26), back.readAttr("uses-sdk", "minSdkVersion"))
    }

    @Test
    fun `改写只追加不重排：已有串下标稳定`() {
        val bytes = fixtureManifest()
        val before = AxmlPatcher.parse(bytes)
        val axml = AxmlPatcher.parse(bytes)
        axml.setStringAttr("manifest", "package", "com.example.another")
        val after = AxmlPatcher.parse(axml.toByteArray())
        // 追加不动旧下标这条不变量的直接证据：未被改写的 versionName 仍读回原值
        assertEquals(
            ManifestAttrValue.Text("1.0.0"),
            after.readAttr("manifest", "versionName"),
            "追加新串不得挪动已有串的下标",
        )
        assertEquals(before.readAttr("application", "label"), after.readAttr("application", "label"))
    }

    @Test
    fun `字面 label 可直接改成新显示名`() {
        val axml = AxmlPatcher.parse(fixtureManifest())
        axml.setStringAttr("application", "label", "模板名")
        assertEquals(ManifestAttrValue.Text("模板名"), axml.readAttr("application", "label"))

        axml.setStringAttr("application", "label", "新显示名")
        val back = AxmlPatcher.parse(axml.toByteArray())
        assertEquals(ManifestAttrValue.Text("新显示名"), back.readAttr("application", "label"))
    }

    @Test
    fun `缺元素与缺属性早失败且报可诊断错误`() {
        val axml = AxmlPatcher.parse(fixtureManifest())
        val missingElement = runCatching { axml.setStringAttr("service", "name", "x") }
        assertTrue(missingElement.isFailure, "模板里没有 <service> 就该失败")
        assertInstanceOf(AutojsException::class.java, missingElement.exceptionOrNull())

        val missingAttr = runCatching { axml.setStringAttr("manifest", "nope", "x") }
        assertTrue(missingAttr.isFailure, "缺属性就该失败，不该静默新建")
        assertInstanceOf(AutojsException::class.java, missingAttr.exceptionOrNull())
    }

    @Test
    fun `损坏的 AXML 报 ERR_IO 而非下标越界`() {
        val truncated = fixtureManifest().let { it.copyOf(it.size / 2) }
        val e = runCatching { AxmlPatcher.parse(truncated) }.exceptionOrNull()
        assertInstanceOf(AutojsException::class.java, e)
        assertEquals(ErrorCode.ERR_IO, (e as AutojsException).error)
    }

    @Test
    fun `非 AXML 输入明确拒绝`() {
        val e = runCatching { AxmlPatcher.parse("这不是 XML".toByteArray()) }.exceptionOrNull()
        assertInstanceOf(AutojsException::class.java, e)
    }

    @Test
    fun `UTF16 池追加多字节串可往返`() {
        val axml = AxmlPatcher.parse(fixtureManifest())
        axml.setStringAttr("manifest", "versionName", "多字节·显示名")
        val back = AxmlPatcher.parse(axml.toByteArray())
        assertEquals(ManifestAttrValue.Text("多字节·显示名"), back.readAttr("manifest", "versionName"))
    }
}
