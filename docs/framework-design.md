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
- **对标 AutoJsPro v9 的 API 能力面**：无障碍自动化、截图找图、悬浮窗、定时任务、原生/Web UI、多脚本引擎、打包为独立 APK。
- **脚本即一等公民**：用户用 Node.js 生态写自动化；内置 IDE/控制台/任务中心。
- **可长期演进**：清晰的依赖方向、明确的接缝（引擎/自动化通道/存储/OCR 可替换）、P0 小而完整可发布。
- **诚实面对 Android 2000 说「不」**：不在保活、后台启动、跨进程同步这些被系统禁令的地方假装可以。

### 非目标（P0-P1 明确不做）
- 不跑在 Play Store 发行管线（specialUse FGS / SCHEDULE_EXACT_ALARM / MANAGE_EXTERNAL_STORAGE 与 Play 政策冲突）→ 官网 / F-Droid / APK 直下。
- 不做「无人值守的自愈」：精确闹钟、电池白名单、开机启动默认**不自动授予**，全部走能力中心引导；未授权即降级并在 UI 明示。
- 不在 v1 承诺 `child_process.spawn`（Node-on-Android 不可用）与未经验证的 `worker_threads`（见 §8.2 决议）。两者都由引擎适配层如实上报为「不支持」，不伪造。
- 不承诺 iOS/Windows 跨端（单 Android 目标）。
- 不做桌面端远程调试生态：VSCode 插件 / `inspector over adb forward` / 远程终端 / 多端协作一律不进路线图。调试只走 App 内置 IDE 控制台与日志回传（§8 最小桥的 `console` 通道），不为任何外部编辑器开 adb 转发端口或暴露安装会话。
- 不做侵入式破解/绕过系统安全（root 通道是用户自选能力，需要 root 设备）。

---

## 2. 核心理念与五条架构铁律

所有设计决策服从以下五条不可谈判的不变量。批判阶段证伪过多种「违反它」的设计。

1. **脚本绝不进 UI 进程。**
   开源 Auto.js 最大的架构教训就是脚本跑在主进程（UI 卡顿、一个死循环拖垮整个 app、无法 kill 单个脚本）。脚本运行时只存在于独立进程。
   - 推论 A：**进程级隔离是唯一的真实隔离**。「模块作用域 + 独立 vm context」不是隔离——`process.exit()` 会带走同进程的一切。任何被用户脚本控制的特性（`process.exit`、`setTimeout` 风暴、OOM）都必须被进程边界吸收。
   - 推论 B：最不可信的代码最隔离 → 若引入沙箱，它必须在**自己的进程**，而不是塞进主进程的一个线程。**2026-09-26 拍板不做沙箱（§18 第 1 项），本推论暂无落地对象**；铁律「脚本不进主进程」不受影响 —— 现有的隔离单元就是 `:nodeN`。

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
| **JS 引擎** | **只有 Node**（QuickJS 沙箱已裁，§18 第 1 项 2026-09-26 拍板） | 单引擎免掉双引擎 API 对齐与 `:sandbox` 进程/白名单三件大事；脚本特权改由**安装时用户选择 + §11 来源提示**约束，不靠进程隔离 | 第三方脚本与自写脚本同权（见 §11 表） |
| **桥** | TS facade → N-API addon（`NAPI_VERSION=10`）→ JNI → Kotlin Router；**全异步 JSON-RPC + requestId 关联**；每 context 一个 `napi_threadsafe_function` | N-API 稳定 ABI、nodejs-mobile 系已验证；TSF 允许任何 Java 线程安全投递事件，`napi_unref_threadsafe_function` 闲置不保活事件循环 | 无同步调用便利性（/并发模型语病，已在 §7.2 定死） |
| **无障碍通道** | `AccessibilityService` 部署在 **`:main`** 进程；**紧凑索引树**传输；句柄带 generation | 与引擎进程分离＝无障碍服务存活不依赖脚本进程；紧凑树省 IPC 体积（全树 JSON 序列化是性能杀手） | 树构建在 UI 进程承担；事件洪峰需节流 |
| **截图** | 默认 **a11y `takeScreenshot`**（API34 起 333ms 节流）；**MediaProjection** 做会话式实时截屏/录屏（API34 每会话确认 + FGS 前置）；**图像分析全走 native**（独立 `libopencv.so`，OpenCV 4.14.0 静态链接 + kleidicv 默认 ON） | 图像管线 0–1 拷贝直达 Native，避免 Bitmap→Byte[]→Buffer 多次拷贝；`FLAG_SECURE` 窗口如实返回 `ERR_SCREEN_LOCKED/ERR_BLACK_FRAME` 系错误对象 | 维护两份 so；OpenCV 静态链接体积 |
| **UI** | 脚本 UI = 桥把 XML 布局描述推给 `:main` 渲染（原生 View）；`ui_web` 走 WebView + JS 桥；悬浮窗独立小型宿主 Activity | AutoJsPro 已验证；XML→View 桥符合「脚本进程只产声明、主进程渲染」原则 | UI 事件回的桥链路较多 |
| **保活** | **specialUse FGS**（`onCreate` 即 `startForeground`，声明 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`，无超时）+ 电池优化白名单引导 + 精确闹钟看门狗 + 开机 specialUse 恢复；**全部作为一级权限项进能力中心** | API35 普通 FGS 有 6h 超时、普通后台启动受限；specialUse 无超时，精确闹钟不受 FGS 后台启动限制 | Play Store 不可发行；部分 ROM 需要引导 |
| **打包** | 模板 APK 改写（AXML/ARSC 编辑替换 application + 注入 assets/project），复用宿主 Node 引擎 so | AutoJsPro 已验证的发行形态；不需要为每个脚本重编 C++ | APK 依赖宿主引擎版本 |
| **npm 支持** | **D（混合）**：vendored 真 npm CLI（npm 12.x 系，Node≥24.15）在**专用安装会话进程**内进程内执行——P0 默认零 spawn（npm12 官方 `allowScripts=none` 等默认语义把「无 child_process」从 workaround 变成契约）；P1 加 spawn 桥升级通道（批准后 lifecycle/npm run）；内置离线 bundle + 精选 tarball 种子通道 | 纯 JS 生态（axios/dayjs/lodash/ws 等）安装**实证无需子进程**（strace 实测 0 execve）；真 CLI 白拿 lockfile v3/audit/审批语义且可审计；不重造轮子 | npm CLI ~8–9MB 体积；供应链护栏只能靠带外信任锚与审批人机分离（§10） |
| **进程隔离** | `:main`(UI+服务) / `:node0..N`(脚本) | §4 进程拓扑（`:sandbox` 已裁，图中留痕标注） | — |
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
│ :platform:capabilities     │   │ :engine:node-process    :engine:sandbox*│
│  a11y / 截图 / 输入 / 悬浮窗 │   │  Node host (.so 装载 + bridge)      │
│  / 系统 / 存储              │   └─────────┬──────────────────────────┘
└────────────────────────────┘             │ JNI
┌──────────────────────────────────────────▼──────────────────────────┐
│  桥基础设施 :bridge (双向)                                            │
│   Kotlin Router · RequestRegistry(TTL) · HandleRegistry(generation) │
│   EventBus · transports · JS facade (TS) · N-API addon · JNI glue    │
└─────────────────────────────────────────────────────────────────────┘
        ▲ 控制面                    ▲ 图像数据面（独立 so：libopencv.so）
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
│ :node0 引擎进程        │   │ :node1              │   │ :sandbox *已裁          │
│  node::Start          │   │  ...                 │   │  (QuickJS 不进排期)     │
│  N-API addon+TSF      │   │  池容量由设备内存决定 │   │  interrupt handler     │
│  单脚本/单 context     │   │  执行 slot 持 FGS     │   │  白名单 auto.* 子集      │
│  libopencv.so      │   │                      │   │  独立进程：最不可信最隔离 │
└───────────────────────┘   └──────────────────────┘   └───────────────────────┘
```

进程职责切割的推演（含批判结论）：
- **无障碍服务放 `:main`**（离系统最近、显著最高、Binder 调用最省），且**与脚本进程解耦**——脚本崩了无障碍服务不死，反之亦然。批判 5/8 交叉验证了「无障碍与运行时同进程=一损俱损」「无障碍进引擎进程是错误」，主进程承接它被否决否认性证伪。
- **脚本（Node）只在 `:nodeN`**，执行中的 slot 持有 specialUse FGS（绑定到 :main 继承进程重要性，形成 group），防 LMK 优先回收。
- ~~**QuickJS 沙箱 `:sandbox`** 单独进程~~ **已裁（2026-09-26，§18 第 1 项）**：不再有第三个进程。CPU 风暴/死循环的吸收者就是 `:nodeN` 自己（进程边界 + 心跳/差分看门狗，§8.4/§8.8），与沙箱无关。

---

## 5. 进程/线程模型

### 5.1 进程
- `:main`：UI + 服务 + 无障碍 + 调度 + 能力（1 个常驻进程，specialUse FGS 保活）。
- `:node0…N`：脚本引擎进程。**每引擎进程恰好一个 `node::Start`/单一 v8 isolate/单一 context**（P0 单脚本进程一对一；并发 = 池化多进程）。孤立过程：
  - **池容量自适应**：由 `/proc/meminfo` + 设备分级决定，默认 1–2，≥6GB 内存 → 至多 3；低内存模式堆上限降为 128MB / 池=1。
  - 一个常驻引擎 slot 绑一个持 FGS 的「执行 slot」；闲时进程被复用（脚本之间不共享内存）。
- ~~`:sandbox`：QuickJS（P1）~~ **已裁（2026-09-26）**，引擎进程只有 `:node0..N`。

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

> 批判建议「约 12 个模块、不要过度拆分」，下表为落定清单（15 个），薄模块已合并（原 4 个 `:platform:*` 合并为 2 个，插件管理器/打包器等薄服务并入对应模块）——拆分的唯一目的是：**让依赖方向能在 Gradle 层面被强制**。

| 模块 | 职责 | 允许依赖 | 所有模块禁止 |
|---|---|---|---|
| `:app` | `AppShellApplication` 启动装配（§4.1 Composition Root）。Compose UI 已拆出为 `:ui`（2026-09-23 落地：launcher 随库 manifest 合并；`:app` 源码零 compose、零 import ui —— Application 实现的是 `:domain` 的 `HostSummary`，装配知识不流向呈现层） | `:app-service:*`、`:domain`、`:ui`、`:engine:node-process`†、`:bridge:java`*、`:platform:capabilities`*、`:platform:system`*（带 * 者仅装配包可用，见右列；† 仅根包 `AppShellApplication` 构造 `engineFactory` 注入，`com.autoscript.shell` 装配包仍禁碰 engine —— :app ArchitectureTest「shell 装配包零跨层泄漏」量化） | 直连 `:platform`/`:bridge` 于装配包之外（**包级例外两则**，均仅限 `com.autoscript.shell`、只做字段级转接无业务逻辑：① 把 handler 挂上 `BridgeRouter` 可依赖 `:bridge:java`；② `SystemSpis`+`CapabilityNamespaces` 生产装配（落点 `PlatformWiring`）可依赖 `:platform:capabilities` 与 `:platform:system`） |
| `:ui` | Compose UI 呈现层：启动 Activity（launcher，manifest 随库合并进 `:app`）、**首屏、能力中心、任务中心与控制台已落地**（2026-09-23/24，四页签齐）。状态经 `:domain` 的 `HostSummary` 读口现取（`MainActivity` 是装配级接线点）；**jvm-test 旁路不含本模块**（compose 无裸 kotlinc 配方），门 = `:ui:testDebugUnitTest`（CI 任务表） | `:domain` | 依赖 `:app` 或业务模块（成环）；承载装配/业务逻辑（呈现层只画快照） |
| `:app-service:runtime` | 执行编排：RuntimeController、EnginePool、Watchdog 仲裁、kill 权威、`engines` 命名空间处理器 | `:domain` | 依赖 UI/Dialog 类、`com.autoscript.bridge..`（archUnit 强制，严于本表的历史约定） |
| `:app-service:scheduler` | 定时/Intent/事件任务、checkpoint 意图日志、runNonce 幂等 | `:domain` | 依赖 RunRecord 之外的引擎细节 |
| `:app-service:script-repo` | 项目/资源/脚本库、assets→filesDir 原子部署（tmp+rename+sha256 校验） | `:domain` | 直访 danger 权限 |
| `:app-service:permission-center` | 权限三态门禁、引导页、降级路径 | `:domain` | — |
| `:app-service:packager` | 模板 APK 改写、签名向导 | `:domain` | — |
| `:domain` | **纯 Kotlin 领域：全部 SPI 接口 + DTO + 状态机 + 领域规则** | 无（std 仅） | 禁 Android 依赖 |
| `:bridge:java` | Kotlin Router、RequestRegistry(TTL)、HandleRegistry(generation)、EventBus、transports | `:domain` | 禁 UI |
| `:bridge:native` | C++：N-API addon 控制面（含 JNI glue、TSF 管理、node::Start）、`libnode.so` 装载 | 被引擎宿主进程引用 | 禁 Android 业务 |
| `:bridge:image` | C++：图像分析管线 addon（独立 so `libopencv.so`，OpenCV 4.14.0 静态链接 + kleidicv，不依赖 node；`imgnative.cpp` 计算核 + `images_jni.cc` 装载面，构建轨 `node-runtime-build/scripts/build-opencv.sh` + `.github/workflows/image-native.yml`；宿主机语义门禁 `bridge/image/test/cpp/`，277 例直链同 commit OpenCV 跑像素断言，覆盖 decode 归一 21 + findColor 36 + matchTemplate 24 + 灰度 28 + 裁剪 46 + 缩放 45 + 旋转 45 + 特征 32） | 被引擎宿主 + `:main` 分析器引用 | — |
| `:bridge:js` | npm workspace：TS facade SDK（`@autojs/*`）、RuntimeChannel、bootstrap loader、d.ts | 仅 npm 依赖 | 禁 Gradle 反向 |
| `:engine:node-process` | `:nodeN` 进程宿主：**`NodeProcessEngine`（Kotlin spawn：ProcessLauncher 缝 + env 契约 + pid/状态语义，实现 `:domain` 的 `ScriptEngine`）**、main.cpp、Node config、JNI 注册 | `:bridge:native`、`:domain` | 禁 Android SDK UI；Kotlin 侧禁 `com.autoscript.bridge..`/`appservice`/`platform`（ArchitectureTest 量化） |
| `:engine:sandbox` | QuickJS 宿主进程 —— **已裁、不进排期**（§18 第 1 项；`settings.gradle.kts` 模块表由协调者冻结，壳保留） | — | — |
| `:platform:capabilities` | a11y 服务/UiNodeTreeReader、截图 FrameSource（a11y 路径已接，MediaProjection 待）、输入通道（无障碍/root/adb/Shizuku）、`a11y`/`screen` 命名空间 handler + 挂载缝薄转接；`DialogHost`（`AndroidDialogHost` 编排 + **设备面全部住 `…capabilities.device` 子包** —— ArchUnit 按包豁免 `android..`，语义层保持纯 JVM）；`dialogs`/`shell`/`device`/`app`/`floatingWindow` 五个 handler（语义层，SPI 由 Android 侧注入）；`datastore`/`zip`/`settings` 三个存储面 + `notification` 通知面 + `clipboard` 剪贴板面 + `sensors` 传感器面 + `images` 图像面 handler（§9.6/§9.2/§12.2，各自独立注入缝 —— 图像面的 `ImagesNamespaceHandler` **单独成文件、刻意不住 `SystemNamespaces.kt`**：那五个共担 OVERLAY/ROOT/ADB_INPUT 门禁组，图像面没有门禁） | `:domain` + 系统 API | 禁服务逻辑；禁直连 `com.autoscript.bridge..`（挂载缝类型住 `:domain`，见 §12.2） |
| `:platform:system` | overlay、通知、datastore（SQLite）、shell、设备信息、zip、系统设置 —— **只放 `com.autoscript.domain.system` 各 SPI 的 Android 实现**（`Runtime.exec`/`Build`/`PackageManager`/`WindowManager`），handler 语义层不在这里（见上一行，理由见 §12.2）。**已落地**：`shell`/`device`/`app`/`floatingWindow` 四件 + `datastore`（`AndroidDataStore`+`SqliteKvOps`）+ `zip`（`JdkZipArchiver`，`java.util.zip` 纯 JVM 无 ops 缝）+ `settings`（`AndroidSystemSettings`+`SettingsSystemOps`）（`SystemSpis.of` 是实现入口，`Bundle` 已到十件）+ `notification`（`AndroidNotificationPoster`+`NotificationOps`，默认 channel 归实现）+ `clipboard`（`AndroidClipboard`+`ClipboardOps`，与 a11y 剪贴板同口径）+ `sensors`（`AndroidSensorSource`+`SensorOps`，拉取式游标/有界环/句柄纪律）；`dialogs` **不在本模块**（domain KDoc 约定实现住 :platform:capabilities，平台模块间无依赖边，构造归 `PlatformWiring.of`）；`images` 的 `ImageAnalyzer` 真实现是 `NativeImageAnalyzer` + `JniOps`（`System.loadLibrary("opencv")`，so 缺位即不构造）——它不住 `SystemSpis.Bundle`（`images` 是独立可选参数，构造归 `PlatformWiring.of`：`JniOps.loadOrNull()` 失败 → null → 桥对 `images.*` 如实 `ERR_NOT_IMPLEMENTED`） | `:domain` | 禁服务逻辑；禁直连 `com.autoscript.bridge..`（本模块不挂 Router，挂载在 `:platform:capabilities`） |
| `:node-runtime-build` | **构建管线（不打包进 APK）**：Node 源码 recipe、NDK 编译、16KB 对齐门禁、产物 hash | CI 脚本 | — |

架构测试（archUnit）进 CI：验证「领域层零 Android import」「`:app` 非装配包不直连平台」「依赖方向无环」。
前者由各模块内 `ArchitectureTest` 按字节码校验（`ClassFileImporter().importPackages(...)`）；后两者由
`:domain` 的 `ModuleGraphTest` 按 build.gradle.kts 依赖边校验 —— 空模块（尚无源码）同样被覆盖，
且能拦住 Gradle 层反向依赖与依赖成环。

**例外不是开后门**：`:app` 碰 `:bridge:java` 与 `:platform:capabilities`/`:platform:system` 都只发生在 `com.autoscript.shell` 一个包（后者是 `SystemSpis` + `CapabilityNamespaces` 的生产装配，落点 `com.autoscript.shell.PlatformWiring` → `AppShellApplication.installWithFiles` 喂 `AppShellKit.assemble`）；`:platform:capabilities` 挂 Router 只碰 `:domain` 的 `NamespaceHandler`。越界由 `:app` 的 `ArchitectureTest` 量化执行（shell 之外的 :app 类碰 platform/bridge 即红）+ `:domain` 的 `ModuleGraphTest` 按 build.gradle.kts 依赖边校验，不是口头约定。

**本机自测（快速旁路；SDK 已配置后 CI 同款 `./gradlew` 亦可本机直跑）**：`tools/jvm-test.sh [--android-jar] <main-src-roots> <test-src-root>` 直接用 Gradle 缓存里的 `kotlin-compiler-embeddable` + JUnit Platform Launcher 编译并跑任意模块的 main+test 源码树；`tools/jvm-test-all.sh [模块名...]` 是逐模块最小依赖的全量驱动（纯 JVM 模块**故意不给** android.jar——`:domain` 里误加 `import android.*` 要能在本机直接编译失败，不被掩盖）。**旁路的结构性盲区**：kotlinc 直跑时 `java.*` 取自本机 JDK（有 `Process.pid`、JDK11+ `Files.readString`），AGP 编译取 android.jar 桩面（**没有**这些）——凡新增 `java.*` 较新 API 必须过一遍 `./gradlew`（2026-09-23 本机 SDK 首跑即抓出 `Process.pid`/`ProcessHandle`/unix 域/`Files.writeString` 四处，jvm-test 全绿掩着）。**`:ui` 不入旁路清单**：compose/`@Composable` 没有裸 kotlinc 配方，本模块的门只有 `./gradlew :ui:testDebugUnitTest`（CI 同款任务）。

`--android-jar` 只是**编译期桩**（取自 AGP transforms 缓存的 android-library `android.jar`）：`android.*` 方法体在运行期一律抛 `RuntimeException`，所以含 Android 源码的模块要做到「本机可测」，必须把 Android 接触面挡在可注入的 ops 缝后面（模式与落地清单见 `platform/system/README.md`）。这份脚手架是**本机提速用的旁路**，不替代 CI：`./gradlew` 仍是唯一权威（AGP/资源合并/Manifest 合并只有它能验），改动仍以 CI 绿为准。

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

- **消费侧已落地（2026-09-24，控制台屏）**：Kotlin 侧 `ConsoleCollector`（有界 2000、容量满丢最老并计数、`drain(sinceSeq, max)` seq 游标**非破坏**拉取） → 读口 `HostSummary.console(sinceSeq, maxLines)`（DTO `ConsoleSnapshot` 住 `:domain`，拼装 `ConsoleRead` 住 `:app` 壳装配包、纯 JVM 可测）→ `:ui` 第四页签控制台屏。呈现纪律：**游标只进不退、行累积**（刷新 = 增量拉取不是重画；并发同游标按 seq 去重）、**读失败保留旧行与游标**（瞬时失败抹掉用户已看到的日志比报错更糟；失败只亮原因）、拉满标「可能还有」不假装到底、**丢包非零不藏**（`droppedTotal` 上屏 —— 显示的不是全部得说出来）、在途执行两端对照随快照带上（§8.3：宿主读不到如实说读不到、**不渲染成某个状态**；分歧标红，判据仍在 `RuntimeController` 不在呈现层）。刷新时机与能力中心/任务中心同构：回前台/切页签现取，页大小 256，读失败不自激。**停止操作面同批落地（2026-09-24）**：读口 `HostSummary.stopRun(runId)`（`:domain`）→ `AssembledShell.stopRun`（壳持有的在途表 `RuntimeController.stop` → 池四步 quiesce，`AlreadyGone` 如实 false 不抛）→ `AppShellApplication.stopRun`（壳未装配抛）→ `:ui` 控制台在途行每行一个「停止」按钮（`ConsoleState.stopError/stopNotice/stopInFlight` 与读账分开记账、刷新现取归零；回执措辞：true = 已请求停止、false = 已不在途；挂起中按钮禁用）。与 `Scheduler.stopLastRun` 的分工：那是调度单槽快捷口（恢复重投会覆盖），本口按 runId 精确命中在途表、不受覆盖影响。
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
错误目录（前 20 个中最关键）：`ERR_TIMEOUT`、`ERR_STALE_HANDLE`、`ERR_PERMISSION_DENIED`（能力未授权/被降级）、`ERR_SERVICE_DISABLED`、`ERR_SCREEN_LOCKED`、`ERR_BLACK_FRAME`（FLAG_SECURE）、`ERR_CAPTURE_DENIED`、`ERR_ENGINE_STOPPED`、`ERR_ENGINE_CRASHED`（进程死）、`ERR_NOT_IMPLEMENTED`（本平台不支持，如 child_process）、`ERR_INVALID_PARAM`、`ERR_FILE_NOT_FOUND`、`ERR_FILE_EXISTS`（打包产物已存在等）、`ERR_DISK_FULL`、`ERR_NOT_FOUND`（UiSelector 未找到 → 可选 `NotFoundError` 对齐 Pro v9）。
映射规则：`Java Exception → 分类 → AutojsError`，保留 `javaStack`，JS `instanceof` 可判。
目录三处落字（`:domain` `core/Error.kt` 的 `ErrorCode`、`bridge/js/src/errors.ts` 的 `ErrCode` + `ERROR_CODES`、本文提及）由 `bridge/js/test/err-catalog.test.cjs` **三面对账**（Kotlin ⇄ JS 双向相等、同文件枚举 ⇄ 字面量表双向相等、文档提及必须两处都在；随 `npm test` 进 CI）——2026-09-26 首跑就抓到真漂移：`ERR_IO` 在宿主全线服役（zip/settings/images/spawn/打包），JS 目录独缺，脚本 `ERROR_CODES.includes('ERR_IO')` 为 false；已补码并被该门的「回潮」断言钉死。

### 7.7 性能关键路径（数量级目标）
| 链路 | 目标 | 设计 |
|---|---|---|
| 空 RPC（JS→:main→回） | p95 < 2ms | 直连 unix socket、零 JSON 二次解析、TSF 双队列 |
| 无障碍 `find → click` | 200ms 内 p60 / ~10ms 树读 | 紧凑索引树 + 按需属性 + 句柄（不全量序列化） |
| `captureScreen → findImage` | < 1s 且一次截图两次匹配 < 700ms | 屏幕帧→native 0 拷贝，模板匹配在 `libopencv.so`；**链路已通**（§18 第 8 项 (b) 2026-09-26 落地：截屏帧经 `ImageAnalyzer.ingest` 进 `images` 同一张帧表，"帧不通用"取消）—— 数字仍是**待实测**的验收口径（真机未量） |
| `findColor`（单人独立子图 1080p） | < 10ms | native 遍历（`cv::inRange` 逐分量包含 + `findNonZero` 取首个；ROI 是浅视图不拷像素；kleidicv 覆盖 `inRange` 面） |
| `matchTemplate` 1080p | < 40ms | OpenCV TM_CCOEFF_NORMED（实测它、不是早前写的 CCORR：见下注）|
| 紧凑树构建/传输 | < 15ms / 数十 KB | 预聚合属性，代价解析放"取用即取" |

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
> `prepareBridgeDistAssets`（`:app` 构建任务）把 **git 跟踪的** `bridge/js/dist`
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
> 含未 strip 的 libnode）。仍待真机：exec 与 dlopen 的设备侧红测（16KB 页机 +
> targetSdk 提取/执行策略），`.so` strip 归 CI 打包管线。

**符号面（动态 T，稳定 ABI）：**

| 符号 | 来源 | 用途 |
|---|---|---|
| `_ZN4node5StartEiPPc`（`node::Start(int, char**)`） | libnode.so | :nodeN 单进程单 isolate 入口（§5.1 一进程一 Start） |
| `_ZN4node4StopEPNS_11EnvironmentENS_9StopFlags5Flags` | libnode.so | quiesce 第④步后收尾（§5 推论 A：kill 必须归还槽位，Stop 即"正常死"的路径） |
| `napi_create_threadsafe_function` / `napi_call_threadsafe_function` | libnode.so | TSF 双队列的创建/投递（§7.3，见下） |
| `napi_module_register` / `napi_module_register_by_symbol` | libnode.so | addon 模块注册（`@autojs/bridge-native` 即一个 N-API 模块） |
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
2. `dlopen libnode.so`（RTLD_NOW|RTLD_GLOBAL——addon 的 `napi_*` 从 libnode 动态表
   解析；16KB 门禁已过，PRODUCT 哈希 `3cadbcdf…` 见 `/tmp/nrb-out7/SHASUMS256`）；
