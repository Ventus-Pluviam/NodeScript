package com.autoscript.platform.system

import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `settings` Android 实现的契约测试（docs §9.6；假 [AndroidSystemSettings.Ops]）。
 *
 * 守三件事（真实现是静态 Settings 调用，只能编译不能跑 —— 语义全在这边）：
 * 1. **写前门**：canWrite false → put 抛 ERR_PERMISSION_DENIED（不是回 false）；
 * 2. **已授权仍拒**：ops put 回 false → ERR_IO（键受保护的现场）；
 * 3. **读侧缺失如实 null**：0 不冒充缺失（0 是合法亮度值）。
 */
class AndroidSystemSettingsTest {

    private class FakeOps(
        var writable: Boolean = true,
        var putSucceeds: Boolean = true,
        val strings: MutableMap<String, String> = mutableMapOf(),
        val ints: MutableMap<String, Int> = mutableMapOf(),
    ) : AndroidSystemSettings.Ops {
        var putCalls = 0
        override fun canWrite() = writable
        override fun getString(key: String): String? = strings[key]
        override fun getInt(key: String): Int? = ints[key]
        override fun putString(key: String, value: String): Boolean {
            putCalls++
            if (putSucceeds) strings[key] = value
            return putSucceeds
        }
        override fun putInt(key: String, value: Int): Boolean {
            putCalls++
            if (putSucceeds) ints[key] = value
            return putSucceeds
        }
    }

    @Test
    fun `读侧——命中回值，缺失回 null 不拿 0 或空串冒充`() {
        val ops = FakeOps(strings = mutableMapOf("ring" to "vibrate"), ints = mutableMapOf("bright" to 0))
        val s = AndroidSystemSettings(ops)
        assertEquals("vibrate", s.getString("ring"))
        assertEquals(0, s.getInt("bright"), "0 是合法亮度：命中就是 0")
        assertNull(s.getString("nope"))
        assertNull(s.getInt("nope"), "缺失是 null，不是 0")
    }

    @Test
    fun `写前门——未授 WRITE_SETTINGS 抛 ERR_PERMISSION_DENIED 而非 false`() {
        val ops = FakeOps(writable = false)
        val s = AndroidSystemSettings(ops)
        val e = assertThrows(AutojsException::class.java) { s.putString("screen_brightness", "128") }
        assertEquals(ErrorCode.ERR_PERMISSION_DENIED, e.error)
        assertEquals(true, e.message!!.contains("WRITE_SETTINGS"), "detail 点名缺的权限")
        assertEquals(0, ops.putCalls, "门没过一个字节都不许写")
        assertThrows(AutojsException::class.java) { s.putInt("screen_off_timeout", 30_000) }
        assertEquals(0, ops.putCalls)
    }

    @Test
    fun `已授权仍被拒——ERR_IO 不吞成静默 false`() {
        val ops = FakeOps(writable = true, putSucceeds = false)
        val s = AndroidSystemSettings(ops)
        val e = assertThrows(AutojsException::class.java) { s.putString("protected_key", "x") }
        assertEquals(ErrorCode.ERR_IO, e.error)
        assertTrue(s.canWrite(), "canWrite 探针如实 true")
    }

    @Test
    fun `授权后写入落地`() {
        val ops = FakeOps(writable = true)
        val s = AndroidSystemSettings(ops)
        s.putString("ring_volume", "5")
        s.putInt("screen_off_timeout", 60_000)
        assertEquals("5", ops.strings["ring_volume"])
        assertEquals(60_000, ops.ints["screen_off_timeout"])
        assertEquals(2, ops.putCalls)
    }

    @Test
    fun `空白键双侧拒——读写都不碰 ops`() {
        val ops = FakeOps()
        val s = AndroidSystemSettings(ops)
        assertThrows(IllegalArgumentException::class.java) { s.getString("  ") }
        assertThrows(IllegalArgumentException::class.java) { s.putInt("", 1) }
        assertEquals(0, ops.putCalls)
    }
}
