# AutoScript 决策记录

> **本文件收两类东西**：① 已拍板的开放决策点（从框架设计 §18 外迁，
> §号与编号保持原样）；② 被实测推翻/改过的口径（原口径与新口径并列，**不删旧**）。
>
> 契约在 [`docs/design/`](design/) 12 卷（入口 [`framework-design.md`](framework-design.md) 索引）；落地状态见
> [`design-status.md`](design-status.md)。三者分工：
> **契约说「是什么」，台账说「实现到哪」，本文件说「为什么这么定、什么被改过」。**
>
> **只追加**：新决策按日期加在「已拍板」顶部；推翻旧口径时在「已推翻」加一行，
> 原文照抄，不编辑历史。

---

## 已拍板（原 §18 全部九项：第 1–7 项 2026-09-26、第 8/9 项 2026-09-25；外加后续新增编号项）

2026-10-01 拍板（§8.5/§8.6 收口：无人 await 的 run 期限线；非 §18 编号项，原口径不涉）：

14. **时限归属判据 = 「发起方等不等」，不是「有没有声明超时」**：
    §8.6 原先自己记的诚实边界是「引擎侧 `waitCompletion` 超时不发起的场景还没人管（在途 run 没人收尾时 watchdog 是唯一兜底）」——
    而看门狗三路判据全看**进程表现**（心跳/CPU/RSS）：心跳正常、CPU 空闲、RSS 很低的长跑脚本三路都判健康，**没有任何一路收得住它**。
    - **判据选型**：加 `PoolAcquireRequest.timeoutEnforcer: TimeoutEnforcer{AWAITER, WATCHDOG}`（缺省 `AWAITER` = 调度链路，行为零改）。
      为什么不按「`EngineRunRequest.timeoutMillis != null` 就交给看门狗」判：调度链路也声明 `timeoutMillis`（透传给执行体），
      但它**自己 await 终结并超时强杀**（`ControllerRunDispatcher` → `killRun(REQUESTED)`）—— 声明了却已经有人在执行，看门狗再收一次就是两套到期口径。
      判据于是落在**谁 await** 上：无人 await 的（桥 `engines.exec`）才把期限线交给看门狗。
    - **期限线的位置**：`RuntimeController.WatchAnchor.deadlineMillis = startedAt + scriptTimeoutMillis`（纯函数，期限定下不再改），
      `EngineWatchdog.tick()` 里**先于** pid/心跳那两条「量不到」分支判 —— 期限不依赖任何度量，排在后面会让「宿主不给 pid」或「心跳未接线」的 run 连期限都够不着。
      落 `KillCause.TIMEOUT`（归 CRASHED：到点强制收账，不是调用方主动停；`Tick.timeoutKilled` 单列，与「病死」「分歧杀」分账）。
    - **`engines.exec` 的 `timeoutMillis` 由可选改必填**：缺席/`null`/`<= 0` → `ERR_INVALID_PARAM`（`PoolAcquireRequest.init` 里 `require` 同款守卫）。
      缺省值在这里没有诚实来源 —— 编一个（30s？5min？）等于替脚本静默决定它能跑多久，与 `queueTimeoutMillis` 传 0 视为漏配同一条纪律：**响亮失败**。
      JS 侧**不预检**（同 payload 发出去、宿主回错），两处校验必然漂移（与空事件名同一条纪律）。
    - **仍待覆盖**：期限只覆盖声明了期限的 run，且只在看门狗轮转真在跑时有效；池空退避周期内新起的 run 最坏晚一个退避周期才被看到。

2026-10-01 拍板（文档计数门口径；非 §18 编号项，原口径不涉）：

15. **文档里的「模块数 / 测试任务数」保持「手写 + 派生校验」，不改生成片段**（外审建议 → **作者本人撤回**）：
    第二次外审的文档组曾建议把 `ModuleGraphTest` 读的那三处文档数字（`CLAUDE.md`、`docs/design/06-modules.md`、
    `.github/workflows/ci.yml`）改成「生成到片段、文档 include」，理由是「改散文就会红」。**2026-10-01 作者撤回该建议，
    裁定「保持现状，不动作」**（条目已从 `docs/backlog.md` 移出，按该文件第 1 条纪律落到这里）。理由：
    - **前提不成立**：该测试**只在「散文写了数字且与派生值不等」时红**；改散文、或干脆不写数字，都不会红。
      「散文被测试绑住」的印象是错的 —— 这正是这条纪律按设计工作（数字可写，但必须等于派生值）。
    - **生成片段是口径变更，不是小改进**：能生成的只有数字本身；「15 个模块」「13 个测试任务」是嵌在句子里的
      人话（还带着「即全部带 `src/test` 的模块」这类限定），没法机器生成。为此把契约散文降级成生成物，
      与 `docs/design/` 「人读的契约」定位相悖。
    - **两条附带观察，均不构成动作**（记录备查）：(a) 抓取正则 `(\d+)\s*个?\s*(模块|测试任务)` 偏宽 ——
      任何 `N 个模块` 的写法都会被抓；逃逸阀是 `quotedCounts` 白名单（现仅一条：§6 引的批判建议原文
      「约 12 个模块」），引文照此登记即可。(b) `ciTestTasks` 只认含 `./gradlew` 的行 —— 方向是**缺省即红**
      （新模块漏登记 CI 任务行会被 `withTests == ciTestTasks` 断言抓住），比反向宽松安全。
    - `domain/build.gradle.kts` 已把被扫文件声明为 `inputs.files(...)`（只改文档不会 UP-TO-DATE 静默跳过门），
      该做对的地方保持不动。

2026-10-01 拍板（安全防线：本地状态不进系统备份；非 §18 编号项，原口径不涉）：

16. **`files/.autojs/` 不参与系统备份与换机迁移 —— `allowBackup="false"`，契约侧登记为 §11.2 新增 T9**：
    依 §11.4「新增/加强防线走 §18 决策台账，不在设计文档里悄悄添」，防线先在这里记账再落契约。
    - **要守的是什么**：`.autojs/` 里没有可再生的用户数据，全是**信任锚与审计面** —— `lock.sig`（「这份 lock
      是本机签过的」§10.5-1）、审批台账（`pkg+versionHash` 的 APPROVED）、安装 journal/history、意图日志。
    - **为什么关**：默认（`allowBackup` 缺省 = true）它们进 Auto Backup / D2D 迁移，落到**另一个设备上下文**：
      `lock.sig` 在新机上验不过（密钥不出本机，Keystore 不随备份走），而审批台账与安装 history 会以
      「已批准 / 已完成」的姿态照常出现 —— 恢复出来的状态**既不可信也不可解释**；`adb backup` 还是一条把
      信任锚整包拷出设备的现成路径。
    - **代价（如实说）**：换机不再自动带走过往审批与历史，需要重装/重批 —— 这是有意的，不是遗漏。
    - **落点**：`app/src/main/AndroidManifest.xml`（`:app` 是唯一设 application 级属性的模块，库 manifest 合并面
      不动）；契约行在 §11.2 T9。

2026-10-01 拍板（backlog 批 7 的三项 S 级；非 §18 编号项，原口径不涉）：

17. **`:app-service:permission-center` 维持独立模块，不并回**（backlog D1，91 行的小模块）：
    外部审查的疑问是「独立模块偏重」，逐条核对依赖图后维持现状 ——
    - **它是 §9.5 那条例外的物理载体**：契约写「所有模块不得直接查 Settings/ActivityCompat，一律经此门禁」，
      而 `:platform:*` 的 archUnit 黑名单含 `com.autoscript.appservice..`（所以门禁不能放在平台模块里）。
      独立模块把这条边界变成**依赖图上的硬边**（`ModuleGraphTest`：`permission-center → :domain` 单点），
      并回任何 `:app-service:*` 则要把「app-service 内部不许直查系统设置」变成口头约定 —— 用架构手段守的边界
      不该降级成约定。
    - **并回的代价大于收益**：它只依赖 `:domain`；并进 runtime/scheduler 任一个都要给那个模块新增一条上游
      依赖或开子包，而那些模块已经有各自的清晰职责。
    - **「等它长」已有征兆**：门禁面在长（`Capability` 九项），行为面（reader/launcher 两道缝）也在长。
    - 记这条是为了让下次再看到「91 行」时不必重查一遍。

18. **命名统一到此为止：`.autojs` 目录与 `autojs-lock-v1` 前缀**保留**（backlog D6 的剩余部分）**：
    描述面（9 处 `@autojs/*`）已按事实侧改成 `auto` / `bridge_native.node`（`AutoJsPro` 九处保留 —— 那是
    **对标产品名**）。但存储面两处**不改**，理由是它们不是命名而是**已落盘的格式**：
    - `files/.autojs/` 是 §10.2 写进契约的存储布局（`lock.sig`/审批账/journal/history 的落位），实现与测试
      共 30+ 处一致使用；
    - `autojs-lock-v1|` 是 **lock 签名前缀**（§10.5-1 带外信任锚的输入串）。改它 = 旧设备上已签的
      `lock.sig` **全部验不过**，而失败形态是「明明签过的 lock 被判未签」—— 对一个安全锚来说是往危险方向退。
    - 要改的话须带兼容策略（新前缀 + 旧前缀验签回退，或一次性重签），属契约变更，须先拍板。
    - **发布用的 npm scope 归属未核**（`bridge/js` 标 `"private": true`、不发布 registry）—— 需维护者确认。

19. **第三方许可声明 = 生成物，且随 APK 分发**（backlog D8；`README.md` 许可节同批改写）：
    - **为什么生成**：版本事实来源是 `node-runtime-build/VERSIONS.env`（Node/NDK/OpenCV/KleidiCV/npm 全部
      钉在那里），手写声明必然在某次版本变更后与事实脱节，而**许可声明脱节在分发时是法律问题**。
      `node-runtime-build/licenses/gen-notices.mjs` 从 VERSIONS.env 渲染 `THIRD_PARTY_NOTICES.md`，
      CI 有一道 `gen-notices.mjs && git diff --exit-code` 门（照抄 js-tests 的 `gen:wire` 形状）。
    - **原文与清单分开**：逐字许可原文入 `node-runtime-build/licenses/`（Node 1586 行 / OpenCV /
      KleidiCV / libjpeg-turbo 双许可含 IJG 原文 / libpng / zlib），生成器**只渲染清单面**——
      转述上游许可即失真，改原文即伪造。
    - **随 APK 分发**：仓里一份只解决审计面；装到用户手机上的二进制，其许可条款必须**随分发可达**，
      故 `prepareNoticesAssets`（`autoscript.engine-natives` 约定插件）把它与七份原文拷进
      `assets/third-party/`。**判据与前三件不同**：本件在 git 里（生成物已入库），缺件是**仓库破损**而不是
      「本机没构建」——按 `bridgeDist` 口径红，不按选填件口径只 warn。
    - **NDK/libc++ 一行如实写「见 NDK 随附 NOTICE（未随包）」**：工具链 NOTICE 体积大且随 NDK 分发，
      声明它而不复制，比复制一份可能过期的副本诚实。

