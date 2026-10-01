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
   RequestRegistry (requestId → PendingRequest{ttl, cancel})
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
  拼装 `ConsoleRead` 住 `:app` 壳装配包、纯 JVM 可测）→ `:ui` 第四页签控制台屏。呈现纪律：**游标只进不退、行累积**（刷新 = 增量拉取不是重画；并发同游标按 seq 去重）、
  **读失败保留旧行与游标**（瞬时失败抹掉用户已看到的日志比报错更糟；失败只亮原因）、拉满标「可能还有」不假装到底、**丢包非零不藏**（`droppedTotal` 上屏 —
  — 显示的不是全部得说出来）、在途执行两端对照随快照带上（§8.3：宿主读不到如实说读不到、**不渲染成某个状态**；分歧标红，判据仍在 `RuntimeController` 不在呈现层）。
  刷新时机与能力中心/任务中心同构：回前台/切页签现取，页大小 256，读失败不自激。**停止操作面同批落地（2026-09-24）**：读口 `HostSummary.stopRun(runId)`（`:domain`）→ `AssembledShell.stopRun`（壳持有的在途表 `RuntimeController.stop` → 池四步 quiesce，
  `AlreadyGone` 如实 false 不抛）→ `AppShellApplication.stopRun`（壳未装配抛）→ `:ui` 控制台在途行每行一个「停止」按钮（`ConsoleState.stopError/stopNotice/stopInFlight` 与读账分开记账、
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
| `matchTemplate` 1080p | < 40ms | OpenCV TM_CCOEFF_NORMED（实测它、不是早前写的 CCORR：见下注）；2026-09-30 起**金字塔粗筛 + 原像素精配**（灰度缩小图只提名候选、坐标与置信度回原 4 通道小窗重算，语义一字不动）+ 两匹配方法可选 `region` 缩窗（机制与错误码见下注、方案裁决见 `design-decisions.md` 第 13 项）——**✅ 2026-09-30 三次实测：370×80 形态全帧 24.47ms 达标转绿（形态注明），48×48 形态见三次实测注** |
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
> **2026-09-30 第三次实测（评审 patch 验证轮 → 采纳，commit `852fb45`；云手机同机
> 同 `scr.raw`，A = patch 默认 vs B = `FORCE_EXACT=1`，×100 中位）**：
> patch = 外部评审 `vision-optimized.patch`（粗筛下限 80→48px、`kMinCoarseSide`
> 24→12、0.25× 候选带宽 +0.05、needle prep 独立锁缓存）；验证走临时分支 +
> PR #11（验后分支已删），全门禁绿后采纳：
>
>   | 项 | 二次实测（`7d92faf`） | **三次 A（采纳）** | 三次 B（强制精确） | 判据 |
>   |---|---|---|---|---|
>   | A4 match 370×80 全帧 | 62.98ms | **24.47ms** | 888.97ms | <40ms ✅ **转绿** |
>   | A4 match 48×48 全帧 | 855.5ms | 948.5ms† | 857.4ms | <40ms ❌（内容受限） |
>   | A4-region 48×48 @300×150 | 16.57ms | 16.88ms | 16.95ms | <40ms ✅ |
>   | A4-region 370×80 @540×190 | 11.77ms | 9.77ms | 29.96ms | <40ms ✅ |
>   | A2 decode+match×2 | 171.75ms | **94.61ms** | 1877.3ms | <700ms ✅ |
>   | A3 findColor ROI | 0.870ms | 0.854ms | 0.877ms | <10ms ✅ |
>
>   - **判据行裁决（2026-09-30，拍板「转绿（形态注明）」）**：`matchTemplate
>     1080p <40ms` 按**原全帧口径**转绿 —— 依据是 370×80 形态 24.47ms（**判据原文
>     一字未改**，改的是数字）。†48×48 形态**不进此绿字**：真机模板灰度 std 6.29
>     <12 + 频率门 0.69 <0.8 双拦 → 恒精确路径（948ms 与基线 855ms 同量级，
>     run 间噪声），形态注明如实挂 ❌，出路 = `region` 16.9ms 推荐姿势。
>   - **门禁诊断（48×48 被拦是保精度，不是门太严）**：该模板 0.25× 自检互相关
>     只有 0.69，**低于候选带宽 thr0.9−margin0.15=0.75** —— 硬放进粗筛，真峰会
>     落在带宽之下 → 零候选 → **必假漏检**。双门按回精确路径 = 与旧行为逐位同解。
>     370×80 则 std 19.45 / rt 0.812 双过 → 0.25× 粗筛（这就是 24.47ms 的来源）。
>   - **精度面结论（采纳依据）**：报出的坐标/置信度永远由**原 4 通道全图
>     `matchTemplate` 在精配窗内重算**，阈值/未命中/坐标口径一字未动；粗筛只决定
>     「提名哪些窗」。假漏检是唯一残余风险面，四层压住：①三道门（尺寸/灰度 std/
>     频率自检，不适合的模板走原路径）②host 差分双跑 372 检查（同位置 + 置信度
>     ≤2e-3 或同未命中）③真机探针 conf=1.0000 同位（A4/A4-small 均验）④
>     `FORCE_EXACT=1` 回退阀。已知边缘：370×80 的 rt=0.812 仅高于门槛 0.012，
>     贴门内容粗峰可能跌破带宽 → `MARGIN` 环境旋钮加宽（付速度）或回精确兜底。
>   - needle prep 缓存（独立锁 `g_match_cache_mu`，锁序 `g_mu→cache_mu` 两处一致）：
>     模板端准备 <1ms/次，真机分辨率测不出收益 —— 随 patch 采纳，账在代码评审面。
>
> **2026-09-30 第四次实测（相位探针门 + 自适应 K，commit `0ec6ef4`，PR #12；云手机同机
> 同 `scr.raw`，A = 默认 vs B = `FORCE_EXACT=1`，×100 中位）**：
> 改动 = 评审二轮原型移植 —— **相位探针门**替换静态 scale-cycle 0.8 门：按粗筛栅格
> 相位 {1,2,3}²（0.25×）反射填充（边 12）互打，取真位置 ±2 窗最差分 →
> `NeedlePrep.phase_worst`（与 thr 无关、随 prep 缓存）；**每调用地板**
> `floor = min(thr − 粗带宽 + headroom, 0.97)` 绑带宽 —— 闭掉静态 0.8 门在高阈值
> （thr−带宽 > 0.8，如 thr=0.99 → 0.84）下放行「够不着候选带宽模板」的假漏洞。
> 另加 **自适应 K**（精配预算 `8×96×398` 定容：`K = min(32, max(t.max_candidates,
> round(预算/精配窗面积)))`，窗小 K 升到 32 补重复峰召回、窗大维持地板 8；pad 升
> `ceil(1/sc)·3+2`）与新 knob `AUTOSCRIPT_MATCH_HEADROOM`（缺省 0.05）；候选带宽
> 与地板单源 `coarse_margin_of(sc)`：
>
>   | 项 | 三次 A（`852fb45`） | **四次 A（相位门）** | 四次 B（强制精确） | 判据 |
>   |---|---|---|---|---|
>   | A4 match 370×80 全帧 | 24.47ms | **25.37ms** | 887.60ms | <40ms ✅ |
>   | A4 match 48×48 全帧 | 948.5ms† | 855.6ms† | 861.5ms | <40ms ❌（同判） |
>   | A4-region 48×48 @300×150 | 16.88ms | 16.53ms | 16.62ms | <40ms ✅ |
>   | A4-region 370×80 @540×190 | 9.77ms | 10.64ms | 25.37ms | <40ms ✅ |
>   | A2 decode+match×2 | 94.61ms | **95.43ms** | 1869.75ms | <700ms ✅ |
>   | A3 findColor ROI | 0.854ms | 0.905ms | 0.899ms | <10ms ✅ |
>
>   - **归因干净**：四次 B = 887.60 / 861.5 / 1869.75ms 与三次 B（888.97 / 857.4 /
>     1877.3）同量级 run 噪声 → A 的提速全部来自相位门路径，无基线漂移；真机探针
>     conf=1.0000 同位（A4 / A4-small 均验）。
>   - **相位门真机诊断（host 同 `scr.raw` 模板，floor=0.800）**：370×80
>     `phase=0.846` 过门 → 保 24.47ms 形态（25.37 含探针成本与 run 噪声）；
>     48×48 `phase=0.794` 拦 + std 6.29<12 双门 → 恒精确（†与三次同判，出路
>     region 不变）。覆盖诊断 9 尺寸 ×60 随机裁片：新门整体严于静态 0.8。
>   - **精度面**：拦漏检的机制从「静态门与 thr 脱钩」换成「相位最差分 < 带宽地板」——
>     子像素相位错位正是静态对齐自检（缩小→放大同位互打）测不到的塌陷源；门恒
>     保守方向（拦错只损失速度）。差分锁新增 **case6 高阈值 0.99 差分**：静态 0.8
>     门下 thr−带宽=0.84>0.8 会放行假漏，新门 floor 绑带宽闭洞。host 全量 377
>     检查绿、NDK 双过。
>
> **2026-10-01 第五次实测（FastPath 12a + 场景端粗筛缓存，commit `a22fbfd`/`8d20500`，PR #13；
> `feat/fastpath-16x` 叠在相位门基线 `0f17af9` 之上；云手机同机同 `scr.raw`，×100 中位）**：
> 两处改动各自治一段：**FastPath(12a)** = NMS 后提名唯一（`cands.size()==1`）且主峰
> ≥ `thr+0.05` 时 `pad` 由常态 `ceil(1/sc)·3+2`（0.25× 档 = 14）收窄到 `ceil(1/sc)·1+2`
> （= 6）；**场景端缓存** = `g_scene_prep`（ref → {sc, hs}）把全帧 `cvtColor(BGRA2GRAY)`
> + `resize(0.25×)` 按 (帧, 缩放档) 缓存，与 needle 缓存同锁同钩子，只缓存全帧
> （region 是浅视图，其灰度/缩小与「先全帧再裁」在小尺度边界有舍入差，键得带 region
> 才等价 —— region 本来就是低延迟出路，不缓存）。
>
> **24ms 的账（分段估算，先于两处改动）**：region 扫掠线性拟合（0.04/0.10/
> 0.32/1.08 Mpx，斜率 7.4ms/Mpx、截距 ≈0）把 100 次稳定值 23.73ms 拆成 ——
>
> | 段 | 全帧成本 | 占比 | 怎么量的 |
> |---|---|---|---|
> | 场景 `cvtColor(BGRA2GRAY)` | **≈5.9ms**（上界†） | 25% | `imgnative_gray` 10.97 − 全帧 `crop` 拷贝 5.12 |
> | `resize 0.25×` | **≈1.6ms** | 7% | `imgnative_resize` 直测 1.63ms |
> | 粗筛 `matchTemplate`（0.25×） | **≈11.4ms** | 48% | 18.9（拟合外推全帧）− 上两行 |
> | 提名 + 精配（K 窗，pad=14） | **≈4.8ms** | 20% | `refine_probe` HIT−miss 同面积对消 |
>
> 前两项只由 (帧, 缩放档) 决定、与模板无关 —— 「帧入表后不可变」这条不变式
> 与 needle 缓存逐字同源，正是场景缓存的依据。†**cvtColor 行是上界**：量的是
> `imgnative_gray`（cvtColor + 4 通道 scatter 回填），生产粗筛路径只出 1 通道灰度、
> 没有 scatter —— 下面同会话三方 A/B/C 给出该行 + resize 的直接联合实测。
>
> **同会话三方 A/B/C（决定性归因；同一 bench 二进制、交替顺序 main→12a→两者→两者→12a→main，
> A4 每档两跑、×100 中位；so = 三个 commit 各自的 CI 产物，sha256 前缀 main `2b0578af` /
> `a22fbfd` `c4013e90` / `8d20500` `d461aaad`）**：
>
>   | 项 | main（相位门） | `a22fbfd`（仅 12a） | `8d20500`（12a + 场景缓存） | 判据 |
>   |---|---|---|---|---|
>   | A4 match 370×80 全帧 | 25.74 / 25.78 → **25.76ms** | 23.73 / 24.18 → **23.96ms** | 20.17 / 20.30 → **20.23ms** | <40ms ✅ |
>   | A4-region 370×80 @540×190 | 10.78ms | 8.68ms | 9.21ms‡ | <40ms ✅ |
>   | A2 decode+match×2 | 96.74ms | 92.73ms | **89.26ms** | <700ms ✅ |
>
>   - **逐项归因**：**12a 单独**贡献 A4 −1.80ms / region −2.10ms / A2 −4.01ms（A2 两次
>     match 都走金字塔，各吃一次收窄）；**场景缓存再叠**贡献 A4 −3.73ms / A2 −3.47ms
>     （A2 只有第二次 match 吃得到缓存）。合计 A4 −5.53ms、A2 −7.48ms —— 与「五次 A
>     vs 四次 A」的跨会话差值（−5.55 / −6.57）**方向与量级一致** —— 跨会话那次比较与本次
>     同会话结论互证。
>   - **场景缓存的实际账面 ≈3.7ms/次重复 match** = 生产 `cvtColor + resize` 的联合
>     实测成本（低于估算表 5.9+1.6=7.5 —— 上界原因见上）。
>   - ‡**region 行的 9.21 vs 8.68 是 run 噪声**（±0.5ms）：region 路径**不走**场景缓存
>     （见上「只缓存全帧」），两 so 在这条路径上代码逐字相同 —— 这组差本身即反证。
>   - **数字与跨会话「五次 A」表的关系**：下表（与上文四次块并列的那张）的 19.82ms 出自
>     较早一次会话（同 so、同 bench），本三方表 20.23ms 是同会话值；两者差 0.4ms 属
>     run 间噪声。判据行取哪张都不改结论。
>
>   | 项 | 四次 A（相位门 `0f17af9`） | **五次 A（12a + 场景缓存）** | Δ | 判据 |
>   |---|---|---|---|---|
>   | A4 match 370×80 全帧 | 25.37ms | **19.82ms** | −5.55（−22%） | <40ms ✅ |
>   | A4-region 370×80 @540×190 | 10.64ms | **8.57ms** | −2.07（−19%） | <40ms ✅ |
>   | A2 decode+match×2 | 95.43ms | **88.86ms** | −6.57（−6.9%） | <700ms ✅ |
>   | A4 match 48×48 全帧 | 855.6ms† | 867.5ms† | run 噪声 | <40ms ❌（形态同判） |
>   | A4-region 48×48 @300×150 | 16.53ms | 16.76ms | — | <40ms ✅ |
>   | A3 findColor ROI | 0.905ms | 0.893ms | — | <10ms ✅ |
>
>   - **跨会话口径**：五次 A 与四次 A 是不同次会话（设备温度/后台不同），逐项归因
>     以同会话三方表为准，本表只作形态与判据的对照。
>   - **48×48 形态**：†与四次同判（真机模板 std 6.29<12 + `phase=0.794` 双拦 → 恒精确
>     路径），两处改动都不进这条路径，867.5 vs 855.6 = run 噪声。判据行绿字口径不变。
>   - **精度面（不变式一字未动）**：FastPath 只改「精配窗多大」不改「报什么」——
>     坐标/置信度仍在原 4 通道窗内重算；场景缓存是纯 memoize（结果只由 (帧, sc) 决定，
>     **缓存命中与否不改变任何出参**）。host 差分门扩到 **396 检查**（新增 case6d/6d2
>     唯一高置信/重复副本破唯一、case9 缓存四检查：连跑两次逐字段一致、中间插不同
>     sc 档再回来仍一致、region 路径与全帧同解），`FORCE_EXACT=1` 回退阀照旧。
>   - **①（1/16× 粗筛）已用数据否掉（只追加，第四次块「出处②已落地」不动）**：
>     host 端到端探针（`c16_full`，同 `scr.raw`，370×80）—— 1/16× 粗筛本身确实快
>     （0.41 vs 6.43ms），但提名**爆量**：`ncand=8`（K 名额被假峰吃满，粗峰不再唯一），
>     每个候选都要精配 → 端到端 **48.1ms**（pad18）/ **29.6ms**（pad6），**全部劣于**
>     现行 0.25× 的 10.6ms；且 1/16 档对 370×80 根本不进守卫（`kMinCoarseSide=12`
>     要求模板短边 ≥192px）。1/16 的「省粗筛」被「精配窗涨」加倍吃回 —— **不做**。
>
> **2026-10-01 第六次实测（大模板形态 + findColor 全帧早退 + findFeature 四修，commit
> `a78515c`/`34fd80e`/`f6cb926`，`feat/fastpath-16x` 叠在 `8d20500` 之上；host x86_64
> 直链同一份 OpenCV 4.14.0，`/tmp/imgbench/scr.png` 1080×2400 真机截图，thr=0.9）**：
> 这一轮治的是**判据口径之外、但真机上真会发生**的三类形态 —— 大模板（粗筛恒被挡回
> 精确路径）、薄/小模板（ORB 预筛恒清空关键点）、全帧大命中面找色。
>
>   | 项 | 改前 | 改后 | 判据 | 备注 |
>   |---|---|---|---|---|
>   | match 300×150 全帧 | 1467ms（回精确） | **13.33ms** | <40ms ✅ | 相位平均粗模板 |
>   | match 200×150 全帧 | 1360ms（回精确） | **10.78ms** | <40ms ✅ | 同上 |
>   | match 370×80 全帧 | 20.23ms | **11.72ms** | <40ms ✅ | 平均模板顺带再省 |
>   | match 120×90 全帧 | 12.9ms | **12.27ms** | <40ms ✅ | 形态不变 |
>   | findColor 全帧·**取首点段** | 22.12ms | **0.003ms** | （口径外） | 行主序早退；整调用 4.33ms（inRange 地板） |
>   | findFeature 真机 UI | 恒 found=0 | **found=1 err 0.0px** | 见 §9.2 | 四修（下详） |
>   | findFeature 可达率 | — | **14/33**（13 例 ≤3px） | （口径外） | 见下「命中率」条 |
>
>   - **大模板的病灶与直觉相反**：粗筛假 miss 不是粗模板"太好"，是它**只对住了一个
>     相位**。真机实测同一模板各相位粗分 `1.0000 / 0.9007 / 0.6926 / 0.8943`，thr=0.9
>     时带宽 0.75 —— 0.6926 够不着 → 提名层空手 → 回精确路径。修法 = 对 nuisance
>     参数做**平均**（匹配滤波的标准解法）：16 个相位（0.25×）/ 4 个相位（0.5×）的
>     粗图按内容原点对齐后取平均当粗模板，最差相位 0.6926 → **0.8086**（0.5× 档
>     0.8269 → 0.9514）。相位探针同步改成量**平均模板**（门与实际跑的粗模板必须同源
>     —— 本轮第一版就是漏了这一步：模板已换成平均版，门还在按相位 0 的老数挡人）。
>   - **headroom 余量整个移除**：`floor = min(thr − 带宽, 0.97)`，`AUTOSCRIPT_MATCH_HEADROOM`
>     调参口一并删除。带宽之上再留余量是重复上保险（提名 ≠ 命中，候选还要过精配），
>     300×150 的相位 0.8086 对带宽 0.75 只差 0.0086 却被 floor=0.80 挡回，代价 473~1467ms、
>     收益为 0。
>   - **精度面**：粗筛仍只提名，坐标/置信度全部回原图 4 通道窗重算 —— 大模板四例
>     全 found=1、Δpos=(0,0)、Δconf=0.0000（对照精确路径 498~767ms）。`FORCE_EXACT=1`
>     回退阀照旧。**绝对 ms 与窗口/机器相关，判据看的是量级**：同形态换窗口复测
>     （`/tmp/ffix/mrow`，同会话另一轮）300×150 @(60,600) 11.7~11.9ms / @(400,600)
>     11.7ms / @(60,1500) **30.9ms**，200×150 @(60,600) 9.3ms / @(60,1000) **21.3ms**
>     （差在停止位与精配候选数）；跨会话再测 300×150 得 12.0~13.2ms、370×80 9.0~11.7ms。
>     表里那一列取的是**同一轮的 A/B 对照值**（改前/改后同进程同窗口），单看一个绝对
>     数字别当常量用。
>   - **findColor 全帧**：改的是**取首点那一段**，不是整调用。`findNonZero` 先把整张
>     命中点表物化（76.7% 命中面 1.99M 点 ≈16MB）再取 `points[0]` —— 该段 19.4~22.1ms，
>     且**命中越多越贵**（89.6% 面 28.4~30.4ms、0.02% 面 1.5ms、未命中面 0）；改成行主序
>     自己扫、第一个非零即返回 → **0.003~0.016ms**（复跑三次的上界；数值本身也随机器浮动）。**整调用另有地板**：全帧
>     `cv::inRange` 本身 ≈4.3ms（三档命中面整调用实测 4.33 / 4.06 / 4.02ms），所以
>     整算子全帧 ≈4.3ms —— **别把 0.003 读成整调用**（判据 <10ms 的口径是「单人独立
>     子图」300×150 ROI，2026-09-30 真机 0.88ms ✅；全帧从来在口径外）。答案口径未变
>     （`findNonZero` 就是行主序，这是同一个答案更早拿到，不是"取任意命中"）。
>   - **findFeature 四修**（真机恒 found=0 的病灶链，详见 §9.2 与 `design-decisions.md`
>     第 13 项）：① 场景侧 ORB 配额按短边分档（>640 抬到 nfeatures=8000/et=10）；
>     ② 内点铺开度门（任一边跨度 <10% 判未匹配）；③ 命中位置改成**模板中心**
>     （原「内点质心」真机偏 82.4px）；④ 薄条/小模板零关键点**垫边重试**。修后真机
>     实测：300×150 err 0.00 conf 0.682 / 200×150 err 0.00 conf 0.750 / 540×600
>     err 0.00 conf 0.583，370×80 与 48×48 仍 found=0（真·无特征，诚实未命中）。
>   - **命中率/可达率与假阳的定量**（`/tmp/ffix/featwin`，1080×2400 真机截图：200×150
>     网格 **24 窗** + 文档引用过的 9 窗 = 33）：**命中 14/33、其中 13 落在真值 3px 内**；
>     唯一一个错位置（200×150 @(660,1900)，报 (760,1431)）经 `matchTemplate` 复查
>     **真值处 ccoeff=1.0000 且是全图 argmax** —— 是该窗口的特征链没走到真值，不是
>     "场景里有更像的地方"。**诚实边界**：ORB 链的**可达率**不是 100%（网格 200×150
>     命中 10/24；26 个 200×150 窗口真值处 ccoeff 实测最小 **0.99993**，位置本身无歧义）；
>     真值处 ccoeff ≈1.0 却 **零特征**的窗口
>     （场景侧配额抬到 40000/et10 仍为 0）**恒 found=0**，这是特征匹配对无纹理区域的
>     固有边界（此时该用 `matchTemplate` —— 它在这批窗口上**命中 33/33**，conf 全 1.0）。
>     **但"命中"不等于"位置唯一"**：同一批窗口它只有 25/33 报在真值处，其余 8 例经
>     `dupchk` 复查全是**像素级重复副本**（报出窗与真值窗逐像素最大差 0~1 —— 屏幕里
>     真有两块一模一样的地方，argmax 挑了另一份，与 §7.7 三次实测里 48×48 报 (57,751)
>     那条同源）。要"唯一位置"得脚本自己拿阈值/多候选择一，算子给的是 argmax 口径。
>   - **诚实交代三条**：① 真机 UI 上仍有恒 found=0 的窗口 —— 19/33，逐窗交叉核对分
>     三类（`scenekp` 场景侧数点 + `featwin2` 模板侧两档数点）：**A 真值处场景侧零
>     关键点 6 例**（48×48 纯色图标 + 5 个 200×150 平坦窗；配额 8000→40000 后仍有
>     5/4 例为 0）、**B 模板侧缺省 ORB 零点 5 例**（370×80 纯文字行、1080×60 工具行、
>     三个 200×150；垫边后 6~57 点仍 found=0）、**C 两侧都有点仍配不上 8 例**
>     （缺省 7~124 点、垫边后 61~486 点）。A 是特征匹配的固有边界、B/C 是 ORB 在低
>     纹理 UI 上的可重复性极限，都不是缺陷；② 分档的 640 门槛是按**整屏截图**形态调的，
>     中等裁剪（540×600、600×800）在缺省档下找 200×150 仍会漏 —— 真机消费方是整屏
>     截图，暂不为中间形态再加一档；③ 垫边重试不是免费的：它让"内容 + 一圈复制缝"
>     整体可比，重复区上的自匹配偶尔会凑出几何一致的错答案（重试路径因此挂像素复核，
>     但复核只挡"报出位置根本没有模板像素"那一类）—— 需要"只认唯一位置"的脚本应改用
>     `matchTemplate`。
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
> **未覆盖**：16KB 页机（该机 PAGE_SIZE=4096）、非 root 的 SELinux enforcing 上下文、
> `nativeLibraryDir` 提取路径、targetSdk36 的 app 数据区 exec 策略 —— 仍需 16KB 模拟器
> 镜像或真机。`.so` strip 归 CI 打包管线。

