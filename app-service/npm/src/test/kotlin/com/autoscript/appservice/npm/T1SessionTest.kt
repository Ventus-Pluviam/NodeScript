package com.autoscript.appservice.npm

import com.autoscript.domain.json.DomainJson
import com.autoscript.platform.process.TreeOps
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [T1Session] 的帧面韧性（§10.3 T1）：**一条坏帧只该带走它自己**。
 *
 * dispatch 的 KDoc 写着「坏帧/未知类型一律静默跳过（一条坏行不该打死整条会话 ——
 * 子进程还在跑）」，但两类帧此前都不在这条承诺的覆盖里：
 *
 * 1. `in` 帧的 `data` 是坏 base64 —— `Base64.getDecoder().decode` 抛
 *    `IllegalArgumentException`（不是 `IOException`，[T1Child.writeStdin] 接不住），
 *    顺着 `readFrames` → `run()` 的 finally 把**整条会话**连锅端（全部子进程被 reap），
 *    而肇事的那条命令甚至可以是无辜的；
 * 2. `spawn` 帧重号（id 是对端给的）—— 直接覆盖 `children[id]`，旧子进程脱离收尸账
 *    （会话关断时漏杀），且旧线程收尾的无条件 `children.remove(id)` 会把**新条目**摘错，
 *    新子进程同样脱离收尸账。
 *
 * 本类用管道对跑真会话线程（不真起进程 —— 那是 [T1BridgeNodeTest]/[T1BridgeE2ETest] 的面）。
 */
class T1SessionTest {

    private val token = "tok"

    /** 假连接：测试写 → 会话读；会话写 → 帧进 [frames] 队列（带截止的读取断言全靠它）。 */
    private class FakeConn {
        private val toSession = PipedOutputStream()
        private val sessionIn = PipedInputStream(toSession, 64 * 1024)
        private val fromSession = PipedInputStream(64 * 1024)
        private val sessionOut = PipedOutputStream(fromSession)
        val closed = AtomicBoolean(false)
        val frames = LinkedBlockingQueue<String>()

        val conn = object : NpmT1Bridge.AcceptedT1Connection {
            override val input: InputStream = sessionIn
            override val output: OutputStream = sessionOut
            override fun close() {
                closed.set(true)
                runCatching { sessionIn.close() }
                runCatching { sessionOut.close() }
            }
        }

        init {
            Thread({
                try {
                    fromSession.bufferedReader().forEachLine { frames.offer(it) }
                } catch (_: IOException) {
                    // 连接被关：读线程的正常死法（帧面断言只看队列，不看这条线程）。
                }
            }, "t1-test-reader").apply { isDaemon = true; start() }
        }

        fun send(json: String) {
            toSession.write((json + "\n").toByteArray(Charsets.UTF_8))
            toSession.flush()
        }

        fun nextFrame(millis: Long): String? = frames.poll(millis, TimeUnit.MILLISECONDS)
    }

    /** 一条**活着、直到 destroy 才退**的假进程（两条流恒空：本类不验输出泵）。 */
    private class LiveProcess : Process() {
        val destroyed = AtomicBoolean(false)
        private val done = CountDownLatch(1)

        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int {
            done.await()
            return 0
        }

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = done.await(timeout, unit)
        override fun exitValue(): Int = 0
        override fun destroy() {
            destroyed.set(true)
            done.countDown()
        }

        override fun destroyForcibly(): Process {
            destroy()
            return this
        }

        override fun isAlive(): Boolean = done.count > 0
        override fun pid(): Long = -1L
    }

    /** 记下游标的假 runner：每次 start 记一条 spec、发一条新 [LiveProcess]。 */
    private class FakeRunner : T1ProcessRunner {
        val started = CopyOnWriteArrayList<T1SpawnSpec>()
        val processes = CopyOnWriteArrayList<LiveProcess>()

        override fun start(spec: T1SpawnSpec): Process {
            started += spec
            return LiveProcess().also { processes += it }
        }
    }

