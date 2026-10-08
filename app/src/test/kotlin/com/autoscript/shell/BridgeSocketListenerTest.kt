package com.autoscript.shell

import com.autoscript.bridge.BridgeHandshake
import com.autoscript.domain.engine.EngineId
import com.autoscript.domain.permission.CapabilityMask
import com.autoscript.domain.permission.ScriptAuthorizationSnapshot
import com.autoscript.domain.scripts.InMemoryIntentStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * [BridgeSocketListener] 缝面验证（§7.5 生产桥监听）：绑定降级、uid 门禁 fail-closed、
 * 帧经 [NewlineFrameServer] 入 Router 回写、关断/换代。
 * 全走假 [BridgeSocketBinder] —— android.* 运行期是桩，Android 面薄转接不在本处碰
 * （它的逻辑量 = 0，逻辑都在被本测覆盖的监听器侧）。
 */
class BridgeSocketListenerTest {

    @TempDir
    lateinit var dir: Path

    /**
     * 本类钉的是 socket 监听缝（绑定降级/uid 门禁/关断），与授权档无关 ——
     * 签发一律给全量快照；授权语义在 `AppShellAuthorizationTest` 与 `RunIdentityRegistryTest` 钉。
     */
    private fun probeAuthorization() = ScriptAuthorizationSnapshot(mask = CapabilityMask.ALL)

    /** 假连接：预填输入帧、截留输出、close 置标（拒收/serve 收尾两条路都可观测）。 */
    private class FakeConn(
        override val peerUid: Int,
        frame: String = "",
        override val peerPid: Int? = null,
    ) : AcceptedBridgeConnection {
        val closed = AtomicBoolean(false)
        override val input: InputStream = ByteArrayInputStream(frame.toByteArray())
        override val output: ByteArrayOutputStream = ByteArrayOutputStream()
        override fun close() {
            closed.set(true)
        }

        /** 读响应：与 NewlineFrameServer 写回的 `synchronized(output)` 同锁（可见性保证）。 */
        fun response(): String = synchronized(output) { output.toString(StandardCharsets.UTF_8) }
    }

    /** 用 latch 真阻塞读取；关闭必须唤醒它，不能靠协程取消碰巧结束。 */
    private class BlockingConn(prefix: ByteArray) : AcceptedBridgeConnection {
        override val peerUid = 1_000
        val waiting = CountDownLatch(1)
        val closed = CountDownLatch(1)
        private val initial = ByteArrayInputStream(prefix)
        override val input = object : InputStream() {
            override fun read(bytes: ByteArray, off: Int, len: Int): Int {
                if (initial.available() > 0) return initial.read(bytes, off, len)
                waiting.countDown()
                check(closed.await(5, TimeUnit.SECONDS)) { "换壳没有关闭阻塞连接" }
                return -1
            }
            override fun read(): Int = ByteArray(1).let { if (read(it, 0, 1) < 0) -1 else it[0].toInt() and 255 }
        }
        override val output = ByteArrayOutputStream()
        override fun close() { closed.countDown() }
    }

    /** 假监听面：accept 从队列取（带短轮询，close 后回 null 唤醒循环）。 */
    private class FakeBound : BoundBridgeSocket {
        private val queue = LinkedBlockingQueue<AcceptedBridgeConnection>()
        private val closed = AtomicBoolean(false)

        fun enqueue(conn: AcceptedBridgeConnection) {
            queue.put(conn)
        }

        override fun accept(): AcceptedBridgeConnection? {
            while (!closed.get()) {
                val conn = queue.poll(20, TimeUnit.MILLISECONDS) ?: continue
                // 取到后复核 closed：close 恰好落在 poll 等待窗内时，这个连接是
                // "关后入队"的 —— 服务端从未拥有它（生产面等价行为：LocalServerSocket
                // .close 后 backlog 由内核拒，accept 根本拿不到），直接回 null，
                // 不带出去再关。否则"停 accept"用例构成 20ms 轮询窗竞态：CI 负载高时
                // accept 线程恰在 poll 中，late 入队即被取走、经实现侧 `if (!running)`
                // 分支关掉，`late.closed` 翻 true 而红 —— 2026-09-30 CI 实测红一次。
                // （实现侧那个分支是生产面 fd 安全网，不动；假件无 fd，丢弃即等价。）
                if (closed.get()) return null
                return conn
            }
            return null
        }

        override fun close() {
            closed.set(true)
        }
    }

    private class RecordingProvider : com.autoscript.appservice.scheduler.core.SchedulerProvider {
        override suspend fun registerTrigger(
            targetFireAtMillis: Long,
            taskId: String,
        ): com.autoscript.appservice.scheduler.core.TriggerHandle =
            com.autoscript.appservice.scheduler.core.TriggerHandle { }

        override suspend fun cancelTrigger(
            handle: com.autoscript.appservice.scheduler.core.TriggerHandle,
        ) = handle.cancel()
    }

