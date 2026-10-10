# 流水切片（按日期）

## 最新追记（2026-10-10，批 91）

- [2026-10-10 · 批 91：T1 spawn 桥本体（`npm run` / `npx` 第一次真跑起来）](2026-10-10.md)：
  批 88/89 的审批卡、批 90 的流式输出到这一步之前全断在同一处（`scriptExecutor` 是
  `Unavailable`，获批后仍得 `ERR_NOT_IMPLEMENTED`）—— **npm P1 里唯一还挡着用户动作的一条**。
  链：npm 会话进程 `--require` 桥 shim → `npm run` 发 `spawn("sh",["-c",body])` → shim 经
  unix socket 报给宿主 → 宿主起真进程 → stdio/退出码以**假管道**回填。只放行异步三兄弟
  （同步三兄弟与 `fork` 如实 `ERR_NOT_IMPLEMENTED`），`detached:true` 当场拒。
  **两条 shim 互斥**：安装会话注门禁（零 spawn），T1 会话**只注桥那一份**。
  **本批最贵的一课**：`Channels.newInputStream/newOutputStream` **共用 `blockingLock()`**，
  读线程抱着锁阻塞 → 写线程永远拿不到锁，**双向协议被自己的流包装锁成单向**，症状是两类
  测试双双挂死而 `jstack` 里只有 `BLOCKED`、报错面只有「超时」；改用直读 `SocketChannel`
  （自带分开的读/写锁）即全绿。同批修两处同源静默挂死：握手帧不能走 `send`（会排队等
  `ready`，而 `ready` 等 `helloAck` —— 死锁）、会话 socket 无在途子进程时必须 `unref`
  （常驻 socket 吊住事件循环，npm 永不退）而有在途时必须 `ref`（`refWhileBusy()`）。
  守卫 16 例（真 node 7 / 真 npm 4 / 替身 5）；T1 两类**不设 `assumeTrue`**（E2E 那条是
  全仓唯一证明「npm 真走桥」的用例，跳过 = 静默丢覆盖）。**欠账如实记**：不用引擎池、
  不做最小 CapabilityMask（同 UID）、不做输出流式、`signal` 恒 null、孙子被 reparent 后
  杀树链断。**边界**：真机未验。

## 最新追记（2026-10-10，批 90）

- [2026-10-10 · 批 90：控制台「活着」三件（真流式 stdout + 常驻进度推送 + 命令历史落盘）](2026-10-10.md)：
  用户口径「控制台从『现取一次』变成『活着』」。**① 真流式**：`HostNodeExecutor.runNpm`
  原先先 `readBytes()` 再 `waitFor`（跑完才拿到全部输出）→ 专门的读流线程 + 新的 `OutputSink`
  缝（与 `ProgressSink` 分开：那条装脚本侧契约形状的 `InstallEvent`，这条装 npm 原文）；
  流真报过时**不再补 `outputTail` 行**（否则同一段文本显示两遍）；空行跳过（真实 npm 输出
  以空行结尾，`takeLast(8000)` 取到的是空行 —— `HostNodeNpmE2ETest` 抓到的）。
  **② 常驻推送**：`:ui` 新增 `LivePoll.pollWhile` + 两个调用点（提交后跟到跑完 / 子页在前台
  时一直跟）；刻意**不**用宿主的 `progress` SharedFlow（**无重放**，晚到订阅者永久丢一批）。
  三条自我约束：有界 / 只依据宿主给的事实 / 每轮走同一个读口；间隔与轮数可注入（否则
  「有界」那条用例要跑四分钟，迟早被 `@Disabled` 关掉）。**③ 历史落盘**：新增 `ConsoleHistory`
  （`files/.autojs/console-history.jsonl`），与审计史**长得一样但纪律相反**（那个只追加永不清理，
  这个允许修剪 —— 差别写在类 KDoc 里防误搬）；按项目分开读；带凭据形态的行**整条不记**
  （不打码：打码后的历史看起来能跑，点进去得到「认证失败」= 历史自己造的假故障）；
  写口在 `:ui` **派发点**（那里才有原文）。**同批修一处真错**（探针测出来的）：
  两处 sink 调用点写的是 `events.tryEmit(ev)`，只喂了 Flow 没经 `emit` ——
  执行体独有的 `DOWNLOAD`/`REIFY` **从未进过事件环**，脚本侧 `onProgress` 收不到、
  阶段条停在 `RESOLVE`。反证四条各自命中。验收：14 任务 JVM 线 + detekt + lintDebug +
  assembleDebug 全绿。**边界**：真机未验；凭据守卫认参数名形态，不是 DLP。

## 最新追记（2026-10-10，批 89）

