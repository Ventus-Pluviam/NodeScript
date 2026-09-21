package com.autoscript.appservice.scheduler.core

import com.autoscript.domain.scripts.EngineRunLink
import com.autoscript.domain.scripts.RunArchive
import com.autoscript.domain.scripts.RunRecord
import com.autoscript.domain.scripts.RunState
import com.autoscript.domain.scripts.isTerminal
import java.time.ZoneId

/**
 * 定时任务模型（docs/framework-design.md §8.6 / §9.6 定时 API）：
 * 一次登记 = 一个调度计划 + 投递参数 + 屏幕契约；每次触发生成新的 runNonce（意图日志幂等锚点）。
 */
data class ScheduledTask(
    val id: String,                             // 任务标识（alarm requestCode / UI 引用）
    val name: String,
    val projectId: String,
    val scriptPath: String,
    val schedule: TimedSchedule,
    val screen: ScreenGuarantee = ScreenGuarantee.ANY,
    val args: List<String> = emptyList(),
    val scriptTimeoutMillis: Long? = null,      // 透传引擎
    val timezone: ZoneId = ZoneId.systemDefault(),
    val enabled: Boolean = true,
)

/**
 * 调度编排（§8.6）：登记/取消/触发 → 意图日志 RUN_START → dispatcher → COMMIT。
 *
 * 事件源五类统一经 [onTrigger] 进入（TIMED/INTENT/EVENT/USER_CLICK/ENGINE_INTERNAL），但只有：
 * - **TIMED（且非 Once）** 触发后推进周期排期（[rearmFor]）；
 *   非 TIMED 来源是独立触发路径（用户点击/广播/事件），**不续排闹钟**（否则事件触发后会
 *   在下一闹钟点再投一次，形成双路径重复执行）。纯事件任务的排期由各自监听器驱动，
 *   不再伪装成定时步调。
 * - **Once** 任何来源触发后即终态化（一次性任务，不接受重复触发）。
 *
 * - [enabled] 守卫：禁用后 TIMED/INTENT/EVENT 不投递；USER_CLICK（手动执行）不受影响。
 * - screen 门禁（§8.6 亮屏+解锁保底）由 dispatcher 实现承担（本模块无屏幕能力，见 [RunDispatcher]）。
 * - :app 侧 AlarmReceiver 拿到 taskId 后转调 [onTrigger]（scheduledAt 用闹钟真实排期）。
 */
