package com.autoscript.appservice.runtime

import com.autoscript.domain.engine.EngineStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 看门狗调度循环（docs §8.4 的最后一块：**周期性**地把三个已就绪的部件接起来）。
 *
 * 三方分工不变（谁也不越权）：
 * - [ProcessMonitor] 只采样 `/proc`（CPU/RSS）；
 * - [WatchdogPolicy] 只裁决（阈值口径）；
 * - [RuntimeController] 持 kill 权威与在途表：本类经 [RuntimeController.watchAnchors] 取锚点、
 *   经 [RuntimeController.judge] 求裁决、经 [RuntimeController.killRun] 落裁决；
 * - 本类只做**调度与记账**：节奏多快、谁的样本进 history、哪个输入其实量不到、什么时候该
 *   [ProcessMonitor.forget]。
 *
 * 挂在调用方给的 [scope] 上（进程级：Application 的 SupervisorJob 域）。**没有 [scope]
 * 就没有生产调度循环** —— 看门狗绝不自决生命周期，谁装配谁决定它活多久。
 */
class EngineWatchdog(
    private val controller: RuntimeController,
    private val policy: WatchdogPolicy = controller.watchdogPolicy(),
    /** 采样循环的墙钟（默认系统钟）：测试用给定值时钟逐轮推进，即可复现真实节奏。 */
    private val clock: Clock = Clock { System.currentTimeMillis() },
    /** 无在途 run 时的轮询间隔：池空不必按采样周期空转。 */
    private val idlePollMillis: Long = DEFAULT_IDLE_POLL_MILLIS,
) {
    /**
     * 心跳来源（runId → 距上次心跳毫秒）；null = 该 run 量不到心跳。
     *
     * §8.4 缺口②：JS 侧心跳到达宿主的打点通道尚未建立，故**缺省 null** —— 缺了它看门狗
     * 只跑 CPU/RSS 两路，[Tick.noHeartbeat] 如实记账。
     *
     * 为什么必须由外部注入而不是本类自己记账：心跳要由**引擎进程**打点。拿看门狗自己的
     * 轮转周期当心跳，等于"我每秒问一次它还没死"—— 那不是心跳而是心跳的伪造，会静默
     * 关掉 §8.4 最要害的一路（死循环脚本心跳还活着、CPU 满载，只靠外带差分抓得到）。
     */
    private var heartbeatMillis: (Long) -> Long? = { null }

    /** 装配期注入心跳来源（轮转启动前调用；轮转中改 = 边跑边换口径，禁止）。 */
    fun withHeartbeat(source: (Long) -> Long?): EngineWatchdog {
        require(!isRunning()) { "轮转中不得更换心跳来源（口径必须稳定）" }
        heartbeatMillis = source
        return this
    }

    /** 默认 /proc 采样器（[withMonitor] 的缺省值）。 */
    private var monitor: ProcessMonitor = ProcessMonitor()

    /** 装配期注入采样器（测试替身/改 clockTicksPerSecond 走这里；轮转中禁止）。 */
    fun withMonitor(sampler: ProcessMonitor): EngineWatchdog {
        require(!isRunning()) { "轮转中不得更换采样器（基线会串）" }
        monitor = sampler
        return this
    }

    /** 墙钟缝：调度侧自己算心跳间隔，不与被采样进程的时钟耦合。 */
    fun interface Clock { fun nowMillis(): Long }

    /**
     * 单轮监督结果（诊断/单测；生产侧仅作日志）。
     *
     * 三个"量不到"清单是**分开**的，因为它们对应三种不同的接线缺口，混成一个
     * `unmeasurable` 会让"没人接 pid"和"没人接心跳"看起来像同一个问题：
     * - [noPid]：宿主没给 [com.autoscript.domain.engine.ScriptEngine.pid]；
     * - [noHeartbeat]：心跳来源未接线（[heartbeatMillis] 回 null）；
     * - [procUnreadable]：`/proc` 读不到（异 UID / 进程已退出）。
     */
    data class Tick(
        val sampled: Int,
        val killed: List<Long> = emptyList(),
        val noPid: List<Long> = emptyList(),
        val noHeartbeat: List<Long> = emptyList(),
        val procUnreadable: List<Long> = emptyList(),
        val forgotten: List<Int> = emptyList(),
    )

    private val book = LinkedHashMap<Int, RunBook>()
    private var job: Job? = null

    /** 已在轮转中（幂等：[start] 重复调用只保留第一个 job）。 */
    fun isRunning(): Boolean = job?.isActive == true

    /**
     * 启动调度循环。节奏 = [WatchdogPolicy.heartbeatIntervalMillis] —— 采样周期与
     * "心跳失联"阈值必须同源，否则 500ms 一跳的阈值配上 3s 的采样节奏会把活着的引擎
     * 判成失联（误杀）。这也是 [WatchdogPolicy] 把这个字段公开的原因。
     */
    fun start(scope: CoroutineScope) {
        val fresh = scope.launch { loop() }
        synchronized(this) {
            if (job?.isActive == true) {
                fresh.cancel()                     // 已有轮转：撤掉重复的那个
            } else {
                job = fresh
            }
        }
    }

    /** 停止轮转（幂等；不夺 [scope] 的所有权）。 */
    suspend fun stop() {
        val current = synchronized(this) {
            val it = job
            job = null
            it
        }
        current?.cancelAndJoin()
    }

    /** 丢弃全部 per-pid 记账，实例回到首轮状态（重启用/单测隔离用）。 */
    fun reset() {
        synchronized(book) { book.clear() }
    }

    private suspend fun loop() {
        // isActive 挂在 CoroutineContext 的 Job 上（kotlinx.coroutines.isActive 扩展属性）
        while (currentCoroutineContext().isActive) {
            if (tick().sampled == 0) delay(idlePollMillis)   // 池空：退避，别按采样周期空转
            delay(policy.heartbeatIntervalMillis)
        }
    }

    /**
     * 跑一轮监督（[loop] 的循环体，单独暴露给单测：不必为了验一轮起真协程）。
     *
     * 诚实口径，一条一条对着 §8.4：
     * - 量不到的输入**不喂值**：没有 pid / 没有心跳来源 / `/proc` 读不到，都如实记账并
     *   跳过该 run 本轮判定，绝不猜 0% 也绝不判死；
     * - 拿不到引擎状态（[RuntimeController.probeStatus] 回 null：已收走或引擎已死）→ 同样
     *   记账跳过。**不拿 RUNNING 兜底**：那不是"乐观"，是伪造一个会被 policy 当真的输入；
     * - pid 复用不背旧账：book 以 pid 为键但带 runId，pid 落到另一个 run 头上就整段清零；
     * - 收尾时对已不在途的 pid 调 [ProcessMonitor.forget]（含刚被 kill 的）——"kill 后立刻
     *   收尾"和"下轮才发现不在了"两条路都走这里，免得调用方漏调，看门狗自己保证。
     */
    suspend fun tick(): Tick {
        val anchors = controller.watchAnchors()
        val killed = mutableListOf<Long>()
        val noPid = mutableListOf<Long>()
        val noHeartbeat = mutableListOf<Long>()
        val procUnreadable = mutableListOf<Long>()
        val forgotten = mutableListOf<Int>()

        for (anchor in anchors) {
            val pid = anchor.pid
            if (pid == null) {
                noPid += anchor.runId
                continue
            }
            val beat = heartbeatMillis(anchor.runId)
            if (beat == null) {
                noHeartbeat += anchor.runId
                continue
            }
            val status = controller.probeStatus(anchor.runId)
            if (status == null) {
                procUnreadable += anchor.runId      // 引擎状态不可得：本轮不判（原因见 KDoc）
                continue
            }
            val state = bookFor(pid, anchor.runId)
            val sample = monitor.sample(pid, status, beat, clock.nowMillis())
            if (sample == null) {
                procUnreadable += anchor.runId
                continue
            }
            val verdict = controller.judge(sample, state.historyFor(sample))
            if (verdict is WatchdogVerdict.Kill) {
                controller.killRun(anchor.runId, verdict.cause)   // kill 权威在 controller
                killed += anchor.runId
            }
            state.push(sample)
        }

        // 收尾：对**已不在途**的 pid 丢弃记账 + [ProcessMonitor.forget]。
        // "在途"= [controller.watchAnchors] 的本轮快照，**减去本轮刚被杀的 runId**：
        // killRun 已把被杀 run 摘出在途表，但快照是 kill 前取的，所以它还挂在里面 ——
        // 不过滤掉就会漏 forget，而被杀进程随时可能退出、pid 随即被 OS 复用。
        val live = anchors.mapNotNull { it.pid }.toSet()
        val stillLive = live - anchors.filter { it.runId in killed }.mapNotNull { it.pid }.toSet()
        val stale = synchronized(book) {
            val gone = book.keys.filter { it !in stillLive }
            gone.forEach { book.remove(it) }
            gone
        }
        stale.forEach { monitor.forget(it) }
        forgotten += stale

        return Tick(
            sampled = anchors.size,
            killed = killed,
            noPid = noPid,
            noHeartbeat = noHeartbeat,
            procUnreadable = procUnreadable,
            forgotten = forgotten,
        )
    }

    /**
     * 取该 pid 的记账段；pid 落到**另一个 run** 头上就整段清零（§8.4 pid 复用防串味）。
     *
     * 为什么必须按 runId 判：`forget` 只在"pid 已不在途"时触发。若上一个 run 刚终态、新 run
     * 立刻复用了同一个 pid（OS 复用 pid 是常态），book 里还留着上个 run 的 CPU 连段 ——
     * 新进程会凭空继承一段"高 CPU 历史"，可能差几个采样就被判成持续风暴（误杀）。
     */
    private fun bookFor(pid: Int, runId: Long): RunBook = synchronized(book) {
        val existing = book[pid]
        if (existing != null && existing.runId == runId) {
            existing
        } else {
            RunBook(runId, pid).also { book[pid] = it }
        }
    }

    /** per-pid+run 记账：CPU 连段（喂 [WatchdogPolicy] 的 history）。 */
    private class RunBook(val runId: Long, val pid: Int) {
        private val samples = ArrayDeque<WatchdogSample>()

        fun push(sample: WatchdogSample) {
            samples.addLast(sample)
            while (samples.size > MAX_HISTORY) samples.removeFirst()
        }

        /** 连段判定用的历史（同 pid 的近段；不含 [current] 本身，policy 会自己拼上）。 */
        fun historyFor(current: WatchdogSample): List<WatchdogSample> =
            samples.filter { it.pid == current.pid }.takeLast(MAX_HISTORY)
    }

    companion object {
        /** 池空时的轮询间隔：不必按采样周期空转，但要能及时接上第一个新 run。 */
        const val DEFAULT_IDLE_POLL_MILLIS: Long = 1_000

        /** 回喂 policy 的历史长度上限（CPU 窗口 30s / 500ms = 60 段，留一倍余量）。 */
        const val MAX_HISTORY: Int = 128
    }
}
