/**
 * 定时任务（docs/framework-design.md §8.6/§9.6 定时 API；对应 scheduler 的 TimedSchedule
 * + `:app` 的 `workManager` 桥命名空间）：
 * P0 仅「每日定点」与「一次性」；cron 表达式（多时区）留 P1（桥侧如实 ERR_NOT_IMPLEMENTED）。
 * - [daily]/[once]/[fromInput]/[nextFireAfter] 是纯本地排期工具（不发桥调用）；
 * - [createTimedTask]/[cancelTask]/[listTasks] 走 `workManager` 桥命名空间登记 Scheduler
 *   （直写注册表 `tasks.jsonl`，重启由 `bootRecover` 续排）。
 */
export type TimedScheduleInput = {
    on: 'once';
    /** 相对当前时刻的延迟（秒）。 */
    afterSeconds: number;
} | {
    on: 'daily';
    hourOfDay: number;
    minuteOfHour: number;
};
/** 调度计划（运行时不可变形态；cron 留 P1）。 */
export type TimedSchedule = {
    readonly kind: 'once';
    readonly delaySeconds: number;
} | {
    readonly kind: 'daily';
    readonly hourOfDay: number;
    readonly minuteOfHour: number;
};
export declare function daily(hourOfDay: number, minuteOfHour: number): TimedSchedule & {
    kind: 'daily';
};
export declare function once(afterSeconds: number): TimedSchedule & {
    kind: 'once';
};
export declare function fromInput(input: TimedScheduleInput): TimedSchedule;
/** 下一次应触发时刻（epoch millis，基于 now 的墙钟）。 */
export declare function nextFireAfter(schedule: TimedSchedule, nowMillis: number): number;
/** 屏幕契约（与 Kotlin ScreenGuarantee 逐字对齐：SCREEN_ON/ANY/SCREEN_OFF）。 */
export type ScreenGuarantee = 'SCREEN_ON' | 'ANY' | 'SCREEN_OFF';
/** 建任务输入（与 Kotlin `create` 载荷逐字段对齐；id 缺省服务端分配）。 */
export interface CreateTimedTaskInput {
    readonly id?: string;
    readonly name: string;
    readonly projectId: string;
    readonly scriptPath: string;
    readonly schedule: TimedSchedule;
    readonly screen?: ScreenGuarantee;
    readonly args?: readonly string[];
    readonly scriptTimeoutMillis?: number | null;
    readonly timezone?: string | null;
    readonly enabled?: boolean;
}
/** 登记后的任务（与 Kotlin `list` 回显同形状）。 */
export interface TimedTaskInfo extends CreateTimedTaskInput {
    readonly id: string;
}
/**
 * 登记定时任务（发桥调用 → Scheduler.schedule，直写注册表）。
 * cron 排期桥侧拒收（ERR_NOT_IMPLEMENTED）：调用方先经 [nextFireAfter] 自查也行，
 * 但真拒绝以桥为准（JS 侧不私藏"支持 cron"的假面）。
 */
export declare function createTimedTask(input: CreateTimedTaskInput, opts?: {
    timeout?: number;
}): Promise<{
    id: string;
}>;
/** 撤销任务（幂等：从未登记的 id 照样 true）。 */
export declare function cancelTask(id: string, opts?: {
    timeout?: number;
}): Promise<boolean>;
/** 列举任务（按 id 排序；与 Kotlin list 回显同形状）。 */
export declare function listTasks(opts?: {
    timeout?: number;
}): Promise<TimedTaskInfo[]>;
