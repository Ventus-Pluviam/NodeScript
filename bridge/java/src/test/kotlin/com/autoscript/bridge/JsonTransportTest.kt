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

    @Test
    fun `截断输入抛契约异常 不抛越界`() {
        // 对端崩溃半截写：未闭合字符串 / 结尾转义符 / 截断 \u 都必须走 IllegalArgumentException 拒绝通道
        val truncated = listOf(
            """{"t":"req","id":1,"ns":"a11""",
            """{"t":"req","id":1,"ns":"a\""",
            """{"t":"req","id":1,"ns":"a\u12""",
            """{"t":"req","id":1,"ns":""",
        )
        for (raw in truncated) {
            assertThrows(IllegalArgumentException::class.java) {
                transport.decodeRequest(raw.toByteArray())
            }
        }
    }

    @Test
    fun `数字 payload 显式拒绝 不得静默丢为 null`() {
        // payload 契约是字符串（JSON 编码参数）；对端发来裸数字时静默丢弃会让 handler 收到无参请求
        val numericPayload = """{"t":"req","id":1,"ns":"images","m":"findOne","ttl":5000,"payload":123}"""
        assertThrows(IllegalArgumentException::class.java) {
            transport.decodeRequest(numericPayload.toByteArray())
        }
    }

    @Test
    fun `负 reqId 解码——kBootstrap 心跳的 -seq 命名空间`() {
        // kBootstrap 自动心跳用负 reqId（-seq），与 JS runtimeBridge 的正数 inflight 永不相撞
        // （§7.8）；TinyJson 数字分支含 '-' 前缀，此处钉住"负 id 不被拒"。
        val frame = """{"t":"req","id":-1,"ns":"engines","m":"heartbeat","ttl":2000,"payload":null,"side":null}"""
        val req = transport.decodeRequest(frame.toByteArray())
        assertEquals(-1L, req.id)
        assertEquals("engines", req.namespace)
        assertEquals("heartbeat", req.method)
    }

    @Test
    fun `addon 心跳帧金样——payload 字符串化后可解`() {
        // 金样 = bridge_addon.cc Invoke 转义后的实际产出（host 编译烟测逐字比对过）：
        // payload 在信封里是 JSON **字符串**，与 JS 侧 JSON.stringify 的形状一致。
        // 改 C++ 转义必须同批改本样（bridge_addon.cc 头注释已指回这里）。
        val golden = """{"t":"req","id":-1,"ns":"engines","m":"heartbeat","ttl":2000,"payload":"{\"runId\":7,\"seq\":1}","side":null}"""
        val req = transport.decodeRequest(golden.toByteArray())
        assertEquals(-1L, req.id)
        assertEquals("engines", req.namespace)
        assertEquals("heartbeat", req.method)
        assertEquals(2_000L, req.ttlMillis)
        assertEquals("""{"runId":7,"seq":1}""", req.payload, "payload 剥壳后须是原 JSON 文本")
    }

    @Test
    fun `payload 裸嵌对象整帧拒绝——addon 必须字符串化`() {
        // 修复前的坏形状：payload 直接嵌对象（无字符串化）。TinyJson readValue 无 '{' 分支
        // → 抛"非法值" → NewlineFrameServer 丢帧。钉住宿主侧：这种帧到不了 handler。
        val raw = """{"t":"req","id":-1,"ns":"engines","m":"heartbeat","ttl":2000,"payload":{"runId":7,"seq":1},"side":null}"""
        assertThrows(IllegalArgumentException::class.java) {
            transport.decodeRequest(raw.toByteArray())
        }
    }
}
