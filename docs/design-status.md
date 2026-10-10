# AutoScript 落地状态台账

> **2026-10-10 · 批 89（Shizuku 反射面 ↔ R8 keep 对齐门 + 「`input tap` 挂 30s」成因订正）**：
> 本批两件，均已并入 `main`。**① 守卫**：`:app` 新增 `ShizukuKeepRuleTest`，把「反射面」
> （`ShizukuInput` 里的 `Class.forName` / `getMethod` 字面量）与「R8 keep 规则」
> （`app/proguard-rules.pro` 的 `-keep class/interface` 块）**双向逐条比**，三条判据：
> 类名集合相等 / **方法 `(名字, 参数表)` 集合相等**（参数表是判据的一部分 —— 2026-10-09
> 那次真机 bug 正是「名字对、签名错」，只比名字照样绿）/ keep 的每条签名在真类上
> `getMethod` 真解析一遍（挡「两边一起错」）。**两处必须记住的坑**：`proguard-rules.pro`
> 解析前先剥注释行（本文件里有一段注释逐字引用了 2026-10-09 删掉的那条
> `ShizukuRemoteProcess` 规则，不剥就是假红，第一次跑出来的正是它）；`proguard-rules.pro`
> 必须声明成 `:app:testDebugUnitTest` 的**输入**（Gradle 看不见测试里那次 `Files.readAllBytes`，
> 不声明则改完 keep 该任务照样 `UP-TO-DATE`、门静默不跑 —— 实测复现过，**假门比没门更坏**）。
> 反证三条各自命中（签名改 `TimeUnit` / 整条删 `IRemoteProcess` / 源集加一处新反射不保）。
> **② 订正**：2026-10-09 的记账把外审第 1 条与「首次 `input tap` 挂 30s」写成**两件各自独立
> 的事**、并给了「只有冷置后的第 1 条挂」这个特征描述 —— 真机对照实验**两条都推翻**：
> 修复前构建上打进**应用自己的窗口**时**每一条都挂**（连续 6 条 32.4–33.8 s，全部超时），
> 且机理是**同一件事**（`input` 默认 `WAIT_FOR_FINISH`，事件投进哪个窗口就要等那个窗口
> 处理完；主线程正卡在 `IRemoteProcess.waitForTimeout` 上收不了 → 陪等到 30s）。
> 证据链 = `logcat` 的 `ANR … Input dispatching timed out (Waited 5005ms for KeyEvent … ENTER(66))`
> + `/data/anr/anr_2026-10-10-07-13-35-268` 的 main 线程栈（与那条链**逐帧吻合**）。
> **顺带一笔（测量口径）**：此前报的「`input tap` 延迟 2.4–2.5 s」是**测量工具的成本**
> （`uiautomator dump` 自己就 ~2.2 s），设备侧实测 tap 本身 0.038–0.081 s、回车→开跑 0.08 s
> —— 「绝对 ms 不是常量」那条教训的第二次现形。**边界**：守卫只覆盖 `ShizukuInput` ↔
> `proguard-rules.pro` 这一对（另两处反射保的是平台类 / `java.lang.Process`，名字不会变）；
> 真机验证积压五件仍挂着，本批**没有**新增真机验证。细节见
> [`log/2026-10-10.md`](log/2026-10-10.md)。

