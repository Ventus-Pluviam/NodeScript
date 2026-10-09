package com.autoscript.platform.capabilities.device

import android.os.ParcelFileDescriptor
import com.autoscript.domain.automation.InputChannel
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import java.io.InputStream
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Shizuku（adb 通道）的设备面：**唯一的 `android.*` + `rikka.shizuku.*` 接触点**
 * （§9.3 三通道里的 adb 那条）。
 *
 * **为什么 adb 通道非得有它**：应用自己 fork 的 `sh` 身份仍是应用 uid，`input` 注不进事件；
 * 要让注入以 **shell uid** 发生，必须借一个由 adb 启动的服务进程 —— Shizuku 就是那个
 * 服务（用户装 Shizuku、用 `adb shell sh /sdcard/…/start.sh` 起它、再授权本应用）。
 *
 * **为什么本文件不 import `rikka.shizuku.*`**：那是**冻结文件**（`gradle/libs.versions.toml`）
 * 里的新依赖，且该依赖只在**真机**上有意义（JVM 单测跑在 mock android.jar 上，碰
 * `Shizuku` 静态初始化即炸）。所以与 `:app` 既有做法一致：**反射调用**。
 * **但依赖不是 `compileOnly`**（初稿的注释曾这么写，是错的）：`ShizukuProvider` 是本应用
 * manifest 里声明的 ContentProvider，**运行时必须真在 APK 里**（类不在 = 授权握手不成立），
 * 所以是 `implementation`，两个坐标都进 APK。反射的失败面（类不在、方法签名变了）**全部
 * 折成 `ERR_PERMISSION_DENIED`**：「Shizuku 没装」与「Shizuku 版本不兼容」对用户是同一句话
 * —— 去装/去更新。
 *
 * **远端进程那一半同样是反射面，且必须从公开接口取**（2026-10-09 真机定位）：
 * `newProcess` 的**返回值**一度用了与 `newProcess` 本身同一个错法（从运行时类取方法），
 * 真机上表现为 adb 档 100% 不可用 —— 机理与判据见 [RemoteProcessApi] 的 KDoc，
 * 读那一段的代码在 [ShizukuProcessReader]。同一台机器上 ROOT 档同一条命令正常，
 * 故问题被限定在本文件这一条通道上。
 *
 * 本文件住三个顶层类：**建通道**（本对象）与**读进程**（[ShizukuProcessReader]）分开 ——
 * 两者失败话术不同（「去装/去启动 Shizuku」vs「进程调用失败 + 真病因」），
 * 且 detekt 对 `object` 的函数数上限（11）比 `internal object`（25）严，挤在一起会当场红。
 */
object ShizukuInput {

    /** Shizuku 主类的全名（`dev.rikka.shizuku` 的公开入口）。 */
    private const val SHIZUKU_CLASS = "rikka.shizuku.Shizuku"

    /**
     * 服务接口的全名（`newProcess` 从**它**上面取，见 [newProcess] 里那段注释）。
     *
     * 这一条与 [SHIZUKU_CLASS] 一样是"按字符串找类"，**release 包上必须留名**
     * （R8 看不见）—— 见 `app/proguard-rules.pro` 第 1b 条。
     */
    private const val IShizuku_SERVICE = "moe.shizuku.server.IShizukuService"

    /** 单条注入命令的超时（与 `ShellInputProvider.DEFAULT_TIMEOUT_MILLIS` 同量级）。 */
    const val DEFAULT_TIMEOUT_MILLIS = 5_000L

    /**
     * 跑一条命令，返回 `(exitCode, stderr)`。
     *
     * 失败一律抛 [AutojsException] `ERR_PERMISSION_DENIED`（`detail` 说清是哪一步）——
     * **不返回一个假的退出码**：调用方（`ShellInputProvider`）要靠退出码判「系统拒绝
     * 这次注入」，拿假码混进去会让「Shizuku 挂了」看起来像「这次点击被拒」。
     */
    fun run(command: String, timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS): Pair<Int, String?> {
        val process = newProcess(command)
        return try {
            ShizukuProcessReader.drain(process, timeoutMillis)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw denied("Shizuku 命令被中断", e)
        }
    }

