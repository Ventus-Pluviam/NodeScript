package com.autoscript.appservice.packager.axml

import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ArscPatcherTest {

    private fun fixtureArsc(): ByteArray =
        FixtureAxml.entry(FixtureAxml.templateApk(), "resources.arsc")

    @Test
    fun `夹具是资源表且能读出 app_name 那一条`() {
        val arsc = ArscPatcher.parse(fixtureArsc())
        assertEquals("AutoScript Template", arsc.readStringResource(0x7f010000))
        assertNull(arsc.readStringResource(0x7f010099), "不存在的资源 id 应返回 null 而非抛")
    }

    @Test
    fun `未改动时逐字节回吐（往返无损）`() {
        val bytes = fixtureArsc()
        assertArrayEquals(bytes, ArscPatcher.parse(bytes).toByteArray(), "没追加新串就必须原样回吐")
    }

    @Test
    fun `按资源 id 改字符串后读回新值`() {
        val arsc = ArscPatcher.parse(fixtureArsc())
        assertEquals(1, arsc.replaceStringResource(0x7f010000, "我的应用"), "应改写到 1 个 config 条目")
        assertEquals("我的应用", arsc.readStringResource(0x7f010000))

        val back = ArscPatcher.parse(arsc.toByteArray())
        assertEquals("我的应用", back.readStringResource(0x7f010000), "重排后再读仍是新值")
    }

    @Test
    fun `改写不波及其他资源与包头`() {
        val arsc = ArscPatcher.parse(fixtureArsc())
        val original = ArscPatcher.parse(fixtureArsc())
        arsc.replaceStringResource(0x7f010000, "改过的")
        val back = arsc.toByteArray()
        val reparsed = ArscPatcher.parse(back)
        assertEquals("改过的", reparsed.readStringResource(0x7f010000))
        // 找不到别的资源 id 就说明没把整表写坏（能重新解析 + packageCount 校验通过）
        assertNull(reparsed.readStringResource(0x7f020000))
        assertEquals(original.readStringResource(0x7f010000), "AutoScript Template")
    }

    @Test
    fun `UTF8 池追加中文串可往返`() {
        val arsc = ArscPatcher.parse(fixtureArsc())
        arsc.replaceStringResource(0x7f010000, "中文·显示名😀")
        val back = ArscPatcher.parse(arsc.toByteArray())
        assertEquals("中文·显示名😀", back.readStringResource(0x7f010000))
    }

    @Test
    fun `重复改写同一资源每次都落到最后一次的值`() {
        val arsc = ArscPatcher.parse(fixtureArsc())
        arsc.replaceStringResource(0x7f010000, "第一版")
        arsc.replaceStringResource(0x7f010000, "第二版")
        assertEquals("第二版", ArscPatcher.parse(arsc.toByteArray()).readStringResource(0x7f010000))
    }

    @Test
    fun `损坏的 ARSC 报 ERR_IO`() {
        val truncated = fixtureArsc().let { it.copyOf(it.size / 2) }
        val e = runCatching { ArscPatcher.parse(truncated) }.exceptionOrNull()
        assertInstanceOf(AutojsException::class.java, e)
        assertEquals(ErrorCode.ERR_IO, (e as AutojsException).error)
    }

    @Test
    fun `非资源表输入明确拒绝`() {
        val e = runCatching { ArscPatcher.parse("not a table".toByteArray()) }.exceptionOrNull()
        assertInstanceOf(AutojsException::class.java, e)
    }

    @Test
    fun `未知顶层块原样保留（不静默丢资源）`() {
        val bytes = fixtureArsc()
        // 手工在表尾接一个未知类型块，确认重排后它还在（aapt2 按 targetSdk 会写 overlayable 等）
        val extra = byteArrayOf(0x04, 0x02, 0x08, 0x00, 0x08, 0x00, 0x00, 0x00)
        val withExtra = bytes.copyOf(bytes.size + extra.size)
        System.arraycopy(extra, 0, withExtra, bytes.size, extra.size)
        // 修正外层 size，否则解析阶段就会以 size 不符拒绝
        val total = withExtra.size
        withExtra[4] = (total and 0xff).toByte()
        withExtra[5] = ((total shr 8) and 0xff).toByte()
        withExtra[6] = ((total shr 16) and 0xff).toByte()
        withExtra[7] = ((total shr 24) and 0xff).toByte()

        val arsc = ArscPatcher.parse(withExtra)
        arsc.replaceStringResource(0x7f010000, "换名")
        val out = arsc.toByteArray()
        assertTrue(out.size >= withExtra.size, "未知块不得被丢掉")
        assertArrayEquals(extra, out.copyOfRange(out.size - extra.size, out.size), "未知块应原样留在表尾")
        assertEquals("换名", ArscPatcher.parse(out).readStringResource(0x7f010000))
    }
}