> **2026-10-09 · 批 88 真机验证（Shizuku adb 档 100% 不可用 —— 已修）**：用户在真机上
> （Android 13 + KernelSU，Shizuku 13.6.0.r1086）冒烟，`su`（root 档）正常、`shizuku`（adb 档）
> 一条都跑不起来（`NoSuchMethodException: …IRemoteProcess$Stub$Proxy.waitForTimeout
> [long, class java.util.concurrent.TimeUnit]`）。**根因**：`5a03dc3` 把 `newProcess` 改成
> 「从公开接口取方法」时，**没有把同一条推理推广到它的返回值上** —— `drain`/`drainBoth`/`pump`
> 一律走 `process.javaClass.getMethod(...)`，而那个运行时类是**包内可见**的 AIDL proxy
> （`IRemoteProcess$Stub$Proxy`，且它自己 override 了接口的每个方法）。三处后果：
> ① `waitForTimeout` 的 AIDL 签名是 `(long, String)` 而非 `(long, TimeUnit)`（后者只在
> 本应用**永远拿不到**的 `rikka.shizuku.ShizukuRemoteProcess` 上）；② 流方法返回
> `ParcelFileDescriptor` 而非 `InputStream`，`as? InputStream` **静默**得 null（症状是
> "输出栏什么都没有"，比 ① 更隐蔽）；③ `destroy`/`exitValue` 抛 `IllegalAccessException`。
> **修法**：新增同文件 `RemoteProcessApi`（每个方法从公开接口取一次）+ `ShizukuProcessReader`
> （读进程那一半整体搬出 `ShizukuInput`；两者失败话术不同，且 detekt 对 `object` 的函数数
> 上限更严）；`waitForTimeout(long, "MILLISECONDS")`（单位名经设备 13.6.0 `classes.dex`
> 反汇编核实：服务端 `TimeUnit.valueOf(unit)`）；流改 `PFD → AutoCloseInputStream`、
> 两流并发读、`finally` 幂等关流、**形状不符即抛**；`step()` 剥开 `InvocationTargetException`；
> **`ShizukuExecResult` 补 `truncated`**（批 88 登记的那条已知缺口就地销账，口径 = 两流各自判
> `or` 成一个数）+ `PlatformWiring` 一起搬；`proguard-rules.pro` 删掉保死代码的
> `ShizukuRemoteProcess` 那条（它保的签名还正好掩盖了本 bug）、补
> `-keep interface moe.shizuku.server.IRemoteProcess`。**回归守卫**：`ShizukuRemoteProcessTest`
> 8 例（喂**包内可见**、逐个 override 接口方法的 AIDL 仿真替身）+ `ArchitectureTest`
> 新增「Shizuku 反射只许 `ShizukuInput` 一处」（门落在**生产类常量池字节**上，点分与斜杠
> 两种形态都要找 —— 只找斜杠时新抄一份 `Class.forName` 照样绿，实测过；已反证会红）。
> **边界**：真机复测待用户跑（`id` / 失败命令带 stderr / 大输出不死锁 / 超时 / a11y 的 ADB
> 输入注入 —— 最后这条**从来没成功过**）；13.6.0 服务端 AIDL 未逐字核对（上述签名事实来自
> 设备 dex 反汇编）；adb 档流上限 4 KiB 与 root 档 1 MiB **仍不统一**（统一要 `:domain` 出共享常量）。
> 细节见 [`log/2026-10-09.md`](log/2026-10-09.md) 与 [`backlog.md`](backlog.md)。
>
> **2026-10-09 · 批 88（控制台 shell 面落地 —— `su` / `shizuku` 特权模式，用户口径）**：
> 用户口径「也需要让控制台能执行 shell」+ 三条指定（`su` 进 root、`shizuku` 进 adb、`exit` 退出，
> 「这部分说的是控制台的」）。批 84 第 2 项裁定当时写的是「只有 npm，不加 shell」—— 本条是
> **用户对该裁定的修订**：shell 进控制台，但**必须显式进模式**（不是「裸命令一律当 shell」）。
> 六件：① `:domain` `NpmConsoleKeys.parse(line, mode)` + 枚举 `ShellConsoleMode`（`DEFAULT`/`ROOT`/`ADB`）
> + 三个入口词常量 + 三个新命令变体（`Shell`/`EnterMode`/`ExitMode`）；
> ② `:domain` `HostSummary.runShellCommand` + `ShellConsoleResult`（四字段）+ `PackageManagerFacade` 同形缺省体；
> ③ `:app-service:npm` 新增 `ConsoleShellRunner`（执行 + 渲染，**独立一件**）+ `ShellOpExecutor` 缝
> + `InstallCoordinator.runShellCommand` + `consoleShellTimeoutMillis` 构造参数；
> ④ `:platform:capabilities` `ShizukuInput.exec`（**并发**排空两条流，各留 4 KiB 尾部）；
> ⑤ `:app` `PlatformWiring.ConsoleShellExecutor`（**ADB 档换成 Shizuku**）+ `asShellOpExecutor()` 转接
> + `AppShellKit` 透传 + `AppShellApplication.runShellCommand`；
> ⑥ `:ui` `ConsoleCmdState.mode` + `ConsoleCmdOps` 进/退模式与 `dispatch` 分流 + `ConsoleScreen` 模式徽标。
> **七条口径**：**必须有模式、不许静默挑一条**（root uid 与 shell uid 是两条不同身份的通道，
> 静默挑 = 让「我以为我在用 root」不可分辨 —— §9.3 同一条纪律；进模式后徽标常驻可见）/
> **`su <cmd>` 与 `su` 是两件事**（带参数 = 就地跑那一条，不切模式）/ **`DEFAULT` 一律拒且拒要落一行**
> （先落 RESULT 再抛；拒绝路径不碰执行体）/ **非零退出是结果不是异常**（照原样进 RESULT，`ok=false`；
> 执行体抛错才走异常）/ **超时不渲染半截输出**（`ERR_TIMEOUT` 且一行不落 —— 画「退出码 N」就是编一个
> 没发生过的退出；`CancellationException` 原样上抛）/ **shell 面与依赖树无关**（不建事务、不占安装会话、
> 不碰项目锁）/ **`adb` 档 = Shizuku 且只在装配层换**（`AndroidShellExecutor` 的 `ShellMode.ADB` 是
> **应用 uid**，改它会动到 a11y 输入注入那条路，故两边都不动）。
> **顺手修两处**：`ShizukuInput.shizukuClass` 补接 `LinkageError`（JVM 单测实测抛
> `NoClassDefFoundError: Could not initialize class rikka.shizuku.Shizuku`，它**不是** `Exception`，
> 原来会越过 `AutojsException` 直穿到调用方；刻意不写 `catch (Throwable)`）；
> `NpmScriptResolver` 的纯 JS 探测拆出 `PureJsProbe`（解析器已 11 个函数、越 `TooManyFunctions` 线）。
> 验证见 [`log/2026-10-09.md`](log/2026-10-09.md)。**边界**：真机行为**未验**（无设备，按纪律由用户自测）；
> **已知缺口**：`ShizukuExecResult` 无 `truncated` 字段 → adb 档输出被截到 4 KiB 时控制台**不会**
> 打「已截断」（root 档有真判据，那一档会打）。
>
> **2026-10-09 · 批 87（§10.9 第 1 条变更半边落地 —— 依赖面板的安装输入行/旗标/阶段条 + 第 5 条 `npm-cache` 尺寸栏）**：
> §10.9 第 1 条要的「安装输入行 / 旗标 / 阶段进度条」此前登记为未落，理由是"没有执行体"；
> 批 84 把 `InstallCoordinator.runConsoleCommand` 做出来之后这条前提**已经不成立** ——
> 剩下的是没人把面板接上去。本批补上：输入行 + 两颗旗标（`-D` / 「离线优先」）+ 六档**阶段条**
> + 清单行上的「卸载」；第 5 条要的 `npm-cache` 尺寸栏（`cacheStorage()` 读口，只量 `content-v2`）
> 一并画进 `QuotaCard`（与 node_modules 那行各自独立判空）。
> 五件：① `:domain` `PackageManagerFacade.cacheStorage()` + `NpmProjectSnapshot.cache`
> + `HostSummary.runNpmPanelCommand()` / `npmInstallEvents()`（两条呈现面读口，都不经桥）；
> ② `:app-service:npm` `InstallCoordinator.cacheStorage()`（IO 调度器上量 `content-v2`）
> + 快照里缓存读数**提到项目循环外**（全机一份，按项目重算 N 次是白烧 IO）+ `CacheIndex.contentBytes()`
> 默认体（`fun interface` 保持 SAM，`CacacheIndex` 覆盖）；
> ③ `:app` 两条生产实现（`runNpmPanelCommand` 直接委托 `runNpmCommand`）；
> ④ `:ui` `NpmInstallOps` + `InstallProgressState`（六档 + `ORDER`）+ `NpmState` 六字段两条派生
> + `NpmScreen` 的 `InstallCard` 与「卸载」按钮 + `MainActivity` 五条回调（`reloadNpm()` 兼作续拉）；
> ⑤ 测试 `NpmInstallOpsTest`（22 例）+ `CacacheIndexBytesTest`（4 例）+ `InstallCoordinatorTest` 两例
> + 契约方法面冻结补 `cacheStorage`。
> **五条口径**：**走的是与控制台同一条宿主口**（`runNpmPanelCommand` → 同一个 `runConsoleCommand`：
> 同一份判据、同一套装前多镜像交叉校验、同一道磁盘/配额预检、同一把项目锁与全局安装会话 ——
> **门禁强度不取决于用户从哪个界面按下去**，新开一条"按 spec 装"的宿主口就是第二份安装入口）/
> **进度是阶段不是百分比**（`InstallEvent.Progress.percent` 全仓**从无赋值**，npm 进程内的 reify
> 是黑盒，宿主只在前后发得出三枚粗标记 —— **这条是对 §10.9 第 1 条原文的收窄，不是实现偷懒**；
> 原文那句「job 数」同样拿不到）/ **「离线优先」不是「仅离线」**（拼的是 `--prefer-offline`，
> 先查缓存、缺了仍联网；真断网要装上靠缓存里恰好有全部闭包，那是 `offlineGap` 答的问题）/
> **草稿失败不清**（宿主是先落 ECHO 行**再抛**的，清早了用户看到的是"一句失败 + 一个空输入框"）/
> **换项目要把阶段条与游标一起归零**（`seq` 是环内全局单调的、`drain` 才按 `projectId` 过滤，
> 沿用旧游标会漏掉新项目 seq 更小的那些事件 —— 包括 `Finished`，阶段条会永远停在「进行中」）。
> 验证见 [`log/2026-10-09.md`](log/2026-10-09.md)。**边界**：真机行为未验（无设备 ——
> 输入行在软键盘下的观感、阶段条在真安装里走不走得动，随下次真机冒烟一并看）；进度是**现取**
> 不是常驻轮询（`:ui` 每个读口都是"进页面/手动刷新时现取一次"），故阶段条通常只走到 `QUEUED`
> 那一格，点「刷新」续拉 —— 宿主把常驻轮询做出来之前，这是如实的。
>
> **2026-10-09 · 批 86（§10.9 第 5 条动作半边落地 —— 依赖维护四颗按钮 + 缓存按 lock 闭包回收）**：
> §10.9 第 5 条写的「一键 prune/dedupe/ci 重装/cache clean」，此前只有尺寸/配额条那半截，
> 配额满时把用户指去控制台敲 `npm prune`（路是通的，但不是一键）。本批补上动作面，**四颗按钮**
> 进依赖管理页配额条下面那一行 `MaintenanceCard`：`清理多余包`(prune) / `依赖去重`(dedupe) /
> `按 lock 重装`(ci) / `回收缓存`(reclaimCache)。
> 五件：① `:domain` 新增 `NpmMaintenanceAction`（`PRUNE`/`DEDUPE`/`CI` 三态）、`NpmCacheReclaimReport`
> （六字段）、`PackageManagerFacade.reclaimCache()`、`InstallHistoryOp.CACHE_RECLAIM`；
> ② `:app-service:npm` 新增 `NpmCacheReclaim`（按 lock 闭包回收 + **修 index 悬空引用**）
> 与 `InstallCoordinator.reclaimCache()`（保留集 = **所有项目** lock 闭包的并集，读不出的项目点名入史）；
> ③ `:app` `HostSummary.runNpmMaintenance`/`reclaimNpmCache` 两条生产实现；
> ④ `:ui` `NpmMaintenanceOps`（三落点）+ `NpmState` 两个忙碌位 + `NpmScreen` 的 `MaintenanceCard`；
> ⑤ **同批修一处真错**：`NpmCacheSeedDeployer.cacheRoot` 成为「npm 的缓存在哪」的**唯一一份**判据
> —— 此前四处读者各拼各的、实测互不相同（`--cache` 拿的是 `cacheDir` 本身、`CacacheIndex` 读
> `cacheDir/npm-cache`、bundle 导入落第三个目录），而 `cacheRoot` 自己是恒等映射却挂着相反的注释；
> 后果不是报错而是**静默失效**（`offlineGap` 恒报缺口、导入完 `ci --offline` 照样不命中）。
> **六裁定**：按 lock 闭包回收而非 `npm cache clean`（全清会把「按 lock 重装」变成必须联网，
> 用户 2026-10-09 裁定）/ 保留集取**所有项目**的并集（只看当前项目会毁掉别的项目的离线能力，
> 而用户在点按钮时看不见）/ 审计 op 名叫 `cache_reclaim` 不叫 `cache_clean`（名字跟着语义走）/
> **回收必须同时摘掉指向已消失 content 的 index 行**（实测 npm 10.9.8：悬空 index 让**在线**
> `npm install` 报 `ENOENT … Invalid response body while trying to fetch` —— 缓存从「没用」变成
> 「有害」且界面上看不出来）/ 认不出形状的条目一律保留、删不掉的不计入 removed（删读不懂的文件
> 是「猜」，猜错的代价是别人的离线能力）/ `CI` 那颗不绕过任何门禁（走 `facade.ci(offline = true)`，
> `lockSigner.verifyOrThrow` 照旧先跑）。
> 口径见 [`design-decisions.md`](design-decisions.md) 第 56 项；验证见 [`log/2026-10-09.md`](log/2026-10-09.md)。
> **边界**：真机行为未验（无设备）；index 修复的实测证据来自本机 npm 10.9.8（随包 npm 是 12.2.0，
> 桶格式同源但未在该版本复跑）；`cacheDir/npm-cache-seed` **仍无生产部署路径**（`deploy` 零生产调用方，
> 素材也不存在 —— 已登记 backlog）。
>
> **2026-10-09 · 批 85（§10.5-2 审计页落地 —— `InstallHistory` 从「零消费方」转为有读口）**：
> §10.5-2 的原话是「审计日志（approve/registry 变更/lock 重签）落 App **且可导出**」，而
> `InstallHistory` 一直在写（每次安装的成败、镜像源变更、快照导出、T1 放行…）却**全仓零 UI 消费方**
> —— 那句话只有前半截成立。本批补上读侧，**不动落盘格式**（那是审计，改形状等于让历史行与将来行不可比）。
> 四件：① `:domain` 新增 `InstallHistoryEntry`（五字段，**由落盘格式决定**）+ `InstallHistoryOp`
> （已知操作名的**唯一一份**；`op` 仍是 `String`，取值域开放）+ `PackageManagerFacade.history()`；
> ② `:app-service:npm` `InstallCoordinator.history()` 实现 + `InstallHistory.Op` 改为指向 `:domain` 的
> **别名**（`:ui` 看不见本模块，字面量抄两份必然漂）+ **补记 `enqueueHeavy` 的磁盘/配额预检拒绝**
> （原先不入史，而 `crossCheckRegistry` 的拒绝一直在记 —— 用户在审计页上会看到「什么都没发生」）；
> ③ `:app` `HostSummary.npmHistory()`；④ `:ui` `AuditScreen`/`AuditState`/`AuditOps`，入口在
> **依赖管理页顶栏**那颗「审计」（不另立管理面板第五项）。
> **三条呈现口径**：失败行不藏（审计要能回答「用户当时看到成功了吗」）/ 未知 op 原样显示
> （不编「未知操作」）/ 筛选只影响显示（宿主读口**无参**全量 —— 按项目筛会让 `registry` 那条
> `projectId` 空串的全局变更掉出去，而它恰恰是审计最该看见的）。
> **边界**：**「可导出」仍未落**（SAF 选目录 + 写文件那条通道没接，故界面**不画导出按钮**）；
> 真机行为未验（无设备）。
>
> **2026-10-09 · 批 84（§10.9 第 3 条「npm 终端视图」落地 —— 控制台改做命令面）**：用户口径
> 「控制台不是放系统日志的地方，是用来执行命令的，比如 npm。或者装的一些依赖会有命令」。控制台页
> 原先全是 §7.3 那条 `HostSummary.console` 读口的呈现，与批 70 的「日志管理」页职责重叠（第 40 项
> 当时把控制台记成「不动」）。本批五件：① `:domain` `NpmConsoleKeys`（命令行的**唯一一份**判据：
> `parse` 四形态 / `SUBCOMMANDS` 白名单 / 轻·重拆分 / `packageSpecsIn` / `gitSpecIn` / `rejectProjectId`）
> + 四个新 DTO（`NpmConsoleHandle`/`NpmConsoleSnapshot`/`SequencedConsoleLine`/`NpmConsoleLine` +
> 枚举 `NpmConsoleLineKind`）；② `:app-service:npm` `InstallCoordinator.runConsoleCommand`/`consoleOutput`
> + 第二条 `SeqRing<NpmConsoleLine>`（512）+ `HeavyOpOutcome.outputTail`（npm 输出尾部）；
> ③ `:app` `HostSummary` 两条读口（**不经桥**，桥面一条不加）；④ `:ui` 控制台重写成命令面
> （`ConsoleCmdState`/`ConsoleCmdOps`/`ConsoleScreen`）+ 日志整体搬去「日志管理」（三段：
> 系统日志/脚本输出/任务日志）；⑤ 顺带修 `ScriptPaths.PROJECT_ID` 放行 `.` 的判据错
> （`resolve("..")` 正好跳出项目根）。
> **六裁定**：日志搬去日志管理一行不少 / 只认 npm 与 npx（不加 sh）/ 子命令白名单 /
> 输出粒度 = 事件流 + npm 输出尾部（**真流式 stdout 未落**）/ 依赖提供的命令照实接线到 T1 门禁
> （审批 ≠ 执行，批完要重敲那一行）/ 判据唯一一份住 `:domain`。
> 口径见 [`design-decisions.md`](design-decisions.md) 第 54 项；验证见 [`log/2026-10-09.md`](log/2026-10-09.md)。
> **边界**：真机行为未验（无设备）；命令历史不落盘；T1 spawn 桥本体仍缺。
>
> **2026-10-09 · 批 83（§10.9 第 8 条新增「镜像源管理」+ §10.2 userconfig 层落地）**：管理面板四项入口
> 只剩「镜像源管理」还是 toast 占位。**先裁归属再动手**（backlog 批 82 追记的要求）：它编的是 **npm registry**，
> 归 `:app-service:npm`（该模块本来就在管 `NpmConfigKey.REGISTRY`、项目 `.npmrc`、`InstallHistory.Op.REGISTRY`
> 审计键、交叉校验首选）。**粒度 = 全局一份**（用户裁定），对应 §10.2 三层链的 `files/.npmrc`(userconfig) 那一层
> —— **该层此前零实现**。解析链落成**两层**：项目 `.npmrc` → 全局 `files/.npmrc` → 出厂官方，
> 且**交叉校验的首选与安装实际用的那家取同一个值**（这才是本批真正的交付物，UI 只是它的呈现面）。
> 四件：① `:domain` `NpmRegistryKeys`（registry 判据的**唯一一份**，`NpmRegistryVerifier.canonicalRegistry`
> 改为委托它、`OFFICIAL`/`MIRROR` 改为别名 —— URL 字面量从此只有一份；`:domain` 首处 `java.net` 用法）；
> ② `:app-service:npm` `NpmrcFile` + `NpmGlobalConfig`（`files/.npmrc` 整文件重写，非行级 append）+
> `InstallCoordinator.resolveRegistry` 两层链 + `globalRegistry()`/`setGlobalRegistry()`；
> ③ `HostNodeExecutor` 不再无条件注入 `--registry`（`registryOverride: String?`，null = 不注入）+
> 新增 `--userconfig` + `prepareWorkDir` 拷项目 `.npmrc`；④ `:app` `HostSummary` 两条 + `:ui`
> `RegistryScreen`/`RegistryState`/`RegistryOps` + 管理面板那一行接上真入口。
> **同批修掉两条断链**（比 UI 占位更严重，实测证据见 [`design-decisions.md`](design-decisions.md) 第 53 项）：
> 生产环境的 `--registry` 因唯一构造点从不传参而**永远钉在官方**（用户的 registry 设置对真实安装零影响）；
> 项目 `.npmrc` 因 `--prefix` 已给且文件不拷进 workDir 而**根本没被 npm 读到**。两条的净效果是
> `setRegistry`/`config()` 的写入侧**生产上空转**。口径见 [`design-decisions.md`](design-decisions.md) 第 53 项；
> 验证见 [`log/2026-10-09.md`](log/2026-10-09.md)。
> **边界**：首启引导的 ping 探测与镜像候选表、审计页、`proxy`/`cache-retention` 两键均**未做**；
> 真机网络下的连通性（企业内网/自建 registry 的证书与代理、`--userconfig` 路径可达性）要装包才验得到。
>
> **2026-10-09 · 批 82（§8.1 新增「脚本执行环境变量」）**：管理面板批 46 画的四项入口里，
> 「环境变量」与「镜像源管理」两行一直是 toast 占位。动手前先查契约面 —— **「环境变量」在 12 卷
> 设计里没有对应条目**（`grep -rn "环境变量" docs/design/*.md` 只命中 §11 的 token 段与 §13 的
> apksigner 口令段），所以本批是**新增契约**，不是接线既有契约：先写进 §8.1 再动代码。
> 归属按用户口径裁定 = **给脚本用的全局环境变量**（用户编一组 KV，所有脚本执行时注入脚本进程的
> `process.env`）。四件：① `:domain` 契约 `ScriptEnvStore`/`ScriptEnvEntry`/`ScriptEnvKeys`
> （键名判据的**唯一一份**）；② `:app-service:script-repo` `core/FileScriptEnvStore`
> （`files/.autojs/script-env.jsonl`，追加 + `force(true)` + replay 收敛 + 最后半行容忍 +
> 损坏行响亮失败，与 `tasks.jsonl` 同族纪律）；③ `:engine:node-process`
> `NodeEngineConfig.scriptEnv: () -> Map`（**每次 spawn 现读**；用户键先写、宿主键后写，
> **顺序即契约**）；④ `:app`/`:ui` 装配与界面（`engineFactory` 闭包捕获 store ⇒
> `AppShell`/`AppShellKit`/`AssembledShell` **零改动**；`HostSummary` 三条；
> `ScriptEnvState`/`ScriptEnvOps`/`ScriptEnvScreen` + 管理面板那一行接上真入口）。
> 四条口径：全局非按项目 / 每次 spawn 现读 / 不注入 npm 会话进程 / **拒收 `AUTOSCRIPT_` 保留前缀**
> （实证：`AUTOSCRIPT_BRIDGE_TOKEN` 被覆盖 = 伪造桥身份或自断桥）。见
> [`design-decisions.md`](design-decisions.md) 第 52 项；验证见 [`log/2026-10-09.md`](log/2026-10-09.md)。
> **边界**：脚本进程 `process.env` 的实际可读性要装包才验得到（真机验证积压）。
>
> **2026-10-09 · 批 81（§10.9.1 / §10.9.2 / §10.5-2，依赖面板 + 审批卡）**：
> §10.9 第 1/2 条一直记成「UI 未排期」，实测复核发现是**结构问题**：`resolveApproval` 与
> `pendingApprovals` 全仓**零生产调用方**、`NpmShellKit` 建 `ApprovalLedger()` 时没传 store
> —— 审批票永远停在 PENDING、`runScript`/`exec` 永远过不了那道闸，重启还会让 `requestId`
> 从 `apr-1` 重来与历史票碰撞。本批三件：① `approvalStore` 缺省落盘
> （`files/.autojs/approve-ledger.jsonl`，逃生口显式）；② 读口 `PackageManagerFacade.snapshot()`
> → `:domain` `NpmPanelSnapshot`（`projects` + **全局** `pendingApprovals`）+
> `HostSummary.npmSnapshot()`/`resolveNpmApproval()` —— 读口**不经桥**，桥面方法表**一条不加**；
> ③ `:ui` `NpmScreen`（已装依赖 + 尺寸/配额条 + 待审批卡片，批准/拒绝 →
> `resolveApproval` = §10.5-2 人机分离的**唯一**生产落点）+ `NpmState`（纯数据，9 例单测）。
> 顺带把 `onCreate` 里摊开的项目页/三个子屏抽成 `ProjectTab`/`ManagementSubPageHost`，
> 三个子页开关收成一个 `ManagementPage?`（路由、返回键让位、切页现取三处同序）。
> 口径见 [`design-decisions.md`](design-decisions.md) 第 51 项；验证见
> [`log/2026-10-09.md`](log/2026-10-09.md)。**边界**：安装半边（输入行/进度条/依赖树/
> `hasInstallScript` 告警）**未落**；真机行为未验。

