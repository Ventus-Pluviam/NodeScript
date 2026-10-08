package com.autoscript.shell

import com.autoscript.appservice.npm.NpmCliDeployer
import com.autoscript.appservice.runtime.EngineWatchdog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import com.autoscript.appservice.scheduler.persist.FileRunArchive
import com.autoscript.appservice.scheduler.persist.FileTaskStore
import com.autoscript.appservice.scheduler.persist.PersistentIntentLog
import com.autoscript.appservice.scheduler.recovery.ScriptDeployRecovery
import com.autoscript.appservice.scriptrepo.core.BridgeAddonDeploy
import com.autoscript.appservice.scriptrepo.core.BridgeDistDeploy
import com.autoscript.domain.bridge.NamespaceHandler
import com.autoscript.domain.host.TaskLogSnapshot
import com.autoscript.domain.host.TaskCenterSnapshot
import com.autoscript.domain.host.ConsoleSnapshot
import com.autoscript.domain.host.TaskRegistration
import com.autoscript.appservice.scheduler.core.TriggerSource
import com.autoscript.domain.scripts.RunArchive
import com.autoscript.domain.scripts.RunRecord

/**
 * 装壳产物与壳自己的协程域（2026-10-01 D7 自 `AppShellKit.kt` 原样外迁：它们与
 * 「怎么装壳」是两件事 —— `AppShellKit` 的 `assemble` 是配方，这里是被装出来的
 * 那个东西与它的收口责任。语义逐字未改）。
 */

/**
 * 壳自己的协程域（看门狗轮转住这里）。
 *
 * 为什么由配方持有而不是让 Application 传一个：[EngineWatchdog.start] 挂上之后，
 * 「谁负责停」必须有着落，否则要么泄漏（进程级 GlobalScope）、要么停不掉。这里用
 * [SupervisorJob] + 壳自己的 close 收口：**子任务失败不牵连壳**（看门狗一轮采样抛错
 * 不能让调度也停），关壳即停轮转。调用方要换成自己的域（如 UI 生命周期域）时传
 * [assemble] 的 `watchdogScope`。
 */
internal class ShellScope : CoroutineScope {
    override val coroutineContext = SupervisorJob() + kotlinx.coroutines.Dispatchers.Default
    val job get() = coroutineContext[kotlinx.coroutines.Job]!!
    fun cancel() = job.cancel()
}

