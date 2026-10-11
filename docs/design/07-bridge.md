## 7. 桥接层设计（JS ↔ Native ↔ Android）

### 7.1 调用链与分层

```
[JS 侧]
api 包 (Promise/EventEmitter 封装)          ← TS facade，业务语义
   │
RuntimeBridge (单例)                        ← requestId 生成/关联、TTL、错误折叠
   │  调用: bridge.invoke('a11y.find', {...}, {ttl: 200})
   ▼
[N-API addon bridge_native.node]           ← 每 message 一个 job
   dispatcher 单注册表（module → napi_function）
   TSF per context (napi_threadsafe_function, nonblocking)
   ▼  跨线程投递（不持锁）
[JNI glue]  AttachCurrentThread(daemon) → 构造 Java 对象 → 投 :main Router
   ▼
[Kotlin Router]                             ← 在 :main 进程
   ModuleRegistry (name → handler)
   RequestRegistry ((connectionId, requestId) → PendingRequest{ttl, cancel})
   HandleRegistry (generation + tombstone)
   → CapabilityManager / AccessibilityService / …（业务模块注册 handler）
```

### 7.2 同步变体禁令（含批判修正 F1）
- **IPC 路径只有异步 Promise**，语法上不提供同步变体（不提供 `bridge.invokeSync`，避免 API 误用）。
- 仅 JS 进程内的纯内存路径可有同步函数（如 `datastore` 的同步 importer、纯 JS 工具），因为无线程/进程边界，死锁面为零。
- JS 侧所有面向用户的 API 都是 async：`await selector.findOne()`；对教育与新手友好性用明确命名（`findOne()` vs Pro 的同步习惯）并保留迁移垫片——垫片也是 Promise 包同步语义，**不绕过桥**。

### 7.3 TSF 队列拆分（控制面 / 数据面）
批判 3/9 指出的「一个 TSF 混装控制与日志可能丢控制消息」→ **每 context 两个队列**：
- `tsf_control`：`stop/pause/resume/ack/evaluate/checkpoint/错误上报` —— **绝不丢弃、绝不降级**，高优先级。
- `tsf_data`：console、事件流、传感器采样 —— 可丢包（丢包统计/背压，溢出时回调 JS 层 `queueError`）。数据面闲置自动 unref。
两类消息都带 `ctxId + seq` 与 generation 校验。

- **消费侧已落地（2026-09-24，控制台屏）**：Kotlin 侧 `ConsoleCollector`（有界 2000、容量满丢最老并计数、`drain(sinceSeq, max)` seq 游标**非破坏**拉取） → 读口 `HostSummary.console(sinceSeq, maxLines)`（DTO `ConsoleSnapshot` 住 `:domain`，
  拼装 `ConsoleRead` 住 `:app` 壳装配包、纯 JVM 可测）→ `:ui` 第四页签控制台屏。**2026-10-09 批 84 改名**：那个页签**不再是控制台** —— 用户口径「控制台不是放系统日志的地方，是用来执行命令的」，控制台改做命令面（§10.9 第 3 条，读口是 `HostSummary.runNpmCommand`/`consoleOutput`，**与本节这条读口无关**），而这条读口（`HostSummary.console`）的消费方改为**管理面板 → 日志管理**（系统日志/脚本输出/任务日志三段）与任务中心的在途执行块。**数据面一个字没动**：`ConsoleCollector`、游标语义、`stopRun`、`ConsoleSnapshot` 的字段与纪律全同下。呈现纪律：**游标只进不退、行累积**（刷新 = 增量拉取不是重画；并发同游标按 seq 去重）、
  **读失败保留旧行与游标**（瞬时失败抹掉用户已看到的日志比报错更糟；失败只亮原因）、拉满标「可能还有」不假装到底、**丢包非零不藏**（`droppedTotal` 上屏 —
  — 显示的不是全部得说出来）、在途执行两端对照随快照带上（§8.3：宿主读不到如实说读不到、**不渲染成某个状态**；分歧标红，判据仍在 `RuntimeController` 不在呈现层）。
  刷新时机与能力中心/任务中心同构：回前台/切页签现取，页大小 256，读失败不自激。**停止操作面同批落地（2026-09-24）**：读口 `HostSummary.stopRun(runId)`（`:domain`）→ `AssembledShell.stopRun`（壳持有的在途表 `RuntimeController.stop` → 池四步 quiesce，
  `AlreadyGone` 如实 false 不抛）→ `AppShellApplication.stopRun`（壳未装配抛）→ `:ui` **日志管理「脚本输出」段的在途行**每行一个「停止」按钮（批 84 前在控制台）（`ConsoleState.stopError/stopNotice/stopInFlight` 与读账分开记账、
  刷新现取归零；回执措辞：true = 已请求停止、false = 已不在途；挂起中按钮禁用）。与 `Scheduler.stopLastRun` 的分工：那是调度单槽快捷口（恢复重投会覆盖），
  本口按 runId 精确命中在途表、不受覆盖影响。
### 7.4 数据与对象生命周期
- **payload 编码**：Kotlin DTO ↔ JSON（结构化小对象）；大二进制（Bitmap/像素）**不走 JSON**：直接 `ByteBuffer.allocateDirect` → `napi_create_external_arraybuffer` + `napi_adjust_external_memory`（0 拷贝，一次性 buf 生命周期绑定）。
- **句柄（Handle）机制**：跨进程资源（UiObject/Image/MediaPlayer/Dialog）在 JS 侧是 `{gen, id}` 代理对象：
  - 内核持有 `HandleRegistry`：`id → NativeResource{ref, generation, tombstone}`。
  - 显式 `dispose()` + `FinalizationRegistry` 兜底；GC 时向内核发 `release(id, gen)`。
  - 任何操作若 `gen` 不匹配 → `ERR_STALE_HANDLE`（防「旧引用操纵新资源」竞态）。
  - 资源 `dispose` 后 tombstone 立即清除内核引用；销毁由内核单线程执行，杜绝并发 Dispose。
