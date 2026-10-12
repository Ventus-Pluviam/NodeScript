# 实现注记（自各分卷外迁，逐字保留）

> **本文件是 [`design-status.md`](design-status.md) 的「实现注记」分片**（2026-10-02 分片，
> backlog C6）。各分卷正文原先内嵌的「已落地 / 实测」叙事整段搬来此处，正文侧只留结论 +
> 指回本节的链接。搬迁**逐字**（不做压缩），行内判据与实测数字一字未改；只删掉了搬运处的空行。
>
> **2026-10-06 又搬来两段**（同一口径：契约卷留结论、叙事下沉）：
> [`18-19-ledger.md`](design/18-19-ledger.md) 的 §19 结语（12 KB 的「下一步 / 已跑通」）
> 与 [`07-bridge.md`](design/07-bridge.md) §7.7 的第三～六次实测（11 KB 的四轮调参记录）。
> 两处的原文都**逐字**保留在下面各自的节里。
>
> **分卷正文里的锚不用改**：`09-capabilities.md` / `08-execution.md` 等卷写的
> `design-status.md#实现注记自各分卷外迁逐字保留` 在 [`design-status.md`](design-status.md)
> 留了一节同名占位指向本文件 —— § 锚与条目文本都没变，只是内容换了文件。
>
> 回指：[`design-status.md`](design-status.md)（当前状态与流水目录）·
> [`docs/log/README.md`](log/README.md)（按日期的流水切片）。

---

## 各分卷实现注记的搬迁状态

口径：分卷正文只留**契约**（是什么 / 口径 / 纪律），实现状态与实测数字搬进本文件
（见下节）。搬迁一律**逐字**，不做压缩 —— 行内判据与实测数字是台账的正文，不是摘要。

### 已迁（2026-10-01）

| 源 | 规模 | 内容 |
|---|---|---|
| §9.2 截图与图像管线 | 23.6 KB | 截图链路实现、八个算子逐条记账、真机实测与四修链 |
| §8.6 调度系统 | 8.4 KB | 排队分级、deadline、停止收口、急停、任务中心读口/操作面 |
| §8.4 看门狗 | 3.5 KB | 三路纯判定、`ProcessMonitor` 采样、调度循环、心跳账本 |
| §8.7 保活与电源 | 2.8 KB | FGS + 唤醒锁账本 + 前台账本 + 屏幕门禁收口 |
| §8.3 生命周期状态机 | 1.6 KB | P0 收敛子集、drift 裁决、P0 不存在的态 |
| §8.5 崩溃恢复 | 0.9 KB | `JournalFileStore`/`FileRunArchive` 持久形态与孤儿结算 |
| §9.5 权限与能力中心 | 2.0 KB | `AndroidPermissionGates` 生产接线、能力中心读口 |
| §9.6 数据与存储 | 2.3 KB | 五个系统命名空间落地清单、`ScriptPaths`/部署恢复 |

正文侧留一句结论 + 指回下节锚点的链接。

### 未迁（本节即待办清单）

按 `grep -c '已落地|已接线|已通桥面|已接生产|已全部落地|实现已接|已落'` **重新测量**
（下表数字为 2026-10-01 实测，行号一律不写 —— 旧表的行号在折行与外迁后已全部失效）：

**2026-10-06 重测**（口径同上；`18-19-ledger.md` 与 `07-bridge.md` 的两大段已按本节
开头的说明搬走，所以这两行的数字掉下来了）：

| 文件 | 命中行 | 备注 |
|---|---|---|
| `13-roadmap-budget.md` | 15 | 该卷主题本就是**进度/预算**，「已落地」是其正文而非错位 —— 不迁 |
| `07-bridge.md` | 11 | §7.7 判据行与最新一轮实测（旧四轮已迁入本文件） |
| `09-capabilities.md` | 6 | §9.1/§9.4/§9.6 散句，与所在契约条目同段 |
| `08-execution.md` | 4 | §8.1/§8.5 残句 |
| `10-npm.md` | 4 | §10.5 审批机制与裁决处置 |
| `12-js-api.md` | 3 | §12.2 接线散文（表格第四列另有专项口径） |
| `06-modules.md` | 1 | 模块表一行 |
| `18-19-ledger.md` | 1 | 只剩 §18 台账里的一处（§19 已迁） |

**判断**：这 ~14 KB 与所在节的主题绑定（进度卷、沿革卷、实测记账卷），逐行抠出会
把契约散文切碎，收益低于风险，故**本轮不动**。真要迁应先给这三卷定性（它们是不是
「契约卷」），那是文档架构决策，不是清理。

`§12.2` 表格第四列（挂载状态）是另一个问题：它是**状态**却住在契约卷里。
换成一列「见台账」需要同时改 `bridge/js/test/wiring-table.test.cjs` 的读表口径
（该门读 `12-js-api.md` 表头→首个空行、要求 ≥15 行），故留待有意改门时一并做。

## 实现注记（自各分卷外迁，逐字保留）

各分卷正文原先内嵌的「已落地 / 实测」叙事整段搬来此处，正文侧只留结论 + 指回本节的链接。搬迁**逐字**（不做压缩），行内判据与实测数字一字未改；只删掉了搬运处的空行。

### §19 结语（自 `18-19-ledger.md` 外迁，逐字保留）

> **原文 2026-10-06 整段搬来**（该卷 §19 现在只留结论一句 + 指回本节的链接）。
> 搬的是「下一步 / 已跑通 / 还剩什么」那批落地叙事 —— 契约卷不该拿 12 KB 记进度。

AutoScript 的骨架可以一句话记住：

> **三个进程、一个异步桥、每脚本一个 Node 进程。**

架构的全部取舍都锚定在五条铁律上：脚本不进主进程、跨进程必异步、每次操作有 TTL、teardown 四步 quiesce、依赖单向接缝可替换。这个骨架让「写脚本→跑起来→守护它→定时它→打包走」的 P0 闭环与 AutoJsPro 对整个 API 面的演进式补齐，是同一条路的两个阶段，而不是两个项目。

下一步（建议与后续迭代方向，需你确认后开工）：
1. **§18 九项已全部拍板**（第 8/9 项 2026-09-25；第 1-7 项 2026-09-26）：1 不要沙箱 / 2 一步到位每脚本一进程 / 3 不发行 / 4 ICU 只要 zh+en / 5 P0 不切第三进程 / 6 原生优先 / 7 官方 registry + 安装时让用户选脚本。**没有待你拍板的开放项了**（§18 保留作决策台账）；
2. ~~Node 垂直切片~~ **已跑通**：Node 24 → 16KB 对齐 `libnode.so` → `:node` 进程执行并回传，构建走 Actions（`.github/workflows/node-slice.yml`，本地禁编）；P0 回环 `P0LoopbackTest`（装 axios → 读 UI 树 → 点节点 → 看 console）已绿；
3. ~~npm 切片~~ **已跑通**：vendored npm CLI + 专用安装会话 + 零 spawn 主路径 + 种子离线首装已在生产装配里（§10.11 P0 主体 + §19 中段的 wire 形状修复）；
4. **下一步**（按可执行性排序）：(a) 等设备的那笔账——§7.7 实测数字 + exec/dlopen / findColor 红测；(b) ICU **已闭环**：旗标改 + Actions 重编 success + 体积差已回填 §15（`libnode.so` +10.70 MiB），
  仅剩 zh/en 的 `Intl.*` 运行期实测归真机那批；(c) ~~§14 P0 剩余项按 §12.2 接线现状表逐条核~~ **已核完（2026-09-26），并据此收口第三处落差**：核过的 P0 条目（构建链、
  桥、a11y、看门狗 CPU/OOM、调度器、权限门禁、CI 门）实为已落；被裁的如实标裁（打包整轨 2026-09-23 后移、§18 第 10 项 npm 呈现层暂不排期、lifecycle 脚本 = P1）。
  核出的真落差是 **npm 事件面双侧无投递方** —— `onProgress`/`onWarning`/`onApproval` 订阅了但生产永远不响（两个 SharedFlow 零订阅、`feedWarning` 零生产调用者、
  `InstallFailure` 连订阅口都没有），「订阅了却收不到」正是 §10.5-3/`feedWarning` 自己点名要禁的最恶失败面。接法沿用仓库既有的游标拉取（桥没有宿主→脚本推送面，
  §7.5）：`:domain` 补 `drainEvents`/`drainApprovals` + 四个 DTO（§10.7），`InstallCoordinator` 两条 512 环由 `emit()`/`requestApprove` 唯一投递，`NpmBridgeHandler` 上桥 `events`/`approvals`，
  JS 侧 `pumpInstallEvents`/`pumpApprovals` 轮询泵 + 新增 `onFinished`，`warning` 一律过 `feedWarning` 校验 kind。钉子：`NpmEventDrainTest` 12 例（环语义/回包三件套/wire 逐字映射/
  参数校验）+ `npm-events.test.cjs` 11 例（首订立拉、游标不重复、瞬时错不推进游标、NOT_IMPLEMENTED 响亮、未知 type/phase/action 响亮、定时器自停）。**第 10 项（脚本库/
  编辑器页、npm 呈现层）2026-09-26 拍板暂不排期**。

