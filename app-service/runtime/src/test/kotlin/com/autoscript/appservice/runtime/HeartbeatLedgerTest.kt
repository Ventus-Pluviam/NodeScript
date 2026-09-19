package com.autoscript.appservice.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 心跳账本验证（docs §8.4 缺口②的宿主侧收单方）。
 *
 * 验的是"距上次心跳多久"这个数**由谁推进**：必须是引擎进程的打点，账本只如实记收到
 * 的时刻与序号。这一次的返回值会直接被 [WatchdogPolicy] 当判据用，所以三条口径
 * （未打点回 null / 过期序号不刷 / 终结即忘）都必须钉死。
 */
class HeartbeatLedgerTest {

    private class FakeClock : HeartbeatLedger.Clock {
        var now = 1_000L
        override fun nowMillis(): Long = now
        fun advance(millis: Long) { now += millis }
    }

    @Test
    fun `从未打点的 run 回 null 而不是 0`() {
        val ledger = HeartbeatLedger()
        assertNull(ledger.sinceLastBeat(42L), "0 会被当成『刚刚打过』，让失联判定永不触发")
        assertNull(ledger.lastSeq(42L))
    }

    @Test
    fun `打点后如实记距上次毫秒，序号随记推进`() {
        val clock = FakeClock()
        val ledger = HeartbeatLedger(clock)
        assertTrue(ledger.beat(1L, seq = 1))
        assertEquals(1L, ledger.lastSeq(1L))

        assertEquals(0L, ledger.sinceLastBeat(1L), "刚打过：0ms")
        clock.advance(1_200)
        assertEquals(1_200L, ledger.sinceLastBeat(1L))
    }

    @Test
    fun `过期序号不刷新时间戳，RQ 记 stale`() {
        val clock = FakeClock()
        val ledger = HeartbeatLedger(clock)
        ledger.beat(1L, seq = 7)
        clock.advance(100)

        assertFalse(ledger.beat(1L, seq = 7), "同 seq 视为重复帧")
        assertFalse(ledger.beat(1L, seq = 6), "旧 seq 视为延迟到达的积压帧")

        // 关键：被拒的帧不得把时间戳往前搬 —— 否则死掉的 run 靠积压心跳一直"活着"
        assertEquals(100L, ledger.sinceLastBeat(1L))
        assertEquals(7L, ledger.lastSeq(1L), "序号不倒退")
        assertEquals(2L, ledger.staleBeats())
    }

    @Test
    fun `forget 后重新起步，不背上一段的假年轻`() {
        val clock = FakeClock()
        val ledger = HeartbeatLedger(clock)
        ledger.beat(1L, seq = 3)
        clock.advance(9_999)
        assertEquals(9_999L, ledger.sinceLastBeat(1L))

        ledger.forget(1L)
        assertNull(ledger.sinceLastBeat(1L), "遗忘 = 回到『量不到』，不是回到 0")
        assertTrue(ledger.trackedRuns().isEmpty())

        ledger.beat(1L, seq = 1)                      // runId 落到新 run 头上：从 1 重新计数
        clock.advance(50)
        assertEquals(50L, ledger.sinceLastBeat(1L))
        assertEquals(1L, ledger.lastSeq(1L))
    }

    @Test
    fun `多 run 各记各的账`() {
        val clock = FakeClock()
        val ledger = HeartbeatLedger(clock)
        ledger.beat(1L, seq = 1)
        clock.advance(400)
        ledger.beat(2L, seq = 5)
        clock.advance(100)

        assertEquals(500L, ledger.sinceLastBeat(1L))
        assertEquals(100L, ledger.sinceLastBeat(2L))
        assertEquals(setOf(1L, 2L), ledger.trackedRuns())

        ledger.forget(1L)
        assertEquals(setOf(2L), ledger.trackedRuns(), "forget 只动目标 run")
    }

    @Test
    fun `超编淘汰最旧条目，不无限堆积`() {
        val clock = FakeClock()
        val ledger = HeartbeatLedger(clock, maxRuns = 2)
        ledger.beat(1L, seq = 1)
        ledger.beat(2L, seq = 1)
        ledger.beat(3L, seq = 1)
        assertEquals(setOf(2L, 3L), ledger.trackedRuns(), "最旧（1L）被淘汰")
        assertNull(ledger.sinceLastBeat(1L))
    }

    @Test
    fun `reset 清空账本与被拒计数`() {
        val clock = FakeClock()
        val ledger = HeartbeatLedger(clock)
        ledger.beat(1L, seq = 1)
        ledger.beat(1L, seq = 1)                      // 被拒一次
        ledger.reset()
        assertTrue(ledger.trackedRuns().isEmpty())
        assertEquals(0L, ledger.staleBeats())
    }

    @Test
    fun `时钟回拨不产出负时长`() {
        val clock = FakeClock()
        val ledger = HeartbeatLedger(clock)
        ledger.beat(1L, seq = 1)
        clock.now -= 500                              // 管理员改表/时区跳变
        assertEquals(0L, ledger.sinceLastBeat(1L), "负间隔会让失联判定看似安全，却把 CPU/RSS 一起拖偏")
    }
}
