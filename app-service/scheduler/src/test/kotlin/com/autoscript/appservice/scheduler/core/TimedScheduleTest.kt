package com.autoscript.appservice.scheduler.core

import java.time.ZoneId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TimedScheduleTest {

    private val utc = ZoneId.of("UTC")

    // 固定基准日：D0 = 1970-01-11 00:00:00 UTC（第 10 天，epoch 秒 = 864000）
    private val d0 = 864_000L * 1_000
    private val dayMillis = 24L * 3_600 * 1_000
    private fun at(h: Int, m: Int) = d0 + (h * 3_600L + m * 60L) * 1_000

    @Test
    fun `Once 按 delay 计算`() {
        assertEquals(1_060_000L, TimedSchedule.Once(60).nextFireAfter(1_000_000L, utc))
    }

    @Test
    fun `Daily 未过则今天，已过则明天`() {
        val daily = TimedSchedule.Daily(9, 30)
        assertEquals(at(9, 30), daily.nextFireAfter(at(8, 0), utc), "今天 9:30 未过 → 今天")
        assertEquals(at(9, 30) + dayMillis, daily.nextFireAfter(at(10, 0), utc), "10:00 已过 → 明天")
    }

    @Test
    fun `Daily 边界：恰好到时返回明天（严格大于 now）`() {
        assertEquals(
            at(9, 30) + dayMillis,
            TimedSchedule.Daily(9, 30).nextFireAfter(at(9, 30), utc),
            "fires 必须严格晚于 now",
        )
    }

    @Test
    fun `Daily 跨时区：以目标时区的墙钟计算`() {
        // 上海午夜 = UTC 前一日 16:00。now(D0 16:00 UTC) == 上海正午夜 → 下一正午夜 = D0 16:00 + 24h
        val shanghai = ZoneId.of("Asia/Shanghai")
        assertEquals(at(16, 0) + dayMillis, TimedSchedule.Daily(0, 0).nextFireAfter(at(16, 0), shanghai))
    }

    @Test
    fun `Cron P1 已落地：每日九点与 Daily 同值`() {
        // cron 细则归 CronTabTest；这里只钉"接线不断"（Cron 分支不再恒 null）。
        val daily = TimedSchedule.Daily(9, 30)
        assertEquals(daily.nextFireAfter(at(8, 0), utc), TimedSchedule.Cron("30 9 * * *").nextFireAfter(at(8, 0), utc))
        assertEquals(daily.nextFireAfter(at(10, 0), utc), TimedSchedule.Cron("30 9 * * *").nextFireAfter(at(10, 0), utc))
    }
}