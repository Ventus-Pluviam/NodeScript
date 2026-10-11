package com.autoscript.platform.system.shell

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 进程树收尸的 `/proc` 面（外审 M6，2026-10-11 批 99）。
 *
 * ## 与批 95 那份 `app-service:npm/ProcOps` 的关系
 *
 * 手法**逐字同款**（`/proc` ppid 图 + `kill` 二进制 + pid 两级反射阶梯），但**代码在两个
 * 模块各有一份** —— 这是被依赖方向逼的，不是疏忽：npm 模块与 platform 模块都只依赖
 * `:domain`，而 `ProcOps` 属于进程/文件系统面（不是领域概念），把它塞进 `:domain` 等于
 * 让领域层知道 `/proc`。两份的 KDoc 在这里与 npm 那侧互相点名：**改一处时另一处必须
 * 跟着看**。真要消重，唯一干净的路是抽一个「进程工具」模块（那是一次模块表变更，
 * 要维护者拍板），本批不做。
 *
 * ## 为什么不用 `java.lang.ProcessHandle`
 *
 * **那整类不在 android.jar 里**（批 95 已实查：`api-versions.xml` 无条目、jar 无 class、
 * `Process` 桩面无 `toHandle()`/`pid()`）—— 桌面 JDK 编译放行、设备上
 * `NoSuchMethodError`/`NoClassDefFoundError` 被吞成**静默空转**。「看着能用」同族第四例。
 */
class ProcOps(
    private val procDir: Path = DEFAULT_PROC,
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
     * 两级反射阶梯（与批 95 同手法、同理由）：`pid()` 方法（JDK 9+ / Android 14+ 运行时有）
     * → 私有字段 `pid`（Android 13 libcore 的 `ProcessImpl` 有）。本模块是纯 JVM 模块，
     * 编译按桌面 JDK 放行，直调 `process.pid()` 在设备桩面上根本不存在。
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
    internal data class Stat(val pid: Long, val state: Char, val ppid: Long)

    /**
     * 解析一条 `stat`。格式 `pid (comm) state ppid …`，而 **comm 是可变的**（进程可以
     * `prctl(PR_SET_NAME)` 改自己，里面可以带空格与 `)`）—— 唯一稳的切法是找**最后一个**
     * `)`：它之后才是定长字段区（state 第一个，ppid 第二个）。
     *
     * [entry] 是 `/proc/<pid>` 那个**目录**（stat 是它里面的文件）。批 95 在 npm 那侧
     * 踩过这个坑：第一版读成了目录本身，全部条目 "Is a directory" 被跳过 → 后代恒空集，
     * 而症状与设备上「静默空转」同形 —— 是那条真进程用例抓到的。
     */
    internal fun readStat(entry: Path): Stat? {
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

/**
 * 进程树收尸的操作面（缝：单测注入替身，生产 = [ProcTreeOps]）。
 *
 * 为什么是缝：收尸的判据（「孙进程确实被点名了」「TERM 后补 KILL」）要能跑成确定性用例，
 * 而真 `/proc` 那条由真进程用例单独钉 —— 与批 95 的 `T1TreeOps` 分工逐字相同。
 */
interface TreeOps {
    /** [process] 的全部后代 pid（不含它自己）。 */
    fun descendantsOf(process: Process): Set<Long>

    /** 按 pid 杀一批（TERM 或 KILL）。 */
    fun kill(pids: Collection<Long>, force: Boolean)
}

/** 生产实现：`/proc` 扫描 + `kill` 二进制。 */
class ProcTreeOps(
    private val ops: ProcOps = ProcOps(),
) : TreeOps {
    override fun descendantsOf(process: Process): Set<Long> = ops.descendantsOf(process)
    override fun kill(pids: Collection<Long>, force: Boolean) = ops.kill(pids, force)
}
