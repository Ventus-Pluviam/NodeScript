## 8. 执行层设计

### 8.1 引擎抽象（`:domain`，纯 Kotlin）—— 已落定形态
```kotlin
interface ScriptEngine {                              // 实现在 :engine:node-process
  val id: EngineId
  suspend fun execute(run: EngineRunRequest): EngineRunReceipt
  suspend fun stop(): StopResult                      // 四步 quiesce 入口
  suspend fun kill(): KillCause                       // 仅 RuntimeController 有调用权（§4.1 kill 权威）
  suspend fun status(): EngineStatus
}
data class EngineRunRequest(projectId, scriptPath, args, runNonce, timeoutMillis)
data class EngineRunReceipt(runId, handle: HandleRef)
enum class EngineStatus { IDLE, BOOTING, RUNNING, QUIESCING, STOPPED, CRASHED }
sealed interface StopResult { Clean; TimedOut(partial) }
enum class KillCause { REQUESTED, WATCHDOG_HEARTBEAT, WATCHDOG_CPU, OOM, ENGINE_REQUEST, DRIFT, TIMEOUT }

interface EnginePool {                                // 实现在 :app-service:runtime
  val capacity: Int
  suspend fun acquire(request: PoolAcquireRequest): PoolAcquireOutcome   // Granted | TimedOut | Failed
  suspend fun release(handle: PoolHandle): StopResult
  suspend fun killAll(reason: KillCause)
  fun recycle(slot: PoolSlot)                         // 强杀后收归：槽位复位 + 还许可证（原子、幂等）
  fun stats(): PoolStats                              // capacity / free / busy
}
```
四条与早期草案的差异（都是落地后收敛的结果，写下来防止文档倒着改代码）：
- **无 `pause/resume`/`console: Flow`/`events: Flow`/`channel()`**：暂停未进 P0；控制台与事件走 §7.3 的 TSF 双队列 + EventBus 拉取，不建模成引擎侧 Flow（轮询式 Flow 会把「事件」伪装成「流」，丢失 TTL 与背压语义）；命名通道在 `:app-service:runtime` 的 `EnginesNamespaceHandler` 侧按 `channel/channelEmit/channelDrain/channelClose` 显式管理。
- **引擎是一次一脚本**（`execute(run)` 非 `start(session)`）：同槽位不并发两个脚本，会话身份 = `runId`。
- **`acquire/release` 收 `PoolAcquireRequest`/`PoolHandle`**而不是 `EngineSession`/`ExecutionHandle`：排队上限（`waitTimeoutMillis`）必须与请求同行，否则满池只能无限等。**时限归属（`TimeoutEnforcer`）同样与请求同行**：`AWAITER`（缺省，调度链路——发起方自己 await 终结并超时强杀）/ `WATCHDOG`（桥 `engines.exec`——发起方拿到句柄就返回，期限线随锚点交给看门狗，到点落 `KillCause.TIMEOUT`）；
  选 `WATCHDOG` 而不给 `scriptTimeoutMillis` 构造即 `require` 失败——判据是**发起方等不等**，不是「有没有声明超时」，声明了却没人执行等于没声明（§8.6）。
- 实现：`:engine:node-process`（NodeFactory），经 **Provider/SPI** 注入（`QuickJSFactory` 随沙箱裁掉，§18 第 1 项；缝留在原处，将来真要第二个引擎不必改接口）。

**执行身份 lease（`:domain` SPI，A5）**：在线引擎依赖 `RunIdentityIssuer.issue(engineId, engineRunId)`，
在 spawn 前登记一次性凭据，spawn 返回后立即 `confirmSpawn(pid, isAlive)` 并返回 receipt，
不等待桥 ready，更不等待 controller 激活。`RuntimeController.start` 的 guard 覆盖 acquire/execute/active 写入，
早到 heartbeat 等同一锁，不另建启动互等协议。PID 可空；receipt 的 PID 是启动快照，活性 getter 的契约不变。
启动异常、取消（含 IO 域返回时的取消）必须撤销本次 lease 并回收本次已取得的 process，不按当前槽位猜执行体。
未配置 HOST_SOCKET 保持离线；配置在线 socket 却缺 issuer/token 是接线错误，不静默降级。

自然退出通知 `lease.naturalExit()`，已绑定连接保留身份作 §7.5 有界排空；后续 pool.release 的 stop 若进程已死，
不把自然排空升级成硬撤销。活进程 stop/kill 则先 revoke 并关闭连接；同槽重用的旧尾帧与新 run 独立。
自停请求可能被自身撤销取消，controller 必须在不可取消收尾区域完成已有的有界停止/强杀及回池，
不承诺被关闭的连接还能收到 stop 成功 ACK。

