# AutoScript 落地状态台账
> **2026-10-12 · 批 107：脚本设置页（线程与内存上限，两档全做）+ 外审 M2 结案** ——
> 设置页「权限列表」下面加一行**脚本设置**（用户口径）。**单脚本**那档落
> `--max-old-space-size`（argv）+ `UV_THREADPOOL_SIZE`（env）；**桥**那档（外审 M2）
> 落**每连接条数 / 字节 / `limitedParallelism`** 三档闸，接在批 100 的取闸链上，
> 「可见面」同期落地（诊断回执 + `connectionStats()`）。两档都**立即生效**（改完下次
> 执行 / 下一次请求起），**不追溯在途** —— 掐在途是「撤销」不是「限额」。
> **M2 挂了三批、这一批结案**：批 100 的两条不做理由都建立在「这个数必须由我们定」
> 之上，而用户要的恰是一个可改的旋钮 —— **旋钮本身就是「要能改」的答案**。
> 契约见 §8.1「脚本资源限额」与新增的 §7.6.1。叙事见 [`本日流水`](log/2026-10-11.md)。
>
> **2026-10-12 · 批 106：jsonl 压实（外审 B3 末件）+ 台账三处矛盾订正** ——
> [`FileScriptEnvStore`](../app-service/script-repo/src/main/kotlin/com/autoscript/appservice/scriptrepo/core/FileScriptEnvStore.kt)
> 写后按 `Files.size` 判、超 **256 KiB** 就把**收敛视图**经 tmp + `ATOMIC_MOVE` 重写
> （外审 B3 三件的最后一件；前两件是批 96 半行清盘与批 104 流式读）。**判据不是「格式像
> 不像 jsonl」，是「读口问的是现状还是发生过什么」** —— 前者可压（`all()` 就是收敛视图的
> 投影），后者不可（`install-history.jsonl` 的全量读口是刻意的，压缩过去就是删审计）。
> 顺带订正台账**五处**「已裁定/已做却仍写『仍挂』或仍挂 🔶」的矛盾 —— 三处在顶部追记块
> 的「仍挂」清单（M4 的「另一半」/ A13 / `TrustTierResolver`），一处是 F 节「下一批」那份
> 逐批过期的清单，一处是结构小节的 🔶 四条（其中三条早已做完：池记账=批 103、
> jsonl→SQLite=批 102、无 `ERR_INTERNAL`=批 97）。**五处全是逐条 grep 出来的**。
> 叙事见 [`本日流水`](log/2026-10-11.md)。
> **2026-10-12 · 批 105：两份 `ProcOps` 合并成 `:platform:process`** ——
> [`ProcOps`](../platform/process/src/main/kotlin/com/autoscript/platform/process/ProcOps.kt)
> + [`TreeOps`](../platform/process/src/main/kotlin/com/autoscript/platform/process/TreeOps.kt)
> 住**新模块 `:platform:process`**（**叶子模块，连 `:domain` 都不依赖**）。消掉的是批 95
> （npm T1 杀树）与批 99（shell 收尸）那两份「手法逐字同款、代码两份」的重复 ——
> 塞 `:domain` 也能消重复，但那是消症状留根因。**`:engine:node-process` 的
> `ProcessLauncher.pidOf` 刻意留一份**：反向依赖即破 §6。模块数 17→18、测试任务 14→15，
> 冻结面五处同步。叙事见 [`本日流水`](log/2026-10-11.md)。
>
> **2026-10-12 · 批 104：外审 M1 收口 + B3 的流式读那半** ——
> [`ScriptPaths.scriptFile`](../domain/src/main/kotlin/com/autoscript/domain/scripts/ScriptPaths.kt)
> 补上读侧词法闸（此前它一个字都不校验，而 `NodeProcessEngine` 拿返回值直接拼 `node <路径>`
> 执行）；`projectId` 复用 `isValidProjectId`，非法路径在引擎侧折 `ERR_INVALID_PARAM`。
> [`FileScriptEnvStore.replay`](../app-service/script-repo/src/main/kotlin/com/autoscript/appservice/scriptrepo/core/FileScriptEnvStore.kt)
> 从 `readAllBytes` 改逐字节流式扫（append-only 长度只增不减，冷启动全量读会把整段历史拉进堆）。
> 守卫新增 7 + 1 + 1。叙事见 [`本日流水`](log/2026-10-11.md)。
>
> **2026-10-12 · 待办池清池续：划掉的 31 行直接删掉（决策第 73 项）** ——
> [`backlog.md`](backlog.md) A–E 五张表里**全部 31 条划掉的行已删除**（只删条目行，
> 结论一条没丢：已完成叙事在流水切片、裁定口径在决策记录）。上一轮那批划掉的
> B3 / B4 / B14 / D13 / E3 / A13 是「**不再跟进**」不是「已完成」，划线等于给待办池
> 塞一条永远不会被领走的假待办 —— 列清单时它就又冒出来。**划线在本仓是「结论已定」的
> 记号，不是「待办」的记号**；没结论的东西（外审 B3/M1/M2 三条半成品、结构性长线项）
> 一条没动。A/B/C/E 改写为「本表已清空 + 删了哪几条」，**D6 是唯一活着的行**（需拍板）。
> 叙事见 [`本日流水`](log/2026-10-11.md)。
>
> **2026-10-12 · KDoc 与实现矛盾七处订正（零行为变更）** —— 外审点名的七处 KDoc
> 「承诺的比实现做的多」全部订正（`DeployPath.resolveIn` 的防符号链接、`scriptFile` 的
> 「越界只读不到」、`RpcNamespaceHandler` 的未知异常穿出、`PoolSlot.generation` 的过期
> 句柄判定、`FileTaskStore` 的重放收敛、`LockSigner` 的防跨项目搬锁、
> `AndroidShellExecutor` 的不留孤儿进程）。**代码零变更，只改注释**；三处同时在 KDoc
> 里记了「为什么不补真解」与补法代价。叙事见 [`本日流水`](log/2026-10-11.md)。
>
> **2026-10-12 · 批 103 收官：待办池清池** —— [`backlog.md`](backlog.md) A–E 各表**未划掉**
> 的六行（B3 / B4 / B14 / D13 / E3 / A13）已按**不再跟进**划掉（原文逐字保留；**不是
> 「已完成」**）。外审登记表里的半成品（B3 压缩/流式读、M1 的 KDoc 矛盾、M2 每连接配额与
> `limitedParallelism`）与结构性长线（typed ids / actor 化 / KDoc 文字）**未动**，仍挂在
> 登记表里。叙事见 [`本日流水 · 批 103 收官`](log/2026-10-11.md)。
>
> **2026-10-12 · 批 103（P2 池记账重构 —— 外审 B2 的彻底解，三阶段落地）**：
> 批 96 修的是**症状**（`stopEpoch` 垫片让调用方不必整段持锁），本批换**根**：
> `Semaphore permits`（计数）+ `PoolSlot.state`（状态）两套靠手拿同步的记账 →
> **空闲队列 = 唯一真源** + `PoolLease`（幂等归还凭证）。一个槽位**要么在队列里、要么在
> 某份 lease 手里**，归还权由 CAS 独占 ⇒ 四种失真（有证无 FREE 槽 / 槽 FREE 但无证可领 /
> **双重归还超发一证 → 同一引擎跑两个脚本** / 归还了但槽仍在用）在**结构上**不可能。
> **阶段二最值钱**：现有测试的替身 `stop()` 不挂起 + 单线程 `runBlocking` **结构上暴露不了
> 交错**，换闩控替身后逼出**三种归还顺序各错一样** —— 最终形态 **CAS 独占 → quiesce →
> 回队**（另两种分别错在「把别人的槽塞回队列」与「下一个持有者拿到仍在跑上一个脚本的引擎」）；
> 另加 `forceReturn` 给 killAll（**不等 quiesce**，强释要的就是「立刻不再用」）。阶段三：
> `Controller.killRun` 改走 lease（归还权必须与 release 抢同一个 CAS）。
> **两条既有纪律的去留**：`stopEpoch` **删不掉**（量的是「急停跨过排队窗口」，与槽位真源正交）；
> §7.4 `generation` **彻底退场**（旧持有者的 claim 恒 no-op，过期句柄物理上到不了槽位）。
> **两处自写断言当场抓到真缺陷**：① `markFree` 的严格检查抓到**启动失败路径漏 `reuse`**；
> ② 交错测试抓到 `stop` 挂起期间 `killAll` 进来会让转移撞 `IDLE → STOPPED`。断言中途一度
> 放宽成「容忍非终态」，结果在合法强释路径上误报 —— **探测器变成噪声就等于没有探测器**，
> 回退成严格断言。CI 14 任务零跳过绿；detekt 绿；守卫新增 8 + 3 例。细节见
> [`log/2026-10-11.md`](log/2026-10-11.md)。

> **2026-10-12 · 批 102（P4：jsonl → SQLite，三份手写格式并两份，四阶段落地）**：
> **阶段 A SPI 搬迁**（纯机械，语义零变化）：`SqliteTaskStore` 必须住 `:platform:system`
> （`android.database.sqlite` 是 Android 面），依赖铁律 `:platform:* → :domain` 逼出这次搬迁 ——
> `TaskStore`/`ScheduledTask`/`TimedSchedule`/`ScreenGuarantee`/`CronTab` → `:domain/scripts`，
> `InMemoryTaskStore` → testFixtures，与 `IntentStore` 当初搬迁同一条纪律。**不留类型别名**
> （别名会让「新代码 import 哪个」变成新问题）。detekt baseline 的 `CronTab` 9 条抑制跟着搬
> （抑制按模块归属，留在原处等于给 `:domain` 新增 9 条违规）。
> **阶段 B `SqliteRunArchive`**：终态不可改写/复活由 **SQL 条件 UPDATE** 挡（锚点归引擎，
> 不是应用层先查后写）；`run_nonce` **无唯一索引**（档案不是幂等锚点，锚点在意图日志侧 ——
> 同一 nonce 可以有多行 = 重投的两次执行都要留档）。**契约套件切两套**：共享套件钉状态机
> 与读口语义（三个实现都跑），持久性只由两个持久实现跑 —— 第一版想用「带理由的豁免」
> 塞跳过，**撞 `test-guard`（「skipped ≠ 绿」）：门是对的**，零 IO 形态重开即清零是它的
> **语义本身**，假话不能钉成门。
> **阶段 C `SqliteTaskStore` + 迁移接线**：`task_id` 是**用户给的** TEXT 主键（无自增硬
> 要求）；排期落 `kind`/`a`/`b` **三列而非 JSON 列**（这三列能按它排序/查）；**重新登记
> = 复活**（upsert 一并置 `revoked_at` 为 NULL，漏了则「撤销后再登记」的任务永不续排）。
> 三份存储一套迁移纪律：导 + 改名归档 / 可重入 / **失败响亮且老文件原样留着** / **无回落**。
> `File*` 两个 jsonl 实现**保留**（契约套件的第二个被测实现，删了就没人证明「换引擎那天
> 行为没变」）。测试抓到两个真 bug：档案登记误写自增发号（id 来自调用方，自增让读回全错）、
> 墓碑时刻用 `null`（null 是「存活」含义 → **撤销过的任务迁移后自己复活**）。
> CI 14 任务零失败零跳过；detekt 全模块绿（baseline 用 detekt 自己的生成器重生成 ——
> ID 含 KDoc 折行，手抄对不上，批 94 记过的同款坑）。细节见
> [`log/2026-10-11.md`](log/2026-10-11.md)。

> **2026-10-11 · 批 101（维护者拍案：§7.6 解冻同步 + §10.3 杀树口径入契约 —— F1/F2）**：
> 维护者对拍板清单逐条裁定：**F1 开**、**F2 做**、**P1 不做**（LockSigner 维持单签名文件）、
> **P3 不做**（D13 缓）、**P2 做**（池记账重构）、**P4 做**（jsonl→SQLite）。本批做契约同步：
> ① **§7.6**：补 `ERR_INTERNAL` 进错误目录 + 写「折叠两档、中间隔一档内部错误」为契约
> （`AutojsException` 原码透传 / `IllegalArgumentException` → `ERR_INVALID_PARAM` /
> **其余一切 → `ERR_INTERNAL`**）—— 这同时**对齐了 `RpcNamespaceHandler` 基类 KDoc 与路由层
> 实际行为**（批 97 之前两处矛盾：KDoc 说「照旧穿出」，路由层一律折 `ERR_INVALID_PARAM`）；
> ② **§10.3**：把 `/proc` 扫描 + `kill` 二进制 + **扫在杀之前** 三档写进契约正文（各自对应
> 一类设备失效形态：`ProcessHandle` 不在 android.jar / JVM 无按 pid 杀的 API / reparent 断链），
> 并把**降级档（pid 探不到 → 只杀主进程）写成契约的一部分** —— 它意味着「杀树在某些运行时上
> 不做它声称的事」，调用方不能假设孙进程一定收净。
> **P2 与 P4 各自占其后一批**（多文件重排级改动，不与契约同步夹批）。细节见
> [`log/2026-10-11.md`](log/2026-10-11.md)。

> **2026-10-11 · 批 100（外审整改第五刀：桥在途字节闸 —— M2 的可做那半）**：
> 原口径只有**条数**闸（`maxInFlight`=256），而 `maxFrameBytes`（8 MiB）× 256 = **2 GiB 堆**
> —— 两个**已生效**默认值相乘就能得到的数，minSdk 26 设备上直接 OOM，而那一刻的现场是
> 「脚本发了一堆大请求把宿主撑死」，看不出是哪条面干的。**条数与字节正交**（与批 99 给 zip
> 加 `MAX_ENTRIES` 同一条纪律），故加第三档闸 `maxBytesInFlight` = 16 MiB：
> **CAS** 取闸（`synchronized` 会变全局竞争点，这条每请求都走）/ 单条超上限**直接拒不
> 部分接纳**（部分接纳 = 请求永远等不到）/ **拒这一条不拒整条连接** —— 回一帧
> `ERR_PERMISSION_DENIED`（话术点名「字节」以免与条数闸混淆），连接继续服务后续小请求，
> **不静默丢**（静默丢的话脚本只会干等到 TTL 超时）。
> **同条另两半如实登记不排期**：每连接配额（一条连接可吃满全部 256 条在途；要连同「连接级
> 计数的可见面」一起设计，否则重演「三处记账手拿会漂」）、`limitedParallelism`
> （本类 scope 直接用 `Dispatchers.IO`，默认 64 线程）。
> 守卫先红后绿（短路字节闸即当场红；帧上限刻意放宽让字节闸先撞）。细节见
> [`log/2026-10-11.md`](log/2026-10-11.md)。

> **2026-10-11 · 批 99（外审整改第四刀：shell 杀树与 stdin 关闭 · zip 三条硬化 —— M6/M7）**：
> **M6-a shell 杀树**：原 KDoc 的「不留孤儿进程」**不成立**（`destroyForcibly()` 只杀
> `sh`/`su`）。加 `killTree`（`/proc` ppid 图 + `kill` 二进制 + pid 两级反射阶梯）+ `TreeOps`
> 缝；超时路径与 `finally` 都走它；**订正了自己写出的顺序错**（点名必须在杀之前 —— 批 95 的
> 纪律）；pid 取不到时诚实降级为只杀主进程。**M6-b stdin 立即关**：shell 面契约是「不喂
> 输入」，攥着写端会让 `cat`/`read x` 挂到超时。**M7 zip 三条**：条目数闸（1 万 —— 零字节
> 海量条目能过掉两道**字节**闸，计数与字节**正交**）、`.tmp` 不自含（绝对路径相等判据）、
> `walk` 的 `UncheckedIOException` 折成 `IOException`（原来接不住 → tmp 不删、码也不对）。
> **两份 `ProcOps`**（`:platform:system` 与 `:app-service:npm`）手法同款、代码两份 ——
> 依赖方向所迫，两份 KDoc 互相点名，改一处必须看另一处；消重需抽模块（模块表变更，待拍板）。
> 守卫 7 例（shell 4 含真 `/proc` 那条、zip 3；tmp 那条实测先红后绿）。细节见
> [`log/2026-10-11.md`](log/2026-10-11.md)。

> **2026-10-11 · 批 98（外审整改第三刀：accept 遇瞬时故障不再收掉整条桥 —— M3）**：
> 两处 accept 循环（`BridgeSocketListener` 设备 `LocalServerSocket` 面 / `NewlineFrameServer`
> 本机 TCP 面）都是「任何 `IOException` 就 break」—— `EMFILE`/`ENFILE`/`ECONNABORTED` 都是
> **瞬时**的，一次就 break 等于**整条桥对所有未来脚本静默死亡**（无日志、无重启、无自愈）。
> 现口径：本端已 close（`running==false` / listener 已移除）→ 收循环；瞬时错 → 计数 + 退避 1s
> 重试、**连续 20 次**才收（无限重试 = 线程永远转不出结果且桥看起来还活着）；非 socket 类
> 异常 → 照旧收（代码 bug 不该被重试掩盖）；退避用 `retryGate.wait()` 以便 close 立刻唤醒。
> 用 `SocketException` 而非 `IOException`（前者只从 accept 抛出，语义精确）。**两份独立实现
> 各修各的、各钉一次**。诊断面各加 `acceptRetryCount` + 失败回执缝（此前死起来零痕迹）。
> 守卫先红后绿（阈值改 1 即模拟旧行为，两条当场红），判据是「握手仍能完成」这个**可观测行为**。
> CI 逐字同源 14 任务全绿零跳过；detekt 全模块绿。细节见 [`log/2026-10-11.md`](log/2026-10-11.md)。

> **2026-10-11 · 批 97（外审整改第二刀：ERR_INTERNAL 与折叠两档 / kv 迁移表 / pid 第二级 —— M4·M5·M8）**：
> 三条「单条语义改动 + 守卫」。**M4 错误映射**：新增 `ErrorCode.ERR_INTERNAL`（宿主代码缺陷
> **不是**调用方参数错），`BridgeRouter` 折叠改两档 —— `AutojsException` 原码透传、
> `IllegalArgumentException` → `ERR_INVALID_PARAM`、**其余一切 → `ERR_INTERNAL`**；此前一切
> 未预料异常都折成参数错并回显 `e.message`（脚本去改错方向的参数 + 内部路径进 wire），且与
> `RpcNamespaceHandler` KDoc「未知异常照旧穿出」直接矛盾。两处落字已同步（`:domain` +
> `bridge/js` `ErrCode`+`ERROR_CODES`，`err-catalog.test.cjs` 三面对账门钉住；**契约卷 §7.6
> 属冻结面已撤出**，待维护者同步），
> 守卫三条成对；契约卷 §7.6 属冻结面已撤出待维护者同步。**M5 pid 绑定**：`engine:node-process` `ProcessLauncher` 补 pid 私有字段那级
> 反射阶梯 → Android 8–12（`Process.pid()` 需 API 33、minSdk 26）`spawn.pid` 不再恒 null，
> 此前 `RunIdentityRegistry` 的防劫持判据**整段静默跳过**；加 `pidUnavailable` 计数
> （「跳过」与「度量通过」在返回类型上一样，计数是唯一可见信号）。**M8 kv 升版**：
> `onUpgrade` 从「DROP 重建」改为显式迁移表（纯函数 `KvSchemaMigrations`，理由：`:platform:system`
> 无 Robolectric，Android 面本机只编译不执行）+ `onDowngrade` 响亮失败；找不到路径抛
> `UnknownMigration` 而非静默重建。守卫 7 例（3+2+...）。CI 逐字同源 14 任务全绿零跳过；
> facade 201 pass（1 skip = 既有真 addon 环境门禁）；detekt 全模块绿。细节见
> [`log/2026-10-11.md`](log/2026-10-11.md)。

> **2026-10-11 · 批 96（外审整改第一刀：排队不挡急停 / jsonl 半行清盘 / 桥 TTL 截断）**：
> 维护者转来外部评审报告，本批做其中四件（纯工程、本机可验证）。**① 排队的 start 不挡
> stop/killAll/看门狗（B1 高）**：`start` 等证不再整段持 `guard`（满池排队可挂 60–120s），
> 只记账时取锁 + 急停代次对表（`killAll`/`forceStopAll` 推进）；池自扛并发（夺槽前与
> execute 后两处对表，代次变即作废回收，绝不夺被急停强释的槽、绝不把账外野进程留在设备上）。
> 守卫新建 3 例，修前实测 `stop(A)` 2927ms（= 排队超时）→ 修后 ≈0。**② jsonl 半行清盘（B3）**：
> `FileTaskStore`/`FileRunArchive` 打开时截到最后一个完整行（原只内存丢、盘上留着 → 新行与
> 残片粘连，之后每次重启构造即 IOException）；守卫各 +1 走通「重开→再写→再重开」闭环。
> **③ 桥 TTL 溢出截断（B4 半）**：`RequestRegistry` 截到 24h 上限（`now+ttl` 翻负 = 立即超时）。
> 守卫 +1。**登记未改**：`BridgeRouter` 未知异常吞成 `ERR_INVALID_PARAM`（教训与 KDoc 矛盾）、
> `readAllBytes` 全量读/无压缩、进程池两处记账真源、`SqliteKvOps.onUpgrade` 丢表。完整 CI JVM
> 门同源待跑。细节见 [`log/2026-10-11.md`](log/2026-10-11.md)。

