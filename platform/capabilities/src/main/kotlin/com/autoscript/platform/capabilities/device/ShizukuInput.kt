package com.autoscript.platform.capabilities.device

import com.autoscript.domain.automation.InputChannel
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import java.io.InputStream
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
 * **未真机验证**：设备道 2026-10-06 已裁（backlog B3/E3）。JVM 侧只能钉「Shizuku 缺席时
 * 如实拒绝」这条；真机上的「装好 Shizuku 后能不能注进去」尚无人跑过。
 */
object ShizukuInput {

    /** Shizuku 主类的全名（`dev.rikka.shizuku` 的公开入口）。 */
    private const val SHIZUKU_CLASS = "rikka.shizuku.Shizuku"

    /**
     * 服务接口的全名（`newProcess` 从**它**上面取，见 [run] 里那段注释）。
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
        val shizuku = shizukuClass()
        val binder = step("Shizuku 服务未运行（binder 为 null）—— 请先启动 Shizuku 并授权本应用") {
            shizuku.getMethod("getBinder").invoke(null)
        }
        val service = step("Shizuku 服务接口不可用（IShizukuService 形状不符）") {
            val stub = Class.forName("moe.shizuku.server.IShizukuService\$Stub")
            stub.getMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder)
        }
        val process = step("Shizuku newProcess 调用失败（服务版本不兼容？）") {
            // **方法必须从公开接口取，不能从 `service.javaClass` 取**：`asInterface` 返回的是
            // AIDL 生成的 `IShizukuService$Stub$Proxy`，那个类是**包内可见**的（`javap` 可见
            // `class moe.shizuku.server.IShizukuService$Stub$Proxy`，无 `public`）。在它上面
            // `getMethod` 拿到的 `Method` 带着"声明类不可见"的访问检查，`invoke` 抛
            // `IllegalAccessException`（不是 `NoSuchMethodException`，故不会被当成"版本不兼容"，
            // 而是被 [step] 折成同一句拒绝 —— 症状是 adb 通道**永远不可用**，且没有任何提示
            // 指向真因）。在**公开接口**上取同一个方法签名则声明类可见，invoke 正常分派到实现类。
            val api = Class.forName(IShizuku_SERVICE)
            api.getMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
                .invoke(service, arrayOf("sh", "-c", command), arrayOf<String>(), null)
        }
        return try {
            drain(process, timeoutMillis)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw denied("Shizuku 命令被中断", e)
        }
    }

    /**
     * 反射调用的一步：null 结果与反射异常都折成同一个「这一步不行」。
     *
     * 抽出来是为了让 [run] 读起来是一条直线（每一步一行），也把「哪一步失败了」的
     * 措辞收在一处 —— 用户看到的是一句话，不该是一串 `InvocationTargetException`。
     */
    private inline fun <T : Any> step(failure: String, call: () -> T?): T = try {
        call() ?: throw denied(failure, null)
    } catch (e: ReflectiveOperationException) {
        throw denied("$failure（反射失败：${e.javaClass.simpleName}）", e)
    }