/** 装壳产物：壳 + 随壳创建的持久句柄 + 看门狗域（关壳即全部收口）。 */
class AssembledShell internal constructor(
    val shell: AppShell,
    private val log: PersistentIntentLog,
    private val archive: RunArchive,
    private val tasks: FileTaskStore?,
    /** npm 装配产出的 handler（装配测试/诊断用；null 表示本次装配未挂 npm）。 */
    val npmHandler: NamespaceHandler?,
    private val scope: ShellScope?,
    /**
     * 装配期脚本补部署报告（§9.6 `files/scripts/<projectId>/` 标准化）。
     * 空报告（`deployed` 与 `failures` 皆空）= 没有来源可补，不是"恢复成功"
     * —— 装配层日志/能力中心不得把它说成"脚本已恢复"。
     */
    val deployReport: ScriptDeployRecovery.Report = ScriptDeployRecovery.Report(),
    /**
     * 装配期 facade dist 落位报告（§12.4 资产交付轨：`assets/bridge-dist/` →
     * `filesDir/node_modules/auto/`）。空报告 = 本次没货可落（**不是**"facade 已就位"）；
     * [BridgeDistDeploy.Report.failures] 非空 = 有文件没落上 + 孤儿未清（见其 KDoc 边界）。
     */
    val bridgeDistReport: BridgeDistDeploy.Report = BridgeDistDeploy.Report(),
    /**
     * 装配期 bridge addon 落位报告（§19 交付轨：`assets/bridge-addon/` →
     * `filesDir/lib/bridge_native.node`）。空报告（`deployed` false 且无 failure）
     * = 本次没货可落（**不是**"addon 已就位"）；引擎侧 `addonPath` 缺文件即降级
     * 不注入（选填纪律），桥调用点如实 `ERR_ENGINE_STOPPED`。
     */
    val bridgeAddonReport: BridgeAddonDeploy.Report = BridgeAddonDeploy.Report(),
    /**
     * 装配期 vendored npm CLI 落位结果（§10.2 调用链首段）。
     * null = 本次没落（原因见 [npmCliFailure]）；`Ready.deployedFresh = false` = 幂等命中
     * （素材没换，整目录跳过 —— 开机路径零 IO）。**调用方自带 `npmHandler` 时本配方
     * 不碰素材，两个字段皆 null** —— 那时它们不表达任何"npm 可用"的意思。
     */
    val npmCli: NpmCliDeployer.Outcome? = null,
    /**
     * npm 执行体没接线的原因原文（null = 已接线）。**不吞**：能力中心据此如实显示
     * "npm 未接线"，而不是把 msg 缺失读成一切正常 —— 这与 [deployReport] 那条
     * "空报告 ≠ 已恢复"是同一条纪律。
     *
     * 与 [npmCli] 的组合是有意义的：两者皆 null = 调用方自带 `npmHandler`（本配方
     * 没参与）；`npmCli` 非 null 而本字段非 null = **CLI 落了但跑不起来**（缺宿主/
     * 执行体构造失败）—— 这一档最该被看见，它离"能用"只差一个宿主。
     */
    val npmCliFailure: String? = null,
    /**
     * 应用密钥没接上的原因原文（null = 已接上，lock 签名与快照签名都在生效）。
     * 与 [npmCliFailure] 同一纪律：**不吞** —— 能力中心据此显示「lock 防线未生效」，
     * 而不是把字段缺失读成一切正常。
     *
     * 与 [npmCliFailure] 的区别：那一条是「CLI 落了但跑不起来」（离能用只差一个宿主），
     * 这一条是「装得上、能装，只是 `npm ci` 不验签」—— 用户看得见的效果是
     * `ci` 不再因 lock 被换而拒，故能力中心两处措辞不能混。
     */
    val npmLockKeyFailure: String? = null,
    /**
     * `child_process` 拦截 shim 的落点（null = 本次没落 / 没素材没宿主，见
     * [AppShellKit.assemble]）。**非 null 才是"零 spawn 不变量有守卫"**：
     * 它是 P0 承诺面（§10.11），所以装配层在它落位失败时**不注入安装执行体**
     * （原因原文进 [npmCliFailure]）—— 能读到这里 = 守卫在盘上，会话进程起时经
     * `NODE_OPTIONS=--require=<它>` 注入。
     */
    val npmSpawnGate: java.nio.file.Path? = null,
) : AutoCloseable {
    /** 没补上的脚本（路径 + 原因；能力中心呈现"有脚本没补上"，不吞成一切正常）。 */
    fun deployFailures(): List<ScriptDeployRecovery.Failure> = deployReport.failures

    /** 没落上的 facade 文件（路径 + 原因；`require('auto')` 会因此解析不到）。 */
    fun bridgeDistFailures(): List<BridgeDistDeploy.Failure> = bridgeDistReport.failures

    /** addon 没落上的原因（null = 本次没货或已就位；非空 = 来源/写入失败原文）。 */
    fun bridgeAddonFailure(): String? = bridgeAddonReport.failure
    override fun close() {
        scope?.cancel()          // 先停看门狗轮转，再关壳/持久句柄（轮转中不得关底下的池）
        shell.close()
        (archive as? AutoCloseable)?.close()
        tasks?.close()           // 注册表 channel：与意图日志同一目录、同一追加纪律
        log.close()
    }

    /**
     * 未终态的运行记录（`RunArchive.unfinished`，§8.5）。
     *
     * 生产实现里**不该有**：`Scheduler.recordLink` 落档案即终态、恢复路径两条孤儿结算
     * （逐 intent + 跨 intent 空档）负责补账。非空 = 上面两条里有一条没走完 ——
     * UI 据此如实显示"有执行没结算"，而不是把 RUNNING 记录当成"正在跑"
     * （那正是 `unfinished()` 只增不减的那种谎）。
     */
    suspend fun unfinishedRuns(): List<RunRecord> = archive.unfinished()

    /** 某项目的执行历史（任务中心读口；`recordsOfProject` 的对偶）。 */
    suspend fun runsOf(projectId: String): List<RunRecord> = archive.recordsOfProject(projectId)

    /** 任务日志：全部项目的终态历史（HostSummary 读口；使用壳自己的档案和双 id 关联）。 */
    suspend fun taskLog(): TaskLogSnapshot =
        TaskLogRead.snapshot(archive.records(), archive::link)

    /** 某次执行的终态（null = 无此记录；UI 按 runId 读"为何没跑"的落点）。 */
    suspend fun runRecord(engineRunId: Long): RunRecord? = archive.record(engineRunId)

    /**
     * 任务中心快照（§8.6 排期 + §8.5 档案/恢复账）—— 呈现层经 `HostSummary.taskCenter()`
     * 读到的就是这一份。
     *
     * 读的是**壳自己持有的那两个寄存器**（注册表经 [AppShell.scheduler]、档案经
     * [archive]），不让 UI 另开 `FileTaskStore`/`FileRunArchive`：第二个实例会各自持
     * channel 与内存视图，写侧两份即失真（见本类 KDoc 的读口说明）。
     *
     * 下一跳由**调度数学的唯一出处**算（`TimedSchedule.nextFireAfter`），停用任务
     * 直接回 null —— 停用任务也会有一个"如果启用就会在何时跑"的答案，把它显示出来
     * 就是在骗用户说这条还会跑。
     *
     * 恢复账取 [recovery]（装配层在 `install` 时接上的那份读口）；缺省 `{ null }` =
     * 本装配没接恢复账，快照里 [TaskCenterSnapshot.recovery] 如实为 null
     * （"本次进程还没跑过恢复"，不是"恢复了 0 条"）。
     */
    suspend fun taskCenter(
        recovery: () -> RecoverySnapshot? = { null },
    ): TaskCenterSnapshot {
        val now = System.currentTimeMillis()
        val tasks = shell.scheduler.tasks()
        val degraded = (shell.schedulerProvider as? AlarmSchedulerProvider)
            ?.degradedTasks()?.keys ?: emptySet()
        return TaskCenterRead.snapshot(
            tasks = tasks,
            // 停用任务不问下一跳（见 KDoc）；时区取任务自己的（Daily 的 DST 边界靠它）。
            nextFireAt = { task ->
                if (!task.enabled) null else task.schedule.nextFireAfter(now, task.timezone)
            },
            degradedTaskIds = degraded,
            runs = archive.unfinished(),
            linkOf = { id -> archive.link(id) },
            recovery = recovery(),
        )
    }

    /**
     * 控制台快照（§7.3 游标拉取）—— 呈现层经 `HostSummary.console()` 读到的就是这一份。
     *
     * 读的是**壳自己持有的两件东西**（收集器 [AppShell.console] + 在途表
     * [AppShell.controller]），不让 UI 另开 `ConsoleCollector`：第二个收集器收不到
     * 桥上注册的行，读到的永远是空（写侧两份视图的老问题，同 `FileRunArchive` 一条理）。
     *
     * 游标（[sinceSeq]）由调用方传：数据面是拉取不是推送，游标只进不退。
     */
    suspend fun consoleView(sinceSeq: Long, maxLines: Int): ConsoleSnapshot =
        ConsoleRead.snapshot(
            collector = shell.console,
            sinceSeq = sinceSeq,
            maxLines = maxLines,
            runStatuses = { shell.controller.runStatuses() },
        )

    /**
     * 登记任务（§8.6 操作面「登记」）—— 呈现层经 `HostSummary.registerTask()` 走到这里。
     *
     * 写的是**壳自己持有的调度器**（`Scheduler.schedule`：先落盘再动内存/闹钟，
     * 落盘失败即抛、注册表无半登记状态），不让 UI 另开 `FileTaskStore` ——
     * 第二个实例 = 写侧两份视图（同 [taskCenter] 读口一条理）。
     * 入参校验在 [TaskCenterOps.toScheduledTask]（与桥侧 `workManager.create` 同一套规则）。
     *
     * @return 分配到的任务 id（入参 id 为空时服务端 UUID）。
     */
    suspend fun registerTask(registration: TaskRegistration): String {
        val task = TaskCenterOps.toScheduledTask(registration)
        shell.scheduler.schedule(task)
        return task.id
    }

    /**
     * 取消任务（§8.6 操作面「取消」）：转发 `Scheduler.cancel` —— 幂等
     * （从未登记的 id 照样返回；先落 tombstone 再动内存），已投递的 runs 不追回。
     */
    suspend fun cancelTask(taskId: String) {
        shell.scheduler.cancel(taskId)
    }

    /**
     * 立即执行（§8.6 操作面「立即执行」，触发源 `USER_CLICK`）。
     *
     * **触发前现查两件事**（`onTrigger` 对两者都是静默 return —— 不查就会把 no-op
     * 呈现成"已触发"）：
     * - 调度已收口（`sink()` 之后宿主正在停止）→ 抛，不让按钮在停机窗口里假装成功；
     * - 任务不在册（可能已被取消/Once 已终态化）→ 抛，原文带 id。
     *
     * **挂起到本次执行结算**（与闹钟/广播同一条 `onTrigger` 路径：排队上限
     * USER_CLICK 10s + 脚本超时/默认 30s）—— 成败不由此口回报（恒 Unit，
     * 结局在意图日志/控制台），调用方文案不得把"返回了"说成"跑成功了"。
     * Once 任务触发即终态化出册，刷新后从列表消失是调度器语义，不是取消。
     *
     * 查与触发之间存在极小竞态窗口（并发取消）：后果是本次静默无事发生 ——
     * 接受它；为关掉它去改 `onTrigger` 返回值会动到全部触发调用点与既有测试的
     * 表达式体形态，收益不抵风险。
     */
    suspend fun runTaskNow(taskId: String) {
        val scheduler = shell.scheduler
        if (scheduler.sinking) {
            throw IllegalStateException("调度已收口（宿主正在停止）：不再接收新触发")
        }
        if (scheduler.tasks().none { it.id == taskId }) {
            throw IllegalStateException("任务不存在（可能已被取消）：$taskId")
        }
        scheduler.onTrigger(taskId, TriggerSource.USER_CLICK)
    }

    /**
     * 停止一次在途执行（`HostSummary.stopRun` 的装配实现，§8.2 池四步 quiesce）。
     *
     * 走的是**在途表**（`RuntimeController.stop`），不是调度单槽
     * （`Scheduler.stopLastRun`）：恢复重投会覆盖单槽的 `lastHandle`，
     * 在途表按 runId 精确命中、不受覆盖影响。`AlreadyGone`（已结算/从未存在）
     * 如实回 false —— 在途表本来就没有它，不是失败，不抛。
     * `StoppedClean`/`StoppedTimeout` 都算"停过了"（后者池侧已 kill 兜底）。
     */
    suspend fun stopRun(runId: Long): Boolean = when (shell.controller.stop(runId)) {
        com.autoscript.appservice.runtime.RuntimeController.StopOutcome.StoppedClean -> true
        is com.autoscript.appservice.runtime.RuntimeController.StopOutcome.StoppedTimeout -> true
        com.autoscript.appservice.runtime.RuntimeController.StopOutcome.AlreadyGone -> false
    }
}