**本仓库的推进顺序（已落地的按 §12.2 接线现状表为准，勿按上表臆造）**：契约与纯 JVM 层（`:domain` / `:bridge:java` / 各 app-service / `:platform:{capabilities,system}` 的 handler —
— 后者为 2026-09-30 步骤 6 归位后的口径）已逐块落地并有单测；`AppShellApplication` 已从 11 行桩变成**闹钟/门禁的装配入口**（`AlarmSchedulerProvider` + `AndroidAlarmPort` + `AndroidScreenGate` + 静态注册的 `AlarmReceiver` → `AlarmDispatch` → `Scheduler.onTrigger`，
漏投记账不静默丢弃），`AppShell.assemble` 的**生产调用方已落地**：`AppShellKit.assemble(filesDir, cacheDir, schedulerProvider, screenGate)`（`:app` 装配包，纯 JVM 可测）是那条路径的单一落点 —
— 目录约定（`files/.ns` 两个持久寄存器 + `files/scripts` 项目根 + `cacheDir/npm-cache`）与持久句柄的成对释放都收在它里面，`AppShellApplication.onCreate` 在 IO 域调它（`installWithFiles`），
装配失败如实降级成"壳保持 null + 闹钟继续漏投记账"而不是半装冒充就绪；引擎工厂**生产已换 `NodeProcessEngine`**（`AppShellApplication.installWithFiles` 注入，`nativeLibraryDir/libnoden.so`+`libnode.so` 候选位；
socket 名 = 桥监听 `BridgeSocketListener` **绑定成功才注入**（失败离线降级），`addonPath = ScriptPaths.bridgeAddonFile(filesDir)`（§19 交付轨 2026-09-24 接线：`assets/bridge-addon/` → `BridgeAddonDeploy` 落位，
文件缺位即降级不注入 —— 与 bridgeDistPath 同一条选填纪律；jniLibs 三件套 `libnoden.so`/`libnode.so`/`libc++_shared.so` 由 `prepareEngineNativeLibs` 三件齐才落包、半套红，
`extractNativeLibs=true` 保证 exec 有真文件）；缺件由 execute 预检**点名绝对路径**——比笼统"未接入"更可操作）；`AppShellKit` 缺省仍是 `UnavailableEngine`（`:app-service:runtime`，
见其 KDoc）——**JVM 配方/测试不经 Application 装配时每次执行如实 `CRASHED` + 真原因进意图日志**，而不是开机后什么都不发生。开机恢复的接线点（`AppShell.bootRecover` → `Scheduler.recoverUncommitted`，
`AppShellApplication.install` 在 IO 域触发；持久形态 `JournalFileStore` + `PersistentIntentLog` 已有 `AppShellProductionWiringTest` 覆盖），npm 侧已有生产装配（`NpmShellKit.assembleHandler(filesDir, cacheDir)` → `assemble(npmHandler = …)`，
`NpmShellKitTest` + 同一接线测试覆盖）；归档侧意图日志与运行档案双持久（`JournalFileStore` + `FileRunArchive`，同一 `JsonLine` 行格式，`FileRunArchiveTest` 与 `InMemoryRunArchiveTest` 同语义锚点），
`AssembledShell` 同时是任务中心的**读口**（`taskCenter()` = `scheduler.tasks()` + `archive.unfinished()`/`link()` + 恢复账经参数给入；`runsOf`/`runRecord`/`unfinishedRuns` 保留为窄读口，
避免 UI 自开第二个 `FileRunArchive` 造成写侧两份视图）兼**操作面**（`registerTask`/`cancelTask`/`runTaskNow` 直通壳持有的同一个 `Scheduler` —— store-first 先落盘后动内存/
闹钟，绝不另开第二个 `FileTaskStore`）；**任务中心全链已接上（2026-09-24）**：`AppShellApplication.taskCenter()`（壳未装配即抛，不冒充空清单）→ `:domain` 的 `TaskCenter.kt` 呈现 DTO → `:ui` 的 `TaskCenterScreen`（三页签之二：
任务行 + 未结算执行 + 恢复账；2026-09-24 再接**操作面** —— 登记/取消/立即执行三写口 + `TaskCenterOps` 语义闸门 + `runTaskNow` 先查后触发的不哑火边界，见 §8.6）；
**控制台全链也已接上（2026-09-24）**：`AppShellApplication.console()`（壳未装配即抛，不冒充「暂无日志」）→ `:domain` 的 `Console.kt` 呈现 DTO → `:app` 的 `ConsoleRead` + `AssembledShell.consoleView`（读壳持有的收集器与在途表，
不另开第二份）→ `:ui` 的 `ConsoleScreen`（页签之三：行累积 + 丢包/拉满/在途两端对照，见 §7.3 末）；`AppShellKitTest` 覆盖自装配全路径（目录落位、门禁拒绝不投递、
启动失败不写孤儿档案、真起引擎落终态记录、落盘遗留经 `bootRecover` 重投）。a11y 的 Android 真实现注入**已接**（`PlatformWiring` → `a11yHandler`：`AndroidUiTree`/
`AndroidGestureInput` 经 `SystemA11yBridge`，服务未连如实 `ERR_SERVICE_DISABLED`），screen 的生产注入**同批已接**（`PlatformWiring.screenHandler`，§9.2 a11y 截图路径）；dialogs 的生产注入**也已接**（`PlatformWiring.of` 构造 `AndroidDialogHost`，
AUTO 选路/强制降级拒绝/通知回调回投 + TTL 双清）；~~仍待的是 MediaProjection 高清会话（授权 UI + FGS，换 producer 即插）~~ **已接（2026-10-08，批 75）**（`ProjectionForegroundService` + `AndroidScreenConsentBroker`，换 producer 即插）；脚本内容侧装配期补部署已接上（`ScriptDeployRecovery` 在 `AppShellKit.assemble` 时跑一次：
只补缺不覆盖、空清单如实为空、失败不投毒，`deployReport`/`deployFailures()` 随壳暴露给能力中心）；§8.4 已闭环（判据/采样/`EngineWatchdog` 调度/`HeartbeatLedger` 心跳打点；
pid 归属表仍归在途账不另建），Kotlin spawn 半边已送 pid 与心跳、桥监听 `BridgeSocketListener` 已接、addon JS 消费面 `attachNative` 已接（见 §8.4 末），设备面只剩真机联调（facade dist 随包 + 打包入口 attach 接线与 jniLibs 三件套/
addon 落位 2026-09-24 均已落 —— assets 构建拷贝 → `BridgeDistDeploy` 落位 `filesDir/node_modules/auto` → env 注入 → kBootstrap `attachNative`，全链有 `BridgeDistPackagingEntryTest`；
二进制侧 `prepareEngineNativeLibs` → `lib/arm64-v8a/{libnoden,libnode,libc++_shared}.so` + addon 走 assets → `BridgeAddonDeploy` → `addonPath`，APK 条目已实测）一道；§8.3 的 drift 已有裁决方（`EngineWatchdog` drift 连段 + `KillCause.DRIFT`：
连续 3 轮对不上杀掉重来）；§8.6 已闭环（dispatcher 排队默认上限按触发源分级 + `PendingRun` deadline 记账与过期不重投；**无人 await 的 run 自带期限（2026-10-01）**：
`TimeoutEnforcer{WATCHDOG}` + 看门狗期限线（`KillCause.TIMEOUT`）+ `engines.exec` 的 `timeoutMillis` 必填；注册表持久 `TaskStore`/`FileTaskStore`（`tasks.jsonl`，upsert+tombstone，
与意图日志同一 `.ns` 目录、同一追加纪律）：`schedule`/`cancel` 先落盘后动内存/闹钟，`bootRecover` 先 `restoreTasks` 续排再重投意向，`AppShellKit` 建第三持久并随壳释放），
**Android 触发侧也已接上**（预拉/Exact/降级记账 + 静态接收器回投 + 屏幕门禁生产实现） + 开机续排（`RECEIVE_BOOT_COMPLETED` + 静态 `BootReceiver`：重启清掉全部闹钟，
没有它持久注册表再完整也没人续排；receiver 无判断只记日志，续排/重投走 `Application.onCreate` 正常装配路径，避免与 `install` 的恢复并发撞车）。**§8.7 保活与电源（`:main` 侧）也已接上**：
`AutoScriptForegroundService`（specialUse FGS，`PROPERTY_SPECIAL_USE_FGS_SUBTYPE="automation"`，清单静态声明、`exported=false`）+ `ForegroundKeeper`（start/stop/renew + 15 分钟守护 ticker）+ `WakeLockLedger`（token 引用计数 + 超时自动释放，
**取锁失败不记账**）+ `AndroidWakeLockOps`（真 `PARTIAL_WAKE_LOCK`，`setReferenceCounted(false)`）；**屏幕门禁的持锁判定就此收口**——`AppShellApplication.screenGateOf` 传 `ForegroundKeeper::lockHeld`（= 账本 `isHeld`，
步骤 6d 起根包经读口不碰 platform 类型），§8.7 原「恒真 = 明写的待接」作废；保活事实经 `ShellSummary.keepAliveActive`（`:domain`，无默认值）透到 `:ui` 首屏（「保活已生效」/
「保活未生效：熄屏的亮屏任务会被拒绝」，不藏二级页）。服务经进程级邮箱 `ForegroundHost` 现取 Keeper（**服务不自装配**，避 service → 根包成环）、`START_NOT_STICKY`（续期统一走 `Application.onCreate` 装配路径，
与 `BootReceiver` 同纪律）；`onTerminate()` 真机上从不被调用，只为测试收口 + 给「谁来停」一个落点。引擎侧 `power_manager` **已落地（2026-09-24）**：`PowerManagerNamespaceHandler` 直驱 `foregroundKeeper()` 的同一本账（`hold(token, timeoutMillis)` 插口当年就是照这个形状留的，
账本零改）+ `powerManagerHandler` 独立缝 + `auto.power` 双侧契约（见 §8.7 与 §12.2 接线表）。**§9.5 能力中心的全链也已接上（2026-09-23）**：`AndroidCapabilityProbes`(6 事实) → `AndroidSystemStateReader`(判据唯一出处) + `AndroidGrantLauncher`(去向唯一出处) → `AppShellApplication.permissionCenter()` → **读口** `HostSummary.capabilityCenter()`/
`openCapabilitySettings()`（`:domain`，`CapabilityCenterSnapshot`/`CapabilityRow`，`canRequestGrant` 是 `CapabilityLifecycle` 的投影）→ 拼装 `CapabilityCenterRead.snapshot`（`:app` 壳装配包，
纯 JVM 可测：全量枚举 + 逐项现问三态 + 同一份 `guideText` + 降级任务账）→ `:ui` 的 `CapabilityScreen`（纯状态 DTO，JVM 可测）：三态各自的中文说法、引导文案原样透传、
降级任务单列一段（§8.6「可能偏差」）、**没读到 ≠ 一个能力都没有**（`NOT_LOADED` 与 `failed` 分开且保留原异常文案）；刷新走「回前台/切页签」重问一次（授完权回来看到的是刚问过的结论，
不是离开时的缓存；读失败不自激重读）。§9.4/§9.6 的五个系统命名空间（`dialogs`/`shell`/`device`/`app`/`floatingWindow`）已落地到**语义层**（2026-09-30 步骤 6 重排：
契约 `:platform:system`（2026-10-01 D3 起按命名空间拆子包：`shell/ShellContracts.kt`、`device/DeviceContracts.kt`、`app/AppContracts.kt`、`floatingWindow/FloatingWindowContracts.kt`；此前是同名的 `SystemHostContracts.kt` 一份四面）—— `DialogHost` 六型留 `:domain`；handler `:platform:system` 十一件各住自己子包（`Shell`/`Device`/`App`/`FloatingWindow` 原为 `SystemNamespaces.kt` 的「四内」，D3 拆出）+ `:platform:capabilities` `DialogsNamespaceHandler` 一件；
原「`:domain` SystemContracts + capabilities SystemNamespaces」口径见 design-decisions）、`AppShell.assemble` 的 `systemHandlers` 束 + `AppShellKit.assemble` 的透传（五个字段各自可空，
未注入即如实 `ERR_NOT_IMPLEMENTED`）与 `bridge/js` 的 `extras.test.cjs` 双侧契约测试，三者串成一条线且都有单测；SPI 的 Android 实现**已落四件**（`:platform:system` 的 `AndroidShellExecutor`/
`AndroidDeviceInfoProvider`/`AndroidAppLauncher`/`AndroidFloatingWindowHost`，入口 `SystemSpis.of(context)`，27 契约测试并进了 CI 测试任务表），`dialogs` 的 `DialogHost` 亦已落地（`AndroidDialogHost` 编排 + `…capabilities.device` 设备面，
构造在 `PlatformWiring.of`，按 domain KDoc 住 :platform:capabilities）；**那次把 `SystemSpis` + `CapabilityNamespaces` 拼进 `AppShellKit.assemble` 的生产调用已落地**（`com.autoscript.shell.PlatformWiring`：
`of(context)` = `SystemSpis.of` → `inject` → `systemHandlers` + `datastore`/`zip`/`settings`/`notification`/`clipboard`/`sensors`/`images` 七独立缝，`AppShellApplication.installWithFiles` 调用；
拓扑靠 §6 **包级例外二**放行——仅 shell 装配包可依赖 `:platform:capabilities`/`:platform:system`，`ArchitectureTest`「平台实现只许装配包碰」+ `ModuleGraphTest` 允许集量化执行）。
**`a11y` 的生产调用已接**（无障碍服务本体 `AutoScriptAccessibilityService` + `PlatformWiring` 注入，服务未连桥如实 `ERR_SERVICE_DISABLED`）；**`screen` 也已接**（§9.2 a11y 截图路径，
与 a11y 同底），~~MediaProjection 高清会话是后续升级（换 producer 即插），不再是接线缺口~~ **该升级已于 2026-10-08（批 75）落地**（`MediaProjectionSource` 经同一条缝接入；~~**录屏仍缺**~~ **录屏亦已于 2026-10-08（批 77）落地**，`MediaProjectionRecorder` 与截屏腿并列）。**`images` 桥面与 native 真实现均已接，P1 桥消费方五算子也已于 2026-09-29 全开**（`toGrayscale`/
`crop`/`resize`/`rotate` 产新帧 + `findFeature` 回模板中心；宿主机语义门禁 **422 例**附上（2026-10-01 复核；该日 D7 拆 TU 前后逐例同值，见 §9.2 末），真机红测待补）—— §12.2 第七条独立缝：`:domain` `ImageAnalyzer` + `ImagesNamespaceHandler` + `images.ts` 双侧契约齐全；
native 侧 `:bridge:image` 的 `libopencv.so`（OpenCV 4.14 静态链接）+ `:platform:system` 的 `NativeImageAnalyzer`/`JniOps` 也齐了，`PlatformWiring.of` 构造（so 缺位 → null → 桥回 `ERR_NOT_IMPLEMENTED`，
看不见像素的内存分析器只能假装匹配成功，那比没有更坏 —— 这条防线保留）。**`auto.npm` 的 wire 形状漂移已修**（与 `a11y.waitFor` 同一类事故：JS facade 读一个宿主从不发的键，
两侧各自的测试都没抓到，因为 JS mock 自己回的那个形状）：`install` 曾被 JS 声明成 `Promise<InstallResult>{name,version,integrity,linkedBins}`，而宿主回的是字面量 `true`—
—现宿主回 `:domain` 的 `InstallHandle`（`{handleId,projectId,enqueuedAtMillis}`），facade 改成 `InstallQueued`，并在两侧注释里钉死「门面此刻还不知道会装出什么版本，
回猜的版本号就是伪造」（§10.8/§12.3 文档里 `install → {name,version,integrity}` 的示例同批改掉：`InstallResult`/`ResolvedPkg` 两个 DTO 至今没有任何实现方产出）；
`audit` 的键名 `vulnerabilities` → `vulns`（§10.8 与 `AuditReport.vulns` 都读它）；`list` 不再发恒 0 的 `sizeBytes`（lockfile 量不到尺寸，尺寸的两条真来源是 `offlineGap` 与 `storage`）；
`offlineGap` 补上 JS 漏声明的 `version`；`requestApprove` 新增 `scripts` 校验 + 回显（与 `setRegistry` 的 scope 同一条纪律：宿主不认的字段被静默丢弃比报错更糟）；
`ApprovalRequest` 的 JS 侧形状改与 `:domain` 逐字段对齐（`scripts` 是入参不是宿主字段）；`InstallEvent.phase` 从 `unpack/link/failed` 改到 `:domain` 六个阶段（`queued/resolve/download/reify/post-check/done`，
失败由 `InstallFailure` 表达）。钉子：Kotlin +4 / JS `npm-contract.test.cjs` +9，反证过任一侧单独漂移立刻红。native/NDK 侧已出空壳：`:bridge:native` addon 控制面（`invoke`/
`setSocketFd`/`setup`/`droppedData` + 读线程 + TSF 接线）与 `:engine:node-process` 宿主 `main.cpp`（§7.8 启动序）均已落地，经本机 NDK r28c 交叉编译验证（`engine/node-process/scripts/build-native.sh`：
AArch64 ELF、`node::Start` 三方符号对表、LOAD≥16KB）；`:bridge:image` 也已落地 C++ 面（`imgnative.cpp` 计算核 + `images_jni.cc` 装载面），OpenCV 构建轨在 Actions（`image-native.yml`），
本机不编译。**Kotlin spawn 执行链已落并本机验证**（`NodeProcessEngine` 16 单测 + `:app` 垂直切片 E2E：spawn → unix 桥 → console/心跳 → `SUCCEEDED` 归档；main.cpp abstract 连接 + `SO_PEERCRED` uid 门禁 + kBootstrap 自动心跳；
addon invoke payload 字符串化金样；生产桥监听 `BridgeSocketListener`：abstract 绑定 + uid 门禁 + `NewlineFrameServer` serve，JVM 假缝单测 6 例；facade addon 消费面 `attachNative()`：
setup(onFrame) 按 id 结算 + invoke 注入 + `errFromThrown` 保留真码，mock 6 例 + env 门禁真 addon 全环），仍待真机：设备侧 exec/dlopen 红测（targetSdk 提取策略；16KB 页机一面 **2026-10-06 已裁定不做真机复验**，见 `design-decisions.md` 第 34 项；
jniLibs 三件套与 addon 落位、facade dist 随包与打包入口 attach 接线均已落，见第 2 条切片路线）。

