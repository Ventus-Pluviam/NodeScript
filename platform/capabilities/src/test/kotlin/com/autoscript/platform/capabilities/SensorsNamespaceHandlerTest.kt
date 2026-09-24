package com.autoscript.platform.capabilities

import com.autoscript.domain.bridge.BridgeRequest
import com.autoscript.domain.bridge.BridgeResponse
import com.autoscript.domain.bridge.HandleRef
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.system.SensorDelay
import com.autoscript.domain.system.SensorEventBatch
import com.autoscript.domain.system.SensorSource
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `sensors` 桥处理器测试（§12.2；JS 对偶 `bridge/js/src/sensors.ts` + `sensors.test.cjs`）。
 *
 * 判据六件：
 * 1. **wire 形状**：register 发 `{name,delay?}`（delay 缺省 NORMAL，wire 传名字面量）→
 *    `{ref:{refId,generation}}`；drain 发 `{ref,sinceSeq?,max?}` → `{first,last,events}`；
 * 2. **参数口径不碰 SPI**：空白名/非法 delay/非正 max/缺 ref → `ERR_INVALID_PARAM`，
 *    且一次注册/注销都不发；
 * 3. **错误不折叠**：SPI 的 `ERR_NOT_SUPPORTED`/`ERR_SERVICE_DISABLED`/`ERR_STALE_HANDLE`
 *    原码透传（调用方要能识别：不支持 vs 系统拒收 vs 句柄已死是三种引导）；
 * 4. **delay 缺省 NORMAL**：不传 delay 即 NORMAL（不是 FASTEST —— 省电侧默认）；
 * 5. **空增量游标**：`drain` 空环回 `first == last == sinceSeq` + 空表；
 * 6. **不猜别名**：`on`/`subscribe`/`watch`/`once` 等未约定方法 → `ERR_NOT_IMPLEMENTED`。
 *
 * 采样语义（环/幂等/分辨）归 `:platform:system` 的 `AndroidSensorSourceTest` ——
 * 这里只钉桥面形状与口径。
 */
class SensorsNamespaceHandlerTest {

    private class FakeSensors : SensorSource {
        val registered = mutableListOf<Pair<String, SensorDelay>>()
        val unregistered = mutableListOf<HandleRef>()
        var unregisterAllCalls = 0
        var supported: (String) -> Boolean = { it == "accelerometer" }
        var registerFail: AutojsException? = null
        var drainFail: AutojsException? = null
        var nextRefId = 1L

        override fun isSupported(name: String): Boolean = supported(name)

        override suspend fun register(name: String, delay: SensorDelay): HandleRef {
            registerFail?.let { throw it }
            registered += name to delay
            return HandleRef(nextRefId++, 1)
        }

        override suspend fun unregister(ref: HandleRef) {
            unregistered += ref
        }

        override suspend fun unregisterAll() {
            unregisterAllCalls++
        }

        override suspend fun drain(ref: HandleRef, sinceSeq: Long, max: Int): SensorEventBatch {
            drainFail?.let { throw it }
            require(max > 0) { "drain 的 max 必须 > 0，实际 $max" }
            return SensorEventBatch(sinceSeq, sinceSeq, emptyList())
        }
    }

    private val fake = FakeSensors()
    private val handler = CapabilityNamespaces.sensors(fake)

    private suspend fun call(method: String, payload: String?): BridgeResponse =
        handler.handle(BridgeRequest(1, "sensors", method, payload, 5_000))

    private fun ok(r: BridgeResponse): String =
        assertInstanceOf(BridgeResponse.Ok::class.java, r).payload!!

    private fun errCode(r: BridgeResponse): String =
        assertInstanceOf(BridgeResponse.Err::class.java, r).errorCode

    @Test
    fun `register wire 形状——name加delay名，回ref体`() = runBlocking {
        val payload = ok(call("register", """{"name":"accelerometer","delay":"GAME"}"""))
        assertTrue(payload.contains("refId") && payload.contains("generation"), "回ref体: $payload")
        assertEquals(listOf("accelerometer" to SensorDelay.GAME), fake.registered)

        val payload2 = ok(call("register", """{"name":"accelerometer"}"""))
        assertTrue(payload2.contains("refId"), "缺省 delay 照样发号: $payload2")
        assertEquals(SensorDelay.NORMAL, fake.registered.last().second, "delay 缺省 NORMAL")
        Unit
    }

