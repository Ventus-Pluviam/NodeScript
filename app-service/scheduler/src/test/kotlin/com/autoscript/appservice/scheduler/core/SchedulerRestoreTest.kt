package com.autoscript.appservice.scheduler.core

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 注册表持久与恢复（§8.6 调度持久性缺口的补账）：
 * - schedule/cancel 直写 store（先落盘后动内存/闹钟）；
 * - `restoreTasks` 在 `recoverUncommitted` 之前重建排期（bootRecover 顺序）；
 * - store 为 null 时退化为纯内存（骨架/旧单测不炸）。
 */
class SchedulerRestoreTest {

    private class RecordingProvider : SchedulerProvider {
        val fires = mutableListOf<Pair<Long, String>>()
        override suspend fun registerTrigger(targetFireAtMillis: Long, taskId: String): TriggerHandle {
            fires += targetFireAtMillis to taskId
            return TriggerHandle { }
        }
        override suspend fun cancelTrigger(handle: TriggerHandle) = handle.cancel()
    }

    private var now = 1_000_000L
    private var nonceSeq = 0

    private fun scheduler(
        provider: RecordingProvider,
        store: TaskStore?,
        log: IntentLog = InMemoryIntentLog(InMemoryIntentLog.RuntimeClock { now }),
    ) = Scheduler(
        provider = provider,
        log = log,
        dispatcher = RunDispatcher { RunOutcome.Succeeded },
        nonceFactory = { "nonce-${++nonceSeq}" },
        clock = { now },
        taskStore = store,
    )

    @Test
    fun `schedule 直写 store：重启后新实例 restore 重建排期`() = runBlocking {
        val store = InMemoryTaskStore()
        val p1 = RecordingProvider()
        val s1 = scheduler(p1, store)
        s1.schedule(ScheduledTask("t1", "每日", "p", "a.js", TimedSchedule.Daily(9, 30)))
        assertEquals(1, store.loadAll().size, "schedule 先落盘")

        // 新进程：全新 Scheduler + 全新 provider，同一 store
        val p2 = RecordingProvider()
        val s2 = scheduler(p2, store)
        assertTrue(s2.tasks().isEmpty(), "新实例内存是空的")
        val restored = s2.restoreTasks()
        assertEquals(listOf("t1"), restored.map { it.id })
        assertEquals(listOf("t1"), s2.tasks().map { it.id }, "内存注册表重建")
        assertEquals(listOf("t1"), p2.fires.map { it.second }, "闹钟续排（Daily 非空计划）")
    }

    @Test
    fun `cancel 落 tombstone：重启后不再回来`() = runBlocking {
        val store = InMemoryTaskStore()
        val s1 = scheduler(RecordingProvider(), store)
        s1.schedule(ScheduledTask("t1", "一", "p", "a.js", TimedSchedule.Once(60)))
        s1.schedule(ScheduledTask("t2", "二", "p", "b.js", TimedSchedule.Once(60)))
        s1.cancel("t1")
        assertEquals(listOf("t2"), store.loadAll().map { it.id })

        val s2 = scheduler(RecordingProvider(), store)
        s2.restoreTasks()
        assertEquals(listOf("t2"), s2.tasks().map { it.id })
    }

    @Test
    fun `store 为 null 时纯内存：restore 空表不炸`() = runBlocking {
        val s = scheduler(RecordingProvider(), null)
        s.schedule(ScheduledTask("t1", "一", "p", "a.js", TimedSchedule.Once(60)))
        assertEquals(emptyList<ScheduledTask>(), s.restoreTasks())
        assertEquals(listOf("t1"), s.tasks().map { it.id }, "内存行为不受影响")
    }

    @Test
    fun `disabled 任务留名不续排`() = runBlocking {
        val store = InMemoryTaskStore()
        val p1 = RecordingProvider()
        scheduler(p1, store).schedule(
            ScheduledTask("t1", "关", "p", "a.js", TimedSchedule.Once(60), enabled = false),
        )
        val p2 = RecordingProvider()
        val s2 = scheduler(p2, store)
        s2.restoreTasks()
        assertEquals(listOf("t1"), s2.tasks().map { it.id }, "名在注册表里")
        assertTrue(p2.fires.isEmpty(), "disabled 不注册闹钟（rearmFor 直接返回）")
    }

    @Test
    fun `Once 触发后落 tombstone：重启不再复活`() = runBlocking {
        val store = InMemoryTaskStore()
        val log = InMemoryIntentLog(InMemoryIntentLog.RuntimeClock { now })
        val mk: (IntentLog) -> Scheduler = { l ->
            Scheduler(
                provider = RecordingProvider(), log = l,
                dispatcher = RunDispatcher { RunOutcome.Succeeded },
                nonceFactory = { "nonce-${++nonceSeq}" }, clock = { now }, taskStore = store,
            )
        }
        val s1 = mk(log)
        s1.schedule(ScheduledTask("t1", "一", "p", "a.js", TimedSchedule.Once(60)))
        s1.onTrigger("t1", scheduledAtMillis = now)   // 触发 → 终态化 + tombstone
        assertTrue(store.loadAll().isEmpty(), "Once 终态即删注册表（落 tombstone）")

        // 新进程：同 store —— restore 不得把它续回来
        val s2 = mk(log)
        assertTrue(s2.restoreTasks().isEmpty())
        assertTrue(s2.tasks().isEmpty(), "已执行的 Once 重启不复活")
        s2.onTrigger("t1", scheduledAtMillis = now)
        assertEquals(1, log.all().size, "复活触发无任务可投（不双跑）")
    }

    @Test
    fun `恢复后闹钟仍响：onTrigger 可投递`() = runBlocking {
        var dispatched = 0
        val store = InMemoryTaskStore()
        val log = InMemoryIntentLog(InMemoryIntentLog.RuntimeClock { now })
        val s1 = Scheduler(
            provider = RecordingProvider(), log = log,
            dispatcher = RunDispatcher { dispatched++; RunOutcome.Succeeded },
            nonceFactory = { "nonce-${++nonceSeq}" }, clock = { now }, taskStore = store,
        )
        s1.schedule(ScheduledTask("t1", "一", "p", "a.js", TimedSchedule.Once(60)))

        // 新进程同 log 同 store：先续排再触发（bootRecover 顺序）
        val s2 = Scheduler(
            provider = RecordingProvider(), log = log,
            dispatcher = RunDispatcher { dispatched++; RunOutcome.Succeeded },
            nonceFactory = { "nonce-${++nonceSeq}" }, clock = { now }, taskStore = store,
        )
        s2.restoreTasks()
        s2.onTrigger("t1", scheduledAtMillis = now)
        assertEquals(1, dispatched, "续排后的任务可正常投递")
    }
}
