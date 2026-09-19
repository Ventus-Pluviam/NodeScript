package com.autoscript.shell

import com.autoscript.appservice.scheduler.core.IntentLog
import com.autoscript.appservice.scheduler.core.InMemoryIntentLog
import com.autoscript.appservice.scheduler.core.RunDispatcher
import com.autoscript.appservice.scheduler.core.RunOutcome
import com.autoscript.appservice.scheduler.core.Scheduler
import com.autoscript.appservice.scheduler.core.ScheduledTask
import com.autoscript.appservice.scheduler.core.SchedulerProvider
import com.autoscript.appservice.scheduler.core.TimedSchedule
import com.autoscript.appservice.scheduler.core.TriggerHandle
import com.autoscript.appservice.scheduler.core.TriggerSource
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 闹钟回投缝验证（§8.6「setExactAndAllowWhileIdle → receiver」的最后一跳）：
 * - **没接路线时不静默丢弃**：漏投进账本，UI 有处可查（而不是"任务这一轮没了"）；
 * - 接上路线后投递走 [Scheduler.onTrigger]（TIMED 来源 + 真实排期），
 *   于是 runNonce/意图日志/dispatcher 的口径与手动触发完全一致；
 * - `Once` 任务经闹钟投递后终态化 —— 重复广播不会让它再跑一遍。
 */
class AlarmDispatchTest {

    /** 记录型触发出线：不碰真实闹钟，只记 taskId 是否被重新排期。 */
    private class FakeProvider : SchedulerProvider {
        val fires = mutableListOf<Pair<Long, String>>()
        var now = 10_000L
        override suspend fun registerTrigger(targetFireAtMillis: Long, taskId: String): TriggerHandle {
            fires += targetFireAtMillis to taskId
            return TriggerHandle { }
        }
        override suspend fun cancelTrigger(handle: TriggerHandle) = handle.cancel()
        override val wakeAheadMillis: Long get() = 0
    }

    /** 记录型 dispatcher：记下被投递的 run（`queueTimeoutMillis` 是 ControllerRunDispatcher 侧的概念）。 */
    private class RecordingDispatcher :
        com.autoscript.appservice.scheduler.core.RunDispatcher {
        val delivered = mutableListOf<com.autoscript.appservice.scheduler.core.PendingRun>()
        override suspend fun dispatch(
            run: com.autoscript.appservice.scheduler.core.PendingRun,
        ) = com.autoscript.appservice.scheduler.core.RunOutcome.Succeeded.also { delivered += run }
    }

    private var now = 10_000L

    private fun setup(log: IntentLog = InMemoryIntentLog(InMemoryIntentLog.RuntimeClock { now })) =
        Triple(FakeProvider(), RecordingDispatcher(), log)

    private fun scheduler(
        provider: SchedulerProvider,
        dispatcher: RunDispatcher,
        log: IntentLog,
    ) = Scheduler(provider, log, dispatcher, null, clock = { now })

    private fun onceTask(id: String) = ScheduledTask(
        id = id, name = id, projectId = "p", scriptPath = "a.js",
        schedule = TimedSchedule.Once(now + 60_000),
    )

    @Test
    fun `没接路线时漏投记账而非静默丢弃`() = runBlocking {
        val dispatch = AlarmDispatch { now }
        assertFalse(dispatch.fire("t1"), "无路线：不投递")
        assertEquals(mapOf("t1" to now), dispatch.missed(), "漏投进账本（UI 有处可查）")
        assertFalse(dispatch.isRouted)
        Unit
    }

    @Test
    fun `装上路线后误投清账`() = runBlocking {
        val dispatch = AlarmDispatch { now }
        dispatch.fire("t1")
        assertTrue(dispatch.missed().containsKey("t1"), "先漏投")

        dispatch.install(AlarmRoute { })                       // 路线接上（Application 装配完成）
        assertTrue(dispatch.isRouted)
        val drained = dispatch.drainMissed()
        assertEquals(mapOf("t1" to now), drained, "呈现过一次后清账")
        assertTrue(dispatch.missed().isEmpty())

        assertTrue(dispatch.fire("t1"), "有路线：真的投了")
        assertTrue(dispatch.missed().isEmpty(), "成功投递不再进漏投账")
    }

