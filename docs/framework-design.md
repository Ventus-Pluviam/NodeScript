# AutoScript —— 内置 Node.js 的安卓自动化平台 · 框架设计 v1.0

> 本文档是「AutoScript」框架的完整架构设计：一个对标 AutoJsPro v9 的 Android 应用，内置 Node.js 运行时，让用户用 JS/Node 编写脚本自动操控系统与其他应用。
> 设计过程：4 路并行技术调研（Node-on-Android 选型 / AutoJs 系架构剖析 / 2026 Android 自动化接口与权限 / JS↔Native 双向桥机制）→ 4 个独立视角提案（分层解耦 / 执行运行时 / 桥接与 API / 产品路线）→ 每提案 3 维度批判（Android 约束 / 执行隔离 / 工程）共 11 份有效批判（全部 verdict=revise）→ 本文对全部有效修正做了一锤定音的综合。
> 设计日期：2026-09-17。参考 API 面：AutoJsPro v9 第二代 API（Node.js 引擎，Promise 风格）。

---

## 0. 一句话

**三个进程、一个异步桥、每脚本一个 Node 进程。脚本永远不进 UI 进程；跨进程调用永远异步；每次操作必有 TTL；teardown 永远四步 quiesce。**

---

## 1. 目标与非目标

### 目标
- **对标 AutoJsPro v9 的 API 能力面**：无障碍自动化、截图找图、悬浮窗、定时任务、原生/Web UI、多脚本引擎、打包为独立 APK、VSCode 远程调试、脚本加密。
- **脚本即一等公民**：用户用 Node.js 生态写自动化；内置 IDE/控制台/任务中心。
- **可长期演进**：清晰的依赖方向、明确的接缝（引擎/自动化通道/存储/OCR 可替换）、P0 小而完整可发布。
- **诚实面对 Android 2000 说「不」**：不在保活、后台启动、跨进程同步这些被系统禁令的地方假装可以。

### 非目标（P0-P1 明确不做）
- 不跑在 Play Store 发行管线（specialUse FGS / SCHEDULE_EXACT_ALARM / MANAGE_EXTERNAL_STORAGE 与 Play 政策冲突）→ 官网 / F-Droid / APK 直下。
- 不做「无人值守的自愈」：精确闹钟、电池白名单、开机启动默认**不自动授予**，全部走能力中心引导；未授权即降级并在 UI 明示。
- 不在 v1 承诺 `child_process.spawn`（Node-on-Android 不可用）与未经验证的 `worker_threads`（见 §8.2 决议）。两者都由引擎适配层如实上报为「不支持」，不伪造。
- 不承诺 iOS/Windows 跨端（单 Android 目标）。
- 不做侵入式破解/绕过系统安全（root 通道是用户自选能力，需要 root 设备）。

---

## 2. 核心理念与五条架构铁律

所有设计决策服从以下五条不可谈判的不变量。批判阶段证伪过多种「违反它」的设计。

1. **脚本绝不进 UI 进程。**
   开源 Auto.js 最大的架构教训就是脚本跑在主进程（UI 卡顿、一个死循环拖垮整个 app、无法 kill 单个脚本）。脚本运行时只存在于独立进程。
   - 推论 A：**进程级隔离是唯一的真实隔离**。「模块作用域 + 独立 vm context」不是隔离——`process.exit()` 会带走同进程的一切。任何被用户脚本控制的特性（`process.exit`、`setTimeout` 风暴、OOM）都必须被进程边界吸收。
   - 推论 B：最不可信的代码最隔离 → QuickJS 沙箱（跑第三方/市场脚本）必须在其**自己的进程**，而不是塞进主进程的一个线程。

2. **跨进程调用永远异步。**
   JSON-RPC 过桥、Binder、IPC 一律 Promise；**同步变体被禁止**。同一事件循环内的纯内存路径（如纯 JS datastore importer）可以同步，但只要有进程/线程边界跳过同步 facade。
   - 理由：任何 `同步等待对方线程` 的调用，遇到对方忙于自身事件循环＝事件循环冻结；双向同步等待＝必死锁。批判 9 明确指出「QuickJS 里加同步桥变体」是死锁陷阱。
   - 推平：UI 主线程(Looper) 与 Node 事件循环(Native 线程) **永不互相阻塞等待**。

3. **每次跨进程操作必有 TTL，状态永不落定。**
   一条消息从发出到收到 reply（或明确 error）有强制超时（默认值按操作分级、可配置）；超时按错误路径收尾（`ERR_TIMEOUT`），释放 request 槽位，绝不允许 `await` 卡死到天荒地老。**zombie RUNNING 状态必须是不可构造的**——任何进入 RUNNING 的路径都必须被绑定到一条会终结它的时限（看门狗心跳 + 操作 TTL 双保险）。

4. **teardown 永远四步 quiesce。**
   停任何执行单元遵循固定协议：**① 停止新的 dispatch（进 SINKING）→ ② 用 generation 号等 in-flight 排空或超时斩杀（进 QUIESCED）→ ③ 释放引用/句柄/TSF → ④ 完成回调 + 归档**。禁止「直接 kill 还清理着共享资源」的野路子。资源句柄全部带 generation 号 + tombstone，跨代消息直接丢弃。
   - 推论 A（**衍生命名**）：「quiesce 是默认、kill 是例外」不改变另一条同样硬的不变量 —— **kill 权威的落点必须同时归还槽位与许可证**。强杀省略收归 = 池容量静默缩水（后续任务全排队到超时），这是比「没杀干净」更难发现的故障形态，因为它不报错。
   - 推论 B（**验收口径**）：一条终结路径写完，必须能看到「在途表摘除 + 槽位复位 + 许可证归还」三件事；三者缺一，该路径就还没写完。

5. **依赖单向 + Domain SPI 防腐蚀。**
   Kotlin 侧依赖方向恒为 `UI / 服务层 → 领域层(纯 Kotlin) ← 平台适配层(实现 SPI)`；JS 侧恒为 `api 包 → 桥`。任何模块禁止向上依赖、禁止跨层。可替换点（ScriptEngine / AutomationChannel / ImageAnalyzer / OcrProvider / Datastore / UiHost）都以接口接缝暴露，实现可热切换。

---

## 3. 技术选型总览

| 决策点 | 定案 | 关键理由 | 反方代价（已权衡） |
|---|---|---|---|
| **Node 发行渠道** | **Node 24.x LTS 源码自建 `libnode.so`**，fork/自持 `nodejs-mobile` 的构建 recipe 管线并自行维护 | 官方 nodejs-mobile 停在 18.20.4（已 EOL）且 ELF 按 4KB 对齐，**在 16KB 页设备上 dlopen 直接崩**；自建才能跟进 LTS 升级补漏洞 | 维护构建管线成本；需长期持有（预期树哈希门禁、NDK r27d/r28、jar 剥离、zlib/gzip 静态化约 26MB ABI 预算） |
| **引擎执行模型** | **每脚本一个 `:node` 进程**，进程池按设备内存自适应 | 唯一的真实执行隔离；`process.exit`/OOM/死循环只杀自己；全局变量天然隔离；崩溃可重建 | 内存开销（池默认 1–2，≥6GB RAM 到 3）；不能共享一个 Node 实例的模块缓存 |
| **JS 引擎** | P0 只做 Node；P1 加 **QuickJS** 作不可信脚本沙箱（独立 `:sandbox` 进程） | AutoJsPro 双引擎（Rhino+Node）证明沙箱是刚需；QuickJS 体积小、有 `JS_SetInterruptHandler` 可打断 CPU 风暴 | 双引擎维护；API 子集对齐成本 |
| **桥** | TS facade → N-API addon（`NAPI_VERSION=10`）→ JNI → Kotlin Router；**全异步 JSON-RPC + requestId 关联**；每 context 一个 `napi_threadsafe_function` | N-API 稳定 ABI、nodejs-mobile 系已验证；TSF 允许任何 Java 线程安全投递事件，`napi_unref_threadsafe_function` 闲置不保活事件循环 | 无同步调用便利性（/并发模型语病，已在 §7.2 定死） |
| **无障碍通道** | `AccessibilityService` 部署在 **`:main`** 进程；**紧凑索引树**传输；句柄带 generation | 与引擎进程分离＝无障碍服务存活不依赖脚本进程；紧凑树省 IPC 体积（全树 JSON 序列化是性能杀手） | 树构建在 UI 进程承担；事件洪峰需节流 |
| **截图** | 默认 **a11y `takeScreenshot`**（API34 起 333ms 节流）；**MediaProjection** 做会话式实时截屏/录屏（API34 每会话确认 + FGS 前置）；**图像分析全走 native**（独立 `libimgnative.so`，OpenCV 4.x） | 图像管线 0–1 拷贝直达 Native，避免 Bitmap→Byte[]→Buffer 多次拷贝；`FLAG_SECURE` 窗口如实返回 `ERR_SCREEN_LOCKED/ERR_BLACK_FRAME` 系错误对象 | 维护两份 so；OpenCV 静态链接体积 |
| **UI** | 脚本 UI = 桥把 XML 布局描述推给 `:main` 渲染（原生 View）；`ui_web` 走 WebView + JS 桥；悬浮窗独立小型宿主 Activity | AutoJsPro 已验证；XML→View 桥符合「脚本进程只产声明、主进程渲染」原则 | UI 事件回的桥链路较多 |
| **保活** | **specialUse FGS**（`onCreate` 即 `startForeground`，声明 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`，无超时）+ 电池优化白名单引导 + 精确闹钟看门狗 + 开机 specialUse 恢复；**全部作为一级权限项进能力中心** | API35 普通 FGS 有 6h 超时、普通后台启动受限；specialUse 无超时，精确闹钟不受 FGS 后台启动限制 | Play Store 不可发行；部分 ROM 需要引导 |
| **打包** | 模板 APK 改写（AXML/ARSC 编辑替换 application + 注入 assets/project），复用宿主 Node 引擎 so；加密资产 + 自定义 loader | AutoJsPro 已验证的发行形态；不需要为每个脚本重编 C++ | APK 依赖宿主引擎版本 |
| **npm 支持** | **D（混合）**：vendored 真 npm CLI（npm 12.x 系，Node≥24.15）在**专用安装会话进程**内进程内执行——P0 默认零 spawn（npm12 官方 `allowScripts=none` 等默认语义把「无 child_process」从 workaround 变成契约）；P1 加 spawn 桥升级通道（批准后 lifecycle/npm run）；内置离线 bundle + 精选 tarball 种子通道 | 纯 JS 生态（axios/dayjs/lodash/ws 等）安装**实证无需子进程**（strace 实测 0 execve）；真 CLI 白拿 lockfile v3/audit/审批语义且可审计；不重造轮子 | npm CLI ~8–9MB 体积；供应链护栏只能靠带外信任锚与审批人机分离（§10） |
| **进程隔离** | `:main`(UI+服务) / `:node0..N`(脚本) / `:sandbox`(QuickJS) | §4 进程拓扑 | — |
| **SDK 基线** | minSdk 24 / compile&target **36**（Android 16）/ arm64-v8a 首发（后续 x86_64）；**16KB ELF 对齐进 CI 门禁** | 2026 事实标准；16KB 页设备成为主流 | 放弃 32 位旧机 |

---

## 4. 总体架构

### 4.1 分层与依赖方向（文本图）

```
┌─────────────────────────────────────────────────────────────────────┐
│  :app  (Compose UI · 脚本 IDE / 任务中心 / 控制台 / 能力中心 / 打包向导) │
├─────────────────────────────────────────────────────────────────────┤
│  应用服务层 (androidx 生命周期 / FGS / 定时 / 仓库)                     │
│   RuntimeController · EnginePool · Watchdog · Scheduler              │
│   ScriptRepo · PermissionCenter · Packager · PluginManager           │
└──────────────────────────────┬──────────────────────────────────────┘
                               │ 只依赖领域层接口
┌──────────────────────────────▼──────────────────────────────────────┐
│  领域层 :domain (纯 Kotlin，零 Android 依赖，可单测)                   │
│   ● ScriptEngine / EngineSession / ExecutionHandle   (引擎 SPI)      │
│   ● AutomationChannel / UiNodeTreeReader / FrameSource               │
│   ● ImageAnalyzer / OcrProvider / Datastore / UiHost                 │
│   ● PermissionFacade / CapabilityState (三态状态机)                   │
└───────▲──────────────────────────────▲───────────────────────────────┘
        │ 实现 SPI                      │ 实现 SPI
┌───────┴────────────────────┐   ┌──────┴──────────────────────────────┐
│ :platform:capabilities     │   │ :engine:node-process    :engine:sandbox│
│  a11y / 截图 / 输入 / 悬浮窗 │   │  Node host (.so 装载 + bridge)      │
│  / 系统 / 存储              │   └─────────┬──────────────────────────┘
└────────────────────────────┘             │ JNI
┌──────────────────────────────────────────▼──────────────────────────┐
│  桥基础设施 :bridge (双向)                                            │
│   Kotlin Router · RequestRegistry(TTL) · HandleRegistry(generation) │
│   EventBus · transports · JS facade (TS) · N-API addon · JNI glue    │
└─────────────────────────────────────────────────────────────────────┘
        ▲ 控制面                    ▲ 图像数据面（独立 so：libimgnative.so）
