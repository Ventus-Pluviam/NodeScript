# Interface: TimedTaskInfo

登记后的任务（与 Kotlin `list` 回显同形状）。

## Extends

- [`CreateTimedTaskInput`](CreateTimedTaskInput.md)

## Properties

### args?

```ts
readonly optional args?: readonly string[];
```

#### Inherited from

[`CreateTimedTaskInput`](CreateTimedTaskInput.md).[`args`](CreateTimedTaskInput.md#args)

***

### enabled?

```ts
readonly optional enabled?: boolean;
```

#### Inherited from

[`CreateTimedTaskInput`](CreateTimedTaskInput.md).[`enabled`](CreateTimedTaskInput.md#enabled)

***

### id

```ts
readonly id: string;
```

#### Overrides

[`CreateTimedTaskInput`](CreateTimedTaskInput.md).[`id`](CreateTimedTaskInput.md#id)

***

### name

```ts
readonly name: string;
```

#### Inherited from

[`CreateTimedTaskInput`](CreateTimedTaskInput.md).[`name`](CreateTimedTaskInput.md#name)

***

### projectId

```ts
readonly projectId: string;
```

#### Inherited from

[`CreateTimedTaskInput`](CreateTimedTaskInput.md).[`projectId`](CreateTimedTaskInput.md#projectid)

***

### schedule

```ts
readonly schedule: TimedSchedule;
```

#### Inherited from

[`CreateTimedTaskInput`](CreateTimedTaskInput.md).[`schedule`](CreateTimedTaskInput.md#schedule)

***

### screen?

```ts
readonly optional screen?: ScreenGuarantee;
```

#### Inherited from

[`CreateTimedTaskInput`](CreateTimedTaskInput.md).[`screen`](CreateTimedTaskInput.md#screen)

***

### scriptPath

```ts
readonly scriptPath: string;
```

#### Inherited from

[`CreateTimedTaskInput`](CreateTimedTaskInput.md).[`scriptPath`](CreateTimedTaskInput.md#scriptpath)

***

### scriptTimeoutMillis?

```ts
readonly optional scriptTimeoutMillis?: number | null;
```

#### Inherited from

[`CreateTimedTaskInput`](CreateTimedTaskInput.md).[`scriptTimeoutMillis`](CreateTimedTaskInput.md#scripttimeoutmillis)

***

### timezone?

```ts
readonly optional timezone?: string | null;
```

#### Inherited from

[`CreateTimedTaskInput`](CreateTimedTaskInput.md).[`timezone`](CreateTimedTaskInput.md#timezone)
