package com.autoscript.bridge

import com.autoscript.domain.automation.InputChannelSession
import com.autoscript.domain.bridge.BridgeResponse
import com.autoscript.domain.bridge.ConnectionResourceRegistry
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore

/**
 * newline 帧接入（§7.5）：hello 绑定执行后才分发，handler 表共享、请求账按连接隔离。
 * 每连接持有真实 IO 关闭器：单纯 cancel 无法唤醒阻塞 socket.read。EOF 后先排完已接收请求，
 * 自然退出保留原归属读取尾帧；主动撤销立即关 IO。全局在途配额仍在起请求协程之前取。
 *
 * **三档配额（2026-10-11 批 100，外审 M2）**：本类原有**两个**上限，各钉一种滥用形态 ——
 * [inFlight]（在途请求**条数**，默认 256）与 [handshakes]（未认证连接数，默认 32）。
 * 批 100 补的第三条见 [maxBytesInFlight]（在途请求**字节**，默认 16 MiB）。
 *
 * **每连接配额与并发闸（2026-10-12 批 107，外审 M2 的另一半）**：上面三档是**全局**的 ——
 * 一条连接就能吃满全部 256 条在途（其他连接的请求全被挤在门外），且 handler 跑在
 * `Dispatchers.IO` 的默认 64 线程上。批 107 补三档**每连接**闸（[perConnectionLimits]）：
 * 条数、字节、并发处理数。**读面刻意不设闸** —— 一条连接的帧读必须严格串行
 * （下一帧的位置依赖上一帧读完），限它会直接卡死连接而不是降速。
 *
 * **为什么这三档与上面三档接在同一条链上而不是另起一份**：批 100 定的纪律是
 * 「先取字节再取条数，取闸与起协程之间不留窗口」—— 反过来就得退款，多一处还就少一份配额。
 * 加每连接闸时最容易犯的错就是把它接在这条链**之外**（比如进 handler 才判），那样就出现了
 * 「全局闸已经取了、连接闸没过、闸的许可悬着」这个新状态。故 [Connection.serveAcceptedFrame]
 * 是一处取、一处还，判定与归还写在一起。
 *
 * **「可见面」是什么、为什么它不是事后加的日志**：批 100 说这两条要「连同连接级计数的
 * 可见面一起设计」，本批把它落成两件事 —— [QuotaSettings.onRejected]（每拒一条报一次，脚本作者
 * 能从 stderr 看到「你这条被拒是因为连接配额」）与 [connectionStats]（每连接的实时读数）。
 * 没有它，用户看到的现象是「有时候卡」，而没有人说得清被谁拒的 —— 那与「不做这半套」
 * 一样没法解释。
 */
/**
 * 每连接闸与其诊断口**打包**（批 107）。
 *
 * **为什么打包成一个参数而不是分两三个进构造函数**：`NewlineFrameServer` 的构造参数已经
 * 压着 detekt `LongParameterList` 的线（12），而「三档闸」与「闸的诊断口」在语义上就是
 * 一个东西 —— 分开传会让调用方摆四个值却看不出哪几个是一组，改闸时忘了带诊断口就成了
 * 「有闸但用户看不到为什么被拒」，那正是批 100 说「两半要一起设计」的由来。
 */
data class QuotaSettings(
    val limits: PerConnectionLimits = PerConnectionLimits.DEFAULTS,
    /**
     * 配额拒绝的诊断回执（批 107，M2 的「可见面」那半）。
     *
     * 与 `onProtocolError`（对端发了非法帧）/ `onAcceptError`（本机 accept 出了故障）分三路：
     * 三者回答三个不同的问题 —— 前两个是**故障**，这一个**是策略行为**。混进前两路里，
     * 脚本作者会以为是自己写错了帧，而真相是宿主设的配额。缺省 no-op。
     */
    val onRejected: (reason: String) -> Unit = {},
)

/**
 * 每条桥连接的三档闸（批 107；外审 M2 的「每连接配额」+「`limitedParallelism`」两半）。
 *
 * **与全局那三档是「与」不是「或」**：一条请求要同时满足全局闸与本连接的闸才被受理。
 * 全局闸管「整个宿主别被撑死」，本连接的闸管「一条连接别把别人的份额吃光」——
 * 前者缺了设备 OOM，后者缺了先到的连接把后来的全挤在门外（批 100 记的那条
 * 「其他连接的请求全被挤在门外」）。
 *
 * **三档各自限的是不同的东西，正交**（与批 99 给 zip 加条目数闸、批 100 加字节闸同一条纪律）：
 * - [inFlight]：同时在飞几条 —— 限「请求的**个数**」；
 * - [bytes]：同时在飞多少字节 —— 限「请求的**体量**」，一条就能是 8 MiB；
 * - [concurrent]：同时**执行中**几条 —— 限「宿主为这条连接付出的**并行度**」。
 *
 * 最后一项与前两项不是一回事：前两项管「收了多少进来」，这一项管「同时干了多少活」。
 * 一条 1 KiB 的请求可以靠 [concurrent] 把 CPU 打满而完全碰不到 [bytes]。
 */