> **2026-10-09 · 批 80（§10.11 P0 / §10.12 末行，`child_process` 拦截 shim 接线 + 零 spawn 金标准）**：
> §10.11 P0 一直把「安装会话强制注入 child_process 拦截 shim」写成承诺，此前**未落** ——
> `--ignore-scripts` 只挡 lifecycle 脚本，挡不住**非脚本** spawn，而那类漂移在本平台上是**静默失败**。
> 本批落地：`npm-spawn-gate.cjs`（`:app-service:npm` classpath 资源，七个入口全替换为抛错，
> `detached:true` → `ERR_PERMISSION_DENIED`、`fork` → `ERR_NOT_IMPLEMENTED`、其余 →
> `ERR_NPM_SPAWN_BLOCKED`，并往 stderr 打可识别播报）+ `NpmSpawnGate`（落 `files/.autojs/`、
> `NODE_OPTIONS=--require` **追加**注入、播报解析、码折叠）+ `HostNodeExecutor(spawnGateFile=…)`
> （非零退出时门禁播报优先于退出码）。**装配层 fail closed**：素材 + 宿主 + shim **三条齐才注入
> 执行体**，shim 落不上就不注入（P0 承诺面不许静默降级）。零 spawn 金标准（§10.12 末行那条
> 「child_process 替换为 throw 的 harness 里跑全命令矩阵」）落成 `NpmSpawnGateMatrixTest`
> （门禁下 P0 命令矩阵全绿 + 不注入也全绿的反向变异 + `npm run` 确实被拦且无产物），
> 已登记进 `check-e2e-ran.sh` 的 nightly 验尸清单。口径见 [`design-decisions.md`](design-decisions.md) 第 50 项；
> 验证见 [`log/2026-10-09.md`](log/2026-10-09.md)。**边界**：它是不变量守卫，不是安全边界。
>
> **2026-10-08 · 批 79（§10.5-1 / §11.2 T2，`lockKey` 生产接线：T2 防线从「代码在、装配没接」转为生效）**：
> `LockSigner` 的签/验一直在，但生产装配 `lockKey = null` —— `npm ci` 不验签直接走（§11.3 第 8 条
> 自记的接线缺口）。本批把钥匙面接上：`LockKeyStore.AndroidKeystore`（`AndroidKeyStore` +
> `HmacSHA256`/256 位，别名 `autoscript.lock.hmac.v1`）经 `LockKeyStore.resolve` 做
> **get-or-create**，`AppShellApplication` → `AppShellKit(npmLockKeys=…)` →
> `NpmShellKit.assembleHandler(lockKey=…)`。**「取不动」绝不静默重建**（只有别名下真没有才新建；
> 锁被换过/库失效/Keystore 不可用一律显式失败）—— 重建会把「这份 lock 曾被换过」洗掉，
> 或让所有项目已有签名一起验不过。装配期**就取一次钥匙**，取不到则本次不装该防线、原因原文进
> `AssembledShell.npmLockKeyFailure`，**不掀翻装配**。口径见 [`design-decisions.md`](design-decisions.md) 第 49 项。
>
> **2026-10-08 · 批 78（jsonl 写入侧删除 + 保守档放回 `SCHEDULER_WRITE` + A11 收口点补在 runId 侧）**：
> **① 意图日志的 jsonl 回落已删（fail closed）** —— 上一段那两句「`AppShellKit` 侧按可用性选型、
> **打开失败如实回落 jsonl**」「两份实现（jsonl / SQLite）跑**同一套契约测试**」**已被本段取代**
> （原文逐字保留作历史）：`JournalFileStore`（271 行）+ 14 个用例已删，生产只走 `SqliteIntentStore`，
> `AppShellKit.intentStore` 由可选改**必填**（缺省值就是那个回落），`PlatformWiring` 不再 try/catch。
> 理由：jsonl 那份的 `runId` 分配与「锁内先查后写」锚点**以单写者为前提**，回落会连带失去幂等锚点。
> **契约套件未缩水**：`InMemoryIntentStore` 落在 `:domain` **自己的 test 源集**，16 例在任何机器上
> 都跑（放 `:platform` 会退回「没 `sqlite3` 就不跑」）。老设备 jsonl 的一次性导入仍可用。
> **② 保守档放回 `SCHEDULER_WRITE`**（三位减两位）：脚本可用 `auto.workManager.*` 建/删/列**自己项目**
> 的定时任务（与批 74 现行行为一致），替别人建仍拒。**③ A11 结项**：run 终结改按 `runId` 收口连接
> 资源，不再依赖 socket 断（脚本把桥 fd 继承给子进程时主进程死掉不产生 EOF）。
> 口径见 [`design-decisions.md`](design-decisions.md) 第 47、48 项。
>
> **2026-10-08 · 批 77（§9.2 / §8.5，录屏腿 + 意图日志落 SQLite）**：
> **§8.5 意图日志的 SQLite 实现已落地** —— 契约写的一直是「append-only（SQLite，启动即回放）」，
> 而生产跑的是 jsonl。本批把 `IntentStore` 接口自 `:app-service:scheduler` 迁到 `:domain`（纯 JVM
> 可测性保住），实现落 `:platform:system`（`SqliteIntentStore`，唯一碰 `android.` 的是
> `AndroidSqliteRunner`），`AppShellKit` 侧按可用性选型、**打开失败如实回落 jsonl**（原因随装配日志输出）。
> 两份实现（jsonl / SQLite）跑**同一套契约测试**（`:domain` 新增 `testFixtures` 源集），
> 正是它抓出「两索引覆盖不重叠 → 同 nonce 已 COMMIT 后仍能直投」的幂等漏洞（现为**单**部分唯一索引）。
> 老设备的 `intent-log.jsonl` **带原 runId** 一次性导入（runId 与 `run-archive.jsonl` 的
> `EngineRunLink.intentRunId` 同批号，不复用是硬要求），可重入、验完才改名归档、失败响亮失败。
> **未做（入池 backlog）**：保留期/清理策略 —— 日志仍只追加不清理，动它会碰到 §8.5 的幂等锚点。
> 同批 **§9.2 录屏腿（`MediaRecorder`）由「未落」转为「已落地」**：与截屏并列的同一条投屏会话账，
> 输出是视频文件；`auto.screen.startRecording/stopRecording` 两侧契约 + 生成物齐全。
> **边界**：真机行为未在本机验证（无设备）；`abortConnection` 对「子进程继承桥 socket fd」形态
> 仍未覆盖（backlog **A11** 仍开放）。本日流水新增 1 条；验证见
> [`本日流水 · 批 77`](log/2026-10-08.md)。

