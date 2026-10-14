package com.autoscript.appservice.runtime

import com.autoscript.domain.bridge.HandleRef
import com.autoscript.domain.engine.EngineId
import com.autoscript.domain.engine.EngineRunReceipt
import com.autoscript.domain.engine.EngineRunRequest
import com.autoscript.domain.engine.EngineStatus
import com.autoscript.domain.engine.KillCause
import com.autoscript.domain.engine.RunSummary
import com.autoscript.domain.engine.ScriptEngine
import com.autoscript.domain.engine.StopResult
import java.util.concurrent.atomic.AtomicLong

/** FakeEngine 的 runId 序列：跨所有替身实例全局唯一（与真实引擎 Receipt 语义一致）。 */
private val fakeRunIds = AtomicLong(1)

/** 测试替身引擎：记录调用、可控 stop 结果/kill 计数/启动失败。 */
class FakeEngine(
    override val id: EngineId,
) : ScriptEngine {

    val executed = mutableListOf<EngineRunRequest>()
    var stopResult: StopResult = StopResult.Clean
    var failOnExecute = false
    var killCalls = 0
    var stopCalls = 0
    var statusToReturn: EngineStatus = EngineStatus.IDLE

    /** 宿主进程 pid；null = 宿主不给（看门狗据此"无法度量"，见 ScriptEngine.pid 契约）。 */
    override var pid: Int? = null

    /** status() 抛错开关：模拟宿主已死/实现未接线（探针读不到，不是"没状态"）。 */
    var blowStatus = false

    /**
     * status() 改抛这个（非 null 优先于 [blowStatus]）：给"取消必须穿透"那条判据用 ——
     * 宿主探针在协程取消时抛 [kotlinx.coroutines.CancellationException]，
     * 控制器不得把它折成"引擎已死/查不到状态"。
     */
    var statusThrowable: Throwable? = null

    /** [ScriptEngine.lastRunSummary] 的返回值（backlog B11）：默认 null = 无事实；测试驱动。 */
    var summaryToReturn: RunSummary? = null

    /**
     * `pause()/resume()` 的返回值（缺省 false = 冻不住，与 `ScriptEngine` 的生产缺省同形）。
     *
     * 为什么缺省是 false 而不是 true：真实实现里 false 表示"pid 拿不到 / `kill` 二进制缺席"
     * —— 替身若默认"冻得住"，那些**依赖「冻不住」**的判据（不进集、不排除锚点）就永远测不到。
     * 要给"冻得住"那条路用例，把这两个开关打开即可（不必写子类）。
     */
    var pauseResult: Boolean = false
    var resumeResult: Boolean = false

    override suspend fun execute(run: EngineRunRequest): EngineRunReceipt {
        if (failOnExecute) throw IllegalStateException("fake boot failure")
        executed += run
        statusToReturn = EngineStatus.RUNNING
        val runId = fakeRunIds.getAndIncrement()
        return EngineRunReceipt(runId = runId, handle = HandleRef(refId = runId, generation = 1), pid = pid)
    }

    override suspend fun stop(): StopResult {
        stopCalls++
        statusToReturn = when (stopResult) {
            StopResult.Clean -> EngineStatus.STOPPED
            else -> EngineStatus.QUIESCING
        }
        return stopResult
    }

    override suspend fun kill(): KillCause {
        killCalls++
        statusToReturn = EngineStatus.CRASHED
        return KillCause.REQUESTED
    }


    override suspend fun status(): EngineStatus {
        statusThrowable?.let { throw it }
        if (blowStatus) throw IllegalStateException("模拟宿主探针失败")
        return statusToReturn
    }

    override suspend fun pause(): Boolean = pauseResult

    override suspend fun resume(): Boolean = resumeResult

    override suspend fun lastRunSummary(): RunSummary? = summaryToReturn
}