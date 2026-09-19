package com.autoscript.appservice.runtime

import com.autoscript.domain.engine.EngineStatus
import java.nio.file.Files
import java.nio.file.Path

/**
 * 引擎进程的 `/proc` 采样器（docs §8.4「已就绪的判据、待接线的眼睛」的眼睛部分）。
 *
 * 分工（与 [WatchdogPolicy] / [RuntimeController] 三方各自只做一件事）：
 * - 本类**只采样不裁决**：读 `/proc/<pid>/stat` 的 utime+stime 差分算 CPU 百分比、
 *   读 `/proc/<pid>/status` 的 `VmRSS:` 拿 RSS，产出 [WatchdogSample]；
 * - 裁决归 [WatchdogPolicy]（纯函数、阈值可配、可单测）；
 * - kill 归 [RuntimeController.killRun]/[killAll]（kill 权威 §4.1）；
 * - **runId→pid 归属表归调用方**（`:app` 持，§8.4）：本类按 pid 采样，不认识 runId。
 *
 * pid 一旦终结（stop/kill/换执行体）调用方必须调 [forget]，否则 OS 复用该 pid 时
 * 新进程会背上旧进程的 CPU 计数做差分，被误判成风暴。详见 [forget] 的注释。
 *
 * CPU 差分的诚实口径：
 * - 分子 = 本次 (utime+stime) − 上次 (utime+stime)，单位 jiffies，按 `clockTicksPerSecond`
 *   归一化到毫秒；
 * - 分母 = 两次采样的**墙钟**间隔（调用方给 [sample] 的 `atMillis`，不读被采样进程的时钟）；
 * - 结果 = 进程拿到的 CPU 千分比（多核下可 >100）。[WatchdogPolicy] 的阈值默认 95% 承接；
 *   **不把多核百分比折算成单核** —— 那会让多核满载看起来「只有一半忙」而漏杀暴风雨进程。
 *
 * 首采样 / pid 变化 / 时钟回拨 / 计数回绕：一律回 0.0%（重新起步），**绝不给假差分**。
 *
 * **按 pid 分别记账**（不是只记一条"上次"）：池里 N 个引擎并发跑时，调度循环是轮询采样的，
 * 只记单条基线会让每次采样都判"pid 变了"→ 清一色 0.0%，CPU 风暴这一路永远不触发（漏杀）。
 *
 * 进程不存在或 /proc 不可读（Android 对异 UID 进程常如此）→ 采样回 null，调用方按
 * 「无法度量」处理（不猜 0%、不伪造健康）。RSS 缺失单独回 0（内存这一路降级为不判定），
 * 不因此让整个样本作废 —— CPU 与 RSS 是两个独立的判定输入。
 */
