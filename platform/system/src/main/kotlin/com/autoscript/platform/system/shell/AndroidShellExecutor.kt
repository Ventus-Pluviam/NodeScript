package com.autoscript.platform.system.shell

import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * `shell` 命名空间的 Android 实现（docs §9.6；SPI 见 [ShellExecutor]，
 * 语义层 handler 住 `:platform:capabilities` 的 `ShellNamespaceHandler`）。
 *
 * 三条契约在这里兑现，每条都有对应单测：
 *
 * 1. **超时是实现者义务**（铁律 3）：[ShellExecutor] 的 KDoc 明说「调用方只传建议值，
 *    实现不得无限等待」。所以超时到点就 `destroyForcibly()` 并抛
 *    [ErrorCode.ERR_TIMEOUT] —— 不是"返回已读到的半截输出"，更不是继续挂着。
 * 2. **任何退出路径都要收尸（但「收尸」不等于「杀干净整棵树」——2026-10-11 批 99 订正）**：
 *    成功、超时、被上层取消（桥 TTL 到点会取消 handler 协程）三条路都走 `finally` 里的
 *    [Process.destroyForcibly]。**但那条只杀直接子进程**（`sh` 或 `su`）—— 它们自己起的
 *    孙进程（`sh -c "sleep 300 &"`）不会被连带杀掉，仍留在设备上。批 99 起按 [killTree]
 *    走 `/proc` ppid 图把整棵树收掉（复用批 95 的同一手法，见下方第 5 条）。
 *    **诚实边界**：批 99 之前那条 KDoc 写的是「不留孤儿进程」，那句话**不成立** ——
 *    原实现只保证「不留直接子进程」。
 * 3. **双流必须并发读干**：管道缓冲区（Linux 默认 64KB）写满即阻塞子进程，
 *    先读干 stdout 再读 stderr 会在输出超过一屏时死锁 —— 所以两条读流在
 *    `waitFor` **之前**就起来了。
 * 4. **捕获有上限**（2026-10-02，backlog A2b）：每条流最多留
 *    [ShellCaptureLimit.MAX_CAPTURE_BYTES]，超出即**静默截断 + Warning + 置标志**
 *    （口径见 `docs/design-decisions.md` 第 21 项）。**关键在「继续读干」**：
 *    到顶后仍然把管道读到 EOF，只是不再往缓冲里放字节 —— 停下来不读的话
 *    子进程会阻塞在写满的管道上，那正是第 3 条要防的死锁，截断反而把它请回来。
 *
 * **读流走独立线程，不是 `async` 子协程**（2026-10-01 修，见 `docs/backlog.md` A2）：
 * `InputStream.read` 阻塞期间**不响应协程取消**（协程取消只标记状态、线程中断也不保证
 * 唤醒它），而 `coroutineScope` 要等全部子协程结束才传播异常/取消 —— 于是超时抛出后
 * 卡在等两条读流上，`finally` 里的 `destroyForcibly()` 反而永远轮不到执行。
 * 真机形态就是「脚本设 1s 超时，实际卡 100s 且没杀掉 `su`」；子进程把管道写端交给
 * 孙进程时（`su -c …`、`sh -c "… &"`）必现 —— 那时杀掉直接子进程也换不来 EOF
 * （写端还握在孙进程手里）。现在读流跑在**可弃的守护线程**上（[PipeReader]），
 * 协程只 await 一个可中断的闩：超时/取消都能立刻返回，读线程随管道关闭自然结束，
 * 或在极端情况下作为孤立守护线程被丢弃（不占 `Dispatchers.IO` 的共享池）。
 *
 * 参数用 argv 数组而非拼串（`su -c "$command"` 那种）：命令里的引号/空格/`$` 一旦
 * 参与拼接就要靠转义猜，argv 形态让内核直接收参数，不经过第二层 shell 解析。
 *
 * **本类不做能力门禁**：ROOT 模式能不能用由装配层的 `PermissionFacade` 先判
 * （§9.5），本类只负责"已经决定要执行之后"的执行与收尸。`su` 不存在时
 * [launcher] 抛 [IOException]，这里如实折成 [ErrorCode.ERR_SERVICE_DISABLED]。
 */
