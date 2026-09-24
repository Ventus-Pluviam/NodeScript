package com.autoscript.platform.capabilities

import com.autoscript.domain.bridge.BridgeRequest
import com.autoscript.domain.bridge.BridgeResponse
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.system.Clipboard
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

/**
 * `clipboard` 桥处理器测试（§12.2；JS 对偶 `bridge/js/src/clipboard.ts` + `clipboard.test.cjs`）。
 *
 * 判据四件：
 * 1. **读空 = 裸 JSON `null`**（不是 `{found,…}` 信封、不是空串冒充）——
 *    值面只有 `String?`，`null` 与任何合法值不撞，故不需要信封（与 settings 读缺失同形）；
 * 2. **空串是真值**：写空串原样存、读回 `""`，不与 null 混淆（a11y `copy` 空节点记空串）；
 * 3. **写口径不碰 SPI**：缺参/非串 → `ERR_INVALID_PARAM`，且一次写都没发出去；
 * 4. **不猜别名**：`get`/`set`/`clear`/`hasText` 等未约定方法 → `ERR_NOT_IMPLEMENTED`。
 *
 * 无门禁可测（写不受限，见 `:domain` [Clipboard] KDoc）—— 因此本测试的假实现
 * 不设 writable/systemRejects；错误透传只钉 `AutojsException` 原码一条。
 */
class ClipboardNamespaceHandlerTest {

    private class FakeClipboard : Clipboard {
        val writes = mutableListOf<String>()
        var stored: String? = null
        var failWith: AutojsException? = null

        override fun getText(): String? {
            failWith?.let { throw it }
            return stored
        }

        override fun setText(text: String) {
            failWith?.let { throw it }
            writes += text
            stored = text
        }
    }

    private val fake = FakeClipboard()
    private val handler = CapabilityNamespaces.clipboard(fake)

    private suspend fun call(method: String, payload: String?): BridgeResponse =
        handler.handle(BridgeRequest(1, "clipboard", method, payload, 5_000))

    private fun ok(r: BridgeResponse): String =
        assertInstanceOf(BridgeResponse.Ok::class.java, r).payload!!

    private fun errCode(r: BridgeResponse): String =
        assertInstanceOf(BridgeResponse.Err::class.java, r).errorCode

    @Test
    fun `读空回裸 JSON null——不拿空串冒充`() = runBlocking {
        assertEquals("null", ok(call("getText", null)), "空剪贴板 = JSON null")
        fake.stored = "hello"
        assertEquals("\"hello\"", ok(call("getText", null)))
        fake.stored = null
        Unit
    }

    @Test
    fun `空串是真值——写空串原样存读回空串`() = runBlocking {
        assertEquals("true", ok(call("setText", """{"text":""}""")))
        assertEquals(listOf(""), fake.writes)
        assertEquals("\"\"", ok(call("getText", null)), "空串是真值，不是缺失")
        assertEquals("true", ok(call("setText", """{"text":"hello"}""")))
        assertEquals("\"hello\"", ok(call("getText", null)))
        Unit
    }

    @Test
    fun `参数口径——缺参非串 ERR_INVALID_PARAM 且一次写都没发`() = runBlocking {
        fake.writes.clear()
        assertEquals(ErrorCode.ERR_INVALID_PARAM.code, errCode(call("setText", null)), "缺 payload")
        assertEquals(ErrorCode.ERR_INVALID_PARAM.code, errCode(call("setText", """{}""")), "缺 text")
        assertEquals(
            ErrorCode.ERR_INVALID_PARAM.code,
            errCode(call("setText", """{"text":128}""")),
            "text 不是串 —— 不替调用方猜型",
        )
        assertEquals(
            ErrorCode.ERR_INVALID_PARAM.code,
            errCode(call("setText", """{"text":null}""")),
            "显式 null 同样是参数错（缺失与 null 同形时按缺参处理）",
        )
        assertEquals(true, fake.writes.isEmpty(), "被拒的调用不碰 SPI")
        Unit
    }

    @Test
    fun `SPI 错误原码透传——不折叠成 INVALID_PARAM`() = runBlocking {
        fake.failWith = AutojsException(ErrorCode.ERR_IO, "剪贴板服务罢工", null)
        val e = assertInstanceOf(
            BridgeResponse.Err::class.java,
            call("getText", null),
        )
        assertEquals(ErrorCode.ERR_IO.code, e.errorCode)
        fake.failWith = null
        Unit
    }

    @Test
    fun `未知方法与别名都如实 ERR_NOT_IMPLEMENTED`() = runBlocking {
        assertEquals(ErrorCode.ERR_NOT_IMPLEMENTED.code, errCode(call("get", null)))
        assertEquals(ErrorCode.ERR_NOT_IMPLEMENTED.code, errCode(call("set", """{"text":"x"}""")))
        assertEquals(ErrorCode.ERR_NOT_IMPLEMENTED.code, errCode(call("clear", null)))
        assertEquals(ErrorCode.ERR_NOT_IMPLEMENTED.code, errCode(call("hasText", null)))
        Unit
    }
}
