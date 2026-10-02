# Type Alias: TimedSchedule

```ts
type TimedSchedule = 
  | {
  delaySeconds: number;
  kind: "once";
}
  | {
  hourOfDay: number;
  kind: "daily";
  minuteOfHour: number;
}
  | {
  expr: string;
  kind: "cron";
};
```

Defined in: src/workManager.ts:29

调度计划（运行时不可变形态；cron 校验/推进以宿主 `CronTab` 为准，本文件只镜像）。
