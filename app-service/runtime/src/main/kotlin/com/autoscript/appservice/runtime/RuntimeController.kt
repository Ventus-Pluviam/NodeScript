package com.autoscript.appservice.runtime

import com.autoscript.domain.bridge.AuthenticatedRunContext
import com.autoscript.domain.bridge.HandleRef
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.engine.EngineStatus
import com.autoscript.domain.engine.KillCause
import com.autoscript.domain.engine.RunSummary
import com.autoscript.domain.engine.StopResult
import com.autoscript.domain.permission.BridgeCapability
import com.autoscript.domain.permission.ScriptAuthorizationPolicy
import com.autoscript.domain.permission.ScriptAuthorizationSnapshot
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 执行仲裁者（docs §8 / §4.1 kill 权威）：
 * 池 + 看门狗的唯一装配点，JS `engines.*` 面的 Kotlin 对偶。
 *
 * 职责：
 * - **[start]/[stop]/[killAll] 经 [guard] 串行化**——串的是**在途表**（谁活着、
 *   谁被摘走），**不是池的容量记账**：容量记账由池自己扛（批 96 摘掉「整段持锁」的病灶、
 *   批 103 换成空闲队列为唯一真源 + [PoolLease] 独占归还），本类不再因「排队等槽」而
 *   阻塞 stop/killAll/看门狗（那正是外审 B1 测到的 2927ms）；
 * - 以 runId 为键维护在途表：`stop(未知 runId)` 如实回 [StopOutcome.AlreadyGone]，
 *   绝不静默吞掉（JS `engines.stop(runId)` 语义）；
 * - kill 权威：[killRun]/[killAll] 是唯一强杀入口（看门狗只给裁决，见 [judge]）；
 * - [judge] 纯委托 [WatchdogPolicy]（只判 RUNNING 等语义见该类）；
 * - [watchAnchors] 供 [EngineWatchdog] 取「runId → 本次执行的引擎 pid」：pid 的事实来源是
 *   [com.autoscript.domain.engine.EngineRunReceipt.pid]（随启动 receipt 一并给出，不可得为
 *   null），调度循环自己不另建一张 pid 表 —— 在途表就在本类里，再抄一份必然错位；
 * - [heartbeat] 收引擎进程打来的心跳（§8.4 缺口②的宿主侧收单方），[heartbeatMillis]
 *   供看门狗问「距上次心跳多久」。心跳账本与在途账同生共死：run 终结即 [HeartbeatLedger.forget]；
 * - [statusOf] 同时读宿主自报状态与池侧状态机投影并**比对**（§8.3 最后一项：两者尚未互相校准时
 *   由本类如实报出分歧，而不是假装一致）。
 */