    /**
     * 跑一条命令并**同时**取回两条流（2026-10-09 新增：控制台的 shell 面要用 stdout）。
     *
     * 与 [run] 的差别只有一个，但那个差别决定它能不能服务控制台：[run] 只为**输入注入**
     * 服务（`input tap` 没有 stdout），故只留 stderr 尾部用于报错；控制台敲
     * `ls -la` 要的**正是 stdout**，用 [run] 会拿到一条永远为空的输出。
     *
     * 失败语义与 [run] 一致：一律抛 [AutojsException] `ERR_PERMISSION_DENIED` 并把
     * 「是哪一步不行」写在 detail 里（不返回假退出码 —— 那会让「Shizuku 挂了」
     * 看起来像「这条命令失败了」）。
     */
    fun exec(command: String, timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS): ShizukuExecResult {
        val process = newProcess(command)
        return try {
            ShizukuProcessReader.drainBoth(process, timeoutMillis)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw denied("Shizuku 命令被中断", e)
        }
    }

    /**
     * 起一个远端 shell 进程（[run] 与 [exec] 的唯一差别在拿到进程**之后**怎么读）。
     *
     * **方法必须从公开接口取，不能从 `service.javaClass` 取**：`asInterface` 返回的是
     * AIDL 生成的 `IShizukuService$Stub$Proxy`，那个类是**包内可见**的（`javap` 可见
     * `class moe.shizuku.server.IShizukuService$Stub$Proxy`，无 `public`）。在它上面
     * `getMethod` 拿到的 `Method` 带着"声明类不可见"的访问检查，`invoke` 抛
     * `IllegalAccessException`（不是 `NoSuchMethodException`，故不会被当成"版本不兼容"，
     * 而是被 [step] 折成同一句拒绝 —— 症状是 adb 通道**永远不可用**，且没有任何提示
     * 指向真因）。在**公开接口**上取同一个方法签名则声明类可见，invoke 正常分派到实现类。
     *
     * **同一个坑在返回值那一侧也有一份**（2026-10-09 真机定位）：`newProcess` 返回的
     * `IRemoteProcess` 同样是包内可见的 proxy，读它的方法也一律从公开接口取 —— 见
     * [RemoteProcessApi]。
     */
    private fun newProcess(command: String): Any {
        val shizuku = shizukuClass()
        val binder = step("Shizuku 服务未运行（binder 为 null）—— 请先启动 Shizuku 并授权本应用") {
            shizuku.getMethod("getBinder").invoke(null)
        }
        val service = step("Shizuku 服务接口不可用（IShizukuService 形状不符）") {
            val stub = Class.forName("moe.shizuku.server.IShizukuService\$Stub")
            stub.getMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder)
        }
        return step("Shizuku newProcess 调用失败（服务版本不兼容？）") {
            val api = Class.forName(IShizuku_SERVICE)
            api.getMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
                .invoke(service, arrayOf("sh", "-c", command), arrayOf<String>(), null)
        }
    }

    /**
     * 反射调用的一步：null 结果与反射异常都折成同一个「这一步不行」。
     *
     * 抽出来是为了让 [newProcess] 读起来是一条直线（每一步一行），也把「哪一步失败了」的
     * 措辞收在一处 —— 用户看到的是一句话，不该是一串 `InvocationTargetException`。
     *
     * `InvocationTargetException` 要**剥开**（2026-10-09，外审第 5 条）：它的 `message`
     * 是 null，直接折进去只剩一句「反射失败」，而真病因（远端 `RemoteException`、
     * `IllegalArgumentException`…）全在 `targetException` 里 —— 那正是排查时唯一有用的
     * 那半个字。
     */
    private inline fun <T : Any> step(failure: String, call: () -> T?): T = try {
        call() ?: throw denied(failure, null)
    } catch (e: ReflectiveOperationException) {
        val hint = (e as? InvocationTargetException)?.targetException?.let { "：$it" } ?: ""
        throw denied("$failure（反射失败：${e.javaClass.simpleName}$hint）", e)
    }

    /**
     * 通道可用性探测（装配期用）：**装没装 + 服务活没活**。
     *
     * 两问都要：只问「类在不在」会把「装了但服务没启动」报成可用，用户拿到的错误
     * 就从「去启动 Shizuku」退化成「注入失败」（少了那一步该去哪做）。
     * 探测本身**不抛** —— 它是个问句，答案就是 true/false。
     */
    fun isAvailable(): Boolean {
        // 反射探测的失败面很宽（类不在 / 方法改名 / 静态初始化炸 / 权限）—— 一律"不可用"。
        // 这里是**唯一**允许 `catch (Throwable)` 的地方：它是问句不是执行路径，
        // 且失败的下游语义是确定的（通道不接线 → 调用方拿 ERR_PERMISSION_DENIED）。
        //
        // 两问都要：binder 拿得到（服务在）+ pingBinder 回 true（服务活着）。只问前者会把
        // 「装了但服务没启动」报成可用，用户拿到的错误就从「去启动 Shizuku」退化成「注入失败」。
        return try {
            val shizuku = shizukuClass()
            shizuku.getMethod("getBinder").invoke(null) != null &&
                (shizuku.getMethod("pingBinder").invoke(null) as? Boolean ?: false)
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 取 Shizuku 主类。
     *
     * 接两种失败，**都是同一句给用户的话**（去装 / 去更新 Shizuku）：
     * - `ClassNotFoundException`：类不在（没装，或 R8 把名字改了 —— 见 `proguard-rules.pro`）；
     * - `LinkageError`（`NoClassDefFoundError` / `ExceptionInInitializerError`）：类在但
     *   **初始化失败**（静态初始化依赖的 `android.*` 在 JVM 单测里是 mock、真机上也可能
     *   因版本不符炸）。2026-10-09 实测：JVM 单测跑 ADB 档时抛的正是
     *   `NoClassDefFoundError: Could not initialize class rikka.shizuku.Shizuku`，
     *   而它**不是** Exception —— 原来只接 `ClassNotFoundException` 会让这条路径
     *   越过 `AutojsException` 直穿到调用方，用户拿到的是一个栈而不是一句「去装 Shizuku」。
     *
     * 刻意**不写 `catch (Throwable)`**：那会把 `OutOfMemoryError` 这类也折成
     * 「Shizuku 没装」，那是把真故障说成用户可修的问题。
     */
    private fun shizukuClass(): Class<*> = try {
        Class.forName(SHIZUKU_CLASS)
    } catch (_: ClassNotFoundException) {
        throw denied("Shizuku 未安装（找不到 $SHIZUKU_CLASS）—— adb 输入通道需要 Shizuku", null)
    } catch (e: LinkageError) {
        throw denied("Shizuku 类无法初始化（$SHIZUKU_CLASS：${e.message}）—— 请更新或重装 Shizuku", e)
    }

    /** 本通道的失败话术（[ShizukuProcessReader] 也用它，两处的措辞只此一份）。 */
    internal fun denied(detail: String, cause: Throwable?) = AutojsException(
        ErrorCode.ERR_PERMISSION_DENIED,
        "adb 输入通道不可用：$detail",
        cause,
    )

    /** 本通道的枚举值（装配层用它登记 `channels` 表）。 */
    val channel: InputChannel get() = InputChannel.ADB
}

/**
 * 远端进程的**读取**那一半（[ShizukuInput] 拿到进程之后的一切）。
 *
 * 单独一个 `internal object` 而不是塞进 [ShizukuInput]：两者失败话术不同（那边是
 * 「去装/去启动 Shizuku」，这边是「进程调用失败 + 真病因」），且 detekt 对 `object`
 * 的函数数上限（11）比 `internal object`（25）严。
 *
 * 全部方法一律经 [RemoteProcessApi] 取 —— **绝不 `process.javaClass.getMethod`**，
 * 理由见 [RemoteProcessApi] 的 KDoc（那是真机上 adb 档全挂的成因）。
 */
internal object ShizukuProcessReader {

    /** 生产缺省的流打开口（见 [ProcessStreamOpener]）。 */
    private val DEFAULT_STREAM_OPENER =
        ProcessStreamOpener { ParcelFileDescriptor.AutoCloseInputStream(it) }

    /**
     * 等远端进程结束并取退出码，**stderr 只留尾部**用于报错。
     *
     * **stderr 必须排空**：Shizuku 的远端进程同样是管道，不读满会反压住对端
     * （与 `:engine:node-process` 的排水线程同一条账）。这里只留尾部用于报错 ——
     * 我们要的是「为什么失败」，不是完整输出。
     */
    fun drain(
        process: Any,
        timeoutMillis: Long,
        streams: ProcessStreamOpener = DEFAULT_STREAM_OPENER,
    ): Pair<Int, String?> {
        val tail = pump(process, RemoteProcessApi.getErrorStream, "shizuku-stderr", streams)
        return try {
            waitOrKill(process, timeoutMillis)
            tail.join()
            exitCode(process) to tail.text()
        } finally {
            // 幂等收尸：正常路径读线程已 done，超时/异常路径在这里把流关掉
            // （不关就是每次 exec 漏一个 fd，真机上只表现为"越跑越卡"）。
            tail.done()
        }
    }

    /**
     * 与 [drain] 同形，但**两条流都排空并各自保留**（`exec` 用）。
     *
     * 两条流必须**并发**读（与 `AndroidShellExecutor` 第 3 条同一条账）：管道缓冲区写满即
     * 阻塞子进程，先读干 stdout 再读 stderr 会在输出超过一屏时死锁。故两个守护线程。
     * 上限同 [CAPTURE_LIMIT_CHARS] —— 控制台要的是「命令说了什么」，不是全量转储
     * （真流式/分页是另一条面，本批不做）。
     */
    fun drainBoth(
        process: Any,
        timeoutMillis: Long,
        streams: ProcessStreamOpener = DEFAULT_STREAM_OPENER,
    ): ShizukuExecResult {
        val out = pump(process, RemoteProcessApi.getInputStream, "shizuku-stdout", streams)
        val err = try {
            pump(process, RemoteProcessApi.getErrorStream, "shizuku-stderr", streams)
        } catch (e: AutojsException) {
            // 第二条流打不开时把第一条收掉：这里漏一个 fd，真机上就是累积泄漏。
            out.done()
            throw e
        }
        return try {
            waitOrKill(process, timeoutMillis)
            out.join()
            err.join()
            ShizukuExecResult(exitCode(process), out.text(), err.text(), out.truncated || err.truncated)
        } finally {
            out.done()
            err.done()
        }
    }

    /**
     * 等进程结束；超时即**尝试终止**并如实抛（[ErrorCode.ERR_PERMISSION_DENIED]，
     * 与其余失败同一个码 —— 调用方只关心「这条通道这次没跑成」）。
     *
     * 超时是**实现侧义务**：上层（`ConsoleShellRunner`）另有一层 `withTimeoutOrNull`，
     * 两层并存不冲突（先到的那个说了算）。
     */
    private fun waitOrKill(process: Any, timeoutMillis: Long) {
        val finished = call(RemoteProcessApi.waitForTimeout, process, timeoutMillis, MILLIS_UNIT) as Boolean
        if (finished) return
        runCatching { RemoteProcessApi.destroy.invoke(process) }
        throw ShizukuInput.denied("Shizuku 命令超时 ${timeoutMillis}ms（已尝试终止）", null)
    }

    private fun exitCode(process: Any): Int = call(RemoteProcessApi.exitValue, process) as Int

    /**
     * 调一条 [RemoteProcessApi] 上的方法，并把包装异常剥开成一句可读的拒绝。
     *
     * `InvocationTargetException` 剥开：它的 `message` 是 null，真病因全在
     * `targetException` 里（远端 `RemoteException` 等）—— 那是排查时唯一有用的那半个字。
     */
    private fun call(method: Method, process: Any, vararg args: Any?): Any? = try {
        method.invoke(process, *args)
    } catch (e: InvocationTargetException) {
        // cause 挂**包装异常本身**（不是 `e.targetException`）：`InvocationTargetException`
        // 的 cause 就是 target，链上一样拿得到真病因，而 detekt 的 SwallowedException
        // 判的是「抛出去的异常有没有带上被捕获的那个」—— 换挂 target 会被判成吞异常。
        throw ShizukuInput.denied("Shizuku 进程调用失败（${method.name}）：${e.targetException}", e)
    }

    /** 起一条守护线程把 [getter] 那条流读到 EOF（截断到 [CAPTURE_LIMIT_CHARS]）。 */
    private fun pump(process: Any, getter: Method, name: String, streams: ProcessStreamOpener): StreamTail {
        val stream = streamOf(call(getter, process), getter.name, streams)
        val tail = StreamTail(stream, CAPTURE_LIMIT_CHARS)
        if (stream == null) {
            // 没有这条流（AIDL 契约里不该发生，但拿到了就如实当"没产出"）：
            // 闩当场打开，join() 不会白等 JOIN_MILLIS。
            tail.done()
            return tail
        }
        Thread({ drainInto(stream, tail) }, name).apply { isDaemon = true; start() }
        return tail
    }

    /**
     * 取一条流。**形状不符就炸，绝不静默当成「没有流」**（2026-10-09，外审第 3 条）。
     *
     * 2026-10-09 之前的写法是 `… as? InputStream`，而 AIDL 的 `getInputStream`/
     * `getErrorStream` 返回的是 `android.os.ParcelFileDescriptor` —— `as?` 于是**静默**
     * 得 `null`，两条流永远读不到一个字节。那种「安静的空」正是这个 bug 能藏住的原因：
     * 症状是「输出栏什么都没有」，而不是任何一条报错。所以这里只放过**真为 null**
     * （契约上不该有，但拿到就认），形状不符一律抛。
     */
    fun streamOf(raw: Any?, streamName: String, streams: ProcessStreamOpener): InputStream? {
        if (raw == null) return null
        val pfd = raw as? ParcelFileDescriptor ?: throw ShizukuInput.denied(
            "Shizuku 进程流形状不符（$streamName 返回 ${raw.javaClass.name}，期望 ParcelFileDescriptor）",
            null,
        )
        return streams.open(pfd)
    }

    /**
     * 读到 EOF 并截断到上限。
     *
     * **到顶之后仍然继续读**：停下来不读的话子进程会阻塞在写满的管道上 —— 那正是
     * `AndroidShellExecutor` 第 3 条要防的死锁，截断反而把它请回来（同一条账）。
     */
    private fun drainInto(stream: InputStream, tail: StreamTail) {
        try {
            stream.bufferedReader().useLines { lines -> lines.forEach { tail.append(it) } }
        } catch (_: Exception) {
            // 排水线程的收尾异常不影响判定：退出码才是判据（同 §8.5 的排水纪律）
        } finally {
            tail.done()
        }
    }

    /**
     * 单条流的捕获上限（**字符数**，不是字节数 —— 缓冲是 `StringBuilder`）。
     *
     * 为什么是 4 KiB：这条通道原本只为**输入注入**服务（`input tap` 的 stderr 只有一行
     * 报错），4 KiB 是"够读完那句为什么"的余量；控制台复用了同一条排水路径，故 stdout
     * 也吃同一个上限。**这与 root 档不一致**（那条走 `AndroidShellExecutor`，上限是
     * `ShellCaptureLimit.MAX_CAPTURE_BYTES` = 1 MiB）：两个数分居两个模块，`platform/
     * capabilities` 看不到 `:platform:system` 的那份契约。统一它们要 `:domain` 出一份
     * 共享常量（跨模块契约变更，另批）—— 在那之前，adb 档的截断由
     * [ShizukuExecResult.truncated] 如实上报，控制台会打「输出超过上限，已截断」，
     * 不静默丢字节。
     */
    private const val CAPTURE_LIMIT_CHARS = 4 * 1024

    /** `waitForTimeout` 第二个参数要传的单位名（见 [RemoteProcessApi.MILLIS_UNIT]）。 */
    private const val MILLIS_UNIT = RemoteProcessApi.MILLIS_UNIT
}

/**
 * `ParcelFileDescriptor` → `InputStream` 的缝。
 *
 * 真机 = `ParcelFileDescriptor.AutoCloseInputStream`（与 `rikka.shizuku.ShizukuRemoteProcess`
 * 内部的做法逐字相同 —— 2026-10-09 反汇编核实：它也是
 * `new AutoCloseInputStream(remote.getInputStream())`，只是那个包装类本应用拿不到）。
 *
 * **为什么做成缝**：mockable `android.jar` 的 `ParcelFileDescriptor` **造不出可读的 fd**
 * （`open()` 是 "not mocked"，`new AutoCloseInputStream(pfd)` 因为 `getFileDescriptor()`
 * 返回 null 而当场 NPE）—— 不给这条缝，[ShizukuProcessReader.drainBoth] 这条**正是出过
 * bug 的**路径在 JVM 上就一行也测不了。单测注入一个忽略 pfd、直接给可读流的替身即可。
 */
internal fun interface ProcessStreamOpener {
    fun open(pfd: ParcelFileDescriptor): InputStream
}

/**
 * `IRemoteProcess` 的反射面：**每一个方法都从公开接口取**，绝不从 `process.javaClass` 取。
 *
 * 为什么（2026-10-09 真机定位，症状是 adb 档 100% 不可用）：`IShizukuService.newProcess`
 * 的运行时返回类型是 AIDL 生成的 `moe.shizuku.server.IRemoteProcess$Stub$Proxy` ——
 * 一个**包内可见**的类（`javap` 可见 `class moe.shizuku.server.IRemoteProcess$Stub$Proxy`，
 * 无 `public`），且它**自己 override 了接口的每一个方法**。在它上面 `getMethod` 有两个坑，
 * 本文件此前**两处都踩了**：
 *
 * 1. **按错的形状找签名**：`rikka.shizuku.ShizukuRemoteProcess`（那个 `public class … extends
 *    Process` 的包装类）有 `waitForTimeout(long, TimeUnit)`，而 AIDL 接口上是
 *    `waitForTimeout(long, String)` —— 本应用**永远拿不到**那个包装类（`Shizuku.newProcess`
 *    是 `private static`，我们直接调 AIDL 接口），于是真机上拿到的是
 *    `NoSuchMethodException: …$Stub$Proxy.waitForTimeout [long, class java.util.concurrent.TimeUnit]`，
 *    命令一条都跑不起来。
 * 2. **声明类不可见**：即便签名找对了，从包内可见类取到的 `Method` 带着访问检查，
 *    `invoke` 抛 `IllegalAccessException`（`5a03dc3` 已在 `newProcess` 上修过同一个坑，
 *    但那条推理没有推广到它的**返回值**上）。
 *
 * 从**公开接口**取则两条都不存在：签名以接口为准，声明类可见，`invoke` 正常分派到实现类。
 *
 * 类不在（R8 改了名 / 依赖没进包）时本对象的初始化失败，抛 `ExceptionInInitializerError`
 * （`LinkageError`）—— 那是**包内配置错误**，不是用户可修的问题，故不折成
 * 「去装 Shizuku」（与 [ShizukuInput.isAvailable] 的 `catch (Throwable)` 分工不同：
 * 那边是问句，这边是执行路径）。
 */
private object RemoteProcessApi {

    /**
     * 远端进程接口的全名。与 `IShizuku_SERVICE` 同理：按字符串找类，**release 包上必须留名**
     * （`app/proguard-rules.pro` 第 1b 条）。
     */
    const val I_REMOTE_PROCESS = "moe.shizuku.server.IRemoteProcess"

    /**
     * `waitForTimeout` 第二个参数要传的单位名。
     *
     * AIDL 上那个参数是 `String`（不是 `TimeUnit`），服务端做的是 `TimeUnit.valueOf(unit)`
     * （2026-10-09 从设备上 Shizuku 13.6.0 的 `classes.dex` 反汇编核实：`invoke-static {v0},
     * Ljava/util/concurrent/TimeUnit;.valueOf`），所以必须是**枚举常量名本身**，
     * `"5s"` 之类会抛 `IllegalArgumentException`。`rikka.shizuku.ShizukuRemoteProcess`
     * （本应用拿不到的那个包装类）传的也是 `TimeUnit.toString()`，两者同值。
     */
    const val MILLIS_UNIT = "MILLISECONDS"

    val getInputStream: Method
    val getErrorStream: Method
    val waitForTimeout: Method
    val exitValue: Method
    val destroy: Method

    init {
        val iface = Class.forName(I_REMOTE_PROCESS)
        getInputStream = iface.getMethod("getInputStream")
        getErrorStream = iface.getMethod("getErrorStream")
        // 第二参是 String（枚举常量名），不是 TimeUnit —— 见 MILLIS_UNIT。
        waitForTimeout = iface.getMethod("waitForTimeout", Long::class.javaPrimitiveType, String::class.java)
        exitValue = iface.getMethod("exitValue")
        destroy = iface.getMethod("destroy")
    }
}

/**
 * 一条流的尾部缓冲（读到 EOF 或线程被丢弃时，读侧拿到的都是"此刻已读到的字节"）。
 *
 * [done] **幂等**：读线程的 `finally` 与调用方的 `finally` 都会调它 —— 正常路径是前者先到，
 * 超时/异常路径靠后者收尸，两边都不会漏掉关流（关流 = 关掉 PFD，不关就是 fd 泄漏）。
 */
private class StreamTail(private val stream: InputStream?, private val limitChars: Int) {
    private val sb = StringBuilder()
    private val latch = CountDownLatch(1)
    private var cut = false

    /** 至少一个字节因超出 [limitChars] 被丢弃。 */
    val truncated: Boolean get() = cut

    /**
     * 整行整行地收；**装不下就收到装得下的那一段为止**并置 [truncated]（读线程继续读，
     * 见 [ShizukuProcessReader] 里的 `drainInto`）。
     *
     * 为什么不是「这一行装不下就整行丢掉」：一条超长行（`cat` 一个压缩过的 js）会因此
     * 一个字节都不留 —— 用户看到的是"没有输出"，而实际是"输出太大"。留半截 + 置标志，
     * 至少与 `AndroidShellExecutor` 的截断语义（留到上限、丢后续）一致。
     *
     * 截断后**不再追加**（省掉后续每一行的判断），但读线程照旧读到 EOF —— 不读会把
     * 子进程堵在写满的管道上（`AndroidShellExecutor` 第 3 条那条死锁）。
     */
    fun append(line: String): StreamTail {
        if (cut) return this
        val room = limitChars - sb.length
        if (room <= 0) {
            cut = true
            return this
        }
        if (line.length + 1 <= room) {
            sb.append(line).append('\n')
            return this
        }
        cut = true
        sb.append(line, 0, room - 1)
        return this
    }

    fun done() {
        runCatching { stream?.close() }
        latch.countDown()
    }

    fun join() {
        latch.await(JOIN_MILLIS, TimeUnit.MILLISECONDS)
    }

    fun text(): String? = sb.toString().ifBlank { null }

    private companion object {
        /** 等读线程收尾的上限（同 `AndroidShellExecutor` 的收尸等待口径）。 */
        const val JOIN_MILLIS = 500L
    }
}

/**
 * [ShizukuInput.exec] 的结果：退出码 + 两条流（null = 该流没产出，**不拿空串冒充**）+ 截断标志。
 *
 * 刻意不复用 `:platform:system` 的 `ShellResult`：本模块看不到那个类型
 * （`platform/capabilities/build.gradle.kts` 只依赖 `:domain`），而且两者的口径确实不同
 * —— 那个带 `ShellCaptureLimit` 的内部判定，这里的上限是 [ShizukuProcessReader] 自己的
 * `CAPTURE_LIMIT_CHARS`（4 KiB，见那一条的 KDoc：两个模块各有各的常量，统一要 `:domain`
 * 出共享契约）。转接发生在 `:app` 的装配侧（`PlatformWiring`），那是唯一同时看得见
 * 两个模块的地方。
 *
 * [truncated] 的语义与 `ShellResult.truncated` 对齐（2026-10-09 补齐，此前是**已知缺口**：
 * adb 档截到上限时控制台不打那句「输出超过上限，已截断」，因为本 DTO 没有这个字段）：
 * **至少一条流被截断**，且**不进 `isSuccess` 的判据** —— `code` 是子进程的真实退出码，
 * 截断是宿主侧的捕获策略，两者正交。
 */
data class ShizukuExecResult(
    val code: Int,
    val stdout: String?,
    val stderr: String?,
    val truncated: Boolean = false,
)