3. `dlsym _ZN4node5StartEiPPc` → `node::Start` 单 isolate/context，argv =
   `node -e BOOTSTRAP -- <script> [args…]`（无 addon 则直接跑 script）：BOOTSTRAP 预载
   `@autojs/bridge-native` addon → `setSocketFd`（首次注入即拉起读线程）→ 读
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
- 实现：`:engine:node-process`（NodeFactory），经 **Provider/SPI** 注入（`QuickJSFactory` 随沙箱裁掉，§18 第 1 项；缝留在原处，将来真要第二个引擎不必改接口）。

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
- **裁决已落地**（`EngineWatchdog` 的 drift 连段 + `KillCause.DRIFT`）：单轮分歧只是真机的窗口期常态（宿主刚推 STOPPED、池还没 quiesce 完），连续 `driftKillThreshold` 轮（缺省 3 轮 ≈ 1.5s）还对不上才是真分裂 —— 此时经 `RuntimeController.killRun(runId, DRIFT)` 杀掉重来，不猜哪一侧对（校准不是替某一侧抹平差异）。`Tick.drift` 照常每轮记账（谁看见谁处理），`Tick.driftKilled` 单独列出分歧杀供诊断区分"病死"（三路判定）与"分歧杀"；连段中间弥合一轮即从头数，失踪/被杀的 run 清零（防 runId 复用背旧账）。`DRIFT` 归 `CRASHED`（`EngineStateMachine.onKill`：非 REQUESTED 一律 CRASHED），与"管理者主动停"（REQUESTED → STOPPED）区分"自杀"与"他杀"。阈值是 `EngineWatchdog` 构造参数（`DEFAULT_DRIFT_KILL_THRESHOLD`），装配层可配。
- 上图里的 `SUSPENDED`（多源计数）与 `PENDING` 在 P0 **均不存在**：`EngineStatus` 枚举里没有 SUSPENDED，`awaitCompletion` 只把 RUNNING 判活（看门狗口径一致，见 §8.4）。**任何依赖 SUSPENDED 的设计（暂停恢复、诊断暂停计数）仍然没有代码基础。**

### 8.4 看门狗（三路，防死循环/僵尸/饥饿）
- **心跳**（数据面，周期 ~500ms，携带自回事务序列号）：连失 K 次 → 重启判定；心跳与操作 TTL 互补——**await 的 RPC 有 TTL，整体执行有心跳**。
- **CPU 外带差分**（`:main` 独立线程读 `/proc/<pid>/stat` utime+stime 差分，**不依赖 Node 合作**）：单核持续 >95% 超过阈值（默认 30s，可配）→ 杀。防 `while(true)`/Promise 风暴这种「心跳还活着但永不放行」的形态。
- **内存**：RSS 超阈值（分级配置，池缩容信号）→ 降载警告，连续超阈 → kill + archive。
- 看门狗**不作为业务**：只输出「恢复建议」（重启/重试/降级），不自动无人值守自愈（§1 诚实原则）。

**P0 已落地**：`:app-service:runtime` 的 `WatchdogPolicy` 是三路纯判定（`WatchdogSample{pid,status,heartbeatMillis,cpuPercent,rssBytes} → Healthy | Kill(cause, reason)`），默认阈值：心跳 500ms × 连失 3 次、CPU ≥95% 持续 30s、RSS ≥512MB；只对 `RUNNING` 判活；`RuntimeController.judge()` 委托它，裁决落点 = `killRun`/`killAll`（kill 权威 §4.1）。
**采样已落地**（`ProcessMonitor`，`:app-service:runtime`）：`/proc/<pid>/stat` 与 `/proc/<pid>/status` 的读取 + 折算，产出同一份 `WatchdogSample`，交给 `WatchdogPolicy` 判定。诚实口径写死在实现与单测里：CPU 分子 = 两次 `utime+stime` 差（jiffies → ms），分母 = 调用方给的墙钟间隔，**不折算单核**（多核满载必须看着就 >100%，否则漏杀 Promise 风暴）；`comm` 含空格括号时从**最后一个 `)`** 之后切字段；首采样 / 换 pid / 时钟回拨 / 计数回绕一律回 0.0%，不给假差分；`/proc` 不可读 → 整份样本回 null（按「无法度量」处理，不猜健康），`status` 读不到只丢 RSS 这一路，不作废 CPU 样本。**本类只采样不裁决**（裁决仍归 `WatchdogPolicy`，kill 仍归 `RuntimeController`，runId→pid 归属表仍归 `:app`）。

**调度循环已落地**（`EngineWatchdog`，`:app-service:runtime`）：§8.4 的三路分工终于有了周期性调度者 —— `RuntimeController.watchAnchors()` 交出在途执行的 `WatchAnchor{runId, receipt.pid}`（**pid 取 `EngineRunReceipt.pid` 快照，不是 `ScriptEngine.pid` 当前值**；槽位复用后两者会分叉），`ProcessMonitor` 按 pid 分别记账采样本，`RuntimeController.judge()` 裁决，`Kill` 落 `killRun(runId, cause)` —— 判据、采样、执行三方仍是三个类，调度者只负责「到点把三者接起来」，不自己长判定口径。`AppShell.assemble` 交出 watchdog 实例与 `ProcessMonitor`/`heartbeatMillis` 注入缝，`startWatchdog(scope)` 之前不转；生产路径由 `AppShellKit.assemble` 在装壳时就转起来（§4.1 的真实调用点）——域缺省是壳自己持有的 `SupervisorJob`（`AssembledShell.close` 先停轮转再关池与持久句柄），调用方也可传自己的域（那就自己负责停：`EngineWatchdog.start` 的 KDoc 写死了这条所有权规则）。
- **采样周期 = `heartbeatIntervalMillis`**（`WatchdogPolicy` 那条因此从 private 变 public）：周期长于心跳阈值会把活引擎判死（500ms×3 的阈值配 3s 轮询 = 假阳性），controller 的这份 policy 经 `RuntimeController.watchdogPolicy()` 原样交给 watchdog —— **只有一份阈值**，不在装配层另造一个。
- **pid 终结即忘**：无论正常结束、被 watchdog 杀掉还是本轮采样后不在途，`EngineWatchdog` 都调 `ProcessMonitor.forget(pid)`。Linux 复用 pid，不遗忘等于让新进程背旧 CPU 基线（虚高 → 误杀）；pid 落到别的 runId 时 CPU 历史整段清零，同理。
- **不另建 pid→runId 表**（§8.4 原口径不变）：归属表只有一份，就在 `RuntimeController` 的在途账里。`:app` 侧再抄一份必然漂移（stop/kill 路径不止一条），`watchAnchors()` 是它的只读投影。

**心跳一路已接线**（§8.4 缺口② 补齐）：`HeartbeatLedger`（`:app-service:runtime`）是心跳的宿主侧收单方，`RuntimeController.heartbeat(runId, seq)` 是它的桥侧入口，`RuntimeController.heartbeatMillis(runId)` 是看门狗的问讯口 —— `EngineWatchdog` 缺省就问 controller 那份账本（`AppShell.assemble` 的 `heartbeatMillis` 缺省 null = 装配时接真账本；显式传 `{ null }` = 明示这一路不接，看门狗如实记 `Tick.noHeartbeat`）。JS 侧 `engines.heartbeat {runId,seq}` + `startHeartbeat(runId)` 定时打点（`unref` 定时器，不保活事件循环）。
- **序号即真伪**：账本只认**递增** `seq`。同/旧 seq 一律拒收（只累计 `staleBeats()`，不刷时间戳）—— 否则宿主张力下积压的旧心跳会把一个**已经死了**的 run 一直喂成活的，那正是心跳这一路要抓的形态。
- **从未打点回 null，不回 0**：0 会被当成「刚刚打过」，失联判定永不触发；null = 量不到，看门狗据此记 `noHeartbeat`。
- **与 run 同生共死**：`stop`/`killRun`/`killAll`/`settleDone`/`settleKilled` 五条终结路径全部 `forget(runId)`。不遗忘 = runId 复用时新 run 背上一段「假年轻」，失联判定被推迟到下一次自然打点。
- **无主心跳不建账**：`RuntimeController.heartbeat` 先验在途再落账本 —— 不在途 runId（已结算/从未存在）回 `false` 且不记账。否则迟到/重发的心跳会在复用 runId 上复活旧账，或把活 run 的账顶出记账上限；桥侧未知 runId 仍 Ok `false`（不是调用方错误，不 4xx），JS mock 宿主复刻同一口径。
- **仍不伪造**：拿 watchdog 自己的轮转周期当心跳依旧是禁止的 —— `while(true)`（心跳活着、CPU 打满）只靠外带差分抓得到。

至此 §8.4 三路判据、采样、调度、心跳打点全部闭环。**pid 半边已接线**：`NodeProcessEngine`（`:engine:node-process`，Kotlin spawn）的 `EngineRunReceipt.pid` = spawn 瞬间真子进程 pid（`NodeProcessEngineRealSpawnTest` 实测 >0 且非自身；`:app` E2E 经 `watchAnchors` 锚点 pid>0 复验），看门狗 `/proc` 采样锚点在 spawn 路径上是活的。**心跳的生产链已备**：spawn env 下传 `AUTOSCRIPT_RUN_ID` → kBootstrap 500ms 自动打点 / 桌面脚本显式 `startHeartbeat`，E2E 实测 `heartbeatMillis(runId)` 落账非 null；桥监听 `BridgeSocketListener` 亦已接（abstract 绑定 + uid 门禁 + `NewlineFrameServer` serve，JVM 假缝单测；bind 失败 = 离线降级不注入名，见 §7.5/§7.8）。addon 的 JS 消费面亦已落（facade `attachNative()`，§7.8）。**设备面只剩真机联调**（jniLibs 三件套 + addon 资产落位 + facade dist 随包与打包入口 attach 接线 2026-09-24 均已落，见 §12.4/§19 切片路线）未落；在那之前，真机上当前生效的是「未 spawn / 宿主不给 pid → noPid」「宿主不打点 → noHeartbeat」两条量不到路径 —— 这由 `Tick` 的三个清单如实区分，不是一个笼统的 `unmeasurable`。

### 8.5 崩溃恢复与幂等（checkpoint 意图日志）
- `:main` 的 scheduler 持久化 **意图日志（intent log）**：`RUN_START(projectId, entry, runNonce, scheduledAt) → …execute… → COMMIT(result)` append-only（SQLite，启动即回放）。
- **恢复只跟随 COMMIT**：进程/手机重启后，未 COMMIT 的 run 视为「未完成意向」→ 重新入队，但生成**新的 runId + 保留 runNonce**；执行体用 `runNonce` 做**幂等键**（外部副作用目标幂等，如「只发一次」的通知 id、datastore 原子键），杜绝重复业务副作用。
- **rerun 新 RunRecord**（每次重跑都是新 runId）——满足批判「resume=新 runId」语义；「断点续跑」只对纯内存任务可选，涉及副作用任务默认不允许自动续。

**归档入口（已落地契约，§8.5）**：两套 runId 是「一个真值的两个投影，必须成对写入」。

| 侧 | 身份字段 | 寄存器 |
|---|---|---|
| 意图日志（scheduler） | `intentRunId`（`IntentRun.runId`） | intent log |
| 引擎运行记录（engine） | `engineRunId`（`EngineRunReceipt.runId`） | `RunRecord(id)` |

`:domain` 的 `EngineRunLink(intentRunId, engineRunId)` 是关联契约；`RunArchive` SPI 是引擎侧档案（`put(record, link)` / `record` / `link` / `recordsOfIntent` / `recordsOfProject` / `unfinished`）。纪律：终态（`SUCCEEDED/FAILED/CRASHED/CANCELLED`）append-only，**不可改写、不可复活**，违反必须响亮失败而不是静默吞。只写一侧 = 孤儿记录（「引擎在跑而任务中心查不到」或反之），`DispatchReport.link` 在门禁拒绝/排队超时/启动失败时如实为 null。接线在 `:app` 的 `ControllerRunDispatcher`（拿到 Receipt 后生成 link）+ `AppShell`（scheduler 持 `RunArchive`）。**持久形态已落地**：`JournalFileStore`（意图日志）+ `FileRunArchive`（运行档案）同用一套 jsonl 行格式（共享 `JsonLine`），`force(true)` + 启动 replay + 半行容忍；两文件分开存是刻意的 —— 键不同（intentRunId vs engineRunId）、只写一侧的孤儿在格式上才可见。

孤儿结算两条路（都只在 `recoverUncommitted` 里跑，运行期绝不扫——那会把正在跑的执行误判成孤儿）：
- **逐 intent**（`settleOrphanArchive`）：本次要 reopen 的遗留意向，其关联档案里还没终态的记录 → 如实 `CRASHED`（宿主死时没结算）；
- **全档空档**（`settleVoidArchive`）：`log.commit` 与 `recordLink` 不同事务，中间崩溃会留下「意图行已终态、档案停在 RUNNING」的孤儿 —— 它挂不到任何未 COMMIT 行上，逐 intent 那条路永远看不见。恢复刚起来时引擎池必空，档案里所有非终态记录都只能是上一进程遗物，此时按 `unfinished()` 自报统一补 `CRASHED`（无 link 的记录跳过：独立执行不属意图日志管辖）；link 原样保留，任务中心仍可按 IntentRun 追到这条失联记录。

### 8.6 调度系统
- 触发源五类：`定时(cron/alarm) `、`Intent/广播`、`事件(无障碍/通知)`、`用户点击`、`引擎内部 engines.exec`。
- **SchedulerProvider SPI**：同一接口后 P1 可切 `WorkManager` 之外的实现（保活场景自持 alarm + 注册 receiver）。触发→拉起引擎进程→注入 API→归日志。
- **守时语义诚实化**（批判 11 定案）：设备**亮屏 + 解锁**是保底契约；预热闹钟 `scheduledAt - 60s` 先拉起进程（引擎进程需时 ~1s），axexact 闹钟失败时降级到 setWindow 并在 UI 标注「可能偏差」。**熄屏任务**＝任务显式声明三态之一：`screen.on`(需 wakelock+确认)/`screen.any`/`screen.off`(禁 MediaProjection，只允许无障碍+网络)。
- 触发时若引擎池满 → 排队，绝无静默丢任务（日志+UI）。
- **排队上限由投递方给**：`ControllerRunDispatcher` 的 `queueTimeoutMillis`；到期 → `RunOutcome.Cancelled`（"排队取消"口径：未获槽、未执行，link 为 null）。
- **P0 已落地**：满池排队**默认有界**，不再有"默认无限等"这条路。`queueTimeoutMillis` 显式覆盖优先；不传则按触发源分级取默认上限（`ControllerRunDispatcher.DEFAULT_QUEUE_TIMEOUTS`）：`ENGINE_INTERNAL` 15s（满池下的跨引擎调用是"持有者等后来者"的嵌套形态，必须最先爆，否则变跨引擎死锁）、`USER_CLICK` 10s（人盯 UI，等不及就如实 Cancelled，不让按钮原地转圈）、`INTENT_BROADCAST`/`EVENT` 60s（外部涌入本应容忍排队）、`TIMED` 120s（守时任务已承诺"亮屏+解锁保底 + 可能偏差"，2 分钟只为满足铁律 3，不追求抢跑）。分级表是**注入缝**（构造函数参数），装配层可换成自己的口径；`queueTimeoutMillis` 传 0 视为漏配，构造即 `IllegalArgumentException`——0 等于"永不允许排队"，与"绝不静默丢任务"相反。
- **P0 已落地（deadline 记账）**：`PendingRun.deadlineMillis` + `isExpired(now)` 记下"本次投递的到期时刻"，与 dispatcher 的排队上限**同源但不同职**——dispatcher 那侧管"在途排队等不等得起"，deadline 管"宿主重启之后这条意向还值不值得投"（崩溃恢复面对的是另一件事：进程死过一次，用户早走了/外部事件早凉了）。期限写在 `IntentRun.deadlineMillis` 上随 RUN_START 行落盘，`reopen` 原样带到新行（恢复重投不得变期限，否则同一意向两套到期口径）；`Scheduler.onTrigger` 按 `排期时刻 + deadlineFor(触发源)` 填，`recoverUncommitted` 遇过期意向照样 `reopen` 封口记账，但**不再 dispatch**，直接 COMMIT [RunOutcome.Cancelled]（`RecoveryRecord.expired` 标出，任务中心按 runId 读到"为何没跑"——绝不在恢复路径里静默跳过）。两处口径同源由装配层保证：`Scheduler` 的 `deadlineFor` 与 `ControllerRunDispatcher.queueTimeout` 默认同喂一张分级表（`DefaultDeadlines` 与 `DEFAULT_QUEUE_TIMEOUTS` 数字一致，前者是缺省值不是契约，装配层可各自覆盖）。
- **P0 已落地（调度侧停止与收口）**：`Scheduler.lastHandle`（`EngineStopHandle{idLink, name, runNonce, stop}`，§12.3 engines.exec 的调度侧投影）只在 dispatcher 真的产生了引擎执行（`DispatchReport.link != null`）时持有 —— 门禁拒绝/排队超时/启动失败没有可停的东西，不持有假句柄；停止入口由 dispatcher 经 `DispatchReport.stop` 填权（`:app` 的 `ControllerRunDispatcher` 在 start 成功时绑定 `RuntimeController.stop` → 池四步 quiesce；三条未产生执行的早退分支回 null stop；已结算后调用落 AlreadyGone 幂等 no-op，永不升级为 kill）；`stopLastRun()` 转发句柄的 stop（成功不清句柄，停止幂等），`canStopLastRun()` 从句柄现算；`sink()` 撤销全部触发器并置位（此后 `onTrigger` 早退）；`quiesceThenStop()` = sink → 停最近一次 run → 返回句柄供归档（§13 铁律 4 的调度侧部分）。线程契约诚实声明：lastHandle/sinking 读并发安全，写仅发生在 `onTrigger` 内（装配层负责把五类触发源串行化）。
- **P0 已落地（执行侧急停）**：`RuntimeController.forceStopAll(cause)` —— 进程级急停的显式入口（应用被杀/系统回收/测试收口），与请求驱动的 `killAll` 区分（killAll 是裁决/停全部的落点，在途表经 guard 串行收走；forceStopAll 只做杀全部 + 清在途表 + 忘心跳，不走请求语义）。**装配层有序收口已落地**：`AppShell.shutdown(cause)` = `scheduler.quiesceThenStop()`（先 sink 拒收新投递、再停最近 run）→ `controller.forceStopAll(cause)`（再杀全部槽位并复用）。顺序不可反：先杀后停会在调度不知情窗口继续投递。两步都幂等；返回调度侧被停句柄供归档（无句柄时为空，不假装停过）。
- **仍待覆盖**：调度链路**别说"无悬挂风险"**——deadline 只关掉了"重启后重投过期意向"这一路，引擎侧 `waitCompletion` 超时不发起的场景还没人管（在途 run 没人收尾时 watchdog 是唯一兜底）。
- **P0 已落地（Android 触发侧）**：`AlarmSchedulerProvider`（`:app` 装配层）把 `SchedulerProvider.registerTrigger` 翻译成闹钟——预拉提前量 `wakeAheadMillis` 是契约字段（60s，测试与调用方同一份值，不藏常量）；**提前量只向前推、不向后扯**（排期已到即夹到当前时刻，ROM 对负延迟处置不一）；`canScheduleExact` 为假时降级 `setWindow` 且**记账**（`degradedTasks()`，`taskId → 排期时刻`），能力中心据此标注「可能偏差」——**不静默降级**。框架调用（真 `AlarmManager`）在 `AndroidAlarmPort`，本类**无判断**：taskId → `KeyStableHash` 定 requestCode（同 taskId 恒同，重复 arm 是替换）、`setExactAndAllowWhileIdle`/`setWindow` 两个调用点、取消 = `alarmManager.cancel` + `pendingIntent.cancel`（两个都要）。`PendingIntent` 在 API 31+ 必须 `FLAG_MUTABLE`（系统要填 `EXTRA_ALARM_*`）。回投侧是**静态注册**的接收器 `AlarmReceiver`（精确闹钟响时进程可能已被 ROM 杀掉，`registerReceiver` 收不到）走 `goAsync()` 在广播窗口内把 taskId 经 `AlarmDispatch` → `SchedulerAlarmRoute` 送回 `Scheduler.onTrigger`（TIMED 来源 + 闹钟真实排期），于是 runNonce/意图日志/dispatcher 口径与手动触发完全一致。**装配前/后的漏投不静默丢弃**：没接路线的闹钟进 `AlarmDispatch.missed()`（同一 taskId 只留最新一条，`drainMissed()` 清账），`AppShellApplication.missedAlarms()` 供能力中心如实呈现「闹钟已响但调度未就绪」。
- **P0 已落地（屏幕门禁的生产实现）**：`AndroidScreenGate`（`:app`）——`SCREEN_ON` 在两个系统查询缝（`interactive` = `PowerManager.isInteractive`，`deferWakeLock` = 持锁方）任一为假时**如实 `Deny`**，不降级成「锁屏也跑」（那条路径的表现是「任务成功、实际什么都没发生」）；`SCREEN_OFF` 先经 `ScreenOffGuard` 收起画面类能力再放行（无障碍 + 网络在锁屏下真实可用）；`ANY` 放行。两条缝的值由 JVM 单测注入，判断逻辑因此可测而不必 Mock 框架对象。**`AllowAll` 与 `AndroidScreenGate` 在 `ANY` 上必须同结论**（`AndroidScreenGateTest` 有断言守着），否则同一条任务在单测里放行、真机上被拒，差别只在现场暴露。

