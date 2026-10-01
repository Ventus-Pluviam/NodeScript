## 18. 开放决策点（决策台账——九项全部已拍板）

开放决策点共九项，设计对每项都给了默认推荐。
> **九项已全部拍板**：第 8、9 项 2026-09-25，第 1–7 项 2026-09-26 —— 论证与代价对比见
> [`design-decisions.md`](../design-decisions.md)（条目原文在那里整段保留，编号不变）。
> 本节正文作**决策台账原样留档**，各项内的「已拍板（日期）」标注即拍板事实（2026-09-30 订正：
> 原导语写「下列条目为第 1–7 项，仍待拍板」是拍板前的原貌，已按只追加纪律改写于此句）。

1. **引擎路线：先 Node-only，还是 P0 就并行 QuickJS 沙箱？**
   推荐「P0 只 Node；QuickJS 沙箱 P1」——沙箱牵扯独立进程、白名单、双引擎 API 对齐三件大事，混进 P0 会把最小闭环拖垮。
   **已拍板（2026-09-26）：不要沙箱**——QuickJS 整条轨撤出排期，**不只是推迟到 P1**：引擎只剩 Node 一条轨，`:engine:sandbox` 不进排期（原记「模块壳保留、模块表冻结不动」；**2026-09-30 壳已注释摘除**，模块表 15→14，ModuleGraphTest 同批），§14 的「QuickJS `:sandbox` 进程」与 §16 的两条相关风险随之作废。
   **这条改的是安全边界，不是排期**：原先「第三方/市场脚本 → `:sandbox` 白名单子集」的隔离（§11 来源分级表）不再存在，第三方脚本与自写脚本**同在 Node 进程、同权**（无障碍/截屏/点击/网络全开）。防线因此只剩两条：**安装时的用户选择**（见第 7 项）与 §11 的来源提示——「靠能力授予而非进程隔离」要写在给用户的提示里，不能让人以为装来的脚本是沙箱跑的。
2. **进程模型：P0 就用「每脚本一进程」，还是先单引擎进程后扩？**
   推荐**一步到位**：反正脚本绝不能进主进程，单引擎进程的边界与多引擎池完全同构，代价只是「池容量先写死为 1」。避免二次重构。
   **已拍板（2026-09-26）：采纳推荐，一步到位**——现状即如此（`FixedEnginePool` 池容量 1，`:nodeN` 每脚本一进程），本项只是把"将来扩到 1-3"那条路确认成默认方向，无代码改动。
3. **分发定位与 Play 态度？**
   推荐完全避开 Play Store（specialUse FGS / SCHEDULE_EXACT_ALARM / MANAGE_EXTERNAL_STORAGE 政策冲突），官网/F-Droid/APK 直下。若你仍想上 Play，需砍掉 specialUse 保活与精确闹钟，P0 范围要变。
   **已拍板（2026-09-26）：不发行**——非商业化项目、不分发，故 Play 政策冲突面（specialUse FGS / 精确闹钟 / 全盘存储）**根本不存在**，上面那组"若上 Play 要砍什么"的代价不用付，保活与 `SCHEDULE_EXACT_ALARM` 原样保留。落地形态 = 本机自装 APK；Play/F-Droid/官网分发轨不进排期。
