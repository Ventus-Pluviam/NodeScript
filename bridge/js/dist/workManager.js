"use strict";
/**
 * 定时任务（docs/framework-design.md §8.6/§9.6 定时 API；对应 scheduler 的 TimedSchedule
 * + `:app` 的 `workManager` 桥命名空间）：
 * P0 仅「每日定点」与「一次性」；cron 表达式（多时区）留 P1（桥侧如实 ERR_NOT_IMPLEMENTED）。
 * - [daily]/[once]/[fromInput]/[nextFireAfter] 是纯本地排期工具（不发桥调用）；
 * - [createTimedTask]/[cancelTask]/[listTasks] 走 `workManager` 桥命名空间登记 Scheduler
 *   （直写注册表 `tasks.jsonl`，重启由 `bootRecover` 续排）。
 */
Object.defineProperty(exports, "__esModule", { value: true });
exports.daily = daily;
exports.once = once;
exports.fromInput = fromInput;
exports.nextFireAfter = nextFireAfter;
exports.createTimedTask = createTimedTask;
exports.cancelTask = cancelTask;
exports.listTasks = listTasks;
const runtime_1 = require("./runtime");
function daily(hourOfDay, minuteOfHour) {
    if (!Number.isInteger(hourOfDay) || hourOfDay < 0 || hourOfDay > 23) {
        throw new RangeError(`hourOfDay=${hourOfDay} 越界 0..23`);
    }
    if (!Number.isInteger(minuteOfHour) || minuteOfHour < 0 || minuteOfHour > 59) {
        throw new RangeError(`minuteOfHour=${minuteOfHour} 越界 0..59`);
    }
    return { kind: 'daily', hourOfDay, minuteOfHour };
}
function once(afterSeconds) {
    if (!Number.isFinite(afterSeconds) || afterSeconds < 0) {
        throw new RangeError(`afterSeconds=${afterSeconds} 必须为非负有限数`);
    }
    return { kind: 'once', delaySeconds: afterSeconds };
}
function fromInput(input) {
    return input.on === 'once' ? once(input.afterSeconds) : daily(input.hourOfDay, input.minuteOfHour);
}
/** 下一次应触发时刻（epoch millis，基于 now 的墙钟）。 */
function nextFireAfter(schedule, nowMillis) {
    if (schedule.kind === 'once')
        return nowMillis + schedule.delaySeconds * 1000;
    const base = new Date(nowMillis);
    let candidate = new Date(base.getFullYear(), base.getMonth(), base.getDate(), schedule.hourOfDay, schedule.minuteOfHour, 0, 0).getTime();
    while (candidate <= nowMillis) {
        candidate += 86_400_000; // 固定 +24h：每日整点语义；DST 由 Date 构造自动对齐
    }
    return candidate;
}
/**
 * 登记定时任务（发桥调用 → Scheduler.schedule，直写注册表）。
 * cron 排期桥侧拒收（ERR_NOT_IMPLEMENTED）：调用方先经 [nextFireAfter] 自查也行，
 * 但真拒绝以桥为准（JS 侧不私藏"支持 cron"的假面）。
 */
async function createTimedTask(input, opts = {}) {
    const r = (await runtime_1.runtimeBridge.invoke('workManager', 'create', {
        ...input,
        schedule: toWireSchedule(input.schedule),
    }, { ttl: opts.timeout ?? 10_000 }));
    return r;
}
/** 撤销任务（幂等：从未登记的 id 照样 true）。 */
async function cancelTask(id, opts = {}) {
    return (await runtime_1.runtimeBridge.invoke('workManager', 'cancel', { id }, { ttl: opts.timeout ?? 10_000 }));
}
/** 列举任务（按 id 排序；与 Kotlin list 回显同形状）。 */
async function listTasks(opts = {}) {
    return (await runtime_1.runtimeBridge.invoke('workManager', 'list', null, { ttl: opts.timeout ?? 10_000 }));
}
function toWireSchedule(s) {
    if (s.kind === 'once')
        return { kind: 'once', delaySeconds: s.delaySeconds };
    if (s.kind === 'daily')
        return { kind: 'daily', hourOfDay: s.hourOfDay, minuteOfHour: s.minuteOfHour };
    // 未知 kind（如 P1 的 cron 透传体）原样透传，由桥侧如实拒绝 —— JS 侧绝不私自折叠成 daily
    //（折叠 = 把"不支持"伪装成"每日任务"，比报错更糟）。
    return { ...s };
}
