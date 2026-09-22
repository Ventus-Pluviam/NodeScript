package com.autoscript.appservice.scheduler.core

import com.autoscript.domain.core.Clock
import com.autoscript.domain.core.SystemClock
import java.util.concurrent.atomic.AtomicLong

/**
 * 内存意图日志（JVM 单测 / 非持久原型用）。
 * 生产替换为 SQLite 实现（:app 装配层），语义对齐：
 * append-only、按 runId 单调、COMMIT 不可逆、**线程安全**（五类触发源都收敛到同一路径，
 * 无单线程收敛声明——实现自身必须提供互斥）。
 */
class InMemoryIntentLog(private val now: Clock = SystemClock) : IntentLog {

    private val lock = Any()                               // 全局互斥：所有读写都在单事务内
    private val counter = AtomicLong(0)                    // runId 单调源（仅锁内递增）
    private val store = mutableListOf<IntentRun>()         // append-only 视图（STARTED→COMMITTED）
    private val committedNonces = hashSetOf<String>()      // 已 COMMIT nonce（Interrupted 不计入）

    override suspend fun appendStart(
        projectId: String,
        scriptPath: String,
        runNonce: String,
        trigger: TriggerSource,
        scheduledAtMillis: Long,
        screen: ScreenGuarantee,
        deadlineMillis: Long?,
        args: List<String>,
        timeoutMillis: Long?,
    ): IntentRun = synchronized(lock) {
        // 原子幂等兜底（§8.5 + 评审 S3）：同一 nonce 已有 STARTED 存活行，或该 nonce 的副作用
        // 已 COMMIT 过，都拒绝——杜绝 isCommitted 预检的 check-then-act 竞态（SQLite 唯一索引对齐）。
        // 唯一合法重投路径是 reopen：先封口为 Interrupted（outcome≠null）再借同一 nonce 重开。
        if (store.any { it.runNonce == runNonce && it.outcome == null }) {
            throw IllegalStateException("runNonce 已有未完成的 STARTED 行，拒绝重复投递: $runNonce")
        }
        if (runNonce in committedNonces) {
            throw IllegalStateException("runNonce 已有 COMMIT，拒绝重复投递: $runNonce")
        }
        val run = IntentRun(
            runId = counter.incrementAndGet(),
            projectId = projectId,
            scriptPath = scriptPath,
            runNonce = runNonce,
            trigger = trigger,
            scheduledAtMillis = scheduledAtMillis,
            screen = screen,
            args = args,
            timeoutMillis = timeoutMillis,
            outcome = null,
            startedAtMillis = now.nowMillis(),
            deadlineMillis = deadlineMillis,
        )
        store += run
        run
    }

    override suspend fun commit(runId: Long, outcome: RunOutcome): IntentRun? = synchronized(lock) {
        val idx = store.indexOfFirst { it.runId == runId }
        if (idx < 0) return null
        val prev = store[idx]
        if (prev.outcome != null) return prev                               // 重复 COMMIT 幂等
        val done = prev.copy(outcome = outcome, committedAtMillis = now.nowMillis())
        store[idx] = done
        // 只有真实完成（非恢复封口）才计入幂等集合；Interrupted 正是要重投的那一次（见 reopen）
        if (outcome != RunOutcome.Interrupted) committedNonces += prev.runNonce
        done
    }

    override suspend fun reopen(oldRunId: Long): IntentRun = synchronized(lock) {
        val idx = store.indexOfFirst { it.runId == oldRunId }
        require(idx >= 0) { "旧 runId 不存在，无法重开: $oldRunId" }
        val old = store[idx]
        require(old.outcome == null) { "旧意向已 COMMIT，不能重开: $oldRunId" }
        // 封口（Interrupted 不计入 committedNonces——同一 nonce 要重新入队）
        val sealed = old.copy(
            outcome = RunOutcome.Interrupted,
            committedAtMillis = now.nowMillis(),
        )
        store[idx] = sealed
        // 重开：新 runId + 保留原 runNonce（§8.5 幂等锚点，恢复重投不丢失 screen 契约）
        val fresh = IntentRun(
            runId = counter.incrementAndGet(),
            projectId = old.projectId,
            scriptPath = old.scriptPath,
            runNonce = old.runNonce,
            trigger = old.trigger,
            scheduledAtMillis = old.scheduledAtMillis,
            screen = old.screen,
            args = old.args,                       // 恢复重投不得丢脚本参数（§8.5）
            timeoutMillis = old.timeoutMillis,     // 恢复重投不得丢脚本超时（§8.5）
            outcome = null,
            startedAtMillis = now.nowMillis(),
            deadlineMillis = old.deadlineMillis,   // 恢复重投不得变期限：否则同一意向两套到期口径
        )
        store += fresh
        fresh
    }

    override suspend fun uncommitted(): List<IntentRun> = synchronized(lock) {
        store.filter { it.outcome == null }
    }

    override suspend fun isCommitted(nonce: String): Boolean = synchronized(lock) {
        nonce in committedNonces
    }

    override suspend fun all(): List<IntentRun> = synchronized(lock) {
        store.toList()
    }
}