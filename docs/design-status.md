# AutoScript 落地状态台账

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
| §8.5/§8.6 | 引擎侧 `waitCompletion` 超时不发起（无人 await 的 run 没人收尾） | **已覆盖（2026-10-01）**：`TimeoutEnforcer{WATCHDOG}` + 看门狗期限线（`KillCause.TIMEOUT`）—— 残余边界见 §8.6（期限只覆盖声明了期限的 run + 轮转须在跑） |
| §8.5 | 意图日志的 **SQLite 实现**（契约写的是「append-only（SQLite，启动即回放）」） | **已落地（2026-10-08，批 77）**：~~未落，且分歧已如实标注~~ 卡点（接口住 `:app-service:scheduler`，`:platform:*` → `:domain` 不反向）**按「搬接口」解决** —— `IntentStore` 迁 `:domain`，`SqliteIntentStore` 落 `:platform:system`，两份实现（jsonl/SQLite）跑**同一套契约测试**（`:domain` 新增 `testFixtures` 源集，正是它抓出幂等索引漏洞）；老 jsonl **带原 runId** 一次性导入（可重入、验完才改名归档）、打开失败**如实回落 jsonl**。**仍待裁**：~~保留期策略~~ 日志**只追加、从不清理**，体积随 run 数线性增长（每次 run 恒定两条行，已无冗余可压），**保留期策略**（老终态行 / 老 nonce 能否丢）会动到 §8.5 的幂等锚点 —— **本批显式排除，入池 backlog**。**2026-10-08 批 78 修订**：jsonl 写入侧已删（生产只有 SQLite，打不开即失败、不回落），故本行的「两份实现（jsonl/SQLite）跑同一套契约测试」「打开失败如实回落 jsonl」**均已作废**（原文逐字保留）；契约套件改由 `:domain` 内的 `InMemoryIntentStore` 承载且**无环境门禁**。口径见 [`design-decisions.md`](design-decisions.md) 第 47 项① |
| §11.2 T2 / §10.2 | **npm 生产装配接线**（`lockKey` / `executor` / `scriptExecutor`） | **部分落地（2026-10-01）**：`executor` **已接线** —— 素材随包（`assets/npm/**` ← gradle `prepareNpmCliAssets` ← `node-runtime-build` 出口）→ 启动期 `AssetTreeCliSource` 幂等落位 `files/npm/` → 注入 `HostNodeExecutor`（宿主 = `nativeLibraryDir/libnoden.so`）；两条同时成立才注入（部署就位 + 有宿主），否则保持 `Unavailable` 且原因原文进 `AssembledShell.npmCliFailure`。~~**仍缺**：`lockKey`（`lock.sig` 既不签也不验，全仓无 `KeyProvider` 实现；接缝形状 A1c 已就位）~~ **`lockKey` 已接线（2026-10-08，批 79）**：`LockKeyStore.AndroidKeystore`（Keystore 密钥，get-or-create；「取不动」绝不静默重建）+ `AppShellKit(npmLockKeys=…)` → `NpmShellKit.assembleHandler(lockKey=…)`；`ci` 先验签、`install` 收尾重签、`exportSnapshot` 带 `snapshot.sig`；取钥失败本次不装该防线且原因原文进 `AssembledShell.npmLockKeyFailure`。口径见 [`design-decisions.md`](design-decisions.md) 第 49 项。**仍缺**：`scriptExecutor`（T1 spawn 属 P1）。另：~~素材版本 = **npm 11.19.0 ≠ §10 脊梁的 npm 12.x 系**（落差登记在 [`backlog.md`](backlog.md)）~~ **落差已消解（2026-10-02 批 9）**：素材换 registry `npm@12.2.0`，§10.1 脊梁满足（backlog A6 划掉，[`design-decisions.md`](design-decisions.md) 第 26 项）。契约侧标注同批更新（§10.1 接线现状 + §10.12 风险表 + `SECURITY.md`）。**2026-10-02 勘误**：本条原先还写了「+ §11.2 T2 + §11.3 第 8 条」—— 实测**没改到**：§11.2 T2 行本来就不含 npm 版本叙述（无物可改），而 `11-security.md:64-69` 的「素材版本落差」段（在 §11.3 第 8 条**之上**的同一子弹列表内，不在第 8 条里）**原文未动、仍写 npm 11.19.0**，且按 `SECURITY.md` 的优先级规则「两处有出入以设计文档为准」→ **错的那份赢**。已登记 [`backlog.md`](backlog.md) 的 **C10** |
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
| 2026-10-09 | 1 | 批 80：`child_process` 拦截 shim 接线 + 零 spawn 金标准（§10.11 P0 / §10.12 末行）—— `npm-spawn-gate.cjs`（classpath 资源，七个入口全替换为抛错；`detached:true` → `ERR_PERMISSION_DENIED`、`fork` → `ERR_NOT_IMPLEMENTED`、其余 → `ERR_NPM_SPAWN_BLOCKED`）+ `NpmSpawnGate`（落 `files/.autojs/`、`NODE_OPTIONS=--require` 追加注入、播报解析、码折叠）+ `HostNodeExecutor(spawnGateFile=…)`；装配层**三条齐才注入执行体**（素材 + 宿主 + shim），shim 落不上就不注入（fail closed）。金标准落成 `NpmSpawnGateMatrixTest`（门禁下 install/ls/dedupe/prune/uninstall/ci 全绿 + 不注入也全绿的反向变异 + `npm run` 确实被拦且无产物），已进 `check-e2e-ran.sh` nightly 验尸清单。验收：14 任务 JVM 线全绿、`NpmSpawnGateTest` 9 例 + 矩阵 3 例全过、lintDebug + assembleDebug 绿（shim 实测进 APK）、去掉 `-PskipNpmE2E` 后 nightly 验尸四条真 npm 路径全 ✓ | [`log/2026-10-09.md`](log/2026-10-09.md) |
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
