package com.autoscript.appservice.runtime

import com.autoscript.domain.core.Clock
import com.autoscript.domain.engine.EngineStateMachine
import com.autoscript.domain.engine.EngineStatus
import com.autoscript.domain.engine.KillCause
import com.autoscript.domain.engine.ScriptEngine
import com.autoscript.domain.engine.StopResult

/** 池槽位（docs §8.3 每执行单元状态机在池侧的最小投影：FREE/BUSY/QUIESCING）。 */
enum class SlotState { FREE, BUSY, QUIESCING }

class PoolSlot internal constructor(
    val index: Int,
    val engine: ScriptEngine,
    private val clock: Clock,
) {
    var state: SlotState = SlotState.FREE
        private set

    /**
     * 每执行单元的 `EngineStatus` 投影（§8.3 接线：`:domain` 的 [EngineStateMachine] 现在
     * 真正驱动本槽位，而不是只被自己的单测驱动）。
     *
     * 为什么挂在 [PoolSlot] 上而不是另建一张表：状态机必须与「占槽/回收」同生共死，
     * 分表就得处理「表里有行、槽位已 FREE」的孤儿清理；而 [FixedEnginePool] 的
     * stateLock 已保证这里的读写与记账原子。非法转移抛 [IllegalStateTransition]：
     * 状态机跳变是**响亮失败**，绝不静默修一个看似合理的状态。
     */
    private val statusMachine = EngineStateMachine(EngineStatus.IDLE)

    var busySinceMillis: Long = 0L
        private set

    /**
     * 占位代次（docs §7.4 generation 纪律）：每次夺槽 / kill 收归都 +1。
     *
     * **这段 KDoc 曾经写着「[PoolHandle] 获取时记下代次；release 时对不上即过期句柄」，
     * 那两句现在都不成立**（2026-10-12 订正，批 103 P2 之后）：[PoolHandle] 根本不再
     * 持有代次，而 release 也**不再拿它判过期** —— 过期判据换成了 [PoolLease] 的 CAS
     * 归还权（归还恰好一次），过期句柄在**物理上**到不了本槽位。
     *
     * 现在这个计数**只剩诊断价值**（`recycle` 与归还时推进，能看出这个槽被占用过几轮），
     * 不承担正确性。删不掉是因为 §7.4 的代次纪律要留个可观测的痕迹，但**谁要拿它判过期
     * 就是用了一个假不变量** —— 队列化的那条真不变量是「槽要么在队列里、要么在 lease 手里」。
     * 读写须在池的互斥临界区内（队列化后即 `Channel` 的收发本身）。
     */
    var generation: Long = 0L
        private set

    /** 代次前进并返回新值（夺槽 [FixedEnginePool.acquire] 与 kill 收归 [FixedEnginePool.recycle] 共用）。 */
    fun occupy(): Long {
        generation += 1
        return generation
    }

    fun markBusy(now: Long) {
        require(state == SlotState.FREE) { "槽位 $index 状态非法: $state" }
        statusMachine.onExecuteRequested()      // IDLE → BOOTING：execute 尚未返回
        state = SlotState.BUSY
        busySinceMillis = now
    }

    /** 四步 quiesce（§8.3）：stop 超时由池 kill 兜底，然后回 FREE。幂等：非 BUSY 视为已净。 */
    suspend fun quiesce(): StopResult {
        // 非 BUSY 视为已净（幂等：并发 release 的输家、killAll 强释后的迟到 release
        // 都走这里）。**这是槽位状态那一句，不是状态机那一句** —— 状态机可能还停在
        // RUNNING（被强释时 quiesce 从未跑过），那归 [markFree] 处理。
        if (state != SlotState.BUSY) return StopResult.Clean
        statusMachine.onQuiesceStart()          // BOOTING|RUNNING → QUIESCING
        state = SlotState.QUIESCING
        val result = engine.stop()
        // 归还是 claim 独占的（见 [PoolLease.release]）：quiesce 执行期间**没有别人能碰这个槽**
        // ——并发 release 的输家在 claim 就被挡住，killAll 的强释同样走 claim。于是下面这两句
        // 状态机转移面对的一定是 QUIESCING，不需要「停完再对表」那种事后补偿。
        if (result is StopResult.TimedOut) {
            engine.kill()
            statusMachine.onKill(KillCause.REQUESTED)   // QUIESCING 超时兜底 → STOPPED（quiesce 未干净完成，StopResult 已携 TimedOut）
        } else {
            statusMachine.onQuiesceCompleted()  // QUIESCING → STOPPED
        }
        // 回收复用：STOPPED|CRASHED → IDLE（下次夺槽又从 BOOTING 起）
        statusMachine.onRecycle()
        state = SlotState.FREE
        busySinceMillis = 0L
        return result
    }

    /** 强杀后强制复位（仅 killAll 用）；调用方保证与 release 串行。 */
    fun forceFree(cause: KillCause = KillCause.REQUESTED) {
        if (state != SlotState.FREE) {
            statusMachine.onKill(cause)          // REQUESTED+RUNNING|QUIESCING→STOPPED；其余→CRASHED
            statusMachine.onRecycle()            // CRASHED|STOPPED → IDLE，槽位可再次夺用
        }
        state = SlotState.FREE
        busySinceMillis = 0L
    }

    /**
     * **归还**（2026-10-12 批 103 P2）：队列化之后，槽回到空闲队列**之前**由 lease 调它。
     *
     * **为什么单独一个方法而不是复用 [reuse]**：[reuse] 会走状态机（按 [cause] 归类到
     * STOPPED/CRASHED 再回收 IDLE），那是**归档**语义（看门狗 kill 之后的归因）；
     * 而「正常归还」与「kill 强释后归还」都不该按某个 cause 归类 —— 它们已经各自走过
     * quiesce / forceFree 了，状态机已经落到 STOPPED 或 IDLE。这里只做**状态复位**
     * （BUSY|QUIESCING → FREE，清 busySince），让槽可以被下一个持有者安全取走。
     *
     * **幂等**：已 FREE 时是 no-op（归还路径可能重复到达，见 [PoolLease]）。
     */
    fun markFree() {
        if (state == SlotState.FREE) return
        state = SlotState.FREE
        busySinceMillis = 0L
        // 归还时状态机必在 STOPPED/CRASHED：`release` 走 quiesce、其余走 forceFree，
        // 两条都先把它落到终态。**这条断言是有意的探测器**（批 103 首跑就抓到「启动失败那条
        // 归还路径漏了 reuse」）—— 若某天某条归还路径忘了，症状会是「下一个持有者拿到一个
        // 状态机还悬着的槽」，而这里当场响亮。
        check(statusMachine.status == EngineStatus.STOPPED || statusMachine.status == EngineStatus.CRASHED) {
            "槽 $index 归还时状态机在 ${statusMachine.status}（应为 STOPPED/CRASHED）—— " +
                "归还路径漏了 quiesce/forceFree 其中一步"
        }
        statusMachine.onRecycle()
    }

    /** kill 收归复用（仅 [FixedEnginePool.recycle] 用，调用方持 stateLock）：状态复位，不触碰许可证。 */
    fun reuse(cause: KillCause = KillCause.REQUESTED) {
        if (state != SlotState.FREE) {
            statusMachine.onKill(cause)          // watchdog/OOM → CRASHED；REQUESTED 活着才 STOPPED
            statusMachine.onRecycle()            // → IDLE：槽位可再次夺用（容量不缩水）
        }
        state = SlotState.FREE
        busySinceMillis = 0L
    }

    /** 当前 [EngineStatus]（§8.3 状态机投影）：BOOTING/RUNNING/QUIESCING/IDLE(空闲)。 */
    fun status(): EngineStatus = statusMachine.status

    /**
     * execute 已返回（:domain 状态机的 BOOTING → RUNNING；§8.3）。
     * 由 [FixedEnginePool.acquire] 在拿到 [EngineRunReceipt] 后调用 —— 进程起来了。
     */
    fun markRunning() {
        statusMachine.onBootCompleted()
    }
}