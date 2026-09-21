package com.autoscript.appservice.scheduler.core

import com.autoscript.domain.scripts.EngineRunLink
import com.autoscript.domain.scripts.RunArchive
import com.autoscript.domain.scripts.RunRecord
import com.autoscript.domain.scripts.RunState
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * §8.5 归档入口（scheduler 侧验证）：
 * - `appendStart` 之后 dispatch 的 [PendingRun.intentRunId] 必须是**日志已落行**的那个
 *   runId（先写日志后执行），dispatcher 回执的 [EngineRunLink] 由 scheduler 成对归档；
 * - 只对真实产生引擎执行的投递落档案（link != null），绝不写孤儿记录；
 * - 崩溃恢复重投同样成对归档（新 runId + 保留 runNonce）。
 */
class SchedulerArchiveTest {

    private class RecordingProvider : SchedulerProvider {
        override suspend fun registerTrigger(targetFireAtMillis: Long, taskId: String): TriggerHandle =
            TriggerHandle { }

        override suspend fun cancelTrigger(handle: TriggerHandle) = handle.cancel()
    }

    /** 回执型假 dispatcher：按 linkFor 决定「这次投递是否真的产生了引擎执行」。 */
    private class LinkingDispatcher(
        private val linkFor: (PendingRun) -> EngineRunLink?,
        private val outcome: RunOutcome = RunOutcome.Succeeded,
    ) : RunDispatcher {
        override suspend fun dispatch(pending: PendingRun): RunOutcome = outcome
        override suspend fun dispatchToReport(pending: PendingRun): DispatchReport =
            DispatchReport(outcome, linkFor(pending))
    }

    private var now = 1_000_000L
    private var nonceSeq = 0

    /** 典型回执：intentRunId + 1 → engineRunId = 9000 + intentRunId（便于断言）。 */
    private val engineLink: (PendingRun) -> EngineRunLink? = { p ->
        p.intentRunId?.let { EngineRunLink(intentRunId = it, engineRunId = 9_000 + it) }
    }

    private fun scheduler(
        log: IntentLog,
        archive: RunArchive?,
        dispatcher: RunDispatcher,
    ) = Scheduler(
        provider = RecordingProvider(),
        log = log,
        dispatcher = dispatcher,
        archive = archive,
        nonceFactory = { "nonce-${++nonceSeq}" },
        clock = { now },
    )

    private fun freshLog() = InMemoryIntentLog(InMemoryIntentLog.RuntimeClock { now })

    @Test
    fun `appendStart 后 dispatch 带日志 runId，归档成对写入`() = runBlocking {
        val archive = InMemoryRunArchive()
        val log = freshLog()
        val s = scheduler(log, archive, LinkingDispatcher(engineLink))

        s.schedule(ScheduledTask("t1", "任务", "p", "a.js", TimedSchedule.Once(0)))
        s.onTrigger("t1", scheduledAtMillis = now)

        val run = log.all().single()
        val records = archive.recordsOfIntent(run.runId)
        assertEquals(1, records.size, "归档按意图 runId 可追溯")
        assertTrue(records.single().id == 9_000L + run.runId, "engineRunId 来自 dispatcher 回执")
        assertEquals(EngineRunLink(run.runId, 9_000L + run.runId), archive.link(9_000L + run.runId))
        assertEquals(
            RunRecord(
                id = 9_000L + run.runId,
                projectId = "p",
                scriptPath = "a.js",
                runNonce = run.runNonce,
                state = RunState.SUCCEEDED,
                startedAtMillis = now,
                finishedAtMillis = now,
            ),
            records.single(),
            "档案带项目/脚本/nonce/起止时刻：dispatcher 返回时执行已终结，落地即终态",
        )
    }