> **2026-10-11 · 批 95（T1 杀树换 /proc 实现 —— 修掉批 91 在真机上的静默空转 + 无人点名的 signal）**：
> 挖出比记账更严重的实情：`java.lang.ProcessHandle` **整类不在 android.jar 里**（api-versions.xml
> 无条目、jar 无 class、`Process` 桩面无 `toHandle()`/`pid()`，javap 逐方法核过）—— 批 91 的
> `runCatching { proc.toHandle().descendants() }` 在**真机上恒空集、零报错**，杀树退化成只杀
> 主进程（「孙子被 reparent 后链断」那条记账其实从未成为主要问题：整条后代链在设备上都不存在；
> 同族「看着能用」坑的第四例，前三例是 `Path.of`/`UnixDomainSocketAddress`/env-NUL）。
> **修法**：`ProcOps`/`ProcTreeOps`（新文件）—— /proc ppid 图扫描 + `kill` 二进制；**扫在杀
> 之前**（reparent 只断死后回找）；同 UID 靠 fork 继承（图上可达即同 UID）；pid 两级反射阶梯
> （`pid()` 方法 → 私有字段 `pid`，与 `ProcessLauncher` 同手法；都探不到如实 null）；
> stat 解析找最后一个 `)`（comm 可变）；收尸 = 扫→TERM→宽限→KILL 活口→**二次收割**
> （从仍活着的第一轮成员再扫，捡 TERM 被忽略那条新生的）→KILL。`T1TreeOps` 缝 +
> procDir 注入（合成 /proc 树单测，不借宿主判状态）。
> **无人点名的 signal**：`exitValue >= 128` 时按 POSIX 惯例 `n = exit - 128` 反查信号名表
> （1–31；32+ 不猜）—— 128+n 就是「被信号杀死」的编码本身；已知误报档（自杀 `exit(143)`）
> 与仍欠（区分 CLD_KILLED/CLD_EXITED 要 JNI waitid）如实记。
> 守卫：`ProcOpsTest` 7 例 + `T1ProcessTest` +5（**真进程树用例抓到第一版实现的真 bug**：
> readStat 读成了目录本身而非其下 stat 文件，症状与设备静默空转同形 —— 没有它这个 bug
> 会原样带上真机）；完整 CI JVM 门 14 任务全绿。决策：[`design-decisions.md`](design-decisions.md)
> 第 64 项。**边界**：真机行为由用户自测。细节见 [`log/2026-10-11.md`](log/2026-10-11.md)。

> **2026-10-11 · 批 94（T1 欠账两件：脚本输出真流式 + 命令可取消 —— 桥 kill 帧 + 控制台「停止」）**：
> 批 91 登记的七件欠账里做掉两件。**① 输出流式**：`ScriptOpExecutor.execute` 补 `OutputSink`
> 缝（与批 90 `HeavyOpExecutor` 同形），T1 会话进程（`npm run`/`npx`）的 stdout 边读边落
> 控制台环 —— 此前那条路只攴尾部进异常详情，控制台看不到脚本逐行输出；「报过流不再重复
> 尾部」的判据落在**调用方选型**上（`OutputSink.None` = 「这边没人看流」，尾部必须兜底）。
> **② 桥侧 kill 帧**：`BridgedChild.kill()` 从「只置 `killed` 位」变真发 `{t:'kill',id,signal}`
> 帧（SIGTERM → destroy、SIGKILL → destroyForcibly，**其余如实 ERR_NOT_IMPLEMENTED**）；
> kill 先于 spawn 落地是合法时序（shim 发完 spawn 就能发 kill），`T1Child` pending 位兜住、
> 起完补杀；exit 帧对**宿主确知的杀**按真 child_process 口径 `code=null, signal=<发出去的
> 信号名>`（「signal 恒 null」旧记账管的是没人点名只看到 128+n 的情形，那条照旧 null）。
> **③ 控制台「停止」**：`ScriptOp` 加 `cancelSignal: OpCancelSignal`（读位不是回调 ——
> `TrackedOp.cancelled` 那个 `@Volatile` 位直接透出，没有「句柄收走后回调悬挂」的问题）；
> `NpmScriptExecutor` 的 `waitFor(timeout)` 单发改**切片轮询**（200ms 一片，片间查取消位与
> 墙钟）—— 置位即 destroyForcibly + 关会话（子进程随 `session.close()` 被收），这是「停止」
> 按下去后进程树**真死**的物理机制。facade/`HostSummary` 加 `cancelConsoleCommand(projectId):
> Boolean`（回「是否取消到了东西」；缺省抛 `ERR_NOT_IMPLEMENTED` 同纪律）；协调器实现 =
> 快照该项目未终态句柄逐个走既有 `cancel`。UI 跑动中「执行」位换成「停止」，回执措辞
> 「已请求停止」（回收是异步的，终态行由页面轮询随后拉）。**覆盖面如实**：T1 真杀；
> **重操作（install 会话）仍弃结果不杀进程**（TTL 兜底），本批没给它接取消探针。
> 守卫：`T1BridgeNodeTest` +1 / `T1BridgeE2ETest` +2（流式到达时间戳 + 秒级取消）/
> `T1ProcessTest` +4 / `InstallCoordinatorTest` +2 / `ConsoleCmdOpsTest` +2；完整 CI JVM 门
> 14 任务 + detekt（`:ui` baseline 重生成，`ConsoleScreen`/`InputRow` 加参）+ `lintDebug` 全绿。
> 契约卷同步：§10.3 T1 落地追记④、§10.7 方法表、§10.9 第 3 条。决策：
> [`design-decisions.md`](design-decisions.md) 第 63 项。**边界**：真机行为由用户自测。
> 细节见 [`log/2026-10-11.md`](log/2026-10-11.md)。

> **2026-10-11 · 批 93（控制台 `npm run` / `npx` 拆除人工审批 + env-NUL 缺陷修复，真机实证）**：
> 用户在真机上看清审批卡的形态后裁定「审批不是这样用的……依赖面板是方便用户直接安装和管理
> 依赖的地方。你把审批去掉吧」—— **整面拆除**：`ApprovalLedger`/`ApprovalLedgerStore` 及
> 测试、桥面 `requestApprove`/`approvals`、`:domain` 七个审批类型、facade 四个审批方法
> （`approvalLedgerSnapshot` 保留只读供审计读旧账）、`HostSummary.resolveNpmApproval`、
> UI 审批卡；依赖面板回到纯安装/管理。放行判据改**解析层白名单**，`versionHash` 只随
> `ScriptOp` 进审计、不再作放行判据。同批修真机暴露的 **env-NUL** 缺陷：
> `AndroidT1SocketBinder.connectTarget` 是 `"\0"+name`（abstract 名前缀），经 env 注入
> `AUTOSCRIPT_T1_SOCKET` 后 `ProcessBuilder.start()` 见 NUL 必抛 —— 改 env 给**裸名**、
> shim 侧按 `main.cpp` 同源判别（'/' 前导 = 路径直连，否则 abstract）补 `'\0'` 前缀。
> 桌面路径型原样，`T1BridgeNodeTest`+`T1BridgeE2ETest` 继续绿 = 直连分支钉死。
> 决策：[`design-decisions.md`](design-decisions.md) 第 62 项。守卫：完整 CI JVM 门 14 任务
> 全绿。**边界**：真机行为由用户自测。
> 细节见 [`log/2026-10-11.md`](log/2026-10-11.md)。

> **2026-10-10 · 批 91（T1 spawn 桥本体 —— `npm run` / `npx` 第一次真跑起来）**：
> 批 88/89 的审批卡、批 90 的流式输出，到这一步之前**全断在同一处**：`InstallSeams.kt` 的
> `scriptExecutor` 是 `Unavailable`，用户人工批了审批票，敲 `npm run build` 得到的仍是
> `ERR_NOT_IMPLEMENTED`。**这是 npm P1 里唯一还挡着用户动作的一条**，本批接通。
> **链的形状**（§10.3 T1 下半段）：npm 会话进程经 `NODE_OPTIONS=--require` 注入桥 shim →
> `npm run` 发 `spawn("sh", ["-c", body])` → shim 经 unix socket 把 `{cmd,args,opts}` 报给
> `:main` → 宿主起真进程 → stdout/stderr/退出码经同一条 socket 以**假管道**回填。
> 件：`npm-t1-bridge.cjs`（classpath 资源）/ `NpmT1Bridge`（绑定 + 握手 + 逐帧 + 收尸）/
> `T1Process`（一个已放行的子进程）/ `NpmScriptExecutor`（一次 `npm run` = 一条 npm 会话）/
> `AndroidT1SocketBinder`（设备侧 `LocalServerSocket` + abstract 名 —— JDK 17 的
> `UnixDomainSocketAddress.of(Path)` **拒 NUL 前缀**，本机实测 `InvalidPathException`）。
> **两条 shim 互斥**（最容易做错的一处）：安装会话要「零 spawn」故注门禁 shim，T1 会话要的
> 恰恰是「spawn 走桥」—— 把门禁也注进去，每一次 spawn 都会先撞门禁，那不是更安全，是把刚
> 接上的路又堵死。故 T1 这条路上 `NODE_OPTIONS` **只注桥那一份**。
> **本批最贵的一课（探针测出来的，不是读代码读出来的）**：`Channels.newInputStream(ch)` 与
> `Channels.newOutputStream(ch)` **共用同一把锁**（`ch.blockingLock()`），而
> `ChannelInputStream.read` 是**抱着那把锁阻塞**的 —— 读线程一进 `readLine()` 等下一帧，
> 写线程（子进程输出泵）就永远拿不到锁，一条**双向**协议被自己的流包装锁成了单向。症状是
> 两类测试双双挂死（探针与 JVM 都停在各自的 `read` 上），`jstack` 里只有
> `BLOCKED (on object monitor)`，**没有一条报错指向锁**，报错面只有「超时」。改直读
> `SocketChannel`（它自带分开的读/写锁，全双工）后当场全绿。**同批还修了两处同源的静默挂死**：
> ① 握手帧**不能走 `send`**（`send` 排队等 `ready`，而 `ready` 恰恰要等 `helloAck` ——
> 那条 Ack 永远不会来）；② 会话 socket 在**没有在途子进程时必须 `unref`**（常驻 socket 是
> 活跃 handle，会把 Node 事件循环吊住，npm 干完活永远不退），子进程在飞时必须 `ref`
> （退出码是经这条 socket 回来的）—— 判据是「有没有在途子进程」（`refWhileBusy()`）。
> 守卫 **16 例**：`T1BridgeNodeTest` 7（真 node，不经 npm）/ `T1BridgeE2ETest` 4（真 npm 全链）/
> `T1ProcessTest` 5（替身：fd 分开、双侧各拒一次、起不来是结果不是崩溃、收尸幂等、TERM 不退
> 才 KILL）。**T1 两类不设 `assumeTrue`**：E2E 那条是全仓**唯一**证明「npm 真把 spawn 走到桥上」
> 的用例，而它既不在 `TestGuard.ENV_GATED` 里、也不在 `check-e2e-ran.sh` 的验尸名单里 ——
> 跳过就是**静默丢覆盖**。**欠账如实记（这一版没做的）**：不用引擎池（§10.3 写的是「沿
> EnginePool 同路径拉临时引擎」，本版起的是 `node <npm-cli.js> run <name>`；引擎池那条路要求
> 「让引擎执行一段 JS」，而这里要执行的是 npm 自己，spawn 面不同）/ 不做最小 CapabilityMask
> （脚本进程与 App **同 UID** —— 设备上没有可用的隔离手段，沙箱已裁）/ 不做输出流式（脚本
> stdout 由 npm 自己捕获）/ stdin 转发不做背压 / `signal` 恒 null（Java 的 `Process` 不给
> 信号号）/ 杀树用 `ProcessHandle.descendants()` 而非 `kill -- -<pgid>`，代价是**孙子进程被
> reparent 给 init 后链就断了**（本版没兜住）。**边界**：真机行为未验（用户自测）。
> 细节见 [`log/2026-10-10.md`](log/2026-10-10.md)。

