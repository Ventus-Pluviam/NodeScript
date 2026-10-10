package com.autoscript.appservice.npm

import com.autoscript.domain.json.DomainJson
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * T1 spawn 桥的**宿主侧**（docs §10.3 T1 下半段：stdio 假管道 + pgrp 杀树）。
 *
 * ## 它接的是哪一段
 *
 * 设备上没有可用的 `child_process`（Node-on-Android 不带它），而 `npm run <script>` /
 * `npm exec <bin>` 的本质就是起一个进程。桥的做法：
 *
 * ```
 * npm 会话进程（HostNodeExecutor 起的 node）
 *   └─ NODE_OPTIONS=--require=npm-t1-bridge.cjs     ← 本批落的 shim
 *        └─ npm run build → child_process.spawn("sh", ["-c", body])
 *             └─ shim 把 {cmd,args,opts} 经 unix socket 报给 :main   ← 本类
 *                  └─ :main 按已批准的那条命令起真进程，stdio 经同一条 socket 回填
 * ```
 *
 * **为什么不让 npm 自己 `sh -c` 了事**（那是能跑通的，见 §10.3 T1 的"降级"讨论）：
 * 平台对批准后脚本承诺了三件事 —— 以最小能力运行、可按进程树回收、输出可审计。
 * 让 npm 在 app 进程里自己起 `sh` 等于三条全丢。桥这一圈买的就是这三条。
 *
 * ## 为什么住 `:app-service:npm` 而不是 `:bridge:java`
 *
 * `:bridge:java` 是**脚本 ↔ 宿主**那条桥（`BridgeRouter`/`RequestRegistry`/TSF 那一套，
 * §7），服务的是 `auto.*` 命名空间调用；本类是**npm 会话 ↔ 宿主**，服务的是
 * "把一条 spawn 变成一次宿主进程"。两者只是**长得像**（都是 unix socket + newline 帧 +
 * hello/token），机制与生命周期都不共用：这条桥随安装会话生灭，那条随 run 生灭。
 * 更硬的理由是依赖方向 —— 本模块的 `ArchitectureTest` 禁 `com.autoscript.bridge..`，
 * 而把 npm 的东西塞进 `:bridge:java` 会让"桥模块知道 npm 的 T1"成为事实。
 *
 * **因此握手帧的形状与 [com.autoscript.bridge.BridgeHandshake] 刻意逐字同形**
 * （`{"t":"hello","v":1,"token":"<64hex>"}` / `helloAck` / `helloErr`）：两份实现各自独立，
 * 但读的人一眼认得出这是同一族协议，将来若要合并也不必改线上格式。
 *
 * ## 边界（如实写死）
 *
 * - **本桥不是安全边界**：shim 在脚本进程里，已获批的脚本能 `delete require.cache` 绕过它
 *   直接 require 未打补丁的 `child_process` —— 而设备上那个模块本来就不工作，绕过去只是
 *   回到"起不来"。真判据是审批（§10.5-2）与「宿主只跑它认得的那条命令」。
 * - **不隔离**：本版起的进程与 App 同 UID（没有可用的隔离手段，见 §10.3 T1 的
 *   "最小 CapabilityMask"讨论）。**这一条是 T1 的已知欠账**，登记在
 *   `design-decisions.md` 与本批的流水里，不在这里假装。
 * - **stdin 转发但不做背压**；**同步三入口不支持**（要阻塞事件循环等一次往返）。
 *
 * ## TTL / 回收
 *
 * 每次 `spawn` 的进程挂在会话上，会话随**安装会话进程退出**（socket EOF）关断 ——
 * 关断时按 TERM → 宽限 → SIGKILL 收全部后代（`ProcessHandle.descendants()`）。
 * 另有单条命令的墙钟上限（[ScriptOp.timeoutMillis]，由协调器 withTimeoutOrNull 套），
 * 到点即杀，不让一条挂死的脚本占着项目锁。
 */
object NpmT1Bridge {

    /** 会话侧 shim 的落盘文件名（`filesDir/.autojs/` 下，与门禁 shim 同处）。 */
    const val SHIM_FILE_NAME: String = "npm-t1-bridge.cjs"

