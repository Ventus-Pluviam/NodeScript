package com.autoscript.appservice.runtime

import com.autoscript.domain.bridge.HandleRef
import com.autoscript.domain.engine.EngineId
import com.autoscript.domain.engine.EngineRunReceipt
import com.autoscript.domain.engine.EngineRunRequest
import com.autoscript.domain.engine.EngineStatus
import com.autoscript.domain.engine.KillCause
import com.autoscript.domain.engine.ScriptEngine
import com.autoscript.domain.engine.StopResult

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
    private var nextRunId = 1L

    override suspend fun execute(run: EngineRunRequest): EngineRunReceipt {
        if (failOnExecute) throw IllegalStateException("fake boot failure")
        executed += run
        statusToReturn = EngineStatus.RUNNING
        return EngineRunReceipt(runId = nextRunId++, handle = HandleRef(refId = runIdBase(id), generation = 1))
    }

    private fun runIdBase(id: EngineId): Long = id.poolIndex * 1000L

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

    override suspend fun status(): EngineStatus = statusToReturn
}