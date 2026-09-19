package com.autoscript.appservice.runtime

/**
 * 心跳账本（docs §8.4 缺口②的**宿主侧收单方**：引擎进程打到宿主的每一次心跳在此记账）。
 *
 * 为什么必须有这个东西：看门狗的心跳失联一路判定的是「距上次心跳多久」，
 * 而这个"距上次"必须由**引擎进程**的打点来推进。此前 [EngineWatchdog] 只能拿
 * `heartbeatMillis` 缝由调用方喂，缺省 null —— 因为根本没有人收单、没有地方可问。
 * 本类就是那个"问得到"的地方：谁打点谁记账，看门狗只问不猜。
 *
 * 三条诚实口径：
 * 1. **从未打点的 run 回 null，不回 0**。0 = "刚刚打过"，会让失联判定永远不触发；
 *    null = "量不到"，调用方据此如实记账（[EngineWatchdog.Tick.noHeartbeat]）。
 * 2. **过期序列号不刷新时间戳**（§8.4「心跳携带自回事务序列号」）。宿主繁忙时一条
 *    旧心跳可能延迟到达；若它也能刷新时间戳，一个**已经死了**的引擎就会靠积压的旧心跳
 *    一直"活着"——那正是心跳这一路要抓的形态。过期的seq记 [staleBeats]（诊断可见），
 *    但不推进时间戳。
 * 3. **进程时间戳不由本类生成**。本类只记"收到"的时刻与引擎给的序号；
 *    `runId → 引擎进程 pid` 的归属在 [RuntimeController] 在途账里，不在此另建一张表。
 */
class HeartbeatLedger(
    /** 收录墙钟（默认系统钟；测试用给定值时钟复现失联临界）。 */
    private val clock: Clock = Clock { System.currentTimeMillis() },
    /** 记账上限：超过即淘汰最旧条目（防无主 runId 无限堆积；正常路径由 [forget] 收口）。 */
    private val maxRuns: Int = MAX_TRACKED_RUNS,
) {
    /** 墙钟缝（与 [EngineWatchdog.Clock] 同形状：调度侧自己算心跳间隔）。 */
    fun interface Clock { fun nowMillis(): Long }

    private val lock = Any()

    /** runId → 该 run 最后一次**有效**心跳（收到墙钟 + 引擎序号）。插入序 = 首次打点序。 */
    private val beats = LinkedHashMap<Long, Beat>()

    /** 因序号过期/重复被拒的心跳数（诊断：持续增长 = 乱序或重发，不是"活着"）。 */
    private var staleCount: Long = 0

    private data class Beat(val atMillis: Long, val seq: Long)

    /**
     * 记一次心跳。
     *
     * @param seq 引擎侧自增的序列号；必须**大于**本 run 上次记录的序号才会刷新时间戳
     *   （见口径 2）。相等的序号视为重复帧，同样不刷新。
     * @return 本次心跳是否被采纳（false = 过期/重复，见 [staleBeats]）。
     */
    fun beat(runId: Long, seq: Long): Boolean {
        val now = clock.nowMillis()
        synchronized(lock) {
            val prev = beats[runId]
            if (prev != null && seq <= prev.seq) {
                staleCount++
                return false
            }
            beats[runId] = Beat(now, seq)
            pruneLocked()
            return true
        }
    }

    /** 距上次**有效**心跳的毫秒；该 run 从未打过点（或已 [forget]）→ null。 */
    fun sinceLastBeat(runId: Long): Long? = synchronized(lock) {
        beats[runId]?.let { now(it) }
    }

    /** 该 run 最后收到的序号（诊断用；从未打点 → null）。 */
    fun lastSeq(runId: Long): Long? = synchronized(lock) { beats[runId]?.seq }

    /** 丢弃该 run 的心跳账（run 终结时调用：不丢 = 下一轮复用 runId 时背上一段"假年轻"）。 */
    fun forget(runId: Long) {
        synchronized(lock) { beats.remove(runId) }
    }

    /** 在册 runId 数（诊断/单测）。 */
    fun trackedRuns(): Set<Long> = synchronized(lock) { beats.keys.toSet() }

    /** 被拒的过期/重复心跳累计（诊断）。 */
    fun staleBeats(): Long = synchronized(lock) { staleCount }

    /** 清空全部账本（重启用/单测隔离用）。 */
    fun reset() {
        synchronized(lock) {
            beats.clear()
            staleCount = 0
        }
    }

    /**
     * 距上次心跳毫秒；**不回负值**（时钟回拨/管理员改表）。
     *
     * 负间隔对心跳一路是"看似安全"（永不超阈），与 [ProcessMonitor] 对回拨一律回 0.0%
     * 同一口径：给不出可信的时长就不给时长。宁可让 CPU/RSS 两路继续兜底，也不让一个
     * 负数把"引擎还活着"这件事讲圆。
     */
    private fun now(b: Beat): Long = (clock.nowMillis() - b.atMillis).coerceAtLeast(0L)

    /** 超编淘汰最旧条目（LinkedHashMap 迭代序 = 插入序；首条不会被本次新写入淘汰）。 */
    private fun pruneLocked() {
        if (beats.size <= maxRuns) return
        val oldest = beats.keys.firstOrNull() ?: return
        beats.remove(oldest)
    }

    companion object {
        /** 记账上限：池容量 × 同时在途 run 数是常态，128 已留足余量。 */
        const val MAX_TRACKED_RUNS: Int = 128
    }
}