- **引用计数对象**（Image/MediaPlayer）用弱引用 + finalize 兜底；`Image.recycle()` 比 GC 优先。
- **帧的所有权：发号侧归一（§18 第 8 项 (b)，2026-09-26 落地）**：图像帧的在场性/发号/像素
  **只由 `:domain` `ImageAnalyzer` 一家持有**（`decode` 与 `ingest` 同一个 `nextRefId`）。
  `screen` 与 `images` 是**两个释放入口、一张表**：`frame.recycle()` 按来源各打各的
  namespace，落进去是同一个"已释放"事实 —— 放过的帧任一侧再用都是 `ERR_STALE_HANDLE`。
  handler 层不再自持第二张在场面表（曾是"两个计数器各自从 1 起"的隐式对齐，属漂移面）；
  `analyzer == null`（so 缺位）时 `ScreenshotSource` 才退回本地表，而此时 `images`
  命名空间未注册，两个号段结构上不可能相撞。

### 7.5 进程间传输
P0 起用 **unix domain socket**（同应用可持久连接、双向流、背压可控），JSON-RPC over newline-delimited frames；抽象为一个 `Transport` 接口，可替换成 binder（只改 `:bridge:java` 内的 Transport impl，不影响上层）。**不引入自制 RPC 编解码 excess**——送 pubsub/流用独立 channel。

**连接身份与控制帧（A5 / A10①）**：

- `NodeProcessEngine` 在 spawn 前经 `:domain` `RunIdentityIssuer` 签发本次执行独占的 lease；
  `RunIdentityRegistry` 以 `SecureRandom` 生成 256-bit 随机凭据（64 位小写 hex），仅驻内存，
  经 `AUTOSCRIPT_BRIDGE_TOKEN` 传给子进程，不落盘、不写日志、不复用 `runNonce`。
- 双侧 UID 门禁保持 fail-closed；首帧必须为 `{"t":"hello","v":1,"token":"<凭据>"}\n`，
  成功回固定金样 `{"t":"helloAck","v":1}\n`，拒绝可回 `{"t":"helloErr","v":1,"code":"ERR_PERMISSION_DENIED"}\n` 后关闭。
  超限、超时、已撤销等路径可以直接关闭，不承诺一定能送达错误帧。控制帧不占 requestId，不入业务请求表。
- 一票一连接，原子消费；hello 先于 spawn 返回时等待 `confirmSpawn(pid, isAlive)`。
  确认进程存活，且双方 PID 都可得时必须匹配；PID 为 null 保留 noPid 路径，以凭据建立归属。
  未知、重放、过期、已结束或撤销的凭据拒收。首帧最多 1 KiB、默认握手期限 10 秒，
  未消费/未确认票据也有 10 秒建连期限；期限不延续到已认证长连接。
- `main.cpp` 在进入 Node、发布 fd、启动 addon reader **之前**独自完成 hello/ACK；成功后清 token env，
  addon 只接已认证 fd。`SocketBootstrap` 使用同一读者完成握手和业务收包，只有 READY 可以 install/invoke，
  并发 connect 共享 Promise；错误、关闭、认证拒绝、超时和主动 close 都结算等待者。
  旧在线客户端没有 hello 明确拒绝，不提供匿名归零回退，不自动重连；重跑签发新票。

**业务作用域与生命周期**：

- 认证结果建立不可变 `AuthenticatedRunContext(engineId, engineRunId, connectionId)`；它由宿主建立，
  不从 payload/side 解码。`BridgeRequest` 业务信封不加可自报的可信 runId，方法表也不变。
- handler 表共享；请求键为 `(connectionId, clientRequestId)`，回复仍带客户端原 id（含负数心跳）。
  双连接同 id 合法，同连接同时重复 id 拒绝；完成、TTL、取消和关闭按独占 ticket 核销，旧结果不能删新请求。
  每连接独立协程域与 `InputChannelSession`；TTL/撤销取消 handler，全局在途配额保持 256。
- 自然退出不立即抹掉已绑定归属：连接进入 DRAINING；首次自然退出通知或 EOF 建立一次 5 秒排空窗口（响应写失败也启动同一窗口），
  重复 status 不续期。读尾帧至 EOF，再等已接收请求结束；对端不再接收 ACK 也不直接丢弃其尾帧。
  窗口到期或硬 stop/kill/撤销时显式关闭底层 IO 并取消在途，不靠协程取消唤醒阻塞 read/write。
  同槽重跑不改变旧连接的 runId，旧 lease/ticket 清理不影响新执行。
- 壳关闭或 listener 换壳同时关闭 pre-auth 与已认证连接；同壳重复 start 幂等。
  `AppShell.close` 不等于停全部执行；进程级停机仍先停调度，再停执行。

**日志归属与边界**：`ConsoleCollector.handle` 必须从认证上下文取 `engineRunId`，缺身份回
`ERR_PERMISSION_DENIED`，绝不回退 0；宿主 `HostLog` 直写仍用 0。系统日志仅显示宿主行，
脚本行在**日志管理的「脚本输出」段**按 `[#runId]` 显示（2026-10-09 批 84 前那是控制台页）；日志归属是 `EngineRunReceipt.runId`，不是 intentRunId/池槽/runNonce。
队列仍有界、TSF 仍可丢包、强制退出仍可损失尾行，不新增持久化或完整 logcat。
`consoleSink` 捕获桥错误后会通过 `onQueueError` 报告并正常兑现 Promise，故 **await 完成不证明送达**；
验收须核对原始 RPC 成功 ACK 与宿主实际入队行，入队亦不代表永久保存。
身份认证不等于 CapabilityMask/跨脚本授权，更不是恶意同 UID 代码隔离，边界见 §11。

