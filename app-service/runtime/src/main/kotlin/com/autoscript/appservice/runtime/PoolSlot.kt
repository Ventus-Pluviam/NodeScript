package com.autoscript.appservice.runtime

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
    var busySinceMillis: Long = 0L
        private set

    fun markBusy(now: Long) {
        require(state == SlotState.FREE) { "槽位 $index 状态非法: $state" }
        state = SlotState.BUSY
        busySinceMillis = now
    }

    /** 四步 quiesce（§8.3）：stop 超时由池 kill 兜底，然后回 FREE。幂等：非 BUSY 视为已净。 */
    suspend fun quiesce(): StopResult {
        if (state != SlotState.BUSY) return StopResult.Clean
        state = SlotState.QUIESCING
        val result = engine.stop()
        if (result is StopResult.TimedOut) {
            engine.kill()
        }
        state = SlotState.FREE
        busySinceMillis = 0L
        return result
    }

    /** 强杀后强制复位（仅 killAll 用）；调用方保证与 release 串行。 */
    fun forceFree() {
        state = SlotState.FREE
        busySinceMillis = 0L
    }
}