- [2026-10-10 · 批 89：Shizuku 反射面 ↔ R8 keep 对齐门 + 「`input tap` 挂 30s」成因订正](2026-10-10.md)：
  **① 守卫**：`:app` 新增 `ShizukuKeepRuleTest`，把「反射面」（`ShizukuInput` 的
  `Class.forName` / `getMethod` 字面量）与「R8 keep 规则」（`app/proguard-rules.pro`）
  **双向逐条比**，三条判据 —— 类名集合相等 / **方法 `(名字, 参数表)` 集合相等**
  （参数表是判据的一部分：2026-10-09 那个 bug 正是「名字对、签名错」）/ keep 的每条签名
  在真类上 `getMethod` 真解析（挡「两边一起错」）。**两个坑**：解析前先剥 proguard 注释行
  （注释里逐字引用了删掉的那条规则，不剥就是假红）；`proguard-rules.pro` 声明成
  `testDebugUnitTest` 的输入（不声明则改 keep 后任务 `UP-TO-DATE`、门静默不跑，实测复现）。
  **② 订正**：2026-10-09 记的「外审第 1 条与 `input tap` 挂 30s 两者各自独立 / 只有冷置后的
  第 1 条挂」被真机对照实验推翻 —— 打进**应用自己的窗口**时**每一条都挂**（连续 6 条
  32.4–33.8 s），机理是**同一件事**（`input` 的 `WAIT_FOR_FINISH` 要等目标窗口处理完，
  而主线程正卡在 `IRemoteProcess.waitForTimeout` 上）。证据 = ANR `logcat` +
  `/data/anr/anr_2026-10-10-07-13-35-268` 的 main 线程栈（逐帧吻合）。
  顺带订正测量口径：先前报的「延迟 2.4–2.5 s」是 `uiautomator dump` 的成本（~2.2 s），
  设备侧 tap 本身 0.038–0.081 s。

## 最新追记（2026-10-09，批 88 真机验证）

- [2026-10-09 · 批 88 真机验证：Shizuku adb 档 100% 不可用（已修）](2026-10-09.md)：
  用户在真机上（Android 13 + KernelSU，Shizuku 13.6.0.r1086）冒烟 —— `su`（root 档）正常，
  `shizuku`（adb 档）一条都跑不起来（`NoSuchMethodException: …IRemoteProcess$Stub$Proxy.
  waitForTimeout [long, class java.util.concurrent.TimeUnit]`）。**根因**：`5a03dc3` 把
  `newProcess` 改成「从公开接口取方法」时，**没有把同一条推理推广到它的返回值上** ——
  那个运行时类是**包内可见**的 AIDL proxy（且自己 override 了接口的每个方法），
  于是签名找不到（AIDL 上是 `(long, String)`）、流方法 `as? InputStream` 静默得 null、
  `destroy`/`exitValue` 抛 `IllegalAccessException`。**修法**：新增同文件
  `RemoteProcessApi`（每个方法从公开接口取）+ `ShizukuProcessReader`（读进程那一半搬出
  `ShizukuInput`）+ `waitForTimeout(long, "MILLISECONDS")`（设备 dex 反汇编核实）+
  流改 `PFD → AutoCloseInputStream` 且形状不符即抛 + `step()` 剥开
  `InvocationTargetException` + `ShizukuExecResult` 补 `truncated`（批 88 那条已知缺口销账）
  + proguard 删死代码规则、补 `IRemoteProcess`。**守卫**：`ShizukuRemoteProcessTest` 8 例
  （喂包内可见的 AIDL 仿真替身）+ `ArchitectureTest` 常量池字节门（已反证会红）。
  **边界**：真机复测待用户跑；13.6.0 服务端 AIDL 未逐字核对；两档流上限仍不统一。

## 最新追记（2026-10-09，批 88）

- [2026-10-09 · 批 88：控制台 shell 面（`su` / `shizuku` 特权模式，用户口径）](2026-10-09.md)：
  用户口径「也需要让控制台能执行 shell」+ 三条指定（`su` 进 root、`shizuku` 进 adb、`exit` 退出，
  「这部分说的是控制台的」）。批 84 第 2 项裁定当时写的是「只有 npm，不加 shell」—— 本条是
  **用户对该裁定的修订**：shell 进控制台，但**必须显式进模式**（不是「裸命令一律当 shell」）。
  `:domain` `NpmConsoleKeys.parse(line, mode)` + 枚举 `ShellConsoleMode` + 三个新命令变体；
  `HostSummary.runShellCommand` + `ShellConsoleResult`；`:app-service:npm` 新增
  `ConsoleShellRunner`（执行 + 渲染，独立一件）+ `ShellOpExecutor` 缝；`:platform:capabilities`
  `ShizukuInput.exec`（**并发**排空两条流）；`:app` `PlatformWiring.ConsoleShellExecutor`
  （**ADB 档换成 Shizuku**）；`:ui` `ConsoleCmdState.mode` + 模式徽标。
  **七条口径**：必须有模式、不许静默挑一条（root uid 与 shell uid 是两条不同身份的通道，
  静默挑 = 让「我以为我在用 root」不可分辨）/ `su <cmd>` 与 `su` 是两件事（带参数 = 就地跑那一条）/
  `DEFAULT` 一律拒且拒要落一行（先落 RESULT 再抛，拒绝不碰执行体）/ 非零退出是结果不是异常 /
  超时不渲染半截输出 / shell 面与依赖树无关（不建事务、不占安装会话、不碰项目锁）/
  `adb` 档 = Shizuku 且只在装配层换（`AndroidShellExecutor` 的 ADB 是**应用 uid**，
  改它会动到 a11y 输入注入那条路）。
  **顺手修两处**：`ShizukuInput.shizukuClass` 补接 `LinkageError`（JVM 实测
  `NoClassDefFoundError` 不是 `Exception`，会越过 `AutojsException` 直穿到调用方）；
  `NpmScriptResolver` 纯 JS 探测拆出 `PureJsProbe`（越 `TooManyFunctions` 线）。
  **边界**：真机未验（无设备，由用户自测）；**已知缺口**：adb 档输出被截到 4 KiB 时
  控制台不打「已截断」（root 档有真判据）。