### 7.6 错误模型
```ts
class AutojsError extends Error {
  code: AutojsErrCode            // 机器可判
  module: string                 // 'a11y' | 'capability' | 'engine' | ...
  method: string
  javaClass?: string             // 源 Java 异常类（E.g. SecurityException）
  javaStack?: string
}
```
错误目录（前 20 个中最关键）：`ERR_TIMEOUT`、`ERR_STALE_HANDLE`、`ERR_PERMISSION_DENIED`（能力未授权/被降级）、`ERR_SERVICE_DISABLED`、`ERR_SCREEN_LOCKED`、`ERR_BLACK_FRAME`（FLAG_SECURE）、
`ERR_CAPTURE_DENIED`、`ERR_ENGINE_STOPPED`、`ERR_ENGINE_CRASHED`（进程死）、`ERR_NOT_IMPLEMENTED`（本平台不支持，如 child_process）、`ERR_INVALID_PARAM`、`ERR_FILE_NOT_FOUND`、`ERR_FILE_EXISTS`（打包产物已存在等）、
`ERR_DISK_FULL`、`ERR_NOT_FOUND`（UiSelector 未找到 → 可选 `NotFoundError` 对齐 Pro v9）。
映射规则：`Java Exception → 分类 → AutojsError`，保留 `javaStack`，JS `instanceof` 可判。
目录三处落字（`:domain` `core/Error.kt` 的 `ErrorCode`、`bridge/js/src/errors.ts` 的 `ErrCode` + `ERROR_CODES`、本文提及）由 `bridge/js/test/err-catalog.test.cjs` **三面对账**（Kotlin ⇄ JS 双向相等、
同文件枚举 ⇄ 字面量表双向相等、文档提及必须两处都在；随 `npm test` 进 CI）——2026-09-26 首跑就抓到真漂移：`ERR_IO` 在宿主全线服役（zip/settings/images/
spawn/打包），JS 目录独缺，脚本 `ERROR_CODES.includes('ERR_IO')` 为 false；已补码并被该门的「回潮」断言钉死。

### 7.7 性能关键路径（数量级目标）
| 链路 | 目标 | 设计 |
|---|---|---|
| 空 RPC（JS→:main→回） | p95 < 2ms | 直连 unix socket、零 JSON 二次解析、TSF 双队列 |
| 无障碍 `find → click` | 200ms 内 p60 / ~10ms 树读 | 紧凑索引树 + 按需属性 + 句柄（不全量序列化） |
| `captureScreen → findImage` | < 1s 且一次截图两次匹配 < 700ms | 屏幕帧→native 0 拷贝，模板匹配在 `libopencv.so`；**链路已通**（§18 第 8 项 (b) 2026-09-26 落地：截屏帧经 `ImageAnalyzer.ingest` 进 `images` 同一张帧表，"帧不通用"取消）—— 数字仍是**待实测**的验收口径（真机未量） |
| `findColor`（单人独立子图 1080p） | < 10ms | native 遍历（`cv::inRange` 逐分量包含 + **行主序首命中早退**（2026-10-01：原 `findNonZero` 会把整张命中点表物化出来再取第一个，全帧大命中面时 1.99M 点 ≈16MB —— **取首点那一段** 19.4~22.1ms；改成按行主序自己扫、第一个非零即返回，同一段 0.003~0.016ms。**整调用另有地板**：全帧 `cv::inRange` 本身 ≈4.3ms，整算子全帧 ≈4.33ms（判据口径是「单人独立子图」300×150 ROI，2026-09-30 真机 0.88ms ✅；全帧在口径外）。答案仍是「行主序第一个命中」，与 `findNonZero` 同解）；ROI 是浅视图不拷像素；kleidicv 覆盖 `inRange` 面） |
| `matchTemplate` 1080p | < 40ms（**小模板 48×48 全帧形态 <100ms**，2026-10-02 拍板 `design-decisions.md` 第 25 项） | OpenCV TM_CCOEFF_NORMED（实测它、不是早前写的 CCORR：见下注）；2026-09-30 起**金字塔粗筛 + 原像素精配**（灰度缩小图只提名候选、坐标与置信度回原 4 通道小窗重算，语义一字不动）+ 两匹配方法可选 `region` 缩窗（机制与错误码见下注、方案裁决见 `design-decisions.md` 第 13 项）——**✅ 2026-09-30 三次实测：370×80 形态全帧 24.47ms 达标转绿（形态注明），48×48 形态见三次实测注**；**2026-10-02 E2 拆 std/相位双门 + 负结果精确兜底**（第七次实测）——48×48 形态 host 33ms / **真机 64.43ms**（同会话同 so 强制精确 882.25ms，13.7×，原恒精确 855~948ms）—— 原判据下差 1.6×，**同日拍板（第 25 项）放宽该形态判据 <100ms → ✅ 转绿（形态注明）**；常规形态 <40ms 绿字仍由 370×80 真机 19.67ms 支撑 |
| 紧凑树构建/传输 | < 15ms / 数十 KB | 预聚合属性，代价解析放"取用即取" |

