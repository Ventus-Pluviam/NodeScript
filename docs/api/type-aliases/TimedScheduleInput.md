# Type Alias: TimedScheduleInput

```ts
type TimedScheduleInput = 
  | {
  afterSeconds: number;
  on: "once";
}
  | {
  hourOfDay: number;
  minuteOfHour: number;
  on: "daily";
}
  | {
  expr: string;
  on: "cron";
};
```

## Union Members

### Type Literal

```ts
{
  afterSeconds: number;
  on: "once";
}
```

#### afterSeconds

```ts
afterSeconds: number;
```

相对当前时刻的延迟（秒）。

#### on

```ts
on: "once";
```

***

### Type Literal

```ts
{
  hourOfDay: number;
  minuteOfHour: number;
  on: "daily";
}
```

***

### Type Literal

```ts
{
  expr: string;
  on: "cron";
}
```

#### expr

```ts
expr: string;
```

5 字段 `分 时 日 月 周`（如 `0 9 * * 1` = 每周一 09:00）。

#### on

```ts
on: "cron";
```