class Scheduler(
    private val provider: SchedulerProvider,
    private val log: IntentLog,
    private val dispatcher: RunDispatcher,
    /** 引擎运行档案（§8.5 归档入口）。null = 未接归档（骨架/直投场景），两侧 id 关联暂缺。 */
    private val archive: RunArchive? = null,
    private val nonceFactory: () -> String = { java.util.UUID.randomUUID().toString() },
    private val clock: () -> Long = { System.currentTimeMillis() },
    /**
     * 投递到期上限（§8.6）：触发源 → 距排期时刻还能等多久。
     *
     * 为什么回 scheduler：`PendingRun.deadlineMillis` 要在 `appendStart` **之前**就算出来
     * （它随 RUN_START 一起进日志，崩溃恢复读的是日志行上的那个值）。而在途排队的上限
     * 住在 dispatcher（`:app` 装配层，它才碰得到引擎池），两处必须同源 —— 由装配层
     * （`AppShell.assemble`）把同一张表喂给两边，本地只提供可测的默认值。
     *
     * 装配层不传 → [DefaultDeadlines]（与 `ControllerRunDispatcher.DEFAULT_QUEUE_TIMEOUTS`
     * 同口径：ENGINE_INTERNAL 15s 最紧（嵌套等待）、USER_CLICK 10s、EVENT/INTENT 60s、
     * TIMED 120s 最宽）。**不要在两处各写一份数字**：漂移了现场极难查。
     */
    private val deadlineFor: (TriggerSource) -> Long = DefaultDeadlines,
) {
    private val stateLock = Any()
    private val running = hashMapOf<String, ScheduledTask>()
    private val handles = hashMapOf<String, TriggerHandle>()

    /**
     * 登记任务：计算首次 fire 并注册触发。
     * [task.schedule] 为 Once 时即单次投递；Daily/Cron 由 [onTrigger] 尾部自推进到下一轮。
     */
    suspend fun schedule(task: ScheduledTask) {
        synchronized(stateLock) { running[task.id] = task }
        rearmFor(task)
    }

    /** 取消任务：撤销已注册触发并移出注册表（已投递的 runs 不追回）。 */
    suspend fun cancel(taskId: String) {
        synchronized(stateLock) {
            running.remove(taskId)
            handles.remove(taskId)
        }?.cancel()
    }

    suspend fun tasks(): List<ScheduledTask> =
        synchronized(stateLock) { running.values.toList() }.sortedBy { it.name }

    /**
     * 统一触发入口。调度把「实际排期时刻」作为意图日志的 scheduledAt（诚实记录，而非 delay 推测）。
     * @param scheduledAtMillis 本次投递的排期时刻（alarm 预拉/wakeAhead 后真实触发时间由 :app 解析）。
     * 投递路径整段包 try/catch：dispatcher/log 抛错绝不逃逸 —— 否则异常会跳过 [rearmFor]，
     * 周期任务静默停排，且日志里的 STARTED 行下次启动被当崩溃重投（副作用双发）。
     */
    suspend fun onTrigger(taskId: String, source: TriggerSource = TriggerSource.TIMED, scheduledAtMillis: Long = clock()) {
        val task = synchronized(stateLock) { running[taskId] } ?: return
        if (!task.enabled && source != TriggerSource.USER_CLICK) return   // §8.6 enabled 守卫

        try {
            val pending = PendingRun(
                projectId = task.projectId,
                scriptPath = task.scriptPath,
                args = task.args,
                runNonce = nonceFactory(),
                trigger = source,
                scheduledAtMillis = scheduledAtMillis,
                screen = task.screen,               // §8.6：屏幕契约透传 dispatcher（门禁在实现层落地）
                timeoutMillis = task.scriptTimeoutMillis,
                // §8.6：本次投递的到期时刻 = 排期时刻 + 排队上限（同一张分级表的入口）。
                // 崩溃恢复据此判断"重启后这条还值不值得投"；在途排队的上限仍由 dispatcher 自己那一侧执行。
                deadlineMillis = scheduledAtMillis + deadlineFor(source),
            )
            // 快速路径预检：同 nonce 已完成则不投。真正的幂等兜底是存储层的原子拒绝（appendStart。
            if (!log.isCommitted(pending.runNonce)) {                       // §8.5 同 nonce 不重复投递
                val started = log.appendStart(
                    projectId = pending.projectId,
                    scriptPath = pending.scriptPath,
                    runNonce = pending.runNonce,
                    trigger = source,
                    scheduledAtMillis = scheduledAtMillis,
                    screen = task.screen,           // 恢复重投不得丢失屏幕契约（reopen 保留）
                    deadlineMillis = pending.deadlineMillis,
                )
                // 归档入口（§8.5）：把日志已落行的 runId 交给 dispatcher，收回引擎侧身份，
                // 由归档器把两侧成对写入（RunArchive.put + EngineRunLink）。
                val report = dispatcher.dispatchToReport(pending.copy(intentRunId = started.runId))
                log.commit(started.runId, report.outcome)
                recordLink(pending, started.runId, report.link, report.outcome)
            }
        } catch (t: Throwable) {
            // 投递失败如实落地：本轮不 commit（意图日志无 STARTED 或保持未 COMMIT，交由恢复路径裁决），
            // 但**绝不**牺牲排期推进 —— 异常逃逸会让周期任务从此静默（评审确认缺陷）。
            when (t) {
                is kotlinx.coroutines.CancellationException -> throw t      // 取消语义照常传播
                else -> Unit                                                // 其余失败：落 finally 续排
            }
        } finally {
            when {
                // Once：一次性任务，触发完成即终态化（取消已注册句柄并移出注册表），不接受重复触发
                task.schedule is TimedSchedule.Once -> {
                    val stale = synchronized(stateLock) {
                        running.remove(task.id)
                        handles.remove(task.id)
                    }
                    stale?.cancel()
                }
                // 周期定时任务：仅 TIMED 闹钟触发后推进到下一轮；非 TIMED 来源是一次独立投递，
                // 不触碰已有排期（否则事件触发 → 下一闹钟点再投 = 双路径重复执行）。
                source == TriggerSource.TIMED -> rearmFor(task)
                else -> Unit
            }
        }
    }

    /**
     * 崩溃恢复（§8.5）：启动时把未 COMMIT 意向重新入队——`log.reopen` **原子**完成
     * 「旧行封口 Interrupted + 新 runId 重开（保留 runNonce）」，
     * 消除两步式「commit-Interrupted 再 appendStart」之间崩溃即静默丢任务的窗口（评审 S6）。
     *
     * **过期意向不重投**（§8.6 最后一条缺口）：带 [PendingRun.deadlineMillis] 且已过期的
     * 意向照样 `reopen`（封口 + 记一笔账，历史不可改写），但**不再 dispatch** ——
     * 直接把新行 COMMIT 成 [RunOutcome.Cancelled]。理由：宿主持久化/重启之后这条任务的
     * 约定时刻早过了，重投一个注定迟到的任务比不投更糟（用户以为"它在跑"，实际是延期执行）；
     * 而静默丢弃等于崩溃恢复吃掉了这条意向，任务中心查不到原因。
     *
     * @return 每条的旧/新 runId 与结果，供装配层做恢复日志。
     */
    suspend fun recoverUncommitted(): List<RecoveryRecord> {
        val uncommitted = log.uncommitted()
        val recovered = mutableListOf<RecoveryRecord>()
        for (old in uncommitted) {
            val fresh = log.reopen(old.runId)                      // 单事务：封口 + 分配新 runId
            val pending = fresh.toPendingRun()
            if (pending.isExpired(clock())) {
                // 过期：封账不投。nonce 已随 reopen 前的那一行历史存在，Cancelled 是终态记账，
                // 由任务中心按 runId 读到「为何没跑」——绝不在恢复路径里静默跳过。
                log.commit(fresh.runId, RunOutcome.Cancelled)
                recovered += RecoveryRecord(
                    oldRunId = old.runId,
                    newRunId = fresh.runId,
                    runNonce = old.runNonce,
                    outcome = RunOutcome.Cancelled,
                    expired = true,
                )
                continue
            }
            val report = dispatcher.dispatchToReport(pending)     // 恢复重投同样归档（§8.5）
            log.commit(fresh.runId, report.outcome)
            recordLink(pending, fresh.runId, report.link, report.outcome)
            settleOrphanArchive(old.runId)   // 旧意向的档案若还有未终态记录：宿主死时没结算，如实 CRASHED
            recovered += RecoveryRecord(
                oldRunId = old.runId,
                newRunId = fresh.runId,
                runNonce = old.runNonce,
                outcome = report.outcome,
            )
        }
        return recovered
    }

    // ── 内部 ─────────────────────────────────────────────────────────────

    /** 按 schedule 计算下一次 fire 并注册；Once 在 schedule() 时即刻注册。 */
    private suspend fun rearmFor(task: ScheduledTask) {
        if (!task.enabled) return
        val now = clock()
        val next = task.schedule.nextFireAfter(now, task.timezone) ?: return
        val fresh = provider.registerTrigger(next, task.id)
        val stale = synchronized(stateLock) {
            val prev = handles.put(task.id, fresh)
            prev
        }
        stale?.cancel()
    }

    private fun IntentRun.toPendingRun() = PendingRun(
        projectId = projectId,
        scriptPath = scriptPath,
        runNonce = runNonce,
        trigger = trigger,
        scheduledAtMillis = scheduledAtMillis,
        screen = screen,
        intentRunId = runId,               // 恢复重投的新行身份（§8.5）
        deadlineMillis = deadlineMillis,   // §8.6：期限从旧行原样带到重投行（见 reopen）
    )

    /**
     * 归档落点（§8.5）：把「意图日志行 ↔ 引擎执行」成对写入 [RunArchive]。
     *
     * 只在 dispatcher **真的产生了引擎执行**（[DispatchReport.link] != null）时写：
     * - 按 [outcome] 直接写终态（[RunOutcome] → [RunState] 见 [outcomeState]），附起止时刻。
     *   dispatcher 返回时执行体已经结束（`awaitCompletion` 在 dispatcher 内部走完），
     *   先写 RUNNING 再结算只是无意义的两次 `put` —— 档案落地即终态，append-only 纪律不变。
     * - link 为 null（门禁拒绝/排队超时/启动失败）如实不建档案，绝不写「有档案、实际没有
     *   对应执行」的孤儿记录。
     *
     * 顺序：在 [log.commit] **之后**执行 —— 本次执行的对外副作用已发生并已落日志，
     * 归档失败（持久层 IO 异常）只让档案缺失，不得回滚 COMMIT（否则 nonce 幂等集合丢失 →
     * 崩溃恢复会重投同一副作用）。
     */
    private suspend fun recordLink(pending: PendingRun, intentRunId: Long, link: EngineRunLink?, outcome: RunOutcome) {
        val a = archive ?: return
        if (link == null) return
        val now = clock()
        val record = RunRecord(
            id = link.engineRunId,
            projectId = pending.projectId,
            scriptPath = pending.scriptPath,
            runNonce = pending.runNonce,
            state = outcomeState(outcome),
            startedAtMillis = now,
            finishedAtMillis = now,
        )
        a.put(record, link)
    }

    /**
     * [RunOutcome] → [RunState]（归档终态映射，§8.5）：
     * - Succeeded → SUCCEEDED；Failed → FAILED；
     * - Crashed → CRASHED（引擎被杀/OOM/看门狗/等待超时强杀）；
     * - Cancelled → CANCELLED；Interrupted（过期封账/崩溃封口）→ CANCELLED
     *   （恢复路径不写档案 —— 过期意向从未投递，`recordLink` 调用前已被 `link == null`
     *   或显式分支过滤；这里只为穷举完备）。
     */
    private fun outcomeState(outcome: RunOutcome): RunState = when (outcome) {
        RunOutcome.Succeeded -> RunState.SUCCEEDED
        RunOutcome.Failed -> RunState.FAILED
        is RunOutcome.Crashed -> RunState.CRASHED
        RunOutcome.Cancelled -> RunState.CANCELLED
        RunOutcome.Interrupted -> RunState.CANCELLED
    }
    /**
     * 恢复时的档案孤儿结算（§8.5 `unfinished` 的清理方）：旧意向 [oldIntentRunId] 关联的
     * 档案里还有没终态的记录，说明宿主死时那次执行没结算 —— 而这个新进程永远不可能再
     * 观察/杀死/等待它（引擎句柄随旧进程一起死了），留 RUNNING 是对任务中心撒谎
     * （`unfinished()` 只增不减，"在跑"列表越积越长）。
     *
     * 如实记 [RunState.CRASHED]：从档案视角看，这次执行确实没跑完就失联了。
     * 诚实边界：引擎 OS 进程可能还活着（宿主死不等于引擎死），但那已是 native 宿主的
     * 孤儿进程问题，本层无 pid 无句柄，CRASHED 表达的是"失联"，不是"已杀死"。
     * 已终态的记录不动（`put` 的终态不可改写纪律会响亮失败，所以先过滤）。
     */
    private suspend fun settleOrphanArchive(oldIntentRunId: Long) {
        val a = archive ?: return
        for (rec in a.recordsOfIntent(oldIntentRunId)) {
            if (!rec.state.isTerminal) {
                a.put(
                    rec.copy(state = RunState.CRASHED, finishedAtMillis = clock()),
                    a.link(rec.id),
                )
            }
        }
    }
}

/**
 * 投递到期上限的默认分级表（§8.6；与 `:app` 的 `ControllerRunDispatcher.DEFAULT_QUEUE_TIMEOUTS`
 * 同口径）。**编订在 scheduler 侧是为了给 `deadlineMillis` 一个可测默认值** ——
 * 生产由装配层把 dispatcher 那一张表喂进来，两处数字永不各写一份。
 */
val DefaultDeadlines: (TriggerSource) -> Long = { trigger ->
    when (trigger) {
        TriggerSource.ENGINE_INTERNAL -> 15_000L
        TriggerSource.USER_CLICK -> 10_000L
        TriggerSource.INTENT_BROADCAST -> 60_000L
        TriggerSource.EVENT -> 60_000L
        TriggerSource.TIMED -> 120_000L
    }
}

/** 恢复结果：一次未完成意向的封口 + 重投（§8.5）。 */
data class RecoveryRecord(
    val oldRunId: Long,
    val newRunId: Long,
    val runNonce: String,
    val outcome: RunOutcome,
    /** 因 [PendingRun.deadlineMillis] 到期而未重投（§8.6）：outcome 必为 [RunOutcome.Cancelled]。 */
    val expired: Boolean = false,
)