## 最新追记（2026-10-09，批 87）

- [2026-10-09 · 批 87：依赖面板变更半边（§10.9 第 1 条）+ `npm-cache` 尺寸栏（第 5 条）](2026-10-09.md)：
  §10.9 第 1 条的「安装输入行 / 旗标 / 阶段进度条」此前登记为未落，前提是"没有执行体"；
  批 84 把 `InstallCoordinator.runConsoleCommand` 做出来之后这条前提**已经不成立**，
  剩下的是没人把面板接上去。本批补上输入行 + 两颗旗标（`-D` / 「离线优先」）+ 六档**阶段条**
  + 清单行上的「卸载」，以及第 5 条要的 `npm-cache` 尺寸栏。`:domain`
  `PackageManagerFacade.cacheStorage()` + `NpmProjectSnapshot.cache` + `HostSummary` 两条呈现面读口；
  `:app-service:npm` `InstallCoordinator.cacheStorage()`（只量 `content-v2`）+ 快照里缓存读数提到
  项目循环外；`:app` 两条生产实现；`:ui` `NpmInstallOps` + `InstallProgressState` + `InstallCard`
  + `QuotaCard` 里那一行缓存读数。
  **五条口径**：**走的是与控制台同一条宿主口**（`runNpmPanelCommand` → 同一个
  `runConsoleCommand`，门禁强度不取决于用户从哪个界面按下去）/ **进度是阶段不是百分比**
  （`InstallEvent.Progress.percent` 全仓从无赋值，画百分比条就是编一个拿不到的数 —— 这是对原文的
  **收窄**）/ 「离线优先」是 `--prefer-offline` 不是"仅离线" / 草稿失败不清（宿主先落 ECHO 行**再抛**）/ 换项目要把阶段条与游标一起归零（seq 环内全局
  单调，沿用旧游标会漏掉新项目 seq 更小的事件，阶段条会永远停在「进行中」）。
  **边界**：真机未验；进度是现取不是常驻轮询（通常只走到 `QUEUED`，点「刷新」续拉）。

## 最新追记（2026-10-09，批 86）

- [2026-10-09 · 批 86：依赖维护四颗按钮 + 缓存按 lock 闭包回收（§10.9 第 5 条动作半边）](2026-10-09.md)：
  §10.9 第 5 条此前只有尺寸/配额条那半截，配额满时把用户指去控制台敲 `npm prune`（路是通的，
  但不是一键）。本批补上动作面：依赖管理页配额条下面一行四颗按钮（`清理多余包`/`依赖去重`/
  `按 lock 重装`/`回收缓存`）。`:domain` `NpmMaintenanceAction`（三态）+ `NpmCacheReclaimReport`
  （六字段）+ `PackageManagerFacade.reclaimCache()` + `InstallHistoryOp.CACHE_RECLAIM`；
  `:app-service:npm` 新增 `NpmCacheReclaim`（按 lock 闭包回收 + **摘悬空 index 行**）+
  `InstallCoordinator.reclaimCache()`（保留集 = **所有项目** lock 并集，读不出的点名入史）；
  `:app` `HostSummary` 两条；`:ui` `NpmMaintenanceOps` + `MaintenanceCard`。**六裁定**：
  按 lock 闭包回收而非 `npm cache clean`（全清会把「按 lock 重装」变成必须联网）/ 保留集取所有
  项目并集（只看当前项目会毁掉别的项目的离线能力，用户看不见）/ op 名 `cache_reclaim` /
  **回收必须同时摘掉悬空 index 行**（实测 npm 10.9.8：悬空 index 让**在线** `npm install` 报
  `ENOENT … Invalid response body while trying to fetch` —— 缓存从「没用」变成「有害」且界面上
  看不出来）/ 认不出形状的条目一律保留、删不掉的不计入 removed / `CI` 那颗不绕过验签。
  **同批修一处真错**：`NpmCacheSeedDeployer.cacheRoot` 成为缓存目录的**唯一一份**判据（四处读者
  此前各拼各的、实测互不相同，后果是静默失效：`offlineGap` 恒报缺口、导入完 `ci --offline` 照样
  不命中）。口径见 [`design-decisions.md`](../design-decisions.md) 第 56 项。**边界**：真机未验；
  index 修复只在 npm 10.9.8 上实测过；`cacheDir/npm-cache-seed` 仍无生产部署路径。

## 最新追记（2026-10-09，批 85）

- [2026-10-09 · 批 85：审计页落地（§10.5-2）](2026-10-09.md)：§10.5-2 写的是「审计日志落 App
  **且可导出**」，实测复核发现**读口压根不存在** —— `InstallHistory` 一直在写（每次安装的成败、
  镜像源变更、快照导出、T1 放行…），而全仓非测试引用只有 `NpmShellKit` 那一处构造，**生产零消费方**。
  本批补上读侧、**不动落盘格式**（那是审计，改形状等于让历史行与将来行不可比）：`:domain`
  `InstallHistoryEntry`（五字段由落盘格式决定）+ `InstallHistoryOp`（已知操作名唯一一份；`op` 仍是
  `String`，取值域开放）+ `PackageManagerFacade.history()` → `HostSummary.npmHistory()` → `:ui`
  `AuditScreen`（入口在**依赖管理页顶栏**，不另立管理面板第五项）。三条呈现口径：**失败行不藏** /
  **未知 op 原样显示** / **筛选只影响显示**（宿主读口**无参**全量 —— 按项目筛会让 `registry` 那条
  `projectId` 空串的全局变更掉出去）。**同批补一条漏账**：`enqueueHeavy` 的磁盘/配额预检拒绝原先
  不入史（`crossCheckRegistry` 的拒绝一直在记），用户在审计页上会看到「什么都没发生」。
  **未落**：§10.5-2 的「**可导出**」那半截（SAF 通道没接，界面不画按钮）。口径见
  [`design-decisions.md`](../design-decisions.md) 第 55 项。

