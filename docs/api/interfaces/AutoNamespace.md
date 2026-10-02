# Interface: AutoNamespace

Defined in: src/index.ts:38

`auto.*` 命名空间根：脚本 `require('auto')` 拿到的就是它（docs §12.1 唯一入口）。

## Properties

### a11y

```ts
readonly a11y: {
  canPerformGestures: Promise<boolean>;
  events: Promise<UiEventBatch>;
  gesture: Promise<boolean>;
  selector: UiSelector;
  waitFor: Promise<boolean>;
};
```

Defined in: src/index.ts:40

#### canPerformGestures()

```ts
canPerformGestures(opts?): Promise<boolean>;
```

手势能力门（§9.1 canPerformGestures；false 时走能力中心引导，不发手势）。

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `opts` | \{ `timeout?`: `number`; \} |
| `opts.timeout?` | `number` |

##### Returns

`Promise`\<`boolean`\>

#### events()

```ts
events(opts?): Promise<UiEventBatch>;
```

事件流拉取（§9.1 节流拉取式，seq 游标；对偶 Kotlin `a11y.events`）。
空增量回 `{first:sinceSeq,last:sinceSeq,events:[]}`——调用方以前进游标为准。

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `opts` | \{ `batch?`: `number`; `sinceSeq?`: `number`; `timeout?`: `number`; \} |
| `opts.batch?` | `number` |
| `opts.sinceSeq?` | `number` |
| `opts.timeout?` | `number` |

##### Returns

`Promise`\<`UiEventBatch`\>

#### gesture()

```ts
gesture(input, opts?): Promise<boolean>;
```

手势派发（§9.1 dispatchGesture；对偶 Kotlin `a11y.gesture`）。
关门回 false（不抛错）；非法手势（空笔画/负坐标/非正 duration）抛 ERR_INVALID_PARAM。

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `input` | `GestureInput` |
| `opts` | \{ `signal?`: `AbortSignal`; `timeout?`: `number`; \} |
| `opts.signal?` | `AbortSignal` |
| `opts.timeout?` | `number` |

##### Returns

`Promise`\<`boolean`\>

#### selector()

```ts
selector(): UiSelector;
```

创建选择器：链式条件，findOne 失败抛 NotFoundError。

##### Returns

`UiSelector`

#### waitFor()

```ts
waitFor(sel, opts?): Promise<boolean>;
```

等待某条件出现（一定次数内触发则成功；§12.3 waitFor）。载荷键见下：`conditions`。

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `sel` | `UiSelector` |
| `opts` | \{ `interval?`: `number`; `timeout?`: `number`; \} |
| `opts.interval?` | `number` |
| `opts.timeout?` | `number` |

##### Returns

`Promise`\<`boolean`\>

***

### app

```ts
readonly app: {
  currentPackage: Promise<string | null>;
  launch: Promise<boolean>;
};
```

Defined in: src/index.ts:50

#### currentPackage()

```ts
currentPackage(opts?): Promise<string | null>;
```

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `opts` | \{ `timeout?`: `number`; \} |
| `opts.timeout?` | `number` |

##### Returns

`Promise`\<`string` \| `null`\>

#### launch()

```ts
launch(packageName, opts?): Promise<boolean>;
```

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `packageName` | `string` |
| `opts` | \{ `timeout?`: `number`; \} |
| `opts.timeout?` | `number` |

##### Returns

`Promise`\<`boolean`\>

***

### bridge

```ts
readonly bridge: RuntimeBridgeImpl;
```

Defined in: src/index.ts:39

***

### clipboard

```ts
readonly clipboard: {
  getText: Promise<string | null>;
  setText: Promise<void>;
};
```

Defined in: src/index.ts:56

#### getText()

```ts
getText(opts?): Promise<string | null>;
```

读剪贴板文本；null = 无内容（空剪贴板 / 后台受限时系统的 null 答案）。

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `opts` | \{ `timeout?`: `number`; \} |
| `opts.timeout?` | `number` |

##### Returns

`Promise`\<`string` \| `null`\>

#### setText()

```ts
setText(text, opts?): Promise<void>;
```

写剪贴板文本（空串是合法内容，原样存）。

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `text` | `string` |
| `opts` | \{ `timeout?`: `number`; \} |
| `opts.timeout?` | `number` |

##### Returns

`Promise`\<`void`\>

***

### console

```ts
readonly console: {
  debug: Promise<void>;
  error: Promise<void>;
  info: Promise<void>;
  log: Promise<void>;
  onQueueError: () => void;
  warn: Promise<void>;
};
```

Defined in: src/index.ts:46

#### debug()

```ts
debug(...args): Promise<void>;
```

##### Parameters

| Parameter | Type |
| ------ | ------ |
| ...`args` | `unknown`[] |

##### Returns

`Promise`\<`void`\>

#### error()

```ts
error(...args): Promise<void>;
```

##### Parameters

| Parameter | Type |
| ------ | ------ |
| ...`args` | `unknown`[] |

##### Returns

`Promise`\<`void`\>

#### info()

```ts
info(...args): Promise<void>;
```

##### Parameters

| Parameter | Type |
| ------ | ------ |
| ...`args` | `unknown`[] |

##### Returns

`Promise`\<`void`\>

#### log()

```ts
log(...args): Promise<void>;
```

##### Parameters

| Parameter | Type |
| ------ | ------ |
| ...`args` | `unknown`[] |

##### Returns

`Promise`\<`void`\>

#### onQueueError()

```ts
onQueueError(listener): () => void;
```

背压/丢包回调（§7.3 queueError）。返回退订函数。

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `listener` | `QueueErrorListener` |

##### Returns

() => `void`

#### warn()

```ts
warn(...args): Promise<void>;
```

##### Parameters

| Parameter | Type |
| ------ | ------ |
| ...`args` | `unknown`[] |

##### Returns

`Promise`\<`void`\>

***

### datastore

```ts
readonly datastore: {
  clear: Promise<void>;
  contains: Promise<boolean>;
  get: Promise<unknown>;
  keys: Promise<string[]>;
  put: Promise<void>;
  remove: Promise<boolean>;
};
```

Defined in: src/index.ts:52

#### clear()