> **2026-10-10 · 批 90（控制台「活着」三件：真流式 stdout + 常驻进度推送 + 命令历史落盘）**：
> 用户口径「控制台从『现取一次』变成『活着』」。**① 真流式**：`HostNodeExecutor.runNpm`
> 原先先 `readBytes()` 再 `waitFor`（顺序上避开了管道反压，代价是跑完才拿到全部输出）→
> **专门的读流线程**逐行经新的 `OutputSink` 缝报回宿主。缝与 `ProgressSink` 分开：那条装的是
> `InstallEvent`（脚本侧契约形状），这条装 npm 自己的原文 —— 塞进事件契约等于为一个宿主界面
> 改脚本侧的面。两处必须记住：流真报过时**不再补 `outputTail` 行**（否则同一段文本显示两遍，
> 用户以为 npm 跑了两回）；`OutputSink` **跳过空行**（真实 npm 输出以空行结尾，而
> `takeLast(8000)` 取到的正是空行 —— `HostNodeNpmE2ETest` 抓到的）。
> **② 常驻进度推送**：`:ui` 新增 `LivePoll.pollWhile` + 两个调用点（提交后跟到跑完 /
> 子页在前台时一直跟）。宿主是入队即返回的，而每个读口原先都是「进页面 / 手动刷新时取一次」
> —— 于是敲完命令屏幕上什么都不动、阶段条永远停在 `QUEUED`。**刻意不用**宿主的 `progress`
> SharedFlow：它**没有重放**，晚到的订阅者永久丢那一批，而「切进来时命令已经跑了一半」正是常态。
> 三条自我约束：**有界**（`maxTicks` 到顶必停）/ 只依据**宿主给的**事实停 / 每轮走同一个读口。
> 间隔与轮数可注入 —— 用真实间隔（400ms）验「有界」会把用例拖成四分钟，而一条拖慢 CI 的用例
> 迟早被 `@Disabled` 关掉，那等于没有守卫。**③ 命令历史落盘**：新增 `ConsoleHistory`
> （`files/.ns/console-history.jsonl`）。与审计史**长得几乎一样但纪律相反**（那个只追加、
> 永不清理；这个允许修剪）—— 差别写在类 KDoc 里，**下一个人很容易把修剪搬到 `InstallHistory`
> 上去，那才是真错**。三条口径：按项目分开读 / **带凭据形态的行整条不记**（`_auth`/`--otp`/…；
> 不打码后记录 —— 打码后的历史看起来是一条能跑的命令，点它得到「认证失败」，那是历史自己造的
> 假故障）/ 写口在 `:ui` 的**派发点**（那里才有用户敲的原文；执行入口拿到的是剥掉入口词的正文）。
> **同批修一处真错（探针测出来的，不是读代码读出来的）**：两处执行体 sink 调用点写的是
> `events.tryEmit(ev)`，只喂了 `SharedFlow` 而**没经 `emit`** —— 而 `emit` 是唯一写三个地方的
> 那个，执行体独有的 `DOWNLOAD`/`REIFY` 阶段**从未进过事件环**（脚本侧 `onProgress` 收不到、
> 阶段条停在 `RESOLVE`）。三个环各自的测试都绿，因为它们都只喂自己那条路。
> 守卫：`HostNodeExecutorStreamTest`（含**到达时间戳**断言 —— 缓冲实现 806ms 失败、阈值 300ms，
> 这是「真流式」与「跑完再报」的唯一区分手段）/ `NpmEventDrainTest` 新增一例 / `ConsoleHistoryTest`
> 9 例 / `LivePollTest` 4 例 / `ConsoleCmdOpsTest` 新增 3 例 / `InstallCoordinatorTest` 新增 2 例；
> 反证四条各自命中（流式回退成缓冲 / `emit` 回退成 `tryEmit` / 去掉历史的项目过滤与凭据守卫 /
> 轮询每轮都读历史）。**边界**：真机行为未验（由用户自测）；凭据守卫认的是**参数名形态**不是
> 熵启发式（用户把 token 直接当包名敲不会被认出来 —— 本类是便利缓存不是 DLP）。
> 细节见 [`log/2026-10-10.md`](log/2026-10-10.md)。

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
> （`files/.ns/script-env.jsonl`，追加 + `force(true)` + replay 收敛 + 最后半行容忍 +
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
> （`files/.ns/approve-ledger.jsonl`，逃生口显式）；② 读口 `PackageManagerFacade.snapshot()`
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
> `ERR_NPM_SPAWN_BLOCKED`，并往 stderr 打可识别播报）+ `NpmSpawnGate`（落 `files/.ns/`、
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
| —（**§8.1 新增**） | 管理面板「环境变量」= **脚本全局环境变量**（契约面原本**没有这一条**，本批新增） | **已落地（2026-10-09，批 82）**：用户编一组 KV，所有脚本执行时注入脚本进程的 `process.env`。`:domain` 契约 `ScriptEnvStore`/`ScriptEnvEntry`/`ScriptEnvKeys`；落盘 `:app-service:script-repo` `core/FileScriptEnvStore`（`files/.ns/script-env.jsonl`）；注入面 `NodeEngineConfig.scriptEnv: () -> Map`（**每次 spawn 现读**；用户键先写、宿主键后写，**顺序即契约**）；`:ui` `ScriptEnvScreen` 经 `HostSummary` 三条读写。**四条口径**：全局非按项目 / 每次 spawn 现读 / 不注入 npm 安装会话进程 / 拒收 `AUTOSCRIPT_` 保留前缀。口径见 [`design-decisions.md`](design-decisions.md) 第 52 项。~~**同组仍有一行未落**：管理面板的「镜像源管理」仍是 toast 占位。~~ **该行已落（2026-10-09，批 83）**，见下一行。**边界**：脚本进程 `process.env` 的实际可读性要装包才验得到（真机验证积压） |
| §10.9 第 3 条 | **npm 终端视图**（`auto.npm` 的命令面） | **已落地（2026-10-09，批 84）**：控制台由「日志屏」改成**命令面** —— 选项目 + 敲一行 npm 命令 + 看输出。判据唯一一份住 `:domain`（`NpmConsoleKeys`：`parse` 四形态 / `SUBCOMMANDS` 白名单 `install·uninstall·ci·ls·list·prune·dedupe·audit` / §10.6 轻·重拆分 / `packageSpecsIn` / `gitSpecIn` / `rejectProjectId`），宿主侧执行入口与 `:ui` 输入行读同一个结论；输出粒度 = **事件流 + npm 输出尾部**（`HeavyOpOutcome.outputTail`，`InstallEvent` → `NpmConsoleLine` 投影在宿主侧一处，`NpmConsoleLine.ok` 是终态成败位，呈现层不按文本猜）；`npm run`/`npx` **照实接线**到 §10.3 T1 门禁（未获批 → `ERR_PERMISSION_DENIED` 且请求已入队；~~获批但 spawn 桥未接 → `ERR_NOT_IMPLEMENTED`~~ —— **该分支自 2026-10-10（批 91）起不再命中**，桥本体已落、获批的命令真跑）。读口 `HostSummary.runNpmCommand`/`consoleOutput`，**不经桥**（桥面一条不加）。原控制台的日志内容**一行不少地**搬去管理面板 → 日志管理（三段：系统日志/脚本输出/任务日志），数据面（`ConsoleCollector`/游标/`stopRun`）一个字没动。~~**未落**：真流式 stdout、shell 引号解析、命令历史持久化、白名单外子命令（`config`/`publish`）、T1 spawn 桥本体、命令的取消~~ —— 逐条销账：**真流式 stdout 与命令历史持久化已落（2026-10-10，批 90）**、**T1 spawn 桥本体已落（2026-10-10，批 91）**。**仍未落**：shell 引号解析、白名单外子命令（`config`/`publish`）、命令的取消（`NpmConsoleHandle` 与 `InstallHandle` 刻意分开，故无 `cancel()`）、T1 脚本输出的流式（脚本 stdout 由 npm 自己捕获，控制台只看得到摘要）。口径见 [`design-decisions.md`](design-decisions.md) 第 54 项；**同批修 `ScriptPaths.PROJECT_ID` 放行 `.`**（`resolve("..")` 正好跳出项目根）。**边界**：真机行为未验 |
| §10.9 第 8 条（**新增**） | 管理面板「镜像源管理」= **npm registry 全局配置面** | **已落地（2026-10-09，批 83）**：编的是 npm registry 的**全局缺省**，粒度**全局一份**（用户裁定；不做「全局缺省 + 项目覆盖」的编辑面）。判据唯一一份住 `:domain`（`NpmRegistryKeys` 的 `canonicalize`/`reject`，被 `NpmRegistryVerifier` 缝边界与 `:ui` 输入校验共用）；落盘 `:app-service:npm` `NpmrcFile` + `NpmGlobalConfig`（`files/.npmrc`，整文件重写非行级 append）；解析链 `InstallCoordinator.resolveRegistry` = 项目 `.npmrc` → 全局 `files/.npmrc` → 出厂官方，**交叉校验的首选与实际安装同源**；`:ui` `RegistryScreen` 经 `HostSummary` 两条读写。**三条写法裁定**：存原样（只 trim）不规整化（规整化丢 query ⇒ 自建网关 `?token=…` 被静默剥掉）；空输入 = 删键（恢复出厂，不是写空值行）；校验不过抛原文。**同批修两条断链**：`HostNodeExecutor` 不再无条件注入 `--registry`（生产唯一构造点从不传参 ⇒ 永远钉官方）且 `prepareWorkDir` 开始拷项目 `.npmrc`（`--prefix` 已给 ⇒ 不拷就成死配置）。口径见 [`design-decisions.md`](design-decisions.md) 第 53 项。**未落**：首启引导 ping 探测/镜像候选表（第 6 条）、审计页（`InstallHistory` 仍无 UI 消费方）、`proxy`/`cache-retention` 两键（桥面本来无入口）。**边界**：真机网络下能否连通要装包才验得到 |
| §8.5/§8.6 | 引擎侧 `waitCompletion` 超时不发起（无人 await 的 run 没人收尾） | **已覆盖（2026-10-01）**：`TimeoutEnforcer{WATCHDOG}` + 看门狗期限线（`KillCause.TIMEOUT`）—— 残余边界见 §8.6（期限只覆盖声明了期限的 run + 轮转须在跑） |
| §8.5 | 意图日志的 **SQLite 实现**（契约写的是「append-only（SQLite，启动即回放）」） | **已落地（2026-10-08，批 77）**：~~未落，且分歧已如实标注~~ 卡点（接口住 `:app-service:scheduler`，`:platform:*` → `:domain` 不反向）**按「搬接口」解决** —— `IntentStore` 迁 `:domain`，`SqliteIntentStore` 落 `:platform:system`，两份实现（jsonl/SQLite）跑**同一套契约测试**（`:domain` 新增 `testFixtures` 源集，正是它抓出幂等索引漏洞）；老 jsonl **带原 runId** 一次性导入（可重入、验完才改名归档）、打开失败**如实回落 jsonl**。**仍待裁**：~~保留期策略~~ 日志**只追加、从不清理**，体积随 run 数线性增长（每次 run 恒定两条行，已无冗余可压），**保留期策略**（老终态行 / 老 nonce 能否丢）会动到 §8.5 的幂等锚点 —— **本批显式排除，入池 backlog**。**2026-10-08 批 78 修订**：jsonl 写入侧已删（生产只有 SQLite，打不开即失败、不回落），故本行的「两份实现（jsonl/SQLite）跑同一套契约测试」「打开失败如实回落 jsonl」**均已作废**（原文逐字保留）；契约套件改由 `:domain` 内的 `InMemoryIntentStore` 承载且**无环境门禁**。口径见 [`design-decisions.md`](design-decisions.md) 第 47 项① |
| §11.2 T2 / §10.2 | **npm 生产装配接线**（`lockKey` / `executor` / `scriptExecutor`） | **部分落地（2026-10-01）**：`executor` **已接线** —— 素材随包（`assets/npm/**` ← gradle `prepareNpmCliAssets` ← `node-runtime-build` 出口）→ 启动期 `AssetTreeCliSource` 幂等落位 `files/npm/` → 注入 `HostNodeExecutor`（宿主 = `nativeLibraryDir/libnoden.so`）；~~两条同时成立才注入（部署就位 + 有宿主）~~ **三条同时成立才注入（部署就位 + 有宿主 + `child_process` 拦截 shim 落位，2026-10-09 批 80）**，否则保持 `Unavailable` 且原因原文进 `AssembledShell.npmCliFailure`（shim 是 §10.11 P0 承诺面，落不上就不注入 —— 不静默降级成「装是能装、守卫没了」）。~~**仍缺**：`lockKey`（`lock.sig` 既不签也不验，全仓无 `KeyProvider` 实现；接缝形状 A1c 已就位）~~ **`lockKey` 已接线（2026-10-08，批 79）**：`LockKeyStore.AndroidKeystore`（Keystore 密钥，get-or-create；「取不动」绝不静默重建）+ `AppShellKit(npmLockKeys=…)` → `NpmShellKit.assembleHandler(lockKey=…)`；`ci` 先验签、`install` 收尾重签、`exportSnapshot` 带 `snapshot.sig`；取钥失败本次不装该防线且原因原文进 `AssembledShell.npmLockKeyFailure`。口径见 [`design-decisions.md`](design-decisions.md) 第 49 项。~~**仍缺**：`scriptExecutor`（T1 spawn 属 P1）~~ **已接线（2026-10-10，批 91）**：`AppShellKit.NpmWiring.scriptExecutor` + `wireT1Executor`（**桥 shim 落位 + socket 试绑**两条齐才有值，缺一条保持 `Unavailable`）；与安装链的「三条齐」**刻意不共用判据** —— 安装链要门禁 shim（零 spawn）、T1 链要桥 shim（spawn 走桥），两者互斥，一条缺席不该拖另一条下水。另：~~素材版本 = **npm 11.19.0 ≠ §10 脊梁的 npm 12.x 系**（落差登记在 [`backlog.md`](backlog.md)）~~ **落差已消解（2026-10-02 批 9）**：素材换 registry `npm@12.2.0`，§10.1 脊梁满足（backlog A6 划掉，[`design-decisions.md`](design-decisions.md) 第 26 项）。契约侧标注同批更新（§10.1 接线现状 + §10.12 风险表 + `SECURITY.md`）。**2026-10-02 勘误**：本条原先还写了「+ §11.2 T2 + §11.3 第 8 条」—— 实测**没改到**：§11.2 T2 行本来就不含 npm 版本叙述（无物可改），而 `11-security.md:64-69` 的「素材版本落差」段（在 §11.3 第 8 条**之上**的同一子弹列表内，不在第 8 条里）**原文未动、仍写 npm 11.19.0**，且按 `SECURITY.md` 的优先级规则「两处有出入以设计文档为准」→ **错的那份赢**。已登记 [`backlog.md`](backlog.md) 的 **C10** |
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
| 2026-10-28 | 1 | 一条目：**依赖页第十八批：右上角刷新改清日志 + 页头盲盒镜像源卡**。**34**（用户口径「右上角刷新改成删除日志框里的日志」）：顶栏「刷新」→「清日志」，`NpmState.clearLog` 清可见行/裁切标注/操作账，`consoleSeq` 水位**不动**（宿主 console 是增量窗口，游标归零会把刚清掉的行重放回来；与悬浮日志窗 `clearLogs` 同语义）；手动刷新删除后快照与日志切页签现取、安装续拉由 `pollInstallWhileRunning` 接管（批 90）。**35**（用户口径「最上面放个包裹/盲盒圆形图标…黑色加粗的选中项目名称…灰色小号的当前使用npm镜像源链接，格式样式tg源码里都有」）：页头三段卡 `ScopeHeaderCard`（圆形底+自绘 `PACKAGE` 字形 · 17.5sp Bold #222222 · 13.5sp #79817E，参数实测 TG `ProfileActivity`—— /tmp/tg 原为空壳，用户指示浅克隆 DrKLO/Telegram 官方仓库作参考）；镜像源显示**当前生效那一家**（configured ?: 官方源，「在用什么就读什么」），NPM 子页现取顺带 `reloadRegistry()` 共享读数 | [`log/2026-10-28.md`](log/2026-10-28.md) |
| 2026-10-28 | 1 | 一条目：**供应链补刀：markdown-it 钉 ≥14.3.3**（结 GHSA-vfgh-jcmw-g37q —— 第十三批起挂账的 js-tests 账外红结算）。typedoc 0.28.20 依赖 range 仍是 ^14.3.0 会装到受影响的 14.3.2，升 typedoc 无解 → npm overrides 钉 `^14.3.3`；lock 无法本机重解析（禁 npm），用 registry 精确 integrity 手改 `node_modules/markdown-it` 一条，由 CI 的 npm ci + audit 实测验过才算数 | [`log/2026-10-28.md`](log/2026-10-28.md) |
| 2026-10-28 | 1 | 一条目：**依赖页第十七批：裁掉安装行下的重复日志 + 第十六批对照实验收官**。**收官**：用户实测「release 版本确实很流畅」—— 病在包形态、代码无罪，后续评分一律用 release 包。**裁剪**（用户口径原文「去掉安装依赖下面会输出日志（目前它的下面和日志框里都会显示，只保留日志框里的就行）」）：`InstallProgressRow` 只画六格阶段条 —— 进行中的阶段文字（label+包名）去掉、收尾后整段收起（结果与病因由日志框承担：失败进 `opError`、成功在 npm 输出与回执）；阶段条不是日志照画（批 87 的进度反馈）。状态层（`InstallProgressState`/`foldProgress`）一行未动，只删呈现层两个绘制点 | [`log/2026-10-28.md`](log/2026-10-28.md) |
| 2026-10-28 | 1 | 一条目：**依赖页第十六批：性能形态对照 —— 真形态管线加出 release 变体**（用户拍板，授权解冻 `engine-native.yml` 一处）。**发现**：真机复测一直在 debug 形态（`assembleDebug` → `debuggable=true`，ART 校验级编译 + dex 不过 R8），Compose 官方口径「评性能必须 release + R8」—— 前几批省的是每帧小钱，包形态贵一个量级。**改法**：同一 gradle 调用出 debug+release，断言函数化对两包同跑，新增 `android-release-true-form` artifact；release 走既有 buildType（R8 全量 + 资源收缩 + 仓库 dev 密钥可互覆）。R8 风险复核：反射面清单逐条再对，逮到一处注记过期（`ProcOps.pidOf` 的 `getDeclaredField`，批 105 后出现），反射目标全在 java.lang 平台类上不参与收缩，结论不变、已加勘误。**预期**：release 不卡 = 包形态的病；还卡 = 才轮到行物理/设备档次。**诚实边界**：实验不是修复，等真机对比 | [`log/2026-10-28.md`](log/2026-10-28.md) |
| 2026-10-28 | 1 | 一条目：**依赖页第十五批：清单收回定高内滚框**（用户口径原文「还是很卡顿，依赖列表你不要让它完全展开。而是在一个组件框里滑动」）。展开态不再把每颗依赖摊成页面 `LazyColumn` 的 item，而是收成一条 item —— 卡内 `heightIn(max = 320.dp)`（约 7 行）的定高框 `DepsListBox`，框内自己的 `LazyColumn` 懒加载各行；页面滚动只剩作用域卡 / 这一组 / 日志卡三段，翻清单时动的只有那个框。**作废第十一批第 31 条**（摊平 + 全页只留一个可滚动容器，2026-10-18）—— 摊平没治好卡，用户裁定相反结构；「两层滚动要交接」的旧顾虑在定高框形状下不成立。与第十四批的分层：十四批去的是每帧整屏圆角裁剪，这批去的是滚动负载落在整页长列表上；若仍未解决，候选只剩行物理与设备档次 | [`log/2026-10-28.md`](log/2026-10-28.md) |
| 2026-10-28 | 1 | 一条目：**第十三批补刀：短路判据提成私有函数 `canReusePrevious`（detekt ComplexCondition 4 超阈值 → 提函数，行为零变更，三条 `===` 用例不动）**；同批另一处红（js-tests）不是本批的账：`npm audit` 报 markdown-it <=14.3.2 新 advisory（GHSA-vfgh-jcmw-g37q，high），依赖环境问题 | [`log/2026-10-28.md`](log/2026-10-28.md) |
| 2026-10-28 | 1 | 一条目：**依赖页第十四批：ManagementTransition 稳态剥壳 —— 顶层裸直绘**（用户口径原文「翻动起来还是很卡啊，认真优化好不好。翻起来列表一顿一顿的」）。第十三批 premise 被证伪：`pollWhile` 轮询只写局部变量、不发布状态（证伪标注见切片第十三批处），卡顿纯渲染侧。逐层排除后，依赖页与首页（pager 里那份，用户说不卡）的唯一渲染路径差异 = 顶层 `alpha + graphicsLayer + 全屏圆角 clip` 三层包装：`Modifier.clip` 每帧给整屏滚动内容重做圆角裁剪路径（非矩形 clip 走 stencil/clip layer，低端 GPU 上一顿一顿的经典来源），首页无任何一层。改法：顶层加 `settled` 判据（progress==1 && dismiss==0 && 无手势 && innerTranslationX==0），稳态裸直绘，三层包装一个不留；动画/手势一开始包装即挂回，同帧完成不闪；`claimPointerHits` 两路都留。圆角裁剪在稳态不可见（顶层铺满屏、物理屏自裁四角）。边界诚实：若仍不解决，剩行物理与设备档次，需真机二分（用户已拒二分） | [`log/2026-10-28.md`](log/2026-10-28.md) |
| 2026-10-28 | 1 | 一条目：**依赖页第十三批：Glyph 坐标提成常量（绘制期零分配）+ 状态「没变就不换引用」短路（轮询期不再整屏陪重组）**（用户口径原文「依赖列表展开时，滑动特别卡顿」）。**一**：12-18 那批的 `GlyphScratch` 池子只覆盖 `Path`/`Stroke`，坐标一路还漏着 —— `path(0.19f to 0.36f, …)` 的 **vararg 数组、`Pair`、装箱 `Float` 在调用点现造**，而 `when (kind)` 整段每帧重跑：展开的依赖列表一行一颗 ⟳，滚动时每帧上百个小对象，GC 是「攒出来的一次停顿」。改法：84 处调用点坐标**整体提成文件级常量**（`P_<字形>_<笔名>` = `Array<out Pair<Float, Float>>`，类加载时建一次），`path()` 改收常量，数值逐字相同、几何一条未动，绘制期零分配。**二**：`NpmState.of` 无条件重建 + `pollInstallEvents` 无条件 `copy`，而快照每次回读内容相同 —— LazyColumn 的 item lambda 捕获的是 state **引用**，等值重建也让展开的可见行每 tick（安装轮询 400ms 一次）全量重组合。两处短路：`of` 在 `snapshot == previous.snapshot` **且**无失败态、无操作账时原样复用 previous（失败态不短路 —— 它只改 `load`，短路会把「读取失败」留在日志框；有账不短路 —— `opNotice` 的 KDoc 写着「现取即清」）；`pollInstallEvents` 在游标没推进且阶段没变时原样返回同一份。三条新测试（`NpmStateTest` 两条 + `NpmInstallOpsTest` 一条）全用 `===` 断引用。**三**：第十二批第八条那个「为什么首页不卡」的待测数依旧开放，没往 `ManagementTransition` 那两层全屏 `graphicsLayer` 上猜；事件真推进时的重组是合理成本，没为省重组把阶段条挪出状态。| [`log/2026-10-28.md`](log/2026-10-28.md) |
| 2026-10-18 | 1 | 一条目：**依赖页第十二批：把依赖清单链路上说得清的白扔去掉**（用户口径原文「滑动起来还是很卡啊，首页的文件列表就不卡。好好优化啊」）。**先说实话：本机测不了帧率**（不跑 Gradle、不连设备、禁 adb 截图/读屏），故本批不是「测出来修好了」，是逐条指名道姓地去浪费。**一**：`NpmState` 的 `installed` / `logLines` / `sizeLabel` / `cacheSizeLabel` / `project` 五个派生属性从 `get()` 改成 `by lazy(LazyThreadSafetyMode.NONE)`—— 从前**每读一次就重算一遍**：`installed` 每次读都 `.map` 整份依赖清单、造一整列新 `NpmRowState`，而卡头那句条数还要为了一句长度**再读一次 `.size`**（又映射一整个列表）；`logLines` 每次读都 `buildList` 一遍且每行重拼 `timeText + 两空格 + text`；两个尺寸每次读都跑 `String.format`；`project` 每次读都线性扫 `snapshot.projects`；`NpmRowState.sizeLabel` 同理由 `get()` 改 `val`。取「算一次」而不是进构造函数现算：一份 `NpmState` 全 `val`、不可变，投影一生不变；而安装框里**每敲一个字**就是一次 `copy()`，进构造函数等于每次 `copy` 都付一遍这份钱。**二**：`@Immutable` 加在 `NpmState` / `NpmRowState` 上 —— 不加则编译器视其为不稳定类型，`NpmScreen` 与每个收 `state` 的子件（`ScopeCard`/`InstallRow`/`LogCard`/`OptionsBar`）**一律不可跳过**，外壳每重组一次这一整片跟着重来（凭据：全字段 `val`，列表只整体换新）。**三**：这一屏六种 item 形状（作用域卡/安装段/卡头段/空态段/依赖行/日志卡）的 `contentType` 从**全报 `null`**（共用一个复用池）改成各报各的，且作用域卡与日志卡**也分开**（同是 `PanelCard` 那层皮，内容形态不是一回事）。**四**：`InstalledRow` 三处文字限一行 + 省略号（照首页 `FileRow`）—— 包名可以很长，不给上限就换行，行高不齐、滚动时反复重排。**五**：三个圆角 `Shape` 提成文件级常量，不再每段 item 现造。**六**：`PillButton` 新增**尾参** `animated: Boolean = true`，依赖行传 `false` —— 这是本批唯一直接冲「每一行进入视野时的成本」去的一条：`animateColorAsState` 按实例建 `Animatable`，每颗胶囊多两个 `Animatable` + 两条订阅协程 + 两次 `SideEffect`，跟着每一行滚进滚出现造现丢；而那颗「卸载」的选中态写死 `false`，动画一辈子不会真跑一帧。分段控件那一档缺省照旧带动画。**七、勘误（只追加纪律下的原地订正）**：第十一批那句「这一屏从头到尾只有**一个**可滚动容器」**说过头了** —— 撤掉的是**依赖列表**那一层，页底日志框 `LogCard` 里的 `LazyColumn` **还在**（`LOG_MAX_HEIGHT` = 200dp 封顶，第十批有意留的）；那片流水与 `design-decisions.md` §104 标题已原地划掉并注明日期。**八、本批解释不了「为什么首页不卡」**：首页那份文件清单只有几条，依赖清单可能几百条 —— 差的不只是每行成本，还有列表长度。设备上要两个数：**依赖条目量级**，以及**控制台页/日志管理页的列表是否也卡** —— 三个管理子页共用 `ManagementTransition` 外壳（子页内容外套两层全屏 `graphicsLayer`：位移/缩放那层 + 窗口圆角 `clip` 那层），首页在 `HorizontalPager` 里没有这两层；若子页都卡则那层才是嫌疑。**本批没动那两层**（逐字复刻 TG 的手势动画，凭猜改风险大于收益）。**九**：:ui 编译（main + test）+ detekt（0 smells；`PillButton` 按精确签名文本在 baseline 补 `animated` 一项）+ 31 个 :ui 测试类全绿。| [`log/2026-10-18.md`](log/2026-10-18.md) |
| 2026-10-18 | 1 | 一条目：**依赖页第十一批：安装依赖与依赖列表合成一组 + 三颗维护动作钉在页底 + 撤掉卡里那层 LazyColumn**（用户口径原文「安装依赖和依赖列表合并成一个列表分组」「三个按键放在低栏最下面的位置」「依赖列表滑动还是很卡」）。**一**：`InstallCard` 与 `DependencyListCard` 并成**一张卡** —— 新 `dependenciesGroup`（`LazyListScope` 扩展）+ `cardSlice`（一段一条 item，相邻两段同色贴紧、缝看不见）：安装行一段、卡头一段、之后每颗依赖各一段；「依赖列表默认收起」照旧（展开态从 `DependencyListCard` 提到 `NpmScreen` 的 `rememberSaveable`）。**二**：三颗维护动作从 `LazyColumn` 里搬出来，变成钉在页底的 `OptionsBar` —— 从前随内容滚，想按得先滑到它那儿，而这一页上半部分可以很长；让位（`TabBarBottomClearance`）随之从列表的 `contentPadding` 挪到这一栏身上（同一件事换地方做，不是多让一份），列表只留 `CARD_SPACING` 一道缝。**第十批第 28 条「日志在滚动内容最下面」一个字没改**（动作栏不占列表位置）。**三**：卡里那层 `LazyColumn`（高度上限 `DEP_LIST_MAX_HEIGHT` = 300dp）**撤掉**，依赖行改成页面列表自己的 item —— 此前这一屏有**两层竖向滚动**，拖动要在两层之间交接、内层滚到头外层才接管；现在只有一个可滚动容器，且只有可见行才组合、才绘制（此前整组是一条 item，比屏幕高时**整条**都要重画）。`DEP_LIST_MAX_HEIGHT` 常量与它的 KDoc 一并删除；`cardSlice` 的**中间段不挂 `clip` 图层**（四角是平的，没有圆角要裁内容），底色一律走 `background(color, shape)`（按形状画一块，不挂图层）。**四、修的是结构，帧率没测过**：本机不跑 Gradle、不连设备、禁 adb 截图/读屏 —— 不把「改成了标准结构」讲成「已经不卡了」，得在设备上看。**五**：:ui 编译通过（main + test）、:ui detekt（69 文件 / 0 smells；baseline 删 `DependencyListCard` 条目、`InstallCard` → `InstallRow`、`OptionsCard` → `OptionsBar` 按精确签名文本改，`dependenciesGroup`/`cardSlice`/`sliceShape`/`depsTitle` 是小写开头的普通函数故不需条目）、31 个 :ui 测试类全绿。| [`log/2026-10-18.md`](log/2026-10-18.md) |
| 2026-10-18 | 1 | 一条目：**依赖页第十批：日志挪到整页最下面 + 查「依赖列表滑动只有 60 帧」**（用户口径原文「把日志放到最下面吧。还有就是依赖列表滑动起来帧率怎么只有60」）。**一**：`LogCard` 从「依赖列表与三颗维护动作**之间**」移到**整页最后一段**，新顺序 = 作用域与尺寸 → 安装 → 依赖列表 → 三个维护动作（`OptionsCard`）→ **日志**；第六批第 22 条「选项放在最下面」在本条被改写（动作后面还有日志），两版口径都原地留着，本条记第十批第 28 条。**二、帧率：本仓没有任何一处设置帧率** —— 全仓 grep `preferredRefreshRate` / `setFrameRate` / `preferredDisplayModeId` / `Choreographer` / `withFrameNanos` / `refreshRate` **一条都没有**，两份 AndroidManifest 里也没有 `hardwareAccelerated` 之类条目；Compose 的帧时钟跟显示器 vsync 走，我们既没锁 60 也没有限帧开关 —— 所以「只有 60」**不是本仓某一行代码直接写出来的**（更可能是面板/系统档位或平台按应用出帧速度投票后回落），**这一条本机无法证实或证伪**（不跑 Gradle、不连设备、禁 adb 截图/读屏）。**三、能落地的那半**：`Canvas` 的绘制 lambda **每帧重跑**，而 `ui/src/main/kotlin/com/autoscript/ui/components/Glyphs.kt` 里原来的 `path(...)` 是 `Path().apply { … }` —— **每画一笔新建一个 Skia 原生 `Path`**，于是每一个可见字形、每一帧都在造 1~4 个原生对象再丢掉（底栏 5 个 + 顶栏 + 依赖列表每一行那两个 ⟳ 与胶囊），这是这条链路上唯一**可证**的每帧开销。改法：`Glyph` 里加 `remember` 在本节点上的 `GlyphScratch` 小池子（绘制开头 `begin()`，每个 `path()` 取池子里下一条 `reset()` 后用，`Stroke` 按 `(u, weight)` 缓存），**80 处 `path(...)` 调用点一处没改、字形几何逐条等价**；复用安全的三个前提写进 `Glyph` 的 KDoc（每个 `path(...)` 都立刻喂给 `drawPath`、没有 `val p = path(...)` 把两条 Path 同时留活；池子按节点隔离；绘制 lambda 在 UI 线程录制期跑）。**这不是「60 帧的答案」**，是这条链路上唯一说得清的一处浪费，能不能顶上去只能在设备上看。**四**：:ui 编译、:ui detekt（69 文件 / 0 smells，`GlyphScratch` 是 class 不是 `@Composable`、不需新增 baseline 条目）、31 个 :ui 测试类全绿。| [`log/2026-10-18.md`](log/2026-10-18.md) |
| 2026-10-18 | 1 | 一条目：**依赖页第九批：触感去掉**（用户口径原文「震动？我不需要震动，去掉吧」）。第八批按「实现触感反馈」补的那条 `LONG_PRESS` 触感整个去掉 —— `OPTION_TILE_HAPTIC_DELAY_MS`、那段 `PressInteraction` 订阅、三个 import（`PressInteraction` / `HapticFeedbackType` / `LocalHapticFeedback`）一并删。**为什么不留着**：那条是照 TG `ProfileActionsView.onTouchEvent` 的 `ACTION_UP` 分支搬的（按住 > 250ms 且手指仍在格内 → `HapticFeedbackConstants.LONG_PRESS`），但 TG 那是「长按有别的语义」的资料页动作行；本页这三颗按下去就出结果，**没有长按这层语义**，震动只是多余的打扰 —— **源码有这条不等于本应用要这条，口径以用户为准**。留下的照旧：缩放回弹（`ButtonBounce` 0.96 / 60ms / 350ms 过冲）与「没有进行中文字」。:ui 编译、:ui detekt（69 文件 / 0 smells）、31 个 :ui 测试类全绿。| [`log/2026-10-18.md`](log/2026-10-18.md) |
| 2026-10-18 | 1 | 一条目：**依赖页第八批：撤掉 `OptionTile` 的「进行中…」文字 + 补上 TG 的长按触感**（用户口径原文「撤掉，实现触感反馈」）。**一**：「进行中…」是第七批之外自己加的，按口径撤掉 —— `OptionTile` 的 `running` 参数、三个调用点的 `busy == NpmMaintenanceAction.PRUNE/DEDUPE` 与 `state.reclaimingCache`、`OptionsCard` 里的 `val busy` 一并删除；**状态没消失**：`canInstall` / `canReclaimCache` 在安装会话期间本来就是 false（安装、维护、回收三者互斥），三颗一起变灰就是状态本身。这也与 TG 一致 —— `SET_PHOTO` / `EDIT_INFO` / `SETTINGS` 三颗的 `supportsLoading` 是 **false**，那套横向扫光的 `LoadingDrawable` 只挂在 gift / call / video / join / report / leave / voice / stream / stop 上（`ProfileActionsView` 的 `switch (key)` 逐个赋的值）。**二**：触感照 `onTouchEvent` 的 `ACTION_UP` 分支 —— **松手那一刻**按「按了多久」决定震不震：`System.currentTimeMillis() - downTime > 250` 且手指仍在格内 → `HapticFeedbackConstants.LONG_PRESS`；实现走与缩放回弹共用的 `MutableInteractionSource`（`Press` 记时刻、`Release` 比 250ms、`Cancel` 不震），门槛取源码原值 `OPTION_TILE_HAPTIC_DELAY_MS = 250L`。**三**：:ui 编译、:ui detekt（69 文件 / 0 smells，baseline 的 `FunctionNaming` 条目按精确签名文本去掉 `running`）、31 个 :ui 测试类全绿。| [`log/2026-10-18.md`](log/2026-10-18.md) |
| 2026-10-18 | 1 | 一条目：**依赖页第七批：三个动作格的几何改成照 TG `ProfileActionsView` 源码取值（54dp 高 / 16dp 圆角 / 24dp 图标 / 11sp 粗体 / 7dp 横缝），按压从 M3 水波换成 `ButtonBounce` 缩放回弹（0.96，按下 60ms、松手 350ms 过冲）**（用户口径原文「你去看tg源码啊，/tmp/tg」「你对着图做干嘛」）。**一**：版式（三张等宽白卡、图标在上文字在下）照旧，**被推翻的是取值来源** —— 前一天第六批那版是拿截图当尺子量的（定标卡高 88dp，得 88dp/20dp/32dp/16sp），现在一律取源码原值：`ProfileActionsView(context, dp(74))` − `ypadding(12)` − `top(8)` = **54dp**；`getRoundRadius()` = **16dp**；`updateBounds()` 的 `drawableSize = dp(24)`；`new Text(cs, 11, AndroidUtilities.bold())` = **11sp 粗体**；`betweenPadding = xpadding / 2f`（`xpadding = dpf2(14)`）= **7dp**。参考图就是 `MODE_MY_PROFILE` 的三颗（`KEY_SET_PHOTO` / `KEY_EDIT_INFO` / `KEY_SETTINGS`，见 `addCameraAction()` / `addEditInfo()` / `addSettings()`） —— 查到源码后截图与源码自洽，差的是**定标定错了**。**二**：按压换掉 —— `onTouchEvent` → `hit.bounce.setPressed()`，`ButtonBounce` 按下 `pressedT` 走 60ms / `CubicBezierInterpolator.DEFAULT`（`cubic-bezier(0.25,0.1,0.25,1)`）到 1，松手 350ms / `OvershootInterpolator(5f)` 回 0，缩放量 `getScale(0.04f)` = `0.96 + 0.04 × (1 − pressedT)`；Compose 侧用 `animateFloatAsState` + `graphicsLayer`（按下 `tween(60)`、松手高回弹弹簧）。**`Modifier.pressableRipple` 整件删除** —— 它是前一天为这三格写的，现已无调用方（那条 M3 `ripple(color)` 只借 RGB、alpha 被换成 `pressedAlpha` 的反编译结论随代码消失，口径记在决策第 100 项）。**三**：两处刻意不照源码，为跟本页对齐 —— 左右外边距 `CARD_MARGIN` 12dp（源码 `xpadding` 14dp）、竖向间距 `CARD_SPACING` 6dp（源码 `top 8` + `ypadding 12`）；文字用 11sp 而非 `dp(11)`（默认字体缩放下逐像素相同，sp 跟随系统字体大小）。**四**：:ui 编译、:ui detekt（69 文件 / 0 smells）、31 个 :ui 测试类全绿。| [`log/2026-10-18.md`](log/2026-10-18.md) |
| 2026-10-17 | 1 | 一条目：**依赖页第六批：清理未声明依赖改名「清理依赖」/ 页底三个动作从「一张卡里的三行 TG 子菜单式选项 + 卡底一句说明」翻成**三张等宽白卡**（图标在上、文字在下）**（用户口径原文「清理未声明依赖改名啊，改成清理依赖」「样式是图片中这样，3个按键。重新做」「不要加多余的描述啊」）。**一**：第 15 条那次改口（「清理多余包」→「清理未声明依赖」）撤销，回到短名「清理依赖」—— 用户口径就是短名，`prune` 删的是 lockfile 里没声明的那些这层意思不进界面。**二**：第五批那版（`OptionsCard` 一张白卡 + 三行 48dp 选项行 + 卡底一句按作用域变措辞的说明）**整条推翻**，换成 `OptionTile`：三张等宽白卡横排、图标在上文字在下、都居中、整格是一个按钮；卡底那句说明**整段下线**（「不要加多余的描述」）。几何不是照抄 TG 子菜单项了，是从用户给的参考图上量的（定标卡高 88dp，其余按图上像素比例换算：圆角 20dp、图标 32dp、图标文字缝 8dp；**间距是唯一没照搬的**，图上 ≈ 15dp 取 12dp —— 与 `CARD_SPACING` 撑出来的卡间缝同宽，同页不该有两种缝）。**三**：底色取 `ThemeColors.surface` 而**不是**本页其他卡的 `ThemeColors.background` —— 浅色下两者同值（都是白，与参考图逐像素一致），深色下 `background` 与页底 `surfaceMuted` 同值（#161E27），那三格会整块消失、只剩图标文字飘在页上；按钮要看得见。`clip` 先于 `background`（同 `PanelCard`），圆角要裁住按下那圈水波。**四**：`pressableRipple`（TG 水波）与禁用判据两条同源（`canInstall` / `canReclaimCache`）不变；进行中的那一格文字换成「进行中…」；`GlyphKind.CLEAR` / `LAYERS` / `CACHE` 三个字形复用。**五**：`ui/detekt-baseline.xml` 的 `FunctionNaming` 条目按**精确签名文本**从 `OptionRow` 改成 `OptionTile`（新末尾参数 `modifier`）；:ui 编译、:ui detekt（69 文件 / 0 smells）、31 个 :ui 测试类全绿。| [`log/2026-10-17.md`](log/2026-10-17.md) |
| 2026-10-17 | 1 | 一条目：**依赖页第五批：全局为什么没有 node_modules（两档作用域口径不一致，修在宿主侧一行）/ 安装输入框收短右对齐（与首页搜索框合成一份 `CapsuleInputField`）/ 作用域缺省改全局并入尺寸卡（`ScopeCard`）/ 维护卡下线 → 页底三个 TG 子菜单式选项（点击走水波）**（用户口径原文「全局为什么没有显示node_modules」「安装依赖的输入框长度短一点（右对齐），样式抄一下首页的搜索框」「依赖作用域，默认为全局，放进和node_modules和npm缓存同一个列表分组」「去除维护列表，清理依赖，依赖去重，清理缓存弄成3个选项。这3个样式参考tg的，tg源码在/tmp/tg，点击动效也参考tg的。选项放在最下面」）。**一**：现象是全局作用域下尺寸卡里只剩「npm 缓存」那一行 —— 根因是**同一个物理事实在两档作用域上画法不同**：`globalSnapshot` 在 `files/scripts/node_modules` 不存在时给 `storage = null`（界面按「没量到就不画」整行藏掉），而项目那条永远非 null（`storage()` 对每个项目都构造读数，目录不在就是 `totalBytes = 0`，照画「node_modules 0 B」）。改在宿主侧一行：全局那条不再给 null，与项目同口径；界面一字未动（纪律本身没错，错的是它在两档上不是一个意思）。**二**：`CapsuleInputField` 从 `ProjectScreen` 搬进 `ui/.../components/Inputs.kt`（第三个使用点出现在**另一个屏**里，`private` 够不到；搬的是位置不是版式），末尾补 `enabled` / `onSubmit` 两个参数；安装行改定宽 168dp，「右对齐」由标签自己的 `weight(1f)` 算出来（写死的 `Spacer` 在窄屏会把整行顶出屏幕），提示语收成「如 axios@1.7.0」。**三**：`NpmState.of` 缺省回落**三档**：上次选的（还在）→ 快照里那条全局 → 第一条项目；中间那档挡的是「快照里没有全局那条时凭空选一个空串」（选了一个清单里不存在的条目）；`StorageCard` + `ProjectPickerCard` 并成 `ScopeCard`；依赖列表卡头与空态措辞跟着 `NpmState.scopeIsGlobal` 改口。**四**：维护卡（两颗 `PillButton`）整张下线，换成页底 `OptionsCard` 的三个选项行：`prune` / `dedupe`+提升 / **缓存回收（本日早些时候下线过，至此重新有入口，代价条款撤销）**；样式取 TG `ActionBarMenuSubItem`（48dp 行高 / 18dp 起手 / 24dp 图标 + 16sp），点击动效取 TG 那条 `getSelectorDrawable(false)` → `createSelectorDrawable(color, RIPPLE_MASK_ALL)` 的 `RippleDrawable`（新 `Modifier.pressableRipple` 走 M3 的 `ripple(bounded = true, color)`；`pressable` 的整块纯色覆盖仍是全仓缺省，两者各有用处）；选项放**整页最下面**（日志大框之后）；新字形 `GlyphKind.LAYERS`（叠层）与 `GlyphKind.CACHE`（圆柱）；禁用判据 `canInstall` / `canReclaimCache` 两条同源，进行中的那一颗行尾写「进行中…」。**五**：`InstallCoordinatorTest` 那条断言从「没建时 storage 是 null」翻成「**是 0 而不是不画**」+ 两档同款对照；`NpmStateTest` 改 1 补 2（三档回落 / `scopeIsGlobal` 认空串且没读到时不冒充）；**四处变异全部当场变红**（storage 回 null / 缺省回第一条 / 去掉中间那档 / `scopeIsGlobal` 对 null 返 true）；三模块 detekt 干净、baseline 外科式改 8 行；文档链接门与冻结面门通过。| [`log/2026-10-17.md`](log/2026-10-17.md) |
| 2026-10-17 | 1 | 一条目：**依赖页第四批：去掉两颗旗标（离线优先转写死缺省）/ 清理改名「清理未声明依赖」/ 去重并提升到全局 / 按 lock 重装下线 / 每行重装 ⟳ + 尺寸**（用户口径原文「去掉开发依赖，和离线优先。默认开启离线优先。」「清理多余包名字改成清理未声明依赖」「依赖去重执行后让它把依赖自动放进全局作用域中」「去除按lock重装。」「在依赖列表里，加一个重装的图标按钮，依赖列表显示一下各个依赖占用的空间大小。」；追问两条当场裁定：「去重后再装一份到全局」「每一行一个（和「卸载」并排）」）。**一**：`-D（开发依赖）` 与 `离线优先` 两颗旗标**整行下线**，理由不同要分开记 —— 「离线优先」是把 `--prefer-offline` 变成**写死的缺省**（不是删能力；也不留一句"已开启"去填那块空位），`-D` 是**语义**下线（这一屏回答"跑起来要什么"）；`NpmState.installDev`/`installOffline` 两字段一并摘掉。**代价**：装 devDependencies 只剩控制台。**二**：「清理多余包」→「清理未声明依赖」（只改文案，`prune` 判据一字未动）。**三**：`:domain` 新读口 `PackageManagerFacade.promoteToGlobal`（取项目 `package.json` **声明的**依赖 `dependencies`+`devDependencies`，**不是 lock 闭包**；带声明的范围而非锁定版本；目标固定 `NpmTargetScope.GLOBAL`；两种"没有"两句话 = 无清单 `ERR_FILE_NOT_FOUND` / 清单空 `ERR_NOT_FOUND`），取数走 `NpmScriptResolver.declaredSpecs`（同名只算一次 + 按名排序，否则 argv 顺序不定没法对拍）、argv 拼装收在新文件 `PromotePlan.kt`；**走的是与控制台逐字相同的那条重操作路**（同一 `packageSpecsIn` → 同一 `crossCheckRegistry` → 同一 `enqueueHeavy`），ECHO 落**全局**那份控制台。`HostSummary` 同步加口，`:domain` **门面冻结清单 +1 —— 被 `PackageManagerFacadeContractTest` 当场抓到**（补登记后 8 例全绿）。**四**：「依赖去重」不走 `<枚举> -> runNpmMaintenanceOp` 通用路，自己一条 `runDedupeAndPromoteOp`（两件事、两个作用域）；**两段成败分开说**：前段失败 → 整颗报失败且**不做后段**（树还乱着就往外提升 = 把半成品抄成全局基准），前段成后段败 → **两个都给**（只报失败用户会再点一次、去重被重跑），作用域已是全局 → **不提升并明说跳过**。**五**：「按 lock 重装」整颗下线（**代价**：`NpmMaintenanceAction.CI` 在本页没有入口了，宿主验签路径与单测都还在、枚举三取值一个没少），维护卡三颗减到两颗。**六**：每行一颗**画出来的** ⟳（新字形 `GlyphKind.REFRESH`：开口圆环 40°→320° + 弧端箭头，开口与箭头两样都要；触控 36dp 的算术 = 36+4+4 = 行最小高 44）+ 每行尺寸（`PkgNode.sizeBytes` 此前**恒为 0** —— `list()` 直读 lockfile 而 lock 里没有字节数；新读法走一遍 `node_modules` 按**顶层包**分摊：`@scope` 取前两段 / `.bin` 与 `.package-lock.json` 那类记账文件不算在任何包头上 / 嵌套的算最外层那个包；量不到**不画**那一格，不写 "0 B"）；重装走与「卸载」同一条命令通道（`npm install <name>@<version> --no-save --prefer-offline`：锁版本 / `--no-save` 防 npm 把传递依赖提升成直接依赖写进清单 / 不换作用域；**已知边界**：npm 会把重装的包 hoist 到顶层，界面不假装能阻止）。**顺手**：把"量目录"整块挪出 `InstallCoordinator`（新文件 `NodeModulesSizing.kt`：总量 + 逐包两个口、各一张 TTL 缓存，**缓存键是目录路径而不是 projectId** —— 挡掉两个指向不同 `filesDir` 的协调器互相顶掉读数）—— 该文件类是 796 行、贴着 detekt `LargeClass`（800），本批新代码让它到 **853**，**先挪到 794、不抬阈值**。**验**（本机禁 gradle）：`:domain`/`:app-service:npm`/`:ui` 三模块 main+test 全量编译零 error；**真跑** `:domain` **28 类 213 例** + npm `InstallCoordinatorTest` **113 例全过**（+6）+ `:ui` **30 个测试类 342 例全绿**（补 AAR 与 `collection-jvm` 后 `MoreDotsTest`/`ParticlesTest`/`LogWindowControllerTest`/`ScriptEditorIoTest`/`Management*` 全部真跑过；`NpmInstallOpsTest` 26 / `NpmMaintenanceOpsTest` 20 / `NpmStateTest` 14）；四类环境红逐字未变（4/2/6/6）；detekt 三模块零 findings，`:ui` baseline **外科式 7 行**、npm baseline **零改动**；**对拍 9 条逐条真红**（第 ⑦ 条"跳过 `.bin`/散文件"**第一次跑是绿的** —— 原断言只看得见 lock 里有名字的包，遂补一条直接断 `packageSizesOf` 原样结果的断言并重跑即红）；文档链接门 843 条全在。**没验**：真机观感（⟳ 在 18dp 上认不认得出、一行五件挤不挤）与"真往全局装一次"（归设备自测）；`:app` 的 `AppShellApplication.promoteNpmToGlobal` override 本机编不了（归 CI `assembleDebug`/`lintDebug`）。 | [`log/2026-10-17.md`](log/2026-10-17.md) |
| 2026-10-17 | 1 | 一条目：**依赖页第三批：依赖作用域（下拉加「全局」）+ 全局依赖落地 / npm 缓存行去字去按钮 / 安装行改「安装依赖 [输入框] 执行」**（用户口径原文「把顶栏那个项目切换按钮换成一个列表…」「**项目选择改成依赖作用域，然后下拉框加个全局**」「**去除 npm 缓存下面的字，去掉右边的清理按钮。**」「**安装这个小字标题去掉，改成输入框左侧，叫安装依赖，右边的安装按钮改成蓝色执行按钮**」）。**一、全局依赖落地**（第二批那条"未做"本次做掉，`docs/backlog.md` 同批追记）：落点 `files/scripts/node_modules`、编码 = 根层哨兵 `ProjectId.ROOT`（空串），新增 `:domain` 的 `NpmTargetScope`（编码与说法的唯一一份，`label("")` = 「全局」）。**为什么必须是这一层**：用户要的「项目依赖优先」是 Node 自己的行为 —— `require` 逐级向上找 `node_modules`，项目目录 `files/scripts/<id>/` 正压在 `files/scripts/` 底下，全局那份落在这儿，项目自己的**天然更近、天然优先，一行代码都不写**。换来的是全局与项目走**同一套寻址**（`projectRoot(ROOT)` 天然是 `files/scripts/`），staging / journal / lockfile / `list` / `offlineGap` / 签名 / 门禁**一行不改**。多出来的两处都是真问题：① 项目枚举要排掉 `node_modules` 与 `node_modules.part-*`（那两个名字**过得了项目号判据**，不排掉面板上会凭空多出一个叫 `node_modules` 的"项目"；判据落 `NpmProjectLayout.projectIds`/`isReservedDirName`，三个枚举点共用）；② 回收缓存的保留集要算上全局那份 lock（它是项目根下的一个**文件**，按目录扫的循环看不见它，漏掉则"只被全局依赖用着"的包被当垃圾删掉、全局离线重装当场失效）。门禁放宽一处：`NpmConsoleKeys.rejectProjectId` **放行空串**（只放"非空"那一条，**防逃逸一字未动**，`resolve("")` 恒等自身；话术里「不能为空」半句同批删掉）；项目**创建**侧照旧拒空（`ProjectId.createOrNull("")` 回 null），哨兵进不了项目列表。`snapshot()` 里全局那条**排最后**（面板缺省选第一条，插最前会把缺省作用域从"第一个项目"悄悄换成"全局"）、**永远在**（没建时 `storage = null`，界面那行尺寸不画）。**代价如实记**：`files/scripts/` 本层多出 `package.json` / `package-lock.json`，会出现在**文件列表的根层**里（`node_modules/` 本身不会，`ScriptFilesRead` 早就整段滤掉）。**二**：「去除 npm 缓存下面的字，去掉右边的清理按钮」——那两行现在逐字同形（名字 + 数字）；**代价**：「回收缓存」在依赖页**没有入口了**（宿主那条路与 `reclaimNpmCacheOp` 都还在、也还测着，只是本屏不再调用，`MainActivity` 接线同批摘掉）。**三**：安装行改「安装依赖 [输入框] 执行」，执行按钮走 `PillButton` 选中态（动作蓝 `featuredButton`），`selected` 跟着"能不能按"走而**不是恒定 true**（恒定 true 时禁用是**蓝底灰字**，看着像坏了）。**四**：「维护」卡标题跟作用域改（原文「当前项目的」在选「全局」时是句错话）。**验**：三模块 main+test 全量编译零 error；`:domain` 真跑 31 例（`NpmConsoleKeysTest` 23 +2）；npm 31 类真跑（`InstallCoordinatorTest` **107 全过**，+6；四个已知环境红逐字未变 2/4/6/6）；`:ui` 状态层 7 类 **115 例全绿**（`NpmStateTest` 13 +1 作用域标签三态 / `NpmInstallOpsTest` 23 +1「选全局不被当成没选项目」）；detekt 三模块零 findings（`:ui` baseline 外科式 3 行改签名文本）。**对拍三次、逐次真红**：① 去保留名过滤 + 全局插最前 → 2 红；② 去回收里全局那份 lock → 2 红；③ 撤掉空串放行 → 2 红。**没验**：真机观感（下拉里「全局」的位置、执行按钮蓝得对不对、窄屏挤不挤）与**真往全局装一次**（`files/scripts/` 当 npm workDir 能不能真跑通）—— 归设备自测。 | [`log/2026-10-17.md`](log/2026-10-17.md) |
| 2026-10-17 | 1 | 一条目：**依赖页第二批现场改动（项目选择下拉 / 尺寸与缓存成列表 / 取消 512MB 配额 / 文案与横线）+ 全局依赖未做**（用户口径原文「把顶栏那个项目切换按钮换成一个列表，列表名字叫项目选择，然后点了用下拉框选取」「node_modules 取消 512mb 限制…」「npm 缓存…也弄成列表，但是右侧弄成清理的按钮」「安装（装进当前项目的 node_modules）描述改成安装就行」「日志没有日志时就直接显示暂无日志。不要弄那么长」「还有依赖列表取消横线分隔」；追问配额范围时改口「取消上限设置吧。再加一个全局依赖的功能。项目依赖和全局依赖如果有重复的，则优先读取项目依赖，」）。**一**：一排 `PillButton` 换成 `ProjectPickerCard`（一行「项目选择」+ 右侧当前项目名 + 三角，点了开 `ContextMenu`；当前项标「（当前）」；只有一个项目时不开下拉、行照画；零项目整卡不画）。**二**：新增卡内行 `PanelRow`（左标签 + 可选副行 + 行尾槽位 + 整行可按），`QuotaCard`→`StorageCard` 两行（node_modules / npm 缓存，各自独立判空）；「回收缓存」从「维护」卡搬到缓存那一行、文案改「清理」，判据提成 `NpmState.canReclaimCache`。**三**：**取消 node_modules 配额，跨三模块** —— `InstallConfig` 摘 `projectQuotaBytes`/`quotaWarnRatio`，`InstallCoordinator` 的「100% 拦」与「80% 黄」下线，`NpmProjectSnapshot` 摘两字段与 `overQuota`/`quotaWarned`/`quotaFraction` 三派生量，`:ui` 的 `quotaLabel`→`sizeLabel`（"1 MB"）、`cacheLabel`→`cacheSizeLabel`；**`InstallEvent.Kind.DISK_QUOTA` 留用、判据换锚**到「磁盘可用进不了 预检下限 `minFreeBytes` 的 1.25 倍余量」（`FREE_WARN_HEADROOM`）—— 配额摘了之后磁盘预检是唯一还会拒安装的资源闸。**代价如实记**：node_modules 从此没有上限（拦安装的只剩"可用空间 < 500MB"）。**四**：安装卡标题→「安装」、日志空态→「暂无日志」、依赖列表取消行间横线。**未做**：用户同批要的「全局依赖」—— 契约级改动，落 [`backlog.md`](backlog.md)（附一条已成立的结论：**「优先读取项目依赖」不用写代码**，Node 逐级向上找 `node_modules`，项目目录 `files/scripts/<projectId>/` 天然更近）。**验**：`:domain`/`:app-service:npm`/`:ui` 三模块 main+test 全量编译零 error；npm 31 类真跑（`InstallCoordinatorTest` 101 全过，`NpmSpawnGate*`/`T1Bridge*` 四类环境红逐字未变 4/2/6/6）；`:ui` 状态层 7 类 112 例全绿（`NpmStateTest` +1「清理按钮三者互斥」+ 两条改写）；detekt 三模块（main+test）零 findings，`:ui` baseline 外科式 4 行；**对拍**：`FREE_WARN_HEADROOM` 调 1.0 → 黄警那条红。**没验**：真机观感（下拉弹出位置、窄屏上缓存那行挤不挤、去横线后行距）—— 归设备自测。 | [`log/2026-10-17.md`](log/2026-10-17.md) |
| 2026-10-17 | 1 | 一条目：**依赖页改底色（任务中心那块灰）+ 依赖列表改白卡折叠头（默认收起）**（用户口径原文「依赖管理页面的背景颜色改成任务中心背景一样的颜色，依赖列表是弄成一个白色胶囊列表，参考任务中心的列表，点下可以伸展开，默认是收起来的」）。**一**：页底 `background` → `surfaceMuted`（浅色 #F1F1F3 / 深色 #161E27），顶栏**同时**传同一个色 —— `ActionBar` 缺省就是"与屏底同色"，只换页底会留"白栏压灰页"那道缝（任务中心批 41 踩过）；深色下两值同色 ⇒ **只动浅色档**。**二**：页上的块整批翻白，这是**改动一的连带**而不是顺手扩大 —— 原灰块用 `fieldBackground`（浅色 #F4F4F4），压新页底 #F1F1F3 只差 3/255，安装输入框 / 未点亮的进度格 / 两张灰胶囊会整批消失；`PanelCard` 因此从"灰底 18dp 胶囊"改成"白底 16dp 卡"（任务中心 `Card` 几何：左右 12dp、圆角 16dp、无描边无阴影），安装/维护/配额三段一并收进同一张皮 —— "白页上的灰块"与"灰页上的白卡"恰好对调。卡底取 `background` 而非 `surface`：`fieldBackground` 的配方是"白 7% 叠 `background`"，取 `surface`（深色 #232326）会让卡里的输入框 #262E36 跟卡糊在一起 = 同一个错换到深色档。**代价**：深色下卡与页同色（整页一色），与任务中心一致，本批不动。**三**：依赖列表卡头换成 `CollapseHeader` —— **从 `TaskCenterScreen` 的私有 `GroupHeader` 提出来的共享件**（46dp 条 / 文字缩进 21dp / 14sp Medium / 右端 CHEVRON 340ms `EASE_OUT_QUINT` 翻 180° 逐字保留），提出时**去掉了自身画的底**（头该露出所在卡片的底，组件不替调用方决定；两处调用点像素不变）；展开态 `rememberSaveable` 留在调用方，初值**收起**；条数只在"读到了"时写进卡头（没读到的 0 不是"没有依赖"是"还不知道"），展开后的空态分两句说（"还没读到…" vs "读到了，这个项目还没有依赖"）。**本批没有新增 JVM 可测面**（折叠态与配色都是 Compose 局部的，本仓展开态一律 `rememberSaveable`，任务中心两个组头也无单测）—— 如实记，不硬造缝。**本机验**：把 `:ui` 的 **Compose 编译面补齐**（`~/.gradle/caches` 的 58 份 compose AAR classes.jar + `kotlin-compose-compiler-plugin-embeddable` 2.0.21 + `android-35/android.jar` 拼一条 kotlinc），main+test **两个源集都编过**（610 class）；真跑 `:ui` 状态层 5 类 **89 例全绿**（NpmState 11 / NpmMaintenanceOps 15 / NpmInstallOps 22 / TaskCenterState 25 / ProjectState 16）；detekt `:ui` main+test 零 findings，baseline **外科式 3 行**（新增 `CollapseHeader.kt` 两条、删掉已搬走的 `GroupHeader` 旧条目）。**没验**：真机观感（灰页白卡的实际对比、折叠动画跟不跟手、深色"整页一色"能否接受）—— 归设备自测。 | [`log/2026-10-17.md`](log/2026-10-17.md) |
| 2026-10-17 | 1 | 一条目：**依赖页三处现场改动**（用户口径原文「删除顶上的报错。不需要它。」「弄个依赖列表，把已装依赖收进去。」「最下面弄个显示日志的大框。灰色背景，胶囊框。」；裁定：日志框**两者都显示**、列表**卡片 + 内滚**）。**一**：读失败 / `opError` / `opNotice` 整段搬进底部日志框（页面上半部分不再有红字）—— 上一条刚写的 `progressFailureShown` 判据随之删除，§91 决策三在 `design-decisions.md` **原地划掉并注明日期**（旧口径留痕），新口径 §92。**二**：已装依赖收成 `DependencyListCard`（灰底 18dp 圆角 + `heightIn(max=300.dp)` + 卡内自滚），行键仍是 `name@version` 且**唯一性由宿主读口的归一保证**（守卫是 npm 那条回归用例）；顺带删死代码 `SectionTitle`。**三**：新增 `LogCard` = 读失败 → 报错 → 回执 → npm 输出行（着色**复用控制台那份** `ConsoleCmdLineState.tone`，不给同一份日志第二套判读），取数走**既有**读口 `HostSummary.consoleOutput`（不新开读口）并与快照**并进同一趟现取**（`loadNpmSnapshot`）；**日志读失败不算操作失败**（两条账分开）；留最近 200 行（`LOG_KEEP`）、裁过就在框里说；贴底用控制台那一套 `LazyListState`（`ScrollState.maxValue` 在「内容刚变、还没测量」那一帧是 0）。回归闸 `NpmStateTest` +4 / `NpmMaintenanceOpsTest` +2，**拆三处对拍 → 3 条红**；本机真跑 `:ui` 状态层 3 类 48 例全绿 + detekt 零 findings（baseline 外科式 4 行）；**没验** Compose 那一屏（归 CI `assembleDebug` + `lintDebug`）与真机观感（归设备自测）。 | [`log/2026-10-17.md`](log/2026-10-17.md) |
| 2026-10-17 | 1 | 一条目：**依赖装多了「查看时闪退」（`list` 归一 + 读口落 IO）+ 同一句报错说两遍**（用户口径原文「取消上面位置的报错，留下面就行」「依赖装多了，查看的时候容易闪退」）。**崩**：`list()` 把 lockfile v3 的 `packages` **逐键**读成平铺闭包，而 lock 的键是**安装位置**（peer 冲突时同一个 包@版本 会在不同父级各落一份），依赖页用 `items(key = "<name>@<version>")` 铺它 —— **键重复 Compose 当场抛**（`LayoutNodeSubcompositionsState`：`Key "c@1.0.0" was already used`），装得越多越易撞上；药 = 按 `(name, version)` 归一（`offlineGap` 不归一：缺口判据是 integrity）。**卡**：`snapshot()` 一趟 = 全项目遍历 + 每项目**两份** lock 解析 + 每棵 node_modules 全树算尺寸 + 缓存遍历，而进页面/刷新/安装期间每 400ms 的续拉都是主线程那条协程直接 await（suspend 不搬线程）；药 = `list`/`offlineGap`/`storage`/`snapshot`/`reclaimCache` 整趟落 `Dispatchers.IO`，`nodeModulesBytes` 改挂起并在自己那层落 IO（两个调用方都在主线程链上），控制台轻操作取数同理 —— 都是本文件既有写法（`crossCheckRegistry`/`cacheStorage`），本批补漏；**一处不好看但必要**：`storage()` 里 `Stream.forEach` 是非内联 `Consumer`，里面调不了挂起函数 （先收目录名、再按名字逐个走）。**重复报错**：顶上 `opError` 与阶段条收尾那行是同一句话；药 = `NpmState.progressFailureShown`（收尾 + 失败 + **`detail` 非空**）才让位 —— 「宁可重复，不可失语」；校验拒绝与维护/回收失败没阶段条，照画。回归闸 `InstallCoordinatorTest` +3 / `NpmStateTest` +1，**两条对拍**（换回 HEAD 版 → 3 条全红；判据钉成 false → 红）；本机真跑 npm 31 类（`InstallCoordinatorTest` 101 全过）+ `NpmStateTest` 8 + detekt 两模块零 findings（baseline 零改动）；**没验** Compose 那一屏与真机（归设备自测）。 | [`log/2026-10-17.md`](log/2026-10-17.md) |
| 2026-10-17 | 1 | 一条目：**CI 红修**：`PackageManagerFacadeContractTest > 门面方法面冻结` —— 上一条把 `PackageManagerFacade.history()` 摘了，而那份冻结方法清单里 `"history"` 那一行**没跟着删**（`:domain:test` 217 例 1 红）。**这正是那条门存在的意义**：读口下线是**契约面变更**，必须被一句话记下来。药 = 删那一行（与两个 DTO 一起，本批 `:domain` 少的就是「1 读口 + 2 DTO」）。**教训**：本机这次只跑 npm 模块的 31 类 + 两个新测试类，`:domain` **只编了 main** —— 那条用例住在 domain 的 **test** 源集里，从没被执行过；把「编译过了」当成「这一层验过了」＝把没跑的套件当成绿的套件（「本机绿 ≠ 门禁有效」的第三变体：前两次是**过滤过窄**与**本机旧版文档**）。补跑：`:domain` 全量 29 类（除 `ArchitectureTest` 缺 ArchUnit）**28 类全绿**；`ModuleGraphTest` 须在仓库根 cwd 跑（在 `/tmp` 下红是本机假象）。 同批推翻/订正：无。 | [`log/2026-10-17.md`](log/2026-10-17.md) |\n| 2026-10-17 | 1 | 一条目：**去掉审计改打系统日志 + 系统日志跨重启**（用户口径原文「去除审计这个东西，把这个日志输出到系统日志中」+「系统日志不要每次进 app 都清除」；追问裁定：去的是依赖管理页顶栏那颗「审计」进的子页，且背后那份只追加的落盘账 `files/.ns/install-history.jsonl` **一并去掉、改打系统日志**）。**药 = 形状之变：从「一本自己的文件」到「一条去向」** —— `:app-service:npm` 新增 `OpLogSink`（`fun interface`）+ `OpName`（自 `:domain` 的 `InstallHistoryOp` 搬来，删掉 `install_script`），写侧只交事实（op/projectId/成败/明细）、去向由装配层给（生产 = `:app` 的 `OpLogHostSink` → `HostLog`：logcat + 日志管理页「系统日志」段 runId=0）；`:domain` 相应少 1 读口 + 2 DTO，`:ui` 少一屏（`AuditScreen`/`AuditState`/`AuditOps`）与顶栏那颗按钮。**三点刻意**：失败也记（四条路都留痕）、op 名**不再翻中文**（那张对照表随页面一起下线；字面量是可检索词，逐字钉在 `OpLogSinkTest`）、**时间戳不进缝**（时刻由去向侧盖，与 logcat 同源）。**第二条口径**：系统日志此前只在内存（有界 2000 环），新增 `:app` 的 `HostLogStore`（`.ns/host-log.jsonl`，只追加无 retention）—— 接线是**一个收集器两条去向**按 `runId` 分道（0 → host-log，否则 → 既有 `ScriptLogStore`，两边守卫对偶）；回放走 `ConsoleCollector` 新增的 `restored`（**构造期预置、不回调 lineSink** —— 回调了每次启动把盘上那批再抄一遍；**seq 重新分配** —— 游标从 0 起），回读只读尾部 1 MiB。**代价如实记**：文件随宿主事件一直长（伤磁盘不伤启动）。**未动**：`npm audit`/`AuditReport`（那是包的安全审计，同名不同物）。**回归闸** `OpLogSinkTest` 2 + `HostLogStoreTest` 6 + `ConsoleCollectorTest` +2 + `NpmSnapshotTest` 反向断言一条，**已对拍**两条。**本机验**：npm 模块 31 类真跑（`InstallCoordinatorTest` 98 全过）、`HostLogStoreTest` 6 与 `ConsoleCollectorTest` 15 真跑全绿、`OpLogHostSink` 配真 `HostLog` 跑通（logcat `i/w npm` + 系统日志 4 条 runId=0）、detekt 六模块零 findings（baseline 外科式改 6 行）；**没验** Compose 面与真机重启后仍在（归设备自测）。同批推翻/订正：§10.5-2「审计日志落 App 且可导出」被取代（决策第 90 项）。 | [`log/2026-10-17.md`](log/2026-10-17.md) |
| 2026-10-17 | 1 | 一条目：**项目缺 `package.json` 时自动补一份最小骨架**（用户报「项目 demo 缺 package.json（…），无法执行 npm install」+ 口径原文「应该让它缺的时候自动创建呀」；决策第 89 项）。**为什么会走到这一步**：`package.json` 此前只有**模板部署**才长得出，而 2026-10-16 用户裁定「根层可写」后**首页根层建文件夹 = 建项目**造出来的是**空目录** —— 两条各自都对的口径合起来 = "自己建的项目装不了依赖"，拦路点就是 `HostNodeExecutor.prepareWorkDir` 那句 `require`。**药**：缺清单照新的 `ProjectManifest.minimal` 造一份、**只落 workDir**、跑完由既有 `harvest` 写回项目根（与 npm 自己重写 manifest 同一条落盘路径），**失败的操作不在项目里留文件**；返回值 `Unit → Boolean` 让调用方在**终态摘要**（→ `InstallEvent.Finished.detail` → 控制台 RESULT 行）如实说一句 —— **刻意不走 `OutputSink`**（那条缝一碰 `runHeavy` 的 `streamed` 就为真，npm 的输出尾部会被当成"已报过流"而不再补）。**三条判据**：① **不写 `main`**（`ProjectIndex` 缺 `main` 会退到 `main.js`/`index.js` ⇒ 这份清单对入口判断零影响）；② **`name` 洗成 npm 认的形态**（项目号判据放宽后中文/大写/空格/`@` 都合法，而 npm 只认小写 URL-safe，不洗即 `EINVALIDPACKAGENAME`；保守到只剩 `[a-z0-9-]`、洗空回 `autoscript-project`；**代价如实记**：中文项目号拿到不含信息量的名字，但它在此处只有展示意义、数据仍在项目号上）；③ **`private: true`** 挡住误发布。**未动**：`InstallCoordinator` 的「无 lifecycle 脚本可跑」（`npm run`）照旧 —— 那条路要的是用户写的 scripts。**回归闸**：`ProjectManifestTest` 5 例 + `HostNodeExecutorSeedTest` 4 例，**已对拍**（退回旧 `require` 即红 3 例，报的正是用户那句）。验：模块 main+test 编译零 error、31 个测试类真跑（新增 9 例全过 / `InstallCoordinatorTest` 99 全过）、`NpmSpawnGate*`+`T1Bridge*` 红数逐字未变（4/2/6/6，环境）、detekt main+test 零 findings。同批推翻/订正：无（新口径）。 | [`log/2026-10-17.md`](log/2026-10-17.md) |
| 2026-10-17 | 1 | 一条目：**设备实测：装依赖报「getFileStore」**（用户报，原文「报错，getfilestore」；紧接上一条 NetworkOnMainThreadException 而来 —— 那条修的是交叉校验，**磁盘探针正是它后面那一步**）。病 = `NpmShellKit.defaultFreeSpaceProbe` 用的 `Files.getFileStore(p).usableSpace`：Android 的 `java.nio.file` 只实现了 NIO2 的一半，`FileSystemProvider.getFileStore` **正是没实现的那一格**，调它抛 `UnsupportedOperationException("getFileStore")`，而那句 message 被 `:ui` 的 `opError = t.message ?: t.javaClass.simpleName` **原样显示**给用户（屏幕上就只有「getFileStore」四个字，没有半个字指向"磁盘"）。原有 `catch (java.io.IOException)` 拦不住 —— UOE 是 RuntimeException。后果不是"读不到磁盘"而是**装依赖整条路走不通**：探针在 `InstallCoordinator.enqueueHeavy` 的每次重操作入口都要跑（§10.2 磁盘预检）。**本机与 CI 都复现不了**（宿主 getFileStore 好得很，本次实测：存在的目录读得出数、不存在的抛 NoSuchFileException）⇒ 又一类只能设备实测发现的错。药 = 读法收到 **`FreeSpace` 一处**（判据的唯一一份），改用 `java.io.File.usableSpace`（Android 侧 statvfs(2) 实现，`java.io` 不属于"NIO2 那一半"；宿主 JVM 侧同样是真数）。**先判存在**：不存在的路径 `usableSpace` **回 0**，而"项目目录在第一次 install 前还不存在"是常态 ⇒ 拿 0 比预检下限就是一句**假的** ERR_DISK_FULL（已实测：本机 `new File(不存在).getUsableSpace() == 0`）。两个探针同源到这一个口（`NpmShellKit` 带 filesDir 兜底的那个＋ `InstallCoordinator` 的裸缺省，后者生产永不用）。**回归闸** = `FreeSpaceTest` 4 例，**已对拍**（去掉 `usableBytesFor` 的存在性判断 →「目录不存在时退到 fallback」立刻红）；**如实记一条测不到的**：宿主 `getFileStore` 是好的 ⇒"Android 那格没实现"在 JVM 上钉不住，这条修的是设备事实，单测只守得住新读法。验：模块 main+test 全量编译零 error、26 个测试类真跑（FreeSpaceTest 4 全过 / `InstallCoordinatorTest` 99 全过）、`NpmSpawnGate*`/`T1Bridge*` 四类红数逐字同改动前（4/2/6/6，环境）、detekt main+test 零 findings、全仓 `getFileStore` 代码里零命中。同批推翻/订正：无。 | [`log/2026-10-17.md`](log/2026-10-17.md) |
| 2026-10-17 | 1 | 一条目：**设备实测：装依赖抛 `NetworkOnMainThreadException`**（用户报，原文「安装依赖报错，networkonmainthreadexception」）。病 = `InstallCoordinator.crossCheckRegistry`（§10.5-1 多镜像交叉校验）调 `NpmRegistryVerifier.verify`，后者走 `HttpRegistrySource.packument` —— **同步阻塞**的 `HttpURLConnection`（每包两趟 packument，`connectTimeout` 5s + `readTimeout` 8s）；整条链确实是 suspend 的，**但 suspend 只给「可挂起」的许可、不搬线程** —— 界面那条协程是 `rememberCoroutineScope()`（= Main），于是取 packument 落在主线程上，Android 当场抛，**一个包都还没发出去**（交叉校验摆在 `enqueueHeavy` 之前，是"先校验再排队"的刻意次序）。**本机与 CI 都抓不到**：JVM 没有那道守门人（它是 Android runtime 的），单测里原本也没有任何"谁在哪个线程上跑"的断言 ⇒ 这一类只能设备实测发现。药 = `verify` 包进 `withContext(Dispatchers.IO)`（**本文件既有写法**，`cacheStorage`/`HostNodeExecutor`/`NpmScriptExecutor` 早就这么做，这条只是漏了；落点选在 `:app-service:npm` 而不是在 `:ui` 整体包一层 —— 做阻塞调用的那一层自己声明它是 IO，且该模块是纯 JVM、`Dispatchers.IO` 是协程概念不是 Android 依赖）。**全仓 main 源码扫过**：`HttpRegistrySource` 是唯一网络调用点（其余 `java.net` 命中全是 LocalSocket/SocketChannel 的本地抽象套接字与 `URLEncoder`）⇒ 一处即够。**回归闸** = 新用例在假校验缝里记 `Thread.currentThread()`、断言它 ≠ 调用方线程（界面与控制台都是拿自己那条协程直接 await 整条 install 链，故"跑在调用方线程上"等价于"落在主线程上"）—— **已对拍**：去掉那行 `withContext` 后该用例红（`expected: not same but was: <Thread[main,5,main]>`），加回来 99 例全绿。验：`:app-service:npm` main+test 全量编译零 error；JUnit launcher 真跑该模块 25 个测试类（除 `ArchitectureTest`，它要 build-logic 的 ArchGate）—— `InstallCoordinatorTest` 99 例全过；`NpmSpawnGateTest`/`NpmSpawnGateMatrixTest`/`T1BridgeE2ETest`/`T1BridgeNodeTest` 本机红（缺装配期生成的 shim/asset 树与 noden 二进制），**拿 `HEAD` 版同一份源码对拍红数逐字相同**（4/2/6/6）⇒ 与本改动无关；detekt main+test 零 findings（baseline 未动）。另说清（**不是 bug 是设计**）：交叉校验排在 `enqueueHeavy` 之前且要联网取两份 packument ⇒ 按了安装到 npm 真开始之前最长等 (5s+8s)×2×包数，用户看到的是"按了没反应"。同批推翻/订正：无。 | [`log/2026-10-17.md`](log/2026-10-17.md) |
| 2026-10-17 | 1 | 一条目：**CI 红修**：`:ui:compileDebugKotlin` —— 呈现层接缝 `onAbsolutePath` 的返回类型写成了 `-> Unit`（`onDelete` 是 Unit，抄顺手了），而调用点 `copy.copy(…, onAbsolutePath(…))` 要的是被复制的那条路径 ⇒ `Argument type mismatch: actual type is 'kotlin.Unit', but 'kotlin.String' was expected`；`MainActivity` 那边的 lambda 返回 String 而 Kotlin 允许往 Unit 上强转，所以只有真正取值那一处炸（JVM 单测与 Android 构建两个 job 同一条红，engine-native 随之失败；**这次没有测试红** —— 本机跑过的 92 例全绿，红的是编译）。药：类型改回 `-> String` + 那两条按签名**原文**匹配的 detekt baseline 重生成。**最值钱的一课**：本机没有 Android SDK/Compose，这两个文件只能「带已编好的 state 类空编一遍看有没有真错误」，而上轮我用的是**过窄的黑名单过滤**（`expecting|unresolved reference: <新符号>`）—— `Argument type mismatch` 这类**两边类型都解析得到**的错误一条都没进过滤表，**编译器报了、我没看**。改成白名单式过滤（只滤 cascading 的 unresolved/infer/ERROR CLASS）后立刻浮出，并与改动前的同一份文件对拍确认其余 3 条全是本机假象。同一条纪律此前在文档链接门踩过（「本机绿 ≠ 门禁有效」），这次是它在家门口的复现。同批推翻/订正：无。 | [`log/2026-10-17.md`](log/2026-10-17.md) |
| 2026-10-17 | 1 | 一条目：**文件列表菜单改三项（复制相对路径 / 复制绝对路径 / 删除）+ 多选批量删除**（用户口径；追问两条当场裁定：根层文件夹可删 = 可删整个项目「允许，不用加弹窗」、多选批量删「顺带做上」）。宿主补两个口（此前一个删除口都没有）：`HostSummary.deleteEntry`（**文件夹递归整棵**、不存在抛）+ `HostSummary.absolutePathOf`（**不查存在性**），两个都是**抽象成员**（全仓只有 `AppShellApplication` 与 `FakeHost` 两个实现者 ⇒ 没接线是编译期红）；落点仍全在 `ScriptFileOps` 共用的 `resolveInProject`（越界/归属/根层单段判据同源），`delete` 多两道闸（目标须存在；不许是 `files/scripts/` 本身 —— 那条闸挡的是「删掉整个项目库」），`absolutePath` 就是它的字符串形态、**不写第二套容错拼法**。菜单三项：「复制相对路径」（原「复制路径」改名，值不动）/「复制绝对路径」（宿主现取 + 失败原文）/「删除」（红字、**点了即删不弹确认框**）；「进入文件夹」**删掉**（点行本身就是进一层，与主手势重复）。选择模式顶栏加一格「删除」（在「选择全部」右侧），删完**退出选择模式**；批量目标由新纯函数 `deleteTargets` 算 ——**剪掉被别个选中项包含的后代** + **深度降序**（搜索态池子是整棵树，父子同选时先删父会让子项假报失败），前缀判据靠目录行 relPath 的**尾随 `/`**（否则 `lib` 会吃掉 `liba.js`）。**代价如实记**：无确认框 ⇒ 不可撤销、无回收站；根层文件夹可删 = 可删整个项目；不检查在途执行；编辑器开着被删文件时保存得既有的「文件不存在（不新建）」。验：`:domain` 全量编译零 error + **JUnit launcher 真跑 92 例全绿**（domain 44 / app 32 / ui 16，含新增 8）+ detekt 三模块零 findings（为压 `TooManyFunctions` 把 `requireName`/`requireProjectName` 合成一个；`:ui` baseline 外科式改 2 行 —— `ProjectScreen` 签名多两个参数）。同批推翻/订正：无。 | [`log/2026-10-17.md`](log/2026-10-17.md) |
| 2026-10-16 | 1 | 一条目：**CI 红修**：`:app-service:script-repo` 的 `ProjectStoreTest > 非法 id 一律拒绝`（JVM 单测 job 红，67 例红 1；detekt 那步 fail-fast 未跑，故 detekt 本轮在 CI 上还没被门禁验过；其余四 job 全绿）。该表是判据的**镜像**，而本轮两处判据都动了：`PROJECT_ID` 放宽成「排除分隔符与点」（`"has space"`/`" a"` 不再非法）+ 空串从「构造不出来」变成**根层哨兵**（表钉的 `rootFor` 恰好是**读口**，`rootFor(ROOT)` 就是库根本身，本来就该通过 —— 闸在 `create`/`delete` 两个写口）。同批 `:domain` 三张表已同步，**漏了这张**（它在 `:app-service:script-repo`，不在我改过的那三个模块的测试清单里）。药：样本表只留字符集判据仍拒的那几个 + **新增 `根层哨兵不建不删`**（`rootFor(ROOT) == 库根` 这条恒等式、两个写口都拒、被拒的 `delete` 一个文件都没删）。验：本机 JUnit launcher 真跑 `ProjectStoreTest` 5 例全绿（`FAILED=0 SUCCEEDED=5`）+ `:domain` 全量重编译零 error。**教训**：判据放宽是**全局**改动，下次按**判据符号**（`isValidProjectId`/`rejectProjectId`/`ProjectId(`）全仓扫，不按「我改过的目录」扫。同批推翻/订正：无。 | [`log/2026-10-16.md`](log/2026-10-16.md) |
| 2026-10-16 | 4 | 四条目：**用户裁定关 stdin 写端发 EOF（自动退）+ 悬浮窗顶栏「结束脚本」**；**CI 红修**：共用取件夹具 `E2EFixtures.extractBootstrap` 那串嵌套量词正则按字符递归，main.cpp 引导块涨到 1233 字符就把 JVM 栈打穿（`BridgeDistPackagingEntryTest` 三例红一例，栈里全是 `Pattern$Loop`；**冷调用炸、热调用过**已坐实 —— 同一线程第 1 次解释执行炸、第 2/3 次 JIT 后过 ⇒ 一整个测试 JVM 里只有第一个调用它的用例红；1132 字符时冷调用也抽得动 ⇒ 早就是边缘哑弹），药 = 换线性扫描（与旧正则输出逐字等价已对拍；另在宿主 node 上照原样复跑了那三条用例的语义）。**项目页新建落在当前进入的那一层 + 新建弹窗白底/去描述行/胶囊输入框**（用户口径四条）：落点从"项目根"改成"按 FAB 那一层" —— 合约加末位参数 `HostSummary.createEntry(..., dirRelPath = null)`，呈现层把 `currentFolder` 原样传下去、裁决全在 `ScriptFileOps.resolveName`（复用 `resolveInProject` 的越界/归属判据 + 「这一层得真在」，**不顺路建中间层**）；弹窗 `containerColor = sheetBackground`（M3 缺省 `surfaceContainerHigh` 在本仓 ColorScheme 里没映射、落回基线色）；删掉「将创建在项目「x」下」那行（落点改了之后它要么多余要么说错）与只为它存在的 `targetProject` 参数；输入框抽出 `CapsuleInputField` **与搜索框共用同一份版式**（灰底/20dp/40dp，放大镜与聚焦上报各自可选）。验：`:domain` 全量编译零 error、`ScriptFileOpsTest` 真跑 15 例全过（含新增 4 例）、detekt 六模块零 findings。上一版进程内 `unref` 补丁（`91929b1d`）**同日 revert（`1e402701`）** —— 用户评审的排序里它排最后，正路是 ① 引擎关 stdin 写端发真 EOF（readline 自然收 `end`/`close`、脚本自己结束；本机实测 408ms、code 0），② 次选是宿主显式结束（**照字面做不到**：`rl.close()`/`unref()` 都是脚本进程内的调用，宿主手里没有 rl —— 它只有「关管道写端」或「杀进程」两条路，超时 `destroyForcibly` 不做默认），③ 专用 `ask()` API 不做（只帮愿意用它的人，与「脚本写法保持原生」相悖）。药三段：**脚本侧只报事实**（`bridge/js/src/stdin-idle.ts`：往 `process.stdin` 挂一个 `'pause'` 当闹钟 + `data`/`readable` 监听器计数当判据，报一次 `engines.stdinIdle`；`setImmediate` 延后一拍，免得把「同 tick 关一个立刻开下一个」误判成没人读；**不碰 readline 模块、不 unref**）→ **引擎侧五层关写端**（`engines.stdinIdle` 目标恒是自己：runId 取自认证连接身份、payload 里没这个字段，缺身份拒、关不了回 `Ok false` 不是错误码 → `RuntimeController.closeStdin` 在途表精确命中 → `ScriptEngine.closeStdin` 缺省 false → `NodeProcessEngine` → `SpawnedProcess.closeStdin` flush+close；能力位 `NONE`）→ **顶栏「结束脚本」**（`GlyphKind.STOP`，在途才可点，`HostSummary.stopRun` 四步停走；兜的是「脚本正卡在 stdin 读」与死循环那两类自动判据管不了的形态）。**代价如实记**：EOF 一次性不可逆，「关掉读口过一会儿才重开」的脚本救不回来（那个 `question` 回调永不触发）。守卫 8 条（含**闭环**：报上来 → 宿主关写端 → 脚本收 `end`+`close` 并 code 0 退出）。**首页可写：根层（`files/scripts/` 本层）建文件/建文件夹 + 项目号判据放宽**（用户裁定「根层可写（打通三条判据）」；报源 = 用户实测「我在首页创建的，怎么进了 demo 里面」—— 根层此前不可写，`targetProject` 留着`files.firstOrNull()?.projectId ?: "demo"` 的顶替兜底）。**编码 = 空 `projectId`（`ProjectId.ROOT`）**：`(projectId, relPath)` 是唯一寻址形状，而 `Path.resolve("")` 恒等自身 ⇒ `projectRoot(filesDir, ROOT) == files/scripts/`、`scriptFile(..., ROOT, RelPath("x.js")) == files/scripts/x.js`，**引擎侧（`NodeProcessEngine` 的 `ProjectId(run.projectId)` 与 cwd）一行不用改**（恒等式进测试）；危险面就地设闸 —— `FsProjectStore.create/delete` 加 `require(id.value.isNotEmpty())`（`rootFor(ROOT)` 就是库根，否则 `delete` 删掉整个项目库），外部输入仍进不来哨兵（`createOrNull("")` 回 null）。**三条判据各改一处**：列（`ScriptFilesRead` 的 `projectId` 改成 `nameCount == 1 → ROOT`，此前根层文件被报成"项目 `x.js`"、点开必得「项目不存在」）/ 读写（`ScriptFileOps.resolveInProject` 根层分支把"首段与项目名一致"换成"**必须正好一层**"，防越界那道闸一字不动）/ 跑（`projectInternalOrNull` 根层回 relPath 自身**且必须单段**，往下照旧 —— 落盘索引 `("", "x.js")` 与清单行同形，悬浮窗兜底补位号照样命中）。**项目号判据放宽**（用户裁定"允许中文"）：`PROJECT_ID` 从 `[A-Za-z0-9_-]+` 换成 `[^/\\\p{Cntrl}.]+` —— 放宽的只是字符集，批 84 那条"拒 `.`"（`resolve("..")` 跳出项目根）**不动**；不能只放宽根层（根层文件夹落盘就是项目目录，两套判据会造出"建得出、里面文件打不开"）。代价：判据是全局的，`NpmConsoleKeys.rejectProjectId` 话术跟着从"允许清单"改成"禁止清单"；已核 npm 执行面是 argv + `pb.directory()`（项目号不进 shell 串）、`ConsoleHistory` 走完整 JSON 转义。根层建文件夹 = 建项目，多两条判据（名字要过 `isValidProjectId`、不许叫 `node_modules`）；顺带堵"建了但看不见"（不以点开头、UTF-8 ≤ 255 字节），**文件名不受项目名那两条限制**。前端：`targetProject` 兜底换成 `ProjectId.ROOT.value`、根层 FAB 与对话框说「新建项目」、日志面三处与**悬浮窗标题**改用 `ScriptPaths.fullRelPath`/`projectLabel`（原先会渲染成 `//x.js`）。验：`:domain` 全量编译零 error + `:app`/`:ui`/`script-repo` 相关文件编过（`AssembledShell` 单文件过语法）；**JUnit launcher 真跑 118 例全绿**（domain 44 / app 26 / ui 48，含新增 16）；detekt 四模块零 findings（`:ui` baseline 外科式改 2 行 —— 多了 `atRoot` 参数即签名文本变）；文档链接门 786 条过。同批推翻/订正：无。 | [`log/2026-10-16.md`](log/2026-10-16.md) |
| 2026-10-16 | 3 | 三条目：**崩溃病因进悬浮窗 + 跨窗口收键盘 + 兜底补位号加归属过滤**（用户报三条）。① 脚本抛错的 `stderrTail` 此前只进档案 `crashSummary`（要翻任务日志详情才读得到），悬浮窗画的是「没有输出」—— 药 = `ControllerRunDispatcher.crashLine` 缝按 error 档写一行、**归属仍是这次 runId**（同一数据面 → 窗/控制台/日志管理三处同时可见）；② `dismissFocusOnTapOutside` 只看得到自己那棵组合，而悬浮窗是另一个窗口、另一份 `FocusManager` —— 药 = `FocusDismissRegistry` 两档（应用窗口触摸 → 清独立窗口那份；悬浮窗被点 → 清应用侧那份）+ `DismissFocusOnWindowBlur`（切后台那一路），**只清对方不清自己**（清自己会打断正在进行的输入）；③ 兜底补位号此前取**全库**最新非引擎外行、无归属过滤 → 窗刚开会被补到上一次别的脚本的输出上（用户读作「它跑了上一次那个脚本」）—— 药 = `fallbackRunIdFor` 按 projectId + 项目内 relPath 全等从落盘索引挑，重跑前另确认文件还在。`prompt()` **不加**（用户选定改用 readline；Node 无全局 prompt，本机实跑 ReferenceError） | [`log/2026-10-16.md`](log/2026-10-16.md) | 两条目：① 悬浮窗底部输入框 → 脚本 stdin + 胶囊框换白（第三轮配色）；② **悬浮窗标题下加分隔线 + 「点输入框外面收键盘」修掉"光标还在闪"** —— 后者真因**不是闪烁动画，是焦点根本没转移**：Compose 的 `clickable` 只装 `focusableInNonTouchMode`（**触屏模式下压根不请求焦点**，1.7.3 源码实读），而 `BasicTextField` 只在别处主动 `requestFocus` 时才失焦 → 点列表行/卡片/⋮ 之后搜索框仍 `focused=true`、输入法仍开着，敲字落进搜索框（设备实测 `input text ZZZ` 把列表过滤成「没有匹配…的文件」）。药 = 新增 `FocusDismiss.kt` 一对修饰符：输入框报根坐标 bounds + 聚焦态，容器在 **Initial 趟**读按下点（**只读不消费**，列表滚动/行长按/日志拖选照旧）、落在框外就 `clearFocus()`；按 bounds 判而不是"看谁消费了事件"（两边都会 consume 那个 down）。三处接上：项目页（挂 `ManagementTransition` 的 modifier，含点编辑面那一下）/ 任务中心 / 悬浮窗。分隔线画在头部**块内底缘**（三块同色，悬在 8dp 缝里会像飘着） | [`log/2026-10-16.md`](log/2026-10-16.md) | **悬浮窗底部输入框 → 脚本 stdin（用户令）+ 胶囊框换白（第三轮配色）**：① 配色第三轮 —— `WindowBlockSurface` 从 `surfaceMuted` 换成 `surface`（与窗底同键），整张卡片是一个白面 + 一圈 1dp 淡灰描边（三轮口径的取舍逐条记在 `WindowSurface` KDoc：第一轮灰窗白胶囊+阴影被用户否掉 → 第二轮白窗描边+灰胶囊 → 第三轮胶囊也白，**同色是用户明确选择不是漏改**）。② 输入链路**五层全走 OS 管道、脚本侧零改动**：`SpawnedProcess.writeStdin`（`ProcessBuilder` 本来就 `Redirect.PIPE`；utf-8 + `\n` + **必须 flush** —— 不 flush 会攒在 8KiB 缓冲里，脚本永远等不到那一行）→ `ScriptEngine.sendInput`（缺省 false）→ `RuntimeController.sendInput`（在途表精确命中；**暂停中也照送**：SIGSTOP 冻的是 CPU，管道内核缓冲照收）→ `HostSummary.sendInput` → `LogWindowController.sendInput`（空白行不发 / 不在途与已结算不发 / 成功才清草稿 / 失败如实 toast）—— 脚本用 `process.stdin`/`readline` 照常读，与裸 `console.log` 走桥同一条判据。③ **系统窗的输入法**：那颗窗全程 `FLAG_NOT_FOCUSABLE`（"贴在旁边看"不抢输入法），而不可聚焦的窗口拿不到 IME —— 药 = `setImeFocus` 在输入框**拿到焦点那段时间**才摘掉它 + 补 `requestFocus`/`showSoftInput`（Compose 自己那次请求发生在窗口还不可聚焦时是空放的）。④ 草稿住 `LogWindowState.inputDraft`（两个宿主画同一个窗，内容层无状态 ⇒ `remember` 会在 `setContent` 重喂时丢字）。守卫四条（真起 node 喂两行 `readline` 收到 / RuntimeController 在途口径三条 / LogWindowController 四条 / 替身各补一条）；detekt 本机跑全部 15 个源面（gradle 缓存里的 detekt-cli 1.23.8 + 仓级 yml），新增两条进 baseline、三处 `LongParameterList` 刷新签名 | [`log/2026-10-16.md`](log/2026-10-16.md) |
| 2026-10-16 | 1 | 一条目：**readline 提示语与输入回显进悬浮窗 + 子页空白处触摸穿透**（用户报两条）。① `readline.createInterface({input})` 没给 output 时 `rl.question('请输入：')` 的提示语写进虚空（`terminal:false` 下 output 缺位；给了 `process.stdout` 也进排水管道，§8.3）—— 药 = 引导期包 `createInterface`，脚本没给 output 时补一个假 output（`EventEmitter` + `write`，非 TTY）把提示语送 console 数据面；给了 output 的一概不碰、只包 `readline` 与 `readline/promises` 两条工厂；② `terminal:false` 下 readline **从不回显**用户敲的行（那是 TTY 驱动的事）—— 药 = **窗口侧回显**：`LogWindowState.echoes`（`InputEcho(atSeq, text)`，送出成功才添）+ `rows` 按 atSeq **归并进行流**（不缀末尾，否则因果读反），清空日志连回显一起清；③ 管理面板子页在前台时点空白会穿透到底下面板（同坐标恰是「依赖管理」入口行），把状态机推出两层设计、退出路径中断（日志管理退不出来）—— 病在 `InnerNodeCoordinator.hitTestChild`：子页空白处没有 pointerInput 节点 → `hasHit()` 为 false → 继续试下面的兄弟；药 = `Modifier.claimPointerHits` 挂在子页那层根上（**不消费事件**，只占位），而**上一版**在下层 `consume()` 的写法会连子页自己的点击一起废掉（`detectTapAndPress` 是 `awaitFirstDown(requireUnconsumed = true)`）—— 错法留证在 KDoc | [`log/2026-10-16.md`](log/2026-10-16.md) |
| 2026-10-16 | 1 | 一条目：**readline 接口 close 之后放开 stdin（答完就能结束）**（用户问「为什么输出 2 之后过了很久才自动关闭」）。病：`process.stdin` 是被 ref 住的句柄，`rl.close()` 只是不再读它、不放开，事件循环永远不空 —— 本机实测管道与真 TTY（pty）**都**这样（真终端靠 Ctrl-D 收尾），而这里 stdin 写端永远开着、等不到 EOF，`91929b1d` 药 = 记账「stdin 上还活着几个接口」，最后一个 close 且无 `data`/`readable` 监听时 `unref()`，新建接口 `ref()` 回来（`resume()` 不会自己 ref 回来，实测 unref 后新建接口等输入 159ms 就退）；放开延后一拍（`setImmediate`）以看见同 tick 挂上的裸 `data` 监听；守卫三条起真子进程。另 `4daf4ad3` 修 CI 红：`rows` 未绑定时仍画回显，改成自己按绑定/水位过滤 | [`log/2026-10-16.md`](log/2026-10-16.md) |
| 2026-10-15 | 8 | 八条目：悬浮窗结算不解绑 + 脚本输出详情闪退修法 + 快速脚本兜底补绑与已结算暂停静默早退 + 悬浮窗第一颗钮三态与重跑走引擎真实再跑 + 三态真机冒烟补记 + 裸 `console.log` 走桥（方案 B） + `console.dir`/`dirxml` 一并接桥（`process.stdout.write` 裁定不做） + **「该执行不支持暂停」真因：pid 反射阶梯方向反了（从 `Process::class.java` 起步够不着子类，设备上 pid 恒 null；推翻此前「云手机冻不住」的误判）—— 药 = 从 `process.javaClass` 起步 + 两条方向门；影响面含看门狗 noPid 与防劫持检查**；计数 8 行 | [`log/2026-10-15.md`](log/2026-10-15.md) |
| 2026-10-15 | 2 | **「日志管理→脚本输出→点一行就闪退」**：`ScriptLogDetail` 把详情三态 `load`（`LoadState`）与行 `lines` 放进 `rememberSaveable` —— `LoadState` 的三态是 `data object`/`data class`，都不是 `canBeSavedToBundle` 白名单（`Serializable`/`Parcelable`/`String` 等）里的类型，**首帧** `requireCanBeSaved` 直接抛 `IllegalArgumentException: MutableState containing NotLoaded cannot be saved`（设备实测完整栈，RememberSaveable.kt:242）→ 点一行必闪退。第一版只改 `lines` 设备复现仍是 `load` 先抛。药 = 两个都改 `remember`（详情是屏内局部现值，配置重建重新拉一次，成本只是一次本地文件读）；`selectedRunId`（`Long?`）留在 `rememberSaveable`。设备验证：坐标序列修前必现 FATAL、修后 pid 不变无 FATAL。**教训**：`rememberSaveable` 失败是**首帧即抛**，不是配置切换才炸 —— JVM 测试看不见（无真注册表） | [`log/2026-10-15.md`](log/2026-10-15.md) |
| 2026-10-14 | 5 | **「终态记录不可改写」（用户报）**：engineRunId 是跨进程持久身份（`RunRecord.id`/`EngineRunLink.engineRunId`/执行日志文件名），而 `NodeProcessEngine` 的分配器是**进程内**计数器（`AtomicLong(1)`）—— App 每次重启从 1 重数，新进程第一次执行重发上一进程用掉的号 → 档案 `put` 撞「终态不可改写」响亮失败 → toast 报错、档案行没写成、悬浮窗落空态。设备实测：`run_archive` 2 行 vs 意图日志 6 行、`script-logs/index.jsonl` 里 `{"run":1}` 三次；`am force-stop` 后必复发。药 = `RunArchive.maxEngineRunId()`（高水位）+ `ScriptEngine.adoptRunIdHighWater`（只前移/幂等/非挂起）+ 装配期把水位顶给池里每个引擎（`AppShell.assemble`/`AppShellKit.assemble` 加 `engineRunIdHighWater=0`，生产读 `RunArchiveWiring.highWater`）。三实现各补一句 + 契约/引擎/装配三条回归门。**边界**：§8.5 缺「跨进程不复用」明句而 `docs/design/` 冻结，留给维护者 | [`log/2026-10-14.md`](log/2026-10-14.md) |
| 2026-10-14 | 4 | **点执行零输出第二层根因**（第一层 `.so` 路径修好后真机复跑仍零输出）：`main.cpp` 把整个位置参数段排在 `--` 之后，而批 107 的堆上限（`--max-old-space-size=192`，argv 注入）也在其中 —— node 遇 `--` 收手，标志被当成**脚本名** `require`，设备实测 `MODULE_NOT_FOUND: Cannot find module '--max-old-space-size=192'`、退出码 1、引擎判 CRASHED（`crash_summary` 空：CRASHED 走 Killed 路径）。桌面真 spawn 测试只对拍「有标志比没标志小一个数量级」且走系统 node，**不覆盖 main.cpp 的拼 argv** —— 又一处 CI 全绿设备红。药 = 按 node argv 规则两段拼：选项插在 `-e BOOTSTRAP` 与 `--` 之间、位置参数仍自 `--` 起透传（host node 实测三形态 `process.argv` 与堆上限值均正确）。**边界**：C++ 本机禁编，未做交叉编译/真机复验，红绿等 CI 后装包再测 | [`log/2026-10-14.md`](log/2026-10-14.md) |
| 2026-10-14 | 3 | **点执行零输出**（用户报「点击执行后没有输出，悬浮窗提示本次执行未开始」）：根因 = 批 109 改名连带 —— `e338000` 把引擎三处 `.so` 路径（hostBinary / libnodePath / npmNodeBin）的接收者从 nativeLibraryDir 串成 nsDir（`files/.ns`）；Kotlin 不报错（两 Path 同型），设备上 `NodeProcessEngine.execute` 必填件预检全量 `ERR_FILE_NOT_FOUND` → 池 `Failed` → 悬浮窗绑不到在途 run → 空态（症状非病）。CI 全绿因 JVM 走假引擎/系统 node。药 = 三处改回 `nativeLibDir.resolve(…)` + 新增 `AppShellEnginePathsTest`（读源现推 val 名、钉「三处接收者 = nativeLibraryDir 派生」+「≠ files/.ns 派生」，已拿 e338000 源验会红）；类 KDoc 记 `design-decisions` §77.3 同形态 | [`log/2026-10-14.md`](log/2026-10-14.md) |
| 2026-10-14 | 2 | **固定开发签名密钥**（用户令「不要每次编译出来的安装包签名都不一样」）：病 = AGP 的 debug keystore 每台机器各自生成 → 本机包与 CI 包签名不同、互相盖不上（装过 CI 包后本机包须先卸载，`filesDir` 一并清空，真机复测每次从零；设备实测两把签名 SHA-256 不同）。药 = 仓库收 `keystore/autoscript-dev.keystore`（口令 `android`，同 AGP 调试约定），`signingConfigs.dev` 供 debug/release 共用，`.gitignore` 对这一个路径开例外；口令可经 `AUTOSCRIPT_DEV_KS_PASS`/`AUTOSCRIPT_DEV_KEY_PASS` 覆盖。**开发密钥非发行密钥**（公开仓人人可取，仅开发期互相覆盖用；§18 第 3 项不发行，正式轨在 :app-service:packager）。与 `SECURITY.md`「密钥库绝不提交」那句冲突，留给维护者补口径 | [`log/2026-10-14.md`](log/2026-10-14.md) |
| 2026-10-14 | 1 | **「点执行就闪退」修法**（用户复报后）：离屏组合协程域没有 `CoroutineExceptionHandler`（javap 核实 1.7.3 只挂 Job），而此前内容效应键含 `lines`（轮询每轮产出新 `List` 实例）→ 每 400ms 重跑一次裸 `setContent` 重喂分支，组合期一抛即进程终结。四层收口全 fail-closed：行数据住 `LogWindowController.lines`（Compose 状态，窗内现读，`setContent` 次数 = 开窗次数）+ `show()` 重喂分支补 catch（失败落 in-app 回退）+ ▶ 链三条 ISE 逃逸路径（`logWindowRef()` 在 try 外 / `scope.launch` 里 / catch 链没接 `IllegalStateException`）收成失败 toast 或「没有悬浮窗的立即执行」 | [`log/2026-10-14.md`](log/2026-10-14.md) |
| 2026-10-13 | 2 | **悬浮窗跟手拖动 + `▶` 链路径形状收口**（用户实测反馈）：拖动不跟手两条真因（`pointerInput` key 是每帧新建的回调 → 检测协程被自己的重组打断；窗随手指走时本地坐标被同向抵消 → 半速）+ in-app 兜底窗也接拖动 + 位置跨窗存活 + 边界夹取（`getCurrentWindowMetrics().bounds`，`getCurrentSizeRange` 会钉死左上角）；**存量 bug**：`▶` 链把清单形状 relPath（首段 = 项目名）直透调度器/引擎 → `scripts/demo/demo/data/test.js`，修法是 `ScriptPaths.projectInternalOrNull` 收口 + 两条链上用例 | [`log/2026-10-13.md`](log/2026-10-13.md) |
| 2026-10-13 | 1 | **执行日志悬浮窗 + 脚本输出按执行持久化 + 暂停执行**（用户令）：系统级悬浮窗（TYPE_APPLICATION_OVERLAY，切 App 也能看）+ 落盘（`filesDir/.ns/script-logs/`，`<runId>.log` + `index.jsonl`，只记脚本行）+ 暂停链（SIGSTOP/SIGCONT + `paused` 旁账集，不加 SUSPENDED）+ 日志管理按执行列表 + 详情。推翻批 74「不做 console 持久化」与「暂停未进 P0」（决策第 79 项）。顺修 `onRunFile` 双 launch 假挂起 | [`log/2026-10-13.md`](log/2026-10-13.md) |
| 2026-10-12 | 19 | 批 109：**typed ids**（外审长线第一项）—— `:domain` 新增 `ProjectId`/`RelPath` 值类，值类只落**两端**（两个 ingress + 拼路径出口），中间 DTO 不动。**本批一半的价值是判据收一份**：原先**三份** relPath 判据 + **两份**项目号判据，现在一份不剩（`ScriptDeployRecovery` 那份私有副本删掉了——它是被架构门禁逼出来的，而 `:domain` 是允许的依赖）。**一次行为变更照实记账**：含 `.` 的项目号不再列出、`"a".repeat(65)` 不再被拒，两条都由 `ProjectStoreTest` 新增用例钉住。签名域一个字节没动。`Stream` + 值类要装箱（编译器给不出唯一重载），显式循环 + 事后排序 | [`log/2026-10-11.md`](log/2026-10-11.md) |
| 2026-10-12 | 18 | 批 108：存储目录 `.autojs` → **`.ns`**、**不做旧版兼容**（维护者拍板，D6 结案）；同批把「对外发布 npm 包」那条需求**从待办池直接删除**。顺手统一三处同病相邻命名（`autojs-lock-v1` / `autojs-snapshot-v1` / `.autojs.build.ignore`）—— 批 7 只扫了描述面没扫到域内字符串；`AutoJsPro` 九处与第三方站点逐字未改。**方法收获**：第 18 项把「结论」与「支撑它的那条风险」焊在了一起，「要配迁移策略」本是成本不是否决 | [`log/2026-10-11.md`](log/2026-10-11.md) |
| 2026-10-12 | 17 | 批 107：**脚本设置页**（用户口径：权限列表下面加一行）—— 单脚本档（堆 `--max-old-space-size` argv + `UV_THREADPOOL_SIZE` env）与桥档（外审 **M2 结案**：每连接条数 64 / 字节 32 MiB / `limitedParallelism` 8，三档接在批 100 的取闸链上）两档全做、立即生效。**M2 挂了三批、这一批能结案**：批 100 的两条不做理由都建立在「这个数必须由我们定」之上，用户要的恰是可改的旋钮 | [`log/2026-10-11.md`](log/2026-10-11.md) |
| 2026-10-12 | 16 | 批 106：**jsonl 压实**（外审 B3 末件）—— `FileScriptEnvStore` 写后按 `Files.size` 判、超 256 KiB 就把收敛视图 tmp+`ATOMIC_MOVE` 重写；**判据是「读口问现状还是问历史」**（前者可压、`install-history.jsonl` 那类审计事实不可）。阈值可注入是为让单测用小阈值走真代码（第一版守卫凑 256 KiB 直接假绿）。一条守卫抓到语义错误：压实是**每次写后判一次**，不是攒够再收拾（盘占用上界 = 阈值 + 一次写入）。`remove` 墓碑行为照旧但 KDoc 里那个理由已被压实压掉，两段互相点名。另订正三处台账矛盾（M4 / A13 / `TrustTierResolver`） | [`log/2026-10-11.md`](log/2026-10-11.md) |
| 2026-10-12 | 15 | 批 105：两份 `ProcOps` 合并成**新模块 `:platform:process`**（消掉批 95/99 留下的「手法逐字同款、代码两份」重复）。**落点选叶子模块而非塞 `:domain`** —— 它只用 `java.nio` + `Process` 桩面，塞领域层能消重复但消的是症状留的是根因（领域层不该知道 `/proc`）。**`:engine:node-process` 的 `ProcessLauncher.pidOf` 刻意留一份**：反向依赖即破 §6，那段纯反射不值得。顺带两处：`ProcTreeOps.alive` 改走注入的 procDir（原来写死 `DEFAULT_PROC`，合成树插不进去）+ 信号名表拆成 npm 侧的 `T1SignalNames.kt`（T1 exit 帧契约，只此一个调用方）。模块数 17→18 / 测试任务 14→15，冻结面五处同步（`ModuleGraphTest` 的计数门当场抓到 `build-logic/settings.gradle.kts` 漏改）。两侧 ArchitectureTest 黑名单**逐条改**，放行粒度到子包（`platform.process` 放行，其余 `platform.*` 三面仍零依赖）。守卫 7 + 1 | [`log/2026-10-11.md`](log/2026-10-11.md) |
| 2026-10-12 | 14 | 批 104：外审 **M1 收口**（`scriptFile` 补读侧词法闸 —— 此前一个字都不校验，而返回值直接进 `node <路径>` 的 argv；`projectId` 复用 `isValidProjectId`，非法路径折 `ERR_INVALID_PARAM`）+ **B3 的流式读那半**（`FileScriptEnvStore.replay` 去 `readAllBytes`，改逐字节扫）。守卫新增 7 + 1 + 1 | [`docs/log/2026-10-11.md`](log/2026-10-11.md) |
| 2026-10-12 | 13 | 待办池清池续：**划掉的 31 行直接删掉**（决策第 73 项）—— A–E 五张表里全部 31 条划掉的行已删除（只删条目行，结论不丢：已完成叙事在流水切片、裁定口径在决策记录）。上一轮那批判的是「不再跟进」不是「已完成」，划线等于往待办池塞一条**永远不会被领走的假待办**，下次列清单它又冒出来 —— **划线在本仓是「结论已定」的记号，不是「待办」的记号**；没结论的（外审 B3/M1/M2 三条半成品、结构性长线项）一条没动。**D6 是 A–E 唯一活着的行**（需拍板） | [`docs/log/2026-10-11.md`](log/2026-10-11.md) |
| 2026-10-12 | 12 | KDoc 与实现矛盾七处订正（**零行为变更**）—— 逐条只改注释：`resolveIn` 纯词法不防符号链接、`scriptFile` 不做读侧校验（越界**读得到**）、`RpcNamespaceHandler` 的穿出在 Router 那档折成 `ERR_INTERNAL`、`generation` 只剩诊断价值（拿它判过期=假不变量）、`FileTaskStore` 收敛的只是崩溃半行（行内损坏响亮失败）、`LockSigner`「防搬锁」只对一半且单文件覆盖所有项目（行为不改，M9 裁定不做）、`AndroidShellExecutor` 准确口径是「尽力收整棵树、不保证」 | [`docs/log/2026-10-11.md`](log/2026-10-11.md) |
| 2026-10-12 | 11 | 批 103 收官：**待办池清池** —— A–E 各表未划掉的六行（B3/B4/B14/D13/E3/A13）按「不再跟进」划掉（**非「已完成」**，原文逐字保留）；外审登记表里的半成品与结构性长线未动。见 [`backlog.md`](backlog.md) 同日追记块 | [`docs/log/2026-10-11.md`](log/2026-10-11.md) |
| 2026-10-12 | 10 | 批 103（P2）：池记账换「空闲队列 = 唯一真源」+ `PoolLease` 幂等归还凭证（消除 `Semaphore`+`slot.state` 两套手拿同步；四种失真改为结构上不可能，含最坏的「双重归还超发一证 → 同一引擎跑两个脚本」）。阶段二的闩控交错守卫逼出归还顺序：**CAS 独占 → quiesce → 回队**（另两种各错一样）；`forceReturn` 给 killAll 不等 quiesce。阶段三 Controller.killRun 改走 lease。`stopEpoch` 删不掉（量「急停跨过排队窗口」）；`generation` 彻底退场（过期句柄物理上到不了槽位）。两处自写断言抓到真缺陷（启动失败路径漏 reuse / quiesce 撞 IDLE→STOPPED），断言一度放宽又回退（探测器变噪声即无探测器）。守卫 8+3 例 | [`log/2026-10-11.md`](log/2026-10-11.md) |
| 2026-10-12 | 9 | 批 102（P4）：jsonl → SQLite 三份并两份，四阶段 —— **A** SPI 搬迁（`TaskStore`/`ScheduledTask`/`TimedSchedule`/`ScreenGuarantee`/`CronTab` → `:domain`，依赖铁律所迫，47 文件语义零变化；baseline 的 CronTab 9 条抑制跟着搬）**B** `SqliteRunArchive`（终态由 SQL 条件 UPDATE 挡；`run_nonce` 无唯一索引 —— 档案不是幂等锚点）+ 契约套件**切两套**（第一版塞跳过撞 test-guard，「skipped≠绿」这道门是对的）**C** `SqliteTaskStore`（`task_id` 用户给的 TEXT 主键、排期三列非 JSON 列、重新登记=复活）+ 两份存储迁移接线（三份一套纪律：导+归档/可重入/失败响亮/无回落），`File*` 保留为契约第二实现。测试抓到两 bug：登记误自增发号、墓碑 null 使撤销任务复活。14 任务零跳过绿 + detekt 绿 | [`log/2026-10-11.md`](log/2026-10-11.md) |
| 2026-10-11 | 9 | 批 101（维护者拍案，F1/F2）：**契约同步** —— §7.6 补 `ERR_INTERNAL` 进错误目录 + 写「折叠两档、中间隔一档内部错误」为契约（AutojsException 原码透传 / IllegalArgumentException → ERR_INVALID_PARAM / 其余 → ERR_INTERNAL），同时对齐 `RpcNamespaceHandler` 基类 KDoc 与路由层实际行为（批 97 之前两处矛盾）；§10.3 把 `/proc` 扫描 + `kill` 二进制 + 扫在杀之前三档写进契约正文，并把**降级档（pid 探不到 → 只杀主进程）写成契约的一部分**（不留在 KDoc 私货里）。P2（池记账重构）/ P4（jsonl→SQLite）已拍板，各占其后一批。bridge/js 三面对账门 201 pass | [`log/2026-10-11.md`](log/2026-10-11.md) |
| 2026-10-11 | 8 | 批 100：外审整改第五刀 —— 桥在途**字节**闸（`maxBytesInFlight`=16 MiB）。原口径只有条数闸 256，而 `maxFrameBytes`(8 MiB)×256 = **2 GiB 堆**（两个已生效默认值相乘）→ 设备 OOM 且看不出是哪条面。条数与字节正交（与批 99 zip `MAX_ENTRIES` 同纪律），故加第三档：CAS 取闸 / 单条超上限直接拒不部分接纳 / 拒这一条不拒整条连接（回 `ERR_PERMISSION_DENIED` 且话术点名「字节」，不静默丢）。同条另两半（每连接配额 / `limitedParallelism`）如实登记不排期。守卫先红后绿 | [`log/2026-10-11.md`](log/2026-10-11.md) |
| 2026-10-11 | 7 | 批 99：外审整改第四刀 —— **M6** shell 收尸从「只杀直接子进程」变「杀整棵树」（`killTree` = `/proc` ppid 图 + `kill` 二进制 + pid 两级反射，`TreeOps` 缝；原 KDoc「不留孤儿进程」不成立，已订正；顺带订正顺序错：点名在杀之前）+ stdin 立即关闭（`cat`/`read x` 不再挂到超时）；**M7** zip 三条硬化（条目数闸 1 万：零字节海量条目能过两道字节闸，计数与字节正交 / `.tmp` 不自含 / `walk` 的 `UncheckedIOException` 折成 IOException 否则 tmp 残留且码不对）。记两份 `ProcOps` 的关系（手法同款代码两份，依赖方向所迫，KDoc 互相点名）。守卫 7 例 | [`log/2026-10-11.md`](log/2026-10-11.md) |
| 2026-10-11 | 6 | 批 98：外审整改第三刀 —— 两处 accept 循环（`BridgeSocketListener` 设备面 + `NewlineFrameServer` 本机 TCP 面）改「瞬时故障退避重试」（`EMFILE`/`ENFILE`/`ECONNABORTED` 一次就 break = 整条桥对所有未来脚本静默死亡）；本端 close 才收循环、连续 20 次才认定非瞬时、退避用 `retryGate.wait()` 供 close 唤醒；诊断面加 `acceptRetryCount` + 失败回执缝。守卫先红后绿（阈值改 1 即模拟旧行为），判据用可观测行为（握手仍能完成）。14 任务零跳过绿 + detekt 绿 | [`log/2026-10-11.md`](log/2026-10-11.md) |
| 2026-10-11 | 5 | 批 97：外审整改第二刀 —— 新增 `ERR_INTERNAL`（宿主代码缺陷≠参数错）+ BridgeRouter 折叠两档（AutojsException 透传 / IllegalArgumentException→ERR_INVALID_PARAM / 其余→ERR_INTERNAL）；pid 取值补私有字段反射阶梯（Android 8–12 防劫持判据不再整段跳过）+ `pidUnavailable` 计数；kv `onUpgrade` 从 DROP 重建改显式迁移表 `KvSchemaMigrations`（找不到路径抛、不静默重建）+ `onDowngrade` 响亮失败。守卫 7 例；契约卷 §7.6 同步；14 任务零跳过绿 + facade 201 + detekt 绿 | [`log/2026-10-11.md`](log/2026-10-11.md) |
| 2026-10-11 | 4 | 批 96：外审整改第一刀 —— 「排队不挡急停」（start 等证出 guard + 急停代次对表，修前 stop(A) 2927ms → 修后 ≈0，守卫 3 例先红后绿）/ jsonl 半行清盘（FileTaskStore/FileRunArchive 打开时截到最后一个完整行，守卫走通「重开→再写→再重开」）/ 桥请求 TTL 溢出截断（显式 24h 上限）。守卫全模块绿；登记未改：BridgeRouter 未知异常吞成 ERR_INVALID_PARAM、readAllBytes 全量读、进程池重构、SqliteKvOps.onUpgrade 丢表 | [`log/2026-10-11.md`](log/2026-10-11.md) |
| 2026-10-11 | 3 | 批 95：T1 杀树换 /proc 实现 —— ProcessHandle 整类不在 android.jar（批 91 杀树在真机静默空转）；ProcOps/ProcTreeOps：/proc ppid 图 + kill 二进制 + 扫在杀之前 + 二次收割 + pid 反射两级阶梯；无人点名 signal 按 128+n 惯例反查信号名表。守卫 ProcOpsTest 7 例 + T1ProcessTest +5（真进程树用例抓到 readStat 读目录的真 bug）。**边界**：真机自测 | [`log/2026-10-11.md`](log/2026-10-11.md) |
| 2026-10-11 | 2 | 批 94：T1 欠账两件 —— 脚本输出真流式（`ScriptOpExecutor` 补 `OutputSink` 缝，T1 会话进程 stdout 边读边落控制台环）+ 命令可取消（桥侧 `kill` 帧 SIGTERM/SIGKILL、`waitFor` 切片轮询查 `ScriptOp.cancelSignal`、facade/`HostSummary` `cancelConsoleCommand`、UI 停止按钮）；覆盖面如实：T1 真杀、重操作仍弃结果（TTL 兜底）。守卫五测试类 +11 例 + detekt baseline 重生成 + lintDebug 全绿 | [`log/2026-10-11.md`](log/2026-10-11.md) |
| 2026-10-11 | 1 | 批 93：控制台 `npm run`/`npx` 拆除人工审批 + env-NUL 修复 —— 用户裁定「审批不是这样用的……依赖面板是方便用户直接安装和管理依赖的地方。你把审批去掉吧」，**整面拆**：账本两类/桥面 `requestApprove`+`approvals`/`:domain` 七个审批类型/facade 四个方法/UI 审批卡（`approvalLedgerSnapshot` 保留只读）；放行判据改解析层白名单，`versionHash` 只进审计。同批修真机暴露 env-NUL：`connectTarget` 的 `"\0"` 前缀经 env 进 `ProcessBuilder` 必抛 `Invalid environment variable value` —— 改 env 给裸名、shim 按 `main.cpp` 同源 '/' 判别补前缀；桌面路径型原样，`T1BridgeNodeTest`+`T1BridgeE2ETest` 继续绿 = 直连分支钉死。守卫：完整 CI JVM 门 14 任务全绿 | [`log/2026-10-11.md`](log/2026-10-11.md) |
| 2026-10-10 | 3 | 批 91：T1 spawn 桥本体 —— `npm run`/`npx` 第一次真跑起来（`npm-t1-bridge.cjs` 经 unix socket 把 spawn 报给宿主、宿主起真进程、stdio 假管道回填；只放行异步三兄弟，`detached` 当场拒；**两条 shim 互斥** —— T1 只注桥那一份）。最贵的一课：`Channels.newInputStream/newOutputStream` 共用 `blockingLock()`，读线程抱锁阻塞 → 写线程永远拿不到锁，双向协议被流包装锁成单向（两类测试双双挂死、报错面只有「超时」）；改直读 `SocketChannel` 即绿。同批修握手死锁（`send` 排队等 `ready` 而 `ready` 等 `helloAck`）与 socket 保活（`refWhileBusy()`：无在途子进程 unref、有则 ref）。守卫 16 例（真 node 7 / 真 npm 4 / 替身 5），T1 两类不设 `assumeTrue`。欠账：不用引擎池 / 同 UID 无最小掩码 / 无输出流式 / `signal` 恒 null / 孙子被 reparent 后杀树链断 | [`log/2026-10-10.md`](log/2026-10-10.md) |
| 2026-10-10 | 2 | 批 90：控制台「活着」三件 —— **① 真流式 stdout**（`HostNodeExecutor.runNpm` 从「先 `readBytes()` 再 `waitFor`」改成专门读流线程 + 新的 `OutputSink` 缝；缝与 `ProgressSink` 分开 —— 那条装 `InstallEvent`（脚本侧契约形状），这条装 npm 原文；流真报过时不再补 `outputTail` 行、空行跳过 —— 后者是 `HostNodeNpmE2ETest` 抓到的：真实 npm 输出以空行结尾而 `takeLast(8000)` 取到空行）+ **② 常驻进度推送**（`:ui` 新增 `LivePoll.pollWhile` + 两个调用点：提交后跟到跑完 / 子页在前台时一直跟；**刻意不用**宿主的 `progress` SharedFlow —— 无重放，晚到订阅者永久丢一批；三条自我约束：有界 / 只依据宿主给的事实 / 每轮同一读口；间隔与轮数可注入）+ **③ 命令历史落盘**（新增 `ConsoleHistory`，`files/.ns/console-history.jsonl`；与审计史纪律相反 —— 那个只追加永不清理、这个允许修剪，差别写进类 KDoc 防误搬；按项目分开读；带凭据形态的行**整条不记**（不打码：打码后的历史看起来能跑，点进去得到「认证失败」）；写口在 `:ui` **派发点**）。**同批修一处真错**（探针 `SinkRingProbeTest` 测出来的，三个环各自的测试都绿因为都只喂自己那条路）：两处执行体 sink 调用点写的是 `events.tryEmit(ev)`，只喂 `SharedFlow` 没经 `emit` —— 执行体独有的 `DOWNLOAD`/`REIFY` **从未进过事件环**，脚本侧 `onProgress` 收不到、阶段条停在 `RESOLVE`。守卫：`HostNodeExecutorStreamTest`（含到达时间戳断言）/ `NpmEventDrainTest` 新增一例 / `ConsoleHistoryTest` 9 例 / `LivePollTest` 4 例 / `ConsoleCmdOpsTest` 新增 3 例 / `InstallCoordinatorTest` 新增 2 例；反证四条各自命中。边界：真机未验；凭据守卫认参数名形态不是 DLP | [`log/2026-10-10.md`](log/2026-10-10.md) |
| 2026-10-10 | 1 | 批 89：Shizuku 反射面 ↔ R8 keep 对齐门（`ShizukuKeepRuleTest`，三条判据双向比：类名集合 / 方法 `(名字, 参数表)` 集合 / 签名在真类上真解析；先剥 proguard 注释行，`proguard-rules.pro` 声明成测试输入 —— 不声明则改 keep 后任务 `UP-TO-DATE`、门静默不跑，实测复现；反证三条各自命中）+ 「`input tap` 挂 30s」成因订正（真机对照实验推翻 2026-10-09 记的「两者各自独立 / 只有冷置后的第 1 条挂」：打进应用自己窗口时**每一条都挂**，6 条 32.4–33.8 s；机理同一件事 —— `input` 的 `WAIT_FOR_FINISH` 要等目标窗口处理完，而主线程卡在 `IRemoteProcess.waitForTimeout` 上；证据 = ANR logcat + `/data/anr` main 线程栈逐帧吻合）。顺带订正测量口径：「延迟 2.4–2.5 s」是 `uiautomator dump` 的成本（~2.2 s），设备侧 tap 本身 0.038–0.081 s | [`log/2026-10-10.md`](log/2026-10-10.md) |
| 2026-10-09 | 10 | 批 88 真机验证（Shizuku adb 档 100% 不可用 —— 已修）：用户在真机上（Android 13 + KernelSU，Shizuku 13.6.0.r1086）冒烟，`su`（root 档）正常、`shizuku`（adb 档）一条都跑不起来（`NoSuchMethodException: …IRemoteProcess$Stub$Proxy.waitForTimeout [long, class java.util.concurrent.TimeUnit]`）。根因：`5a03dc3` 把 `newProcess` 改成「从公开接口取方法」时**没把同一条推理推广到它的返回值上** —— `drain`/`drainBoth`/`pump` 走 `process.javaClass.getMethod(...)`，而那是**包内可见**的 AIDL proxy。修法：新增同文件 `RemoteProcessApi`（每方法从公开接口取）+ `ShizukuProcessReader`（读进程那一半搬出 `ShizukuInput`）+ `waitForTimeout(long, "MILLISECONDS")`（设备 dex 反汇编核实）+ 流改 `PFD → AutoCloseInputStream` 且形状不符即抛 + `step()` 剥开 `InvocationTargetException` + `ShizukuExecResult` 补 `truncated`（批 88 已知缺口销账）+ proguard 删死代码规则、补 `IRemoteProcess`。守卫：`ShizukuRemoteProcessTest` 8 例 + `ArchitectureTest` 常量池字节门（已反证会红）。边界：真机复测待用户跑；13.6.0 服务端 AIDL 未逐字核对；两档流上限仍不统一。批 88：控制台 shell 面落地（`su`/`shizuku` 特权模式，用户口径）—— `:domain` `NpmConsoleKeys.parse(line, mode)` + 枚举 `ShellConsoleMode` + 三个入口词常量 + 三个新命令变体；`HostSummary.runShellCommand` + `ShellConsoleResult`（四字段）+ `PackageManagerFacade` 同形缺省体；`:app-service:npm` `ConsoleShellRunner`（执行 + 渲染，独立一件）+ `ShellOpExecutor` 缝 + `InstallCoordinator.runShellCommand`；`:platform:capabilities` `ShizukuInput.exec`（并发排空两条流）；`:app` `PlatformWiring.ConsoleShellExecutor`（ADB 档换成 Shizuku）+ `asShellOpExecutor()`；`:ui` `ConsoleCmdState.mode` + `ConsoleCmdOps` 进/退模式 + `ConsoleScreen` 模式徽标。**七条口径**：必须有模式不许静默挑一条（§9.3 同纪律）/ `su <cmd>` 与 `su` 是两件事 / `DEFAULT` 一律拒且拒要落一行 / 非零退出是结果不是异常 / 超时不渲染半截输出 / shell 面与依赖树无关（不占安装会话、不碰项目锁）/ `adb` 档 = Shizuku 且只在装配层换（`AndroidShellExecutor` 的 ADB 是应用 uid，改它会动到 a11y 输入注入）。**顺手修两处**：`ShizukuInput.shizukuClass` 补接 `LinkageError`（JVM 实测 `NoClassDefFoundError` 不是 Exception，会越过 `AutojsException` 直穿）；`NpmScriptResolver` 纯 JS 探测拆出 `PureJsProbe`（越 `TooManyFunctions` 线）。批 87：§10.9 第 1 条变更半边落地（依赖面板的安装输入行/旗标/阶段条 + 清单行「卸载」）+ 第 5 条 `npm-cache` 尺寸栏 —— `:domain` `PackageManagerFacade.cacheStorage()` + `NpmProjectSnapshot.cache` + `HostSummary.runNpmPanelCommand()`/`npmInstallEvents()`；`:app-service:npm` `InstallCoordinator.cacheStorage()`（只量 `content-v2`）+ 快照里缓存读数提到项目循环外 + `CacheIndex.contentBytes()` 默认体；`:app` 两条生产实现；`:ui` `NpmInstallOps` + `InstallProgressState`（六档阶段）+ `InstallCard` + `NpmState` 六字段两条派生 + `MainActivity` 五条回调。**五条口径**：**走的是与控制台同一条宿主口**（门禁强度不取决于用户从哪个界面按下去）/ **进度是阶段不是百分比**（`InstallEvent.Progress.percent` 全仓从无赋值 —— 对原文的**收窄**）/ 「离线优先」是 `--prefer-offline` 不是"仅离线" / 草稿失败不清（宿主先落 ECHO 行再抛）/ **换项目要把阶段条与游标一起归零**（`seq` 环内全局单调，沿用旧游标会漏掉新项目 seq 更小的事件，阶段条会永远停在「进行中」）。批 86：§10.9 第 5 条动作半边落地（依赖维护四颗按钮 + 缓存按 lock 闭包回收 + `cacheRoot` 唯一一份判据）—— `:domain` `NpmMaintenanceAction`/`NpmCacheReclaimReport`/`PackageManagerFacade.reclaimCache()`/`InstallHistoryOp.CACHE_RECLAIM`；`:app-service:npm` `NpmCacheReclaim`（按 lock 闭包回收 + **修 index 悬空引用**）+ `InstallCoordinator.reclaimCache()`（保留集 = 所有项目 lock 并集，读不出的点名入史）+ `NpmCacheSeedDeployer.cacheRoot` 成为缓存目录唯一一份判据（四处读者此前互不相同，静默失效）；`:app` `HostSummary` 两条；`:ui` `NpmMaintenanceOps` + `MaintenanceCard`。**六裁定**：按 lock 闭包回收而非 `npm cache clean` / 保留集取所有项目并集 / op 名 `cache_reclaim` / **必须同时摘悬空 index 行**（实测悬空 index 让在线 `npm install` 报 `ENOENT … Invalid response body`）/ 认不出形状的保留、删不掉的不计入 / `CI` 不绕过验签。`ui/detekt-baseline.xml` + `app-service/npm/detekt-baseline.xml` 随新增 `@Composable` 与循环跳转重生成。批 85：审计页落地（§10.5-2）—— `InstallHistory` 从「生产零消费方」转为有读口：`:domain` `InstallHistoryEntry`（五字段，**由落盘格式决定**）+ `InstallHistoryOp`（已知操作名唯一一份；`op` 仍是 `String`，取值域开放 —— `opName(args)` 直取 argv 首词）+ `PackageManagerFacade.history()`；`:app-service:npm` `InstallCoordinator.history()` + `InstallHistory.Op` 改指向 `:domain` 的**别名** + **补记 `enqueueHeavy` 的磁盘/配额预检拒绝**（原先不入史，`crossCheckRegistry` 的拒绝一直在记）；`:app` `HostSummary.npmHistory()`；`:ui` `AuditScreen`/`AuditState`/`AuditOps`，入口在依赖管理页顶栏。**三条呈现口径**：失败行不藏 / 未知 op 原样显示 / 筛选只影响显示（宿主读口无参全量 —— 按项目筛会让 `registry` 那条空 `projectId` 的全局变更掉出去）。**未落**：「可导出」那半截（SAF 通道没接，界面不画按钮）。`ui/detekt-baseline.xml` 随新增 `@Composable` 与 `MainActivity` 复杂度/函数名重生成。批 84：控制台改做命令面（§10.9 第 3 条「npm 终端视图」落地）+ 日志整体搬去「日志管理」（三段：系统日志/脚本输出/任务日志）—— `:domain` `NpmConsoleKeys`（命令行判据唯一一份：`parse` 四形态 / `SUBCOMMANDS` 白名单 / 轻·重拆分 / `packageSpecsIn` / `gitSpecIn` / `rejectProjectId`）+ 四个新 DTO + 枚举 `NpmConsoleLineKind`；`:app-service:npm` `InstallCoordinator.runConsoleCommand`/`consoleOutput` + 第二条 `SeqRing<NpmConsoleLine>`（512）+ `HeavyOpOutcome.outputTail`；`:app` `HostSummary` 两条（不经桥，桥面一条不加）；`:ui` `ConsoleCmdState`/`ConsoleCmdOps`/`ConsoleScreen` 重写 + `LogManagementScreen` 三段化 + `ActiveRunState.summaryText()` 收拢重复；顺带修 `ScriptPaths.PROJECT_ID` 放行 `.`（`resolve("..")` 跳出项目根）。**六裁定**：日志搬去日志管理一行不少 / 只认 npm 与 npx（不加 sh）/ 子命令白名单 / 输出粒度 = 事件流 + npm 输出尾部（真流式 stdout 未落）/ 依赖提供的命令照实接线到 T1 门禁（审批 ≠ 执行，批完要重敲那一行）/ 判据唯一一份住 `:domain`。`ui/detekt-baseline.xml` 随新增 `@Composable` 重生成。批 83：镜像源管理（§10.9 **新增第 8 条**）+ registry 两条断链收口 —— 管理面板「镜像源管理」落地：`:domain` `NpmRegistryKeys`（判据唯一一份；`NpmRegistryVerifier.canonicalRegistry` 改为委托、`OFFICIAL`/`MIRROR` 改别名）+ `:app-service:npm` `NpmrcFile`/`NpmGlobalConfig`（`files/.npmrc` 整文件重写）/`InstallCoordinator.resolveRegistry`（两层链：项目 `.npmrc` → 全局 → 出厂）/`globalRegistry()`+`setGlobalRegistry()`（存原样、空=删键、校验不过抛原文、入 `Op.REGISTRY`）+ `HostNodeExecutor` `registryOverride: String?`（null = 不注入 `--registry`）+ `--userconfig` + `prepareWorkDir` 拷 `.npmrc` + `:app` `HostSummary` 两条 + `:ui` `RegistryScreen`/`RegistryState`/`RegistryOps` + 管理面板那行接上真入口（`ManagementBackHandler` 的 `else ->` 改显式枚举分支，防新子页静默吞返回键）。**两条断链实测**：`--registry` 因唯一构造点不传参而永远钉官方、项目 `.npmrc` 因 `--prefix` 已给且不拷进 workDir 而根本没被 npm 读到（npm 12.2.0 实测：`--prefix` 一给，项目级配置只看 `prefix/.npmrc`）—— 净效果是 `setRegistry`/`config()` 写入侧生产上空转。`ui/detekt-baseline.xml` 随新增 `@Composable` 重生成。批 82：脚本全局环境变量（§8.1 **新增契约**）—— 管理面板「环境变量」落地：`:domain` `ScriptEnvStore`/`ScriptEnvEntry`/`ScriptEnvKeys` + `:app-service:script-repo` `FileScriptEnvStore`（`files/.ns/script-env.jsonl`，追加 + `force(true)` + replay 收敛 + 半行容忍）+ `NodeEngineConfig.scriptEnv: () -> Map`（**每次 spawn 现读**，用户键先写/宿主键后写 = 顺序即契约）+ `HostSummary` 三条 + `:ui` `ScriptEnvScreen`/`ScriptEnvState`/`ScriptEnvOps`；**拒收 `AUTOSCRIPT_` 保留前缀**（`AUTOSCRIPT_BRIDGE_TOKEN` 被覆盖 = 伪造桥身份或自断桥，写入侧拦 + spawn 侧兜两道）。`ui/detekt-baseline.xml` 随新增 `@Composable` 与 `MainActivity` 越 `TooManyFunctions` 线重生成，detekt 全模块绿。批 81：依赖面板 + 审批卡（§10.9.1 / §10.9.2）—— 三件：审批账本缺省落盘 / `:domain` `NpmPanelSnapshot` 读口 + `HostSummary` 两条（不经桥）/ `:ui` `NpmScreen` + `NpmState`（批准/拒绝走 `resolveApproval`）；`:ui`/`:app` detekt baseline 随新增 `@Composable` 与构造参数重生成；`:domain`/`:app-service:npm`/`:ui`/`:app` 四模块测试全绿。批 80：`child_process` 拦截 shim 接线 + 零 spawn 金标准（§10.11 P0 / §10.12 末行）—— `npm-spawn-gate.cjs`（classpath 资源，七个入口全替换为抛错；`detached:true` → `ERR_PERMISSION_DENIED`、`fork` → `ERR_NOT_IMPLEMENTED`、其余 → `ERR_NPM_SPAWN_BLOCKED`）+ `NpmSpawnGate`（落 `files/.ns/`、`NODE_OPTIONS=--require` 追加注入、播报解析、码折叠）+ `HostNodeExecutor(spawnGateFile=…)`；装配层**三条齐才注入执行体**（素材 + 宿主 + shim），shim 落不上就不注入（fail closed）。金标准落成 `NpmSpawnGateMatrixTest`（门禁下 install/ls/dedupe/prune/uninstall/ci 全绿 + 不注入也全绿的反向变异 + `npm run` 确实被拦且无产物），已进 `check-e2e-ran.sh` nightly 验尸清单。验收：14 任务 JVM 线全绿、`NpmSpawnGateTest` 9 例 + 矩阵 3 例全过、lintDebug + assembleDebug 绿（shim 实测进 APK）、去掉 `-PskipNpmE2E` 后 nightly 验尸四条真 npm 路径全 ✓ | [`log/2026-10-09.md`](log/2026-10-09.md) |
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
