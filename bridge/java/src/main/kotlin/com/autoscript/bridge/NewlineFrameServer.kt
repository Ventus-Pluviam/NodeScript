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
 */
class NewlineFrameServer(
    val router: BridgeRouter,
    val identities: RunIdentityRegistry,
    private val transport: JsonTransport = JsonTransport(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val maxFrameBytes: Int = DEFAULT_MAX_FRAME_BYTES,
    private val maxInFlight: Int = DEFAULT_MAX_IN_FLIGHT,
    private val handshakeMillis: Long = BridgeHandshake.TIMEOUT_MILLIS,
    private val drainMillis: Long = 5_000,
    private val onProtocolError: (reason: String) -> Unit = {},
) : AutoCloseable {
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

    init { require(maxFrameBytes > 0 && maxInFlight > 0 && handshakeMillis > 0 && drainMillis > 0) }

    fun acceptLoop(serverSocket: ServerSocket): Job {
        synchronized(lock) {
            if (closed) serverSocket.close() else listeners.add(serverSocket)
        }
        return scope.launch {
            try {
                while (isActive) {
                    val socket = try { serverSocket.accept() } catch (_: Exception) { break }
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
                        inFlight.acquire()
                        val requestJob = launch(bound.caller + session, start = CoroutineStart.LAZY) {
                            handleFrame(frame, output)
                        }
                        // 取消发生在 launch 前/首次调度前也归还许可证。
                        requestJob.invokeOnCompletion { inFlight.release() }
                        requestJob.start()
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
        private val connectionIds = AtomicLong(1)
        private val LF = byteArrayOf(10)
    }
}

class FrameTooLargeException : IllegalStateException("帧超过上限")