20. **APK 体积超支：接受 + 在能力中心明示安装体积**（backlog E1；§15 唯一被实测推翻的条目）：
    - **选项 (a)**，2026-10-02 拍板。(b) 按需分发与 (c) 继续裁 OpenCV 面都否掉，理由是实测：
      OpenCV 只占 7.0 MiB / 6.6%（按 ICU 后 92MB 分母折算，原 7.8%/8.6% 同样不改变结论），
      **(c) 的收益上限就是那 6.6%**，而 `imgcodecs` 已只留 PNG/JPEG，再裁要动 SPI 承诺；
      (b) 要给 `libnode.so` 找 exec 之外的落位链（§19 未解决），是比"披露一个大数字"大得多的工程。
    - **改的是披露，不是预算**：§15 表里 `≤ 40MB release` 那一行**保留原样并标注已超支**，
      证据链（`node-slice` artifact 体积 + `SHASUMS256` + `VERSIONS.env`）也保留。推翻一个契约要留账，
      静默把数字改大就是"账被擦了"。
    - **UI 给的是实测值，不是抄文档**：§15 记的 ≈92MB 是 node-slice 产物的**未压缩** jniLibs
      三件套合计，用户装的是**压缩后**的 APK，两者不是同一个数。抄文档数字进 UI 就是呈现层说谎。
      故 `InstallSizeRead`（`:app`，纯 JVM 可测）量 `applicationInfo.sourceDir` 与
      `nativeLibraryDir` 的**真实文件**，`CapabilityCenterSnapshot.installSize` 传下去，
      `InstallSizeState.text()` 出文案（`MiB` 口径，不与 MB 混用）。
    - **三件都要说全**：总数、其中引擎那一段（超支全在它身上，不分段就答不上"为什么这么大"）、
      引擎 .so 在不在。**量不到就说未量到，不显示 0** —— 0 会被读成"安装包是空的"；
      引擎缺失时体积照报（同一条纪律的另一面：装了个跑不了脚本的壳是事实，隐去比显示更糟）。

21. **shell 捕获输出超限：静默截断 + 显式日志 Warning + 返回截断标志**（backlog A2b；§9.6 原口径只写「双流并发读干」，没定过上限）：
    - **背景**：`AndroidShellExecutor.PipeReader` 把 stdout/stderr **全量**读进内存，一条 `cat` 大文件
      就能把宿主撑爆。这是 2026-10-01 修 A2（超时/收尸）时露出的口子，当时刻意不夹带 ——
      加 cap 是**契约口径**变更，两条路都合法且代价不同：**超限报错**让合法的大输出直接失败，
      **截断**有损但可用。故先拍板再动手。
    - **拍板**：**截断**。「静默」指的是**调用方不因此失败**（不抛 `ERR_*`、`code` 仍是子进程真实
      退出码、`isSuccess` 语义不变），**不是瞒着** —— 三处同时留痕，缺一处就是悄悄丢字节：
      ① `ShellResult.truncated`（**程序**读的那个，纯增量字段、缺省 false）；
      ② 可注入 `LogSink` 的 Warning（**运维**读的那个，真机进 logcat，带命令本身与丢弃字节数）；
      ③ 被截那条流末尾追加 `TRUNCATION_MARK` 说明行（**人**读的那个 —— 否则半截输出看起来
      就是一条正常结束的输出）。
    - **上限 = 每条流 1 MiB**（`ShellCaptureLimit.MAX_CAPTURE_BYTES`），不是随手取的整数，三条约束：
      ① 正常 shell 输出是 KB 级，1 MiB 对合法用途"够不着"；② 1 MiB 文本 JSON 编码后仍 ≈1 MiB，
      远在单帧上限 8 MiB（§7.5）之下；③ **最坏情况**：`DomainJson.appendQuoted` 把 `c.code < 0x20`
      转义成 `\uXXXX`（**6 倍**膨胀，非估算 —— `ShellCaptureLimitTest` 拿 `DomainJson` 真编一遍钉住），
      1 MiB × 6 ≈ 6 MiB 仍不触顶。**这条余量是必须的**：单帧超限在桥上是 `FrameTooLargeException`
      → 读循环 `break` → **关连接**，比截断重得多，所以上限必须在实现侧先兜住。
    - **两条流各自计数**（stdout 截了不挤掉 stderr 的额度）：错误信息短、且最该留住，
      共用一份额度会让啰嗦的 stdout 把它挤掉。
    - **到顶后仍然读到 EOF**（关键，写错就退化成死锁）：只是不再往缓冲里放字节。停下来不读的话，
      子进程会阻塞在写满的管道上 —— 那正是 §9.6「双流必须并发读干」要防的死锁，截断反而把它请回来。
      `AndroidShellExecutorTest` 用记账读端（`CountingStream`）钉住"全部字节都被读走"。
    - **日志不走 `android.util.Log`**：`:platform:system` 的 JVM 单测没有 `isReturnDefaultValues`，
      直接调会抛 "not mocked"，一条"输出超限"的告警不该把测试判红。故走可注入的 `LogSink`
      （缺省 `java.util.logging`，其 `ConsoleHandler` 写 `System.err`，Android 把 `System.err`
      重定向进 logcat —— 落得到，但 tag 不叫包名；要精确 tag/优先级就注入自己的 `LogSink`）。
    - **落地面**：`ShellContracts.kt`（`truncated` 字段）/ `ShellCaptureLimit.kt`（上限 + 日志缝）/
      `AndroidShellExecutor.kt`（cap + 播报）/ `ShellNamespaceHandler.kt`（四字段载荷）/
      `bridge/js/src/extras.ts`（`ShellResult.truncated`，**双侧逐字对齐**）；契约正文见 §9.6，
      示例见 §12.3。

2026-10-02 拍板（backlog C6；文档架构，非 §18 编号项）：

22. **`design-status.md` 按「当前状态页 + 按日期的流水切片 + 实现注记」三分，§ 锚与只追加纪律不动**：
    - **背景**：该文件 175 KB —— 头部与接口期表 ~5 KB，流水段 108 KB（35 条，全挤在一节里），
      实现注记段 59 KB（自各分卷外迁的逐字叙事）。外审建议拆「当前状态页 + 按日期的日志文件」。
    - **口径**：① `design-status.md` 只留**接口期表 + 流水目录**（按日期的表，逐行链到切片）；
      ② 流水按日期切片到 `docs/log/<YYYY-MM-DD>.md`（本次四片：09-29 / 09-30 / 10-01 / 10-02，
      共 35 条），索引 `docs/log/README.md`；③ 实现注记（含「各分卷实现注记的搬迁状态」两表）
      搬到 `docs/implementation-notes.md`。
    - **纪律不变**：**只追加** —— 新条目加在**当天那个切片**顶部（文件不存在就新建），
      并把 `design-status.md` 目录表里那一行的条目计数 +1；被推翻的记账不删除，原地标作废。
    - **§ 锚不动（本次最要紧的一条）**：`09-capabilities.md` / `08-execution.md` 等卷正文里
      9 处 `design-status.md#实现注记自各分卷外迁逐字保留` 指向的是**那个标题**，不是内容 ——
      故在 `design-status.md` **逐字保留同名一节**做占位 + 指针，那些链接一条都没改。
      § 号仍是唯一权威锚，不随文件位置变化；分卷正文里的 `§X.Y` 引用不受影响。
    - **搬迁是逐字的**：四片切片拼接 == 原 45–1016 行、实现注记正文 == 原 1022–1372 行
      （按字节比对通过）；切片之间原有的空行原样保留，只在搬运处改相对链接深度
      （`log/` 比 `docs/` 深一层：`design-decisions.md` → `../design-decisions.md`）。
    - **为什么不是 `CHANGELOG`**：本项与本文件第 21 项（外审 C5「不设 CHANGELOG」）不冲突 ——
      拆的是**同一个台账文件**，不是新开第二个事实来源；切片仍是「流水」，只是按日期分了文件。
    - **留下的锚代价**：`design-status.md` 里「实现注记」一节是**空壳**（只指向
      `implementation-notes.md`），为的就是不动那 9 条分卷链接 —— 若将来愿意改那 9 条链接，
      这个空壳可以删。

2026-10-02 拍板（backlog C9；文档架构，非 §18 编号项）：

23. **`docs/README.md` 作为 `docs/` 的总索引；「文档边界」表**搬**过去、原地留指针，不并列两份**：
    - **要解决的是什么**：外审 C6 建议里的第三件（拆 C6 时未做，登记为 C9）。当时的顾虑是
      **同一事实写两遍** —— `framework-design.md` 的「文档边界」表 + 根 `README.md` 的仓库地图
      已经覆盖了「哪份文档回答什么」，再加一份并列的表就是第二个事实来源（判据同本文件第 15 项）。
    - **口径**：**搬家不是并列**。`docs/README.md` 收下这张表并**扩成完整地图**（12 卷契约 /
      入口 / 决策 / 状态页 / 流水切片 / 实现注记 / 待办池 / 归档，逐行带**纪律**一列）；
      `framework-design.md` 与 `design/18-19-ledger.md` 那两处「文档边界」段**原地换成指针**。
      于是这张表在仓库里**只有一份**，位置从「契约入口的附带一节」升成「`docs/` 自己的门面」——
      导航本就该在这里（读 `docs/` 的人第一眼要看见的就是它），而不是藏在某一卷的末尾。
    - **为什么不是「再加一份并列的表」**：并列两份的下场是可预期的漂移（改一份忘一份），
      而这类漂移**没有门能抓**（`ModuleGraphTest` 只派生模块数 / 测试任务数，抓不到散文表）。
      本仓对这件事的一贯处置是「同一事实只写一份，其余地方留指针」（见第 15 项与第 21/22 项）。
    - **没动的**：`§ 号仍是唯一权威锚`、**只追加**纪律、`docs/log/README.md`（那是**流水切片的索引**，
      按日期逐条列条目，与本文档地图**不是同一份**，两者关系在 `docs/README.md` 末尾点明）；
      `CONTRIBUTING.md` 的四份文档分工表**保留**（它讲的是「写东西时的纪律」，地图由 `docs/README.md` 承担，
      两处已在文字里各自点明分工，不再互为副本）。
    - **代价（明写）**：多一个文件要跟着改；换来的是「哪份文档回答什么」有唯一落点，
      且 `docs/` 目录自己有了入口（此前从根 `README.md` 或 `framework-design.md` 绕进来）。

