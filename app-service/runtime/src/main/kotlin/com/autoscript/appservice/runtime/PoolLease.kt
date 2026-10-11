package com.autoscript.appservice.runtime

import com.autoscript.domain.engine.StopResult
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 一个槽位的**占用凭证**（2026-10-12 批 103 P2 重构；外审 B2 的彻底解）。
 *
 * ## 为什么要有它（替换掉什么）
 *
 * 原实现有**两套靠手拿保持同步的记账**：`Semaphore permits`（计**数**）与
 * `PoolSlot.state`（计**状态**）—— 「有证但无 FREE 槽」与「槽 FREE 但无证可领」两种失真
 * 都是「忘了还另一半」的产物，而池一旦失真，现场是「引擎再不接活」或「同一引擎跑两个脚本」。
 * 批 96 的 `stopEpoch` 是**垫片**（为了让调用方不必整段串行化而加的第三套记账），不是解。
 *
 * 现形态：**槽位空闲队列是唯一真源**（`FixedEnginePool.idle`），本类只量两件事：
 * **归还权有没有被独占**（[returned] 的 CAS）与**槽确已腾空后回队**（[giveBack]）。
 *
 * ## 归还的顺序：三种排列各错一样（批 103 的交错测试逼出来的）
 *
 * 1. **不能「先 quiesce 再归还」**：quiesce 自己会 `state = FREE`；若它执行期间另一个
 *    acquire 从队列取走了槽，本类随后的回队会把**别人的**槽再塞进队列 —— 队列里出现两个
 *    同号槽，容量记账当场失真。
 * 2. **不能「先 CAS 再 quiesce」**：CAS 赢家立刻把槽交还队列，而 quiesce 还没跑 ——
 *    下一个持有者拿到一个仍在跑上一个脚本的引擎。
 * 3. **所以是「CAS 独占 → quiesce → 回队」**（[release]）：CAS 保证只有一个赢家能碰槽；
 *    quiesce 保证槽确实空了；回队发生在两者之后。代价是归还要等 quiesce，而 [release]
 *    本来就是挂起的。
 *
 * ## 三条不变量
 *
 * 1. **归还恰好一次**：claim 的 CAS 是唯一入口；无论归还路径有多少条（正常 release /
 *    启动失败 recycle / kill 收归 / 调用方取消），槽位**最多**回队一次。
 * 2. **强释路径不经 quiesce**：[FixedEnginePool.killAll] 走 [forceReturn]（状态机强杀复位
 *    + 直接回队），不等待引擎收尾 —— 它要的就是「立刻不再用」。
 * 3. **代次纪律（§7.4）在这里不再需要**：旧持有者手里的 lease 已归还，它的 [release]
 *    恒为 no-op，**物理上到不了槽位**。
 */
class PoolLease internal constructor(
    val slot: PoolSlot,
    /** 回队动作（池提供：把**已腾空**的槽放回空闲队列）—— 由池注入，因为「怎么还」是池的内部形状。 */
    private val giveBackSlot: (PoolSlot) -> Unit,
) {
    private val returned = AtomicBoolean(false)

    /** 这份占用是否已被归还过（幂等判据；`true` 之后不得再动 [slot] 的生命周期）。 */
    val isReturned: Boolean get() = returned.get()

    /**
     * 归还（**恰好一次**）：CAS 独占 → quiesce → 回队。
     *
     * quiesce 抛异常时**仍然回队**（`finally`）：不回队 = 池容量永久缩水，而那条异常由上层
     * 记账；池的职责是「不因为一次收尾失败而永久少一个槽」。
     */
    suspend fun release(): StopResult {
        if (!claim()) return StopResult.Clean
        return try {
            slot.quiesce()
        } finally {
            giveBack()
        }
    }

    /**
     * **强释归还**（[FixedEnginePool.killAll] 用）：不等引擎收尾，直接按 cause 复位状态机
     * 并回队 —— 强释要的就是「立刻不再用」，等 quiesce 反而让急停卡在引擎的收尾上。
     *
     * 与 [release] 抢的是**同一个 CAS**：谁先到谁做，另一条是 no-op。于是
     * 「killAll 强释 + 持有者迟到 release」里槽只回队一次。
     */
    fun forceReturn(cause: com.autoscript.domain.engine.KillCause) {
        if (!claim()) return
        slot.forceFree(cause)
        giveBack()
    }

    private fun claim(): Boolean = returned.compareAndSet(false, true)

    /** 槽确已腾空（或已被强释复位）后回队。幂等由 claim 保证只有一个调用者。 */
    private fun giveBack() {
        giveBackSlot(slot)
    }
}
