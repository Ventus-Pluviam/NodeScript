package com.autoscript.domain.scripts

/**
 * 意图日志原子存储操作（docs §8.5「append-only + 崩溃持久」的最小接缝）。
 *
 * 本接口是「意图日志语义」与「存储引擎」之间的唯一边界。
 *
 * **实现**：
 * - `SqliteIntentStore`（`:platform:system`）—— §8.5 契约写的 SQLite 形态，
 *   （`SQLiteOpenHelper` 依赖 `android.`），**生产唯一实现**；
 * - [InMemoryIntentStore]（本模块 `testFixtures`）—— 零 IO 的测试替身，
 *   跑同一套契约用例。**它不是生产实现**：不落盘 = 崩溃恢复在它上面不成立。
 *
 * **生产没有第二条路，也没有回落**（2026-10-08 裁定）：SQLite 打不开 = 装配失败，
 * 壳保持未就绪、闹钟走漏投记账（`AppShellApplication.installWithFiles` 的失败分支）。
 * 原先「打不开就回落 jsonl」的那条路已删 —— 回落目标（jsonl 追加式存储）的
 * 「runId 不复用」与「幂等锚点原子」两条都靠单写者假设撑着（`max+1` 发号、锁内先查后写），
 * 那不是降级，是把「调度坏了」伪装成「调度还能用」。
 *
 * **为什么接口住 `:domain` 而不是实现模块**：本接口就是 SPI（与 [RunArchive] 同形），
 * 而依赖铁律是 `:platform:*` → `:domain`（不反向）—— 接口若留在
 * `:app-service:scheduler`，`:platform:system` 够不到它，SQLite 实现只能靠给
 * scheduler 加 Android 依赖来换（那会丢掉这一层的纯 JVM 可测）。
 *
 * 每条实现都必须满足：
 * - **崩溃持久**：insert/seal 返回前数据已落盘（SQLite synchronous=FULL）；
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

    // —— 存储行模型（与 scheduler 的 IntentRun 一一对应，独立类型避免本层依赖运行态）——

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