4. **ICU 取舍：全量 ICU（完整 Unicode/时区/国际化，体积 +20MB 级）还是配 `--with-intl=none`（体积小但字符串/时区残缺，自动化和 UI 场景产物不友好）？**
   推荐**全量 ICU + 裁剪为所需 subset**（也可放 assets 按需加载），自动化 app 大量依赖正则/时区/日期格式化。
   **已拍板（2026-09-26）：只要中文 + 英文**——即 locale 面收成 `{zh, en}`，既不停在 `none`（那样连 `zh-CN` 的 `Intl.*`/`toLocaleString` 都不可用，等于还是残缺），也不背全量的 +20MB。落点是 Node 构建旗标 **`--with-intl=small-icu --with-icu-locales=zh,en`**。**旗标已改（2026-09-26，同日）**：`node-runtime-build/scripts/fetch-and-build.sh` 由 `--with-intl=none` 换成上述两行；
   数据源是仓内 canned ICU（`deps/icu-small/` 带 `README-FULL-ICU.txt` → `configure.py` 走 `canned_is_full`），**不联网下载 icu4c**，`root` 由 configure 自动并入。
   跟进（构建轨，Actions 跑，本机不编）：旗标已改 → **重编已过（run `36185853302`，success，2026-09-25T23:01Z，约 2h34m）** → **体积差已量并回填 §15**：`libnode.so` 70,725,976 → **81,950,376 B（+11,224,400 B = +10.70 MiB = +15.87%）**，取证两个 `node-slice` artifact（基线 `36153816811` intl=none / 新 `36185853302`）+ `config.gypi`（`icu_small=true`、`icu_locales=en,root,zh`、`icu_path=deps/icu-small`、`icu_ver_major=78`）。
   旧估数 "~10MB+" 是 small-icu **默认面**，`zh,en` 实测比它还略高一点（ICU 数据不是按 locale 线性摊的）。`RISKS.md` §3 同批改成「已改 + 已量」。**仍待设备**：`Intl.DateTimeFormat`/`Collator` 在 zh/en 上的运行期实测（arm64 二进制本机跑不了，归 §8b 那批真机账）。副作用要写明：`toLocaleString('ja_JP')` 之类非 zh/en locale 会回落 en —— 脚本作者该知道这不是 bug。
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
   - **默认 registry = 官方 `registry.npmjs.org`**——不分发、不面向陌生用户（第 3 项），镜像加速不是开箱前提；用户要快自己 `setRegistry` 切 npmmirror。`HostNodeExecutor`（实际装包用的那家）与 `NpmRegistryVerifier`（交叉校验的首选）**两处缺省同批改**，
     §10.2/§10.4 的"默认 npmmirror"同批改。**第二意见的规则同时改写**：交叉校验要的是**两个运营主体**，不是"官方那一家"——首选官方时镜像做第二意见，
     首选任意别家时官方做第二意见（`secondary` 缺省跟着 `primary` 走），否则会出现 primary 与 secondary 同站、自己跟自己比也算通过。
   - **lifecycle 脚本不做出厂卡口，安装时让用户自己选**——既不是 global-deny 也不是白名单：装包时按包如实告知 `hasInstallScript`（**禁止静默**，§10.12 那条保留），跑不跑由这次安装的使用者当场决定；`requestApprove`/审批接口保留为这条选择的落点。
   - **实现落差（明写，不当已办）**：现网 T0 是 `--ignore-scripts` 全程 + npm12 `allowScripts=none`，lifecycle 脚本**一个都没跑过**，安装回执显式发 `scripts-skipped`（禁止静默那条就是为这个静默面立的）。"用户选择跑"要先有 spawn 桥（`child_process` 真执行），那是 §14 的 **P1 项**——**口径在此定死，实现排 P1**，P1 落地时按本条写交互，不重新拍。
