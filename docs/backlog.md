# AutoScript 待办池（backlog）

- **外审建议（2026-10-09，未做，登记）**：`IShizukuService`/`IRemoteProcess` 其实**就在
  编译类路径上**（`dev.rikka.shizuku:aidl` 是 `api` 的传递依赖，`implementation` 收得到，
  实测 `:platform:capabilities:dependencies --configuration debugCompileClasspath` 可见）。
  既然编译期看得见，那条链可以**直接转型**而不必反射：
  `IShizukuService.Stub.asInterface(binder)` → `IRemoteProcess`，一处反射都不用。
  收益是把这一整类 bug（签名漂移、声明类不可见、R8 改名）从**运行时**挪到**编译期**，
  连 `proguard-rules.pro` 那五条 keep 也可以删（静态引用自带保名）。
  本批没做，理由：现修法已由测试 + R8 mapping 双重验证，且它与 `newProcess` 那条
  已被真机验证过的写法同形，改动面最小；转型那条要重写守卫并重验 R8。
  **要做就得一次做完**（`Shizuku` 主类那条仍须反射 —— 它的静态初始化在 JVM 上会炸，
  见 `ShizukuInput` 的类 KDoc；只有 AIDL 接口那半可以转型）。
  注：这也解释了 `ShizukuInput` 类 KDoc 里「不 import `rikka.shizuku.*`」那句的**边界** ——
  它针对的是 `rikka.shizuku.Shizuku`（静态初始化重），不是 AIDL 接口。

## 2026-10-09 追记（批 88 真机 bug：Shizuku adb 档 100% 不可用 —— 已修）

- **真机实测（2026-10-09，用户设备 Android 13 + KernelSU，Shizuku 13.6.0.r1086）**：
  批 88 的 `shizuku` 档在真机上**一条命令都跑不起来**，报
  `NoSuchMethodException: moe.shizuku.server.IRemoteProcess$Stub$Proxy.waitForTimeout
  [long, class java.util.concurrent.TimeUnit]`；同机同一条命令走 `su`（root 档）正常，
  故问题被限定在 Shizuku 这条通道。**上一条追记里「真机验证积压①」已就地划掉**
  （答案有了：不通，且不是环境问题）。修法见 `fix(capabilities,app)` 那次提交
  （2026-10-09），机理与判据写在 `ShizukuInput` 的 `RemoteProcessApi` KDoc 里。
- **根因一句话**：`5a03dc3` 已把 `newProcess` 本身改成「从公开接口取方法」，
  **但同一条推理没有推广到它的返回值上** —— `drain`/`drainBoth`/`pump` 一律走
  `process.javaClass.getMethod(...)`，而那个运行时类是**包内可见**的 AIDL proxy
  （`IRemoteProcess$Stub$Proxy`），且它自己 override 了接口的每一个方法。三处后果：
  ① `waitForTimeout` 的 AIDL 签名是 `(long, String)` 不是 `(long, TimeUnit)`
  （后者只存在于本应用**永远拿不到**的 `rikka.shizuku.ShizukuRemoteProcess` ——
  `Shizuku.newProcess` 是 `private static`）；② 两条流方法返回 `ParcelFileDescriptor`
  而非 `InputStream`，`as? InputStream` **静默**得 null（比 ① 更隐蔽：症状是"输出栏什么
  都没有"，不是任何一条报错）；③ `destroy`/`exitValue` 在包内可见类上 invoke 抛
  `IllegalAccessException`。
- **adb 档的输出截断不报（上一条追记那条）已就地划掉**：`ShizukuExecResult` 补了
  `truncated`，`PlatformWiring` 逐字段转接时一起搬。**口径**：两条流**各自**判、`or` 起来
  算一个数（任一条被截即置位）—— 与 `AndroidShellExecutor` 的
  `truncated = out.truncated || err.truncated` 同形，不另立一套。
- **两条流的上限仍不统一（登记，未做）**：adb 档是 `ShizukuProcessReader.CAPTURE_LIMIT_CHARS`
  = 4 KiB（字符），root 档是 `ShellCaptureLimit.MAX_CAPTURE_BYTES` = 1 MiB（字节）——
  两个数分居两个模块（`platform/capabilities` 看不到 `:platform:system` 的契约），
  统一它们要 `:domain` 出一份共享常量，属跨模块契约变更。在那之前 adb 档的截断**如实上报**
  （置 `truncated` + 控制台打「已截断」），不静默丢字节。
- **回归守卫已立（JVM，无需真机）**：`ShizukuRemoteProcessTest` 8 例（喂一个**包内可见**、
  逐个 override 接口方法的 AIDL 仿真替身，把"从运行时类取方法必失败 / 从公开接口取才通"
  的语义钉死）+ `ArchitectureTest` 新增「Shizuku 反射只许 `ShizukuInput` 一处」
  （本模块生产代码引用不到那两个包，故门落在**常量池字节**上；**点分与斜杠两种形态都要找**
  —— 只找斜杠时新抄一份 `Class.forName` 照样绿，实测过）。
- **真机验证积压（本批新增，等用户跑）**：修完这一版要验的五件 ——
  ① `shizuku` 档 `id` 出 uid + 退出码；② 失败命令（`ls /nonexistent`）非零退出且 stderr 有内容；
  ③ 大输出命令（`logcat -d`）不死锁；④ 长命令走超时路径；⑤ a11y 的 `ADB` 输入通道
  （`ShizukuInput.run`）真能注入（**这条从来没成功过**，与 bug 是同一处）。
- **13.6.0 的服务端 AIDL 未逐字核对（登记）**：`waitForTimeout(long, String)` 与
  「第二参是 `TimeUnit.valueOf` 要的枚举常量名」两条，是从设备上 Shizuku 13.6.0 的
  `classes.dex` **反汇编**核实（`invoke-static {v0}, Ljava/util/concurrent/TimeUnit;.valueOf`），
  随仓库 vendored 的是 13.1.5。若真机复测仍报签名不符，就从设备上把服务端接口 dump 出来对齐。

## 2026-10-09 追记（批 88：控制台 shell 面）

- **控制台 shell 面已落（2026-10-09，批 88）**：`su` 进 root、`shizuku` 进 adb、`exit` 退出；
  默认模式下裸首词仍是 npm bin。**批 84 那条「只认 npm 与 npx，不加 sh」的裁定已被用户同日后续
  口径修订**（口径见 [`design-decisions.md`](design-decisions.md) 第 58 项 + 「已推翻」表）。
- **真机验证积压（批 88 新增，登记）**：无设备，按既定纪律由用户自测。具体没验的三件 ——
  ~~① Shizuku 装好之后 `newProcess` 那条反射链在真机上通不通（本机只能验「缺席时如实拒绝」）~~
  **作废（2026-10-09 真机实测：不通，见上方追记）**；
  ② `su -c` 在真 ROM 上的话术与行为（有些 ROM 的 `su` 是 Magisk 的、有些根本没有）
  —— **部分已验（2026-10-09 真机：KernelSU 上 `su` 进 root 档、`id` 出 `uid=0(root)`、
  退出码 0、`ls /data/data/com.autoscript/files` 退出码 0；换 ROM 的差异仍未验）**；
  ③ 特权模式下软键盘的观感 + 模式徽标是否足够显眼（这条只有人眼能判）**（仍未验）**。
- ~~**adb 档的输出截断不报（批 88 发现，登记）**~~ **已修（2026-10-09，见上方追记：
  `ShizukuExecResult` 补 `truncated`，两条流各自判、`or` 成一个数）**。原文照抄如下 ——
  `ShizukuExecResult` **没有** `truncated` 字段，
  经 `PlatformWiring` 转成 `ShellConsoleResult` 时取缺省 `false` —— 于是 adb 档的输出被截到
  4 KiB 时，控制台**不会**打那句「输出超过上限，已截断」（root 档走 `AndroidShellExecutor`，
  有真判据，那一档会打）。修法是给 `ShizukuExecResult` 加一个 `truncated` 并让 `pump` 在越限时置位；
  本批没做，因为要先定「截断」在两条流上怎么算一个数（两条流各自截断？还是合起来算一个？）。
  **别把它当成"已经做了只是没显示"** —— 那个字段本身不存在。
- **`su` / `shizuku` 的「就地跑一条」不做引号解析（批 88 现状，登记）**：`su echo "a  b"` 里那对
  双引号会被原样交给 shell（`sh -c` 自己解析），而**宿主这一层**按空白切分只为剥掉入口词
  （`substringAfter(' ')`）—— 故多空格与引号由 shell 决定，不是宿主吃掉的。这与 §10.9 第 3 条
  「分词按空白切、不做 shell 引号解析」**不冲突**：那条管的是 npm 面（npm 自己不解析引号），
  特权模式那条路整行原样交给 `sh -c`，引号归 shell 解析。**别顺手把两面的分词"统一"掉**。