2026-10-02 拍板（backlog E2；图像算子预算，非 §18 编号项）：

24. **`matchTemplate` 拆 std/相位双门 + 负结果回精确兜底（E2 裁「修」，不是改口径）**：
    - **前提先纠（E2 起草数字已过期）**：E2 引的「A4 933.6ms、A2 计算段 1912.8ms 均 ❌」
      是 2026-09-30 首测数；2026-10-01 的相位平均 + FastPath 12a + 场景缓存（§7.7 五次
      实测）已把 A4 370×80 打到 **19.82ms ✅**、A2 **88.86ms ✅**。E2 的实际残余只有
      **48×48 全帧形态**：真机模板 std 6.29<12（std 门）+ `phase_worst` 0.6742<带宽
      0.75（相位门）双拦 → 恒精确路径 855~948ms（判据 <40ms）。
    - **拍板：修**。拆掉 std 门与相位门（尺寸门 `min_templ_side=48` /
      `kMinCoarseSide=12` 保留），`match_pyramid` 负结果加**回全图精确路径的兜底**
      （`best.conf >= thr ? best : match_exact(h, n, thr)`）。相位探针 `phase_probe` /
      `NeedlePrep.phase_worst` 随门退役（只服务那道门，留着是死代码）；
      `build_phase_avg` 相位平均粗模板**保留** —— 它治的是粗筛召回，不是门。
    - **为什么是兜底而不是调门**：两门的唯一职责是防假漏检，它们拿「挡回精确路径」
      当挡错时的代价 —— 白付一次全图 matchTemplate（473~1500ms）还没解决问题。兜底
      把风险面反过来：**假漏检结构性消除**（强制精确 vs 金字塔的答案差从语料统计问题
      变成构造性质），挡错的代价封顶为「多付一次粗筛」（host 实测噪声负例 +1%）。
      门挡对时省的那一次粗筛（≈0.1× 全图成本）买不起它挡错时的赔率。**「调门」路线
      同批否决**：host 复量证明 0.5× 档能过相位门但 std 门仍拦、`kMinCoarseSide`
      12→13 不改变任何一档 —— 调参是在坏机制上挪数，不动机制救不了（§7.7 第七次块）。
    - **实测（host x86_64，同一 OpenCV pin，1080×2400 真机截图，thr=0.9）**：48×48
      全帧 532→**33.0ms**（16×，两路径均命中 conf=1.0000，报位差属重复副本 argmax
      口径）；370×80 回归 508→32.9ms；噪声负例 45.3→45.8ms（兜底代价 = 粗筛本身）。
      host 语义门九个二进制 **422 检查 0 失败**（case6c 高频反例的守护对象从「频率门」
      换成「兜底」，断言一字未改）。数字与机制勘误全文见 §7.7 第七次块。
    - **诚实边界（明写）**：判据状态以**真机**为准 —— 2026-10-02 同日补跑（同机同
      `scr.raw` 同 bench ×100 中位，同 so `FORCE_EXACT` A/B，全文见 §7.7 第七次块真机
      表）：48×48 全帧 855~948ms → **64.43ms**（13.7×，同轮强制精确 882.25ms），**仍
      差 1.6× 未过 <40ms 判据** —— 判据行该形态继续如实挂 ❌，改善记档不冒充达标；
      370×80 19.67ms、A2 88.20ms 无回归，region/A3 全绿。残余立项 backlog E5。原
      「四层压住假漏检」中 std/相位两门的那两层由兜底取代；§7.7 三次
      实测块「零候选 → 必假漏检」的机制描述同批**勘误为「名额挤兑」**（真值排 327，
      K=32 被假峰吃满；只追加勘误，原文不动）。判据 <40ms **原文不改**（与第 13 项
      ③ 同一纪律：改的是数字，不是口径）。
    - **没选「改口径」**：把 48×48 挂到「出路 = region 16.9ms 推荐姿势」了事也能消 ❌，
      但那是把没解决的问题改成文字问题；region 仍推荐，全帧判据继续按原文验。

2026-10-02 拍板（backlog E5；小模板形态判据放宽，非 §18 编号项）：

25. **`matchTemplate` 48×48 小模板全帧形态判据放宽为 <100ms（实测 64.43ms 接受转绿）；E5 关闭、不投优化**：
    - **拍板 = 降低要求、接受现状**：48×48 小模板全帧形态不再套 `<40ms`（第七次真机实测
      中位 64.43ms / run 内 max 72.47ms），该形态判据放宽为 **<100ms** —— 1.5× 实测余量
      盖住 run 噪声与机型差，仍比同轮强制精确路径（882.25ms）严 8.8×、比首测（862.4ms）
      严 8.6×。**常规形态 `<40ms` 原文不动**（370×80 真机 19.67ms 支撑；第 13 项 ③ 与
      第 24 项「原文不改」对常规形态继续成立 —— 本项只给小模板形态开口子，不是把
      40ms 改成 100ms）。
    - **E5 关闭 = 不投优化**：K=32 候选面（NMS 在 270×600 结果面扫 32 轮 + 32 个精配窗）
      不收紧、懒惰 NMS / 带宽收窄都不做 —— 继续压的收益是 64→<40ms，被裁定不值当。
      region 17ms 姿势照旧是低延迟推荐，但不再是「全帧 ❌ 的出路」（该形态已转绿）。
    - **落点（同批）**：§7.7 判据行目标列注分形态、第七次块真机表 48×48 行标
      `~~❌ 差 1.6×~~ → ✅（放宽后）`（原数字保留，只追加标注）、§15 预算表
      （`13-roadmap backlog` 行同注）、design-status 接口期行、backlog E5 划掉。
    - **代价（明写）**：放宽后的 100ms 红线仍能抓「退化回精确路径级」的巨幅回归
      （882ms 的 1/9），但抓不到 100~120ms 的小劣化 —— 换掉的是这类灵敏度，如实认账。

2026-10-02 拍板并落地（backlog A6 + E4 合批；node-runtime-build 素材线，非 §18 编号项）：

26. **vendored npm 素材来源换 registry 发布态 tarball（`npm@12.2.0`）；E4 随换源天然达成**：
    - **A6 要解决的**：素材取自 Node 源码树 `deps/npm` = npm 11.19.0 ≠ §10.1 脊梁的
      「npm 12.x 系」；「等 Node 线携带」已实测否掉（868 条官方发布无一携带 12.x）。
      **先核实后动手**（backlog 标的两个「未核实」当天查完）：① npm 12 **存在** ——
      registry 索引 latest = **12.2.0**（sha1 `9b58e3ad…`，2026-10-02 实抓）；
      ② `allowScripts` 默认语义 —— **半对**：host node 22.23.1 直跑解包产物实测，
      npm 12.2.0 **依赖** lifecycle 默认拒绝（`allow-scripts` 白名单缺省为空，拦时带
      `npm warn install-scripts` 播报 + `install-scripts approve` 放行通道 —— 禁静默
      达标），`allow-git=none`、`allow-remote=none` 逐字吻合；**项目自身** lifecycle
      仍执行（历代如此）。契约 §10.1「拒绝全部 lifecycle」措辞同批精化为「依赖
      lifecycle」，旧「现状是 11.19.0」注标作废。
    - **口径**：换源 —— `VERSIONS.env` 钉 `NPM_CLI_VERSION=12.2.0` + `NPM_CLI_SHA1`，
      `fetch-and-build.sh` §9 下载（落 `$DL`，随工作流下载层缓存）+ sha1 校验 + 解包，
      原有剪裁 / 点条目清零 / 三锚在场 / 版本断言 / 基表**全部照旧**。原「不另下
      registry tarball」的理由（版本必须与 libnode 同一条「钉死 + 全链回归」纪律）
      **没有丢**：改这两个值即命中 node-slice 的 paths 触发面 → 全链回归，§9 还有
      sha1 + 版本双闸。
    - **E4 随换源天然达成**：registry 发布态没有 `test/`（1.9MB 表观）与
      `tap-snapshots/`（816K）—— 不是剪掉的，是**发布形态本来就不带**；§9 干跑实测
      素材 1846 文件/11MiB 表观 → **1674 文件/9MiB**、占盘 18M → 16M（净差 −172 文件 /
      −2MiB 表观，与 E4 登记的 2.7MB 两目录毛估同量级，净差略小属正常 —— registry 形态
      与源码树互有增减）。剪裁清单 docs/man 保留（发布态仍带
      176+89 个文件）。
    - **验证**：§9 段落本地干跑（假 `$DL`/`$SRC`/`$OUT`）两轮通过 —— 下载/校验/解包/
      剪裁/点条目清零/三锚在场/版本断言/基表全绿；**node-slice 全链回归在 CI 跑**
      （本机禁编 Node，CLAUDE.md 口径），出库数字与旧值对比记进当天流水。
    - **代价（明写）**：素材与 Node 源码树**版本解耦** —— 以后升 Node 不再自动带
      npm 升级（各自钉、各自回归）；换来的是脊梁可兑现。§10.12 残余不变：
      child_process 拦截 shim 仍未落（P0 未排），硬编码 `--ignore-scripts` 主控不撤。

2026-10-02 拍板并落地（backlog C7 + B4 合批；facade 文档面 + 依赖供应链，非 §18 编号项）：