### §7.7 实测记账：第三～六次（自 `07-bridge.md` 外迁，逐字保留）

> **原文 2026-10-06 整段搬来**（§7.7 现在只留判据表 + 第七次实测块 + 指回本节的链接）。
> 搬的是四轮调优的逐次记录（第三次评审 patch 验证 / 第四次相位探针门 / 第五次 FastPath
> 与场景端缓存 / 第六次大模板形态与 findFeature 四修）—— 契约卷不该拿 19 KB 记调参过程。

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

### §9.2 截图与图像管线（自 `09-capabilities.md` 外迁）

- **已落地（Kotlin 侧）**：`ScreenshotSource`（333ms 节流 / generation=1 单帧句柄 / 会话 open-close；`recycle` 已升为 `:domain` `FrameSource` SPI 方法）+ `ScreenNamespaceHandler`（构造只收 `FrameSource` SPI，
  `capture/recycle/startCapturer/nextFrame/closeSession`）。**Android 真实现已接（§9.2 a11y 截图路径）**：`AndroidFrameProducer` 经 `A11yBridge.{screenSnapshot,takeScreenshot}`（`AutoScriptAccessibilityService` 设备面实现—
  —`ScreenshotResult` HardwareBuffer→软位图→**紧密 RGBA 像素**（2026-09-26 起不再压 JPEG —— 那段压缩是死重且有损），**实际尺寸随帧走**（`ProducedFrame`，曾经固定 1080×2400 回包是对 JS 报假尺寸，
  已除）；配置 `canTakeScreenshot=true` 进 res/xml（AOSP 明示缺它两法都不可用）；失败码分类映射 SECURE→`ERR_BLACK_FRAME`、系统限频→`ERR_INVALID_PARAM`、通道失效/
  无效窗口→`ERR_SERVICE_DISABLED`、内部错→`ERR_IO`；API34+ `takeScreenshotOfWindow`、API30–33 `takeScreenshot`、API<30 如实 `ERR_NOT_IMPLEMENTED`）。生产装配 `PlatformWiring.screenHandler = CapabilityNamespaces.screen(ScreenshotSource(AndroidFrameProducer(), analyzer = images))`（**同一个** analyzer 也喂给 `images` 缝，
  §18-8(b) 一张表；analyzer 为 null 即退回本地帧表） → `AppShellApplication.installWithFiles`，与 a11y 同底（`SystemA11yBridge`，服务未连 = `ERR_SERVICE_DISABLED`）；锁屏/无窗口由 `ScreenPolicy` 预检分类，
  安全窗由回调码兜底（无障碍读不到窗口 FLAG_SECURE，`secureForeground` 预检位恒 false —— 不伪造预检能力，分类结果殊途同归）。~~**仍缺**：MediaProjection 高清会话（授权 UI + FGS + ImageReader→libopencv.so）——换 producer 即插，语义面不动；P0 会话由同一 a11y 帧源连续截图承接。~~ **已落地（2026-10-08，批 75）**：授权 UI + `mediaProjection` 型 FGS + ImageReader→共享 `ImageAnalyzer.ingest` 都已接；~~**录屏仍缺**~~ **录屏亦已落地（2026-10-08，批 77）**。