## 2026-10-09 追记（批 87：依赖面板变更半边 + `npm-cache` 尺寸栏）

- **§10.9 第 1 条的变更半边已落（2026-10-09，批 87）**：安装输入行 + 两颗旗标（`-D` /
  「离线优先」）+ 六档阶段条 + 清单行「卸载」。**上一条追记里那句「未落：安装输入行/旗标/
  阶段进度条」已就地划掉。** 口径见 [`design/10-npm.md`](design/10-npm.md) §10.9 第 1 条。
- **`npm-cache` 尺寸栏已落（2026-10-09，批 87）**：`cacheStorage()` 读口 + `QuotaCard` 里的
  一行（只量 `content-v2`）。**上一条追记里那句「`npm-cache` 尺寸没进配额条」已就地划掉** ——
  但**回执口径不变**：回收的删/留数字仍取自 `NpmCacheReclaimReport` 本身，不取自那一行
  （一个是「这一刻有多大」、一个是「这次删了多少」，拿后者对前者只会让人以为对不上）。
- **阶段条只走到 `QUEUED` 是现状不是缺陷（批 87 登记）**：`runNpmPanelCommand` 是**入队即返回**，
  而 `:ui` 每个读口都是"进页面/手动刷新时现取一次"（无常驻轮询循环），故一次现取多半只拿到
  `QUEUED`，续拉要用户再点「刷新」。**要真做成会自己走的进度条**，缺的是宿主侧一条常驻推送
  （`drainEvents` 那条环已经在，缺的是"谁来按节奏拉"）—— 那是一件独立的事，别顺手在 `:ui`
  里塞一个 `while(true)` 轮询（那会把「读一次」变成"后台一直在问"）。
- **真机验证积压（批 87 新增一条）**：输入行在软键盘下的观感（弹出是否遮住阶段条、回车是否
  收键盘）、阶段条在真安装里走不走得动 —— 无设备。随下次真机冒烟一并看。

## 2026-10-09 追记（批 86：依赖维护按钮 + 缓存按 lock 闭包回收）

- **§10.9 第 5 条的动作半边已落（2026-10-09，批 86）**：依赖管理页配额条下面一行四颗按钮
  （`清理多余包`/`依赖去重`/`按 lock 重装`/`回收缓存`）。口径见
  [`design-decisions.md`](design-decisions.md) 第 56 项。**上一条追记里那句「动作半边仍未落」已就地划掉。**
- **`cacheDir/npm-cache-seed` 没有生产部署路径（批 86 发现，登记）**：§10.2 写的是
  「`cacheDir/npm-cache-seed`：精选 tarball 种子，首启播种」，但实测 `grep -rn NpmCacheSeedDeployer`
  的非测试命中只有它自己与注释 —— **`NpmCacheSeedDeployer.deploy` 在生产里零调用方**（非测试引用只剩 `cacheRoot`/`cacacheDir`/`contentPath` 这几个路径函数），
  种子素材（`assets/` 侧）也没有对应的 gradle 任务。后果具体：§10.11 P0 承诺的
  「精选缓存种子 + 离线首装」在真机上**一次都没播过种**，于是 `offlineGap` 恒报缺口
  （这现象本身被批 86 的 `cacheRoot` 合一掩盖了一半 —— 目录统一了，但里面还是空的）。
  要做需要三件：素材生成（构建期从 `~/.npm` 物化 + 侧车 `.sha512`）、随包（`assets/npm-seed/**`
  + 一个 `prepareNpmSeedAssets` 任务）、启动期部署（`AppShellKit` 里与 npm CLI 落位同批）。
  **别把它当成「已经做了只是没接线」** —— 素材本身也不存在。
- ~~**`npm-cache` 尺寸没进配额条（批 86 发现，登记）**：`CacacheIndex.contentBytes()` 有实现，
  但 `QuotaCard` 只画 `node_modules`，§10.9 第 5 条要的「per-project `node_modules` + `npm-cache`
  尺寸」今天只有前半截。~~ **已落（2026-10-09，批 87）**：`cacheStorage()` 读口 + `InstallCard`
  里那一行。**回收回执里的删/留数字仍取自 `NpmCacheReclaimReport` 本身**（口径不变，理由见本文件
  顶部批 87 那条）。
- **缓存回收的 index 修复只在 npm 10.9.8 上实测过（批 86 边界，登记）**：悬空 index 让在线
  `npm install` 报 `ENOENT … Invalid response body while trying to fetch` 这条结论来自本机 npm 10.9.8；
  随包 npm 是 12.2.0，cacache 桶格式同源（`index-v5` 的追加式行 + `sha1(json)` 前缀）但**未在该版本上复跑**。
  真机冒烟时顺手看一眼回收后 `npm install` 是否正常。
- **`NpmCacheReclaim.repairIndex` 不清理「指向仍存在、只是没人再需要」的 index 条目（刻意留的）**：
  那些是 packument 索引等几 KB 的小文件，清它们要重建整棵桶树，收益与风险不成比例。
  故 `Report.keptEntries` 会大于真正被 lock 引用的条目数。**这不是漏删**，别当 bug 修。
- **`ci` 与 `importOfflineBundle` 是否漏入史 —— 仍未核实（批 85 登记，批 86 复核后仍留）**：
  批 85 那条「同类待查」写的就是这两条。批 86 顺带确认了它们**确实在 handle 存在之前抛**
  （`ci` 的 `lockSigner.verifyOrThrow`、`importOfflineBundle` 的 `ERR_FILE_NOT_FOUND`），
  但**没有修**：要不要把「一次从没开始的会话」记成审计事件是**口径问题**，得先拍板再动手。
- **真机验证积压（批 86 新增一条）**：四颗按钮在真机上的观感（忙碌态文案、回收后配额条是否
  真的动了）未验 —— 无设备。随下次真机冒烟一并看。

## 2026-10-09 追记（批 85：审计页落地 —— `InstallHistory` 从「零消费方」转为有读口）

- **§10.5-2「审计日志落 App 且可导出」的读侧已落（2026-10-09，批 85）**：前半截（落盘）自始成立，
  后半截的**读口**此前压根不存在 —— 实测 `grep -rn InstallHistory --include=*.kt`（去掉测试与定义处）
  **零命中**，全仓非测试引用只有 `NpmShellKit` 那一处构造。已补：`PackageManagerFacade.history()`
  → `HostSummary.npmHistory()` → `:ui` `AuditScreen`（入口 = 依赖管理页顶栏）。
  口径见 [`design-decisions.md`](design-decisions.md) 第 55 项。
- **「可导出」仍未落（登记为后续）**：§10.5-2 的原话含「可导出」，那条通道 = SAF 选目录 + 写文件。
  本批**不画按钮**（按下去什么都不发生的按钮比不画更糟）。要做先想清导出格式：
  jsonl 原文（可再导入）还是人读文本（可贴给别人）—— 两者不是一回事。
- ~~**包大小管理页的动作半边仍未落（§10.9 第 5 条）**：配额满时那句提示今天把用户指去**控制台**
  敲 `npm prune`（批 84 的白名单里真有 `prune`，那条路是通的），但 prune/dedupe/ci 重装/cache clean
  的**一键按钮**没有。它是本批顺带看清的一条：**有路可走 ≠ 有一键**，记账时别把前者写成后者。~~
  **作废（2026-10-09，批 86）**：四颗按钮已落，见本文件顶部那条。
- **同批补的一条漏账（记下来，别再漏）**：`enqueueHeavy` 的磁盘/配额**预检拒绝**原先不入史 ——
  用户屏幕上是一句「已达配额」，审计页上却是「什么都没发生」。已补记。**同类待查**：
  其余在 handle 存在之前就抛的路径（`importOfflineBundle` 的 `ERR_FILE_NOT_FOUND`、
  `ci` 的 `lockSigner.verifyOrThrow` 失败）是否也漏了入史 —— 本批**只修了确认漏的那一条**，
  另两条未逐条核实，留作下一批的第一件事（别当成已修）。
- **`InstallHistoryEntry.op` 的取值域是开放的**（`opName(args)` 直取 argv 首词、
  T1 动作名来自 `ApprovalAction.name.lowercase()`）—— 将来若要收窄，先读
  [`design-decisions.md`](design-decisions.md) 第 55 项裁定四：收窄 = 让不认识的行消失，
  而审计里消失的行等于没发生过。
- **真机验证积压（批 85 新增一条）**：审计页在真机上的观感（长列表、筛选条档数多时的横向滚动）
  未验 —— 无设备。随下次真机冒烟一并看。

