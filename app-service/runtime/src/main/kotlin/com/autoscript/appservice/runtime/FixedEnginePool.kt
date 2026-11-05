package com.autoscript.appservice.runtime

import com.autoscript.domain.core.Clock
import com.autoscript.domain.core.SystemClock
import com.autoscript.domain.engine.EngineId
import com.autoscript.domain.engine.EngineRunRequest
import com.autoscript.domain.engine.KillCause
import com.autoscript.domain.engine.ScriptEngine
import com.autoscript.domain.engine.StopResult
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 定容引擎进程池（docs §8.2 进程池决议）。
 *
 * ## 唯一真源：**空闲槽位队列**（ 重构；外审 B2 的彻底解）
 *
 * 原实现有两套靠手拿同步的记账 —— `Semaphore permits`（计**数**）与 `PoolSlot.state`
 * （计**状态**）。两套的一致性全靠「取/还都要记得另一半」，于是四种失真全是「忘了还
 * 另一半」的产物：
 * - 有证无 FREE 槽 → [acquire] 拿不到槽却占着许可（池永久卡死一半容量）；
 * - 槽 FREE 但无证可领 → 「引擎再不接活」（现场症状，极难查）；
 * - 双重归还 → 超发一证、两个占用者并存（**同一引擎进程跑两个脚本**，违反 §8.2）；
 * - 归还了但槽仍在用 → 收尾与下一次启动重叠。
 *
 * 现在**空闲队列就是全部**（[idle]）：队列里就是可用的，出队就是被占。一个槽位
 * **要么在队列里、要么在某一份 [PoolLease] 手里** —— 这两条互斥，而「归还是否发生过」由
 * lease 的 CAS 保证，于是上面前三种失真在**结构上**不再可能（第四种由「先 quiesce 后归还」
 * 保证，见 [PoolLease.release]）。
 *
 * ## 并发约定（ 定，本批未改）
 *
 * 排队等槽可能挂到 `waitTimeoutMillis`（调度链路 60–120s），所以**不得**把它圈进调用方
 * （`RuntimeController`）的锁里 —— 那是外审 B1 的病灶（实测 `stop(A)` 挡 2927ms）。
 * 队列化之后本类自己扛并发，靠两件：
 * 1. **[stopEpoch]**（急停代次）：[killAll] 推进一步；抢槽路径在「等完槽夺槽前」与
 *    「execute 返回后」两处对表 —— 它量的是**「急停跨过了排队窗口」**，与槽位真源正交，
 *    队列化**删不掉**它。
 * 2. **队列操作本身是原子的**：`Channel` 的收发即临界区，取代原 `stateLock`。
 *
 * ## 与 §7.4 generation 纪律的关系
 *
 * 原实现靠 `slot.generation` 判「这是不是过期句柄」（旧句柄 release 不得拆新占用者）。
 * 队列化之后**过期句柄在物理上不可能** —— 旧持有者手里是一份**已归还**的 lease，它的
 * [PoolLease.returnOnce] 恒为 no-op，压根到不了槽位。`generation` 只剩诊断价值
 * （[recycle] 与归还时推进），不再承担正确性。
 */
