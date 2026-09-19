package com.autoscript.appservice.runtime

import com.autoscript.domain.engine.EngineStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `/proc` 采样器验证（docs §8.4「采样已落地、调度仍缺」部分）：
 * 采样**不裁决**，只把 /proc 文本折成 [WatchdogSample]；判定仍归 [WatchdogPolicy]。
 * 这里只保证「算得对、读不到时不编」。
 */
class ProcessMonitorTest {

    /**
 * 一段最小 /proc/<pid>/stat：末段补字段 10..13 的零，utime/stime 才落在字段 14/15。
 * （comm 含空格与括号的边界另测。）
 */
    private fun stat(utime: Long, stime: Long, comm: String = "node"): String {
        val head = "1234 ($comm) S 1 1234 1234 0 -1 4194560"
        val tail = "0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0"
        // 字段 14/15 是 utime/stime；把 head+tail 排成字段号正确的序列
        val sb = StringBuilder()
        sb.append(head)
        // head 到字段 9（S 1 1234 1234 0 -1 4194560 = 字段 3..9），
        // 补字段 10..13 四个 0，utime/stime 才落在字段 14/15（parser 取 fields[11]/[12]）
        repeat(4) { sb.append(" 0") }
        sb.append(" $utime $stime")
        sb.append(" ")
        sb.append(tail)   // 注意补空格：否则 stime 与 tail 首字段粘成一个数（"20"+"0" → 200）
        return sb.toString()
    }

    private fun status(rssKb: Long): String =
        "Name:\tnode\nUmask:\t0022\nState:\tR\nVmRSS:\t$rssKb kB\nThreads:\t1\n"

    private val monitor = ProcessMonitor(clockTicksPerSecond = 100)   // 1 jiffy = 10ms

    @Test
    fun `stat 解析取 utime stime 并折毫秒`() {
        assertEquals(500L, ProcessMonitor.parseStatCpuMillis(stat(50, 0), millisPerTick = 10))
        assertEquals(1_000L, ProcessMonitor.parseStatCpuMillis(stat(80, 20), millisPerTick = 10))
        Unit                                           // 显式收尾：void 返回值才被 JUnit5 视为测试
    }

    @Test
    fun `comm 含空格与括号仍能取对字段`() {
        // 从最后一个 ')' 之后切字段：comm = "((sd-pam))" 含括号与空格
        val text = "4321 ((weird name)) R 1 4321 4321 0 -1 4194560 0 0 0 0 100 200 0 0"
        assertEquals(3_000L, ProcessMonitor.parseStatCpuMillis(text, millisPerTick = 10))
        Unit                                           // 显式收尾：void 返回值才被 JUnit5 视为测试
    }

    @Test
    fun `缺 utime stime 字段如实回 null`() {
        assertNull(ProcessMonitor.parseStatCpuMillis("4321 (node) R 1 4321 4321", millisPerTick = 10))
        assertNull(ProcessMonitor.parseStatCpuMillis("", millisPerTick = 10))
        Unit                                           // 显式收尾：void 返回值才被 JUnit5 视为测试
    }

    @Test
    fun `VmRSS 折字节；缺行回 0`() {
        assertEquals(64L * 1024 * 1024, ProcessMonitor.parseRssBytes(status(64L * 1024)))
        assertEquals(0L, ProcessMonitor.parseRssBytes("Name:\tnode\nThreads:\t1\n"))
        Unit                                           // 显式收尾：void 返回值才被 JUnit5 视为测试
    }

    @Test
    fun `两次采样的 CPU 差分 = 用量除以墙钟间隔`() {
        val m = ProcessMonitor(100)
        val first = m.sample(
            pid = 4242, status = EngineStatus.RUNNING, sinceHeartbeatMillis = 100, atMillis = 1_000,
            statReader = ProcessMonitor.statReaderOf(stat(utime = 100, stime = 0)),   // 1000ms CPU
            statusReader = ProcessMonitor.statusReaderOf(status(1_024)),
        )
        assertNotNull(first)
        assertEquals(1_024L * 1024, first!!.rssBytes, "RSS 原样折字节")

        val second = m.sample(
            pid = 4242, status = EngineStatus.RUNNING, sinceHeartbeatMillis = 200, atMillis = 2_000,
            statReader = ProcessMonitor.statReaderOf(stat(utime = 150, stime = 0)),   // +500ms CPU / 1000ms 墙钟
            statusReader = ProcessMonitor.statusReaderOf(status(1_024)),
        )
        assertNotNull(second)
        assertEquals(50.0, second!!.cpuPercent, "500ms / 1000ms = 50%")
        assertEquals(200L, second.heartbeatMillis, "心跳间隔由调用方记账，采样器只透传")
        Unit                                           // 显式收尾：void 返回值才被 JUnit5 视为测试
    }