## 2026-10-09 追记（批 84：控制台改做命令面 —— §10.9 第 3 条落地）

- **§10.9 第 3 条「npm 终端视图」已落地（2026-10-09，批 84）**：控制台由「日志屏」改成**命令面**
  （用户口径「控制台不是放系统日志的地方，是用来执行命令的」），原控制台的日志内容整体搬去
  管理面板 → 日志管理（三段）。口径见 [`design-decisions.md`](design-decisions.md) 第 54 项。
  **核实说明**：这条**从未进过本池** —— 它一直只记在 `docs/design/10-npm.md` 的「P1 未落」里，
  本池 `grep 终端视图` 零命中。故本行不是「结项」而是**首次登记现状**（免得将来有人以为它入过池又被丢了）。
- **未落（本批明确划界，登记为后续；不单列条目号）**：
  1. **真流式 stdout** —— 现在 `OUTPUT` 行是 npm **退出后**的尾部（`HeavyOpOutcome.outputTail`，
     截尾有上限），不是逐行推送。要做需给 `HostNodeExecutor` 加流式读取面 + 一条宿主内部的事件通道
     （**不**动桥面：脚本侧的事件契约不因宿主多了一个界面而改形状）。
  2. **shell 引号解析** —— `NpmConsoleKeys.parse` 按空白切分（写死在 KDoc 里）。要做就是重写 npm 的
     参数文法，而半吊子重写正是「看起来对、实际是另一个包」这类静默错的来源。
  3. **命令历史持久化** —— `NpmConsoleHandle` 不落盘、不跨进程重启保留；界面只有本次进程内的输出环（512）。
  4. **白名单外子命令** —— `npm config` / `publish` / `login` / `token` / `owner` / `init` / `link` / `cache`：
     要么改宿主全局状态、要么要凭据、要么在本平台没有意义。要放哪个进来先想清楚它改的是谁的状态。
  5. **T1 spawn 桥本体**（`scriptExecutor`）—— 仍是 P1 剩余的唯一一件（批 80 追记里那段「为何验不到」
     照旧成立）。**直接后果**：控制台里 `npm run <script>` / `npx <bin>` 今天**只能走到** `ERR_NOT_IMPLEMENTED`
     （获批之后）—— 这是**如实**的接线，不是 bug；补上桥本体这两条命令才会真跑。
  6. **命令的取消** —— `NpmConsoleHandle` 与 `InstallHandle` 刻意分开（一个是「用户敲了一行」的账、
     一个是「一次安装会话」的账），故没有 `cancel()`。重操作的取消仍只能走 `InstallHandle` 那条路
     （今天也没有界面入口）。
- **真机验证积压（批 84 新增一条）**：控制台的命令面在真机上未验（无设备）—— 尤其
  `npm ls` 这类轻操作的直读路径与重操作的入队/进度回显在真机上的观感。**不单列条目号**，
  随下次真机冒烟一并看。
- **同批修掉的一条判据错（记下来，别再放回去）**：`ScriptPaths.PROJECT_ID` 原正则
  `[A-Za-z0-9._-]+` 放行 `.` 与 `..`，而 `projectsRoot.resolve("..")` 正好**跳出项目根** ——
  那条 `require` 自称「防路径逃逸」，实际把最经典的一种逃逸放行了（`.` 本身则让
  `files/scripts/.` 变成项目根，于是「项目 `.npmrc`」落在共享目录上）。已收成 `[A-Za-z0-9_-]+`。

## 2026-10-09 追记（批 83：镜像源管理落地 + registry 两条断链收口）

- **「镜像源管理」归属未裁那条已结项（2026-10-09，批 83）**：裁 = **npm registry 的全局配置面**，
  归 `:app-service:npm`，**粒度全局一份**（用户裁定）。它确实与 §10 的 registry 配置是同一件事 ——
  这一条现在有了契约条目（**§10.9 第 8 条**，本批新增）。口径见
  [`design-decisions.md`](design-decisions.md) 第 53 项。
- **两条断链的实测证据留档**（这是本批比 UI 占位更值得留的东西，别再让它们悄悄回来）：
  1. **生产环境的 `--registry` 永远指向官方** —— `HostNodeExecutor.registry` 缺省
     `NpmRegistryVerifier.OFFICIAL` 并写进 argv，而**全仓唯一的构造点**
     `AppShellKit.wireNpmExecutor` 从不传这个参数 ⇒ 用户经 `setRegistry` 设的镜像对真实安装
     **零影响**。修法：`registryOverride: String?`（null = 不注入）+ `--userconfig`。
     防复发断言在 `AppShellNpmCliTest`（走**真装配路径**断言 argv 里有 `--userconfig`、没有钉死的
     `--registry`）。
  2. **项目 `.npmrc` 根本没被 npm 读到** —— `prepareWorkDir` 只拷 `package.json`/
     `package-lock.json`，而 `runNpm` 用 `--prefix workDir`。本机 **npm 12.2.0**（与 vendored 同版本）
     实测：**`--prefix` 一旦给出，npm 的项目级配置就只看 `prefix/.npmrc`，cwd 不再参与**
     （`--registry` 显式给出时赢 `registry=` 键，但文件仍被读 —— `@scope:registry`/proxy/cache 都靠它，
     所以两者都要给，不是二选一）。修法：`prepareWorkDir` 拷 `.npmrc` 进 workDir。
  - **两条的净效果**：`setRegistry`/`config()` 的写入侧**生产上是空转**，且交叉校验的首选
    与实际安装的那家可以不是一家。任何"再引入一条 registry 读数路径"的改动，先看这条。
- **真机验证积压（批 83 新增两条）**：① 镜像源在**真机网络**下能否连通 —— 企业内网/自建 registry
  的证书与代理行为，本机验不到；② `--userconfig` 指向的 `files/.npmrc` 在 Android 上的路径可达性
  （SELinux/应用私有目录）。**不单列条目号**，随下次真机冒烟一并看。
- **未做（批 83 明确划界，仍在池里）**：§10.9 第 6 条首启引导（registry ping 探测 + 镜像候选表 +
  代理配置）、审计页（`InstallHistory` 至今**零 UI 消费方** —— approve/registry 变更/lock 重签
  都写进去了但没人读）、`proxy`/`cache-retention` 两个 `NpmConfigKey`（桥面本来就没有入口）。
- **`NpmServices.registryOf` 全仓零引用**（批 83 探查时发现）：本批**不模仿它**（新参数放
  `InstallCoordinator` 构造），也**不顺手删**（删除是另一件事，且要确认没有反射/装配路径在读它）。
  留作一条待清的死成员。
## 2026-10-09 追记（批 82：脚本全局环境变量 —— 管理面板「环境变量」落地）

- **管理面板四项入口里，「环境变量」已由 toast 占位转真入口（2026-10-09，批 82）**。
  动手前先查契约面：**这一条在 12 卷设计里没有对应条目**（`grep -rn "环境变量" docs/design/*.md`
  只命中 §11 的 token 段与 §13 的 apksigner 口令段），故本批是**新增契约**（先写进 §8.1 再动代码）。
  口径见 [`design-decisions.md`](design-decisions.md) 第 52 项。
- ~~**同组仍剩一行未落**：管理面板的**「镜像源管理」**仍是 `toast?.show("…尚未开放")`。
  它与 npm 镜像源（§10 的 registry 配置）是不是同一件事、归 `:app-service:npm` 还是新建面，
  **尚未裁定** —— 要做先裁归属，别照着「环境变量」这次的形状照抄（那是脚本面，这是 npm 面）。~~
  **已结项（2026-10-09，批 83）**：归属裁定 = `:app-service:npm`（它本来就在管 registry），
  粒度 = 全局一份，解析链 = 项目 `.npmrc` → 全局 `files/.npmrc` → 出厂官方。见本文件顶部追记。
- **真机验证积压（新增一条）**：脚本进程里 `process.env` 的实际可读性、以及用户设的值与
  Node 自身 `process.env` 的交互（Node 启动后是否改写/删除某些键），**本机验不到**（无设备），
  要装包才验得到。**不单列条目号**，随下次真机冒烟一并看。
- **`AUTOSCRIPT_` 保留前缀的判据是字面大小写敏感**（有单测钉）：宿主键全大写，
  `autoscript_foo` 不撞任何宿主键，拦它属过度收窄。将来若新增小写宿主键，这条判据要跟着改 ——
  **改的是 `ScriptEnvKeys` 一处**（`:ui` 与落盘实现共用，不抄两份）。

## 2026-10-09 追记（批 81：依赖面板 + 审批卡 —— 审批链的生产落点补齐）