    /**
     * 等远端进程结束并取退出码。
     *
     * **stderr 必须排空**：Shizuku 的远端进程同样是管道，不读满会反压住对端
     * （与 `:engine:node-process` 的排水线程同一条账）。这里只留尾部 4 KiB 用于报错 ——
     * 我们要的是「为什么失败」，不是完整输出。
     */
    private fun drain(process: Any, timeoutMillis: Long): Pair<Int, String?> {
        val err = process.javaClass.getMethod("getErrorStream").invoke(process) as? InputStream
        val tail = StringBuilder()
        val reader = err?.let { stream ->
            Thread({
                try {
                    stream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            if (tail.length < STDERR_TAIL_CHARS) tail.append(line)
                        }
                    }
                } catch (_: Exception) {
                    // 排水线程的收尾异常不影响判定：退出码才是判据（同 §8.5 的排水纪律）
                }
            }, "shizuku-stderr").apply { isDaemon = true; start() }
        }
        val waitForTimeout = process.javaClass.getMethod("waitForTimeout", Long::class.java, TimeUnit::class.java)
        val finished = waitForTimeout.invoke(process, timeoutMillis, TimeUnit.MILLISECONDS) as Boolean
        if (!finished) {
            runCatching { process.javaClass.getMethod("destroy").invoke(process) }
            throw denied("Shizuku 命令超时 ${timeoutMillis}ms（已尝试终止）", null)
        }
        reader?.join(JOIN_MILLIS)
        val code = process.javaClass.getMethod("exitValue").invoke(process) as Int
        return code to tail.toString().ifBlank { null }
    }

    /**
     * 与 [drain] 同形，但**两条流都排空并各自保留**（[exec] 用）。
     *
     * 两条流必须**并发**读（与 `AndroidShellExecutor` 第 3 条同一条账）：管道缓冲区写满即
     * 阻塞子进程，先读干 stdout 再读 stderr 会在输出超过一屏时死锁。故两个守护线程。
     * 上限同 [STDERR_TAIL_CHARS] —— 控制台要的是「命令说了什么」，不是全量转储
     * （真流式/分页是另一条面，本批不做）。
     */
    private fun drainBoth(process: Any, timeoutMillis: Long): ShizukuExecResult {
        val out = pump(process, "getInputStream", "shizuku-stdout")
        val err = pump(process, "getErrorStream", "shizuku-stderr")
        val waitForTimeout = process.javaClass.getMethod("waitForTimeout", Long::class.java, TimeUnit::class.java)
        val finished = waitForTimeout.invoke(process, timeoutMillis, TimeUnit.MILLISECONDS) as Boolean
        if (!finished) {
            runCatching { process.javaClass.getMethod("destroy").invoke(process) }
            throw denied("Shizuku 命令超时 ${timeoutMillis}ms（已尝试终止）", null)
        }
        out.join(); err.join()
        val code = process.javaClass.getMethod("exitValue").invoke(process) as Int
        return ShizukuExecResult(code, out.text(), err.text())
    }

    /** 起一条守护线程把 [getter] 那条流读到 EOF（截断到 [STDERR_TAIL_CHARS]）。 */
    private fun pump(process: Any, getter: String, name: String): StreamTail {
        val tail = StreamTail()
        val stream = process.javaClass.getMethod(getter).invoke(process) as? InputStream
        if (stream == null) {
            tail.done()
            return tail
        }
        Thread({
            try {
                stream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (tail.length < STDERR_TAIL_CHARS) tail.append(line)
                    }
                }
            } catch (_: Exception) {
                // 排水线程的收尾异常不影响判定：退出码才是判据（同 §8.5 的排水纪律）
            } finally {
                tail.done()
            }
        }, name).apply { isDaemon = true; start() }
        return tail
    }

    /** 一条流的尾部缓冲（读到 EOF 或线程被丢弃时，读侧拿到的都是"此刻已读到的字节"）。 */
    private class StreamTail {
        private val sb = StringBuilder()
        private val latch = CountDownLatch(1)
        val length: Int get() = sb.length
        fun append(line: String): StreamTail { sb.append(line).append('\n'); return this }
        fun done() = latch.countDown()
        fun join() { latch.await(JOIN_MILLIS, TimeUnit.MILLISECONDS) }
        fun text(): String? = sb.toString().ifBlank { null }
    }

    /**
     * 跑一条命令并**同时**取回两条流（2026-10-09 新增：控制台的 shell 面要用 stdout）。
     *
     * 与 [run] 的差别只有一个，但那个差别决定它能不能服务控制台：[run] 只为**输入注入**
     * 服务（`input tap` 没有 stdout），故只留 stderr 尾部 4 KiB 用于报错；控制台敲
     * `ls -la` 要的**正是 stdout**，用 [run] 会拿到一条永远为空的输出。
     *
     * 失败语义与 [run] 一致：一律抛 [AutojsException] `ERR_PERMISSION_DENIED` 并把
     * 「是哪一步不行」写在 detail 里（不返回假退出码 —— 那会让「Shizuku 挂了」
     * 看起来像「这条命令失败了」）。
     */
    fun exec(command: String, timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS): ShizukuExecResult {
        val shizuku = shizukuClass()
        val binder = step("Shizuku 服务未运行（binder 为 null）—— 请先启动 Shizuku 并授权本应用") {
            shizuku.getMethod("getBinder").invoke(null)
        }
        val service = step("Shizuku 服务接口不可用（IShizukuService 形状不符）") {
            val stub = Class.forName("moe.shizuku.server.IShizukuService\$Stub")
            stub.getMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder)
        }
        val process = step("Shizuku newProcess 调用失败（服务版本不兼容？）") {
            val api = Class.forName(IShizuku_SERVICE)
            api.getMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
                .invoke(service, arrayOf("sh", "-c", command), arrayOf<String>(), null)
        }
        return try {
            drainBoth(process, timeoutMillis)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw denied("Shizuku 命令被中断", e)
        }
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

    private fun denied(detail: String, cause: Throwable?) = AutojsException(
        ErrorCode.ERR_PERMISSION_DENIED,
        "adb 输入通道不可用：$detail",
        cause,
    )

    private const val STDERR_TAIL_CHARS = 4 * 1024
    private const val JOIN_MILLIS = 500L

    /** 本通道的枚举值（装配层用它登记 `channels` 表）。 */
    val channel: InputChannel get() = InputChannel.ADB
}

/**
 * [ShizukuInput.exec] 的结果：退出码 + 两条流（null = 该流没产出，**不拿空串冒充**）。
 *
 * 刻意不复用 `:platform:system` 的 `ShellResult`：本模块看不到那个类型
 * （`platform/capabilities/build.gradle.kts` 只依赖 `:domain`），而且两者的口径确实不同
 * —— 那个带 `truncated` 的内部判定，这里只有尾部截断。转接发生在 `:app` 的装配侧
 * （`PlatformWiring`），那是唯一同时看得见两个模块的地方。
 *
 * **已知缺口（如实记，不假装没有）**：本 DTO **没有** `truncated` 字段，于是经
 * `PlatformWiring` 转成 `ShellConsoleResult` 时它取缺省 `false` —— adb 档的输出被
 * 截到 4 KiB 时，控制台**不会**打那句「输出超过上限，已截断」。root 档（走
 * `AndroidShellExecutor`）有真判据，那一档会打。补法是给本 DTO 加一个 `truncated`
 * 并让 [pump] 在越限时置位；本批没做（要先定「截断」在两条流上怎么算一个数）。
 */
data class ShizukuExecResult(val code: Int, val stdout: String?, val stderr: String?)