27. **脚本 API 参考用 typedoc 生成、生成物入库并设零 diff 门（C7）；依赖供应链面补 `engines` + audit/SBOM（B4）**（原标题「~~依赖供应链面开 dependency-review 并补 `engines`~~」**作废** —— 2026-10-02 当日勘误，见 B4 条）：
    - **先核实后动手**（backlog C7 的「未评估 typedoc 覆盖度」当天实测）：typedoc
      0.28.20 + `typedoc-plugin-markdown` 4.13.1 跑 `bridge/js/src/index.ts`，**0 error**
      （35 warning 全是「某类型被引用但不在文档里」，非错误）。**但单入口只出 15 页** ——
      `auto` 是匿名对象字面量，typedoc 把它渲染成一串 `__type`，用户向参考没有可链接的
      入口。**修法（本项落地的实质）**：把 `auto` 的形状提成具名 `AutoNamespace` interface
      （字段类型全是 `typeof <现成的 namespace 对象>` —— 与实现同源，**不引入第二份事实**，
      手写签名才会漂移），`export const auto: AutoNamespace = …` 标注之。
    - **形态裁决（生成物入库 + 零 diff 门）**：`docs/api/**` 进 git，CI 的 `js-tests` job
      加一步 `npm run docs:api && git diff --exit-code` —— 与既有的 wire 生成物门、
      `THIRD_PARTY_NOTICES.md` 门**同一条纪律**（生成物入库、漂移即红、手改无意义）。
      选 markdown 而非 HTML：markdown 能被既有的**文档链接门**扫到（相对链接逐条验存在），
      HTML 不能；且 markdown 在 PR 里 diff 得出来。入口面 = `index.ts` 的 export 面
      （作者划的边界），内部件（`runtime`/`bridge` 实现细节、各 namespace 的 `pump*`）
      刻意不进 entryPoints —— 免得把内部件抬成「文档上的 API」。
    - **同批顺手修的两处 KDoc 缺陷**（都是生成物暴露的，不是顺手改代码）：① `sensors.register`
      的 `@param delay` / `@param ignoresUnsupported` 写在**方法** KDoc 里、而参数在 `opts`
      对象里，typedoc 报 "not used" 且用户向文档**看不到这两个选项** → 改成 `opts` 的行内
      KDoc（生成物里逐字可见）；② `console.QueueErrorListener` 是私有类型却出现在公开签名
      `consoleSink.onQueueError` 上 → 提成 `export type`。两处都**不动行为**（`tsc` + 194
      条 facade 单测逐字不变）。
    - **B4（依赖供应链面）**：**2026-10-02 当日勘误（本条原先记的是「开
      `dependency-review` job」，实测红后撤掉，原口径不删，见下方第二段）**：① `package.json`
      补 `engines.node` = **npm 12.2.0 自己的
      engines 逐字**（`^22.22.2 || ^24.15.0 || >=26.0.0`，解包产物实读）—— 不自己发明
      范围，免得与「vendored npm 的宿主要求」两份口径；② 漏洞扫描与 SBOM 进 CI
      （`npm audit --audit-level=low` + `npm sbom --sbom-format cyclonedx` → artifact）。
      **`actions/dependency-review-action` 试过并撤掉（PR #26 实测红）**：报
      「Dependency review is not supported on this repository. Please ensure that
      Dependency graph is enabled」—— 本仓**依赖图未开**（`/dependency-graph/sbom` 404、
      `/dependabot/alerts` 403，两条 API 实测）。开它是**维护者侧的仓库设置开关**
      （Code security and analysis 页，或 `PATCH /repos/{owner}/{repo}` 的
      `security_and_analysis`，两者都需 admin 权限 —— 本仓令牌 403），与 C4 那次
      「只剩维护者动作」同型 → 撤 action、改走**不依赖依赖图**的两条，并把开关登记进
      backlog B4 行等拍板。
      **诚实边界（明写）**：`npm audit` + `npm sbom` 覆盖的是 **npm 面**（facade 的
      devDependencies）；**Gradle 面（`:domain` 之外的 Android 依赖）当前没有漏洞扫描**
      —— 它要么靠依赖图 + `gradle/actions/dependency-submission`，要么自建（独立一件事，
      未排期）。npm 面实测 `npm audit` = **0 vulnerabilities**（2026-10-02，官方 registry）。
    - **代价（明写）**：① 生成物入库 = 改 facade 的公开注释/签名要记得重跑生成器（漏跑 CI 红，
      不是静默）；② `docs/api/**` 现在也在文档链接门的扫描面里（341 条链接），typedoc
      改了链接形状会连带红 —— 这是想要的耦合，不是意外；③ 供应链面当前只覆盖 npm 面
      （见上，dependency-review 已撤）；④ 补 `engines` 不动 CI 的 Node 版本（CI 仍 pin node 24 且 `engine-strict`
      未开，engines 是声明不是门禁）。

2026-09-30 拍板（外部审查整改步骤 7；非 §18 编号项，原口径不涉）：

