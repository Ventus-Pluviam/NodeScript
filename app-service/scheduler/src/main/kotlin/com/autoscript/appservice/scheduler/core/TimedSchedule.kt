package com.autoscript.appservice.scheduler.core

/**
 * 定时任务的调度计划（docs §8.6 / §9.6 定时 API）：
 * P0 仅「每日定点」与「一次性（倒计时/延迟）」；cron 表达式（多时区）留 P1（§743）。
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

    /** P1：cron 表达式（含时区、多规则）。 */
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
            is Cron -> null // P1
        }
    }
}