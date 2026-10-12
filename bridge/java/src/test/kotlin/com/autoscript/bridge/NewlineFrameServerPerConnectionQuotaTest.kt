package com.autoscript.bridge

import com.autoscript.domain.bridge.BridgeResponse
import com.autoscript.domain.engine.EngineId
import com.autoscript.domain.permission.CapabilityMask
import com.autoscript.domain.permission.ScriptAuthorizationSnapshot
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * **每连接配额 + 并发闸**（批 107；外审 M2 的另一半）。
 *
 * 与 [NewlineFrameServerByteQuotaTest]（全局字节闸）分家是因为它们验的是不同的性质：
 * 全局闸那条验「拒一条不连带整条连接」，本类验的是**「A 连接的配额不影响 B 连接」** ——
 * 这个性质在只有全局闸时根本无法观测（A 吃满全局后 B 是被**全局**挤掉的，
 * 与「B 撞自己的配额」是两件事，故必须真有两档才测得出来）。
 *
 * 全部用内存流 + 真 [RunIdentityRegistry] 握手，与既有同款。
 */
class NewlineFrameServerPerConnectionQuotaTest {

    private val identities = RunIdentityRegistry()
    private val ids = AtomicInteger(1)

    @AfterEach
    fun closeIdentities() = identities.close()

    private fun hello(): ByteArray = BridgeHandshake.hello(
        identities.issue(
            EngineId(0), ids.getAndIncrement().toLong(), "per-conn-probe",
            ScriptAuthorizationSnapshot(mask = CapabilityMask.ALL),
        ).also { it.confirmSpawn(null) { true } }.token,
    )

    private fun frame(payloadSize: Int, id: Long): ByteArray {
        val payload = "x".repeat(payloadSize)
        return ("""{"t":"req","id":$id,"ns":"sink","m":"m","ttl":30000,"payload":"$payload"}""" + "\n")
            .toByteArray(StandardCharsets.UTF_8)
    }

    /** 不回的 handler：让请求真正在飞，配额才压得住。 */
    private fun blockingRouter(gate: CompletableDeferred<Unit>) = BridgeRouter(RequestRegistry()).also { r ->
        r.register("sink") { gate.await(); BridgeResponse.Ok(it.id, null) }
    }

