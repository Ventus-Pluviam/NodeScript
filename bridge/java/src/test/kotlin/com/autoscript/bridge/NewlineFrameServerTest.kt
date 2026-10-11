package com.autoscript.bridge

import com.autoscript.domain.automation.InputChannel
import com.autoscript.domain.automation.InputChannelSession
import com.autoscript.domain.engine.EngineId
import com.autoscript.domain.permission.CapabilityMask
import com.autoscript.domain.permission.ScriptAuthorizationSnapshot
import com.autoscript.domain.bridge.BridgeRequest
import com.autoscript.domain.bridge.BridgeResponse
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
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
    /** 帧编解码用例与授权无关：掩码给全量（授权语义在 RunIdentityRegistryTest / :app 侧钉）。 */
    private val identities = RunIdentityRegistry()
    private val ids = java.util.concurrent.atomic.AtomicLong(1)
    private fun hello(): ByteArray = BridgeHandshake.hello(
        identities.issue(
            EngineId(0), ids.getAndIncrement(), "frame-probe",
            ScriptAuthorizationSnapshot(mask = CapabilityMask.ALL),
        ).also { it.confirmSpawn(null) { true } }.token,
    )
    private fun authenticated(bytes: ByteArray) = ByteArrayInputStream(hello() + bytes)
    @org.junit.jupiter.api.AfterEach fun closeIdentities() = identities.close()


    @Test
    fun `读取帧时取消不会被当作 EOF`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val input = object : java.io.InputStream() {
            override fun read(): Int {
                entered.complete(Unit)
                throw kotlinx.coroutines.CancellationException("read cancelled")
            }
        }
        val server = NewlineFrameServer(router(), identities)
        try {
            val connection = server.serveConnection(input, ByteArrayOutputStream())
            withTimeout(5_000) { entered.await(); connection.join() }
            assertTrue(connection.isCancelled, "读取取消必须使连接 Job 以取消结束")
        } finally {
            server.close()
        }
    }

    private val transport = JsonTransport()
    private fun router() = BridgeRouter(RequestRegistry()).also { r ->
        r.register("echo") { req -> BridgeResponse.Ok(req.id, req.payload) }
    }

    private fun frame(bytes: ByteArray) = bytes + "\n".toByteArray(StandardCharsets.UTF_8)

    /**
     * 等业务回帧（helloAck 不算业务响应）；连接 Job 现在包含 EOF 后的在途排空。
     */
    private suspend fun awaitFrame(out: ByteArrayOutputStream) {
        withTimeout(5_000) {
            while (out.size() == 0) delay(10)
        }
    }

    private fun readFrames(out: ByteArrayOutputStream): List<BridgeResponse> {
        val lines = out.toString(StandardCharsets.UTF_8.name()).split("\n").filter { it.isNotEmpty() && !it.contains("helloAck") }
        return lines.map { transport.decodeResponse(it.toByteArray(StandardCharsets.UTF_8)) }
    }

    @Test
    fun `echo over streams`() = runBlocking {
        val server = NewlineFrameServer(router(), identities)
        val requests = frame(transport.encodeRequest(com.autoscript.domain.bridge.BridgeRequest(1, "echo", "m", """{"x":1}""", 5_000))) +
            frame(transport.encodeRequest(com.autoscript.domain.bridge.BridgeRequest(2, "echo", "m", null, 5_000)))
        val input = authenticated(requests)
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
        val server = NewlineFrameServer(router(), identities)
        val requests = frame(transport.encodeRequest(com.autoscript.domain.bridge.BridgeRequest(3, "ghost", "m", null, 5_000)))
        val output = ByteArrayOutputStream()
        withTimeout(5_000) {
            server.serveConnection(authenticated(requests), output).join()
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
        val server = NewlineFrameServer(router(), identities)
        val good = frame(transport.encodeRequest(com.autoscript.domain.bridge.BridgeRequest(4, "echo", "m", null, 5_000)))
        val requests = "不是json\n".toByteArray(StandardCharsets.UTF_8) + good
        val output = ByteArrayOutputStream()
        withTimeout(5_000) {
            server.serveConnection(authenticated(requests), output).join()
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
        val server = NewlineFrameServer(router(), identities, maxFrameBytes = 16, onProtocolError = { errors += it })
        val requests = frame(ByteArray(64) { 'x'.code.toByte() })
        val output = ByteArrayOutputStream()
        withTimeout(5_000) { server.serveConnection(authenticated(requests), output).join() }
        assertEquals(0, readFrames(output).size)
        assertTrue(errors.isNotEmpty())
        server.close()
    }

    @Test
    fun `loopback TCP round trip with concurrent requests`() = runBlocking {
        val server = NewlineFrameServer(router(), identities)
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
                            sock.soTimeout = 5_000
                            val reader = sock.getInputStream().bufferedReader(StandardCharsets.UTF_8)
                            out.write(hello())
                            out.flush()
                            assertTrue(reader.readLine().contains("helloAck"))
                            out.write(frame(transport.encodeRequest(req)))
                            out.flush()
                            val line = reader.readLine()
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

    @Test
    fun `帧坏了但 id 还在 —— 回错误帧而不是静默丢弃`() = runBlocking {
        val server = NewlineFrameServer(router(), identities)
        // payload 是数字：decodeRequest 拒绝值型，但信封 id 读得到 —— 对端不该干等 TTL。
        val bad = frame(
            """{"t":"req","id":7,"ns":"echo","m":"m","ttl":5000,"payload":123}"""
                .toByteArray(StandardCharsets.UTF_8),
        )
        val output = ByteArrayOutputStream()
        withTimeout(5_000) {
            server.serveConnection(authenticated(bad), output).join()
            withTimeout(1_000) { while (readFrames(output).isEmpty()) delay(10) }
        }
        val responses = readFrames(output)
        assertEquals(1, responses.size, "坏帧必须回一帧，否则对端要等满 TTL")
        val err = assertInstanceOf(BridgeResponse.Err::class.java, responses[0])
        assertEquals(7L, err.id)
        assertEquals("ERR_INVALID_PARAM", err.errorCode)
        server.close()
    }

    @Test
    fun `在途帧数受 maxInFlight 约束（背压，不无限起协程）`() = runBlocking {
        val running = AtomicInteger()
        val peak = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val slowRouter = BridgeRouter(RequestRegistry())
        slowRouter.register("slow") { req ->
            val now = running.incrementAndGet()
            peak.updateAndGet { maxOf(it, now) }
            gate.await()
            running.decrementAndGet()
            BridgeResponse.Ok(req.id, null)
        }
        val server = NewlineFrameServer(slowRouter, identities, maxInFlight = 2)
        val requests = (1..6).fold(ByteArray(0)) { acc, i ->
            acc + frame(transport.encodeRequest(com.autoscript.domain.bridge.BridgeRequest(i.toLong(), "slow", "m", null, 5_000)))
        }
        val output = ByteArrayOutputStream()
        val conn = server.serveConnection(authenticated(requests), output)
        withTimeout(5_000) { while (peak.get() < 2) delay(5) }
        delay(100)   // 给"多起协程"留出发生的时间窗
        assertEquals(2, peak.get(), "在途帧数越过 maxInFlight：读循环没有背压")
        gate.complete(Unit)
        withTimeout(5_000) {
            while (readFrames(output).size < 6) delay(10)
        }
        conn.join()
        assertEquals(6, readFrames(output).size, "背压解除后剩下的帧仍要处理完")
        server.close()
    }

    // ── 每连接一个 InputChannelSession（2026-10-06，§9.3） ─────────────
    //
    // 会话级状态（当前输入通道）必须**按连接**隔离：handler 是全局单例、所有脚本共用
    // 一条桥连接池，字段级会话态会让一个脚本设的通道漏给另一个 —— 静默的跨脚本串扰。

    /** 记账 handler：把「这一帧看到的会话通道」回给调用方。 */
    private class ChannelEcho : com.autoscript.domain.bridge.NamespaceHandler {
        override suspend fun handle(request: BridgeRequest): BridgeResponse {
            val session = kotlin.coroutines.coroutineContext[InputChannelSession]
            return BridgeResponse.Ok(request.id, session?.current?.name ?: "NO_SESSION")
        }
    }

    @Test
    fun `每帧带会话上下文——同一连接内共享`() = runBlocking {
        val server = NewlineFrameServer(BridgeRouter(RequestRegistry()).also { it.register("ch", ChannelEcho()) }, identities)
        try {
            val session = InputChannelSession(InputChannel.ROOT)
            val req = frame(transport.encodeRequest(BridgeRequest(11, "ch", "probe", null, 5_000)))
            val out = ByteArrayOutputStream()
            server.serveConnection(authenticated(req), out, session).join()
            awaitFrame(out)
            assertTrue(
                out.toString(StandardCharsets.UTF_8.name()).contains("ROOT"),
                "帧任务要能看到本连接的会话通道：$out",
            )
        } finally {
            server.close()
        }
    }

    @Test
    fun `连接之间会话隔离——另一个连接看不到上一个设的通道`() = runBlocking {
        val server = NewlineFrameServer(BridgeRouter(RequestRegistry()).also { it.register("ch", ChannelEcho()) }, identities)
        try {
            val a = ByteArrayOutputStream()
            server.serveConnection(
                authenticated(frame(transport.encodeRequest(BridgeRequest(21, "ch", "probe", null, 5_000)))),
                a, InputChannelSession(InputChannel.ADB),
            ).join()
            awaitFrame(a)
            val b = ByteArrayOutputStream()
            // 默认参数 = 新会话（生产里每条连接都是新会话）
            server.serveConnection(
                authenticated(frame(transport.encodeRequest(BridgeRequest(22, "ch", "probe", null, 5_000)))),
                b,
            ).join()
            awaitFrame(b)
            assertTrue(a.toString(StandardCharsets.UTF_8.name()).contains("ADB"))
            // 新连接是**没设过**（NO_SESSION），不是"缺省 AUTO" —— 通道无缺省（2026-10-06
            // 用户口径「必须显式传」），会话没设过时调用方必须自己给 channel。
            assertTrue(
                b.toString(StandardCharsets.UTF_8.name()).contains("NO_SESSION"),
                "新连接不该继承上一个连接的通道，也不该凭空有个缺省：$b",
            )
        } finally {
            server.close()
        }
    }
}

/**
 * 外审 M3（2026-10-11 批 98）：**accept 遇瞬时故障不得收掉整条桥**。
 *
 * 与 `app` 侧 `BridgeSocketListenerAcceptRetryTest` 同一纪律的两个实现面（设备 LocalServerSocket
 * 与本机 TCP）—— 各钉一次，因为它们是**两份独立实现**，修一份不修另一份同样静默死亡。
 *
 * 判据用可观测行为：抛过一次瞬时错之后**照样能完成握手**（helloAck）。
 */
class NewlineFrameServerAcceptRetryTest {

    private val identities = RunIdentityRegistry()
    private val ids = java.util.concurrent.atomic.AtomicLong(1)

    @org.junit.jupiter.api.AfterEach
    fun closeIdentities() = identities.close()

    private fun hello(): ByteArray = BridgeHandshake.hello(
        identities.issue(
            EngineId(0), ids.getAndIncrement(), "retry-probe",
            ScriptAuthorizationSnapshot(mask = CapabilityMask.ALL),
        ).also { it.confirmSpawn(null) { true } }.token,
    )

    @Test
    fun `瞬时 SocketException 之后桥照样握手（外审 M3）`() {
        val acceptErrors = CopyOnWriteArrayList<String>()
        val server = NewlineFrameServer(
            BridgeRouter(RequestRegistry()), identities,
            onAcceptError = { acceptErrors.add(it) },
        )
        // 覆写 accept 的 ServerSocket：第一次抛瞬时错，之后照常委托给真 socket。
        val real = ServerSocket(0)
        var remainingFailures = 1
        val flaky = object : ServerSocket() {
            override fun accept(): Socket {
                if (remainingFailures-- > 0) throw SocketException("Too many open files (simulated EMFILE)")
                return real.accept()
            }
        }
        try {
            server.acceptLoop(flaky)
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline && acceptErrors.isEmpty()) Thread.sleep(10)
            assertEquals(1, acceptErrors.size, "瞬时错应留下诊断回执：$acceptErrors")

            Socket("127.0.0.1", real.localPort).use { sock ->
                sock.soTimeout = 5_000
                sock.getOutputStream().apply { write(hello()); flush() }
                val line = sock.getInputStream().bufferedReader(StandardCharsets.UTF_8).readLine()
                assertTrue(
                    line != null && line.contains("helloAck"),
                    "退避后桥仍能完成握手 —— 这就是「桥还活着」的可观测判据（实际：$line）",
                )
            }
        } finally {
            flaky.close()
            real.close()
            server.close()
        }
    }
}