- **图像分析面（`images`，§12.2 第七条独立缝）已通桥面、已通 native 实现（2026-09-25）**：`:domain` `ImageAnalyzer` SPI（**十方法**：`decode`/`matchTemplate`/`findImage`/
  `findColor`/`release`/`toGrayscale`/`crop`/`resize`/`rotate`/`findFeature` —— 后五个 2026-09-29 随 P1 桥消费方开通；`ImageFrame{HandleRef,width,height}` / `ImageMatch{x,y,width,height,confidence}` /
  `ColorHit{x,y,r,g,b,a}` / `FeatureHit{x,y,confidence}`）+ `:platform:capabilities` `ImagesNamespaceHandler`（**无状态转接**：帧表/发号/在场性全在 SPI，2026-09-26 起不再自管第二张表 —
  — §18 第 8 项 (b) 发号侧归一）+ JS facade `images.ts`。**P0 刻意不提供内存分析器**：看不见像素的替身只能靠自报坐标假装匹配成功，那比没有更坏 —— so 缺位时 `imagesHandler` 为 null，
  桥对 `images.*` 如实 `ERR_NOT_IMPLEMENTED`（2026-09-25 起 native 已接，见下）。**`findColor` 已落地（2026-09-25，P1 第一个算子）**：单色 + 逐分量容差 + 可选区域 + 回第一个命中，
  四层同改（`imgnative_color` → `colorNative` → `NativeImageAnalyzer.findColor` → `images.findColor`）。两条口径在该层钉死：**未命中是答案**（`x = -1` 哨兵回裸 `null` —
  — (0,0) 是合法首像素，拿 0 当“没有”会把左上角的命中静悄悄吃掉）；**“扫过 0 像素”是参数错**（`ERR_INVALID_PARAM`，那不是“没有”而是“根本没找”）。
  P1 native 面的八个算子已全落（**灰度、裁剪、缩放、旋转与特征**：计算核 `imgnative_gray`/`imgnative_crop`/`imgnative_resize`/`imgnative_rotate`/`imgnative_feature` 已落（28 + 46 + 45 + 45 + 51 例 host 断言），
  **桥面同批开通 2026-09-29** —— 见本节末各算子的「桥面已开」追记与 §12.3.3）。**宿主机语义门禁**：计算核零 JNI（见 imgnative.cpp 文件头），于是 host 侧用**同 commit** 的 OpenCV 4.14.0 静态库直链它、
  以 x86_64 跑像素断言（`bridge/image/test/cpp/`：`run-host-tests.sh` 一条命令装+编+跑）。这道门存在的理由很实：`imgnative_color` 里有三处判读是「编得过但译反了照样出结论」（**已覆盖**，
  `host_color_test` 36 例）——Vec4b 回读的通道序（px[2]→r/px[1]→g/px[0]→b，译反只是回包 r/b 互换）、ROI 内坐标+roi 左上角=全帧坐标、以及「扫过了、没有」与「扫过 0 像素」的区分；
  JVM 522 例与 JS 128 例全绿时它照样能错，而违约金是 hook 不到。**2026-09-25 已实证**：NDK `-fsyntax-only`、JVM、JS 三门全绿之际，这道门抓出 `cv::imread(IMREAD_COLOR)` 把任何来源压成 3 通道 BGR、
  `at<Vec4b>` 静默读进下一行首字节 —— alpha 分量从来没参与过判定（ASan 也不报）。修法=IMREAD_UNCHANGED + decode 归一成 4 通道 + `imgnative_color` 内通道/深度兜底守卫，
  `host_decode_norm_test.cpp` 21 例钉死。 **`imgnative_match` 的判读也补上了 host 断言**（`host_match_test.cpp`，2026-09-25 引入时 24 例，随后续各轮一路加到当前 **124 例**）：
  命中坐标 = 模板左上角（matchTemplate 的 maxloc 是结果面左上角，照搬即模板位置，**不**再叠加 ROI 偏移）、w/h = 模板尺寸、**未匹配是答案不是异常**（out_match=0 且 x/
  y/w/h/confidence 全 0，status 仍是 0 —— 与 findColor 的 `x = -1` 同一条纪律；若把 `w == 0` 当"未命中"判，一张 0 宽模板就能让脚本把命中读成未命中）、阈值域 `maxv == threshold` 判命中（"≥ 阈值即命中"是契约原话）、
  模板比画面大 → ERR_IO(3)。补它的理由与 decode 归一同源：**模板匹配此前只被 JVM mock 覆盖，mock 不碰像素、永远回一个编好的 ImageMatch**，于是"坐标译反了""未命中时把 x/
  y/w/h 也回出去"在 JVM/JS 两门全绿时照样能溜过去。**灰度也落进计算核了**（`imgnative_gray`，2026-09-25，`host_gray_test.cpp` 28 例）：它是 decode 之外**第一个产出新帧的算子**，
  所以这道门额外钉了"产出新帧"的边界 ——产出帧号 > 原帧号且不顶掉原帧、原帧不被就地改灰、两帧各自独立 release、放掉产出帧后原帧照常可用（这四条 JVM/
  JS 两门**结构上看不见**：mock 只回一个自报的 ref）。产出帧仍是 4 通道 BGRA（帧表不变式，为此提了具名谓词 `frame_is_normalized`，`imgnative_color` 里原先手写的那处判据一并换过去），
  **alpha 原样带过去不抹 255**（灰度压掉的是色彩信息，透明与否不是色彩；decode 用 IMREAD_UNCHANGED 保住 A 就是让 a 分量参与判定，在灰度这步抹平等于把那次事故引回来）。
  灰度权重用库的 `COLOR_BGRA2GRAY` （0.299R+0.587G+0.114B），不自己写系数（抄一份就多一个漂移面）；host 断言钉纯红 76 / 纯绿 150 / 纯蓝 29，并正面否认平均法（找 85 必须未命中）。
  **桥面已开**（2026-09-29，`images.toGrayscale`）：当初「脚本侧没有消费方就不开桥面」的判据，在 P1 图像桥消费方这一批里被消费方（预处理 + 取亮度面）兑现了 —
  — 于是 `:domain ImageAnalyzer` 与 handler 同批认它。原判据本身不撤销（没有消费方的算子仍不开桥面），只是这条的消费方到了。**裁剪也落进计算核了**（`imgnative_crop`，
  2026-09-25，`host_crop_test.cpp` 46 例）：它是第一个**尺寸会变**的产出算子，比灰度多三处判断，每一处都在 host 上钉住。（1）**区域判据复用 `resolve_region`**（找色那同一个函数，
  不是抄一份）—— 于是"越界"在两处是同一个码、同一个边界口径（`rx + rw == cols` 贴边合法，越界一律 `ERR_INVALID_PARAM`）。（2）**`region == nullptr` 在本算子是拒收**，
  不按 `resolve_region` 的缺省解释成"整帧"：裁剪的语义就是"取一个子区域"，缺区域时唯一自洽的解释是"整帧拷贝"—— 想要整帧副本就明写整帧区域（那也有断言）。
  （3）**产出必须是拷贝（`clone()`），不能是 `(*f)(roi)` 视图**：帧表是**所有权表**不是视图表，源帧一 release，脚本手里的"子图"就悬垂。第（3）条值得单独记一笔，
  因为**它的判据不是直觉能给的**：`(*f)(roi)` 是浅视图，而 OpenCV 的 ROI **会把父缓冲的引用计数带住**（实测 `src.u->refcount` 1→2），所以"放掉源帧后视图会读到垃圾"**不成立** —
  — 缓冲根本没被释放，像素照常读出正确值（host 侧实测：放掉源帧后再读产出帧，视图实现同样正确，64 轮同尺寸重分配也没能把它分出来）。别名唯一可观测的后果是**内存归属**：
  视图共享同一个 `UMatData`，引用计数不为零就释放不掉，于是一张 1×1 的产出帧能把源帧整块缓冲钉在常驻内存里（4000×4000×4 实测：拷贝实现放源帧后 RSS 回落到基线，
  视图实现留 ~61MB）。这条判据因此写成了 **RSS 断言**（`host_crop_test.cpp` 第 8 段，大源帧裁 1×1 后放源帧、阈值 16MB 取在两种实现中间；§7.7 本来就没有为 crop 承诺 0 拷贝，
  而"0.3MB 的产出帧钉住 64MB 常驻"正是它在脚本侧的代价）。顺带钉住：产出帧号 > 源帧号且不顶掉源帧、宽高 = region 的 w/h（不是源帧尺寸）、区域坐标是**源帧坐标系**（子图 (0,0) 逐分量 == 源帧 (rx,ry)，
  非零起点也钉了）、产出帧仍是 4 通道 BGRA 且 **alpha 原样带过去**（裁出的单像素 a=210 不抹 255）、产出帧可以**再裁**、句柄已死 → `STALE`、出参指针为 null → `ERR_INVALID_PARAM`，
  且**拒收一律早退不写出参**（与 match/gray 同口径）。**裁剪的桥面已开**（2026-09-29，`images.crop`，region 必填四元组）—— 当初那条「没有消费方（找色能在 `region` 上限定范围、
  读路径不产出帧；只有要把子图当独立一帧反复用/当模板时才需要 crop）」已被 P1 桥消费方兑现；region 缺省**不开**口（想要整帧副本就明写整帧区域）。
  **缩放也落进计算核了**（`imgnative_resize`，2026-09-25，`host_resize_test.cpp` 45 例）：它是第一个**像素值要重算**的产出算子（crop 是搬像素、逐点相等；resize 按插值重算，
  逐点不等是正常的）。入参是**目标尺寸**（`dst_w` × `dst_h`）不是倍数（倍数是调用方算的浮点；与 gray/crop 的"宽高随帧回"同一条纪律：尺寸真值只有产出帧的地方知道，
  让下游自己推是猜）。插值**固定 `INTER_LINEAR`，不做入参**：NEAREST 放大是块状马赛克（UI 细线条/文字边缘丢信息），CUBIC/LANCZOS 更贵且在截图/PNG 这类非照片输入上无可证增益 —
  — 多一个入参就多一个"选错静默换答案"的漂移面。门禁把 LINEAR 的可观测行为钉了三条：纯色帧任意缩放值不变（杀通道丢失/alpha 被抹）、2×2 四角帧放 4×4 四角守恒（杀几何对错/
  行列互换）、中心 (1,1) r=101 混合值**专杀 NEAREST**（NEAREST 给 100；已用故意 NEAREST 实现验过变红 —— 但诚实起见：CUBIC/LANCZOS 在此处同样给混合值，这条钉的是"不是 NEAREST"而非"只能是 LINEAR"）。
  另有 4×4 象限→2×2 的均值行为、同尺寸合法拷贝（不早退，调用方不用先判要不要调）、非对称尺寸（宽高各自独立）、**16384 单边配额**（16384²×4≈1GB，
  再往上是笔误把字节数当宽高；配额拒收与尺寸不合法同码，注释里分开写）。产出 4 通道 BGRA（resize 逐通道，归一进归一出，显式再断言一次防将来换后端静默掉通道），
  拒收一律早退，**桥面已开**（2026-09-29，`images.resize`，入参是目标尺寸、域 (0,16384] 整数）—— 当初那条「只有同一套模板跑多分辨率设备时才需要」已被 P1 桥消费方兑现。
  **旋转也落进计算核了**（`imgnative_rotate`，2026-09-25，`host_rotate_test.cpp` 45 例）：它是第一个**画布尺寸要算**的产出算子（resize 的尺寸是入参直给，rotate 的画布是包络公式 bw=round(|w·cosθ|+|h·sinθ|)、
  bh=round(|w·sinθ|+|h·cosθ|) 算出来的 —— 4×2 转 90°→2×4、5×5 转 30°→7×7 都钉了）。入参是**逆时针角度**（与 `getRotationMatrix2D` 正方向一致；转反了整行对不上 —
  — 5×5 数字帧 90° 首行 {5,10,15,20,25}，顺时针会是 {21,21,16,11,6}）。画布是 **expand**（包住整图不静默裁像素；想要"旋转裁剪"先 rotate 再 crop —— 两个算子都在了）。
  中心是**帧中心** ((w-1)/2,(h-1)/2)：奇尺寸下恰落中心像素，90° 倍角采样点落整数格点、敢写整行精确相等（相邻行采样到同一行是**行复制** —— warpAffine 逆映射的精确行为，
  host 实测五组全是整行相等，不是 bug；实现注释里写明了）。插值固定 LINEAR、填充固定 REPLICATE（黑边是找色的假阳性源 —— 见实现注释），30° 中心 3×3 混合值**专杀 NEAREST**（已用故意 NEAREST 实现验过变红；
  诚实线与 resize 同一条：CUBIC/LANCZOS 同样给混合值）。NaN/Inf → INVALID_PARAM（三角函数吃掉它们不报错，矩阵是垃圾 —— 入口拒比 warpAffine 断言变 IO 错更诚实），
  0°/360° 恒等（360° 先归一，不因浮点余数差一像素），**桥面已开**（2026-09-29，`images.rotate`，角度必须有限）—— 「把画面转正再匹配」这个消费方到了；
  展开画布 + REPLICATE 填充的口径不变（想要旋转裁剪就再 crop）。**特征也落进计算核了**（`imgnative_feature`，2026-09-25，`host_feature_test.cpp` 51 例）：P1 native 面的收官算子，
  也是第一个**不产出帧、只回坐标**的算子（回模板中心在场景中的 (x,y,confidence) —— **「中心」是字面口径**：报出的是模板几何中心在
   场景里的坐标（中位偏移 + 模板半宽高），不是内点质心（2026-10-01 修，见下），不是新帧号 —— 与 matchTemplate 的 ImageMatch 不同：特征匹配没有"模板尺寸"的概念，模板在场景里多大是未知的；回中心让脚本直接点下去）。链全固定：ORB → BFMatcher(HAMMING) knn k=2 → Lowe ratio 0.75 → 中位数偏移 ±3px 几何一致性计数 → **内点铺开度门**（任一边跨度 ≥10% 模板边长）。
   **2026-10-01 四修**（真机 UI 上恒 found=0 的病灶链，实测与探针见 §7.7 第六次实测块）：
   **成因（2026-10-01 逐窗交叉核对）**：33 例里 19 例恒 found=0，经 `scenekp`（场景侧
   逐窗数点）+ `featwin2`（模板侧缺省/垫边两档数点）交叉分开成三类 ——
   **A 真值处场景侧零关键点 6 例**（48×48 纯色图标 + 5 个 200×150 平坦窗；
   场景配额 8000→20000→40000 后仍有 5/4 例为 0；它们的真值处 ccoeff 实测最小
   **0.99993**，即"图上确实有"）；**B 模板侧缺省 ORB 零点 5 例**（370×80 纯文字行、
   1080×60 工具行、360×900 与 660×1400 与 60×2200 的 200×150 —— 场景侧有点
   （26/91/31/69/2），模板侧缺省档 0 点、垫边后 6~57 点，**仍** found=0）；
   **C 两侧都有点仍配不上 8 例**（模板侧缺省 7~124 点、垫边后 61~486 点，场景侧
   2~253 点）。A 是**结构性的地板**（场景侧一个点都没有，任何参数都无从谈起）；
   B/C 是 ORB 描述子在这类低纹理 UI 上的**可重复性极限**（两侧都有点、垫边后
   模板侧动辄数百点，仍凑不齐几何一致的内点）。三类都别当缺陷修，别的地方修不掉。
   ① **场景侧配额按形态分档** —— 短边 >640（整屏截图）才抬到 `nfeatures=8000 / edgeThreshold=10`，
   小场景保持缺省 1000/31（既有 host 夹具因此逐字不变）；模板侧永远缺省。
   ② **内点铺开度门** —— 内点挤在模板一小块里（任一边跨度 <10%）判未匹配（假阳的形状：
   真机实测报错 208.7px 而 inl=7/conf=0.64，模板侧坐标只占 5%×3%）。
   ③ **命中位置 = 模板中心**（中位偏移 + 模板半宽高）—— 原「内点质心」只在均匀铺开时成立，
   真机偏 82.4px（540×600）/ 60.8px（300×150），改后同批样本 0.0~0.8px。
   ④ **薄条/小模板零关键点垫边重试** —— ORB 的 `runByImageBorder` 在某边 ≤ 2·et(=62) 时
   清空该层全部关键点（与内容无关，1080×60 工具行、120×90 图标面板全中），反射复制 32px
   后重试；这条路另挂一道像素复核（报出位置严格坐标处 TM_CCOEFF_NORMED ≥ 0.6）。
   修后真机：300×150 err 0.00 / 200×150 err 0.00 / 540×600 err 0.00；**可达率不是
   100%** —— 1080×2400 真机截图上 200×150 网格 24 窗 + 文档引用窗口共 33 例，
   命中 14（13 例落在真值 3px 内）；370×80 纯文字行与 48×48 纯色图标恒 found=0
   （成因按下面的 A/B/C 三类分：370×80 是模板侧缺省档零点、48×48 是场景侧零点；
   两者真值处 ccoeff=1.0，配额抬到 40000/et10 也救不回 —— 特征匹配对无纹理
   区域的固有边界，该用 `matchTemplate`；后者在同批 33 窗口命中 33/33，但位置唯一
   只有 25/33（其余 8 例是像素级重复副本，argmax 口径））。**耗时分段**（1080×2400
   整屏，探针 `tiercost`）：场景侧 ORB 是整条链的地板 —— 缺省档 26.8ms、整屏档
   42.4ms（+15.6ms 就是分档的代价；跨会话复跑 27.7~28.7 / 42.9~44.4ms，
   **绝对值随机器浮动、稳定的是这 ~15ms 的差**），模板 ORB + BF 匹配只占几毫秒。每个固定点都有 host 实测依据：nfeatures 500/1000 同一子图描述子逐字节一致（参数只截断、
   不换答案）；BGRA 直喂与手转灰一致（ORB 内部按第一通道取灰，转灰是冗余步骤）；ratio 0.7~0.8 不换答案（good=29/30/33、几何正确都是 19），0.75 取 Lowe 原论文值。
   门禁钉了：子块命中（中心 ±10px、conf≈0.63±0.15 —— conf 指纹把 ratio 链钉住，ratio=0.99 会把它拉到 0.47，已用变体验过变红）、棋盘格误报 → 未匹配（host 实测误报 top 距离 60+，
   ratio 后 good 寥寥）、纯色模板空描述子 → 未匹配（不是 IO 错）、旋转 30° 后**未匹配**（描述子对得上但中位数偏移假设不再成立 —— 这是设计不是 bug，
   真要转着找得接 findHomography/calib3d，那是另一个算子；断言把这个边界钉死，免得被脑补成"旋转容忍=转着也找到"）、拒收早退、两帧不消耗。**构建轨代价**：
   features2d/flann 进 BUILD_LIST（`VERSIONS.env` 三模块 → 五模块；`build-opencv.sh` 链接行同步；host 门禁用同 commit 另配的五模块缓存过渡，本机重配一次即可合一）。
   so 体积增量待下一次 device 构建实测（host 静态库实测 features2d 1.8MB + flann 1.3MB —— 那是 x86_64 未 strip 的 .a，不是 arm64 so 增量，写在这里免得被当成结论引用）。
   **桥面已开**（2026-09-29，`images.findFeature`）—— 同一条纪律：刚性匹配（matchTemplate）与特征匹配（feature）是两种找图语义，脚本侧有了消费方（容忍缩放/
   轻微旋转的找图）才开第二个。P1 native 面至此收官：八算子（decode/match/release/color/gray/crop/resize/rotate/feature）全在计算核 + host 门禁里；**桥面也已在 2026-09-29 全部开通**（十方法：
   decode/matchTemplate/findImage/findColor/release/toGrayscale/crop/resize/rotate/findFeature —— 见 §12.2/§12.3.3）。**附带修掉一处判据主语**：`imgnative_color` 里的 `frame_is_normalized` 原先问的是 **ROI 视图**，
   而视图的 `channels()/depth()` 与父矩阵**同解**（实测），所以那既不是漏判也不构成事故；改问源帧、且挪到 `resolve_region` **之前**，是因为谓词的名字与注释谈的都是"帧"—
   — 判据该写在它自己声称的主语上，顺序本身也是判据的一部分（非归一帧上"某个 region 合不合法"是另一套尺寸语义）。这道门附带钉住一条此前没写明的口径：
   `imgnative_match` 的拒收分支是**早退**——不写出参，所以调用方**不能拿 out_match 当"没命中"判**（可能还是上次调用的残留），判据只有 status；真实调用链正是这么做的（`images_jni.cc` 的 `matchNative` 只看 rc，
   `NativeImageAnalyzer.match` 同样 rc 优先）。NDK 交叉 `-fsyntax-only` 与这道 host 门**互不替代**（前者管 aarch64 能编、后者管判读对），真机红测仍是最后一关。它已接进 `.github/workflows/image-native.yml`（job `host-image-semantics`，
   与 build-opencv 同文件、paths 同源，且**排在构建前面**：判读先红一个 5–10 分钟的，不占满 15–30 分钟的构建槽；OpenCV 按 VERSIONS.env 同 commit 拉取并对表，
   tarball 会让对表退化成口号）。**native 侧已接**：`libopencv.so` = OpenCV 4.14.0（`core+imgproc+imgcodecs+features2d+flann`，`BUILD_JPEG/BUILD_PNG/BUILD_ZLIB=ON` 树内源码、`WITH_KLEIDICV` 默认 ON）静态链接进我们自己的桥面 C++（`imgnative.cpp` 纯计算核：
   `extern "C"` 十入口、帧表自管、`cv::Exception` 就地折叠）；装载面 `images_jni.cc`（全仓图像侧唯一 `#include <jni.h>`）+ Kotlin `NativeImageAnalyzer`/`JniOps`（住 `:platform:system`，
   零 android import；`System.loadLibrary` 失败即不构造）。构建在 `node-runtime-build/scripts/build-opencv.sh`（按 commit SHA 固定、kleidicv pin 对表、16KB LOAD/NEEDED 白名单门禁、
   SHASUMS256 旁 kleidicv ON/OFF 审计行），由 `.github/workflows/image-native.yml` 在 Actions 跑；`PlatformWiring.of` 一行接上（so 缺位 → `ERR_NOT_IMPLEMENTED`，不塞内存替身）。

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

