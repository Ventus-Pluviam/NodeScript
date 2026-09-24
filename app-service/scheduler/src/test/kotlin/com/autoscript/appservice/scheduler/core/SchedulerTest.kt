package com.autoscript.appservice.scheduler.core

import com.autoscript.domain.core.Clock
import kotlinx.coroutines.runBlocking
import java.time.ZoneId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
        log: IntentLog = InMemoryIntentLog(Clock { now }),
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
    fun `Cron 任务：登记即注册首跳，TIMED 触发后自推进`() = runBlocking {
        val provider = RecordingProvider()
        val scheduler = testScheduler(provider)
        // now=1_000_000（周四 1970-01-01 00:16:40 UTC）：最近周一 09:00 是 1-05。
        scheduler.schedule(
            ScheduledTask("c1", "周一", "p", "cron.js", TimedSchedule.Cron("0 9 * * 1"), timezone = ZoneId.of("UTC")),
        )
        val firstFire = provider.fires.single().first
        val expect = TimedSchedule.Cron("0 9 * * 1").nextFireAfter(now, ZoneId.of("UTC"))
        requireNotNull(expect)
        assertEquals(expect, firstFire, "首跳走调度数学唯一出处（与 Daily 同一条 rearmFor）")

        now = firstFire + 1_000
        scheduler.onTrigger("c1", scheduledAtMillis = firstFire)
        assertEquals(2, provider.fires.size, "Cron 触发后应续排下一轮")
        assertEquals(1, dispatched.size, "USER_CLICK 之外只投一次")
        Unit
    }

    @Test
    fun `Cron 不可能日期：登记留名但不续排闹钟`() = runBlocking {
        val provider = RecordingProvider()
        val scheduler = testScheduler(provider)
        scheduler.schedule(
            ScheduledTask("cx", "二月三十", "p", "cron.js", TimedSchedule.Cron("0 0 30 2 *"), timezone = ZoneId.of("UTC")),
        )
        assertTrue(provider.fires.isEmpty(), "算不出下一跳：留名不续排（与停用任务同一口径）")
        assertEquals(listOf("cx"), scheduler.tasks().map { it.id }, "任务仍在册，不从列表消失")
        Unit
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
        val log = InMemoryIntentLog(Clock { now })
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
    fun `onTrigger 给每次投递写上到期时刻（排期 + 排队上限）`() = runBlocking {
        val scheduler = testScheduler()
        scheduler.schedule(ScheduledTask("t1", "demo", "p", "a.js", TimedSchedule.Once(60)))
        scheduler.onTrigger("t1", TriggerSource.TIMED, scheduledAtMillis = now)

        val deadline = dispatched.single().deadlineMillis
        // 触发源是 TIMED → 分级表最宽 120s（与 :app 的 DEFAULT_QUEUE_TIMEOUTS 同口径）
        assertEquals(now + 120_000, deadline, "到期时刻 = 排期 + 排队上限，写在投递上")
        assertTrue(dispatched.single().runNonce.isNotBlank())
    }

    @Test
    fun `排队上限分级表同口径且无无限等`() {
        val table = TriggerSource.entries.associateWith { DefaultDeadlines(it) }
        assertTrue(table.values.all { it > 0 }, "铁律 3：任何触发源都不许无限等: $table")
        assertTrue(
            table[TriggerSource.ENGINE_INTERNAL]!! < table[TriggerSource.TIMED]!!,
            "嵌套等待（持有者等后来者）须最先爆: $table",
        )
    }

    @Test
    fun `过期意向不重投：封口 Cancelled 且不投给 dispatcher`() = runBlocking {
        val log = InMemoryIntentLog(Clock { now })
        val scheduler = testScheduler(log = log)

        // 崩溃遗留：RUN_START 已写、期限只有 1s；宿主重启拖了 10 分钟才走到恢复路径
        log.appendStart(
            projectId = "p",
            scriptPath = "a.js",
            runNonce = "nonce-stale",
            trigger = TriggerSource.TIMED,
            scheduledAtMillis = now,
            deadlineMillis = now + 1_000,
        )
        now += 600_000                                   // 墙钟推进：重启后的恢复时刻

        val recovered = scheduler.recoverUncommitted()

        assertEquals(1, recovered.size)
        assertTrue(recovered[0].expired, "到期了：这一笔标记为过期未重投")
        assertEquals(RunOutcome.Cancelled, recovered[0].outcome, "过期如实封口 Cancelled（未获槽、未执行）")
        assertTrue(dispatched.isEmpty(), "绝不再投一个注定迟到的任务")
        val rows = log.all()
        assertEquals(2, rows.size, "旧行封口 + 新行记账：历史可追溯「为何没跑」")
        assertEquals(RunOutcome.Interrupted, rows[0].outcome, "旧行按 §8.5 封口")
        assertEquals(RunOutcome.Cancelled, rows[1].outcome)
        assertEquals("nonce-stale", rows[1].runNonce, "同一 nonce：幂等锚点不因过期丢失")
        assertTrue(log.uncommitted().isEmpty(), "恢复后无悬挂意向")
    }

    @Test
    fun `未到期意向照常重投，到期与否由期限而非成败决定`() = runBlocking {
        val log = InMemoryIntentLog(Clock { now })
        val scheduler = testScheduler(log = log)
        log.appendStart(
            projectId = "p",
            scriptPath = "a.js",
            runNonce = "nonce-fresh",
            trigger = TriggerSource.USER_CLICK,
            scheduledAtMillis = now,
            deadlineMillis = now + 600_000,
        )

        val recovered = scheduler.recoverUncommitted()

        assertEquals(1, dispatched.size, "未到期：照旧重投")
        assertFalse(recovered[0].expired)
        assertEquals(RunOutcome.Succeeded, recovered[0].outcome, "结果仍由 dispatcher 给")
    }

    @Test
    fun `无期限的遗留意向（老路径）永不被判过期`() = runBlocking {
        val log = InMemoryIntentLog(Clock { now })
        val scheduler = testScheduler(log = log)
        log.appendStart("p", "a.js", "nonce-nodl", TriggerSource.TIMED, now)   // 不传 deadline

        val recovered = scheduler.recoverUncommitted()

        assertEquals(1, dispatched.size, "null 期限 = 不判过期（直投/未挂日志的老路径）")
        assertFalse(recovered[0].expired)
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

    @Test
    fun `投递抛错不失排：Daily 本轮失败后仍续排下一轮`() = runBlocking {
        val provider = RecordingProvider()
        val scheduler = testScheduler(provider)
        scheduler.schedule(ScheduledTask("d1", "每日", "p", "daily.js", TimedSchedule.Daily(9, 30)))

        // dispatcher 本轮抛错（引擎池满/IO 失败）——异常不得逃逸、排期必须推进
        val failing = Scheduler(
            provider = provider,
            log = InMemoryIntentLog(Clock { now }),
            dispatcher = RunDispatcher { throw IllegalStateException("池满拒收") },
            nonceFactory = { "nonce-${++nonceSeq}" },
            clock = { now },
        )
        failing.schedule(ScheduledTask("d2", "每日失败", "p", "daily.js", TimedSchedule.Daily(9, 30)))
        val base = provider.fires.size
        now = provider.fires.last().first + 1_000
        failing.onTrigger("d2", scheduledAtMillis = provider.fires.last().first)

        assertEquals(base + 1, provider.fires.size, "投递失败后 Daily 必须续排，不得静默停排")
    }

    @Test
    fun `并发 schedule 与 cancel 不破坏注册表一致性`() = runBlocking {
        val provider = RecordingProvider()
        val scheduler = testScheduler(provider)
        // 多线程并发登记/取消同一批任务：串行化后注册表与句柄表必须一一对应
        val threads = (1..32).map { i ->
            Thread {
                runBlocking {
                    scheduler.schedule(ScheduledTask("t$i", "任务$i", "p", "a.js", TimedSchedule.Once(60)))
                    if (i % 2 == 0) scheduler.cancel("t$i")
                }
            }.apply { start() }
        }
        threads.forEach { it.join() }
        val remaining = scheduler.tasks()
        assertEquals(16, remaining.size, "奇数号任务留存：并发登记/取消串行化后注册表与句柄表一一对应")
        assertTrue(remaining.all { it.id.removePrefix("t").toInt() % 2 == 1 })
    }

    @Test
    fun `sink 后 onTrigger 早退且撤销触发器`() = runBlocking {
        val provider = RecordingProvider()
        val scheduler = testScheduler(provider)
        scheduler.schedule(ScheduledTask("t1", "定时", "p", "a.js", TimedSchedule.Daily(9, 30)))
        scheduler.sink()
        assertTrue(scheduler.sinking)
        val before = dispatched.size
        scheduler.onTrigger("t1", scheduledAtMillis = now)
        assertEquals(before, dispatched.size, "收口后不再投递")
    }

    @Test
    fun `无投递时 stopLastRun 如实 false`() = runBlocking {
        val scheduler = testScheduler()
        assertFalse(scheduler.stopLastRun(), "骨架期无引擎实现，不假装已停")
        assertFalse(scheduler.canStopLastRun())
        assertTrue(scheduler.quiesceThenStop().isEmpty())
    }

    @Test
    fun `link 为 null 的投递不产假句柄`() = runBlocking {
        val scheduler = testScheduler()
        scheduler.schedule(ScheduledTask("t1", "定时", "p", "a.js", TimedSchedule.Once(60)))
        scheduler.onTrigger("t1", scheduledAtMillis = now)
        // 默认 dispatcher 只回 outcome 不带 link：没有可停的东西，不持有句柄
        assertFalse(scheduler.canStopLastRun())
        assertFalse(scheduler.stopLastRun())
    }

    @Test
    fun `recoverUncommitted 重投带 args 与 timeoutMillis（恢复不丢执行载荷）`() = runBlocking {
        val log = InMemoryIntentLog(Clock { now })
        val scheduler = testScheduler(log = log)

        // 崩溃遗留：RUN_START 已落行（含执行载荷）、未 COMMIT
        log.appendStart(
            "p", "a.js", "nonce-payload", TriggerSource.TIMED, now,
            args = listOf("--fast"), timeoutMillis = 45_000,
        )

        scheduler.recoverUncommitted()

        val pending = dispatched.single()
        assertEquals(listOf("--fast"), pending.args, "§8.5 重投不得丢脚本参数（toPendingRun 从 IntentRun 带出）")
        assertEquals(45_000L, pending.timeoutMillis, "§8.5 重投不得丢脚本超时")
    }
}