- **P0 已落地（任务中心读口与屏，2026-09-24）**：`HostSummary` 增 `taskCenter()`（挂起：读任务注册表 + 运行档案两个持久寄存器；**读失败抛** —— `:ui` 如实显示「读任务失败」，而不是冒充「一条任务都没有」，那是「读成功且真没登记过」的另一种事实）→ 拼装 `TaskCenterRead.snapshot`（`:app` 壳装配包，纯 JVM 可测，**不自带 IO**：取数由调用方注入）：`TimedSchedule` 三态 → `ScheduleSpec` 三态**逐字段**映射不聚合（Cron 也保留成行 —— 不可能日期/坏行算不出下一跳时留名不续排，任务不从列表凭空消失）、`ScreenGuarantee` → `ScreenRequirement` **按名对表**（不用 `ordinal`；`TaskCenterReadTest` 有两边枚举同集断言，调度器加值先红再谈映射）、停用任务**不问下一跳**（问了也会得到答案，而那个答案会让人以为停用任务还会跑）、恢复账 `total`/`expired`/`failureText` 三笔分开且**失败时 `retried=0`**（`total-expired` 会把失败报成成功）→ DTO 住 `:domain` 的 `TaskCenter.kt`（`TaskCenterSnapshot`/`ScheduledTaskRow`/`RunRow`/`RecoveryRow` —— 调度器类型只在 `:app`，呈现层照旧只依赖 `:domain`）→ `:ui` 的 `TaskCenterScreen` + `TaskCenterState`（纯状态 DTO，JVM 可测）：三页签之二、**没读到 ≠ 一条任务都没有**（`NOT_LOADED` 与 `failed` 分开且保留原异常文案）、下一跳为 null **不编时间**（停用与 Cron 算不出两义由 `enabled`/`schedule` 分辨）、未结算执行文案点破「**不是此刻正在跑**」（`unfinished()` 只增不减，这栏只可能是上一进程遗物）、降级任务标「可能偏差」。刷新与能力中心同构：回前台/切页签现取一次，读失败不自激重读。读切片落地后，登记/取消/立即执行随操作面接上（见下条）。
- **P0 已落地（任务操作面：登记/取消/立即执行，2026-09-24）**：写口走 `HostSummary` 三方法 —— `registerTask(TaskRegistration): String`（回分配到的 id；`:domain` 的 `TaskRegistration` 是**纯数据** DTO，校验不在 DTO 里）/ `cancelTask(taskId)`（幂等：tombstone 先行，ghost id 照样返回成功 —— 用户视角"它已经不在了"）/ `runTaskNow(taskId)`（`TriggerSource.USER_CLICK`，**挂起到本次执行结算**再返回）。两个实现方都收口：`AppShellApplication` 壳未装配即抛（不冒充成功），`FakeHost` 同步长出 UOE（替身纪律：HostSummary 长一个成员，替身同批跟上，否则 `:ui` 单测编译断）。语义闸门唯一落点是 `:app` 的 `TaskCenterOps.toScheduledTask` —— 与桥侧 `WorkManagerNamespaceHandler` **同规则两侧各测**：空串三字段点名拒绝、`Once.delaySeconds<0` 拒、`Daily` 靠 `TimedSchedule` 的 0..23/0..59 require 拒、`Cron` 经调度器 `CronTab.parse` 校验（非法点名哪一段，两侧非法样本同源）、timeout≤0 拒、非法时区拒、`ScreenRequirement` **按名对表** `ScreenGuarantee`（同集断言先红）。形状解析（整数文本、trim）收在 `:ui` 的 `RegistrationForm`，语义规则不复述一份（两处规则必然漂移）。诚实边界三条：(1) **`runTaskNow` 不哑火** —— `onTrigger` 对 sinking/缺席任务静默 return，所以 `AssembledShell.runTaskNow` 先查再触发，查不过就抛（检查与触发间的 TOCTOU 窗口已接受并写进 KDoc）；不改 `onTrigger` 签名（~28 处调用点全是语句位，改返回型会让非 void `@Test` 静默跳过）；(2) **回执不说脚本成败** —— `onTrigger` 只到结算，成败在意图日志/控制台，回执写「执行成败见控制台」；UI 侧 `TaskCenterState.opInFlight` 挂起期间禁用全部操作按钮（防双击双投）；(3) **Once 立即执行即出册** —— 调度器 `finally` 对 Once 终态化，卡片事前标「一次性任务：执行过后自动移出注册表」，回执点破「已执行并出册」，刷新后卡片消失是排期语义不是被取消。操作失败**不清任务清单**（`opError` ≠ `loadError`，`copy` 保留 `tasks` —— 抹掉会让用户以为任务全没了）；`opNotice` 只由成功写入，`of()`/`failed()` 归零（现取纪律），`performTaskOp` 刷完表再盖回。取消走 `AlertDialog` 一次性确认（误点的代价 = 手工重登记）。登记表单**缺省全空**（cron 格缺省 `0 9 * * *`）—— 误提交过不了 `:app` 闸门，不产生幽灵任务。UI 侧操作是 `HostSummary` 的**独立写口**，不经 `workManager` 桥（桥是脚本侧命名空间；宿主自己的操作面不该绕进程一匝）。
### 8.7 保活与电源
- `:main` 持 **specialUse FGS**（`onCreate` 启动，`TYPE_SPECIAL_USE` 勾选 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE="automation"`，无超时）。
- 电池优化白名单、精确闹钟、开机启动、后台 Activity 启动豁免(**BAL**：仅允许 overlay 可见窗口路径/notification 触发路径)、自启动被 ROM 关闭——**全部入 PermissionCenter 三态门禁**（未授权=黄，被 ROM 杀=红且给跳转指引）。
- 长跑脚本自身需要**WakeLock** 时用 `power_manager`（引擎进程请求 → `:main` 对应 FGS 加唤醒锁的 acquire/release，配套超时自动释放）——**已落地（2026-09-24）**：`:app` 的 `PowerManagerNamespaceHandler`（`acquire`/`release`/`status` 三方法，直驱 `foregroundKeeper()` 的同一本 `WakeLockLedger`，账本语义零改）+ `AppShell.assemble` 的 `powerManagerHandler` **独立缝**（与 datastore/zip/settings/notification/clipboard 同形，不入 `systemHandlers` 束）+ `AppShellApplication.installWithFiles` 现建喂缝 + `bridge/js` 的 `power.ts`（`acquire`/`release`/`status`）与 `power.test.cjs` 双侧契约。诚实口径三条：脚本锁必须限时（无期限只属框架 token）、token 服务端分配（脚本自带会互撞/互释）、取不到锁回 `ERR_SERVICE_DISABLED` 且未记账（门禁据此拒绝 `SCREEN_ON`）；直驱账本不走 `ForegroundKeeper.start(token)`（那个单槽只属框架，调两次互踩 `frameworkToken`）。
- **P0 已落地（`:main` 侧 FGS + 真唤醒锁，2026-09-23）**：三个可分离的缝，判断全在 JVM 可测面，系统接触面各收在一个类里。
  - **`WakeLockOps` / `WakeLockLedger`**（`app/.../shell/WakeLock.kt`）：`AndroidWakeLockOps` = 真 `PARTIAL_WAKE_LOCK`（`setReferenceCounted(false)`，acquire/release 异常一律吞成 `false` + 日志）；`WakeLockLedger` = **token 引用计数 + 超时自动释放**——`hold(token, timeoutMillis)` 只在**首次**（账本为空）时真取锁，**取锁失败不记账**（"记了账却没锁"是假绿之源）；`release(token)` 用"先查存在再删"（超时 token 的值为 null，照样能释放）；`sweep()` 释放到期项并返回名单；`isHeld()` = **账本非空 ∧ `ops.held`**——两侧任一说"没有"就一律算没锁。token/超时的形状就是给 P1 `power_manager` 预留的插口（引擎进程请求 → FGS 加锁走同一账本）——该命名空间已落地（见上条），账本语义零改，兑现了预留时的承诺。
  - **`ForegroundOps` / `ForegroundServiceBase` / `ForegroundKeeper`**（`app/.../shell/ForegroundOps.kt`、`ForegroundKeeper.kt`）：`AndroidForegroundOps` 管 `startForeground`/`stopForeground`，API 34+ 传 `FOREGROUND_SERVICE_TYPE_SPECIAL_USE`，通知走 `ForegroundNotifications`（渠道 `autoscript.foreground`）；`ForegroundServiceBase` 是**服务不自装配**的落点——服务的 `onStartCommand` 按 `ACTION_START`/`ACTION_STOP` 分支，Keeper 从进程级邮箱 `ForegroundHost.keeper` 现取（服务**不持有** Keeper/账本，避免 service → 根包成环；`START_NOT_STICKY`：重启路径没有 Keeper 上下文，续期统一走 `Application.onCreate` 的同一条装配路径，与 `BootReceiver` 同纪律）；`ForegroundKeeper.isActive()` = **`ops.foregroundRunning` ∧ `wakeLocks.isHeld()`**——"请求过" ≠ "生效了"；`stop()` 在系统仍报前台时返回 `false`（保持 drain 路径活着，不假装停干净），随后由 `renew()` 兜；`renew()` = sweep 到期锁 → 账本还持着而服务掉了就补拉 → 账本空了但服务还开着就停掉；守护 ticker（15 分钟）只做这两件事，`Throwable` 全吞（`scheduleWithFixedDelay` 一旦抛出就静默停摆）。
  - **屏幕门禁收口**：`AndroidScreenGate.of(...)` 的 `deferWakeLock` 生产实参已是 `WakeLockLedger::isHeld`（`AppShellApplication.screenGateOf`）。**§8.7 原来那条"恒真 = 明写的待接"就此作废**：现在熄屏 + `SCREEN_ON` 的真实表现是「锁没拿到就 `Deny`」，且 `Deny` 的判词与账本一致——不是靠恒真放行后再指望系统。
  - **如实呈现**：`ShellSummary.keepAliveActive`（`:domain`，**不带默认值**——每个产出方必须显式回答"保活到底生效没有"，漏填编译期就炸）由 `AppShellApplication` 填 `keepAliveActive()`；`:ui` 首屏据此直说「保活已生效」/「保活未生效：熄屏的亮屏任务会被拒绝」——这是"任务为什么没跑"的直接答案，不藏在二级页。
  - **诚实边界**：`onTerminate()` 在真机上**从不被调用**（进程死时系统自行回收 wakelock），它存在只为测试/模拟器收口 + 给"谁来停"一个代码落点；保活未生效时 `AppShellApplication` 记 `Log.w` 并在 UI 上显示红色，**不降级成"锁屏也跑"**。

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
- **已落地（Kotlin 侧）**：`A11yNamespaceHandler`（方法表与 payload 见该类 KDoc；构造只收 `:domain` SPI `UiNodeTreeReader`/`UiActionExecutor`/`InputProvider`，Android 真实现替换内存树/输入即插——handler 逻辑不变）+ 内存窗口树/输入替身，共 62 项 JVM 单测（`A11yWaitForTest` 5；app 侧挂载测试另计）；**Kotlin 侧 `waitFor` 读的载荷键是 `conditions`**（与 `findOne` 同构；轮询等待是宿主责任，内存树是单次快照，`timeout/interval` 只透传回显，不伪造等待）。JS facade `a11y.ts` 的 `waitFor` 已对齐：发 `conditions`（曾发 `selector`，会在白名单外字段上回 `ERR_INVALID_PARAM`——已修，两侧同构），其余方法键早已对齐。**`waitFor` 回 boolean，`findOne` 回 `{ref}`（别混）**：`waitFor` 命中回 `Ok "true"`、无匹配回 `Ok "false"`，**不回**节点体也不回 `Err NOT_FOUND` —— JS facade 的 `waitFor` 是 `Promise<boolean>`（§12.3 `const ok = await auto.a11y.waitFor(...)`，`result === true`），复用 `findOne` 的回包路径会让它恒 `false`（这正是刚修掉的漂移：Kotlin 侧曾是 `"waitFor" -> findOne(...)`，两侧各自的测试都没抓到）。**参数错误仍是 `Err ERR_INVALID_PARAM`**，不一并折成 `false`（「没等到」与「发错了」是两回事）。两侧各有钉它的测试：Kotlin `A11yWaitForTest`（5 项）、JS `a11y.test.cjs` 的「waitFor 返回 boolean」用例。**Android 真实现已落**（`:platform:capabilities`，分层两答）：语义层 `AndroidUiTree`（树+动作一体、句柄注册表共享——与 `InMemoryUiTree` 双身份同形）+ `AndroidGestureInput` 只认 `A11yBridge` 缝（纯 JVM，假桥注入跑全部选择器/句柄/手势逻辑）；设备层 `AutoScriptAccessibilityService`（清单+配置随库合并）实现同一接口并在 `onServiceConnected` 登记 `A11yServiceHolder`、`onDestroy` 清空。**桥面方法**（root/findBySelector/findByText/剪贴板/手势）服务未连 → `ERR_SERVICE_DISABLED`；**句柄面动作**先解注册表（未登记 → `ERR_STALE_HANDLE`；已登记但服务已死 → `refresh=false` → `ERR_STALE_HANDLE` 并回收）；`MODAL` 作用域以焦点窗为模态代理（AOSP `AccessibilityWindowInfo` **没有** `isModal`，主源码实证）；手势关门位读服务 `CAPABILITY_CAN_PERFORM_GESTURES`（系统没有 `AccessibilityManager.canPerformGestures()`，同样主源码实证——设计里这个方法名是错的）；四向滚动走 `AccessibilityAction.ACTION_SCROLL_*.getId()`（顶层 int 只有 FORWARD/BACKWARD）。ArchUnit 量化分层：`android..` 只许服务三件（`AutoScriptAccessibilityService`/`ServiceBridge`/`ServiceNode`），语义层碰 android 即红；capabilities 本机测试随之切 `--android-jar`（运行期 stub 不碰——测试全走假桥）。事件环 `A11yEventRing`（有界 512、`nodeHandle` 恒 null 不伪造句柄、type 用 `windowStateChanged`/`windowContentChanged`/`viewScrolled` 诚实名）。

### 9.2 截图与图像管线（`media_projection` / `image` / `@autojs/opencv`）
```
FrameSource (SPI)
  ├─ AccessibilityScreenshotSource  API34 takeScreenshotOfWindow · 333ms 节流 · 默认
  └─ MediaProjectionSource          会话式 · createScreenCaptureIntent→同意→FGS(type mediaProjection)→createVirtualDisplay
       └─ Surface → ImageReader(maxImages=2~3 对象池) → Frame 进入 libopencv.so
            └─ 灰度/裁剪/缩放/旋转/找色/模板匹配/特征(ORB)/颜色查找 — 全 native, 0~1 拷贝
               （计算核八算子全落：找色/模板匹配/灰度/裁剪/缩放/旋转/特征，前两者已开桥面；P1 native 面收官，剩桥面消费方）
```
- 截图对象生命周期：JS `Image` 句柄 → native 帧句柄；`recycle()` 显式 + finalize 兜底；`dispose` tombstone 协议同 §7.4。
- `FLAG_SECURE` → 分类错误（§7.6），不返回黑图（让脚本可判断）。
- **已落地（Kotlin 侧）**：`ScreenshotSource`（333ms 节流 / generation=1 单帧句柄 / 会话 open-close；`recycle` 已升为 `:domain` `FrameSource` SPI 方法）+ `ScreenNamespaceHandler`（构造只收 `FrameSource` SPI，`capture/recycle/startCapturer/nextFrame/closeSession`）。**Android 真实现已接（§9.2 a11y 截图路径）**：`AndroidFrameProducer` 经 `A11yBridge.{screenSnapshot,takeScreenshot}`（`AutoScriptAccessibilityService` 设备面实现——`ScreenshotResult` HardwareBuffer→软位图→**紧密 RGBA 像素**（2026-09-26 起不再压 JPEG —— 那段压缩是死重且有损），**实际尺寸随帧走**（`ProducedFrame`，曾经固定 1080×2400 回包是对 JS 报假尺寸，已除）；配置 `canTakeScreenshot=true` 进 res/xml（AOSP 明示缺它两法都不可用）；失败码分类映射 SECURE→`ERR_BLACK_FRAME`、系统限频→`ERR_INVALID_PARAM`、通道失效/无效窗口→`ERR_SERVICE_DISABLED`、内部错→`ERR_IO`；API34+ `takeScreenshotOfWindow`、API30–33 `takeScreenshot`、API<30 如实 `ERR_NOT_IMPLEMENTED`）。生产装配 `PlatformWiring.screenHandler = CapabilityNamespaces.screen(ScreenshotSource(AndroidFrameProducer(), analyzer = images))`（**同一个** analyzer 也喂给 `images` 缝，§18-8(b) 一张表；analyzer 为 null 即退回本地帧表） → `AppShellApplication.installWithFiles`，与 a11y 同底（`SystemA11yBridge`，服务未连 = `ERR_SERVICE_DISABLED`）；锁屏/无窗口由 `ScreenPolicy` 预检分类，安全窗由回调码兜底（无障碍读不到窗口 FLAG_SECURE，`secureForeground` 预检位恒 false —— 不伪造预检能力，分类结果殊途同归）。**仍缺**：MediaProjection 高清会话（授权 UI + FGS + ImageReader→libopencv.so）——换 producer 即插，语义面不动；P0 会话由同一 a11y 帧源连续截图承接。
- MediaProjection **会话语义**：`capture()` 一次性授权会话（API34 每会话确认）；`reconnect` 不自动重试授权，由 PermissionCenter 引导用户重授权。
- **图像分析面（`images`，§12.2 第七条独立缝）已通桥面、已通 native 实现（2026-09-25）**：`:domain` `ImageAnalyzer` SPI（`decode`/`matchTemplate`/`findImage`/`findColor`/`release` 五方法，`ImageFrame{HandleRef,width,height}` / `ImageMatch{x,y,width,height,confidence}` / `ColorHit{x,y,r,g,b,a}`）+ `:platform:capabilities` `ImagesNamespaceHandler`（**无状态转接**：帧表/发号/在场性全在 SPI，2026-09-26 起不再自管第二张表 —— §18 第 8 项 (b) 发号侧归一）+ JS facade `images.ts`。**P0 刻意不提供内存分析器**：看不见像素的替身只能靠自报坐标假装匹配成功，那比没有更坏 —— so 缺位时 `imagesHandler` 为 null，桥对 `images.*` 如实 `ERR_NOT_IMPLEMENTED`（2026-09-25 起 native 已接，见下）。**`findColor` 已落地（2026-09-25，P1 第一个算子）**：单色 + 逐分量容差 + 可选区域 + 回第一个命中，四层同改（`imgnative_color` → `colorNative` → `NativeImageAnalyzer.findColor` → `images.findColor`）。两条口径在该层钉死：**未命中是答案**（`x = -1` 哨兵回裸 `null` —— (0,0) 是合法首像素，拿 0 当“没有”会把左上角的命中静悄悄吃掉）；**“扫过 0 像素”是参数错**（`ERR_INVALID_PARAM`，那不是“没有”而是“根本没找”）。P1 native 面的八个算子已全落（**灰度、裁剪、缩放、旋转与特征**：计算核 `imgnative_gray`/`imgnative_crop`/`imgnative_resize`/`imgnative_rotate`/`imgnative_feature` 已落（28 + 46 + 45 + 45 + 32 例 host 断言），但桥面同样不开 —— 见本节末）。**宿主机语义门禁**：计算核零 JNI（见 imgnative.cpp 文件头），于是 host 侧用**同 commit** 的 OpenCV 4.14.0 静态库直链它、以 x86_64 跑像素断言（`bridge/image/test/cpp/`：`run-host-tests.sh` 一条命令装+编+跑）。这道门存在的理由很实：`imgnative_color` 里有三处判读是「编得过但译反了照样出结论」（**已覆盖**，36 例）——Vec4b 回读的通道序（px[2]→r/px[1]→g/px[0]→b，译反只是回包 r/b 互换）、ROI 内坐标+roi 左上角=全帧坐标、以及「扫过了、没有」与「扫过 0 像素」的区分；JVM 522 例与 JS 128 例全绿时它照样能错，而违约金是 hook 不到。**2026-09-25 已实证**：NDK `-fsyntax-only`、JVM、JS 三门全绿之际，这道门抓出 `cv::imread(IMREAD_COLOR)` 把任何来源压成 3 通道 BGR、`at<Vec4b>` 静默读进下一行首字节 —— alpha 分量从来没参与过判定（ASan 也不报）。修法=IMREAD_UNCHANGED + decode 归一成 4 通道 + `imgnative_color` 内通道/深度兜底守卫，`host_decode_norm_test.cpp` 21 例钉死。 **`imgnative_match` 的判读也补上了 host 断言**（`host_match_test.cpp` 24 例，2026-09-25）：命中坐标 = 模板左上角（matchTemplate 的 maxloc 是结果面左上角，照搬即模板位置，**不**再叠加 ROI 偏移）、w/h = 模板尺寸、**未匹配是答案不是异常**（out_match=0 且 x/y/w/h/confidence 全 0，status 仍是 0 —— 与 findColor 的 `x = -1` 同一条纪律；若把 `w == 0` 当"未命中"判，一张 0 宽模板就能让脚本把命中读成未命中）、阈值域 `maxv == threshold` 判命中（"≥ 阈值即命中"是契约原话）、模板比画面大 → ERR_IO(3)。补它的理由与 decode 归一同源：**模板匹配此前只被 JVM mock 覆盖，mock 不碰像素、永远回一个编好的 ImageMatch**，于是"坐标译反了""未命中时把 x/y/w/h 也回出去"在 JVM/JS 两门全绿时照样能溜过去。**灰度也落进计算核了**（`imgnative_gray`，2026-09-25，`host_gray_test.cpp` 28 例）：它是 decode 之外**第一个产出新帧的算子**，所以这道门额外钉了"产出新帧"的边界 ——产出帧号 > 原帧号且不顶掉原帧、原帧不被就地改灰、两帧各自独立 release、放掉产出帧后原帧照常可用（这四条 JVM/JS 两门**结构上看不见**：mock 只回一个自报的 ref）。产出帧仍是 4 通道 BGRA（帧表不变式，为此提了具名谓词 `frame_is_normalized`，`imgnative_color` 里原先手写的那处判据一并换过去），**alpha 原样带过去不抹 255**（灰度压掉的是色彩信息，透明与否不是色彩；decode 用 IMREAD_UNCHANGED 保住 A 就是让 a 分量参与判定，在灰度这步抹平等于把那次事故引回来）。灰度权重用库的 `COLOR_BGRA2GRAY` （0.299R+0.587G+0.114B），不自己写系数（抄一份就多一个漂移面）；host 断言钉纯红 76 / 纯绿 150 / 纯蓝 29，并正面否认平均法（找 85 必须未命中）。**桥面刻意不开**：`:domain ImageAnalyzer` 五方法里没有它，handler 也不认 `toGrayscale` —— 灰度在脚本侧是可选操作（匹配与找色都按需处理通道），没有消费方就不开桥面。**裁剪也落进计算核了**（`imgnative_crop`，2026-09-25，`host_crop_test.cpp` 46 例）：它是第一个**尺寸会变**的产出算子，比灰度多三处判断，每一处都在 host 上钉住。（1）**区域判据复用 `resolve_region`**（找色那同一个函数，不是抄一份）—— 于是"越界"在两处是同一个码、同一个边界口径（`rx + rw == cols` 贴边合法，越界一律 `ERR_INVALID_PARAM`）。（2）**`region == nullptr` 在本算子是拒收**，不按 `resolve_region` 的缺省解释成"整帧"：裁剪的语义就是"取一个子区域"，缺区域时唯一自洽的解释是"整帧拷贝"—— 想要整帧副本就明写整帧区域（那也有断言）。（3）**产出必须是拷贝（`clone()`），不能是 `(*f)(roi)` 视图**：帧表是**所有权表**不是视图表，源帧一 release，脚本手里的"子图"就悬垂。第（3）条值得单独记一笔，因为**它的判据不是直觉能给的**：`(*f)(roi)` 是浅视图，而 OpenCV 的 ROI **会把父缓冲的引用计数带住**（实测 `src.u->refcount` 1→2），所以"放掉源帧后视图会读到垃圾"**不成立** —— 缓冲根本没被释放，像素照常读出正确值（host 侧实测：放掉源帧后再读产出帧，视图实现同样正确，64 轮同尺寸重分配也没能把它分出来）。别名唯一可观测的后果是**内存归属**：视图共享同一个 `UMatData`，引用计数不为零就释放不掉，于是一张 1×1 的产出帧能把源帧整块缓冲钉在常驻内存里（4000×4000×4 实测：拷贝实现放源帧后 RSS 回落到基线，视图实现留 ~61MB）。这条判据因此写成了 **RSS 断言**（`host_crop_test.cpp` 第 8 段，大源帧裁 1×1 后放源帧、阈值 16MB 取在两种实现中间；§7.7 本来就没有为 crop 承诺 0 拷贝，而"0.3MB 的产出帧钉住 64MB 常驻"正是它在脚本侧的代价）。顺带钉住：产出帧号 > 源帧号且不顶掉源帧、宽高 = region 的 w/h（不是源帧尺寸）、区域坐标是**源帧坐标系**（子图 (0,0) 逐分量 == 源帧 (rx,ry)，非零起点也钉了）、产出帧仍是 4 通道 BGRA 且 **alpha 原样带过去**（裁出的单像素 a=210 不抹 255）、产出帧可以**再裁**、句柄已死 → `STALE`、出参指针为 null → `ERR_INVALID_PARAM`，且**拒收一律早退不写出参**（与 match/gray 同口径）。**裁剪的桥面同样刻意不开**（`:domain ImageAnalyzer` 五方法里没有它，handler 也不认 `crop`）—— 同灰度那条纪律：脚本侧没有消费方（找色已经能在 `region` 上限定范围，读路径不产出帧；只有"要把子图当独立一帧反复用/当模板"时才需要 crop）。**缩放也落进计算核了**（`imgnative_resize`，2026-09-25，`host_resize_test.cpp` 45 例）：它是第一个**像素值要重算**的产出算子（crop 是搬像素、逐点相等；resize 按插值重算，逐点不等是正常的）。入参是**目标尺寸**（`dst_w` × `dst_h`）不是倍数（倍数是调用方算的浮点；与 gray/crop 的"宽高随帧回"同一条纪律：尺寸真值只有产出帧的地方知道，让下游自己推是猜）。插值**固定 `INTER_LINEAR`，不做入参**：NEAREST 放大是块状马赛克（UI 细线条/文字边缘丢信息），CUBIC/LANCZOS 更贵且在截图/PNG 这类非照片输入上无可证增益 —— 多一个入参就多一个"选错静默换答案"的漂移面。门禁把 LINEAR 的可观测行为钉了三条：纯色帧任意缩放值不变（杀通道丢失/alpha 被抹）、2×2 四角帧放 4×4 四角守恒（杀几何对错/行列互换）、中心 (1,1) r=101 混合值**专杀 NEAREST**（NEAREST 给 100；已用故意 NEAREST 实现验过变红 —— 但诚实起见：CUBIC/LANCZOS 在此处同样给混合值，这条钉的是"不是 NEAREST"而非"只能是 LINEAR"）。另有 4×4 象限→2×2 的均值行为、同尺寸合法拷贝（不早退，调用方不用先判要不要调）、非对称尺寸（宽高各自独立）、**16384 单边配额**（16384²×4≈1GB，再往上是笔误把字节数当宽高；配额拒收与尺寸不合法同码，注释里分开写）。产出 4 通道 BGRA（resize 逐通道，归一进归一出，显式再断言一次防将来换后端静默掉通道），拒收一律早退，**桥面同样刻意不开** —— 同一条纪律：同一套模板跑多分辨率设备时才需要它。**旋转也落进计算核了**（`imgnative_rotate`，2026-09-25，`host_rotate_test.cpp` 45 例）：它是第一个**画布尺寸要算**的产出算子（resize 的尺寸是入参直给，rotate 的画布是包络公式 bw=round(|w·cosθ|+|h·sinθ|)、bh=round(|w·sinθ|+|h·cosθ|) 算出来的 —— 4×2 转 90°→2×4、5×5 转 30°→7×7 都钉了）。入参是**逆时针角度**（与 `getRotationMatrix2D` 正方向一致；转反了整行对不上 —— 5×5 数字帧 90° 首行 {5,10,15,20,25}，顺时针会是 {21,21,16,11,6}）。画布是 **expand**（包住整图不静默裁像素；想要"旋转裁剪"先 rotate 再 crop —— 两个算子都在了）。中心是**帧中心** ((w-1)/2,(h-1)/2)：奇尺寸下恰落中心像素，90° 倍角采样点落整数格点、敢写整行精确相等（相邻行采样到同一行是**行复制** —— warpAffine 逆映射的精确行为，host 实测五组全是整行相等，不是 bug；实现注释里写明了）。插值固定 LINEAR、填充固定 REPLICATE（黑边是找色的假阳性源 —— 见实现注释），30° 中心 3×3 混合值**专杀 NEAREST**（已用故意 NEAREST 实现验过变红；诚实线与 resize 同一条：CUBIC/LANCZOS 同样给混合值）。NaN/Inf → INVALID_PARAM（三角函数吃掉它们不报错，矩阵是垃圾 —— 入口拒比 warpAffine 断言变 IO 错更诚实），0°/360° 恒等（360° 先归一，不因浮点余数差一像素），**桥面同样刻意不开** —— "把画面转正再匹配"是调用方显式要的变换，不是匹配内部顺手做的。**特征也落进计算核了**（`imgnative_feature`，2026-09-25，`host_feature_test.cpp` 32 例）：P1 native 面的收官算子，也是第一个**不产出帧、只回坐标**的算子（回模板中心在场景中的 (x,y,confidence)，不是新帧号 —— 与 matchTemplate 的 ImageMatch 不同：特征匹配没有"模板尺寸"的概念，模板在场景里多大是未知的；回中心让脚本直接点下去）。链全固定：ORB(nfeatures=1000) → BFMatcher(HAMMING) knn k=2 → Lowe ratio 0.75 → 中位数偏移 ±3px 几何一致性计数。每个固定点都有 host 实测依据：nfeatures 500/1000 同一子图描述子逐字节一致（参数只截断、不换答案）；BGRA 直喂与手转灰一致（ORB 内部按第一通道取灰，转灰是冗余步骤）；ratio 0.7~0.8 不换答案（good=29/30/33、几何正确都是 19），0.75 取 Lowe 原论文值。门禁钉了：子块命中（中心 ±10px、conf≈0.63±0.15 —— conf 指纹把 ratio 链钉住，ratio=0.99 会把它拉到 0.47，已用变体验过变红）、棋盘格误报 → 未匹配（host 实测误报 top 距离 60+，ratio 后 good 寥寥）、纯色模板空描述子 → 未匹配（不是 IO 错）、旋转 30° 后**未匹配**（描述子对得上但中位数偏移假设不再成立 —— 这是设计不是 bug，真要转着找得接 findHomography/calib3d，那是另一个算子；断言把这个边界钉死，免得被脑补成"旋转容忍=转着也找到"）、拒收早退、两帧不消耗。**构建轨代价**：features2d/flann 进 BUILD_LIST（`VERSIONS.env` 三模块 → 五模块；`build-opencv.sh` 链接行同步；host 门禁用同 commit 另配的五模块缓存过渡，本机重配一次即可合一）。so 体积增量待下一次 device 构建实测（host 静态库实测 features2d 1.8MB + flann 1.3MB —— 那是 x86_64 未 strip 的 .a，不是 arm64 so 增量，写在这里免得被当成结论引用）。**桥面同样刻意不开** —— 同一条纪律：刚性匹配（matchTemplate）与特征匹配（feature）是两种找图语义，脚本侧没有消费方之前不开第二个。P1 native 面至此收官：八算子（decode/match/release/color/gray/crop/resize/rotate/feature）全在计算核 + host 门禁里，桥面仍是五方法（decode/matchTemplate/findImage/findColor/release）。**附带修掉一处判据主语**：`imgnative_color` 里的 `frame_is_normalized` 原先问的是 **ROI 视图**，而视图的 `channels()/depth()` 与父矩阵**同解**（实测），所以那既不是漏判也不构成事故；改问源帧、且挪到 `resolve_region` **之前**，是因为谓词的名字与注释谈的都是"帧"—— 判据该写在它自己声称的主语上，顺序本身也是判据的一部分（非归一帧上"某个 region 合不合法"是另一套尺寸语义）。这道门附带钉住一条此前没写明的口径：`imgnative_match` 的拒收分支是**早退**——不写出参，所以调用方**不能拿 out_match 当"没命中"判**（可能还是上次调用的残留），判据只有 status；真实调用链正是这么做的（`images_jni.cc` 的 `matchNative` 只看 rc，`NativeImageAnalyzer.match` 同样 rc 优先）。NDK 交叉 `-fsyntax-only` 与这道 host 门**互不替代**（前者管 aarch64 能编、后者管判读对），真机红测仍是最后一关。它已接进 `.github/workflows/image-native.yml`（job `host-image-semantics`，与 build-opencv 同文件、paths 同源，且**排在构建前面**：判读先红一个 5–10 分钟的，不占满 15–30 分钟的构建槽；OpenCV 按 VERSIONS.env 同 commit 拉取并对表，tarball 会让对表退化成口号）。**native 侧已接**：`libopencv.so` = OpenCV 4.14.0（`core+imgproc+imgcodecs`，`BUILD_JPEG/BUILD_PNG/BUILD_ZLIB=ON` 树内源码、`WITH_KLEIDICV` 默认 ON）静态链接进我们自己的桥面 C++（`imgnative.cpp` 纯计算核：`extern "C"` 九入口、帧表自管、`cv::Exception` 就地折叠）；装载面 `images_jni.cc`（全仓图像侧唯一 `#include <jni.h>`）+ Kotlin `NativeImageAnalyzer`/`JniOps`（住 `:platform:system`，零 android import；`System.loadLibrary` 失败即不构造）。构建在 `node-runtime-build/scripts/build-opencv.sh`（按 commit SHA 固定、kleidicv pin 对表、16KB LOAD/NEEDED 白名单门禁、SHASUMS256 旁 kleidicv ON/OFF 审计行），由 `.github/workflows/image-native.yml` 在 Actions 跑；`PlatformWiring.of` 一行接上（so 缺位 → `ERR_NOT_IMPLEMENTED`，不塞内存替身）。
- **两缝帧表已合一（§18 第 8 项 (b)，2026-09-26 落地）**：`screen.capture()` 出的帧与
  `images.decode` 出的帧现在**同号段、互认** —— 拿截屏帧当 `images.findImage()` 的
  haystack 通，`images.release()` 也放得掉一帧截屏。"帧不通用"那条纪律**取消**。
  落点是**发号侧归一**（§7.4）：
  - `:domain` `ImageAnalyzer` 加第 6 方法 `ingest(width, height, rgba)` —— 把**已在内存里**
    的紧密打包 RGBA（`width*height*4`，R,G,B,A 序）登记进它自己那张帧表，回句柄。
    `decode` 与 `ingest` 共用同一个 `nextRefId`，这就是"两帧互认"的结构证据；
  - `:platform:capabilities` `ImagesNamespaceHandler` **去掉自管的三张本地表**（`ids`/`live`/`sizes`）
    退成无状态转接 —— 曾经它靠"两个计数器各自从 1 起、每次 decode 各加一"的隐式不变式
    与 SPI 对齐，那种对齐是漂移面不是契约；
  - `ScreenshotSource` 收一个可选 `analyzer`：给了就把截出的帧 `ingest` 进 SPI 的表、
    `recycle` 转 `ImageAnalyzer.release`；没给（so 缺位）才退回本地表 —— 此时 `images`
    命名空间根本没注册，两个号段不可能相撞；
  - `AutoScriptAccessibilityService.frameOf` 从 **JPEG 压缩改成原样 RGBA 像素**。
    曾经那段 `Bitmap.compress(JPEG, 90)` 是死重：`ProducedFrame.bytes` 全仓只被
    `isEmpty()` 看过一眼，而有损 JPEG 一旦真进 `findColor`，"按分量精确判定"的承诺
    就吃到压缩伪影。改走 `Bitmap.getPixels(int[])` → 打包 `0xAARRGGBB` → 拆 RGBA：
    **不猜 `copyPixelsToBuffer` 的字节序**（那是 Skia 缓冲的原样拷贝，文档没承诺通道序；
    猜反了就是 r/b 互换，四道门全绿照样错）；
  - 像素契约：ingest 收 RGBA、native 进帧表时做一次 `cvtColor(RGBA→BGRA)` swizzle 并
    **拷出自有缓冲**（不持有调用方的 `ByteArray`）—— 帧表不变式仍是 4 通道 BGRA。
  钉子：`host_ingest_test.cpp`（通道序 + **跨来源同表**：`imgnative_decode` 出的模板帧与
  `imgnative_ingest` 出的截屏帧共用 `g_next_ref`，release 一个另一个照常可用）、
  `ImagesNamespaceHandlerTest` 的跨命名空间用例、`NativeImageAnalyzerTest` 的 ingest 三例、
  `images.test.cjs` 的互认用例。§7.7 表里 `captureScreen → findImage` 的数字仍是待实测口径。
  三条落选出路留档：(a) `screen.save` 有损、(c) `decodeBytes` 让 bytes 过桥 —— 都切掉被契约
  承诺的性质（见 §18 第 8 项）。