    @Test
    fun `摘除路线回到记账状态`() = runBlocking {
        val dispatch = AlarmDispatch { now }
        dispatch.install(AlarmRoute { })
        assertFalse(dispatch.fire("t1") == false, "有路线 => 投递")

        dispatch.install(null)
        assertFalse(dispatch.fire("t2"), "摘除后回到「记账不投递」")
        assertEquals(setOf("t2"), dispatch.missed().keys)
    }

    @Test
    fun `同一任务反复漏投只留最新一条`() = runBlocking {
        val dispatch = AlarmDispatch { now }
        dispatch.recordMissed("t1")
        now += 1_000
        dispatch.recordMissed("t1")
        assertEquals(mapOf("t1" to now), dispatch.missed(), "有界：一条任务一行，不无限增长")
    }

    @Test
    fun `SchedulerAlarmRoute 走 onTrigger 的 TIMED 路径`() = runBlocking {
        val (provider, dispatcher, log) = setup()
        val s = scheduler(provider, dispatcher, log)
        val scheduledAt = now + 60_000
        s.schedule(onceTask("t1"))

        // 闹钟响 → 路线以 TIMED 来源 + 排期时刻投递（与手动点击的口径区分在 TriggerSource）
        val alarmAt = 20_000L
        var passedAt = 0L
        val route = SchedulerAlarmRoute(s) { alarmAt }
        route.fire("t1")

        val nonce = log.all().single().runNonce
        assertTrue(nonce.isNotEmpty(), "投递经 onTrigger：runNonce 已生成（绕过它会双跑）")
        passedAt = log.all().single().deadlineMillis ?: -1
        assertTrue(passedAt >= 0, "期限随行落档")
        assertEquals(1, dispatcher.delivered.size, "投递真的到了 dispatcher")
        assertEquals(RunOutcome.Succeeded, log.all().single().outcome,
            "dispatcher 回报 Succeeded：行由 Scheduler 侧封口（不留未完成意向）")
        Unit
    }

    @Test
    fun `Once 任务经闹钟投递后终态化`() = runBlocking {
        val (provider, dispatcher, log) = setup()
        val s = scheduler(provider, dispatcher, log)
        s.schedule(onceTask("t1"))
        val firesBefore = provider.fires.size

        val route = SchedulerAlarmRoute(s) { now + 60_000 }
        route.fire("t1")

        assertEquals(firesBefore, provider.fires.size, "Once 末次触发后不再续排闹钟（§8.6 不双路径）")
        assertEquals(1, log.all().size, "只有一行意图日志")
        assertEquals(1, dispatcher.delivered.size)
    }

    @Test
    fun `周期任务经闹钟投递后续排下一轮`() = runBlocking {
        val (provider, dispatcher, log) = setup()
        val s = scheduler(provider, dispatcher, log)
        val daily = ScheduledTask(
            id = "d1", name = "d", projectId = "p", scriptPath = "a.js",
            schedule = TimedSchedule.Daily(9, 0),
        )
        s.schedule(daily)
        val firesBefore = provider.fires.size

        // 闹钟以真实排期时刻回投：2200-06-01 09:00:00Z 的一次触发
        SchedulerAlarmRoute(s) { 1_780_400_400_000L }.fire("d1")

        assertEquals(firesBefore + 1, provider.fires.size, "Daily 触发后自推进到下一轮")
        assertEquals(1, dispatcher.delivered.size)
        assertEquals(TriggerSource.TIMED, log.all().single().trigger, "闹钟来源 = TIMED")
        assertEquals(RunOutcome.Succeeded, log.all().single().outcome, "投递已封口：不留下未完成意向")
        assertTrue(log.uncommitted().isEmpty(), "周期任务续排的是下一轮的闹钟，不是再开一条未完成意向")
    }

    @Test
    fun `排期时刻取自路线而非当前时刻`() = runBlocking {
        val (provider, dispatcher, log) = setup()
        val s = scheduler(provider, dispatcher, log)
        val past = now - 30_000
        s.schedule(onceTask("t1"))

        // 排期已过：deadline 仍以排期为锚（+120s TIMED 上限），不以"现在"重置。
        SchedulerAlarmRoute(s) { past }.fire("t1")
        assertEquals(past + 120_000, log.all().single().deadlineMillis, "期限锚在排期上，不是触发时刻")
    }
}
