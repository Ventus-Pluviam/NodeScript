# Interface: ErrPayload

桥回包中的可序列化错误骨架（跨进程往返的唯一错误载体）。

## Properties

### code

```ts
code: string;
```

***

### detail?

```ts
optional detail?: string | null;
```

***

### javaClass?

```ts
optional javaClass?: string;
```

***

### javaStack?

```ts
optional javaStack?: string;
```

***

### method?

```ts
optional method?: string;
```

***

### module?

```ts
optional module?: string;
```
