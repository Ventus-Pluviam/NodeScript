/**
 * 定时任务（docs/framework-design.md §8.6/§9.6 定时 API；对应 scheduler 的 TimedSchedule）：
 * P0 仅「每日定点」与「一次性」；cron 表达式（多时区）留 P1。
 * - [daily] 生成钟点级每日排期；[once] 生成相对 now 的倒计时（秒）。
 * - [nextFireAfter] 是唯一时序来源（与 Kotlin TimedSchedule.nextFireAfter 语义一致，
 *   DST 切换时保持本地时刻一致）。
 */

export type TimedScheduleInput = {
  on: 'once'
  /** 相对当前时刻的延迟（秒）。 */
  afterSeconds: number
} | {
  on: 'daily'
  hourOfDay: number
  minuteOfHour: number
}

/** 调度计划（运行时不可变形态；cron 留 P1）。 */
export type TimedSchedule = {
  readonly kind: 'once'
  readonly delaySeconds: number
} | {
  readonly kind: 'daily'
  readonly hourOfDay: number
  readonly minuteOfHour: number
}

export function daily(hourOfDay: number, minuteOfHour: number): TimedSchedule & { kind: 'daily' } {
  if (!Number.isInteger(hourOfDay) || hourOfDay < 0 || hourOfDay > 23) {
    throw new RangeError(`hourOfDay=${hourOfDay} 越界 0..23`)
  }
  if (!Number.isInteger(minuteOfHour) || minuteOfHour < 0 || minuteOfHour > 59) {
    throw new RangeError(`minuteOfHour=${minuteOfHour} 越界 0..59`)
  }
  return { kind: 'daily', hourOfDay, minuteOfHour }
}

export function once(afterSeconds: number): TimedSchedule & { kind: 'once' } {
  if (!Number.isFinite(afterSeconds) || afterSeconds < 0) {
    throw new RangeError(`afterSeconds=${afterSeconds} 必须为非负有限数`)
  }
  return { kind: 'once', delaySeconds: afterSeconds }
}

export function fromInput(input: TimedScheduleInput): TimedSchedule {
  return input.on === 'once' ? once(input.afterSeconds) : daily(input.hourOfDay, input.minuteOfHour)
}

/** 下一次应触发时刻（epoch millis，基于 now 的墙钟）。 */
export function nextFireAfter(schedule: TimedSchedule, nowMillis: number): number {
  if (schedule.kind === 'once') return nowMillis + schedule.delaySeconds * 1000
  const base = new Date(nowMillis)
  let candidate = new Date(
    base.getFullYear(), base.getMonth(), base.getDate(),
    schedule.hourOfDay, schedule.minuteOfHour, 0, 0,
  ).getTime()
  while (candidate <= nowMillis) {
    candidate += 86_400_000 // 固定 +24h：每日整点语义；DST 由 Date 构造自动对齐
  }
  return candidate
}