### 9.3 输入通道（`root_automator` / 手势）
`InputProvider` SPI 三实现：无障碍手势（默认）/ root `sendevent`（root 设备自选）/ Shizuku-ADB（可代理 dev `${i}` 事件）。统一 `touchDown/Move/Up` + 手势 DSL。root 能力分级进 PermissionCenter，无 root 不降级渲染为禁用（不假装可用）。

### 9.4 悬浮窗 / UI 宿主
- `floating_window`：`TYPE_ACCESSIBILITY_OVERLAY`（可信窗口易保持）＋ `SYSTEM_ALERT_WINDOW`（普通）；运行时权限 checkbox 进能力中心。
- **落地分层（勿混）**：`floatingWindow` handler 只管**参数与信封**（spec 守卫 → `ERR_INVALID_PARAM`、句柄两字段 `{refId,generation}` 的原样编码、分类错误原码透传），住 `:platform:capabilities` 的 `FloatingWindowNamespaceHandler`（纯 JVM 可测）；**句柄记账（generation 递增）、`close` 幂等、`ERR_STALE_HANDLE` 的起源、窗口类型选择（`TYPE_ACCESSIBILITY_OVERLAY`/`SYSTEM_ALERT_WINDOW`）全在实现侧** —— 由 `:domain` 的 `FloatingWindowHost` SPI 承接，Android 实现 `AndroidFloatingWindowHost` 已落在 `:platform:system`（§12.2 分两层）。
- 脚本 UI：JS 声明 XML 布局 → 桥传 `:main` 渲染原生 View（`UiHost` SPI）；`ui_web` → WebView + JS 桥（双向事件回 Node）；`ui` Activity 方式独立宿主 Activity（BAL 限制内，仅当可见/继承时启动）。
- 事件回投（点击/输入/页面生命周期）→ RuntimeChannel → JS 侧 `EventEmitter`。

### 9.5 权限与能力中心（three-state 门禁）
统一 `CapabilityStatus = GRANTED / DEGRADED / DENIED`：
- **GRANTED**：系统授予且当前可用（含会话型 MediaProjection 已激活）。
- **DEGRADED**：可降级但受限（如 a11y 树只能节流读、闹钟降 setWindow、无 root、BAL 限制、电池未豁免、ROM 自启被关）。
- **DENIED**：被用户/系统拒绝，操作抛 `ERR_PERMISSION_DENIED`。
- 能力中心 UI：枚举所有能力 + 当前三态 + 一键跳转系统页 + 降级说明；`PermissionFacade` 是唯一的权限入口（模块不直接查 `Settings`/`ActivityCompat`，可 Mock）。
- **已接生产（`:app` 侧）**：`AndroidPermissionGates`（`AndroidCapabilityProbes`(6 事实真查询) → `AndroidSystemStateReader`(事实→三态映射，判据唯一出处) + `AndroidGrantLauncher`(`pageFor` 能力→页 + `specFor` 页→Intent 规格，字面量单测锁死) → `permissionCenterOf`(纯拼装，不判断)；住 `com.autoscript.shell` 装配包，`android..` 直连与 `AndroidAlarmPort` 同例）→ `AppShellApplication.permissionCenter()`（懒建缓存，查询本身不缓存）→ 能力中心 UI；降级账本读口 `degradedAlarmTasks()`（`AlarmSchedulerProvider.degradedTasks` 只读视图）。门禁从"编排可测"推进到"生产有人问系统"。
- **能力中心读口已接（2026-09-23）**：`HostSummary` 增 `capabilityCenter()`（挂起：三态是**现问系统**的结论，含 root 探测的 IO 切换）与 `openCapabilitySettings(capability)`（**无判断**，转给 `PermissionFacade.openSystemSettings` —— 呈现层因此不必也不许碰 `Settings`/`Intent`）。快照 DTO 住 `:domain`（`CapabilityCenterSnapshot`/`CapabilityRow`，后者带 `canRequestGrant` = `CapabilityLifecycle.canRequestGrant` 的投影：**按钮显隐的判据唯一出处仍在 :domain**，呈现层不许自己写 `state != GRANTED`）。拼装在 `:app` 的 `CapabilityCenterRead.snapshot(facade, degradedAlarmTaskIds)`（**纯 JVM 可测** —— `Application` 在 JVM 里构造不出来，而"全量枚举能力 + 逐项问三态 + 配同一份 guideText + 带上降级任务账"这段判断是那一屏的全部事实来源）。**全量枚举**（`Capability.entries`）不是"只列异常项"：能力中心要能回答"我到底有哪些能力"；**读失败抛**，`PermissionCenter.state` 只把 reader 查询崩收敛成 `DEGRADED`（可用性未知即受限），更外层的抛穿到 `:ui` 如实显示 —— 吞成"全 DENIED"会让用户以为授权全丢了。呈现侧 `:ui` 的能力中心（`CapabilityScreen` + `CapabilityCenterState`，纯状态 DTO，JVM 可测）：三态各有中文说法（可用/降级可用/被拒绝 —— "用户该做什么"逐态不同）、引导文案**原样透传**（改写过的文案会与系统里的真实路径漂移）、降级中的定时任务**单列一段**（§8.6 承诺的「可能偏差」标注，不是权限问题）、**没读到 ≠ 一个能力都没有**（`NOT_LOADED` 与 `CapabilityCenterState.failed` 分开，失败时保留原异常文案）。刷新时机：回前台/切到该页签时经 `LaunchedEffect` 重问一次 —— 用户从系统设置页授完权回来看到的是**刚问过**的结论，而不是离开时那份缓存（后者正是"授权了但界面还说没授权"的来源）；读失败不反过来触发重读（不自激）。

### 9.6 数据与存储（`datastore` / `settings` / `zip`）
- `datastore` = SQLite-backed KV + serializer 适配（JSON/native 对象/byte），事务语义；同步 importer 仅供纯内存。**契约已落 `:domain`**（`storage/`）：`DataStore` SPI（全挂起，同步 importer 不进契约 —— 铁律 2，纯 JS 进程内路径专有）+ `StoredEntry`（`Json` 透传文本 / `Bytes` 内容相等；存 JSON `null` ≠ 键缺失）+ `DataStoreTxn`（暂存写、block 抛错整批不落、提交对并发读者一个原子点）+ `InMemoryDataStore` 纯内存参考实现与契约测试 —— **桥面已通**：handler（`:platform:capabilities` 的 `DatastoreNamespaceHandler`，`{found,value}` 信封保 `undefined`≠`null`、字节值不过桥如实 NOT_IMPLEMENTED、`transaction` 不上桥）+ 独立注入缝（`assemble.datastoreHandler`，不入 `systemHandlers` 束 —— 存储面无共担门禁）+ JS facade（`datastore.ts`，`get` 拆信封）。**SQLite 实现已落** `:platform:system`（`AndroidDataStore` + `SqliteKvOps` 单表 `kv(key,kind,value)`，`KvRowCodec` 显式 kind 列裁定文本/字节、访问器按 kind 惰性；`SystemSpis.Bundle.datastore` 入口就绪）——**生产已接**（`PlatformWiring.of` → `inject` → `AppShellApplication.installWithFiles` 喂 `datastoreHandler` 独立缝；`PlatformWiringTest` 同路径真转接覆盖）。多库 = handler 键前缀 `name:key`，领域层不发明第二套路由。
- `settings` = 系统设置读写（`:domain` `SystemSettings` SPI + `:platform:system` `AndroidSystemSettings`，P0 钉在 `Settings.System` 命名空间：string/int 两型 × 读写 + `canWrite` 探针）。写入口径（§9.5）：未授 `WRITE_SETTINGS` → 抛 `ERR_PERMISSION_DENIED`（不是回 false —— 授权问题是分类错误）；已授权仍被系统拒 → `ERR_IO`；读侧缺失回 null（不拿 0/空串冒充 —— 0 是合法亮度）。**桥面已通**：handler（`:platform:capabilities` 的 `SettingsNamespaceHandler`，方法表照抄 SPI 五件 `canWrite`/`getString`/`getInt`/`putString`/`putInt`，**不提供猜型的 `get`/`put` 别名** —— 串与数是两套系统 API，按 `typeof` 推断就是发明策略；读缺失回**裸 JSON `null`** 而非 datastore 的 `{found,value}` 信封 —— 本契约值面只有 String/Int，`null` 不与任何合法值撞，空串/0 是真值一律不冒充缺失；写前**不预检 `canWrite`** —— 未授 `WRITE_SETTINGS` 抛 `ERR_PERMISSION_DENIED` 的判据唯一出处是 SPI，handler 再判一遍必漂移）+ 独立注入缝（`assemble.settingsHandler`，同 datastore/zip 不入 `systemHandlers` 束）+ JS facade（`settings.ts`，五方法与 SPI 1:1，缺失回 `null` 不回 `undefined`）。双侧钉子：`SettingsNamespaceHandlerTest` + `settings.test.cjs`。**授权引导页未进 `GrantPage`**（能力中心有 settings 入口时再开）；**生产已接**（`PlatformWiring.of` → `inject` → `installWithFiles` 喂 `settingsHandler` 独立缝；`PlatformWiringTest` 同路径覆盖）。
- 脚本文件目录：`files/scripts/<projectId>/` 标准化；要求 `assets→filesDir` **原子部署**（tmp 写入 + sha256 校验 + rename 替换），防止半截断电文件。
- `shell`：SPI 是 `:domain` 的 `ShellExecutor`（`exec(command, mode, timeoutMillis)` → `ShellResult`，超时是**实现者义务**而非可选项 —— §10 零 spawn 的副作用落在宿主侧，且铁律 3 禁止无限等待）；语义层 `ShellNamespaceHandler` 住 `:platform:capabilities`（参数校验、超时默认值 30s、`ShellMode` 字面量 `default`/`root`/`adb`），Android 实现 `AndroidShellExecutor`（`Runtime.exec("sh","-c",…)`）已落在 `:platform:system`。**Promise 封装的诚实口径**：`stdout`/`stderr` 为 `null` 表示「流无输出」，不是「有输出但为空」；退出码非 0 不是异常，原样回 `{code, stdout, stderr}` 由 JS 侧判；child_process 副作用限制如实上报。
- **五个系统命名空间（`dialogs`/`shell`/`device`/`app`/`floatingWindow`）已全部落地语义层**：SPI 与 DTO 住 `:domain`（`SystemContracts.kt`），handler 住 `:platform:capabilities`（`SystemNamespaces.kt`），`AppShell.assemble` 的 `systemHandlers` 束五个字段各自可空 —— 未接线的那一个如实回 `ERR_NOT_IMPLEMENTED`，不伪造可用。`app.launch` 返回 `false` / `app.currentPackage` 返回 `null` 是**诚实答案**（前台无包名、启动被系统拒），不抛异常；`dialogs` 的 `mode` 三态 `auto`/`overlay`/`notification` 由 handler 解析并**原样交给 `DialogHost`**，BAL 降级选路（overlay 可见则弹窗，否则通知回调）是 `DialogHost` 实现的事 —— handler 看不到 overlay 实况，不做这个判断；取消语义统一：prompt 用 `{value:null, confirmed:false}`，choose 用裸索引 `-1`（与 `extras.ts` 逐字对齐）。 **SPI 侧进度**：`shell`/`device`/`app`/`floatingWindow` 四件已有 `:platform:system` 真实现（`Runtime.exec`/`Build`/`PackageManager`+`UsageStatsManager`/`WindowManager`；`SystemSpis.of(context)` 是实现入口），`dialogs` 的 `DialogHost` **已落地**（实现按 domain KDoc 约定住 `:platform:capabilities`：`AndroidDialogHost` 纯 JVM 编排——AUTO 按 `overlayAvailable` 选路、OVERLAY 强制不可用即 `ERR_PERMISSION_DENIED` 不静默降级、通知路径先登记后 post、`finally` 注销+撤幽灵通知、晚到答案丢弃；设备面 `SystemDialogOps` 在 `…capabilities.device` 子包——AlertDialog overlay 弹窗（BadToken→`ERR_PERMISSION_DENIED`）+ 独立通道通知（prompt 回复动作挂 RemoteInput、choose 每项一个动作、`DialogActionReceiver` 按 key 回投；requestCode 分槽位防 filterEquals 撞 PendingIntent）；构造在 `PlatformWiring.of`（同 `overlayAvailable` 喂悬浮窗与对话框），`inject` 缺省 null 仍如实 `ERR_NOT_IMPLEMENTED`）。
- `zip` = 归档压缩/解压（`:domain` `ZipArchiver` SPI + `:platform:system` `JdkZipArchiver`）。契约级安全底线：**zip-slip 先验后写** —— 全包条目名校验通过才落字节（`../` 逃逸/绝对路径条目 → `ERR_INVALID_PARAM`，界内零写入；与 shell 超时同级的实现方义务）。压缩 tmp+rename 原子落位、空目录条目保留、垃圾包如实 `ERR_IO`（`ZipFile` 中央目录校验，不用对垃圾"零条目静默成功"的流式读）。**桥面已通**：handler（`:platform:capabilities` `ZipNamespaceHandler`，参数口径+错误原码透传，不碰归档字节）+ 独立注入缝（`assemble.zipHandler`）+ JS facade（`zip.ts`）。与 packager 的 npm 专用 zip（`NpmSnapshot` 固定 mtime+integrity）边界分明，不互相复用。
- **已落地（项目目录与装配期补部署）**：`:domain` 的 `ScriptPaths`（项目根 `files/scripts/<projectId>` 的单一事实来源 —— 纯路径计算、无 IO；拼错目录名编译期即可见）被三处复用（`:app-service:packager` 的 `NpmProjectLayout`、`AppShellKit` 装配、调度侧补部署）。`:app-service:scheduler` 的 `ScriptDeployRecovery` 在 `AppShellKit.assemble` 时跑一次：**只补缺、绝不覆盖**（用户手改的脚本原样留着）、空清单如实为空（`deployReport.changed == false`，不粉饰成"已恢复"）、单文件失败不带走整批（`deployFailures()` 列路径+原因）。来源合并（`scriptSources` 显式优先 + `assets/scripts/<projectId>/` 按项目补缺，单项目读失败跳过不炸整批）：配方经 `scriptProjects` + `assetReader` 两缝拿资产（配方本身不直连 AssetManager，保持纯 JVM 可测），`AppShellApplication.installWithFiles` 喂真实现（`assets.list("scripts")` 枚举 + `AndroidAssetsSource.readScripts()` 按需读）。**诚实边界**：本类不是部署器 —— 覆盖/版本/审批仍归 `script-repo` 的 `AtomicDeployer` 与 npm `InstallCoordinator`（sha256/journal/审批链路）；框架也没有内置脚本模板，来源给多少补多少。


### 9.7 OCR（P1）与插件（P2）
- `OcrProvider` SPI：P1 内置 MLKit 插件基准实现（可下载模型）；插件以独立 `:plugin:*` 模块 + 清单注册，native addon 走 `:node-runtime-build` 交叉编译管线（arm64 `.node`）。
- 插件加载: P2，plugin.json 声明 require 钩子/资源/权限；市场脚本不可加载任意插件（白名单）。**沙箱已裁（§18 第 1 项）** —— 这条白名单是能力面的，不再有"引到 QuickJS 子集"的去处。

---

## 10. npm 支持（包管理与依赖生态）

> 硬性需求「必须支持 npm」的设计超纲部分。核心矛盾：Node-on-Android 无 `child_process`，而 npm CLI 重度依赖 spawn。以下方案把「零 spawn」从 workaround 变成**官方默认语义**。

### 10.1 总体策略 —— D（混合）

**脊梁：vendored 真 npm CLI（npm 12.x 系，要求 Node≥24.15，由 24.21.0 满足）在专用安装会话进程内「进程内执行」。**

- **零 spawn 是实证事实**：`npm install` 的实质 = `@npmcli/arborist reify()` + pacote 下载/解包/链接；本机 strace 实测 `npm install --ignore-scripts` 全程 **0 次 execve**。纯 JS 生态（axios/dayjs/lodash/cheerio/ws/express ≈99% 用例）根本不需要子进程。
- **用真 CLI 而非重造轮子**：lockfile v3、audit、`approve-scripts`、`replace-registry-host`、`--prefer-offline` 免费获得且可审计。npm 12 默认「拒绝全部 lifecycle + allow-git=none + allow-remote=none」，把 child_process 缺失从 workaround 变成**官方默认语义**。
- **三通道合一**：B（零 spawn 编程式）作 P0 主线；A（spawn 桥）作 P1 升级通道（批准后脚本/`npm run`/`npm exec`）；C（离线 bundle + 精选 tarball 种子）作首发与离线通道。
- 否决纯 B（丢 CLI audit/approve/config 语义，多维护一层）、纯 A（P0 依赖未经验证的真子进程，OEM exec/SIGKILL-only/内存峰值风险最高）、纯 C（桌面预装无法满足「用户自主安装」的硬性需求）。
- **沙箱关系（2026-09-26 已裁，口径随之简化）**：npm 是 Node 高信任层的能力，而引擎只剩 Node 一条轨 —— 不存在"`:sandbox` 白名单不含 `auto.npm`"这回事，能力中心也不再显示「沙箱不支持」。
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
- registry 配置：项目 `.npmrc` → `files/.npmrc`(userconfig) → `NPM_CONFIG_REGISTRY` env；默认 **`registry.npmjs.org` 官方**（§18 第 7 项 2026-09-26 拍板；要快自己 `setRegistry` 切 npmmirror/华为/腾讯），`replace-registry-host=npmjs` 使 lockfile 跨 registry 可用；代理 `Settings.Global.HTTP_PROXY` → 引擎 env `HTTP(S)_PROXY`。

### 10.3 spawn 三层策略与不可行边界

| 层 | 内容 | 阶段 |
|---|---|---|
| **T0 零 spawn**（P0 承诺面） | npm12 默认拒绝全部 lifecycle + `--no-audit --no-fund`；install/ci/ls/dedupe/prune/uninstall/audit(在线)/cache 全进程内；bin-links 走纯 fs（sdcard 才需 `--no-bin-links`） | P0 |
| **T1 批准后脚本**（P1） | per-package approve 后的 lifecycle/`npm run`/`npm exec` 触发 spawn，被 `--require` 注入的 child_process shim 拦截 → 桥 → `:main` 沿 EnginePool 同路径拉临时引擎执行；stdio 走 TSF 二进制数据通道做假管道；**shim 直接拒绝 `detached:true`/`setsid`**（ERR_PERMISSION_DENIED + 可操作话术）；脚本宿主独立 pgrp，回收顺序 TERM→超时→SIGKILL 且 `kill -- -<pgid>` + `/proc` 同 UID+PPID 链二次收割；stdio write-end 由桥独占持有（CLOEXEC 注入）+ EOF 看门狗，宿主退出即强制 close 全部挂接 FD 防孤儿占管 | P1 |
| **T2 sh 包裹与 PATH `node`**（P1红测项） | 需要「node 可执行身份」= node-shim PIE（dlopen libnode.so + node::Start，几十~200KB，同源同 16KB ELF 门禁），经 jniLibs 交付，PATH 注入；`sh -c` 由 `:main` ProcessBuilder 起 `/system/bin/sh`；targetSdk36 设备若 app 数据区 exec 被拒 → **降级 T1-only + UI 明示** | P1 |

**明确不可行（写死拒绝、报可操作错误而非假成功）**：
- `git:` 依赖 → `ERR_NOT_SUPPORTED`（install 入口即拒，引导本地 tarball 导入）；
- `node-gyp` 设备端编译 → `ERR_NOT_IMPLEMENTED`（设备无 NDK/编译器，引导 **wasm 优先、纯 JS 兜底**——如 `esbuild-wasm`/`node:sqlite`/`bcryptjs`；prebuild 小工具已移出排期，真需要时再立需求）；
- `fork`/`cluster` → `ERR_NOT_IMPLEMENTED`（并发用 engines 进程池）；
- 从 app 数据区任意 exec 二进制（W^X）→ 拒绝（原生 bin 走「代码签名 exec」独立通道，见 §10.11 P3）；
- `npm exec` 非 node 二进制 → 仅走既有 `auto.shell`(root/adb) 能力且 Node 高信任才可（沙箱已裁，不存在另一档"一律拒绝"的引擎）。

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

   **已落地（裁决与处置，`:app-service:packager`）**：`NpmRegistryVerifier` 是三分裁决而非布尔——`Agreed` / `Disagreed` / `Unverifiable`，调用方必须能区分「验过且一致」与「没能验」（否则 UI 只能画同一个绿勾）。第二意见恒为 `registry.npmjs.org`，**不随用户首选变**（首选容易被自己改成 npmjs，那就成了自己跟自己比）。处置写死在 `InstallCoordinator.crossCheckRegistry` 一处：`Disagreed` → `ERR_REGISTRY_UNAVAILABLE` + 两家版本/完整 integrity，安装会话不起、事务不建、拒本身入史；`Unverifiable` → **不拦安装**但发 `InstallEvent.Warning(TRUST_DOWNGRADED)` + 入史「来源未校验」（副镜像不可达 / 版本只在一侧 / 无 integrity 锚点都是「没验成」而非「验出问题」，当分歧拒掉会把镜像同步窗口期误判成攻击）。判定对象是 `dist.integrity` 而非两个 tarball 的字节（结论等价、少一倍下载）。JS 侧 `auto.npm.onWarning` 的 `kind` 联合与 `InstallEvent.Kind` 五值逐字对齐。**生产投递走事件拉取口**（`drainEvents` → JS 轮询泵 → 过 `feedWarning` 做 kind 校验；`feedWarning` 自身降为注入缝供装配/测试直调，2026-09-26 起不再是唯一投递方 —— 在那之前它零生产调用者）；未知 kind 抛错而非静默丢弃（契约漂移即响亮错误）。诚实边界：**不**回答「镜像 hardcode 的摘要是否真由上游产生」——那要 sigstore/官方签名端点，记为未决项。