13. **`images` 匹配链路提速方案**（2026-09-30 评审拍板；A2–A4 实测 ❌ 后的出路裁决）：
   **2026-10-01 追加（`findFeature` 四修；本项标题是"提速"，但 findFeature 那四条是
   正确性修复 —— 记在这里是因为同一个算子族、同一轮实测）**：
   - (a) **场景侧 ORB 配额按短边分档** —— 短边 >640（整屏截图形态）抬到
     `nfeatures=8000 / edgeThreshold=10`，小场景保持缺省 1000/31。病灶：整屏 1000 个
     点全被高对比区吃掉、模板区一个不剩 → **恒 found=0**（这就是真机 UI 上 findFeature
     恒假的原因）。37 缺省值让细条状 UI 被整个切掉，一并抬到 10。代价 29.1 → 46.0ms
     （真机 ×7 中位）—— 与「多花 17ms 换掉一个恒假的算子」相比划算。模板侧**不抬**：
     小图配额从来不是瓶颈（同 1000 档的点一个不落），且缺省值让 host 既有夹具逐字不变。
   - (b) **内点铺开度门** —— 内点在模板坐标里任一边跨度 <10% 判未匹配。假阳的形状就是
     这个：几枚误配凑出同一偏移、inl 够 4 个、conf 还漂亮（真机实测 200×150 报错
     208.7px / inl=7 / conf=0.64），但模板侧坐标只占 5%×3%。门槛取**任一边**够
     （两边都要求会把「横向铺开、纵向只有一行字」的真命中挡掉：真机 300×150 的 y 跨度
     只有 0.107）。
   - (c) **命中位置 = 模板中心**（中位偏移 + 模板半宽高），**取代原「内点在场景中的
     质心」**。质心只在"内点在模板里均匀铺开"时等于模板位置，而 ORB 的内点从来不是
     均匀的（只有文字行/图标边缘出特征）→ 真机偏 **82.4px**（540×600）/ **60.8px**
     （300×150）。改后同批样本 0.0~0.8px。契约字面（§9.2）本就是"模板中心"，
     这是把实现拉回字面，不是加精度。
   - (d) **薄条/小模板零关键点垫边重试** —— ORB 的 `runByImageBorder(kp, size, et)`
     在某边 ≤ 2·et 时清空该层**全部**关键点，与内容无关；缺省 et=31 → 短边 ≤62 的
     模板恒零关键点（1080×60 工具行、120×90 图标面板全中）。**「按短边反解 et」被
     实测否掉**（预筛过了点仍进不了匹配：粗八度 patch 被模板边框裁掉，hamming 19~64、
     ratio 恒 ≥0.75）；**反射复制 32px** 管用（短边 +64 越过门槛）。只对"缺省 ORB
     一个点都没给"的模板生效（有点的模板逐字节不变）。448 样本：对 48→78、错 2→5
     （3 个是场景真有重复区）。重试路径另挂**像素复核**（报出位置严格坐标处
     TM_CCOEFF_NORMED ≥ 0.6；探针实测真命中恒 ≥0.909、纯编 ≤0.501）。
   - **诚实边界**：370×80 纯文字行、48×48 纯色图标仍 found=0 —— 真·无特征，诚实的
     未命中；640 分档门槛按整屏截图形态调，中等裁剪（540×600）找 200×150 仍会漏。

   背景 = §7.7 实测 A4 933.6ms / A2 1912.8ms 双 ❌（判据 <40ms / <700ms）。评估中
   被**否掉**的两条：
   - **帧内缓存频谱**：bench 的 A2 是同帧**同**模板 match×2，看着第二次免费 —— 真实
     脚本多半是同帧**不同**模板，零收益；且 `cv::matchTemplate` 的内部频谱不暴露、
     不可跨调用复用，要真缓存得手写 DFT 相关 + 积分图，数值风险不值。
   - **灰度直配（P0a）**：阈值/置信度语义会漂（灰度化把色差折进亮度加权）——
     「报出去的数字与精确路径一字不动」这条守不住，放弃。
   **已拍板（2026-09-30）四件套**：
   - (1) **金字塔粗筛 + 原像素精配**：灰度 0.25×/0.5× 上只提名 ≤K 候选（thr−margin
     带宽 + NMS 压重复），逐候选回原 4 通道小窗重算 —— 坐标/置信度全来自原像素，
     语义不动；小模板（短边 <80px）/纯色/平噪走精确路径。差分双跑门（host
     `host_match_test`）对拍两条路径；`AUTOSCRIPT_MATCH_FORCE_EXACT=1` 现场回退。
   - (2) **两匹配方法加可选 `region`**（复用 findColor 的 `resolve_region` 判据；
     **region 比模板小 → `ERR_IO`**、越界 → `ERR_INVALID_PARAM`、坐标恒全帧口径）——
     小模板进不了粗筛，缩窗是它唯一的提速出路（bench 决定性行 = 48×48 @ 300×150）。
   - (3) **计算出锁**：`g_mu` 只盖帧表查找（浅拷贝两个 Mat 头即放锁）—— 帧入表后
     不可变（十算子产出新帧不改原帧），900ms 级 match 不再把 release/findColor 关在锁后。
   - (4) **调参口走环境变量**：`MIN_TEMPL_SIDE`/`MARGIN`/`MAX_CANDIDATES` 进程起始
     读入（真机扫参免重编 so），缺省与原 constexpr 逐字相同。
   **判据 <40ms 不改**：region 实测数字回来之前不谈口径（改判据是拍板动作，不混在
   实现里）。附护栏 **频率门**（模板「缩小→放大」自检 <0.8 → 精确路径）是差分门
   首跑抓到的 i.i.d. 噪声假 miss 的修法 —— 方向保守：挡错只损失速度。
   挂起不排期：多核条带切分（独立实验）、4→3 通道、`WITH_OPENCL`（两道门 + 体积代价）。
   **同日真机复测落点（只追加）**：A2 计算段 1912.8 → **171.75ms ✅**；A4 全帧 933.6 →
   **62.98ms**（仍差 1.6×）；**region 两行 11.77 / 16.57ms ✅**；`FORCE_EXACT` A/B 验证
   回退阀 ≈ 旧行为（892.3ms）。~~**判据 <40ms 改不改 = 待拍板**（全帧 ❌ vs region ✅ 的
   口径之争，数字在 §7.7 复测块）—— 本项只记方案拍板，口径另议。~~
   **同日第三次实测后已裁（只追加，上句划线保留原貌）**：
   - **本项「小模板（短边 <80px）走精确路径」的阈值口径被同日评审 patch 改写**：
     `MIN_TEMPL_SIDE` 80→48、`kMinCoarseSide` 24→12、0.25× 候选带宽 +0.05
     （commit `852fb45`，外部 `vision-optimized.patch` 完整验证轮后采纳）——
     370×80 因此从 0.5× 升 0.25× 粗筛，全帧 62.98 → **24.47ms**。
   - **判据行裁决：`matchTemplate 1080p <40ms` 按原全帧口径转绿（形态注明）** ——
     依据 370×80 形态 24.47ms，**判据原文一字未改**；48×48 形态不进绿字（真机
     内容双门拦截恒精确 948ms，如实 ❌，region 16.9ms 推荐）。数字见 §7.7 三次实测块。
   - needle prep 缓存（`g_match_cache_mu` 独立锁）随 patch 一并采纳：模板端准备
     <1ms/次、真机不可测，账在代码评审面（锁序 `g_mu→cache_mu` 已核无反转）。
   - **同日第四次实测后追加（相位探针门，commit `0ec6ef4`，PR #12）**：附护栏
     「频率门 0.8 静态值」被**相位探针门**替换 —— 按粗筛栅格相位反射填充互打取
     真位置 ±2 窗最差分，每调用 `floor = min(thr − 粗带宽 + headroom, 0.97)` 绑
     带宽（静态 0.8 与 thr 脱钩，高阈值下会放行够不着候选带宽的模板 = 假漏；
     子像素相位错位是静态对齐自检测不到的塌陷源）。方向仍保守（拦错只损失速度）。
     另加自适应 K（精配预算定容 8×96×398，窗小 K 升 32）+ 新 knob `HEADROOM`
     （0.05），候选带宽/地板单源 `coarse_margin_of(sc)`。真机 A/B 归因干净
     （A4 25.37ms / A2 95.43ms，B ≈ 三次 B），**判据转绿口径与 48×48 ❌ 形态均
     不变**。数字见 §7.7 第四次实测块。

   - **2026-10-01 追加（FastPath 12a + 场景端粗筛缓存，commits `a22fbfd`/`8d20500`，PR #13）**：
     上面第四次块的「相位探针门」基线之上再叠两件，**不动本项已拍的任何口径**：
     - **FastPath(12a)：提名唯一 + 主峰高置信 → 精配窗收窄一档**。触发 = NMS 后
       `cands.size()==1`（含「第二峰过不了带宽」，探针 370×80：peak1=0.979、
       NMS 后 peak2=0.728 < 带宽 0.75）且主峰 ≥ `thr+0.05`；动作 = pad 由常态
       `ceil(1/sc)·kPadk+2`（0.25× 档 = 14）→ `ceil(1/sc)·1+2`（= 6）。
       **不变式仍是本项 (1) 条那句「坐标/置信度全部回原 4 通道小窗重算」** ——
       12a 只改「窗多大」不改「报什么」；收窄使 CCOEFF 归一化分母随窗缩微变，
       饱和区实测 conf 仍 1.0000 同位（漂移 <1e-4）。差分门 case6d/6d2 逐字段钉住。
       与 12b（提名唯一即整个跳过精配、直接采信粗筛坐标/置信度）**划清界限**：
       12b 会把「报出的数字来自原像素」这条根挖掉 = 语义漂移，**未采纳、不进排期**。
     - **场景端粗筛缓存 `g_scene_prep`（ref → {sc, hs}）**：全帧 `cvtColor(BGRA2GRAY)`
       + `resize(sc)` 按 (帧, 缩放档) memoize。依据与本项 (1) 条同源 ——
       **帧入表后不可变**（十算子产出新帧不改原帧），与 needle prep 缓存（第三次实测
       随 patch 采纳）逐字同一条。**只缓存全帧**：region 是浅视图，其灰度/缩小与
       「先全帧再裁」在小尺度边界有舍入差，缓存键得带 region 才等价 —— region 本来
       就是低延迟出路，不走缓存（原样现算）。**纯 memoize**：结果只由 (帧, sc) 决定，
       缓存命中与否不改变任何出参。锁序 `g_mu → g_match_cache_mu` 与既有两处一致；
       release 同钩子清（帧没了图也没了）。A2「一次截图两次匹配」是它的主消费方。
     - **先算账再动手（方法留档）**：动手前把 23.73ms 拆成 场景 cvtColor ≈5.9（25%）
       + resize ≈1.6（7%）+ 粗筛 ≈11.4（48%）+ 提名/精配 ≈4.8（20%）——
       方法 = region 扫掠线性拟合（斜率 7.4ms/Mpx、截距≈0）+ 组件直测 + HIT/miss
       同面积对消；**这个拆分决定了 12a 的上限（精配段 20%）与场景缓存的账面（前两项
       32%）**，也决定了①（1/16× 粗筛）不值 —— 1/16 省的是粗筛那 48%，但提名爆量
       （`ncand=8`，假峰吃满 K）把精配段翻几倍，端到端 48.1/29.6ms 全部劣于现行
       0.25× 的 10.6ms（host `c16_full` 探针），**数据否掉、不做**。1/16 若再议，
       得先有压住提名爆量的手段（如 1/16 只做「有/没有」预判、坐标仍由 0.25× 定）。
     - **判据与形态口径完全不动**：A4 全帧 370×80 形态 25.37 → **19.82ms** ✅（判据
       <40ms 原文不改，绿字口径同第四次）；48×48 形态两处改动都不进其路径（相位门
       双拦恒精确），867.5ms† 同判 ❌，出路仍是 region 16.8ms。
     - **同会话三方 A/B/C 归因（so = 各 commit 的 CI 产物，交替顺序跑）**：
       | 项 | main | 仅 12a | 12a+场景缓存 |
       |---|---|---|---|
       | A4 370×80 全帧 | 25.76ms | 23.96ms | **20.23ms** |
       | A4-region 370×80 | 10.78ms | **8.68ms** | 9.21ms（噪声‡） |
       | A2 decode+match×2 | 96.74ms | 92.73ms | **89.26ms** |
       12a 单独 = A4 −1.80 / region −2.10 / A2 −4.01；场景缓存再叠 = A4 −3.73 /
       A2 −3.47（≈3.7ms/次重复 match，= cvtColor+resize 生产联合实测）。
       ‡region 不走场景缓存，两 so 该路径代码逐字同 → 差为噪声。数字与口径
       见 §7.7 第五次实测块 + `design-status.md` 流水 2026-10-01 条。

   - **2026-10-01 再追加（大模板形态：相位平均粗模板 + 移除 headroom，commit `34fd80e`）**：
     **本项 (1) 条那句「相位探针量真位置最差分」的口径被改写 —— 门量的对象从
     「相位 0 的 resize 产物」换成「粗筛实际用的相位平均模板」**，理由是同源：
     门若与被门控的对象不同源，门就是在回答另一个问题（本轮第一版正是如此 ——
     模板已换成平均版，门还按相位 0 的 0.7180 挡人，自己挡自己）。
     - **病灶**（真机同 OpenCV 实测）：粗筛假 miss 不是粗模板"太好"，是它**只对住了
       一个相位**。300×150 @0.25 各相位粗分 `1.0000/0.9007/0.6926/0.8943`，thr=0.9
       带宽 0.75 —— 0.6926 够不着 → 提名层空手 → 回精确路径 473~1467ms。
     - **修法 = 对 nuisance 参数做平均**（匹配滤波标准解）：16 相位（0.25×）/ 4 相位
       （0.5×）的粗图按内容原点对齐后取平均当粗模板。最差相位 0.6926 → **0.8086**
       （0.5× 档 0.8269 → 0.9514）。**粗分整体下移**（best 1.0 → 0.97）使提名略保守，
       这正是要的方向。风险与界（探针 `pavg.cpp`）：对结构起支配的模板（UI/文字/图标）
       抬相位地板；对 i.i.d. 高频噪声模板会把结构抹平 —— 那类模板现状本就该被挡回
       （相位 0 自匹配虚高 1.0，任何相位错位都塌），平均只是把"虚高 1.0"换成"诚实的低分"。
     - **headroom 移除**：`floor = min(thr − 带宽, 0.97)`，`AUTOSCRIPT_MATCH_HEADROOM`
       调参口一并删除（**本项第四次追加里「+ 新 knob `HEADROOM`(0.05)」一句作废**，
       保留原貌不删）。依据：带宽之上再留余量是重复上保险（提名 ≠ 命中，候选还要过
       带宽 + 精配在原图重算），300×150 相位 0.8086 对带宽 0.75 只差 0.0086 却被
       floor=0.80 挡回，代价 473~1467ms、收益为 0。
     - **不变式不动**：粗筛仍只提名，坐标/置信度全部回原图 4 通道窗重算 —— 大模板
       四例（300×150/200×150/370×80/120×90）全 found=1、Δpos=(0,0)、Δconf=0.0000
       （对照精确路径 498~767ms）。实测 300×150 **1467 → 13.33ms**、200×150
       **1360 → 10.78ms**。判据行 `<40ms` 原文一字未改，形态注明口径同第四次。

   - **2026-10-01 三次追加（数字口径与可达率的复测，只追加）**：
     - **绝对 ms 不是常量**：同形态换窗口/换会话复测，300×150 得 11.7 / 11.7 / **30.9**ms
       （窗 @(60,600) / @(400,600) / @(60,1500)），200×150 得 9.3 / **21.3**ms，
       370×80 得 8.9 / 9.0ms（同会话）与 11.7ms（跨会话），120×90 9.9~12.3ms。
       差在**停止位与精配候选数**，不是测量噪声（同轮两遍逐位复现）。**判据看量级、
       结论看 A/B**（同进程同窗口的改前/改后对照）；把表里某个绝对值当常量引用
       会得到"某窗口偶发 31ms 是不是回归了"这类假问题。
     - **findColor 的 22.12 → 0.003ms 是「取首点段」**，不是整调用：全帧 `cv::inRange`
       本身就是 ≈4.3ms 地板（三档命中面整调用实测 4.33 / 4.06 / 4.02ms）。同一段在
       89.6% 面是 28.4ms（复跑 28.4~30.4ms）、0.02% 面 1.5ms、未命中面 0 —— 旧实现的
       代价随**命中数**走，这正是它错的地方。判据 <10ms 的口径是 300×150 ROI（真机 0.88ms），全帧在口径外。
     - **findFeature 的可达率不是 100%**（1080×2400 真机截图，33 个已知真位置的窗口：
       200×150 网格 24 + 文档引用窗口 9）：**命中 14、其中 13 落在真值 3px 内**。唯一
       错位置那例（@(660,1900) 报 (760,1431)）经 `matchTemplate` 复查：**真值处
       ccoeff=1.0000 且是全图 argmax** —— 是该窗口的特征链没走到真值，不是"场景里
       有更像的地方"。反向的 19 例恒 found=0 分三类（`scenekp` + `featwin2` 交叉核对）：
       **A 真值处场景侧零关键点 6 例**（配额 8000 抬到 20000/40000 后仍有 5/4 例为 0；
       真值处 ccoeff 最小 0.99993 —— 图上确实有，是特征匹配对无纹理区域的固有边界）；
       **B 模板侧缺省 ORB 零点 5 例**（垫边重试后 6~57 点仍 found=0）；
       **C 两侧都有点仍配不上 8 例**（模板侧缺省 7~124 点）。B/C 是 ORB 描述子在低
       纹理 UI 上的可重复性极限。**结论**：`findFeature` 是"能不能找到"的鲁棒定位（尺度/轻度形变容忍），
       与 `matchTemplate` 是**互补**的两条链而非替代：后者同批 33 窗口命中 33/33，
       但位置唯一只有 25/33 —— 其余 8 例经 `dupchk` 复查是**像素级重复副本**
       （报出窗与真值窗逐像素最大差 0~1，屏幕里真有两块一样的地方，argmax 挑另一份），
       与三次实测里 48×48 报 (57,751) 那条同源。两条都写进 §9.2 与 §7.7，
       别让读者以为四修之后 findFeature 变成了万能定位器。