- **§10.9 第 1/2 条的「UI 未排期」记账已订正（2026-10-09，批 81）**：真问题是**结构性的** ——
  `PackageManagerFacade.resolveApproval`/`pendingApprovals` 全仓零生产调用方、
  `NpmShellKit` 建 `ApprovalLedger()` 时没传 store（重启即蒸发 + `requestId` 从 `apr-1` 重来
  与历史票碰撞）。已修：审批账本缺省落盘 + `:domain` `NpmPanelSnapshot` 读口 +
  `HostSummary.npmSnapshot()/resolveNpmApproval()` + `:ui` `NpmScreen`/`NpmState`。
  口径见 [`design-decisions.md`](design-decisions.md) 第 51 项。**未落**（登记为后续）：
  ~~安装输入行/旗标/阶段进度条~~（**已落 2026-10-09 批 87**）、依赖树、`hasInstallScript`
  前置告警、白名单放行通道。
- **npm 面的「有实现、零生产调用方」三条（记下来，别当成已做）**：`update`、
  `exportSnapshot`（§10.9.4 高信任快照）、`storage()`。前两条**也不在桥面** —— 它们要等
  「依赖面板的变更半边」（~~**已落 2026-10-09 批 87**~~ —— 但 `update` **仍未接**：批 87 接的是
  `runConsoleCommand` 那条命令通道，`update()` 那个方法面至今零生产调用方）与「打包向导」（§10.9.7）。**不加进桥面**：§10.7 的 facade 是内部面，
  桥面是脚本面，两件事。`storage()` 已是宿主内部读口（喂依赖面板的尺寸条）。
- **契约面数字订正**：`docs/design/12-js-api.md` 的 npm 行原写「方法表 13 项」，
  实测生成物（`WireMethods.kt` / `wire.schema.json`）自 2026-09-26 起一直是 **14 条** ——
  是数错，不是漂移，已就地订正（桥面本次一条不加）。
- **npm P1 剩余件不变**：`scriptExecutor`（T1 spawn 桥本体）。**A13 仍开放**。

## 2026-10-09 追记（批 80：`child_process` 拦截 shim 接线 + 零 spawn 金标准）

- **§10.11 P0 / §10.12 末行那条「零 spawn 不变量漂移」的兜底已落（2026-10-09，批 80）**：
  `NpmSpawnGate` + `npm-spawn-gate.cjs` + `HostNodeExecutor(spawnGateFile=…)`，装配层
  **三条齐才注入执行体**（素材 + 宿主 + shim 落位），shim 落不上就不注入（fail closed）。
  金标准落成 `NpmSpawnGateMatrixTest`，已进 `check-e2e-ran.sh` 的 nightly 验尸清单。
  口径见 [`design-decisions.md`](design-decisions.md) 第 50 项。
- **npm P1 剩余件收窄为一件**：只剩 `scriptExecutor`（T1 spawn 桥本体 —— stdio 假管道、
  pgrp 杀树、node-shim PIE + PATH 注入）。`lockKey`（批 79）与零 spawn 第二层（批 80）均已收口。
  **A13 仍开放**。
- **T1 桥本体为何本批没做（如实记）**：它在当前环境下**验不到** —— `:app` 的 JVM 单测里没有
  脚本引擎（`scriptExecutor` 是 `Unavailable`），且 `engine/node-process/main.cpp` 把 node argv
  写死（`node [-e BOOTSTRAP --] <script> <args>`），Kotlin 侧注入不了 `--require`。要真做，
  先得让 T1 会话进程有可注入的 argv 面 + 一个能在 JVM 测试里跑的假引擎。本批因此只做 T0 面
  （已声明为 P0、可全 JVM 验证、且让早已声明的 `ERR_NPM_SPAWN_BLOCKED` 第一次真有人发）。

## 2026-10-08 追记（批 79：`lockKey` 生产接线）

- **T2 的 `lockKey` 装配缺口已收口（2026-10-08，批 79）**：`LockKeyStore.AndroidKeystore` +
  `AppShellKit(npmLockKeys=…)` → `NpmShellKit.assembleHandler(lockKey=…)`。接线后 `ci` 先验签、
  `install` 收尾重签、`exportSnapshot` 带 `snapshot.sig`；取钥失败本次不装该防线、原因原文进
  `AssembledShell.npmLockKeyFailure`。口径见 [`design-decisions.md`](design-decisions.md) 第 49 项。
  **仍未验**：`AndroidKeyStore` 那层（`KeyGenParameterSpec` 在真 ROM 上收不收、`getEntry`
  返回类型）要装包才验得到 —— 归入「真机验证积压」那一类，不单列条目。
- **npm P1 剩余件不变**：`scriptExecutor`（T1 spawn 桥本体）、`lockKey` 之外无新增。**A13 仍开放**。

## 2026-10-08 追记（批 78：jsonl 写入侧删除 + 保守档放回 `SCHEDULER_WRITE` + A11 结项）

- **A11 已结项（2026-10-08，批 78）**：批 75 登记的「`abortConnection` 对『子进程继承桥 socket
  fd』形态未覆盖」已补上 —— 会话资源收口点**新增一条按 `runId` 的路**（`runConnections` 映射 +
  `revokeRunResources(runId)`，装填在认证成功后、任何业务请求之前，`dispose` 条件摘除），
  执行终结**不再依赖 socket 断**。接在**全部六条** run 终结路径上（`stop` / `killRun` /
  `settleDone` / `settleKilled` / `killAll` / `forceStopAll`）。**只收资源、不关 IO**（run 自然
  结束时连接可能还在排空尾帧，硬关会把「正常跑完」误走成硬撤销）。**下方 A11 原始记录逐字保留
  作历史，当前状态以本条为准。**口径见 [`design-decisions.md`](design-decisions.md) 第 48 项。
- **jsonl 意图日志写入侧已删除（fail closed）**：生产只有 `SqliteIntentStore`，SQLite 打不开
  即装配失败。**下方 A13 证据里的 `JournalFileStore.kt` 原文逐字保留**（该文件已删）。
  契约套件改由 `:domain` 内的 `InMemoryIntentStore` 承载且**无环境门禁**（放 `:platform` 会退回
  「没 `sqlite3` 就不跑」）。口径见 [`design-decisions.md`](design-decisions.md) 第 47 项①。
- **`SCHEDULER_WRITE` 已放回保守档（批 78）**：脚本可自建定时任务，与批 74 现行行为一致。
  `workManager.create` 的闸已拆为自建/跨脚本二分（项目号由认证点从 lease 装填，非 wire 字段）。
  **A5 相关行不因此复活** —— 跨脚本那两位仍未放回。
- **A13 仍开放**：保留期/清理策略仍未拍板（动它会碰幂等锚点）。**A11/A13 之外无新登记。**


## 2026-10-08 追记（批 77：录屏腿与 §8.5 SQLite 落地，A12 结项）

- **A12 已结项（2026-10-08，批 77）**：**MediaProjection 录屏腿已落地** ——
  `MediaProjectionRecorder`（`MediaRecorder` + VirtualDisplay）与截屏腿并列、共用同一条投屏
  会话账，输出是视频文件；`auto.screen.startRecording/stopRecording` 两侧契约与两份生成物齐全；
  产物落 `files/scripts/<projectId>/.recordings/`（项目号来自认证租约，**不是脚本自报**）。
  **下方 2026-10-08 追记块里那条「新增 A12」原始记录逐字保留作历史，当前状态以本条为准。**
  **边界**：真机行为未在本机验证（无设备）；`ScriptPaths` KDoc 如实记了「打包器会把录屏产物
  一起打进 APK」这条副作用。
- **§8.5 意图日志的 SQLite 实现已落地（2026-10-08，批 77）**：`IntentStore` 接口迁 `:domain`，
  `SqliteIntentStore` 落 `:platform:system`，两份实现（jsonl/SQLite）跑同一套契约测试；
  老 jsonl 带原 runId 一次性迁移、打开失败如实回落 jsonl。口径见
  [`design-decisions.md`](design-decisions.md) 第 46 项。**`design-status.md` 接口期表里
  「§8.5 SQLite 实现未落」那行同批改写**（原文以删除线保留）。
- **新增 A13（S，未排期）：意图日志的保留期 / 清理策略未定**。日志**只追加、从不清理**，
  体积随 run 数线性增长（每次 run 恒定两条行，已无冗余可压）。老终态行、老 nonce 能否丢
  会直接动到 §8.5 的幂等锚点 —— **丢了老 nonce，重投就会重放副作用**（幂等键失效）。
  批 77 **显式排除**，未拍板。证据：`platform/system/.../persist/SqliteIntentStore.kt`（无删除路径）、
  ~~`app-service/scheduler/.../persist/JournalFileStore.kt`（同上；**该文件已于 2026-10-08 批 78 删除**，生产不再有 jsonl 写入侧）~~、
  [`design/08-execution.md`](design/08-execution.md) §8.5。