    /** classpath 资源路径（`src/main/resources/` 下，与包名同构）。 */
    const val SHIM_RESOURCE_PATH: String = "/com/autoscript/appservice/npm/npm-t1-bridge.cjs"

    /** shim 打在 stderr 上的可识别前缀 —— 与 `npm-t1-bridge.cjs` 的 `MARKER` 逐字同值。 */
    const val MARKER: String = "[npm-t1-bridge]"

    /** 注入给会话进程的两个环境变量（shim 那侧逐字同名）。 */
    const val ENV_SOCKET: String = "AUTOSCRIPT_T1_SOCKET"
    const val ENV_TOKEN: String = "AUTOSCRIPT_T1_TOKEN"

    /** 握手版本（与 shim 的 `VERSION` 同值）。 */
    const val VERSION: Int = 1

    /** 单帧上限：超过即断（防对端灌一条无限长的行把宿主内存吃掉）。 */
    const val MAX_FRAME_BYTES: Int = 1 shl 20

    /** hello 必须在这么久内到达，否则断连（对齐 `BridgeHandshake.TIMEOUT_MILLIS`）。 */
    const val HANDSHAKE_TIMEOUT_MILLIS: Long = 10_000L

    /** socket 抽象名（Android abstract namespace 与桌面都只用名字，不带路径）。 */
    fun socketName(): String = "com.autoscript.t1." + randomHex(8)

    /** 新凭据（32 字节 → 64 位十六进制）。 */
    fun newToken(): String = randomHex(32)

    private fun randomHex(bytes: Int): String {
        val buf = ByteArray(bytes)
        SecureRandom().nextBytes(buf)
        return buf.joinToString("") { "%02x".format(it) }
    }

    /**
     * 把 shim 落到 `filesDir/.autojs/npm-t1-bridge.cjs`。
     *
     * 与门禁 shim 共用 [NpmSpawnGate.deployResource]（原子写、字节一致不动盘、空文件拒收）——
     * 那三条对两份 shim 的要求逐字相同，各写一遍迟早只改一份。
     */
    fun deployShim(filesDir: Path): NpmSpawnGate.Deploy =
        NpmSpawnGate.deployResource(SHIM_RESOURCE_PATH, shimFile(filesDir), "T1 桥 shim")

    /** shim 落点（宿主内务目录，与 journal / lock.sig / 门禁 shim 同处）。 */
    fun shimFile(filesDir: Path): Path = filesDir.resolve(".autojs").resolve(SHIM_FILE_NAME)

    /**
     * 把 T1 桥的两个 env 写进会话进程的环境。
     *
     * **这两个键与 `mergeNodeOptions` 那条"追加不覆盖"的纪律不同，是直接写的** ——
     * 因为它们不可能是"别人的设置"：凭据是本次执行现签的（[newToken]），socket 名是
     * 本次执行现绑的（[socketName]）。沿用父环境里同名的值等于把上一次会话的凭据带进来，
     * 那条连接早就关了。
     *
     * [connectTarget] 是**对端该连的串**（见 [BoundSocket.connectTarget]），不是 socket 名：
     * 桌面是路径、Android 是 abstract 名，只有绑定方知道。
     */
    fun applyEnv(env: MutableMap<String, String>, connectTarget: String, token: String) {
        env[ENV_SOCKET] = connectTarget
        env[ENV_TOKEN] = token
    }

    /**
     * socket 绑定缝：回 null = 绑定失败（名字被抢/平台不支持）→ 调用方**不注入桥**
     * （`scriptExecutor` 保持 `Unavailable`，如实 `ERR_NOT_IMPLEMENTED`），不冒泡炸装配。
     *
     * 与 `:app` 的 `BridgeSocketBinder` 同一个形状与同一条"失败即降级"口径 ——
     * 这里不 import 它（依赖方向），但语义刻意一致。
     *
     * **这条缝是"平台"而不是"可选项"**：设备侧必须由 `:app` 注入
     * `android.net.LocalServerSocket` 那条实现，[fileSystemBinder] 在 Android 上必炸
     * （见那边的 KDoc）。故装配层**没有**"忘了传就用缺省"这个安全缺省 ——
     * `AppShellKit.assemble` 把它列成必填参数，编译器就是那道门。
     */
    fun interface SocketBinder {
        fun bind(socketName: String): BoundSocket?
    }

