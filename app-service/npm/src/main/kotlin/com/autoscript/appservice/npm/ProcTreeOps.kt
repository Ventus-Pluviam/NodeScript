package com.autoscript.appservice.npm

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * T1 杀树的 **proc 扫描与按 pid 终止面**（2026-10-11 批 95：§10.3「`/proc` 同 UID+PPID 链
 * 二次收割」的落地，同时修掉批 91 那版在设备上的**静默空转**）。
 * （标题里不写 `*` + `/proc` 连排：那两个字符连起来会把本 KDoc 提前终止掉。）
 *
 * ## 为什么 `ProcessHandle.descendants()` 在真机上从来没跑起来过
 *
 * 批 91 的 [T1Child.reap] 用 `proc.toHandle().descendants()` 收后代。那条链在**桌面上是对的**，
 * 在**设备上是静默空转**：`java.lang.ProcessHandle` 整类**不在 android.jar 里**
 * （实查 `platforms/android-35/data/api-versions.xml` 无此类，jar 里也无 class 文件；
 * `java.lang.Process` 桩面只有 destroy/destroyForcibly/exitValue/isAlive/waitFor ——
 * 既无 `toHandle()` 也无 `pid()`）。运行时调用抛 `NoSuchMethodError`/`NoClassDefFoundError`，
 * 被 `runCatching` 吞掉 → 后代集合**恒空** → 杀树退化成只杀主进程，而**没有任何报错**
 * （这正是 ProcessLauncher KDoc 记的「看着能用」错觉：javac 按桌面 JDK 放行，本模块是纯
 * JVM 模块、没有 lint 的 NewApi 兜着）。用户设备 Android 13 上，批 91 记的「孙子被
 * reparent 后链断」其实从未成为主要问题 —— **整条后代链在设备上都不存在**。
 *
 * ## /proc 版的三个要点
 *
 * 1. **扫在杀之前**：reparent 只断「死了之后沿 ppid 往回找」；pid 集合一旦在树还活着时
 *    到手，之后谁被 reparent 都无所谓 —— 我们手里有号。
 * 2. **同 UID 是继承出来的**：后代的 uid 必然等于我们的 uid（fork 不换 uid），所以
 *    ppid 图上从 rootPid 走得到的集合**天然同 UID**，不必逐 pid 查 `/proc/<pid>/status`；
 *    Android 的 hidepid=2 还会让别家的条目在扫描时直接读不到（跳过即可）。
 * 3. **按 pid 杀用 `kill` 二进制**（toybox/util-linux 两边都有）：JVM 没有「按 pid 杀」的
 *    API（`ProcessHandle` 上面说了不在设备上），我们只有主进程的 `Process` 对象。
 *
 * [procDir] 可注入（单测喂一棵合成的 /proc 树 —— 借宿主 /proc 判状态在 CI 上是假绿，
 * 本仓有过教训）。默认 `/proc`。
 */
