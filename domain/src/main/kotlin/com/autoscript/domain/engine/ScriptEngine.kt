package com.autoscript.domain.engine

import com.autoscript.domain.bridge.HandleRef

/** :nodeN 引擎进程宿主 SPI（docs/framework-design.md §8）。实现位于 :engine:node-process。 */
interface ScriptEngine {
    /** 进程标识（pool id 派生），用于归属日志/看门狗。 */
    val id: EngineId

    /** 启动一次执行；同引擎一次一脚本。 */
    suspend fun execute(run: EngineRunRequest): EngineRunReceipt

    /** 请求侧主动停止：四步 quiesce（§8.3），返回是否按顺序干净退出。 */
    suspend fun stop(): StopResult

    /** 看门狗强制手段：仅 RuntimeController 有调用权（kill 权威，§4.1）。 */
    suspend fun kill(): KillCause

    /** 当前状态快照（事件推送走 bridge EventBus，不在此轮询建模）。 */
    suspend fun status(): EngineStatus
}

data class EngineId(val poolIndex: Int)

data class EngineRunRequest(
    val projectId: String,
    val scriptPath: String,     // filesDir 相对路径
    val args: List<String> = emptyList(),
    val runNonce: String? = null,   // 调度幂等锚点（§8.1/§8.5）：执行体用 runNonce 做对外副作用幂等键
    val timeoutMillis: Long? = null,
)

data class EngineRunReceipt(
    val runId: Long,
    val handle: HandleRef,      // 用于引擎通道/控制（RuntimeChannel 关联）
)

enum class EngineStatus { IDLE, BOOTING, RUNNING, QUIESCING, STOPPED, CRASHED }

sealed interface StopResult {
    data object Clean : StopResult                      // 四步 quiesce 完成
    data class TimedOut(val partial: Boolean) : StopResult  // 超时，仍需 SIGKILL(由调用方决定)
}

enum class KillCause { REQUESTED, WATCHDOG_HEARTBEAT, WATCHDOG_CPU, OOM, ENGINE_REQUEST }

data class CrashInfo(
    val cause: KillCause? = null,
    val message: String? = null,
)