    /** 已绑定的监听面（[close] 解除 [accept] 阻塞）。 */
    interface BoundSocket : AutoCloseable {
        /**
         * **对端该连的那个串**（经 [ENV_SOCKET] 交给 shim，`net.connect({path: …})` 直接用）。
         *
         * 为什么不让调用方自己拼：绑定的**实现**才知道它绑的是什么 —— 桌面是
         * `$TMPDIR/autoscript-t1-<name>.sock` 这样一条**文件系统路径**，Android 是
         * `LocalServerSocket(<name>)` 那样一个 **abstract 名**（不是路径）。让调用方按
         * "名字"拼一次，桌面侧就会拼出一条不存在的相对路径 —— 实测症状是
         * `connect ENOENT com.autoscript.t1.xxxx`，而报错里那个串看起来完全正常。
         */
        val connectTarget: String

        /** 阻塞取下一条连接；本端已 close → null。 */
        fun accept(): AcceptedT1Connection?
    }

    /**
     * 一条已接入的连接（**流对**而不是 `SocketChannel`）。
     *
     * 为什么不是 `SocketChannel`：设备侧的 `android.net.LocalSocket` **不是** `SocketChannel`
     * （android.net 与 java.nio 是两套 API，`minSdk 26` 也没有 unix 的 NIO 通道）。
     * 桥真正需要的只是两条流，故抽象就停在流这一层 —— 桌面实现用
     * `Channels.newInputStream(channel)` 包一下即可，设备实现直接用 `LocalSocket` 的流。
     * 若抽象成 `SocketChannel`，设备侧就只能靠一层假 channel 去凑，那是为抽象服务而不是为功能。
     */
    interface AcceptedT1Connection : AutoCloseable {
        val input: InputStream
        val output: OutputStream
    }

    /**
     * 缺省绑定：JDK 的 unix domain socket —— **桌面专用，Android 上永不可达**。
     *
     * **只支持文件系统路径**（JDK 17 的 `UnixDomainSocketAddress.of(Path)` 拒 NUL 前缀，
     * 故 abstract namespace 用它绑不上）。设备上要 abstract 得走 `android.net.LocalServerSocket`
     * —— 那条路由 `:app` 的装配层注入（本模块看不到 `android.*`），与本类无关。
     *
     * ## 为什么"Android 上永不可达"要写成硬约束而不是愿望（2026-10-10 真机实测）
     *
     * `java.net.UnixDomainSocketAddress` **在 android.jar 里根本不存在**（实查：整个
     * `java/net/` 下没有这个类；`StandardProtocolFamily.UNIX` 与
     * `ServerSocketChannel.open(ProtocolFamily)` 倒是有 —— 这正是"看起来能编过"的原因，
     * 编译期只校验后者）。故本函数在设备上**不是"降级"，是必炸**，而且是
     * `NoClassDefFoundError`（Error，不是 Exception）—— 任何 `catch (e: Exception)`
     * 都拦不住它，它会一路掀翻整个壳的装配。
     *
     * 判据因此是**装配期注入**：`:app` 的生产装配必须传 `AndroidT1SocketBinder`
     * （见 [SocketBinder] 这条缝的 KDoc）。本函数只该出现在桌面单测与 JVM 装配里。
     */
    fun fileSystemBinder(): SocketBinder = SocketBinder { name ->
        // `Paths.get` 而不是 `Path.of`：后者在 Android 上 since=34（`api-versions.xml` 实查），
        // 本模块是**纯 JVM 模块、没有 lint**，故这条纪律在这里没有门禁兜着 —— 只能靠这行注释
        // 与真机验证。2026-10-10 真机实测（API 33）：`Path.of` 抛 NoSuchMethodError，
        // 且它把**整个壳的装配**掀翻了（见 [fileSystemBinder] 的 KDoc）。
        val path = Paths.get(System.getProperty("java.io.tmpdir")).resolve("autoscript-t1-$name.sock")
        try {
            Files.deleteIfExists(path)
            val ch = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
            ch.bind(UnixDomainSocketAddress.of(path))
            object : BoundSocket {
                // 桌面侧对端连的就是这条路径本身（不是那个短名字）—— 见 connectTarget 的 KDoc。
                override val connectTarget: String = path.toAbsolutePath().toString()

                override fun accept(): AcceptedT1Connection? = try {
                    val c = ch.accept() ?: return null
                    object : AcceptedT1Connection {
                        override val input: InputStream = ChannelInput(c)
                        override val output: OutputStream = ChannelOutput(c)
                        override fun close() {
                            runCatching { c.close() }
                        }
                    }
                } catch (_: java.nio.channels.ClosedChannelException) {
                    null
                } catch (_: java.nio.channels.AsynchronousCloseException) {
                    null
                }

                override fun close() {
                    runCatching { ch.close() }
                    runCatching { Files.deleteIfExists(path) }
                }
            }
        } catch (_: IOException) {
            // 绑不上（tmpdir 不可写/名字非法）：回 null，装配层据此保持 Unavailable
            // （与 `:app` 的 `BridgeSocketBinder` 同一条"失败即降级"口径）。
            null
        }
    }
}

