package com.autoscript.shell

import com.autoscript.bridge.NewlineFrameServer
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * 已绑定的桥监听面（§7.5 unix domain socket 的缝对端）：阻塞 [accept] 取连接，
 * [close] 解除阻塞。Android 生产实现 = `LocalServerSocket(String)`（abstract 名，
 * 见 [AndroidBridgeBinder]）；JVM 单测注入假实现，把门禁/serve/关断跑成确定性用例。
 */
interface BoundBridgeSocket : AutoCloseable {
    /**
     * 阻塞取下一条连接；**本端已 close → null**（唯一语义）。
     * accept 期 IO 异常直接上抛 —— 由监听循环按「关断/故障」收循环，不静默转 null
     * （吞成 null 会把一次真故障说成"干净停机"，§1 诚实）。
     */
    fun accept(): AcceptedBridgeConnection?

    override fun close()
}

/** 一条已接入的连接：对端凭据（uid 门禁）+ 双向流 + 单连接释放。 */
interface AcceptedBridgeConnection {
    /**
     * 对端 uid（Android = 内核 `SO_PEERCRED`/`LocalSocket.peerCredentials`）。
     * 实现读不到凭据时应让 getter **抛** —— 门禁侧按 fail-closed 拒收（读不出身份 ≠ 可信）。
     */
    val peerUid: Int

    val input: InputStream
    val output: OutputStream

    /** 单连接释放：serve 结束（EOF/取消）、门禁拒收、监听关断三条路都必须走到这里（fd 不泄漏）。 */
    fun close()
}

/** bind 缝：回 null = 绑定失败（abstract 名被抢/权限）→ 调用方离线降级，不冒泡炸装配。 */
fun interface BridgeSocketBinder {
    fun bind(socketName: String): BoundBridgeSocket?
}

/**
 * `:main` 桥监听的生产形态（§7.5 unix domain socket；`NodeEngineConfig.hostSocketName`
 * 的 KDoc 点名的类）：abstract 名绑定 → accept 循环 → 对端 uid 门禁 → 每连接交给
 * [NewlineFrameServer]（newline 帧读写/分发归它，本类只管**介质与门禁**）。
 *
 * **uid 门禁为什么必须（双侧对称）**：abstract 名没有文件权限位，抢绑/冒名是纯竞速 ——
 * 服务端收连接验 [myUid]，客户端 main.cpp `ConnectHostSocket` 对称验 peer uid，
 * 任何一侧失配即断（防别的 App 抢绑后被脚本当宿主连上，或冒名客户端摸进 Router）。
 * 凭据读不到 = fail-closed 拒收。
 *
 * **绑定与 serve 两段式**：[bind] 可在壳装配**前** —— `FixedEnginePool.init` 是
 * **eager** 构造引擎，`hostSocketName` 必须在装配期就定；[start] 在壳就绪后才开 accept
 * （serve 要 router）。两段之间的入连接在内核 backlog 排队，start 后由 accept 取用
 * —— 生产顺序（bind → assemble → start → install）里没有执行体能抢在这窗口内 spawn。
 *
 * **纯 JVM**：本类不碰 `android.*`（`:app` 单测运行期 classpath 不带 android.jar，
 * 一碰就是 NoClassDefFound/Stub 崩 —— 见 CLAUDE.md 本机自测旁路）；Android 面全部
 * 收在 [AndroidBridgeBinder]（薄到没有逻辑，不在单测里碰）。
 *
 * @see AndroidBridgeBinder 设备侧绑定实现。
 */
class BridgeSocketListener private constructor(
    /** 绑定的 abstract 名（= 注入 `NodeEngineConfig.hostSocketName` 的值）。 */
    val socketName: String,
    private val bound: BoundBridgeSocket,
    /** 本端 uid（Android = `Process.myUid()`）：对端必须等于它才放行。 */
    private val myUid: Int,
) : AutoCloseable {

    @Volatile
    private var running = true

    /** serve 面：每次 [start] 换代（旧在途连接挂旧 scope 随之收，新连接走新 router）。 */
    @Volatile
    private var frameServer: NewlineFrameServer? = null

    @Volatile
    private var acceptThread: Thread? = null

    /** 门禁拒收累计（诊断：持续增长 = 有异 uid 在撞门，不是"连接不稳"）。 */
    private val rejected = AtomicLong(0)

    fun rejectedCount(): Long = rejected.get()

    /**
     * 开 accept/serve（壳就绪后、`install` 前调；**幂等** —— 二次调用只换 router 面，
     * accept 线程不重启）。换代语义：旧 [NewlineFrameServer] 收掉（其 scope 内在途
     * serve 被取消 → 连接经 `invokeOnCompletion` 释放），此后新连接全走 [shell] 的 router。
     * 装配线程串行调用，无需加锁。
     */
    fun start(shell: AppShell) {
        val next = NewlineFrameServer(shell.router)
        frameServer?.close()
        frameServer = next
        if (acceptThread != null) return
        acceptThread = Thread({ acceptLoop() }, "bridge-accept").apply {
            isDaemon = true      // 进程级守护：不挡 JVM/测试退出
            start()
        }
    }

    private fun acceptLoop() {
        while (running) {
            val conn = try {
                bound.accept()
            } catch (_: IOException) {
                break                       // 关断（close 顶醒 accept）或监听面故障：收循环
            } ?: break                     // 本端 close 的干净信号
            if (!running) {
                conn.close()               // close 与 accept 竞态的落网连接：立即释放
                break
            }
            // uid 门禁（fail-closed）：读不到凭据 = 拒收，绝不默认放行
            val ok = try {
                conn.peerUid == myUid
            } catch (_: Exception) {
                false
            }
            if (!ok) {
                rejected.incrementAndGet()
                conn.close()
                continue
            }
            val fs = frameServer
            if (fs == null) {
                conn.close()               // 未 start/已 close：无 router 面，不 serve
                continue
            }
            // serve 收尾（EOF/取消/异常）必关连接 —— fd 归 accept 方管，泄漏 = 慢死
            fs.serveConnection(conn.input, conn.output).invokeOnCompletion { conn.close() }
        }
    }

    /**
     * 关断（幂等）：停循环 → 解 accept 阻塞 → 收 serve 面（在途连接随之释放）。
     * 不 join accept 线程死等 —— [BoundBridgeSocket.close] 顶醒后线程自行退出（daemon）。
     */
    override fun close() {
        running = false
        try {
            bound.close()
        } catch (_: Exception) {
            // 关断路径吞异常：目标就是"尽量停"，二次 close 本就幂等
        }
        frameServer?.close()
        frameServer = null
    }

    companion object {
        /**
         * 设备侧 abstract 名：**按 uid 隔离** —— abstract 是系统全局命名空间，打包模板
         * 多实例同装一台机时固定名会让第二个 App 绑不上（静默离线）；带 uid 各绑各的。
         * 无 `/` 前缀 = main.cpp `ConnectHostSocket` 走 abstract 分支（§7.8 判别式）。
         */
        fun defaultName(uid: Int): String = "autoscript.bridge.$uid"

        /**
         * 绑定（不 accept —— serve 要等 [start]）。
         * binder 回 null → 整体回 null：调用方记日志走离线降级，**不注入** `hostSocketName`
         * （main.cpp 合同：env 给了连不上 = exit 3，注入一个绑不上的名字比不注入更糟）。
         */
        fun bind(socketName: String, binder: BridgeSocketBinder, myUid: Int): BridgeSocketListener? =
            binder.bind(socketName)?.let { BridgeSocketListener(socketName, it, myUid) }
    }
}
