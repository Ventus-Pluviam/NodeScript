package com.autoscript.engine.nodeprocess

import com.autoscript.domain.engine.RunSummary
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * spawn 缝（对齐 `:app-service:packager` 的 `ProcessLauncher` 惯例，签名按引擎语义重画）：
 * [NodeProcessEngine] 与真实 OS 进程之间唯一的接触面 —— JVM 单测注入假实现即可断言
 * argv/env/cwd 与 stop/kill 语义，不必真起进程；真起进程的集成测试再换 [ProcessBuilderLauncher]。
 */
fun interface ProcessLauncher {
    /**
     * 拉起子进程。[env] 是**增量覆盖**（实现负责与父环境合并后写入子进程）；
     * [workingDir] 是子进程 cwd（实现不得忽略 —— 脚本的相对路径解析依赖它）。
     * 启动失败抛 [IOException]（调用方原样上抛进池的 Failed 路径，消息即真原因）。
     */
    fun spawn(command: List<String>, env: Map<String, String>, workingDir: Path): SpawnedProcess
}

/** 已拉起的子进程最小视图：pid/存活/退出码/两级终止 —— 语义面（何时用哪一级）在 [NodeProcessEngine]。 */
interface SpawnedProcess {
    /**
     * 子进程 pid；宿主运行时不可得（旧 API 无 `Process.pid()`）为 null ——
     * 与 `ScriptEngine.pid` 同一诚实口径（§8.4：绝不给 0/自身 pid），看门狗据此走 noPid 清单。
     */
    val pid: Int?

    /** 仍在运行（未退出、未被收尸）。 */
    val isAlive: Boolean

    /** 退出码；仍存活为 null（对齐 JVM `exitValue()` 语义，调用方先查 [isAlive]）。 */
    fun exitValue(): Int?

    /** 礼貌终止（SIGTERM）—— 四步 quiesce 的「请求退出」。 */
    fun destroy()

    /** 强制终止（SIGKILL）—— 仅 kill 权威（§4.1）经 quiesce 超时兜底触达。 */
    fun destroyForcibly()

    /** 等待退出，[timeoutMillis] 内退出回 true，超时回 false（不抛）。 */
    fun waitFor(timeoutMillis: Long): Boolean

    /**
     * 已捕获的 stderr 尾部（backlog B11 诊断面）：排水线程在自己的读通道里保留的
     * **有界尾部摘要**（utf-8、截断至 [com.autoscript.domain.engine.RunSummary.MAX_DETAIL]）。
     * 读取方取「当前缓冲」（进程已退净后即最终态；未捕获 = ""）。
     *
     * 与 stdout 严格区分：**stdout 仍读即弃** —— 捕获的成本是内存，给 stdout 也留
     * 一份会翻倍无界的风险；病因诊断的权威面是 stderr（脚本/宿主都往这里写错）。
     */
    val stderrTail: String

    /**
     * 等 stderr 排水读到 EOF，至多 [timeoutMillis]；排净回 true，超时回 false（不抛、不无限阻塞）。
     *
     * 为什么需要：[isAlive] = false 只说明子进程已被收尸，**不说明**排水线程已把管道读完 ——
     * 两者之间没有 happens-before，直接取 [stderrTail] 可能拿到半截尾部（病因恰在最后几行）。
     * 超时（如孙进程继承了 stderr 一直不关管道）由调用方用当前快照兜底。
     *
     * 缺省实现回 true：没有排水线程的假实现，[stderrTail] 本身即最终态。
     */
    fun awaitStderrDrained(timeoutMillis: Long): Boolean = true
}

/**
 * `Process.pid()` 的反射句柄：android.jar 桩面没有这个方法（编译期不可见），类加载时探测一次 ——
 * 桌面 JDK 得到真句柄；设备运行时按其 libcore 有无法如实为 null。
 */
private val jdkProcessPidMethod: java.lang.reflect.Method? = try {
    Process::class.java.getMethod("pid")
} catch (_: NoSuchMethodException) {
    null
}

/**
 * 私有字段 `pid` 的反射句柄（2026-10-11 批 97，外审 M5 的**第二级阶梯**）。
 *
 * 为什么加这一级：`pid()` 方法在 Android 上只有 API 33+ 才有（`api-versions.xml` 实查），
 * 而 minSdk 26 —— 于是 Android 8–12 上 [SpawnedProcess.pid] 恒 null，看门狗走 noPid 清单，
 * **且 [com.autoscript.bridge.RunIdentityRegistry] 的防劫持检查整段被跳过**（`peerPid != null
 * && spawn.pid != null` 两条件）。Android 13 libcore 的 `ProcessImpl` 有 `pid` 这个字段 ——
 * 与批 95 的 `ProcOps.pidOf` 同一手法、同一理由（那处是为了杀树，这里是为了身份绑定）。
 *
 * **桌面 JDK 上这一级是死代码**：模块系统拒 `setAccessible`，但那时 tier 1 已经成了。
 */
