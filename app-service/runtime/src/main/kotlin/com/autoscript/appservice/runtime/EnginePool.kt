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

    /**
     * 看门狗 kill 后的槽位收归（kill 权威归 RuntimeController，§4.1；许可证记账归池）。
     * 池侧原子完成「槽位复位 + 许可证归还」，保证杀槽不缩水池容量（free 与可领证恒一致）。
     * 重复收归（槽位已 FREE）幂等，绝不超发许可证。
     */
    fun recycle(slot: PoolSlot, cause: KillCause? = null)

    fun stats(): PoolStats
}

data class PoolAcquireRequest(
    val projectId: String,
    val scriptPath: String,
    val args: List<String> = emptyList(),
    val runNonce: String? = null,               // 调度幂等锚点，透传 EngineRunRequest（§8.5）
    val scriptTimeoutMillis: Long? = null,      // 脚本自身超时，透传 EngineRunRequest
    val waitTimeoutMillis: Long? = null,        // 排队等待上限；null = 无限等
)

sealed interface PoolAcquireOutcome {
    data class Granted(val handle: PoolHandle) : PoolAcquireOutcome
    data object TimedOut : PoolAcquireOutcome                       // 排队超时
    data class Failed(val message: String) : PoolAcquireOutcome     // 引擎启动失败（槽位已收回）
}

/** 已获槽位的执行句柄（池内身份：请求 + 槽位 + 收据 + 代次；无跨层包装——会话面在桥两侧各一边：Kotlin 侧是 `EnginesNamespaceHandler` 的 exec/stop/status/channel* 方法，JS 侧是 `EngineSessionImpl`/`EngineChannel`）。 */
class PoolHandle internal constructor(
    val request: PoolAcquireRequest,
    val slot: PoolSlot,
    val receipt: EngineRunReceipt,
    /** 获取时的槽位代次（§7.4 generation 纪律）：release 时对不上即过期句柄。 */
    internal val slotGeneration: Long = slot.generation,
)

data class PoolStats(
    val capacity: Int,
    val free: Int,
    val busy: Int,
)