### §8.3 （自 `08-execution.md` 外迁）

**P0 已落地的收敛子集**（代码是事实来源，别按上图臆造）：
- `:domain` 的 `EngineStateMachine`（合法转移表 + `kill()` 归因：`REQUESTED → STOPPED`，其余原因 → `CRASHED`）**已真正驱动池侧**：`PoolSlot` 持一个状态机实例，与「占槽/
  回收」同生共死 —— 夺槽 `markBusy` → `BOOTING`，`execute` 拿到 `EngineRunReceipt` → `RUNNING`（`PoolSlot.markRunning`），`quiesce` → `QUIESCING → STOPPED`（stop 超时兜底杀掉则归 `REQUESTED`），
  `recycle`/`forceFree` 按传入 `KillCause` 归因（watchdog/OOM → `CRASHED`）后回收回 `IDLE`。槽位侧的三态投影 `SlotState{FREE, BUSY, QUIESCING}` 仍在（§8.2 记账要用），但不再与 `EngineStatus` 脱节。
- 为什么状态机挂 `PoolSlot` 而不是另建一张表：状态机必须与占槽/回收同生共死，分表就要处理「表里有行、槽位已 FREE」的孤儿；`FixedEnginePool` 的 stateLock 已保证读写与记账原子。非法转移抛 `IllegalStateTransition`（响亮失败，不静默修状态）。
- **状态对照已落地**（`RuntimeController.statusOf(runId)` / `runStatuses()`）：同时读宿主自报（`ScriptEngine.status()`）与池侧投影（`PoolSlot.status()`），分歧如实进 `RunStatus.drift`；`EngineWatchdog.Tick.drift` 把它带进每轮监督清单。**只报分歧、不改状态** —— 校准不是替某一侧抹平差异，而是让差异先可见。
  合法组合白名单：池 IDLE ↔ 宿主任意（已回收，宿主说什么都不算异常）、BOOTING ↔ 宿主 IDLE/BOOTING（execute 未返回）、RUNNING ↔ RUNNING、QUIESCING ↔ QUIESCING/STOPPED、池侧 STOPPED/CRASHED ↔ 宿主任意。宿主读不到（探针抛错/引擎已死）→ `host = null` 且**不算 drift**：那是「量不到」，不是「不一致」，混在一起会让真分歧被噪声埋掉。
- **裁决已落地**（`EngineWatchdog` 的 drift 连段 + `KillCause.DRIFT`）：单轮分歧只是真机的窗口期常态（宿主刚推 STOPPED、池还没 quiesce 完），连续 `driftKillThreshold` 轮（缺省 3 轮 ≈ 1.5s）还对不上才是真分裂 —— 此时经 `RuntimeController.killRun(runId, DRIFT)` 杀掉重来，不猜哪一侧对（校准不是替某一侧抹平差异）。
  `Tick.drift` 照常每轮记账（谁看见谁处理），`Tick.driftKilled` 单独列出分歧杀供诊断区分"病死"（三路判定）与"分歧杀"；连段中间弥合一轮即从头数，失踪/被杀的 run 清零（防 runId 复用背旧账）。`DRIFT` 归 `CRASHED`（`EngineStateMachine.onKill`：非 REQUESTED 一律 CRASHED），与"管理者主动停"（REQUESTED → STOPPED）区分"自杀"与"他杀"。
  阈值是 `EngineWatchdog` 构造参数（`DEFAULT_DRIFT_KILL_THRESHOLD`），装配层可配。
- 上图里的 `SUSPENDED`（多源计数）与 `PENDING` 在 P0 **均不存在**：`EngineStatus` 枚举里没有 SUSPENDED，`awaitCompletion` 只把 RUNNING 判活（看门狗口径一致，见 §8.4）。**任何依赖 SUSPENDED 的设计（暂停恢复、诊断暂停计数）仍然没有代码基础。**

### §8.4 （自 `08-execution.md` 外迁）

