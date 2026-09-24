package com.autoscript.appservice.scheduler.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.SortedSet

/**
 * 5 字段 cron（`分 时 日 月 周`，docs §8.6 P1 排期数学；[TimedSchedule.Cron] 唯一的解析/推进出处）。
 *
 * 支持的写法（标准 cron 子集，不含秒/年/`@宏`/`L`/`W`/`#` —— 遇到即构造拒绝，不猜）：
 * - 每字段：`*` / 步长（星号斜杠步长，如每 15 分钟）/ `a-b` / 范围加步长 / 起点加步长（= 起点到最大值加步长）/ `n` / 逗号列表；
 * - 范围：分 0..59、时 0..23、日 1..31、月 1..12、周 0..7（0 与 7 都是周日）；
 * - 名字：月 `JAN..DEC`、周 `MON..SUN`（大小写不敏感；数字与名字可混列）；
 * - 日 vs 周同受限时取**或**（标准 cron 语义），仅一侧受限时另一侧不参与
 *   （`0 0 1 * *` = 每月 1 号不看周几；`0 9 * * 1` = 每周一不看几号）。
 *
 * [nextAfter] 按天推进（最多 1462 天 ≈ 4 年，闰年 2-29 必命中）：某天命中日规则后取
 * 当天分/时笛卡尔积里严格晚于 now 的最小者（同一天内取最小 —— DST 跳空把 02:30 拨到
 * 03:30 时，真正的下一跳可能是 03:00，不能按字段序 early-return）。4 年无命中
 * （如 `0 0 30 2 *`：2 月没有 30 号）回 null —— 调用方（`Scheduler.rearmFor`）
 * 按"留名不续排"处理，与停用任务同一诚实口径。
 *
 * 非法表达式**构造即抛** [IllegalArgumentException]（与 `TimedSchedule.Daily` 的
 * init require 同口径）：登记入口（`:app` 的 Ops IAE / 桥侧 INVALID_PARAM）直接调
 * [parse] 做唯一校验出处，错误在登记时就被拒，
 * 不会变成一条"在册却永远不跑"的任务。
 */
object CronTab {

    data class Tab(
        val minutes: SortedSet<Int>,
        val hours: SortedSet<Int>,
        val daysOfMonth: SortedSet<Int>,
        val months: SortedSet<Int>,
        val daysOfWeek: SortedSet<Int>,
        val domRestricted: Boolean,
        val dowRestricted: Boolean,
    )

    private val MONTH_NAMES = mapOf(
        "JAN" to 1, "FEB" to 2, "MAR" to 3, "APR" to 4, "MAY" to 5, "JUN" to 6,
        "JUL" to 7, "AUG" to 8, "SEP" to 9, "OCT" to 10, "NOV" to 11, "DEC" to 12,
    )
    private val DOW_NAMES = mapOf(
        "MON" to 1, "TUE" to 2, "WED" to 3, "THU" to 4, "FRI" to 5, "SAT" to 6, "SUN" to 7,
    )

    /** 解析（非法即抛；`*` 以外的写法都算"受限"）。 */
    fun parse(expr: String): Tab {
        val parts = expr.trim().split(Regex("\\s+"))
        if (parts.size != 5 || parts.any { it.isEmpty() }) {
            throw IllegalArgumentException("cron 必须是 5 字段（分 时 日 月 周），收到「$expr」")
        }
        val minutes = parseField(parts[0], 0, 59, "分钟", null)
        val hours = parseField(parts[1], 0, 23, "小时", null)
        val daysOfMonth = parseField(parts[2], 1, 31, "日", null)
        val months = parseField(parts[3], 1, 12, "月", MONTH_NAMES)
        val daysOfWeek = parseField(parts[4], 0, 7, "周", DOW_NAMES)
            .mapTo(sortedSetOf()) { if (it == 7) 0 else it }
        return Tab(
            minutes = minutes,
            hours = hours,
            daysOfMonth = daysOfMonth,
            months = months,
            daysOfWeek = daysOfWeek,
            domRestricted = parts[2] != "*",
            dowRestricted = parts[4] != "*",
        )
    }

    /** now 之后严格下一命中（epoch millis）；4 年无命中回 null（见对象 KDoc）。 */
    fun nextAfter(tab: Tab, nowMillis: Long, zone: ZoneId): Long? {
        val base = Instant.ofEpochMilli(nowMillis).atZone(zone)
        var date = base.toLocalDate()
        repeat(1462) {
            if (matchesDay(tab, date)) {
                var best: Long? = null
                for (h in tab.hours) {
                    for (m in tab.minutes) {
                        val cand = date.atTime(h, m).atZone(zone).toInstant().toEpochMilli()
                        if (cand > nowMillis && (best == null || cand < best)) best = cand
                    }
                }
                if (best != null) return best
            }
            date = date.plusDays(1)
        }
        return null
    }

    private fun matchesDay(tab: Tab, date: LocalDate): Boolean {
        if (date.monthValue !in tab.months) return false
        val domHit = date.dayOfMonth in tab.daysOfMonth
        val dowHit = (date.dayOfWeek.value % 7) in tab.daysOfWeek
        return when {
            tab.domRestricted && tab.dowRestricted -> domHit || dowHit
            tab.domRestricted -> domHit
            tab.dowRestricted -> dowHit
            else -> true
        }
    }

    private fun parseField(
        token: String,
        min: Int,
        max: Int,
        label: String,
        names: Map<String, Int>?,
    ): SortedSet<Int> {
        if (token.isEmpty()) throw IllegalArgumentException("cron $label 字段为空")
        val out = sortedSetOf<Int>()
        for (atom in token.split(",")) {
            if (atom.isEmpty()) throw IllegalArgumentException("cron $label 字段有空项「$token」")
            val stepSplit = atom.split("/")
            if (stepSplit.size > 2) throw IllegalArgumentException("cron $label 字段步长只能写一个「/」：$atom")
            val step = if (stepSplit.size == 2) {
                stepSplit[1].toIntOrNull()?.takeIf { it >= 1 }
                    ?: throw IllegalArgumentException("cron $label 字段步长必须 ≥ 1 的整数：$atom")
            } else {
                1
            }
            val range = stepSplit[0]
            val (lo, hi) = if (range == "*") {
                min to max
            } else if ("-" in range) {
                val bounds = range.split("-")
                if (bounds.size != 2) throw IllegalArgumentException("cron $label 字段范围只能写一个「-」：$atom")
                resolve(bounds[0], min, max, label, atom, names) to
                    resolve(bounds[1], min, max, label, atom, names)
            } else {
                val v = resolve(range, min, max, label, atom, names)
                if (stepSplit.size == 2) v to max else v to v
            }
            if (lo > hi) throw IllegalArgumentException("cron $label 字段范围倒置：$atom")
            var v = lo
            while (v <= hi) {
                out += v
                v += step
            }
        }
        if (out.isEmpty()) throw IllegalArgumentException("cron $label 字段无有效值：$token")
        return out
    }

    private fun resolve(
        tok: String,
        min: Int,
        max: Int,
        label: String,
        atom: String,
        names: Map<String, Int>?,
    ): Int {
        names?.get(tok.uppercase())?.let { return it }
        val v = tok.toIntOrNull()
            ?: throw IllegalArgumentException(
                "cron $label 字段非法「$tok」（在 $atom 里；只接受数字/范围/步长/列表，月可用 JAN..DEC、周可用 MON..SUN）",
            )
        if (v !in min..max) throw IllegalArgumentException("cron $label 越界 $min..$max：$atom")
        return v
    }
}