```ts
clear(opts?): Promise<void>;
```

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `opts` | \{ `timeout?`: `number`; \} |
| `opts.timeout?` | `number` |

##### Returns

`Promise`\<`void`\>

#### contains()

```ts
contains(key, opts?): Promise<boolean>;
```

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `key` | `string` |
| `opts` | \{ `timeout?`: `number`; \} |
| `opts.timeout?` | `number` |

##### Returns

`Promise`\<`boolean`\>

#### get()

```ts
get(key, opts?): Promise<unknown>;
```

读一键；缺失 `undefined`，存的 JSON null 回 `null`（两者不折叠）。

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `key` | `string` |
| `opts` | \{ `timeout?`: `number`; \} |
| `opts.timeout?` | `number` |

##### Returns

`Promise`\<`unknown`\>

#### keys()

```ts
keys(opts?): Promise<string[]>;
```

全部键（顺序不作契约保证，调用方如需稳定序自行排序）。

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `opts` | \{ `timeout?`: `number`; \} |
| `opts.timeout?` | `number` |

##### Returns

`Promise`\<`string`[]\>

#### put()

```ts
put(
   key, 
   value, 
   opts?
): Promise<void>;
```

写/覆盖一键（任意 JSON 可序列化值）。

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `key` | `string` |
| `value` | `unknown` |
| `opts` | \{ `timeout?`: `number`; \} |
| `opts.timeout?` | `number` |

##### Returns

`Promise`\<`void`\>

#### remove()

```ts
remove(key, opts?): Promise<boolean>;
```

删一键；回是否真的移除了东西（键不存在 → false，幂等不抛）。

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `key` | `string` |
| `opts` | \{ `timeout?`: `number`; \} |
| `opts.timeout?` | `number` |

##### Returns

`Promise`\<`boolean`\>

***

### device

```ts
readonly device: {
  model: Promise<string>;
  sdkInt: Promise<number>;
};
```

Defined in: src/index.ts:49

#### model()

```ts
model(opts?): Promise<string>;
```

品牌/型号/系统（P0 最小集；其余 P2）。

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `opts` | \{ `timeout?`: `number`; \} |
| `opts.timeout?` | `number` |

##### Returns

`Promise`\<`string`\>

#### sdkInt()

```ts
sdkInt(opts?): Promise<number>;
```

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `opts` | \{ `timeout?`: `number`; \} |
| `opts.timeout?` | `number` |

##### Returns

`Promise`\<`number`\>

***

### dialogs

```ts
readonly dialogs: {
  choose: Promise<number>;
  prompt: Promise<DialogResult>;
};
```

Defined in: src/index.ts:47

#### choose()

```ts
choose(
   title, 
   options, 
   opts?
): Promise<number>;
```

选择框（同步选项列表；返回选中索引，取消 -1）。

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `title` | `string` |
| `options` | readonly `string`[] |
| `opts` | \{ `mode?`: `DialogMode`; `timeout?`: `number`; \} |
| `opts.mode?` | `DialogMode` |
| `opts.timeout?` | `number` |

##### Returns

`Promise`\<`number`\>

#### prompt()

```ts
prompt(title, opts?): Promise<DialogResult>;
```

输入框（overlay 可见时弹窗，否则通知回调）；取消 → { value: null, confirmed: false }。

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `title` | `string` |
| `opts` | \{ `mode?`: `DialogMode`; `placeholder?`: `string`; `timeout?`: `number`; \} |
| `opts.mode?` | `DialogMode` |
| `opts.placeholder?` | `string` |
| `opts.timeout?` | `number` |

##### Returns

`Promise`\<`DialogResult`\>

***

### engines

```ts
readonly engines: {
  channel: Promise<RuntimeChannel>;
  exec: Promise<EngineSession>;
  heartbeat: Promise<boolean>;
  poolStats: Promise<EnginePoolStats>;
  status: Promise<EngineStatus>;
  stop: Promise<boolean>;
};
```

Defined in: src/index.ts:41

#### channel()

```ts
channel(name, opts?): Promise<RuntimeChannel>;
```

打开（或复用）命名通道（§8：同 host 的通道生命周期由 :app-service:runtime 管理）。
回包包成 [EngineChannel]（此前是 wire 原样 `as RuntimeChannel` —— `.emit()` 当场
TypeError 的空壳，已补实）。

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `name` | `string` |
| `opts` | \{ `timeout?`: `number`; \} |
| `opts.timeout?` | `number` |

##### Returns

`Promise`\<`RuntimeChannel`\>

#### exec()

```ts
exec(request, opts?): Promise<EngineSession>;
```

启动一次执行（§8 池仲裁；返回会话句柄）。同引擎一次一脚本；运行态由 RuntimeController 仲裁。
超载排队，不做静默丢弃。

**`timeoutMillis` 必填**（见 [EngineRunRequest]）：本调用返回的是句柄不是结果，
宿主那条 run 没有人 await —— 期限是它唯一的收尾人。宿主对缺席/非正值回
`ERR_INVALID_PARAM`，本层**不预检**（两处校验必然漂移，与空事件名同一条纪律）。

回包包成 [EngineSessionImpl]（此前是 wire 原样 `as EngineSession` —— `.cancel()`
当场 TypeError 的空壳，已补实；与 `channel()` 包 `EngineChannel` 同一条纪律）。

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `request` | `EngineRunRequest` |
| `opts` | \{ `signal?`: `AbortSignal`; `timeout?`: `number`; \} |
| `opts.signal?` | `AbortSignal` |
| `opts.timeout?` | `number` |

##### Returns

`Promise`\<`EngineSession`\>

#### heartbeat()

```ts
heartbeat(
   runId, 
   seq, 
   opts?
): Promise<boolean>;
```

心跳打点（§8.4 缺口②的引擎侧源头）：宿主据此算「距上次心跳多久」，看门狗据此判失联。

`seq` 单调递增，由本进程内计数器给出（见 [heartbeatSeq]）：宿主的账本只认递增序号，
重复/乱序帧不被采纳（回 false）。**不要**为了"显得活着"而高频重发同一 seq ——
那既骗不过账本，也会让积压帧把死掉之后的样子伪装成活的。

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `runId` | `number` |
| `seq` | `number` |
| `opts` | \{ `timeout?`: `number`; \} |
| `opts.timeout?` | `number` |