> **2026-10-08 · 批 76（§11，来源分级裁定不做 + 契约面措辞清账）**：
> **「内置 / 自写 / 第三方 / 打包」的来源分级经裁定不做** —— `TrustTier` 四档与
> `TrustTierResolver` 注入缝保留作**记账词汇**，不接真来源元数据、不产生授权差异
> （生产唯一可达档仍是 `UNKNOWN` 保守档 A）。理由是**它不产生防护**：同 UID 同权下
> 脚本可绕开桥直接读写宿主私有目录，掩码收窄只挡老实脚本。**本批零生产代码改动。**
> 同批把契约面五处仍写「MediaProjection 未落」的措辞逐处清账（§9.2 已落地，
> **录屏仍缺**）。口径见 [`design-decisions.md`](design-decisions.md) 第 45 项，
> 待办入池见 [`backlog.md`](backlog.md)（新增 A11 / A12）。
> 验证与限制见 [`本日流水 · 批 76`](log/2026-10-08.md)。

> **2026-10-08 · 批 75（§9.2 / §11 / §13 / §16，A5 第一阶段 + MediaProjection 会话）**：
> **执行级 `CapabilityMask` 已接** —— 桥路由在进 handler 之前做 deny-by-default 判据，未覆盖即
> `ERR_PERMISSION_DENIED` 且不进请求账；跨脚本控制/查询经 `CrossScriptAuthorizer`（触达他人执行要跨脚本位
> **且**调用方掩码覆盖目标掩码），`engines.exec` 派生不得提权，命名通道按发起执行私有。
> **缺省（无来源元数据）取保守档 A**：全量减跨脚本位与 `SCHEDULER_WRITE` —— 脚本默认不能再自建定时任务
> （**已知功能回退**，待来源分级接入后恢复）；**来源分级本身仍未接**（四档都还是 `UNKNOWN`），
> 且掩码只约束桥面命名空间，**不是沙箱**（不拦 Node 内建 `fs`/`http`）。
> 同批 **§14 P1「MediaProjection 高清会话」由「未落」转为「已落地」**：一次性同意 + API 34+ 顺序 +
> 幂等释放 + 会话资源随连接终结收口；**306s 上限由 FGS(`mediaProjection`) 消除，不做自动续期**
> （§13 风险表与 §16 兼容矩阵两处措辞已订正）。
> 本日流水新增 1 条；口径见 [`design-decisions.md`](design-decisions.md) 第 43/44 项，
> 验证与限制见 [`本日流水 · 批 75`](log/2026-10-08.md)。

> **2026-10-07 · 批 74（§7.5 / §8 / §11，A10① / A5）**：脚本日志归属真实 engineRunId，
> 桥一次性凭据认证、连接级请求隔离、自然尾帧排空与自身心跳校验已接；宿主行仍为 0。
> A10 数据接线齐全；A5 的 CapabilityMask/跨脚本授权仍未实现，不是沙箱。
> 本日流水新增 1 条，现为 **9 条**；下方旧汇总与批 72 进度逐字保留，当前状态以本追记为准。
> 验证与限制见 [`本日流水 · 批 74`](log/2026-10-07.md)。

> **2026-10-07 · 批 72（§7.3 / §8.6 / §8.7，A10②）**：宿主日志已镜像进系统日志，含启动期
> 有界缓冲回放；A10①（脚本执行归属）仍未接。范围、验证与限制见 [`本日流水`](log/2026-10-07.md)。

> **本文件不是契约。** 契约在 [`docs/design/`](design/) 12 卷（入口 [`framework-design.md`](framework-design.md) 索引）。
> 这里只记「哪些已落地、哪些还是接口期、哪次实测推翻了什么」——即原
> 框架设计 §19 那条 9,584 字符的流水账，以及散在各卷里的 `**已落地**` 块。
>
> **锚定规则**：条目一律以 `§X.Y` 锚回契约条款；§号是唯一权威锚，在本文件与
> 设计各卷里同义（§号不随文件位置变化）。
>
> **只追加，不改写历史结论**：新事实加在 [`docs/log/`](log/) 里**当天那个切片**的顶部
> （见下「流水目录」）；被推翻的记账不删除，原地标 `~~作废（日期 + 原因）~~`。
>
> **分片（2026-10-02，backlog C6）**：本文件原 175 KB，流水段 108 KB、实现注记段 59 KB
> 全挤在一处。现在本文件只留**接口期表 + 流水目录**（十几 KB），历史条目搬进
> [`docs/log/<日期>.md`](log/)，实现注记搬进 [`implementation-notes.md`](implementation-notes.md)。
> **§ 号仍是唯一权威锚**，不随文件位置变化；分卷正文里指回台账的链接一条都没改。
>
> **读契约时不要读这里**：正文里的落地注记是「实现注记」，不是规则本身。
> 判一条规则，看 `docs/design/` 对应卷；判它实现没有，看这里。

---

## 接口期（未落地，写了就是撒谎）

> **这张表怎么读（2026-10-02 起）**：它同时收**未落**与**已落地**两种行 —— 已落地的行保留
> 是为了让「这条曾经是缺口」有据可查，但**标题「未落地」只对 `未落` 那几行成立**。要找
> 「还剩什么没做」，看**现状列以「未落」开头**的行；要找「某条后来怎么解决的」，看该行里
> 的日期与链接（细节在 `docs/log/<日期>.md`，口径在 `docs/design-decisions.md`）。
> 本页**只做索引**：长叙事（>200 字）一律下沉到 log/decisions，这里留一行摘要 + 指针。