## 2026-10-09（批 84）

- [2026-10-09 · 批 84：控制台改做命令面（§10.9 第 3 条）+ 日志整体搬去「日志管理」](2026-10-09.md)：
  用户口径「控制台不是放系统日志的地方，是用来执行命令的，比如 npm」。控制台从「日志屏」改成**命令面**：
  选项目 + 敲一行 npm 命令 + 看输出；原控制台的日志内容**一行不少地**搬去管理面板 → 日志管理
  （三段：系统日志 / 脚本输出 / 任务日志）。判据唯一一份住 `:domain`（`NpmConsoleKeys`：`parse` 四形态 /
  白名单 `install·uninstall·ci·ls·list·prune·dedupe·audit` / 轻·重拆分 / `packageSpecsIn` / `gitSpecIn`）；
  输出粒度 = **事件流 + npm 输出尾部**（`HeavyOpOutcome.outputTail`，真流式 stdout **未落**）；
  `npm run`/`npx` **照实接线**到 §10.3 T1 门禁（未获批 → 已入队；获批但 spawn 桥未接 → `ERR_NOT_IMPLEMENTED`；
  **审批 ≠ 执行**，批完要重敲那一行）；读口 `HostSummary.runNpmCommand`/`consoleOutput`，**不经桥**。
  同批修 `ScriptPaths.PROJECT_ID` 放行 `.`（`resolve("..")` 正好跳出项目根）。口径见
  [`design-decisions.md`](../design-decisions.md) 第 54 项（第 40 项「控制台不动」已就地划掉）。
  **边界**：真机行为未验；命令历史不落盘。

## 最新追记（2026-10-09，批 83）

- [2026-10-09 · 批 83：镜像源管理（§10.9 新增第 8 条）+ registry 两条断链收口](2026-10-09.md)：
  管理面板「镜像源管理」落地 = **npm registry 全局配置面**，归 `:app-service:npm`，粒度**全局一份**
  （用户裁定），对应 §10.2 三层链的 `files/.npmrc`(userconfig) 那一层（此前零实现）。
  判据唯一一份住 `:domain`（`NpmRegistryKeys`，`NpmRegistryVerifier` 改为委托它、URL 字面量只剩一份）；
  解析链两层：项目 `.npmrc` → 全局 → 出厂官方，**交叉校验的首选与实际安装同源**（本批真正的交付物）。
  **同批修两条断链**（实测：npm 12.2.0 下 `--prefix` 一给，项目级配置只看 `prefix/.npmrc`）：
  `--registry` 因生产唯一构造点从不传参而永远钉官方；项目 `.npmrc` 因不拷进 workDir 而根本没被 npm 读到
  —— 净效果是 `setRegistry`/`config()` 写入侧生产上空转。口径见
  [`design-decisions.md`](../design-decisions.md) 第 53 项。**未做**：首启引导 ping 探测/镜像候选表、
  审计页、`proxy`/`cache-retention` 两键；真机网络连通性未验。

## 最新追记（2026-10-09，批 82）

- [2026-10-09 · 批 82：脚本全局环境变量（§8.1 新增契约）](2026-10-09.md)：
  管理面板「环境变量」落地。**契约面原本没有这一条**（`grep -rn "环境变量" docs/design/*.md`
  只命中 §11 的 token 段与 §13 的 apksigner 口令段），故本批是**新增契约**：`:domain`
  `ScriptEnvStore`/`ScriptEnvEntry`/`ScriptEnvKeys` + `:app-service:script-repo`
  `FileScriptEnvStore`（`files/.autojs/script-env.jsonl`）+ `NodeEngineConfig.scriptEnv: () -> Map`
  （**每次 spawn 现读**，用户键先写/宿主键后写 = 顺序即契约）+ `:ui` `ScriptEnvScreen`。
  四条口径：全局非按项目 / 每次 spawn 现读 / 不注入 npm 会话进程 / 拒收 `AUTOSCRIPT_` 保留前缀。
  口径见 [`design-decisions.md`](../design-decisions.md) 第 52 项。**边界**：真机行为未验；
  同组的「镜像源管理」仍是 toast 占位。

## 最新追记（2026-10-09，批 81）

- [2026-10-09 · 批 81：依赖面板 + 审批卡（§10.9.1 / §10.9.2）](2026-10-09.md)：
  实测复核先推翻了「UI 未排期」这条记账 —— 真问题是审批链**结构性地死了**
  （`resolveApproval`/`pendingApprovals` 零生产调用方、`ApprovalLedger()` 没传 store）。
  三件：`NpmShellKit` 审批账本缺省落盘；`:domain` `NpmPanelSnapshot` 读口 + `HostSummary`
  两条（读口**不经桥**）；`:ui` `NpmScreen` + `NpmState`（已装依赖 + 待审批两段，
  批准/拒绝走 `resolveApproval` = §10.5-2 人机分离的唯一生产落点）。
  口径见 [`design-decisions.md`](../design-decisions.md) 第 51 项。**边界**：安装半边
  （输入行/进度条/依赖树）未落；真机行为未验。