┌───────┴──────────────────┐   ┌───┴───────────────────────────────────┐
│ :bridge:native (libnode) │   │ :bridge:image (OpenCV 管线)            │
│  node::Start / TSF 管理   │   │  RGBA→灰度/找色/模板匹配/特征/旋转       │
└──────────────────────────┘   └───────────────────────────────────────┘
```

依赖规则（Gradle 强制，`api`/`implementation` 配置 + 架构测试把关）：
- `:app` → `:app-service:*` → `:domain`
- `:platform:*` → `:domain`（**实现**领域接口，**不反向**）
- `:bridge:java` 依赖 `:domain`（DTO 复用）；`:bridge:native` 仅被引擎宿主进程使用
- `:domain` 零 Android / 零桥依赖，纯 Kotlin，全部可 JVM 单测

### 4.2 进程拓扑

```
┌──────────────────────────────────────────────────────────────────────────┐
│ :main  （前台进程 · 显著性最高）                                            │
│   Compose UI（脚本列表/编辑器/控制台/任务中心/能力中心/打包向导）              │
│   AccessibilityService（本进程，绑定了一次 Binder，离系统最近）              │
│   RuntimeController / 引擎池控制 / Watchdog 仲裁                          │
│   Scheduler（意图日志 checkpoint 驻留）+ 精确闹钟 + 开机 specialUse FGS     │
│   PermissionCenter（three-state 门禁）                                    │
│   CapabilityManager：MediaProjection 会话 / overlay / 通知 / datastore     │
└───────┬──────────────────────────────────────────────────────────────────┘
        │  bridge router（unix socket / binder？见 §7.5）
