# Interface: TimedTaskInfo

Defined in: src/workManager.ts:229

登记后的任务（与 Kotlin `list` 回显同形状）。

## Extends

- [`CreateTimedTaskInput`](CreateTimedTaskInput.md)

## Properties

### args?

```ts
readonly optional args?: readonly string[];
```

Defined in: src/workManager.ts:222

#### Inherited from

[`CreateTimedTaskInput`](CreateTimedTaskInput.md).[`args`](CreateTimedTaskInput.md#args)

***

### enabled?

```ts
readonly optional enabled?: boolean;
```

Defined in: src/workManager.ts:225

#### Inherited from

[`CreateTimedTaskInput`](CreateTimedTaskInput.md).[`enabled`](CreateTimedTaskInput.md#enabled)

***

### id

```ts
readonly id: string;
```

Defined in: src/workManager.ts:230

#### Overrides

[`CreateTimedTaskInput`](CreateTimedTaskInput.md).[`id`](CreateTimedTaskInput.md#id)

***

### name

```ts
readonly name: string;
```

Defined in: src/workManager.ts:217

#### Inherited from

[`CreateTimedTaskInput`](CreateTimedTaskInput.md).[`name`](CreateTimedTaskInput.md#name)

***

### projectId

```ts
readonly projectId: string;
```

Defined in: src/workManager.ts:218

#### Inherited from

[`CreateTimedTaskInput`](CreateTimedTaskInput.md).[`projectId`](CreateTimedTaskInput.md#projectid)

***

### schedule

```ts
readonly schedule: TimedSchedule;
```

Defined in: src/workManager.ts:220

#### Inherited from

[`CreateTimedTaskInput`](CreateTimedTaskInput.md).[`schedule`](CreateTimedTaskInput.md#schedule)

***

### screen?

```ts
readonly optional screen?: ScreenGuarantee;
```

Defined in: src/workManager.ts:221

#### Inherited from

[`CreateTimedTaskInput`](CreateTimedTaskInput.md).[`screen`](CreateTimedTaskInput.md#screen)

***

### scriptPath

```ts
readonly scriptPath: string;
```

Defined in: src/workManager.ts:219

#### Inherited from

[`CreateTimedTaskInput`](CreateTimedTaskInput.md).[`scriptPath`](CreateTimedTaskInput.md#scriptpath)

***

### scriptTimeoutMillis?

```ts
readonly optional scriptTimeoutMillis?: number | null;
```

Defined in: src/workManager.ts:223

#### Inherited from

[`CreateTimedTaskInput`](CreateTimedTaskInput.md).[`scriptTimeoutMillis`](CreateTimedTaskInput.md#scripttimeoutmillis)

***

### timezone?

```ts
readonly optional timezone?: string | null;
```

Defined in: src/workManager.ts:224

#### Inherited from

[`CreateTimedTaskInput`](CreateTimedTaskInput.md).[`timezone`](CreateTimedTaskInput.md#timezone)
