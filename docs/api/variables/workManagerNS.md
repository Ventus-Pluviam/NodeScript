# Variable: workManagerNS

```ts
const workManagerNS: {
  cancelTask: (id, opts) => Promise<boolean>;
  createTimedTask: (input, opts) => Promise<{
     id: string;
  }>;
  cron: (expr) => TimedSchedule & {
     kind: "cron";
  };
  daily: (hourOfDay, minuteOfHour) => TimedSchedule & {
     kind: "daily";
  };
  fromInput: (input) => TimedSchedule;
  listTasks: (opts) => Promise<TimedTaskInfo[]>;
  nextCronFireAfter: (expr, nowMillis) => number | null;
  nextFireAfter: (schedule, nowMillis) => number | null;
  once: (afterSeconds) => TimedSchedule & {
     kind: "once";
  };
};
```

workManager 命名空间（scheduler 面：每日/一次性/cron 排期工具函数，运行态挂全局任务表）。

## Type Declaration

### cancelTask

```ts
cancelTask: (id, opts) => Promise<boolean>;
```

撤销任务（幂等：从未登记的 id 照样 true）。

#### Parameters

| Parameter | Type |
| ------ | ------ |
| `id` | `string` |
| `opts` | \{ `timeout?`: `number`; \} |
| `opts.timeout?` | `number` |

#### Returns

`Promise`\<`boolean`\>

### createTimedTask

```ts
createTimedTask: (input, opts) => Promise<{
  id: string;
}>;
```

登记定时任务（发桥调用 → Scheduler.schedule，直写注册表）。
非法 cron 表达式桥侧拒收（ERR_INVALID_PARAM）：调用方可先经 [nextFireAfter]
本地预览自查，但真拒绝以桥为准（宿主 `CronTab.parse` 是唯一校验出处）。

#### Parameters

| Parameter | Type |
| ------ | ------ |
| `input` | [`CreateTimedTaskInput`](../interfaces/CreateTimedTaskInput.md) |
| `opts` | \{ `timeout?`: `number`; \} |
| `opts.timeout?` | `number` |

#### Returns

`Promise`\<\{
  `id`: `string`;
\}\>

### cron

```ts
cron: (expr) => TimedSchedule & {
  kind: "cron";
};
```

#### Parameters

| Parameter | Type |
| ------ | ------ |
| `expr` | `string` |

#### Returns

[`TimedSchedule`](../type-aliases/TimedSchedule.md) & \{
  `kind`: `"cron"`;
\}

### daily

```ts
daily: (hourOfDay, minuteOfHour) => TimedSchedule & {
  kind: "daily";
};
```

#### Parameters

| Parameter | Type |
| ------ | ------ |
| `hourOfDay` | `number` |
| `minuteOfHour` | `number` |

#### Returns

[`TimedSchedule`](../type-aliases/TimedSchedule.md) & \{
  `kind`: `"daily"`;
\}

### fromInput

```ts
fromInput: (input) => TimedSchedule;
```

#### Parameters

| Parameter | Type |
| ------ | ------ |
| `input` | [`TimedScheduleInput`](../type-aliases/TimedScheduleInput.md) |

#### Returns

[`TimedSchedule`](../type-aliases/TimedSchedule.md)

### listTasks

```ts
listTasks: (opts) => Promise<TimedTaskInfo[]>;
```

列举任务（按 id 排序；与 Kotlin list 回显同形状）。

#### Parameters

| Parameter | Type |
| ------ | ------ |
| `opts` | \{ `timeout?`: `number`; \} |
| `opts.timeout?` | `number` |

#### Returns

`Promise`\<[`TimedTaskInfo`](../interfaces/TimedTaskInfo.md)[]\>

### nextCronFireAfter

```ts
nextCronFireAfter: (expr, nowMillis) => number | null;
```

cron 本地预览（宿主 `CronTab` 的 JS 镜像：字段语义逐条对齐，段内校验从简）。

漂移纪律：本函数只给脚本侧"大概下次何时"的预览；登记与续排的权威是宿主
（`CronTab.parse` 拒非法 → 桥回 ERR_INVALID_PARAM）。两者分歧时以宿主为准，
本镜像的用例只钉"与宿主同值"的几个锚点（每日九点/每周一/不可能日期 null）。

#### Parameters

| Parameter | Type |
| ------ | ------ |
| `expr` | `string` |
| `nowMillis` | `number` |

#### Returns

`number` \| `null`

### nextFireAfter

```ts
nextFireAfter: (schedule, nowMillis) => number | null;
```

下一次应触发时刻（epoch millis，基于 now 的墙钟）。

#### Parameters

| Parameter | Type |
| ------ | ------ |
| `schedule` | [`TimedSchedule`](../type-aliases/TimedSchedule.md) |
| `nowMillis` | `number` |

#### Returns

`number` \| `null`

### once

```ts
once: (afterSeconds) => TimedSchedule & {
  kind: "once";
};
```

#### Parameters

| Parameter | Type |
| ------ | ------ |
| `afterSeconds` | `number` |

#### Returns

[`TimedSchedule`](../type-aliases/TimedSchedule.md) & \{
  `kind`: `"once"`;
\}
