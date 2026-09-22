package com.autoscript.appservice.runtime

import com.autoscript.domain.engine.EngineStateMachine
import com.autoscript.domain.engine.EngineStatus
import com.autoscript.domain.engine.KillCause
import com.autoscript.domain.engine.ScriptEngine
import com.autoscript.domain.engine.StopResult

/** 池槽位（docs §8.3 每执行单元状态机在池侧的最小投影：FREE/BUSY/QUIESCING）。 */
enum class SlotState { FREE, BUSY, QUIESCING }

/** 池侧时钟：为避免 :runtime → :bridge 反向依赖，本地定义；多处需要时上移 :domain。 */
fun interface RuntimeClock {
    fun nowMillis(): Long

    companion object {
        val system = RuntimeClock { System.currentTimeMillis() }
    }
}

class PoolSlot internal constructor(
    val index: Int,
    val engine: ScriptEngine,
    private val clock: RuntimeClock,
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
     * [PoolHandle] 获取时记下代次；release 时对不上即过期句柄 —— 绝不拆新占用者。
     * 须在池的互斥临界区（[FixedEnginePool] 的 stateLock）内读写。
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
        if (state != SlotState.BUSY) return StopResult.Clean
        statusMachine.onQuiesceStart()          // BOOTING|RUNNING → QUIESCING
        state = SlotState.QUIESCING
        val result = engine.stop()
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