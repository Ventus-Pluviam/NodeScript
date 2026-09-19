package com.autoscript.bridge

import com.autoscript.domain.bridge.BridgeResponse
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * newline-delimited JSON frame 传输服务端（docs/framework-design.md §7.5）。
 *
 * 与 `bridge/js` 的 `SocketBootstrap` 双侧对齐：
 * - 一行一帧：请求 `{"t":"req",...}\n` → [BridgeRouter.dispatch] → 响应 `{"t":"ok"|"err",...}\n` 回写；
 * - 每行独立协程 dispatch，响应按 requestId 关联（允许乱序回包，客户端按 id 结算）；
 * - 非法帧（超 [maxFrameBytes] / 非法 JSON / 非请求信封）→ 记 [onProtocolError]，未知 id 无法回包则丢弃，
 *   连接保持（除超限帧：直接关连接，防内存吞噬）；
 * - EOF 即正常结束；[close] 取消全部在途任务并关闭监听。
 *
 * 传输介质由调用方决定：桌面/CI 走 loopback TCP（`ServerSocket(0)`），设备上走 unix domain socket
 *（`ServerSocketChannel.bind(UnixDomainSocketAddress)`，同一 read/write 路径）。
 */
class NewlineFrameServer(
    private val router: BridgeRouter,
    private val transport: JsonTransport = JsonTransport(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val maxFrameBytes: Int = DEFAULT_MAX_FRAME_BYTES,
    private val onProtocolError: (reason: String) -> Unit = {},
) : AutoCloseable {

    /** 监听循环：每个 accept 起一个连接任务。返回监听 Job（取消即停）。 */
    fun acceptLoop(serverSocket: ServerSocket): Job = scope.launch {
        while (true) {
            val socket = try {
                serverSocket.accept()
            } catch (_: Exception) {
                break // 监听关闭
            }
            serveConnection(socket)
        }
    }

    /** 服务一条连接：读帧 → 并发 dispatch → 回写。返回读循环 Job（EOF 即正常结束）。 */
    fun serveConnection(socket: Socket): Job = scope.launch {
        socket.use { s ->
            readLoop(s.getInputStream(), s.getOutputStream())
        }
    }

    /** 流版本（测试/自定义传输直接用；与 socket 版本同一语义）。 */
    fun serveConnection(input: InputStream, output: OutputStream): Job = scope.launch {
        readLoop(input, output)
    }

    /**
     * 读循环：读一帧起一个 server-scope 协程 dispatch（慢请求不挡快请求，响应按 id 关联可乱序）。
     * 帧任务挂在 server scope 而非连接 Job 下：EOF 关连接时在途 dispatch 不被连带取消
     *（写回失败即对端已走，丢弃）。
     */
    private suspend fun readLoop(input: InputStream, output: OutputStream) {
        val buffered = BufferedInputStream(input)
        while (true) {
            val frame = try {
                readFrame(buffered)
            } catch (e: FrameTooLargeException) {
                onProtocolError("帧超过上限 ${maxFrameBytes} 字节")
                break // 关连接，防内存吞噬
            } catch (_: Exception) {
                break
            } ?: break // EOF：对端正常关闭
            // 每帧独立 server-scope 协程（scope.launch 非连接 Job.launch）：
            // 慢请求不挡快请求，响应按 id 关联可乱序；EOF 关连接时在途 dispatch 不被连带取消。
            scope.launch {
                val response: BridgeResponse = try {
                    val req = transport.decodeRequest(frame)
                    router.dispatch(req)
                } catch (e: IllegalArgumentException) {
                    onProtocolError("非法请求帧: ${e.message}")
                    return@launch // 无有效 id，无法回包，丢弃
                } catch (e: Exception) {
                    onProtocolError("dispatch 异常: ${e.message}")
                    return@launch
                }
                val line = transport.encodeResponse(response) + LF
                synchronized(output) {
                    try {
                        output.write(line)
                        output.flush()
                    } catch (_: Exception) {
                        // 写失败（对端已走）：丢弃，不抛
                    }
                }
            }
        }
    }

    /**
     * 读一帧（到 `\n` 为止，不含换行）。返回 null = EOF（无任何字节）。
     * 超 [maxFrameBytes] 抛 [FrameTooLargeException]；`\r\n` 兼容（去尾 `\r`）。
     */
    private fun readFrame(input: InputStream): ByteArray? {
        // 手工逐字节读：BufferedReader.readLine 无长度上限，恶意巨帧会吞内存。
        var buf = ByteArray(256)
        var len = 0
        while (true) {
            val b = input.read()
            if (b == -1) return if (len == 0) null else buf.copyOf(len).trimCr()
            if (b == '\n'.code) return buf.copyOf(len).trimCr()
            if (len + 1 > maxFrameBytes) throw FrameTooLargeException()
            if (len == buf.size) buf = buf.copyOf(buf.size * 2)
            buf[len++] = b.toByte()
        }
    }

    private fun ByteArray.trimCr(): ByteArray =
        if (isNotEmpty() && last() == '\r'.code.toByte()) copyOf(size - 1) else this

    override fun close() {
        scope.cancel()
    }

    companion object {
        const val DEFAULT_MAX_FRAME_BYTES: Int = 64 * 1024 * 1024
    }
}

class FrameTooLargeException : IllegalStateException("帧超过上限")

private operator fun ByteArray.plus(other: ByteArray): ByteArray {
    val out = ByteArray(size + other.size)
    copyInto(out)
    other.copyInto(out, size)
    return out
}

private val LF = "\n".toByteArray(StandardCharsets.UTF_8)