12. **wire 面单一事实来源 = `bridge/schema/wire.schema.json`；与 §12.4 的 d.ts 分工**：
    - **schema 管 wire 面**（每 ns 的方法表 + aliases + dynamicSinks + facade 归属），`generate.mjs` 双发射 `bridge/js/src/generated/wire-types.ts` 与 `:domain` `WireMethods.kt`（生成物入库、`--check` + CI `git diff --exit-code` 双门）；19 个 handler 的 `methods()` 申报单源指 `BY_NS.getValue(ns)` —— 表不手抄，杜绝「申报与 `when` 两份手抄互相漂移」。
      对账三门分工：`wire-schema.test.cjs` 四向（生成物同步 / facade→schema / register↔schema / 申报↔schema + 死分支 aliases 真伪）、`wiring-table.test.cjs` 表↔schema、`pull-wire`/`event-wire`/`err-catalog` 各管自己的拉取环与错误目录。
    - **d.ts 仍是对外 API 评审面（§12.4 口径不动）**：d.ts 描述脚本作者看得见的 TS 形状（参数/返回/重载），schema 描述桥线上跑的 wire 名 —— 对象不同，不合并：把参数形状塞进 schema，生成器就得长出第二套类型系统；把 wire 名塞进 d.ts，内部协议就变成了公共 API 承诺。两份都入库、各有一道门。
    - **正则测试处置**：`wire-reconcile`（花括号计数啃 `when` 块、全局方法名集合）删 —— 底账脆且不认 ns 归属；`wiring-table` 换表↔schema 基；`jni-names`/`docs-surface` **保留**（JNI 编译面 / 文档表面积门，不是镜像测试，偏差与理由在此记明）。
    - **`bridge/js/dist` 出库**：tsc 产物改构建产物口径 —— 每次 build 弄脏工作树的 38 文件消失，代价是 CI jvm-tests job 与本机都要先 `npm --prefix bridge/js run build`（两处报错文案已点名命令）。

2026-09-30 拍板（外部审查整改步骤 3；非 §18 编号项，原口径不涉）：

11. **JSON codec 合一 = `:domain` 手写值族 `DomainJson`；kotlinx.serialization 评估后不采纳**：
    仓内 5 个手写 codec（`A11yBridgeJson` 步骤 4 迁入、`NpmBridgeJson`、`EngineBridgeJson`、`TinyJson`、内联 `WmJson`）与 `JsonLine` 的递归下降 Parser 全部收敛到唯一 codec `DomainJson`（六值族 `Value` + `decode/decodeObject/encode/encodeParsed` + `reqStr`/`opt*` 字段读取族），调用点只换名不改形状 —— `Map<String, Value>` + 抛 IAE → `RpcNamespaceHandler` 折 `ERR_INVALID_PARAM` 的口径不变。
    不选 kotlinx.serialization 的理由：① 全仓零该依赖，选它 = catalog/插件面 churn（根 `libs.versions.toml` 冻结，审查亦要求零改动）；② 现网 60+ 调用点已是 `Map<String, Value>` + `requiredStr` 语义，迁移近似改包名，却要引入 `@Serializable` 注解 + `Json {}` 解码器配置两套心智；③ 动态 wire payload（桥载荷、jsonl 行）的目标是 `Map`/值域裁剪，不是 data class，@Serializable 的强项用不上。
    备选评估到此为止。
    边界两处不计入「多头」：`JsonLine`（scheduler persist）保留为**薄件协议垫片**（~80 行：冻结行格式四型值域裁剪 + IOException 保型），编解码本体走 `DomainJson` —— 行 framing 是协议不是 codec；`ConsoleCollector` 保留自写 `handle`（非 RPC 形的 sink 面，不经错误折叠契约）。
    兼容边（同步记 design-status 流水）：`DomainJson.parseString` 拒未转义控制字符（老 `JsonLine.quote` 会把控制符直塞裸字节），且 `decode` 查尾部多余字符（老 JsonLine/TinyJson 忽略尾随）—— 存量含裸控制符的 journal 行将被拒，落在 IOException 保型内，表现仍是「行损坏」响亮失败；方向是收紧（响亮失败不静默）。

2026-09-30 拍板（外部审查整改步骤 4；非 §18 编号项，原口径不涉）：

10. **RPC 处理器基类收口 `:domain`；`*Lite` 自定义形状废除**：
    全部桥命名空间 handler（`:platform:capabilities` 14 个、`:app-service:runtime` 的 `EnginesNamespaceHandler`、`:app-service:packager` 的 `NpmBridgeHandler`、`:app` 装配包的 `WorkManager`/`PowerManager`）统一继承 `:domain` 的 `RpcNamespaceHandler` —— `handle` 为 **final**，只折叠两类：`AutojsException`（原码透传）与 `IllegalArgumentException`（→ `ERR_INVALID_PARAM`）；
    未知异常照穿（bug 显形，不伪造参数错）。子类只剩 `dispatch`（`when (request.method)` 分发 + 业务 + 参数校验）。解码 helpers（`decodeObject`/`requiredStr`/…，原 `SystemNamespaces.kt` internal 泛化）与唯一 codec `DomainJson`（原 `A11yBridgeJson` 迁入）同批入 `:domain`；`methods()` 留位步骤 7 的 schema 方法表。
    废除的旧口径：① 每个 handler 自带一份 `try { Ok } catch (Autojs) catch (IAE)` 折叠（全仓 ~60 处 `catch (e: AutojsException)`）；② `BridgeRequestLite`/`ResponseLite`（capabilities 为绕「禁直连 `:bridge:java`」自造的桥形状）与 a11y/screen/engines 的嵌套自定义 `Request`/`Response` —— 类型本就住 `:domain`，门禁理由不成立，签名换 `BridgeRequest`/`BridgeResponse`；
    ③ 各 handler 的 `mount(): NamespaceHandler` 包装（类本身即 `NamespaceHandler`，调用点直挂，`AppShell.handleLike` 适配扩展随之删除）。收敛后残留的 `catch (e: AutojsException)` 只剩基类一处 + 与桥无关的业务面（ApkRepacker/InstallCoordinator/FloatingWindow/SensorSource/Zip）；保留自写 `handle` 的只有 `ConsoleCollector`（非 RPC 形的 sink 面，不经错误折叠契约）。

2026-09-26 拍板（原 §18 第 1–7 项；2026-09-30 自 §18 迁入，原文整段保留，§号与编号不变——迁移前这些条目写在契约 §18 里、标注「已拍板（2026-09-26）」）：

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
   - **门禁侧已落（2026-09-29，存盘于本地 `node-slice`；2026-10-01 移植进 main，见 `design-status.md` 流水）**：`runScript`/`exec` 走「宿主从盘上 manifest 重算 `(pkg, versionHash)` → ledger 命中 APPROVED 才放行 → 纯 JS bin 白名单」，
     **未获批时把请求自请入队**（approvals 流 + `drainApprovals` 拉取口）让用户在审批卡上当场选，而不是干巴巴拒回或由脚本自批 —— 这就是「安装时让用户自己选」的交互落点。
     执行体是 `ScriptOpExecutor` 接缝（不复用 `HeavyOpExecutor`：lifecycle 不做 reify）。**安装侧 `hasInstallScript` 的当场告知与选择仍缺**（那要先有 spawn 桥才兑现得了"跑了"），
     见 [`design/10-npm.md`](design/10-npm.md) §10.3 的 T1 落地追记与「仍未落」段。本条**口径未变**，只是实现进度推进。

