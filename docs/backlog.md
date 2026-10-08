# AutoScript 待办池（backlog）

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
