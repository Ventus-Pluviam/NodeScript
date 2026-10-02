# Interface: ErrPayload

Defined in: src/errors.ts:32

桥回包中的可序列化错误骨架（跨进程往返的唯一错误载体）。

## Properties

### code

```ts
code: string;
```

Defined in: src/errors.ts:33

***

### detail?

```ts
optional detail?: string | null;
```

Defined in: src/errors.ts:34

***

### javaClass?

```ts
optional javaClass?: string;
```

Defined in: src/errors.ts:37

***

### javaStack?

```ts
optional javaStack?: string;
```

Defined in: src/errors.ts:38

***

### method?

```ts
optional method?: string;
```

Defined in: src/errors.ts:36

***

### module?

```ts
optional module?: string;
```

Defined in: src/errors.ts:35
