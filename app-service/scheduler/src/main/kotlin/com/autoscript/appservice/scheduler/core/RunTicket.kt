package com.autoscript.appservice.scheduler.core

import com.autoscript.domain.core.Clock
import com.autoscript.domain.core.SystemClock
import com.autoscript.domain.scripts.EngineRunLink

/**
 * 一次投递对应的执行句柄（§8.5 / EngineSessionHandle 契约）：
 * dispatcher 把「控制面」收敛到 RequestResponse —— scheduler 拿到 [request] 后
 * 只有一个待确认的 runTicket；四个终态由 [Outcome] 如实上报（不含 Interrupted：
 * 恢复重投由日志层处理，不让新 run 的句柄背旧账）。
 */
data class RunTicket(
    val idLink: EngineRunLink?,   // intent↔engine 双 id 关联（§8.5 归档入口；骨架期可为 null）
    val outcome: Outcome?,       // null = 尚未结束
)

sealed interface Outcome {
    data object Finished : Outcome
    data class Failed(val error: String? = null) : Outcome
}

/**
 * 控制面请求（scheduler → 引擎执行体）：进程池获取后的单一事务句柄。
 * 骨架期不与真实引擎握手，scheduler 只把意图日志的 runId 放进来（EngineRunLink.engineRunId 待定）。
 */
data class ControlRequest(
    val idLink: EngineRunLink? = null,
)

/**
 * 骨架实现：不接真实引擎，仅回「已受理」。生产替换为 runtime：pool → PoolHandle → EngineExecutionHandle。
 */
class NoopDispatcherRunner : RunDispatcher {
    override suspend fun dispatch(pending: PendingRun): RunOutcome = RunOutcome.Succeeded
}