| 声明处 | 东西 | 现状 |
|---|---|---|
| §18 | 开放决策点 | **已全部拍板**（第 8/9 项 2026-09-25，第 1–7 项 2026-09-26，见 [`design-decisions.md`](design-decisions.md)；§18 保留作决策台账） |
| §14 P1 | MediaProjection 高清会话 | **已落地（2026-10-08，批 75）**：一次性同意（API 34+ 每会话重新征询）+ `mediaProjection` 型 FGS + VirtualDisplay/ImageReader 会话账；帧经共享 `ImageAnalyzer.ingest` 与 a11y 截图同表同号段；会话资源随连接终结结构性收口。**不做自动续期** —— 306s 上限由该型 FGS 消除（[`design-decisions.md`](design-decisions.md) 第 44 项）。**边界**：真机行为未在本机验证（无设备）；`abortConnection` 对「子进程继承桥 socket fd」形态未覆盖（入池 backlog **A11**）。**录屏（`MediaRecorder`）2026-10-08 已落（批 77）**：`MediaProjectionRecorder` + VirtualDisplay，与截屏并列同一条会话账、输出是视频文件（`auto.screen.startRecording/stopRecording`）；落点 `files/scripts/<projectId>/.recordings/`。**边界**：真机行为未在本机验证（无设备） |
| §14 P1 | QuickJS `:sandbox` 进程 | 未落；模块壳 **2026-09-30 已从 settings 注释摘除**（不计入模块数），**空壳目录与 settings 注释行 2026-10-01 已一并删除**（复活 = 重建模块目录 + include 行加回 + ModuleGraphTest 登记） |
| §14 P1 | `ui` 原生 XML UI 宿主 / `ui_web` | 未落 |
| §9.7 | OCR（P1）/ 插件（P2） | 未落 |
| §10.5 | 生物特征二次确认 | 未落（`BiometricPrompt` 全仓零引用） |
| §10.9.1 / §10.9.2 | **依赖面板 + 审批卡**（`auto.npm` 的呈现面） | **部分落地（2026-10-09，批 81）**：~~UI 未排期~~ 实测是**结构问题**（`resolveApproval`/`pendingApprovals` 零生产调用方、`ApprovalLedger()` 没传 store）—— 已修。已落：已装清单（`list()` 直读 lockfile）+ 尺寸/配额条（`storage()` + `InstallConfig` 的 512MB/80% 判据，呈现层不自己写死）+ 待审批卡片（批准/拒绝 → `resolveApproval`，§10.5-2 人机分离的唯一生产落点）；读口 `PackageManagerFacade.snapshot()`（`:domain` `NpmPanelSnapshot`，**全量**：`projects` + **全局** `pendingApprovals`）经 `HostSummary` 现取，**不经桥**（桥面仍 14 条方法，一条不加）。**审计页已落（2026-10-09，批 85）**：`PackageManagerFacade.history()` → `HostSummary.npmHistory()` → `:ui` `AuditScreen`（入口 = 依赖管理页顶栏）；`InstallHistory` 此前**生产零消费方**，现已接上（读口**无参**全量，筛在呈现层）。**未落**：安装输入行/旗标/阶段进度条（要 `install` 会话的完整形态 —— 其中**输入行**已被批 84 的控制台部分顶掉：`npm install axios` 今天有真实入口）、依赖**树**（`list(depth)` 宿主侧只用 0）、`hasInstallScript` 前置告警（要 packument 解析面）、白名单放行通道（T1 之后）、**审计导出**（§10.5-2 的「可导出」那半截：SAF 通道没接，界面不画按钮）、~~**包大小管理页的动作半边**（prune/dedupe/ci/cache clean 一键按钮 —— 配额满时那句提示今天把用户指去控制台敲 `npm prune`）~~ **已落（2026-10-09，批 86）**：四颗按钮（`清理多余包`/`依赖去重`/`按 lock 重装`/`回收缓存`）进依赖管理页，配额满那句提示改指本页。口径见 [`design-decisions.md`](design-decisions.md) 第 51 项 + 第 56 项 |
| —（**§8.1 新增**） | 管理面板「环境变量」= **脚本全局环境变量**（契约面原本**没有这一条**，本批新增） | **已落地（2026-10-09，批 82）**：用户编一组 KV，所有脚本执行时注入脚本进程的 `process.env`。`:domain` 契约 `ScriptEnvStore`/`ScriptEnvEntry`/`ScriptEnvKeys`；落盘 `:app-service:script-repo` `core/FileScriptEnvStore`（`files/.autojs/script-env.jsonl`）；注入面 `NodeEngineConfig.scriptEnv: () -> Map`（**每次 spawn 现读**；用户键先写、宿主键后写，**顺序即契约**）；`:ui` `ScriptEnvScreen` 经 `HostSummary` 三条读写。**四条口径**：全局非按项目 / 每次 spawn 现读 / 不注入 npm 安装会话进程 / 拒收 `AUTOSCRIPT_` 保留前缀。口径见 [`design-decisions.md`](design-decisions.md) 第 52 项。~~**同组仍有一行未落**：管理面板的「镜像源管理」仍是 toast 占位。~~ **该行已落（2026-10-09，批 83）**，见下一行。**边界**：脚本进程 `process.env` 的实际可读性要装包才验得到（真机验证积压） |
| §10.9 第 3 条 | **npm 终端视图**（`auto.npm` 的命令面） | **已落地（2026-10-09，批 84）**：控制台由「日志屏」改成**命令面** —— 选项目 + 敲一行 npm 命令 + 看输出。判据唯一一份住 `:domain`（`NpmConsoleKeys`：`parse` 四形态 / `SUBCOMMANDS` 白名单 `install·uninstall·ci·ls·list·prune·dedupe·audit` / §10.6 轻·重拆分 / `packageSpecsIn` / `gitSpecIn` / `rejectProjectId`），宿主侧执行入口与 `:ui` 输入行读同一个结论；输出粒度 = **事件流 + npm 输出尾部**（`HeavyOpOutcome.outputTail`，`InstallEvent` → `NpmConsoleLine` 投影在宿主侧一处，`NpmConsoleLine.ok` 是终态成败位，呈现层不按文本猜）；`npm run`/`npx` **照实接线**到 §10.3 T1 门禁（未获批 → `ERR_PERMISSION_DENIED` 且请求已入队；获批但 spawn 桥未接 → `ERR_NOT_IMPLEMENTED`）。读口 `HostSummary.runNpmCommand`/`consoleOutput`，**不经桥**（桥面一条不加）。原控制台的日志内容**一行不少地**搬去管理面板 → 日志管理（三段：系统日志/脚本输出/任务日志），数据面（`ConsoleCollector`/游标/`stopRun`）一个字没动。**未落**：真流式 stdout、shell 引号解析、命令历史持久化、白名单外子命令（`config`/`publish`）、T1 spawn 桥本体、命令的取消。口径见 [`design-decisions.md`](design-decisions.md) 第 54 项；**同批修 `ScriptPaths.PROJECT_ID` 放行 `.`**（`resolve("..")` 正好跳出项目根）。**边界**：真机行为未验 |
| §10.9 第 8 条（**新增**） | 管理面板「镜像源管理」= **npm registry 全局配置面** | **已落地（2026-10-09，批 83）**：编的是 npm registry 的**全局缺省**，粒度**全局一份**（用户裁定；不做「全局缺省 + 项目覆盖」的编辑面）。判据唯一一份住 `:domain`（`NpmRegistryKeys` 的 `canonicalize`/`reject`，被 `NpmRegistryVerifier` 缝边界与 `:ui` 输入校验共用）；落盘 `:app-service:npm` `NpmrcFile` + `NpmGlobalConfig`（`files/.npmrc`，整文件重写非行级 append）；解析链 `InstallCoordinator.resolveRegistry` = 项目 `.npmrc` → 全局 `files/.npmrc` → 出厂官方，**交叉校验的首选与实际安装同源**；`:ui` `RegistryScreen` 经 `HostSummary` 两条读写。**三条写法裁定**：存原样（只 trim）不规整化（规整化丢 query ⇒ 自建网关 `?token=…` 被静默剥掉）；空输入 = 删键（恢复出厂，不是写空值行）；校验不过抛原文。**同批修两条断链**：`HostNodeExecutor` 不再无条件注入 `--registry`（生产唯一构造点从不传参 ⇒ 永远钉官方）且 `prepareWorkDir` 开始拷项目 `.npmrc`（`--prefix` 已给 ⇒ 不拷就成死配置）。口径见 [`design-decisions.md`](design-decisions.md) 第 53 项。**未落**：首启引导 ping 探测/镜像候选表（第 6 条）、审计页（`InstallHistory` 仍无 UI 消费方）、`proxy`/`cache-retention` 两键（桥面本来无入口）。**边界**：真机网络下能否连通要装包才验得到 |
| §8.5/§8.6 | 引擎侧 `waitCompletion` 超时不发起（无人 await 的 run 没人收尾） | **已覆盖（2026-10-01）**：`TimeoutEnforcer{WATCHDOG}` + 看门狗期限线（`KillCause.TIMEOUT`）—— 残余边界见 §8.6（期限只覆盖声明了期限的 run + 轮转须在跑） |
| §8.5 | 意图日志的 **SQLite 实现**（契约写的是「append-only（SQLite，启动即回放）」） | **已落地（2026-10-08，批 77）**：~~未落，且分歧已如实标注~~ 卡点（接口住 `:app-service:scheduler`，`:platform:*` → `:domain` 不反向）**按「搬接口」解决** —— `IntentStore` 迁 `:domain`，`SqliteIntentStore` 落 `:platform:system`，两份实现（jsonl/SQLite）跑**同一套契约测试**（`:domain` 新增 `testFixtures` 源集，正是它抓出幂等索引漏洞）；老 jsonl **带原 runId** 一次性导入（可重入、验完才改名归档）、打开失败**如实回落 jsonl**。**仍待裁**：~~保留期策略~~ 日志**只追加、从不清理**，体积随 run 数线性增长（每次 run 恒定两条行，已无冗余可压），**保留期策略**（老终态行 / 老 nonce 能否丢）会动到 §8.5 的幂等锚点 —— **本批显式排除，入池 backlog**。**2026-10-08 批 78 修订**：jsonl 写入侧已删（生产只有 SQLite，打不开即失败、不回落），故本行的「两份实现（jsonl/SQLite）跑同一套契约测试」「打开失败如实回落 jsonl」**均已作废**（原文逐字保留）；契约套件改由 `:domain` 内的 `InMemoryIntentStore` 承载且**无环境门禁**。口径见 [`design-decisions.md`](design-decisions.md) 第 47 项① |
| §11.2 T2 / §10.2 | **npm 生产装配接线**（`lockKey` / `executor` / `scriptExecutor`） | **部分落地（2026-10-01）**：`executor` **已接线** —— 素材随包（`assets/npm/**` ← gradle `prepareNpmCliAssets` ← `node-runtime-build` 出口）→ 启动期 `AssetTreeCliSource` 幂等落位 `files/npm/` → 注入 `HostNodeExecutor`（宿主 = `nativeLibraryDir/libnoden.so`）；~~两条同时成立才注入（部署就位 + 有宿主）~~ **三条同时成立才注入（部署就位 + 有宿主 + `child_process` 拦截 shim 落位，2026-10-09 批 80）**，否则保持 `Unavailable` 且原因原文进 `AssembledShell.npmCliFailure`（shim 是 §10.11 P0 承诺面，落不上就不注入 —— 不静默降级成「装是能装、守卫没了」）。~~**仍缺**：`lockKey`（`lock.sig` 既不签也不验，全仓无 `KeyProvider` 实现；接缝形状 A1c 已就位）~~ **`lockKey` 已接线（2026-10-08，批 79）**：`LockKeyStore.AndroidKeystore`（Keystore 密钥，get-or-create；「取不动」绝不静默重建）+ `AppShellKit(npmLockKeys=…)` → `NpmShellKit.assembleHandler(lockKey=…)`；`ci` 先验签、`install` 收尾重签、`exportSnapshot` 带 `snapshot.sig`；取钥失败本次不装该防线且原因原文进 `AssembledShell.npmLockKeyFailure`。口径见 [`design-decisions.md`](design-decisions.md) 第 49 项。**仍缺**：`scriptExecutor`（T1 spawn 属 P1）。另：~~素材版本 = **npm 11.19.0 ≠ §10 脊梁的 npm 12.x 系**（落差登记在 [`backlog.md`](backlog.md)）~~ **落差已消解（2026-10-02 批 9）**：素材换 registry `npm@12.2.0`，§10.1 脊梁满足（backlog A6 划掉，[`design-decisions.md`](design-decisions.md) 第 26 项）。契约侧标注同批更新（§10.1 接线现状 + §10.12 风险表 + `SECURITY.md`）。**2026-10-02 勘误**：本条原先还写了「+ §11.2 T2 + §11.3 第 8 条」—— 实测**没改到**：§11.2 T2 行本来就不含 npm 版本叙述（无物可改），而 `11-security.md:64-69` 的「素材版本落差」段（在 §11.3 第 8 条**之上**的同一子弹列表内，不在第 8 条里）**原文未动、仍写 npm 11.19.0**，且按 `SECURITY.md` 的优先级规则「两处有出入以设计文档为准」→ **错的那份赢**。已登记 [`backlog.md`](backlog.md) 的 **C10** |
| §15 | APK ≤ 150MB release（**2026-10-07 自 `≤ 40MB` 重定**，见 [`design-decisions.md`](design-decisions.md) 第 41 项） | **按新预算不超支**：真形态 debug APK 实测 51,838,708 B ≈ 49.4 MiB（artifact `37581330329`）；未压缩 jniLibs 三件套 ≈92MB ≈ 87.7 MiB 是上界。旧账（≈92MB 取证链、第 20 项的 `InstallSizeRead` 实测链）**一字未删**。**release 形态（含引擎）本仓还没出过包** —— 已有的 release 实测（1,560,977 B）是不含引擎的性能包 |
| — | 真机红测：exec/dlopen + 桥全链 | **已做**（2026-09-29，见下「流水」；非 root、Android 13/arm64、生产布局）。**2026-10-06 批 55 复验并加宽**：换成批 54 的真形态 APK（B10 收口后单 ABI）装在云手机上，adb **直调** `nativeLibraryDir/libnoden.so`（不经 app 前端）走完 exec → 连桥 socket → `dlopen`/`dlsym` → `-e` 引导 → `attachNative` → `require('auto')` → 帧往返；demo 五帧逐字回桩、`rc=0`，20 连跑 `OK=20 FAIL=0`，负向 9 条（exit 2/3/4/5 + `console` 不抛/`device.*` 抛）全命中。**边界**：桩替掉了 Kotlin Router/各 handler/`ConsoleCollector`/UI —— 证的是**引擎半边** |
| §12.1 | **脚本 API 参考（用户向）** | **已落地（2026-10-02，批 10 / backlog C7）**：[`docs/api/`](api/index.md) 由 `bridge/js` 的公开注释经 typedoc 生成（15 个 md，生成物入库 + CI 零 diff 门）。**覆盖边界**：`auto.*` 门面的方法级说明 + 类型；引擎/桥的**内部件**不在入口面（作者划的边界）。契约侧指针加在 §12.1 |
| — | 真机红测：SELinux enforcing / `nativeLibraryDir` 提取路径 / targetSdk 36 的 app 数据区 exec 策略 | 未做（这几项该机**原理上测不到**，要特定设备）。**16KB 页机已不在其列**：2026-10-06 拍板**不做真机复验**（装载风险由构建期 ELF 对齐门禁承接，残余面「内核按 16KB 基页映射的装载行为」属已知不测），见 [`design-decisions.md`](design-decisions.md) 第 34 项 |
| — | 真机红测：性能数字（冷启/帧往返/图像算子） | 部分（冷启 158ms→新件 181–206ms；桥往返 p95=1ms；引擎 RSS≈46MB；`Intl` zh/en 运行期**已验**；图像算子 A2–A4 已量 2026-09-30：A3 契约口径 0.88ms ✅；~~A4 933.6ms ❌、A2 计算段 1912.8ms ❌~~ **作废（2026-10-02，前提过期）** —— 2026-10-01 五次实测 A4 19.82ms ✅ / A2 88.86ms ✅，残余 48×48 全帧于 2026-10-02 E2 拆双门 + 精确兜底后 host 33ms / **真机 64.43ms**（同会话精确 882ms，13.7×）**仍 ❌ 差 1.6×** → 同日拍板放宽该形态判据 <100ms（`design-decisions.md` 第 25 项）**✅ 转绿（形态注明）**、E5 关闭不投优化，见 `log/2026-10-02.md` 与 §7.7 第七次块） |