data class PerConnectionLimits(
    val inFlight: Int,
    val bytes: Long,
    val concurrent: Int,
) {
    companion object {
        /**
         * 出厂值。
         *
         * [concurrent] 的 8 与全局 `Dispatchers.IO` 的 64 是一档之差：**一条连接最多占
         * 八分之一的处理并行度**，八条连接并列时正好用满，再多的连接分不到 —— 那就是
         * [inFlight] 该拒的那一档（而不是让处理线程被某一条连接垄断）。
         *
         * [bytes] 取 32 MiB（比全局 16 MiB 宽）：单连接可以比全局宽，但**不能宽到无界** ——
         * 全局闸会在总量上兜住，这一条只防「一条连接自己吃掉全局预算的绝大部分」。
         */
        val DEFAULTS: PerConnectionLimits = PerConnectionLimits(
            inFlight = 64,
            bytes = 32L * 1024 * 1024,
            concurrent = 8,
        )
    }
}

/**
 * 一条连接的实时读数（批 107 的「可见面」那半）。
 *
 * **为什么它是只读快照而不是回调**：回调型可见面（每拒一条报一次）解决的是
 * 「我这条为什么被拒」，而这个回答的是「现在谁占着多少」—— 后者只有**值**能答，
 * 事件流答不了（「现在是 12 条」不是一个事件）。两者一起才是完整的可见面。
 */
data class ConnectionStat(
    val connectionId: Long,
    val runId: Long,
    val inFlight: Int,
    val inFlightLimit: Int,
    val bytes: Long,
    val bytesLimit: Long,
)

