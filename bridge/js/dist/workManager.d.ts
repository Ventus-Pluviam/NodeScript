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
export type TimedScheduleInput = {
    on: 'once';
    /** 相对当前时刻的延迟（秒）。 */
    afterSeconds: number;
} | {
    on: 'daily';
    hourOfDay: number;
    minuteOfHour: number;
} | {
    on: 'cron';
    /** 5 字段 `分 时 日 月 周`（如 `0 9 * * 1` = 每周一 09:00）。 */
    expr: string;
};
/** 调度计划（运行时不可变形态；cron 校验/推进以宿主 `CronTab` 为准，本文件只镜像）。 */
export type TimedSchedule = {
    readonly kind: 'once';
    readonly delaySeconds: number;
} | {
    readonly kind: 'daily';
    readonly hourOfDay: number;
    readonly minuteOfHour: number;
} | {
    readonly kind: 'cron';
    readonly expr: string;
};
export declare function daily(hourOfDay: number, minuteOfHour: number): TimedSchedule & {
    kind: 'daily';
};
export declare function once(afterSeconds: number): TimedSchedule & {
    kind: 'once';
};
export declare function cron(expr: string): TimedSchedule & {
    kind: 'cron';
};
export declare function fromInput(input: TimedScheduleInput): TimedSchedule;
/** 下一次应触发时刻（epoch millis，基于 now 的墙钟）。 */
export declare function nextFireAfter(schedule: TimedSchedule, nowMillis: number): number | null;
/**
 * cron 本地预览（宿主 `CronTab` 的 JS 镜像：字段语义逐条对齐，段内校验从简）。
 *
 * 漂移纪律：本函数只给脚本侧"大概下次何时"的预览；登记与续排的权威是宿主
 * （`CronTab.parse` 拒非法 → 桥回 ERR_INVALID_PARAM）。两者分歧时以宿主为准，
 * 本镜像的用例只钉"与宿主同值"的几个锚点（每日九点/每周一/不可能日期 null）。
 */
export declare function nextCronFireAfter(expr: string, nowMillis: number): number | null;
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
 * 非法 cron 表达式桥侧拒收（ERR_INVALID_PARAM）：调用方可先经 [nextFireAfter]
 * 本地预览自查，但真拒绝以桥为准（宿主 `CronTab.parse` 是唯一校验出处）。
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