> **2026-09-30 真机实测回填（A2–A4；云手机 Android 13 / arm64 + OpenCV 4.14 `libopencv.so`
> dlopen 直连——无桥/JNI 开销，已是最好情况；输入 = 1080×2400 真机截图，每项 ×100 取中位）**：
> - **`findColor`（单人独立子图 300×150 ROI）：median 0.88ms ✅**（判据 <10ms，余量 11×）。
>   全帧扫描 61.4ms 是**口径外**参考值（判据写明「单人独立子图」；host x86 同款全帧 30.2ms / ROI 0.19ms）。
> - **`matchTemplate`：未达标 ❌** —— 370×80 UI 切片 median **933.6ms**、48×48 小模板 median
>   **862.4ms**（判据 <40ms，超 21–23×）。两尺寸**同量级**说明耗时由 `cv::matchTemplate` 的
>   图像频谱/DFT 固定开销主导、与模板尺寸弱相关；**host x86_64 OpenCV 4.10 同输入 565.8 / 614.1ms**
>   —— 不是设备慢，是「每次调用全图重算频谱」的量级本身：判据 <40ms 对**裸 `cv::matchTemplate`
>   全图搜索**在两个平台都不可达（该判据自记入起从未真机量过，2026-09-30 首次实测即此结果）。
> - **`captureScreen → findImage`：计算段未达标 ❌** —— decode 46.2ms + match×2 1827ms =
>   median **1912.8ms**，已超「一次截图两次匹配 < 700ms」（2.7×）与「端到端 < 1s」两条判据；
>   截屏段未测（无生产 APK / a11y 服务在跑），但计算段既已超，端到端必超。
> - **出路（未拍板，列选项不改判据）**：① 帧内缓存图像频谱 —— 同帧 match×2 第二次免 DFT，
>   正中 A2 形态，实现落点在 `imgnative.cpp` 帧表侧；② 预筛收缩搜索窗（findColor/金字塔粗定位
>   后再小窗精确 match）；③ 判据改口径 —— 把 <40ms 定义到「预筛后小窗」而非全图搜索（拍板项）。
>   数字出处、方法与 driver 缺陷修正见 [`design-status.md`](../design-status.md) 流水 2026-09-30
>   「A2–A4 真机性能实测」条（2026-10-02 起流水按日期切片，该条在 [`log/2026-09-30.md`](../log/2026-09-30.md)）。
>
> **2026-09-30 出路落定（只追加，上列选项原文不动；裁决全文见 `design-decisions.md` 第 13 项）**：
>   ① 帧内缓存频谱**被评审否掉** —— bench 的 A2 是同帧**同**模板 match×2 看着免费，
>   真实脚本多半是同帧**不同**模板（零收益），且 `cv::matchTemplate` 的图像频谱不暴露、
>   不可跨调用复用（要真缓存得手写 DFT 相关 + 积分图，数值风险另算）；
>   ② 已落地（**金字塔变体**）：灰度 0.25×/0.5× 上只提名 ≤K 候选（thr−margin 带宽 +
>   NMS），坐标/置信度全部回**原 4 通道**小窗重算 —— 阈值与置信度语义一字不动；
>   差分双跑门（`host_match_test`）每 case 强制精确 vs 金字塔对拍（同位置 + 置信度
>   ≤2e-3 或同未命中），`AUTOSCRIPT_MATCH_FORCE_EXACT=1` 是现场回退阀。护栏一枚：
>   **频率门** —— 模板「缩小→放大」自检互相关 <0.8 即落精确路径（i.i.d. 噪声在
>   0.25× 混叠后粗峰值欠估 → 假 miss，差分门首跑抓到的真红，正是它修的）。
>   **region 契约（两匹配方法，2026-09-30 新增）**：缺省 null = 全帧；给了 =
>   `[x,y,w,h]` 复用 `resolve_region` 判据 —— 越界 → `ERR_INVALID_PARAM`（不静默
>   裁剪）、**region 比模板小 → `ERR_IO`**（与「模板比画面大」同属参数关系不成立）；
>   命中坐标恒**全帧口径**（区域只是搜索范围不是坐标系，与 findColor 同一条）。
>   小模板（短边 <80px 进不了粗筛）的出路就是缩窗。③ 判据 <40ms **不改** ——
>   等 region 实测数字回来再谈口径（拍板动作，不在实现里偷改）。
>   计算段锁口径同步收窄：`g_mu` 只盖**帧表段**（查找/发号/擦除），match 计算出锁
>   （帧入表后不可变、浅拷贝即安全）—— 见本卷 §7.4 与 `imgnative.cpp` 注释。
> - **2026-09-30 同日第二次实测（优化后复测；image-native run 36718494804 产物
>   `libopencv.so` sha256=0f23441…34246，推云手机 dlopen 直连，×100 中位；同机同
>   `scr.raw`，与上列首测唯一差 = 金字塔粗筛 + region + 计算出锁已入 so）**：
>   run1 = 默认（金字塔），run2 = `AUTOSCRIPT_MATCH_FORCE_EXACT=1`（回退阀 A/B）：
>
>   | 项 | 首测 | run1 金字塔 | run2 强制精确 | 判据 | 状态 |
>   |---|---|---|---|---|---|
>   | A3 findColor ROI 300×150 | 0.878ms | 0.870ms | 0.906ms | <10ms | ✅ 不变 |
>   | A4 match 370×80 全帧 | 933.6ms | **62.98ms**（14.2×） | 892.3ms | <40ms | ❌ 差 1.6× |
>   | A4 match 48×48 全帧 | 862.4ms | 855.5ms | 866.2ms | <40ms | ❌ 预期（短边<80 走精确） |
>   | A4-region 48×48 @300×150 | （=全帧 862） | **16.57ms** | 16.60ms | <40ms | ✅ 新行 |
>   | A4-region 370×80 @540×190 | — | **11.77ms** | 25.25ms | <40ms | ✅ 新行 |
>   | A2 decode+match×2 | 1912.8ms | **171.75ms**（10.8×） | 1851.3ms | match×2 <700ms（段内≈125ms） | ✅ 转绿 |
>
>   - **A2 判据转绿**；A3 稳住；**A4 全帧仍 ❌（63ms，1.6×）** —— 模板短边 80px
>     只撑得起 0.5× 粗筛，粗筛层本身还要吃一帧全图 cvtColor+resize，这是该路径的
>     地板；**带 region 后 11.77ms（余量 3.4×）/ 16.57ms** —— 「有 region 则达标」。
>   - A/B 归因干净：run2 的 892.3ms ≈ 首测 933.6ms（回退阀等价于旧行为 ✓），
>     run1/run2 之差即金字塔净贡献；48×48 两 run 同为 ~860ms = 频率/尺寸门把它
>     挡在精确路径的直接证据；A4-region 48×48 两 run 同为 ~16.6ms（该尺寸恒精确
>     路径，region 收益与金字塔无关）。
>   - 48×48 命中报 (57,751) 而非模板原位 (60,1295)、conf=1.0 且**两 run 同位**：
>     屏上存在像素级重复的另一行 UI，argmax 挑了内容相同的一份 —— 不是噪声漂移
>     （差分门的「重复副本」口径，host `host_match_test` case7 钉的正是这类）。
>   - ~~**判据 <40ms 仍不改**（首测块的「出路 ③」保持待拍板）：region 数字现在有了 ——
>     全帧 63ms ❌ vs region 11.8ms ✅，改口径与否是拍板动作，等裁决。~~
>     **已裁决（同日三次实测后，见下块）：370×80 形态转绿（形态注明），判据原文不改。**
>
> **第三次～第六次实测（2026-09-30 至 2026-10-01，共四轮调优）2026-10-06 已整段搬到**
> [`docs/implementation-notes.md`](../implementation-notes.md) 的「§7.7 实测记账」节 —— 契约卷留**结论**，
> 逐轮的数字与探针记录留在注记里。结论见上表判据行与本文件 §7.7 的第七次实测块。
>
> **2026-10-02 第七次实测（E2 裁决落地：拆 std/相位双门 + 负结果精确兜底；backlog E2
> 裁「修」，口径见 `design-decisions.md` 第 24 项；host x86_64 直链同一份 OpenCV 4.14.0
> pin，`/tmp/ffix/featreal_scr.png` 1080×2400 真机截图，thr=0.9，九个 host 语义
> 二进制同门）**：
> 背景先纠前提：E2 起草引的「A4 933.6ms、A2 计算段 1912.8ms ❌」是 2026-09-30 首测数
> —— 2026-10-01 的相位平均 + FastPath 12a + 场景缓存（五次实测）已把 A4 370×80 打到
> **19.82ms ✅**、A2 **88.86ms ✅**，**唯一残余 ❌ = 48×48 全帧形态**（真机模板 std
> 6.29<12 被 std 门拦 + `phase_worst` 0.6742<带宽 0.75 被相位门拦 → 恒精确路径
> 855~948ms，判据 <40ms）。E2 只剩这一个形态要裁，裁决 = **修**：
>
>   **拆两门、留一门、加兜底**：std 门（`kMinTemplStd=12`）与相位门（`phase_worst ≥
>   floor`）移除，尺寸门（`min_templ_side=48` / `kMinCoarseSide=12`）保留；
>   `match_pyramid` 负结果由「直接未命中」改为**回全图精确路径**
>   （`best.conf >= thr ? best : match_exact(...)`）。相位探针 `phase_probe` /
>   `NeedlePrep.phase_worst` 随门退役（只服务那道门）；`build_phase_avg` 相位平均
>   粗模板**保留** —— 它治的是粗筛召回（六次实测的大模板假 miss），不是门。
>
>   | 项 | host 强制精确 | host 金字塔（新） | 判据 | 备注 |
>   |---|---|---|---|---|
>   | 48×48 全帧（真机截图+真机图标） | 532ms | **33.0ms**（16×） | <40ms | 原被双门拦死的形态 |
>   | 370×80 全帧（真机截图裁片） | 508ms | **32.9ms** | <40ms | 回归对照，门拆除前后同路径 |
>   | 噪声负例 128×96 @640×480（图上没有） | 45.3ms | **45.8ms**（+1%） | — | 兜底代价 = 粗筛本身 |
>
>   - **答案面**：370×80 与噪声负例两路径逐字段同解；48×48 两路径均命中
>     conf=1.0000，报位 (48,751) 精确 vs (54,751) 金字塔 —— 与三次实测 (57,751)
>     同源：屏上存在像素级重复副本，argmax 挑哪份由路径决定（argmax 口径，host
>     case7 等价集钉的正是这类）。
>   - **门禁诊断勘误（只追加，三次实测块原文不动）**：「硬放进粗筛 → 零候选 → 必
>     假漏检」的机制描述**不准** —— host 复量该 48×48 模板 0.25× 粗筛有 **1688 个
>     位置 ≥ 带宽**（相位平均模板 1509 个，真位置粗分 0.9278），不是零候选；真机制
>     是 K=32 提名名额被假峰挤兑（真值排第 327）。结论「双拦下硬放粗筛会假 miss」
>     不变，机制从「候选空集」修正为「名额挤兑」—— 这也是「调门」救不了它的原因
>     （0.5× 档过相位门但 std 门仍拦；`kMinCoarseSide` 12→13 不改变任何一档）。
>   - **精度面（口径更新，取代三次实测「四层压住」的现行表述）**：假漏检从「语料
>     统计能不能抓住」变成**结构上不可能**（负结果必回精确路径）。现四层 = ①尺寸门
>     （<48px 不进粗筛）②负结果精确兜底 ③host 差分双跑 + 全量语义门 **422 检查**
>     （九个二进制合计；match 家族 124 含逐字段对拍与等价集，case6c 高频反例的
>     守护对象从「频率门」换成「兜底」，断言一字未改）④`FORCE_EXACT=1` 回退阀。
>   - **为什么拆门而不是调门（裁决依据）**：两门的唯一职责是防假漏检，挡错时的
>     代价是「白付一次全图 matchTemplate（473~1500ms）还没解决问题」；兜底把风险面
>     反过来 —— 挡错封顶为「多付一次粗筛」（实测 +1%），挡对省的那一次粗筛
>     （≈0.1× 全图成本）买不起原赔率。
>   - **host 门全绿**：`run-host-tests.sh` 九个二进制 **422 检查 0 失败**（同命令、
>     同 OpenCV pin）。
>   - **真机复测（2026-10-02 同日补跑；云手机 frp-box，同机同 `scr.raw` 同 bench ×100
>     中位，so = 本 PR 的 image-native CI 产物 sha256 `72728f52…`，A/B = 同一 so 的
>     `AUTOSCRIPT_MATCH_FORCE_EXACT` 开关，同会话无基线漂移）**：
>
>     | 项 | 默认（E2 金字塔 + 兜底） | 同 so 强制精确 | 判据 |
>     |---|---|---|---|
>     | A4 match 48×48 全帧 | **64.43ms** | 882.25ms | ~~<40ms **❌ 差 1.6×**~~ → **<100ms ✅**（同日拍板第 25 项放宽，原数字保留） |
>     | A4 match 370×80 全帧 | **19.67ms** | 931.31ms | <40ms ✅（与五次 19.82 同值，无回归） |
>     | A4-region 48×48 @300×150 | 17.00ms | 17.03ms | <40ms ✅ |
>     | A4-region 370×80 @540×190 | 10.56ms | 25.89ms | <40ms ✅ |
>     | A2 decode+match×2 | **88.20ms** | 1908.33ms | <700ms ✅（无回归） |
>     | A3 findColor ROI 300×150 | 0.085ms | 0.088ms | <10ms ✅ |
>
>     - **48×48 形态：855~948ms → 64.43ms（13.7×），金字塔确认进场**（强制精确同轮
>       882.25ms 与历史 857~948ms 同量级 → 基线无漂移）—— 但**仍差 1.6× 未过判据**，
>       判据行该形态如实继续挂 ❌，改善记档不冒充达标。差额在小模板的候选面：
>       win_area 小 → 自适应 K 顶到预算公式上限 32，NMS 在 270×600 结果面扫 32 轮 +
>       32 个精配窗；host 33ms 与真机 64ms 的平台差与历史形态一致。残余曾立项 backlog
>       **E5** —— ~~同判据继续挂 ❌~~ **同日拍板关闭（第 25 项）**：降低要求，该形态
>       判据放宽 <100ms（64.43ms 转绿·形态注明）、**不投优化**；常规形态 <40ms 不动。
>     - **报位**：默认 (48,1023) vs 强制精确 (57,751)，conf 均 1.0000；region 内两路径
>       同报 (48,1295) —— 屏上多行像素级重复副本（y=751/1023/1295），argmax 口径，
>       与三次实测 (57,751) 同源。
>     - **host 数字的定位不变**：33ms 只证明机制与量级，判据状态以本表真机数为准
>       （本机绿不是门禁有效，2026-09-30 实测纪律）。
>
> **`TM_CCOEFF_NORMED` 而不是 `TM_CCORR_NORMED`（2026-09-25 实测改口径，非抄来的）**：
> `imgnative_match` 一直用 CCOEFF，早前本表与 `:domain` KDoc 两处写成 CCORR —— 名字漂移
> 谁都没炸，因为真实纹理模板下两者都能拿 1.0。差别在**画面里没有模板**时（host 静态库实测）：
> CCORR_NORMED 的 max 仍有 **+0.955**（阈值 0.9 直接误判命中），CCOEFF_NORMED 的 max 只有
> **+0.599**（正确判未命中）。相关系数自带亮度归一，对抗画面里的均匀亮块伪阳性；
> 这也是"两个匹配方法同一个阈值键"敢统一到 `[0,1]` 的前提。
> 代价钉在这儿：CCOEFF 对**方差≈0 的模板**（纯色块）会给出恒 1.0 的结果面，实测 14651/14651
> 个位置全满分 —— 那类请求的命中坐标稳定但不唯一，脚本要按 `threshold` 高就把结果当"就是这块"
> 会踩空。这是 opencv 口径，不是我们能修的，先在契约里写明。

