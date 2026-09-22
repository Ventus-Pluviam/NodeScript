/**
 * 定时任务（docs/framework-design.md §8.6/§9.6 定时 API；对应 scheduler 的 TimedSchedule
 * + `:app` 的 `workManager` 桥命名空间）：
 * P0 仅「每日定点」与「一次性」；cron 表达式（多时区）留 P1（桥侧如实 ERR_NOT_IMPLEMENTED）。
 * - [daily]/[once]/[fromInput]/[nextFireAfter] 是纯本地排期工具（不发桥调用）；
 * - [createTimedTask]/[cancelTask]/[listTasks] 走 `workManager` 桥命名空间登记 Scheduler
 *   （直写注册表 `tasks.jsonl`，重启由 `bootRecover` 续排）。
 */

import { runtimeBridge } from './runtime'

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
/** 屏幕契约（与 Kotlin ScreenGuarantee 逐字对齐：SCREEN_ON/ANY/SCREEN_OFF）。 */
export type ScreenGuarantee = 'SCREEN_ON' | 'ANY' | 'SCREEN_OFF'

/** 建任务输入（与 Kotlin `create` 载荷逐字段对齐；id 缺省服务端分配）。 */
export interface CreateTimedTaskInput {
  readonly id?: string
  readonly name: string
  readonly projectId: string
  readonly scriptPath: string
  readonly schedule: TimedSchedule
  readonly screen?: ScreenGuarantee
  readonly args?: readonly string[]
  readonly scriptTimeoutMillis?: number | null
  readonly timezone?: string | null
  readonly enabled?: boolean
}

/** 登记后的任务（与 Kotlin `list` 回显同形状）。 */
export interface TimedTaskInfo extends CreateTimedTaskInput {
  readonly id: string
}

/**
 * 登记定时任务（发桥调用 → Scheduler.schedule，直写注册表）。
 * cron 排期桥侧拒收（ERR_NOT_IMPLEMENTED）：调用方先经 [nextFireAfter] 自查也行，
 * 但真拒绝以桥为准（JS 侧不私藏"支持 cron"的假面）。
 */
export async function createTimedTask(
  input: CreateTimedTaskInput,
  opts: { timeout?: number } = {},
): Promise<{ id: string }> {
  const r = (await runtimeBridge.invoke('workManager', 'create', {
    ...input,
    schedule: toWireSchedule(input.schedule),
  }, { ttl: opts.timeout ?? 10_000 })) as { id: string }
  return r
}

/** 撤销任务（幂等：从未登记的 id 照样 true）。 */
export async function cancelTask(
  id: string,
  opts: { timeout?: number } = {},
): Promise<boolean> {
  return (await runtimeBridge.invoke('workManager', 'cancel', { id }, { ttl: opts.timeout ?? 10_000 })) as boolean
}

/** 列举任务（按 id 排序；与 Kotlin list 回显同形状）。 */
export async function listTasks(
  opts: { timeout?: number } = {},
): Promise<TimedTaskInfo[]> {
  return (await runtimeBridge.invoke('workManager', 'list', null, { ttl: opts.timeout ?? 10_000 })) as TimedTaskInfo[]
}

function toWireSchedule(s: TimedSchedule): Record<string, unknown> {
  if (s.kind === 'once') return { kind: 'once', delaySeconds: s.delaySeconds }
  if (s.kind === 'daily') return { kind: 'daily', hourOfDay: s.hourOfDay, minuteOfHour: s.minuteOfHour }
  // 未知 kind（如 P1 的 cron 透传体）原样透传，由桥侧如实拒绝 —— JS 侧绝不私自折叠成 daily
  //（折叠 = 把"不支持"伪装成"每日任务"，比报错更糟）。
  return { ...(s as unknown as Record<string, unknown>) }
}