private val jdkProcessPidField: java.lang.reflect.Field? = run {
    var cls: Class<*>? = Process::class.java
    while (cls != null && cls != Any::class.java) {
        val found = runCatching {
            cls!!.getDeclaredField("pid").apply { isAccessible = true }
        }.getOrNull()
        if (found != null) {
            found
        } else {
            cls = cls.superclass
        }
    }
    null
}

/**
 * 真起进程（`ProcessBuilder`）。stdout 读即弃、**stderr 捕获有界尾部**（backlog B11）——
 * 不排水会把管道写满、把子进程卡死在 write 上（经典 pipe 反压死锁）：stdout 那条通道
 * 仍然只管排空，不存内容；stderr 在排空时顺带写入环形尾 buffer，诊断要用的是"崩了之后
 * 那几行"，不是全量。
 *
 * 为什么**不做** `redirectErrorStream(true)` 再合并捕获：合流会丢失"这是 stderr 写的"
 * 的分辨，而病因诊断（原生层 fprintf/addon 报错/脚本 throw）几乎全在 stderr ——
 * 合流后想把"真病因"从"常规输出"里捞出来多一次无谓的串扰。
 *
 * `Process.pid()` **不在 Android 平台 API 表**里 —— `platforms/android-3{5,6}/data/api-versions.xml`
 * 的 `java/lang/Process` 只有 `destroyForcibly`/`isAlive`/`waitFor(…,TimeUnit)`（均 since 26），
 * **没有 `pid`**；`java/lang/ProcessHandle` 整类也缺席。故直接调用会被 lint 的 `NewApi` 抓
 * （全模块 `lintDebug` 的 `checkDependencies`，见 `.github/workflows/ci.yml` 的 android-build job）。
 * 注意**不是** javac 抓的：android.jar 桩面里 `pid()` 其实**有**（libcore 派生），javac 放行 ——
 * 这也正是"看着能用"的错觉来源。故取 pid 走反射探测：桌面 JDK 恒有真 pid；Android 运行时有该
 * 方法则取，没有如实回 null（§8.4 noPid 路径，不是崩溃、不是 0/自身）。
 */
class ProcessBuilderLauncher : ProcessLauncher {

    override fun spawn(command: List<String>, env: Map<String, String>, workingDir: Path): SpawnedProcess {
        val process = ProcessBuilder(command)
            .directory(workingDir.toFile())
            .apply {
                environment().putAll(env)
                redirectErrorStream(false)   // 分开两条通道：stdout 排空、stderr 捕获尾部（见类 KDoc）
            }
            .start()
        // stderr 尾部缓冲（有界、只增到上限后丢最老）。单进程单写者（排水线程），读写经
        // @Synchronized 保可见性。注意「进程退净」**不蕴含**「排水线程已读到 EOF」——
        // 收尸与管道读完之间无 happens-before；要最终态须先 awaitStderrDrained（join 排水线程）。
        val tail = StderrTail(RunSummary.MAX_DETAIL)   // 上限与 :domain 摘要契约同值
        val drain = Thread {
            try {
                // stdout：读即弃（防反压死锁的排空主路，不存内容 —— 病因在 stderr 面）
                process.inputStream.use { input ->
                    val buf = ByteArray(8192)
                    while (input.read(buf) != -1) { /* 排空：不卡子进程在 write 上 */ }
                }
            } catch (_: Exception) {
                // 进程退出后管道关闭引发的读异常：排水线程的正常终点
            }
        }.apply {
            isDaemon = true
            name = "node-engine-drain-${process.hashCode()}"
            start()
        }
        // stderr 排水（捕获尾部）：与 stdout 同一原则（读不完会卡死子进程），
        // 只是路上把字节喂给有界尾 buffer。进程退净 → 本线程读到 EOF 自然退出。
        val drainErr = Thread {
            try {
                process.errorStream.use { input ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = input.read(buf)
                        if (n == -1) break
                        tail.append(buf, n)
                    }
                }
            } catch (_: Exception) {
                // 进程退出后管道关闭引发的读异常：排水线程的正常终点
            }
        }.apply {
            isDaemon = true
            name = "node-engine-drain-err-${process.hashCode()}"
            start()
        }
        return JdkSpawnedProcess(process, drain, drainErr, tail)
    }

    private class JdkSpawnedProcess(
        private val process: Process,
        @Suppress("unused") private val drain: Thread,   // 持引用防 GC 提前回收排水线程句柄
        private val drainErr: Thread,
        private val tail: StderrTail,
    ) : SpawnedProcess {
        /** 两级阶梯（`pid()` 方法 → 私有字段）：都探不到才回 null（§8.4 诚实 noPid，不是 0/自身）。 */
        override val pid: Int? = pidOf(process)

        override val isAlive: Boolean get() = process.isAlive

        override fun exitValue(): Int? = if (process.isAlive) null else process.exitValue()

        override val stderrTail: String get() = tail.snapshot()

        override fun awaitStderrDrained(timeoutMillis: Long): Boolean {
            try {
                drainErr.join(timeoutMillis.coerceAtLeast(1))   // join(0) = 无限等，故下限 1ms
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()               // 保留中断标记，用当前快照兜底
            }
            return !drainErr.isAlive
        }

        override fun destroy() = process.destroy()

        override fun destroyForcibly() {
            process.destroyForcibly()
        }

        override fun waitFor(timeoutMillis: Long): Boolean =
            process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
    }
}