    @Test
    fun `首采样不给假差分，cpuPercent 为 0`() {
        val s = monitor.sample(
            pid = 7, status = EngineStatus.RUNNING, sinceHeartbeatMillis = 0, atMillis = 1_000,
            statReader = ProcessMonitor.statReaderOf(stat(100, 0)),
            statusReader = ProcessMonitor.statusReaderOf(status(512)),
        )
        assertEquals(0.0, s!!.cpuPercent)
        Unit                                           // 显式收尾：void 返回值才被 JUnit5 视为测试
    }

    @Test
    fun `换了 pid 重新起步，不拿上个进程的计数做差分`() {
        val m = ProcessMonitor(100)
        m.sample(
            pid = 11, status = EngineStatus.RUNNING, sinceHeartbeatMillis = 0, atMillis = 1_000,
            statReader = ProcessMonitor.statReaderOf(stat(utime = 500, stime = 0)),
            statusReader = ProcessMonitor.statusReaderOf(status(1)),
        )
        val other = m.sample(
            pid = 22, status = EngineStatus.RUNNING, sinceHeartbeatMillis = 0, atMillis = 2_000,
            statReader = ProcessMonitor.statReaderOf(stat(utime = 100, stime = 0)),
            statusReader = ProcessMonitor.statusReaderOf(status(1)),
        )
        assertEquals(0.0, other!!.cpuPercent, "pid 变了：绝不做跨进程差分")
        Unit                                           // 显式收尾：void 返回值才被 JUnit5 视为测试
    }

    @Test
    fun `时钟回拨与 jiffies 回绕一律回 0，不产出无意义百分比`() {
        val m = ProcessMonitor(100)
        m.sample(
            pid = 5, status = EngineStatus.RUNNING, sinceHeartbeatMillis = 0, atMillis = 10_000,
            statReader = ProcessMonitor.statReaderOf(stat(utime = 100, stime = 0)),
            statusReader = ProcessMonitor.statusReaderOf(status(1)),
        )
        val rollback = m.sample(
            pid = 5, status = EngineStatus.RUNNING, sinceHeartbeatMillis = 0, atMillis = 9_000,
            statReader = ProcessMonitor.statReaderOf(stat(utime = 900, stime = 0)),
            statusReader = ProcessMonitor.statusReaderOf(status(1)),
        )
        assertEquals(0.0, rollback!!.cpuPercent, "墙钟回拨：不报 80%/负值")

        val wrap = m.sample(
            pid = 5, status = EngineStatus.RUNNING, sinceHeartbeatMillis = 0, atMillis = 11_000,
            statReader = ProcessMonitor.statReaderOf(stat(utime = 10, stime = 0)),
            statusReader = ProcessMonitor.statusReaderOf(status(1)),
        )
        assertEquals(0.0, wrap!!.cpuPercent, "计数回绕：重新起步而不是报负数")
        Unit                                           // 显式收尾：void 返回值才被 JUnit5 视为测试
    }

    @Test
    fun `proc 不可读采样回 null，调用方按无法度量处理`() {
        val s = monitor.sample(
            pid = 999_999, status = EngineStatus.RUNNING, sinceHeartbeatMillis = 0,
            statReader = { null },
            statusReader = { null },
        )
        assertNull(s, "读不到进程时不猜 0%、不伪造健康")
        Unit                                           // 显式收尾：void 返回值才被 JUnit5 视为测试
    }

    @Test
    fun `status 读不到只丢 RSS，CPU 样本仍成立`() {
        val s = monitor.sample(
            pid = 3, status = EngineStatus.RUNNING, sinceHeartbeatMillis = 100, atMillis = 1_000,
            statReader = ProcessMonitor.statReaderOf(stat(utime = 10, stime = 0)),
            statusReader = { null },
        )
        assertNotNull(s)
        assertEquals(0L, s!!.rssBytes, "内存路降级为不判定，不拖垮整份样本")
        Unit                                           // 显式收尾：void 返回值才被 JUnit5 视为测试
    }

