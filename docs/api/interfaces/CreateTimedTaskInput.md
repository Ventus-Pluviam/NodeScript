# Interface: CreateTimedTaskInput

Defined in: src/workManager.ts:215

建任务输入（与 Kotlin `create` 载荷逐字段对齐；id 缺省服务端分配）。

## Extended by

- [`TimedTaskInfo`](TimedTaskInfo.md)

## Properties

### args?

```ts
readonly optional args?: readonly string[];
```

Defined in: src/workManager.ts:222

***

### enabled?

```ts
readonly optional enabled?: boolean;
```

Defined in: src/workManager.ts:225

***

### id?

```ts
readonly optional id?: string;
```

Defined in: src/workManager.ts:216

***

### name

```ts
readonly name: string;
```

Defined in: src/workManager.ts:217

***

### projectId

```ts
readonly projectId: string;
```

Defined in: src/workManager.ts:218

***

### schedule

```ts
readonly schedule: TimedSchedule;
```

Defined in: src/workManager.ts:220

***

### screen?

```ts
readonly optional screen?: ScreenGuarantee;
```

Defined in: src/workManager.ts:221

***

### scriptPath

```ts
readonly scriptPath: string;
```

Defined in: src/workManager.ts:219

***

### scriptTimeoutMillis?

```ts
readonly optional scriptTimeoutMillis?: number | null;
```

Defined in: src/workManager.ts:223

***

### timezone?

```ts
readonly optional timezone?: string | null;
```

Defined in: src/workManager.ts:224
