package com.autoscript.platform.capabilities

import com.autoscript.domain.bridge.BridgeRequest
import com.autoscript.domain.bridge.BridgeResponse
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.storage.SystemSettings
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

/**
 * `settings` 桥处理器测试（§9.6；JS 对偶 `bridge/js/src/settings.ts` + `settings.test.cjs`）。
 *
 * 判据五件：
 * 1. **读缺失 = 裸 JSON `null`**（不是 `{found,…}` 信封、不是 0/空串冒充）——
 *    值面只有 String/Int，`null` 与任何合法值不撞，故不需要信封；
 * 2. **两型不猜**：`putString`/`putInt` 分别把串与整数原样送到 SPI，空串 value 合法
 *    （只有 key 不得空白）；
 * 3. **错误不折叠**：SPI 的 `ERR_PERMISSION_DENIED`（未授 WRITE_SETTINGS）与 `ERR_IO`
 *    （已授权仍被拒）原码透传 —— 授权问题要能被调用方识别并引导；
 * 4. **参数口径不碰 SPI**：缺参/空白 key/非串 value/非整或超 Int 范围 →
 *    `ERR_INVALID_PARAM`，且一次写都没发出去；
 * 5. **不猜别名**：`get`/`put`/`write` 等未约定方法 → `ERR_NOT_IMPLEMENTED`。
 *
 * 门禁语义的唯一出处是 SPI（这里刻意不预检 canWrite，见 handler KDoc）——
 * 因此本测试的假实现自己判 writable/systemRejects，与 `AndroidSystemSettings` 同构。
 */
class SettingsNamespaceHandlerTest {

    private class FakeSettings : SystemSettings {
        val writes = mutableListOf<Pair<String, Any>>()
        private val strs = mutableMapOf<String, String>()
        private val ints = mutableMapOf<String, Int>()
        var writable = true
        /** 已授权但系统仍拒（键被保护 / ROM 裁剪）→ ERR_IO。 */
        var systemRejects = false
        var failWith: AutojsException? = null

        override fun canWrite(): Boolean = writable

        override fun getString(key: String): String? {
            failWith?.let { throw it }
            return strs[key]
        }

        override fun getInt(key: String): Int? {
            failWith?.let { throw it }
            return ints[key]
        }

        override fun putString(key: String, value: String) {
            failWith?.let { throw it }
            if (!writable) {
                throw AutojsException(ErrorCode.ERR_PERMISSION_DENIED, "系统设置未授权写入: $key", null)
            }
            if (systemRejects) {
                throw AutojsException(ErrorCode.ERR_IO, "系统拒绝写入设置: $key", null)
            }
            writes += "putString" to value
            strs[key] = value
        }

        override fun putInt(key: String, value: Int) {
            failWith?.let { throw it }
            if (!writable) {
                throw AutojsException(ErrorCode.ERR_PERMISSION_DENIED, "系统设置未授权写入: $key", null)
            }
            if (systemRejects) {
                throw AutojsException(ErrorCode.ERR_IO, "系统拒绝写入设置: $key", null)
            }
            writes += "putInt" to value
            ints[key] = value
        }
    }

    private val fake = FakeSettings()
    private val handler = CapabilityNamespaces.settings(fake)

    private suspend fun call(method: String, payload: String?): BridgeResponse =
        handler.handle(BridgeRequest(1, "settings", method, payload, 5_000))

    private fun ok(r: BridgeResponse): String =
        assertInstanceOf(BridgeResponse.Ok::class.java, r).payload!!

    private fun errCode(r: BridgeResponse): String =
        assertInstanceOf(BridgeResponse.Err::class.java, r).errorCode

    @Test
    fun `读缺失回裸 JSON null——不拿空串或 0 冒充`() = runBlocking {
        assertEquals("null", ok(call("getString", """{"key":"absent"}""")), "缺键 = JSON null")
        assertEquals("null", ok(call("getInt", """{"key":"absent"}""")), "缺键 = JSON null，0 是合法亮度")

        fake.writable = true
        ok(call("putString", """{"key":"ring_volume","value":""}"""))
        assertEquals("\"\"", ok(call("getString", """{"key":"ring_volume"}""")), "空串是真值，不是缺失")

        ok(call("putInt", """{"key":"screen_brightness","value":0}"""))
        assertEquals("0", ok(call("getInt", """{"key":"screen_brightness"}""")), "0 是真值，不是缺失")
        Unit
    }

