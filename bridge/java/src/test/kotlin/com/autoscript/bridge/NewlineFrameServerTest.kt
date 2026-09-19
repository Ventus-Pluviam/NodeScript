package com.autoscript.bridge

import com.autoscript.domain.bridge.BridgeResponse
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NewlineFrameServerTest {

    private val transport = JsonTransport()
    private fun router() = BridgeRouter(RequestRegistry()).also { r ->
        r.register("echo") { req -> BridgeResponse.Ok(req.id, req.payload) }
    }

    private fun frame(bytes: ByteArray) = bytes + "\n".toByteArray(StandardCharsets.UTF_8)

    private fun readFrames(out: ByteArrayOutputStream): List<BridgeResponse> {
        val lines = out.toString(StandardCharsets.UTF_8.name()).split("\n").filter { it.isNotEmpty() }
        return lines.map { transport.decodeResponse(it.toByteArray(StandardCharsets.UTF_8)) }
    }

    @Test
    fun `echo over streams`() = runBlocking {
        val server = NewlineFrameServer(router())
        val requests = frame(transport.encodeRequest(com.autoscript.domain.bridge.BridgeRequest(1, "echo", "m", """{"x":1}""", 5_000))) +
            frame(transport.encodeRequest(com.autoscript.domain.bridge.BridgeRequest(2, "echo", "m", null, 5_000)))
        val input = ByteArrayInputStream(requests)
        val output = ByteArrayOutputStream()
        withTimeout(5_000) {
            server.serveConnection(input, output).join()
            // 帧任务挂 server scope：连接 Job 结束后再等 dispatch 落盘（最多 1s）。
            withTimeout(1_000) {
                while (readFrames(output).size < 2) delay(10)
            }
        }
        val responses = readFrames(output)
        assertEquals(2, responses.size)
        val byId = responses.associateBy { it.id }
        assertEquals(BridgeResponse.Ok(1, """{"x":1}"""), byId[1])
        assertEquals(BridgeResponse.Ok(2, null), byId[2])
        server.close()
    }

    @Test
    fun `unknown namespace returns ERR_NOT_IMPLEMENTED frame`() = runBlocking {
        val server = NewlineFrameServer(router())
        val requests = frame(transport.encodeRequest(com.autoscript.domain.bridge.BridgeRequest(3, "ghost", "m", null, 5_000)))
        val output = ByteArrayOutputStream()
        withTimeout(5_000) {
            server.serveConnection(ByteArrayInputStream(requests), output).join()
            withTimeout(1_000) {
                while (readFrames(output).isEmpty()) delay(10)
            }
        }
        val responses = readFrames(output)
        assertEquals(1, responses.size)
        val err = assertInstanceOf(BridgeResponse.Err::class.java, responses[0])
        assertEquals("ERR_NOT_IMPLEMENTED", err.errorCode)
        server.close()
    }

    @Test
    fun `malformed frame dropped, connection survives`() = runBlocking {
        val server = NewlineFrameServer(router())
        val good = frame(transport.encodeRequest(com.autoscript.domain.bridge.BridgeRequest(4, "echo", "m", null, 5_000)))
        val requests = "不是json\n".toByteArray(StandardCharsets.UTF_8) + good
        val output = ByteArrayOutputStream()
        withTimeout(5_000) {
            server.serveConnection(ByteArrayInputStream(requests), output).join()
            withTimeout(1_000) {
                while (readFrames(output).isEmpty()) delay(10)
            }
        }
        val responses = readFrames(output)
        assertEquals(1, responses.size)
        assertEquals(BridgeResponse.Ok(4, null), responses[0])
        server.close()
    }

    @Test
    fun `oversize frame closes connection`() = runBlocking {
        val errors = mutableListOf<String>()
        val server = NewlineFrameServer(router(), maxFrameBytes = 16) { errors += it }
        val requests = frame(ByteArray(64) { 'x'.code.toByte() })
        val output = ByteArrayOutputStream()
        withTimeout(5_000) { server.serveConnection(ByteArrayInputStream(requests), output).join() }
        assertEquals(0, readFrames(output).size)
        assertTrue(errors.isNotEmpty())
        server.close()
    }

    @Test
    fun `loopback TCP round trip with concurrent requests`() = runBlocking {
        val server = NewlineFrameServer(router())
        val listener = ServerSocket(0)
        try {
            server.acceptLoop(listener)
            val sockets = (1..8).map { Socket("127.0.0.1", listener.localPort) }
            try {
                val results = withTimeout(10_000) {
                    (1..8).map { i ->
                        async {
                            val sock = sockets[i - 1]
                            val req = com.autoscript.domain.bridge.BridgeRequest(i.toLong(), "echo", "m", """{"i":$i}""", 5_000)
                            val out = sock.getOutputStream()
                            out.write(frame(transport.encodeRequest(req)))
                            out.flush()
                            val line = sock.getInputStream().bufferedReader(StandardCharsets.UTF_8).readLine()
                                ?: throw IllegalStateException("连接提前关闭（i=$i）")
                            transport.decodeResponse(line.toByteArray(StandardCharsets.UTF_8))
                        }
                    }.awaitAll()
                }
                assertEquals(8, results.size)
                val byId = results.associateBy { it.id }
                for (i in 1..8) {
                    assertEquals(BridgeResponse.Ok(i.toLong(), """{"i":$i}"""), byId[i.toLong()])
                }
            } finally {
                sockets.forEach { runCatching { it.close() } }
            }
        } finally {
            listener.close()
            server.close()
        }
    }
}
