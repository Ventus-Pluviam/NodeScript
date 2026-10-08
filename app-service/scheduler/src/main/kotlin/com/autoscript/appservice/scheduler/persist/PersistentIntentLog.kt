package com.autoscript.appservice.scheduler.persist

import com.autoscript.appservice.scheduler.core.IntentLog
import com.autoscript.appservice.scheduler.core.IntentRun
import com.autoscript.appservice.scheduler.core.RunOutcome
import com.autoscript.appservice.scheduler.core.ScreenGuarantee
import com.autoscript.appservice.scheduler.core.TriggerSource
import com.autoscript.domain.scripts.IntentStore

/**
 * 持久化意图日志（docs §8.5 生产实现）：语义与 [InMemoryIntentLog] 严格一致，
 * 存储引擎由 [IntentStore] 注入 —— 生产走 SQLite（`SqliteIntentStore`，
 * 装配在 `PlatformWiring.intentStore`，打不开即装配失败，**无回落**），
 * 测试走 `:domain` `testFixtures` 的 `InMemoryIntentStore`。
 * 换引擎不改语义：两个实现共用 `:domain` 的 `IntentStoreContract` 同一组用例。
 *
 * 崩溃恢复路径（§8.5「启动即回放，恢复只跟随 COMMIT」）：
 * 构造后 [IntentLog.uncommitted] 即存活行（= 崩溃遗留意向），调度器据此 reopen 重投。
 */
class PersistentIntentLog(
    private val store: IntentStore,
    private val now: () -> Long = { System.currentTimeMillis() },
) : IntentLog {

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
    ): IntentRun {
        val runId = store.insertStart(
            IntentStore.StartRow(
                projectId = projectId,
                scriptPath = scriptPath,
                runNonce = runNonce,
                trigger = trigger.name,
                screen = screen.name,
                scheduledAtMillis = scheduledAtMillis,
                startedAtMillis = now(),
                deadlineMillis = deadlineMillis,
                args = args,
                timeoutMillis = timeoutMillis,
            ),
        )
        return store.allRows().first { it.runId == runId }.toIntentRun()
    }

    override suspend fun commit(runId: Long, outcome: RunOutcome): IntentRun? =
        store.seal(runId, outcome.toStored())?.toIntentRun()

    override suspend fun reopen(oldRunId: Long): IntentRun {
        val newId = store.sealAndReopen(oldRunId)
        return store.allRows().first { it.runId == newId }.toIntentRun()
    }

    override suspend fun uncommitted(): List<IntentRun> = store.liveRows().map { it.toIntentRun() }

    override suspend fun isCommitted(nonce: String): Boolean = store.hasCommittedNonce(nonce)

    override suspend fun all(): List<IntentRun> = store.allRows().map { it.toIntentRun() }

    fun close() = store.close()
}