2026-09-25 拍板。编号与 §18 原编号一致（第 8、9 项），原文整段保留：

8. **截屏帧与 images 帧的通路**（§9.2 记账的缺口，决定 §7.7 表里 `captureScreen → findImage < 1s` 这条链路什么时候能兑现）：
   `screen.capture()` 出的帧与 `images.decode` 出的帧**互不通用**（两缝各发各的号，§12.2），且 screen 面既不给 `save()` 也不给 `pixel()`（字节出不了 `:main`），脚本目前**只能自己先落盘再 decode**（§12.3 示例这么写）。三条出路，入口在同一处（换 producer 或加一个 `screen.save`），代价不同：
   - (a) **`screen` 面加 `save(path)`**：把 a11y 已产出的 JPEG 字节原样落盘。设备面已经在压 JPEG 了，最小改动；代价是**有损**——`findColor` 的分量判定会吃到压缩伪影（§9.2 的契约是按分量精确夹的），"屏幕上这个色还在吗"这类判读会变钝。
   - (b) **两缝共用一个帧表**（producer 直接把帧写进 `images` 的帧表）：收益是真正的 0 拷贝直连（§7.4 所有权边界仍是每个句柄一份 Mat，变的是**发号那一侧**归谁）；代价是"帧不通用"这条纪律取消，`screen`/`images` 两个命名空间的释放语义要重新对齐（谁 release 谁背 STALE）。
   - (c) **`images` 面加 `decodeBytes(byte[])`**：屏幕字节不落盘直进 native；代价是 bytes 要过桥，§7.7 的"屏幕帧→native 0 拷贝"这条在**两个维度上**都要重新记账，且 §7.4 的多一路径 = 多一处规格要守。
   **已拍板 (b)**（2026-09-25）：只有它同时保住了"0 拷贝"与"按分量精确判定"两条被契约明确承诺的性质，(a) 切掉的是判读精度、(c) 切掉的是性能口径。(a) 不作为过渡 —— 过渡方案一旦进示例就会被抄成正式用法，而带 JPEG 往返的链路不叫「屏幕帧→native 0 拷贝」，§7.7 的买单口径不为它改。
   落地时动的是 §7.4 所有权边界（发号侧归一），`screen`/`images` 的句柄纪律届时同批重写，"帧不通用"那条纪律取消。

9. **`images.decode` 的相对路径口径**（2026-09-25 实测记账，影响 §9.2/§12.3 的示例写法）：
   `:domain` 的 `ImageAnalyzer.decode` KDoc 写着「路径解析（相对项目根 or filesDir）由实现定」，但**四层里没有任何一层解析路径**（计算核 `std::fopen`/`cv::imread` 直取、
   装载面与 `NativeImageAnalyzer` 原样透传、handler 只挡空白串）。host 侧实测把这条钉死了：传相对路径时按**进程 CWD** 解析——同一个文件，绝对写法与「chdir 到该目录 + 相对写法」都回 `ERR_IO(3)`（说明相对写法确实命中到了文件），
   而不存在的相对路径回 `ERR_FILE_NOT_FOUND(2)`。`libopencv.so` 载在 `:main` 进程里，那个进程的 CWD 是 `/`（Android 对 zygote 后代的固定行为），于是脚本写 `images.decode('part.png')` 会在根目录找一个并不存在的文件—
   —**回的是 `ERR_FILE_NOT_FOUND`，且报的路径是对的**，所以看起来像"文件真的不在"，不像"口径没定"。
   两条出路，代价不同：
   - (a) **就在契约里写明"路径必须是绝对的"**（示例改成 `/sdcard/...` 或让脚本自己拼 `filesDir`）。零实现改动，代价是 v9 的 `fromFile('part.png')` 这种相对用法在 AutoScript 直接不成立，脚本要改写法。
   - (b) **在 handler 层加一层基准解析**（相对路径按项目根 / `filesDir` 拼绝对再往下传）。保住 v9 的写法，代价是要定"基准是谁"（项目根？脚本所在目录？filesDir？）——**三选一本身又是一个要拍板的策略**，且 §9.2 的「不做路径策略」那条边界要重画。
   **已拍板 (a)**（2026-09-25）：路径必须是绝对的 —— 把"相对路径"从契约里去掉而不是猜一个基准。(b) 不给：基准三选一本身又是一个策略，且 §9.2「不做路径策略」的边界不重画。**§12.3 已按 (a) 改写**：示例路径一律绝对（`fromFile('/sdcard/part.png')`），并写明了相对写法为什么回 `ERR_FILE_NOT_FOUND`。
   跟进动作：`:domain` `ImageAnalyzer.decode` KDoc 里「路径解析由实现定」那句要收紧为"只收绝对路径"（见下）。

---

## ~~仍待拍板（原 §18 第 1–7 项）~~ **已全部拍板（2026-09-26），见上方「已拍板」第 1–7 项**

> **2026-09-30 订正（只追加，原表不动）**：本段原写「以下七项尚未拍板」是拍板前的原貌 ——
> 七项已于 2026-09-26 全部拍板，条目已迁入上方「已拍板」（编号不变、原文整段保留）；
> 台账正文在 [`design/18-19-ledger.md` §18](design/18-19-ledger.md)。原表按下保留：

以下七项尚未拍板，原文保留在 §18（现居 `design/18-19-ledger.md`），
契约正文按「推荐默认值」编写；一旦拍板，**逐项搬到这里**并在 §18 留一行指针。

| 编号 | 议题 | 推荐默认值 |
|---|---|---|
| 1 | 引擎路线：P0 只 Node vs 并行 QuickJS | P0 只 Node，QuickJS 沙箱 P1 |
| 2 | 进程模型：一步到位 vs 先单引擎进程 | 一步到位（池容量先写死 1） |
| 3 | 分发定位与 Play 态度 | 完全避开 Play（官网/F-Droid/APK 直下） |
| 4 | ICU 取舍 | 全量 ICU + 裁剪 subset（**注**：构建管线当前是 `--with-intl=none`，见 RISKS §3） |
| 5 | a11y 与脚本进程的极限形态 | P0 不做第三进程，接口已留接缝 |
| 6 | UI 宿主策略 | 两者都留、原生优先 |
| 7 | npm 默认镜像与审批严苛度 | `registry.npmmirror.com` + 出厂 global-deny |

---

## 已推翻 / 已改口径

| 原口径 | 出处 | 处置 | 日期 |
|---|---|---|---|
| `TM_CCORR_NORMED` | §7.7 表 + `:domain` KDoc | 改 `TM_CCOEFF_NORMED`（CCORR 在「画面里没有模板」时 max 仍 +0.955，阈值 0.9 直接误判命中） | 2026-09-25 |
| APK ≤ 40MB | §15 表 | 实测推翻：jniLibs 三件套未压缩 ≈81MB；OpenCV 面仅 7.0 MiB、不是超支原因。三条出路见本文件「仍待拍板」第 7 项旁的 §15 记账 | 2026-09-25 |
| 「屏幕帧→native 0 拷贝」链路可走 | §7.7 表 | 两缝帧不通用，脚本当前走不通；数字是「链路通了之后」的口径。出路已拍板 (b) 两缝共用帧表 | 2026-09-25 |
| `images.decode` 相对路径 | §12.3 示例 | 四层无一层解析路径，相对写法在 `:main`（CWD=`/`）恒 `ERR_FILE_NOT_FOUND`。已拍板 (a)：**路径必须绝对** | 2026-09-25 |
| §8.7「恒真 = 明写的待接」 | §8.7 | 作废：`AppShellApplication.screenGateOf` 传 `WakeLockLedger::isHeld`，持锁判定收口 | 2026-09 |
| §7.8 步骤② 「addon 的 `napi_*` 从 libnode 动态表解析」 | §7.8 | 真机推翻：bionic 的 linker namespace **不把先做的 `dlopen(RTLD_GLOBAL)` 符号给后做的 `dlopen`**（glibc 会）——addon 实测 `cannot locate symbol "napi_add_env_cleanup_hook"`。改为 **addon 自己 DT_NEEDED `libnode.so`**（按 SONAME 命中已加载的那份，与落位目录无关） | 2026-09-29 |
| 「bionic 上 DT_NEEDED + `$ORIGIN` 足以让链接形宿主起来」 | 本文件上一行方案 | 实测**不完整**：RUNPATH 只作用于可执行文件自己的直接 NEEDED，**不作用于被依赖库的传递依赖** → 链接形宿主三处摆放一致死在 `libc++_shared.so`（`CANNOT LINK EXECUTABLE … needed by …/libnode.so`）。宿主改为**自己 NEEDED `libc++_shared.so`**（撤 `-static-libstdc++`）、保持 dlopen 形不链 libnode；addon 反之（链 libnode、静态 STL） | 2026-09-29 |
| 宿主 `-static-libstdc++`「不把 libc++_shared 推给装载面」 | `build-native.sh` LDFLAGS 注 | 作废：正是它让 libnode 的传递依赖无人解析。宿主改用共享 libc++（NDK 默认） | 2026-09-29 |
| 绝对 rpath / `LD_LIBRARY_PATH` 注入作为兜底 | 上一轮讨论 | 绝对 rpath 在 bionic 上**不生效**（`CANNOT LINK EXECUTABLE … not found`，`$ORIGIN` 有效）；`LD_LIBRARY_PATH` 只作真机调试手段，不作为交付依赖（交付形态实测无需它） | 2026-09-29 |
| 「打包整轨」 | §14 P0 | 移入后续版本（向导 UI 与 Keystore 取密随轨走）；加密资产/loader 一并移出需求 | 2026-09-23 |
| `tools/jvm-test*` 本机旁路「保留作快速门」 | CLAUDE.md 构建段 + §6 末 | **删除**（脚本已删）：本机快速门 = CI 同源 `./gradlew` 单模块任务（本机 SDK 已配置、12 任务同源可直跑，不留第二口径）；原 jvm-test-all 的「aborted ≠ 绿」纪律由约定插件 `autoscript.test-guard`（build-logic）承接 —— 测试出现 skipped/aborted 即红，环境门禁类在 `TestGuard.ENV_GATED`，其余 `-PallowSkipped=<类名>` 显式放行 | 2026-09-30 |
| `:engine:sandbox` 模块壳保留、settings 模块表按协调者冻结不动 | §6 行 + §18 第 1 项（2026-09-26 拍板附注） | **空壳从 settings 注释摘除**（QuickJS 裁撤口径不变，审查步骤 1）：`include` 注释 + ModuleGraphTest `include` 正则改锚行首（注释行不计）+ 允许集删行 + 模块表 15→14；目录留盘，复活 = 注释回 + 登记 | 2026-09-30 |
| §12.2「语义层（handler）住 `:platform:capabilities`」+「为什么 handler 不住 `:platform:system`」两层理由 | §12.2「分两层，别混」段（原两句原文见本节末引用块） | **口径反转（审查步骤 6，零新模块方案）**：handler 归位实现模块 —— 系统面十一件住 `:platform:system` 的 `SystemNamespaces.kt`（与 `SystemSpis`/契约同模块，2026-09-30 步骤 6a/6b/6d 分批迁入），`dialogs` 留 `:platform:capabilities`（`DialogHost` 实现按约定在同模块），`workManager` 归 `:app-service:scheduler`、`power_manager` 归 `:platform:system`；原理由 (1) 共担门禁组 → 校验仍单点住在 `SystemNamespaces` 工厂束（同模块一处，不各写一份），(2) 装配层双模块直连 → `PlatformWiring` 本就经 §6 包级例外二同时可见两模块、根包经工厂缝零 platform 类型（`ArchitectureTest` 依赖级门禁验证）；与 `EnginesNamespaceHandler` 住 `:app-service:runtime` 同形态（handler 归位实现模块）。§6 两行、§12.2 表 handler 列与两段散文同批改 | 2026-09-30 |