##### Returns

`Promise`\<`boolean`\>

#### poolStats()

```ts
poolStats(opts?): Promise<EnginePoolStats>;
```

池容量快照（容量/空闲/占用）。

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `opts` | \{ `timeout?`: `number`; \} |
| `opts.timeout?` | `number` |

##### Returns

`Promise`\<`EnginePoolStats`\>

#### status()

```ts
status(runId, opts?): Promise<EngineStatus>;
```

引擎侧状态快照（`onExit` 轮询的地基；Kotlin `probeStatus` 只读在途表）。

在途 → 状态名字符串（与 :domain `EngineStatus` 枚举名逐字一致）；
已结算/从未存在 → 抛 `ERR_NOT_FOUND`（结算后无状态可读，宿主不伪造 `"STOPPED"`，
见 handler `status` 注释 —— 调用方不得把 NOT_FOUND 翻译成任何终态）。

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `runId` | `number` |
| `opts` | \{ `timeout?`: `number`; \} |
| `opts.timeout?` | `number` |

##### Returns

`Promise`\<`EngineStatus`\>

#### stop()

```ts
stop(runId, opts?): Promise<boolean>;
```

请求侧主动停止（PoolAcquireOutcome 语义）：runId 未发行 → failed；发行后取消是 EngineSession.cancel。

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `runId` | `number` |
| `opts` | \{ `timeout?`: `number`; \} |
| `opts.timeout?` | `number` |

##### Returns

`Promise`\<`boolean`\>

***

### envelope

```ts
readonly envelope: {
  encodeRequest: BridgeRequest;
  err: BridgeResponse;
  ok: BridgeResponse;
};
```

Defined in: src/index.ts:59

#### encodeRequest()

```ts
readonly encodeRequest(req): BridgeRequest;
```

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `req` | `Omit`\<`BridgeRequest`, `"t"`\> |

##### Returns

`BridgeRequest`

#### err()

```ts
readonly err(
   id, 
   code, 
   detail?
): BridgeResponse;
```

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `id` | `number` |
| `code` | `string` |
| `detail?` | `string` \| `null` |

##### Returns

`BridgeResponse`

#### ok()

```ts
readonly ok(id, payload?): BridgeResponse;
```

##### Parameters

| Parameter | Type | Default value |
| ------ | ------ | ------ |
| `id` | `number` | `undefined` |
| `payload` | `string` \| `null` | `null` |

##### Returns

`BridgeResponse`

***

### floatingWindow

```ts
readonly floatingWindow: {
  close: Promise<void>;
  create: Promise<FloatingWindowRef>;
};
```

Defined in: src/index.ts:51

#### close()

```ts
close(ref, opts?): Promise<void>;
```

关闭（宿主侧透传；未知/跨代句柄回 ERR_STALE_HANDLE，释放不了的窗口不说成已关）。
句柄必须来自 [create] —— 不猜 id、不自造 generation。

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `ref` | `FloatingWindowRef` |
| `opts` | \{ `timeout?`: `number`; \} |
| `opts.timeout?` | `number` |

##### Returns

`Promise`\<`void`\>

#### create()

```ts
create(opts?): Promise<FloatingWindowRef>;
```

创建悬浮窗宿主（overlay 权限门禁在装配层，被拒 → ERR_PERMISSION_DENIED）。
**形状参数原样过桥**（§12.3.3 已收口）：`{title,width,height}` 进 payload，
缺省字段发 null（handler 的 `optStr`/`optLong` 把 null 当缺席，不套错默认）。

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `opts` | \{ `height?`: `number` \| `null`; `timeout?`: `number`; `title?`: `string` \| `null`; `width?`: `number` \| `null`; \} |
| `opts.height?` | `number` \| `null` |
| `opts.timeout?` | `number` |
| `opts.title?` | `string` \| `null` |
| `opts.width?` | `number` \| `null` |

##### Returns

`Promise`\<`FloatingWindowRef`\>

***

### images

```ts
readonly images: {
  crop: Promise<FrameSource>;
  decode: Promise<FrameSource>;
  findColor: Promise<ColorHitResult | null>;
  findFeature: Promise<FeatureHitResult | null>;
  findImage: Promise<MatchResult | null>;
  fromFile: Promise<FrameSource>;
  matchTemplate: Promise<MatchResult | null>;
  release: Promise<void>;
  resize: Promise<FrameSource>;
  rotate: Promise<FrameSource>;
  toGrayscale: Promise<FrameSource>;
};
```

Defined in: src/index.ts:44

#### crop()

```ts
crop(
   frame, 
   region, 
   opts?
): Promise<FrameSource>;
```

裁剪：`frame` 的 `region` 子矩形 → 新帧（尺寸 = region 的 `[x,y,w,h]`；不含
region 外像素）。**必须给 region**（缺省 = `ERR_INVALID_PARAM`，不是整帧副本）。
region 须**整体落在帧内**（`x+w == 宽` 贴边合法，越界 → `ERR_INVALID_PARAM`，
不静默裁剪成"只看得到的那半"）—— 与 findColor 的 region 同一条边界口径。

第三个参数 defaultValue 没有 —— region 是必填（v9 也要求区域）。

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `frame` | `FrameSource` |
| `region` | `Region4` |
| `opts` | \{ `timeout?`: `number`; \} |
| `opts.timeout?` | `number` |

##### Returns

`Promise`\<`FrameSource`\>

#### decode()

```ts
decode(path, opts?): Promise<FrameSource>;
```

从文件读一帧（`decode`，§9.2）：回 `{ref,width,height}` 帧句柄 —— 宽高是**文件真值**
（脚本要拿它做坐标换算）。路径不得空白；文件缺失/不是合法图片由宿主原码透传
（`ERR_FILE_NOT_FOUND`/`ERR_IO`，不折叠成参数错）。

**路径写绝对路径**（§18 第 9 项 2026-09-25 已拍板：只收绝对路径）：**四层里没有一层解析路径** —— 计算核直接 `fopen`/`imread`，
于是相对路径按**宿主进程 CWD** 解析，而 so 载在 `:main` 里、那个进程的 CWD 是 `/`。
`decode('part.png')` 会去根目录找一个并不存在的文件，**回 `ERR_FILE_NOT_FOUND`
且报的路径是对的** —— 看起来像"文件真的不在"，不像"口径没定"。别写相对路径。