---

## 已推翻 / 已改口径

见 [`design-decisions.md`](design-decisions.md#已推翻--已改口径) —— 口径变更属决策侧，
只在那里写一份（本文件不复制，避免两处漂移）。

## 流水（最新在最上）

**2026-10-02 起，流水按日期分片**（口径见 [`design-decisions.md`](design-decisions.md) 第 22 项）：
新条目加在**当天那个切片**的顶部 —— 该文件不存在就新建一个，并把下表那一行的「条目」数 +1。
被推翻的记账不删除，原地标 `~~作废（日期 + 原因）~~`。索引与全文见 [`docs/log/README.md`](log/README.md)。

| 日期 | 条目 | 主题 | 文件 |
|---|---|---|---|
| 2026-10-10 | 1 | 批 89：Shizuku 反射面 ↔ R8 keep 对齐门（`ShizukuKeepRuleTest`，三条判据双向比：类名集合 / 方法 `(名字, 参数表)` 集合 / 签名在真类上真解析；先剥 proguard 注释行，`proguard-rules.pro` 声明成测试输入 —— 不声明则改 keep 后任务 `UP-TO-DATE`、门静默不跑，实测复现；反证三条各自命中）+ 「`input tap` 挂 30s」成因订正（真机对照实验推翻 2026-10-09 记的「两者各自独立 / 只有冷置后的第 1 条挂」：打进应用自己窗口时**每一条都挂**，6 条 32.4–33.8 s；机理同一件事 —— `input` 的 `WAIT_FOR_FINISH` 要等目标窗口处理完，而主线程卡在 `IRemoteProcess.waitForTimeout` 上；证据 = ANR logcat + `/data/anr` main 线程栈逐帧吻合）。顺带订正测量口径：「延迟 2.4–2.5 s」是 `uiautomator dump` 的成本（~2.2 s），设备侧 tap 本身 0.038–0.081 s | [`log/2026-10-10.md`](log/2026-10-10.md) |
| 2026-10-09 | 10 | 批 88 真机验证（Shizuku adb 档 100% 不可用 —— 已修）：用户在真机上（Android 13 + KernelSU，Shizuku 13.6.0.r1086）冒烟，`su`（root 档）正常、`shizuku`（adb 档）一条都跑不起来（`NoSuchMethodException: …IRemoteProcess$Stub$Proxy.waitForTimeout [long, class java.util.concurrent.TimeUnit]`）。根因：`5a03dc3` 把 `newProcess` 改成「从公开接口取方法」时**没把同一条推理推广到它的返回值上** —— `drain`/`drainBoth`/`pump` 走 `process.javaClass.getMethod(...)`，而那是**包内可见**的 AIDL proxy。修法：新增同文件 `RemoteProcessApi`（每方法从公开接口取）+ `ShizukuProcessReader`（读进程那一半搬出 `ShizukuInput`）+ `waitForTimeout(long, "MILLISECONDS")`（设备 dex 反汇编核实）+ 流改 `PFD → AutoCloseInputStream` 且形状不符即抛 + `step()` 剥开 `InvocationTargetException` + `ShizukuExecResult` 补 `truncated`（批 88 已知缺口销账）+ proguard 删死代码规则、补 `IRemoteProcess`。守卫：`ShizukuRemoteProcessTest` 8 例 + `ArchitectureTest` 常量池字节门（已反证会红）。边界：真机复测待用户跑；13.6.0 服务端 AIDL 未逐字核对；两档流上限仍不统一。批 88：控制台 shell 面落地（`su`/`shizuku` 特权模式，用户口径）—— `:domain` `NpmConsoleKeys.parse(line, mode)` + 枚举 `ShellConsoleMode` + 三个入口词常量 + 三个新命令变体；`HostSummary.runShellCommand` + `ShellConsoleResult`（四字段）+ `PackageManagerFacade` 同形缺省体；`:app-service:npm` `ConsoleShellRunner`（执行 + 渲染，独立一件）+ `ShellOpExecutor` 缝 + `InstallCoordinator.runShellCommand`；`:platform:capabilities` `ShizukuInput.exec`（并发排空两条流）；`:app` `PlatformWiring.ConsoleShellExecutor`（ADB 档换成 Shizuku）+ `asShellOpExecutor()`；`:ui` `ConsoleCmdState.mode` + `ConsoleCmdOps` 进/退模式 + `ConsoleScreen` 模式徽标。**七条口径**：必须有模式不许静默挑一条（§9.3 同纪律）/ `su <cmd>` 与 `su` 是两件事 / `DEFAULT` 一律拒且拒要落一行 / 非零退出是结果不是异常 / 超时不渲染半截输出 / shell 面与依赖树无关（不占安装会话、不碰项目锁）/ `adb` 档 = Shizuku 且只在装配层换（`AndroidShellExecutor` 的 ADB 是应用 uid，改它会动到 a11y 输入注入）。**顺手修两处**：`ShizukuInput.shizukuClass` 补接 `LinkageError`（JVM 实测 `NoClassDefFoundError` 不是 Exception，会越过 `AutojsException` 直穿）；`NpmScriptResolver` 纯 JS 探测拆出 `PureJsProbe`（越 `TooManyFunctions` 线）。批 87：§10.9 第 1 条变更半边落地（依赖面板的安装输入行/旗标/阶段条 + 清单行「卸载」）+ 第 5 条 `npm-cache` 尺寸栏 —— `:domain` `PackageManagerFacade.cacheStorage()` + `NpmProjectSnapshot.cache` + `HostSummary.runNpmPanelCommand()`/`npmInstallEvents()`；`:app-service:npm` `InstallCoordinator.cacheStorage()`（只量 `content-v2`）+ 快照里缓存读数提到项目循环外 + `CacheIndex.contentBytes()` 默认体；`:app` 两条生产实现；`:ui` `NpmInstallOps` + `InstallProgressState`（六档阶段）+ `InstallCard` + `NpmState` 六字段两条派生 + `MainActivity` 五条回调。**五条口径**：**走的是与控制台同一条宿主口**（门禁强度不取决于用户从哪个界面按下去）/ **进度是阶段不是百分比**（`InstallEvent.Progress.percent` 全仓从无赋值 —— 对原文的**收窄**）/ 「离线优先」是 `--prefer-offline` 不是"仅离线" / 草稿失败不清（宿主先落 ECHO 行再抛）/ **换项目要把阶段条与游标一起归零**（`seq` 环内全局单调，沿用旧游标会漏掉新项目 seq 更小的事件，阶段条会永远停在「进行中」）。批 86：§10.9 第 5 条动作半边落地（依赖维护四颗按钮 + 缓存按 lock 闭包回收 + `cacheRoot` 唯一一份判据）—— `:domain` `NpmMaintenanceAction`/`NpmCacheReclaimReport`/`PackageManagerFacade.reclaimCache()`/`InstallHistoryOp.CACHE_RECLAIM`；`:app-service:npm` `NpmCacheReclaim`（按 lock 闭包回收 + **修 index 悬空引用**）+ `InstallCoordinator.reclaimCache()`（保留集 = 所有项目 lock 并集，读不出的点名入史）+ `NpmCacheSeedDeployer.cacheRoot` 成为缓存目录唯一一份判据（四处读者此前互不相同，静默失效）；`:app` `HostSummary` 两条；`:ui` `NpmMaintenanceOps` + `MaintenanceCard`。**六裁定**：按 lock 闭包回收而非 `npm cache clean` / 保留集取所有项目并集 / op 名 `cache_reclaim` / **必须同时摘悬空 index 行**（实测悬空 index 让在线 `npm install` 报 `ENOENT … Invalid response body`）/ 认不出形状的保留、删不掉的不计入 / `CI` 不绕过验签。`ui/detekt-baseline.xml` + `app-service/npm/detekt-baseline.xml` 随新增 `@Composable` 与循环跳转重生成。批 85：审计页落地（§10.5-2）—— `InstallHistory` 从「生产零消费方」转为有读口：`:domain` `InstallHistoryEntry`（五字段，**由落盘格式决定**）+ `InstallHistoryOp`（已知操作名唯一一份；`op` 仍是 `String`，取值域开放 —— `opName(args)` 直取 argv 首词）+ `PackageManagerFacade.history()`；`:app-service:npm` `InstallCoordinator.history()` + `InstallHistory.Op` 改指向 `:domain` 的**别名** + **补记 `enqueueHeavy` 的磁盘/配额预检拒绝**（原先不入史，`crossCheckRegistry` 的拒绝一直在记）；`:app` `HostSummary.npmHistory()`；`:ui` `AuditScreen`/`AuditState`/`AuditOps`，入口在依赖管理页顶栏。**三条呈现口径**：失败行不藏 / 未知 op 原样显示 / 筛选只影响显示（宿主读口无参全量 —— 按项目筛会让 `registry` 那条空 `projectId` 的全局变更掉出去）。**未落**：「可导出」那半截（SAF 通道没接，界面不画按钮）。`ui/detekt-baseline.xml` 随新增 `@Composable` 与 `MainActivity` 复杂度/函数名重生成。批 84：控制台改做命令面（§10.9 第 3 条「npm 终端视图」落地）+ 日志整体搬去「日志管理」（三段：系统日志/脚本输出/任务日志）—— `:domain` `NpmConsoleKeys`（命令行判据唯一一份：`parse` 四形态 / `SUBCOMMANDS` 白名单 / 轻·重拆分 / `packageSpecsIn` / `gitSpecIn` / `rejectProjectId`）+ 四个新 DTO + 枚举 `NpmConsoleLineKind`；`:app-service:npm` `InstallCoordinator.runConsoleCommand`/`consoleOutput` + 第二条 `SeqRing<NpmConsoleLine>`（512）+ `HeavyOpOutcome.outputTail`；`:app` `HostSummary` 两条（不经桥，桥面一条不加）；`:ui` `ConsoleCmdState`/`ConsoleCmdOps`/`ConsoleScreen` 重写 + `LogManagementScreen` 三段化 + `ActiveRunState.summaryText()` 收拢重复；顺带修 `ScriptPaths.PROJECT_ID` 放行 `.`（`resolve("..")` 跳出项目根）。**六裁定**：日志搬去日志管理一行不少 / 只认 npm 与 npx（不加 sh）/ 子命令白名单 / 输出粒度 = 事件流 + npm 输出尾部（真流式 stdout 未落）/ 依赖提供的命令照实接线到 T1 门禁（审批 ≠ 执行，批完要重敲那一行）/ 判据唯一一份住 `:domain`。`ui/detekt-baseline.xml` 随新增 `@Composable` 重生成。批 83：镜像源管理（§10.9 **新增第 8 条**）+ registry 两条断链收口 —— 管理面板「镜像源管理」落地：`:domain` `NpmRegistryKeys`（判据唯一一份；`NpmRegistryVerifier.canonicalRegistry` 改为委托、`OFFICIAL`/`MIRROR` 改别名）+ `:app-service:npm` `NpmrcFile`/`NpmGlobalConfig`（`files/.npmrc` 整文件重写）/`InstallCoordinator.resolveRegistry`（两层链：项目 `.npmrc` → 全局 → 出厂）/`globalRegistry()`+`setGlobalRegistry()`（存原样、空=删键、校验不过抛原文、入 `Op.REGISTRY`）+ `HostNodeExecutor` `registryOverride: String?`（null = 不注入 `--registry`）+ `--userconfig` + `prepareWorkDir` 拷 `.npmrc` + `:app` `HostSummary` 两条 + `:ui` `RegistryScreen`/`RegistryState`/`RegistryOps` + 管理面板那行接上真入口（`ManagementBackHandler` 的 `else ->` 改显式枚举分支，防新子页静默吞返回键）。**两条断链实测**：`--registry` 因唯一构造点不传参而永远钉官方、项目 `.npmrc` 因 `--prefix` 已给且不拷进 workDir 而根本没被 npm 读到（npm 12.2.0 实测：`--prefix` 一给，项目级配置只看 `prefix/.npmrc`）—— 净效果是 `setRegistry`/`config()` 写入侧生产上空转。`ui/detekt-baseline.xml` 随新增 `@Composable` 重生成。批 82：脚本全局环境变量（§8.1 **新增契约**）—— 管理面板「环境变量」落地：`:domain` `ScriptEnvStore`/`ScriptEnvEntry`/`ScriptEnvKeys` + `:app-service:script-repo` `FileScriptEnvStore`（`files/.autojs/script-env.jsonl`，追加 + `force(true)` + replay 收敛 + 半行容忍）+ `NodeEngineConfig.scriptEnv: () -> Map`（**每次 spawn 现读**，用户键先写/宿主键后写 = 顺序即契约）+ `HostSummary` 三条 + `:ui` `ScriptEnvScreen`/`ScriptEnvState`/`ScriptEnvOps`；**拒收 `AUTOSCRIPT_` 保留前缀**（`AUTOSCRIPT_BRIDGE_TOKEN` 被覆盖 = 伪造桥身份或自断桥，写入侧拦 + spawn 侧兜两道）。`ui/detekt-baseline.xml` 随新增 `@Composable` 与 `MainActivity` 越 `TooManyFunctions` 线重生成，detekt 全模块绿。批 81：依赖面板 + 审批卡（§10.9.1 / §10.9.2）—— 三件：审批账本缺省落盘 / `:domain` `NpmPanelSnapshot` 读口 + `HostSummary` 两条（不经桥）/ `:ui` `NpmScreen` + `NpmState`（批准/拒绝走 `resolveApproval`）；`:ui`/`:app` detekt baseline 随新增 `@Composable` 与构造参数重生成；`:domain`/`:app-service:npm`/`:ui`/`:app` 四模块测试全绿。批 80：`child_process` 拦截 shim 接线 + 零 spawn 金标准（§10.11 P0 / §10.12 末行）—— `npm-spawn-gate.cjs`（classpath 资源，七个入口全替换为抛错；`detached:true` → `ERR_PERMISSION_DENIED`、`fork` → `ERR_NOT_IMPLEMENTED`、其余 → `ERR_NPM_SPAWN_BLOCKED`）+ `NpmSpawnGate`（落 `files/.autojs/`、`NODE_OPTIONS=--require` 追加注入、播报解析、码折叠）+ `HostNodeExecutor(spawnGateFile=…)`；装配层**三条齐才注入执行体**（素材 + 宿主 + shim），shim 落不上就不注入（fail closed）。金标准落成 `NpmSpawnGateMatrixTest`（门禁下 install/ls/dedupe/prune/uninstall/ci 全绿 + 不注入也全绿的反向变异 + `npm run` 确实被拦且无产物），已进 `check-e2e-ran.sh` nightly 验尸清单。验收：14 任务 JVM 线全绿、`NpmSpawnGateTest` 9 例 + 矩阵 3 例全过、lintDebug + assembleDebug 绿（shim 实测进 APK）、去掉 `-PskipNpmE2E` 后 nightly 验尸四条真 npm 路径全 ✓ | [`log/2026-10-09.md`](log/2026-10-09.md) |
| 2026-10-08 | 7 | 批 79：`lockKey` 生产接线 —— `LockKeyStore.AndroidKeystore`（Keystore HMAC 密钥，get-or-create，「取不动」绝不静默重建）+ `AppShellKit(npmLockKeys=…)` → `NpmShellKit.assembleHandler(lockKey=…)`；装配期就取一次钥匙、取不到本次不装该防线且原因原文进 `AssembledShell.npmLockKeyFailure`（不掀翻装配）；接线后 `ci` 先验签、`install` 收尾重签、`exportSnapshot` 带 `snapshot.sig`。本机 14 任务 JVM 线 + `:app` detekt 绿，两处反向变异各自命中。批 78：jsonl 意图日志写入侧删除（fail closed，生产只走 SQLite）+ 保守档放回 `SCHEDULER_WRITE` 并把建任务闸拆为自建/跨脚本二分 + A11 结项（run 终结按 `runId` 收口连接资源，堵住子进程继承桥 fd 的漏）。CI 七门全绿（PR #56）。批 77 补记：CI 首轮红在 `:app:testDebugUnitTest` —— 录屏接线用例把 `filesDir` 写死成真设备路径 `/data/app/files`，而 `MediaProjectionRecorder` 会真去 `createDirectories`（CI 非 root 建不动 → `ERR_IO`；本机 root 跑得动，反在宿主根上建出 `/data`）。「本机绿 ≠ 门禁有效」的又一例。改用 `@TempDir` + 期望值现算后 CI 七门全绿。批 77：录屏腿（`MediaRecorder` + VirtualDisplay，§9.2）+ §8.5 意图日志落 SQLite（`IntentStore` 迁 `:domain`、`SqliteIntentStore` 落 `:platform:system`、两份实现共跑一套契约套件、带原 runId 的一次性可重入迁移、打开失败回落 jsonl；保留期策略不做，入池）。两条轨并行开发、协调者会话合流（两个 merge 提交，零冲突）。验收（集成树）：14 任务 JVM 线 **1638/0/0/0**、detekt、lintDebug、:app:assembleDebug（APK 11,108,218 B，不含引擎）、npm test 213（212 通过 / 1 环境门禁跳过）、gen:wire + gen-notices + docs:api 三条生成物门零漂移、文档链接门 512、冻结面门通过。批 76：来源分级裁定不做（四档枚举保留作记账词汇、不接元数据，零代码改动）+ 契约面「MediaProjection 未落」五处措辞清账（§9.2 截屏已落地、**录屏仍缺**）+ backlog 新增 A11/A12 与 A5/A10/D1 三行结项。批 75：执行级 `CapabilityMask` 与跨脚本授权（A5 第一阶段 —— deny-by-default 桥面判据、单一决策链杀 TOCTOU、跨脚本不得提权、命名通道按执行私有、`workManager.create` 缺接缝即全拒；缺省保守档 A；**2026-10-08 批 78 修订：该闸已拆为自建/跨脚本二分 —— 项目号（认证点从 lease 装填，非 wire 字段）与目标相同即自建放行，不同才走跨脚本全套，空串走跨脚本那条；缺接缝仍全拒**）+ MediaProjection 高清会话（§14 P1 由未落转已落地：一次性同意、API 34+ 顺序、幂等释放、会话资源随连接终结收口）；同批订正 §13 风险表与 §16 兼容矩阵的 306s 措辞（不做自动续期）。两条轨并行开发、协调者会话合流（两个 merge 提交）。验收：14 任务 JVM 线 1562/0/0/0、detekt、lintDebug、assembleDebug、npm test 208/207/0/1、gen:wire 零漂移、文档链接门 477、冻结面门通过 | [`log/2026-10-08.md`](log/2026-10-08.md) |
| 2026-10-07 | 8 | 批 73：B15 真 Node PID 测试改为 loopback 退出握手，定向连续 10 次通过；批 72：宿主日志双写与启动缓冲（A10②）+ 收集器并发游标修复；批 71：APK 体积预算 `≤ 40MB` → `≤ 150MB release`（用户裁定）+ 批 70：日志管理页 —— 系统日志（控制台 `runId==0` 行；**脚本输出今天也在其中、宿主事件不在**，见 backlog A10）+ 任务日志（`HostSummary.taskLog()` 全部项目终态历史，`RunArchive.records()`）、项目页历史入口拿掉；同 PR：B11 stderr 排水竞态、编辑器严格 UTF-8、测试替身线程安全；补记 #46 的 tree-sitter 高亮从没进过 APK（`engine-native.yml` 未喂 `TREESITTER_OUT`，选填件缺位只 warn）、NDK 版本码漂移、pager bring-into-view 抽动；批 66：冻结面门（下游 PR 不许改契约面/台账面 —— `.github/scripts/check-frozen-paths.sh` + `ci.yml` 的 `frozen-paths` job，只对 `author_association` 非 OWNER/MEMBER 的 PR 生效）+ `docs/` 收进 CODEOWNERS + `CONTRIBUTING.md`/`CLAUDE.md`/PR 模板同步；批 65：PR #44 外审 —— release 形态 R8 把 Shizuku 反射面删光（`proguard-rules.pro` 补四条 `-keep` + 订正「按字符串找类只有一处」的过期判据）+ `newProcess` 改从公开接口取（原取法落在包内可见的 `$Stub$Proxy` 上，`invoke` 恒 `IllegalAccessException`）与 `String[]` 形参不再传 `null` + `docs/` 台账被该 PR 覆盖的复原（批 58–62 插回、PR 六条重编号 64–69）；批 63：`NOTICE` 措辞核实不做（backlog A9 结项）+ 上游 12.10.6 核对抽查留档；批 64：能力引导文案改成「与三态无关」（backlog A8 结项）+ `ADB_INPUT` 三态随批 61 修正为真探测（恒 `DEGRADED` → 就绪 `GRANTED` / 不就绪 `DENIED`）+ 大文件余量收口（backlog D7 结项） | [`log/2026-10-07.md`](log/2026-10-07.md) |
| 2026-10-06 | 20 | 批 49–69（**无 63** —— 该号被同日另一条轨用在 2026-10-07 切片里，本文件顶部那六条已改号 64–69）：设置页重写与「权限列表」子页、A7 取消语义、B9 npm 素材对账、B6/B8 质量门、B5 真形态 APK 门 + B10 ABI 收口 + SDK 基线对齐、B11 引擎 stderr 与崩溃摘要、批 55 真机引擎冒烟（直调 `libnoden.so`）、批 56 第三轮外审整改（24 条复核约半证伪；D3 链接门零链接即红 / L7 盘符检疫 / L1 许可收窄为 `GPL-2.0-only` + `NOTICE`）、批 57 冗余注释与文档去重、批 58 B12 离线 bundle 体积上限（两道闸 + 可注入测试缝）、批 59 裁定不做 16KB 页真机测试（B3/E3 的 16KB 那一半结项）、批 60 B13 `auto.zip.extract` 体积上限、批 61 输入通道三选一（`auto`/`adb`/`root` 平级 + 必须显式 + 绝不降级）与 Shizuku 引入、批 62 `ui/` 定性更正（「不是衍生」）+ 许可正文随包（`LICENSE`/`NOTICE` 进 APK）、批 64 项目页前端（FAB 按压档按形状裁 / 子钮回 48dp 图标 / 点文件进文本编辑 + 行尾「更多」/ 锁竖屏去旋转钮）、批 65 修好点文件进编辑（relPath 口径）+ 子钮改圆 + 主题切换圆形揭示、批 66 Telegram 源码入库（gitignore）+ 夜间模式配色/切换动画按 TG 对齐 + 三处 ⋮ 字重 + 编辑态收底栏与键盘 + 三处流畅度、批 67 编辑器行号槽与右下跳转钮（气泡数屏幕外行数）+ 点空白落文末 + 字距 + 夜间屏底改 #161E27 + 项目页行间分割线、批 68 文件日期改 `yy-MM-dd HH:mm`（`nowMillis` 整链拆掉）+ 夜间顶栏/菜单/底栏统一 #161E27 + 编辑器关自动换行（不折行 + 左右滚）与双指缩放（TG 那颗钮本 clone 没有，留证不改）、批 69 编辑器捏合不再每帧重排（预览走绘制期 `graphicsLayer`、松手一次提交 + `anchoredScroll` 补滚动）+ 跳转钮改直角箭头与骑边线滚轮数字 + release 形态「性能包」（R8 + 资源收缩 + debug 签名，11.5MB→1.56MB、dex 20→1、非 debuggable、带基线 profile） | [`log/2026-10-06.md`](log/2026-10-06.md) |
| 2026-10-05 | 10 | 批 39–48：任务栏/任务中心按 TG 重做（联系人页版式 → 搜索框几何三次纠偏 → 改名 → 回执改浮层）；管理面板与设置页重写；设置页「权限」进子页 + 补第九项 `USAGE_ACCESS` | [`log/2026-10-05.md`](log/2026-10-05.md) |
| 2026-10-04 | 6 | 批 34–38：项目页目录下钻与选择模式；拆掉下拉刷新；底栏按 TG 重做（含一次勘误）；菜单圆角、开关动画与描边 | [`log/2026-10-04.md`](log/2026-10-04.md) |
| 2026-10-02 | 19 | 批 9–19 + 文档面批：npm 素材换 registry `12.2.0`；`:ui` Telegram 质感重构与三轮返工；保活服务进程级真错；本机出真形态 APK；C6 分片 + C9 总索引；A2b/E1/E2/E5 拍板落地 | [`log/2026-10-02.md`](log/2026-10-02.md) |
| 2026-10-01 | 17 | 外审整改收尾、批 1–7、两次外审建议入池 | [`log/2026-10-01.md`](log/2026-10-01.md) |
| 2026-09-30 | 15 | 外审整改步骤 1–8、图像提速三案、A 组真机实测 | [`log/2026-09-30.md`](log/2026-09-30.md) |
| 2026-09-29 | 1 | 真机垂直切片红测（非 root） | [`log/2026-09-29.md`](log/2026-09-29.md) |
| 2026-09-25 及更早 | — | 自 §19 结语整段外迁 | [`archive/status-2026-09-25.md`](archive/status-2026-09-25.md) |

## 归档

- [2026-09-25 及更早流水（自 §19 结语整段外迁，逐字保留）](archive/status-2026-09-25.md) —— 2026-10-01 归档，原 14,625 字节单行随之搬走。
- [2026-09-29 起的流水（分片，逐字保留）](log/) —— 2026-10-02 分片（backlog C6）；
  条目数与最新主题见上方目录表（那份是唯一一份，索引与全文见 [`docs/log/README.md`](log/README.md)）。

## 实现注记（自各分卷外迁，逐字保留）

**2026-10-02 起本节内容搬进 [`implementation-notes.md`](implementation-notes.md)**（分片，backlog C6）。
本节标题**逐字保留**：`docs/design/08-execution.md`、`09-capabilities.md` 等卷正文里指回本节的链接
（`design-status.md#实现注记自各分卷外迁逐字保留`）据此解析，标题不改则那些链接一条都不用动。
分卷的搬迁状态（已迁 / 未迁两表）随内容一并搬走，见该文件的头两节。
