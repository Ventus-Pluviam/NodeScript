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
     * 装配层不传 → [DefaultDeadlines]。生产由装配层显式喂入
     * `ControllerRunDispatcher.DEFAULT_QUEUE_TIMEOUTS`（与本表同一引用，见其 KDoc）——
     * 缺省恰好相同是巧合，写出来才是契约。
     */
    private val deadlineFor: (TriggerSource) -> Long = DefaultDeadlines,
    /**
     * 任务注册表持久缝（§8.6 调度持久性）。
     *
     * null = 未接存储（骨架/单测）：纯内存行为。非 null 时 [schedule]/[cancel] 直写、
     * [restoreTasks] 从它重建 —— 顺序都是"先落盘后动内存/闹钟"：IO 抛错时内存与
     * AlarmManager 保持原样，绝不出现"内存有、盘上无"的幽灵登记（反过来"盘上有、
     * 内存无"下次 restore 还能收敛，是安全的失败方向）。
     */
    private val taskStore: TaskStore? = null,
) {
    private val stateLock = Any()
    private val running = hashMapOf<String, ScheduledTask>()
    private val handles = hashMapOf<String, TriggerHandle>()

    /**
     * 登记任务：计算首次 fire 并注册触发。
     * [task.schedule] 为 Once 时即单次投递；Daily/Cron 由 [onTrigger] 尾部自推进到下一轮。
     */
    suspend fun schedule(task: ScheduledTask) {
        taskStore?.put(task)                                  // 先落盘：失败即抛，内存/闹钟不动
        synchronized(stateLock) { running[task.id] = task }
        rearmFor(task)
    }

    /** 取消任务：撤销已注册触发并移出注册表（已投递的 runs 不追回）。 */
    suspend fun cancel(taskId: String) {
        taskStore?.remove(taskId)                             // 先落 tombstone：失败即抛，原登记保留
        synchronized(stateLock) {
            running.remove(taskId)
            handles.remove(taskId)
        }?.cancel()
    }

    /**
     * 启动恢复（§8.6 注册表重建）：从 [taskStore] 重建内存注册表并为每个任务续排闹钟。
     *
     * 调用顺序必须在 [recoverUncommitted] **之前**（装配层保证）：恢复重投的意向属于
     * "已投递"的账，而这里重建的是"还没到点"的排期 —— 顺序反了不丢数据，但重投的
     * Once 任务会被这里的续排又注册一次（Once 触发后才终态化，恢复时它还在注册表里）。
     * 幂等：重复调用只是重新 `put` 同一批任务并替换闹钟句柄（旧句柄取消），不叠加。
     *
     * @return 重建的任务（按 id 排序；store 为 null 或空时为空表）。
     */
    suspend fun restoreTasks(): List<ScheduledTask> {
        val stored = taskStore?.loadAll() ?: return emptyList()
        for (task in stored) {
            synchronized(stateLock) { running[task.id] = task }
            rearmFor(task)        // disabled/Cron-null 内部直接返回：留名不续排（见 rearmFor）
        }
        return stored
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
        // 先做**全档空档结算**（见 settleVoidArchive）：本次恢复要 reopen 的 intent 集合之外，
        // 还可能有「档案 RUNNING 但意图行早已 COMMIT」的孤儿 —— 例如上一进程在 COMMIT 之后、
        // 档案结算之前死掉（scheduler 的 recordLink 在 log.commit 之后，见其 KDoc）。
        // 这类孤儿没有任何未 COMMIT 行可挂靠，逐 intent 的 settleOrphanArchive 永远看不到它。
        settleVoidArchive(uncommitted.map { it.runId }.toSet())
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
     *
     * **代价（已知并显式化）**：两者不是同一事务，中间崩溃会留下「意图行已终态、档案记录
     * 不存在或停在非终态」的孤儿。前者的可见形态是档案缺行（记录本就是历史副本，缺失如实
     * 呈现为"无档案"）；后者由 [recoverUncommitted] 的跨 intent 空档结算补账
     * （[settleVoidArchive]）。反过来（先写档案）会让崩溃留下「档案有终态、意图未 COMMIT」，
     * 恢复重投时会撞上档案的终态不可改写纪律 —— 那条路是响亮失败而非补账，更糟。
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

    /**
     * 跨 intent 的空档结算（§8.5 `unfinished()` 的补集清理方）：
     * [settleOrphanArchive] 只能看见「本次恢复要 reopen 的那些 intent」的档案，而
     * 档案里 RUNNING 的记录未必挂在未 COMMIT 的 intent 上 —— 上一进程可能在
     * `log.commit` **之后**、`recordLink` **之前**死掉（两者不同事务，见 [recordLink] 的 KDoc）：
     * 意图行已是终态（不在 `uncommitted()` 里），档案记录却永远停在 RUNNING。
     *
     * 这类孤儿没有任何未 COMMIT 行可挂靠，逐 intent 的结算永远看不见它，
     * `unfinished()` 于是只增不减。**恢复是唯一能安全做这件事的时机**：此刻本进程
     * 才刚起来，引擎池必然空（before 任何 dispatch），档案里所有非终态记录都只能是
     * 上一进程的遗物。运行期绝不能这么扫 —— 那会把正在跑的执行误判成孤儿。
     *
     * 只扫 [RunArchive.unfinished]（档案自报），不反查意图日志：档案是引擎侧的唯一事实源，
     * 用它的自报做输入才是「谁的状态谁维护」；拿日志去猜档案该是什么状态会造出第二个真值。
     *
     * 关联（link）原样保留：link 是历史事实（那次执行确实由那个 intent 投递），
     * 不因结算而消失 —— 任务中心仍可按 IntentRun 追到这条"失联"记录。
     */
    private suspend fun settleVoidArchive(reopeningIntentIds: Set<Long>) {
        val a = archive ?: return
        for (rec in a.unfinished()) {
            val link = a.link(rec.id) ?: continue        // 无关联的记录不属意图日志管辖（独立执行），不结算
            if (link.intentRunId in reopeningIntentIds) continue   // 本次恢复会经 settleOrphanArchive 处理，不重复写
            a.put(
                rec.copy(state = RunState.CRASHED, finishedAtMillis = clock()),
                link,
            )
        }
    }
}

/**
 * 排队/到期上限的默认分级表（§8.6 分级口径的**唯一正本**）。
 *
 * 分级依据 = **谁在等、等久了会不会连带出事**：
 * - ENGINE_INTERNAL 最紧（15s）：一个已占槽的引擎在等另一个引擎，满池时这是
 *   「持有者等后来者」的嵌套形态，等久了就是跨引擎死锁，必须先爆；
 * - USER_CLICK 次之（10s）：人盯着 UI，给不出结果就该如实回 Cancelled，让任务中心
 *   呈现「引擎忙，未执行」，而不是让按钮原地转圈；
 * - INTENT_BROADCAST / EVENT 宽一些（60s）：外部涌入的批量触发本就该容忍排队；
 * - TIMED 最宽（120s）：守时任务已承诺「亮屏+解锁保底 + 可能偏差」，2 分钟兜底
 *   只为满足铁律 3（满池排队必须有 TTL，绝不无限等），不追求抢跑。
 *
 * 两处消费同一引用（不是两份相同的数字）：scheduler 的 `deadlineMillis`（恢复判过期）
 * 与 `:app` dispatcher 的排队上限（在途等多久）。`:app` 侧以
 * `ControllerRunDispatcher.DEFAULT_QUEUE_TIMEOUTS` 别名引用本表（arch 门禁禁止
 * scheduler→:app 方向，故正本只能住 scheduler 侧）；生产装配（`AppShell.assemble`）
 * 把那张表显式喂给 `Scheduler(deadlineFor=…)` —— 缺省恰好相同是巧合，写出来才是契约。
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