- [2026-10-09 · 批 80：`child_process` 拦截 shim 接线 + 零 spawn 金标准](2026-10-09.md)：
  `npm-spawn-gate.cjs`（classpath 资源）+ `NpmSpawnGate`（落 `files/.autojs/`、`NODE_OPTIONS=--require`
  追加注入、播报解析、码折叠）+ `HostNodeExecutor(spawnGateFile=…)`（门禁播报优先于退出码）；
  装配层**三条齐才注入执行体**（素材 + 宿主 + shim，落不上就不注入 = fail closed）。
  零 spawn 金标准落成 `NpmSpawnGateMatrixTest`（门禁下 P0 命令矩阵全绿 + 不注入也全绿的反向变异 +
  `npm run` 确实被拦），已进 `check-e2e-ran.sh`。本机 14 任务 JVM 线 + lint + assembleDebug 绿，
  去掉 `-PskipNpmE2E` 后 nightly 验尸四条真 npm 路径全 ✓。**边界**：不变量守卫，不是安全边界。

## 最新追记（2026-10-08，批 79）

- [2026-10-08 · 批 79：`lockKey` 生产接线（T2 防线生效）](2026-10-08.md)：
  `LockKeyStore.AndroidKeystore`（Keystore HMAC 密钥，get-or-create，「取不动」绝不静默重建；
  别名带 `v1` 版本号）+ `AppShellKit(npmLockKeys=…)` → `NpmShellKit.assembleHandler(lockKey=…)`；
  装配期就取一次钥匙，取不到则本次不装该防线、原因原文进 `AssembledShell.npmLockKeyFailure`（不掀翻装配）。
  接线后 `ci` 先验签、`install` 收尾重签、`exportSnapshot` 带 `snapshot.sig`。本机 14 任务 JVM 线 +
  `:app` detekt 绿，两处反向变异各自命中。**边界**：`AndroidKeyStore` 那层要真机才验得到。

## 最新追记（2026-10-08，批 77）

- [2026-10-08 · 批 77：录屏腿（`MediaRecorder`）+ §8.5 意图日志落 SQLite](2026-10-08.md)：
  `IntentStore` 迁 `:domain` + `SqliteIntentStore` 落 `:platform:system` + 两份实现共跑一套契约
  套件（正是它抓出「两索引覆盖不重叠」的幂等漏洞）+ 带原 runId 的一次性可重入迁移 + 失败回落 jsonl；
  `auto.screen.startRecording/stopRecording`（录屏产物落项目 `.recordings/`，**打包器会带上它**，如实记账）。
  集成树验收 14 任务 JVM 线 **1638/0/0/0** + detekt/lintDebug/assembleDebug + npm 212 通过 1 跳过 +
  三条生成物门零漂移。**A12 结项**；A11 仍开放。本日现 **4 条**（含补记）；下方批 76/75 的摘要保留，不覆盖。

## 最新追记（2026-10-08，批 76）

- [2026-10-08 · 批 76：来源分级裁定不做 + 契约面「MediaProjection 未落」措辞清账](2026-10-08.md)：
  `TrustTier` 四档与注入缝保留作记账词汇、不接元数据、不产生授权差异（理由：同 UID 同权下不产生防护）；
  契约面五处（§9.2 / §11.3 / §12.2 / §13 P1 / 06-modules）与实现注记三处清账；backlog 新增 A11/A12、
  A5/A10/D1 三行结项。**零生产代码改动**。本日现 **2 条**；下方批 75 的摘要保留，不覆盖。

## 最新追记（2026-10-08，批 75）

- [2026-10-08 · 批 75：执行级能力掩码与跨脚本授权（A5 第一阶段）+ MediaProjection 高清会话（§9.2）](2026-10-08.md)：
  桥路由 deny-by-default 判据、单一决策链杀 TOCTOU、跨脚本不得提权、命名通道按执行私有、
  `workManager.create` 缺接缝即全拒（缺省保守档 A）；投屏一次性同意 + API 34+ 顺序 + 幂等释放 +
  会话资源随连接终结收口，306s 措辞订正为「FGS 消除上限、不做自动续期」。
  本日现 **1 条**；下方历史索引及摘要保留，不覆盖批 74/73/72 的原摘要。

> [`design-status.md`](../design-status.md) 的**流水段**按日期拆成的切片，2026-10-02 分片
> （backlog C6）。**逐字搬入，只追加纪律不变**：新条目加在**对应日期文件的顶部**（新日期就新建
> 一个文件），并把 [`design-status.md`](../design-status.md) 目录表里那一行的条目计数同步 +1。
> 被推翻的记账不删除，原地标 `~~作废（日期 + 原因）~~`。
>
> **为什么拆**：单文件 175 KB 时「加一条」要动一个所有人都要读的文件，且 § 锚与条目文本在同一个
> 巨大的 blob 里。拆完：当前状态页只留**接口期表 + 目录**（十几 KB），历史条目按日期分文件。
> **§ 号仍是唯一权威锚**，不随文件位置变化；分卷正文里指回台账的链接一条都没改。

## 切片