### 7.8 :bridge:native 落地契约（v24.21.0 + NDK r28c 实证，CI 构建前置）

> 本节是 `:bridge:native`（N-API addon 控制面）与 `:engine:node-process`
> （:nodeN 宿主）的**契约先行**文档：C++/CMake 落地前先把符号面、线程铁律、
> 传输对接钉死。符号面全部经 `llvm-nm -D /tmp/nrb-out7/libnode.so` 实证，
> 非臆造。addon 控制面已落 `bridge/native/src/main/cpp/bridge_addon.cc`——含 §7.8
> 点名的**起线程/TSF 接线**（`setSocketFd(fd>=0)` 首次注入拉起读线程；`setup(onFrame)`
> 建 data 面 TSF；环境 cleanup hook 换代释 TSF）；宿主已落
> `engine/node-process/src/main/cpp/main.cpp`（下述启动序 ①②③）。两者均经本机 NDK
> r28c 交叉编译验证（`engine/node-process/scripts/build-native.sh`：AArch64 ELF、
> `napi_register_module_v1` 导出、`node::Start` 声明↔dlsym 字面量↔libnode 导出三方
> 对表、LOAD≥16KB）。**Kotlin spawn 半边已落地并本机验证**：`NodeProcessEngine`
> （ProcessLauncher 缝 + env 合同 + pid 回执/状态语义，`:engine:node-process` 16 个
> 单测含真 node spawn）；`:app` 垂直切片 E2E 全链（spawn → unix socket 桥 →
> console/心跳上送 → `SUCCEEDED` 归档 + 双 id 关联）；spawn env 合同五键（下①与
> `NodeProcessEngine` companion、main.cpp 头注释三处同名）——`AUTOSCRIPT_LIBNODE`
> 必填 / `AUTOSCRIPT_BRIDGE_ADDON` 选填 / `AUTOSCRIPT_HOST_SOCKET` 选填（null=离线）/
> `AUTOSCRIPT_RUN_ID` 恒注入（kBootstrap 据此 500ms 自动心跳，reqId 走 `-seq` 负数
> 命名空间不撞 JS 正数 inflight）/ `AUTOSCRIPT_RUN_NONCE`（§8.5 幂等键，透传不消费）。
> addon `invoke` 的帧 payload 按信封契约**字符串化转义**（信封里是 JSON 字符串，与
> JS `JSON.stringify` 同形；金样钉 `JsonTransportTest`「addon 心跳帧金样」，host 编译
> 烟测逐字比对；裸嵌对象会被宿主扁平解码整帧拒掉）。**生产桥监听已落**（§7.5）：
> `BridgeSocketListener`（shell 装配包）= `LocalServerSocket(String)` abstract 绑定
> （名按 uid 隔离，打包多实例不撞）+ accept 循环 + 对端 uid 门禁（fail-closed，与
> main.cpp 客户端侧 `SO_PEERCRED` 对称）+ 每连接交 `NewlineFrameServer`；bind/门禁/
> serve/关断全走缝（`BoundBridgeSocket`/`BridgeSocketBinder`），JVM 假缝单测覆盖，
> `AppShellApplication` bind 赶在 assemble 前（`FixedEnginePool.init` eager）注入
> `hostSocketName`、绑定失败 = 离线降级不注入（不触发 exit 3）。**addon 的 JS 消费面
> 已落**（§12.4 接入面 1）：facade `attachNative()` / `NativeBootstrap` ——
> `setup(onFrame)` 按在途 id 结算（负 id/未知 id 查不到即丢，kBootstrap `-seq` 心跳
> 合同）、`addon.invoke` 直接作 `InvokeHandler` 注入、NAPI 抛错经 `errFromThrown`
> 保留 `ERR_*` 真码（断链不折成参数错）；**不碰 `setSocketFd`**（fd 注入归宿主）。
> mock 单测 6 例 + `AUTOSCRIPT_TEST_ADDON` 门禁的真 addon 全环（setup 前
> `droppedData` 前账 → attach 结算 → 心跳负 id 不撞在途）。
>
> **facade dist 随包 + 打包入口 `attachNative` 接线已落（2026-09-24，资产交付轨）**：
> `prepareBridgeDistAssets`（`:app` 构建任务）把 **npm build 产物**（步骤 7 出库，CI jvm-tests 前置构建）`bridge/js/dist`
> 拷成 `assets/bridge-dist/`（srcDir 取**父目录** —— 资产键 = `bridge-dist/<file>`，
> 指成子目录会拍平到 assets 根、`list("bridge-dist")` 恒空且**没有报错**）→
> `AppShellApplication` 全量读成扁平 map（枚举/任一读失败 = **整体空**：宁可这次不落，
> 不可半量落 —— 半量 + 孤儿清理会把"读失败那个文件"当旧版删掉）→
> `BridgeDistDeploy`（`:app-service:script-repo`）落位 `ScriptPaths.autoModuleRoot`
> = `filesDir/node_modules/auto`（Node 解析走位第 3 站：脚本目录 → `files/scripts/<id>/
> node_modules` → `files/scripts/node_modules` → `files/node_modules`；**无 package.json
> 走 index.js 缺省**）—— 覆盖语义与脚本补部署**相反**：应用自有资产，**字节即版本**
> （异则原位替换 + 全量成功后清孤儿；任何失败不删孤儿，宁可留旧不可丢件）→
> `NodeProcessEngine` 按 `bootstrap.js` 在位注入 `AUTOSCRIPT_BRIDGE_DIST`（与 addon
> 同一条选填纪律：配置了没落位 = 不注入）→ main.cpp kBootstrap
> `require($DIST/bootstrap.js).attachNative({addon: a})`（复用引导已 require 的同一
> addon 实例；dist 缺/require 抛错 = stderr 点名、**脚本照跑** —— 选填件不杀执行；
> addon 在而 dist env 不在时 main.cpp 额外打一行"facade 未接入，require('auto') 将失败"）。
> 全链验证 = `BridgeDistPackagingEntryTest`（kBootstrap 从 main.cpp **现抽** —— 单一
> 事实源不复制引导串；真 dist 落位 + 系统 node 起进程：`require('auto')` 解析、
> `installed`、addon `setup` 三关，外加 env 缺席/坏 bootstrap 两条诚实分支）+
> `BridgeDistDeployTest`（7 项）+ `AppShellBridgeDistTest` + 引擎 env 契约用例。
>
> **jniLibs 二进制交付 + `libc++_shared.so` 也已落（2026-09-24，同轨 §19）**：
> `:app` 的 `prepareEngineNativeLibs` 把本机构建三件（`build-native.sh` 的 `noden`→
> `libnoden.so`、`LIBNODE`/`node-runtime-build/out` 的 `libnode.so`、NDK sysroot 的
> `libc++_shared.so` —— readelf 实证它是 libnode 的 NEEDED）拷进
> `generated/engineNativeLibs/arm64-v8a/`（jniLibs.srcDir）；**三件齐才落包、半套红、
> 全无警告**（来源不在 git，与 bridge/js/dist 的"缺=仓库破损"不同判据）。
> `useLegacyPackaging = true`（merged manifest 实测 `extractNativeLibs="true"`）——
> 默认不提取时 `nativeLibraryDir` 是空的，exec/预检都落空。addon 独立走
> `assets/bridge-addon/bridge_native.node`（PM 只提取 `*.so`，`.node` 进不了
> jniLibs；而 main.cpp 的 `require(env)` 只认 `.node` 扩展）→
> `BridgeAddonDeploy` 落位 `ScriptPaths.bridgeAddonFile` = `filesDir/lib/
> bridge_native.node`（单文件、字节即版本、不 claim 目录故无孤儿清理）→
> `addonPath` 注入，引擎按文件在位降级（缺 = 不注入，脚本照跑）。
> APK 实测三条 `lib/arm64-v8a/*` + `assets/bridge-addon/*` 齐在（debug ~40MB，
> 含未 strip 的 libnode）。**真机台账（2026-09-29，非 root shell，Android 13/arm64）**：
> exec + `dlopen` + `node::Start` 通（node v24.21.0，冷启 158ms），abstract socket +
> `SO_PEERCRED` 通，facade→addon→桥→宿主全链 6 帧往返（生产布局 `lib/arm64-v8a/` +
> 无 `LD_LIBRARY_PATH`）；失败形态按设计：addon 缺位 = 降级照跑，socket 给错 = exit 3。
> **未覆盖**：非 root 的 SELinux enforcing 上下文、`nativeLibraryDir` 提取路径、
> targetSdk 36 的 app 数据区 exec 策略 —— 仍需真机。**16KB 页机不在其列**（2026-10-06
> 拍板不做真机复验，装载风险由 §16 的构建期门禁承接，见 `design-decisions.md` 第 34 项）。
> `.so` strip 归 CI 打包管线。

