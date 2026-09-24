package com.autoscript.appservice.scheduler.core

/**
 * 定时任务的调度计划（docs §8.6 / §9.6 定时 API）：
 * 「每日定点」+「一次性（倒计时/延迟）」+「cron 表达式」（P1 已落地，解析/推进见 [CronTab]）。
 * [nextFireAfter] 是唯一时序来源，供 alarm 预拉（wakeAheadMs）与 UI 预览使用。
 */
sealed interface TimedSchedule {
    /** 相对 now 的 delay 秒（意图日志里 scheduledAt 固定为投递时刻）。 */
    data class Once(val delaySeconds: Long) : TimedSchedule

    /** 每日首次命中 now 之后的整点时刻。 */
    data class Daily(val hourOfDay: Int, val minuteOfHour: Int) : TimedSchedule {
        init {
            require(hourOfDay in 0..23) { "hourOfDay=$hourOfDay 越界 0..23" }
            require(minuteOfHour in 0..59) { "minuteOfHour=$minuteOfHour 越界 0..59" }
        }
    }

    /**
     * cron 表达式（5 字段 `分 时 日 月 周`，时区取任务自己的 [ScheduledTask.timezone]）。
     *
     * 构造**不校验**（与 [Daily] 的 init require 不同）：注册表里可能躺着手改坏掉的行，
     * 重放/读口构造时抛会把整表拖崩 —— 非法表达式在这里只算不出下一跳（回 null，
     * 留名不续排），真正的拒绝在登记入口（Ops IAE / 桥侧 INVALID_PARAM，见 [CronTab]）。
     */
    data class Cron(val expr: String) : TimedSchedule

    /** 下一次应触发时刻（epoch millis，基于 now 的墙钟）；无法表达时返回 null。 */
    fun nextFireAfter(nowMillis: Long, zone: java.time.ZoneId): Long? {
        return when (this) {
            is Once -> nowMillis + delaySeconds * 1000
            is Daily -> {
                // 基于 LocalDate 重建而非固定 +24h，DST 切换时保持本地时刻一致
                val base = java.time.Instant.ofEpochMilli(nowMillis).atZone(zone)
                var day = base.toLocalDate()
                var candidate = day.atTime(hourOfDay, minuteOfHour).atZone(zone).toInstant().toEpochMilli()
                while (candidate <= nowMillis) {
                    day = day.plusDays(1)
                    candidate = day.atTime(hourOfDay, minuteOfHour).atZone(zone).toInstant().toEpochMilli()
                }
                candidate
            }
            is Cron -> try {
                CronTab.nextAfter(CronTab.parse(expr), nowMillis, zone)
            } catch (_: IllegalArgumentException) {
                // 非法表达式：留名不续排（见本类 KDoc —— 拒绝归登记入口，这里只算数）。
                null
            }
        }
    }
}