8. **截屏帧与 images 帧的通路**（§9.2 记账的缺口，决定 §7.7 表里 `captureScreen → findImage < 1s` 这条链路什么时候能兑现）：
   **记账时的现状（2026-09-25）**：`screen.capture()` 出的帧与 `images.decode` 出的帧**互不通用**（两缝各发各的号，§12.2），且 screen 面既不给 `save()` 也不给 `pixel()`（字节出不了 `:main`），脚本**只能自己先落盘再 decode**（§12.3 示例当时这么写）。三条出路，入口在同一处（换 producer 或加一个 `screen.save`），代价不同：
   - (a) **`screen` 面加 `save(path)`**：把 a11y 已产出的 JPEG 字节原样落盘。设备面已经在压 JPEG 了，最小改动；代价是**有损**——`findColor` 的分量判定会吃到压缩伪影（§9.2 的契约是按分量精确夹的），"屏幕上这个色还在吗"这类判读会变钝。
   - (b) **两缝共用一个帧表**（producer 直接把帧写进 `images` 的帧表）：收益是真正的 0 拷贝直连（§7.4 所有权边界仍是每个句柄一份 Mat，变的是**发号那一侧**归谁）；代价是"帧不通用"这条纪律取消，`screen`/`images` 两个命名空间的释放语义要重新对齐（谁 release 谁背 STALE）——**已对齐为"两个释放入口、一张表"**（本项末）。
   - (c) **`images` 面加 `decodeBytes(byte[])`**：屏幕字节不落盘直进 native；代价是 bytes 要过桥，§7.7 的"屏幕帧→native 0 拷贝"这条在**两个维度上**都要重新记账，且 §7.4 的多一路径 = 多一处规格要守。
   **已拍板 (b)**（2026-09-25）：只有它同时保住了"0 拷贝"与"按分量精确判定"两条被契约明确承诺的性质，(a) 切掉的是判读精度、(c) 切掉的是性能口径。(a) 不作为过渡 —— 过渡方案一旦进示例就会被抄成正式用法，而带 JPEG 往返的链路不叫「屏幕帧→native 0 拷贝」，§7.7 的买单口径不为它改。
   **已落地**（2026-09-26，§7.4 发号侧归一 + §9.2 末落地段）：`ImageAnalyzer.ingest`（第 6 方法，紧密 RGBA → 同一张帧表）、`ImagesNamespaceHandler` 退成无状态转接、`ScreenshotSource` 可选 `analyzer`（null 即本地表，且那时 `images` 未注册故不撞号）、`frameOf` JPEG → 原样 RGBA（`getPixels(int[])` 打包序，不猜 `copyPixelsToBuffer` 字节序）。
   钉子 = `host_ingest_test`（跨来源同表）+ JVM 三处 + JS 互认用例。**`captureScreen → findImage` 的实测数字仍欠**（§7.7 表里是验收口径，真机未量）。

9. **`images.decode` 的相对路径口径**（2026-09-25 实测记账，影响 §9.2/§12.3 的示例写法）：
   `:domain` 的 `ImageAnalyzer.decode` KDoc 写着「路径解析（相对项目根 or filesDir）由实现定」，但**四层里没有任何一层解析路径**（计算核 `std::fopen`/`cv::imread` 直取、
   装载面与 `NativeImageAnalyzer` 原样透传、handler 只挡空白串）。host 侧实测把这条钉死了：传相对路径时按**进程 CWD** 解析——同一个文件，绝对写法与「chdir 到该目录 + 相对写法」都回 `ERR_IO(3)`（说明相对写法确实命中到了文件），
   而不存在的相对路径回 `ERR_FILE_NOT_FOUND(2)`。`libopencv.so` 载在 `:main` 进程里，那个进程的 CWD 是 `/`（Android 对 zygote 后代的固定行为），于是脚本写 `images.decode('part.png')` 会在根目录找一个并不存在的文件—
   —**回的是 `ERR_FILE_NOT_FOUND`，且报的路径是对的**，所以看起来像"文件真的不在"，不像"口径没定"。
   两条出路，代价不同：
   - (a) **就在契约里写明"路径必须是绝对的"**（示例改成 `/sdcard/...` 或让脚本自己拼 `filesDir`）。零实现改动，代价是 v9 的 `fromFile('part.png')` 这种相对用法在 AutoScript 直接不成立，脚本要改写法。
   - (b) **在 handler 层加一层基准解析**（相对路径按项目根 / `filesDir` 拼绝对再往下传）。保住 v9 的写法，代价是要定"基准是谁"（项目根？脚本所在目录？filesDir？）——**三选一本身又是一个要拍板的策略**，且 §9.2 的「不做路径策略」那条边界要重画。
   **已拍板 (a)**（2026-09-25）：路径必须是绝对的 —— 把"相对路径"从契约里去掉而不是猜一个基准。(b) 不给：基准三选一本身又是一个策略，且 §9.2「不做路径策略」的边界不重画。**§12.3 已按 (a) 改写**：示例路径一律绝对（`fromFile('/sdcard/part.png')`），并写明了相对写法为什么回 `ERR_FILE_NOT_FOUND`。
   **跟进动作已执行**（同日 `a0a802e`）：`:domain` `ImageAnalyzer.decode` 的「路径解析由实现定」收紧为"路径**必须是绝对的**"，facade `images.ts` 注释同步。

---