帧是**文件侧**的句柄：`recycle()` 打 `images/release`（不是 `screen/recycle`）。
两个入口通到同一张表，放过的帧两边都认得"已释放"。

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `path` | `string` |
| `opts` | \{ `timeout?`: `number`; \} |
| `opts.timeout?` | `number` |

##### Returns

`Promise`\<`FrameSource`\>

#### findColor()

```ts
findColor(
   haystack, 
   color, 
   tolerance, 
   opts?
): Promise<ColorHitResult | null>;
```

找色（`findColor`，§9.2 native 面第一个 P1 算子；§7.7 承诺 `findColor` 1080p < 10ms）：
在 `haystack` 帧（或其 `region` 子矩形）里找**第一个**与 `color` 的**每个分量**
差都不超过 `tolerance` 的像素，回它的全帧坐标与实际像素分量。

与 `matchTemplate` 的分界：那是"整块图案在哪"，这是"这个色在哪"—— 找色不问
图案、形状、连通性，只看分量是否落在容差带内（native `inRange` 的逐分量包含语义）。

命中多个时回的是一个**稳定可复现**的坐标，但不承诺"离左上角最近"——要挑
最近/最大连通域的脚本拿 x/y 自己再筛。

**未命中是答案不是异常**：回 `null`（扫过了、没有），不编 `ERR_NOT_FOUND`。
但**"扫过 0 像素"**（空区域/region 越界）是 `ERR_INVALID_PARAM` —— 那不是"没有"，
是"根本没找"，混成 `null` 会让脚本把空区域当成搜过一遍。

参数域（越界一律 `ERR_INVALID_PARAM` 且一次 native 调用都不发）：
`color` 恒四分量 `[r,g,b,a]`（**R,G,B,A 序**，与 Android `0xAARRGGBB` 同序），
各 `[0,255]`；`tolerance` `[0,255]`（逐分量，非欧氏距离）；`region` 给了必须四元组
且整体落在帧内（不静默裁剪 —— 半截区域在帧外时"帧外的像素"没有答案）。

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `haystack` | `FrameSource` |
| `color` | readonly `number`[] |
| `tolerance` | `number` |
| `opts` | \{ `region?`: `Region4`; `timeout?`: `number`; \} |
| `opts.region?` | `Region4` |
| `opts.timeout?` | `number` |

##### Returns

`Promise`\<`ColorHitResult` \| `null`\>

#### findFeature()

```ts
findFeature(
   scene, 
   template, 
   opts?
): Promise<FeatureHitResult | null>;
```

特征匹配（`findFeature`）：在 `scene` 帧里找 `template` 帧的**不同尺寸/轻微
旋转变体**，回**模板中心**坐标 + 置信度。与 [matchTemplate] 的左上角 + 模板
尺寸不同：特征匹配没有"模板尺寸"概念（模板在场景里多大是未知的），回中心
让脚本直接点下去。

链全固定（ORB 1000 → BFMatcher HAMMING → Lowe ratio 0.75 → 中位数偏移
±3px 几何一致性）；**未匹配是答案**：回 `null`（与 matchTemplate 同一条
纪律 —— 纯色模板"空描述子"也落未匹配，不是异常）。**无阈值入参**：置信度
随回包给，是否"够"由调用方自己判。

刚性匹配（matchTemplate，模板尺寸必须一致）与特征匹配（容忍缩放/旋转）是
两种找图语义，脚本按场景任选其一。

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `scene` | `FrameSource` |
| `template` | `FrameSource` |
| `opts` | \{ `timeout?`: `number`; \} |
| `opts.timeout?` | `number` |

##### Returns

`Promise`\<`FeatureHitResult` \| `null`\>

#### findImage()

```ts
findImage(
   haystack, 
   needle, 
   opts?
): Promise<MatchResult | null>;
```

找图（`findImage`，threshold/region 语义/缺省同 [matchTemplate]）。
v9 的两个名字是同一个 opencv 概念：wire 形状逐字段相同，宿主侧同一套校验。

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `haystack` | `FrameSource` |
| `needle` | `FrameSource` |
| `opts` | \{ `region?`: `Region4`; `threshold?`: `number`; `timeout?`: `number`; \} |
| `opts.region?` | `Region4` |
| `opts.threshold?` | `number` |
| `opts.timeout?` | `number` |

##### Returns

`Promise`\<`MatchResult` \| `null`\>

#### fromFile()

```ts
fromFile(path, opts?): Promise<FrameSource>;
```

从文件读图（`decode` 的 v9 名：Pro 侧叫 `fromFile`）。保留此别名是为了脚本可读性，
wire 上仍是 `decode`（两侧同名，不搞两套方法名）。

别名只此一个：`load`/`open`/`read`/`bitmap` 一律不提供 —— 宿主侧同样只认 `decode`。

v9 的 `fromFile('part.png')` 这种相对写法**在 AutoScript 眼下不成立**（同上：
无路径解析，按 `:main` 的 CWD 走）。别名保留的是名字，不是相对路径语义。

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `path` | `string` |
| `opts` | \{ `timeout?`: `number`; \} |
| `opts.timeout?` | `number` |

##### Returns

`Promise`\<`FrameSource`\>

#### matchTemplate()

```ts
matchTemplate(
   haystack, 
   needle, 
   opts?
): Promise<MatchResult | null>;
```

模板匹配（`matchTemplate`）：在 `haystack` 帧（或其 `region` 子矩形）里找
`needle` 帧，置信度 ≥ `threshold` 即命中。**两帧只要是同一张表里的在场句柄
就行** —— `screen.capture()` 的帧可以直接当 haystack（§18-8(b) 两 namespace
共用帧表）。

未匹配**不是异常**：回 `null`（图里没有达到阈值的位置）。帧已释放 →
`ERR_STALE_HANDLE`；阈值缺省 `0.9`（v9 同名默认值；域 `[0,1]` 之外 →
`ERR_INVALID_PARAM` 且一次匹配都不发）。