- **A11 仍开放**（`abortConnection` 对「子进程继承桥 socket fd」形态未覆盖）：批 77 **未触碰**，
  见下方 2026-10-08 追记块。

## 2026-10-08 追记（批 76：A5 来源分级裁定不做 + 批 75 遗留缺口入池）

- **A5 的「来源分级」经裁定不做（2026-10-08，用户裁定）**：`TrustTier` 四档与
  `TrustTierResolver` 注入缝保留作**记账词汇**，但不接真来源元数据、不产生授权差异 ——
  理由是**它不产生防护**（同 UID 同权下脚本可绕开桥直接读写宿主私有目录，掩码收窄只挡老实脚本）。
  口径与理由见 [`design-decisions.md`](design-decisions.md) 第 45 项，契约侧同步见
  [`design/11-security.md`](design/11-security.md)。**故下方 A5 行的「来源分级仍未实现」不再作待办。**
- **A5 的掩码与跨脚本授权已落地（2026-10-08，批 75）**：执行级 `CapabilityMask` 在桥路由做
  deny-by-default 过滤、`CrossScriptAuthorizer` 管跨脚本、命名通道按执行私有。**下方 A5 原始行
  （写于只有身份、没有掩码时）逐字保留作历史，当前状态以本条为准。**
- **A10 两半均已落地**：① 脚本 console 归属（批 74）、② 宿主事件进收集器（批 72）。
  **下方 A10 原始行逐字保留作历史，当前状态以本条为准。**
- **D1 已拍板维持独立模块**（2026-10-01，口径见 [`design-decisions.md`](design-decisions.md) 第 17 项），
  按本文件纪律「裁定不做了从这里移走」——**下方 D1 行不再是待办**，逐字保留作历史。
- **新增 A11（S，未排期）：`abortConnection` 对「子进程继承桥 socket fd」形态未覆盖**。
  批 75 的会话资源收口（`ConnectionResourceRegistry`）挂在**桥连接**上：abort / dispose / EOF
  三条路都会 `revokeAll()`。但脚本若把桥 socket fd 继承给子进程，脚本主进程死了
  **不产生 EOF**，连接不会 abort，挂在这条连接上的进程级资源（投屏会话等）就没有撤销点。
  要补需要 `runId → connectionId` 映射（宿主现在只知道 runId，连接号在接入端）。
  证据：`bridge/java/.../NewlineFrameServer.kt`（abort/dispose/EOF 三条撤销路）、
  `domain/.../bridge/AuthenticatedRunContext.kt`（`resources` 挂在连接上下文上）。
  批 75 如实登记未做，**未夹带半成品**（见 [`log/2026-10-08.md`](log/2026-10-08.md) 与
  [`design-decisions.md`](design-decisions.md) 第 44 项末条）。**属新范围，未排期。**
- **新增 A12（M，未排期）：MediaProjection 录屏未落**。批 75 只做了**会话式截屏**那条腿
  （`MediaProjectionSource` → ImageReader → 共享 `ImageAnalyzer.ingest`）；
  **录屏（`MediaRecorder`）全仓零引用**。契约 §9.2 的 FrameSource 结构里「会话式实时截屏/录屏」
  是两件事，本条登记后者。证据：全仓 grep `MediaRecorder` 零命中；
  [`design/09-capabilities.md`](design/09-capabilities.md) §9.2、[`design/13-roadmap-budget.md`](design/13-roadmap-budget.md) P1 段。

## 2026-10-07 追记（批 74：A10① / A5）

- **A10① 已落地**：脚本 console 从认证连接取得 engineRunId，不再恒写 0；系统日志只含宿主行，
  脚本输出在控制台按执行显示。A10② 的 HostLog 双写与启动缓冲保持，A10 两半的数据接线齐全。
  不新增 console 持久化，任务日志仍是终态历史；验证与环境边界见 [`本日流水`](log/2026-10-07.md)。
- **A5 身份/归属部分已完成，授权残余仍待办**：一次性 spawn 凭据 + hello/ACK、PID 可空匹配、
  连接级请求账、身份绑定日志及自身心跳已接；**CapabilityMask、跨脚本控制授权/来源分级仍未实现**。
  同 UID 能窃取其他执行凭据的代码不在抗冒用保证内，不能把本次认证当沙箱。
- 下方 A5/A10 与批 72 原始记录逐字保留作历史，当前进展以本条为准；不据旧行再做匿名 runId 透传。

## 2026-10-07 追记（批 72）

- **B15 已结项（2026-10-07，批 73）**：真 Node 进程测试的退出时序竞态已修复。
  `NodeProcessEngineRealSpawnTest` 改用 loopback 握手：子进程先报告 ready，测试完成存活期 PID
  断言后才放行 `process.exit(0)`；另保留立即退出用例，验证 `receipt.pid` 是启动快照、退出后
  `NodeProcessEngine.pid` 回 null。**定向测试连续 10 次失败/错误/跳过均 0；生产 PID getter
  与契约未改。** 下方批 72 的 B15 原始记录保留作历史，当前状态以本条为准。
- **A10② 已落地**：`:app` 四个文件的 28 处宿主日志经 `HostLog` 双写 logcat / 控制台，
  启动期有界缓冲在壳激活时回放。**A10① / A5 仍待做**：脚本输出仍没有执行归属，
  因而系统日志仍混有脚本输出。下方 A10 原始记录保留作历史，当前进展以本条及
  [`本日流水`](log/2026-10-07.md) 批 72 为准。
- **B15（新增，S，未排期）**：真 Node 进程测试存在退出时序竞态。
  `engine/node-process/src/test/kotlin/com/autoscript/engine/nodeprocess/NodeProcessEngineRealSpawnTest.kt:79,88`
  用 `process.exit(0)` 立即退出，却在取过 `selfPid()` 后仍断言 `receipt.pid == e.pid`；
  生产 `NodeProcessEngine.pid` 在已退出时正确返回 null（同文件生产实现的 `pid` getter）。
  **核实：2026-10-07 全量门首轮在第 88 行失败，后续原样重跑通过；本批未改引擎模块。**
  建议用可控信号让子进程在存活断言后再退出，不靠固定 sleep，也不放宽生产 pid 契约。
> **本文件不是契约，也不是台账。** 契约在 [`docs/design/`](design/)（入口 [`framework-design.md`](framework-design.md)），
> 落地状态在 [`design-status.md`](design-status.md)，口径变更在 [`design-decisions.md`](design-decisions.md)。
> 这里只放**尚未排期的待做项** —— 是收件箱，不是承诺。三条纪律：
>
> 1. **排期了就从这里移走**（或就地标 `已排期（谁/何时）`）；**做完了**去 `design-status.md` 记流水；
>    **裁定不做了**去 `design-decisions.md` 记口径 —— 本文件不留"历史结论"。
> 2. **「核实」列是硬要求**：`✅（日期）` = 在当前 tip 上真读过代码/文件确认过；
>    `待核实` = 只是外部审查的原始陈述（静态阅读、不构建不运行），**动手前先自己验一遍**。
> 3. **一次只做一批**：按 §F 的批次走，每批做完跑 CI 同源门（见 `CLAUDE.md` 构建节）。

**来源**：2026-10-01 外部审查（静态阅读约 15 个文件，未构建未运行）：优化 / 文档 / 结构三部分，
已逐条落进下表并标了核实状态。成本记号沿用外审：**S** < 1 天、**M** 1–3 天、**L** > 1 周。

---

## A. 安全与正确性