/**
 * 一条已接入的 T1 会话（= 一次 `npm run` / `npm exec` 的脚本进程）。
 *
 * 帧协议（newline 分隔的 JSON，字段名与 shim 逐字对应）：
 * - 会话 → 宿主：`{"t":"hello","v":1,"token":"<64hex>"}`、
 *   `{"t":"spawn","id":N,"cmd":..,"args":[..],"opts":{..}}`、`{"t":"in","id":N,"data":"<b64>"}`
 * - 宿主 → 会话：`{"t":"helloAck","v":1}` / `{"t":"helloErr","code":..,"detail":..}`、
 *   `{"t":"out","id":N,"fd":1|2,"data":"<b64>"}`、`{"t":"exit","id":N,"code":N,"signal":..}`、
 *   `{"t":"err","id":N,"code":..,"detail":..}`
 *
 * 读帧在**一条**线程上串行（[run]），故子进程表不需要额外加锁 —— 唯一的并发写者是
 * 各子进程的输出泵，它们只往 [out] 写（`OutputStream` 的写本身加锁）。
 */
class T1Session(
    private val connection: NpmT1Bridge.AcceptedT1Connection,
    private val token: String,
    private val runner: T1ProcessRunner,
) : AutoCloseable {

    private val input: InputStream = BufferedInputStream(connection.input)
    private val out: OutputStream = connection.output
    private val closed = AtomicBoolean(false)

    /** 本会话起的子进程（id → 句柄），关断时逐个收。 */
    private val children = ConcurrentHashMap<Long, T1Child>()

    /** 握手完成位（成不成都是"完成"）：[awaitHandshake] 的看门狗据此判超时。 */
    private val handshakeDone = CountDownLatch(1)

    /** 跑到底（阻塞本线程）：握手 → 逐帧处理 → EOF/关断时收掉全部子进程。 */
    fun run() {
        try {
            if (!handshake()) return
            readFrames()
        } catch (_: IOException) {
            // 对端断开/宿主关断：正常收尾路径，不当异常上报（真病因在子进程那侧）。
        } finally {
            handshakeDone.countDown()
            close()
        }
    }

    /**
     * 等握手完成，最多 [millis]；false = 到点还没完成（调用方**关掉连接**去唤醒那条
     * 阻塞在 [readLine] 上的线程）。
     *
     * 为什么需要这条看门狗：`accept` 接到的可能是**任何**连上来的东西 —— 一个只连不发
     * （或发半截）的对端会让读线程**永久**卡在 `read` 上，而这条会话占着 socket、
     * 占着一次执行。`T1Session` 自己没法设读超时（[AcceptedT1Connection] 抽象在
     * `InputStream` 这一层，而设备侧的 `LocalSocket` 与桌面的 `SocketChannel` 设超时的
     * 手法各不相同），所以由持有连接的那一方关连接来解除阻塞 —— 关连接是两种实现都认的
     * 唯一一种"叫醒它"。
     */
    fun awaitHandshake(millis: Long): Boolean = handshakeDone.await(millis, TimeUnit.MILLISECONDS)

    /**
     * 握手：读一行，验 `t/v/token` 三件。
     *
     * 凭据比对走 [MessageDigest.isEqual]（定长比较）：本桥的威胁模型里对端是**自己起的**
     * npm 会话，时间侧信道不在威胁面内 —— 但用定长比较的成本是零，而没有理由在
     * "反正不要紧"的地方写一个需要论证的短比较。
     */
    private fun handshake(): Boolean {
        val line = readLine() ?: return false
        val fields = try {
            DomainJson.decodeObject(line)
        } catch (_: IllegalArgumentException) {
            writeFrame(mapOf("t" to "helloErr", "v" to NpmT1Bridge.VERSION, "code" to "ERR_INVALID_PARAM", "detail" to "握手帧不是 JSON 对象"))
            return false
        }
        val t = (fields["t"] as? DomainJson.Value.S)?.v
        val v = (fields["v"] as? DomainJson.Value.N)?.raw
        val got = (fields["token"] as? DomainJson.Value.S)?.v
        if (t != "hello" || v != NpmT1Bridge.VERSION.toString()) {
            writeFrame(mapOf("t" to "helloErr", "v" to NpmT1Bridge.VERSION, "code" to "ERR_INVALID_PARAM", "detail" to "握手协议不符（t=$t v=$v）"))
            return false
        }
        if (got == null || !MessageDigest.isEqual(got.toByteArray(), token.toByteArray())) {
            writeFrame(mapOf("t" to "helloErr", "v" to NpmT1Bridge.VERSION, "code" to "ERR_PERMISSION_DENIED", "detail" to "T1 桥凭据不符"))
            return false
        }
        writeFrame(mapOf("t" to "helloAck", "v" to NpmT1Bridge.VERSION))
        return true
    }

    /**
     * 逐帧读到 EOF / 关断。
     *
     * 循环体里**没有 `continue`**（拆去 [dispatch]）：一条坏帧要跳过的只是"这一帧"，
     * 而读循环要跳过的东西多了以后（坏帧/空行/关断）就分不清哪条 `continue` 跳的是哪一层。
     */
    private fun readFrames() {
        var line = readLine()
        while (line != null && !closed.get()) {
            if (line.isNotBlank()) dispatch(line)
            line = readLine()
        }
    }

    /** 派发一帧；坏帧/未知类型一律静默跳过（一条坏行不该打死整条会话 —— 子进程还在跑）。 */
    private fun dispatch(line: String) {
        val fields = try {
            DomainJson.decodeObject(line)
        } catch (_: IllegalArgumentException) {
            return
        }
        when ((fields["t"] as? DomainJson.Value.S)?.v) {
            "spawn" -> onSpawn(fields)
            "in" -> onStdin(fields)
            else -> Unit // 未知帧类型：忽略（版本偏斜时前向兼容）
        }
    }

    private fun onSpawn(fields: Map<String, DomainJson.Value>) {
        val id = (fields["id"] as? DomainJson.Value.N)?.raw?.toLongOrNull() ?: return
        val cmd = (fields["cmd"] as? DomainJson.Value.S)?.v ?: return
        val args = (fields["args"] as? DomainJson.Value.Arr)?.items
            ?.mapNotNull { (it as? DomainJson.Value.S)?.v } ?: emptyList()
        val opts = (fields["opts"] as? DomainJson.Value.Obj)?.fields ?: emptyMap()
        val child = T1Child(
            id = id,
            cmd = cmd,
            args = args,
            cwd = (opts["cwd"] as? DomainJson.Value.S)?.v,
            env = (opts["env"] as? DomainJson.Value.Obj)?.fields?.mapNotNull { (k, v) ->
                (v as? DomainJson.Value.S)?.let { k to it.v }
            }?.toMap(),
            detached = (opts["detached"] as? DomainJson.Value.B)?.v == true,
            shell = (opts["shell"] as? DomainJson.Value.B)?.v == true,
            runner = runner,
            emit = ::writeFrame,
        )
        children[id] = child
        // 每条 spawn 一条线程：脚本之间互相独立（一条挂死不该卡住另一条）。
        Thread({ child.run(); children.remove(id) }, "t1-child-$id").apply {
            isDaemon = true
            start()
        }
    }

    private fun onStdin(fields: Map<String, DomainJson.Value>) {
        val id = (fields["id"] as? DomainJson.Value.N)?.raw?.toLongOrNull() ?: return
        val data = (fields["data"] as? DomainJson.Value.S)?.v ?: return
        children[id]?.writeStdin(data)
    }

    /** 读一行（`\n` 界）；EOF 回 null；超 [NpmT1Bridge.MAX_FRAME_BYTES] 抛（由 [run] 收成关断）。 */
    private fun readLine(): String? {
        val buf = java.io.ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString(StandardCharsets.UTF_8)
            if (b == '\n'.code) return buf.toString(StandardCharsets.UTF_8)
            buf.write(b)
            if (buf.size() > NpmT1Bridge.MAX_FRAME_BYTES) throw IOException("T1 帧超限")
        }
    }

    /** 写一帧（`synchronized`：子进程输出泵与握手/主读线程都可能写）。 */
    private fun writeFrame(fields: Map<String, Any?>): Boolean = synchronized(out) {
        if (closed.get()) return false
        try {
            out.write((DomainJson.encode(fields) + "\n").toByteArray(StandardCharsets.UTF_8))
            out.flush()
            true
        } catch (_: IOException) {
            // 对端没了：静默（子进程的收尾走 close()，不靠这条写成功与否）
            false
        }
    }

    /** 关断：收全部子进程（TERM → 宽限 → KILL，含后代），再关连接。幂等。 */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        children.values.forEach { it.reap() }
        children.clear()
        runCatching { connection.close() }
    }
}