    /** 空树缝：假进程没有后代可扫（也绝不借宿主 /proc —— 那是判死判活判错对象的来路）。 */
    private object NoTree : TreeOps {
        override fun descendantsOf(process: Process): Set<Long> = emptySet()
        override fun descendantsOf(alivePids: Set<Long>): Set<Long> = emptySet()
        override fun kill(pids: Collection<Long>, force: Boolean) = Unit
        override fun alive(pid: Long): Boolean = false
    }

    private fun sessionOf(conn: FakeConn, runner: FakeRunner): T1Session {
        val s = T1Session(conn.conn, token, runner, NoTree)
        Thread({ s.run() }, "t1-session-test").apply { isDaemon = true; start() }
        return s
    }

    private fun FakeConn.hello() {
        send("{\"t\":\"hello\",\"v\":${NpmT1Bridge.VERSION},\"token\":\"$token\"}")
        val ack = nextFrame(5_000)
        assertNotNull(ack, "握手应答该来")
        assertTrue(ack!!.contains("helloAck"), "握手应答该是 helloAck：$ack")
    }

    private fun awaitTrue(what: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (what()) return true
            Thread.sleep(10)
        }
        return what()
    }

    @Test
    fun `坏 base64 的 in 帧只跳过这一帧 会话与别的子进程照常`() {
        val conn = FakeConn()
        val runner = FakeRunner()
        val s = sessionOf(conn, runner)
        conn.hello()
        conn.send("{\"t\":\"spawn\",\"id\":1,\"cmd\":\"sh\",\"args\":[\"-c\",\"true\"]}")
        assertTrue(awaitTrue { runner.started.size == 1 }, "第一条 spawn 该起进程")

        // 肇事帧：base64 解不出。旧实现里 IAE 顺 run() 的 finally 把整条会话连锅端。
        conn.send("{\"t\":\"in\",\"id\":1,\"data\":\"!!!not-base64!!!\"}")

        conn.send("{\"t\":\"spawn\",\"id\":2,\"cmd\":\"sh\",\"args\":[\"-c\",\"true\"]}")
        assertTrue(awaitTrue { runner.started.size == 2 }, "坏帧之后会话必须还活着、还能派发")

        s.close()
        assertTrue(runner.processes[0].destroyed.get(), "会话关断收第一条")
        assertTrue(runner.processes[1].destroyed.get(), "会话关断收第二条（坏帧没把它带走）")
    }

    @Test
    fun `同号 spawn 旧进程被当场收 且旧线程收尾不摘新条目的收尸账`() {
        val conn = FakeConn()
        val runner = FakeRunner()
        val s = sessionOf(conn, runner)
        conn.hello()
        conn.send("{\"t\":\"spawn\",\"id\":7,\"cmd\":\"sh\",\"args\":[\"-c\",\"true\"]}")
        assertTrue(awaitTrue { runner.started.size == 1 }, "第一条 spawn 该起进程")
        val first = runner.processes[0]

        conn.send("{\"t\":\"spawn\",\"id\":7,\"cmd\":\"sh\",\"args\":[\"-c\",\"true\"]}")
        assertTrue(awaitTrue { runner.started.size == 2 }, "同号第二条也该起进程")
        val second = runner.processes[1]
        assertTrue(awaitTrue { first.destroyed.get() }, "同号顶入：旧进程必须被当场收掉（不许漏杀）")
        assertFalse(second.destroyed.get(), "新进程不该陪杀")

        // 等旧线程的 exit 帧落地 —— exit 之后它立刻做 children.remove(id, 旧条目)：
        // 若是旧实现的无条件 remove，摘走的是**新条目**，下面 close() 就收不到 second 了。
        var sawExit = false
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && !sawExit) {
            val f = conn.nextFrame(500) ?: continue
            val fields = DomainJson.decodeObject(f)
            if ((fields["t"] as? DomainJson.Value.S)?.v == "exit" &&
                (fields["id"] as? DomainJson.Value.N)?.raw == "7"
            ) {
                sawExit = true
            }
        }
        assertTrue(sawExit, "旧进程该发 exit 帧")

        s.close()
        assertTrue(second.destroyed.get(), "收尸账上必须还有新进程（值条件 remove 护住了它）")
    }
}