**按日期的汇总表不在这里**（原有一份与 [`design-status.md`](../design-status.md) 的流水目录
逐行同构，2026-10-06 去重后只留那一份，且两份的条目计数已经漂过一次）。本文件只做**逐条索引**。

## 逐条索引

**一条一行、只留摘要与指针**：完整叙事在切片文件里，这里不复述。
（本表与切片文件的 `###` 标题一一对应，条数写在每个日期的小标题里。）

### [2026-10-10](2026-10-10.md)（3 条，最新在最上）

- 批 91：T1 spawn 桥本体 —— `npm run`/`npx` 第一次真跑起来（shim 经 unix socket 把 spawn 报给宿主、宿主起真进程、stdio 假管道回填；只放行异步三兄弟，`detached` 当场拒；两条 shim 互斥）。最贵的一课：`Channels.newInputStream/newOutputStream` 共用 `blockingLock()` → 双向协议被流包装锁成单向（挂死且报错面只有「超时」），改直读 `SocketChannel` 即绿；同批修握手死锁与 socket 保活（`refWhileBusy`）。守卫 16 例；欠账如实记（不用引擎池 / 无最小掩码 / 无输出流式 / 杀树有洞）
- 批 90：控制台「活着」三件 —— 真流式 stdout（读流线程 + `OutputSink` 缝；流真报过就不补 `outputTail` 行；空行跳过）+ 常驻进度推送（`:ui` `LivePoll.pollWhile`，提交后跟到跑完 / 子页在前台时一直跟；刻意不用无重放的 `progress` SharedFlow；有界 + 只依据宿主给的事实 + 每轮同一读口）+ 命令历史落盘（`ConsoleHistory`，与审计史纪律相反、按项目分开、凭据形态的行整条不记、写口在 `:ui` 派发点）；同批修「`events.tryEmit` 没经 `emit`」—— 执行体独有的 `DOWNLOAD`/`REIFY` 从未进事件环（探针测出，反证会红）
- 批 89：Shizuku 反射面 ↔ R8 keep 对齐门（`ShizukuKeepRuleTest`，三条判据双向比；先剥 proguard 注释行、`proguard-rules.pro` 声明成测试输入 —— 不声明则门静默不跑，实测复现；反证三条各自命中）+ 「`input tap` 挂 30s」成因订正（真机对照实验推翻「两者各自独立 / 只有冷置第 1 条挂」：打进应用自己窗口时每一条都挂，6 条 32.4–33.8 s；机理同一件事）+ 测量口径订正（延迟 2.4–2.5 s 是 `uiautomator dump` 的成本，设备侧 tap 本身 0.038–0.081 s）

### [2026-10-08](2026-10-08.md)（4 条，最新在最上）

- 批 77 补记：CI 首轮红在 `:app:testDebugUnitTest`（录屏接线用例写死 `/data/app/files`，`createDirectories` 真建目录；CI 非 root 建不动、本机 root 反在宿主根建出 `/data`）→ 改 `@TempDir` + 期望值现算，重跑七门全绿
- 批 77：录屏腿（MediaRecorder + VirtualDisplay）+ §8.5 意图日志落 SQLite（接口迁 `:domain`、共享契约套件抓出幂等索引漏洞、带原 runId 一次性迁移、失败回落 jsonl）；集成树 1638/0/0/0 + 全门绿；A12 结项
- 批 76：来源分级裁定不做（记账词汇，不接元数据）+ 契约面「MediaProjection 未落」五处措辞清账（截屏已落、录屏仍缺）+ backlog A11/A12 入池、A5/A10/D1 结项

- 批 75：执行级 `CapabilityMask` 与跨脚本授权（A5 第一阶段）+ MediaProjection 高清会话；§13/§16 的 306s 措辞订正为不做自动续期。验收 1562/0/0/0 + 全门 EXIT=0

### [2026-10-07](2026-10-07.md)（8 条，最新在最上）

- 批 73：B15 真 Node PID 测试改为 loopback 退出握手，定向连续 10 次通过；批 72：宿主日志双写（A10②，28 处调用 + 启动缓冲 + 换壳接线）与收集器并发游标修复；A10① 留待协议变更
- 批 71：APK 体积预算 `≤ 40MB` → `≤ 150MB release`（用户裁定；§15 表改数、旧实测账一字未删、`InstallSizeRead` 链零改动）
- 批 70：日志管理页（系统日志 = 控制台 `runId==0` 行 + 任务日志 = 全部项目终态历史，读口 `projectHistory` → `taskLog()`）+ 项目页历史入口拿掉 + 同 PR 三条修（B11 stderr 排水竞态 / 编辑器严格 UTF-8 / 测试替身线程安全）+ 补记 #46 的四条（tree-sitter 高亮从没进过 APK、NDK 版本码漂移、pager bring-into-view 抽动）
- 批 66：冻结面门（下游 PR 不许改契约面/台账面，`check-frozen-paths.sh` + `ci.yml` 的 `frozen-paths` job）+ `docs/` 收进 CODEOWNERS
- 批 65：PR #44 外审 —— release 形态 R8 把 Shizuku 反射面删光（补 `-keep`）+ `newProcess` 改从公开接口取 + `docs/` 台账被覆盖的复原（PR 六条重编号 64–69）
- 批 64：引导文案改成「与三态无关」（A8 结项）+ `ADB_INPUT` 三态随批 61 修正 + 大文件余量收口（D7 结项）
- 批 63：`NOTICE` 措辞核实不做（A9 结项）+ 上游核对抽查留档