**P0 已落地**：`:app-service:runtime` 的 `WatchdogPolicy` 是三路纯判定（`WatchdogSample{pid,status,heartbeatMillis,cpuPercent,rssBytes} → Healthy | Kill(cause, reason)`），默认阈值：心跳 500ms × 连失 3 次、CPU ≥95% 持续 30s、RSS ≥512MB；只对 `RUNNING` 判活；`RuntimeController.judge()` 委托它，裁决落点 = `killRun`/`killAll`（kill 权威 §4.1）。
**采样已落地**（`ProcessMonitor`，`:app-service:runtime`）：`/proc/<pid>/stat` 与 `/proc/<pid>/status` 的读取 + 折算，产出同一份 `WatchdogSample`，交给 `WatchdogPolicy` 判定。诚实口径写死在实现与单测里：CPU 分子 = 两次 `utime+stime` 差（jiffies → ms），分母 = 调用方给的墙钟间隔，**不折算单核**（多核满载必须看着就 >100%，否则漏杀 Promise 风暴）；
`comm` 含空格括号时从**最后一个 `)`** 之后切字段；首采样 / 换 pid / 时钟回拨 / 计数回绕一律回 0.0%，不给假差分；`/proc` 不可读 → 整份样本回 null（按「无法度量」处理，不猜健康），`status` 读不到只丢 RSS 这一路，不作废 CPU 样本。**本类只采样不裁决**（裁决仍归 `WatchdogPolicy`，kill 仍归 `RuntimeController`，runId→pid 归属表仍归 `:app`）。

**调度循环已落地**（`EngineWatchdog`，`:app-service:runtime`）：§8.4 的三路分工终于有了周期性调度者 —— `RuntimeController.watchAnchors()` 交出在途执行的 `WatchAnchor{runId, receipt.pid}`（**pid 取 `EngineRunReceipt.pid` 快照，
不是 `ScriptEngine.pid` 当前值**；槽位复用后两者会分叉），`ProcessMonitor` 按 pid 分别记账采样本，`RuntimeController.judge()` 裁决，`Kill` 落 `killRun(runId, cause)` —
— 判据、采样、执行三方仍是三个类，调度者只负责「到点把三者接起来」，不自己长判定口径。`AppShell.assemble` 交出 watchdog 实例与 `ProcessMonitor`/`heartbeatMillis` 注入缝，
`startWatchdog(scope)` 之前不转；生产路径由 `AppShellKit.assemble` 在装壳时就转起来（§4.1 的真实调用点）——域缺省是壳自己持有的 `SupervisorJob`（`AssembledShell.close` 先停轮转再关池与持久句柄），
调用方也可传自己的域（那就自己负责停：`EngineWatchdog.start` 的 KDoc 写死了这条所有权规则）。
- **采样周期 = `heartbeatIntervalMillis`**（`WatchdogPolicy` 那条因此从 private 变 public）：周期长于心跳阈值会把活引擎判死（500ms×3 的阈值配 3s 轮询 = 假阳性），controller 的这份 policy 经 `RuntimeController.watchdogPolicy()` 原样交给 watchdog —— **只有一份阈值**，不在装配层另造一个。
- **pid 终结即忘**：无论正常结束、被 watchdog 杀掉还是本轮采样后不在途，`EngineWatchdog` 都调 `ProcessMonitor.forget(pid)`。Linux 复用 pid，不遗忘等于让新进程背旧 CPU 基线（虚高 → 误杀）；pid 落到别的 runId 时 CPU 历史整段清零，同理。
- **不另建 pid→runId 表**（§8.4 原口径不变）：归属表只有一份，就在 `RuntimeController` 的在途账里。`:app` 侧再抄一份必然漂移（stop/kill 路径不止一条），`watchAnchors()` 是它的只读投影。

**心跳一路已接线**（§8.4 缺口② 补齐）：`HeartbeatLedger`（`:app-service:runtime`）是心跳的宿主侧收单方，`RuntimeController.heartbeat(runId, seq)` 是它的桥侧入口，`RuntimeController.heartbeatMillis(runId)` 是看门狗的问讯口 —— `EngineWatchdog` 缺省就问 controller 那份账本（`AppShell.assemble` 的 `heartbeatMillis` 缺省 null = 装配时接真账本；
显式传 `{ null }` = 明示这一路不接，看门狗如实记 `Tick.noHeartbeat`）。JS 侧 `engines.heartbeat {runId,seq}` + `startHeartbeat(runId)` 定时打点（`unref` 定时器，不保活事件循环）。
- **序号即真伪**：账本只认**递增** `seq`。同/旧 seq 一律拒收（只累计 `staleBeats()`，不刷时间戳）—— 否则宿主张力下积压的旧心跳会把一个**已经死了**的 run 一直喂成活的，那正是心跳这一路要抓的形态。
- **从未打点回 null，不回 0**：0 会被当成「刚刚打过」，失联判定永不触发；null = 量不到，看门狗据此记 `noHeartbeat`。
- **与 run 同生共死**：`stop`/`killRun`/`killAll`/`settleDone`/`settleKilled` 五条终结路径全部 `forget(runId)`。不遗忘 = runId 复用时新 run 背上一段「假年轻」，失联判定被推迟到下一次自然打点。
- **无主心跳不建账**：`RuntimeController.heartbeat` 先验在途再落账本 —— 不在途 runId（已结算/从未存在）回 `false` 且不记账。否则迟到/重发的心跳会在复用 runId 上复活旧账，或把活 run 的账顶出记账上限；桥侧未知 runId 仍 Ok `false`（不是调用方错误，不 4xx），JS mock 宿主复刻同一口径。
- **仍不伪造**：拿 watchdog 自己的轮转周期当心跳依旧是禁止的 —— `while(true)`（心跳活着、CPU 打满）只靠外带差分抓得到。

至此 §8.4 三路判据、采样、调度、心跳打点全部闭环。**pid 半边已接线**：`NodeProcessEngine`（`:engine:node-process`，Kotlin spawn）的 `EngineRunReceipt.pid` = spawn 瞬间真子进程 pid（`NodeProcessEngineRealSpawnTest` 实测 >0 且非自身；`:app` E2E 经 `watchAnchors` 锚点 pid>0 复验），看门狗 `/proc` 采样锚点在 spawn 路径上是活的。
**心跳的生产链已备**：spawn env 下传 `AUTOSCRIPT_RUN_ID` → kBootstrap 500ms 自动打点 / 桌面脚本显式 `startHeartbeat`，E2E 实测 `heartbeatMillis(runId)` 落账非 null；桥监听 `BridgeSocketListener` 亦已接（abstract 绑定 + uid 门禁 + `NewlineFrameServer` serve，JVM 假缝单测；bind 失败 = 离线降级不注入名，见 §7.5/§7.8）。
addon 的 JS 消费面亦已落（facade `attachNative()`，§7.8）。**设备面只剩真机联调**（jniLibs 三件套 + addon 资产落位 + facade dist 随包与打包入口 attach 接线 2026-09-24 均已落，见 §12.4/§19 切片路线）未落；在那之前，真机上当前生效的是「未 spawn / 宿主不给 pid → noPid」「宿主不打点 → noHeartbeat」两条量不到路径 —— 这由 `Tick` 的三个清单如实区分，不是一个笼统的 `unmeasurable`。

### §8.5 （自 `08-execution.md` 外迁）

**持久形态已落地**：`JournalFileStore`（意图日志）+ `FileRunArchive`（运行档案）同用一套 jsonl 行格式（共享 `JsonLine`），`force(true)` + 启动 replay + 半行容忍；
两文件分开存是刻意的 —— 键不同（intentRunId vs engineRunId）、只写一侧的孤儿在格式上才可见。

孤儿结算两条路（都只在 `recoverUncommitted` 里跑，运行期绝不扫——那会把正在跑的执行误判成孤儿）：
- **逐 intent**（`settleOrphanArchive`）：本次要 reopen 的遗留意向，其关联档案里还没终态的记录 → 如实 `CRASHED`（宿主死时没结算）；
- **全档空档**（`settleVoidArchive`）：`log.commit` 与 `recordLink` 不同事务，中间崩溃会留下「意图行已终态、档案停在 RUNNING」的孤儿 —— 它挂不到任何未 COMMIT 行上，逐 intent 那条路永远看不见。恢复刚起来时引擎池必空，档案里所有非终态记录都只能是上一进程遗物，此时按 `unfinished()` 自报统一补 `CRASHED`（无 link 的记录跳过：独立执行不属意图日志管辖）；
  link 原样保留，任务中心仍可按 IntentRun 追到这条失联记录。

### §8.6 （自 `08-execution.md` 外迁）

- **P0 已落地**：满池排队**默认有界**，不再有"默认无限等"这条路。`queueTimeoutMillis` 显式覆盖优先；不传则按触发源分级取默认上限（`ControllerRunDispatcher.DEFAULT_QUEUE_TIMEOUTS`）：
  `ENGINE_INTERNAL` 15s（满池下的跨引擎调用是"持有者等后来者"的嵌套形态，必须最先爆，否则变跨引擎死锁）、`USER_CLICK` 10s（人盯 UI，等不及就如实 Cancelled，
  不让按钮原地转圈）、`INTENT_BROADCAST`/`EVENT` 60s（外部涌入本应容忍排队）、`TIMED` 120s（守时任务已承诺"亮屏+解锁保底 + 可能偏差"，2 分钟只为满足铁律 3，
  不追求抢跑）。分级表是**注入缝**（构造函数参数），装配层可换成自己的口径；`queueTimeoutMillis` 传 0 视为漏配，构造即 `IllegalArgumentException`——0 等于"永不允许排队"，
  与"绝不静默丢任务"相反。
- **P0 已落地（deadline 记账）**：`PendingRun.deadlineMillis` + `isExpired(now)` 记下"本次投递的到期时刻"，与 dispatcher 的排队上限**同源但不同职**——dispatcher 那侧管"在途排队等不等得起"，deadline 管"宿主重启之后这条意向还值不值得投"（崩溃恢复面对的是另一件事：进程死过一次，用户早走了/外部事件早凉了）。
  期限写在 `IntentRun.deadlineMillis` 上随 RUN_START 行落盘，`reopen` 原样带到新行（恢复重投不得变期限，否则同一意向两套到期口径）；`Scheduler.onTrigger` 按 `排期时刻 + deadlineFor(触发源)` 填，`recoverUncommitted` 遇过期意向照样 `reopen` 封口记账，但**不再 dispatch**，直接 COMMIT [RunOutcome.Cancelled]（`RecoveryRecord.expired` 标出，任务中心按 runId 读到"为何没跑"——绝不在恢复路径里静默跳过）。
  两处口径同源由装配层保证：`Scheduler` 的 `deadlineFor` 与 `ControllerRunDispatcher.queueTimeout` 默认同喂一张分级表（`DefaultDeadlines` 与 `DEFAULT_QUEUE_TIMEOUTS` 数字一致，前者是缺省值不是契约，装配层可各自覆盖）。
- **P0 已落地（调度侧停止与收口）**：`Scheduler.lastHandle`（`EngineStopHandle{idLink, name, runNonce, stop}`，§12.3 engines.exec 的调度侧投影）只在 dispatcher 真的产生了引擎执行（`DispatchReport.link != null`）时持有 —
  — 门禁拒绝/排队超时/启动失败没有可停的东西，不持有假句柄；停止入口由 dispatcher 经 `DispatchReport.stop` 填权（`:app` 的 `ControllerRunDispatcher` 在 start 成功时绑定 `RuntimeController.stop` → 池四步 quiesce；
  三条未产生执行的早退分支回 null stop；已结算后调用落 AlreadyGone 幂等 no-op，永不升级为 kill）；`stopLastRun()` 转发句柄的 stop（成功不清句柄，停止幂等），
  `canStopLastRun()` 从句柄现算；`sink()` 撤销全部触发器并置位（此后 `onTrigger` 早退）；`quiesceThenStop()` = sink → 停最近一次 run → 返回句柄供归档（§13 铁律 4 的调度侧部分）。
  线程契约诚实声明：lastHandle/sinking 读并发安全，写仅发生在 `onTrigger` 内（装配层负责把五类触发源串行化）。