**脚本执行环境变量（全局一份，批 82 落地）**：管理面板「环境变量」编一组 KV，
**所有脚本执行时注入脚本进程的 `process.env`**。契约住 `:domain`
（`com.autoscript.domain.scripts.ScriptEnvStore` / `ScriptEnvEntry` / `ScriptEnvKeys`），
落盘实现在 `:app-service:script-repo`（`core/FileScriptEnvStore`，jsonl 追加 + replay 收敛，
`files/.autojs/script-env.jsonl`，与 `tasks.jsonl` 同族纪律）。

注入面只有一处：`NodeEngineConfig.scriptEnv: () -> Map<String, String>`，
在 `NodeProcessEngine.execute` 构造子进程 env 时调用。三条口径：

- **每次 spawn 现读**（`() -> Map` 而不是装配期定死的 `Map`）：改完下次执行即生效，
  不做装配期缓存 —— 缓存 = 用户在界面改完却不生效。与本仓「读口现取不缓存」同一条纪律。
- **顺序即契约**：用户键**先**写，宿主键（`AUTOSCRIPT_LIBNODE` / `AUTOSCRIPT_HOST_SOCKET` /
  `AUTOSCRIPT_BRIDGE_TOKEN` / `AUTOSCRIPT_RUN_ID` 等）**后**写，故宿主键一律胜出。
  这是第二道兜底，第一道在写入侧（下一条）。
- **`AUTOSCRIPT_` 是保留前缀**：`ScriptEnvKeys.reject` 在保存时当场拒收并点名那个键，
  `:ui` 与落盘实现共用这一份判据（不抄两份）。理由是实证而非洁癖：
  `AUTOSCRIPT_BRIDGE_TOKEN` 是宿主签发的一次性桥凭据（`main.cpp` 用它做 hello/ACK 认证，
  认证后 `unsetenv`；JS 侧 `bootstrap.ts` 认证成功后也 `delete process.env.AUTOSCRIPT_BRIDGE_TOKEN`），
  用户覆盖它 = 伪造桥身份或自断桥。前缀判据是**字面大小写敏感**的（宿主键全大写，
  `autoscript_foo` 不撞任何宿主键，拦它属过度收窄）。

写入侧其余拒收面：空串 / 含 `=`（`KEY=VALUE` 的分隔符）/ 含 NUL 或换行（行格式靠 JSON 转义，
裸换行会撕坏 jsonl 的行边界）。值**不过**这些限制 —— 空串是合法值（与「没设」不同），
值经 JSON 编码往返逐字相等。

**不注入 npm 安装会话进程**：`HostNodeExecutor` spawn 的 npm CLI 是宿主工具进程、不是用户脚本，
零 spawn 门禁（§11）的注入面越窄越好 —— 这份表只进用户脚本的 `NodeProcessEngine` 路径。

### 8.2 引擎实例模型决议（批判决议）
- **不做**「单 Node 实例多 engine/多 Job」——共享 context 的 `process.exit()`、全局变量、模块副作用全部泄漏（批判 2 反面教材）；「模块作用域隔离」被明确定为**假隔离**。
- **不做**「v1 用 worker_threads 做并发引擎」——手机端行为未验证（nodejs-mobile #130），且一个 worker 群共享进程=共享隔离边界。列为 P3 **实验性**特性，入口显式标「实验」。
- **做**：进程池 + 每脚本一进程。并发上限=池容量；超载任务进入队列（清晰的产品化语义，而不是偷偷并发）。
- 执行中的 slot 在 `:main` 持 FGS/绑定，池进程按内存采样动态缩容（占位 slot 空闲超时回收）。

**记账不变量（`FixedEnginePool` 强制，违反即池缩水）**：
- 许可证（公平 `Semaphore`）与 FREE 槽位 **1:1**；夺槽必须在 `stateLock` 临界区内、且**先于** `engine.execute` —— 否则 execute 的启动耗时就是窗口期，并发 acquire 会选中同一槽位（同一进程跑两个脚本）。
- 有证无槽 = 记账失真，立即还证返回失败，绝不吞证转死锁。
- **每条终结路径都必须成对归还「槽位 + 许可证」**：正常 stop/release 走 `quiesce()` 后还证；启动失败与调用方取消走 `recycle`；**强杀（killRun）也必须收归** —— 这是踩过的坑：只 `kill()` 不还证，`free` 与可领许可证永久错位，池容量缩水，表现为「引擎再不接活」。
- `recycle(slot)` 在池侧原子完成「状态复位 + 代次前进 + 还证」，幂等不超发；`PoolSlot.generation` 让过期句柄 release 时如实判定已净，绝不拆新占用者（§7.4 代次纪律）。
- 满池时排队上限来自 `PoolAcquireRequest.waitTimeoutMillis`；**桥接路径上 `engines.exec` 的上限 = payload `waitTimeoutMillis` 优先，否则请求侧 TTL**（§7.4 每次跨进程操作必有 TTL）。TTL 若不递进池，满池只剩「无限等」一条路，调用方只能自己取消，无法诚实回 `ERR_TIMEOUT`。