    @Test
    fun `两型原样到 SPI——putString 存原文 putInt 存整数`() = runBlocking {
        assertEquals("true", ok(call("putString", """{"key":"ring_volume","value":"auto"}""")))
        assertEquals("true", ok(call("putInt", """{"key":"screen_off_timeout","value":60000}""")))
        assertEquals(listOf<Pair<String, Any>>("putString" to "auto", "putInt" to 60_000), fake.writes)
        assertEquals("\"auto\"", ok(call("getString", """{"key":"ring_volume"}""")))
        assertEquals("60000", ok(call("getInt", """{"key":"screen_off_timeout"}""")))
        Unit
    }

    @Test
    fun `canWrite 原样透传——读探针不吃参数`() = runBlocking {
        assertEquals("true", ok(call("canWrite", null)))
        fake.writable = false
        assertEquals("false", ok(call("canWrite", null)))
        fake.writable = true
        Unit
    }

    @Test
    fun `参数口径——非法输入 ERR_INVALID_PARAM 且一次写都没发`() = runBlocking {
        assertEquals(ErrorCode.ERR_INVALID_PARAM.code, errCode(call("getString", null)), "缺 payload")
        assertEquals(ErrorCode.ERR_INVALID_PARAM.code, errCode(call("getString", """{"key":"  "}""")), "空白 key")
        assertEquals(ErrorCode.ERR_INVALID_PARAM.code, errCode(call("getInt", """{}""")), "缺 key")
        assertEquals(
            ErrorCode.ERR_INVALID_PARAM.code,
            errCode(call("putString", """{"key":"ring_volume"}""")),
            "缺 value",
        )
        assertEquals(
            ErrorCode.ERR_INVALID_PARAM.code,
            errCode(call("putString", """{"key":"ring_volume","value":128}""")),
            "value 不是串 —— 不替调用方猜型",
        )
        assertEquals(
            ErrorCode.ERR_INVALID_PARAM.code,
            errCode(call("putInt", """{"key":"screen_off_timeout","value":"60000"}""")),
            "value 不是数",
        )
        assertEquals(
            ErrorCode.ERR_INVALID_PARAM.code,
            errCode(call("putInt", """{"key":"screen_off_timeout","value":1.5}""")),
            "非整数",
        )
        assertEquals(
            ErrorCode.ERR_INVALID_PARAM.code,
            errCode(call("putInt", """{"key":"screen_off_timeout","value":2147483648}""")),
            "超出 Int 范围",
        )
        assertEquals(true, fake.writes.isEmpty(), "被拒的调用不碰 SPI")
        Unit
    }

    @Test
    fun `SPI 错误原码透传——权限与 IO 不折叠成 INVALID_PARAM`() = runBlocking {
        fake.writable = false
        val denied = assertInstanceOf(
            BridgeResponse.Err::class.java,
            call("putString", """{"key":"ring_volume","value":"auto"}"""),
        )
        assertEquals(ErrorCode.ERR_PERMISSION_DENIED.code, denied.errorCode)
        assertEquals(true, denied.detail!!.contains("未授权"), "detail 原样带现场")

        fake.writable = true
        fake.systemRejects = true
        val io = assertInstanceOf(
            BridgeResponse.Err::class.java,
            call("putInt", """{"key":"screen_off_timeout","value":60000}"""),
        )
        assertEquals(ErrorCode.ERR_IO.code, io.errorCode)
        fake.systemRejects = false
        assertEquals(true, fake.writes.isEmpty(), "被拒的调用不写")
        Unit
    }

    @Test
    fun `未知方法与 get put 别名都如实 ERR_NOT_IMPLEMENTED`() = runBlocking {
        assertEquals(
            ErrorCode.ERR_NOT_IMPLEMENTED.code,
            errCode(call("get", """{"key":"ring_volume"}""")),
            "get 是没约定过的别名：两型该用哪个由调用方选，不猜",
        )
        assertEquals(
            ErrorCode.ERR_NOT_IMPLEMENTED.code,
            errCode(call("put", """{"key":"ring_volume","value":128}""")),
        )
        assertEquals(ErrorCode.ERR_NOT_IMPLEMENTED.code, errCode(call("write", null)))
        Unit
    }
}