class RuntimeController(
    private val pool: EnginePool,
    private val watchdog: WatchdogPolicy = WatchdogPolicy(),
    /**
     * 心跳账本（§8.4「引擎进程打点、宿主记账」）。可注入：装配层与单测各持一份
     * （Application 域一个进程一个，替身进程各自隔离）。默认新建生产实例。
     */
    private val heartbeats: HeartbeatLedger = HeartbeatLedger(),
    /**
     * 墙钟缝（越界判定的时间来源）：生产用系统钟，测试给定时钟即可逐毫秒复现
     * 「心跳失联 → 再等满窗口 → 才杀」的三段节奏，不必真等 1.5s + 5s。
     */
    private val clock: () -> Long = { System.currentTimeMillis() },
    /**
     * 跨脚本目标授权（A5，§11）。持有它是为了让「动别的执行」这条判据只有一处：
     * `engines` handler 与调度投递都经本类的 [authorizeTarget]/[start] 过闸，
     * 不各自记得再判一次（判据分散 = 迟早漏一处）。
     */
    private val crossScript: CrossScriptAuthorizer = CrossScriptAuthorizer(),
    /**
     * 来源授权策略（A5，§11）：**与身份签发方同一份实例**（`AppShell.assemble` 只建一个）。
     *
     * 为什么必须同源：`engines.exec` 的「派生不得提权」判据要算**子执行的掩码**，
     * 而子掩码的真值来自签发时那份策略。两处各建一个 = 两套矩阵，改了其一静默不一致，
     * 现场表现为「exec 放行的组合，子进程起来却被自己的掩码拒」。
     */
    private val authorization: ScriptAuthorizationPolicy = ScriptAuthorizationPolicy(),
    /**
     * **run 终结时的连接资源收口口**（A11，§9.2）：按 runId 撤销那条 run 的桥连接上挂着的
     * 进程级资源（投屏会话）。缺省 = **不做**（`{ _ -> }`），此时本类**只**清自己的账
     * （在途表/心跳），run 终结不影响任何连接资源 —— 那是接入端那条连接自己的生命周期。
     *
     * **为什么需要它（A11 的原始病灶）**：接入端的收口挂在**连接**上（abort / dispose / EOF），
     * 而脚本若把桥 socket fd 继承给子进程，脚本主进程死了**不产生 EOF**，连接不结束，
     * 于是投屏会话没有撤销点 —— 通知栏一条"正在投屏"却没有任何脚本在跑。补上 runId 侧的
     * 撤销入口后，run 终结**不再依赖 socket 断**。
     *
     * **为什么判据住在接入端**：连接号在那里发出，本类自始至终只认 runId。缝是无类型的
     * `(Long) -> Unit`，实现是 `NewlineFrameServer::revokeRunResources`。
     *
     * **幂等、不抛**是契约要求：收口回调由设备层写（`VirtualDisplay.release` 之类），
     * 漏掉或抛异常都只影响那一条资源。这里是**执行终结路径**，绝不让它打断引擎收尾。
     */
    private val revokeRunResources: (Long) -> Unit = {},
    /**
     * **run 启动时的落盘口**（2026-10-13 执行日志持久化）：`start()` 的 Granted 分支、
     * `startedAt` 记账之后调用一次。实现是 `ScriptLogStore::begin`（`:app` 的装配缝）。
     *
     * 与 [revokeRunResources] 对偶：那边是"终结时收"，这边是"开始时记" ——
     * 两条都在 [guard] 临界区内与在途表同生共死。同样**幂等、不抛**：落盘是诊断面，
     * 绝不掀翻启动路径（`ScriptLogStore` 自己也 runCatching，这里不再包一层）。
     */
    private val onRunStarted: (runId: Long, projectId: String, scriptPath: String, startedAtMillis: Long) -> Unit =
        { _, _, _, _ -> },
) {
    private val guard = Mutex()
    private val active = HashMap<Long, PoolHandle>()

    /**
     * 在途 run 的启动时刻（runId → 启动墙钟；仅 [EngineWatchdog] 的越界判定读它）。
     *
     * 为什么不放进 [PoolHandle]：那是池内身份（请求/槽位/收据/代次），启动时刻是
     * **看门狗这一路**的记账，且 [watchAnchors] 的快照形态已经要带上它 —— 放这里
     * 与在途表同生共死（[settleDone]/[killRun]/[killAll]/[forceStopAll] 都清在途表）。
     */
    private val startedAt = HashMap<Long, Long>()

    /**
     * 已 OS 冻结（SIGSTOP）的在途 run（2026-10-13 执行暂停；[guard] 下读写）。
     *
     * **为什么是旁账集合而不是状态**：`EngineStatus` 没有 SUSPENDED（P0 不存在，
     * 见 implementation-notes L533）—— 引擎 `status()` 暂停中仍报 RUNNING，
     * 池侧投影也是 RUNNING。暂停的全部特殊处理（看门狗锚点过滤、宽限重置、
     * 期限顺延、await 冻结）都以"runId 在不在这个集合"为判据，状态机一字不动。
     */
    private val paused = HashSet<Long>()

    /**
     * 冻住一次在途执行（悬浮窗「暂停执行」；`HostSummary.pauseRun` 的落点）。
     *
     * 在途才问引擎：`engine.pause()` true → 进 [paused] 集。引擎 false
     * （替身/离线/pid 拿不到/`kill` 缺席）→ 不进集，如实回 false ——
     * "冻不住"与"已冻住"在账上必须是两件事，否则看门狗会把一个没冻住的 run
     * 当暂停 run 排除掉（那就是 1.5s 必杀的反方向：该杀的不杀）。
     */
    suspend fun pauseRun(runId: Long): Boolean {
        val handle = guard.withLock { active[runId] } ?: return false
        val frozen = try {
            handle.slot.engine.pause()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return false
        }
        if (!frozen) return false
        guard.withLock { paused.add(runId) }
        return true
    }

    /**
     * 解冻 [pauseRun] 冻住的执行。
     *
     * 成功时**刷新两处时间账**（暂停多长的两条误杀陷阱都在这里收）：
     * - `startedAt[runId] = clock()`：看门狗 noHeartbeat 宽限（`startedAt + 5s`）与
     *   WATCHDOG 期限线（`startedAt + scriptTimeout`）都以它现算 —— 刷新等于把整个
     *   暂停时长顺延出去，否则恢复瞬间宽限已过期/期限已到，当场被杀；
     * - `heartbeats.rebase(runId)`：暂停期间首跳已"陈旧"（`sinceLastBeat` 停在冻住那一刻），
     *   不 rebase 则恢复后第一轮就被判心跳失联。
     * 非在途/引擎解冻失败 → false（集里那笔不动：冻住的仍是冻住的）。
     */
    suspend fun resumeRun(runId: Long): Boolean {
        val handle = guard.withLock { active[runId] } ?: return false
        val thawed = try {
            handle.slot.engine.resume()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return false
        }
        if (!thawed) return false
        guard.withLock {
            paused.remove(runId)
            startedAt[runId] = clock()
        }
        heartbeats.rebase(runId)
        return true
    }

    /**
     * 往一次**在途**执行的脚本进程 stdin 送一行（悬浮窗底部输入框 → 脚本的
     * `process.stdin`；2026-10-16 用户令；`HostSummary.sendInput` 的落点）。
     *
     * 与 [pauseRun] 同一条"在途表精确命中"口径：不在途（已结算/从未存在）→ false，
     * 不去猜它是谁 —— 窗里的 `boundRunId` 结算后仍留着（结算不解绑），那时用户
     * 敲进输入框的一行没有去处，如实回 false 比写进一个已死的管道好。
     *
     * **暂停中也照送**：SIGSTOP 冻住的是 CPU，管道的**内核缓冲**照常收字节，
     * 脚本解冻后 `read` 就能拿到 —— 用户"暂停时先把要输的先敲好"是合理用法，
     * 这里不额外拦（拦了反而要在 UI 上解释"为什么暂停时输入框是灰的"）。
     */
    suspend fun sendInput(runId: Long, line: String): Boolean {
        val handle = guard.withLock { active[runId] } ?: return false
        return try {
            handle.slot.engine.sendInput(line)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 急停代次（[killAll]/[forceStopAll] 推进；读写都在 [guard] 临界区内）。
     *
     * 为什么要它：本类是池的**唯一**调用方，而池侧「排队等证」与「execute 启动中」两个窗口
     * 都可能与急停并发（见 [start] 的 KDoc）。代次是「这条 start 跨过了急停」的唯一判据 ——
     * 池侧 [EnginePool.killAll] 只认槽位，在途表只认 runId，两者之间需要这个锚。
     */
    private var stopEpoch: Long = 0L

    /** 启动结果（对偶 JS PoolAcquireOutcome：granted/timedOut/failed）。 */
    sealed interface StartOutcome {
        data class Started(val runId: Long, val handle: HandleRef) : StartOutcome
        data object QueueTimeout : StartOutcome
        data class StartFailed(val message: String) : StartOutcome
    }

    /** 停止结果（对偶 ScriptEngine.stop + 句柄已失踪的诚实上报）。 */
    sealed interface StopOutcome {
        data object StoppedClean : StopOutcome
        data class StoppedTimeout(val partial: Boolean) : StopOutcome
        data object AlreadyGone : StopOutcome
    }

    /**
     * 取槽启动；满则按 [PoolAcquireRequest.waitTimeoutMillis] 排队（null = 无限等）。
     *
     * **等证不持 [guard]**（2026-10-11 批 96，外审 B1）：`pool.acquire` 在满池时会挂到
     * `waitTimeoutMillis`（调度链路 60–120s）。原实现整段 `guard.withLock`，于是「A 在跑、
     * B 排队」时 A 的 [stop]/[killAll]/[forceStopAll] 与看门狗取锚全被 B 的排队挡住 ——
     * 实测容量 1 下 `stop(A)` 耗时 2927ms（= B 的排队超时），现场表现为「脚本停不下来、
     * 急停按不动」。现在只在**记账那一刻**取锁（夺槽本身由池的 stateLock 保证原子），
     * 并在这里补一次「急停代次」对表：等证期间发生过 [killAll]/[forceStopAll] 就作废本条
     * （池侧 [EnginePool.killAll] 已把当时非 FREE 的槽强释，这条的执行体不属于任何人）。
     */
    suspend fun start(request: PoolAcquireRequest): StartOutcome {
        // 授权决策**一次算死**（A5 整改第 4 条）：调用方没给快照时在这里补一份，随后
        // 池请求 → 引擎请求 → 身份签发 → 连接上下文搬的都是这**同一份**，下游不重算 ——
        // 否则会出现「预检校验的是 A 掩码、签发出去的是 B 掩码」的窗口。
        // 这一段是纯计算（读策略矩阵），本就不需要持 guard。
        val effective = request.authorization?.let { request }
            ?: request.copy(authorization = authorization.decide(request.projectId).snapshot())
        val epochAtEntry = guard.withLock { stopEpoch }
        return when (val outcome = pool.acquire(effective)) {
            is PoolAcquireOutcome.Granted -> guard.withLock {
                if (stopEpoch != epochAtEntry) {
                    // 等证/启动期间急停过：这条执行体已在池侧被强杀，不得进在途表
                    // （进了就等于把一条已死的 run 记成在途，stop/killRun 都当它活着）。
                    StartOutcome.StartFailed(ABORTED_BY_STOP)
                } else {
                    val receipt = outcome.handle.receipt
                    active[receipt.runId] = outcome.handle
                    val started = clock()
                    startedAt[receipt.runId] = started
                    // 落盘口（2026-10-13）：index 先行 —— 日志行随后经
                    // ConsoleCollector.lineSink 落 `<runId>.log`，详情页靠它找文件。
                    onRunStarted(receipt.runId, outcome.handle.request.projectId, outcome.handle.request.scriptPath, started)
                    StartOutcome.Started(receipt.runId, receipt.handle)
                }
            }
            PoolAcquireOutcome.TimedOut -> StartOutcome.QueueTimeout
            is PoolAcquireOutcome.Failed -> StartOutcome.StartFailed(outcome.message)
        }
    }

    /**
     * 目标授权（A5，§11）：调用方 [caller] 要操作 [targetRunId]。
     *
     * 委托 [CrossScriptAuthorizer]（纯函数）；**目标在不在途**与**目标授权快照**在同一把
     * [guard] 下一次取出 —— 分两次取会在中间被 stop/kill 摘走，出现「目标在途表里、
     * 快照却读到 null」的错配。「不在途 → `ERR_NOT_FOUND`」这条判据因此**在这里**做
     * （在途表就在本类手里，纯函数不该拿到整张表只为判一次包含关系）；
     * [CrossScriptAuthorizer] 只回答「在途的前提下，这次触达允不允许」。
     */
    suspend fun authorizeTarget(
        caller: AuthenticatedRunContext,
        targetRunId: Long,
        required: BridgeCapability,
    ) = guard.withLock {
        val target = active[targetRunId]
            ?: throw AutojsException(ErrorCode.ERR_NOT_FOUND, "未知 runId: $targetRunId")
        crossScript.authorizeTarget(
            caller = caller,
            targetRunId = targetRunId,
            required = required,
            target = target.request.authorization,
        )
    }

    /**
     * 派生授权（A5，§11）：调用方 [caller] 要拉起 [projectId] 的新执行。
     *
     * 子掩码由**与签发同源**的 [authorization] 现算，并**返回这份快照** —— 调用方
     * （`engines` handler）必须把它原样放进 [PoolAcquireRequest.authorization]，这样
     * 「派生不得提权」判据校验的掩码与真正签发出去的掩码是同一份（A5 整改第 4 条）。
     * 未授权时抛 `ERR_PERMISSION_DENIED`，调用方（handler）转成桥错误码。
     *
     * 宿主发起（[caller] == null）时不做调用方判据（宿主是链的根），仍**走同一份**
     * [authorization] 算掩码 —— 所以它同时也是「宿主这条路的掩码从哪来」的唯一读口，
     * 不需要再开一个只算不算判的孪生方法（那会多一个只服务测试的公开 API）。
     */
    fun authorizeStart(caller: AuthenticatedRunContext?, projectId: String): ScriptAuthorizationSnapshot {
        val child = authorization.decide(projectId)
        crossScript.authorizeStart(caller, child.mask)
        return child.snapshot()
    }

    /** 优雅停止一次执行（四步 quiesce 由池/槽位驱动，TimedOut 已 kill 兜底）。 */
    suspend fun stop(runId: Long): StopOutcome {
        val handle = guard.withLock {
            startedAt.remove(runId)
            // 过账：暂停中被停 —— 引擎侧 `stop()` 入口也会 auto-resume，这里清集是
            // 为了让「它曾被暂停过」这个事实随 run 终结一起消失（集只装在途）。
            paused.remove(runId)
            // 必须放在最后：`withLock` 的值就是本块最后一个表达式 —— 放到 `paused.remove`
            // 之后会把 `Boolean` 当成 `PoolHandle` 交出去（2026-10-13 CI 抓到）。
            active.remove(runId)
        } ?: return StopOutcome.AlreadyGone
        heartbeats.forget(runId)
        // A11：stop 也是 run 终结 —— 同一条收口（与 kill/自然退出同口径）。
        revokeRunResources(runId)
        // 自停会撤销当前桥连接并取消调用协程；已摘走的 handle 必须完成回池。
        return withContext(NonCancellable) {
            when (val result = pool.release(handle)) {
                StopResult.Clean -> StopOutcome.StoppedClean
                is StopResult.TimedOut -> StopOutcome.StoppedTimeout(result.partial)
            }
        }
    }

    /**
     * 强杀一次执行（看门狗裁决的落点；kill 权威 §4.1）。
     *
     * **终结路径必须收归槽位**：本方法既是 kill 入口也是这次 run 的结束点
     * （[awaitCompletion] 的 CRASHED/ settleKilled 同口径），所以 kill 之后必须
     * 把槽位与许可证一并还给池 —— 否则 `free` 与可领许可证永久错位，池容量缩水
     * （后续 start 一律排队到超时，表现为「引擎再不接活」）。
     * 收归走 [EnginePool.recycle]（池侧原子完成「槽位复位 + 还证」，幂等不超发），
     * 与 [killAll] 的强释路径区分：killAll 是广播式全清，不逐个 recycle。
     * 不用 [EnginePool.release]：那会先走四步 quiesce（stop）再 kill 兜底 ——
     * 对一个已经决定强杀的执行体是多余的一次「礼貌请求」，且 stop 的语义是
     * 优雅排空，与 kill 权威语义相反。
     *
     * @return null = 该 runId 不在途（已被 stop/killAll 收走），调用方不得视为成功 kill。
     */
    suspend fun killRun(runId: Long, cause: KillCause): KillCause? {
        val handle = guard.withLock {
            startedAt.remove(runId)
            // 与 [stop] 同一条过账：暂停中被强杀同理清集（`engine.kill` 的 SIGKILL
            // 对 frozen 进程有效，不需要先解冻 —— 那是 SIGTERM 才有的问题）。
            paused.remove(runId)
            // 必须是最后一个表达式（`withLock` 的值 = 本块末值）。
            active.remove(runId)
        } ?: return null
        heartbeats.forget(runId)
        revokeRunResources(runId)
        return withContext(NonCancellable) {
            val killed = handle.slot.engine.kill()
            // 强杀即终结：槽位必须归还（§8.2 记账）；cause 透传给状态机归类
            // （§8.3：REQUESTED→STOPPED，watchdog/OOM→CRASHED）。
            // 归还经 [PoolHandle.lease]（批 103 P2：空闲队列是唯一真源，lease 的 claim
            // 独占归还权）—— 与 [stop] 走的是**同一条归还路径**，故
            // 「killRun 与迟到的 stop 同时到达」只会归还一次。
            handle.lease.forceReturn(cause)
            killed
        }
    }

    /** 全部强杀（killAll 与 start/stop 串行，防许可证超发）。 */
    suspend fun killAll(reason: KillCause) = guard.withLock {
        stopEpoch += 1
        val gone = active.keys.toList()
        active.clear()
        startedAt.clear()
        // 暂停账与在途表同生共死：全清之后没有"曾暂停"的 run（与 startedAt 同口径）。
        paused.clear()
        gone.forEach { heartbeats.forget(it) }
        gone.forEach { revokeRunResources(it) }
        withContext(NonCancellable) { pool.killAll(reason) }
    }

    /**
     * 收口的**强制兜底**（docs §13 铁律 4 的执行侧部分）：绕开请求语义，直接强杀全部非 FREE
     * 槽位并复用。
     *
     * 与 [killAll] 的分工：[killAll] 是请求驱动的停止语义（看门狗裁决/用户停全部的落点，
     * 在途表经 guard 串行收走）；本方法是**进程级急停**（应用被杀/系统回收/测试收口）——
     * 不重建 guard 语义，只做「杀全部 + 清在途表 + 忘心跳」，事件面也不发 stopAll 那条流
     * （调用方按急停路径记账，不污染正常停止的审计）。
     */
    suspend fun forceStopAll(cause: KillCause) = guard.withLock {
        stopEpoch += 1
        val gone = active.keys.toList()
        active.clear()
        startedAt.clear()
        paused.clear()
        gone.forEach { heartbeats.forget(it) }
        // A11：急停也是 run 终结 —— 与 killAll 同一收口口径（进程被杀/测试收口那条路上，
        // 宿主随后多半还会 close 整个壳，但那时连接已被强拆，收口已经晚了）。
        gone.forEach { revokeRunResources(it) }
        withContext(NonCancellable) { pool.killAll(cause) }
    }

    /** 看门狗裁决（纯判断，不执行；执行走 [killRun]/[killAll]）。 */
    fun judge(sample: WatchdogSample, history: List<WatchdogSample> = emptyList()): WatchdogVerdict =
        watchdog.evaluate(sample, history)

    /**
     * 本控制器持有的裁决口径（装配层给 [EngineWatchdog] 用，保证**只有一份**阈值）。
     *
     * 为什么必须从这里取：裁决是 controller 落的（[judge] → [watchdog]），若装配层另 new 一个
     * [WatchdogPolicy]，两处的阈值就是两份事实 —— 改了其一，另一个静默不一致，现场表现为
     * "看门狗按 95% 判、循环按别的节奏跑"，极难查。
     */
    fun watchdogPolicy(): WatchdogPolicy = watchdog

    /** 心跳账本（[HeartbeatLedger]）：装配层经它把宿主持有的账本喂给看门狗。 */
    fun heartbeats(): HeartbeatLedger = heartbeats

    /**
     * 收一次心跳（§8.4 缺口②的宿主侧入口；JS `engines.heartbeat` 的落点）。
     *
     * 幂等由序号保证（[HeartbeatLedger.beat]）：同 seq 或更旧不回刷时间戳，
     * 所以重发/乱序帧不会把一个死掉的 run 假装成活的。
     *
     * 不在途 runId（已结算/从未存在）→ false 且**不记账**：已终结的 run 不得再收心跳，
     * 否则无主条目会堆积并把活 run 的账顶出记账上限（[HeartbeatLedger.maxRuns] 淘汰
     * 最旧条目），且调用方会把"已结算"误读成"一次有效心跳"。
     *
     * @return false = 不在途 / 该 seq 过期或重复（未被采纳）；true = 已刷新时间戳。
     */
    suspend fun heartbeat(runId: Long, seq: Long): Boolean {
        val live = guard.withLock { active.containsKey(runId) }
        if (!live) return false
        return heartbeats.beat(runId, seq)
    }

    /** 距上次心跳毫秒（从未打过点 → null，看门狗据此记 [EngineWatchdog.Tick.noHeartbeat]）。 */
    fun heartbeatMillis(runId: Long): Long? = heartbeats.sinceLastBeat(runId)

    fun stats(): PoolStats = pool.stats()

    /**
     * 看门狗锚点：在途 runId → 该次执行的引擎 pid 与**期限归属**（docs §8.4 调度循环的取数口）。
     *
     * pid 取 [com.autoscript.domain.engine.EngineRunReceipt.pid]，即**这次 run** 的 pid，
     * 而非宿主 [com.autoscript.domain.engine.ScriptEngine.pid] 的"当前值"——同一槽位换过
     * 执行体后两者不同。宿主不给 pid（实现未接线）时如实给 null：调用方按"无法度量"处理。
     *
     * [WatchAnchor.deadlineMillis] 同理取**这次请求**声明的那一份（[PoolAcquireRequest.timeoutEnforcer]
     * 为 [TimeoutEnforcer.WATCHDOG] 时 = `startedAt + scriptTimeoutMillis`）：看门狗据此落
     * `KillCause.TIMEOUT`。归属为 [TimeoutEnforcer.AWAITER] 时为 null —— 那条路由发起方自己
     * await 并超时强杀（§8.6 dispatcher），看门狗**不得**越过调用方去替它收账。
     */
    suspend fun watchAnchors(): List<WatchAnchor> = guard.withLock {
        // 暂停的 run 不进锚点（2026-10-13）：冻住的进程心跳必停，`WatchdogPolicy`
        // 1.5s 即判 WATCHDOG_HEARTBEAT —— 不排除等于"暂停 1.5 秒就被杀"。这是
        // 08-execution.md L140「看门狗判定只看 RUNNING」在本账上的落点：
        // 暂停的 run 此刻不是"可被判定"的 RUNNING，故不在取数口出现。
        active.values.filter { it.receipt.runId !in paused }.map {
            val started = startedAt[it.receipt.runId] ?: clock()
            WatchAnchor(it.receipt.runId, it.receipt.pid, started, deadlineOf(it.request, started))
        }
    }

    /**
     * 本次执行的期限线（null = 不归看门狗管，见 [watchAnchors]）。
     *
     * 纯函数：只由"这次请求的归属声明 + 启动时刻"算出，不读任何易变状态 ——
     * 期限一旦定下就不再改（中途改期限 = 同一 run 两套到期口径）。
     */
    private fun deadlineOf(request: PoolAcquireRequest, startedAtMillis: Long): Long? {
        if (request.timeoutEnforcer != TimeoutEnforcer.WATCHDOG) return null
        val declared = request.scriptTimeoutMillis ?: return null   // 构造期已 require，防御性兜底
        return startedAtMillis + declared
    }

    /**
     * 在途 runId 快照（诊断/UI 用；跨脚本授权的目标判据走 [authorizeTarget]，不经这里 ——
     * 那条路要与目标快照同锁取出，不能先取一张可能过期的表）。
     *
     * **必须持 [guard]**：`active` 是普通 [HashMap]，无锁读 `keys` 在并发 start/stop 下
     * 是数据竞争（A5 整改第 3 条）。挂起函数是因为要拿挂起锁。
     */
    suspend fun activeRunIds(): Set<Long> = guard.withLock { active.keys.toSet() }

    /**
     * 完成等待（docs §8.2 执行语义：JS `engines.exec` 句柄的 `onExit` 订阅 ——
     * 脚本退出/崩溃是事件，不是 start 的返回值）。
     *
     * 语义：
     * - 正常 stop（优雅退出）→ [Completed.StoppedClean]；
     * - 软停超时（四步 quiesce 未净，已 kill 兜底）→ [Completed.StopTimeout]；
     * - 看门狗/killAll 强杀 → [Completed.Killed]；
     * - 未知 runId（已结算/从未存在）→ [Completed.UnknownRun]；
     * - [timeoutMillis] 内未完成 → [Completed.TimedOut]（返回，不抛；调用方决定 kill/续等）。
     *
     * 本类只做状态机结算：完成/强杀两种终态都收走槽位并归档在途表；
     * kill 仍只经 [killRun]/[killAll]（kill 权威 §4.1）。
     */
    sealed interface Completed {
        data object StoppedClean : Completed   // 优雅退出
        /** 软停超时（已 kill 兜底）——失败类：带 settle 时点从引擎侧取回的进程摘要（backlog B11）。 */
        data class StopTimeout(val summary: RunSummary? = null) : Completed
        /** 看门狗/killAll 强杀、以及自然 CRASHED 结算——失败类：带进程摘要（同 [StopTimeout]）。 */
        data class Killed(val summary: RunSummary? = null) : Completed
        /** 未知 runId（已结算/从未存在）——失败类：无句柄可问，摘要如实 null。 */
        data class UnknownRun(val summary: RunSummary? = null) : Completed
        data object TimedOut : Completed       // 等待超时（仍在途，调用方决定后续）
    }

    /**
     * 等待一次执行终结。轮询口径：仅 RUNNING 判活（[isWatchable]；SUSPENDED
     * 不存在于本状态机）。引擎侧 [ScriptEngine.status] 由宿主实现喂实；
     * FakeEngine 语义下 stop 后即 STOPPED，可直接结算。
     */
    suspend fun awaitCompletion(runId: Long, timeoutMillis: Long = 30_000): Completed {
        val handle = guard.withLock { active[runId] } ?: return Completed.UnknownRun(null)
        var deadline = System.currentTimeMillis() + timeoutMillis
        while (true) {
            val status = try {
                handle.slot.engine.status()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return settleKilled(runId)
            }
            // CRASHED 是终态（引擎已死但 kill 权威尚未收走）：按 Killed 结算并释放槽位。
            // 注意顺序：isWatchable 仅 RUNNING 为真，CRASHED 须先判（否则被 settleDone 吞掉）。
            if (status == EngineStatus.CRASHED) {
                return settleKilled(runId)
            }
            if (!isWatchable(status)) {
                return settleDone(runId)
            }
            // 暂停期不计时（2026-10-13）：冻住的 run 的 status 仍是 RUNNING（引擎不报
            // SUSPENDED），不冻结 deadline 的话，长暂停会被 dispatcher 以 REQUESTED 强杀 ——
            // 用户按了暂停，宿主却替它收了尸。注意这里用 [guard] 快照判，不持锁过 delay。
            if (guard.withLock { runId in paused }) {
                deadline = System.currentTimeMillis() + timeoutMillis
            } else if (System.currentTimeMillis() >= deadline) {
                return Completed.TimedOut
            }
            delay(POLL_INTERVAL_MILLIS)
        }
    }

    /** runId → 引擎侧状态快照的轻量查询（UI/任务中心轮询用，不在途 → null）。 */
    suspend fun probeStatus(runId: Long): EngineStatus? {
        val handle = guard.withLock { active[runId] } ?: return null
        return try {
            handle.slot.engine.status()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 宿主自报状态 + 池侧状态机投影的**对照**（§8.3 最后一项落地的第一步：不静默，只如实报分歧）。
     *
     * 为什么要这个：池侧状态机（[PoolSlot.status]）是记账投影，宿主 [ScriptEngine.status] 才是
     * 执行真相。真实现接上前两者必然有窗口期差距（宿主还报 RUNNING、池已回 IDLE），
     * 此前的缺口正是「不会响亮失败」。本方法不擅自修任何一侧，只把差异交出来 ——
     * 由看门狗/UI/日志决定怎么处理，调用方看得见才算校准。
     *
     * @return null = 该 runId 不在途（已结算/从未存在），无状态可校准。
     */
    suspend fun statusOf(runId: Long): RunStatus? {
        val handle = guard.withLock { active[runId] } ?: return null
        val host = try {
            handle.slot.engine.status()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        val pool = handle.slot.status()
        val drift = host != null && !agrees(host, pool)
        // 暂停判据不同锁读两次（active 与 paused 各自持锁取）：中间被 resume/stop 摘走，
        // 最坏是"暂停标记晚一轮" —— 现值每次现取的面，晚一轮不是错（与 host/pool 的
        // 窗口期差距同一性质，不判 drift）。
        return RunStatus(
            runId = runId,
            host = host,
            pool = pool,
            drift = drift,
            projectId = handle.request.projectId,
            scriptPath = handle.request.scriptPath,
            paused = guard.withLock { runId in paused },
        )
    }

    /** 在途 run 的全量状态对照（诊断/看门狗用；不在途 → 空）。 */
    suspend fun runStatuses(): List<RunStatus> = guard.withLock {
        active.keys.toList()
    }.mapNotNull { statusOf(it) }

    /** 一次执行的两个状态来源（§8.3 校准）。 */
    data class RunStatus(
        val runId: Long,
        /** 宿主自报（[ScriptEngine.status]）；读不到 → null（引擎已死/实现未接线）。 */
        val host: EngineStatus?,
        /** 池侧状态机投影（[PoolSlot.status]）。 */
        val pool: EngineStatus,
        /** 两侧是否**不一致**（host 读不到时不判分歧：那可能是宿主已死，见 [statusOf] KDoc）。 */
        val drift: Boolean,
        /**
         * 本次执行的归属（2026-10-13：悬浮窗标题与日志详情的"这是谁的输出"就靠它 ——
         * `PoolHandle.request` 里本来就有，之前只是没带出来。现值每次现取）。
         */
        val projectId: String,
        /** 同 [projectId]：项目内相对路径（如 `data/test.js`）。 */
        val scriptPath: String,
        /** 是否被 [pauseRun] 冻住（2026-10-13：暂停按钮的事实源，不另开读口）。 */
        val paused: Boolean,
    )

    /**
     * 池侧投影与宿主自报何时算一致。
     *
     * 合法组合（其余视为分歧）：
     * - 池 BOOTING ↔ 宿主 IDLE/BOOTING：execute 尚未返回，池先跳；
     * - 池 RUNNING ↔ 宿主 RUNNING：稳态；
     * - 池 QUIESCING ↔ 宿主 QUIESCING/STOPPED：四步排空，宿主可能已先净；
     * - 池 IDLE ↔ 宿主任意：**不算分歧**（已回收，宿主此刻说什么都不该算异常）；
     * - host 为 null → 由 [RunStatus.drift] 单独表达，不进本函数。
     */
    private fun agrees(host: EngineStatus, pool: EngineStatus): Boolean = when (pool) {
        EngineStatus.IDLE -> true
        EngineStatus.BOOTING -> host == EngineStatus.BOOTING || host == EngineStatus.IDLE
        EngineStatus.RUNNING -> host == EngineStatus.RUNNING
        EngineStatus.QUIESCING -> host == EngineStatus.QUIESCING || host == EngineStatus.STOPPED
        EngineStatus.STOPPED, EngineStatus.CRASHED -> true
    }

    /**
     * 在途执行的看门狗锚点（[watchAnchors] 的元素）。
     *
     * [startedAtMillis] 是**这次执行**的启动墙钟：看门狗靠它区分「还没到首跳」与
     * 「打过点又断了」（见 [EngineWatchdog] 的启动宽限口径）。
     *
     * [deadlineMillis] 是**这次执行的墙钟期限**（= 启动时刻 + 声明超时），只在
     * [PoolAcquireRequest.timeoutEnforcer] 为 [TimeoutEnforcer.WATCHDOG] 时非空：
     * 那条路没人 await 终结，期限线是它唯一的收尾人。归属 [TimeoutEnforcer.AWAITER]
     * 时为 null（发起方自己收账），看门狗据此**不越权**。
     */
    data class WatchAnchor(
        val runId: Long,
        val pid: Int?,
        val startedAtMillis: Long,
        val deadlineMillis: Long? = null,
    )

    companion object {
        const val POLL_INTERVAL_MILLIS: Long = 200

        /** [StartOutcome.StartFailed] 的话术：等证/启动期间撞上急停（与池侧常量同一句）。 */
        const val ABORTED_BY_STOP: String = "启动期间宿主急停（killAll/forceStopAll）：本次执行已作废"
    }

    /**
     * 完成结算：release 槽位 + 归档在途表（与 stop/killAll 串行，经 guard）。
     *
     * 摘要取在**句柄收走之前**：槽位还在本 run 手里时才能问引擎进程（[ScriptEngine.lastRunSummary]），
     * release 之后句柄已回池、同一引擎可能已被下一次 execute 复用 —— 那时问到的就是别的 run 的摘要。
     */
    private suspend fun settleDone(runId: Long): Completed {
        val handle = guard.withLock {
            startedAt.remove(runId)
            // 暂停中自然退出的 run：集里那笔随终结消失（与 stop/killRun 同口径）。
            paused.remove(runId)
            // 必须是最后一个表达式（`withLock` 的值 = 本块末值）。
            active.remove(runId)
        } ?: return Completed.UnknownRun(null)
        heartbeats.forget(runId)
        revokeRunResources(runId)
        return withContext(NonCancellable) {
            val summary = handle.slot.engine.lastRunSummary()
            when (pool.release(handle)) {
                StopResult.Clean -> Completed.StoppedClean
                is StopResult.TimedOut -> Completed.StopTimeout(summary)
            }
        }
    }

    /**
     * 强杀结算：只收走本 run 的槽位（release 走 quiesce+kill 兜底），不碰其他在途。
     * 摘要同样在收走前取（见 [settleDone] KDoc 同一窗口口径）。
     */
    private suspend fun settleKilled(runId: Long): Completed {
        val handle = guard.withLock {
            startedAt.remove(runId)
            paused.remove(runId)
            // 必须是最后一个表达式（`withLock` 的值 = 本块末值）。
            active.remove(runId)
        } ?: return Completed.UnknownRun(null)
        heartbeats.forget(runId)
        revokeRunResources(runId)
        return withContext(NonCancellable) {
            val summary = handle.slot.engine.lastRunSummary()
            pool.release(handle)
            Completed.Killed(summary)
        }
    }

    /** 仅 RUNNING 判活的看门狗喂样口径（SUSPENDED 不存在于本状态机，见 EngineStatus）。 */
    fun isWatchable(status: EngineStatus): Boolean = status == EngineStatus.RUNNING
}