## 19. 结语

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
— 目录约定（`files/.autojs` 两个持久寄存器 + `files/scripts` 项目根 + `cacheDir/npm-cache`）与持久句柄的成对释放都收在它里面，`AppShellApplication.onCreate` 在 IO 域调它（`installWithFiles`），
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
AUTO 选路/强制降级拒绝/通知回调回投 + TTL 双清）；仍待的是 MediaProjection 高清会话（授权 UI + FGS，换 producer 即插）；脚本内容侧装配期补部署已接上（`ScriptDeployRecovery` 在 `AppShellKit.assemble` 时跑一次：
只补缺不覆盖、空清单如实为空、失败不投毒，`deployReport`/`deployFailures()` 随壳暴露给能力中心）；§8.4 已闭环（判据/采样/`EngineWatchdog` 调度/`HeartbeatLedger` 心跳打点；
pid 归属表仍归在途账不另建），Kotlin spawn 半边已送 pid 与心跳、桥监听 `BridgeSocketListener` 已接、addon JS 消费面 `attachNative` 已接（见 §8.4 末），设备面只剩真机联调（facade dist 随包 + 打包入口 attach 接线与 jniLibs 三件套/
addon 落位 2026-09-24 均已落 —— assets 构建拷贝 → `BridgeDistDeploy` 落位 `filesDir/node_modules/auto` → env 注入 → kBootstrap `attachNative`，全链有 `BridgeDistPackagingEntryTest`；
二进制侧 `prepareEngineNativeLibs` → `lib/arm64-v8a/{libnoden,libnode,libc++_shared}.so` + addon 走 assets → `BridgeAddonDeploy` → `addonPath`，APK 条目已实测）一道；§8.3 的 drift 已有裁决方（`EngineWatchdog` drift 连段 + `KillCause.DRIFT`：
连续 3 轮对不上杀掉重来）；§8.6 已闭环（dispatcher 排队默认上限按触发源分级 + `PendingRun` deadline 记账与过期不重投；**无人 await 的 run 自带期限（2026-10-01）**：
`TimeoutEnforcer{WATCHDOG}` + 看门狗期限线（`KillCause.TIMEOUT`）+ `engines.exec` 的 `timeoutMillis` 必填；注册表持久 `TaskStore`/`FileTaskStore`（`tasks.jsonl`，upsert+tombstone，
与意图日志同一 `.autojs` 目录、同一追加纪律）：`schedule`/`cancel` 先落盘后动内存/闹钟，`bootRecover` 先 `restoreTasks` 续排再重投意向，`AppShellKit` 建第三持久并随壳释放），
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
与 a11y 同底），MediaProjection 高清会话是后续升级（换 producer 即插），不再是接线缺口。**`images` 桥面与 native 真实现均已接，P1 桥消费方五算子也已于 2026-09-29 全开**（`toGrayscale`/
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
setup(onFrame) 按 id 结算 + invoke 注入 + `errFromThrown` 保留真码，mock 6 例 + env 门禁真 addon 全环），仍待真机：设备侧 exec/dlopen 红测（16KB 页机 + targetSdk 提取策略；
jniLibs 三件套与 addon 落位、facade dist 随包与打包入口 attach 接线均已落，见第 2 条切片路线）。

---

## 文档边界（拆分后）

本文件（与 [`framework-design.md`](../framework-design.md) 导航的 `design/` 12 卷）是**契约的
单一事实来源**：只写「是什么 / 为什么这么设计」。哪份文档回答什么，表已 2026-10-02 搬到
[`docs/README.md`](../README.md)（backlog C9），原地只留指针 —— 一句话版：
[`design-decisions.md`](../design-decisions.md) 管「为什么这么定 / 什么被改过」，
[`design-status.md`](../design-status.md) 管「实现到哪了」（当前状态页；历史流水见 [`log/`](../log/)、
实现注记见 [`implementation-notes.md`](../implementation-notes.md)）。

**推进顺序与接线现状不在本文件**，见 [`design-status.md`](../design-status.md) —— 那里是
唯一权威（原 §12.2 接线现状表仍在本文件 §12.2，属契约的一部分，两条互为印证时以
更晚的日期为准）。