    @Test
    fun `未产生引擎执行的投递不写档案（无孤儿记录）`() = runBlocking {
        val archive = InMemoryRunArchive()
        val log = freshLog()
        val s = scheduler(log, archive, LinkingDispatcher({ null }))   // 门禁拒绝/排队超时/启动失败

        s.schedule(ScheduledTask("t2", "任务", "p", "a.js", TimedSchedule.Once(0)))
        s.onTrigger("t2", scheduledAtMillis = now)

        assertEquals(1, log.all().size, "意图日志仍如实投递")
        assertTrue(archive.recordsOfProject("p").isEmpty(), "无引擎执行 → 无档案")
    }

    @Test
    fun `崩溃恢复重投同样成对归档（新 runId 保留 nonce）`() = runBlocking {
        val archive = InMemoryRunArchive()
        val log = freshLog()
        val s = scheduler(log, archive, LinkingDispatcher(engineLink))

        // 模拟崩溃遗留：RUN_START 已写但未 COMMIT
        log.appendStart("p", "a.js", "nonce-orphan", TriggerSource.TIMED, now)

        s.recoverUncommitted()

        val recovered = log.all().last()               // reopen 重开的新行
        assertEquals(1, archive.recordsOfIntent(recovered.runId).size, "恢复重投也归档")
        assertEquals(
            "nonce-orphan",
            archive.recordsOfIntent(recovered.runId).single().runNonce,
            "nonce 随档案保留（幂等锚点可追溯）",
        )
    }

    @Test
    fun `未接归档的骨架 scheduler 不炸（archive 缺省）`() = runBlocking {
        val log = freshLog()
        val s = scheduler(log, null, LinkingDispatcher(engineLink))

        s.schedule(ScheduledTask("t3", "任务", "p", "a.js", TimedSchedule.Once(0)))
        s.onTrigger("t3", scheduledAtMillis = now)

        assertEquals(RunOutcome.Succeeded, log.all().single().outcome, "归档缺省不影响投递/COMMIT")
    }

    @Test
    fun `归档按 outcome 落终态：失败对偶 FAILED、被杀对偶 CRASHED`() = runBlocking {
        suspend fun stateOf(outcome: RunOutcome): RunState {
            val archive = InMemoryRunArchive()
            val log = freshLog()
            val s = scheduler(log, archive, LinkingDispatcher(engineLink, outcome))
            s.schedule(ScheduledTask("t-$outcome", "任务", "p", "a.js", TimedSchedule.Once(0)))
            s.onTrigger("t-$outcome", scheduledAtMillis = now)
            return archive.recordsOfIntent(log.all().single().runId).single().state
        }
        assertEquals(RunState.FAILED, stateOf(RunOutcome.Failed))
        assertEquals(RunState.CRASHED, stateOf(RunOutcome.Crashed("看门狗强杀")))
        assertEquals(RunState.CANCELLED, stateOf(RunOutcome.Cancelled))
    }

    @Test
    fun `恢复结算旧档案孤儿：宿主死时没结算的 RUNNING 如实 CRASHED`() = runBlocking {
        val archive = InMemoryRunArchive()
        val log = freshLog()
        val s = scheduler(log, archive, LinkingDispatcher(engineLink))

        // 崩溃前那次执行：档案里 RUNNING，但宿主死了（新进程永远结算不了它）
        // 遗留意向的旧行恰好就是那次执行的意图行：reopen 会封口它，孤儿结算认它
        val old = log.appendStart("p", "a.js", "nonce-orphan", TriggerSource.TIMED, now)
        archive.put(
            RunRecord(
                id = 9_555L,
                projectId = "p",
                scriptPath = "a.js",
                runNonce = "nonce-before-crash",
                state = RunState.RUNNING,
                startedAtMillis = now,
            ),
            EngineRunLink(intentRunId = old.runId, engineRunId = 9_555L),
        )

        s.recoverUncommitted()

        assertEquals(RunState.CRASHED, archive.record(9_555L)!!.state, "失联的执行如实 CRASHED")
        assertTrue(archive.unfinished().none { it.id == 9_555L }, "unfinished 不再泄漏")
    }
}