class FixedEnginePool(
    engineFactory: (EngineId) -> ScriptEngine,
    override val capacity: Int,
    private val clock: Clock = SystemClock,
) : EnginePool {

    init {
        require(capacity > 0) { "池容量必须 > 0: $capacity" }
    }

    private val slots: List<PoolSlot> =
        List(capacity) { PoolSlot(it, engineFactory(EngineId(it)), clock) }

    /**
     * **唯一真源**：空闲槽位队列。初始装满（全部空闲）；[acquire] 从这里取，
     * [PoolLease] 归还时往这里放。
     *
     * `Channel(capacity)` 而非 `UNLIMITED`：有界队列让「重复归还」（记账 bug）表现为
     * `trySend` 失败（可观测），而 `UNLIMITED` 下它会**静默地把队列撑大** ——
     * 那正是本次要消灭的那类失真。
     */
    private val idle = Channel<PoolSlot>(capacity).also { ch -> slots.forEach { ch.trySend(it) } }

    /**
     * 在途占用数（[stats] 的读面）。
     *
     * **它不是记账的第二真源** —— 记账的真源只有 [idle]；这个数是「有多少 lease 未归还」，
     * 由 lease 归还那一侧减、由夺槽那一侧加，两处都在同一条 lease 生命周期内
     * （归还幂等 ⇒ 减也幂等）。它存在的理由：[Channel] 没有「不消费地窥视长度」的手段，
     * 而 `stats()` 是 UI 的读面（池统计卡片），不能为了读它去做一次收发。
     */
    private val inUse = AtomicInteger(0)

    /** 槽 → 当前未归还的 lease（[recycle] 要经它归还；池自己产的，出 lease 时登记）。 */
    private val leases = ConcurrentHashMap<PoolSlot, PoolLease>()

    /** 急停代次（[killAll] 推进；读在 [acquire] 的两处对表点）。 */
    @Volatile
    private var stopEpoch: Long = 0L

    override suspend fun acquire(request: PoolAcquireRequest): PoolAcquireOutcome {
        // 等槽前先记代次：这一步可能挂到 waitTimeoutMillis，期间完全可能与 killAll 并发。
        val epochAtEntry = stopEpoch

        val slot: PoolSlot = if (request.waitTimeoutMillis == null) {
            idle.receive()
        } else {
            withTimeoutOrNull(request.waitTimeoutMillis) { idle.receive() }
                ?: return PoolAcquireOutcome.TimedOut
        }

        // 等槽期间急停：本条作废，立刻还槽（不占槽、不起进程）。
        if (stopEpoch != epochAtEntry) {
            slot.markFree()
            idle.trySend(slot)
            return PoolAcquireOutcome.Failed(ABORTED_BY_STOP)
        }

        // 夺槽必须在 execute **之前**完成：否则 execute 的真实启动耗时就是窗口期 ——
        // 并发 acquire 会同时选中同一 FREE 槽，同一引擎进程跑两个脚本（违反 §8.2 每脚本一进程）。
        // 队列化后这一步只有一个出队动作，**不再有「取到了却没占上」的中间态**。
        //
        // markBusy 抛 = 槽位记账已坏（markFree 的残态探测此前必已响过一次 —— 那是根因，
        // 这里是其余波）。槽**必须回队再抛**：出队即「被占」，不回队就是容量永久 -1
        // 且现场没有任何日志指向它 —— 那正是本类 KDoc 说的最坏失真。回队后这条残态槽
        // 每次被夺都会在同一处再响一次（失败留在明处、轮得到别的槽不受影响）。
        try {
            slot.markBusy(clock.nowMillis())
        } catch (e: Exception) {
            check(idle.trySend(slot).isSuccess) {
                "槽 ${slot.index} 回队失败：空闲队列已满（结构性双重归还）"
            }
            throw e
        }
        slot.occupy()
        val lease = newLease(slot)
        inUse.incrementAndGet()

        return try {
            val receipt = slot.engine.execute(
                EngineRunRequest(
                    projectId = request.projectId,
                    scriptPath = request.scriptPath,
                    args = request.args,
                    runNonce = request.runNonce,        // 幂等锚点透传执行体（§8.5）
                    timeoutMillis = request.scriptTimeoutMillis,
                    // 授权快照原样下传（A5，§11）：引擎不重算，只把它交给身份签发方。
                    authorization = request.authorization,
                )
            )
            if (stopEpoch != epochAtEntry) {
                // 启动期间被 killAll 强释：这条执行体已不属于任何人 —— 当场收掉，
                // **绝不 markRunning**（那会在 IDLE 上抛非法状态转移，且把一条账外野进程
                // 留在设备上）。归还走 lease（幂等）：槽回队列，执行体另收。
                killQuietly(slot)
                lease.forceReturn(KillCause.ENGINE_REQUEST)   // BOOTING → CRASHED → IDLE → 回队
                PoolAcquireOutcome.Failed(ABORTED_BY_STOP)
            } else {
                slot.markRunning()           // execute 返回 = 进程已拉起：BOOTING → RUNNING（§8.3）
                PoolAcquireOutcome.Granted(PoolHandle(request, lease, receipt))
            }
        } catch (e: CancellationException) {
            // 取消也是异常收场：BOOTING → CRASHED → IDLE → 回队
            lease.forceReturn(KillCause.ENGINE_REQUEST)
            throw e                               // 取消语义照常向上传播
        } catch (e: Exception) {
            // 启动失败：状态机仍在 BOOTING（execute 没返回 → 没有 markRunning）——
            // 归还前必须先走 [reuse] 把它落到 CRASHED→IDLE，否则 [PoolSlot.markFree] 的
            // 「归还时状态机必在 STOPPED/CRASHED」断言会响亮失败（那条断言是有意的：
            // 它正是「归还路径漏了 quiesce/reuse」的探测器）。
            lease.forceReturn(KillCause.ENGINE_REQUEST)   // 启动失败：BOOTING → CRASHED → IDLE → 回队
            PoolAcquireOutcome.Failed(e.message ?: (e::class.simpleName ?: "engine start failed"))
        }
    }

    /**
     * 产一份 lease 并登记；它的回队回调是**唯一的槽位回队口**（[PoolLease] 只管「谁有权
     * 归还」，具体怎么还由本类给 —— 与/99 的 `T1TreeOps`/`TreeOps` 同一分工）。
     */
    private fun newLease(slot: PoolSlot): PoolLease {
        val lease = PoolLease(slot) { returnedSlot ->
            leases.remove(returnedSlot)
            inUse.decrementAndGet()
            // 回队是「池不缩水」的最后一道：markFree 的探测（响亮失败）再响也不能
            // 跳过它 —— 探测红的代价是一次异常，跳过回队的代价是容量永久 -1
            // （现场只剩「引擎再不接活」，没有任何日志指向它）。
            try {
                returnedSlot.markFree()
            } finally {
                // 有界队列的 trySend 失败 = 结构性双重归还（同号槽进了队列第二份）。
                // 本类 KDoc 说它要「可观测」——吞掉返回值就什么都观测不到，当场响亮。
                check(idle.trySend(returnedSlot).isSuccess) {
                    "槽 ${returnedSlot.index} 回队失败：空闲队列已满（结构性双重归还）"
                }
            }
        }
        leases[slot] = lease
        return lease
    }

    /**
     * 收掉一条执行体而**不让「收尸失败」外溢**（[acquire] 的急停分支与 [killAll] 的
     * 全清共用）：`ScriptEngine.kill()` 没承诺不抛，而两条调用路径要的都是
     * 「已经死了/收不动都不阻断归还路径」—— 取消照常穿透，其余异常咽下（
     * 收不动多半意味着进程已随 killAll 死掉，收尸失败不改变「这条作废/全清继续」的结论）。
     */
    private suspend fun killQuietly(slot: PoolSlot) {
        try {
            slot.engine.kill()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 进程可能已随 killAll 死掉：收尸失败不改变「本条作废」这个结论。
        }
    }

    override suspend fun release(handle: PoolHandle): StopResult = handle.lease.release()

    override suspend fun killAll(reason: KillCause) {
        // 代次推进先于强释：并发的 acquire 要么在夺槽前看到新代次（直接作废并还槽），
        // 要么在 execute 返回后看到代次变了（走 killQuietly 那条），不存在第三种。
        stopEpoch += 1
        // 队列里的都是空闲槽（不必杀、也不改变容量）；在途的槽逐个强杀并**强制归还**
        // （lease 幂等 ⇒ 持有者随后的迟到 release 是 no-op，不会双重归还）。
        // 快照 keys：循环体里会 remove 元素，直接遍历 ConcurrentHashMap 弱一致但难推理。
        leases.keys.toList().forEach { slot ->
            val lease = leases[slot] ?: return@forEach
            try {
                // 经 killQuietly：`ScriptEngine.kill()` 没承诺不抛（真实现是
                // destroyForcibly + waitFor，后者可抛 InterruptedException）——
                // 单槽收尸失败不得**中断全清**（剩下的槽还要杀、要还），
                // 与 [acquire] 急停分支同一条「收尸失败不改变结论」纪律。
                killQuietly(slot)
            } finally {
                // 强释走 lease：状态机复位 + 回队一次；持有者随后的 release 是 no-op。
                // **不等 quiesce** —— 强释要的就是「立刻不再用」，等收尾反而让急停卡住。
                // **归还放在 finally**：kill 抛错（含被取消）跳过归还的代价是槽永久
                // 扣在循环外 —— 容量 -1 且现场只剩「引擎再不接活」，与 [PoolLease]
                // 自己的 try/finally 同一条「池不缩水优先」纪律。
                lease.forceReturn(reason)
            }
        }
    }

    override fun stats(): PoolStats {
        val busy = inUse.get().coerceIn(0, capacity)
        return PoolStats(capacity = capacity, free = capacity - busy, busy = busy)
    }

    /**
     * 看门狗 kill 后的槽位收归（§8.4 kill → 重分配）：走 lease 归还，**幂等**
     * （重复收归是 no-op，不超发也不漏发 —— 这正是队列化要消灭的那类失真）。
     *
     * @param cause 收归原因（§8.3 状态机归类）：watchdog/OOM 原因 → CRASHED；null 或
     *   [KillCause.REQUESTED] 在 RUNNING/QUIESCING 收归 → STOPPED，BOOTING（启动失败
     *   走本方法收口）→ CRASHED —— 没跑起来的「主动收」不伪造干净停。
     */
    override fun recycle(slot: PoolSlot, cause: KillCause?) {
        val lease = leases[slot] ?: return   // 已归还/从未占用：幂等 no-op（不再手工还许可）
        lease.forceReturn(cause ?: KillCause.REQUESTED)   // 状态机归类 + 复位 + 唯一回队口
    }

    companion object {
        /**
         * 等槽/启动期间撞上急停的如实回话（[acquire] → [PoolAcquireOutcome.Failed]）：
         * 不是"引擎起不来"，而是"这条启动已被急停作废" —— 两者在诊断上必须分得开。
         */
        const val ABORTED_BY_STOP: String = "启动期间宿主急停（killAll/forceStopAll）：本次执行已作废"
    }
}