`region` 可选 `[x,y,w,h]` 搜索范围（缺省 = 全帧）：给了必须**整体**落在帧内
（越界 → `ERR_INVALID_PARAM`，不静默裁剪）；**region 比模板小 → `ERR_IO`**
（与"模板比画面大"同属参数关系不成立）。命中坐标恒是**全帧坐标**（区域只是
搜索范围不是坐标系，与 findColor 同款）。已知大致位置就传 —— 缩窗是小模板
（进不了金字塔粗筛的 <80px 那类）提速的主要出路。

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `haystack` | `FrameSource` |
| `needle` | `FrameSource` |
| `opts` | \{ `region?`: `Region4`; `threshold?`: `number`; `timeout?`: `number`; \} |
| `opts.region?` | `Region4` |
| `opts.threshold?` | `number` |
| `opts.timeout?` | `number` |

##### Returns

`Promise`\<`MatchResult` \| `null`\>

#### release()

```ts
release(frame, opts?): Promise<void>;
```

释放 `decode` 出来的帧。首次释放回 `true`；**再放同一帧 → `ERR_STALE_HANDLE`**
（不是静默成功也不是内部错 —— 未知/跨代同码，"已释放"与"从未存在"由这句 detail 可辨）。
所以脚本 `finally` 里的补刀要自己兜这个码（或只放一次）。帧对象自带的
`recycle()` 就是转发到这里。

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `frame` | `FrameSource` |
| `opts` | \{ `timeout?`: `number`; \} |
| `opts.timeout?` | `number` |

##### Returns

`Promise`\<`void`\>

#### resize()

```ts
resize(
   frame, 
   width, 
   height, 
   opts?
): Promise<FrameSource>;
```

缩放：`frame` → 目标尺寸 `width` × `height` 新帧（像素值重算，逐点不一定等于
原帧）。插值固定 LINEAR（不做入参）。入参是**目标尺寸**不是倍数。

域：正整数 + 单边配额 16384（16384²×4≈1GB，再往上是笔误把字节数当宽高 ——
越界 `ERR_INVALID_PARAM` 且一次调用都不发）。

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `frame` | `FrameSource` |
| `width` | `number` |
| `height` | `number` |
| `opts` | \{ `timeout?`: `number`; \} |
| `opts.timeout?` | `number` |

##### Returns

`Promise`\<`FrameSource`\>

#### rotate()

```ts
rotate(
   frame, 
   degrees, 
   opts?
): Promise<FrameSource>;
```

旋转：`frame` 绕帧中心**逆时针**转 `degrees` 度 → 新帧。角度必须**有限**
（NaN/Inf → `ERR_INVALID_PARAM`）。

画布是 **expand**（包住整图不静默裁像素 —— 宽高随角度由包络公式算出，随回包
给真值）；0°/360° 恒等。插值 LINEAR、填充 REPLICATE（黑边是找色的假阳性源）。
想要"旋转裁剪"：先 `rotate` 再 `crop`（两个算子都在）。

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `frame` | `FrameSource` |
| `degrees` | `number` |
| `opts` | \{ `timeout?`: `number`; \} |
| `opts.timeout?` | `number` |

##### Returns

`Promise`\<`FrameSource`\>

#### toGrayscale()

```ts
toGrayscale(frame, opts?): Promise<FrameSource>;
```

灰度化：`frame` → 新帧（4 通道 BGRA，三通道同灰值、alpha 原样带过去）。
**产出新帧不改原帧**：原帧照常可用，新帧要 `recycle()` 独立释放（与 decode 帧
同一条路 —— 同一个 ref 信封 + 宽高真值）。灰值按 0.299R+0.587G+0.114B
（OpenCV `COLOR_BGRA2GRAY`），权重不经本层。

用途由调用方定：匹配前的预处理（灰帧不改变 findImage 的结果，省一点计算）、
给下游取"亮度面"。

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `frame` | `FrameSource` |
| `opts` | \{ `timeout?`: `number`; \} |
| `opts.timeout?` | `number` |

##### Returns

`Promise`\<`FrameSource`\>

***

### installed

```ts
readonly installed: boolean;
```

Defined in: src/index.ts:64

***

### notification

```ts
readonly notification: {
  cancel: Promise<void>;
  canPost: Promise<boolean>;
  post: Promise<void>;
};
```

Defined in: src/index.ts:55

#### cancel()

```ts
cancel(id, opts?): Promise<void>;
```

按 id 撤销；幂等无回执（撤一个没发过的 id 什么也不会发生）。

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `id` | `number` |
| `opts` | \{ `timeout?`: `number`; \} |
| `opts.timeout?` | `number` |

##### Returns

`Promise`\<`void`\>

#### canPost()

```ts
canPost(opts?): Promise<boolean>;
```

通知是否可发（应用通知开关 + `POST_NOTIFICATIONS`；读侧不需要授权）。

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `opts` | \{ `timeout?`: `number`; \} |
| `opts.timeout?` | `number` |

##### Returns

`Promise`\<`boolean`\>

#### post()

```ts
post(spec, opts?): Promise<void>;
```

发/覆盖一条通知。未授权 → 抛 `ERR_PERMISSION_DENIED`。

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `spec` | `NotificationSpec` |
| `opts` | \{ `timeout?`: `number`; \} |
| `opts.timeout?` | `number` |

##### Returns

`Promise`\<`void`\>

***

### npm

```ts
readonly npm: {
  audit: Promise<AuditReport>;
  ci: Promise<void>;
  dedupe: Promise<void>;
  importOfflineBundle: Promise<void>;
  importTarball: Promise<void>;
  install: Promise<InstallQueued>;
  list: Promise<PkgNode[]>;
  offlineGap: Promise<MissingPkg[]>;
  onApproval: () => void;
  onFinished: () => void;
  onProgress: () => void;
  onWarning: () => void;
  prune: Promise<void>;
  remove: Promise<void>;
  requestApprove: Promise<ApprovalTicket>;
  setRegistry: Promise<void>;
};
```

Defined in: src/index.ts:45

#### audit()

```ts
audit(opts?): Promise<AuditReport>;
```

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `opts` | \{ `offline?`: `boolean`; `timeout?`: `number`; \} |
| `opts.offline?` | `boolean` |
| `opts.timeout?` | `number` |

##### Returns

`Promise`\<`AuditReport`\>

#### ci()