class ProcessMonitor(
    private val clockTicksPerSecond: Long = SC_CLK_TCK_FALLBACK,
    /** 实例级读取缝：生产走 [statFile]/[statusFile]。上层次级循环持有本类，故缝也要能整体注入。 */
    private val statReader: StatReader = StatReader { statFile(it) },
    /** 实例级读取缝；单测/桌面无 Android 时可整体换成假 /proc。 */
    private val statusReader: StatusReader = StatusReader { statusFile(it) },
) {
    init {
        require(clockTicksPerSecond > 0) { "clockTicksPerSecond 必须 > 0: $clockTicksPerSecond" }
    }

    /** /proc 文本读取缝（测试注入假 /proc；生产走 [readFile]）。 */
    fun interface StatReader { fun read(pid: Int): String? }
    fun interface StatusReader { fun read(pid: Int): String? }

    /** 某个 pid 的上次采样状态（差分只对同一 pid 有意义）。 */
    private data class Last(val pid: Int, val cpuMillis: Long, val atMillis: Long)

    private val lock = Any()

    /**
     * pid → 上次采样（插入序 = 首次观测序，供 [MAX_TRACKED_PIDS] 淘汰时定位最旧条目）。
     * 必须每 pid 一份：见类注释的"按 pid 分别记账"。
     */
    private val baselines = LinkedHashMap<Int, Last>()

    /**
     * 采一次样。
     *
     * @param pid 引擎进程 pid。差分只对**同一 pid 的连续两次采样**有意义：换 pid 即重新起步
     *   （不做跨进程差分，见 [baselines]）。
     */
    fun sample(
        pid: Int,
        status: EngineStatus,
        sinceHeartbeatMillis: Long,
        atMillis: Long = System.currentTimeMillis(),
        statReader: StatReader = this.statReader,       // 缺省走实例缝（生产 = /proc，测试 = 假文本）
        statusReader: StatusReader = this.statusReader,
    ): WatchdogSample? {
        val statText = statReader.read(pid) ?: return null
        val millisPerTick = 1000L / clockTicksPerSecond
        val cpu = parseStatCpuMillis(statText, millisPerTick) ?: return null
        val rss: Long = when (val statusText = statusReader.read(pid)) {
            null -> 0L                      // 读不到 status：只丢 RSS（内存路不判定），不废整份样本
            else -> parseRssBytes(statusText)
        }

        val cpuPercent = synchronized(lock) {
            val prev = baselines[pid]
            baselines[pid] = Last(pid, cpu, atMillis)   // 覆盖写不改变插入序（旧条目位置不变）
            pruneLocked()
            if (prev == null) 0.0 else cpuBetween(prev.cpuMillis, cpu, prev.atMillis, atMillis)
        }

        return WatchdogSample(
            pid = pid,
            status = status,
            heartbeatMillis = sinceHeartbeatMillis,
            cpuPercent = cpuPercent,
            rssBytes = rss,
        )
    }

    /**
     * 忘记某个 pid 的采样基线（run 终结 / 被 kill / 换执行体时必须调用，§8.4）。
     *
     * 为什么必需：Linux 会**复用 pid**。不忘记基线，同一个 pid 号落到新进程头上时，
     * [cpuBetween] 拿旧进程的 utime+stime 做分子 —— 新进程可能因此拿到一个虚高的
     * cpuPercent，看门狗据此判「CPU 风暴」杀掉一个刚起步的无辜进程。
     * 忘记后该 pid 的下一次采样是首采样 → 0.0%（重新起步，不背旧账）。
     *
     * 幂等：从不认识该 pid 时也是空操作。
     */
    fun forget(pid: Int) {
        synchronized(lock) {
            baselines.remove(pid)
        }
    }

    /** 当前持有基线的 pid 集合（诊断/单测用：验证忘记真的发生了）。 */
    fun trackedPids(): Set<Int> = synchronized(lock) { baselines.keys.toSet() }

    /** 淘汰最旧的一条基线（按 atMillis）：调用方漏调 [forget] 时降级为丢 CPU 判定，而不是无界增长。 */
    private fun pruneLocked() {
        if (baselines.size <= MAX_TRACKED_PIDS) return
        val stalest = baselines.values.minByOrNull { it.atMillis } ?: return
        baselines.remove(stalest.pid)
    }

    private fun cpuBetween(prevCpu: Long, nowCpu: Long, prevAt: Long, nowAt: Long): Double {
        if (nowAt <= prevAt) return 0.0      // 时钟回拨 / 同刻重复采样：不给无意义百分比
        val used = nowCpu - prevCpu
        if (used < 0) return 0.0             // jiffies 回绕（pid 复用等）：重新起步
        return used * 100.0 / (nowAt - prevAt)
    }

    companion object {

        /** Linux/Android 用户态 `SC_CLK_TCK` 恒为 100（每 jiffy 10ms）；可覆盖便于测试。 */
        val SC_CLK_TCK_FALLBACK: Long = 100L

        /**
         * 同时保有基线的 pid 数上限。取 128 的理由：默认口径下 30s 窗口 × 2 次/秒 = 60 段，
         * 128 足够覆盖任何合理配置的在途引擎数；真超过了说明调用方漏调 [forget]
         * （漏调是 bug，但看门狗不该因此 OOM）——淘汰最旧基线，退化成"这个 pid 没有 CPU 判定"。
         */
        const val MAX_TRACKED_PIDS: Int = 128

        /**
         * 解析 `/proc/<pid>/stat`，取字段 14/15（utime/stime）折成毫秒。
         *
         * 陷阱：字段 2 `comm` 可含空格与括号（如 `((sd-pam))`），必须从**最后一个** `)`
         * 之后再按空白切；从 `(` 起算会切错（见 `man 5 proc`）。
         */
        fun parseStatCpuMillis(text: String, millisPerTick: Long): Long? {
            val close = text.lastIndexOf(')')
            if (text.isEmpty() || close < 0) return null
            val fields = text.substring(close + 1).trim().split(' ').filter { it.isNotEmpty() }
            // fields[0] 是 state（字段 3）；字段号 N 对应 fields[N - 3]
            val utime = fields.getOrNull(14 - 3)?.toLongOrNull() ?: return null
            val stime = fields.getOrNull(15 - 3)?.toLongOrNull() ?: return null
            return utime * millisPerTick + stime * millisPerTick
        }

        /** 解析 `/proc/<pid>/status` 的 `VmRSS:`（kB → bytes）；缺行回 0（内存路降级，不判死）。 */
        fun parseRssBytes(text: String): Long {
            for (line in text.lineSequence()) {
                if (!line.startsWith("VmRSS:")) continue
                val kb = line.removePrefix("VmRSS:").trim().removeSuffix("kB").trim().toLongOrNull()
                return kb?.times(1024) ?: 0L
            }
            return 0L
        }

        /** 测试用的假 /proc：始终回同一段文本（生产路径走 [readFile]）。 */
        fun statReaderOf(text: String): StatReader = StatReader { text }
        fun statusReaderOf(text: String): StatusReader = StatusReader { text }
    }
}

/** /proc/<pid>/stat 的默认读法：文件级函数，供构造默认值引用（构造期还不可用实例成员）。 */
private fun statFile(pid: Int): String? = readFile(Path.of("/proc/$pid/stat"))

/** /proc/<pid>/status 的默认读法。 */
private fun statusFile(pid: Int): String? = readFile(Path.of("/proc/$pid/status"))

/** 读 /proc 文本：不存在/不可读/读失败统一回 null（调用方按「不可度量」处理）。 */
private fun readFile(path: Path): String? = try {
    if (Files.isReadable(path)) Files.readString(path).ifEmpty { null } else null
} catch (_: Exception) {
    null
}