| `engines.exec` 的 `timeoutMillis` 可选（缺省交给引擎/看门狗自行兜底） | §8.6 原「仍待覆盖」段 + `bridge/js` `EngineRunRequest` | **改必填**：桥路径无人 await 终结，缺席即 `ERR_INVALID_PARAM`；到点由看门狗期限线落 `KillCause.TIMEOUT`（§8.6 期限线，判据 = 发起方等不等） | 2026-10-01 |
| `:engine:sandbox`「**目录留盘**」（2026-09-30 摘壳时的附带口径） | 本表上一则 `:engine:sandbox` 行 | **目录删除**（2026-10-01 拍板，backlog D2）：裁撤口径本身不变（§18 第 1 项「不要沙箱」），只是把空壳目录从盘上清掉 —— 目录里只有 1 个 `build.gradle.kts`（QuickJS 白名单/独立进程那条轨的残骸，P1 再实装的口子），模块早在 2026-09-30 就不在 `settings.gradle.kts` 的模块表里。**复活 = 重建模块目录 + 注释回 include + ModuleGraphTest 允许集登记**（比原口径多「重建目录」一步）；`build.gradle.kts` 可从 git 历史取回（`git show <摘除前的 sha>:engine/sandbox/build.gradle.kts`）。`settings.gradle.kts` 里那行 `// include(":engine:sandbox")` 是协调者冻结文件，本次不动 | 2026-10-01 |
| `settings.gradle.kts` 里 `// include(":engine:sandbox")` 那行注释「留作复活提示」 | 本表上一则 `:engine:sandbox` 行末句 | **注释行删除**（2026-10-01 同日拍板，承上一则）：目录既然已不在盘上，留一行指向不存在目录的注释只剩误导 —— 它引用的两个事实（空壳摘除、复活办法）在文档侧都有登记，而**冻结文件**里留死引用正是 C3 那批刚清掉的形态。复活口径随之定为「重建模块目录 + include 行加回 + ModuleGraphTest 允许集登记」（不再有「取消注释」这一步）；ModuleGraphTest 的 `include` 正则锚行首，删注释行不影响派生计数（`:domain:test` 已重跑验证） | 2026-10-01 |
| 外审 C5：「无 `CHANGELOG`」 | `docs/backlog.md` C5 | **不设 `CHANGELOG`**（2026-10-01 拍板）：变更流水已经在 `docs/design-status.md` 的「流水」段（只追加、按日期、带 § 锚），再开一份 `CHANGELOG` 必然漂成第二个事实来源 —— 本仓对「同一事实写两遍」的代价有明确判据（`ModuleGraphTest` 的派生计数就是为此立的门）。外审同一行的另外两项**已采纳**：`CONTRIBUTING.md` 与 PR / issue 模板已建（批 3）。发版流程真立起来那天再谈（口径随 §18 第 3 项「不发行」） | 2026-10-01 |
| 外审 C5：`versionName` 硬编码 `"0.1.0"` | `app/build.gradle.kts` | **保留硬编码占位**（2026-10-01 拍板）：本仓不发行正式版（§18 第 3 项），没有发版流程 —— 此刻把版本号接到 `gradle.properties` / 版本目录只会**多出一个会漂的事实来源**，换不来任何东西。改为在该行上方写明：这是占位、为什么不引第二个来源、真要发版时该怎么改（两数同改、`versionCode` 单调递增、同步 §13/§14 交付轨） | 2026-10-01 |
| `LockSigner.KeyProvider.keyBytes(): ByteArray`（应用密钥接缝的原始形状） | `LockSigner.kt`（`:app-service:npm`） | **改 `secretKey(): SecretKey`**（2026-10-01 拍板，backlog A1c）：Keystore 里的密钥材料**不出库**，`getEncoded()` 拿不到字节 ⇒ 原形状**接不上** Keystore，而 Keystore 正是设计口径的密钥存放处。给句柄则两边都成立（Keystore HMAC 密钥与测试用 `SecretKeySpec` 都能喂 `Mac.init(SecretKey)`），语义一字不变（仍 HMAC-SHA256、落盘仍 `v1 <hex>`）。**同时拍板实现落点：Keystore 版住 `:app` 装配层**（Composition Root 已依赖 `:app-service:npm`，零契约变更；`:app-service:npm` 保持零 `android.*`）。**注意：这是接缝形状，不是接线** —— 生产装配的 `lockKey` 仍是 `null`（`SECURITY.md` / §11.3 第 8 条口径不变） | 2026-10-01 |
| **「vendored npm CLI 素材从哪来」**（backlog A1 的卡点：CI 产 / 入库 / 取本机 npm 目录三选一，2026-10-01 之前未定） | `docs/backlog.md` A1、§10.1 脊梁 | **取 Node 源码树自带的 `deps/npm`，随 libnode 同批出库**（第四选项）：`fetch-and-build.sh` §9 收敛到 `OUT/npm` → `node-slice.yml` artifact → gradle `prepareNpmCliAssets` 随包 `assets/npm/` → 启动期幂等落位 `files/npm/`。**选它的理由**：素材与 `NODE_VERSION` 同一把锁（换 Node 版本时 npm 跟着走，`NPM_CLI_VERSION` 与素材 `package.json` 逐字比对，漂移当场红 —— 逼一次显式决策），不引入第二条下载源与第二套校验，且与 libnode 同批出库（同一个 artifact、同一次构建、同一份基表纪律）。**代价（明写，不当已办）**：Node 24.21.0 携带的是 **npm 11.19.0**，**低于 §10.1 脊梁写的「npm 12.x 系」** —— npm 12 的「拒绝全部 lifecycle + allow-git=none + allow-remote=none」这层**官方默认语义当前不在位**。护栏并没有因此静默消失，但**只剩一层**：`HostNodeExecutor` 对每条命令硬编码 `--ignore-scripts`（§11.1 T1 的零 spawn 主路径），它与 npm 版本无关；而**非脚本** spawn 路径的第二层兜底（§10.12 末行的 child_process 拦截 shim）本就未落。升级到 12.x 是**独立一件事**，已登记 backlog；**同日追加实测**：`nodejs.org/dist/index.json` 的 868 条官方发布里**没有任何一条携带 npm 12.x**（最新 v26.10.0 / 2026-09-21 带的是 npm 11.19.1）→「等 Node 线携带」这条升级路径**原理上不成立**，要 12.x 只能另找素材来源；改 `NPM_CLI_VERSION` 即触发全链回归。**→ 2026-10-02 改口径（第 26 项）**：素材来源换 **registry 发布态 tarball `npm@12.2.0`** —— 「与 `NODE_VERSION` 同一把锁」解除，版本纪律改由 `VERSIONS.env` 的 `NPM_CLI_VERSION` + `NPM_CLI_SHA1` 承担（改钉仍触发全链回归，§9 sha1+版本双闸）；上文原结论保留 | 2026-10-01（→ 2026-10-02 改） |
### 附：§12.2 被反转口径原文照抄（2026-09-30 步骤 6 摘录前的原文）

> - **语义层**（handler）住 `:platform:capabilities` 的 `SystemNamespaces.kt`，纯 JVM 可测（假 SPI 注入即可跑）：参数校验（spec 守卫、必填字段、`timeout > 0`）、枚举字面量解析（`ShellMode`/`DialogMode`，拼错即报错不静默套默认）、默认值（shell 超时 30s）、错误分类**透传**（`AutojsException.error` 原码回桥）、响应形状编码（与 `extras.ts` 逐字对齐）；

> 所以「为什么 handler 不住 `:platform:system`」有两层理由：(1) 五个命名空间共享一套门禁组（OVERLAY/ROOT/ADB_INPUT），语义放一起才不会各写一份校验；(2) handler 若住 `:platform:system`，
> 装配层就得同时直连 `:platform:capabilities` 与 `:platform:system` 两个模块才凑得齐 Router —— §6 对 `:app` 非装配包明令禁止这一直连（装配包 shell 的生产装配 `PlatformWiring` 经包级例外二放行，
> 但那只是"把 SPI 拼成束"，不构成把 handler 挪去 `:platform:system` 的理由：主因仍是 (1) 的共担门禁）。

---

## 口径之外的判据注记（暂留契约侧）

§7.7 的 `TM_CCOEFF_NORMED` 长篇实测注记（2026-09-25）目前仍在契约正文里。
它**读起来像决策**（「口径改了」在末句），但主体是「为什么 CCOEFF 而不是 CCORR」
的判据论证。这一轮不动；下次迁移时按「判据留契约、变更进本文件」切开。
