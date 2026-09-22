package com.autoscript.bridge

import com.autoscript.domain.bridge.BridgeRequest
import com.autoscript.domain.bridge.BridgeResponse
import com.autoscript.domain.core.Clock
import com.autoscript.domain.core.ErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 可推进的假时钟。 */
class FakeClock(var now: Long = 0L) : Clock {
    override fun nowMillis(): Long = now
}

class RequestRegistryTest {

    private fun reg(ttl: Long) = BridgeRequest(id = 1, namespace = "n", method = "m", payload = null, ttlMillis = ttl)

    @Test
    fun `completes in time`() {
        val clock = FakeClock()
        val r = RequestRegistry(clock)
        var got: BridgeResponse? = null
        assertTrue(r.register(reg(1_000)) { got = it })
        assertTrue(r.isRegistered(1))

        val resp = BridgeResponse.Ok(1, "{}")
        assertTrue(r.complete(1, resp))
        assertEquals(resp, got)
        assertFalse(r.isRegistered(1))
    }

    @Test
    fun `expireDue completes with ERR_TIMEOUT`() {
        val clock = FakeClock(now = 10_000)
        val r = RequestRegistry(clock)
        var got: BridgeResponse? = null
        assertTrue(r.register(reg(1_000)) { got = it })

        clock.now = 10_999   // 未到期
        assertEquals(0, r.expireDue())

        clock.now = 11_000   // 到期边界
        assertEquals(1, r.expireDue())
        val err = got as BridgeResponse.Err
        assertEquals(ErrorCode.ERR_TIMEOUT.code, err.errorCode)
        assertFalse(r.isRegistered(1))
    }

    @Test
    fun `duplicate requestId rejected`() {
        val r = RequestRegistry(FakeClock())
        assertTrue(r.register(reg(5_000)) {})
        assertFalse(r.register(reg(5_000)) {})
        assertEquals(1, r.size())
    }

    @Test
    fun `finishAll completes everything on shutdown`() {
        val r = RequestRegistry(FakeClock())
        val done = mutableListOf<BridgeResponse>()
        r.register(reg(9_000)) { done.add(it) }
        r.register(reg(9_000).copy(id = 2)) { done.add(it) }

        assertEquals(2, r.finishAll(ErrorCode.ERR_ENGINE_STOPPED))
        assertEquals(2, done.size)
        assertTrue(done.all { (it as? BridgeResponse.Err)?.errorCode == ErrorCode.ERR_ENGINE_STOPPED.code })
        assertEquals(0, r.size())
    }

    @Test
    fun `complete of missing id is noop`() {
        val r = RequestRegistry(FakeClock())
        assertNotNull(r)
        assertFalse(r.complete(99, BridgeResponse.Ok(99, null)))
    }
}