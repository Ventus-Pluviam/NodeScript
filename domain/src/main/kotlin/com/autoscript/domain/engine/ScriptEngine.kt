package com.autoscript.domain.engine

import com.autoscript.domain.bridge.HandleRef

/** :nodeN 引擎进程宿主 SPI（docs/framework-design.md §8）。实现位于 :engine:node-process。 */
interface ScriptEngine {
    /** 进程标识（pool id 派生），用于归属日志/看门狗。 */
    val id: EngineId

    /**
     * 引擎宿主进程的 OS pid；不存在（未启动 / 已退出）回 null。
     *
     * **看门狗的外带采样锚点**（§8.4）：CPU/RSS 走 `/proc/<pid>/stat|status`，
     * 不依赖引擎合作。不可得时**如实回 null，绝不给 0/自身 pid** —— 0 会被 `/proc/0`
     * 解析失败污染 CPU 基线，自身 pid 会让看门狗误杀 App 主进程。
     */
    val pid: Int?

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
    /**
     * 这次执行所落的引擎进程 pid（启动瞬间的快照）；不可得（宿主不给 / 已退出）为 null。
     *
     * 看门狗按 pid 采样 `/proc/<pid>/stat|status`（§8.4），所以 pid 必须随 receipt 出来，
     * 而不能让调用方自己去问 [ScriptEngine.pid]（那会读到"当前"pid，而非这次 run 的 pid
     * —— 同一槽位换过一次执行体后两者就不同了）。回路见 §8.4 的调度循环说明。
     */
    val pid: Int? = null,
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