```ts
ci(opts?): Promise<void>;
```

lockfile v3 严格重建（验签后）；市场脚本唯一入口。

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `opts` | \{ `offline?`: `boolean`; `timeout?`: `number`; \} |
| `opts.offline?` | `boolean` |
| `opts.timeout?` | `number` |

##### Returns

`Promise`\<`void`\>

#### dedupe()

```ts
dedupe(opts?): Promise<void>;
```

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `opts` | \{ `timeout?`: `number`; \} |
| `opts.timeout?` | `number` |

##### Returns

`Promise`\<`void`\>

#### importOfflineBundle()

```ts
importOfflineBundle(uri, opts?): Promise<void>;
```

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `uri` | `string` |
| `opts` | \{ `timeout?`: `number`; \} |
| `opts.timeout?` | `number` |

##### Returns

`Promise`\<`void`\>

#### importTarball()

```ts
importTarball(path, opts?): Promise<void>;
```

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `path` | `string` |
| `opts` | \{ `timeout?`: `number`; \} |
| `opts.timeout?` | `number` |

##### Returns

`Promise`\<`void`\>

#### install()

```ts
install(spec, opts?): Promise<InstallQueued>;
```

安装（排队→门禁→起会话→执行→post-check→归档；P0）。回包 = 排队结果，非装完。

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `spec` | `string` |
| `opts` | \{ `offline?`: `boolean`; `save?`: `boolean`; `timeout?`: `number`; \} |
| `opts.offline?` | `boolean` |
| `opts.save?` | `boolean` |
| `opts.timeout?` | `number` |

##### Returns

`Promise`\<`InstallQueued`\>

#### list()

```ts
list(opts?): Promise<PkgNode[]>;
```

轻操作：Kotlin 直读，不依赖网络。

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `opts` | \{ `depth?`: `number`; `timeout?`: `number`; \} |
| `opts.depth?` | `number` |
| `opts.timeout?` | `number` |

##### Returns

`Promise`\<`PkgNode`[]\>

#### offlineGap()

```ts
offlineGap(opts?): Promise<MissingPkg[]>;
```

离线闭包差距（缺哪些包、共多大）。

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `opts` | \{ `timeout?`: `number`; \} |
| `opts.timeout?` | `number` |

##### Returns

`Promise`\<`MissingPkg`[]\>

#### onApproval()

```ts
onApproval(listener): () => void;
```

审批请求事件（宿主 approvals 拉取口；自己的轮询与安装事件互不牵连）。

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `listener` | (`req`) => `void` |

##### Returns

() => `void`

#### onFinished()

```ts
onFinished(listener): () => void;
```

安装终止（成功**和**失败都发，detail 带失败原因）。

`install()` 的回包只是「已入队」，装没装完只能听这里 —— 没有它，脚本要么
轮询 `list()` 猜、要么干脆不知道失败（§1 诚实原则）。

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `listener` | (`e`) => `void` |

##### Returns

() => `void`

#### onProgress()

```ts
onProgress(listener): () => void;
```

进度事件（数据面，可丢包）。返回退订函数；首订即开拉取轮询。

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `listener` | (`e`) => `void` |

##### Returns

() => `void`

#### onWarning()

```ts
onWarning(listener): () => void;
```

警告（此类不可恢复的静默漂移变响亮错误）。

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `listener` | (`e`) => `void` |

##### Returns

() => `void`

#### prune()

```ts
prune(opts?): Promise<void>;
```

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `opts` | \{ `timeout?`: `number`; \} |
| `opts.timeout?` | `number` |

##### Returns

`Promise`\<`void`\>

#### remove()

```ts
remove(spec, opts?): Promise<void>;
```

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `spec` | `string` |
| `opts` | \{ `timeout?`: `number`; \} |
| `opts.timeout?` | `number` |

##### Returns

`Promise`\<`void`\>

#### requestApprove()

```ts
requestApprove(pkg, opts?): Promise<ApprovalTicket>;
```

审批：只提交请求，绝不脚本直调（人机分离，UI 人工确认）。

回包 `{requestId, status, scripts}`：前两个是宿主票号与状态（`pending`），
[ApprovalRequest.scripts] 是**入参回显** —— 宿主校验了数组形态并原样带回，
让脚本能确认「我声明的脚本清单宿主收到了」。不回显的话，宿主与脚本各持一份
scripts，改了哪一侧都看不出来（与 setRegistry 的 scope 同一条纪律）。

若宿主拒绝提交，会抛 ERR_PERMISSION_DENIED/ERR_NPM_* —— 如实上抛。

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `pkg` | `string` |
| `opts` | \{ `scripts?`: readonly `string`[]; `timeout?`: `number`; `versionHash?`: `string`; \} |
| `opts.scripts?` | readonly `string`[] |
| `opts.timeout?` | `number` |
| `opts.versionHash?` | `string` |

##### Returns

`Promise`\<`ApprovalTicket`\>

#### setRegistry()

```ts
setRegistry(registry, opts?): Promise<void>;
```

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `registry` | `string` |
| `opts` | \{ `scope?`: `string`; `timeout?`: `number`; \} |
| `opts.scope?` | `string` |
| `opts.timeout?` | `number` |

##### Returns

`Promise`\<`void`\>

***

### power

```ts
readonly power: {
  acquire: Promise<string>;
  release: Promise<boolean>;
  status: Promise<{
     held: boolean;
     holders: number;
  }>;
};
```

Defined in: src/index.ts:58

#### acquire()

```ts
acquire(timeoutMillis, opts?): Promise<string>;
```

持一把限时唤醒锁；回服务端分配的 token（用完记得 `release`，到期宿主也会自动收）。

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `timeoutMillis` | `number` |
| `opts` | \{ `timeout?`: `number`; \} |
| `opts.timeout?` | `number` |

##### Returns

`Promise`\<`string`\>

#### release()

```ts
release(token, opts?): Promise<boolean>;
```

放自己那一份；false = 该 token 当时并未持有（重复放/陌生 token/已过期）。

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `token` | `string` |
| `opts` | \{ `timeout?`: `number`; \} |
| `opts.timeout?` | `number` |

##### Returns

`Promise`\<`boolean`\>

#### status()

