package com.autoscript.platform.process

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 进程树收尸的 `/proc` 面与 pid 提取面（docs §8 / §9.6；外审 M6）。
 *
 * ## 来历：两份逐字同款的重复合并到一处
 *
 * 2026-10-11 批 95（`:app-service:npm` 的 T1 杀树）与批 99（`:platform:system` 的 shell
 * 收尸）各自落了一份 `ProcOps`，**手法逐字同款**（`/proc` ppid 图 + `kill` 二进制 + pid
 * 两级反射阶梯），**代码两份**。当时两边 KDoc 互相点名、明确写下「被依赖方向逼的」：
 * npm 与 platform 两个模块都只依赖 `:domain`，而 `ProcOps` 属于进程/文件系统面，
 * 塞进 `:domain` 等于让领域层知道 `/proc`。**改一处时另一处必须跟着看** —— 那不是维护性，
 * 是欠了债。批 105 抽本模块把债还掉，两侧现在各留一句「已合并」的指针。
 *
 * 本模块**零模块依赖**（连 `:domain` 都不需要）：只用 `java.nio` 与 `Process` 桩面。
 *
 * ## 为什么不用 `java.lang.ProcessHandle`
 *
 * **那整类不在 android.jar 里**（批 95 已实查：`platforms/android-35/data/api-versions.xml`
 * 无条目、jar 无 class、`Process` 桩面无 `toHandle()`/`pid()`）—— 桌面 JDK 编译放行、
 * 设备上 `NoSuchMethodError`/`NoClassDefFoundError` 被吞成**静默空转**：后代集合恒空 →
 * 杀树退化成只杀主进程，**且没有任何报错**。这正是 `ProcessLauncher` KDoc 记的「看着能用」
 * 错觉的来源（javac 按桌面 JDK 放行，npm/platform 两模块是纯 JVM 模块、没有 lint 的
 * `NewApi` 兜着）。用户设备 Android 13 上，批 91 记的「孙子被 reparent 后链断」
 * 其实从未成为主要问题 —— **整条后代链在设备上都不存在**。
 *
 * ## `/proc` 版的三个要点
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
 * 本仓有过教训：pid=4242 借宿主 /proc 判死，本机全绿 CI 红）。默认 `/proc`。
 */
class ProcOps(
    /** 扫哪个 `/proc`。可注入（合成树单测）；生产走 [DEFAULT_PROC]。 */
    val procDir: Path = DEFAULT_PROC,
) {

    /** [process] 的全部后代 pid（不含它自己）；pid 取不到 / 平台不可读 → 空集（降级只杀主进程）。 */
    fun descendantsOf(process: Process): Set<Long> = pidOf(process)?.let { descendantsOf(it) } ?: emptySet()

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
            return emptySet()   // /proc 读不了：降级为只杀主进程，不炸收尸
        }
        val out = mutableSetOf<Long>()
        val queue = ArrayDeque<Long>()
        queue.add(rootPid)
        while (queue.isNotEmpty()) {
            for (child in byParent.remove(queue.removeFirst()) ?: continue) {
                if (out.add(child)) queue.add(child)
            }
        }
        return out
    }

    /**
     * [process] 的 pid；取不到回 null（**诚实降级，不回 0/自身** —— §8.4 同一条纪律）。
     *
     * 两级反射阶梯（`:engine:node-process` 的 `ProcessLauncher` 同手法、同理由，那处是
     * 为身份绑定，这里为杀树）：`pid()` 方法（JDK 9+ / Android 14+ 运行时有）→ 私有
     * 字段 `pid`（Android 13 libcore 的 `ProcessImpl` 有）。本模块是纯 JVM 模块，编译
     * 按桌面 JDK 放行，直调 `process.pid()` 在设备桩面上根本不存在。
     */
    fun pidOf(process: Process): Long? {
        pidMethod?.let { m ->
            val viaMethod = runCatching { (m.invoke(process) as? Number)?.toLong() }.getOrNull()
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

    /** 按 pid 杀一批（TERM 或 KILL）；空集 / 二进制缺失 = 尽力而为，不抛（收尸路径不许炸）。 */
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
                    if (!p.waitFor(KILL_WAIT_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS)) p.destroyForcibly()
                }
            }
        }
    }

    /** `/proc/<pid>/stat` 的三件：pid / state / ppid（comm 可变 → 找最后一个 `)`）。 */
    data class Stat(val pid: Long, val state: Char, val ppid: Long)

    /**
     * 解析一条 `stat`。格式 `pid (comm) state ppid …`，而 **comm 是可变的**（进程可以
     * `prctl(PR_SET_NAME)` 改自己，里面可以带空格与 `)`）—— 唯一稳的切法是找**最后一个**
     * `)`：它之后才是定长字段区（state 第一个，ppid 第二个）。
     *
     * [entry] 是 `/proc/<pid>` 那个**目录**（stat 是它里面的文件）。批 95 在 npm 那侧
     * 踩过这个坑：第一版读成了目录本身，全部条目 "Is a directory" 被跳过 → 后代恒空集，
     * 而症状与设备上「静默空转」同形 —— 是那条真进程用例抓到的。
     */
    fun readStat(entry: Path): Stat? {
        val pid = entry.fileName.toString().toLongOrNull() ?: return null
        val line = try {
            Files.readAllLines(entry.resolve("stat")).firstOrNull() ?: return null
        } catch (_: Exception) {
            return null   // 进程恰在此刻退出：跳过这一条，不是错误
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
        const val KILL_BIN: String = "kill"
        const val KILL_BATCH: Int = 100
        const val KILL_WAIT_MILLIS: Long = 2_000L

        private val pidMethod = try {
            Process::class.java.getMethod("pid")
        } catch (_: NoSuchMethodException) {
            null
        }
    }
}