### [2026-10-06](2026-10-06.md)（20 条，最新在最上）

- 批 69：编辑器捏合不再每帧重排（缩放跟手）+ 跳转钮改直角箭头/数字骑边线滚轮 + release 形态「性能包」
- 批 68：文件日期改 yy-MM-dd HH:mm + 夜间顶栏/菜单/底栏统一 #161E27 + 编辑器不折行（横向滚动）与双指缩放
- 批 67：编辑器行号槽 + 点空白落文末 + 右下跳转钮（气泡数屏幕外行数）+ 夜间屏底改 #161E27 + 项目页行间分割线
- 批 66：Telegram 源码入库（gitignore）+ 夜间模式配色/切换动画按 TG 对齐 + 三处 ⋮ 字重 + 编辑态收底栏与键盘 + 三处流畅度
- 批 65：修好点文件进编辑（relPath 口径）+ 子钮改圆 + 主题切换圆形揭示
- 批 64：项目页前端（FAB 按压档按形状裁 / 子钮回 48dp 图标 / 点文件进文本编辑 + 行尾「更多」/ 锁竖屏去旋转钮）
- 批 62：`ui/` 定性更正（「不是衍生，只是抄了 UI」）+ 许可正文随包（`LICENSE`/`NOTICE` 进 `assets/third-party/`，8 → 10 件）
- 批 61：输入通道三选一（`auto`/`adb`/`root` 平级、必须显式、绝不降级）+ 引入 Shizuku
- 批 60：B13 `auto.zip.extract` 体积上限（与 B12 同一个洞）
- 批 59：裁定不做 16KB 页真机测试（B3/E3 的 16KB 那一半结项）
- 批 58：B12 离线 bundle 体积上限（两道闸 + 可注入的测试缝）
- 批 57：冗余注释与文档去重（审计 → 应用两阶段）
- 批 55：真机引擎冒烟（直接调 `libnoden.so`，不经 app 前端）
- 批 54：B5 真形态 APK 门 + B10 ABI 收口 + SDK 基线对齐
- 批 53：B11 引擎 stderr 捕获 + 崩溃摘要归档
- 批 56：第三轮外审整改（先证伪，再改三条）
- 批 52：B6 覆盖率报告 + B8 静态分析（全走约定插件）
- 批 51：B9 随包 npm 素材端到端对账
- 批 50：A7 取消语义与安装收尾
- 批 49：设置页两组纯文字 + 「权限」改名「权限列表」

### [2026-10-05](2026-10-05.md)（10 条，最新在最上）

- 批 48：设置页「权限」入口进子页 + 补列使用情况访问权限
- 批 47：设置页按 TG 重写 + 管理分组去横线
- 批 46：管理面板重写 + 控制台改为独立入口
- 批 45：任务中心顶栏去副标题 + 两组缺省收起
- 批 44：任务中心改名 + 两处间距对齐 TG + 空组可收放 + 回执改浮层
- 批 43：搜索框几何再纠偏（补第二层内缩）+ 首行间距 + 首页搜索框对齐
- 批 42：搜索框尺寸修正 + 搜索框下空隙 + 两组拆两卡
- 批 41：任务栏按用户口径收窄（九处界面裁定）
- 批 40：任务栏重做（TG 联系人页复刻）
- 批 39：拆掉顶栏底部的全宽分割线

### [2026-10-04](2026-10-04.md)（6 条，最新在最上）

- 批 38：菜单开/关动画 + 间隙 + 描边按 TG 重做
- 批 37：菜单圆角精确到12dp，修复主题切换死区及多处UI细节
- 批 36 勘误：底栏高亮块的底色被 blend 成了灰（用户实测点名）
- 批 36：底栏整条按 TG 重做（不等宽分格 / 三键各自 blend / 删掉自加的按压态）（分支 `electric…
- 批 35：拆掉下拉刷新 —— 参考项目没有这个手势（分支 `electric-crocodile`）
- 批 34：项目页「一层一层走」—— 目录下钻 / 选择模式 / 底部操作面板（分支 `electric-crocodil…

### [2026-10-02](2026-10-02.md)（19 条，最新在最上）

