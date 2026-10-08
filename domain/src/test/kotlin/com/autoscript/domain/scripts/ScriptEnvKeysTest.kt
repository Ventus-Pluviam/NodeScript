package com.autoscript.domain.scripts

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [ScriptEnvKeys.reject] 的判据（§8.1）：保留前缀 / 空 / 含 `=` / 含控制字符四条各自命中，
 * 且**合法键不被误拒** —— 误拒比漏拒更难查（用户看不出为什么一个正常名字存不进去）。
 *
 * 每条拒收都断言"原文点名了那个键"：界面上只显示这一句话，不点名就等于让用户自己猜。
 */
class ScriptEnvKeysTest {

    @Test
    fun `保留前缀被拒且点名那个键`() {
        val key = "AUTOSCRIPT_HOST_SOCKET"
        val why = ScriptEnvKeys.reject(key)
        assertTrue(why != null && key in why, "拒收原文要点名键：$why")
        assertTrue("宿主保留" in (why ?: ""), "还要说清为什么：$why")
    }

    @Test
    fun `空串被拒`() {
        assertEquals("变量名不得为空", ScriptEnvKeys.reject(""))
    }

    @Test
    fun `含等号被拒`() {
        val why = ScriptEnvKeys.reject("A=B")
        assertTrue(why != null && "A=B" in why, "点名原键：$why")
    }

    @Test
    fun `含换行或 NUL 被拒`() {
        assertTrue(ScriptEnvKeys.reject("A\nB") != null)
        assertTrue(ScriptEnvKeys.reject("A\rB") != null)
        assertTrue(ScriptEnvKeys.reject("A\u0000B") != null)
    }

    @Test
    fun `常见合法键不被误拒`() {
        // 点、连字符、下划线、数字开头、非 ASCII —— 都是真会用到的名字。
        for (k in listOf("TOKEN", "my.token", "my-token", "my_token", "1PASSWORD", "路径", "HTTP_PROXY")) {
            assertNull(ScriptEnvKeys.reject(k), "合法键被误拒：$k")
        }
    }

    @Test
    fun `前缀是大小写敏感的——小写不是保留面`() {
        // 宿主自己的键全是大写 `AUTOSCRIPT_`；`autoscript_foo` 不撞任何宿主键，
        // 拦它属于过度收窄（用户看不出理由）。这条钉住"按字面前缀判"而不是忽略大小写。
        assertNull(ScriptEnvKeys.reject("autoscript_foo"))
    }
}