2. **审批 = 人的动作（人机分离）**（整改自批判「程序化绕过」）：
   - `approveScript`/`runScript`/`exec` **不允许脚本直调**——脚本只能发出 `ApprovalRequest` 排队，等 UI 弹卡人工二次确认（可配生物特征），脚本侧限流 + 全量审计。
   - 审批记录绑定 `pkg+版本+脚本内容哈希`，版本升级必须重新审批；审计日志（approve/registry 变更/lock 重签）落 App 且可导出。
3. **恶意包防线（缺省启用）**：
   - 默认**拒绝全部 install 脚本**（对操纵无障碍/root 的自动化脚本是最大投毒面）；postinstall 包装完即出「脚本未运行」显式警告，**禁止静默**。
   - 在线 `npm audit` + `audit signatures`（ECDSA）；离线捆绑 OSV 库 + `osv-scanner --offline`；签名端点不可用**绝不静默降级**。
4. **低信任边界**：T1 脚本执行会话一律**独立最小 CapabilityMask**（仅 npm 目录 fs+network，无 a11y/shell/root），与用户脚本会话物理区分；UI 明示「审批 postinstall ≠ 授权自动化能力」；高信任须**可验证签名 + 用户显式升级**（不用软签名）。
5. ~~**QuickJS 白名单库独立 vendored**~~ **已裁（2026-09-26，§18 第 1 项）**：沙箱不在排期，白名单库不复存在。（"共享 store 要持 `store 哈希 == 各项目 lock 哈希` 的加签映射校验"那半句是 store 的纪律，随 P3 跨项目共享 store 保留。）

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
  fun progress(projectId): Flow<InstallEvent>              // :main 订阅用（Flow 无重放）
  fun approvals(projectId): Flow<ApprovalRequest>
  suspend fun drainEvents(projectId, sinceSeq, batch=32): InstallEventBatch   // 脚本侧拉取口（§7.5 桥无宿主→脚本推送面）
  suspend fun drainApprovals(projectId, sinceSeq, batch=32): ApprovalBatch    // 同上；游标由调用方持有
  suspend fun storage(): Map<ProjectId, NodeModulesStats>
  suspend fun exportSnapshot(uri): SnapshotRef                 // node_modules.zip+lock+ledger→SAF；高信任通道
  suspend fun cancel(handle: InstallHandle)               // TTL/取消 → quiesce 安装会话
}
```

**事件面是拉取不是推送（2026-09-26 收口的第三处落差）**：桥的入站面只有按 requestId 结算的 ok/err（§7.5），宿主没有任何主动推给脚本的通道 —— 在此之前 JS 的 `onProgress`/`onWarning`/`onApproval` **双侧都没有投递方**（订阅了但生产永远不响：`progress`/`approvals` 两个 SharedFlow 零订阅、`feedWarning` 零生产调用者、`InstallFailure` 连订阅口都没有），正是 `feedWarning` KDoc 自己写的「比没有这个 API 更糟」。接法沿用仓库既有的游标拉取（`a11y.events`/传感器批次/`startHeartbeat`）：`InstallCoordinator` 两条有界环（`SeqRing`，512、DROP_OLDEST、单调 seq）由 `emit()`/`requestApprove` 唯一投递，`drainEvents`/`drainApprovals` 按调用方游标取批；回包 `{first,last,items}`，空增量 `first=last=sinceSeq`，环丢过最旧时 `first > sinceSeq+1` 即空洞可见（进度是可丢数据面，如实露洞不补造）。四个 DTO（`InstallEventBatch`/`SequencedInstallEvent`/`ApprovalBatch`/`SequencedApproval`）与两个新方法由 `PackageManagerFacadeContractTest` 冻结，JS 侧 `npm-events.test.cjs` 逐字复刻同一套回包语义。

### 10.8 JS API —— `auto.npm`

Promise 优先 + EventEmitter；npm 操作一律跨进程路由到全局安装会话、TTL 绑定，**绝不阻塞脚本事件循环**；脚本内不直接 `require('child_process')`。

```ts
// 安装（P0）
const handle = await auto.npm.install('axios', { save: true, offline: false, timeout: 60_000 });
//     → { handleId, projectId, enqueuedAtMillis }   // :domain InstallHandle 上桥（只代表已入队）
const list = await auto.npm.list();                 // 装了什么：直读 lockfile（权威）
//     → [{ name:'axios', version:'1.20.0' }]        // 无 sizeBytes（lockfile 量不到尺寸）
const gap = await auto.npm.offlineGap();
//     → [{ name:'…', version:'…', size:1234 }]      // 尺寸在这条路（缺失清单）
await auto.npm.onProgress(e => console.log(e.phase, e.name, e.percent));   // phase: queued/resolve/download/reify/post-check/done
await auto.npm.remove('axios');
await auto.npm.ci({ offline: true });                          // lockfile v3 严格重建（验签后）
const list = await auto.npm.list({ depth: 0 });                // 轻操作，Kotlin 直读
await auto.npm.prune(); await auto.npm.dedupe();
const gap = await auto.npm.offlineGap();                       // 离线闭包差距（缺哪些包、共多大）
const report = await auto.npm.audit({ offline: true });        // { vulns:[{id,severity,name}], level, offline }
//                                                            // 键名是 vulns（不是 vulnerabilities）

// 配置/离线（P0）
await auto.npm.setRegistry('https://registry.npmmirror.com', { scope: '@my' });
await auto.npm.importOfflineBundle('/sdcard/Download/baseBundle.zip');   // SAF uri 亦可
await auto.npm.importTarball('/sdcard/Download/pkg.tgz');

// 审批（人机分离：只能发起请求，人工在 UI 弹卡确认）
await auto.npm.requestApprove('esbuild', { scripts: ['postinstall'] });  // 不直接 approve；不 await 的话被拒会成 unhandled rejection

// 事件（四个独立方法，**不是** `on('progress')` —— 那种写法在 facade 上会 TypeError，见 §12.3.2 第 6 条）
// 实现是**带游标拉取轮询**（§7.5 桥没有宿主→脚本的推送面，§10.7 drainEvents/drainApprovals）：
// 首订立拉一轮、之后按周期补拉、退订干净自停（定时器 unref 不保活事件循环，同 startHeartbeat）；
// 宿主没实现 events/approvals → 响亮 ERR_NOT_IMPLEMENTED（「订阅了却永远收不到」禁静默），瞬时错误吞掉走下一拍。
const offP = auto.npm.onProgress(e => console.log(e.phase, e.name, e.percent));
const offA = auto.npm.onApproval(req => notify('需人工确认', req.pkg));   // ApprovalRequest 六字段，无 scripts
const offW = auto.npm.onWarning(e => console.log(e.kind, e.pkgs, e.message));
const offF = auto.npm.onFinished(f => f.success ? done() : fail(f.detail)); // 成功**和**失败都发（install 回包只是已入队）
// kind: scripts-skipped/trust-downgraded/registry-fallback/low-memory/disk-quota（:domain InstallEvent.Kind 同集）