- 批 19：冷启那两行红字不会自己变绿 —— 首屏加一条**有界**的重问（分支 `rich-owl`）
- 批 18：`:ui` 三处返工 —— 文字顶到状态栏、底栏抄错了 TG 的哪一条栏、切页每帧重组（分支 `rich-ow…
- 批 17：保活服务的**进程级**真错 —— 装配层投的 Intent 不带契约，服务只能拒收，代价是系统连整个进程一起…
- 批 16：本机出「真形态」APK（引擎三件随包）+ 随包资产被静默剪裁的真错（分支 `rich-owl`）
- 批 15：TG 的手势与动效补齐（页面切换 / 删除粒子 / 下拉刷新 / 长按菜单）（分支 `rich-owl`）
- 批 14：命名去 `Tg` 前缀 + 补齐 TG 的三处动效（分支 `rich-owl`）
- 批 13：许可改 GPL-2.0-or-later + `:ui` 前端重构（Telegram 质感 · 深浅双主题）（…
- 批 12（D5 + B7 + D14）：贡献门槛拆分 · INTERNET 权限真机 A/B 冒烟 · ModuleGr…
- 结构面批（backlog D9 + D10 + D12）：`HostNpm` 两份并成一份 · `bridge/READ…
- 文档面改进批（backlog C10 + D2 + D7-②）：契约侧过期叙述订正 · 状态页可扫性 · 未归位参考件入…
- 第二轮外审处置：能证伪的先证伪（INTERNET 影响面收窄）· 立刻改 1 条（README 如实警告）· 登记 ba…
- 批 10：C7 脚本 API 参考（typedoc 生成物入库 + 零 diff 门）+ B4 依赖供应链面（分支 `h…
- 批 9：A6+E4 落地 —— vendored npm 素材换 registry `npm@12.2.0`（脊梁 12…
- E5 拍板：48×48 形态判据放宽 <100ms（64.43ms 接受转绿），E2 全收口（分支 `hellish-s…
- E2 落地：`matchTemplate` 拆 std/相位双门 + 负结果精确兜底（backlog E2 裁决「修」；…
- C9 落地：`docs/README.md` 总索引（「文档边界」表搬家，不是并列）（分支 `hellish-shrim…
- C6 落地：`design-status.md` 拆「当前状态页 + 按日期流水切片 + 实现注记」（分支 `helli…
- A2b 拍板落地：shell 捕获输出超限改「静默截断 + Warning + 截断标志」（分支 `hellish-sh…
- E1 拍板落地：接受 APK 超支 + 能力中心明示实测安装体积（分支 `hellish-shrimp`）

### [2026-10-01](2026-10-01.md)（17 条，最新在最上）

- CI 红的两例 AppShellTest：裁决输入借了宿主 `/proc`（分支 `hellish-shrimp`）
- 批 7（三项 S 级）：D8 许可声明 / D6 命名面 / D1 模块归属（分支 `hellish-shrimp`）
- 批 6：**D3/D5 platform 子包对齐 + D7 大文件拆分**（分支 `hellish-shrimp`）
- 批 5：**B1 CI 覆盖收口**（Android Lint / APK 构建进 PR 门；真 npm E2E 进 n…
- 批 4 后半：**A1 npm 生产装配接线收口**（素材随包 → 启动期落位 → 注入执行体；分支 `hellish-…
- 批 4 前半：**A1c 接缝形状**（`secretKey(): SecretKey`；分支 `hellish-shr…
- backlog **C2** 收口（机器路径出跟踪文件；分支 `hellish-shrimp`）
- C4 收口（维护者已开通 GitHub 私密上报；分支 `hellish-shrimp`）
- 待办池**批 3**（C1 / C5；C4 复核；分支 `hellish-shrimp`）
- 待办池**批 2**（B2 / C3 / D4 / D2；分支 `hellish-shrimp`）
- 待办池**批 1**（A2 / A3 / A1b / A4；分支 `hellish-shrimp`）
- 第二次外审：建议落进新建的 **[`docs/backlog.md`](../backlog.md)**（待办池）
- 外审整改·文档侧收尾 + 四处稳健性修复（5+1+4；`0e42ed3`…`08e89a6`，分支 `hellish-s…
- npm P1 T1 放行门禁的**存盘移植**（`node-slice` 两提交 → `feat/npm-t1-life…
- §8.5/§8.6 收口：无人 await 的 run 自带期限（`feat/fastpath-16x`）
- 三大形态修复：matchTemplate 大模板 / findFeature 恒假 / findColor 全帧（com…
- FastPath 12a + 场景端粗筛缓存（commits `a22fbfd`/`8d20500`，PR #13）

### [2026-09-30](2026-09-30.md)（15 条，最新在最上）

- 相位探针门 + 自适应 K（评审二轮原型移植，commit `0ec6ef4`，PR #12）
- 评审 patch 验证轮 → 采纳（commit `852fb45`；粗筛下限 48px + kMinCoarseSid…
- `images` 匹配提速：金字塔粗筛 + `region` + 计算出锁（评审拍板案，两提交）
- A2–A4 优化后真机复测（同日第二次；run1 金字塔 vs run2 强制精确 A/B）
- A2–A4 真机性能实测（恢复自挂起；云手机 Android 13/API 33/arm64/4KB，OpenCV 4.…
- 外部审查整改·步骤 8：framework-design.md 拆 12 卷 + 机器读者/文档漂移修缮
- 外部审查整改·步骤 7：单一 schema 生成契约 + dist 出库
- 外部审查整改·步骤 6：platform 按能力重组 + Wm/Power 移出 `:app`（零新模块，模块表 15…
- 外部审查整改·步骤 5：拆 `:app-service:npm`（模块 14 → 15）
- 外部审查整改·步骤 3：JSON 只留一个（DomainJson 合一）
- 外部审查整改·步骤 1：机器路径 18 处清零 + 原生暂存入约定 + `:engine:sandbox` 空壳摘除
- 外部审查整改·步骤 4：删 \*Lite + RpcNamespaceHandler 基类承接解码与错误映射
- 外部审查整改·步骤 2/4 先行：build-logic 约定插件 + JVM 插件纠偏 + 共享架构门
- A 组第一批实测（云手机，Android 13 / API 33 / arm64 / PAGE_SIZE=4096）
- 等设备的那笔账：真机红测可执行清单（未执行，只列账）

### [2026-09-29](2026-09-29.md)（1 条，最新在最上）

- 真机垂直切片红测（非 root shell，Android 13 / API 33 / arm64-v8a / PAGE…
