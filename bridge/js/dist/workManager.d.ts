/**
 * 定时任务（docs/framework-design.md §8.6/§9.6 定时 API；对应 scheduler 的 TimedSchedule）：
 * P0 仅「每日定点」与「一次性」；cron 表达式（多时区）留 P1。
 * - [daily] 生成钟点级每日排期；[once] 生成相对 now 的倒计时（秒）。
 * - [nextFireAfter] 是唯一时序来源（与 Kotlin TimedSchedule.nextFireAfter 语义一致，
 *   DST 切换时保持本地时刻一致）。
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