    private fun kit(): AssembledShell = AppShellKit.assemble(
        filesDir = dir.resolve("files"),
        cacheDir = dir.resolve("cache"),
        intentStore = InMemoryIntentStore(),
        schedulerProvider = RecordingProvider(),
        screenGate = ScreenGate.AllowAll,
    )

    /** 轮询到条件成立（accept 循环在自己线程上跑，测试线程只等观测面）。 */
    private fun await(what: String, timeoutMillis: Long = 5_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(20)
        }
        throw AssertionError("超时（${timeoutMillis}ms）未观测到：$what")
    }

    @Test
    fun `bind 失败回 null——调用方离线降级不炸装配`() {
        assertNull(
            BridgeSocketListener.bind("name-taken", { null }, myUid = 1_000),
            "binder 回 null（名字被抢/权限）→ 整体 null：不注入 hostSocketName，绝不 exit 3",
        )
    }

    @Test
    fun `默认名按 uid 隔离且无斜杠——main cpp 走 abstract 分支`() {
        assertEquals("autoscript.bridge.10123", BridgeSocketListener.defaultName(10123))
        // '/' 开头会被 main.cpp 当文件系统路径 —— 设备面必须是 abstract 名
        assertFalse(BridgeSocketListener.defaultName(1000).contains("/"))
    }

    @Test
    fun `uid 不符拒收——不 serve 计数加一 连接必关`() {
        val bound = FakeBound()
        val listener = BridgeSocketListener.bind("gate", { bound }, myUid = 1_000)!!
        kit().use { s ->
            listener.start(s.shell)
            val evil = FakeConn(
                peerUid = 99_999,
                frame = """{"t":"req","id":1,"ns":"engines","m":"heartbeat","ttl":2000,"payload":null,"side":null}""" + "\n",
            )
            bound.enqueue(evil)
            await("异 uid 连接被拒收关断") { evil.closed.get() }
            assertEquals(1, listener.rejectedCount(), "拒收必须计数（诊断面：撞门 vs 连接不稳）")
            assertEquals(0, evil.output.size(), "拒收的连接绝不 serve（输出零字节）")
        }
        listener.close()
    }

    @Test
    fun `uid 相符——帧入 Router 响应回写 连接随 serve 收尾关闭`() {
        val bound = FakeBound()
        val listener = BridgeSocketListener.bind("serve", { bound }, myUid = 1_000)!!
        kit().use { s ->
            listener.start(s.shell)
            // engines.heartbeat 对不在途 runId 如实回 Ok false（§8.4：不是调用方错误，不 4xx）
            val frame =
                """{"t":"req","id":7,"ns":"engines","m":"heartbeat","ttl":2000,"payload":"{\"runId\":42,\"seq\":1}","side":null}""" + "\n"
            val token = s.shell.identities.issue(EngineId(0), 42, "socket-probe", probeAuthorization()).also { it.confirmSpawn(null) { true } }.token
            val me = FakeConn(peerUid = 1_000, frame = BridgeHandshake.hello(token).toString(Charsets.UTF_8) + frame)
            bound.enqueue(me)
            await("响应回写") { me.response().contains("\"t\":\"ok\"") }
            val resp = me.response()
            assertTrue(resp.contains("\"id\":7"), "响应按 id 关联回写：$resp")
            assertEquals(0, listener.rejectedCount())
            // 输入预 EOF（ByteArray）→ readLoop 结束 → serve 收尾必须关连接（fd 不泄漏）
            await("serve 收尾关连接") { me.closed.get() }
        }
        listener.close()
    }

    @Test
    fun `close 幂等且停 accept——之后入队的连接不再被取用`() {
        val bound = FakeBound()
        val listener = BridgeSocketListener.bind("shutdown", { bound }, myUid = 1_000)!!
        kit().use { s ->
            listener.start(s.shell)
            listener.close()
            listener.close()                    // 幂等：二次关不抛
            val late = FakeConn(peerUid = 1_000, frame = "x\n")
            bound.enqueue(late)
            Thread.sleep(150)                   // 假 accept 轮询周期 20ms × 7 轮足够看出"没人取"
            assertFalse(late.closed.get(), "已关循环不得再取用/处置新连接")
            assertEquals(0, listener.rejectedCount())
            assertEquals(0, late.output.size())
        }
        listener.close()                        // use 收尾后再关一次仍幂等
    }

    @Test
    fun `同uid未知票据和PID不符拒绝，UID凭据读取失败不碰业务流`() {
        val bound = FakeBound()
        BridgeSocketListener.bind("identity", { bound }, myUid = 1_000)!!.use { listener ->
            kit().use { s ->
                listener.start(s.shell)
                val unknown = FakeConn(1_000, BridgeHandshake.hello("0".repeat(64)).toString(Charsets.UTF_8))
                val lease = s.shell.identities.issue(EngineId(0), 42, "socket-probe", probeAuthorization()).also { it.confirmSpawn(123) { true } }
                val mismatch = FakeConn(1_000, BridgeHandshake.hello(lease.token).toString(Charsets.UTF_8), peerPid = 456)
                for (conn in listOf(unknown, mismatch)) {
                    bound.enqueue(conn)
                    await("身份不符连接关闭") { conn.closed.get() }
                    assertFalse(conn.response().contains("helloAck"))
                }
                val closed = CountDownLatch(1)
                bound.enqueue(object : AcceptedBridgeConnection {
                    override val peerUid: Int get() = error("凭据不可读")
                    override val input: InputStream get() = error("UID拒收不应打开输入流")
                    override val output: OutputStream get() = error("UID拒收不应打开输出流")
                    override fun close() { closed.countDown() }
                })
                assertTrue(closed.await(5, TimeUnit.SECONDS))
                assertEquals(1, listener.rejectedCount())
                assertEquals(0, s.shell.identities.size())
                assertTrue(s.shell.console.drain(0).second.isEmpty())
            }
        }
    }

    @Test
    fun `同壳重复start不拆连接，换壳关闭preauth和已认证阻塞IO`() {
        val bound = FakeBound()
        BridgeSocketListener.bind("blocking-swap", { bound }, myUid = 1_000)!!.use { listener ->
            kit().use { first ->
                kit().use { second ->
                    listener.start(first.shell)
                    val lease = first.shell.identities.issue(EngineId(0), 42, "socket-probe", probeAuthorization()).also { it.confirmSpawn(null) { true } }
                    val preauth = BlockingConn(byteArrayOf())
                    val active = BlockingConn(BridgeHandshake.hello(lease.token))
                    bound.enqueue(preauth)
                    bound.enqueue(active)
                    assertTrue(preauth.waiting.await(5, TimeUnit.SECONDS))
                    assertTrue(active.waiting.await(5, TimeUnit.SECONDS))
                    listener.start(first.shell)
                    assertEquals(1L, active.closed.count)
                    listener.start(second.shell)
                    assertTrue(preauth.closed.await(5, TimeUnit.SECONDS))
                    assertTrue(active.closed.await(5, TimeUnit.SECONDS))
                    assertThrows(IllegalStateException::class.java) { first.shell.identities.issue(EngineId(0), 43, "socket-probe", probeAuthorization()) }
                    val fresh = second.shell.identities.issue(EngineId(0), 44, "socket-probe", probeAuthorization()).also { it.confirmSpawn(null) { true } }
                    val next = BlockingConn(BridgeHandshake.hello(fresh.token))
                    bound.enqueue(next)
                    assertTrue(next.waiting.await(5, TimeUnit.SECONDS))
                    first.shell.close()
                    assertEquals(1L, next.closed.count, "旧壳延迟关闭不影响新连接")
                    listener.close()
                    assertTrue(next.closed.await(5, TimeUnit.SECONDS))
                    assertThrows(IllegalStateException::class.java) { listener.start(second.shell) }
                }
            }
        }
    }

    @Test
    fun `引擎工厂装配失败关闭本次身份入口`() {
        var issuer: com.autoscript.domain.engine.RunIdentityIssuer? = null
        assertThrows(IllegalStateException::class.java) {
            AppShellKit.assemble(
                filesDir = dir.resolve("failed-files"),
                cacheDir = dir.resolve("failed-cache"),
                intentStore = InMemoryIntentStore(),
                schedulerProvider = RecordingProvider(),
                engineFactory = { _, identities -> issuer = identities; error("工厂失败") },
            )
        }
        val captured = requireNotNull(issuer)
        assertThrows(IllegalStateException::class.java) { captured.issue(EngineId(0), 45, "socket-probe", probeAuthorization()) }
    }

    @Test
    fun `二次 start 换 router 面——accept 线程不重启 仍可 serve`() {
        val bound = FakeBound()
        val listener = BridgeSocketListener.bind("swap", { bound }, myUid = 1_000)!!
        val s1 = kit()
        val s2 = kit()
        try {
            listener.start(s1.shell)
            listener.start(s2.shell)            // 换代：旧 frameServer 收掉，循环复用
            val frame =
                """{"t":"req","id":9,"ns":"engines","m":"heartbeat","ttl":2000,"payload":"{\"runId\":42,\"seq\":1}","side":null}""" + "\n"
            val token = s2.shell.identities.issue(EngineId(0), 42, "socket-probe", probeAuthorization()).also { it.confirmSpawn(null) { true } }.token
            val me = FakeConn(peerUid = 1_000, frame = BridgeHandshake.hello(token).toString(Charsets.UTF_8) + frame)
            bound.enqueue(me)
            await("换代后仍 serve") { me.response().contains("\"t\":\"ok\"") }
            assertTrue(me.response().contains("\"id\":9"))
        } finally {
            listener.close()
            s1.close()
            s2.close()
        }
    }
}
