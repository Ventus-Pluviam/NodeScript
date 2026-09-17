package com.autoscript.appservice.scheduler.core

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SchedulerTest {

    private class RecordingProvider : SchedulerProvider {
        val fires = mutableListOf<Pair<Long, String>>()   // (fireAt, taskId)
        val cancels = mutableListOf<String>()
        override suspend fun registerTrigger(targetFireAtMillis: Long, taskId: String): TriggerHandle {
            fires += targetFireAtMillis to taskId
            return TriggerHandle { cancels += taskId }
        }
        override suspend fun cancelTrigger(handle: TriggerHandle) = handle.cancel()
    }

    private val dispatched = mutableListOf<PendingRun>()
    private var nextOutcome: RunOutcome = RunOutcome.Succeeded

    private var now = 1_000_000L
    private var nonceSeq = 0

    private fun testScheduler(
        provider: RecordingProvider = RecordingProvider(),
        log: IntentLog = InMemoryIntentLog(InMemoryIntentLog.RuntimeClock { now }),
    ) = Scheduler(
        provider = provider,
        log = log,
        dispatcher = RunDispatcher { p -> dispatched += p; nextOutcome },
        nonceFactory = { "nonce-${++nonceSeq}" },
        clock = { now },
    )

    @Test
    fun `Once 任务：登记即注册一次触发，触发后不再续排`() = runBlocking {
        val provider = RecordingProvider()
        val scheduler = testScheduler(provider)

        scheduler.schedule(ScheduledTask("t1", "跑一次", "p", "once.js", TimedSchedule.Once(60)))
        assertEquals(listOf(now + 60_000L to "t1"), provider.fires, "Once 只注册一次")

        scheduler.onTrigger("t1", scheduledAtMillis = now + 60_000)
        assertEquals(1, dispatched.size)
        assertEquals("nonce-1", dispatched[0].runNonce)

        now += 99_999
        scheduler.onTrigger("t1") // 不应再有动作（Once 末次）
        assertEquals(1, provider.fires.size)
        assertEquals(1, dispatched.size)
    }

    @Test
    fun `Daily 任务：登记后注册首日触发，触发完成自推进到下一日`() = runBlocking {
        val provider = RecordingProvider()
        val scheduler = testScheduler(provider)
        scheduler.schedule(ScheduledTask("d1", "每日", "p", "daily.js", TimedSchedule.Daily(9, 30)))
        val firstFire = provider.fires.single().first

        // 推进到排期时刻之后，模拟 alarm 真实触发
        now = firstFire + 1_000
        scheduler.onTrigger("d1", scheduledAtMillis = firstFire)
        assertEquals(2, provider.fires.size, "Daily 触发后应续排下一轮（再 24h）")
        assertEquals(firstFire + 24L * 3_600 * 1_000, provider.fires[1].first, "下次 fire = 次日同一时刻")
        assertEquals(1, dispatched.size)
        assertEquals(TriggerSource.TIMED, dispatched[0].trigger)
    }

    @Test
    fun `enabled 守卫：禁用后 TIMED 不投递，USER_CLICK 仍投递`() = runBlocking {
        val scheduler = testScheduler()
        scheduler.schedule(ScheduledTask("t1", "守卫", "p", "a.js", TimedSchedule.Once(60), enabled = false))

        scheduler.onTrigger("t1", scheduledAtMillis = now + 60_000)
        scheduler.onTrigger("t1", TriggerSource.USER_CLICK, scheduledAtMillis = now + 61_000)

        assertEquals(1, dispatched.size, "TIMED 被拦，仅 USER_CLICK 通过")
        assertEquals(TriggerSource.USER_CLICK, dispatched[0].trigger)
    }

    @Test
    fun `recoverUncommitted：旧意向封口 Interrupted 并保留 nonce 重投`() = runBlocking {
        val log = InMemoryIntentLog(InMemoryIntentLog.RuntimeClock { now })
        val scheduler = testScheduler(log = log)

        // 模拟崩溃遗留：RUN_START 已写但未 COMMIT
        log.appendStart("p", "a.js", "nonce-orphan", TriggerSource.TIMED, now)

        val recovered = scheduler.recoverUncommitted()

        assertEquals(1, recovered.size)
        assertEquals("nonce-orphan", recovered[0].runNonce, "恢复必须保留原 nonce（§8.5）")
        assertTrue(recovered[0].newRunId > recovered[0].oldRunId, "恢复分配新 runId")
        assertEquals(1, dispatched.size)
        assertEquals("nonce-orphan", dispatched[0].runNonce, "重投的 PendingRun 沿用同一 nonce")
        assertTrue(log.uncommitted().none { it.runId == recovered[0].oldRunId })
        assertTrue(log.uncommitted().none { it.runId == recovered[0].newRunId })
    }

    @Test
    fun `recoverUncommitted：无遗留则空恢复`() = runBlocking {
        val scheduler = testScheduler()
        val recovered = scheduler.recoverUncommitted()
        assertTrue(recovered.isEmpty())
    }

    @Test
    fun `非 TIMED 来源触发不续排闹钟（杜绝双路径）`() = runBlocking {
        val provider = RecordingProvider()
        val scheduler = testScheduler(provider)
        scheduler.schedule(ScheduledTask("d1", "每日", "p", "daily.js", TimedSchedule.Daily(9, 30)))
        val firstFire = provider.fires.single().first
        assertEquals(1, provider.fires.size, "登记只注册一次")

        // USER_CLICK / EVENT 等非 TIMED 触发：独立投递，不再 rearm（避免事件后吃到定时重复执行）
        now = firstFire - 60_000
        scheduler.onTrigger("d1", TriggerSource.USER_CLICK, scheduledAtMillis = now)
        scheduler.onTrigger("d1", TriggerSource.INTENT_BROADCAST, scheduledAtMillis = now)

        assertEquals(2, dispatched.size, "两次非 TIMED 触发各投一次")
        assertEquals(1, provider.fires.size, "非 TIMED 触发不触碰已有排期")
        assertEquals(0, provider.cancels.size, "原有闹钟句柄保持不变")
    }

    @Test
    fun `screen 契约透传：PendingRun 与意图日志同源`() = runBlocking {
        val scheduler = testScheduler()
        scheduler.schedule(
            ScheduledTask("s1", "亮屏任务", "p", "screen.js", TimedSchedule.Once(0), screen = ScreenGuarantee.SCREEN_ON)
        )
        scheduler.onTrigger("s1", scheduledAtMillis = now)

        assertEquals(ScreenGuarantee.SCREEN_ON, dispatched.single().screen, "PendingRun 携带屏幕契约")
        assertEquals(TriggerSource.TIMED, dispatched[0].trigger)
    }
}