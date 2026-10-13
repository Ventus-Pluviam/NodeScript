package com.autoscript.domain.engine

import com.autoscript.domain.bridge.HandleRef
import com.autoscript.domain.permission.ScriptAuthorizationSnapshot

/** :nodeN 引擎进程宿主 SPI（docs §8）。实现位于 :engine:node-process。 */
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

    /**
     * OS 级冻结这次执行（2026-10-13 执行暂停；悬浮窗「暂停执行」按钮的落点）。
     *
     * **不是**优雅停：进程原地冻住（SIGSTOP）、心跳与输出都停，[resume] 后从
     * 冻住那一行继续。暂停期间看门狗照常看不到心跳 —— 看门狗排除、宽限与
     * 期限顺延是 `RuntimeController` 的账（见它那边的 `paused`），引擎只管冻/解冻。
     *
     * @return true = 已冻住；false = 本引擎冻不住（替身/离线实现、pid 拿不到、
     *   `kill` 二进制缺席 —— 原文由实现日志，调用方如实报"不支持"，不假装）。
     * 缺省 false：与 `lastRunSummary` 的缺省 null 同一条"替身零改动"纪律 ——
     * FakeEngine/UnavailableEngine 不用动，UI 按 false 反馈。
     */
    suspend fun pause(): Boolean = false

    /** 解冻 [pause] 冻住的执行（SIGCONT）。语义与 [pause] 对偶，缺省同样 false。 */
    suspend fun resume(): Boolean = false

    /** 当前状态快照（事件推送走 bridge EventBus，不在此轮询建模）。 */
    suspend fun status(): EngineStatus

    /**
     * **进程边界诊断**（backlog B11，契约见 docs §8.5 末段）：上次执行的引擎侧事实
     * （退出码 + 捕获的 stderr 尾部）。**exit 0 的干净退出也会有**摘要（它只是诊断读口，
     * 状态归类不看它）；未执行 / 尚未退净 / 被强杀或请求停止（137/143 不是病因）回 null
     * —— 填充时机见 `:engine:node-process` 的实现 KDoc。
     *
     * 数据面语义与 ConsoleCollector 区分：桥 console 是全量异步通道（可丢包，且崩溃时
     * 桥可能也断了）；本摘要是**排水线程里那半个字节**的保留（尾部、有界、utf-8）——
     * 两个面一起看才能拼出完整诊断。**两者互不替代**：这里不做全量日志。
     *
     * 默认实现回 null：与 FakeEngine/UnavailableEngine 等替身成对 —— 替身没有进程边界，
     * 没捕获就不假装有摘要。真实现覆盖。
     */
    suspend fun lastRunSummary(): RunSummary? = null
}

/** 一次执行的进程侧事实（崩溃摘要载体；见 [ScriptEngine.lastRunSummary]）。 */
data class RunSummary(
    /** 子进程退出码；仍存活为 null（进程还没退）。 */
    val exitCode: Int? = null,
    /** 捕获的 stderr 尾部（utf-8，截断至 [MAX_DETAIL]；无内容为 null）。 */
    val stderrTail: String? = null,
) {
    companion object {
        /** 摘要上限：够看病因，不吞内存（崩溃日志动辄几 KB，拿前 4KB 已覆盖诊断）。 */
        const val MAX_DETAIL: Int = 4096
    }
}

data class EngineId(val poolIndex: Int)

data class EngineRunRequest(
    val projectId: String,
    val scriptPath: String,     // 项目内相对路径（引擎宿主用 ScriptPaths.scriptFile 拼绝对路径；见 ScriptPaths KDoc）
    val args: List<String> = emptyList(),
    val runNonce: String? = null,   // 调度幂等锚点（§8.1/§8.5）：执行体用 runNonce 做对外副作用幂等键
    val timeoutMillis: Long? = null,
    /**
     * 这次执行的**授权快照**（A5，§11）：宿主对本次 spawn 已算好的不可变授权（掩码 + 来源档），
     * 由 `RuntimeController.start` 一次算出、经池请求传到这里，再由引擎原样交给
     * [RunIdentityIssuer.issue] 落到执行身份上。
     *
     * 为什么必须随请求带下来而不是让签发方自己算：签发方（身份账）不知道也不该知道
     * 来源策略 —— 让它在签发时另算一遍，就出现「预检校验的是 A 掩码、签发出去的是 B 掩码」
     * 的窗口（A5 整改第 4 条）。null = **离线/未接身份**（不签发桥身份的那些路径：
     * 引擎离线模式、替身引擎）—— 那种情况没有可签发的掩码，如实为 null，不编一个。
     */
    val authorization: ScriptAuthorizationSnapshot? = null,
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

enum class KillCause {
    REQUESTED,
    WATCHDOG_HEARTBEAT,
    WATCHDOG_CPU,
    OOM,
    ENGINE_REQUEST,

    /**
     * 宿主自报与池侧投影持续分歧（§8.3 drift 裁决，看门狗调度循环落点）：
     * 两侧都活着但说的不一样，分不清谁对 —— 杀掉重来比猜一边可审计。
     * 与 `ENGINE_REQUEST` 的区别：ENGINE_REQUEST 是引擎自己要求退出（宿主可信）；
     * 本原因是仲裁层在宿主可疑时主动杀（宿主不可信），归档/日志据此区分"自杀"与"他杀"。
     * 与 REQUESTED 的区别：REQUESTED 是管理者主动停（调度超时/用户停止，归 STOPPED）；
     * 本原因归 CRASHED（见 `EngineStateMachine.onKill`：非 REQUESTED 一律 CRASHED）。
     */
    DRIFT,

    /**
     * 无人等待的 run 到了声明的墙钟期限（§8.6「谁 await 谁负责时限，没人 await 的必须自带期限」；
     * 看门狗调度循环的**期限线**落点）。
     *
     * 与三路健康判据的区别：那三路看的是**进程表现**（心跳/CPU/RSS），本原因看的是**契约**——
     * 发起方声明了这次跑多久，到点就得交账。心跳正常、CPU 空闲、RSS 很低的长跑脚本照样到点。
     * 与 REQUESTED 的区别：REQUESTED 是调用方主动停（归 STOPPED）；本原因是期限到期被强制
     * 收账，归 CRASHED（`EngineStateMachine.onKill`：非 REQUESTED 一律 CRASHED）。
     * 与调度链路的区别：调度链路（dispatcher）自己 await 终结并超时强杀，走 REQUESTED；
     * 本原因只属**无人 await** 的那些 run（bridge `engines.exec`）。
     */
    TIMEOUT,
}

data class CrashInfo(
    val cause: KillCause? = null,
    val message: String? = null,
)