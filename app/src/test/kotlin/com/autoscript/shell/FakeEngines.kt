package com.autoscript.shell

import com.autoscript.domain.bridge.HandleRef
import com.autoscript.domain.engine.EngineId
import com.autoscript.domain.engine.EngineRunReceipt
import com.autoscript.domain.engine.EngineRunRequest
import com.autoscript.domain.engine.EngineStatus
import com.autoscript.domain.engine.KillCause
import com.autoscript.domain.engine.ScriptEngine
import com.autoscript.domain.engine.StopResult
import java.util.concurrent.atomic.AtomicLong

private val fakeRunIds = AtomicLong(1)

/** 测试替身引擎（对齐 runtime FakeEngine 语义；:app test 不可见其 test source，自备）。 */
class FakeEngineForDispatcher(
    override val id: EngineId,
    override val pid: Int? = null,
    /** 脚本执行体 run 起来后多久"自退出"（真机：脚本跑完宿主推 STOPPED）；null = 永不退出（悬挂）。 */
    @Volatile var autoExitAfterMillis: Long? = 50,
) : ScriptEngine {
    val executed = mutableListOf<EngineRunRequest>()
    /** 已分配的 EngineRunReceipt.runId（顺序 = execute 顺序）：供断言 engineRunId 关联。 */
    val receiptRunIds = mutableListOf<Long>()
    var stopResult: StopResult = StopResult.Clean
    var failOnExecute = false
    var killCalls = 0
    var statusToReturn: EngineStatus = EngineStatus.IDLE

    override suspend fun execute(run: EngineRunRequest): EngineRunReceipt {
        if (failOnExecute) throw IllegalStateException("fake boot failure")
        executed += run
        statusToReturn = EngineStatus.RUNNING
        val runId = fakeRunIds.getAndIncrement()
        val wait = autoExitAfterMillis
        if (wait != null) {
            // 真机语义：脚本跑完宿主推 STOPPED（daemon 线程，不阻塞测试结束）。
            Thread({
                try {
                    Thread.sleep(wait)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                if (statusToReturn == EngineStatus.RUNNING) statusToReturn = EngineStatus.STOPPED
            }, "fake-engine-autoexit-$runId").also { it.isDaemon = true }.start()
        }
        receiptRunIds += runId
        return EngineRunReceipt(runId = runId, handle = HandleRef(refId = runId, generation = 1))
    }

    override suspend fun stop(): StopResult {
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
