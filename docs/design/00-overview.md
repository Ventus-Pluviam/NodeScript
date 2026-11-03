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
   - 推论 B：最不可信的代码最隔离 → 若引入沙箱，它必须在**自己的进程**，而不是塞进主进程的一个线程。**拍板不做沙箱（§18 第 1 项），本推论暂无落地对象**；铁律「脚本不进主进程」不受影响 —— 现有的隔离单元就是 `:nodeN`。

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