**符号面（动态 T，稳定 ABI）：**

| 符号 | 来源 | 用途 |
|---|---|---|
| `_ZN4node5StartEiPPc`（`node::Start(int, char**)`） | libnode.so | :nodeN 单进程单 isolate 入口（§5.1 一进程一 Start） |
| `_ZN4node4StopEPNS_11EnvironmentENS_9StopFlags5Flags` | libnode.so | quiesce 第④步后收尾（§5 推论 A：kill 必须归还槽位，Stop 即"正常死"的路径） |
| `napi_create_threadsafe_function` / `napi_call_threadsafe_function` | libnode.so | TSF 双队列的创建/投递（§7.3，见下） |
| `napi_module_register` / `napi_module_register_by_symbol` | libnode.so | addon 模块注册（`bridge_native.node` 即一个 N-API 模块，见 `engine/node-process/scripts/build-native.sh`） |
| 20562 个动态 T 符号（含 `napi_create_external_arraybuffer` 系） | libnode.so | §7.4 大二进制 0 拷贝（`allocateDirect` → external arraybuffer）的符号依据 |

`NAPI_VERSION=10`（§3 技术选型表冻结）：addon 编译期 `-DNAPI_VERSION=10`，
头文件取自建 Node 树 `src/node_api.h + js_native_api.h + node_api_types.h`
（三文件自足，实证存在；`node_api_types.h:16` 有 `#if NAPI_VERSION >= 3` 门，
版本宏由编译命令行注入）。