- **P0 已落地（执行侧急停）**：`RuntimeController.forceStopAll(cause)` —— 进程级急停的显式入口（应用被杀/系统回收/测试收口），与请求驱动的 `killAll` 区分（killAll 是裁决/
  停全部的落点，在途表经 guard 串行收走；forceStopAll 只做杀全部 + 清在途表 + 忘心跳，不走请求语义）。**装配层有序收口已落地**：`AppShell.shutdown(cause)` = `scheduler.quiesceThenStop()`（先 sink 拒收新投递、
  再停最近 run）→ `controller.forceStopAll(cause)`（再杀全部槽位并复用）。顺序不可反：先杀后停会在调度不知情窗口继续投递。两步都幂等；返回调度侧被停句柄供归档（无句柄时为空，
  不假装停过）。
- **P0 已落地（无人 await 的 run 自带期限，2026-10-01）**：
  原先的诚实边界是「引擎侧 `waitCompletion` 超时不发起的场景还没人管——在途 run 没人收尾时 watchdog 是唯一兜底」，而看门狗三路判据全看**进程表现**（心跳/CPU/RSS）：一个心跳正常、CPU 空闲、RSS 很低的长跑脚本三路都判它健康，**没有任何一路收得住它**。收口判据不是「有没有声明超时」而是**发起方等不等**（`PoolAcquireRequest.timeoutEnforcer`，见 §8.1）：
  - `AWAITER`（缺省）：调度链路的 `ControllerRunDispatcher` 自己 `awaitCompletion`，超时 → `killRun(REQUESTED)`（归 STOPPED）——这条本来就有人收尾；
  - `WATCHDOG`：桥 `engines.exec` 拿到句柄即返回、没人 await 终结 —— 期限 `startedAt + scriptTimeoutMillis` 经 `RuntimeController.WatchAnchor.deadlineMillis` 交给看门狗，到点落 `KillCause.TIMEOUT`（归 CRASHED：期限到期是强制收账，不是调用方主动停）。
  期限线在 `EngineWatchdog.tick()` 里**先于** pid/心跳那两条「量不到」分支判 —— 期限不依赖任何度量，它是发起方声明的事实；排在后面会让「宿主不给 pid」或「心跳未接线」的 run 连期限都够不着，
  恰好退回「没人收尾」那一类。同时 `engines.exec` 的 `timeoutMillis` 由可选改为**必填**：缺席/`null`/`<= 0` → `ERR_INVALID_PARAM`（构造期 `require` 同款守卫）——
  缺省值在这里没有诚实来源，编一个（30s？5min？）等于替脚本静默决定它能跑多久（与 `queueTimeoutMillis` 传 0 视为漏配同一条纪律：响亮失败）。
  **仍待覆盖（诚实边界）**：期限只覆盖**声明了期限**的 run，且只在看门狗轮转真的在跑时有效（生产轮转挂 `AppShellApplication` 的 SupervisorJob 域）——轮转没起 = 没有期限线；
  池空退避（`idlePollMillis`）期间新起的 run 最坏晚一个退避周期才被看到。另外 `runNonce` 幂等只保证**外部副作用**不重复，不保证「期限内跑完」——期限到点即杀，
  写到一半的副作用仍由执行体自己的幂等键兜底。
- **P0 已落地（Android 触发侧）**：`AlarmSchedulerProvider`（`:app` 装配层）把 `SchedulerProvider.registerTrigger` 翻译成闹钟——预拉提前量 `wakeAheadMillis` 是契约字段（60s，
  测试与调用方同一份值，不藏常量）；**提前量只向前推、不向后扯**（排期已到即夹到当前时刻，ROM 对负延迟处置不一）；`canScheduleExact` 为假时降级 `setWindow` 且**记账**（`degradedTasks()`，
  `taskId → 排期时刻`），能力中心据此标注「可能偏差」——**不静默降级**。框架调用（真 `AlarmManager`）在 `AndroidAlarmPort`，本类**无判断**：taskId → `KeyStableHash` 定 requestCode（同 taskId 恒同，
  重复 arm 是替换）、`setExactAndAllowWhileIdle`/`setWindow` 两个调用点、取消 = `alarmManager.cancel` + `pendingIntent.cancel`（两个都要）。`PendingIntent` 在 API 31+ 必须 `FLAG_MUTABLE`（系统要填 `EXTRA_ALARM_*`）。
  回投侧是**静态注册**的接收器 `AlarmReceiver`（精确闹钟响时进程可能已被 ROM 杀掉，`registerReceiver` 收不到）走 `goAsync()` 在广播窗口内把 taskId 经 `AlarmDispatch` → `SchedulerAlarmRoute` 送回 `Scheduler.onTrigger`（TIMED 来源 + 闹钟真实排期），
  于是 runNonce/意图日志/dispatcher 口径与手动触发完全一致。**装配前/后的漏投不静默丢弃**：没接路线的闹钟进 `AlarmDispatch.missed()`（同一 taskId 只留最新一条，
  `drainMissed()` 清账），`AppShellApplication.missedAlarms()` 供能力中心如实呈现「闹钟已响但调度未就绪」。
- **P0 已落地（屏幕门禁的生产实现）**：`AndroidScreenGate`（`:app`）——`SCREEN_ON` 在两个系统查询缝（`interactive` = `PowerManager.isInteractive`，`deferWakeLock` = 持锁方）任一为假时**如实 `Deny`**，不降级成「锁屏也跑」（那条路径的表现是「任务成功、实际什么都没发生」）；`SCREEN_OFF` 先经 `ScreenOffGuard` 收起画面类能力再放行（无障碍 + 网络在锁屏下真实可用）；
  `ANY` 放行。两条缝的值由 JVM 单测注入，判断逻辑因此可测而不必 Mock 框架对象。**`AllowAll` 与 `AndroidScreenGate` 在 `ANY` 上必须同结论**（`AndroidScreenGateTest` 有断言守着），否则同一条任务在单测里放行、真机上被拒，差别只在现场暴露。

- **P0 已落地（任务中心读口与屏，2026-09-24）**：`HostSummary` 增 `taskCenter()`（挂起：读任务注册表 + 运行档案两个持久寄存器；**读失败抛** —— `:ui` 如实显示「读任务失败」，
  而不是冒充「一条任务都没有」，那是「读成功且真没登记过」的另一种事实）→ 拼装 `TaskCenterRead.snapshot`（`:app` 壳装配包，纯 JVM 可测，**不自带 IO**：
  取数由调用方注入）：`TimedSchedule` 三态 → `ScheduleSpec` 三态**逐字段**映射不聚合（Cron 也保留成行 —— 不可能日期/坏行算不出下一跳时留名不续排，任务不从列表凭空消失）、
  `ScreenGuarantee` → `ScreenRequirement` **按名对表**（不用 `ordinal`；`TaskCenterReadTest` 有两边枚举同集断言，调度器加值先红再谈映射）、停用任务**不问下一跳**（问了也会得到答案，
  而那个答案会让人以为停用任务还会跑）、恢复账 `total`/`expired`/`failureText` 三笔分开且**失败时 `retried=0`**（`total-expired` 会把失败报成成功）→ DTO 住 `:domain` 的 `TaskCenter.kt`（`TaskCenterSnapshot`/
  `ScheduledTaskRow`/`RunRow`/`RecoveryRow` —— 调度器类型只在 `:app`，呈现层照旧只依赖 `:domain`）→ `:ui` 的 `TaskCenterScreen` + `TaskCenterState`（纯状态 DTO，JVM 可测）：
  三页签之二、**没读到 ≠ 一条任务都没有**（`NOT_LOADED` 与 `failed` 分开且保留原异常文案）、下一跳为 null **不编时间**（停用与 Cron 算不出两义由 `enabled`/
  `schedule` 分辨）、未结算执行文案点破「**不是此刻正在跑**」（`unfinished()` 只增不减，这栏只可能是上一进程遗物）、降级任务标「可能偏差」。刷新与能力中心同构：
  回前台/切页签现取一次，读失败不自激重读。读切片落地后，登记/取消/立即执行随操作面接上（见下条）。
- **P0 已落地（任务操作面：登记/取消/立即执行，2026-09-24）**：写口走 `HostSummary` 三方法 —— `registerTask(TaskRegistration): String`（回分配到的 id；`:domain` 的 `TaskRegistration` 是**纯数据** DTO，
  校验不在 DTO 里）/ `cancelTask(taskId)`（幂等：tombstone 先行，ghost id 照样返回成功 —— 用户视角"它已经不在了"）/ `runTaskNow(taskId)`（`TriggerSource.USER_CLICK`，
  **挂起到本次执行结算**再返回）。两个实现方都收口：`AppShellApplication` 壳未装配即抛（不冒充成功），`FakeHost` 同步长出 UOE（替身纪律：HostSummary 长一个成员，
  替身同批跟上，否则 `:ui` 单测编译断）。语义闸门唯一落点是 `:app` 的 `TaskCenterOps.toScheduledTask` —— 与桥侧 `WorkManagerNamespaceHandler` **同规则两侧各测**：
  空串三字段点名拒绝、`Once.delaySeconds<0` 拒、`Daily` 靠 `TimedSchedule` 的 0..23/0..59 require 拒、`Cron` 经调度器 `CronTab.parse` 校验（非法点名哪一段，两侧非法样本同源）、
  timeout≤0 拒、非法时区拒、`ScreenRequirement` **按名对表** `ScreenGuarantee`（同集断言先红）。形状解析（整数文本、trim）收在 `:ui` 的 `RegistrationForm`，语义规则不复述一份（两处规则必然漂移）。
  诚实边界三条：(1) **`runTaskNow` 不哑火** —— `onTrigger` 对 sinking/缺席任务静默 return，所以 `AssembledShell.runTaskNow` 先查再触发，查不过就抛（检查与触发间的 TOCTOU 窗口已接受并写进 KDoc）；
  不改 `onTrigger` 签名（~28 处调用点全是语句位，改返回型会让非 void `@Test` 静默跳过）；(2) **回执不说脚本成败** —— `onTrigger` 只到结算，成败在意图日志/
  控制台，回执写「执行成败见控制台」；UI 侧 `TaskCenterState.opInFlight` 挂起期间禁用全部操作按钮（防双击双投）；(3) **Once 立即执行即出册** —— 调度器 `finally` 对 Once 终态化，
  卡片事前标「一次性任务：执行过后自动移出注册表」，回执点破「已执行并出册」，刷新后卡片消失是排期语义不是被取消。操作失败**不清任务清单**（`opError` ≠ `loadError`，
  `copy` 保留 `tasks` —— 抹掉会让用户以为任务全没了）；`opNotice` 只由成功写入，`of()`/`failed()` 归零（现取纪律），`performTaskOp` 刷完表再盖回。取消走 `AlertDialog` 一次性确认（误点的代价 = 手工重登记）。
  登记表单**缺省全空**（cron 格缺省 `0 9 * * *`）—— 误提交过不了 `:app` 闸门，不产生幽灵任务。UI 侧操作是 `HostSummary` 的**独立写口**，不经 `workManager` 桥（桥是脚本侧命名空间；
  宿主自己的操作面不该绕进程一匝）。

### §8.7 （自 `08-execution.md` 外迁）

