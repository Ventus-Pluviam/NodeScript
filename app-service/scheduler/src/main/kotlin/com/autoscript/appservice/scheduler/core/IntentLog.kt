package com.autoscript.appservice.scheduler.core

/**
 * checkpoint 意图日志（docs/framework-design.md §8.5）：
 * `RUN_START(projectId, entry, runNonce, scheduledAt, screen) → …execute… → COMMIT(result)`。
 *
 * - append-only：状态只能从 STARTED 前进到 COMMITTED，不允许改写/删除历史行
 *   （STARTED 瞬态可从 [all]/[uncommitted] 的 outcome==null 观察，见 §接口模型）。
 * - **恢复只跟随 COMMIT**：启动回放时未 COMMIT 的 run 视为「未完成意向」→ 原子 [reopen]
 *   封口旧行并以**新 [runId] + 保留 [runNonce]** 重新入队（执行体用 runNonce 做幂等键）。
 * - 实现必须是线程安全 + 崩溃持久的（Android 侧 SQLite；本模块提供内存实现供 JVM 单测）。
 *
 * **归档入口（§8.5「每次重跑新 RunRecord」）**：IntentRun.runId 是意图日志身份，引擎侧
 * `EngineRunReceipt.runId` 是 RunRecord 身份，两套 id 由 dispatcher 实现（:app 装配层）在
 * dispatch 时关联——RunDispatcher 实现负责把 PendingRun.runNonce / 对应的 IntentRun.runId
 * 写入引擎 RunRecord（:domain scripts.RunRecord），使 UI/任务中心能按 IntentRun 追溯引擎记录。
 */
interface IntentLog {

    /**
     * 追加 RUN_START 行，分配单调递增的 runId。
     * @throws IllegalStateException 若 [runNonce] 已有 COMMIT —— 幂等锚点原子下沉存储层
     *   （SQLite 用 committed-nonce 唯一索引兜底，杜绝 isCommitted 预检的 check-then-act 竞态）。
     */
    suspend fun appendStart(
        projectId: String,
        scriptPath: String,
        runNonce: String,
        trigger: TriggerSource,
        scheduledAtMillis: Long,
        screen: ScreenGuarantee = ScreenGuarantee.ANY,
        deadlineMillis: Long? = null,
    ): IntentRun

    /** 对该 runId 追加 COMMIT 行；重复 COMMIT 幂等返回已提交结果。 */
    suspend fun commit(runId: Long, outcome: RunOutcome): IntentRun?

    /**
     * 原子「封口 + 重开」（崩溃恢复专用，§8.5）：单事务内把 [oldRunId] 封口为
     * [RunOutcome.Interrupted] 并分配新 runId 追加新 START 行（保留原 runNonce）。
     * 消除两步式「先 commit-Interrupted 再 appendStart」之间的崩溃窗口。
     * @throws IllegalArgumentException 若 oldRunId 不存在或已 COMMIT。
     */
    suspend fun reopen(oldRunId: Long): IntentRun

    /** 启动回放：尚未 COMMIT 的所有意向（崩溃/被杀遗留），按投递顺序。 */
    suspend fun uncommitted(): List<IntentRun>

    /** 该 runNonce 是否已有 COMMIT（幂等键查询，防重复副作用）。 */
    suspend fun isCommitted(nonce: String): Boolean

    /** 全量日志（审计/UI）；顺序 = append 顺序。 */
    suspend fun all(): List<IntentRun>
}

/** 一次意向执行行（RUN_START 与 COMMIT 折叠为不可变记录，append-only 视角）。 */
data class IntentRun(
    val runId: Long,                        // 单调递增；崩溃恢复时重新分配
    val projectId: String,
    val scriptPath: String,
    val runNonce: String,                   // 幂等键：同一 nonce 的副作用不重复（§8.5）
    val trigger: TriggerSource,
    val scheduledAtMillis: Long,
    val screen: ScreenGuarantee,            // 投递时的屏幕契约（恢复重投不得丢失）
    val outcome: RunOutcome?,               // null = 未 COMMIT（STARTED）
    val startedAtMillis: Long,
    /** 本次投递的到期时刻（§8.6：排期 + 排队上限；null = 无期限，恢复时不判过期）。 */
    val deadlineMillis: Long? = null,
    val committedAtMillis: Long? = null,
)

/** 执行结果（对外副作用幂等锚点的对偶：业务重复性统一由 nonce 挡住）。 */
sealed interface RunOutcome {
    data object Succeeded : RunOutcome
    data object Failed : RunOutcome                      // 脚本自身失败 exit!=0 / run 抛错
    data class Crashed(val message: String? = null) : RunOutcome   // 引擎被杀/OOM/看门狗
    data object Cancelled : RunOutcome                   // 用户停止/排队取消

    /**
     * 崩溃恢复封口（§8.5）：旧意向未完成，重新入队。
     * **不计入已 COMMIT nonce 集合**——它不构成一次完成的对外副作用，正是要重投的那次。
     */
    data object Interrupted : RunOutcome
}