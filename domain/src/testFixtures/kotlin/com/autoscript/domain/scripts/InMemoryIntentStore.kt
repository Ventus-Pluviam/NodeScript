package com.autoscript.domain.scripts

import java.util.concurrent.atomic.AtomicLong

/**
 * 内存意图存储（纯 JVM，零 IO）：§8.5 契约套件的一份实现，也是各模块测试的替身。
 *
 * **为什么需要它**：`IntentStoreContract` 是「同一组用例打在每一个实现上」的那份规格，
 * 而唯一的生产实现 `SqliteIntentStore` 要宿主 `sqlite3`（测试里 `assumeTrue` 门禁）——
 * 门禁一挂，整套语义规格就静默不跑了。本实现**无环境门禁**，把这条兜住。
 *
 * **「崩溃持久」怎么表达**：契约套件的 `open()` 每次必须给**新实例**（那是「重启后重开」
 * 的模拟）。本实现把持久性换成「同一份 [State] 跨实例共享」—— 两次构造传同一个 [State]，
 * 新实例就看得见旧实例写下的行。
 *
 * **它不是生产实现，也不该变成生产实现**：不落盘 = 进程被杀即丢，§8.5 的崩溃恢复
 * 在它上面**不成立**。生产只有 `SqliteIntentStore` 一条路（装配见 `PlatformWiring.intentStore`，
 * 打不开即装配失败 —— fail closed，不回落）。
 *
 * @param state 跨实例共享的底层数据；缺省 = 新建一份空的。
 */
class InMemoryIntentStore(private val state: State = State()) : IntentStore {

    /**
     * 跨实例共享的存储状态。
     *
     * **线程安全**：所有读写都在 [lock] 内 —— 契约的并发用例（同 nonce 直投恰好一个赢家）
     * 要求存储层自身提供互斥，不是调用方保证。
     */
    class State {
        internal val lock = Any()
        internal val nextId = AtomicLong(1)
        internal val rows = sortedMapOf<Long, IntentStore.StoredRow>()
        internal val liveNonce = HashMap<String, Long>()
        internal val committedNonce = HashMap<String, Long>()
    }

    override fun insertStart(row: IntentStore.StartRow): Long = synchronized(state.lock) {
        if (row.runNonce in state.liveNonce) {
            throw IllegalStateException("runNonce 已有未完成的 STARTED 行，拒绝重复投递: ${row.runNonce}")
        }
        if (row.runNonce in state.committedNonce) {
            throw IllegalStateException("runNonce 已有 COMMIT，拒绝重复投递: ${row.runNonce}")
        }
        val runId = state.nextId.getAndIncrement()
        state.rows[runId] = IntentStore.StoredRow(runId, row, null, null)
        state.liveNonce[row.runNonce] = runId
        runId
    }

    override fun seal(runId: Long, outcome: IntentStore.StoredOutcome): IntentStore.StoredRow? =
        synchronized(state.lock) {
            val old = state.rows[runId] ?: return null
            if (old.outcome != null) return old            // 幂等：已终态返回现态
            val nonce = old.start.runNonce
            if (outcome.name != INTERRUPTED && nonce in state.committedNonce) {
                throw IllegalStateException("runNonce 已 COMMIT，拒绝重复副作用: $nonce")
            }
            val at = System.currentTimeMillis()
            val sealed = old.copy(outcome = outcome, committedAtMillis = at)
            state.rows[runId] = sealed
            state.liveNonce.remove(nonce)
            if (outcome.name != INTERRUPTED) state.committedNonce[nonce] = runId
            sealed
        }

    override fun sealAndReopen(oldRunId: Long): Long = synchronized(state.lock) {
        val old = state.rows[oldRunId]
            ?: throw IllegalArgumentException("旧 runId 不存在，无法重开: $oldRunId")
        require(old.outcome == null) { "旧意向已 COMMIT，不能重开: $oldRunId" }
        val at = System.currentTimeMillis()
        state.rows[oldRunId] = old.copy(
            outcome = IntentStore.StoredOutcome.INTERRUPTED,
            committedAtMillis = at,
        )
        state.liveNonce.remove(old.start.runNonce)
        val newId = state.nextId.getAndIncrement()
        val fresh = IntentStore.StoredRow(
            newId,
            old.start.copy(startedAtMillis = at),
            null,
            null,
        )
        state.rows[newId] = fresh
        state.liveNonce[old.start.runNonce] = newId
        newId
    }

    override fun liveRows(): List<IntentStore.StoredRow> = synchronized(state.lock) {
        state.rows.values.filter { it.outcome == null }
    }

    override fun allRows(): List<IntentStore.StoredRow> = synchronized(state.lock) {
        state.rows.values.toList()
    }

    override fun hasCommittedNonce(nonce: String): Boolean = synchronized(state.lock) {
        nonce in state.committedNonce
    }

    override fun hasLiveNonce(nonce: String): Boolean = synchronized(state.lock) {
        nonce in state.liveNonce
    }

    private companion object {
        /** 恢复封口：不构成一次完成的对外副作用，正是要重投的那一次（见 `IntentStore`）。 */
        const val INTERRUPTED = "INTERRUPTED"
    }
}
