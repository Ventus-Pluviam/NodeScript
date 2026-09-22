package com.autoscript.platform.capabilities

import com.autoscript.domain.automation.ScreenSnapshot
import com.autoscript.domain.bridge.BridgeRequest
import com.autoscript.domain.bridge.BridgeResponse
import com.autoscript.domain.core.ErrorCode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 挂载缝薄转接验证（§4.1/§6）：[CapabilityNamespaces] 把本模块 handler 的自有
 * Request/Response 折成桥信封，**逐字段透传、不改写**。
 *
 * 判据：
 * - Ok 载荷原样到达（a11y 命中体 / screen 帧三字段），id 回显请求侧 id；
 * - Err 的 code/detail 原样到达（ERR_NOT_IMPLEMENTED / ERR_INVALID_PARAM /
 *   ERR_SCREEN_LOCKED 等分类），不把错误折叠成 INVALID_PARAM；
 * - 未知方法 → ERR_NOT_IMPLEMENTED（handler 自己的兜底，转接层不加戏）。
 */
class CapabilityNamespacesTest {

    private suspend fun seedTree(): InMemoryUiTree {
        val tree = InMemoryUiTree()
        tree.add(
            InMemoryUiTree.Attrs(text = "启动", className = "Button", clickable = true),
        )
        return tree
    }

    @Test
    fun `a11y 挂载缝透传 Ok 载荷与 id`() = runBlocking {
        val tree = seedTree()
        val handler = CapabilityNamespaces.a11y(tree, tree)

        val resp = handler.handle(
            BridgeRequest(7, "a11y", "findOne", """{"conditions":{"text":"启动"}}""", 5_000),
        )

        val ok = assertInstanceOf(BridgeResponse.Ok::class.java, resp)
        assertEquals(7L, ok.id)
        val o = A11yBridgeJson.decodeObject(ok.payload!!)
        val ref = (o["ref"] as A11yBridgeJson.Value.Obj).fields
        assertEquals("1", (ref["refId"] as A11yBridgeJson.Value.N).raw)
        Unit                                           // 显式收尾：void 返回值才被 JUnit5 视为测试
    }

    @Test
    fun `a11y 挂载缝透传错误码不改写`() = runBlocking {
        val tree = seedTree()
        val handler = CapabilityNamespaces.a11y(tree, tree)

        val notFound = assertInstanceOf(
            BridgeResponse.Err::class.java,
            handler.handle(BridgeRequest(1, "a11y", "findOne", """{"conditions":{"text":"不存在"}}""", 5_000)),
        )
        assertEquals(ErrorCode.ERR_NOT_FOUND.code, notFound.errorCode)

        val unknownMethod = assertInstanceOf(
            BridgeResponse.Err::class.java,
            handler.handle(BridgeRequest(2, "a11y", "fly", "{}", 5_000)),
        )
        assertEquals(ErrorCode.ERR_NOT_IMPLEMENTED.code, unknownMethod.errorCode)
        assertTrue(unknownMethod.detail!!.contains("未知 a11y 方法"), "detail 原样透传: ${unknownMethod.detail}")

        val badPayload = assertInstanceOf(
            BridgeResponse.Err::class.java,
            handler.handle(BridgeRequest(3, "a11y", "findOne", "not-json", 5_000)),
        )
        assertEquals(ErrorCode.ERR_INVALID_PARAM.code, badPayload.errorCode)
        Unit                                           // 显式收尾：void 返回值才被 JUnit5 视为测试
    }

    @Test
    fun `a11y 缝树与动作分离注入后各自生效`() = runBlocking {
        // 树只给"登录"，动作只认另一棵树的句柄 —— 两块 SPI 各自生效，
        // 证明转接缝不是把内存树写死的（真实现替换 = 换这两个参数）。
        val tree = InMemoryUiTree()
        tree.add(InMemoryUiTree.Attrs(text = "登录", className = "Button", clickable = true))
        val actions = InMemoryUiTree()
        val ref = actions.add(InMemoryUiTree.Attrs(text = "注销", className = "Button", clickable = true))
        val handler = CapabilityNamespaces.a11y(tree, actions)

        val found = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            handler.handle(BridgeRequest(11, "a11y", "findOne", "{\"conditions\":{\"text\":\"登录\"}}", 5_000)),
        )
        assertEquals(11L, found.id)

        val rid = ref.refId
        val clickOther = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            handler.handle(
                BridgeRequest(12, "a11y", "click", "{\"ref\":{\"refId\":" + rid + ",\"generation\":1}}", 5_000),
            ),
        )
        assertEquals(12L, clickOther.id, "动作走 actions 侧：tree 里没有该句柄也照样点得动")
        Unit                                           // 显式收尾：void 返回值才被 JUnit5 视为测试
    }

    @Test
    fun `screen 挂载缝穿透屏幕策略的分类错误`() = runBlocking {
        val locked = ScreenshotSource(
            object : ScreenshotSource.FrameProducer {
                override suspend fun snapshot(): ScreenSnapshot =
                    ScreenSnapshot(locked = true, secureForeground = false, hasWindows = true)
                override suspend fun produce(width: Int, height: Int): ProducedFrame =
                    ProducedFrame(byteArrayOf(1), 1080, 2400)
            },
        )
        val handler = CapabilityNamespaces.screen(locked)

        val resp = assertInstanceOf(
            BridgeResponse.Err::class.java,
            handler.handle(BridgeRequest(4, "screen", "capture", null, 5_000)),
        )

        assertEquals(ErrorCode.ERR_SCREEN_LOCKED.code, resp.errorCode, "§8.8 分类错误不改写成黑图")
        assertEquals(4L, resp.id)
        Unit                                           // 显式收尾：void 返回值才被 JUnit5 视为测试
    }

    @Test
    fun `screen 挂载缝透传会话载荷`() = runBlocking {
        var now = 1_000L
        val source = ScreenshotSource(
            object : ScreenshotSource.FrameProducer {
                override suspend fun snapshot(): ScreenSnapshot = ScreenSnapshot(false, false, true)
                override suspend fun produce(width: Int, height: Int): ProducedFrame =
                    ProducedFrame(byteArrayOf(1), 1080, 2400)
            },
            clock = { now += 1_000; now },
        )
        val handler = CapabilityNamespaces.screen(source)

        val start = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            handler.handle(BridgeRequest(5, "screen", "startCapturer", null, 5_000)),
        )
        val sessionId = ((A11yBridgeJson.decodeObject(start.payload!!)["session"] as A11yBridgeJson.Value.Obj)
            .fields["refId"] as A11yBridgeJson.Value.N).raw

        val frame = assertInstanceOf(
            BridgeResponse.Ok::class.java,
            handler.handle(
                BridgeRequest(6, "screen", "nextFrame", """{"session":{"refId":$sessionId,"generation":1}}""", 5_000),
            ),
        )
        val o = A11yBridgeJson.decodeObject(frame.payload!!)
        assertEquals("1080", (o["width"] as A11yBridgeJson.Value.N).raw)
        assertEquals("2400", (o["height"] as A11yBridgeJson.Value.N).raw)

        val unknownSession = assertInstanceOf(
            BridgeResponse.Err::class.java,
            handler.handle(BridgeRequest(8, "screen", "nextFrame", """{"session":{"refId":999,"generation":1}}""", 5_000)),
        )
        assertEquals(ErrorCode.ERR_NOT_FOUND.code, unknownSession.errorCode)
        Unit                                           // 显式收尾：void 返回值才被 JUnit5 视为测试
    }
}
