package com.autoscript.appservice.runtime

import com.autoscript.domain.engine.StopResult
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 一个槽位的**占用凭证**（2026-10-12 批 103 P2 重构；外审 B2 的彻底解）。
 *
 * ## 为什么要有它（替换掉什么）
 *
 * 原实现有**两套靠手拿保持同步的记账**：`Semaphore permits`（计**数**）与
 * `PoolSlot.state`（计**状态**）—— 「有证但无 FREE 槽」与「槽 FREE 但无证可领」两种失真都
 * 是「忘了还另一半」的产物，而池一旦失真，现场是「引擎再不接活」或「同一引擎跑两个脚本」。
 * 批 96 的 `stopEpoch` 是**垫片**（为了让调用方不必整段串行化而加的第三套记账），
 * 不是解 —— 垫片越多，真源越糊。
 *
 * 现形态：**槽位空闲队列是唯一真源**（见 `FixedEnginePool.idle`），本类只量一件事：
 * **「这份占用是否已经被归还过」**（[returned]）。归还**恰好一次**由它保证，
 * 于是「先释放许可证再 quiesce，中途抛异常」与「killAll 强释 + 持有者迟到释放」
 * 这两条双重归还路径都收敛成 no-op。
 *
 * ## 三条不变量
 *
 * 1. **幂等归还**：[returned] 的 CAS 是唯一入口 —— 无论归还路径有多少条（正常 release /
 *    启动失败 recycle / kill 收归 / 调用方取消），槽位**最多**回队一次。
 * 2. **归还后持有者不得再动槽位**：[RuntimeController] 在归还之后只读
 *    `handle.slot.engine`（状态查询、诊断摘要），**不**再触发 quiesce/kill。
 * 3. **代次纪律（§7.4）在这里不再需要**：旧实现靠 `slot.generation` 判「这是不是过期句柄」。
 *    队列化之后**过期句柄在物理上就不可能** —— 一个槽位要么在队列里（= 空闲，可被取走），
 *    要么在某一份 lease 手里；两份 lease 同时持有同一槽位这件事由 [returned] 挡住。
 *
 * @param slot 本次占用的槽（宿主状态查询与诊断摘要仍经它，见
 *   `RuntimeController.probeStatus`/`statusOf`/`lastRunSummary`）
 * @param onReturn 归还动作（池提供：把槽放回空闲队列）—— **由池注入**，
 *   因为「怎么还」是池的内部形状，lease 只管「还几次」。
 */
class PoolLease internal constructor(
    val slot: PoolSlot,
    private val onReturn: (PoolLease) -> Unit,
) {
    private val returned = AtomicBoolean(false)

    /** 这份占用是否已被归还过（幂等判据；`true` 之后不得再动 [slot] 的生命周期）。 */
    val isReturned: Boolean get() = returned.get()

    /**
     * 归还（**恰好一次**）。@return true = 本次是首次归还；false = 此前已归还过（幂等 no-op）。
     *
     * 谁先归还谁负责执行 `onReturn` —— 于是「killAll 强释 + 持有者迟到释放」里，
     * 槽位只回队一次，另一次静默 no-op（不是「重复还」也不是「漏还」）。
     */
    fun returnOnce(): Boolean = if (returned.compareAndSet(false, true)) {
        onReturn(this)
        true
    } else {
        false
    }

    /**
     * 优雅释放（§8.3 四步 quiesce，TimedOut 由池 kill 兜底）后归还。
     *
     * **先 quiesce 再归还**（不是反过来）：归还是「这个槽位可以给别人用了」，而 quiesce
     * 是「它真的空了」。反过来会让下一个持有者与上一个的收尾重叠 —— 同一引擎进程上跑两个脚本。
     * quiesce 抛异常时**仍然归还**（`finally`）：不归还 = 池容量永久缩水，而那条异常
     * 由上层记账；池的职责是「不因为一次收尾失败而永久少一个槽」。
     */
    suspend fun release(): StopResult {
        var result: StopResult = StopResult.Clean
        try {
            if (!isReturned) result = slot.quiesce()
        } finally {
            returnOnce()
        }
        return result
    }
}