// 12 个构造参数压着 detekt `LongParameterList` 的线（11）。**刻意不合并**：
// 每个参数都是一条独立可调的策略（帧上限 / 条数闸 / 字节闸 / 握手窗 / 排空窗 / 三个诊断口），
// 打包成 `Settings` 会让「改字节闸要连带改条数闸的构造位置」——那正是本仓反复点名的
// 「打包省了参数数、丢了可读性」。批 107 已经把**真正该成组**的两件（每连接闸 +
// 其诊断口）收进了 [QuotaSettings]；剩下的每个都该各占一格。
@Suppress("LongParameterList")
class NewlineFrameServer(
    val router: BridgeRouter,
    val identities: RunIdentityRegistry,
    private val transport: JsonTransport = JsonTransport(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val maxFrameBytes: Int = DEFAULT_MAX_FRAME_BYTES,
    private val maxInFlight: Int = DEFAULT_MAX_IN_FLIGHT,
    /**
     * 在途请求的**字节**上限（外审 M2，批 100）。
     *
     * 为什么 [maxInFlight] 不够：它是**条数**闸，而一条请求可以是 8 MiB（[maxFrameBytes]）
     * —— 256 条满配就是 2 GiB 堆，在 minSdk 26 的设备上直接 OOM，而那一刻的现场是
     * 「脚本发了一堆大请求把宿主撑死」，看不出是哪条面。**条数与字节正交**，必须各设一闸
     * （与批 99 给 zip 加的 `MAX_ENTRIES` 同一条纪律）。
     *
     * 取值：256 × 典型帧（几十 KiB）远低于此；1 万条 1 KiB 的小帧也远低于此 ——
     * 所以正常脚本**感知不到**这道闸，它只拦「用大帧把宿主撑死」那一档。
     */
    private val maxBytesInFlight: Long = DEFAULT_MAX_BYTES_IN_FLIGHT,
    private val handshakeMillis: Long = BridgeHandshake.TIMEOUT_MILLIS,
    private val drainMillis: Long = 5_000,
    private val onProtocolError: (reason: String) -> Unit = {},
    /**
     * accept 瞬时故障的诊断回执（外审 M3，批 98）。
     *
     * 与 [onProtocolError] 分开：协议错误是**对端**的问题（要能追到某个脚本），accept
     * 瞬时故障是**本机**的问题（fd 表满、系统压力）—— 混进同一条回执里，两者都定位不到来源。
     * 缺省 no-op。
     */
    private val onAcceptError: (reason: String) -> Unit = {},
    /**
     * 每条连接**自己的**三档闸（批 107，外审 M2 的另一半；见类 KDoc「每连接配额与并发闸」）。
     *
     * **为什么是「一个可变的当前值」而不是四个 ctor 参数**：批 100 之所以把这两半登记不排期，
     * 一条理由就是「要连同连接级计数的可见面一起设计」，而可见面的前提是**用户能改**。
     * ctor 上的 `val` 改一次要重启宿主，于是「可见面」只能显示一个用户动不了的值 ——
     * 那不是可见面，是一行死文字。故这里是 `@Volatile` 的当前值 + [updatePerConnectionLimits]，
     * 改完**下一次请求**即用新值（在途请求用它们开始时的那份，见该方法 KDoc）。
     */
    /**
     * 每连接三档闸 + 它们的诊断回执**打包一个参数**（批 107；见 [QuotaSettings]）。
     *
     * **不是省事，是它们本来就是一个东西**：分两三个进构造函数会让调用方摆四个值
     * 却看不出哪几个是一组，改闸时忘了带诊断口就成��「有闸但用户看不到为什么被拒」——
     * 那正是批 100 说「这两半要连同可见面一起设计」的由来。
     */
    quota: QuotaSettings = QuotaSettings(),
) : AutoCloseable {

    /**
     * 每连接闸的**当前值**（批 107）。
     *
     * `@Volatile` 而不是 `AtomicReference`：每次请求读一次、每次设置整体换一份不可变数据，
     * 没有「改一半」的中间态可言（`PerConnectionLimits` 是 data class，整体替换即原子）。
     */
    @Volatile
    private var quotaSettings: QuotaSettings = quota

    /** 当前每连接三档闸（从 [quotaSettings] 现取；见 [updatePerConnectionLimits]）。 */
    private val perConnection: PerConnectionLimits get() = quotaSettings.limits

    /**
     * 改每连接闸（批 107 的「立即生效」那半）。缺省对象不被改（用户可以拿到它）。
     *
     * **在途请求用它们开始时的那份，不追溯**：一个请求已经在飞了才改配额，把它掐掉是
     * 「撤销」不是「限额」，两件事混起来会让脚本在配额没变的情况下莫名失败。
     * 归还时也不校验配额（许可数是「取了几发几」，与后来调了多少无关）。
     *
     * **不校验入参**：调用方是 [com.autoscript.domain.scripts.ScriptRuntimeLimits] 那条路
     * （判据唯一一份在 `:domain` 的 `reject`），而本类是 `:bridge:java` —— 它看得见 `:domain`，
     * 但**不复述那套上限**：桥的闸与「用户填的数」是两个层次的东西，桥只管「不低于 1」
     * 这一条它自己的前提（0 个许可 = 连一条都发不出，那不是配置而是禁用）。
     */
    fun updatePerConnectionLimits(limits: PerConnectionLimits) {
        require(limits.inFlight > 0 && limits.bytes > 0 && limits.concurrent > 0) {
            "每连接三档闸都必须 > 0（0 等于禁用这条连接，不是「不限」）：$limits"
        }
        quotaSettings = quotaSettings.copy(limits = limits)
    }

    /** 每连接的实时读数（批 107 的可见面那半；见类 KDoc）。 */
    fun connectionStats(): List<ConnectionStat> = synchronized(lock) {
        connections.map { it.stat() }
    }

    private val lock = Any()
    private val connections = HashSet<Connection>()
    private val listeners = HashSet<ServerSocket>()

    /**
     * 每条连接的**资源收口口**（§7.5 × §9.2）：连接撤销/结束时按 `connectionId` 取出来
     * 跑一遍。**键是连接号而不是 `Connection` 对象**：撤销回调在连接对象之外也要能调
     * （`abortConnection(id)` —— 宿主/看门狗那条路），对象键在那条路上取不到。
     *
     * 条目在 `dispose` 里摘掉：连接结束后再撤销一次是无害的 no-op，不留悬挂引用。
     */
    private val connectionResources = ConcurrentHashMap<Long, () -> Unit>()

    /**
     * `runId → connectionId`（A11，§7.5 × §9.2）：让**只认 runId 的调用方**（执行仲裁者
     * [com.autoscript.appservice.runtime.RuntimeController]）也能问得到"这条 run 的连接上
     * 挂着什么"，从而在 run 终结时收口。
     *
     * **为什么映射住在接入端而不是 controller**：连接号是这里发的（`connectionIds.getAndIncrement()`），
     * 而 runId 是**认证之后**才与它绑在一起的（[RunIdentityRegistry.authenticate] 用 lease 装填
     * [AuthenticatedRunContext.engineRunId]）—— 宿主/controller 那侧自始至终不知道连接号。
     * 反过来把映射塞进 controller 会要求它替接入端记账，两处各记一份必然错位。
     *
     * **装填点必须早于任何业务请求**（认证成功后立刻，见 `serve`）：否则存在一个
     * 「run 已终结、映射才装上」的窗口，撤销会扑空。选在认证之后是因为更早时 runId 还不���在。
     *
     * **为什么这条映射能堵住「子进程继承 socket fd」的漏**（A11 的原始病灶）：脚本进程死了
     * 不产生 EOF，连接不会 abort/dispose，于是挂在这条连接上的进程级资源（投屏会话）没有撤销点。
     * 补上 runId 侧的撤销入口后，执行终结**不再依赖 socket 断**。
     */
    private val runConnections = ConcurrentHashMap<Long, Long>()

    private var closed = false
    private val inFlight = Semaphore(maxInFlight)
    private val handshakes = Semaphore(32)

    /**
     * 在途字节账（外审 M2，批 100）：取在**读出帧之后、起请求协程之前**，还与
     * [inFlight] 的许可证成对（取字节必取条数、还字节必还条数 —— 两者是同一份「这条请求在
     * 飞」的记账，两个独立信号量手拿会漂）。
     *
     * 用 `AtomicLong` + CAS 而不是拿字节数的信号量：字节数是变量不是计数许可，
     * `Semaphore(permits = 1MiB)` 那种写法在 kotlinx 里不存在（它只数许可个数）。
     */
    private val bytesInFlight = java.util.concurrent.atomic.AtomicLong(0)

    init {
        require(maxFrameBytes > 0 && maxInFlight > 0 && handshakeMillis > 0 && drainMillis > 0)
        require(maxBytesInFlight > 0) { "在途字节上限必须 > 0：$maxBytesInFlight" }
    }

    /**
     * 在途字节账的 CAS 取闸（外审 M2，批 100）：`current + n <= 上限` 才进，否则 false。
     *
     * 为什么是 CAS 而不是 `synchronized`：读循环每秒能取放几千次，而这一段在**每条请求**上
     * 都要走；`synchronized` 会把它变成全局竞争点（所有连接的读循环都串行过一次）。
     * CAS 循环在无竞争时是一条 CAS 指令。
     *
     * 单条超上限（`n > 上限`）直接拒 —— 不去取 `[0, 上限)` 的随机起点那种「部分接纳」
     * 的做法：一条 8 MiB 帧配 1 MiB 上限，**部分接纳等于让请求永远等不到**，不如立刻拒。
     */
    private fun tryReserveBytes(n: Long): Boolean {
        while (true) {
            val current = bytesInFlight.get()
            if (current + n > maxBytesInFlight) return false
            if (bytesInFlight.compareAndSet(current, current + n)) return true
        }
    }

    /**
     * accept 循环（外审 M3，2026-10-11 批 98 修订，与 [BridgeSocketListener] 同一纪律）。
     *
     * **原口径**：任何异常都 `break`。`EMFILE`/`ENFILE`/`ECONNABORTED` 都是**瞬时**的，
     * 一次就把整条桥收掉（对所有未来脚本静默死亡，无日志无重启）。
     *
     * **现口径**：[SocketException] 且本端未关断 → 计数 + 退避重试；连续
     * [ACCEPT_MAX_CONSECUTIVE_FAILURES] 次才收循环（那已经不是瞬时的了）；其余异常照旧
     * 收（那是代码 bug，不该被重试掩盖）。关断（[close]）时 listener 被移除并 close，
     * accept 随之抛 —— 那一路由 `closed` 判据接住，走「该停」而不是「故障」。
     *
     * 用 [SocketException] 而非 [IOException] 是有意的：前者只从 accept 这一个调用点抛出，
     * 语义精确；后者会把「已关断」与「系统压力」混在一起（本机 unix socket 上两者
     * 抛出的类型确实不同，但类型判据比字符串判据稳）。
     */
    fun acceptLoop(serverSocket: ServerSocket): Job {
        synchronized(lock) {
            if (closed) serverSocket.close() else listeners.add(serverSocket)
        }
        return scope.launch {
            var consecutiveFailures = 0
            try {
                while (isActive) {
                    val socket: Socket = try {
                        serverSocket.accept()
                    } catch (e: SocketException) {
                        val shut = synchronized(lock) { closed || !listeners.contains(serverSocket) }
                        if (shut) break                       // 本端关断：该停，不是故障
                        consecutiveFailures++
                        if (consecutiveFailures >= ACCEPT_MAX_CONSECUTIVE_FAILURES) {
                            onAcceptError(
                                "accept 连续 ${consecutiveFailures} 次 SocketException（非瞬时），收桥：${e.message}",
                            )
                            break
                        }
                        onAcceptError(
                            "accept 第 $consecutiveFailures 次 SocketException（瞬时，退避后重试）：${e.message}",
                        )
                        delay(ACCEPT_RETRY_BACKOFF_MILLIS)
                        continue
                    } catch (_: Exception) {
                        break                              // 非 socket 类异常 = 代码 bug，不重试
                    }
                    consecutiveFailures = 0                 // 成功一次即清账
                    serveConnection(socket)
                }
            } finally {
                serverSocket.close()
                synchronized(lock) { listeners.remove(serverSocket) }
            }
        }
    }

    fun serveConnection(socket: Socket): Job = serveConnection(
        socket.getInputStream(), socket.getOutputStream(), closeConnection = { socket.close() },
    )

    /** 关闭器在 launch 前登记：pre-auth、阻塞读、排空中的连接都必须可由 close 唤醒。 */
    fun serveConnection(
        input: InputStream,
        output: OutputStream,
        session: InputChannelSession = InputChannelSession(),
        peerPid: Int? = null,
        closeConnection: () -> Unit = { try { input.close() } finally { output.close() } },
    ): Job {
        val conn = Connection(closeConnection)
        synchronized(lock) {
            if (closed) conn.abort() else connections.add(conn)
        }
        val job = scope.launch { conn.serve(input, output, session, peerPid) }
        job.invokeOnCompletion { conn.dispose() }
        return job
    }

    /**
     * **按连接号撤销**（§9.2 会话资源）：脚本崩了 / 被看门狗掐了 / 执行被停 —— 那条连接上
     * 开出来的进程级资源（投屏会话）必须收掉，而不是等 socket 自己断。
     *
     * 与 `Connection.abort()` 的关系：那条路是"从连接内部"撤（IO 出错、宿主 close），
     * 本方法是"从连接外部"撤（持有连接号的调用方）—— 两者落到同一个幂等收口口上，
     * 谁先到都收一次，后到的不会重复跑。
     *
     * 幂等、不抛；连接已经结束（或从来没存在过）是无害的 no-op。
     */
    fun abortConnection(connectionId: Long) {
        connectionResources[connectionId]?.invoke()
    }

    /**
     * **按 runId 收口这条 run 的连接资源**（A11，§9.2）：执行终结（自然退出 / 被停 / 被掐 /
     * 宿主急停）时由 [com.autoscript.appservice.runtime.RuntimeController] 调用。
     *
     * 与 [abortConnection] 的分工：那条是**从连接外部按连接号**撤（调用方已经握着连接号），
     * 本条是**按 runId** 撤 —— 而执行仲裁者自始至终只认 runId（连接号在接入端内部发出，
     * 它无从得知，见 `runConnections` 的 KDoc）。两条落到**同一个幂等收口口**上。
     *
     * **刻意不关 IO、只收资源**：run 自然结束时连接可能还在排空尾帧（§7.5 EOF 排空语义），
     * 那时把 socket 硬关掉会把「脚本正常跑完」误走成硬撤销。收口目标只是「脚本没了，
     * 投屏会话不该继续挂着」，与连接何时结束是两件事。
     *
     * 幂等、不抛；该 run 从未连过桥（没有映射）或连接早已结束，都是无害的 no-op ——
     * 那两种情况下资源要么不存在、要么已经被 dispose 收过。
     */
    fun revokeRunResources(runId: Long) {
        runConnections[runId]?.let { connectionResources[it]?.invoke() }
    }

    private inner class Connection(private val closer: () -> Unit) {
        val id = connectionIds.getAndIncrement()
        private val stateLock = Any()
        private var ended = false
        private var draining = false
        private var owner: Job? = null
        private var drainTimer: Job? = null
        private var binding: RunIdentityRegistry.Binding? = null

        /** 认证后装填；dispose 时据此摘掉 `runConnections` 条目（0 = 从未绑定）。 */
        private var boundRunId: Long = 0L

        /**
         * 这条连接的资源收口（§7.5 × §9.2）：注册表**在 serve 一开始就建**（早于认证），
         * 于是"认证成功后开的资源"与"连接撤销"之间没有窗口 —— 撤销先发生的话，
         * 之后登记的收口回调会**立刻兑现**（见 `ConnectionResourceRegistry.register`）。
         */
        private val resources = ConnectionResourceRegistry { onProtocolError("连接资源收口失败") }

        init {
            // 接入端把这条连接的注册表挂到 connectionId 上（撤销时按它收口）。
            connectionResources[id] = { resources.revokeAll() }
        }

        fun abort() {
            val job = synchronized(stateLock) { ended = true; owner }
            // 先取消投递域，再立即关闭 IO（取消回调也会 close）；绝不等 job 完成才关 fd。
            // 若先 close 再 cancel，EOF 可抢先唤醒 reader，把硬撤销误走成自然排空。
            job?.cancel()
            runCatching { closer() }
            // 连接级资源收口（§9.2）：脚本崩了/被掐了，它开的投屏会话不能继续挂着。
            resources.revokeAll()
            router.closeConnection(id)
        }

        private fun beginDrain() = synchronized(stateLock) {
            if (!draining && !ended) {
                draining = true
                drainTimer = scope.launch { delay(drainMillis); abort() }
            }
        }

        suspend fun serve(input: InputStream, output: OutputStream, session: InputChannelSession, peerPid: Int?) {
            val current = currentCoroutineContext()[Job]!!
            synchronized(stateLock) { owner = current; if (ended) current.cancel() }
            currentCoroutineContext().ensureActive()
            val buffered = BufferedInputStream(input)
            val timeout = scope.launch { delay(handshakeMillis); abort() }
            var permit = false
            try {
                if (!handshakes.tryAcquire()) return
                permit = true
                val hello = readBlocking { readFrame(buffered, BridgeHandshake.MAX_BYTES) } ?: return
                val token = try { BridgeHandshake.decodeHello(hello) } catch (_: Exception) {
                    writeBlocking(output, BridgeHandshake.error(ErrorCode.ERR_PERMISSION_DENIED))
                    return
                }
                val bound = try {
                    identities.authenticate(token, peerPid, id, ::beginDrain, ::abort)
                } catch (e: CancellationException) { throw e } catch (_: AutojsException) {
                    writeBlocking(output, BridgeHandshake.error(ErrorCode.ERR_PERMISSION_DENIED))
                    return
                }
                binding = bound
                // 把这条连接的资源收口口交给身份（§9.2）：此后这条连接上开的进程级资源
                // 都能在 abort/dispose 时被统一收掉。装填点必须在这里 —— 早于任何业务请求，
                // 晚于认证（撤销先发生时 `register` 会立刻兑现，不会漏）。
                bound.caller.resources = resources
                // A11：runId ↔ connectionId 在这里成对（认证一成功就是**任何业务请求之前**，
                // 见 `runConnections` KDoc 的窗口论证）。`boundRunId` 与表项同批写，
                // 供 dispose 摘除。
                boundRunId = bound.caller.engineRunId
                runConnections[boundRunId] = id
                writeBlocking(output, BridgeHandshake.ack())
                timeout.cancel()
                handshakes.release()
                permit = false
                // 返回的 Job 包含读循环 + 全部请求排空，不能 EOF 就先关闭 output。
                supervisorScope {
                    while (isActive) {
                        val frame = readBlocking { readFrame(buffered, maxFrameBytes) } ?: break
                        // 条数与字节**各取一闸、取闸与起协程之间不留窗口**（外审 M2）：
                        // 先拿字节再拿条数 —— 反过来的话，「拿到条数许可证但字节超限」那条
                        // 路必须把条数许可证还回去，少一处还就少一份配额。
                        // 「拒一条」那一支与主循环体一起搬进 [serveAcceptedFrame]（detekt
                        // `LoopWithTooManyJumpStatements`：循环里只留 `break`）。
                        this@supervisorScope.serveAcceptedFrame(frame, output, bound.caller, session)
                    }
                    beginDrain()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: FrameTooLargeException) {
                onProtocolError("桥帧超过上限")
            } catch (_: Exception) {
                // 断链/关闭；不记录原始 hello 或异常文本，以免 token 被诊断回显。
                onProtocolError("桥连接 IO 结束")
            } finally {
                timeout.cancel()
                if (permit) handshakes.release()
                dispose()
            }
        }

        /** suspend 的取消回调直接 close IO，读线程由 IO 域排水退出，不挂死关闭调用方。 */
        private suspend fun <T> readBlocking(action: () -> T): T = suspendCancellableCoroutine { continuation ->
            val worker = scope.launch(Dispatchers.IO) {
                val result = runCatching(action)
                continuation.resumeWith(result)
            }
            continuation.invokeOnCancellation { runCatching { closer() }; worker.cancel() }
        }

        private suspend fun writeBlocking(output: OutputStream, bytes: ByteArray) = readBlocking {
            synchronized(output) { output.write(bytes); output.flush() }
        }

        /**
         * 一帧的「受理或拒绝」全段（外审 M2 抽出来的：字节闸 + 条数闸 + 起协程 + 成对归还）。
         *
         * 抽出来有两个理由：
         * 1. **detekt `LoopWithTooManyJumpStatements`** —— 读循环里已有 `?: break`，字节闸那
         *    一支再加 `continue` 就是两个跳转；
         * 2. **可读性更重要**：「拒一条」与「断一条」是本批最容易被改错的判据
         *    （`continue` 写成 `break`，就把「一个请求的配额问题」升级成「连接断掉」）——
         *    给它一个名字比让它躺在循环中间耐改。
         *
         * 归还成对且在 [Job.invokeOnCompletion] 里（那里覆盖「launch 前/首次调度前被取消」
         * 那一档 —— 与既有 [inFlight] 归还同一条纪律）。
         */
        private suspend fun kotlinx.coroutines.CoroutineScope.serveAcceptedFrame(
            frame: ByteArray,
            output: OutputStream,
            caller: com.autoscript.domain.bridge.AuthenticatedRunContext,
            session: InputChannelSession,
        ) {
            // **一份快照管这一条请求的全程**（批 107）：配额改小也不掐在途请求（见
            // [updatePerConnectionLimits] 的 KDoc）。取一次、下面全程用这一份。
            val mine = perConnection
            val size = frame.size.toLong()
            // 四道闸，一个一个过；**每过一道就记住过了**，被拒时按已经取过的那几道原路归还 ——
            // 「拒一半、还一半」是最容易漏的那种漂：许可悬着，后来者永远等不到。
            if (!tryReserveBytes(size)) {
                rejectOverQuota(frame, output, "全局在途字节", "全局上限 ${maxBytesInFlight} B")
                return
            }
            if (size > mine.bytes || !tryReserveOwnBytes(size)) {
                bytesInFlight.addAndGet(-size)                       // ← 只还取过的那一道
                rejectOverQuota(frame, output, "本连接在途字节", "本连接上限 ${mine.bytes} B")
                return
            }
            inFlight.acquire()
            val ownCount = ownInFlight(mine.inFlight)
            if (ownCount == null) {
                // 连接级闸建不起来（见 [ownInFlight]）：**降级为只有全局那两道**，
                // 而不是拒掉这条连接上的所有请求 —— 那会把一次内部初始化失败变成「脚本全挂」。
                requestJob(mine, size, frame, output, caller, session, ownCount = null)
                return
            }
            if (!ownCount.tryAcquire()) {
                inFlight.release()
                ownBytes.addAndGet(-size)                           // ← 还取过的两道
                rejectOverQuota(frame, output, "本连接在途条数", "本连接上限 ${mine.inFlight} 条")
                return
            }
            requestJob(mine, size, frame, output, caller, session, ownCount = ownCount)
        }

        /**
         * 起请求协程 + 挂归还（批 107：归还从上一段搬进来，与取闸写在同一处）。
         *
         * [ownCount] 是「这一条**实际取过**的那个连接级闸」，null = 没取过 → 归还只还前两道。
         * **传实例而不是传布尔**：配额在「取闸」与「协程结束」之间被改过时，`ownInFlight()`
         * 会换成一个新闸 —— 那时按布尔去查「当前那个闸」就会还错闸（新闸多还一个 = 配额
         * 悄悄放宽，且此后每条请求都多还一个，永远追不回来）。
         * 少还一道 = 许可永久漏掉 = 配额随时间单调收紧，而那个现象在现场极难归因
         * （「昨天还好好的，今天跑一会儿就全被拒了」没有任何日志指向它）。
         */
        private fun kotlinx.coroutines.CoroutineScope.requestJob(
            mine: PerConnectionLimits,
            size: Long,
            frame: ByteArray,
            output: OutputStream,
            caller: com.autoscript.domain.bridge.AuthenticatedRunContext,
            session: InputChannelSession,
            ownCount: Semaphore?,
        ) {
            val requestJob = launch(
                caller + session + handlerDispatcher(mine.concurrent),
                start = CoroutineStart.LAZY,
            ) {
                handleFrame(frame, output)
            }
            requestJob.invokeOnCompletion {
                bytesInFlight.addAndGet(-size)
                inFlight.release()
                ownBytes.addAndGet(-size)
                ownCount?.release()
            }
            requestJob.start()
        }

        /**
         * 连接级条数闸，**按配额大小缓存**（批 107）。
         *
         * 为什么不每次现造一个 [Semaphore]：许可数是**闸的容量**，一条连接在配置没变时必须
         * 始终用**同一个**实例，否则「同一连接的请求数上限」在语义上就不存在（每条请求
         * 各自一闸 = 没有闸）。配置变了就换一个新闸（旧的让在途请求跑完）——
         * 这正是 [updatePerConnectionLimits] 说的「不追溯在途」的具体形态。
         *
         * 建不起来（配额非正 —— 那不该发生，[updatePerConnectionLimits] 已 require）时
         * 回 null，由调用方**降级**而不是拒连接（见 [serveAcceptedFrame]）。
         */
        private fun ownInFlight(limit: Int): Semaphore? {
            val existing = ownCount
            if (existing != null && ownCountLimit == limit) return existing
            val created = runCatching { Semaphore(limit) }.getOrNull() ?: return null
            ownCount = created
            ownCountLimit = limit
            return created
        }

        @Volatile private var ownCount: Semaphore? = null
        @Volatile private var ownCountLimit: Int = -1

        /**
         * 本连接的在途字节账（[ownInFlight] 的字节档）。**CAS 取闸**而不是信号量 ——
         * 与全局 [bytesInFlight] 同一条理由（字节数是变量不是许可个数，见那里的 KDoc）。
         *
         * 上限**每请求现读**（不像条数闸要缓存）：它是个纯数字，与「闸实例」无关，
         * 少了缓存那层就没有「换闸」这件事，也就没有「在途请求用哪一档」的歧义。
         */
        private val ownBytes = AtomicLong(0)

        /** CAS 取本连接字节闸；超限 false（见 [tryReserveBytes] 同款形状与理由）。 */
        private fun tryReserveOwnBytes(n: Long): Boolean {
            while (true) {
                val current = ownBytes.get()
                if (current + n > perConnection.bytes) return false
                if (ownBytes.compareAndSet(current, current + n)) return true
            }
        }

        /**
         * 配额拒掉的那一条：**回一帧错误**（不静默丢 —— 静默丢的话脚本只会干等到 TTL 超时，
         * 那才是真正的坑）。话术**点名是哪一道闸**（全局字节 / 本连接字节 / 本连接条数），
         * 以免与 [rejectOverByteQuota] 那条混淆成「好像都是字节」。
         *
         * 顺带走一次 [QuotaSettings.onRejected]：批 100 说这两半要「连同可见面一起设计」，这就是
         * 那条可见面里**唯一用户能拿到的直接证据**（另一半是 [connectionStats] 的实时读数）。
         */
        private suspend fun rejectOverQuota(
            frame: ByteArray,
            output: OutputStream,
            which: String,
            limit: String,
        ) {
            quotaSettings.onRejected("桥配额拒请求：连接 $id 撞到$which（$limit）")
            val requestId = transport.probeRequestId(frame) ?: return
            val err = BridgeResponse.Err(
                requestId,
                ErrorCode.ERR_PERMISSION_DENIED.code,
                "$which 超限（$limit）—— 这是宿主设的配额，不是参数错；调大它去设置 → 脚本设置",
            )
            writeBlocking(output, transport.encodeResponse(err) + LF)
        }

        /**
         * 本连接的处理并行度闸（批 107；`limitedParallelism` 那半）。
         *
         * **它限的是「并发执行的协程数」不是「线程数」** —— 桥 handler 绝大多数时候在等
         * IO（截图、a11y 树、shell 回读），协程挂起时线程就还给池子，故「并发协程数」才是
         * 这条连接的并行度上界；用线程数当判据会得到一个小得多且更难解释的数。
         *
         * **缓存而不是每次现造**：同一条连接在配置没变时反复 `limitedParallelism` 会造出
         * 一个个新视图，每个都带一份限流状态 —— 而这里要的正是「同一条连接共用同一个上界」，
         * 现造就等于每条请求一个独立的上界（闸形同虚设）。
         */
        private fun handlerDispatcher(concurrent: Int): kotlin.coroutines.CoroutineContext {
            handlerDispatchers[concurrent]?.let { return it }
            val created = scope.coroutineContext
                .minusKey(Job)
                .plus(Dispatchers.IO.limitedParallelism(concurrent))
            handlerDispatchers[concurrent] = created
            return created
        }
        private val handlerDispatchers = HashMap<Int, kotlin.coroutines.CoroutineContext>()

        /** 本连接的真实读数（可见面；见 [ConnectionStat]）。 */
        fun stat(): ConnectionStat {
            val mine = perConnection
            return ConnectionStat(
                connectionId = id,
                runId = boundRunId,
                inFlight = mine.inFlight - (ownCount?.availablePermits ?: mine.inFlight),
                inFlightLimit = mine.inFlight,
                bytes = mine.bytes - ownBytes.get().coerceAtLeast(0L),
                bytesLimit = mine.bytes,
            )
        }

        private suspend fun handleFrame(frame: ByteArray, output: OutputStream) {
            try {
                val request = try {
                    transport.decodeRequest(frame)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    onProtocolError("非法请求帧")
                    transport.probeRequestId(frame)?.let {
                        val error = BridgeResponse.Err(it, ErrorCode.ERR_INVALID_PARAM.code, "非法请求帧")
                        writeBlocking(output, transport.encodeResponse(error) + LF)
                    }
                    return
                }
                val response = router.dispatch(request)
                writeBlocking(output, transport.encodeResponse(response) + LF)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 对端自然退出后无法回 ACK，不等于已送来的尾帧作废；有界读至 EOF 并继续排空。
                beginDrain()
            }
        }

        fun dispose() {
            synchronized(stateLock) { ended = true; drainTimer?.cancel() }
            binding?.close()
            // 连接级资源收口（§9.2）：正常结束也要收（EOF 退出、对端关连接）。
            // 与 abort 的调用幂等 —— 注册表按"恰好一次"兑现。
            resources.revokeAll()
            router.closeConnection(id)
            runCatching { closer() }
            // 连接结束了：摘掉按号索引的收口口（此后 `abortConnection(id)` 是无害的 no-op，
            // 也不再留一个悬挂引用）。资源本身在上一行已经收过。
            connectionResources.remove(id)
            // 摘掉 runId 侧的索引（条件 remove：只摘**仍指向本连接**的那一条 —— 连接号不复用，
            // 但条件形式让「摘不掉」也是无害的，不至于误删别人的映射）。
            if (boundRunId != 0L) runConnections.remove(boundRunId, id)
            synchronized(lock) { connections.remove(this) }
        }
    }

    private fun readFrame(input: InputStream, limit: Int): ByteArray? {
        var buf = ByteArray(minOf(256, limit))
        var length = 0
        while (true) {
            val b = input.read()
            if (b == -1) return null // 未以换行结尾的半帧不是已接收请求。
            if (b == '\n'.code) {
                val size = if (length > 0 && buf[length - 1] == '\r'.code.toByte()) length - 1 else length
                return buf.copyOf(size)
            }
            if (length >= limit) throw FrameTooLargeException()
            if (length == buf.size) buf = buf.copyOf(minOf(limit, buf.size * 2))
            buf[length++] = b.toByte()
        }
    }

    override fun close() {
        val all = synchronized(lock) {
            if (closed) return
            closed = true
            listeners.forEach { runCatching { it.close() } }
            listeners.clear()
            connections.toList()
        }
        identities.close()
        // `abort()` 里面已经带了连接级资源收口（§9.2），这里不重复一遍 ——
        // 两条路都落到同一个幂等收口口上，多调一次只是白跑一趟空表。
        all.forEach { it.abort() }
        scope.cancel()
    }

    companion object {
        const val DEFAULT_MAX_FRAME_BYTES = 8 * 1024 * 1024
        const val DEFAULT_MAX_IN_FLIGHT = 256

        /**
         * 在途请求字节上限（外审 M2，批 100）：**条数闸的搭档**。
         *
         * 取值依据：最坏形态是「[DEFAULT_MAX_FRAME_BYTES]（8 MiB）× [DEFAULT_MAX_IN_FLIGHT]
         * （256）= 2 GiB」—— 那不是理论值，是把两个已生效的默认值相乘就能得到的数。
         * 本仓的合法用法（截图帧、无障碍树、图像分析）在**数十 KiB 到 1 MiB** 量级，
         * 16 MiB 足够几十条大帧同时在飞；再往上是「拿大帧把宿主堆撑死」那一档。
         * 与条数上限一样：它是**策略**，不是物理限制 —— 真要更大改此常量即可。
         */
        const val DEFAULT_MAX_BYTES_IN_FLIGHT: Long = 16L * 1024 * 1024

        /** accept 瞬时故障后的退避（毫秒），与 `BridgeSocketListener` 同一取值口径。 */
        const val ACCEPT_RETRY_BACKOFF_MILLIS: Long = 1_000L

        /** 连续 accept 失败多少次才认定「不是瞬时的」而收桥（`BridgeSocketListener` 同名常量同值）。 */
        const val ACCEPT_MAX_CONSECUTIVE_FAILURES: Int = 20
        private val connectionIds = AtomicLong(1)
        private val LF = byteArrayOf(10)
    }
}

class FrameTooLargeException : IllegalStateException("帧超过上限")