| # | 事项 | 证据位置 | 核实 | 影响 | 成本 |
|---|---|---|---|---|---|
| ~~**A2b**~~ | 已完成（2026-10-02）—— 叙事见 [`docs/log/2026-10-02.md`](log/2026-10-02.md) （本条不留历史；口径变更另见 [`design-decisions.md`](design-decisions.md)） | — | ✅ | — | — |
| ~~**A5**~~ | **已完成（2026-10-08，批 74 身份 + 批 75 掩码；来源分级经裁定不做，批 76）** —— 原文逐字保留：桥没有 per-engine 身份：同一 uid 的任何进程可达全部命名空间（`SECURITY.md` 已承认是有意为之），无法按脚本/按 run 归因与审计 | 同 uid 门禁 `BridgeSocketListener.kt:117-122` 是 fail-closed；abstract 名可预测 | ✅ 2026-10-01 | 以后想加 per-run 权限会很贵 | —（已结项） |
| ~~**A6**~~ | 已完成（2026-10-02）—— 叙事见 [`docs/log/2026-10-02.md`](log/2026-10-02.md) （本条不留历史；口径变更另见 [`design-decisions.md`](design-decisions.md)） | — | ✅ | — | — |
| ~~**A8**~~ | 已完成（2026-10-07，批 64）—— 裁定**选项①**（文案改成与三态无关，显示逻辑不动），八条逐条重写；**同批顺带修正 `ADB_INPUT` 三态**（恒 `DEGRADED` → 真探测 `GRANTED`/`DENIED`，理由见流水）。叙事见 [`docs/log/2026-10-07.md`](log/2026-10-07.md)，口径见 [`design-decisions.md`](design-decisions.md) 第 38 项（本条不留历史） | — | ✅ | — | — |
| ~~**A10**~~ | **已完成（①2026-10-07 批 74、②2026-10-07 批 72）** —— 原文逐字保留：**脚本的 console 输出没有执行归属；宿主事件不进控制台收集器** —— 日志管理页的「系统日志」因此不是「纯宿主事件」：脚本输出也落在里面（`runId` 恒 0），宿主装配/调度/闹钟/恢复/保活事件却**看不到**（走 `android.util.Log`）。页面顶部已如实说明，本条是补齐它。两半各自独立：① 给桥请求帧补 `runId`（`BridgeRequest` 现无 `side` 字段，`ConsoleCollector` 类注释写的「`side.runId` 透传」是愿望不是现状）→ 脚本输出归到各次执行，系统日志自然只剩 `runId=0` 的宿主行；② 把宿主事件镜像进收集器（`runId=0`）→ 系统日志才真有宿主事件可看 | `bridge/java/.../ConsoleCollector.kt`（`handle` 恒 `append(runId = 0L, …)`；生产代码无任何 `append(runId, …)` 调用方）；`domain/.../bridge/BridgeContract.kt`（`BridgeRequest` 五个字段无归属）；`app/.../AppShellApplication.kt` 等处的 `Log.i/w/e`；`ui/.../LogManagementScreen.kt`（顶部说明） | ✅ 2026-10-07 | 日志管理页的「系统日志」名不副实，只靠一段说明撑着；①与 A5 同一次协议变更 | —（已结项） |


## B. CI / 工程基建

| # | 事项 | 证据位置 | 核实 | 影响 | 成本 |
|---|---|---|---|---|---|
| ~~**B5**~~ | 已完成（2026-10-06）—— 叙事见 [`docs/log/2026-10-06.md`](log/2026-10-06.md) （本条不留历史；口径变更另见 [`design-decisions.md`](design-decisions.md)） | — | ✅ | — | — |
| ~~**B7**~~ | 已完成（2026-10-02）—— 叙事见 [`docs/log/2026-10-02.md`](log/2026-10-02.md) （本条不留历史；口径变更另见 [`design-decisions.md`](design-decisions.md)） | — | ✅ | — | — |
| ~~**B12**~~ | 已完成（2026-10-06）—— 叙事见 [`docs/log/2026-10-06.md`](log/2026-10-06.md) （本条不留历史；口径变更另见 [`design-decisions.md`](design-decisions.md)） | — | ✅ | — | — |
| ~~**B13**~~ | 已完成（2026-10-06）—— 叙事见 [`docs/log/2026-10-06.md`](log/2026-10-06.md) （本条不留历史；口径变更另见 [`design-decisions.md`](design-decisions.md)） | — | ✅ | — | — |
| **B3** | 无设备/仪器化测试道；`libs.versions.toml` 里的 `espresso` / `androidx-test-junit` **零引用**（要么用起来要么删目录项）；**SELinux / targetSdk exec 两条真机检查仍空白**。~~16KB 页~~ **已裁定不做真机复验（2026-10-06）** —— 装载风险由构建期 ELF 对齐门禁承接（`LOAD align >= 0x4000` 且 `p_offset ≡ p_vaddr (mod align)`，三个产物 + `libc++_shared.so` 逐件在 CI 断言，且该门禁被负向证伪过）；**残余面「内核真按 16KB 基页映射时的装载行为」属已知不测**，口径见 [`design-decisions.md`](design-decisions.md) 第 34 项 | 全仓无 `androidTest` 目录；`libs.versions.toml:15,31,32` | ✅ 2026-10-01（16KB 那一面 ✅ 2026-10-06 裁定不做） | native exec/dlopen/a11y 只在一台设备上验过 | L（16KB 裁掉后余量略降） |
| **B4** | 依赖漏洞扫描 / SBOM / 依赖图。**已落地（2026-10-02，批 10）**：① `engines.node` = **npm 12.2.0 自己的 engines 逐字**（`^22.22.2 \|\| ^24.15.0 \|\| >=26.0.0`，解包产物实读）；② CI 加 `npm audit --audit-level=low` + `npm sbom --sbom-format cyclonedx`（→ artifact）。**仍缺两件**：**(a) 依赖图未开** —— `actions/dependency-review-action` 试过并撤掉（PR #26 实测红：「Dependency review is not supported on this repository. Please ensure that Dependency graph is enabled」）；开它是**维护者侧的仓库设置开关**（Code security and analysis 页，或 `PATCH /repos/{owner}/{repo}` 的 `security_and_analysis`，需 admin —— 本仓令牌 403 实测；`/dependency-graph/sbom` 404、`/dependabot/alerts` 403 两条 API 同证），与 C4 那次「只剩维护者动作」同型。开了之后本行可回填 dependency-review job（或至少让 Dependabot 安全更新有数据源）。**(b) Gradle 面零覆盖** —— 当前两条只扫 npm 面（facade devDependencies）；Android 依赖要接 `gradle/actions/dependency-submission` 或自建，**独立一件事、未排期**。npm 面实测 `npm audit` = **0 vulnerabilities**。顺带核实：`package-lock.json` 里 25 条 `resolved` 原先指向 **npmmirror**（本机 `~/.npmrc` 的镜像）→ 已按官方 registry 重生成（版本/integrity 逐条不变，只换主机名）。口径见 [`design-decisions.md`](design-decisions.md) 第 27 项 | `package.json`；`.github/workflows/ci.yml`；GitHub 仓库设置（维护者） | ✅ 2026-10-02（engines 逐字读 + 依赖图 404/403 实查 + npm audit 实跑） | S（本批已做）/ **待维护者**（开依赖图） |
| ~~**B10**~~ | **APK 声明的 ABI 比实际有引擎的 ABI 多三个**（2026-10-02 批 16 实测 `aapt2 dump badging`）：`native-code: 'arm64-v8a' 'armeabi-v7a' 'x86' 'x86_64'` —— 后三个来自 `libandroidx.graphics.path.so`，而引擎四件只在 `lib/arm64-v8a/`（契约 §13 第 144 行：**arm64-v8a 首发，x86_64/模拟器 P1 补**）。**影响面如实收窄**：不是静默失败 —— `NodeProcessEngine` 的 execute 预检对绝对路径宿主二进制缺位回 `ERR_FILE_NOT_FOUND` 并**点名绝对路径**（`NodeProcessEngine.kt:137-140`，已有单测），32 位设备上表现为「任务每次都以『缺哪个文件、期望在哪』告终」。**要裁定的是「装不装得上」**：加 `ndk.abiFilters` 只留 arm64 = 32 位设备直接装不上（Play 也会过滤），代价是连 UI 都试不了；不加 = 装得上、能看界面，一跑脚本才被告知。现状是后者（未做任何限制）~~ —— **已拍板并落地（2026-10-06，批 54）：只留 `arm64-v8a`** —— `app/build.gradle.kts` 的 `defaultConfig` 加 `ndk { abiFilters += "arm64-v8a" }`。取「只留 arm64」而不是「维持四个」的理由：契约 §3/§13 已把代价写明（「放弃 32 位旧机」），而「装得上、能看界面、一跑脚本才被告知缺件」不是更友好的降级 —— 它把一次**安装期**就能给的答复推迟到用户配好任务之后。**代价照单全收**：32 位设备与 x86_64 模拟器装不上（Play 也按此过滤）。**实证**：`aapt2 dump badging` 从四个 ABI 变成 `native-code: 'arm64-v8a'`，APK 内 `lib/` 只剩 `arm64-v8a/`（`libandroidx.graphics.path.so` 这个唯一贡献者随之一并收进该目录）。**P1 补 x86_64（§13 兼容矩阵）时把该 ABI 加回那一行即可** —— 届时引擎产物与 `prepareEngineNativeLibs` 的 ABI 子目录要同步多一份，别只改这里。口径见 [`design-decisions.md`](design-decisions.md) 的 B10 行| `app/build.gradle.kts`（无 `abiFilters`）；`app/build/outputs/apk/debug/app-debug.apk`（`aapt2 dump badging` 实读）；`engine/node-process/.../NodeProcessEngine.kt:137-140`；`docs/design/13-roadmap-budget.md:144`；✅ 2026-10-06（改后 badging 实读单 arm64-v8a + APK 内 `lib/` 逐条实读） | ~~32 位设备上「装得上但跑不了」~~（已解：装不上，且是**安装期**就给出的答复） | S（已做完） | S（待裁定口径） |
| ~~**B11**~~ | ~~**脚本的 stdout/stderr 在设备上无人接收**~~ —— **已落地（2026-10-06，批 53）**：排水线程不再「读即弃」—— `ProcessBuilderLauncher` **不做** `redirectErrorStream(true)`（合流会丢掉「这是 stderr 写的」的分辨，而病因几乎全在 stderr），stdout 那条**仍然只排空不存内容**（它存在的唯一目的是防管道写满反压），stderr 在排空时顺带写进**有界环形尾 buffer**（字节级、上限 `RunSummary.MAX_DETAIL` = 4096、满了丢最老、取快照才整体 UTF-8 解码 → 跨 read 的多字节字符不撕裂）。摘要经 `ScriptEngine.lastRunSummary(): RunSummary?`（`:domain` 新契约，缺省实现回 null，老替身零改动）取出，**只在 `status()` 判出「自然退出」时填充**（含 exit 0 —— 摘要只是诊断读口）—— 请求停止（143）与强杀（137）如实不填：那是终止手段的产物，不是病因。落点分三层：`RuntimeController.Completed` 的失败类（`StopTimeout`/`Killed`/`UnknownRun`）在**槽位收走前**带上快照（收走后同槽可能已被下一次 execute 复用）→ `:app` dispatcher **摊平**成 `RunOutcome.{Failed,Crashed}` 的 `exitCode`/`crashSummary` 两个裸字段（scheduler 的架构门禁止依赖 `com.autoscript.domain.engine..`，故不折 `RunSummary` 整体，见 `RunOutcome` KDoc）→ `Scheduler.recordLink` 折进 `RunRecord.exitCode`/`crashSummary`。`FileRunArchive` 两字段 **append 式新增**（parse 走 `optLong`/`optStr`）：旧 journal 行无需迁移即可 replay。呈现面：`RunRow`/`RunRowState` 补字段 + `RunStateText.describe(state, crashSummary)`（有摘要说病因、无则原句）。**契约**见 [`design/08-execution.md`](design/08-execution.md) §8.5 末段。**未做（如实登记）**：① **任务中心卡不渲染**这一栏（批 41 用户拍板摘掉未结算块；本批只补数据面，摘要的消费者是将来项目历史列表）；② ~~**真机冒烟**仍缺（引擎二进制还没进 APK → **B5**）~~ —— **引擎二进制已进 APK 且真机跑通（2026-10-06 批 55）**：装批 54 的真形态 APK（`nativeLibraryDir` 四件齐），adb 直调 `libnoden.so` 走完 exec → 连桥 socket → `dlopen`/`dlsym node::Start` → `-e` 引导 `require(addon)`+`attachNative` → 脚本 `require('auto')` → 帧往返，demo 五帧逐字回桩、`rc=0`，20 连跑 `OK=20 FAIL=0`，负向 9 条（exit 2/3/4/5 与 console 不抛 / device 抛的分界）全命中。**但本批验的是引擎的退出码，不是「摘要经 `RunRecord` 落到 journal 再被念出来」那条链** —— 后者要 app dispatcher 驱动才走得到，而设备上 `.autojs/{intent-log,run-archive}.jsonl` 至今 0 字节（旁证：本批全走 adb 直调）。**故本条的「真机回读摘要」仍缺**，缺口归未完工的前端（用户口径：「app前端我又没做完」）；③ 全量 stdout/stderr 的流式通道（桥 `console` namespace）是另一条面，本批不做；④ **不做去抖/首行忽略**（Node 启动噪声治理）—— 只做确定性截断，见了真数据再调 | `engine/node-process/.../ProcessLauncher.kt`（`StderrTail`）；`domain/.../engine/ScriptEngine.kt`（`RunSummary`/`lastRunSummary`）；`domain/.../scripts/ScriptModel.kt`（`RunRecord` +2 字段）；`app-service/runtime/.../RuntimeController.kt`（`Completed` 三失败类带摘要）；`app/.../ControllerRunDispatcher.kt`；`app-service/scheduler/.../{core/IntentLog.kt,core/Scheduler.kt,persist/FileRunArchive.kt}`；`app/.../TaskCenterRead.kt`；`ui/.../TaskCenterState.kt` | ✅ 2026-10-02（排水线程「读即弃」逐行实读；`RunRecord` 字段逐条核；示例脚本改走 `auto.console` 前后**实测**：裸 console.log 的 5 行在管道里只出来 3 行且设备侧无出路）；✅ 2026-10-06（**真起 node** 验捕获：写 stderr 后 exit 3 → `lastRunSummary` 带 `exitCode=3` + 病因原文；只写 stdout 的噪声**不进**摘要，证 `redirectErrorStream(false)` 确实分了两条通道；`FileRunArchive` 旧行（无两字段）replay 不炸且两字段为 null） | 真机上「任务失败但说不出为什么」→ **本批已消**（真机复验待 B5 补齐引擎二进制）；P1 | S–M |