```ts
status(opts?): Promise<{
  held: boolean;
  holders: number;
}>;
```

锁现状：held（门禁判据）+ holders（账本席位数）。

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `opts` | \{ `timeout?`: `number`; \} |
| `opts.timeout?` | `number` |

##### Returns

`Promise`\<\{
  `held`: `boolean`;
  `holders`: `number`;
\}\>

***

### screen

```ts
readonly screen: {
  capture: Promise<FrameSource>;
  startCapturer: Promise<ScreenCapturer>;
};
```

Defined in: src/index.ts:43

#### capture()

```ts
capture(opts?): Promise<FrameSource>;
```

截图（§9.2）：a11y takeScreenshot（333ms 节流）/ MediaProjection 会话。
分类错误直接抛（§8.8）：锁屏 → ERR_SCREEN_LOCKED，FLAG_SECURE → ERR_BLACK_FRAME，
无窗口/空帧 → ERR_SERVICE_DISABLED，节流命中 → ERR_INVALID_PARAM（退避重试）。
成功回帧句柄 `{ref:{refId,generation},width,height}` + `recycle()` 显式释放。

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `opts` | \{ `signal?`: `AbortSignal`; `timeout?`: `number`; \} |
| `opts.signal?` | `AbortSignal` |
| `opts.timeout?` | `number` |

##### Returns

`Promise`\<`FrameSource`\>

#### startCapturer()

```ts
startCapturer(opts?): Promise<ScreenCapturer>;
```

会话式截屏（ScreenCapturer，§9.2 MediaProjection）：
open 时即做策略判定（锁屏等直接 Err，不发空会话）；`nextFrame()` 取帧，
`close()` 关闭（会话是连接态，二次关如实报 ERR_NOT_FOUND）。

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `opts` | \{ `height?`: `number`; `timeout?`: `number`; `width?`: `number`; \} |
| `opts.height?` | `number` |
| `opts.timeout?` | `number` |
| `opts.width?` | `number` |

##### Returns

`Promise`\<`ScreenCapturer`\>

***

### sensors

```ts
readonly sensors: {
  isSupported: Promise<boolean>;
  register: Promise<SensorSubscription | null>;
  unregisterAll: Promise<void>;
};
```

Defined in: src/index.ts:57

#### isSupported()

```ts
isSupported(name, opts?): Promise<boolean>;
```

设备是否支持该传感器（空白名 → 宿主 `ERR_INVALID_PARAM`，不是 false）。

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `name` | `string` |
| `opts` | \{ `timeout?`: `number`; \} |
| `opts.timeout?` | `number` |

##### Returns

`Promise`\<`boolean`\>

#### register()

```ts
register(name, opts?): Promise<SensorSubscription | null>;
```

注册监听并返回订阅（`name` 是传感器名，未知名/设备缺席按上方诚实口径抛错）。

##### Parameters

| Parameter | Type | Description |
| ------ | ------ | ------ |
| `name` | `string` | - |
| `opts` | \{ `delay?`: `SensorDelay`; `ignoresUnsupported?`: `boolean`; `timeout?`: `number`; \} | - |
| `opts.delay?` | `SensorDelay` | 采样档位；缺省 `NORMAL`（省电侧默认，不是 FASTEST）；wire 传名字面量。 |
| `opts.ignoresUnsupported?` | `boolean` | v9 兼容折叠：为 true 且宿主报 `ERR_NOT_SUPPORTED` 时回 `null` （其余错误照常抛 —— 不支持是"没这个传感器"，拒收/句柄错是"现场坏了"， 后者吞掉就是谎）。 |
| `opts.timeout?` | `number` | - |

##### Returns

`Promise`\<`SensorSubscription` \| `null`\>

#### unregisterAll()

```ts
unregisterAll(opts?): Promise<void>;
```

注销**全部**订阅。

越界提醒（与 `:domain` KDoc 同步）：宿主侧没有脚本归属，清的就是全清 ——
多脚本并发时误调会掐掉别人的订阅。要精准请 `subscription.unsubscribe()`。

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `opts` | \{ `timeout?`: `number`; \} |
| `opts.timeout?` | `number` |

##### Returns

`Promise`\<`void`\>

***

### settings

```ts
readonly settings: {
  canWrite: Promise<boolean>;
  getInt: Promise<number | null>;
  getString: Promise<string | null>;
  putInt: Promise<void>;
  putString: Promise<void>;
};
```

Defined in: src/index.ts:54

#### canWrite()

```ts
canWrite(opts?): Promise<boolean>;
```

`WRITE_SETTINGS` 是否已授（写前的诚实探针；读设置不需要授权）。

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `opts` | \{ `timeout?`: `number`; \} |
| `opts.timeout?` | `number` |

##### Returns

`Promise`\<`boolean`\>

#### getInt()

```ts
getInt(key, opts?): Promise<number | null>;
```

读整型设置；键缺失 → `null`（0 是合法亮度，不拿 0 冒充）。

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `key` | `string` |
| `opts` | \{ `timeout?`: `number`; \} |
| `opts.timeout?` | `number` |

##### Returns

`Promise`\<`number` \| `null`\>

#### getString()

```ts
getString(key, opts?): Promise<string | null>;
```

读字符串设置；键缺失 → `null`（绝不拿空串冒充缺失）。

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `key` | `string` |
| `opts` | \{ `timeout?`: `number`; \} |
| `opts.timeout?` | `number` |

##### Returns

`Promise`\<`string` \| `null`\>

#### putInt()

```ts
putInt(
   key, 
   value, 
   opts?
): Promise<void>;
```

写整型设置。失败口径同 putString（未授 `ERR_PERMISSION_DENIED`、已授权仍被拒 `ERR_IO`）。

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `key` | `string` |
| `value` | `number` |
| `opts` | \{ `timeout?`: `number`; \} |
| `opts.timeout?` | `number` |

##### Returns

`Promise`\<`void`\>

#### putString()

```ts
putString(
   key, 
   value, 
   opts?
): Promise<void>;
```

写字符串设置（空串是合法值）。未授 `WRITE_SETTINGS` → 抛 `ERR_PERMISSION_DENIED`。

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `key` | `string` |
| `value` | `string` |
| `opts` | \{ `timeout?`: `number`; \} |
| `opts.timeout?` | `number` |