### 8.3 生命周期状态机（每执行单元）—— 目标态 vs P0 已落地

```
          ┌────────────────────────────────────────────────────────┐
          ▼                                                        │
  ┌────────────┐  start →  ┌──────────┐  心跳失联×N/CPU 风暴/OOM   │
  │  PENDING    │─────────▶│ RUNNING  │───────────────────────────▶│
  └────────────┘           └────┬─────┘    (看门狗触发 kill→下一态)  │
                                │ pause             resume          │
                                │ ◀────────┐  SU────  ┌───────────┐ │
                                │          └─────────│ SUSPENDED  │─┤
                                │      (多源计数>0)   └───────────┘ │
            stop/reason/崩溃     ▼                                  │
                          ┌───────────┐  排空超时   ┌────────────┐  │
                          │ SINKING    │──────────▶│ QUIESCED    │─┘
                          └───────────┘            └────────────┘
  一切终态: TERMINATED(done|crashed|killed|timeout) → 归档 RunRecord
```

规则：
- **SUSPENDED 用多源计数**（UI 页面、FGS 需求、诊断暂停……各自 `acquire/release`），计数归零才回 RUNNING；不是布尔标志。
- **看门狗判定只看 RUNNING**；SUSPENDED 不回度量（dispatchLag 只在 RUNNING 采样，防误杀合法暂停）。
- `SINKING → QUIESCED` 有容忍窗口（grace，默认 5s，可配置）：排空 in-flight（generation 匹配才算有效），窗口到未排枯则斩杀。
- 任何路径都不可能「停在 RUNNING 无归宿」：RUNNING 必须挂一个心跳 deadline，超时即进 SINKING。
**实现注记已外迁**：P0 收敛子集（`EngineStateMachine`、`PoolSlot` 挂载理由、状态对照/裁决的 drift 连段与 `KillCause.DRIFT`、P0 不存在的 `SUSPENDED`/`PENDING`）逐字见 [`design-status.md` §8.3 实现注记](../design-status.md#实现注记自各分卷外迁逐字保留)。

### 8.4 看门狗（三路，防死循环/僵尸/饥饿）
- **心跳**（数据面，周期 ~500ms，携带自回事务序列号）：连失 K 次 → 重启判定；心跳与操作 TTL 互补——**await 的 RPC 有 TTL，整体执行有心跳**。
- **CPU 外带差分**（`:main` 独立线程读 `/proc/<pid>/stat` utime+stime 差分，**不依赖 Node 合作**）：单核持续 >95% 超过阈值（默认 30s，可配）→ 杀。防 `while(true)`/Promise 风暴这种「心跳还活着但永不放行」的形态。
- **内存**：RSS 超阈值（分级配置，池缩容信号）→ 降载警告，连续超阈 → kill + archive。
**实现注记已外迁**：`WatchdogPolicy` 三路纯判定、`ProcessMonitor` 采样口径、`EngineWatchdog` 调度循环、`HeartbeatLedger` 打点链与生产接线，逐字见 [`design-status.md` §8.4 实现注记](../design-status.md#实现注记自各分卷外迁逐字保留)。
- 看门狗**不作为业务**：只输出「恢复建议」（重启/重试/降级），不自动无人值守自愈（§1 诚实原则）。

- **心跳归属**：`engines.heartbeat` 的 payload.runId 必须等于连接绑定的 engineRunId；
  缺身份或替其他 run 打点回 `ERR_PERMISSION_DENIED` 且不刷新账本。合法身份对已不在途的 run 仍回 false。

### 8.5 崩溃恢复与幂等（checkpoint 意图日志）
- `:main` 的 scheduler 持久化 **意图日志（intent log）**：`RUN_START(projectId, entry, runNonce, scheduledAt) → …execute… → COMMIT(result)` append-only（SQLite，启动即回放）。

> **2026-10-08 批 78 修订**：本条的「生产实现 = jsonl + SQLite 双轨、打不开回落 jsonl」已作废 ——
> **生产只有 `SqliteIntentStore` 一条路**，SQLite 打不开即装配失败（fail closed），不回落。
> 理由与口径见 [`../design-decisions.md`](../design-decisions.md) 第 47 项①：jsonl 那份的 `runId`
> 分配（`max+1`）与「锁内先查后写」锚点**以单写者为前提**，回落会连带失去幂等锚点 ——
> 把「调度坏了」伪装成「调度还在」。**老设备 `intent-log.jsonl` 的一次性导入仍可用**（读的是
> 格式，纯 `:domain` parser，不依赖写入方）。**契约（append-only / 启动即回放 / nonce 幂等）不变。**
- **恢复只跟随 COMMIT**：进程/手机重启后，未 COMMIT 的 run 视为「未完成意向」→ 重新入队，但生成**新的 runId + 保留 runNonce**；执行体用 `runNonce` 做**幂等键**（外部副作用目标幂等，如「只发一次」的通知 id、datastore 原子键），杜绝重复业务副作用。
- **rerun 新 RunRecord**（每次重跑都是新 runId）——满足批判「resume=新 runId」语义；「断点续跑」只对纯内存任务可选，涉及副作用任务默认不允许自动续。

**归档入口（已落地契约，§8.5）**：两套 runId 是「一个真值的两个投影，必须成对写入」。

| 侧 | 身份字段 | 寄存器 |
|---|---|---|
| 意图日志（scheduler） | `intentRunId`（`IntentRun.runId`） | intent log |
| 引擎运行记录（engine） | `engineRunId`（`EngineRunReceipt.runId`） | `RunRecord(id)` |

`:domain` 的 `EngineRunLink(intentRunId, engineRunId)` 是关联契约；`RunArchive` SPI 是引擎侧档案（`put(record, link)` / `record` / `link` / `recordsOfIntent` / `recordsOfProject` / `unfinished`）。
纪律：终态（`SUCCEEDED/FAILED/CRASHED/CANCELLED`）append-only，**不可改写、不可复活**，违反必须响亮失败而不是静默吞。只写一侧 = 孤儿记录（「引擎在跑而任务中心查不到」或反之），
**实现注记已外迁**：`JournalFileStore`/`FileRunArchive` 的持久形态与两条孤儿结算路，逐字见 [`design-status.md` §8.5 实现注记](../design-status.md#实现注记自各分卷外迁逐字保留)。
`DispatchReport.link` 在门禁拒绝/排队超时/启动失败时如实为 null。接线在 `:app` 的 `ControllerRunDispatcher`（拿到 Receipt 后生成 link）+ `AppShell`（scheduler 持 `RunArchive`）。

**进程边界诊断（`RunRecord.exitCode` / `RunRecord.crashSummary`，backlog B11 落地）**：引擎宿主的排水线程不再对子进程 stderr「读即弃」——
`ProcessBuilderLauncher` **不做** `redirectErrorStream(true)`（合流会丢失「这是 stderr 写的」的分辨，而病因几乎全在 stderr），
stdout 那条通道**仍然只排空、不存内容**（它存在的唯一目的是防管道写满反压），stderr 在排空时顺带写入**有界环形尾 buffer**
（字节级、上限 `RunSummary.MAX_DETAIL` = 4096、满了丢最老、取快照时才整体 UTF-8 解码，故跨 read 的多字节字符不会撕裂）。
摘要经 `ScriptEngine.lastRunSummary(): RunSummary?`（`:domain` 契约，缺省实现回 null）取出，**只在 `status()` 判出「自然退出」时填充**
（含 exit 0 —— 摘要只是诊断读口，状态归类不看它）；**请求停止（143）与强杀（137）如实不填** —— 那两个退出码是终止手段的产物，不是病因。摘要的落点分两层：
`RuntimeController.Completed` 的失败类（`StopTimeout`/`Killed`/`UnknownRun`）在**槽位收走前**带上快照（收走后同槽可能已被下一次 execute 复用），
`:app` 的 dispatcher 把它**摊平**成 `RunOutcome.{Failed,Crashed}` 的 `exitCode`/`crashSummary` 两个裸字段
（scheduler 的架构门禁止依赖 `com.autoscript.domain.engine..`，故不折 `RunSummary` 整体），最终由 `Scheduler.recordLink` 折进 `RunRecord`。
`FileRunArchive` 的两个新字段是 **append 式新增**（parse 走 `optLong`/`optStr`）：旧 journal 行无需迁移即可 replay，缺字段如实为 null。
**本层不做去抖/首行忽略**：只做确定性截断（保留最尾 4 KiB）—— Node 启动噪声的治理等见了真数据再调。
**诚实边界**：这是「尾部摘要」不是「全量日志」；全量 stdout/stderr 的流式通道（桥 `console` namespace）是另一条面，两者互不替代。

### 8.6 调度系统
- 触发源五类：`定时(cron/alarm) `、`Intent/广播`、`事件(无障碍/通知)`、`用户点击`、`引擎内部 engines.exec`。
- **SchedulerProvider SPI**：同一接口后 P1 可切 `WorkManager` 之外的实现（保活场景自持 alarm + 注册 receiver）。触发→拉起引擎进程→注入 API→归日志。
- **守时语义诚实化**（批判 11 定案）：设备**亮屏 + 解锁**是保底契约；预热闹钟 `scheduledAt - 60s` 先拉起进程（引擎进程需时 ~1s），axexact 闹钟失败时降级到 setWindow 并在 UI 标注「可能偏差」。**熄屏任务**＝任务显式声明三态之一：`screen.on`(需 wakelock+确认)/`screen.any`/`screen.off`(禁 MediaProjection，只允许无障碍+网络)。
- 触发时若引擎池满 → 排队，绝无静默丢任务（日志+UI）。
**实现注记已外迁**：排队上限分级表、deadline 记账、调度侧停止与收口、执行侧急停、无人 await 的 run 期限、Android 触发侧（`AlarmSchedulerProvider`）与屏幕门禁、任务中心读口与操作面 —— 逐字见 [`design-status.md` §8.6 实现注记](../design-status.md#实现注记自各分卷外迁逐字保留)。
- **排队上限由投递方给**：`ControllerRunDispatcher` 的 `queueTimeoutMillis`；到期 → `RunOutcome.Cancelled`（"排队取消"口径：未获槽、未执行，link 为 null）。
### 8.7 保活与电源
- `:main` 持 **specialUse FGS**（`onCreate` 启动，`TYPE_SPECIAL_USE` 勾选 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE="automation"`，无超时）。
- 电池优化白名单、精确闹钟、开机启动、后台 Activity 启动豁免(**BAL**：仅允许 overlay 可见窗口路径/notification 触发路径)、自启动被 ROM 关闭——**全部入 PermissionCenter 三态门禁**（未授权=黄，被 ROM 杀=红且给跳转指引）。
- 长跑脚本自身需要**WakeLock** 时用 `power_manager`（引擎进程请求 → `:main` 对应 FGS 加唤醒锁的 acquire/release，配套超时自动释放）——**已落地（2026-09-24；2026-09-30 步骤 6d handler 与账本同批迁 `:platform:system`）**：`PowerManagerNamespaceHandler`（`acquire`/`release`/`status` 三方法，直驱同模块 `WakeLockLedger`，账本语义零改；
  keepalive 走 `:domain` `KeepAliveRenew` 窄缝）+ `AppShell.assemble` 的 `powerManagerHandler` **独立缝**（生产经 `PlatformWiring.powerManagerHandler(keeper)` 造）（与 datastore/zip/settings/notification/clipboard 同形，不入 `systemHandlers` 束）+ `AppShellApplication.installWithFiles` 现建喂缝 + `bridge/js` 的 `power.ts`（`acquire`/`release`/`status`）与 `power.test.cjs` 双侧契约。
**实现注记已外迁**：`:main` 侧 FGS + 真唤醒锁（`WakeLockOps`/`WakeLockLedger`、`ForegroundOps`/`ForegroundKeeper`、屏幕门禁收口、`ShellSummary.keepAliveActive`）逐字见 [`design-status.md` §8.7 实现注记](../design-status.md#实现注记自各分卷外迁逐字保留)。
  诚实口径三条：脚本锁必须限时（无期限只属框架 token）、token 服务端分配（脚本自带会互撞/互释）、取不到锁回 `ERR_SERVICE_DISABLED` 且未记账（门禁据此拒绝 `SCREEN_ON`）；直驱账本不走 `ForegroundKeeper.start(token)`（那个单槽只属框架，调两次互踩 `frameworkToken`）。

### 8.8 屏幕语义（截图直连 §9.2）
`ERR_SCREEN_LOCKED`/`ERR_BLACK_FRAME` 显式化：MediaProjection 在 keyguard 下黑帧、a11y 在锁屏无可用窗口 → 引擎收到的是**分类错误而非黑图**，脚本可 try/catch 策略分支。

---