| **B14** | **输入通道三选一（批 61）只过了 JVM 面，真机面三件全空白**：① **Shizuku 装上后能不能真注进去** —— JVM 侧钉的只是「缺席时如实拒（`ERR_PERMISSION_DENIED`）」，反射链 `getBinder` → `IShizukuService$Stub.asInterface` → `newProcess` 的真机形状未验（该库停更于 2023-09，`lastUpdated=20230921`，与 Android 16 的兼容性无人跑过）；② **`bounds` → 坐标这条映射准不准** —— 非 `auto` 通道的节点动作走「解 bounds 再点中心/按方向划一条」，代码里按 `bounds` 中心算，但没有任何一次真机对拍（`auto` 与 `adb` 对同一控件点下去，落点差多少）；③ **`sendevent` 真轨迹要不要做** —— `gesture` 经 `adb`/`root` 现在逐笔画串行注入、每条只取首尾两点（shell 的 `input` 没有轨迹原语），真轨迹要按设备事件节点写，**未落地**。另：`ShizukuInput` 的反射面在 `adb` 通道登记探测（`isAvailable()`）上也未真机验过 | `platform/capabilities/src/main/kotlin/com/autoscript/platform/capabilities/device/ShizukuInput.kt`；`platform/system/src/main/kotlin/com/autoscript/platform/system/shell/ShellInputProvider.kt`；`app/src/main/kotlin/com/autoscript/shell/PlatformWiring.kt`；[`design/09-capabilities.md`](design/09-capabilities.md) §9.3；[`design-decisions.md`](design-decisions.md) 第 35 项 | 待核实（须真机 + Shizuku 管理器） | 三条通道里有两条在真机上从没跑过 —— 脚本按 `adb`/`root` 写的动作可能一次都不成立 | M（须真机；与 B3 设备道同源） |

## C. 文档

| # | 事项 | 证据位置 | 核实 | 成本 |
|---|---|---|---|---|
| ~~**C6**~~ | 已完成（2026-10-02）—— 叙事见 [`docs/log/2026-10-02.md`](log/2026-10-02.md) （本条不留历史；口径变更另见 [`design-decisions.md`](design-decisions.md)） | — | ✅ | — | — |
| ~~**C7**~~ | 已完成（2026-10-02）—— 叙事见 [`docs/log/2026-10-02.md`](log/2026-10-02.md) （本条不留历史；口径变更另见 [`design-decisions.md`](design-decisions.md)） | — | ✅ | — | — |
| ~~**C9**~~ | 已完成（2026-10-02）—— 叙事见 [`docs/log/2026-10-02.md`](log/2026-10-02.md) （本条不留历史；口径变更另见 [`design-decisions.md`](design-decisions.md)） | — | ✅ | — | — |
> **旁注（2026-10-02，PR #21 的 CI 红换来的一条）**：文档链接门读的是 `git ls-files '*.md'` —— **输入是索引不是工作树**。拆 C6 时我在 `git add` **之前**跑了门，新增的五个文件还是 untracked、不在扫描面里，于是本地 148 条全绿、CI 214 条红 19 条。**跑门的顺序是「先 add 再跑」**；同类教训（本地绿 ≠ 门禁有效）见 `design-decisions.md` 与 `docs/design-status.md` 的口径。

| ~~**C10**~~ | 已完成（2026-10-02）—— 叙事见 [`docs/log/2026-10-02.md`](log/2026-10-02.md) （本条不留历史；口径变更另见 [`design-decisions.md`](design-decisions.md)） | — | ✅ | — | — |

