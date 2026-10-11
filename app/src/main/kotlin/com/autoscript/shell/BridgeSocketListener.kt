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

    /** PID 只能是内核凭据；平台确实不可得时为 null，与 UID fail-closed 分开。 */
    val peerPid: Int? get() = null

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
 * **纯 JVM**：本类不碰 `android.*`（`:app` 单测跑在可 mock 的 android.jar 上 ——
 * 那是桩，`android.*` 一碰就是「not mocked」）；Android 面全部
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
    /**
     * accept 瞬时故障的诊断回执（外审 M3，批 98）。
     *
     * 为什么要有缝：原实现对 [IOException] 静默 `break`，一次 `EMFILE` 就让**整条桥对
     * 所有未来脚本静默死亡**且**没有任何痕迹**。现口径改成「退避重试」，于是必须有一条
     * 「它退避了几次、为什么」的可见出口 —— 与 [rejectedCount] 同一纪律（拒绝要能数）。
     * 缺省 no-op（生产不接日志时零成本）。
     */
    private val onAcceptError: (String) -> Unit = {},
    /** 退避时长（毫秒）；可注入以便单测不真等。 */
    private val acceptRetryBackoffMillis: Long = ACCEPT_RETRY_BACKOFF_MILLIS,
) : AutoCloseable {

    @Volatile
    private var running = true

    /** serve 面：同壳重复 start 不变；换壳关闭旧 IO 与身份入口，新连接走新 router。 */
    @Volatile
    private var frameServer: NewlineFrameServer? = null

    @Volatile
    private var acceptThread: Thread? = null

    /** 退避等待的门闩：close 唤醒它，让 accept 线程不必睡满整段退避（见 [sleepBeforeRetry]）。 */
    private val retryGate = Object()

    /** 门禁拒收累计（诊断：持续增长 = 有异 uid 在撞门，不是"连接不稳"）。 */
    private val rejected = AtomicLong(0)

    fun rejectedCount(): Long = rejected.get()

    /** accept 瞬时故障累计（外审 M3）：> 0 = 桥曾被系统压力打断过并自己恢复。 */
    private val acceptRetries = AtomicLong(0)

    fun acceptRetryCount(): Long = acceptRetries.get()

    private fun onAcceptFailure(reason: String) {
        acceptRetries.incrementAndGet()
        runCatching { onAcceptError(reason) }   // 诊断回执不许打断 accept 循环
    }

    /**
     * 退避并等 close 打断；@return false = 本端已关断（该收循环）。
     *
     * 用 [Object.wait] 而不是 `Thread.sleep`：关断路径 [close] 要能立刻把它唤醒
     * （`sleep` 里那个线程收不到 close 的通知，close 得再等满整段退避才生效 —— 那是
     * 「用户点了急停但线程还赖着 1s」的由来）。
     */
    private fun sleepBeforeRetry(): Boolean {
        if (!running) return false
        synchronized(retryGate) {
            if (!running) return false
            try {
                retryGate.wait(acceptRetryBackoffMillis)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return running
    }

    /**
     * 开 accept/serve（壳就绪后、`install` 前调）：同壳重复调用幂等，换壳不重启 accept
     * 线程。旧 [NewlineFrameServer] 先显式关闭全部 IO（包括 pre-auth 与阻塞 read），
     * 再取消请求；不能等阻塞 read 的协程完成后才关闭连接。新连接使用 [shell] 的身份账与 router。
     * 装配线程串行调用，无需加锁。
     */
    fun start(shell: AppShell) {
        check(running) { "桥监听已关闭" }
        val next = shell.frameServer
        if (frameServer === next) return
        frameServer?.close()
        frameServer = next
        if (acceptThread != null) return
        acceptThread = Thread({ acceptLoop() }, "bridge-accept").apply {
            isDaemon = true      // 进程级守护：不挡 JVM/测试退出
            start()
        }
    }

    /**
     * accept 循环（外审 M3，2026-10-11 批 98 修订）。
     *
     * **原口径**：任何 [IOException] 都收循环。`IOException` 是个**家族** —— 本端
     * [close] 顶醒 accept 是「该停了」，而 `EMFILE`（fd 用尽）/`ECONNABORTED`（内核踢掉
     * 半开连接）/`ENFILE`（系统表用尽）都是**瞬时**故障：一次就 `break` 等于**整条桥对
     * 所有未来脚本静默死亡**（无日志、无重启、表现是「脚本再也连不上」）。
     *
     * **现口径**：分「该停」与「再试」两类。
     * - 本端已 [close]（`running == false`）或 accept 返回 null → 收循环；
     * - 其余 [IOException] → 计数 + **退避后继续**（[ACCEPT_RETRY_BACKOFF_MILLIS]），
     *   连续 [ACCEPT_MAX_CONSECUTIVE_FAILURES] 次才收循环（那种情形已经不是瞬时的了）；
     * - 非 [IOException] 的异常照旧收循环（那是代码 bug，不该被重试掩盖）。
     *
     * 退避存在的原因是「连续失败时立刻重试」会把 fd 表再撞一次；1s 起步对用户无感
     * （桥是长连，面只在脚本起进程时用），但足以让内核把表项释放掉。
     */
    private fun acceptLoop() {
        var consecutiveFailures = 0
        while (running) {
            val conn = try {
                bound.accept()
            } catch (e: IOException) {
                if (!running) break              // 本端 close 顶醒 accept：该停，不是故障
                consecutiveFailures++
                if (consecutiveFailures >= ACCEPT_MAX_CONSECUTIVE_FAILURES) {
                    onAcceptFailure("accept 连续 ${consecutiveFailures} 次 IOException（非瞬时），收桥：${e.message}")
                    break
                }
                onAcceptFailure(
                    "accept 第 $consecutiveFailures 次 IOException（瞬时，退避后重试）：${e.message}",
                )
                if (!sleepBeforeRetry()) break    // 关断打断退避：该停
                continue
            } ?: break                          // 本端 close 的干净信号
            consecutiveFailures = 0              // 成功一次即清账
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
            val peerPid = try { conn.peerPid } catch (_: Exception) { null }
            fs.serveConnection(conn.input, conn.output, peerPid = peerPid, closeConnection = conn::close)
        }
    }

    /**
     * 关断（幂等）：停循环 → 解 accept 阻塞 → 收 serve 面（在途连接随之释放）。
     * 不 join accept 线程死等 —— [BoundBridgeSocket.close] 顶醒后线程自行退出（daemon）。
     */
    override fun close() {
        running = false
        synchronized(retryGate) { retryGate.notifyAll() }   // 唤醒退避中的 accept 线程
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

        /**
         * 可观测/可测的 bind 变体（外审 M3，批 98）：多两个诊断缝，生产 [bind] 走缺省。
         *
         * 收口面用 [bind] 是因为它「要么绑上要么回 null」的形状最干净；可观测性与退避时长
         * 属诊断/测试需求，不值得让生产路径的每个调用点都传两个参数。
         */
        fun bind(
            socketName: String,
            binder: BridgeSocketBinder,
            myUid: Int,
            onAcceptError: (String) -> Unit,
            acceptRetryBackoffMillis: Long = ACCEPT_RETRY_BACKOFF_MILLIS,
        ): BridgeSocketListener? = binder.bind(socketName)?.let {
            BridgeSocketListener(socketName, it, myUid, onAcceptError, acceptRetryBackoffMillis)
        }

        /** accept 瞬时故障后的退避（毫秒）：够内核释放 fd 表项，对用户无感（桥是长连面）。 */
        const val ACCEPT_RETRY_BACKOFF_MILLIS: Long = 1_000L

        /**
         * 连续 accept 失败多少次才认定「不是瞬时的」而收桥。
         *
         * 之所以要有这个上限而不是无限重试：`accept` 抛的若是永久性错误（监听面被系统
         * 收走、抽象名被抢），无限重试 = 一个永远转不出结果的线程 + 桥看起来还活着
         * （脚本连不上但宿主不报错）。20 次 × 1s ≈ 20s 后如实收桥并留下回执。
         */
        const val ACCEPT_MAX_CONSECUTIVE_FAILURES: Int = 20
    }
}