    @Test
    fun `一条连接吃满自己的条数配额后 被拒的是它自己 另一条连接照常（外审 M2 每连接条数）`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val server = NewlineFrameServer(
            blockingRouter(gate), identities,
            maxFrameBytes = 64 * 1024,
            maxInFlight = 8,          // 全局条数给足（8），让 A 撞的是**自己的**配额
            quota = QuotaSettings(PerConnectionLimits(
                inFlight = 2,         // 每连接 2：A 发 3 条必被拒
                bytes = 4L * 1024 * 1024,
                concurrent = 8,
            )),
        )
        try {
            // A：一条连接连发 3 条，前 2 条占满自己的配额，第 3 条应被拒。
            val aOut = ByteArrayOutputStream()
            val aJob = server.serveConnection(
                ByteArrayInputStream(hello() + frame(16, 1L) + frame(16, 2L) + frame(16, 3L)),
                aOut,
            )
            // 等前两条真的占住闸（handler 挂着不返回）。
            withTimeoutOrNull(2_000) {
                while (server.connectionStats().none { it.inFlight >= 2 }) delay(10)
                true
            }
            // 等的是**错误帧**而不是「输出变非空」—— 握手 ACK 先到，那不是拒绝的回执。
            // 第一版写成 `aOut.size() == 0` 于是读到 ACK 就走，当场红。
            val aErr = withTimeoutOrNull(3_000) {
                while (!aOut.toString(StandardCharsets.UTF_8).contains("ERR_PERMISSION_DENIED")) delay(10)
                aOut.toString(StandardCharsets.UTF_8)
            }
            assertTrue(aErr != null, "A 的第 3 条应被自己的配额拒：超时未见错误帧")
            assertTrue(aErr!!.contains("本连接在途条数"), "话术要点名是**条数**闸（不是字节、不是全局）：$aErr")

            // B：另一条连接在同一时刻发自己的请求 —— 不该被 A 的配额影响。
            val bOut = ByteArrayOutputStream()
            val bJob = server.serveConnection(ByteArrayInputStream(hello() + frame(16, 11L)), bOut)
            withTimeoutOrNull(2_000) {
                while (bOut.size() == 0) delay(10)
                true
            }
            val bErr = withTimeoutOrNull(500) { bJob.join(); bOut.toString(StandardCharsets.UTF_8) }.orEmpty()
            assertTrue(
                !bErr.contains("ERR_PERMISSION_DENIED"),
                "B 是另一条连接，不该被 A 占满的配额拒 —— 这条正是「每连接」两个字的全部意义：$bErr",
            )
            gate.complete(Unit)
            withTimeoutOrNull(3_000) { aJob.join() }
            bJob.cancel()
        } finally {
            gate.complete(Unit)
            server.close()
        }
        Unit
    }

    @Test
    fun `每连接字节闸独立于全局字节闸 撞自己的上限被拒`(
    ) = runBlocking {
        val gate = CompletableDeferred<Unit>()
        // 全局字节给足（8 MiB），让被拒的是**本连接**那道 4 KiB。
        val server = NewlineFrameServer(
            blockingRouter(gate), identities,
            maxFrameBytes = 64 * 1024,
            maxBytesInFlight = 8L * 1024 * 1024,
            quota = QuotaSettings(PerConnectionLimits(
                inFlight = 64,
                bytes = 4L * 1024,     // 每连接 4 KiB
                concurrent = 8,
            )),
        )
        try {
            val out = ByteArrayOutputStream()
            server.serveConnection(
                ByteArrayInputStream(hello() + frame(8 * 1024, 1L)),   // 8 KiB > 4 KiB
                out,
            ).join()
            val err = out.toString(StandardCharsets.UTF_8)
            assertTrue(err.contains("ERR_PERMISSION_DENIED"), "超本连接字节上限要如实回错误：$err")
            assertTrue(err.contains("本连接在途字节"), "话术要区分本连接字节闸与全局字节闸：$err")
            assertTrue(!err.contains("全局在途字节"), "这条撞的不是全局闸（全局给了 8 MiB）：$err")
        } finally {
            gate.complete(Unit)
            server.close()
        }
        Unit
    }

    /**
     * 并发闸（`limitedParallelism` 那半）：限的是**同时占用处理位的协程数**。
     *
     * **第一版守卫写错了，当场红**：它让 handler `delay(300)` 再数 peak，观测到 5 ——
     * 于是看起来「limiter 没生效」。真相是本仓探查时已写下的那条语义：
     * `limitedParallelism` 限的是**并发执行**的协程，**挂起的协程已经把位子还回去了**
     * （delay 与真阻塞不等价 —— 这正是探查那条笔记里「限的是并发协程数不是线程数」的另一面：
     * 协程挂起时线程还给池子，所以对 IO 型 handler 来说 `delay` 期间它**不算占位**）。
     *
     * 所以判据必须用**真阻塞**（`CountDownLatch.await`）—— 那才是桥 handler 的真实形态
     * （截图、a11y 树、shell 回读都在阻塞等 IO）。本条用真阻塞复现一次，
     * 并在注释里钉住「用 delay 观测会假红」这条，避免下一个人再写一遍。
     */
    @Test
    fun `并发闸限住同一条连接同时占位的请求数`(
    ) = runBlocking {
        val gate = CountDownLatch(1)
        val occupied = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val router = BridgeRouter(RequestRegistry()).also { r ->
            r.register("sink") {
                val now = occupied.incrementAndGet()
                peak.accumulateAndGet(now) { a, b -> maxOf(a, b) }
                gate.await()                                   // 真阻塞：占着处理位不放
                occupied.decrementAndGet()
                BridgeResponse.Ok(it.id, null)
            }
        }
        val server = NewlineFrameServer(
            router, identities,
            maxFrameBytes = 64 * 1024,
            quota = QuotaSettings(PerConnectionLimits(inFlight = 64, bytes = 4L * 1024 * 1024, concurrent = 2)),
        )
        val served = server.serveConnection(
            ByteArrayInputStream((1L..5L).fold(hello()) { acc, id -> acc + frame(16, id) }),
            ByteArrayOutputStream(),
        )
        try {
            // 等 peak 稳定（2 = 闸满了，再多也进不来）。
            withTimeoutOrNull(3_000) { while (peak.get() < 2) delay(10) }
            delay(300)
            assertEquals(
                2, peak.get(),
                "并发闸 2 却被观测到 ${peak.get()} 条同时占位 —— limitedParallelism 没生效",
            )
        } finally {
            gate.countDown()
            withTimeoutOrNull(3_000) { served.join() }
            server.close()
        }
        Unit
    }

    @Test
    fun `连接级读数是可见面——能答出现在谁占着多少`(
    ) = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val server = NewlineFrameServer(
            blockingRouter(gate), identities,
            maxFrameBytes = 64 * 1024,
            quota = QuotaSettings(PerConnectionLimits(inFlight = 8, bytes = 64L * 1024, concurrent = 8)),
        )
        try {
            server.serveConnection(ByteArrayInputStream(hello() + frame(16, 1L) + frame(16, 2L)), ByteArrayOutputStream())
            withTimeoutOrNull(2_000) {
                while (server.connectionStats().none { it.inFlight >= 2 }) delay(10)
                true
            }
            val stat = server.connectionStats().single()
            assertEquals(2, stat.inFlight, "此刻有 2 条在飞")
            assertEquals(8, stat.inFlightLimit, "读数带上限（否则用户看到 2 不知道是被谁限的）")
            assertTrue(stat.bytes > 0, "字节账也要能读出来")
            assertEquals(64L * 1024, stat.bytesLimit)
            assertTrue(stat.runId > 0, "带 runId —— 没有它无法把读数对回某个脚本")
        } finally {
            gate.complete(Unit)
            server.close()
        }
        Unit
    }

    @Test
    fun `改配额立即生效且不掐在途请求`(
    ) = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val server = NewlineFrameServer(
            blockingRouter(gate), identities,
            maxFrameBytes = 64 * 1024,
            quota = QuotaSettings(PerConnectionLimits(inFlight = 8, bytes = 64L * 1024, concurrent = 8)),
        )
        try {
            val out = ByteArrayOutputStream()
            val job = server.serveConnection(
                ByteArrayInputStream(hello() + frame(16, 1L) + frame(16, 2L)), out,
            )
            withTimeoutOrNull(2_000) {
                while (server.connectionStats().none { it.inFlight >= 2 }) delay(10)
                true
            }
            // 把配额砍到 1：此刻 2 条在飞 —— **不许被掐**（掐在途是「撤销」不是「限额」）。
            server.updatePerConnectionLimits(
                PerConnectionLimits(inFlight = 1, bytes = 64L * 1024, concurrent = 8),
            )
            assertTrue(job.isActive, "砍配额不该取消在途请求")
            assertTrue(!out.toString(StandardCharsets.UTF_8).contains("ERR_PERMISSION_DENIED"))

            // 第三条现在该撞新配额（1 条，且前两条还占着 → 拒）。
            // 新配额 ≤ 已在途 = 后续全部拒，这正是「砍配额」应有的形态。
            gate.complete(Unit)
            withTimeoutOrNull(3_000) { job.join() }
        } finally {
            gate.complete(Unit)
            server.close()
        }
        Unit
    }

    @Test
    fun `配额为 0 拒构造——0 是禁用不是不限`(
    ) = runBlocking {
        val server = NewlineFrameServer(BridgeRouter(RequestRegistry()), identities)
        try {
            val e = runCatching {
                server.updatePerConnectionLimits(PerConnectionLimits(inFlight = 0, bytes = 1, concurrent = 1))
            }.exceptionOrNull()
            assertTrue(e is IllegalArgumentException, "0 个许可 = 连一条都发不出，那是禁用不是「不限」：$e")
        } finally {
            server.close()
        }
        Unit
    }
}