**TSF 双队列 → 线程铁律映射（§5.3/§7.3）：**

- `tsf_control`（stop/ack/evaluate/错误上报）：`napi_tsfn_nonblocking` 投递，
  **绝不丢弃、绝不降级**；其队列满 = 背压信号向上游（看门狗/调度）报告，不静默吞。
- `tsf_data`（console/事件流/传感器）：同为 nonblocking，**可丢包**（丢包计数 →
  JS 层 `queueError`，`consoleSink.onQueueError` 对偶）；闲置 `napi_unref_threadsafe_function`
  不保活事件循环（脚本跑完即退出，不靠 TSF 吊命）。
- `async_work` 线程**不碰 JS**（N-API 约束）；需回 JS 的一律经 TSF。
- 持有 Java lock 时**禁止**回调用 JS（死锁铁律）；回调在释锁后投递。
- 原生入口只 `GetEnv` + 局部 attach，**绝不缓存 `JNIEnv*` 跨函数**（§5.2）。

**addon ↔ Kotlin Router 对接（§7.5 信封，JsonTransport 已实证）：**

- addon 内嵌 socket 客户端（与 `SocketBootstrap` 同语义）：请求
  `{"t":"req","id","ns","m","ttl","payload","side"}\n` → 回复 `ok/err` 按 id 结算；
  addon 不解释 payload（§7 只透传，CapabilityNamespaces 注释同纪律）。