    @Test
    fun `参数口径——空白名非法delay非正max缺ref全拒且不碰SPI`() = runBlocking {
        fake.registered.clear()
        assertEquals(ErrorCode.ERR_INVALID_PARAM.code, errCode(call("register", null)), "缺 payload")
        assertEquals(ErrorCode.ERR_INVALID_PARAM.code, errCode(call("register", """{"name":"  "}""")), "空白名")
        assertEquals(
            ErrorCode.ERR_INVALID_PARAM.code,
            errCode(call("register", """{"name":"accelerometer","delay":"TURBO"}""")),
            "拼错 delay 不静默套默认",
        )
        assertEquals(ErrorCode.ERR_INVALID_PARAM.code, errCode(call("register", """{"name":128}""")), "名非串")
        assertEquals(true, fake.registered.isEmpty(), "被拒的注册不碰 SPI")

        assertEquals(
            ErrorCode.ERR_INVALID_PARAM.code,
            errCode(call("drain", """{"ref":{"refId":1,"generation":1},"sinceSeq":0,"max":0}""")),
            "max 非正",
        )
        assertEquals(ErrorCode.ERR_INVALID_PARAM.code, errCode(call("drain", """{"sinceSeq":0}""")), "缺 ref")
        assertEquals(
            ErrorCode.ERR_INVALID_PARAM.code,
            errCode(call("unregister", """{}""")),
            "unregister 缺 ref",
        )
        assertEquals(true, fake.unregistered.isEmpty(), "被拒的注销不碰 SPI")
        Unit
    }

    @Test
    fun `SPI 错误原码透传——三码不折叠`() = runBlocking {
        fake.registerFail = AutojsException(ErrorCode.ERR_NOT_SUPPORTED, "设备不支持")
        assertEquals(ErrorCode.ERR_NOT_SUPPORTED.code, errCode(call("register", """{"name":"foo"}""")))
        fake.registerFail = AutojsException(ErrorCode.ERR_SERVICE_DISABLED, "系统拒收")
        assertEquals(ErrorCode.ERR_SERVICE_DISABLED.code, errCode(call("register", """{"name":"accelerometer"}""")))
        fake.registerFail = null

        fake.drainFail = AutojsException(ErrorCode.ERR_STALE_HANDLE, "订阅已死")
        val e = assertInstanceOf(
            BridgeResponse.Err::class.java,
            call("drain", """{"ref":{"refId":9,"generation":1}}"""),
        )
        assertEquals(ErrorCode.ERR_STALE_HANDLE.code, e.errorCode)
        fake.drainFail = null
        Unit
    }

    @Test
    fun `isSupported 与 unregisterAll 直通`() = runBlocking {
        assertEquals("true", ok(call("isSupported", """{"name":"accelerometer"}""")))
        assertEquals("false", ok(call("isSupported", """{"name":"heart_rate"}""")))
        assertEquals(ErrorCode.ERR_INVALID_PARAM.code, errCode(call("isSupported", """{"name":""}""")), "空白名是参数错")

        assertEquals("true", ok(call("unregisterAll", null)))
        assertEquals(1, fake.unregisterAllCalls)
        Unit
    }

    @Test
    fun `drain 空增量游标——first等于last等于sinceSeq`() = runBlocking {
        val payload = ok(call("drain", """{"ref":{"refId":1,"generation":1},"sinceSeq":7}"""))
        assertTrue(payload.contains(""""first":7""") && payload.contains(""""last":7"""), "游标回显: $payload")
        assertTrue(payload.contains(""""events":[]"""), "空表: $payload")
        Unit
    }

    @Test
    fun `未知方法与别名都如实 ERR_NOT_IMPLEMENTED`() = runBlocking {
        assertEquals(ErrorCode.ERR_NOT_IMPLEMENTED.code, errCode(call("on", null)))
        assertEquals(ErrorCode.ERR_NOT_IMPLEMENTED.code, errCode(call("subscribe", null)))
        assertEquals(ErrorCode.ERR_NOT_IMPLEMENTED.code, errCode(call("watch", null)))
        assertEquals(ErrorCode.ERR_NOT_IMPLEMENTED.code, errCode(call("once", null)))
        Unit
    }
}