/**
 * 一条**已绑定但还没接客**的 T1 桥会话（[NpmScriptExecutor] 拿到的就是它）。
 *
 * 为什么是"先 bind、后 start"两段而不是"open 就接客"：凭据与 socket 名必须在
 * **起 npm 会话之前**就定下来（那两个值要经 env 交给 shim），而接客线程要等 npm 会话
 * 起来才有意义。合成一步的话，`open()` 就得阻塞等一个可能永远不来的连接。
 */
interface T1SessionHandle {
    /** 对端该连的串（见 [NpmT1Bridge.BoundSocket.connectTarget]）。 */
    val connectTarget: String
    val token: String

    /** 开接客线程（幂等）。 */
    fun start()

    /** 关断（收子进程 + 关连接 + 释放 socket）。幂等。 */
    fun close()
}

/** 开一条 T1 桥会话的缝；回 null = 绑不上（装配层据此保持 `Unavailable`）。 */
fun interface T1SessionFactory {
    fun open(): T1SessionHandle?
}

/**
 * 生产会话工厂：绑 socket → 等**一条**连接 → 交给 [T1Session]。
 *
 * **只接一条**：一条 npm 会话（= 一次 `npm run`）只该有一条连接 —— shim 在会话进程里
 * 是单例，它只会连一次。多接的那条无从归属（哪个 npm 会话的？），故接完第一条就停。
 * 这不是省事：允许第二条会让"另一个进程连上来"变成合法，而那条连接没有任何身份可言。
 *
 * 接客线程是 daemon：npm 会话若因为别的原因没连上来（比如 CLI 起不来），
 * 这条线程不能把 JVM/测试吊住。
 */