// 错误码新增：ERR_NPM_*（安装失败/审批被拒/SPAWN_BLOCKED）、ERR_NOT_SUPPORTED（git:依赖）、
// ERR_REGISTRY_UNAVAILABLE（网络/镜像可诊断）、ERR_DISK_FULL、ERR_NPM_LOWMEM、ERR_NOT_IMPLEMENTED（node-gyp/exec）
```

### 10.9 UX 流程

1. **依赖面板**（IDE 项目页）：搜索 / `npm install <spec>` 输入行 + 旗标（`-D`/`--offline`/registry 选择器）→ 阶段进度条（packument→下载→解包→链接，job 数）→ 完成横幅；hasInstallScript 包显式警告。
2. **脚本审批卡**：带 install/postinstall 脚本的包 → 卡片列表（可展开「脚本=任意代码」风险说明）→ per-package 人工批准/拒绝 / 「全局禁止脚本」（出厂默认）→ 批准记录入审计页。
3. **npm 终端视图**（P1）：项目内终端 `npm install axios` / `npm ls`，stdout/stderr 流式输出 + exit code；与依赖面板同一安装会话队列。
4. **离线包导入**：SAF 选择（tarball / lock+cacache bundle / 快照 node_modules.zip）→ 验签 → 队列安装；另提供「从内置精选缓存离线装 axios/dayjs/…」。`node_modules.zip` 导入**仅限高信任项目**，签名锚定 `HMAC(应用密钥, lock.sig + zip.sha256)`；市场脚本一律拒绝该格式（走 reify 产出 integrity）。
5. **包大小管理页**：per-project `node_modules` + `npm-cache` 尺寸（Kotlin 遍历）+ 配额条（80%黄/100%拦）→ 一键 prune/dedupe/ci 重装/cache clean；明确标注 node_modules 计入系统「App 数据」。
6. **首启引导**：原子部署 assets/npm CLI + 播种精选缓存 → registry ping 探测 → 选镜像（**默认官方 npmjs**，§18 第 7 项；镜像是加速选项不是开箱前提）与配置代理（能力中心网络项）。
7. **打包向导联动**：node_modules 默认入 APK + `.autojs.build.ignore` 排除规则 + 「完全离线变体」（宿主预装 node_modules.zip）+ 项目 lock 签名生成。

### 10.10 与既有机制的关系

- 桥/进程/看门狗/quiesce/原子部署/来源分级全部复用既有框架；npm 不建平行体系。
- `assets/npm` 原子部署复用 script-repo 的 tmp+sha256+rename；安装会话复用 EnginePool acquire/quiesce 四步与 TSF 双队列。
- 运行中脚本的 node_modules 被重装/删包 → 懒加载 ENOENT；per-project 互斥锁 + 默认「脚本结束后安装」+ 强制时显式风险确认。

### 10.11 优先级落定（npm 相关增补到 §14）

- **P0**：vendored npm CLI + 专用安装会话进程；零 spawn 主路径（install/ci/ls/uninstall/prune/dedupe）；T0 拦截 shim 硬失败；精选缓存种子 + 离线首装 + `--prefer-offline`；镜像/代理三路径 + replace-registry-host；事务化安装 + journal 自愈；磁盘/配额预检；hasInstallScript 前置告警 + 审批卡 UI（仅请求）；lock v3 + `npm ci` 强制 + 带外信任锚 + 多镜像交叉校验；依赖面板 + `auto.npm` 核心 API；打包向导 node_modules 入包。
- **P1**：spawn 桥完整 polyfill（stdio 假管道 + pgrp 杀树 + detached 拒绝）+ **lifecycle 脚本真实执行**（§18 第 7 项 2026-09-26 口径：安装时让用户自己选跑不跑，不设出厂卡口，也**不是**"审批通过才跑"的流）+ `npm run/exec`（纯 JS bin 白名单）；node-shim PIE + PATH 注入（2–3 台 ROM 红测）；npm 终端视图；在线 audit + audit signatures + OSV 离线；`offlineGap` + 种子金标准测试。（原「QuickJS 白名单库独立 vendored」随第 1 项裁掉。）
- **P2**：离线 bundle 打包器（desktop `npm ci` 物化 + cacache 复制体交付）+ 增量更新 + 导入 UX；「完全离线变体」打磨；native 依赖 **wasm 方案**（2026-09 拍板）：优先取上游 wasm 构建（`esbuild-wasm`、`argon2-wasm`、sql.js 等——Node 内置 `WebAssembly`，无 ABI/无 dlopen、一份全平台、随 bundle 离线送达），无 wasm 产物的回落纯 JS 替代/内置（sharp→jimp 或平台图像桥、bcrypt→bcryptjs、better-sqlite3→`node:sqlite`——Node 24 官方标 **STABILITY 1.2 Release-candidate**，随 libnode 钉版即锁 API，保守备选 sql.js-wasm）；安装期检测 `binding.gyp`/平台 optionalDeps 点名引导，不静默半装。**对标 AutoX-v7（研究笔记，2026-09）**：它**不需要**这条管线 —— 运行时是 Javet（`com.caoccao.javet:javet-node-android:5.0.2`，进程内 `NodeRuntime`）而非真 libnode，全仓零 node-gyp/prebuild/`NODE_MODULE_VERSION`/`.node` dlopen 痕迹；模块解析是自研 `NodeModuleResolver`（`createRequire` + package.json 走查 + ESM），常用包以**已物化的纯 JS 树**预置在 `assets/modules/npm`（buffer/stream/process/events + lodash/cheerio/bluebird/rxjs），原生能力全走 Java↔V8 绑定（`NativeApiManager` → `Autox.*`），paddle OCR 等 `.so` 只经 Java `System.loadLibrary`、与 JS 引擎无关。即：没有 N-API 加载面就没有 `.node` 交付问题。本仓 §7 桥本体就是 N-API addon（真 libnode 不能换），故 native 依赖的策略已定为 **wasm 优先、纯 JS 兜底**——他们 assets 全纯 JS 是兜底可行的实证；**prebuild `.node` 小工具已移出排期**（需要时再立需求），ABI/`--dest-os` 断言届时随需求一起复活。
- **P3**：跨项目共享 store 去重（pnpm 式，须 store↔lock 加签映射）；程序化安装服务化；ECDSA 签名强制；vendored npm 自动升级（仅通过零 spawn 金标准闸门）；esbuild 类**代码签名原生 exec** 独立通道。

### 10.12 npm 特有风险与缓解

| 风险 | 缓解 |
|---|---|
| `--ignore-scripts` 的「假装成功」（postinstall 下载二进制/自检、真原生包装上才炸） | packument `hasInstallScript` 前置扫描 + 显式 warning + 人工审批升级通道，**禁止静默** |
| 第三方 `.node` V8 ABI 稀缺且难匹配（Node24 `NODE_MODULE_VERSION`=137 与 libnode 快照不一致则 dlopen 崩）；`process.platform` 非 android 会让平台探测失真 | 当前策略**不引入第三方 `.node`**（wasm 优先、纯 JS 兜底；prebuild 工具已移出排期，需要时再立）；自建 libnode 必须 `--dest-os=android` + CI `process.platform/arch` 断言 + 16KB 双门禁（本仓构建线照旧） |
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
| 用户自写脚本 | 中 | Node | 全量 | 提示风险 |
| 第三方/市场脚本 | **低** | **Node（与自写脚本同进程、同权）** | **全量** —— **沙箱已裁，进程隔离这条防线不存在**（§18 第 1 项 2026-09-26） | 防线改为：**安装时按 `hasInstallScript` 如实告知并由用户当场选**（§18 第 7 项）+ 来源分级提示（本表）+ 运行期 TTL/看门狗；**文案不得让人误以为是沙箱跑的** |
| 打包分发脚本 | 中高 | Node | 打包时配置 | 走签名链 |

- RuntimeChannel/engines 通信也按来源分级（低信任不能给高信任发控制消息）。
- 桥的 `ModuleRegistry` 按 scripts 的 CapabilityMask 过滤 handler（非授权模块调用 → `ERR_PERMISSION_DENIED`，不是静默 no-op）。

---

## 12. JS API 设计

### 12.1 设计原则（对齐 AutoJsPro v9 二代 API 风格）
- **Promise 优先**：`await` 一切；同步语义的系统能力（如纯计算）由明确的同步函数提供（`images.format` 等纯函数）。
- **EventEmitter 事件**：a11y 事件、引擎事件、截图流、数据流统一 EventEmitter。
- **超时/取消**：`{timeout}` 选项默认给；返回 Promise 的可选 `AbortSignal`（图形接口）。
- **唯一入口**：脚本 `require('auto')` 返回命名空间根对象（`auto.a11y` / `auto.engines` / …），结构化维护 API。**落位**（2026-09-24 资产交付轨）：装配期 `BridgeDistDeploy` 把随包 dist 放进 `filesDir/node_modules/auto`（`ScriptPaths.autoModuleRoot` 单一出处；无 package.json 走 `index.js` 缺省入口），每个项目脚本沿目录树向上第 3 站解析到；桥 handler 由打包入口 kBootstrap 的 `attachNative` 在脚本前装上（见 §12.4 切片路线）。
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
- `auto.power`（脚本电源：`power_manager` 桥面的 `acquire`/`release`/`status`，§8.7）
- `auto.npm`（包管理与依赖生态，§10：install/ci/list/audit/offlineGap/importOfflineBundle/requestApprove——审批人机分离；事件面 `onProgress`/`onApproval`/`onWarning`/`onFinished` 四方法，wire 走 `events`/`approvals` 两个拉取口而非推送，§10.7）
- Node 内建：`fs/path/http/os/process` 等**完整可用**（除 `child_process` 显式报 `ERR_NOT_IMPLEMENTED`）；`@autojs/*` npm 包 SDK（`@autojs/opencv` 对齐 Pro）。

**接线现状（Kotlin 侧，与 `AppShell.assemble` 对齐；未列出的命名空间在两侧都还没有 handler）**：

> 本表有**机械化门禁**：`bridge/js/test/wire-reconcile.test.cjs` 双向对账两份源码（不经 mock）——JS `invoke` 的命名空间必须被 `register`、JS 发的每个方法必须有 `when(request.method)` 分支、宿主方法表里每个分支必须有人发（收了没人发的 wire 名要么 facade 漏调、要么写进该测试的 `ALIASES` 并说明为什么，且 `ALIASES` 不许虚报）。它是 `npm test` 的一部分，随 CI 跑；a11y 选择器动作经 `call('<m>')` 字面量、`shell.shell()` 别名与 `findOneOrNull` 兼容分支是仅有的三处登记。 同族另有三道：`event-wire.test.cjs`（npm **事件面** wire 逐字对账——宿主 `phaseWire`/`kindWire`/`actionWire`/`type` ⇄ `npm.ts` 的 `PHASES`/`WARNING_KINDS`/`APPROVAL_ACTIONS`/`routeInstallEvent` 分支双向集合相等，防 `.name.lowercase()` 折出 `post_check` 那类连字符漂移），并钉**键名面**——`encodeEvent`/审批 `mapOf` 发的每个键 ⇄ JS `w.x` 读的键逐分支对账，JS 读宿主不发的键即红、宿主发了没人读的键须登记 `UNREAD` 并写明理由（mock 测试发的永远是 JS 自己认识的键，键名漂移只有这道门能抓）与 `err-catalog.test.cjs`（错误目录三面对账，见 §7.6）；`wiring-table.test.cjs`（**本表**三向对账：facade 列点名的 `.ts` 真存在、handler 列点名的类真存在（花括号组展开，且每行至少认出一个候选防改名绕过）、宿主 `register` 了的命名空间本表必列 + 状态列写「已挂」的必须真注册——表是手写的，§19 又宣布它为事实来源，就该有门看着）；`pull-wire.test.cjs`（**另两条拉取环** a11y.events / sensors.drain 的回包键名 + 入参键名对账——入参 `sinceSeq` 改名的失败面是宿主读不到、游标恒 0、事件重复投递，不报错只出错数据）。

| 命名空间 | JS facade | Kotlin handler | 挂载状态 |
|---|---|---|---|
| `console` | `console.ts` | `ConsoleCollector`（`:bridge:java`） | `AppShell.assemble` 已挂；`:ui` 控制台屏读口已接（`HostSummary.console`，§7.3 末） |
| `engines` | `engines.ts` | `EnginesNamespaceHandler`（`:app-service:runtime`） | 已挂（含 `heartbeat` 打点，§8.4；命名通道 `channel/channelEmit/channelDrain/channelClose` 双侧对齐：Kotlin 侧缓冲 + 游标、`EngineChannel` 按 `sinceSeq` 节流轮询；`status` 只读在途表、结算后 `ERR_NOT_FOUND` 不伪造 `STOPPED`，`exec` 回 `EngineSessionImpl`（`cancel`→`stop` 归口、`onExit`→`status` 轮询：本会话 cancel 后结算报 null、外部结算报 UNKNOWN）） |
| `a11y` | `a11y.ts` | `A11yNamespaceHandler`（`:platform:capabilities`）+ `CapabilityNamespaces.a11y(tree, actions, input, events)` 装配缝（树/动作/输入/事件四 SPI）+ **Android 真实现** `AndroidUiTree`/`AndroidGestureInput` 经 `SystemA11yBridge`（`A11yServiceHolder` 连接态） | **生产已接**：`PlatformWiring.inject` → `a11yHandler` → `AppShellApplication.installWithFiles`（服务未连 = 桥如实 `ERR_SERVICE_DISABLED`，装配期即可注入不必等 `onServiceConnected`）；内存实现仍是单测缺省；未注入缝保留 → 仍如实 `ERR_NOT_IMPLEMENTED` |
| `screen` | `images.ts` | `ScreenNamespaceHandler`（`:platform:capabilities`）+ `ScreenshotSource`（333ms 节流/§8.8 策略预检/句柄记账）+ **Android 真实现** `AndroidFrameProducer`（经 `SystemA11yBridge.takeScreenshot`：API34+ 窗口级、API30–33 显示级、API<30 如实 `ERR_NOT_IMPLEMENTED`；失败码分类 SECURE→BLACK_FRAME/限频→INVALID_PARAM/通道失效→SERVICE_DISABLED/内部→ERR_IO） | **生产已接**：`PlatformWiring.screenHandler` → `AppShellApplication.installWithFiles`（与 a11y 同底：服务未连 = `ERR_SERVICE_DISABLED`）；**回包尺寸 = 系统真值**（`ProducedFrame` 随帧走，不再固定 1080×2400）。MediaProjection 高清会话仍待（换 producer 即插） |
| `images`（decode/matchTemplate/findImage/findColor/release） | `images.ts` | `ImagesNamespaceHandler.kt`（`:platform:capabilities`，经 `CapabilityNamespaces.images(analyzer)` 转接；SPI = `:domain` `ImageAnalyzer`+`ImageFrame`/`ImageMatch`，真身 = `:platform:system` 的 `NativeImageAnalyzer`/`JniOps` + `:bridge:image` 的 `libopencv.so`，2026-09-25 已接） | **桥面已可挂**：`assemble` 的 `imagesHandler` **独立缝**（同 datastore/zip/settings/notification/clipboard/sensors —— 图像面无共担门禁：读图是应用私有目录内 IO、匹配是纯计算，`ERR_FILE_NOT_FOUND`/`ERR_STALE_HANDLE` 判据在 SPI；不入 `systemHandlers` 束；未注入则如实 `ERR_NOT_IMPLEMENTED`；`AppShellKit.assemble` 透传同一缝）。**生产侧已喂**（2026-09-25）：`PlatformWiring.of` 构造 `NativeImageAnalyzer.of(JniOps.loadOrNull())` 传 `inject(images = …)` —— so 缺位（未跑 `build-opencv.sh` 的 CI JVM / 无 native 的设备）→ null → 桥对 `images.*` 如实 `ERR_NOT_IMPLEMENTED`（一个看不见像素的内存分析器只能靠自报坐标假装匹配成功，那比没有更坏 —— 这条防线从"不喂"变成"缺件不喂"，语义不变）。两侧钉子：`ImagesNamespaceHandlerTest` + `images.test.cjs` + `NativeImageAnalyzerTest` |
| `dialogs`/`shell`/`device`/`app`/`floatingWindow` | `extras.ts` | `SystemNamespaces.{Dialogs,Shell,Device,App,FloatingWindow}NamespaceHandler`（`:platform:capabilities`，经 `CapabilityNamespaces.{dialogs,shell,device,app,floatingWindow}` 转接） | **生产已接**：`com.autoscript.shell.PlatformWiring.of(context)`（§6 包级例外二）把 `SystemSpis.of` 十件拼成 `systemHandlers` 束 + 七独立缝，`AppShellApplication.installWithFiles` 喂 `AppShellKit.assemble`（七个字段各自可空，未注入仍如实 `ERR_NOT_IMPLEMENTED`；`dialogs` **生产已接** —— `PlatformWiring.of(context)` 构造 `AndroidDialogHost(SystemDialogOps(...))`（实现住 :platform:capabilities，`inject` 单测缺省不传仍 null→`ERR_NOT_IMPLEMENTED`）。SPI 侧 `shell`/`device`/`app`/`floatingWindow` 四件走 `:platform:system` 真实现；JS 双侧契约见 `extras.test.cjs`（mock 宿主验 wire 形状）。**参数面已收口（2026-09-26，原 §12.3.3 记的两处缺口）**：`floatingWindow.create` 三处一起改齐 —— facade 把 `{title,width,height}` 原样发 payload（缺省显式 `null`，不静默删键）+ 补 `close({ref})`，与 handler 的 payload 要求对上；`screen.startCapturer` 的 `{width,height}` 现透给 `FrameSource.openSession`（**请求提示**，回包尺寸仍是真实帧）。两侧各加契约测试（`extras.test.cjs` / `screen.test.cjs` + `ScreenNamespaceHandlerTest`/`ScreenshotSourceTest`），任一侧漂移即红 |
| `datastore` | `datastore.ts`（`get/put/remove/contains/keys/clear`；`get` 拆 `{found,value}` 信封：缺失 `undefined` ≠ 存的 JSON `null`） | `DatastoreNamespaceHandler`（`:platform:capabilities`，经 `CapabilityNamespaces.datastore(store)` 转接；SPI = `:domain` `DataStore`，测试传 `InMemoryDataStore`） | **已可挂**：`assemble` 的 `datastoreHandler` **独立缝**（不入 `systemHandlers` 束 —— 存储面无共担门禁；未注入则如实 `ERR_NOT_IMPLEMENTED`；`AppShellKit.assemble` 透传同一缝）。字节值不过桥（§7.4 side-channel 未接 → `get` 如实 ERR_NOT_IMPLEMENTED）、`transaction` 不上桥（facade 无此方法）；SPI 真身 `:platform:system` `AndroidDataStore`，生产已接（`PlatformWiring.of` → `inject` → `installWithFiles` 喂 `datastoreHandler` 独立缝；`PlatformWiringTest` 同路径真转接覆盖）。双侧钉子：`DatastoreNamespaceHandlerTest` + `datastore.test.cjs` |
| `zip` | `zip.ts`（`compress`/`extract` 两方法，TTL 缺省 60s） | `ZipNamespaceHandler`（`:platform:capabilities`，经 `CapabilityNamespaces.zip(archiver)` 转接；SPI = `:domain` `ZipArchiver`，真身 `:platform:system` `JdkZipArchiver`） | **已可挂**：`assemble` 的 `zipHandler` **独立缝**（同 datastore —— 归档无共担门禁，不入 `systemHandlers` 束；未注入则如实 `ERR_NOT_IMPLEMENTED`；`AppShellKit.assemble` 透传同一缝）。SPI 错误原码透传不折叠；`unzip` 等未约定别名两侧都不提供。双侧钉子：`ZipNamespaceHandlerTest` + `zip.test.cjs`；归档语义（zip-slip）钉在 `JdkZipArchiverTest` |
| `settings` | `settings.ts`（`canWrite`/`getString`/`getInt`/`putString`/`putInt` 五方法与 SPI 1:1，读缺失回 `null`；不提供猜型的 `get`/`put`） | `SettingsNamespaceHandler.kt`（`:platform:capabilities`，经 `CapabilityNamespaces.settings(systemSettings)` 转接；SPI = `:domain` `SystemSettings`，真身 `:platform:system` `AndroidSystemSettings`） | **已可挂**：`assemble` 的 `settingsHandler` **独立缝**（同 datastore/zip —— `WRITE_SETTINGS` 判据在 SPI、与五命名空间无共担门禁，不入 `systemHandlers` 束；未注入则如实 `ERR_NOT_IMPLEMENTED`；`AppShellKit.assemble` 透传同一缝）。双侧钉子：`SettingsNamespaceHandlerTest` + `settings.test.cjs`；授权语义钉在 `AndroidSystemSettingsTest`；生产已接（`PlatformWiring.of` → `inject` → `installWithFiles` 喂 `settingsHandler` 独立缝；`PlatformWiringTest` 同路径覆盖） |
| `notification` | `notification.ts`（`canPost`/`post`/`cancel` 三方法与 SPI 1:1；`post` 未授权**抛** `ERR_PERMISSION_DENIED`、`cancel` 回 void 无回执；不带 `channelId`/actions/`ongoing`） | `NotificationNamespaceHandler.kt`（`:platform:capabilities`，经 `CapabilityNamespaces.notification(poster)` 转接；SPI = `:domain` `NotificationPoster`+`NotificationSpec`，真身 `:platform:system` `AndroidNotificationPoster`） | **已可挂**：`assemble` 的 `notificationHandler` **独立缝**（同 datastore/zip/settings —— `POST_NOTIFICATIONS` 判据在 SPI、与五命名空间不共担 OVERLAY/ROOT/ADB_INPUT，不入 `systemHandlers` 束；未注入则如实 `ERR_NOT_IMPLEMENTED`；`AppShellKit.assemble` 透传同一缝）。id 必填（§8.5「只发一次」的幂等键目标，不自动发号）。双侧钉子：`NotificationNamespaceHandlerTest` + `notification.test.cjs`；门禁语义钉在 `AndroidNotificationPosterTest`；生产已接（`PlatformWiring.of` → `inject` → `installWithFiles` 喂 `notificationHandler` 独立缝；`PlatformWiringTest` 同路径覆盖） |
| `clipboard` | `clipboard.ts`（`getText`/`setText` 两方法与 SPI 1:1；读空回 `null`，空串是真值；写侧无门禁；不带 `clear`/`hasText`/富文本） | `ClipboardNamespaceHandler.kt`（`:platform:capabilities`，经 `CapabilityNamespaces.clipboard(clipboard)` 转接；SPI = `:domain` `Clipboard`，真身 `:platform:system` `AndroidClipboard`+`ClipboardOps`） | **生产已接**：`assemble` 的 `clipboardHandler` **独立缝**（同 datastore/zip/settings/notification，不入 `systemHandlers` 束；未注入如实 `ERR_NOT_IMPLEMENTED`；`PlatformWiring.of` → `inject` → `installWithFiles` 喂缝；`PlatformWiringTest` 同路径覆盖）。双侧钉子：`ClipboardNamespaceHandlerTest` + `clipboard.test.cjs`；读写语义钉在 `AndroidClipboardTest` |
| `sensors` | `sensors.ts`（`isSupported`/`register`/`unregister`/`unregisterAll`/`drain` 五方法与 SPI 1:1；拉取式游标不做 push 回调，`on('change')` 只是 facade 节流轮询；delay 缺省 `NORMAL`、wire 传名字面量；`ignoresUnsupported` 只折叠 `ERR_NOT_SUPPORTED`；P0 只做 motion/environment 名单） | `SensorsNamespaceHandler.kt`（`:platform:capabilities`，经 `CapabilityNamespaces.sensors(sensors)` 转接；SPI = `:domain` `SensorSource`+`SensorDelay`/`SensorEvent`，真身 `:platform:system` `AndroidSensorSource`+`SensorOps`） | **生产已接**：`assemble` 的 `sensorsHandler` **独立缝**（同 datastore/zip/settings/notification/clipboard，不入 `systemHandlers` 束；未注入如实 `ERR_NOT_IMPLEMENTED`；`PlatformWiring.of` → `inject` → `installWithFiles` 喂缝；`PlatformWiringTest` 同路径覆盖）。双侧钉子：`SensorsNamespaceHandlerTest` + `sensors.test.cjs`；采样语义（归一化/发号/有界环/幂等/拒收折叠）钉在 `AndroidSensorSourceTest` |
| `npm` | `npm.ts` | `NpmBridgeHandler`（`:app-service:packager`，`mount(): NamespaceHandler`） | **已可挂**：`assemble` 的 `npmHandler` 缝（未注入则如实 `ERR_NOT_IMPLEMENTED`；方法表 13 项 —— 含 2026-09-26 补的 `events`/`approvals` 两个事件拉取口，`resolveApproval` 刻意不在桥面，§10.5 人机分离）。**wire 形状已两侧对齐**（原与 `a11y.waitFor` 同类漂移：facade 读宿主从不发的键）：`install` → `:domain` `InstallHandle`（`{handleId,projectId,enqueuedAtMillis}`，非包体）；`audit` 键名 `vulns`；`list` 不带 `sizeBytes`；`offlineGap` 带 `version`；`requestApprove` 校验 + 回显 `scripts`；`InstallEvent.phase` 取 `:domain` 六阶段。钉子在 Kotlin `NpmBridgeHandlerTest` + JS `npm-contract.test.cjs`（mock 逐字复刻宿主回包）；事件面另有 `NpmEventDrainTest` + `npm-events.test.cjs`（游标/环语义与响亮分档，§10.7） |
| `workManager`（create/cancel/list 建任务面） | `workManager.ts`（排期工具 + 桥门面） | `WorkManagerNamespaceHandler`（`:app`，直驱 Scheduler，直写注册表） | **已挂**（恒挂载，调度器是本壳自建、无注入缝；cron 非法表达式桥侧 `ERR_INVALID_PARAM`，校验出处 `CronTab.parse` 与 UI 侧同口径） |
| `power_manager`（acquire/release/status 脚本电源面） | `power.ts`（`acquire`/`release`/`status`） | `PowerManagerNamespaceHandler`（`:app`，直驱 `foregroundKeeper()` 的同一本 `WakeLockLedger`，账本零改） | **已挂**（`powerManagerHandler` **独立缝**，与 datastore/zip/settings/notification/clipboard/sensors 同形、不入 `systemHandlers` 束；生产由 `AppShellApplication.installWithFiles` 从 keeper 账本现建喂缝；脚本锁必须限时、无期限只属框架；token 服务端分配；取不到锁 `ERR_SERVICE_DISABLED` 且未记账；直驱账本不走 `ForegroundKeeper.start(token)` 单槽） |

**为什么能力命名空间走注入缝**：`a11y`/`screen` 的真实现住 `:platform:capabilities`，而 §6 禁止 `:app` **非装配包**直连 `:platform`（装配包 shell 经包级例外二可直连，见 `PlatformWiring` —— 但注入缝本身仍是设计答案：`AppShellKit`/`AppShell` 保持纯 JVM 可测，真假实现共用同一条缝）。解法是 `:domain` 上的挂载缝 `NamespaceHandler` + `:platform:capabilities` 的薄转接 `CapabilityNamespaces.{a11y,screen}`，由持有真实现的 Android 侧在调用 `assemble` 时注入；`BridgeRouter` 的 `RequestHandler` 只是这条缝的 typealias。这不违反依赖规则：两侧都只见 `:domain`。

**五个系统命名空间（`dialogs`/`shell`/`device`/`app`/`floatingWindow`）分两层，别混**：
- **语义层**（handler）住 `:platform:capabilities` 的 `SystemNamespaces.kt`，纯 JVM 可测（假 SPI 注入即可跑）：参数校验（spec 守卫、必填字段、`timeout > 0`）、枚举字面量解析（`ShellMode`/`DialogMode`，拼错即报错不静默套默认）、默认值（shell 超时 30s）、错误分类**透传**（`AutojsException.error` 原码回桥）、响应形状编码（与 `extras.ts` 逐字对齐）；
- **Android 实现层**住 `:platform:system` —— `com.autoscript.domain.system` 的 `ShellExecutor`/`DeviceInfoProvider`/`AppLauncher`/`FloatingWindowHost` 四件**已落地**（`AndroidShellExecutor`/`AndroidDeviceInfoProvider`/`AndroidAppLauncher`/`AndroidFloatingWindowHost`，入口 `SystemSpis.of(context)`；各自只碰一小块 Android，其余在可注入的 ops 缝后面，本机无 SDK 也能跑契约测试），`DialogHost` **住 :platform:capabilities 而非本模块**（domain KDoc 约定 + 平台模块间无依赖边；编排 `AndroidDialogHost` 纯 JVM 可测，设备面在 `…capabilities.device` 子包）。**有状态的判断归实现层**：句柄记账与 generation、`close` 幂等、`ERR_STALE_HANDLE`/`ERR_PERMISSION_DENIED` 的起源、`DialogMode.AUTO` 按 overlay 可见性选路（降级决策需要 overlay 实况，handler 看不到）。

所以「为什么 handler 不住 `:platform:system`」有两层理由：(1) 五个命名空间共享一套门禁组（OVERLAY/ROOT/ADB_INPUT），语义放一起才不会各写一份校验；(2) handler 若住 `:platform:system`，装配层就得同时直连 `:platform:capabilities` 与 `:platform:system` 两个模块才凑得齐 Router —— §6 对 `:app` 非装配包明令禁止这一直连（装配包 shell 的生产装配 `PlatformWiring` 经包级例外二放行，但那只是"把 SPI 拼成束"，不构成把 handler 挪去 `:platform:system` 的理由：主因仍是 (1) 的共担门禁）。**§9.6 的存储面（datastore/settings/zip）与这五个命名空间无关**：`datastore` 已单列入上表（handler 住 `:platform:capabilities`、独立注入缝；SPI 实现仍按模块表落 `:platform:system`）；`zip` 已单列入上表（SPI+实现+桥面俱全，§9.6）；`settings` 已单列入上表（SPI+实现+桥面俱全，§9.6）—— 三者都与五个命名空间无共担门禁，已逐条单列；`notification` 是**第四条独立缝**（门禁是 `POST_NOTIFICATIONS`，同样不与那五个共担），故也单列入上表。`clipboard` 是**第五条独立缝**（剪贴板无门禁，读受限是系统的 null 答案、写不受限，判据在 SPI，同样不与那五个共担），故也单列入上表。`sensors` 是**第六条独立缝**（P0 名单无运行时门禁，未知名→`ERR_NOT_SUPPORTED`、系统拒收→`ERR_SERVICE_DISABLED` 判据在 SPI，同样不与那五个共担），故也单列入上表。`images` 是**第七条独立缝**（§9.2 图像面：无运行时门禁，`ERR_FILE_NOT_FOUND`/`ERR_IO`/`ERR_STALE_HANDLE` 判据在 SPI 自己身上）：桥面五方法 `decode`/`matchTemplate`/`findImage`/`findColor`/`release` 已就位（阈值**一个键** `threshold`、域 `[0,1]`、未匹配回裸 `null` 不是异常；找色的 `color` 恒四分量 `[r,g,b,a]`、`tolerance` 逐分量 `[0,255]`、`region` 四元组，未命中同样回裸 `null`，而“扫过 0 像素”是 `ERR_INVALID_PARAM`），真实现也已接（`NativeImageAnalyzer` + `libopencv.so`，见 §9.2 末）—— 与那六条现在完全同形：`PlatformWiring.of` 都喂真实现，唯独图像面多一条"so 缺位即不喂"的判据（`JniOps.loadOrNull()`）。

**能力门禁不在 handler 里**：`:app-service:permission-center` 的 `PermissionFacade` 住 `:app-service:*`，而 `:platform:capabilities` 的 archUnit 黑名单含 `com.autoscript.appservice..`（§6）。门禁由装配层在取用这些 handler 之前完成（`ensure(Capability.OVERLAY)` 等），handler 只负责**能力已保证之后的语义**；被拒时由 `PermissionFacade` 抛带引导文案的 `ERR_PERMISSION_DENIED`，handler 侧的分类错误（如句柄过期 `ERR_STALE_HANDLE`、服务未启用 `ERR_SERVICE_DISABLED`）原样透传到 JS。

### 12.3 关键签名示例（风格示范）

**本节的口径**：下面每一行都在 `bridge/js/dist` 上真跑过（mock 宿主逐字复刻 Kotlin handler 的回包），不是照 §12.2 的命名空间清单手写的。所以这里同时是 **facade 现状的实测记录** —— 已落地与未落地分开写，未落地的一律按**接口期两侧都不提供**处理（宿主如实 `ERR_NOT_IMPLEMENTED`），示例不写"将来会通"的用法。

#### 12.3.1 已落地的调用（照抄可跑）

```ts
// ── a11y：选择器链（条件之间 AND；findOne 无匹配抛 NotFoundError，findOneOrNull 回 null）
const btn = await auto.a11y.selector()
  .text('启动').packageName('com.example')   // 条件名与 :domain UiSelector 1:1（没有 .package() 这种截断别名）
  .time(2_000)                               // 超时挂在**选择器**上：findOne 未传 timeout 时取它
  .findOne()
await btn.click();                           // UiObject 句柄代理：动作经 invoke 回桥（携带 generation 校验）
await btn.bounds;                            // getter 也是桥调用（一次 invoke）—— 循环里逐节点读属性要先想清楚
await btn.dispose();                         // void（fire-and-forget；释放失败不抛给脚本）

// 只问"在不在"：无匹配是控制流不是异常 —— findOneOrNull 回 null（其余错误照常抛）
const maybe = await auto.a11y.selector().text('登录成功').findOneOrNull({ timeout: 5_000 })

// 等到出现为止（回 boolean；超时也回 false，**不抛** NotFoundError）
const ok = await auto.a11y.waitFor(
  auto.a11y.selector().text('登录成功'),
  { timeout: 10_000, interval: 300 })

// 事件流是"拉取式游标"（不是 push 回调）：空增量回 {first:sinceSeq,last:sinceSeq,events:[]}，
// 调用方以前进游标为准 —— 别拿"这轮 0 条"当"界面没变化"（两者不是同一件事）
const batch = await auto.a11y.events({ sinceSeq: 0, batch: 32 })

// 手势：先问能力（false 时走能力中心引导），再派发（通道关门回 false；非法手势抛 ERR_INVALID_PARAM）
if (await auto.a11y.canPerformGestures()) {
  await auto.a11y.gesture({
    strokes: [{ points: [{ x: 540, y: 1800 }, { x: 540, y: 600 }], durationMillis: 300 }],
  })
}

// ── screen：截图帧源（句柄归 screen 自己发号）
const img = await auto.screen.capture();     // 锁屏 ERR_SCREEN_LOCKED / FLAG_SECURE ERR_BLACK_FRAME /
                                             // 无窗口 ERR_SERVICE_DISABLED / 节流 ERR_INVALID_PARAM（退避重试）
console.log(img.width, img.height);          // 尺寸是**系统真值**（随帧走，不是固定 1080×2400）
await img.recycle();                         // 打 screen/recycle

// 会话式（MediaProjection）：open 时即做策略判定，会话内逐帧拉；close 是连接态（二次关 ERR_NOT_FOUND）
const cap = await auto.screen.startCapturer();          // 也可 {width,height}：**请求提示**，生产者可忽略
// await auto.screen.startCapturer({ width: 720, height: 1280 });  // 回包与帧尺寸仍是真实值，不按提示编
try {
  const f1 = await cap.nextFrame();
  await f1.recycle();
} finally {
  await cap.close();
}

// ── images：图像分析面（**与 screen 共用一张帧表**，§18 第 8 项 (b)，见 12.3.2 第 3 条）
const shot = await auto.images.decode('/sdcard/shot.png');   // 宽高是**文件真值**
const icon = await auto.images.decode('/sdcard/icon.png');
const m = await auto.images.findImage(shot, icon, { threshold: 0.9 });  // null = 没找到（**不是异常**）
if (m) console.log(m.x, m.y, m.width, m.height, m.confidence);

// matchTemplate 与 findImage 是同一个 opencv 概念的 v9 两名（wire 逐字段相同，宿主同一套校验）
const m2 = await auto.images.matchTemplate(shot, await auto.images.fromFile('/sdcard/part.png'),
  { threshold: 0.85 });

// 找色（P1 第一个算子）：null = 扫过了、没有；ERR_INVALID_PARAM = 根本没找（空区域/region 越界）
const px = await auto.images.findColor(shot, [18, 52, 86, 255], 10, { region: [0, 0, 540, 2400] });

await icon.recycle();                        // 谁的帧谁来放（images/release）
await shot.recycle();                        // 再放同一帧 → ERR_STALE_HANDLE（不是静默成功）

// ── workManager：定时任务（亮屏+解锁是保底契约；screen 三态显式声明）
const task = await auto.workManager.createTimedTask({
  name: '早安打卡', projectId: 'p1', scriptPath: 'entry.js',
  schedule: auto.workManager.cron('0 9 * * 1'),   // 5 字段 分 时 日 月 周；每周一 09:00
  timezone: 'Asia/Shanghai',
  screen: 'SCREEN_ON',                            // SCREEN_ON / ANY / SCREEN_OFF
});
// 登记前本地预览下一跳（纯本地排期工具，唯一时序来源；段内合法性仍归宿主 CronTab.parse 裁决）
const next = auto.workManager.nextFireAfter(auto.workManager.cron('0 9 * * 1'), Date.now());
await auto.workManager.cancelTask(task.id);       // 幂等：从未登记的 id 照样 true
const tasks = await auto.workManager.listTasks();

// ── power：限时唤醒锁（CPU 不休眠；不碰屏幕亮灭，也不起停前台服务）
const token = await auto.power.acquire(10 * 60_000);  // 超时**必填**（无期限只属框架保活）；token 服务端分配
try {
  await longRunningWork();
} finally {
  await auto.power.release(token);               // 重复放/已过期 → false（如实不对账成功）
}
const lock = await auto.power.status();          // {held, holders}；分歧时 held=false 而 holders>0，不折叠

// ── engines：多引擎（池仲裁；超载排队，不静默丢弃）
const other = await auto.engines.exec({ projectId: 'p1', scriptPath: 'worker.js' });
const chan = await auto.engines.channel('progress');       // 命名通道**显式打开**（session.channel 恒 null）
await chan.emit('progress', JSON.stringify({ done: 3 }));  // 载荷是 JSON 字符串，不是对象
const sub = chan.on('progress', (payload) => console.log('子脚本说', payload), { pollMillis: 500 });
other.onExit((info) => {                                    // info 是 CrashInfo | null，不是数字退出码
  console.log(info === null ? '干净结束' : `异常：${info.cause}`);
  // 外部结算（看门狗/他人 stop）报 {cause:'UNKNOWN'} —— 结算即离表，不把"查不到"伪造成干净结束
});
await chan.close(); sub.cancel();
await other.cancel();                                       // → engines.stop(runId)，池四步 quiesce
console.log(await auto.engines.poolStats());                // {capacity, free, busy}

// ── dialogs / shell / device / app / floatingWindow
const name = await auto.dialogs.prompt('输入名字', { mode: 'auto' });  // auto：overlay 可见弹窗，否则通知回调
const out = await auto.shell.exec('pm list packages');      // shell 是**命名空间对象**，不是可调用函数；
                                                            // 分级 DENIED 抛 ERR_PERMISSION_DENIED
console.log(out.code, out.stdout, out.stderr);
console.log(await auto.device.model(), await auto.device.sdkInt());
console.log(await auto.app.launch('com.example'), await auto.app.currentPackage());  // false/null 是**诚实答案**
const win = await auto.floatingWindow.create({ title: '面板', width: 300, height: 200 });  // {refId,generation}；缺省项显式 null
await auto.floatingWindow.close(win);   // 幂等；晚到/重放 → ERR_STALE_HANDLE

// ── datastore / zip / settings / notification / clipboard / sensors（六条独立缝）
await auto.datastore.put('progress', { chapter: 3 });
const saved = await auto.datastore.get('progress');         // 缺键 undefined ≠ 存的 JSON null（不折叠）
await auto.zip.compress('/sdcard/out', '/sdcard/out.zip');  // TTL 缺省 60s（归档可大可慢，5s 默认必超）
const bright = await auto.settings.getInt('screen_brightness');  // 缺键 null（0 是合法亮度，不拿 0 冒充）
if (await auto.settings.canWrite()) await auto.settings.putInt('screen_brightness', 128);
if (await auto.notification.canPost()) {
  await auto.notification.post({ id: 1, text: '脚本跑完了', title: 'AutoScript' });  // 未授权**抛**，不静默丢弃
}
await auto.clipboard.setText('要粘贴的文本');                  // 空串是合法内容，读空回 null
const sub2 = await auto.sensors.register('accelerometer', { delay: 'UI' });  // 未知名抛 ERR_NOT_SUPPORTED
if (sub2) {
  const stop = sub2.on('change', (evs) => console.log(evs[0].values), { intervalMs: 200 });
  await sub2.unsubscribe(); stop();                          // on 只是节流轮询，不是第二套订阅语义
}

// ── npm：跨进程路由到全局安装会话（TTL 绑定，绝不阻塞脚本事件循环）
const handle = await auto.npm.install('axios', { timeout: 60_000 }); // → {handleId, projectId, enqueuedAtMillis}
// 回包只代表**已入队**：宿主此刻还不知道会装出什么版本，回猜的版本号就是伪造（§1）
const installed = await auto.npm.list();                    // 装了什么以 lockfile 为准（含 version）
await auto.npm.ci({ offline: true });                       // lockfile v3 严格重建（验签后）
const gap = await auto.npm.offlineGap();                    // 离线闭包缺哪些包（名+版本+尺寸）
const report = await auto.npm.audit({ offline: true });     // 键名是 vulns（不是 vulnerabilities）
auto.npm.onProgress((e) => console.log(e.phase, e.name, e.percent));  // phase: queued/resolve/download/
                                                                     // reify/post-check/done
auto.npm.onApproval((req) => notify('需人工确认', req.pkg)); // 只能提交请求，绝不脚本直调（人机分离）
auto.npm.onFinished((f) => (f.success ? done() : fail(f.detail))); // 成功+失败都到（install 回包只是已入队）
                                                             // 四条订阅 2026-09-26 起由 events/approvals 拉取口真投递
await auto.npm.requestApprove('evil-pkg', { scripts: ['postinstall'] });  // → {requestId, status:'pending', scripts}
                                                             // scripts 是**入参回显**（宿主确认收到了这份清单）

// ── console：数据面（可丢包，永不抛给脚本；丢包经 onQueueError 报）
const offQe = auto.console.onQueueError((e) => console.warn('日志丢了', e.level, e.reason));
await auto.console.log('普通日志', { a: 1 });               // log/info/warn/error/debug 五档，都回 Promise<void>
offQe();
```

#### 12.3.2 读这段示例时必须知道的六条（每一条都是踩过的坑）

1. **错误面要从 `require('auto')` 具名导入，不在 `auto` 根对象上**：
   `const { AutojsError, ERROR_CODES } = require('auto')` 成立，`auto.AutojsError` 是 `undefined`（`index.ts` 的具名导出，不挂在命名空间根上）。判错两条路：`e instanceof AutojsError && e.code === 'ERR_FILE_NOT_FOUND'`，或 `e.is('ERR_FILE_NOT_FOUND')`。
   **`ErrCode` 是 TS `const enum`，运行期不存在**（编译期内联，`dist` 里只剩 `/* ErrCode.NOT_FOUND */` 注释）。所以 `e.code === ErrCode.FILE_NOT_FOUND` 只对 TS 脚本成立；`.js` 脚本用 `ERROR_CODES` 里的字符串字面量。`bridge/js/src` 内部用 `ErrCode` 是因为它整体过 `tsc`，不是"运行期也能拿到"的证据。
2. **`auto.shell` 是命名空间对象，不是可调用函数**：`await auto.shell('pm list packages')` 当场 `TypeError`（`auto.shell` 是 `{exec, shell}`）。**`shell.shell()` 是别名，wire 上仍是 `shell/exec`**。`auto.a11y.selector().timeout(2000)` 同理——选择器上的超时方法叫 `time()`（`timeout` 只在 `findOne` 的选项里）。
3. **`screen.*` 与 `images.*` 是两个释放入口、一张帧表**（§18 第 8 项 (b) 2026-09-26 落地，"帧不通用"取消）：`decode` 的帧 `recycle()` 打 `images/release`，`capture` 的帧打 `screen/recycle` —— **打进去是同一张表**，所以 `screen.capture()` 的帧可以直接当 `findImage`/`findColor` 的 haystack，反过来 `images.release()` 也放得掉一帧截屏。放过的帧任一侧再用都是 `ERR_STALE_HANDLE`（同一个"已释放"事实）。**"截屏→找图"不再需要先落成文件**（§9.2 的落地段写明了链路与钉子）。
4. **未命中 / 缺键 / 空结果是答案，不是异常**：`findImage`/`matchTemplate`/`findColor` 未命中回裸 `null`（`findColor` 的 native 侧用 `x = -1` 哨兵，因为 `(0,0)` 是合法首像素）；`findOneOrNull` 回 `null`；`datastore.get` 缺键回 `undefined` 而存的 JSON `null` 回 `null`（两者不折叠）；`settings.getInt`/`clipboard.getText` 缺键回 `null`。**但"扫过 0 像素"（空 region / region 越界）是 `ERR_INVALID_PARAM`** —— 那不是"没有"，是"根本没找"，混成 `null` 会让脚本把空区域当成搜过一遍。
5. **引擎会话的两个名字都是 v9 的两代形态，别照旧写法**：`engines.exec({projectId, scriptPath})`（不是 `{script}`）；`session.onExit(info => …)` 且 `info` 是 `CrashInfo | null`（不是 `on('exit', code => …)` 的数字码，也没有 `.on` 这个方法）；`session.channel` 恒 `null`，命名通道要 `engines.channel(name)` **显式打开**（隐式建通道会在宿主侧留一条永远没人 drain 的缓冲）。
6. **npm 的事件订阅名与 §12.2 表格一致，不是 `on('progress')`**：`onProgress`/`onApproval`/`onWarning`/`onFinished` 四个独立方法（各有退订返回值）。`on('progress')`/`on('approval')` 在 facade 上**不存在**（会 `TypeError`），wire 上也没有对应方法（§10.8 的示例同批改）。四条都是**拉取轮询**投递（首订立拉、退订自停）：宿主侧没有推给脚本的通道，谁把 wire 上的 `events`/`approvals` 删了，`pump*` 会响亮抛 `ERR_NOT_IMPLEMENTED` 而不是安静空转。

#### 12.3.3 接口期未落地（示例里故意不写，写了就是撒谎）

- **`images` 的 `toGrayscale`/`crop`/`rotate`/`pixel`**：`fromFile` 是 `decode` 的合法别名（两侧同名 `decode`），其余名字**两侧都没有**。灰度、裁剪、缩放、旋转与特征特殊一点：**计算核已落**（`imgnative_gray` 28 例 + `imgnative_crop` 46 例 + `imgnative_resize` 45 例 + `imgnative_rotate` 45 例 + `imgnative_feature` 32 例 host 断言，§9.2 末），但**桥面刻意不开** —— `:domain ImageAnalyzer` 五方法里没有它们，handler 也不认 `toGrayscale`/`crop`/`resize`/`rotate`/`feature`。落了一半是刻意的：脚本侧还没有消费方（刚性匹配与特征匹配是两种找图语义，不开第二个）。
- **`engines.stop(runId)` 之外的会话操作**、`npm` 的 `resolveApproval`（人机分离，§10.5）等：刻意不在桥面，脚本调即 `ERR_NOT_IMPLEMENTED`（诚实）。
- ~~`floatingWindow.create` 的参数面 / 缺 `close`~~、~~`screen.startCapturer` 的 `{width,height}` 不生效~~ —— **两处已于 2026-09-26 收口**（facade 发真 payload + 补 `close`；尺寸经 `openSession(w,h)` 透给生产者，回包尺寸仍 = 真实帧）。故示例（§12.3.1）现在**照写**；余下本节的其他条目仍是"写了就是撒谎"。

#### 12.3.4 本节与 §12.2 的分工

§12.2 是**命名空间清单**（有什么、接线到哪、谁注入），§12.3 是**调用形状**（怎么调、回什么、哪里会抛）。两者冲突时以本节为准（本节是实测），并应回来改 §12.2。§18 的**已拍板缺口**别在本节自行发明口径：第 8 项（截屏帧 ↔ images 帧的通路）**已于 2026-09-26 落地**（(b) 两缝共用帧表，见 §9.2）；第 9 项（`images.decode` 的相对路径口径）**已于 2026-09-25 拍板 (a)** —— 只收绝对路径，故本节示例**一律绝对**（`fromFile('/sdcard/part.png')`）。相对写法按 `:main` 的 CWD（= `/`）解析，`fromFile('part.png')` 回 `ERR_FILE_NOT_FOUND` 而报的路径是对的，看起来像"文件真的不在"，不像口径没定。

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
| ~~**Interrupt Handler**~~ | ~~QuickJS 沙箱 CPU 打断~~ **已裁**（§18 第 1 项）；CPU 风暴由 `:nodeN` 进程边界 + 看门狗吸收 | 风暴消融（改测 `:nodeN` 进程级） |

---

## 14. 需求优先级路线图

### P0 — 小而完整、可发布的最小闭环
**用户故事**：写一个无障碍脚本 → 在 App 内运行/停止/看 console → 被守护（看门狗杀僵尸不拖垮 UI）→ 能设一个每天定时任务。
- 构建链行：`:node` 进程宿主（单脚本）、Node 24 自建管线 + 16KB 门禁。
- 最小桥：TSF 双队列 + RPC + TTL + HandleRegistry、`console` 回传。
- a11y 基础：选择器/click/scroll/setText/文本事件；a11y 截图（333ms）。
  **Kotlin 侧已落地**：选择器/点击/滚动/文本/剪贴板/事件流/手势 + 截图（333ms 节流/会话），均 JVM 可测；`AppShell.assemble` 已留 `a11yHandler`/`screenHandler` 挂载缝（见 §12.2 接线现状表）。
- 执行：池（默认 1）状态机、四步 quiesce、心跳+Cpu+OOM 看门狗、崩溃重启(仅本轮 run)。
- 定时：单 alarm 定时任务 + 意图日志 + runNonce 幂等。
- 权限三态中心 UI + 引导页；specialUse FGS 骨架。
- 打包：模板 APK 改装（assets 注入、签名向导）——**整轨移入后续版本**（2026-09-23 决策：本版不做打包；下方已落地记录保留作既成事实）。
  **P0 领域+收集侧已落地**：`ApkIdentity`（包名/aapt2 关键字校验）+ `TemplateInfo`（引擎版本锚定）+ `TemplateApkPlans`（planDigest 组装/改写前复验，防清单错配；`offlineVariant` 参与摘要）+ `PackagerCollector.plan()`（规格+清单+身份一次产出计划），均 JVM 可测。
  **P0 AXML/ARSC 真改写已落地（纯 JVM，`:app-service:packager`）**：`IdentityTemplatePatch` 接 `PackagerPipeline.TemplatePatch` 缝——`AxmlPatcher` 改 manifest 的 package/versionName/versionCode/label，label 为 `@string` REF 时走 `ArscPatcher` 按资源 id 改全局池（REF 的 data 不变，AXML 无需重排；ARSC 缺资源则兜底降级为字面串），组件类名按**旧包**绝对化（`.X`/裸名 → 绝对名；dex 命名空间随模板编译定死，换 `package` 后相对名会按新包解析而类并不存在、装上即崩；本就绝对的与外部类不动，alias 的 `targetActivity` 同规则），`ApkRepacker` 重打包并剔除旧 v1 签名条目。字符串池**只追尾追加**（已有下标不动），未改动条目与未追加时的池字节逐字节保留，未知顶层块原样透传；夹具 APK（aapt2 产物）回读校验（另附组件+图标俱全的 `fixture-template-full.apk`）。换图标同趟条目级完成：`ApkPackager.iconPng` 换掉全密度 `ic_launcher(_round).png` 并剔除 `anydpi` 自适应 XML（API26+ 会拿自适应遮住 PNG），无密度 PNG/非 PNG 魔数/文件缺都在动模板前拒绝，`ApkRepacker.rewrite` 增删除集与 `entries()` 实况枚举。编排闭环已落地：`ApkPackager`（plan 两段式 → prepare → 身份改写 → `assets/project/` 批量注入（逐文件 sha256 对清单，collect 后被改即拒）→ `ZipAlignRunner` → `ApkSignerRunner`，**先对齐后签名**焊死，`apkSha256` 取对齐后字节），packager 模块内 200+ JVM 单测覆盖 argv/顺序/两道复验/失败口径；本机对真 `zipalign`+`apksigner`+debug keystore 手工跑通并 `zipalign -c`/`apksigner verify` 回读。**打包整轨已移入后续版本**（2026-09-23 决策：向导 UI 与 Keystore 取密随轨道走）；加密资产/loader 一并移出需求（与脚本加密同批收窄）。
  **P0 签名向导领域侧已落地**：`SigningKey`（Debug 临时/ECDSA 发布密钥库描述）+ `SignPlans`（请求组装绑定计划摘要，签名前复验）+ `ApkSignerArgs`（apksigner 参数表纯构造，口令只走 `env:NAME` 不进参数表），均 JVM 可测。
  **P0 apksigner 起进程已落地（纯 JVM 缝）**：`ApkSignerRunner` 注入 `ProcessLauncher`（对齐 `HostNodeExecutor` 惯例）——argv 与领域参数表逐字一致、口令只经 `AUTOSCRIPT_KS_PASS`/`AUTOSCRIPT_KEY_PASS` 环境变量、非 0 退出码与"报成功但没产出包"都如实失败。**参数形态经真 apksigner 验证**：必须是两项式 `--ks-pass env:NAME`（`--ks-pass:env` 连写会被拒 `Unsupported option`）。`ZipAlignRunner`（`zipalign -f -p 4 in out`，同样注入 `ProcessLauncher`）与编排顺序（先对齐后签名、`SignPlans` 摘要绑对齐后字节）已由 `ApkPackager` 焊死。仍留 Android/后续侧：Keystore 取密钥、签名向导 UI。
- 单测/archUnit CI；Docker 构建镜像。**已落地**：`.github/workflows/ci.yml`（JVM 单测 + archUnit）；本机已配 Android SDK（`/root/android-sdk`，2026-09-23）——CI 同款 `./gradlew …` 命令可本机直跑复现；`tools/jvm-test.sh [--android-jar]`（+ `jvm-test-all.sh`）仍是快速旁路，见 §6 末。
- npm P0（§10.11）：vendored npm CLI + 专用安装会话进程 + 零 spawn 主路径 + 事务化安装/journal 自愈 + 精选缓存种子离线首装 + 带外信任锚/lock 验签/审批卡 UI + 依赖面板 + 打包 node_modules 入包。

### P1 — 并发、图像、生态关键件（沙箱已裁，§18 第 1 项）
- 引擎池自适应（1-3）＋执行 slot FGS + 队列语义；`engines` 多引擎/`RuntimeChannel`。
- ~~QuickJS `:sandbox` 进程~~ **已裁（2026-09-26，§18 第 1 项「不要沙箱」）**：QuickJS 整条轨撤出排期，引擎只剩 Node 一条；`:engine:sandbox` 模块壳保留但不进排期。连带作废的还有 npm P1 里的「QuickJS 白名单库独立 vendored」与 §16 的两条相关风险——第三方脚本的防线改为**安装时用户选择 + §11 来源提示**（进程隔离那条不再存在）。
- `libopencv.so` 全图像管线的 P1 算子已全落（剩桥面消费方）；**找色已落地**（2026-09-25，§9.2：单色 + 逐分量容差 + 可选区域 + 首个命中，四层同改，`x=-1` 哨兵与“扫过 0 像素”两条口径），模板匹配 + `decode`/`release` 亦已随 §9.2 落地，**灰度、裁剪、缩放、旋转与特征已落计算核**（2026-09-25：`imgnative_gray` 产出新帧 + 28 例；`imgnative_crop` 尺寸会变的产出 + 复用区域判据 + 真拷贝 + 46 例；`imgnative_resize` 目标尺寸入参 + 固定 LINEAR + 配额 + 45 例；`imgnative_rotate` 逆时针角度 + expand 包络画布 + 帧中心 + 45 例；`imgnative_feature` ORB+ratio+几何一致性只回坐标 + 32 例 host 断言；五者桥面刻意未开——脚本侧没有消费方，P1 native 面收官）；MediaProjection 会话式截屏/录屏仍待（换 producer 即插）。
- `ui` 原生 XML UI 宿主 + `ui_web` WebView JS 桥 + 悬浮窗。
- datastore SQLite、settings、sensors、notification、app Intent、zip、power_manager（**已落地**，见 §8.7；clipboard 亦已落地 §12.2 第五条独立缝，sensors 亦已落地 §12.2 第六条独立缝，images 桥面与 native 实现均已落地 §12.2 第七条独立缝 —— `libopencv.so`（OpenCV 4.14 静态链接，`node-runtime-build/scripts/build-opencv.sh` + `.github/workflows/image-native.yml`）+ `NativeImageAnalyzer`/`JniOps`（`:platform:system`）+ `PlatformWiring.of` 三件套齐全，so 缺位时桥回 `ERR_NOT_IMPLEMENTED`）。
- OCR (MLKit 插件基准实现) + `OcrProvider`。
- 插件框架骨架 + 打包合并插件资产。
- npm P1（§10.11）：spawn 桥 polyfill + **lifecycle 脚本真实执行**（§18 第 7 项口径：不做出厂卡口、安装时让用户自己选，不是"批准后才跑"的审批流）+ npm 终端 + 在线/OSV 离线审计 + node-shim 红测。（原「QuickJS 白名单库独立 vendored」随第 1 项沙箱裁掉。）

### P2 — 生态与分发
- `dialogs` 全形态的 **Android 渲染侧**（overlay 真弹窗 / 通知回调的真投递；`mode` 选择与 BAL 降级判据已在 §9.6 的语义层落地）、`root_automator`/Shizuku 输入、`shell` 全量（`ShellMode.ROOT`/`ADB` 的真执行通道；`DEFAULT` 侧语义已落地）。
- 通知触发的 Intent 任务；cron `@宏`/`L`/`W` 扩展写法；alarm 生成日历视图。
- 分享（**文件形态**：导出项目包文件 → 对方经 §10 离线包导入通道落盘 + 验签后安装）、`axios`/第三方包预置。
- npm P2（§10.11）：离线 bundle 打包器 + 增量更新；native 依赖 **wasm 方案**（wasm 构建优先、纯 JS 替代兜底、安装期点名引导）。

### P3 — 前沿与实验
- worker_threads 实验性引擎（若手机端验证可行）——标记实验、默认关闭。
- Flutter/Compose 全重做 IDE 主题化；性能剖析面板。

**原则**：P0 的「小而完整」优先于「多而残缺」；每个 P 的退出标准都有可测验收（§16 预算联动）。

---

## 15. 性能与体积预算

| 指标 | 目标 |
|---|---|
| APK 体积 | ≤ 40MB release（`libnode.so` + `libopencv.so` + assets）—— **已超支，见下** |

> **APK 体积预算是本表唯一已被实测推翻的条目（2026-09-25 记账）**：`:engine:node-process` 侧 jniLibs 三件套
> `libnoden.so` + `libnode.so` + `libc++_shared.so` 实测未压缩合计已 ≈81MB（APK 压缩安装后另计）——**2026-09-26 ICU 之后要按 ≈92MB 读**：`libnode.so` 由 `--with-intl=none` 换成 `small-icu zh,en` 后实测 70,725,976 → 81,950,376 B（**+11,224,400 B = +10.70 MiB = +15.87%**），增量全在 `libnode.so`，故三件套 +10.70 MiB；取证 = 两个 `node-slice` artifact（`36153816811` / `36185853302`）+ 各自 `config.gypi`（旧 `icu_small=false`，新 `icu_small=true, icu_locales=en,root,zh, icu_path=deps/icu-small`）。§18 第 4 项的拍板（只要 zh,en）**已按本条买单**——不拍这条的话全量 ICU 还要再多，预算只会更超（下文 `libopencv` 两处「占三件套 8.6%/7.8%」的分母仍是 ICU 前的 81MB，按 ≈92MB 折算应为 7.6%/6.6%，分母换了、结论不变：图像面不是超支原因）；
> `libopencv.so` 是 OpenCV 4.14 `core+imgproc+imgcodecs+features2d+flann` 静态链接（kleidicv=ON；五模块 device 构建实测 **7,298,272 B = 7.0 MiB**（特征落地前 6,328,916 B = 6.0 MiB，增量不足 1MB、+15.3%），占引擎三件套 81MB 的 8.6%）——
> 仅按 `BUILD_LIST` 裁剪，**未压缩实测 6,328,916 B = 6.0 MiB**（占三件套 81MB 的 7.8%）——
> 早前"再添一个数量级相当的份额"是不成立的推断，实测不是同一量级。因此超支**全在引擎三件套**，
> 图像面不是 §15 超支的原因；据此 (c)「继续裁 OpenCV 面」的性价比极低（最多省 6MB，且已是最小可用集），
> 三条选项供 §18 决策：
> (a) 接受超支并在能力中心明示安装体积（最省事，代价是转化率）；
> (b) 按需分发 —— 引擎/图像两条 native 轨改走首次启动下载或 Play 动态交付（`libopencv.so` 无 exec 需求，
> 可整轨后移；`libnode.so` 有 exec 硬需求，动它要先解决 §19 的落位链）；
> (c) 继续裁 OpenCV 面（`imgcodecs` 只留 PNG/JPEG 已是最小可用集，再裁要动 SPI 承诺）。
> 记账而非静默删除：预算数字是 §15 的契约，推翻它得留证据链（`node-runtime-build/out*/SHASUMS256` + artifact 体积）。

| 冷启动→就绪 | ≤ 800ms（无系统抖动） |
| 脚本 warm start（二次复用 slot） | ≤ 300ms |
| a11y 空 RPC p95 | < 2ms |
| 截图→找图 | < 1s；模板匹配 1080p < 40ms; 找色 < 10ms（`findColor` 已随 P1 落地，真机红测待补） |
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
| **16KB 页 / ELF 对齐** | 未对齐 so 在新设备加载即崩 | **CI 门禁强制 `LOAD 0x4000` 对齐**（用 `llvm-objdump --private-headers` 断言）；红测机里常驻一台 16KB 页设备；`libopencv.so` 同轨还有一个**JNI 符号面**断言（五个 `JniOps_*` 逐个在场）—— 2026-09-25 补，理由是符号名是字符串约定、改包名/类名漏一处照样编得过，前三类断言一条都不红；**2026-09-26 修过一次真错位**：cc 用 `NativeImageAnalyzer_` 而声明类是顶层 `JniOps`，JVM 按声明类找 `JniOps_` 一个都找不到（无 RegisterNatives 兜底），本机 `jni-names.test.cjs` 先钉、CI 的符号面断言同批改对 |
| **引擎进程被杀/LMK** | 长任务中断 | 执行 slot 与 `:main` 绑定继承进程重要性 + specialUse FGS；看门狗对「被杀」能恢复意图日志重调度（幂等）；low-memory 降池 |
| **无障碍树洪峰（滚动/动画）** | IPC 爆炸 / UI 卡顿 | 节流拉取（seq 游标批量）+ 数据面可丢包 + 紧凑索引树按需属性 |
| **`process.exit` / CPU 风暴 / OOM 单脚本** | 曾拖垮整个 app | **进程边界**吸收全部；外带 CPU 差分 + 心跳双通道 + 堆 cap（沙箱 interrupt handler 随 §18 第 1 项裁掉） |
| **屏幕锁定时守时任务失败** | 闹钟响但任务是黑帧/无窗口 | 诚实契约：亮屏+解锁保底；预热闹钟 -60s；`screen` 三态声明；分类错误可 catch |
| **厂商 ROM（MIUI/HyperOS/Vivo）杀后台** | 自启/保活失效 | 能力中心三态 +「一键引导」直达 ROM 白名单页；无保证的功能如实降级标注 |
| **SCHEDULE_EXACT_ALARM 默认拒绝** | 定时不准 | 一级权限项 + 可降级 setWindow；坏 case 用户可见偏差标注 |
| **MediaProjection 会话授权中断** | 截屏功能随会话失效 | 会话状态机 + 可重授权引导；306s 超时前自动续期/提示 |
| ~~**QuickJS 沙箱缺口**~~ **作废（2026-09-26，§18 第 1 项）** | 恶意脚本逃逸 | **换成：无进程隔离后的逃逸面** —— 第三方脚本与自写脚本同权，防线只剩安装时用户选择 + 来源提示 + TTL/看门狗；永不默认静默提级 |
| ~~**双引擎 API 漂移**（Node vs QuickJS）~~ **作废（2026-09-26，§18 第 1 项）** | 同脚本两处行为不同 | **只剩 Node 一条轨**，不存在两处行为；`ImageAnalyzer`/引擎缝仍留 SPI 以便将来换实现 |
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

设计已给出默认推荐，但以下九点会实质影响方向，由你决策：

1. **引擎路线：先 Node-only，还是 P0 就并行 QuickJS 沙箱？**
   推荐「P0 只 Node；QuickJS 沙箱 P1」——沙箱牵扯独立进程、白名单、双引擎 API 对齐三件大事，混进 P0 会把最小闭环拖垮。
   **已拍板（2026-09-26）：不要沙箱**——QuickJS 整条轨撤出排期，**不只是推迟到 P1**：引擎只剩 Node 一条轨，`:engine:sandbox` 不进排期（`settings.gradle.kts` 里的模块壳保留，模块表按协调者冻结不动），§14 的「QuickJS `:sandbox` 进程」与 §16 的两条相关风险随之作废。
   **这条改的是安全边界，不是排期**：原先「第三方/市场脚本 → `:sandbox` 白名单子集」的隔离（§11 来源分级表）不再存在，第三方脚本与自写脚本**同在 Node 进程、同权**（无障碍/截屏/点击/网络全开）。防线因此只剩两条：**安装时的用户选择**（见第 7 项）与 §11 的来源提示——「靠能力授予而非进程隔离」要写在给用户的提示里，不能让人以为装来的脚本是沙箱跑的。
2. **进程模型：P0 就用「每脚本一进程」，还是先单引擎进程后扩？**
   推荐**一步到位**：反正脚本绝不能进主进程，单引擎进程的边界与多引擎池完全同构，代价只是「池容量先写死为 1」。避免二次重构。
   **已拍板（2026-09-26）：采纳推荐，一步到位**——现状即如此（`FixedEnginePool` 池容量 1，`:nodeN` 每脚本一进程），本项只是把"将来扩到 1-3"那条路确认成默认方向，无代码改动。
3. **分发定位与 Play 态度？**
   推荐完全避开 Play Store（specialUse FGS / SCHEDULE_EXACT_ALARM / MANAGE_EXTERNAL_STORAGE 政策冲突），官网/F-Droid/APK 直下。若你仍想上 Play，需砍掉 specialUse 保活与精确闹钟，P0 范围要变。
   **已拍板（2026-09-26）：不发行**——非商业化项目、不分发，故 Play 政策冲突面（specialUse FGS / 精确闹钟 / 全盘存储）**根本不存在**，上面那组"若上 Play 要砍什么"的代价不用付，保活与 `SCHEDULE_EXACT_ALARM` 原样保留。落地形态 = 本机自装 APK；Play/F-Droid/官网分发轨不进排期。
4. **ICU 取舍：全量 ICU（完整 Unicode/时区/国际化，体积 +20MB 级）还是配 `--with-intl=none`（体积小但字符串/时区残缺，自动化和 UI 场景产物不友好）？**
   推荐**全量 ICU + 裁剪为所需 subset**（也可放 assets 按需加载），自动化 app 大量依赖正则/时区/日期格式化。
   **已拍板（2026-09-26）：只要中文 + 英文**——即 locale 面收成 `{zh, en}`，既不停在 `none`（那样连 `zh-CN` 的 `Intl.*`/`toLocaleString` 都不可用，等于还是残缺），也不背全量的 +20MB。落点是 Node 构建旗标 **`--with-intl=small-icu --with-icu-locales=zh,en`**。**旗标已改（2026-09-26，同日）**：`node-runtime-build/scripts/fetch-and-build.sh` 由 `--with-intl=none` 换成上述两行；数据源是仓内 canned ICU（`deps/icu-small/` 带 `README-FULL-ICU.txt` → `configure.py` 走 `canned_is_full`），**不联网下载 icu4c**，`root` 由 configure 自动并入。
   跟进（构建轨，Actions 跑，本机不编）：旗标已改 → **重编已过（run `36185853302`，success，2026-09-25T23:01Z，约 2h34m）** → **体积差已量并回填 §15**：`libnode.so` 70,725,976 → **81,950,376 B（+11,224,400 B = +10.70 MiB = +15.87%）**，取证两个 `node-slice` artifact（基线 `36153816811` intl=none / 新 `36185853302`）+ `config.gypi`（`icu_small=true`、`icu_locales=en,root,zh`、`icu_path=deps/icu-small`、`icu_ver_major=78`）。旧估数 "~10MB+" 是 small-icu **默认面**，`zh,en` 实测比它还略高一点（ICU 数据不是按 locale 线性摊的）。`RISKS.md` §3 同批改成「已改 + 已量」。**仍待设备**：`Intl.DateTimeFormat`/`Collator` 在 zh/en 上的运行期实测（arm64 二进制本机跑不了，归 §8b 那批真机账）。副作用要写明：`toLocaleString('ja_JP')` 之类非 zh/en locale 会回落 en —— 脚本作者该知道这不是 bug。
5. **无障碍服务与脚本进程共享与否的极限形态**：本设计定案「a11y 在 `:main`、脚本在 `:nodeN`」。若未来遇到「无障碍回调海量 + 脚本高频读树」压垮 `:main`，可演进出
   `:accessibility` 第三进程（§9.1 的接口已留好接缝）。P0 不做——保持最少进程数。
   **已拍板（2026-09-26）：采纳推荐，P0 不做**（接口缝照留；真出现压垮证据再切）。
6. **UI 宿主策略**：脚本 UI 用「`:main` 渲染原生 View」还是「脚本自带 WebView（ui_web）」为主？
   推荐**两者都留、原生优先**（原生 View 桥链短、性能好；WebView 桥用于复杂富交互）。若优先做 Web 方案更快出 demo，可调整为 Web 优先。你在意的 demo 速度可以决定这个顺序。
   **已拍板（2026-09-26）：采纳推荐，两者都留、原生优先**——两个 UI 面都还在 P1 排期里（§14），本项只是定下先后：原生 XML UI 先，`ui_web` 桥后。
7. **npm 默认镜像与脚本审批严苛度**（§10 已定案技术路线，这两项是面向用户的策略）：
   - 默认 registry：推荐 `registry.npmmirror.com`（国内实测存活）——若你的目标用户全球分布则改 `npmjs.org` + 可切换。种子缓存与「离线秒装」文案都要绑定默认镜像。
   - 脚本审批默认值：推荐出厂 **global-deny**（全部 install 脚本默认拒绝，人工逐个批准）。代价是与 AutoJsPro 既有的「默认跑脚本」用户习惯不同，新旧用户需要文档/示例适配；若你更看重无缝迁移，可出厂 allow-listed 常用安全包 + 黑名单模式。
   **已拍板（2026-09-26）**：
   - **默认 registry = 官方 `registry.npmjs.org`**——不分发、不面向陌生用户（第 3 项），镜像加速不是开箱前提；用户要快自己 `setRegistry` 切 npmmirror。`HostNodeExecutor`（实际装包用的那家）与 `NpmRegistryVerifier`（交叉校验的首选）**两处缺省同批改**，§10.2/§10.4 的"默认 npmmirror"同批改。**第二意见的规则同时改写**：交叉校验要的是**两个运营主体**，不是"官方那一家"——首选官方时镜像做第二意见，首选任意别家时官方做第二意见（`secondary` 缺省跟着 `primary` 走），否则会出现 primary 与 secondary 同站、自己跟自己比也算通过。
   - **lifecycle 脚本不做出厂卡口，安装时让用户自己选**——既不是 global-deny 也不是白名单：装包时按包如实告知 `hasInstallScript`（**禁止静默**，§10.12 那条保留），跑不跑由这次安装的使用者当场决定；`requestApprove`/审批接口保留为这条选择的落点。
   - **实现落差（明写，不当已办）**：现网 T0 是 `--ignore-scripts` 全程 + npm12 `allowScripts=none`，lifecycle 脚本**一个都没跑过**，安装回执显式发 `scripts-skipped`（禁止静默那条就是为这个静默面立的）。"用户选择跑"要先有 spawn 桥（`child_process` 真执行），那是 §14 的 **P1 项**——**口径在此定死，实现排 P1**，P1 落地时按本条写交互，不重新拍。
8. **截屏帧与 images 帧的通路**（§9.2 记账的缺口，决定 §7.7 表里 `captureScreen → findImage < 1s` 这条链路什么时候能兑现）：
   **记账时的现状（2026-09-25）**：`screen.capture()` 出的帧与 `images.decode` 出的帧**互不通用**（两缝各发各的号，§12.2），且 screen 面既不给 `save()` 也不给 `pixel()`（字节出不了 `:main`），脚本**只能自己先落盘再 decode**（§12.3 示例当时这么写）。三条出路，入口在同一处（换 producer 或加一个 `screen.save`），代价不同：
   - (a) **`screen` 面加 `save(path)`**：把 a11y 已产出的 JPEG 字节原样落盘。设备面已经在压 JPEG 了，最小改动；代价是**有损**——`findColor` 的分量判定会吃到压缩伪影（§9.2 的契约是按分量精确夹的），"屏幕上这个色还在吗"这类判读会变钝。
   - (b) **两缝共用一个帧表**（producer 直接把帧写进 `images` 的帧表）：收益是真正的 0 拷贝直连（§7.4 所有权边界仍是每个句柄一份 Mat，变的是**发号那一侧**归谁）；代价是"帧不通用"这条纪律取消，`screen`/`images` 两个命名空间的释放语义要重新对齐（谁 release 谁背 STALE）——**已对齐为"两个释放入口、一张表"**（本项末）。
   - (c) **`images` 面加 `decodeBytes(byte[])`**：屏幕字节不落盘直进 native；代价是 bytes 要过桥，§7.7 的"屏幕帧→native 0 拷贝"这条在**两个维度上**都要重新记账，且 §7.4 的多一路径 = 多一处规格要守。
   **已拍板 (b)**（2026-09-25）：只有它同时保住了"0 拷贝"与"按分量精确判定"两条被契约明确承诺的性质，(a) 切掉的是判读精度、(c) 切掉的是性能口径。(a) 不作为过渡 —— 过渡方案一旦进示例就会被抄成正式用法，而带 JPEG 往返的链路不叫「屏幕帧→native 0 拷贝」，§7.7 的买单口径不为它改。
   **已落地**（2026-09-26，§7.4 发号侧归一 + §9.2 末落地段）：`ImageAnalyzer.ingest`（第 6 方法，紧密 RGBA → 同一张帧表）、`ImagesNamespaceHandler` 退成无状态转接、`ScreenshotSource` 可选 `analyzer`（null 即本地表，且那时 `images` 未注册故不撞号）、`frameOf` JPEG → 原样 RGBA（`getPixels(int[])` 打包序，不猜 `copyPixelsToBuffer` 字节序）。钉子 = `host_ingest_test`（跨来源同表）+ JVM 三处 + JS 互认用例。**`captureScreen → findImage` 的实测数字仍欠**（§7.7 表里是验收口径，真机未量）。

9. **`images.decode` 的相对路径口径**（2026-09-25 实测记账，影响 §9.2/§12.3 的示例写法）：
   `:domain` 的 `ImageAnalyzer.decode` KDoc 写着「路径解析（相对项目根 or filesDir）由实现定」，但**四层里没有任何一层解析路径**（计算核 `std::fopen`/`cv::imread` 直取、装载面与 `NativeImageAnalyzer` 原样透传、handler 只挡空白串）。host 侧实测把这条钉死了：传相对路径时按**进程 CWD** 解析——同一个文件，绝对写法与「chdir 到该目录 + 相对写法」都回 `ERR_IO(3)`（说明相对写法确实命中到了文件），而不存在的相对路径回 `ERR_FILE_NOT_FOUND(2)`。`libopencv.so` 载在 `:main` 进程里，那个进程的 CWD 是 `/`（Android 对 zygote 后代的固定行为），于是脚本写 `images.decode('part.png')` 会在根目录找一个并不存在的文件——**回的是 `ERR_FILE_NOT_FOUND`，且报的路径是对的**，所以看起来像"文件真的不在"，不像"口径没定"。
   两条出路，代价不同：
   - (a) **就在契约里写明"路径必须是绝对的"**（示例改成 `/sdcard/...` 或让脚本自己拼 `filesDir`）。零实现改动，代价是 v9 的 `fromFile('part.png')` 这种相对用法在 AutoScript 直接不成立，脚本要改写法。
   - (b) **在 handler 层加一层基准解析**（相对路径按项目根 / `filesDir` 拼绝对再往下传）。保住 v9 的写法，代价是要定"基准是谁"（项目根？脚本所在目录？filesDir？）——**三选一本身又是一个要拍板的策略**，且 §9.2 的「不做路径策略」那条边界要重画。
   **已拍板 (a)**（2026-09-25）：路径必须是绝对的 —— 把"相对路径"从契约里去掉而不是猜一个基准。(b) 不给：基准三选一本身又是一个策略，且 §9.2「不做路径策略」的边界不重画。**§12.3 已按 (a) 改写**：示例路径一律绝对（`fromFile('/sdcard/part.png')`），并写明了相对写法为什么回 `ERR_FILE_NOT_FOUND`。**跟进动作已执行**（同日 `a0a802e`）：`:domain` `ImageAnalyzer.decode` 的「路径解析由实现定」收紧为"路径**必须是绝对的**"，facade `images.ts` 注释同步。

---

## 19. 结语

AutoScript 的骨架可以一句话记住：

> **三个进程、一个异步桥、每脚本一个 Node 进程。**

架构的全部取舍都锚定在五条铁律上：脚本不进主进程、跨进程必异步、每次操作有 TTL、teardown 四步 quiesce、依赖单向接缝可替换。这个骨架让「写脚本→跑起来→守护它→定时它→打包走」的 P0 闭环与 AutoJsPro 对整个 API 面的演进式补齐，是同一条路的两个阶段，而不是两个项目。

下一步（建议与后续迭代方向，需你确认后开工）：
1. **§18 九项已全部拍板**（第 8/9 项 2026-09-25；第 1-7 项 2026-09-26）：1 不要沙箱 / 2 一步到位每脚本一进程 / 3 不发行 / 4 ICU 只要 zh+en / 5 P0 不切第三进程 / 6 原生优先 / 7 官方 registry + 安装时让用户选脚本。**没有待你拍板的开放项了**（§18 保留作决策台账）；
2. ~~Node 垂直切片~~ **已跑通**：Node 24 → 16KB 对齐 `libnode.so` → `:node` 进程执行并回传，构建走 Actions（`.github/workflows/node-slice.yml`，本地禁编）；P0 回环 `P0LoopbackTest`（装 axios → 读 UI 树 → 点节点 → 看 console）已绿；
3. ~~npm 切片~~ **已跑通**：vendored npm CLI + 专用安装会话 + 零 spawn 主路径 + 种子离线首装已在生产装配里（§10.11 P0 主体 + §19 中段的 wire 形状修复）；
4. **下一步**（按可执行性排序）：(a) 等设备的那笔账——§7.7 实测数字 + exec/dlopen / findColor 红测；(b) ICU **已闭环**：旗标改 + Actions 重编 success + 体积差已回填 §15（`libnode.so` +10.70 MiB），仅剩 zh/en 的 `Intl.*` 运行期实测归真机那批；(c) ~~§14 P0 剩余项按 §12.2 接线现状表逐条核~~ **已核完（2026-09-26），并据此收口第三处落差**：核过的 P0 条目（构建链、桥、a11y、看门狗 CPU/OOM、调度器、权限门禁、CI 门）实为已落；被裁的如实标裁（打包整轨 2026-09-23 后移、§18 第 10 项 npm 呈现层暂不排期、lifecycle 脚本 = P1）。核出的真落差是 **npm 事件面双侧无投递方** —— `onProgress`/`onWarning`/`onApproval` 订阅了但生产永远不响（两个 SharedFlow 零订阅、`feedWarning` 零生产调用者、`InstallFailure` 连订阅口都没有），「订阅了却收不到」正是 §10.5-3/`feedWarning` 自己点名要禁的最恶失败面。接法沿用仓库既有的游标拉取（桥没有宿主→脚本推送面，§7.5）：`:domain` 补 `drainEvents`/`drainApprovals` + 四个 DTO（§10.7），`InstallCoordinator` 两条 512 环由 `emit()`/`requestApprove` 唯一投递，`NpmBridgeHandler` 上桥 `events`/`approvals`，JS 侧 `pumpInstallEvents`/`pumpApprovals` 轮询泵 + 新增 `onFinished`，`warning` 一律过 `feedWarning` 校验 kind。钉子：`NpmEventDrainTest` 12 例（环语义/回包三件套/wire 逐字映射/参数校验）+ `npm-events.test.cjs` 11 例（首订立拉、游标不重复、瞬时错不推进游标、NOT_IMPLEMENTED 响亮、未知 type/phase/action 响亮、定时器自停）。**第 10 项（脚本库/编辑器页、npm 呈现层）2026-09-26 拍板暂不排期**。

**本仓库的推进顺序（已落地的按 §12.2 接线现状表为准，勿按上表臆造）**：契约与纯 JVM 层（`:domain` / `:bridge:java` / 各 app-service / `:platform:capabilities` 的 handler）已逐块落地并有单测；`AppShellApplication` 已从 11 行桩变成**闹钟/门禁的装配入口**（`AlarmSchedulerProvider` + `AndroidAlarmPort` + `AndroidScreenGate` + 静态注册的 `AlarmReceiver` → `AlarmDispatch` → `Scheduler.onTrigger`，漏投记账不静默丢弃），`AppShell.assemble` 的**生产调用方已落地**：`AppShellKit.assemble(filesDir, cacheDir, schedulerProvider, screenGate)`（`:app` 装配包，纯 JVM 可测）是那条路径的单一落点 —— 目录约定（`files/.autojs` 两个持久寄存器 + `files/scripts` 项目根 + `cacheDir/npm-cache`）与持久句柄的成对释放都收在它里面，`AppShellApplication.onCreate` 在 IO 域调它（`installWithFiles`），装配失败如实降级成"壳保持 null + 闹钟继续漏投记账"而不是半装冒充就绪；引擎工厂**生产已换 `NodeProcessEngine`**（`AppShellApplication.installWithFiles` 注入，`nativeLibraryDir/libnoden.so`+`libnode.so` 候选位；socket 名 = 桥监听 `BridgeSocketListener` **绑定成功才注入**（失败离线降级），`addonPath = ScriptPaths.bridgeAddonFile(filesDir)`（§19 交付轨 2026-09-24 接线：`assets/bridge-addon/` → `BridgeAddonDeploy` 落位，文件缺位即降级不注入 —— 与 bridgeDistPath 同一条选填纪律；jniLibs 三件套 `libnoden.so`/`libnode.so`/`libc++_shared.so` 由 `prepareEngineNativeLibs` 三件齐才落包、半套红，`extractNativeLibs=true` 保证 exec 有真文件）；缺件由 execute 预检**点名绝对路径**——比笼统"未接入"更可操作）；`AppShellKit` 缺省仍是 `UnavailableEngine`（`:app-service:runtime`，见其 KDoc）——**JVM 配方/测试不经 Application 装配时每次执行如实 `CRASHED` + 真原因进意图日志**，而不是开机后什么都不发生。开机恢复的接线点（`AppShell.bootRecover` → `Scheduler.recoverUncommitted`，`AppShellApplication.install` 在 IO 域触发；持久形态 `JournalFileStore` + `PersistentIntentLog` 已有 `AppShellProductionWiringTest` 覆盖），npm 侧已有生产装配（`NpmShellKit.assembleHandler(filesDir, cacheDir)` → `assemble(npmHandler = …)`，`NpmShellKitTest` + 同一接线测试覆盖）；归档侧意图日志与运行档案双持久（`JournalFileStore` + `FileRunArchive`，同一 `JsonLine` 行格式，`FileRunArchiveTest` 与 `InMemoryRunArchiveTest` 同语义锚点），`AssembledShell` 同时是任务中心的**读口**（`taskCenter()` = `scheduler.tasks()` + `archive.unfinished()`/`link()` + 恢复账经参数给入；`runsOf`/`runRecord`/`unfinishedRuns` 保留为窄读口，避免 UI 自开第二个 `FileRunArchive` 造成写侧两份视图）兼**操作面**（`registerTask`/`cancelTask`/`runTaskNow` 直通壳持有的同一个 `Scheduler` —— store-first 先落盘后动内存/闹钟，绝不另开第二个 `FileTaskStore`）；**任务中心全链已接上（2026-09-24）**：`AppShellApplication.taskCenter()`（壳未装配即抛，不冒充空清单）→ `:domain` 的 `TaskCenter.kt` 呈现 DTO → `:ui` 的 `TaskCenterScreen`（三页签之二：任务行 + 未结算执行 + 恢复账；2026-09-24 再接**操作面** —— 登记/取消/立即执行三写口 + `TaskCenterOps` 语义闸门 + `runTaskNow` 先查后触发的不哑火边界，见 §8.6）；**控制台全链也已接上（2026-09-24）**：`AppShellApplication.console()`（壳未装配即抛，不冒充「暂无日志」）→ `:domain` 的 `Console.kt` 呈现 DTO → `:app` 的 `ConsoleRead` + `AssembledShell.consoleView`（读壳持有的收集器与在途表，不另开第二份）→ `:ui` 的 `ConsoleScreen`（页签之三：行累积 + 丢包/拉满/在途两端对照，见 §7.3 末）；`AppShellKitTest` 覆盖自装配全路径（目录落位、门禁拒绝不投递、启动失败不写孤儿档案、真起引擎落终态记录、落盘遗留经 `bootRecover` 重投）。a11y 的 Android 真实现注入**已接**（`PlatformWiring` → `a11yHandler`：`AndroidUiTree`/`AndroidGestureInput` 经 `SystemA11yBridge`，服务未连如实 `ERR_SERVICE_DISABLED`），screen 的生产注入**同批已接**（`PlatformWiring.screenHandler`，§9.2 a11y 截图路径）；dialogs 的生产注入**也已接**（`PlatformWiring.of` 构造 `AndroidDialogHost`，AUTO 选路/强制降级拒绝/通知回调回投 + TTL 双清）；仍待的是 MediaProjection 高清会话（授权 UI + FGS，换 producer 即插）；脚本内容侧装配期补部署已接上（`ScriptDeployRecovery` 在 `AppShellKit.assemble` 时跑一次：只补缺不覆盖、空清单如实为空、失败不投毒，`deployReport`/`deployFailures()` 随壳暴露给能力中心）；§8.4 已闭环（判据/采样/`EngineWatchdog` 调度/`HeartbeatLedger` 心跳打点；pid 归属表仍归在途账不另建），Kotlin spawn 半边已送 pid 与心跳、桥监听 `BridgeSocketListener` 已接、addon JS 消费面 `attachNative` 已接（见 §8.4 末），设备面只剩真机联调（facade dist 随包 + 打包入口 attach 接线与 jniLibs 三件套/addon 落位 2026-09-24 均已落 —— assets 构建拷贝 → `BridgeDistDeploy` 落位 `filesDir/node_modules/auto` → env 注入 → kBootstrap `attachNative`，全链有 `BridgeDistPackagingEntryTest`；二进制侧 `prepareEngineNativeLibs` → `lib/arm64-v8a/{libnoden,libnode,libc++_shared}.so` + addon 走 assets → `BridgeAddonDeploy` → `addonPath`，APK 条目已实测）一道；§8.3 的 drift 已有裁决方（`EngineWatchdog` drift 连段 + `KillCause.DRIFT`：连续 3 轮对不上杀掉重来）；§8.6 已闭环（dispatcher 排队默认上限按触发源分级 + `PendingRun` deadline 记账与过期不重投；注册表持久 `TaskStore`/`FileTaskStore`（`tasks.jsonl`，upsert+tombstone，与意图日志同一 `.autojs` 目录、同一追加纪律）：`schedule`/`cancel` 先落盘后动内存/闹钟，`bootRecover` 先 `restoreTasks` 续排再重投意向，`AppShellKit` 建第三持久并随壳释放），**Android 触发侧也已接上**（预拉/Exact/降级记账 + 静态接收器回投 + 屏幕门禁生产实现） + 开机续排（`RECEIVE_BOOT_COMPLETED` + 静态 `BootReceiver`：重启清掉全部闹钟，没有它持久注册表再完整也没人续排；receiver 无判断只记日志，续排/重投走 `Application.onCreate` 正常装配路径，避免与 `install` 的恢复并发撞车）。**§8.7 保活与电源（`:main` 侧）也已接上**：`AutoScriptForegroundService`（specialUse FGS，`PROPERTY_SPECIAL_USE_FGS_SUBTYPE="automation"`，清单静态声明、`exported=false`）+ `ForegroundKeeper`（start/stop/renew + 15 分钟守护 ticker）+ `WakeLockLedger`（token 引用计数 + 超时自动释放，**取锁失败不记账**）+ `AndroidWakeLockOps`（真 `PARTIAL_WAKE_LOCK`，`setReferenceCounted(false)`）；**屏幕门禁的持锁判定就此收口**——`AppShellApplication.screenGateOf` 传 `WakeLockLedger::isHeld`，§8.7 原「恒真 = 明写的待接」作废；保活事实经 `ShellSummary.keepAliveActive`（`:domain`，无默认值）透到 `:ui` 首屏（「保活已生效」/「保活未生效：熄屏的亮屏任务会被拒绝」，不藏二级页）。服务经进程级邮箱 `ForegroundHost` 现取 Keeper（**服务不自装配**，避 service → 根包成环）、`START_NOT_STICKY`（续期统一走 `Application.onCreate` 装配路径，与 `BootReceiver` 同纪律）；`onTerminate()` 真机上从不被调用，只为测试收口 + 给「谁来停」一个落点。引擎侧 `power_manager` **已落地（2026-09-24）**：`PowerManagerNamespaceHandler` 直驱 `foregroundKeeper()` 的同一本账（`hold(token, timeoutMillis)` 插口当年就是照这个形状留的，账本零改）+ `powerManagerHandler` 独立缝 + `auto.power` 双侧契约（见 §8.7 与 §12.2 接线表）。**§9.5 能力中心的全链也已接上（2026-09-23）**：`AndroidCapabilityProbes`(6 事实) → `AndroidSystemStateReader`(判据唯一出处) + `AndroidGrantLauncher`(去向唯一出处) → `AppShellApplication.permissionCenter()` → **读口** `HostSummary.capabilityCenter()`/`openCapabilitySettings()`（`:domain`，`CapabilityCenterSnapshot`/`CapabilityRow`，`canRequestGrant` 是 `CapabilityLifecycle` 的投影）→ 拼装 `CapabilityCenterRead.snapshot`（`:app` 壳装配包，纯 JVM 可测：全量枚举 + 逐项现问三态 + 同一份 `guideText` + 降级任务账）→ `:ui` 的 `CapabilityScreen`（纯状态 DTO，JVM 可测）：三态各自的中文说法、引导文案原样透传、降级任务单列一段（§8.6「可能偏差」）、**没读到 ≠ 一个能力都没有**（`NOT_LOADED` 与 `failed` 分开且保留原异常文案）；刷新走「回前台/切页签」重问一次（授完权回来看到的是刚问过的结论，不是离开时的缓存；读失败不自激重读）。§9.4/§9.6 的五个系统命名空间（`dialogs`/`shell`/`device`/`app`/`floatingWindow`）已落地到**语义层**：`:domain` 的 `SystemContracts.kt`（`ShellExecutor`/`DeviceInfoProvider`/`AppLauncher`/`DialogHost`/`FloatingWindowHost` + DTO）、`:platform:capabilities` 的 `SystemNamespaces.kt`（五个 handler）、`AppShell.assemble` 的 `systemHandlers` 束 + `AppShellKit.assemble` 的透传（五个字段各自可空，未注入即如实 `ERR_NOT_IMPLEMENTED`）与 `bridge/js` 的 `extras.test.cjs` 双侧契约测试，三者串成一条线且都有单测；SPI 的 Android 实现**已落四件**（`:platform:system` 的 `AndroidShellExecutor`/`AndroidDeviceInfoProvider`/`AndroidAppLauncher`/`AndroidFloatingWindowHost`，入口 `SystemSpis.of(context)`，27 契约测试并进了 CI 测试任务表），`dialogs` 的 `DialogHost` 亦已落地（`AndroidDialogHost` 编排 + `…capabilities.device` 设备面，构造在 `PlatformWiring.of`，按 domain KDoc 住 :platform:capabilities）；**那次把 `SystemSpis` + `CapabilityNamespaces` 拼进 `AppShellKit.assemble` 的生产调用已落地**（`com.autoscript.shell.PlatformWiring`：`of(context)` = `SystemSpis.of` → `inject` → `systemHandlers` + `datastore`/`zip`/`settings`/`notification`/`clipboard`/`sensors`/`images` 七独立缝，`AppShellApplication.installWithFiles` 调用；拓扑靠 §6 **包级例外二**放行——仅 shell 装配包可依赖 `:platform:capabilities`/`:platform:system`，`ArchitectureTest`「平台实现只许装配包碰」+ `ModuleGraphTest` 允许集量化执行）。**`a11y` 的生产调用已接**（无障碍服务本体 `AutoScriptAccessibilityService` + `PlatformWiring` 注入，服务未连桥如实 `ERR_SERVICE_DISABLED`）；**`screen` 也已接**（§9.2 a11y 截图路径，与 a11y 同底），MediaProjection 高清会话是后续升级（换 producer 即插），不再是接线缺口。**`images` 桥面与 native 真实现均已接，且 P1 第一个算子 `findColor` 已落地**（单色+逐分量容差+可选区域+回第一个命中，四层同改；宿主机语义门禁 109 例附上（见 §9.2 末），真机红测待补）—— §12.2 第七条独立缝：`:domain` `ImageAnalyzer` + `ImagesNamespaceHandler` + `images.ts` 双侧契约齐全；native 侧 `:bridge:image` 的 `libopencv.so`（OpenCV 4.14 静态链接）+ `:platform:system` 的 `NativeImageAnalyzer`/`JniOps` 也齐了，`PlatformWiring.of` 构造（so 缺位 → null → 桥回 `ERR_NOT_IMPLEMENTED`，看不见像素的内存分析器只能假装匹配成功，那比没有更坏 —— 这条防线保留）。**`auto.npm` 的 wire 形状漂移已修**（与 `a11y.waitFor` 同一类事故：JS facade 读一个宿主从不发的键，两侧各自的测试都没抓到，因为 JS mock 自己回的那个形状）：`install` 曾被 JS 声明成 `Promise<InstallResult>{name,version,integrity,linkedBins}`，而宿主回的是字面量 `true`——现宿主回 `:domain` 的 `InstallHandle`（`{handleId,projectId,enqueuedAtMillis}`），facade 改成 `InstallQueued`，并在两侧注释里钉死「门面此刻还不知道会装出什么版本，回猜的版本号就是伪造」（§10.8/§12.3 文档里 `install → {name,version,integrity}` 的示例同批改掉：`InstallResult`/`ResolvedPkg` 两个 DTO 至今没有任何实现方产出）；`audit` 的键名 `vulnerabilities` → `vulns`（§10.8 与 `AuditReport.vulns` 都读它）；`list` 不再发恒 0 的 `sizeBytes`（lockfile 量不到尺寸，尺寸的两条真来源是 `offlineGap` 与 `storage`）；`offlineGap` 补上 JS 漏声明的 `version`；`requestApprove` 新增 `scripts` 校验 + 回显（与 `setRegistry` 的 scope 同一条纪律：宿主不认的字段被静默丢弃比报错更糟）；`ApprovalRequest` 的 JS 侧形状改与 `:domain` 逐字段对齐（`scripts` 是入参不是宿主字段）；`InstallEvent.phase` 从 `unpack/link/failed` 改到 `:domain` 六个阶段（`queued/resolve/download/reify/post-check/done`，失败由 `InstallFailure` 表达）。钉子：Kotlin +4 / JS `npm-contract.test.cjs` +9，反证过任一侧单独漂移立刻红。native/NDK 侧已出空壳：`:bridge:native` addon 控制面（`invoke`/`setSocketFd`/`setup`/`droppedData` + 读线程 + TSF 接线）与 `:engine:node-process` 宿主 `main.cpp`（§7.8 启动序）均已落地，经本机 NDK r28c 交叉编译验证（`engine/node-process/scripts/build-native.sh`：AArch64 ELF、`node::Start` 三方符号对表、LOAD≥16KB）；`:bridge:image` 也已落地 C++ 面（`imgnative.cpp` 计算核 + `images_jni.cc` 装载面），OpenCV 构建轨在 Actions（`image-native.yml`），本机不编译。**Kotlin spawn 执行链已落并本机验证**（`NodeProcessEngine` 16 单测 + `:app` 垂直切片 E2E：spawn → unix 桥 → console/心跳 → `SUCCEEDED` 归档；main.cpp abstract 连接 + `SO_PEERCRED` uid 门禁 + kBootstrap 自动心跳；addon invoke payload 字符串化金样；生产桥监听 `BridgeSocketListener`：abstract 绑定 + uid 门禁 + `NewlineFrameServer` serve，JVM 假缝单测 6 例；facade addon 消费面 `attachNative()`：setup(onFrame) 按 id 结算 + invoke 注入 + `errFromThrown` 保留真码，mock 6 例 + env 门禁真 addon 全环），仍待真机：设备侧 exec/dlopen 红测（16KB 页机 + targetSdk 提取策略；jniLibs 三件套与 addon 落位、facade dist 随包与打包入口 attach 接线均已落，见第 2 条切片路线）。
