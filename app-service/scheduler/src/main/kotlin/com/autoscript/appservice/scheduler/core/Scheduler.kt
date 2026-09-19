package com.autoscript.appservice.scheduler.core

import com.autoscript.domain.scripts.EngineRunLink
import com.autoscript.domain.scripts.RunArchive
import com.autoscript.domain.scripts.RunRecord
import com.autoscript.domain.scripts.RunState
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
                )
                // 归档入口（§8.5）：把日志已落行的 runId 交给 dispatcher，收回引擎侧身份，
                // 由归档器把两侧成对写入（RunArchive.put + EngineRunLink）。
                val report = dispatcher.dispatchToReport(pending.copy(intentRunId = started.runId))
                log.commit(started.runId, report.outcome)
                recordLink(pending, started.runId, report.link)
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
     * @return 每条的旧/新 runId 与结果，供装配层做恢复日志。
     */
    suspend fun recoverUncommitted(): List<RecoveryRecord> {
        val uncommitted = log.uncommitted()
        val recovered = mutableListOf<RecoveryRecord>()
        for (old in uncommitted) {
            val fresh = log.reopen(old.runId)                      // 单事务：封口 + 分配新 runId
            val pending = fresh.toPendingRun()
            val report = dispatcher.dispatchToReport(pending)     // 恢复重投同样归档（§8.5）
            log.commit(fresh.runId, report.outcome)
            recordLink(pending, fresh.runId, report.link)
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
    )

    /**
     * 归档落点（§8.5）：把「意图日志行 ↔ 引擎执行」成对写入 [RunArchive]。
     *
     * 只在 dispatcher **真的产生了引擎执行**（[DispatchReport.link] != null）时写：
     * - 登记一条 RUNNING 档案（含 [EngineRunLink]）——任务中心立即可见「引擎在跑」；
     *   终态结算由任务中心/UI 侧按需前进（[RunState] 状态机不允许改写终态）。
     * - link 为 null（门禁拒绝/排队超时/启动失败）如实不建档案，绝不写「有档案、实际没有
     *   对应执行」的孤儿记录。
     *
     * 顺序：在 [log.commit] **之后**执行 —— 本次执行的对外副作用已发生并已落日志，
     * 归档失败（持久层 IO 异常）只让档案缺失，不得回滚 COMMIT（否则 nonce 幂等集合丢失 →
     * 崩溃恢复会重投同一副作用）。
     */
    private suspend fun recordLink(pending: PendingRun, intentRunId: Long, link: EngineRunLink?) {
        val a = archive ?: return
        if (link == null) return
        val record = RunRecord(
            id = link.engineRunId,
            projectId = pending.projectId,
            scriptPath = pending.scriptPath,
            runNonce = pending.runNonce,
            state = RunState.RUNNING,
            startedAtMillis = clock(),
        )
        a.put(record, link)
    }
}

/** 恢复结果：一次未完成意向的封口 + 重投（§8.5）。 */
data class RecoveryRecord(
    val oldRunId: Long,
    val newRunId: Long,
    val runNonce: String,
    val outcome: RunOutcome,
)