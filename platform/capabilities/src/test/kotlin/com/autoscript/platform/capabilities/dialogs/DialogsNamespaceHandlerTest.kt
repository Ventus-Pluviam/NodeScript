package com.autoscript.platform.capabilities.dialogs

import com.autoscript.domain.bridge.BridgeRequest
import com.autoscript.domain.bridge.BridgeResponse
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.system.DialogHost
import com.autoscript.domain.system.DialogMode
import com.autoscript.domain.system.DialogOutcome
import com.autoscript.domain.system.DialogPromptRequest
import com.autoscript.domain.system.DialogChooseRequest
import com.autoscript.domain.system.DialogChoice
import com.autoscript.domain.json.DomainJson
import com.autoscript.platform.capabilities.CapabilityNamespaces
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `dialogs` 桥处理器测试（docs §9.4/§12.2，JS 对偶 `extras.ts`）——
 * 自 SystemNamespacesTest 拆出（dialogs 留 capabilities，
 * shell/device/app/floatingWindow 随实现迁 :platform:system）。
 *
 * 判据与原文件同一套：载荷形状与 JS facade 逐字对齐（`{value,confirmed}`、裸下标
 * 取消 -1）、错误分类不被抹平、不伪造可用。
 */
class DialogsNamespaceHandlerTest {

    private class FakeDialogs(
        private val outcome: DialogOutcome = DialogOutcome("abc", true),
        private val choice: DialogChoice = DialogChoice(1),
    ) : DialogHost {
        override suspend fun prompt(request: DialogPromptRequest): DialogOutcome = outcome
        override suspend fun choose(request: DialogChooseRequest): DialogChoice = choice
    }

    // ── dialogs ─────────────────────────────────────────────────────

    @Test
    fun `dialogs prompt 回 value confirmed 两字段`() = runBlocking {
        val h = CapabilityNamespaces.dialogs(FakeDialogs(outcome = DialogOutcome("张三", true)))
        val ok = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            h.handle(BridgeRequest(1, "dialogs", "prompt", """{"title":"名字","placeholder":"请输入"}""", 5_000)),
        )
        val o = DomainJson.decodeObject(ok.payload!!)
        assertEquals("张三", (o["value"] as DomainJson.Value.S).v)
        assertTrue((o["confirmed"] as DomainJson.Value.B).v)
        Unit
    }

    @Test
    fun `dialogs prompt 取消折叠为 value null confirmed false`() = runBlocking {
        val h = CapabilityNamespaces.dialogs(FakeDialogs(outcome = DialogOutcome.CANCELLED))
        val ok = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            h.handle(BridgeRequest(1, "dialogs", "prompt", """{"title":"名字"}""", 5_000)),
        )
        val o = DomainJson.decodeObject(ok.payload!!)
        assertTrue(o["value"] is DomainJson.Value.Null)
        assertFalse((o["confirmed"] as DomainJson.Value.B).v)
        Unit
    }

    @Test
    fun `dialogs choose 直出下标，取消即 -1`() = runBlocking {
        val h = CapabilityNamespaces.dialogs(FakeDialogs(choice = DialogChoice(2)))
        val ok = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            h.handle(BridgeRequest(1, "dialogs", "choose", """{"title":"选","options":["a","b","c"]}""", 5_000)),
        )
        assertEquals("2", (DomainJson.decode(ok.payload!!) as DomainJson.Value.N).raw)

        val cancelled = CapabilityNamespaces.dialogs(FakeDialogs(choice = DialogChoice.CANCELLED))
        val c = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            cancelled.handle(BridgeRequest(2, "dialogs", "choose", """{"title":"选","options":["a"]}""", 5_000)),
        )
        assertEquals("-1", (DomainJson.decode(c.payload!!) as DomainJson.Value.N).raw)
        Unit
    }

    @Test
    fun `dialogs 空标题与空选项在构造期即拒`() = runBlocking {
        val h = CapabilityNamespaces.dialogs(FakeDialogs())
        val noTitle = assertInstanceOf(
            BridgeResponse.Err::class.java,
            h.handle(BridgeRequest(1, "dialogs", "prompt", """{"title":""}""", 5_000)),
        )
        assertEquals(ErrorCode.ERR_INVALID_PARAM.code, noTitle.errorCode)
        val noOptions = assertInstanceOf(
            BridgeResponse.Err::class.java,
            h.handle(BridgeRequest(2, "dialogs", "choose", """{"title":"选","options":[]}""", 5_000)),
        )
        assertEquals(ErrorCode.ERR_INVALID_PARAM.code, noOptions.errorCode)
        val notArray = assertInstanceOf(
            BridgeResponse.Err::class.java,
            h.handle(BridgeRequest(3, "dialogs", "choose", """{"title":"选","options":"a"}""", 5_000)),
        )
        assertEquals(ErrorCode.ERR_INVALID_PARAM.code, notArray.errorCode)
        Unit
    }

    @Test
    fun `dialogs BAL 降级路径失败回分类错误`() = runBlocking {
        // overlay 未授权且通知不可达 → 宿主如实抛 ERR_PERMISSION_DENIED，handler 原码透传
        val h = CapabilityNamespaces.dialogs(
            object : DialogHost {
                override suspend fun prompt(request: DialogPromptRequest): DialogOutcome =
                    throw AutojsException(ErrorCode.ERR_PERMISSION_DENIED, "overlay 未授权且通知降级不可达")
                override suspend fun choose(request: DialogChooseRequest): DialogChoice =
                    throw AutojsException(ErrorCode.ERR_SERVICE_DISABLED, "无对话框宿主")
            },
        )
        val err = assertInstanceOf(
            BridgeResponse.Err::class.java,
            h.handle(BridgeRequest(1, "dialogs", "prompt", """{"title":"名字","mode":"overlay"}""", 5_000)),
        )
        assertEquals("ERR_PERMISSION_DENIED", err.errorCode)
        val err2 = assertInstanceOf(
            BridgeResponse.Err::class.java,
            h.handle(BridgeRequest(2, "dialogs", "choose", """{"title":"选","options":["a"]}""", 5_000)),
        )
        assertEquals("ERR_SERVICE_DISABLED", err2.errorCode)
        Unit
    }


    @Test
    fun `dialogs 的 mode 缺省是 auto（JS 不传 mode 不抛错）`() = runBlocking {
        var seenMode: DialogMode? = null
        val h = CapabilityNamespaces.dialogs(
            object : DialogHost {
                override suspend fun prompt(request: DialogPromptRequest): DialogOutcome {
                    seenMode = request.mode
                    return DialogOutcome.CANCELLED
                }
                override suspend fun choose(request: DialogChooseRequest): DialogChoice = DialogChoice.CANCELLED
            },
        )
        h.handle(BridgeRequest(2, "dialogs", "prompt", """{"title":"t"}""", 5_000))
        assertEquals(DialogMode.AUTO, seenMode)
        Unit
    }
}