##### Returns

`Promise`\<`void`\>

***

### shell

```ts
readonly shell: {
  exec: Promise<ShellResult>;
  shell: Promise<ShellResult>;
};
```

Defined in: src/index.ts:48

#### exec()

```ts
exec(cmd, opts?): Promise<ShellResult>;
```

执行 shell 命令（root/adb）；分级 DENIED → ERR_PERMISSION_DENIED。

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `cmd` | `string` |
| `opts` | \{ `timeout?`: `number`; \} |
| `opts.timeout?` | `number` |

##### Returns

`Promise`\<`ShellResult`\>

#### shell()

```ts
shell(cmd, opts?): Promise<ShellResult>;
```

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `cmd` | `string` |
| `opts` | \{ `timeout?`: `number`; \} |
| `opts.timeout?` | `number` |

##### Returns

`Promise`\<`ShellResult`\>

***

### workManager

```ts
readonly workManager: {
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

Defined in: src/index.ts:42

#### cancelTask

```ts
cancelTask: (id, opts) => Promise<boolean>;
```

撤销任务（幂等：从未登记的 id 照样 true）。

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `id` | `string` |
| `opts` | \{ `timeout?`: `number`; \} |
| `opts.timeout?` | `number` |

##### Returns

`Promise`\<`boolean`\>

#### createTimedTask

```ts
createTimedTask: (input, opts) => Promise<{
  id: string;
}>;
```

登记定时任务（发桥调用 → Scheduler.schedule，直写注册表）。
非法 cron 表达式桥侧拒收（ERR_INVALID_PARAM）：调用方可先经 [nextFireAfter]
本地预览自查，但真拒绝以桥为准（宿主 `CronTab.parse` 是唯一校验出处）。

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `input` | [`CreateTimedTaskInput`](CreateTimedTaskInput.md) |
| `opts` | \{ `timeout?`: `number`; \} |
| `opts.timeout?` | `number` |

##### Returns

`Promise`\<\{
  `id`: `string`;
\}\>

#### cron

```ts
cron: (expr) => TimedSchedule & {
  kind: "cron";
};
```

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `expr` | `string` |

##### Returns

[`TimedSchedule`](../type-aliases/TimedSchedule.md) & \{
  `kind`: `"cron"`;
\}

#### daily

```ts
daily: (hourOfDay, minuteOfHour) => TimedSchedule & {
  kind: "daily";
};
```

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `hourOfDay` | `number` |
| `minuteOfHour` | `number` |

##### Returns

[`TimedSchedule`](../type-aliases/TimedSchedule.md) & \{
  `kind`: `"daily"`;
\}

#### fromInput

```ts
fromInput: (input) => TimedSchedule;
```

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `input` | [`TimedScheduleInput`](../type-aliases/TimedScheduleInput.md) |

##### Returns

[`TimedSchedule`](../type-aliases/TimedSchedule.md)

#### listTasks

```ts
listTasks: (opts) => Promise<TimedTaskInfo[]>;
```

列举任务（按 id 排序；与 Kotlin list 回显同形状）。

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `opts` | \{ `timeout?`: `number`; \} |
| `opts.timeout?` | `number` |

##### Returns

`Promise`\<[`TimedTaskInfo`](TimedTaskInfo.md)[]\>

#### nextCronFireAfter

```ts
nextCronFireAfter: (expr, nowMillis) => number | null;
```

cron 本地预览（宿主 `CronTab` 的 JS 镜像：字段语义逐条对齐，段内校验从简）。

漂移纪律：本函数只给脚本侧"大概下次何时"的预览；登记与续排的权威是宿主
（`CronTab.parse` 拒非法 → 桥回 ERR_INVALID_PARAM）。两者分歧时以宿主为准，
本镜像的用例只钉"与宿主同值"的几个锚点（每日九点/每周一/不可能日期 null）。

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `expr` | `string` |
| `nowMillis` | `number` |

##### Returns

`number` \| `null`

#### nextFireAfter

```ts
nextFireAfter: (schedule, nowMillis) => number | null;
```

下一次应触发时刻（epoch millis，基于 now 的墙钟）。

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `schedule` | [`TimedSchedule`](../type-aliases/TimedSchedule.md) |
| `nowMillis` | `number` |

##### Returns

`number` \| `null`

#### once

```ts
once: (afterSeconds) => TimedSchedule & {
  kind: "once";
};
```

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `afterSeconds` | `number` |

##### Returns

[`TimedSchedule`](../type-aliases/TimedSchedule.md) & \{
  `kind`: `"once"`;
\}

***

### zip

```ts
readonly zip: {
  compress: Promise<void>;
  extract: Promise<void>;
};
```

Defined in: src/index.ts:53

#### compress()

```ts
compress(
   source, 
   archive, 
   opts?
): Promise<void>;
```

压缩文件或目录 → zip（目录递归；目标已存在则替换）。

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `source` | `string` |
| `archive` | `string` |
| `opts` | \{ `timeout?`: `number`; \} |
| `opts.timeout?` | `number` |

##### Returns

`Promise`\<`void`\>

#### extract()

```ts
extract(
   archive, 
   targetDir, 
   opts?
): Promise<void>;
```

解压 zip → 目标目录（不存在则创建；zip-slip 越界条目在宿主侧整次拒绝）。

##### Parameters

| Parameter | Type |
| ------ | ------ |
| `archive` | `string` |
| `targetDir` | `string` |
| `opts` | \{ `timeout?`: `number`; \} |
| `opts.timeout?` | `number` |

##### Returns

`Promise`\<`void`\>

## Methods

### handleResponse()

```ts
handleResponse(resp): void;
```

Defined in: src/index.ts:63

宿主把 ok/err 响应回投给桥（Kotlin Router → TSF → JS）。配合 install 的第 4 参 [reqId] 使用。

#### Parameters

| Parameter | Type |
| ------ | ------ |
| `resp` | `BridgeResponse` |

#### Returns

`void`

***

### install()

```ts
install(handler): void;
```

Defined in: src/index.ts:61

安装桥宿主（单例；重复安装抛错）。

#### Parameters

| Parameter | Type |
| ------ | ------ |
| `handler` | `InvokeHandler` |

#### Returns

`void`