┌───────▼──────────────┐   ┌───────▼──────────────┐   ┌──────▼────────────────┐
│ :node0 引擎进程        │   │ :node1              │   │ :sandbox (P1)          │
│  node::Start          │   │  ...                 │   │  QuickJS 隔离池         │
│  N-API addon+TSF      │   │  池容量由设备内存决定 │   │  interrupt handler     │
│  单脚本/单 context     │   │  执行 slot 持 FGS     │   │  白名单 auto.* 子集      │
│  libimgnative.so      │   │                      │   │  独立进程：最不可信最隔离 │
└───────────────────────┘   └──────────────────────┘   └───────────────────────┘
```

进程职责切割的推演（含批判结论）：
- **无障碍服务放 `:main`**（离系统最近、显著最高、Binder 调用最省），且**与脚本进程解耦**——脚本崩了无障碍服务不死，反之亦然。批判 5/8 交叉验证了「无障碍与运行时同进程=一损俱损」「无障碍进引擎进程是错误」，主进程承接它被否决否认性证伪。
- **脚本（Node）只在 `:nodeN`**，执行中的 slot 持有 specialUse FGS（绑定到 :main 继承进程重要性，形成 group），防 LMK 优先回收。
- **QuickJS 沙箱 `:sandbox`** 单独进程：CPU 风暴不饿死 `:main` 渲染与无障碍回调；`process.exit`/死循环只死沙箱。批判 6 指出的「QuickJS 放主进程 = CPU 饥饿主进程」已修正为独立进程。

---

## 5. 进程/线程模型

### 5.1 进程
- `:main`：UI + 服务 + 无障碍 + 调度 + 能力（1 个常驻进程，specialUse FGS 保活）。
- `:node0…N`：脚本引擎进程。**每引擎进程恰好一个 `node::Start`/单一 v8 isolate/单一 context**（P0 单脚本进程一对一；并发 = 池化多进程）。孤立过程：
  - **池容量自适应**：由 `/proc/meminfo` + 设备分级决定，默认 1–2，≥6GB 内存 → 至多 3；低内存模式堆上限降为 128MB / 池=1。
  - 一个常驻引擎 slot 绑一个持 FGS 的「执行 slot」；闲时进程被复用（脚本之间不共享内存）。
- `:sandbox`：QuickJS（P1）。

### 5.2 线程
| 线程 | 归属 | 职责 | 规则 |
|---|---|---|---|
| Android 主线程 | `:main` | Looper / 渲染 / 无障碍回调 / Router dispatch | **永不阻塞等待 Node**；桥调用经队列异步化 |
| Node 事件循环线程 | `:nodeN` | JS 执行 / 全部 JS 逻辑 | 原生入口只应 `GetEnv`+局部 attach，**绝不缓存 `JNIEnv*` 跨函数** |
| libuv worker 池 | `:nodeN` | fs/网络等 off-main | 进 Java 回调时 `AttachAsDaemon`，短生命周期 |
| `:main` 桥接收队列 | `:main` | 收 Node→Java 事件 | 走 Handler/Looper 或直接线程池（不阻塞） |
| 看门狗外带线程 | `:main` | CPU 差分采样 `/proc/<pid>/stat`（O(1) 读，不依赖 Node 心跳）| 见 §8.4 |

### 5.3 互不阻塞协定（桥的线程铁律）
1. **Java 线程向 JS 投递一律 `napi_tsfn_nonblocking`**；Node 事件循环闲置时 `napi_unref_threadsafe_function` 不导致进程不退出（对应 `console.log`/事件流）。**绝不 `tsfn_blocking` 等在自身上**。
2. **JS → Java 请求由 `:main` 路由器异步派发**；严重路径（如无障碍节点读）不要求调用方阻塞——统一走 request-reply，调用方 `await` 且带 TTL。
3. **持有 Java lock 时禁止回调用 JS**（反向调用会与锁序互相等待 → 死锁）。所有回调在释放锁的临界区外投递。
4. **`async_work` 线程不碰 JS**（N-API 约束）；需要回报安全的线程（TSF）或 Android Handler。

### 5.4 错误 → 状态一致性
任何线程挂掉 → 看门狗仲裁 → `:main` 决定「重启 slot」还是「炸任务」→ 按 §8.5 checkpoint 语义恢复。绝无「半死不活还占着 slot」状态。

---

## 6. Gradle 模块结构与依赖规则

> 批判建议「约 12 个模块、不要过度拆分」，下表为落定清单（14 个），薄模块已合并（原 4 个 `:platform:*` 合并为 2 个，插件管理器/打包器等薄服务并入对应模块）——拆分的唯一目的是：**让依赖方向能在 Gradle 层面被强制**。

| 模块 | 职责 | 允许依赖 | 所有模块禁止 |
|---|---|---|---|
| `:app` | Compose UI（IDE/任务中心/控制台/能力中心/打包向导）＋ `AppShellApplication` 启动装配 | `:app-service:*`、`:domain` | 直连 `:platform`/`:bridge`（**唯一例外**：`com.autoscript.shell` 装配包为把 handler 挂上 `BridgeRouter` 可依赖 `:bridge:java`，只做字段级转接，无业务逻辑） |
| `:app-service:runtime` | 执行编排：RuntimeController、EnginePool、Watchdog 仲裁、kill 权威、`engines` 命名空间处理器 | `:domain` | 依赖 UI/Dialog 类、`com.autoscript.bridge..`（archUnit 强制，严于本表的历史约定） |
| `:app-service:scheduler` | 定时/Intent/事件任务、checkpoint 意图日志、runNonce 幂等 | `:domain` | 依赖 RunRecord 之外的引擎细节 |
| `:app-service:script-repo` | 项目/资源/脚本库、assets→filesDir 原子部署（tmp+rename+sha256 校验） | `:domain` | 直访 danger 权限 |
| `:app-service:permission-center` | 权限三态门禁、引导页、降级路径 | `:domain` | — |
| `:app-service:packager` | 模板 APK 改写、签名向导、加密资产注入 | `:domain` | — |
| `:domain` | **纯 Kotlin 领域：全部 SPI 接口 + DTO + 状态机 + 领域规则** | 无（std 仅） | 禁 Android 依赖 |
| `:bridge:java` | Kotlin Router、RequestRegistry(TTL)、HandleRegistry(generation)、EventBus、transports | `:domain` | 禁 UI |
| `:bridge:native` | C++：N-API addon 控制面（含 JNI glue、TSF 管理、node::Start）、`libnode.so` 装载 | 被引擎宿主进程引用 | 禁 Android 业务 |
| `:bridge:image` | C++：图像分析管线 addon（独立 so `libimgnative.so`，OpenCV 4.x，不依赖 node） | 被引擎宿主 + `:main` 分析器引用 | — |
| `:bridge:js` | npm workspace：TS facade SDK（`@autojs/*`）、RuntimeChannel、bootstrap loader、d.ts | 仅 npm 依赖 | 禁 Gradle 反向 |
| `:engine:node-process` | `:nodeN` 进程宿主：main.cpp、Node config、JNI 注册、桥服务端 | `:bridge:native` | 禁 Android SDK UI |
| `:engine:sandbox` | QuickJS 宿主进程（P1） | — | — |
| `:platform:capabilities` | a11y 服务/UiNodeTreeReader、MediaProjection、截图 FrameSource、输入通道（无障碍/root/adb/Shizuku）、`a11y`/`screen` 命名空间 handler + 挂载缝薄转接 | `:domain` + 系统 API | 禁服务逻辑；禁直连 `com.autoscript.bridge..`（挂载缝类型住 `:domain`，见 §12.2） |
| `:platform:system` | overlay、通知、datastore（SQLite）、shell、设备信息、zip、系统设置 | `:domain` | 禁服务逻辑；**仅空壳（build.gradle.kts + namespace，零源文件）**，§9.6 的实现尚未开始 |
| `:node-runtime-build` | **构建管线（不打包进 APK）**：Node 源码 recipe、NDK 编译、16KB 对齐门禁、产物 hash | CI 脚本 | — |

架构测试（archUnit）进 CI：验证「领域层零 Android import」「`:app` 不直连平台」「依赖方向无环」。

**例外不是开后门**：`:app` 碰 `:bridge:java` 只发生在 `com.autoscript.shell` 一个包；`:platform:capabilities` 挂 Router 只碰 `:domain` 的 `NamespaceHandler`。两侧的越界都由各自的 `ArchitectureTest` 量化执行，不是口头约定。

---

## 7. 桥接层设计（JS ↔ Native ↔ Android）

### 7.1 调用链与分层

```
[JS 侧]
api 包 (Promise/EventEmitter 封装)          ← TS facade，业务语义
   │
RuntimeBridge (单例)                        ← requestId 生成/关联、TTL、错误折叠
   │  调用: bridge.invoke('a11y.find', {...}, {ttl: 200})
   ▼
[N-API addon @autojs/bridge-native]         ← 每 message 一个 job
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

### 7.4 数据与对象生命周期
- **payload 编码**：Kotlin DTO ↔ JSON（结构化小对象）；大二进制（Bitmap/像素）**不走 JSON**：直接 `ByteBuffer.allocateDirect` → `napi_create_external_arraybuffer` + `napi_adjust_external_memory`（0 拷贝，一次性 buf 生命周期绑定）。
- **句柄（Handle）机制**：跨进程资源（UiObject/Image/MediaPlayer/Dialog）在 JS 侧是 `{gen, id}` 代理对象：
  - 内核持有 `HandleRegistry`：`id → NativeResource{ref, generation, tombstone}`。
  - 显式 `dispose()` + `FinalizationRegistry` 兜底；GC 时向内核发 `release(id, gen)`。
  - 任何操作若 `gen` 不匹配 → `ERR_STALE_HANDLE`（防「旧引用操纵新资源」竞态）。
  - 资源 `dispose` 后 tombstone 立即清除内核引用；销毁由内核单线程执行，杜绝并发 Dispose。
- **引用计数对象**（Image/MediaPlayer）用弱引用 + finalize 兜底；`Image.recycle()` 比 GC 优先。

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
错误目录（前 20 个中最关键）：`ERR_TIMEOUT`、`ERR_STALE_HANDLE`、`ERR_PERMISSION_DENIED`（能力未授权/被降级）、`ERR_SERVICE_DISABLED`、`ERR_SCREEN_LOCKED`、`ERR_BLACK_FRAME`（FLAG_SECURE）、`ERR_CAPTURE_DENIED`、`ERR_ENGINE_STOPPED`、`ERR_ENGINE_CRASHED`（进程死）、`ERR_NOT_IMPLEMENTED`（本平台不支持，如 child_process）、`ERR_INVALID_PARAM`、`ERR_FILE_NOT_FOUND`、`ERR_DISK_FULL`、`ERR_NOT_FOUND`（UiSelector 未找到 → 可选 `NotFoundError` 对齐 Pro v9）。
映射规则：`Java Exception → 分类 → AutojsError`，保留 `javaStack`，JS `instanceof` 可判。

### 7.7 性能关键路径（数量级目标）
| 链路 | 目标 | 设计 |
|---|---|---|
| 空 RPC（JS→:main→回） | p95 < 2ms | 直连 unix socket、零 JSON 二次解析、TSF 双队列 |
| 无障碍 `find → click` | 200ms 内 p60 / ~10ms 树读 | 紧凑索引树 + 按需属性 + 句柄（不全量序列化） |
| `captureScreen → findImage` | < 1s 且一次截图两次匹配 < 700ms | 屏幕帧→native 0 拷贝，模板匹配在 `libimgnative.so` |
| `findColor`（单人独立子图 1080p） | < 10ms | native 遍历 |
| `matchTemplate` 1080p | < 40ms | OpenCV TM_CCORR_NORMED + 降采样 |
| 紧凑树构建/传输 | < 15ms / 数十 KB | 预聚合属性，代价解析放"取用即取" |

---

## 8. 执行层设计

### 8.1 引擎抽象（`:domain`，纯 Kotlin）—— 已落定形态
```kotlin
interface ScriptEngine {                              // 实现在 :engine:node-process
  val id: EngineId
  suspend fun execute(run: EngineRunRequest): EngineRunReceipt
  suspend fun stop(): StopResult                      // 四步 quiesce 入口
  suspend fun kill(): KillCause                       // 仅 RuntimeController 有调用权（§4.1 kill 权威）
  suspend fun status(): EngineStatus
}
data class EngineRunRequest(projectId, scriptPath, args, runNonce, timeoutMillis)
data class EngineRunReceipt(runId, handle: HandleRef)
enum class EngineStatus { IDLE, BOOTING, RUNNING, QUIESCING, STOPPED, CRASHED }
sealed interface StopResult { Clean; TimedOut(partial) }
enum class KillCause { REQUESTED, WATCHDOG_HEARTBEAT, WATCHDOG_CPU, OOM, ENGINE_REQUEST }

interface EnginePool {                                // 实现在 :app-service:runtime
  val capacity: Int
  suspend fun acquire(request: PoolAcquireRequest): PoolAcquireOutcome   // Granted | TimedOut | Failed
  suspend fun release(handle: PoolHandle): StopResult
  suspend fun killAll(reason: KillCause)
  fun recycle(slot: PoolSlot)                         // 强杀后收归：槽位复位 + 还许可证（原子、幂等）
  fun stats(): PoolStats                              // capacity / free / busy
}
```
四条与早期草案的差异（都是落地后收敛的结果，写下来防止文档倒着改代码）：
- **无 `pause/resume`/`console: Flow`/`events: Flow`/`channel()`**：暂停未进 P0；控制台与事件走 §7.3 的 TSF 双队列 + EventBus 拉取，不建模成引擎侧 Flow（轮询式 Flow 会把「事件」伪装成「流」，丢失 TTL 与背压语义）；命名通道在 `:app-service:runtime` 的 `EnginesNamespaceHandler` 侧按 `channel/channelEmit/channelDrain/channelClose` 显式管理。
- **引擎是一次一脚本**（`execute(run)` 非 `start(session)`）：同槽位不并发两个脚本，会话身份 = `runId`。
- **`acquire/release` 收 `PoolAcquireRequest`/`PoolHandle`**而不是 `EngineSession`/`ExecutionHandle`：排队上限（`waitTimeoutMillis`）必须与请求同行，否则满池只能无限等。
- 实现：`:engine:node-process`（NodeFactory）、`:engine:sandbox`（QuickJSFactory），通过 **Provider/SPI** 注入；界面完全面向接口，双引擎可互换。

### 8.2 引擎实例模型决议（批判决议）
- **不做**「单 Node 实例多 engine/多 Job」——共享 context 的 `process.exit()`、全局变量、模块副作用全部泄漏（批判 2 反面教材）；「模块作用域隔离」被明确定为**假隔离**。
- **不做**「v1 用 worker_threads 做并发引擎」——手机端行为未验证（nodejs-mobile #130），且一个 worker 群共享进程=共享隔离边界。列为 P3 **实验性**特性，入口显式标「实验」。
- **做**：进程池 + 每脚本一进程。并发上限=池容量；超载任务进入队列（清晰的产品化语义，而不是偷偷并发）。
- 执行中的 slot 在 `:main` 持 FGS/绑定，池进程按内存采样动态缩容（占位 slot 空闲超时回收）。

**记账不变量（`FixedEnginePool` 强制，违反即池缩水）**：
- 许可证（公平 `Semaphore`）与 FREE 槽位 **1:1**；夺槽必须在 `stateLock` 临界区内、且**先于** `engine.execute` —— 否则 execute 的启动耗时就是窗口期，并发 acquire 会选中同一槽位（同一进程跑两个脚本）。
- 有证无槽 = 记账失真，立即还证返回失败，绝不吞证转死锁。
- **每条终结路径都必须成对归还「槽位 + 许可证」**：正常 stop/release 走 `quiesce()` 后还证；启动失败与调用方取消走 `recycle`；**强杀（killRun）也必须收归** —— 这是踩过的坑：只 `kill()` 不还证，`free` 与可领许可证永久错位，池容量缩水，表现为「引擎再不接活」。
- `recycle(slot)` 在池侧原子完成「状态复位 + 代次前进 + 还证」，幂等不超发；`PoolSlot.generation` 让过期句柄 release 时如实判定已净，绝不拆新占用者（§7.4 代次纪律）。
- 满池时排队上限来自 `PoolAcquireRequest.waitTimeoutMillis`；**桥接路径上 `engines.exec` 的上限 = payload `waitTimeoutMillis` 优先，否则请求侧 TTL**（§7.4 每次跨进程操作必有 TTL）。TTL 若不递进池，满池只剩「无限等」一条路，调用方只能自己取消，无法诚实回 `ERR_TIMEOUT`。

### 8.3 生命周期状态机（每执行单元）—— 目标态 vs P0 已落地

```
          ┌────────────────────────────────────────────────────────┐
          ▼                                                        │
  ┌────────────┐  start →  ┌──────────┐  心跳失联×N/CPU 风暴/OOM   │
  │  PENDING    │─────────▶│ RUNNING  │───────────────────────────▶│
  └────────────┘           └────┬─────┘    (看门狗触发 kill→下一态)  │
                                │ pause             resume          │
                                │ ◀────────┐  SU────  ┌───────────┐ │
                                │          └─────────│ SUSPENDED  │─┤
                                │      (多源计数>0)   └───────────┘ │
            stop/reason/崩溃     ▼                                  │
                          ┌───────────┐  排空超时   ┌────────────┐  │
                          │ SINKING    │──────────▶│ QUIESCED    │─┘
                          └───────────┘            └────────────┘
  一切终态: TERMINATED(done|crashed|killed|timeout) → 归档 RunRecord
```

规则：
- **SUSPENDED 用多源计数**（UI 页面、FGS 需求、诊断暂停……各自 `acquire/release`），计数归零才回 RUNNING；不是布尔标志。
- **看门狗判定只看 RUNNING**；SUSPENDED 不回度量（dispatchLag 只在 RUNNING 采样，防误杀合法暂停）。
- `SINKING → QUIESCED` 有容忍窗口（grace，默认 5s，可配置）：排空 in-flight（generation 匹配才算有效），窗口到未排枯则斩杀。
- 任何路径都不可能「停在 RUNNING 无归宿」：RUNNING 必须挂一个心跳 deadline，超时即进 SINKING。

**P0 已落地的收敛子集**（代码是事实来源，别按上图臆造）：
- `:domain` 的 `EngineStateMachine`（合法转移表 + `kill()` 归因：`REQUESTED → STOPPED`，其余原因 → `CRASHED`）**已真正驱动池侧**：`PoolSlot` 持一个状态机实例，与「占槽/回收」同生共死 —— 夺槽 `markBusy` → `BOOTING`，`execute` 拿到 `EngineRunReceipt` → `RUNNING`（`PoolSlot.markRunning`），`quiesce` → `QUIESCING → STOPPED`（stop 超时兜底杀掉则归 `REQUESTED`），`recycle`/`forceFree` 按传入 `KillCause` 归因（watchdog/OOM → `CRASHED`）后回收回 `IDLE`。槽位侧的三态投影 `SlotState{FREE, BUSY, QUIESCING}` 仍在（§8.2 记账要用），但不再与 `EngineStatus` 脱节。
- 为什么状态机挂 `PoolSlot` 而不是另建一张表：状态机必须与占槽/回收同生共死，分表就要处理「表里有行、槽位已 FREE」的孤儿；`FixedEnginePool` 的 stateLock 已保证读写与记账原子。非法转移抛 `IllegalStateTransition`（响亮失败，不静默修状态）。
- **状态对照已落地**（`RuntimeController.statusOf(runId)` / `runStatuses()`）：同时读宿主自报（`ScriptEngine.status()`）与池侧投影（`PoolSlot.status()`），分歧如实进 `RunStatus.drift`；`EngineWatchdog.Tick.drift` 把它带进每轮监督清单。**只报分歧、不改状态** —— 校准不是替某一侧抹平差异，而是让差异先可见。合法组合白名单：池 IDLE ↔ 宿主任意（已回收，宿主说什么都不算异常）、BOOTING ↔ 宿主 IDLE/BOOTING（execute 未返回）、RUNNING ↔ RUNNING、QUIESCING ↔ QUIESCING/STOPPED、池侧 STOPPED/CRASHED ↔ 宿主任意。宿主读不到（探针抛错/引擎已死）→ `host = null` 且**不算 drift**：那是「量不到」，不是「不一致」，混在一起会让真分歧被噪声埋掉。
- **仍缺**：差异只被报出，还没有**裁决方**（谁 drift 了就降载/重启/标记槽位坏，尚未定义）。真实现接上前这是唯一可持续的形态：宁可每轮报一条 drift，也不悄悄挑一侧当真。
- 上图里的 `SUSPENDED`（多源计数）与 `PENDING` 在 P0 **均不存在**：`EngineStatus` 枚举里没有 SUSPENDED，`awaitCompletion` 只把 RUNNING 判活（看门狗口径一致，见 §8.4）。**任何依赖 SUSPENDED 的设计（暂停恢复、诊断暂停计数）仍然没有代码基础。**

### 8.4 看门狗（三路，防死循环/僵尸/饥饿）
- **心跳**（数据面，周期 ~500ms，携带自回事务序列号）：连失 K 次 → 重启判定；心跳与操作 TTL 互补——**await 的 RPC 有 TTL，整体执行有心跳**。
- **CPU 外带差分**（`:main` 独立线程读 `/proc/<pid>/stat` utime+stime 差分，**不依赖 Node 合作**）：单核持续 >95% 超过阈值（默认 30s，可配）→ 杀。防 `while(true)`/Promise 风暴这种「心跳还活着但永不放行」的形态。
- **内存**：RSS 超阈值（分级配置，池缩容信号）→ 降载警告，连续超阈 → kill + archive。
- 看门狗**不作为业务**：只输出「恢复建议」（重启/重试/降级），不自动无人值守自愈（§1 诚实原则）。

**P0 已落地**：`:app-service:runtime` 的 `WatchdogPolicy` 是三路纯判定（`WatchdogSample{pid,status,heartbeatMillis,cpuPercent,rssBytes} → Healthy | Kill(cause, reason)`），默认阈值：心跳 500ms × 连失 3 次、CPU ≥95% 持续 30s、RSS ≥512MB；只对 `RUNNING` 判活；`RuntimeController.judge()` 委托它，裁决落点 = `killRun`/`killAll`（kill 权威 §4.1）。
**采样已落地**（`ProcessMonitor`，`:app-service:runtime`）：`/proc/<pid>/stat` 与 `/proc/<pid>/status` 的读取 + 折算，产出同一份 `WatchdogSample`，交给 `WatchdogPolicy` 判定。诚实口径写死在实现与单测里：CPU 分子 = 两次 `utime+stime` 差（jiffies → ms），分母 = 调用方给的墙钟间隔，**不折算单核**（多核满载必须看着就 >100%，否则漏杀 Promise 风暴）；`comm` 含空格括号时从**最后一个 `)`** 之后切字段；首采样 / 换 pid / 时钟回拨 / 计数回绕一律回 0.0%，不给假差分；`/proc` 不可读 → 整份样本回 null（按「无法度量」处理，不猜健康），`status` 读不到只丢 RSS 这一路，不作废 CPU 样本。**本类只采样不裁决**（裁决仍归 `WatchdogPolicy`，kill 仍归 `RuntimeController`，runId→pid 归属表仍归 `:app`）。

**调度循环已落地**（`EngineWatchdog`，`:app-service:runtime`）：§8.4 的三路分工终于有了周期性调度者 —— `RuntimeController.watchAnchors()` 交出在途执行的 `WatchAnchor{runId, receipt.pid}`（**pid 取 `EngineRunReceipt.pid` 快照，不是 `ScriptEngine.pid` 当前值**；槽位复用后两者会分叉），`ProcessMonitor` 按 pid 分别记账采样本，`RuntimeController.judge()` 裁决，`Kill` 落 `killRun(runId, cause)` —— 判据、采样、执行三方仍是三个类，调度者只负责「到点把三者接起来」，不自己长判定口径。`AppShell.assemble` 交出 watchdog 实例与 `ProcessMonitor`/`heartbeatMillis` 注入缝，`startWatchdog(scope)` 之前不转。
- **采样周期 = `heartbeatIntervalMillis`**（`WatchdogPolicy` 那条因此从 private 变 public）：周期长于心跳阈值会把活引擎判死（500ms×3 的阈值配 3s 轮询 = 假阳性），controller 的这份 policy 经 `RuntimeController.watchdogPolicy()` 原样交给 watchdog —— **只有一份阈值**，不在装配层另造一个。
- **pid 终结即忘**：无论正常结束、被 watchdog 杀掉还是本轮采样后不在途，`EngineWatchdog` 都调 `ProcessMonitor.forget(pid)`。Linux 复用 pid，不遗忘等于让新进程背旧 CPU 基线（虚高 → 误杀）；pid 落到别的 runId 时 CPU 历史整段清零，同理。
- **不另建 pid→runId 表**（§8.4 原口径不变）：归属表只有一份，就在 `RuntimeController` 的在途账里。`:app` 侧再抄一份必然漂移（stop/kill 路径不止一条），`watchAnchors()` 是它的只读投影。

**心跳一路已接线**（§8.4 缺口② 补齐）：`HeartbeatLedger`（`:app-service:runtime`）是心跳的宿主侧收单方，`RuntimeController.heartbeat(runId, seq)` 是它的桥侧入口，`RuntimeController.heartbeatMillis(runId)` 是看门狗的问讯口 —— `EngineWatchdog` 缺省就问 controller 那份账本（`AppShell.assemble` 的 `heartbeatMillis` 缺省 null = 装配时接真账本；显式传 `{ null }` = 明示这一路不接，看门狗如实记 `Tick.noHeartbeat`）。JS 侧 `engines.heartbeat {runId,seq}` + `startHeartbeat(runId)` 定时打点（`unref` 定时器，不保活事件循环）。
- **序号即真伪**：账本只认**递增** `seq`。同/旧 seq 一律拒收（只累计 `staleBeats()`，不刷时间戳）—— 否则宿主张力下积压的旧心跳会把一个**已经死了**的 run 一直喂成活的，那正是心跳这一路要抓的形态。
- **从未打点回 null，不回 0**：0 会被当成「刚刚打过」，失联判定永不触发；null = 量不到，看门狗据此记 `noHeartbeat`。
- **与 run 同生共死**：`stop`/`killRun`/`killAll`/`settleDone`/`settleKilled` 五条终结路径全部 `forget(runId)`。不遗忘 = runId 复用时新 run 背上一段「假年轻」，失联判定被推迟到下一次自然打点。
- **仍不伪造**：拿 watchdog 自己的轮转周期当心跳依旧是禁止的 —— `while(true)`（心跳活着、CPU 打满）只靠外带差分抓得到。

至此 §8.4 三路判据、采样、调度、心跳打点全部闭环。**仍未接线**：`ScriptEngine.pid`/心跳的**原生宿主**（`:engine:node-process` native 侧尚未把 pid 与心跳送出来，见 §19 native/NDK 空壳清单），因此真机上当前生效的是「宿主不给 pid → noPid」「宿主不打点 → noHeartbeat」两条量不到路径 —— 这由 `Tick` 的三个清单如实区分，不是一个笼统的 `unmeasurable`。

### 8.5 崩溃恢复与幂等（checkpoint 意图日志）
- `:main` 的 scheduler 持久化 **意图日志（intent log）**：`RUN_START(projectId, entry, runNonce, scheduledAt) → …execute… → COMMIT(result)` append-only（SQLite，启动即回放）。
- **恢复只跟随 COMMIT**：进程/手机重启后，未 COMMIT 的 run 视为「未完成意向」→ 重新入队，但生成**新的 runId + 保留 runNonce**；执行体用 `runNonce` 做**幂等键**（外部副作用目标幂等，如「只发一次」的通知 id、datastore 原子键），杜绝重复业务副作用。
- **rerun 新 RunRecord**（每次重跑都是新 runId）——满足批判「resume=新 runId」语义；「断点续跑」只对纯内存任务可选，涉及副作用任务默认不允许自动续。

**归档入口（已落地契约，§8.5）**：两套 runId 是「一个真值的两个投影，必须成对写入」。

| 侧 | 身份字段 | 寄存器 |
|---|---|---|
| 意图日志（scheduler） | `intentRunId`（`IntentRun.runId`） | intent log |
| 引擎运行记录（engine） | `engineRunId`（`EngineRunReceipt.runId`） | `RunRecord(id)` |

`:domain` 的 `EngineRunLink(intentRunId, engineRunId)` 是关联契约；`RunArchive` SPI 是引擎侧档案（`put(record, link)` / `record` / `link` / `recordsOfIntent` / `recordsOfProject` / `unfinished`）。纪律：终态（`SUCCEEDED/FAILED/CRASHED/CANCELLED`）append-only，**不可改写、不可复活**，违反必须响亮失败而不是静默吞。只写一侧 = 孤儿记录（「引擎在跑而任务中心查不到」或反之），`DispatchReport.link` 在门禁拒绝/排队超时/启动失败时如实为 null。接线在 `:app` 的 `ControllerRunDispatcher`（拿到 Receipt 后生成 link）+ `AppShell`（scheduler 持 `RunArchive`）。

### 8.6 调度系统
- 触发源五类：`定时(cron/alarm) `、`Intent/广播`、`事件(无障碍/通知)`、`用户点击`、`引擎内部 engines.exec`。
- **SchedulerProvider SPI**：同一接口后 P1 可切 `WorkManager` 之外的实现（保活场景自持 alarm + 注册 receiver）。触发→拉起引擎进程→注入 API→归日志。
- **守时语义诚实化**（批判 11 定案）：设备**亮屏 + 解锁**是保底契约；预热闹钟 `scheduledAt - 60s` 先拉起进程（引擎进程需时 ~1s），axexact 闹钟失败时降级到 setWindow 并在 UI 标注「可能偏差」。**熄屏任务**＝任务显式声明三态之一：`screen.on`(需 wakelock+确认)/`screen.any`/`screen.off`(禁 MediaProjection，只允许无障碍+网络)。
- 触发时若引擎池满 → 排队，绝无静默丢任务（日志+UI）。
- **排队上限由投递方给**：`ControllerRunDispatcher` 的 `queueTimeoutMillis`；到期 → `RunOutcome.Cancelled`（"排队取消"口径：未获槽、未执行，link 为 null）。
- **P0 已落地**：满池排队**默认有界**，不再有"默认无限等"这条路。`queueTimeoutMillis` 显式覆盖优先；不传则按触发源分级取默认上限（`ControllerRunDispatcher.DEFAULT_QUEUE_TIMEOUTS`）：`ENGINE_INTERNAL` 15s（满池下的跨引擎调用是"持有者等后来者"的嵌套形态，必须最先爆，否则变跨引擎死锁）、`USER_CLICK` 10s（人盯 UI，等不及就如实 Cancelled，不让按钮原地转圈）、`INTENT_BROADCAST`/`EVENT` 60s（外部涌入本应容忍排队）、`TIMED` 120s（守时任务已承诺"亮屏+解锁保底 + 可能偏差"，2 分钟只为满足铁律 3，不追求抢跑）。分级表是**注入缝**（构造函数参数），装配层可换成自己的口径；`queueTimeoutMillis` 传 0 视为漏配，构造即 `IllegalArgumentException`——0 等于"永不允许排队"，与"绝不静默丢任务"相反。
- **P0 已落地（deadline 记账）**：`PendingRun.deadlineMillis` + `isExpired(now)` 记下"本次投递的到期时刻"，与 dispatcher 的排队上限**同源但不同职**——dispatcher 那侧管"在途排队等不等得起"，deadline 管"宿主重启之后这条意向还值不值得投"（崩溃恢复面对的是另一件事：进程死过一次，用户早走了/外部事件早凉了）。期限写在 `IntentRun.deadlineMillis` 上随 RUN_START 行落盘，`reopen` 原样带到新行（恢复重投不得变期限，否则同一意向两套到期口径）；`Scheduler.onTrigger` 按 `排期时刻 + deadlineFor(触发源)` 填，`recoverUncommitted` 遇过期意向照样 `reopen` 封口记账，但**不再 dispatch**，直接 COMMIT [RunOutcome.Cancelled]（`RecoveryRecord.expired` 标出，任务中心按 runId 读到"为何没跑"——绝不在恢复路径里静默跳过）。两处口径同源由装配层保证：`Scheduler` 的 `deadlineFor` 与 `ControllerRunDispatcher.queueTimeout` 默认同喂一张分级表（`DefaultDeadlines` 与 `DEFAULT_QUEUE_TIMEOUTS` 数字一致，前者是缺省值不是契约，装配层可各自覆盖）。
- **仍待覆盖**：调度链路**别说"无悬挂风险"**——deadline 只关掉了"重启后重投过期意向"这一路，引擎侧 `waitCompletion` 超时不发起的场景还没人管（在途 run 没人收尾时 watchdog 是唯一兜底）。
- **P0 已落地（Android 触发侧）**：`AlarmSchedulerProvider`（`:app` 装配层）把 `SchedulerProvider.registerTrigger` 翻译成闹钟——预拉提前量 `wakeAheadMillis` 是契约字段（60s，测试与调用方同一份值，不藏常量）；**提前量只向前推、不向后扯**（排期已到即夹到当前时刻，ROM 对负延迟处置不一）；`canScheduleExact` 为假时降级 `setWindow` 且**记账**（`degradedTasks()`，`taskId → 排期时刻`），能力中心据此标注「可能偏差」——**不静默降级**。框架调用（真 `AlarmManager`）在 `AndroidAlarmPort`，本类**无判断**：taskId → `KeyStableHash` 定 requestCode（同 taskId 恒同，重复 arm 是替换）、`setExactAndAllowWhileIdle`/`setWindow` 两个调用点、取消 = `alarmManager.cancel` + `pendingIntent.cancel`（两个都要）。`PendingIntent` 在 API 31+ 必须 `FLAG_MUTABLE`（系统要填 `EXTRA_ALARM_*`）。回投侧是**静态注册**的接收器 `AlarmReceiver`（精确闹钟响时进程可能已被 ROM 杀掉，`registerReceiver` 收不到）走 `goAsync()` 在广播窗口内把 taskId 经 `AlarmDispatch` → `SchedulerAlarmRoute` 送回 `Scheduler.onTrigger`（TIMED 来源 + 闹钟真实排期），于是 runNonce/意图日志/dispatcher 口径与手动触发完全一致。**装配前/后的漏投不静默丢弃**：没接路线的闹钟进 `AlarmDispatch.missed()`（同一 taskId 只留最新一条，`drainMissed()` 清账），`AppShellApplication.missedAlarms()` 供能力中心如实呈现「闹钟已响但调度未就绪」。
- **P0 已落地（屏幕门禁的生产实现）**：`AndroidScreenGate`（`:app`）——`SCREEN_ON` 在两个系统查询缝（`interactive` = `PowerManager.isInteractive`，`deferWakeLock` = 持锁方）任一为假时**如实 `Deny`**，不降级成「锁屏也跑」（那条路径的表现是「任务成功、实际什么都没发生」）；`SCREEN_OFF` 先经 `ScreenOffGuard` 收起画面类能力再放行（无障碍 + 网络在锁屏下真实可用）；`ANY` 放行。两条缝的值由 JVM 单测注入，判断逻辑因此可测而不必 Mock 框架对象。**`AllowAll` 与 `AndroidScreenGate` 在 `ANY` 上必须同结论**（`AndroidScreenGateTest` 有断言守着），否则同一条任务在单测里放行、真机上被拒，差别只在现场暴露。

### 8.7 保活与电源
- `:main` 持 **specialUse FGS**（`onCreate` 启动，`TYPE_SPECIAL_USE` 勾选 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE="automation"`，无超时）。
- 电池优化白名单、精确闹钟、开机启动、后台 Activity 启动豁免(**BAL**：仅允许 overlay 可见窗口路径/notification 触发路径)、自启动被 ROM 关闭——**全部入 PermissionCenter 三态门禁**（未授权=黄，被 ROM 杀=红且给跳转指引）。
- 长跑脚本自身需要**WakeLock** 时用 `power_manager`（引擎进程请求 → `:main` 对应 FGS 加唤醒锁的 acquire/release，配套超时自动释放）。
- **当前 `deferWakeLock` 缝的诚实状态**：`AndroidScreenGate.of(context)` 注入的持锁判定恒真（真 wakelock 的 acquire/release 随 FGS 路径落地）。这不是漏洞而是**明写的待接**——熄屏 + `screen.on` 的任务现在会因 `interactive` 为假而 `Deny`，不会出现「锁也没拿却照样跑」。

### 8.8 屏幕语义（截图直连 §9.2）
`ERR_SCREEN_LOCKED`/`ERR_BLACK_FRAME` 显式化：MediaProjection 在 keyguard 下黑帧、a11y 在锁屏无可用窗口 → 引擎收到的是**分类错误而非黑图**，脚本可 try/catch 策略分支。

---

## 9. 自动化能力设计

### 9.1 无障碍通道（`accessibility` / `ui_selector` / `ui_object`）
- **服务在 `:main`**；EventDelegate 面向桥暴露**节流拉取式**事件流（按 `seq` 游标，批量取，背压到数据面 TSF）。
- **紧凑索引树**：节点序列化只含基础索引（id、className、关键 attr 指针），属性**按需二次查询**；全树 JSON 序列化性能杀手已从协议层面排除。
- 查找：`UiSelector` 构建 → 树读 + [可选] 属性谓词过滤，`findOne/findAll/findOneOf`（Promise），超时 `ERR_NOT_FOUND/NotFoundError`。
- 操作：`click/longClick/scroll/setText/copy/paste` 走无障碍 Action；手势 `dispatchGesture`（canPerformGestures）。
- **句柄代理**：JS 侧 `UiObject` = 代理对象（§7.4），操作带 generation，控件已离开窗口树 → `ERR_STALE_HANDLE`。
- 窗口树：`window('modal/active/…)`、`UiObject.window`、event 监听（`EventEmitter`）。
- 全链路如实时树可能加速：惰性属性化已内建在索引树设计中。
- **已落地（Kotlin 侧）**：`A11yNamespaceHandler`（方法表与 payload 见该类 KDoc）+ 内存窗口树/输入替身，共 53 项 JVM 单测；**Kotlin 侧 `waitFor` 读的载荷键是 `conditions`**（与 `findOne` 同构；轮询等待是宿主责任，内存树是单次快照，`timeout/interval` 只透传回显，不伪造等待）。JS facade `a11y.ts` 的 `waitFor` 已对齐：发 `conditions`（曾发 `selector`，会在白名单外字段上回 `ERR_INVALID_PARAM`——已修，两侧同构），其余方法键早已对齐。

### 9.2 截图与图像管线（`media_projection` / `image` / `@autojs/opencv`）
```
FrameSource (SPI)
  ├─ AccessibilityScreenshotSource  API34 takeScreenshotOfWindow · 333ms 节流 · 默认
  └─ MediaProjectionSource          会话式 · createScreenCaptureIntent→同意→FGS(type mediaProjection)→createVirtualDisplay
       └─ Surface → ImageReader(maxImages=2~3 对象池) → Frame 进入 libimgnative.so
            └─ 灰度/裁剪/缩放/旋转/找色/模板匹配/特征(ORB)/颜色查找 — 全 native, 0~1 拷贝
```
- 截图对象生命周期：JS `Image` 句柄 → native 帧句柄；`recycle()` 显式 + finalize 兜底；`dispose` tombstone 协议同 §7.4。
- `FLAG_SECURE` → 分类错误（§7.6），不返回黑图（让脚本可判断）。
- **已落地（Kotlin 侧）**：`ScreenshotSource`（333ms 节流 / generation=1 单帧句柄 / 会话 open-close）+ `ScreenNamespaceHandler`（`capture/recycle/startCapturer/nextFrame/closeSession`）。
- MediaProjection **会话语义**：`capture()` 一次性授权会话（API34 每会话确认）；`reconnect` 不自动重试授权，由 PermissionCenter 引导用户重授权。

### 9.3 输入通道（`root_automator` / 手势）
`InputProvider` SPI 三实现：无障碍手势（默认）/ root `sendevent`（root 设备自选）/ Shizuku-ADB（可代理 dev `${i}` 事件）。统一 `touchDown/Move/Up` + 手势 DSL。root 能力分级进 PermissionCenter，无 root 不降级渲染为禁用（不假装可用）。

### 9.4 悬浮窗 / UI 宿主
- `floating_window`：`TYPE_ACCESSIBILITY_OVERLAY`（可信窗口易保持）＋ `SYSTEM_ALERT_WINDOW`（普通）；运行时权限 checkbox 进能力中心。
- 脚本 UI：JS 声明 XML 布局 → 桥传 `:main` 渲染原生 View（`UiHost` SPI）；`ui_web` → WebView + JS 桥（双向事件回 Node）；`ui` Activity 方式独立宿主 Activity（BAL 限制内，仅当可见/继承时启动）。
- 事件回投（点击/输入/页面生命周期）→ RuntimeChannel → JS 侧 `EventEmitter`。

### 9.5 权限与能力中心（three-state 门禁）
统一 `CapabilityStatus = GRANTED / DEGRADED / DENIED`：
- **GRANTED**：系统授予且当前可用（含会话型 MediaProjection 已激活）。
- **DEGRADED**：可降级但受限（如 a11y 树只能节流读、闹钟降 setWindow、无 root、BAL 限制、电池未豁免、ROM 自启被关）。
- **DENIED**：被用户/系统拒绝，操作抛 `ERR_PERMISSION_DENIED`。
- 能力中心 UI：枚举所有能力 + 当前三态 + 一键跳转系统页 + 降级说明；`PermissionFacade` 是唯一的权限入口（模块不直接查 `Settings`/`ActivityCompat`，可 Mock）。

### 9.6 数据与存储（`datastore` / `settings` / `zip`）
- `datastore` = SQLite-backed KV + serializer 适配（JSON/native 对象/byte），事务语义；同步 importer 仅供纯内存。
- 脚本文件目录：`files/scripts/<projectId>/` 标准化；要求 `assets→filesDir` **原子部署**（tmp 写入 + sha256 校验 + rename 替换），防止半截断电文件。
- `shell`：独立 `Shell` 实现（root 或 adb），Promise 封装；child_process 副作用限制如实上报。

### 9.7 OCR（P1）与插件（P2）
- `OcrProvider` SPI：P1 内置 MLKit 插件基准实现（可下载模型）；插件以独立 `:plugin:*` 模块 + 清单注册，native addon 走 `:node-runtime-build` 交叉编译管线（arm64 `.node`）。
- 插件加载: P2，plugin.json 声明 require 钩子/资源/权限；市场脚本不可加载任意插件（白名单）→ 引到 QuickJS 沙箱子集。

---

## 10. npm 支持（包管理与依赖生态）

> 硬性需求「必须支持 npm」的设计超纲部分。核心矛盾：Node-on-Android 无 `child_process`，而 npm CLI 重度依赖 spawn。以下方案把「零 spawn」从 workaround 变成**官方默认语义**。

### 10.1 总体策略 —— D（混合）

**脊梁：vendored 真 npm CLI（npm 12.x 系，要求 Node≥24.15，由 24.21.0 满足）在专用安装会话进程内「进程内执行」。**

- **零 spawn 是实证事实**：`npm install` 的实质 = `@npmcli/arborist reify()` + pacote 下载/解包/链接；本机 strace 实测 `npm install --ignore-scripts` 全程 **0 次 execve**。纯 JS 生态（axios/dayjs/lodash/cheerio/ws/express ≈99% 用例）根本不需要子进程。
- **用真 CLI 而非重造轮子**：lockfile v3、audit、`approve-scripts`、`replace-registry-host`、`--prefer-offline` 免费获得且可审计。npm 12 默认「拒绝全部 lifecycle + allow-git=none + allow-remote=none」，把 child_process 缺失从 workaround 变成**官方默认语义**。
- **三通道合一**：B（零 spawn 编程式）作 P0 主线；A（spawn 桥）作 P1 升级通道（批准后脚本/`npm run`/`npm exec`）；C（离线 bundle + 精选 tarball 种子）作首发与离线通道。
- 否决纯 B（丢 CLI audit/approve/config 语义，多维护一层）、纯 A（P0 依赖未经验证的真子进程，OEM exec/SIGKILL-only/内存峰值风险最高）、纯 C（桌面预装无法满足「用户自主安装」的硬性需求）。
- **QuickJS 关系**：npm 是 Node 高信任层的能力；`:sandbox` 白名单**不含** `auto.npm` 与 spawn 桥，冷启动即缺失、不可降级（能力中心如实显示「沙箱不支持」）。
- **process.exit() 边界**：安装长任务与 `process.exit` 风险由专用安装会话进程吸收，与用户脚本引擎池隔离（`slotTag='npm'`，heapCap 列见 §10.6）。

### 10.2 调用链与存储布局

```
用户/IDE「安装」按钮 · auto.npm API · 打包内嵌依赖
  → :main InstallCoordinator（全局唯一安装调度器）
      门禁：项目信任分级 / CapabilityMask={fs-write,network} / per-project 互斥锁
            / 磁盘 free≥500MB 预检 / 项目+全局配额(80%黄·100%拦) / 惰性脚本审批检查
      后台合并：走 SchedulerProvider 统一调度；强停后持久化队列恢复未完成安装
  → EnginePool.acquire(slotTag='npm', maxOldSpace=§10.6) 拉起专用 :nodeN 安装会话
  → 顶层脚本执行 node <filesDir>/npm/bin/npm-cli.js <install|ci|uninstall|ls|prune|dedupe|audit>
      --cache <cacheDir>/npm-cache --prefer-offline   （npm12 默认 allowScripts=none 等）
      + 强制注入 child_process 拦截 shim（非批准路径 spawn 硬失败 ERR_NPM_SPAWN_BLOCKED）
  → 进度/警告/审批事件经 TSF 双队列 JSON-RPC 回 :main → UI 渲染
  → post-check（lock 验签 / hasInstallScript 告警 / 防篡改比对）→ 四步 quiesce 回收槽
```

存储布局（**分层到不同生命周期目录**，整改自批判「状态单点系于 filesDir」）：
- `files/scripts/<projectId>/`：`package.json`、`package-lock.json`(v3)、`node_modules/`、`.npmrc`（项目级）。⚠ `filesDir` 所在分区文件系统由厂商决定（ext4/f2fs 皆有）——f2fs+eMMC 纳入真机红测矩阵，bin-links/符号链接/20k 小文件写方差按最差形态设计超时。
- `files/.autojs`（**App 私有、安装会话只读、HMAC keyed 于 :main**）：`approve-ledger.json`（审批记录，条目绑定 `pkg+版本+脚本内容哈希`，新版本必须重新审批）、`lock.sig`、`install.journal`（事务日志）、`install-history`（审计）。
- `cacheDir/npm-cache`（**系统可自动清，损失可接受**）：npm 内容寻址缓存 `content-v2 + index-v5`（非 SQLite）；`cacheDir/npm-cache-seed`：精选 tarball 种子（axios/dayjs/lodash/cheerio 等 ~5MB），首启播种。
- `files/npm/`：vendored npm CLI（assets→filesDir 原子部署 tmp+sha256+rename；首启/升级落盘）。
- `files/offline-bundles/<bundleId>`、`files/npm-import/`：离线 bundle / 本地 tarball 导入区。
- registry 配置：项目 `.npmrc` → `files/.npmrc`(userconfig) → `NPM_CONFIG_REGISTRY` env；默认 `registry.npmmirror.com`（实证存活；可切 npmjs/华为/腾讯），`replace-registry-host=npmjs` 使 lockfile 跨 registry 可用；代理 `Settings.Global.HTTP_PROXY` → 引擎 env `HTTP(S)_PROXY`。

### 10.3 spawn 三层策略与不可行边界

| 层 | 内容 | 阶段 |
|---|---|---|
| **T0 零 spawn**（P0 承诺面） | npm12 默认拒绝全部 lifecycle + `--no-audit --no-fund`；install/ci/ls/dedupe/prune/uninstall/audit(在线)/cache 全进程内；bin-links 走纯 fs（sdcard 才需 `--no-bin-links`） | P0 |
| **T1 批准后脚本**（P1） | per-package approve 后的 lifecycle/`npm run`/`npm exec` 触发 spawn，被 `--require` 注入的 child_process shim 拦截 → 桥 → `:main` 沿 EnginePool 同路径拉临时引擎执行；stdio 走 TSF 二进制数据通道做假管道；**shim 直接拒绝 `detached:true`/`setsid`**（ERR_PERMISSION_DENIED + 可操作话术）；脚本宿主独立 pgrp，回收顺序 TERM→超时→SIGKILL 且 `kill -- -<pgid>` + `/proc` 同 UID+PPID 链二次收割；stdio write-end 由桥独占持有（CLOEXEC 注入）+ EOF 看门狗，宿主退出即强制 close 全部挂接 FD 防孤儿占管 | P1 |
| **T2 sh 包裹与 PATH `node`**（P1红测项） | 需要「node 可执行身份」= node-shim PIE（dlopen libnode.so + node::Start，几十~200KB，同源同 16KB ELF 门禁），经 jniLibs 交付，PATH 注入；`sh -c` 由 `:main` ProcessBuilder 起 `/system/bin/sh`；targetSdk36 设备若 app 数据区 exec 被拒 → **降级 T1-only + UI 明示** | P1 |

**明确不可行（写死拒绝、报可操作错误而非假成功）**：
- `git:` 依赖 → `ERR_NOT_SUPPORTED`（install 入口即拒，引导本地 tarball 导入）；
- `node-gyp` 设备端编译 → `ERR_NOT_IMPLEMENTED`（设备无 NDK/编译器，引导**打包期 NDK 交叉编译 / prebuild-install 预编译 `.node`**）；
- `fork`/`cluster` → `ERR_NOT_IMPLEMENTED`（并发用 engines 进程池）；
- 从 app 数据区任意 exec 二进制（W^X）→ 拒绝（原生 bin 走「代码签名 exec」独立通道，见 §10.11 P3）；
- `npm exec` 非 node 二进制 → 仅走既有 `auto.shell`(root/adb) 能力且 Node 高信任才可，QuickJS 一律拒绝。

### 10.4 事务化安装与崩溃自愈（整改自批判 android-runtime F1）

> 批判指出：安装是原地、非原子写 node_modules，而 Android 上进程死亡是常态；半解包 + 坏符号链接会让后续 ci/install 在坏树上反复 EINTEGRITY。

- **reify 目标 = `node_modules.part-<ts>` 暂存目录 → 完成校验 → rename 到位**；写入 `.autojs/install.journal`（begin/commit/fail 三段）。
- 启动与每次安装前置扫描 journal：检测 incomplete → UI 提示**一键回滚重建**（按 lock 走 `ci --offline`）。
- 安装会话挂 **specialUse FGS（副类型 automation）+ oomAdj 前台豁免**，并纳入 `:main` 看门狗/kill 权威（RuntimeController 语义）而非独立存活；`:main`↔session 心跳断 → 新会话接管收尾。
- Doze/强停：安装统一走 SchedulerProvider；Doze 下降级为「仅解析+下载+离线物化 cache，reify 写 node_modules 推迟到前台机会」；网络失败返回 `ERR_REGISTRY_UNAVAILABLE`（可诊断）而非挂死。
- 产品规则改为「**熄屏仅允许下载物化，reify 需前台或 specialUse FGS 持有时**」——而非一刀切「熄屏不安装」。

### 10.5 供应链安全（带外信任锚 + 审批人机分离）

1. **Lockfile-first + 带外信任锚**（整改自批判「TOFU 自签」）：
   - **pin npm 官方 ECDSA 注册表签名公钥**；首装前验 packument/tarball 签名。
   - 镜像不支持签名端点（npmmirror 暂无）→ **多镜像 integrity 交叉校验**（npmmirror+npmjs 对同一 spec 的 extract 哈希一致才接受）。
   - 设备端首生锁标记「来源未校验」**降信任级 + UI 明示**；正式链路走桌面/CI 离线生成 + 来源证明（sigstore 可选）。
   - App 以应用密钥对 lock 做 HMAC/ECDSA 签名（`lock.sig`，私钥入 **Android Keystore**；密钥丢失 = 显式「安全降级」状态而非假装模型成立）；`npm ci` 前验签；市场/第三方项目**只开放 npm ci**（无裸 install）。

   **已落地（裁决与处置，`:app-service:packager`）**：`NpmRegistryVerifier` 是三分裁决而非布尔——`Agreed` / `Disagreed` / `Unverifiable`，调用方必须能区分「验过且一致」与「没能验」（否则 UI 只能画同一个绿勾）。第二意见恒为 `registry.npmjs.org`，**不随用户首选变**（首选容易被自己改成 npmjs，那就成了自己跟自己比）。处置写死在 `InstallCoordinator.crossCheckRegistry` 一处：`Disagreed` → `ERR_REGISTRY_UNAVAILABLE` + 两家版本/完整 integrity，安装会话不起、事务不建、拒本身入史；`Unverifiable` → **不拦安装**但发 `InstallEvent.Warning(TRUST_DOWNGRADED)` + 入史「来源未校验」（副镜像不可达 / 版本只在一侧 / 无 integrity 锚点都是「没验成」而非「验出问题」，当分歧拒掉会把镜像同步窗口期误判成攻击）。判定对象是 `dist.integrity` 而非两个 tarball 的字节（结论等价、少一倍下载）。JS 侧 `auto.npm.onWarning` 的 `kind` 联合与 `InstallEvent.Kind` 五值逐字对齐，宿主经 `feedWarning` 显式投递；未知 kind 抛错而非静默丢弃（契约漂移即响亮错误）。诚实边界：**不**回答「镜像 hardcode 的摘要是否真由上游产生」——那要 sigstore/官方签名端点，记为未决项。
2. **审批 = 人的动作（人机分离）**（整改自批判「程序化绕过」）：
   - `approveScript`/`runScript`/`exec` **不允许脚本直调**——脚本只能发出 `ApprovalRequest` 排队，等 UI 弹卡人工二次确认（可配生物特征），脚本侧限流 + 全量审计。
   - 审批记录绑定 `pkg+版本+脚本内容哈希`，版本升级必须重新审批；审计日志（approve/registry 变更/lock 重签）落 App 且可导出。
3. **恶意包防线（缺省启用）**：
   - 默认**拒绝全部 install 脚本**（对操纵无障碍/root 的自动化脚本是最大投毒面）；postinstall 包装完即出「脚本未运行」显式警告，**禁止静默**。
   - 在线 `npm audit` + `audit signatures`（ECDSA）；离线捆绑 OSV 库 + `osv-scanner --offline`；签名端点不可用**绝不静默降级**。
4. **低信任边界**：T1 脚本执行会话一律**独立最小 CapabilityMask**（仅 npm 目录 fs+network，无 a11y/shell/root），与用户脚本会话物理区分；UI 明示「审批 postinstall ≠ 授权自动化能力」；高信任须**可验证签名 + 用户显式升级**（不用软签名）。
5. **QuickJS 白名单库独立 vendored**：冻结版本 + 独立 integrity + 只读区（npm 可写目录之外），禁止从 npm 目录/共享 store 解析；未来共享 store 必须持「store 内容哈希 == 各项目 lock 哈希」的加签映射校验。

### 10.6 轻/重操作拆分与资源预算（整改自批判「过度设计+欠定义」）

- **重操作**（全局安装会话互斥排队）：`install / ci / uninstall / audit / importOfflineBundle` 等变更+网络操作。
- **轻操作**（`:main` Kotlin 直读实现，零 Node 进程、零全局互斥）：`list/ls / config / storage / prune 报告`——目录遍历算尺寸（不用 `du`），读 `.npmrc`。
- **内存预算协商化**（不按固定 384MB 封顶）：npm 会话 `--max-old-space-size` 从框架池预算反推（常规 192MB / 低内存 96MB）；packument 解析**流式化**（大 lockfile 依赖图不全量驻留 JS 堆）；会话 RSS 记账，超阈值先降载再放弃。
- **准入/驱逐优先级**：脚本槽优先；安装会话可排队且 UI 展示 ETA；**reify 中禁止被自适应内存回收**；quiesce 只在命令间界发生；池满或预算不足 → 显式 `ERR_NPM_LOWMEM` + 引导离线 bundle/桌面打包（替代被内核杀）。
- 全局同时至多一个 npm 安装会话；per-project 串行；安装期间对项目 node_modules 加写锁 + 默认建议「脚本结束后安装」（用户强制时显式风险确认）。

### 10.7 PackageManagerFacade（`:domain` 纯 Kotlin 接口）

```kotlin
interface PackageManager {
  suspend fun install(projectId, specs, flags): InstallHandle   // 排队→门禁→起会话→执行→post-check→归档
  suspend fun ci(projectId, offline = true)                    // lock 严格重建；市场脚本唯一入口
  suspend fun update(projectId, spec?) / uninstall(projectId, name)
  suspend fun list(projectId, depth): PkgNode[]                // 轻：Kotlin 直读
  suspend fun dedupe(projectId) / prune(projectId)             // 变更走安装会话
  suspend fun audit(projectId, offline): AuditReport           // 在线 audit(+签名) / 离线 OSV
  suspend fun offlineGap(projectId): List<MissingPkg>          // lock 闭包 − 缓存 的缺失清单(名+尺寸)
  suspend fun approveScript(pkg:, versionHash:, action)        // 仅提交人工确认队列
  suspend fun runScript(projectId, name, args) / exec(bin, args, env)   // P1 仅待人工确认项，纯 JS bin 白名单
  suspend fun importOfflineBundle(uri) / importTarball(path)   // 验签→校验→入缓存→ci
  suspend fun config(projectId?, key, value)                   // .npmrc 层；registry 变更经 :main 卡可配列表+审计
  fun progress(projectId): Flow<InstallEvent>
  fun approvals(projectId): Flow<ApprovalRequest>
  suspend fun storage(): Map<ProjectId, NodeModulesStats>
  suspend fun exportSnapshot(uri): SnapshotRef                 // node_modules.zip+lock+ledger→SAF；高信任通道
  suspend fun cancel(handle: InstallHandle)               // TTL/取消 → quiesce 安装会话
}
```

### 10.8 JS API —— `auto.npm`

Promise 优先 + EventEmitter；QuickJS 白名单缺失该命名空间；npm 操作一律跨进程路由到全局安装会话、TTL 绑定，**绝不阻塞脚本事件循环**；脚本内不直接 `require('child_process')`。

```ts
// 安装（P0）
const pkg = await auto.npm.install('axios', { save: true, offline: false, timeout: 60_000 });
//     → { name:'axios', version:'1.20.0', integrity:'sha512-…', linkedBins:['axios'] }
await auto.npm.remove('axios');
await auto.npm.ci({ offline: true });                          // lockfile v3 严格重建（验签后）
const list = await auto.npm.list({ depth: 0 });                // 轻操作，Kotlin 直读
await auto.npm.prune(); await auto.npm.dedupe();
const gap = await auto.npm.offlineGap();                       // 离线闭包差距（缺哪些包、共多大）
const report = await auto.npm.audit({ offline: true });        // { vulns:[{id,severity,name}], level }

// 配置/离线（P0）
await auto.npm.setRegistry('https://registry.npmmirror.com', { scope: '@my' });
await auto.npm.importOfflineBundle('/sdcard/Download/baseBundle.zip');   // SAF uri 亦可
await auto.npm.importTarball('/sdcard/Download/pkg.tgz');

// 审批（人机分离：只能发起请求，人工在 UI 弹卡确认）
auto.npm.requestApprove('esbuild', { scripts: ['postinstall'] });        // 不直接 approve

// 事件
auto.npm.on('progress', e => ({ phase: 'download', name: 'axios', percent: 0.4 }));
auto.npm.on('approval', req => ({ pkg: 'esbuild', scripts: ['postinstall'], projectId }));
auto.npm.on('warning', e => ({ kind: 'trust-downgraded', pkgs: ['axios'], message: '来源未能多镜像交叉校验' }));

// 错误码新增：ERR_NPM_*（安装失败/审批被拒/SPAWN_BLOCKED）、ERR_NOT_SUPPORTED（git:依赖）、
// ERR_REGISTRY_UNAVAILABLE（网络/镜像可诊断）、ERR_DISK_FULL、ERR_NPM_LOWMEM、ERR_NOT_IMPLEMENTED（node-gyp/exec）
```

### 10.9 UX 流程

1. **依赖面板**（IDE 项目页）：搜索 / `npm install <spec>` 输入行 + 旗标（`-D`/`--offline`/registry 选择器）→ 阶段进度条（packument→下载→解包→链接，job 数）→ 完成横幅；hasInstallScript 包显式警告。
2. **脚本审批卡**：带 install/postinstall 脚本的包 → 卡片列表（可展开「脚本=任意代码」风险说明）→ per-package 人工批准/拒绝 / 「全局禁止脚本」（出厂默认）→ 批准记录入审计页。
3. **npm 终端视图**（P1）：项目内终端 `npm install axios` / `npm ls`，stdout/stderr 流式输出 + exit code；与依赖面板同一安装会话队列。
4. **离线包导入**：SAF 选择（tarball / lock+cacache bundle / 快照 node_modules.zip）→ 验签 → 队列安装；另提供「从内置精选缓存离线装 axios/dayjs/…」。`node_modules.zip` 导入**仅限高信任项目**，签名锚定 `HMAC(应用密钥, lock.sig + zip.sha256)`；市场脚本一律拒绝该格式（走 reify 产出 integrity）。
5. **包大小管理页**：per-project `node_modules` + `npm-cache` 尺寸（Kotlin 遍历）+ 配额条（80%黄/100%拦）→ 一键 prune/dedupe/ci 重装/cache clean；明确标注 node_modules 计入系统「App 数据」。
6. **首启引导**：原子部署 assets/npm CLI + 播种精选缓存 → registry ping 探测 → 选镜像（默认 npmmirror）与配置代理（能力中心网络项）。
7. **打包向导联动**：node_modules 默认入 APK + `.autojs.build.ignore` 排除规则 + 「完全离线变体」（宿主预装 node_modules.zip）+ 项目 lock 签名生成。

### 10.10 与既有机制的关系

- 桥/进程/看门狗/quiesce/原子部署/来源分级全部复用既有框架；npm 不建平行体系。
- `assets/npm` 原子部署复用 script-repo 的 tmp+sha256+rename；安装会话复用 EnginePool acquire/quiesce 四步与 TSF 双队列。
- 运行中脚本的 node_modules 被重装/删包 → 懒加载 ENOENT；per-project 互斥锁 + 默认「脚本结束后安装」+ 强制时显式风险确认。

### 10.11 优先级落定（npm 相关增补到 §14）

- **P0**：vendored npm CLI + 专用安装会话进程；零 spawn 主路径（install/ci/ls/uninstall/prune/dedupe）；T0 拦截 shim 硬失败；精选缓存种子 + 离线首装 + `--prefer-offline`；镜像/代理三路径 + replace-registry-host；事务化安装 + journal 自愈；磁盘/配额预检；hasInstallScript 前置告警 + 审批卡 UI（仅请求）；lock v3 + `npm ci` 强制 + 带外信任锚 + 多镜像交叉校验；依赖面板 + `auto.npm` 核心 API；打包向导 node_modules 入包。
- **P1**：spawn 桥完整 polyfill（stdio 假管道 + pgrp 杀树 + detached 拒绝）+ 批准后脚本真实执行（人工确认）+ `npm run/exec`（纯 JS bin 白名单）；node-shim PIE + PATH 注入（2–3 台 ROM 红测）；npm 终端视图；在线 audit + audit signatures + OSV 离线；QuickJS 白名单库独立 vendored；`offlineGap` + 种子金标准测试。
- **P2**：离线 bundle 打包器（desktop `npm ci` 物化 + cacache 复制体交付）+ 增量更新 + 导入 UX；「完全离线变体」打磨；VSCode 远程终端复用安装会话队列；prebuild `.node` 交叉编译管线产品化（napi-rs/NDK，`NODE_MODULE_VERSION` 与 libnode ABI 匹配校验 + `--dest-os=android` 断言）；纯 JS 替代清单（sharp→jimp/opencv、bcrypt→bcryptjs、better-sqlite3→node:sqlite）。
- **P3**：跨项目共享 store 去重（pnpm 式，须 store↔lock 加签映射）；程序化安装服务化；ECDSA 签名强制；vendored npm 自动升级（仅通过零 spawn 金标准闸门）；esbuild 类**代码签名原生 exec** 独立通道。

### 10.12 npm 特有风险与缓解

| 风险 | 缓解 |
|---|---|
| `--ignore-scripts` 的「假装成功」（postinstall 下载二进制/自检、真原生包装上才炸） | packument `hasInstallScript` 前置扫描 + 显式 warning + 人工审批升级通道，**禁止静默** |
| 预编译 `.node` V8 ABI 稀缺（Node24 NODE_MODULE_VERSION=137 与 libnode 快照不一致则 dlopen 崩） | 自建 libnode 必须 `--dest-os=android`（否则 `process.platform` 非 android，napi-rs 永不命中）+ CI `process.platform/arch` 断言 + 16KB 双门禁 |
| vendored npm 12 要求 Node≥24.15，降级 npm11 会恢复「脚本默认执行」使护栏静默消失 | `:node-runtime-build` 钉版本下限 |
| 设备端 100 依赖安装 15–60s（eMMC/f2fs 更差），非「秒级」 | 独立会话 + 分级超时 + FGS + 熄屏仅物化；进度如实展示 |
| 锁 TOFU；缓存条目与 lock 版本绑定（更新依赖后旧 tarball EINTEGRITY） | 带外信任锚 + 多镜像交叉校验 + 设备端锁降信任标记；提示联网/升级包 |
| **零 spawn 不变量漂移**（npm 升级引入新 spawn 路径，allowScripts 拦不住非脚本 spawn） | 安装会话**强制注入 child_process 拦截 shim**（非批准 spawn 硬失败 ERR_NPM_SPAWN_BLOCKED）；桌面 CI 金标准：child_process 替换为 throw 的 harness 里跑全命令矩阵必须全绿；vendored npm 升级只准通过此闸 |
| 离线 bundle 与 lock 闭包不匹配（盯顶层包，锁含的传递依赖不在种子内 → ENOTCACHED） | `offlineGap` 返回缺失清单（名+尺寸）；导入先按当前 lock 校验；「仅凭种子 npm ci --offline」金标准 |
| 审批/ledger 被已批准脚本改写（自批+改 registry） | ledger 迁 App 私有只读目录 + 条目绑定版本+脚本哈希 + post-check 防篡改比对 + .npmrc 变更经 :main 卡控审计 |
| 数据被清（clear data/卸载重装）导致依赖与审批记录蒸发 | npm-cache/seed → cacheDir（可重建）；node_modules/ledger/lock → filesDir；导出/导入 SAF 快照；清后强制重审批并明示 |
| esbuild 类原生 bin「install 成功、build 一律 W^X 拒绝」的过度承诺 | 审批卡标注是否需要原生 exec；run/exec 首版纯 JS bin 白名单（eslint/prettier/esbuild-wasm）并钱袋承诺，原生 exec 留代码签名通道 |

---

## 11. 安全模型（来源分级）

| 来源 | 信任级别 | 引擎 | 能力 | 说明 |
|---|---|---|---|---|
| 内置/作者签名模板 | 高 | Node | 全量（含 root） | 软签名验证 |
| 用户自写脚本 | 中 | Node（+QuickJS 可选） | 全量 | 提示风险 |
| 第三方/市场脚本 | **低** | **QuickJS `:sandbox` 进程** | **白名单子集**（无 root、无 shell、无任意 file、无 UI 宿主、无媒体投影） | 硬盘级隔离 + interrupt handler + 超时；`process.exit` 只死沙箱 |
| 打包分发脚本 | 中高 | Node | 打包时配置 | 走签名链 |

- RuntimeChannel/engines 通信也按来源分级（低信任不能给高信任发控制消息）。
- 桥的 `ModuleRegistry` 按 scripts 的 CapabilityMask 过滤 handler（非授权模块调用 → `ERR_PERMISSION_DENIED`，不是静默 no-op）。

---

## 12. JS API 设计

### 12.1 设计原则（对齐 AutoJsPro v9 二代 API 风格）
- **Promise 优先**：`await` 一切；同步语义的系统能力（如纯计算）由明确的同步函数提供（`images.format` 等纯函数）。
- **EventEmitter 事件**：a11y 事件、引擎事件、截图流、数据流统一 EventEmitter。
- **超时/取消**：`{timeout}` 选项默认给；返回 Promise 的可选 `AbortSignal`（图形接口）。
- **唯一入口**：脚本 `require('auto')` 返回命名空间根对象（`auto.a11y` / `auto.engines` / …），结构化维护 API。
- **错误码**：`ERR_*` 目录 + `instanceof AutojsError`，可 try/catch 策略化。
- 兼容垫片：对知名差异（如 `uc_obj` 语义）通过 `compat` 标志位提供，**不反向攻坚原生语义**。

### 12.2 命名空间清单（对应 AutoJsPro v9，含设计说明）
- `auto.a11y` —— 无障碍（选择器/控件/手势/事件）
- `auto.ui` / `auto.ui.activity / layout / view / web / res / selector`
- `auto.engine`（自身引擎）/ `auto.engines`（多引擎, `engines.getEngine/exec/stop/channel`）
- `auto.screen`（`capture()` 截图 / `ScreenCapturer` 会话）/ `auto.images`（OpenCV 图像）
- `auto.floatingWindow`（悬浮窗）/ `auto.dialogs`（对话框，降级到 overlay path）
- `auto.device` / `auto.app` / `auto.shell` / `auto.rootAutomator`
- `auto.clipboard` / `auto.notification` / `auto.sensors` / `auto.media`
- `auto.datastore` / `auto.settings`  / `auto.zip`
- `auto.ocr`（P1）/ `auto.plugins`（P2）/ `auto.workManager`（定时/Intent 任务）
- `auto.npm`（包管理与依赖生态，§10：install/ci/list/audit/offlineGap/importOfflineBundle/requestApprove——审批人机分离，QuickJS 沙箱缺失该命名空间）
- Node 内建：`fs/path/http/os/process` 等**完整可用**（除 `child_process` 显式报 `ERR_NOT_IMPLEMENTED`）；`@autojs/*` npm 包 SDK（`@autojs/opencv` 对齐 Pro）。

**接线现状（Kotlin 侧，与 `AppShell.assemble` 对齐；未列出的命名空间在两侧都还没有 handler）**：

| 命名空间 | JS facade | Kotlin handler | 挂载状态 |
|---|---|---|---|
| `console` | `console.ts` | `ConsoleCollector`（`:bridge:java`） | `AppShell.assemble` 已挂 |
| `engines` | `engines.ts` | `EnginesNamespaceHandler`（`:app-service:runtime`） | 已挂（含 `heartbeat` 打点，§8.4） |
| `a11y` | `a11y.ts` | `A11yNamespaceHandler`（`:platform:capabilities`） | **已可挂**：`assemble` 的 `a11yHandler` 缝（未注入则如实 `ERR_NOT_IMPLEMENTED`） |
| `screen` | `images.ts` | `ScreenNamespaceHandler`（`:platform:capabilities`） | **已可挂**：同上，`screenHandler` 缝 |
| `images`（fromFile/matchTemplate/findImage） | `images.ts` | 无 | 待建（`:bridge:image` / native，P1） |
| `dialogs`/`shell`/`device`/`app`/`floatingWindow` | `extras.ts` | 无 | 待建（§9.4/§9.6，多为平台能力） |
| `npm` | `npm.ts` | `NpmBridgeHandler`（`:app-service:packager`，`mount(): NamespaceHandler`） | **已可挂**：`assemble` 的 `npmHandler` 缝（未注入则如实 `ERR_NOT_IMPLEMENTED`；方法表 11 项，`resolveApproval` 刻意不在桥面，§10.5 人机分离） |
| `workManager` | `workManager.ts` | 无（纯本地 helper，不发桥调用） | scheduler 面，另走 Scheduler SPI |

**为什么能力命名空间走注入缝**：`a11y`/`screen` 的真实现住 `:platform:capabilities`，而 §6 禁止 `:app` 直连 `:platform`。解法是 `:domain` 上的挂载缝 `NamespaceHandler` + `:platform:capabilities` 的薄转接 `CapabilityNamespaces.{a11y,screen}`，由持有真实现的 Android 侧在调用 `assemble` 时注入；`BridgeRouter` 的 `RequestHandler` 只是这条缝的 typealias。这不违反依赖规则：两侧都只见 `:domain`。

### 12.3 关键签名示例（风格示范）
```ts
// a11y 选择器（Promise + 超时）
const btn = await auto.a11y.selector()
  .text('启动').package('com.example')
  .timeout(2000).findOne()            // 失败抛 NotFoundError
await btn.click();                    // UiObject 句柄代理

// 控件监听（一定次数内触发则成功）
const ok = await auto.a11y.waitFor(
  auto.a11y.selector().text('登录成功'),
  { timeout: 10_000, interval: 300 })

// 截图 + 找图（全 native 0 拷贝）
const img = await auto.images.captureScreen();      // FrameSource 句柄
const m = await auto.images.findImage(img, await auto.images.fromFile('icon.png'), { threshold: 0.9 });
await img.recycle();

// 定时任务（诚实语义：亮屏+解锁保底契约）
const task = await auto.workManager.createTimedTask({
  name: '早安打卡', script: 'entry.js',
  schedule: { type: 'cron', cron: '0 9 * * *', timezone: 'Asia/Shanghai' },
  screen: 'on',                            // on/any/off
  wakeAheadMs: 60_000,                     // 预热闹钟提前拉起
});

// 多引擎通信
const other = await auto.engines.exec({ script: 'worker.js' });
other.channel('progress').emit({ done: 3 });         // RuntimeChannel
other.on('exit', (code) => console.log('worker 退出', code));

// dialogs（BAL 安全路径：overlay 可见时弹窗，否则通知回调）
const name = await auto.dialogs.prompt('输入名字', { mode: 'auto' });

// shell / root 能力（分级成 DENIED 时抛 ERR_PERMISSION_DENIED）
const out = await auto.shell(`pm list packages`);

// 图片（原生 addon，可与 Java 对象互转）
const gray = await auto.images.toGrayscale(img);
const found = await auto.images.matchTemplate(gray, part, { tolerance: 0.85 });

// 依赖管理（Promise + 事件流；跨进程路由到全局安装会话，绝不阻塞脚本事件循环）
const pkg = await auto.npm.install('axios', { timeout: 60_000 });   // → {name, version, integrity}
await auto.npm.ci({ offline: true });                               // lockfile v3 严格重建（验签后）
const gap = await auto.npm.offlineGap();                            // 离线闭包缺哪些包（名+尺寸）
auto.npm.on('progress', e => console.log(e.phase, e.name, e.percent));
auto.npm.on('approval', req => notify('需人工确认', req.pkg));       // 审批只能提交请求，绝不脚本直调
```

### 12.4 typings 工程
`:bridge:js` 产出全套 `.d.ts`（@types/auto），IDE 补全不依赖文档站点；d.ts 作为 API 契约的单一事实来源，API 评审以 d.ts diff 为准。

---

## 13. 设计模式应用总表（架构落地位置）

| 模式 | 落地位置 | 用在哪 / 为什么 |
|---|---|---|
| **Facade** | `auto` 根 / `RuntimeBridge` / `ScriptEngine` | 对脚本隐藏桥细节 |
| **Provider / SPI（服务定位的换代）** | `EnginePool`、`FrameSource`、`InputProvider`、`SchedulerProvider`、`UiHost`、`OcrProvider` | 全部可替换接缝 |
| **Strategy** | `SchedulerProvider`、`InputProvider`（a11y/root/Shizuku）、截图源 | 触发/输入/帧源多路实现平切 |
| **State Machine** | 执行单元生命周期、`CapabilityStatus`(3 态)、MediaProjection 会话 | 不可落定的状态自动终结 |
| **Observer / EventEmitter** | 引擎事件、a11y 事件、屏幕会话、数流 | 事件订阅隔离生产者消费者 |
| **Registry + Request-Reply 关联** | `RequestRegistry(requestId+TTL)`、`HandleRegistry(gen)` | 可靠超时、句柄安全、错误折叠 |
| **Watchdog + Circuit Breaker** | `:main` 看门狗三路、桥的连接熔断 | 防僵尸/风暴级联 |
| **Half-Sync / Half-Async** | 桥：异步调用 + 队列化同步任务 | 两个事件循环互不阻塞 |
| **Proxy（句柄代理）** | `UiObject`/`Image`/`MediaPlayer` JS 代理 | 跨进程资源生命周期 |
| **Object Pool** | `ImageReader(maxImages=2~3)`、页面帧、engine slot | 减少分配/避免堆外内存 |
| **Template Method** | 引擎宿主 start/stop 骨架、打包 AXML 改写 | quiesce 协议稳定、差异内聚 |
| **Adapter** | `nodejs-mobile` recipe → 自建管线；传输(unix socket/binder) | 隔离上游差异 |
| **Composition Root（手写 DI）** | `AppShellApplication` 启动装配 | 显式依赖、可测；**不用 Hilt**（模块扁平、装配少） |
| **Repository** | `ScriptRepo`、`Datastore`、`Scheduler` 的 RunRecord | 三处存取各自仅通过仓库 |
| **EventBus** | `:main` 内事件（能力状态变化、引擎状态） | 模块解耦、不直连 |
| **Idempotency 键（runNonce）** | checkpoint 意图日志 | 崩溃恢复不重复副作用 |
| **Interceptor/Decorator（child_process shim）** | npm 安装会话进程 | 零 spawn 强制不变量：拦截非批准 spawn 并硬失败（ERR_NPM_SPAWN_BLOCKED），把静默漂移变成响亮错误 |
| **Transaction Journal + 暂存目录/rename** | InstallCoordinator · reify | 安装原子化（node_modules.part-<ts> + install.journal begin/commit/fail），崩溃自愈坏树 |
| **Trust Anchor（带外）** | 供应链安全 | pin 注册表签名公钥 + 多镜像 integrity 交叉校验，破除 TOFU 自签 |
| **Generation/tombstone（Handle）** | HandleRegistry | 跨进程资源竞态安全 |
| **Interrupt Handler** | QuickJS 沙箱 CPU 打断 | 风暴消融 |

---

## 14. 需求优先级路线图

### P0 — 小而完整、可发布的最小闭环
**用户故事**：写一个无障碍脚本 → 在 App 内运行/停止/看 console → 被守护（看门狗杀僵尸不拖垮 UI）→ 能设一个每天定时任务 → 能打包成独立 APK。
- 构建链行：`:node` 进程宿主（单脚本）、Node 24 自建管线 + 16KB 门禁。
- 最小桥：TSF 双队列 + RPC + TTL + HandleRegistry、`console` 回传。
- a11y 基础：选择器/click/scroll/setText/文本事件；a11y 截图（333ms）。
  **Kotlin 侧已落地**：选择器/点击/滚动/文本/剪贴板/事件流/手势 + 截图（333ms 节流/会话），均 JVM 可测；`AppShell.assemble` 已留 `a11yHandler`/`screenHandler` 挂载缝（见 §12.2 接线现状表）。
- 执行：池（默认 1）状态机、四步 quiesce、心跳+Cpu+OOM 看门狗、崩溃重启(仅本轮 run)。
- 定时：单 alarm 定时任务 + 意图日志 + runNonce 幂等。
- 权限三态中心 UI + 引导页；specialUse FGS 骨架。
- 打包：模板 APK 改装（assets 注入、签名向导）——闭环验证。
- 单测/archUnit CI；Docker 构建镜像。
- npm P0（§10.11）：vendored npm CLI + 专用安装会话进程 + 零 spawn 主路径 + 事务化安装/journal 自愈 + 精选缓存种子离线首装 + 带外信任锚/lock 验签/审批卡 UI + 依赖面板 + 打包 node_modules 入包。

### P1 — 并发、沙箱、图像、生态关键件
- 引擎池自适应（1-3）＋执行 slot FGS + 队列语义；`engines` 多引擎/`RuntimeChannel`。
- QuickJS `:sandbox` 进程（白名单子集 + interrupt handler + CPU 配额）。
- `libimgnative.so` 全图像管线（找色/模板/无拷贝截图）；MediaProjection 会话式截屏/录屏。
- `ui` 原生 XML UI 宿主 + `ui_web` WebView JS 桥 + 悬浮窗。
- datastore SQLite、settings、sensors、notification、app Intent、zip、power_manager。
- OCR (MLKit 插件基准实现) + `OcrProvider`。
- VSCode 远程调试（inspector over adb forward）。
- 插件框架骨架 + 打包合并插件资产。
- npm P1（§10.11）：spawn 桥 polyfill + 批准后脚本真实执行（纯 JS bin 白名单）+ npm 终端 + 在线/OSV 离线审计 + QuickJS 白名单库独立 vendored + node-shim 红测。

### P2 — 生态与分发
- 脚本加密分级（AES-GCM vault → Dex → 快照 → engine-native 逐级）；`.js` 资源重打包链路。
- `dialogs` 全形态（overlay/通知降级）、`root_automator`/Shizuku 输入、`shell` 全量。
- 打包 APK 深度自定义（权限/图标/启动配置、低 targetSdk 壳可配）。
- 通知触发的 Intent 任务；多时区 cron；alarm 生成日历视图。
- 市场/分享、`axios`/第三方包预置、插件市场。
- npm P2（§10.11）：离线 bundle 打包器 + 增量更新；`prebuild .node` 交叉编译管线产品化 + 纯 JS 替代清单；VSCode 远程终端执行 npm。

### P3 — 前沿与实验
- worker_threads 实验性引擎（若手机端验证可行）——标记实验、默认关闭。
- 多设备/服务器远端执行；LLM 驱动的自动化智能体；VSCode 协作。
- Flutter/Compose 全重做 IDE 主题化；性能剖析面板。

**原则**：P0 的「小而完整」优先于「多而残缺」；每个 P 的退出标准都有可测验收（§16 预算联动）。

---

## 15. 性能与体积预算

| 指标 | 目标 |
|---|---|
| APK 体积 | ≤ 40MB release（`libnode.so` + `libimgnative.so` + assets） |
| 冷启动→就绪 | ≤ 800ms（无系统抖动） |
| 脚本 warm start（二次复用 slot） | ≤ 300ms |
| a11y 空 RPC p95 | < 2ms |
| 截图→找图 | < 1s；模板匹配 1080p < 40ms; 找色 < 10ms |
| 紧凑树传输 | < 15ms / 数十 KB |
| 引擎进程 RSS | 80–160MB（Node 24 baseline）；低内存模式 ≤ 128MB 堆 |
| 池内存预算 | 默认 1–2 引擎；≥6GB 设备至多 3；峰值不可超出设备内存 1/3（自适应采样调节） |
| npm 安装会话 | 堆上限常规 192MB / 低内存 96MB（从池预算反推，RSS 记账超阈先降载再放弃）；设备端装 100 依赖 15–60s（eMMC/f2fs 更差，进度如实展示） |
| vendored npm CLI | ~8–9MB 运行态（APK 内压缩、首启解一次）；精选缓存种子 ~5MB |

---

## 16. 风险与缓解

| 风险 | 影响 | 缓解 |
|---|---|---|
| **Node-on-Android 升级依赖自持管线** | 上游（nodejs-mobile）停更；我方需长期维护 recipe | 建立 `:node-runtime-build` 固化管线：固定 Node LTS、预期树哈希门禁、NDK 版本锁定、CI 每日构建冒烟、产物 ABI 号校验；管线减至「换版本号→跑一次→回归」 |
| **16KB 页 / ELF 对齐** | 未对齐 so 在新设备加载即崩 | **CI 门禁强制 `LOAD 0x4000` 对齐**（用 `llvm-objdump --private-headers` 断言）；红测机里常驻一台 16KB 页设备 |
| **引擎进程被杀/LMK** | 长任务中断 | 执行 slot 与 `:main` 绑定继承进程重要性 + specialUse FGS；看门狗对「被杀」能恢复意图日志重调度（幂等）；low-memory 降池 |
| **无障碍树洪峰（滚动/动画）** | IPC 爆炸 / UI 卡顿 | 节流拉取（seq 游标批量）+ 数据面可丢包 + 紧凑索引树按需属性 |
| **`process.exit` / CPU 风暴 / OOM 单脚本** | 曾拖垮整个 app | **进程边界**吸收全部；外带 CPU 差分 + 心跳双通道 + 堆 cap；沙箱 interrupt handler |
| **屏幕锁定时守时任务失败** | 闹钟响但任务是黑帧/无窗口 | 诚实契约：亮屏+解锁保底；预热闹钟 -60s；`screen` 三态声明；分类错误可 catch |
| **厂商 ROM（MIUI/HyperOS/Vivo）杀后台** | 自启/保活失效 | 能力中心三态 +「一键引导」直达 ROM 白名单页；无保证的功能如实降级标注 |
| **SCHEDULE_EXACT_ALARM 默认拒绝** | 定时不准 | 一级权限项 + 可降级 setWindow；坏 case 用户可见偏差标注 |
| **MediaProjection 会话授权中断** | 截屏功能随会话失效 | 会话状态机 + 可重授权引导；306s 超时前自动续期/提示 |
| **QuickJS 沙箱缺口** | 恶意脚本逃逸 | 沙箱进程只开白名单能力 + 无集成桥权限 + interrupt/TTL 双保险；永不默认静默提级 |
| **双引擎 API 漂移**（Node vs QuickJS） | 同脚本两处行为不同 | P0 仅有 Node；QuickJS 以「白名单子集」封锁边界，逐项对照测试 |
| **桥死锁回归** | 事件循环冻结 | archUnit + 专项契约测试（双向同步禁令的静态检查 + 死锁压力测试）；线程规则写进 code review checklist |
| **脚本间广播/通信滥用** | 引擎间干扰 | RuntimeChannel 按来源分级过滤；低信任不得控制高信任引擎 |
| **打包 APK 依赖宿主引擎版本** | 旧 APK + 新宿主 mismatch | 打包时记录引擎 ABI 哈希，启动校验 |
| **npm 供应链 / 零 spawn 漂移 / 安装中断** | 依赖投毒、护栏静默消失、半截 node_modules | §10.5（带外信任锚/审批人机分离）与 §10.12（拦截 shim 金标准/事务化安装/离线闭包差距），不再在此重复 |

---

## 17. 兼容矩阵要点（修订版）

| 维度 | 决策 |
|---|---|
| SDK | minSdk 24（Android 7）· target/compile **36**（Android 16 · 2025/26 基线）；arm64-v8a 首发，x86_64/模拟器 P1 补 |
| 页对齐 | 16KB ELF 对齐为 CI 硬门禁（§16） |
| 无障碍 | API 31+ 需启用手势 → 能力中心引导；hidden API 在黑名单 → 不 curl，用 Safe-mode 替代路径 |
| 前台服务 | API 34 起必须带 type → specialUse；API 35 6h 超时对 specialUse 不适用（但要声明 subtype） |
| MediaProjection | API 34+ 每会话确认 + FGS(mediaProjection) 前置；会话 306s 感知 |
| Doze/App Standby | 精确闹钟豁免必须在白名单内；未豁免必须降级并如实标注 |
| FLAG_SECURE | 一律不采样 → `ERR_BLACK_FRAME`/`ERR_SCREEN_LOCKED` 分类错误 |
| 厂商 ROM | 华为/小米/OPPO/vivo 自启与保活白名单差异化 → PermissionCenter ROM 适配表 + 跳转写死到页 |
| 网络代理 | 国内网络环境可配 `HTTP_PROXY`/`HTTPS_PROXY` 注入引擎 env（对齐用户代理经验） |
| 存储 | scoped storage：脚本资产走应用私有目录 + 用户授权目录（SAF）；`MANAGE_EXTERNAL_STORAGE` 作为 P2 可选权限条目 |

---

## 18. 开放决策点（留给你的拍板项）

设计已给出默认推荐，但以下六点会实质影响方向，由你决策：

1. **引擎路线：先 Node-only，还是 P0 就并行 QuickJS 沙箱？**
   推荐「P0 只 Node；QuickJS 沙箱 P1」——沙箱牵扯独立进程、白名单、双引擎 API 对齐三件大事，混进 P0 会把最小闭环拖垮。
2. **进程模型：P0 就用「每脚本一进程」，还是先单引擎进程后扩？**
   推荐**一步到位**：反正脚本绝不能进主进程，单引擎进程的边界与多引擎池完全同构，代价只是「池容量先写死为 1」。避免二次重构。
3. **分发定位与 Play 态度？**
   推荐完全避开 Play Store（specialUse FGS / SCHEDULE_EXACT_ALARM / MANAGE_EXTERNAL_STORAGE 政策冲突），官网/F-Droid/APK 直下。若你仍想上 Play，需砍掉 specialUse 保活与精确闹钟，P0 范围要变。
4. **ICU 取舍：全量 ICU（完整 Unicode/时区/国际化，体积 +20MB 级）还是配 `--with-intl=none`（体积小但字符串/时区残缺，自动化和 UI 场景产物不友好）？**
   推荐**全量 ICU + 裁剪为所需 subset**（也可放 assets 按需加载），自动化 app 大量依赖正则/时区/日期格式化。
5. **无障碍服务与脚本进程共享与否的极限形态**：本设计定案「a11y 在 `:main`、脚本在 `:nodeN`」。若未来遇到「无障碍回调海量 + 脚本高频读树」压垮 `:main`，可演进出
   `:accessibility` 第三进程（§9.1 的接口已留好接缝）。P0 不做——保持最少进程数。
6. **UI 宿主策略**：脚本 UI 用「`:main` 渲染原生 View」还是「脚本自带 WebView（ui_web）」为主？
   推荐**两者都留、原生优先**（原生 View 桥链短、性能好；WebView 桥用于复杂富交互）。若优先做 Web 方案更快出 demo，可调整为 Web 优先。你在意的 demo 速度可以决定这个顺序。
7. **npm 默认镜像与脚本审批严苛度**（§10 已定案技术路线，这两项是面向用户的策略）：
   - 默认 registry：推荐 `registry.npmmirror.com`（国内实测存活）——若你的目标用户全球分布则改 `npmjs.org` + 可切换。种子缓存与「离线秒装」文案都要绑定默认镜像。
   - 脚本审批默认值：推荐出厂 **global-deny**（全部 install 脚本默认拒绝，人工逐个批准）。代价是与 AutoJsPro 既有的「默认跑脚本」用户习惯不同，新旧用户需要文档/示例适配；若你更看重无缝迁移，可出厂 allow-listed 常用安全包 + 黑名单模式。

---

## 19. 结语

AutoScript 的骨架可以一句话记住：

> **三个进程、一个异步桥、每脚本一个 Node 进程。**

架构的全部取舍都锚定在五条铁律上：脚本不进主进程、跨进程必异步、每次操作有 TTL、teardown 四步 quiesce、依赖单向接缝可替换。这个骨架让「写脚本→跑起来→守护它→定时它→打包走」的 P0 闭环与 AutoJsPro 对整个 API 面的演进式补齐，是同一条路的两个阶段，而不是两个项目。

下一步（建议与后续迭代方向，需你确认后开工）：
1. 确认 §18 决策点（现为 7 个，含 npm 默认镜像与脚本审批策略；或直接采纳推荐默认值）；
2. 在 `:node-runtime-build` 上跑通「Node 24 → 16KB 对齐 libnode.so → 最小 `:node` 进程能执行 `console.log` 并回传」的**垂直切片**——这是全架构的第一块里程碑，也是最硬的一块骨头；
3. 第二个切片接 **npm**：专用安装会话进程内跑 vendored npm CLI 完成一次 `npm ci --offline`（用种子缓存装 axios），把 §10 的零 spawn 契约、事务化安装与镜像校验一次验证；
4. 切片通过后，按 §14 P0 展开桥与 a11y 最小集。文档将随切片验证持续修订。

**本仓库的推进顺序（已落地的按 §12.2 接线现状表为准，勿按上表臆造）**：契约与纯 JVM 层（`:domain` / `:bridge:java` / 各 app-service / `:platform:capabilities` 的 handler）已逐块落地并有单测；`AppShellApplication` 已从 11 行桩变成**闹钟/门禁的装配入口**（`AlarmSchedulerProvider` + `AndroidAlarmPort` + `AndroidScreenGate` + 静态注册的 `AlarmReceiver` → `AlarmDispatch` → `Scheduler.onTrigger`，漏投记账不静默丢弃），但 `AppShell.assemble` 的**生产调用方仍未落地**（`a11y`/`screen` 的 Android 真实现、开机恢复 `recoverUncommitted` 的接线点都还缺）；§8.4 已闭环（判据/采样/`EngineWatchdog` 调度/`HeartbeatLedger` 心跳打点；pid 归属表仍归在途账不另建），仅剩 native 宿主送出 pid 与心跳；再往后给 §8.3 的 drift 定裁决方（状态机→`PoolSlot` 已接线、宿主↔池侧对照已落地）；§8.6 已闭环（dispatcher 排队默认上限按触发源分级 + `PendingRun` deadline 记账与过期不重投），**Android 触发侧也已接上**（预拉/Exact/降级记账 + 静态接收器回投 + 屏幕门禁生产实现）。native/NDK 侧（`:bridge:native`、`:engine:node-process`、`:bridge:image`、`:platform:system`）仍是空壳，见第 2 条的切片路线。