class ProcOps(
    private val procDir: Path = DEFAULT_PROC,
) {

    /** [process] 的全部后代 pid（不含它自己）；pid 取不到 / 进程已死 → 空集（降级为只杀主进程）。 */
    fun descendantsOf(process: Process): Set<Long> {
        val pid = pidOf(process) ?: return emptySet()
        return descendantsOf(pid)
    }

    /** 从 [rootPid] 沿 ppid 图向下走得到的全部 pid（不含 root 自己）。 */
    fun descendantsOf(rootPid: Long): Set<Long> {
        val byParent = mutableMapOf<Long, MutableList<Long>>()
        try {
            Files.newDirectoryStream(procDir).use { stream ->
                for (entry in stream) {
                    val stat = readStat(entry) ?: continue
                    byParent.getOrPut(stat.ppid) { mutableListOf() }.add(stat.pid)
                }
            }
        } catch (_: Exception) {
            // /proc 读不了（不存在的平台/权限）：回空集 —— 降级为只杀主进程，不炸收尸。
            return emptySet()
        }
        val out = mutableSetOf<Long>()
        val queue = ArrayDeque<Long>()
        queue.add(rootPid)
        while (queue.isNotEmpty()) {
            val children = byParent.remove(queue.removeFirst()) ?: continue
            for (child in children) {
                if (out.add(child)) queue.add(child)
            }
        }
        return out
    }

    /**
     * [process] 的 pid；取不到回 null（诚实降级，不回 0/自身 —— §8.4 同一条纪律）。
     *
     * 两级阶梯，**都走反射**（本模块是纯 JVM 模块，编译按桌面 JDK 放行，但设备运行时
     * 两个 tier 的可用性不同 —— 直调 `process.pid()` 在 android.jar 桩面上根本不存在，
     * 与 `Path.of`/`UnixDomainSocketAddress` 同一族「看着能用」的坑）：
     * 1. `pid()` 方法（JDK 9+ / Android 14+ 运行时有）；
     * 2. 私有字段 `pid`（Android 13 libcore 的 `ProcessImpl` 有这个字段；桌面 JDK 17 的
     *    模块系统会拒 `setAccessible` —— 那时 tier 1 已经成了，轮不到它）。
     */
    fun pidOf(process: Process): Long? {
        pidMethod?.let { m ->
            val viaMethod = runCatching { m.invoke(process) as? Number }.getOrNull()?.toLong()
            if (viaMethod != null && viaMethod > 0) return viaMethod
        }
        var cls: Class<*>? = process.javaClass
        while (cls != null && cls != Any::class.java) {
            val viaField = runCatching {
                val f = cls.getDeclaredField("pid")
                f.isAccessible = true
                (f.get(process) as? Number)?.toLong()
            }.getOrNull()
            if (viaField != null && viaField > 0) return viaField
            cls = cls.superclass
        }
        return null
    }

    /** 按 pid 杀一批（TERM 或 KILL）；空集/二进制缺失 = 尽力而为，不抛（收尸路径不许炸）。 */
    fun kill(pids: Collection<Long>, force: Boolean) {
        pids.filter { it > 0 }.chunked(KILL_BATCH).forEach { batch ->
            runCatching {
                val argv = buildList {
                    add(KILL_BIN)
                    add(if (force) "-KILL" else "-TERM")
                    addAll(batch.map { it.toString() })
                }
                val p = ProcessBuilder(argv).start()
                runCatching {
                    if (!p.waitFor(KILL_WAIT_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                        p.destroyForcibly()
                    }
                }
            }
        }
    }

    /** `/proc/<pid>/stat` 的三件：pid / state / **ppid**（comm 里可以有空格和右括号，见 [readStat]）。 */
    internal data class Stat(val pid: Long, val state: Char, val ppid: Long)

    /**
     * 解析一条 `stat`。格式 `pid (comm) state ppid …`，而 **comm 是可变的**（进程可以
     * 改自己的名字，里面可以带空格与 `)`）—— 唯一稳的切法是找**最后一个** `)`：
     * 它之后才是定长字段区（state 是第一个，ppid 是第二个）。
     *
     * [entry] 是 `/proc/<pid>` 那个**目录**（stat 是它里面的文件 —— 第一版写成了直接读
     * 目录本身，症状是全部条目 "Is a directory" 被跳过、后代恒空集，测试的真进程树用例
     * 当场抓到）。
     */
    internal fun readStat(entry: Path): Stat? {
        val pid = entry.fileName.toString().toLongOrNull() ?: return null
        val line = try {
            Files.readAllLines(entry.resolve("stat")).firstOrNull() ?: return null
        } catch (_: Exception) {
            return null // 进程恰在此刻退出：跳过这一条，不是错误
        }
        val close = line.lastIndexOf(')')
        if (close < 0 || close + 2 >= line.length) return null
        val rest = line.substring(close + 2).split(' ')
        if (rest.size < 2) return null
        val state = rest[0].firstOrNull() ?: return null
        val ppid = rest[1].toLongOrNull() ?: return null
        return Stat(pid, state, ppid)
    }

    companion object {
        val DEFAULT_PROC: Path = Paths.get("/proc")

        /** `kill` 二进制：Android 在 `/system/bin/kill`（toybox），桌面在 PATH 里。 */
        const val KILL_BIN: String = "kill"

        /** 单次 `kill` 的 pid 上限（命令行长度是有界的；T1 的树实际很小，纯防御）。 */
        const val KILL_BATCH: Int = 100

        /** 等 `kill` 二进制自己退的上限（它该是微秒级；不退就强杀，别让收尸挂住）。 */
        const val KILL_WAIT_MILLIS: Long = 2_000L

        /**
         * `Process.pid()` 的反射句柄（与 `:engine:node-process` 的 `ProcessLauncher` 同一个
         * 探测手法与同一条理由）：android.jar 桩面没有这个方法，类加载时探一次，设备上
         * 没有就走字段那级。
         */
        private val pidMethod = try {
            Process::class.java.getMethod("pid")
        } catch (_: NoSuchMethodException) {
            null
        }
    }
}

/**
 * T1 杀树的操作面（缝：单测注入替身，生产 = [ProcTreeOps]）。
 *
 * 为什么不是直接在 [T1Child] 里 new [ProcOps]：reap 的判据（扫树在杀之前 / TERM→宽限→KILL /
 * 二次收割）要能用替身跑成确定性用例，而真 /proc + 真 `kill` 那条由真进程用例单独钉
 * （T1ProcessTest 的「真进程树」那条）。同 [T1ProcessRunner] 的分工。
 *
 * **为什么是 public 而不是 internal**：[T1Child] 是 public 类，构造参数的类型必须同级可见
 * （Kotlin 不许 public 签名暴露 internal 类型）—— 与 T1ProcessRunner 同一个理由。
 */
interface T1TreeOps {
    /** [process] 的全部后代 pid（不含它自己）。 */
    fun descendantsOf(process: Process): Set<Long>

    /** 从仍活着的 pid 集合再扫一轮后代（二次收割：宽限期内 TERM 被忽略的成员新起的）。 */
    fun descendantsOf(alivePids: Set<Long>): Set<Long>

    /** 按 pid 杀一批（TERM 或 KILL）。 */
    fun kill(pids: Collection<Long>, force: Boolean)

    /** [pid] 还活着吗（`/proc/<pid>` 在不在）。 */
    fun alive(pid: Long): Boolean
}

/** 生产实现：/proc 扫描 + `kill` 二进制。 */
class ProcTreeOps(
    private val ops: ProcOps = ProcOps(),
) : T1TreeOps {
    override fun descendantsOf(process: Process): Set<Long> = ops.descendantsOf(process)
    override fun descendantsOf(alivePids: Set<Long>): Set<Long> =
        alivePids.flatMap { ops.descendantsOf(it) }.toSet()
    override fun kill(pids: Collection<Long>, force: Boolean) = ops.kill(pids, force)
    override fun alive(pid: Long): Boolean = Files.exists(ProcOps.DEFAULT_PROC.resolve(pid.toString()))
}

/**
 * POSIX 信号名表（1–31，Linux/Android 同一份；32+ 是实时信号，编号各家不同**不猜**）。
 *
 * 用途见 [T1Child.run] 的 exit 帧：`exitValue >= 128` 时按 shell 惯例 `n = exit - 128`
 * 反查名字。**已知的误报档**：进程自己 `exit(143)` 会被报成 SIGTERM —— POSIX shell
 * （包括 npm 自己跑的 `sh`）对同一个数字也是这么报的，脚本生态里正常退出码 < 128，
 * 这个约定就是为「被信号杀死」这一档存在的。
 */
internal fun signalName(num: Int): String? = POSIX_SIGNAL_NAMES[num]

/** 表住这里而不是 when：三十一条分支直接把圈复杂度顶过 detekt 阈值（表没有分支语义）。 */
private val POSIX_SIGNAL_NAMES: Map<Int, String> = mapOf(
    1 to "SIGHUP", 2 to "SIGINT", 3 to "SIGQUIT", 4 to "SIGILL", 5 to "SIGTRAP",
    6 to "SIGABRT", 7 to "SIGBUS", 8 to "SIGFPE", 9 to "SIGKILL", 10 to "SIGUSR1",
    11 to "SIGSEGV", 12 to "SIGUSR2", 13 to "SIGPIPE", 14 to "SIGALRM", 15 to "SIGTERM",
    16 to "SIGSTKFLT", 17 to "SIGCHLD", 18 to "SIGCONT", 19 to "SIGSTOP", 20 to "SIGTSTP",
    21 to "SIGTTIN", 22 to "SIGTTOU", 23 to "SIGURG", 24 to "SIGXCPU", 25 to "SIGXFSZ",
    26 to "SIGVTALRM", 27 to "SIGPROF", 28 to "SIGWINCH", 29 to "SIGIO", 30 to "SIGPWR",
    31 to "SIGSYS",
)
