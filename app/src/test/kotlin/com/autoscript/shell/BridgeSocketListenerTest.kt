package com.autoscript.shell

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
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

    /** 假连接：预填输入帧、截留输出、close 置标（拒收/serve 收尾两条路都可观测）。 */
    private class FakeConn(
        override val peerUid: Int,
        frame: String = "",
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

    /** 假监听面：accept 从队列取（带短轮询，close 后回 null 唤醒循环）。 */
    private class FakeBound : BoundBridgeSocket {
        private val queue = LinkedBlockingQueue<AcceptedBridgeConnection>()
        private val closed = AtomicBoolean(false)

        fun enqueue(conn: AcceptedBridgeConnection) {
            queue.put(conn)
        }

        override fun accept(): AcceptedBridgeConnection? {
            while (!closed.get()) {
                queue.poll(20, TimeUnit.MILLISECONDS)?.let { return it }
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

    private fun kit(): AppShellKit.AssembledShell = AppShellKit.assemble(
        filesDir = dir.resolve("files"),
        cacheDir = dir.resolve("cache"),
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
            val me = FakeConn(peerUid = 1_000, frame = frame)
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
            val me = FakeConn(peerUid = 1_000, frame = frame)
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
