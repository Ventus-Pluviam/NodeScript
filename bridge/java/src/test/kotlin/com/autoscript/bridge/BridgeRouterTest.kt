package com.autoscript.bridge

import com.autoscript.domain.bridge.BridgeRequest
import com.autoscript.domain.bridge.BridgeResponse
import com.autoscript.domain.core.ErrorCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BridgeRouterTest {

    private val router: BridgeRouter
        get() = BridgeRouter(RequestRegistry())

    @Test
    fun `echo round trip`() = runBlocking {
        val r = router
        assertTrue(r.register("echo") { req ->
            BridgeResponse.Ok(req.id, req.payload)
        })
        val resp = r.dispatch(BridgeRequest(7, "echo", "echo", "{\"x\":1}", 5_000))
        assertInstanceOf(BridgeResponse.Ok::class.java, resp)
        assertEquals(7L, (resp as BridgeResponse.Ok).id)
        assertEquals("{\"x\":1}", resp.payload)
    }

    @Test
    fun `unknown namespace returns ERR_NOT_IMPLEMENTED`() = runBlocking {
        val resp = router.dispatch(BridgeRequest(1, "ghost", "m", null, 5_000))
        val err = assertInstanceOf(BridgeResponse.Err::class.java, resp)
        assertEquals(ErrorCode.ERR_NOT_IMPLEMENTED.code, err.errorCode)
    }

    @Test
    fun `duplicate requestId rejected`() = runBlocking {
        val r = router
        // 首个 dispatch 以挂起态 handler 占住 requestId=5（未完成 → 仍在册），
        // 这样第二次同 id dispatch 才能命中 registry.register 的重复拒绝路径。
        val gate = CompletableDeferred<Unit>()
        r.register("echo") { req ->
            gate.await()                                   // 持续挂起，保持请求在册
            BridgeResponse.Ok(req.id, null)
        }
        val first = async { r.dispatch(BridgeRequest(5, "echo", "echo", null, 5_000)) }
        delay(50)                                           // 确保首个 dispatch 进入 registry
        val second = r.dispatch(BridgeRequest(5, "echo", "echo", null, 5_000))
        val err = assertInstanceOf(BridgeResponse.Err::class.java, second)
        assertEquals(ErrorCode.ERR_INVALID_PARAM.code, err.errorCode)
        gate.complete(Unit)                                // 放行首个，避免超时噪音
        first.await()
        Unit                                           // 显式收尾：void 返回值才被 JUnit5 视为测试
    }

    @Test
    fun `slow handler times out with ERR_TIMEOUT`() = runBlocking {
        val r = router
        r.register("slow") {
            delay(300)
            BridgeResponse.Ok(it.id, null)
        }
        val resp = r.dispatch(BridgeRequest(1, "slow", "m", null, 50))
        val err = assertInstanceOf(BridgeResponse.Err::class.java, resp)
        assertEquals(ErrorCode.ERR_TIMEOUT.code, err.errorCode)
    }

    @Test
    fun `handler exception mapped to ERR_INVALID_PARAM`() = runBlocking {
        val r = router
        r.register("boom") { throw IllegalStateException("炸了") }
        val resp = r.dispatch(BridgeRequest(1, "boom", "m", null, 5_000))
        val err = assertInstanceOf(BridgeResponse.Err::class.java, resp)
        assertEquals(ErrorCode.ERR_INVALID_PARAM.code, err.errorCode)
        assertTrue(err.detail.orEmpty().contains("炸了"))
    }
}