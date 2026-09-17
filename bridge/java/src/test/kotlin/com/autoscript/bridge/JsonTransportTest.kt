package com.autoscript.bridge

import com.autoscript.domain.bridge.BridgeRequest
import com.autoscript.domain.bridge.BridgeResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class JsonTransportTest {

    private val transport = JsonTransport()

    @Test
    fun `request round trip with escaped payload`() {
        val req = BridgeRequest(
            id = 42,
            namespace = "a11y",
            method = "findOne",
            payload = """{"text":"他说 \"你好\"\n换行\t制表符"}""",
            ttlMillis = 5_000,
        )
        val back = transport.decodeRequest(transport.encodeRequest(req))
        assertEquals(req, back)
    }

    @Test
    fun `request round trip with null payload`() {
        val req = BridgeRequest(1, "images", "capture", null, 3_000)
        assertEquals(req, transport.decodeRequest(transport.encodeRequest(req)))
    }

    @Test
    fun `ok response round trip`() {
        val resp = BridgeResponse.Ok(9, """{"frames":2}""")
        assertEquals(resp, transport.decodeResponse(transport.encodeResponse(resp)))
    }

    @Test
    fun `ok response with unicode`() {
        val resp = BridgeResponse.Ok(9, "flow.js 世界🌏")
        assertEquals(resp, transport.decodeResponse(transport.encodeResponse(resp)))
    }

    @Test
    fun `err response round trip`() {
        val resp = BridgeResponse.Err(3, "ERR_STALE_HANDLE", "控件已离开窗口树")
        assertEquals(resp, transport.decodeResponse(transport.encodeResponse(resp)))
    }

    @Test
    fun `unknown field rejected`() {
        val evil = """{"t":"req","id":1,"ns":"a11y","m":"x","ttl":1,"__proto__":"pwn"}"""
        assertThrows(IllegalArgumentException::class.java) {
            transport.decodeRequest(evil.toByteArray())
        }
    }

    @Test
    fun `invalid type rejected`() {
        val notReq = """{"t":"ok","id":1,"payload":null}"""
        assertThrows(IllegalArgumentException::class.java) {
            transport.decodeRequest(notReq.toByteArray())
        }
    }
}