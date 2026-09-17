package com.autoscript.appservice.runtime

import com.autoscript.domain.engine.EngineRunReceipt
import com.autoscript.domain.engine.KillCause
import com.autoscript.domain.engine.ScriptEngine
import com.autoscript.domain.engine.StopResult

/**
 * 引擎进程池契约（docs/framework-design.md §8.1/§8.2）：
 * 进程池 + 每脚本一进程；并发上限 = 池容量，超载排队（§8.6：绝不静默丢任务）。
 * 槽位宿主即 [ScriptEngine]（:engine:node-process 实现），本层只做生命周期仲裁。
 */
interface EnginePool {
    val capacity: Int

    /** 取空槽执行；满则排队至 [PoolAcquireRequest.waitTimeoutMillis]（null = 无限等待）。 */
    suspend fun acquire(request: PoolAcquireRequest): PoolAcquireOutcome

    /** 放回槽位：四步 quiesce（[ScriptEngine.stop]），TimedOut 由池 kill 兜底。 */
    suspend fun release(handle: PoolHandle): StopResult

    /** 全部槽位强杀（kill 权威仅归 RuntimeController，§4.1）。 */
    suspend fun killAll(reason: KillCause)

    fun stats(): PoolStats
}

data class PoolAcquireRequest(
    val projectId: String,
    val scriptPath: String,
    val args: List<String> = emptyList(),
    val scriptTimeoutMillis: Long? = null,      // 脚本自身超时，透传 EngineRunRequest
    val waitTimeoutMillis: Long? = null,        // 排队等待上限；null = 无限等
)

sealed interface PoolAcquireOutcome {
    data class Granted(val handle: PoolHandle) : PoolAcquireOutcome
    data object TimedOut : PoolAcquireOutcome                       // 排队超时
    data class Failed(val message: String) : PoolAcquireOutcome     // 引擎启动失败（槽位已收回）
}

/** 已获槽位的执行句柄；scheduler 侧再包 :domain 的 EngineSessionHandle（含 RuntimeChannel）。 */
class PoolHandle internal constructor(
    val request: PoolAcquireRequest,
    val slot: PoolSlot,
    val receipt: EngineRunReceipt,
)

data class PoolStats(
    val capacity: Int,
    val free: Int,
    val busy: Int,
)