- JS→Java：addon 的 `invoke(ns, method, payloadJson, reqId, ttl)` 即
  `RuntimeBridgeImpl.install` 的 handler 形（`bridge/js` 已用此形跑通 E2E）。
- Java→JS：Kotlin 侧事件经 JNI 进 addon → 按 context 的 TSF 投递 → JS 事件循环；
  TSF 每 context 一对（control+data），context 销毁时 pair 同生共死（§7.4 句柄
  generation 语义在 native 侧的对偶：跨代 TSF 投递直接丢弃）。

**宿主进程（:nodeN）最小启动序**（①②③已落 `main.cpp`，本机交叉编译验证；真机执行待 CI）：

1. 连 `:main` unix socket（`AUTOSCRIPT_HOST_SOCKET` 同名 env，`SocketBootstrap` 语义）。
   **地址双形态**（main.cpp `ConnectHostSocket` 判别式）：`/` 开头 = 文件系统路径
   （桌面/CI/E2E），否则 = Linux abstract 名（设备：minSdk 26 无 `ServerSocketChannel`
   unix API，`:main` 监听用 `LocalServerSocket(String)`）；双侧 `SO_PEERCRED` 校 uid
   （abstract 名没有文件权限 → 防抢绑/冒名顶替，uid 不符即拒）。两种形态都缺 env =
   离线模式（桥调用如实 `ERR_ENGINE_STOPPED`），env 给了连不上即硬失败 exit 3，不静默降级；
   **宿主建连并完成 §7.5 hello/ACK 后，才经 `AUTOSCRIPT_SOCK_FD` 注入 addon**。
   建连、短读写、EINTR、EOF 与 ACK 校验共享 10 秒截止时间；在线缺 token/认证拒绝同样 exit 3，
   不继续执行在线脚本。成功后清 token env，但不把它当同 UID 隔离保证。
   addon 不自连、不读取控制 ACK，一次性凭据不自动重连；
2. `dlopen libnode.so`（RTLD_NOW|RTLD_GLOBAL；宿主自身 **DT_NEEDED `libc++_shared.so`
   + RUNPATH `$ORIGIN`**，用来满足 libnode 自己的传递依赖 —— bionic 的 RUNPATH 不作用于
   被依赖库的传递依赖，2026-09-29 真机实证；16KB 门禁已过，PRODUCT 哈希 `3cadbcdf…`
   见 `/tmp/nrb-out7/SHASUMS256`）。
   **`napi_*` 的解析面不是这一步给的**（原口径"addon 的 `napi_*` 从 libnode 动态表解析"
   已推翻，见 [`design-decisions.md`](../design-decisions.md#已推翻--已改口径)）：bionic 的
   linker namespace **不把先做的 `dlopen(RTLD_GLOBAL)` 符号给后做的 `dlopen`**（glibc 会）。
   addon 侧的解法是它自己 **DT_NEEDED `libnode.so`**（按 SONAME 命中已在进程内的那份，
   与落位目录无关），宿主不必先加载 libnode；宿主保持 dlopen 形只为 exit 4 的失败语义与
   "libnode 可被候选位替换"（见 `engine/node-process/scripts/build-native.sh` 的装载闭包断言）；
3. `dlsym _ZN4node5StartEiPPc` → `node::Start` 单 isolate/context，argv =
   `node -e BOOTSTRAP -- <script> [args…]`（无 addon 则直接跑 script）：BOOTSTRAP 预载
   `bridge_native.node` addon → `setSocketFd`（首次注入即拉起读线程）→ 读
   `AUTOSCRIPT_RUN_ID` 起 500ms `engines.heartbeat` 自动打点（`setInterval().unref()`
   不吊命事件循环；reqId `-seq` 负数命名空间；打点失败 try/catch 吞掉不炸脚本 ——
   失联由看门 `noHeartbeat` 判，不是让心跳反过来杀脚本）→ require 真脚本
   （`AUTOSCRIPT_RUN_NONCE` 随 env 透传给脚本做 §8.5 幂等键，本文件不消费）；
   JS 侧 `setup(onFrame)` 建 data TSF 由 facade 接入时调 —— **已落**（facade
   `attachNative()`：setup 结算 + `addon.invoke` 注入 runtimeBridge + `errFromThrown`
   保留错误码；打包入口的调用已随资产交付轨落地 —— main.cpp kBootstrap 在
   `AUTOSCRIPT_BRIDGE_DIST` 在位时调 `attachNative({addon})`，2026-09-24，
   见 §12.4 切片路线）。未 attach 时响应帧计入 `droppedData()`
   （诚实可查，不静默吞）。`console.log` 经 tsf_data → socket → `ConsoleCollector`
   （E2E 已在 JVM+Node 双侧验证语义，待真机跑通即 §19 垂直切片闭环）；
4. 停机走四步 quiesce（SINKING → generation 排空/超时斩杀 → 释 TSF/句柄——addon 侧
   环境 cleanup hook 已接（换代 + 释 TSF，读线程先断源不再触已释放句柄）→
   `node::Stop` + 回调归档），禁直接 kill（§5 推论 A）。

---