    @Test
    fun `判定仍归 WatchdogPolicy，采样器不越权`() {
        val m = ProcessMonitor(100)
        val sample = m.sample(
            pid = 42, status = EngineStatus.RUNNING, sinceHeartbeatMillis = 2_000, atMillis = 1_000,
            statReader = ProcessMonitor.statReaderOf(stat(utime = 10, stime = 0)),
            statusReader = ProcessMonitor.statusReaderOf(status(600L * 1024)),
        )!!
        // 采样器自己不给裁决；裁决只看 policy 的阈值口径
        val verdict = WatchdogPolicy().evaluate(sample)
        assertTrue(verdict is WatchdogVerdict.Kill, "RSS 600MB ≥ 512MB 硬阈值：由 policy 判杀，非采样器")
        Unit                                           // 显式收尾：void 返回值才被 JUnit5 视为测试
    }

    @Test
    fun `forget 后 pid 复用不背旧账`() {
        val m = ProcessMonitor(100)
        // 第一轮：pid 88 用掉 1000ms CPU
        m.sample(
            pid = 88, status = EngineStatus.RUNNING, sinceHeartbeatMillis = 0, atMillis = 1_000,
            statReader = ProcessMonitor.statReaderOf(stat(utime = 100, stime = 0)),
            statusReader = ProcessMonitor.statusReaderOf(status(1)),
        )
        // 该 run 终结 → 调用方 forget
        m.forget(88)

        // pid 88 被 OS 复用给新进程：新进程累计 20ms（不是接着 1000ms）
        val reused = m.sample(
            pid = 88, status = EngineStatus.RUNNING, sinceHeartbeatMillis = 0, atMillis = 2_000,
            statReader = ProcessMonitor.statReaderOf(stat(utime = 2, stime = 0)),
            statusReader = ProcessMonitor.statusReaderOf(status(1)),
        )
        assertEquals(0.0, reused!!.cpuPercent, "忘记基线后是首采样：不拿旧进程的计数做分母")

        // 下一轮才重新起步算差分：+20ms / 1000ms = 2%
        val after = m.sample(
            pid = 88, status = EngineStatus.RUNNING, sinceHeartbeatMillis = 0, atMillis = 3_000,
            statReader = ProcessMonitor.statReaderOf(stat(utime = 4, stime = 0)),
            statusReader = ProcessMonitor.statusReaderOf(status(1)),
        )
        assertEquals(2.0, after!!.cpuPercent)
        Unit                                           // 显式收尾：void 返回值才被 JUnit5 视为测试
    }

    @Test
    fun `forget 未知 pid 幂等，不影响其他 pid 的基线`() {
        val m = ProcessMonitor(100)
        m.sample(
            pid = 1, status = EngineStatus.RUNNING, sinceHeartbeatMillis = 0, atMillis = 1_000,
            statReader = ProcessMonitor.statReaderOf(stat(utime = 100, stime = 0)),
            statusReader = ProcessMonitor.statusReaderOf(status(1)),
        )
        m.sample(
            pid = 2, status = EngineStatus.RUNNING, sinceHeartbeatMillis = 0, atMillis = 1_000,
            statReader = ProcessMonitor.statReaderOf(stat(utime = 100, stime = 0)),
            statusReader = ProcessMonitor.statusReaderOf(status(1)),
        )
        m.forget(999)                                  // 从未采过：空操作不抛
        m.forget(1)                                    // 只忘记 pid 1

        val second = m.sample(
            pid = 2, status = EngineStatus.RUNNING, sinceHeartbeatMillis = 0, atMillis = 2_000,
            statReader = ProcessMonitor.statReaderOf(stat(utime = 200, stime = 0)),
            statusReader = ProcessMonitor.statusReaderOf(status(1)),
        )
        assertEquals(100.0, second!!.cpuPercent, "pid 2 的基线未被无关的 forget 抹掉：+1000ms/1000ms 墙钟")
        Unit                                           // 显式收尾：void 返回值才被 JUnit5 视为测试
    }
}
