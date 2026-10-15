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

    /**
     * OS 级冻结（SIGSTOP；2026-10-13 执行暂停）：进程原地停住，可被 [resumeExecution] 解冻。
     *
     * 信号走 `kill` 二进制（toybox，`ProcOps` 同手法）—— JVM 没有"按 pid 发信号"的 API
     * （`ProcessHandle` 不在 android.jar 桩面），本模块又禁依赖 `:platform:*`
     * （archUnit 引擎黑名单），故在模块内起 `kill` 进程（见真实现）。缺省 false：
     * 假实现/替身进程冻不住，如实报、不假装。
     */
    fun pauseExecution(): Boolean = false

    /** 解冻 [pauseExecution] 冻住的进程（SIGCONT）。缺省同样 false。 */
    fun resumeExecution(): Boolean = false

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
 * 私有字段 `pid` 的反射查找（2026-10-11 批 97，外审 M5 的**第二级阶梯**；
 * 2026-10-15 方向订正 —— 见下）。
 *
 * 为什么加这一级：`pid()` 方法**不在 Android 平台 API 表里**（`api-versions.xml` 实查，
 * android-36 的 `java/lang/Process` 仍只有 destroy/isAlive/waitFor 那几个），minSdk 26 ——
 * 于是设备上 tier 1 恒缺席，[SpawnedProcess.pid] 会恒 null：看门狗走 noPid 清单、
 * **[com.autoscript.bridge.RunIdentityRegistry] 的防劫持检查整段被跳过**
 * （`peerPid != null && spawn.pid != null` 两条件），**悬浮窗「暂停」也恒回 false**
 * （`pauseExecution` 第一句就是 `val target = pid ?: return false`）。
 *
 * **方向订正（2026-10-15，设备实测）**：原实现从 `Process::class.java` 起步、每轮
 * `cls.superclass` 往上走 —— 而 `getDeclaredField` **只看该类自己声明的成员**，
 * 于是这条循环走的是 `Process → Object`，**永远够不着子类**。设备 libcore 的真实形状是
 * 字段声明在**子类**上（Android 13 `core-oj.jar` 反编译实证：`java.lang.UNIXProcess`
 * 有 `private final int pid`，而 `java.lang.Process` 一个字段都没有；
 * 桌面 JDK 同形 —— `java.lang.ProcessImpl.pid`），所以这条阶梯**从来没生效过**。
 * 与批 95 的 `ProcOps.pidOf` 同一手法（那处从**运行时类** `process.javaClass` 起步，
 * 方向本来就是对的）—— 批 105 起那处住 `:platform:process`（两份副本合并），
 * 本模块这份**仍留**：`:platform:*` 是引擎的下层，引擎不得反向依赖它
 * （§6 + 本模块 ArchitectureTest 黑名单），一份纯反射阶梯的拷贝比那条越界依赖便宜。
 *
 * **为什么不缓存成类级 val**：字段住**运行时类**上，而运行时类是每次 spawn 才知道的
 * （且同一 JVM 里可能不止一种）—— 这里按调用查，代价是每个进程一次 `getDeclaredField`。
 *
 * **桌面 JDK 上这一级是死代码**：模块系统拒 `setAccessible`，但那时 tier 1 已经成了。
 */
private fun pidFieldOf(process: Process): java.lang.reflect.Field? {
    var cls: Class<*>? = process.javaClass
    while (cls != null && cls != Any::class.java) {
        val found = runCatching {
            cls!!.getDeclaredField("pid").apply { isAccessible = true }
        }.getOrNull()
        if (found != null) return found
        cls = cls.superclass
    }
    return null
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

        /**
         * `kill -STOP <pid>`（2026-10-13）：pid 为 null（旧 API 反射拿不到）或
         * `kill` 二进制缺席时回 false —— 冻不住就如实说，不猜。`waitFor` 等信号送达：
         * exit 0 才算送到（toybox 的 kill 对不存在的 pid 非 0，如实回 false）。
         */
        override fun pauseExecution(): Boolean = signal("-STOP")

        /** `kill -CONT <pid>`（与 [pauseExecution] 对偶）。 */
        override fun resumeExecution(): Boolean = signal("-CONT")

        private fun signal(flag: String): Boolean {
            val target = pid ?: return false
            return try {
                val sent = ProcessBuilder("kill", flag, target.toString()).start()
                val done = sent.waitFor(SIGNAL_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                done && sent.exitValue() == 0
            } catch (_: Exception) {
                false
            }
        }

        companion object {
            /** `kill` 信号进程的等待上限：发信号是瞬时动作，超了就是二进制缺席/卡住，如实 false。 */
            private const val SIGNAL_TIMEOUT_MILLIS = 2_000L
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
 * 与 `:platform:process` 的 `ProcOps.pidOf`（批 105 的合并落点）同一手法、同一理由，
 * 仍抄一份在本地：**两处抽不出共享模块** —— 引擎在 `:platform` 之上，反向依赖即破 §6 依赖
 * 方向（本模块 ArchitectureTest 把 `com.autoscript.platform..` 钉在黑名单里）。
 * **两份的方向都对**（都从运行时类起步）；引擎这份 2026-10-15 才改对，`ProcOps` 那份一直是对的
 * —— 这正说明"手法同款、代码两份"的真实代价：**一处抄漏一行，另一处不会红**。
 *
 * @return null = 运行时两个 tier 都探不到（诚实 noPid，**绝不给 0/自身**）。
 */
internal fun pidOf(process: Process): Int? {
    jdkProcessPidMethod?.let { m ->
        val viaMethod = runCatching { (m.invoke(process) as? Number)?.toLong() }.getOrNull()
        if (viaMethod != null && viaMethod > 0) return viaMethod.toInt()
    }
    pidFieldOf(process)?.let { f ->
        val viaField = runCatching { (f.get(process) as? Number)?.toLong() }.getOrNull()
        if (viaField != null && viaField > 0) return viaField.toInt()
    }
    return null
}
