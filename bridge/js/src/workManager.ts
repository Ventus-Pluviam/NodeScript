/**
 * 定时任务（docs/framework-design.md §8.6/§9.6 定时 API；对应 scheduler 的 TimedSchedule
 * + `:app` 的 `workManager` 桥命名空间）：
 * 「每日定点」+「一次性」+「cron 表达式」（5 字段 `分 时 日 月 周`，P1 已落地）：
 * 宿主侧解析/推进是调度器的 `CronTab`（唯一校验出处），本文件的 cron 镜像只做
 * **本地预览**（[nextFireAfter]），登记仍走桥由宿主裁决 —— 两侧漂移以宿主为准。
 * - [daily]/[once]/[cron]/[fromInput]/[nextFireAfter] 是纯本地排期工具（不发桥调用）；
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
} | {
  on: 'cron'
  /** 5 字段 `分 时 日 月 周`（如 `0 9 * * 1` = 每周一 09:00）。 */
  expr: string
}

/** 调度计划（运行时不可变形态；cron 校验/推进以宿主 `CronTab` 为准，本文件只镜像）。 */
export type TimedSchedule = {
  readonly kind: 'once'
  readonly delaySeconds: number
} | {
  readonly kind: 'daily'
  readonly hourOfDay: number
  readonly minuteOfHour: number
} | {
  readonly kind: 'cron'
  readonly expr: string
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

export function cron(expr: string): TimedSchedule & { kind: 'cron' } {
  if (typeof expr !== 'string' || expr.trim().split(/\s+/).length !== 5) {
    throw new RangeError(`cron 必须是 5 字段（分 时 日 月 周），收到「${expr}」`)
  }
  // 只做形状门（5 字段）：段内合法性（范围/名字/步长）由宿主 CronTab.parse 裁决，
  // 登记时非法回 ERR_INVALID_PARAM —— JS 侧不复述第二套校验（两份必漂移）。
  return { kind: 'cron', expr: expr.trim() }
}

export function fromInput(input: TimedScheduleInput): TimedSchedule {
  if (input.on === 'once') return once(input.afterSeconds)
  if (input.on === 'daily') return daily(input.hourOfDay, input.minuteOfHour)
  return cron(input.expr)
}

/** 下一次应触发时刻（epoch millis，基于 now 的墙钟）。 */
export function nextFireAfter(schedule: TimedSchedule, nowMillis: number): number | null {
  if (schedule.kind === 'once') return nowMillis + schedule.delaySeconds * 1000
  if (schedule.kind === 'daily') {
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
  return nextCronFireAfter(schedule.expr, nowMillis)
}

/**
 * cron 本地预览（宿主 `CronTab` 的 JS 镜像：字段语义逐条对齐，段内校验从简）。
 *
 * 漂移纪律：本函数只给脚本侧"大概下次何时"的预览；登记与续排的权威是宿主
 * （`CronTab.parse` 拒非法 → 桥回 ERR_INVALID_PARAM）。两者分歧时以宿主为准，
 * 本镜像的用例只钉"与宿主同值"的几个锚点（每日九点/每周一/不可能日期 null）。
 */
export function nextCronFireAfter(expr: string, nowMillis: number): number | null {
  const tab = parseCron(expr)
  const base = new Date(nowMillis)
  const day = new Date(base.getFullYear(), base.getMonth(), base.getDate())
  for (let i = 0; i < 1462; i++) {
    const d = new Date(day.getTime() + i * 86_400_000)
    if (!matchesCronDay(tab, d)) continue
    let best: number | null = null
    for (const h of tab.hours) {
      for (const m of tab.minutes) {
        const cand = new Date(d.getFullYear(), d.getMonth(), d.getDate(), h, m, 0, 0).getTime()
        if (cand > nowMillis && (best === null || cand < best)) best = cand
      }
    }
    if (best !== null) return best
  }
  return null // 4 年无命中（如 2 月 30 号）：与宿主同一口径
}

interface CronTabSets {
  minutes: number[]
  hours: number[]
  daysOfMonth: number[]
  months: number[]
  daysOfWeek: number[]
  domRestricted: boolean
  dowRestricted: boolean
}

const CRON_MONTHS: Record<string, number> = {
  JAN: 1, FEB: 2, MAR: 3, APR: 4, MAY: 5, JUN: 6,
  JUL: 7, AUG: 8, SEP: 9, OCT: 10, NOV: 11, DEC: 12,
}
const CRON_DOWS: Record<string, number> = {
  MON: 1, TUE: 2, WED: 3, THU: 4, FRI: 5, SAT: 6, SUN: 7,
}

function parseCron(expr: string): CronTabSets {
  const parts = expr.trim().split(/\s+/)
  if (parts.length !== 5 || parts.some((p) => p.length === 0)) {
    throw new RangeError(`cron 必须是 5 字段（分 时 日 月 周），收到「${expr}」`)
  }
  const minutes = parseCronField(parts[0], 0, 59, '分钟', null)
  const hours = parseCronField(parts[1], 0, 23, '小时', null)
  const daysOfMonth = parseCronField(parts[2], 1, 31, '日', null)
  const months = parseCronField(parts[3], 1, 12, '月', CRON_MONTHS)
  const dows = parseCronField(parts[4], 0, 7, '周', CRON_DOWS).map((v) => (v === 7 ? 0 : v))
  return {
    minutes, hours, daysOfMonth, months,
    daysOfWeek: [...new Set(dows)].sort((a, b) => a - b),
    domRestricted: parts[2] !== '*',
    dowRestricted: parts[4] !== '*',
  }
}

function matchesCronDay(tab: CronTabSets, d: Date): boolean {
  if (!tab.months.includes(d.getMonth() + 1)) return false
  const domHit = tab.daysOfMonth.includes(d.getDate())
  const dowHit = tab.daysOfWeek.includes(d.getDay())
  if (tab.domRestricted && tab.dowRestricted) return domHit || dowHit
  if (tab.domRestricted) return domHit
  if (tab.dowRestricted) return dowHit
  return true
}

function parseCronField(
  token: string, min: number, max: number, label: string, names: Record<string, number> | null,
): number[] {
  if (token.length === 0) throw new RangeError(`cron ${label}字段为空`)
  const out = new Set<number>()
  for (const atom of token.split(',')) {
    if (atom.length === 0) throw new RangeError(`cron ${label}字段有空项「${token}」`)
    const stepSplit = atom.split('/')
    if (stepSplit.length > 2) throw new RangeError(`cron ${label}字段步长只能写一个「/」：${atom}`)
    const step = stepSplit.length === 2
      ? parseCronInt(stepSplit[1], label, atom, 1, Number.MAX_SAFE_INTEGER, '步长必须 ≥ 1 的整数')
      : 1
    const range = stepSplit[0]
    let lo: number
    let hi: number
    if (range === '*') {
      lo = min; hi = max
    } else if (range.includes('-')) {
      const bounds = range.split('-')
      if (bounds.length !== 2) throw new RangeError(`cron ${label}字段范围只能写一个「-」：${atom}`)
      lo = parseCronVal(bounds[0], min, max, label, atom, names)
      hi = parseCronVal(bounds[1], min, max, label, atom, names)
    } else {
      const v = parseCronVal(range, min, max, label, atom, names)
      if (stepSplit.length === 2) { lo = v; hi = max } else { lo = v; hi = v }
    }
    if (lo > hi) throw new RangeError(`cron ${label}字段范围倒置：${atom}`)
    for (let v = lo; v <= hi; v += step) out.add(v)
  }
  if (out.size === 0) throw new RangeError(`cron ${label}字段无有效值：${token}`)
  return [...out].sort((a, b) => a - b)
}

function parseCronVal(
  tok: string, min: number, max: number, label: string, atom: string, names: Record<string, number> | null,
): number {
  const named = names?.[tok.toUpperCase()]
  if (named !== undefined) return named
  return parseCronInt(tok, label, atom, min, max, `只接受数字/范围/步长/列表，月可用 JAN..DEC、周可用 MON..SUN`)
}

function parseCronInt(
  tok: string, label: string, atom: string, min: number, max: number, why: string,
): number {
  if (!/^-?\d+$/.test(tok)) throw new RangeError(`cron ${label}字段非法「${tok}」（在 ${atom} 里；${why}）`)
  const v = parseInt(tok, 10)
  if (v < min || v > max) throw new RangeError(`cron ${label}越界 ${min}..${max}：${atom}`)
  return v
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
 * 非法 cron 表达式桥侧拒收（ERR_INVALID_PARAM）：调用方可先经 [nextFireAfter]
 * 本地预览自查，但真拒绝以桥为准（宿主 `CronTab.parse` 是唯一校验出处）。
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
  return { kind: 'cron', expr: s.expr }
}
