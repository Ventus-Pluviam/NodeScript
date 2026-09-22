package com.autoscript.appservice.scheduler.persist

import com.autoscript.appservice.scheduler.core.IntentRun
import com.autoscript.appservice.scheduler.core.RunOutcome
import com.autoscript.appservice.scheduler.core.ScreenGuarantee
import com.autoscript.appservice.scheduler.core.TriggerSource
import java.nio.file.Path

/**
 * 意图日志原子存储操作（docs/framework-design.md §8.5「append-only + 崩溃持久」的最小接缝）。
 *
 * 本接口是「意图日志语义」与「存储引擎」之间的唯一边界：
 * - JVM 本机/单测 → [JournalFileStore]（jsonl 追加 + fsync + 启动 replay）；
 * - Android 生产 → :app 装配层注入 SQLiteDatabase 实现（同一 SQL 语义，WAL + FULL sync）。
 *
 * 两条实现都必须满足：
 * - **崩溃持久**：insert/seal 返回前数据已落盘（fsync 或 SQLite synchronous=FULL）；
 * - **幂等锚点原子**：同一 runNonce 的「存活行唯一」与「已提交 nonce 唯一」由存储层原子拒绝
 *   （部分唯一索引 / 文件内全量校验 + 单写者锁），不是调用方预检；
 * - **runId 单调**：存储层分配，崩溃后不复用。
 */
interface IntentStore : AutoCloseable {

    /**
     * 追加 START 行并返回存储层分配的 runId。
     * @throws IllegalStateException 同一 runNonce 已有存活（未封口）行
     */
    fun insertStart(row: StartRow): Long

    /**
     * 对 [runId] 落终态（[outcome]）。
     * @return null = runId 不存在或已终态（幂等，返回现态）；否则返回封口后的行
     * @throws IllegalStateException outcome≠INTERRUPTED 且该 nonce 已有非 INTERRUPTED 终态
     */
    fun seal(runId: Long, outcome: StoredOutcome): StoredRow?

    /** 原子「封口旧行(Interrupted) + 同 nonce 新开一行」；返回新 runId。旧行不存在/已终态 → IllegalArgumentException。 */
    fun sealAndReopen(oldRunId: Long): Long

    /** 存活行（未封口），按 runId 升序。启动回放入口。 */
    fun liveRows(): List<StoredRow>

    /** 全部行（审计/UI），按 runId 升序。 */
    fun allRows(): List<StoredRow>

    /** 该 nonce 是否已有非 INTERRUPTED 终态（副作用幂等查询）。 */
    fun hasCommittedNonce(nonce: String): Boolean

    /** 该 nonce 是否已有存活行（appendStart 预检提示用；真去重仍靠 insertStart 原子拒）。 */
    fun hasLiveNonce(nonce: String): Boolean

    override fun close() {}

    // —— 存储行模型（与 IntentRun 一一对应，独立类型避免 persist 依赖 core 运行态）——

    data class StartRow(
        val projectId: String,
        val scriptPath: String,
        val runNonce: String,
        val trigger: String,          // TriggerSource.name
        val screen: String,           // ScreenGuarantee.name
        val scheduledAtMillis: Long,
        val startedAtMillis: Long,
        val deadlineMillis: Long?,
        val args: List<String> = emptyList(),   // 脚本参数（恢复重投不得丢失，§8.5）
        val timeoutMillis: Long? = null,        // 脚本自身超时（恢复重投不得丢失，§8.5）
    )

    data class StoredRow(
        val runId: Long,
        val start: StartRow,
        val outcome: StoredOutcome?,   // null = 存活
        val committedAtMillis: Long?,
    )

    /** outcome 名 + 可选详情（Crashed 携带 message；§8.5 审计需要）。 */
    data class StoredOutcome(val name: String, val detail: String? = null) {
        companion object {
            val SUCCEEDED = StoredOutcome("SUCCEEDED")
            val FAILED = StoredOutcome("FAILED")
            val CANCELLED = StoredOutcome("CANCELLED")
            val INTERRUPTED = StoredOutcome("INTERRUPTED")
            fun crashed(message: String?) = StoredOutcome("CRASHED", message)
        }
    }
}

/** core 侧映射：StoredRow → IntentRun（单向；persist 不反向依赖 core 类型之外的东西）。 */
internal fun IntentStore.StoredRow.toIntentRun(): IntentRun = IntentRun(
    runId = runId,
    projectId = start.projectId,
    scriptPath = start.scriptPath,
    runNonce = start.runNonce,
    trigger = TriggerSource.valueOf(start.trigger),
    scheduledAtMillis = start.scheduledAtMillis,
    screen = ScreenGuarantee.valueOf(start.screen),
    outcome = outcome?.let {
        when (it.name) {
            "SUCCEEDED" -> RunOutcome.Succeeded
            "FAILED" -> RunOutcome.Failed
            "CANCELLED" -> RunOutcome.Cancelled
            "CRASHED" -> RunOutcome.Crashed(it.detail)
            "INTERRUPTED" -> RunOutcome.Interrupted
            else -> throw IllegalStateException("未知 outcome: ${it.name}")
        }
    },
    startedAtMillis = start.startedAtMillis,
    deadlineMillis = start.deadlineMillis,
    args = start.args,
    timeoutMillis = start.timeoutMillis,
    committedAtMillis = committedAtMillis,
)

internal fun RunOutcome.toStored(): IntentStore.StoredOutcome = when (this) {
    RunOutcome.Succeeded -> IntentStore.StoredOutcome.SUCCEEDED
    RunOutcome.Failed -> IntentStore.StoredOutcome.FAILED
    RunOutcome.Cancelled -> IntentStore.StoredOutcome.CANCELLED
    is RunOutcome.Crashed -> IntentStore.StoredOutcome.crashed(message)
    RunOutcome.Interrupted -> IntentStore.StoredOutcome.INTERRUPTED
}