/**
 * 有界环形尾 buffer（字节级，按 [capacityBytes] 只留最尾）：供排水线程写入、
 * [snapshot] 取 utf-8 尾部字符串。
 *
 * **分段解码**：`snapshot` 只对**当前缓冲**解码，drain 线程跨 read 切分写入的
 * 多字节字符不会跨界损坏 —— 因为我们保留的是字节尾巴，取快照时才一次性 UTF-8 解码，
 * 头部若是半个多字节字符（截断所致）解码为 U+FFFD，尾端永远干净。
 * 同理 [dropOldest] 按字节丢最老：可能丢进一个字符中间，但那只是一两字节的代价，
 * 与「保住病因那几行」的主目标相比可忽略。
 */
private class StderrTail(
    private val capacityBytes: Int,
) {
    private val ring = ByteArray(capacityBytes)
    private var start = 0
    private var length = 0

    @Synchronized
    fun append(src: ByteArray, n: Int) {
        var off = 0
        while (off < n) {
            val space = capacityBytes - length
            if (space > 0) {
                val take = minOf(space, n - off)
                val ringPos = (start + length) % capacityBytes
                System.arraycopy(src, off, ring, ringPos, take)
                length += take
                off += take
            } else if (dropOldest()) {
                // 满：丢最老给新字节腾位（dropOldest 已推进 start/length）
            } else {
                return
            }
        }
    }

    /** 丢最老一字节；buf 为空时无可丢。返回是否真的有东西可丢。 */
    private fun dropOldest(): Boolean {
        if (length == 0) return false
        start = (start + 1) % capacityBytes
        length -= 1
        return true
    }

    /** 当前缓冲的 utf-8 尾部快照（空 = ""）。 */
    @Synchronized
    fun snapshot(): String {
        if (length == 0) return ""
        val out = ByteArray(length)
        for (i in 0 until length) out[i] = ring[(start + i) % capacityBytes]
        return String(out, StandardCharsets.UTF_8)
    }
}

/**
 * 取子进程 pid（两级反射阶梯：方法 → 私有字段）。
 *
 * 与 `app-service:npm` 的 `ProcOps.pidOf` 逐字同款：**两处抽不出共享模块**（npm → domain 的
 * 依赖方向上 domain 不该知道进程；反之 `ProcOps` 还要 /proc 扫描，用不上就多背一份），
 * 故同一手法抄两处并在两侧 KDoc 互相点名 —— 改一处时另一处必须跟着看。
 *
 * @return null = 运行时两个 tier 都探不到（诚实 noPid，**绝不给 0/自身**）。
 */
internal fun pidOf(process: Process): Int? {
    jdkProcessPidMethod?.let { m ->
        val viaMethod = runCatching { (m.invoke(process) as? Number)?.toLong() }.getOrNull()
        if (viaMethod != null && viaMethod > 0) return viaMethod.toInt()
    }
    jdkProcessPidField?.let { f ->
        val viaField = runCatching { (f.get(process) as? Number)?.toLong() }.getOrNull()
        if (viaField != null && viaField > 0) return viaField.toInt()
    }
    return null
}