**符号面（动态 T，稳定 ABI）：**

| 符号 | 来源 | 用途 |
|---|---|---|
| `_ZN4node5StartEiPPc`（`node::Start(int, char**)`） | libnode.so | :nodeN 单进程单 isolate 入口（§5.1 一进程一 Start） |
| `_ZN4node4StopEPNS_11EnvironmentENS_9StopFlags5Flags` | libnode.so | quiesce 第④步后收尾（§5 推论 A：kill 必须归还槽位，Stop 即"正常死"的路径） |
| `napi_create_threadsafe_function` / `napi_call_threadsafe_function` | libnode.so | TSF 双队列的创建/投递（§7.3，见下） |
| `napi_module_register` / `napi_module_register_by_symbol` | libnode.so | addon 模块注册（`bridge_native.node` 即一个 N-API 模块，见 `engine/node-process/scripts/build-native.sh`） |
| 20562 个动态 T 符号（含 `napi_create_external_arraybuffer` 系） | libnode.so | §7.4 大二进制 0 拷贝（`allocateDirect` → external arraybuffer）的符号依据 |

`NAPI_VERSION=10`（§67 选型表冻结）：addon 编译期 `-DNAPI_VERSION=10`，
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
   **宿主建连、经 `AUTOSCRIPT_SOCK_FD` 注入 addon**——addon 契约是「宿主注入已连 fd、
   建连/重试/熔断归宿主」，不自连（§7.5 对接条 + addon 注释）；
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