class SocketT1Sessions(
    private val binder: NpmT1Bridge.SocketBinder,
    private val runner: T1ProcessRunner = ProcessBuilderT1Runner(),
    /**
     * 握手看门狗的期限（见 [T1Session.awaitHandshake]）。可注入是为了让"连上不发 hello
     * 会被收掉"这条能用一个几百毫秒的值验 —— 真期限 10s 的用例没人愿意等，
     * 而一条没人愿意等的用例迟早被关掉，那等于没有守卫。
     */
    private val handshakeTimeoutMillis: Long = NpmT1Bridge.HANDSHAKE_TIMEOUT_MILLIS,
) : T1SessionFactory {

    override fun open(): T1SessionHandle? {
        val name = NpmT1Bridge.socketName()
        val bound = binder.bind(name) ?: return null
        val token = NpmT1Bridge.newToken()
        return object : T1SessionHandle {
            override val connectTarget: String = bound.connectTarget
            override val token: String = token
            private val started = AtomicBoolean(false)
            @Volatile private var session: T1Session? = null

            override fun start() {
                if (!started.compareAndSet(false, true)) return
                Thread({
                    val conn = try {
                        bound.accept()
                    } catch (_: IOException) {
                        null
                    }
                    if (conn == null) return@Thread
                    val s = T1Session(conn, token, runner)
                    session = s
                    watchHandshake(conn, s)
                    s.run()
                }, "t1-accept").apply {
                    isDaemon = true
                    start()
                }
            }

            override fun close() {
                session?.close()
                bound.close()
            }
        }
    }

    /**
     * 握手看门狗：到点还没握完就**关连接**（那条阻塞在 `read` 上的线程随之醒来）。
     *
     * 见 [T1Session.awaitHandshake] 的 KDoc：这是本桥唯一能设的"读超时"。
     * 线程是 daemon：它只活到握手完成为止，正常路径上是一条几乎立刻退出的空转。
     */
    private fun watchHandshake(conn: NpmT1Bridge.AcceptedT1Connection, session: T1Session) {
        Thread({
            if (!session.awaitHandshake(handshakeTimeoutMillis)) {
                runCatching { conn.close() }
            }
        }, "t1-handshake-watchdog").apply {
            isDaemon = true
            start()
        }
    }
}