> **C8（文档数字改成生成片段）已裁定：保持现状、不动作** —— 2026-10-01 提案人本人撤回，
> 理由与两条附带观察记在 [`design-decisions.md`](design-decisions.md) 第 15 项。**不再作为待办。**

## D. 结构 / 重构

| # | 事项 | 核实 | 成本 |
|---|---|---|---|
| ~~**D1**~~ | **已拍板维持独立模块（2026-10-01，`design-decisions.md` 第 17 项）** —— 原文逐字保留：`:app-service:permission-center` main 只有 **91 行**（独立模块偏重）：并回现有模块，或明确"等它长"。**核实结论（2026-10-01 批 7，建议维持现状）**：① 它是 §9.5「**所有模块不得直接查 Settings，一律经此门禁**」那条例外的物理载体 —— 合并进别的 `:app-service:*` 会让"门禁住 `:platform:*`（archUnit 黑名单含 `com.autoscript.appservice..`，见 `SystemNamespaces` KDoc）"这条边界变成模块内的口头约定，独立模块正是把这条边界变成 Gradle 依赖图上的**硬边**（`ModuleGraphTest` 允许集里 `permission-center → :domain` 单点）；② 它只依赖 `:domain`，并回任何 `:app-service:*` 都要给那个模块新增一个上游依赖或开子包 —— 代价大于收益；③ 待它长：门禁面已经在长（`Capability` 九项），**建议拍板维持独立**，记入 `design-decisions` | ✅ 2026-10-01（依赖图与 archUnit 边界已逐条核对） | —（已裁定） |
| **D6** | 命名不一致。**已做（2026-10-01 批 7）**：`@autojs/*` 这个 npm scope 在**描述面**的 9 处（`06-modules`/`07-bridge` ×3/`09-capabilities`/`12-js-api`/`CLAUDE.md`/`bridge/js/package.json`/`ImageAnalyzer.kt`）全部改成事实侧口径 —— 脚本侧导入名 `auto`（`filesDir/node_modules/auto`）与真实交付物名 `bridge_native.node`；**`AutoJsPro` 九处保留**（那是**对标产品名**，不是自己的名字）。**还剩两件**：① `.autojs` 存储目录与 `autojs-lock-v1` 签名前缀在契约正文（§10.2/§10.5）与全仓 30+ 处实现/测试里一致使用 —— 改它是**存储格式变更**（会读不出用户既有 lock 签名），须拍板并给迁移/兼容策略，不是命名顺手能改的；② **发布用的 npm scope 是否自己拥有**需你确认（`bridge/js` 标 `"private": true`、不发布，发布 scope 归属未核） | ✅ 2026-10-01（描述面 9 处已改；① 需拍板 / ② 需你确认） | S（①）/ 待你确认（②） |
| ~~**D7**~~ | 已完成（2026-10-07，批 64）—— 复核结论：`Scheduler` 类体**无值得付的接缝**（单一内聚状态机，拆大方法要把八九个构造参数摊成 `internal`），只外迁三个顶层声明；`AppShellApplication` 读侧零重复不变式（不搬）、写侧抽一个刀口（四类 APK 资产读法 → `shell/AssetsRead.kt`，**该文件不可单测**，已如实登记）。叙事见 [`docs/log/2026-10-07.md`](log/2026-10-07.md)，口径见 [`design-decisions.md`](design-decisions.md) 第 39 项（本条不留历史） | ✅ 2026-10-07 | — |
| ~~**D9**~~ | 已完成（2026-10-02）—— 叙事见 [`docs/log/2026-10-02.md`](log/2026-10-02.md) （本条不留历史；口径变更另见 [`design-decisions.md`](design-decisions.md)） | — | ✅ | — | — |
| ~~**D10**~~ | 已完成（2026-10-02）—— 叙事见 [`docs/log/2026-10-02.md`](log/2026-10-02.md) （本条不留历史；口径变更另见 [`design-decisions.md`](design-decisions.md)） | — | ✅ | — | — |
| ~~**D11**~~ | 已完成（2026-10-02）—— 叙事见 [`docs/log/2026-10-02.md`](log/2026-10-02.md) （本条不留历史；口径变更另见 [`design-decisions.md`](design-decisions.md)） | — | ✅ | — | — |
| ~~**D12**~~ | 已完成（2026-10-02）—— 叙事见 [`docs/log/2026-10-02.md`](log/2026-10-02.md) （本条不留历史；口径变更另见 [`design-decisions.md`](design-decisions.md)） | — | ✅ | — | — |
| **D13** | **`docs/` 按受众重排**（外审建议的目标形态：`guide/`（新）+ `api/` + `design/` + `process/` + `reference/`）：把现在混在一起的**人类向导**（README/CONTRIBUTING）、**契约**（`design/`）、**过程台账**（status/decisions/backlog/implementation-notes/log/archive）与**参考件**（`api/`、`autojspro-docs.txt`）分开。**本仓的具体约束（外审也点了，逐条复核成立）**：① § 号是唯一权威锚，**带 § 号的文件名不能改**；② `design-status.md#实现注记自各分卷外迁逐字保留` 被 **9 处**分卷正文链接，搬迁必须留占位标题或同批改齐 9 处；③ 文档链接门读 `git ls-files`（**先 `git add` 再跑**）；④ `ModuleGraphTest` 会扫特定文档路径。**风险最高、收益最晚的一条** —— 建议排在所有 P0/P1 之后，且一次只搬一个目录、一个目录一个 PR（**证据**：`docs/` 全树；`docs/README.md`（地图要同批改）） | ✅ 2026-10-02（9 处反链、链接门输入源、ModuleGraphTest 扫描面均实测复核） | M–L |
| ~~**D14**~~ | 已完成（2026-10-02）—— 叙事见 [`docs/log/2026-10-02.md`](log/2026-10-02.md) （本条不留历史；口径变更另见 [`design-decisions.md`](design-decisions.md)） | — | ✅ | — | — |

## E. 需要拍板（产品面，不是工程顺手能做）

| # | 事项 | 现状 | 核实 |
|---|---|---|---|
| ~~**E1**~~ | 已完成（2026-10-02）—— 叙事见 [`docs/log/2026-10-02.md`](log/2026-10-02.md) （本条不留历史；口径变更另见 [`design-decisions.md`](design-decisions.md)） | — | ✅ | — | — |
| ~~**E2**~~ | 已完成（2026-10-02）—— 叙事见 [`docs/log/2026-10-02.md`](log/2026-10-02.md) （本条不留历史；口径变更另见 [`design-decisions.md`](design-decisions.md)） | — | ✅ | — | — |
| **E3** | 设备/仪器化测试道值不值得投入 L 级成本。**16KB 页镜像那一半已裁定不做（2026-10-06）**，本行只剩 SELinux enforcing / targetSdk 提取策略两条真机检查是否值得为它立道 | B3；[`design-decisions.md`](design-decisions.md) 第 34 项 | ✅ |
| ~~**E4**~~ | 已完成（2026-10-02）—— 叙事见 [`docs/log/2026-10-02.md`](log/2026-10-02.md) （本条不留历史；口径变更另见 [`design-decisions.md`](design-decisions.md)） | — | ✅ | — | — |
| ~~**E5**~~ | 已完成（2026-10-02）—— 叙事见 [`docs/log/2026-10-02.md`](log/2026-10-02.md) （本条不留历史；口径变更另见 [`design-decisions.md`](design-decisions.md)） | — | ✅ | — | — |

---

## F. 建议批次（一次一批，每批跑完整 CI 同源门）

**已排期的批次全部做完**（批 1–62，2026-10-01 起）。逐批的完整叙述（做了什么、
门跑出什么、当场露出的新口子）在流水切片里，按批次号可检索：

| 批 | 切片 |
|---|---|
| 1–7、33 | [`docs/log/2026-10-01.md`](log/2026-10-01.md) |
| 8–19 | [`docs/log/2026-10-02.md`](log/2026-10-02.md) |
| 23–26、30、34–38 | [`docs/log/2026-10-04.md`](log/2026-10-04.md) |
| 39–48 | [`docs/log/2026-10-05.md`](log/2026-10-05.md) |
| 49–62 | [`docs/log/2026-10-06.md`](log/2026-10-06.md) |

（批 20–22、27–29、31–32 只存在于已归档的分支上，**不在本仓的流水切片里** ——
要追溯得去远端那些分支，别在本仓里找。）

**下一批**：从上面 A–E 各表里**没划掉**的行里挑（那才是待办），一次一批、
每批跑完整 CI 同源门（见 [`../CLAUDE.md`](../CLAUDE.md) 构建节）。