class AndroidShellExecutor(
    private val launcher: ProcessLauncher = ProcessLauncher { Runtime.getRuntime().exec(it) },
    private val logSink: ShellCaptureLimit.LogSink = ShellCaptureLimit.defaultLogSink,
    /**
     * 进程树收尸的操作面（外审 M6，批 99；缝的理由见 [killTree]）。
     * 缺省生产实现走 `/proc` + `kill` 二进制（与批 95 的 T1 杀树逐字同款手法）。
     */
    private val treeOps: TreeOps = ProcTreeOps(),
) : ShellExecutor {

    /** 进程启动缝：真机走 [Runtime.exec]；单测注入以记录 argv / 供可控进程。 */
    fun interface ProcessLauncher {
        fun launch(argv: Array<String>): Process
    }

    override suspend fun exec(
        command: String,
        mode: ShellMode,
        timeoutMillis: Long,
    ): ShellResult {
        require(timeoutMillis > 0) { "shell 超时必须是正数（铁律 3：不允许无限等待），实际 $timeoutMillis" }
        val argv = argvFor(command, mode)
        return withContext(Dispatchers.IO) {
            val process = try {
                launcher.launch(argv)
            } catch (e: IOException) {
                throw AutojsException(
                    ErrorCode.ERR_SERVICE_DISABLED,
                    "shell 通道不可用（mode=$mode）：${e.message}",
                    e,
                )
            }
            val out = PipeReader(process.inputStream, "autoscript-shell-stdout").start()
            val err = PipeReader(process.errorStream, "autoscript-shell-stderr").start()
            // stdin 立即关（外审 M6，批 99）：shell 面的契约是「**只执行命令，不喂输入**」
            // （[ShellExecutor.exec] 签名里根本没有 stdin 参数）。不关的话，等 stdin 的
            // 命令（`cat`、`read x`）会**一直挂到超时** —— 用户看到的是「一条 cat 命令
            // 跑满超时还返回空」，而根因是宿主攥着写端不撒手。关掉之后它们立刻读到 EOF
            // 并正常退出 —— 这才是「没有输入」该有的形态。
            runCatching { process.outputStream.close() }
            try {
                // runInterruptible：桥 TTL 到点取消本协程时，阻塞中的 waitFor 才收得到中断
                //（否则取消只能等进程自己结束，"超时"就成了一句空话）。
                val finished = runInterruptible {
                    process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
                }
                if (!finished) {
                    // 先杀再抛：抛出去之后本函数只剩 finally，中间没有任何顺序保证能证明
                    // "进程已强杀"这句话 —— 这里显式杀，也让读线程尽早拿到 EOF/重置。
                    killTree(process)
                    throw AutojsException(
                        ErrorCode.ERR_TIMEOUT,
                        "shell 超时 ${timeoutMillis}ms（进程树已强杀）：$command",
                    )
                }
                // 进程已退出 ⇒ 写端关闭 ⇒ 两条读流应当**立刻** EOF（正常形态：剩余的
                // ≤64KB 还在内核管道缓冲里，读出来是微秒级的事）。宽限只兜一个例外：
                // 写端被孙进程继承（`sh -c "… &"`），此时输出不再属于这条命令，
                // 等到点就带着已读到的字节返回 —— 铁律 3 不允许为它无限等待。
                out.awaitEofOrGiveUp(DRAIN_GRACE_MILLIS)
                err.awaitEofOrGiveUp(DRAIN_GRACE_MILLIS)
                // 截断在**两条流都抽干之后**才播报：这样告警里的"丢了多少"是终值，
                // 不是"到此刻为止"——日志里出现两个数会让人以为丢了两次。
                warnIfTruncated("stdout", out, command)
                warnIfTruncated("stderr", err, command)
                ShellResult(
                    code = process.exitValue(),
                    stdout = out.textOrNull(),
                    stderr = err.textOrNull(),
                    truncated = out.truncated || err.truncated,
                )
            } finally {
                // 收尸兜底：超时/取消/异常路径都把**整棵树**收掉（见 KDoc 第 2 条与 [killTree]）。
                if (process.isAlive) killTree(process)
            }
        }
    }

    /**
     * 收掉**整棵进程树**（外审 M6，批 99）。
     *
     * [Process.destroyForcibly] 只杀直接子进程（`sh`/`su`），它们自己起的孙进程留在设备上
     * —— 而「越跑越卡」这种现场恰恰查不到源头。手法与批 95 的 T1 杀树**逐字同款**：
     * **扫在杀之前**（reparent 只断死后回找，pid 集合在树还活着时到手）、同 UID 靠 fork
     * 继承（ppid 图上可达即同 UID）、按 pid 杀用 `kill` 二进制（JVM 没有「按 pid 杀」的
     * API）、pid 取不到就**诚实降级为只杀直接子进程**（不更坏、不炸收尸）。
     *
     * **为什么不在这里 new 一份而要注入**（与 T1 的 `T1TreeOps` 同理由）：判据要能用替身
     * 跑成确定性用例（「孙进程确实被点名了」），而真 `/proc` 那条由真进程用例单独钉。
     */
    private fun killTree(process: Process) {
        val firstWave = runCatching { treeOps.descendantsOf(process) }.getOrDefault(emptySet())
        runCatching { treeOps.kill(firstWave, force = false) }     // 后代 TERM（先点名，主进程还在）
        process.destroyForcibly()
        runCatching { treeOps.kill(firstWave, force = true) }
        // pid 拿不到 / /proc 不可读 → firstWave 为空：上面退化成「只杀主进程」，
        // 与批 95 的降级口径一致（不更坏、也不假装收干净了）。
    }

    /**
     * 截断发生时的**显式日志 Warning**（口径第 2 层：程序看 [ShellResult.truncated]，
     * 运维看这条日志，人看流末尾的 [ShellCaptureLimit.TRUNCATION_MARK]）。
     *
     * 走注入的 [logSink] 而不是直接 `android.util.Log`：`:platform:system` 的 JVM 单测
     * 没有 `isReturnDefaultValues`，直接调会抛 "not mocked"（详见 [ShellCaptureLimit.LogSink]）。
     * 日志里带**命令本身**（截断几乎总是某条具体的 `cat`/`dumpsys` 干的，没有它这条告警
     * 只能告诉人"有东西被截了"，定位不到是谁）。
     */
    private fun warnIfTruncated(stream: String, reader: PipeReader, command: String) {
        if (!reader.truncated) return
        logSink.warn(
            "shell $stream 输出超过上限 ${ShellCaptureLimit.MAX_CAPTURE_LABEL}，" +
                "已截断（丢弃 ${reader.droppedBytes} 字节；结果 truncated=true）：$command",
        )
    }

    /**
     * 单条管道的抽干器：**一条守护线程**把 [input] 读到 EOF 或读端失效，字节进内存缓冲。
     *
     * 为什么是裸线程而不是 `Dispatchers.IO` 上的协程（KDoc 顶部有完整病灶）：
     * 阻塞读不可取消，唯一可行的姿势是"跑在一条可以丢弃的线程上，协程只等一个可中断的闩"。
     * 用裸守护线程还有第二个好处 —— 被丢弃时不会占住 `Dispatchers.IO` 的共享工作线程
     * （那个池子是全 App 共用的，被管道读流占满会连累无关的业务）。
     */
    private class PipeReader(
        private val input: InputStream,
        private val name: String,
        private val capBytes: Int = ShellCaptureLimit.MAX_CAPTURE_BYTES,
    ) {

        // ByteArrayOutputStream 的 write/toByteArray 自带同步：宽限到点后调用方拿到的是
        // "此刻已读到的字节"快照，不需要额外加锁。截断计数同理（@Volatile 供读侧现取）。
        private val buffer = ByteArrayOutputStream(INITIAL_BUFFER_BYTES)
        private val eof = CountDownLatch(1)
        private val thread = Thread({ pump() }, name).apply { isDaemon = true }

        /** 到顶后**被丢弃**的字节数（只计不再进缓冲的那些，不是"流的总长度"）。 */
        @Volatile
        var droppedBytes: Long = 0L
            private set

        /** 是否发生过截断（即 [droppedBytes] > 0）。 */
        val truncated: Boolean get() = droppedBytes > 0L

        fun start(): PipeReader {
            thread.start()
            return this
        }

        private fun pump() {
            try {
                input.use {
                    val chunk = ByteArray(COPY_CHUNK_BYTES)
                    while (true) {
                        val n = it.read(chunk)
                        if (n < 0) break
                        if (n > 0) append(chunk, n)
                    }
                }
            } catch (_: IOException) {
                // 进程被杀时读端被重置（EIO/"Stream closed"）是**正常收尾** —— 超时路径
                // 必然走到这里；已读到的字节照常交回。shell 面没有比这更细的分类可用。
            } finally {
                eof.countDown()
            }
        }

        /**
         * 收下 [n] 字节，**至多收到 [capBytes]**；多出来的只计数不落缓冲。
         *
         * 注意这个函数**仍然每轮都被调用**（读循环没停）—— 到顶后不读的写法会让
         * 子进程卡在写满的管道上（见类 KDoc 第 3/4 条），那是比内存膨胀更坏的死锁。
         * 上限按**字节**判：UTF-8 一个字符最多 4 字节，所以在 `capBytes` 处切断
         * 可能切在字符中间，`toTextOrNull()` 用替换字符收场（既有口径，不另造）。
         */
        private fun append(chunk: ByteArray, n: Int) {
            val room = capBytes - buffer.size()
            if (room <= 0) {
                droppedBytes += n
                return
            }
            if (n <= room) {
                buffer.write(chunk, 0, n)
            } else {
                buffer.write(chunk, 0, room)
                droppedBytes += (n - room).toLong()
            }
        }

        /**
         * 等 EOF，最多等 [millis]，到点就放弃（带着已读到的字节继续，不抛错）。
         * 用 `runInterruptible` 包住：调用方协程被取消时能立刻返回，不再死等 ——
         * 这正是修复前那个"取消也挂住"的病灶在等待侧的对应修法。
         */
        suspend fun awaitEofOrGiveUp(millis: Long) {
            runInterruptible { eof.await(millis, TimeUnit.MILLISECONDS) }
        }

        /**
         * 已捕获的字节 → 文本。**截断时末尾追加 [ShellCaptureLimit.TRUNCATION_MARK]** ——
         * 给人读的那一层（机器判定用 `ShellResult.truncated`）。
         *
         * 追加发生在文本层而非字节层：上限是**字节**语义，标记是 UTF-8 的多字节串，
         * 混进字节缓冲会把"上限 = capBytes"这句话变成"上限 ≈ capBytes"。
         * 两种终局都追加：即便那 1 MiB 恰好切在多字节字符中间（末尾一个替换字符），
         * 标记照样在 —— 截断过就必须看得出来。
         */
        fun textOrNull(): String? {
            val text = buffer.toByteArray().toTextOrNull()
            if (!truncated) return text
            return (text ?: "") + ShellCaptureLimit.TRUNCATION_MARK
        }
    }

    private companion object {
        /**
         * 进程退出后等读流收敛的宽限（见 [exec] 里的说明）：正常形态下用不到
         * （退出即 EOF），只为"写端被孙进程继承"这一种例外设上限 ——
         * 铁律 3 不允许无限等待，所以这里必须有数。
         */
        const val DRAIN_GRACE_MILLIS = 250L

        const val INITIAL_BUFFER_BYTES = 8 * 1024
        const val COPY_CHUNK_BYTES = 16 * 1024

        /**
         * 模式 → argv（§9.6 三通道）：
         * - [ShellMode.DEFAULT] / [ShellMode.ADB]：`sh -c <cmd>`（adb 通道下设备已身处 adb shell 内，
         *   不需要再套一层 adb 客户端）；
         * - [ShellMode.ROOT]：`su -c <cmd>` —— 命令作为**单个** argv 元素交给 su，由它转交自己的 sh。
         */
        fun argvFor(command: String, mode: ShellMode): Array<String> = when (mode) {
            ShellMode.DEFAULT, ShellMode.ADB -> arrayOf("sh", "-c", command)
            ShellMode.ROOT -> arrayOf("su", "-c", command)
        }

        /**
         * 字节 → 文本：**零字节即 null**（"该流没产出"，与 `ShellResult` 的可空语义对齐），
         * 非空则原样解码、**不裁剪**（尾换行是有信息的字节，裁掉是有损变换；
         * 要 `trim()` 是调用方的事）。非法 UTF-8 用替换字符而非抛异常 ——
         * shell 输出混二进制是常态，为它丢掉整条命令的结果不划算。
         */
        fun ByteArray.toTextOrNull(): String? =
            if (isEmpty()) null else toString(Charsets.UTF_8)
    }
}