- **P0 已落地（`:main` 侧 FGS + 真唤醒锁，2026-09-23）**：三个可分离的缝，判断全在 JVM 可测面，系统接触面各收在一个类里。
  - **`WakeLockOps` / `WakeLockLedger`**（`platform/system/.../system/power/WakeLock.kt`，步骤 6d 自 `:app` 迁入，2026-10-01 D3 随子包对齐移入 `power/`；`:app` 经 `ForegroundKeeper`/`lockHeld()` 读）：`AndroidWakeLockOps` = 真 `PARTIAL_WAKE_LOCK`（`setReferenceCounted(false)`，
    acquire/release 异常一律吞成 `false` + 日志）；`WakeLockLedger` = **token 引用计数 + 超时自动释放**——`hold(token, timeoutMillis)` 只在**首次**（账本为空）时真取锁，
    **取锁失败不记账**（"记了账却没锁"是假绿之源）；`release(token)` 用"先查存在再删"（超时 token 的值为 null，照样能释放）；`sweep()` 释放到期项并返回名单；
    `isHeld()` = **账本非空 ∧ `ops.held`**——两侧任一说"没有"就一律算没锁。token/超时的形状就是给 P1 `power_manager` 预留的插口（引擎进程请求 → FGS 加锁走同一账本）—
    —该命名空间已落地（见上条），账本语义零改，兑现了预留时的承诺。
  - **`ForegroundOps` / `ForegroundServiceBase` / `ForegroundKeeper`**（`app/.../shell/ForegroundOps.kt`、`ForegroundKeeper.kt`）：`AndroidForegroundOps` 管 `startForeground`/`stopForeground`，API 34+ 传 `FOREGROUND_SERVICE_TYPE_SPECIAL_USE`，
    通知走 `ForegroundNotifications`（渠道 `autoscript.foreground`）；`ForegroundServiceBase` 是**服务不自装配**的落点——服务的 `onStartCommand` 按 `ACTION_START`/`ACTION_STOP` 分支，
    Keeper 从进程级邮箱 `ForegroundHost.keeper` 现取（服务**不持有** Keeper/账本，避免 service → 根包成环；`START_NOT_STICKY`：重启路径没有 Keeper 上下文，续期统一走 `Application.onCreate` 的同一条装配路径，
    与 `BootReceiver` 同纪律）；`ForegroundKeeper.isActive()` = **`ops.foregroundRunning` ∧ `wakeLocks.isHeld()`**——"请求过" ≠ "生效了"；`stop()` 在系统仍报前台时返回 `false`（保持 drain 路径活着，
    不假装停干净），随后由 `renew()` 兜；`renew()` = sweep 到期锁 → 账本还持着而服务掉了就补拉 → 账本空了但服务还开着就停掉；守护 ticker（15 分钟）只做这两件事，
    `Throwable` 全吞（`scheduleWithFixedDelay` 一旦抛出就静默停摆）。
  - **屏幕门禁收口**：`AndroidScreenGate.of(...)` 的 `deferWakeLock` 生产实参已是 `ForegroundKeeper::lockHeld`（= 账本 `isHeld`；`AppShellApplication.screenGateOf`，根包经此读口不碰 platform 类型）。**§8.7 原来那条"恒真 = 明写的待接"就此作废**：现在熄屏 + `SCREEN_ON` 的真实表现是「锁没拿到就 `Deny`」，且 `Deny` 的判词与账本一致——不是靠恒真放行后再指望系统。
  - **如实呈现**：`ShellSummary.keepAliveActive`（`:domain`，**不带默认值**——每个产出方必须显式回答"保活到底生效没有"，漏填编译期就炸）由 `AppShellApplication` 填 `keepAliveActive()`；`:ui` 首屏据此直说「保活已生效」/「保活未生效：熄屏的亮屏任务会被拒绝」——这是"任务为什么没跑"的直接答案，不藏在二级页。
  - **诚实边界**：`onTerminate()` 在真机上**从不被调用**（进程死时系统自行回收 wakelock），它存在只为测试/模拟器收口 + 给"谁来停"一个代码落点；保活未生效时 `AppShellApplication` 记 `Log.w` 并在 UI 上显示红色，**不降级成"锁屏也跑"**。

### §9.5 （自 `09-capabilities.md` 外迁）

- **已接生产（`:app` 侧）**：`AndroidPermissionGates`（`AndroidCapabilityProbes`(6 事实真查询) → `AndroidSystemStateReader`(事实→三态映射，判据唯一出处) + `AndroidGrantLauncher`(`pageFor` 能力→页 + `specFor` 页→Intent 规格，
  字面量单测锁死) → `permissionCenterOf`(纯拼装，不判断)；住 `com.autoscript.shell` 装配包，`android..` 直连与 `AndroidAlarmPort` 同例）→ `AppShellApplication.permissionCenter()`（懒建缓存，
  查询本身不缓存）→ 能力中心 UI；降级账本读口 `degradedAlarmTasks()`（`AlarmSchedulerProvider.degradedTasks` 只读视图）。门禁从"编排可测"推进到"生产有人问系统"。
- **能力中心读口已接（2026-09-23）**：`HostSummary` 增 `capabilityCenter()`（挂起：三态是**现问系统**的结论，含 root 探测的 IO 切换）与 `openCapabilitySettings(capability)`（**无判断**，
  转给 `PermissionFacade.openSystemSettings` —— 呈现层因此不必也不许碰 `Settings`/`Intent`）。快照 DTO 住 `:domain`（`CapabilityCenterSnapshot`/`CapabilityRow`，后者带 `canRequestGrant` = `CapabilityLifecycle.canRequestGrant` 的投影：
  **按钮显隐的判据唯一出处仍在 :domain**，呈现层不许自己写 `state != GRANTED`）。拼装在 `:app` 的 `CapabilityCenterRead.snapshot(facade, degradedAlarmTaskIds)`（**纯 JVM 可测** —
  — `Application` 在 JVM 里构造不出来，而"全量枚举能力 + 逐项问三态 + 配同一份 guideText + 带上降级任务账"这段判断是那一屏的全部事实来源）。**全量枚举**（`Capability.entries`）不是"只列异常项"：
  能力中心要能回答"我到底有哪些能力"；**读失败抛**，`PermissionCenter.state` 只把 reader 查询崩收敛成 `DEGRADED`（可用性未知即受限），更外层的抛穿到 `:ui` 如实显示 —
  — 吞成"全 DENIED"会让用户以为授权全丢了。呈现侧 `:ui` 的能力中心（`CapabilityScreen` + `CapabilityCenterState`，纯状态 DTO，JVM 可测）：三态各有中文说法（可用/
  降级可用/被拒绝 —— "用户该做什么"逐态不同）、引导文案**原样透传**（改写过的文案会与系统里的真实路径漂移）、降级中的定时任务**单列一段**（§8.6 承诺的「可能偏差」标注，
  不是权限问题）、**没读到 ≠ 一个能力都没有**（`NOT_LOADED` 与 `CapabilityCenterState.failed` 分开，失败时保留原异常文案）。刷新时机：回前台/切到该页签时经 `LaunchedEffect` 重问一次 —
  — 用户从系统设置页授完权回来看到的是**刚问过**的结论，而不是离开时那份缓存（后者正是"授权了但界面还说没授权"的来源）；读失败不反过来触发重读（不自激）。

### §9.6 （自 `09-capabilities.md` 外迁）

- **五个系统命名空间（`dialogs`/`shell`/`device`/`app`/`floatingWindow`）已全部落地语义层**：SPI 与 DTO 住 `:platform:system`（`SystemHostContracts.kt` + 五份能力契约，2026-09-30 步骤 6a 自 `:domain` 迁入；
  `DialogHost` 六型留 `:domain`），handler 住 `:platform:system`（`SystemNamespaces.kt`，`dialogs` 例外在 `:platform:capabilities` 的 `DialogsNamespaceHandler`），`AppShell.assemble` 的 `systemHandlers` 束五个字段各自可空 —
  — 未接线的那一个如实回 `ERR_NOT_IMPLEMENTED`，不伪造可用。`app.launch` 返回 `false` / `app.currentPackage` 返回 `null` 是**诚实答案**（前台无包名、启动被系统拒），
  不抛异常；`dialogs` 的 `mode` 三态 `auto`/`overlay`/`notification` 由 handler 解析并**原样交给 `DialogHost`**，BAL 降级选路（overlay 可见则弹窗，否则通知回调）是 `DialogHost` 实现的事 —
  — handler 看不到 overlay 实况，不做这个判断；取消语义统一：prompt 用 `{value:null, confirmed:false}`，choose 用裸索引 `-1`（与 `extras.ts` 逐字对齐）。 **SPI 侧进度**：
  `shell`/`device`/`app`/`floatingWindow` 四件已有 `:platform:system` 真实现（`Runtime.exec`/`Build`/`PackageManager`+`UsageStatsManager`/`WindowManager`；`SystemSpis.of(context)` 是实现入口），
  `dialogs` 的 `DialogHost` **已落地**（实现按 domain KDoc 约定住 `:platform:capabilities`：`AndroidDialogHost` 纯 JVM 编排——AUTO 按 `overlayAvailable` 选路、OVERLAY 强制不可用即 `ERR_PERMISSION_DENIED` 不静默降级、
  通知路径先登记后 post、`finally` 注销+撤幽灵通知、晚到答案丢弃；设备面 `SystemDialogOps` 在 `…capabilities.device` 子包——AlertDialog overlay 弹窗（BadToken→`ERR_PERMISSION_DENIED`）+ 独立通道通知（prompt 回复动作挂 RemoteInput、
  choose 每项一个动作、`DialogActionReceiver` 按 key 回投；requestCode 分槽位防 filterEquals 撞 PendingIntent）；构造在 `PlatformWiring.of`（同 `overlayAvailable` 喂悬浮窗与对话框），
  `inject` 缺省 null 仍如实 `ERR_NOT_IMPLEMENTED`）。
- **已落地（项目目录与装配期补部署）**：`:domain` 的 `ScriptPaths`（项目根 `files/scripts/<projectId>` 的单一事实来源 —— 纯路径计算、无 IO；拼错目录名编译期即可见）被三处复用（`:app-service:npm` 的 `NpmProjectLayout`、
  `AppShellKit` 装配、调度侧补部署）。`:app-service:scheduler` 的 `ScriptDeployRecovery` 在 `AppShellKit.assemble` 时跑一次：**只补缺、绝不覆盖**（用户手改的脚本原样留着）、
  空清单如实为空（`deployReport.changed == false`，不粉饰成"已恢复"）、单文件失败不带走整批（`deployFailures()` 列路径+原因）。来源合并（`scriptSources` 显式优先 + `assets/scripts/<projectId>/` 按项目补缺，
  单项目读失败跳过不炸整批）：配方经 `scriptProjects` + `assetReader` 两缝拿资产（配方本身不直连 AssetManager，保持纯 JVM 可测），`AppShellApplication.installWithFiles` 喂真实现（`assets.list("scripts")` 枚举 + `AndroidAssetsSource.readScripts()` 按需读）。
  **诚实边界**：本类不是部署器 —— 覆盖/版本/审批仍归 `script-repo` 的 `AtomicDeployer` 与 npm `InstallCoordinator`（sha256/journal/审批链路）；框架也没有模板/脚手架机制，来源给多少补多少；
  **2026-10-02 批 16 起** `:app` 随包**一个**示例脚本 `assets/scripts/demo/`（此前资产位为空 ——
  APK 里零脚本时，登记出来的任务只会以「脚本文件不存在」告终，四层通没通从界面上看不出来）。