/**
 * `SocketChannel` → [InputStream]，**刻意不用 `Channels.newInputStream`**。
 *
 * ## 为什么不用 JDK 那个（2026-10-10 批 91 实测，这条是本桥最贵的一课）
 *
 * `Channels.newInputStream(ch)` 与 `Channels.newOutputStream(ch)` 会**共用同一个锁**
 * （`ch.blockingLock()`），而 `ChannelInputStream.read` 是**抱着那把锁阻塞**的。于是
 * 读线程一旦进入 `readLine()` 等下一帧，写线程（子进程输出泵）就永远拿不到锁 ——
 * 一条**双向**协议被自己的流包装锁成了单向：读在等写，写在等读。
 *
 * 实测症状：`T1BridgeNodeTest` 与 `T1BridgeE2ETest` 双双挂死，探针进程与 JVM 都停在
 * 各自的 `read` 上，`jstack` 里 `t1-child-1` 显示 `BLOCKED (on object monitor)` 而
 * `t1-accept` 显示 `RUNNABLE` 卡在 `SocketDispatcher.read0`。**没有一条报错指向锁** ——
 * 报错面只有「超时」。
 *
 * `SocketChannel` 自己**本来就有分开的读写锁**（`readLock` / `writeLock`，全双工），
 * 故直接用 channel 读写、不共用任何锁，才是 JDK 那份包装想当然但没做到的事。
 */
private class ChannelInput(private val ch: SocketChannel) : InputStream() {

    private val one = ByteBuffer.allocate(1)

    override fun read(): Int {
        one.clear()
        val n = ch.read(one)
        return if (n < 0) -1 else one.get(0).toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        return ch.read(ByteBuffer.wrap(b, off, len))
    }

    override fun available(): Int = 0

    override fun close() {
        ch.close()
    }
}

/** `SocketChannel` → [OutputStream]（与 [ChannelInput] 成对，理由见那边）。 */
private class ChannelOutput(private val ch: SocketChannel) : OutputStream() {

    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    override fun write(b: ByteArray, off: Int, len: Int) {
        val bb = ByteBuffer.wrap(b, off, len)
        while (bb.hasRemaining()) ch.write(bb)
    }

    override fun close() {
        ch.close()
    }
}
