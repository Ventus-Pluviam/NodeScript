package com.autoscript.appservice.scheduler.core

import java.time.ZoneId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * cron 排期数学（[CronTab] + [TimedSchedule.Cron.nextFireAfter]）。
 *
 * 基准日 D0 = 1970-01-11 00:00:00 UTC（**周日** —— 周字段向量的锚点）：
 * - 周一 1-12 09:00 UTC = 982_800_000；周一 00:00 = 950_400_000；
 * - 周日 1-11 09:00 UTC = 896_400_000；下周日 1-18 09:00 = 1_501_200_000；
 * - 2-01 00:00 UTC = 2_678_400_000；闰日 1972-02-29 00:00 UTC = 68_169_600_000。
 */
class CronTabTest {

    private val utc = ZoneId.of("UTC")
    private val d0 = 864_000L * 1_000

    private fun cronNext(expr: String, now: Long, zone: ZoneId = utc): Long? =
        TimedSchedule.Cron(expr).nextFireAfter(now, zone)

    @Test
    fun `每分钟与整点：最小步长 60 秒`() {
        assertEquals(d0 + 60_000L, cronNext("* * * * *", d0))
        assertEquals(d0 + 900_000L, cronNext("*/15 * * * *", d0 + 60_000L), "00:01 → 00:15")
        assertEquals(d0 + 900_000L, cronNext("*/15 * * * *", d0), "整点本身是命中点，但 fires 必须严格晚于 now → 00:15")
    }

    @Test
    fun `每日九点与 Daily 同值 —— cron 是 Daily 的超集不是第二套算法`() {
        val daily = TimedSchedule.Daily(9, 0)
        for (now in listOf(d0, d0 + 8 * 3_600_000L, d0 + 10 * 3_600_000L)) {
            assertEquals(daily.nextFireAfter(now, utc), cronNext("0 9 * * *", now))
        }
    }

    @Test
    fun `按周：周日基准向后找周一，周日当天九点前命中今天`() {
        assertEquals(982_800_000L, cronNext("0 9 * * 1", d0), "周日 → 下周一 09:00")
        assertEquals(896_400_000L, cronNext("0 9 * * 0", d0), "周日 00:00 → 今天 09:00")
        assertEquals(1_501_200_000L, cronNext("0 9 * * 0", d0 + 10 * 3_600_000L), "周日 10:00 → 下周日")
        assertEquals(982_800_000L, cronNext("0 9 * * mon", d0), "名字大小写不敏感")
        assertEquals(896_400_000L, cronNext("0 9 * * 7", d0), "7 也是周日")
        assertEquals(896_400_000L, cronNext("0 9 * * SUN", d0))
    }

    @Test
    fun `按月与按号：每月一号不看周几`() {
        assertEquals(2_678_400_000L, cronNext("0 0 1 * *", d0), "1-11 → 2-01 00:00")
        assertEquals(2_678_400_000L, cronNext("0 0 1 FEB *", d0), "月份名字")
        assertEquals(950_400_000L, cronNext("0 0 1 * 1", d0), "日或周：周一先到（或语义，不是与）")
    }

    @Test
    fun `列表与范围步长：同一天内取最小命中`() {
        val ten = d0 + 10 * 3_600_000L
        assertEquals(d0 + 18 * 3_600_000L, cronNext("0 9,18 * * *", ten), "10:00 → 18:00")
        assertEquals(896_400_000L, cronNext("0 9-17/2 * * *", d0 + 8 * 3_600_000L), "08:00 → 09:00")
        assertEquals(d0 + 11 * 3_600_000L, cronNext("0 9-17/2 * * *", ten), "10:00 → 11:00（步长 9,11,13,15,17）")
        assertEquals(d0 + 9 * 3_600_000L, cronNext("0 9/2 * * *", d0), "a/b = a-最大值/b")
    }

    @Test
    fun `闰日四年必命中，不可能日期回 null 留名不续排`() {
        assertEquals(68_169_600_000L, cronNext("0 0 29 2 *", d0), "1972 闰年 2-29")
        assertNull(cronNext("0 0 30 2 *", d0), "2 月没有 30 号：null，不是抛")
    }

    @Test
    fun `非法表达式构造即拒 —— 消息点名哪一段`() {
        for (bad in listOf("", "0 9 * *", "* * * * * *", "61 9 * * *", "0 24 * * *", "0 9 * * 8")) {
            val e = assertThrows(IllegalArgumentException::class.java) { CronTab.parse(bad) }
            assertTrue(e.message!!.isNotBlank(), "「$bad」拒绝要有原因：${e.message}")
        }
        assertThrows(IllegalArgumentException::class.java) { CronTab.parse("0 9 * * FOO") }
        assertThrows(IllegalArgumentException::class.java) { CronTab.parse("0 9 * * */0") }
        assertThrows(IllegalArgumentException::class.java) { CronTab.parse("*/2/3 * * * *") }
        assertThrows(IllegalArgumentException::class.java) { CronTab.parse("@daily") }
        assertThrows(IllegalArgumentException::class.java) { CronTab.parse("0 9 0 * *") }
    }

    @Test
    fun `非法表达式经 TimedSchedule 只回 null 不抛 —— 注册表重放不崩整表`() {
        assertNull(cronNext("61 9 * * *", d0), "手改坏的行：留名不续排，拒绝归登记入口")
    }

    @Test
    fun `多余空白容忍 —— 手填表达式不因首尾空格被拒`() {
        assertEquals(982_800_000L, cronNext("  0 9 * * 1  ", d0))
    }

    @Test
    fun `跨时区：以上海墙钟算九点`() {
        val shanghai = ZoneId.of("Asia/Shanghai")
        // D0 00:00 UTC = 上海 08:00 → 今天上海 09:00 = UTC 01:00
        assertEquals(d0 + 3_600_000L, cronNext("0 9 * * *", d0, shanghai))
        assertEquals(
            TimedSchedule.Daily(9, 0).nextFireAfter(d0, shanghai),
            cronNext("0 9 * * *", d0, shanghai),
            "同值：cron 与 Daily 同一时区口径",
        )
    }

    @Test
    fun `纽约春跳空：02-30 不存在则顺延到 03-30 当天的最小命中`() {
        val ny = ZoneId.of("America/New_York")
        // 1970-04-26 06:00Z = 纽约 02:00 EST（跳空起点）；02:30 被拨到 03:30 EDT = 07:30Z
        val now = 9_936_000_000L + 6 * 3_600_000L
        assertEquals(9_963_000_000L, cronNext("30 2 * * *", now, ny), "跳空时刻顺延，不早触发")
    }

    @Test
    fun `纽约秋回拨：重叠小时取早偏移只命中第一次 —— 接受并写明`() {
        val ny = ZoneId.of("America/New_York")
        // 1970-10-25 05:00Z = 纽约第一次 01:00 EDT；01:30 取早偏移 = 05:30Z
        val first = 25_660_800_000L + 5 * 3_600_000L
        assertEquals(first + 1_800_000L, cronNext("30 1 * * *", first, ny))
        // 第一次 01:45 EDT（05:45Z）之后：早偏移的 01:30 已过，不补第二次，直接次日
        val next = cronNext("30 1 * * *", first + 2_700_000L, ny)
        requireNotNull(next)
        assertTrue(next > first + 24 * 3_600_000L - 3_600_000L, "回拨日第二次 01:30 不补：$next")
    }
}
