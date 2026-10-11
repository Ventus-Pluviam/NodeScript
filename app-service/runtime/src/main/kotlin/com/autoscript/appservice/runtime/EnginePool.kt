package com.autoscript.appservice.runtime

import com.autoscript.domain.engine.EngineRunReceipt
import com.autoscript.domain.engine.KillCause
import com.autoscript.domain.engine.ScriptEngine
import com.autoscript.domain.engine.StopResult
import com.autoscript.domain.permission.ScriptAuthorizationSnapshot

/**
 * 引擎进程池契约（docs §8.1/§8.2）：
 * 进程池 + 每脚本一进程；并发上限 = 池容量，超载排队（§8.6：绝不静默丢任务）。
 * 槽位宿主即 [ScriptEngine]（:engine:node-process 实现），本层只做生命周期仲裁。
 */
interface EnginePool {
    val capacity: Int

    /** 取空槽执行；满则排队至 [PoolAcquireRequest.waitTimeoutMillis]（null = 无限等待）。 */
    suspend fun acquire(request: PoolAcquireRequest): PoolAcquireOutcome

    /**
     * 放回槽位：四步 quiesce（[ScriptEngine.stop]），TimedOut 由池 kill 兜底。
     *
     * **幂等**（批 103 P2）：经 [PoolHandle.lease] 归还，重复调用是 no-op（返回 Clean）——
     * 原实现的「双重归还 → 超发一证 → 同一引擎跑两个脚本」那条路已消除。
     */
    suspend fun release(handle: PoolHandle): StopResult

    /** 全部槽位强杀（kill 权威仅归 RuntimeController，§4.1）。 */
    suspend fun killAll(reason: KillCause)

    /**
     * 看门狗 kill 后的槽位收归（kill 权威归 RuntimeController，§4.1）。
     *
     * **生产路径不走这里**（2026-10-12 批 103 P2）：控制器改走 [PoolHandle.lease] 的
     * [PoolLease.forceReturn] —— 因为「归还权」必须与 [release] 抢**同一个 CAS**，否则
     * 「killRun 与迟到的 stop 同时到达」会双重归还。本方法保留给
     * 「只拿到槽位、拿不到 lease」的诊断路径与直接测池记账的单测。
     *
     * 重复收归幂等（经 lease 的 claim）。
     */
    fun recycle(slot: PoolSlot, cause: KillCause? = null)

    fun stats(): PoolStats
}

/**
 * 一次执行的**时限由谁收口**（§8.6 铁律"进入 RUNNING 的路径必须绑定一条终结它的时限"）。
 *
 * 判据是**发起方等不等**，不是"有没有声明超时"——声明了却没人执行等于没声明：
 * - [AWAITER]：发起方自己 await 终结（调度链路的 `ControllerRunDispatcher`：
 *   `awaitCompletion` 超时 → `killRun(REQUESTED)`）；
 * - [WATCHDOG]：发起方不等（桥 `engines.exec` 拿到句柄即返回，之后只有脚本自己
 *   `cancel`）——期限线随 `RuntimeController.watchAnchors()` 交给看门狗，到点由它落
 *   `KillCause.TIMEOUT`。
 */
enum class TimeoutEnforcer { AWAITER, WATCHDOG }

data class PoolAcquireRequest(
    val projectId: String,
    val scriptPath: String,
    val args: List<String> = emptyList(),
    val runNonce: String? = null,               // 调度幂等锚点，透传 EngineRunRequest（§8.5）
    val scriptTimeoutMillis: Long? = null,      // 脚本自身超时，透传 EngineRunRequest（引擎不计时，见其 KDoc）
    val waitTimeoutMillis: Long? = null,        // 排队等待上限；null = 无限等
    /**
     * 本次 spawn 的**授权快照**（A5，§11）：掩码 + 来源档，由 [RuntimeController.start] 一次
     * 算出（问装配层注入的 `ScriptAuthorizationPolicy`），沿本请求 → `EngineRunRequest`
     * → `RunIdentityIssuer.issue` → 连接上下文一路传下去。
     *
     * 为什么放在请求里而不是让下游各自算：授权判据（跨脚本目标授权、派生不得提权）与
     * 实际签发的掩码必须是**同一份**。下游重算 = 两套事实，现场表现为「预检放行的组合，
     * 子进程起来被自己的掩码拒」。
     *
     * **不是 wire 字段**：它由宿主一侧算出，脚本无法自报；桥 handler 也**不读** payload 里的
     * 任何授权相关键（见 `EnginesNamespaceHandler.parseExec`）。
     *
     * null = 调用方未给（测试替身/直投老路径）→ [RuntimeController.start] 按策略现算一份
     * （见其 KDoc：这只是"没传就补一个"的便利，不是第二条策略来源）。
     */
    val authorization: ScriptAuthorizationSnapshot? = null,
    /**
     * 时限归属（见 [TimeoutEnforcer]）。缺省 [TimeoutEnforcer.AWAITER]（调度链路）。
     *
     * **选 [TimeoutEnforcer.WATCHDOG] 就必须给 [scriptTimeoutMillis]** —— 否则这条 run
     * 又落回"没人收尾"那一类（init 里 require，构造即响亮失败，不静默放过）。
     */
    val timeoutEnforcer: TimeoutEnforcer = TimeoutEnforcer.AWAITER,
) {
    init {
        require(timeoutEnforcer == TimeoutEnforcer.AWAITER || scriptTimeoutMillis != null) {
            "TimeoutEnforcer.WATCHDOG 必须带 scriptTimeoutMillis：无人 await 的 run 必须自带期限（§8.6）"
        }
    }
}

sealed interface PoolAcquireOutcome {
    data class Granted(val handle: PoolHandle) : PoolAcquireOutcome
    data object TimedOut : PoolAcquireOutcome                       // 排队超时
    data class Failed(val message: String) : PoolAcquireOutcome     // 引擎启动失败（槽位已收回）
}

/** 已获槽位的执行句柄（池内身份：请求 + 槽位 + 收据 + 代次；无跨层包装——会话面在桥两侧各一边：Kotlin 侧是 `EnginesNamespaceHandler` 的 exec/stop/status/channel* 方法，JS 侧是 `EngineSessionImpl`/`EngineChannel`）。 */
class PoolHandle internal constructor(
    val request: PoolAcquireRequest,
    /**
     * 本次占用的**归还凭证**（2026-10-12 批 103 P2）：release / recycle 都经它，
     * 而**不是**外部直接碰 [slot]。归还**恰好一次**由 [PoolLease] 的 CAS 保证。
     */
    val lease: PoolLease,
    val receipt: EngineRunReceipt,
) {
    /** 本次占用的槽位（宿主状态查询与诊断摘要仍经它；**生命周期**不由外部驱动）。 */
    val slot: PoolSlot get() = lease.slot

    /** 该占用是否已被归还（读面；归还之后持有者不得再动槽的生命周期）。 */
    val leaseReturned: Boolean get() = lease.isReturned
}

data class PoolStats(
    val capacity: Int,
    val free: Int,
    val busy: Int,
)