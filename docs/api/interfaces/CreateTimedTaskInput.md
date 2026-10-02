# Interface: CreateTimedTaskInput

建任务输入（与 Kotlin `create` 载荷逐字段对齐；id 缺省服务端分配）。

## Extended by

- [`TimedTaskInfo`](TimedTaskInfo.md)

## Properties

### args?

```ts
readonly optional args?: readonly string[];
```

***

### enabled?

```ts
readonly optional enabled?: boolean;
```

***

### id?

```ts
readonly optional id?: string;
```

***

### name

```ts
readonly name: string;
```

***

### projectId

```ts
readonly projectId: string;
```

***

### schedule

```ts
readonly schedule: TimedSchedule;
```

***

### screen?

```ts
readonly optional screen?: ScreenGuarantee;
```

***

### scriptPath

```ts
readonly scriptPath: string;
```

***

### scriptTimeoutMillis?

```ts
readonly optional scriptTimeoutMillis?: number | null;
```

***

### timezone?

```ts
readonly optional timezone?: string | null;
```
