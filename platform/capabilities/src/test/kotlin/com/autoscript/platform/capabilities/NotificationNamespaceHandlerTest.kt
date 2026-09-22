package com.autoscript.platform.capabilities

import com.autoscript.domain.bridge.BridgeRequest
import com.autoscript.domain.bridge.BridgeResponse
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.system.NotificationPoster
import com.autoscript.domain.system.NotificationSpec
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

/**
 * `notification` 桥处理器测试（§12.2；JS 对偶 `bridge/js/src/notification.ts` +
 * `notification.test.cjs`）。
 *
 * 判据四件：
 * 1. **wire 形状**：post 发 `{id,text,title?}` 三字段原样到 SPI（缺 title 即 null，
 *    省略与显式 null 同义），改一侧另一侧就 undefined；
 * 2. **参数口径不碰 SPI**：缺/非数/越界的 id、缺/非串/空白的 text → `ERR_INVALID_PARAM`，
 *    且一次投递都没发出去；
 * 3. **错误不折叠**：SPI 的 `ERR_PERMISSION_DENIED` 原码透传（授权问题要能被识别并引导）；
 * 4. **不猜别名**：`notify`/`show`/`cancelAll` 等未约定方法 → `ERR_NOT_IMPLEMENTED`。
 *
 * 门禁语义的唯一出处是 SPI（handler 刻意不问 canPost，见 handler KDoc）——
 * 因此假实现自己判 postable，与 `AndroidNotificationPoster` 同构。
 */
class NotificationNamespaceHandlerTest {

    private class FakePoster : NotificationPoster {
        val posted = mutableListOf<NotificationSpec>()
        val cancelled = mutableListOf<Int>()
        var postable = true

        override fun canPost(): Boolean = postable

        override fun post(spec: NotificationSpec) {
            if (!postable) {
                throw AutojsException(ErrorCode.ERR_PERMISSION_DENIED, "通知未授权发送: id=${spec.id}", null)
            }
            posted += spec
        }

        override fun cancel(id: Int) {
            cancelled += id
        }
    }

    private val fake = FakePoster()
    private val handler = CapabilityNamespaces.notification(fake)

    private suspend fun call(method: String, payload: String?): BridgeResponse =
        handler.handle(BridgeRequest(1, "notification", method, payload, 5_000))

    private fun ok(r: BridgeResponse): String =
        assertInstanceOf(BridgeResponse.Ok::class.java, r).payload!!

    private fun errCode(r: BridgeResponse): String =
        assertInstanceOf(BridgeResponse.Err::class.java, r).errorCode

    @Test
    fun `post 三字段原样到 SPI——title 省略即 null`() = runBlocking {
        assertEquals("true", ok(call("post", """{"id":7,"text":"跑完了","title":"任务"}""")))
        assertEquals("true", ok(call("post", """{"id":8,"text":"无标题"}""")))
        assertEquals(
            listOf(
                NotificationSpec(id = 7, text = "跑完了", title = "任务"),
                NotificationSpec(id = 8, text = "无标题", title = null),
            ),
            fake.posted,
            "缺 title 与显式 null 同义",
        )
        Unit
    }

    @Test
    fun `参数口径——非法 id 与 text 全是 ERR_INVALID_PARAM 且不投递`() = runBlocking {
        assertEquals(ErrorCode.ERR_INVALID_PARAM.code, errCode(call("post", null)), "缺 payload")
        assertEquals(ErrorCode.ERR_INVALID_PARAM.code, errCode(call("post", """{"text":"x"}""")), "缺 id")
        assertEquals(ErrorCode.ERR_INVALID_PARAM.code, errCode(call("post", """{"id":"7","text":"x"}""")), "id 非数")
        assertEquals(ErrorCode.ERR_INVALID_PARAM.code, errCode(call("post", """{"id":1.5,"text":"x"}""")), "id 小数")
        assertEquals(
            ErrorCode.ERR_INVALID_PARAM.code,
            errCode(call("post", """{"id":2147483648,"text":"x"}""")),
            "id 越界",
        )
        assertEquals(ErrorCode.ERR_INVALID_PARAM.code, errCode(call("post", """{"id":1}""")), "缺 text")
        assertEquals(
            ErrorCode.ERR_INVALID_PARAM.code,
            errCode(call("post", """{"id":1,"text":128}""")),
            "text 非串",
        )
        assertEquals(
            ErrorCode.ERR_INVALID_PARAM.code,
            errCode(call("post", """{"id":1,"text":"   "}""")),
            "空白 text 是参数错，不是空通知",
        )
        assertEquals(
            ErrorCode.ERR_INVALID_PARAM.code,
            errCode(call("post", """{"id":1,"text":"x","title":9}""")),
            "title 非串",
        )
        assertEquals(ErrorCode.ERR_INVALID_PARAM.code, errCode(call("cancel", """{}""")), "cancel 缺 id")
        assertEquals(true, fake.posted.isEmpty(), "被拒的调用不投递")
        assertEquals(true, fake.cancelled.isEmpty())
        Unit
    }

    @Test
    fun `SPI 权限错误原码透传——不折叠成 INVALID_PARAM`() = runBlocking {
        fake.postable = false
        assertEquals("false", ok(call("canPost", null)), "探针回裸 JSON false")
        val denied = assertInstanceOf(
            BridgeResponse.Err::class.java,
            call("post", """{"id":7,"text":"跑完了"}"""),
        )
        assertEquals(ErrorCode.ERR_PERMISSION_DENIED.code, denied.errorCode)
        assertEquals(true, denied.detail!!.contains("未授权"), "detail 带现场")
        assertEquals(true, fake.posted.isEmpty(), "被拒的那次一个都没投")

        fake.postable = true
        assertEquals("true", ok(call("canPost", null)), "授权后探针回真")
        assertEquals("true", ok(call("post", """{"id":8,"text":"授权后"}""")), "授权后真能发")
        assertEquals(listOf(NotificationSpec(id = 8, text = "授权后", title = null)), fake.posted)
        Unit
    }

    @Test
    fun `cancel 幂等无回执——发到 SPI 即回 true，不编撤销成功`() = runBlocking {
        assertEquals("true", ok(call("cancel", """{"id":42}""")))
        assertEquals(listOf(42), fake.cancelled)
        assertEquals("true", ok(call("cancel", """{"id":999}""")), "没发过的 id 同样只是发一次撤销")
        assertEquals(listOf(42, 999), fake.cancelled)
        Unit
    }

    @Test
    fun `未知方法与 notify show 别名都如实 ERR_NOT_IMPLEMENTED`() = runBlocking {
        assertEquals(ErrorCode.ERR_NOT_IMPLEMENTED.code, errCode(call("notify", """{"id":1,"text":"x"}""")))
        assertEquals(ErrorCode.ERR_NOT_IMPLEMENTED.code, errCode(call("show", """{"id":1,"text":"x"}""")))
        assertEquals(ErrorCode.ERR_NOT_IMPLEMENTED.code, errCode(call("cancelAll", null)))
        Unit
    }
}
