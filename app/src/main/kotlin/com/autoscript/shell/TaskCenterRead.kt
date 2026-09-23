package com.autoscript.shell

import com.autoscript.appservice.scheduler.core.RecoveryRecord
import com.autoscript.appservice.scheduler.core.ScheduledTask
import com.autoscript.appservice.scheduler.core.ScreenGuarantee
import com.autoscript.appservice.scheduler.core.TimedSchedule
import com.autoscript.domain.host.RecoveryRow
import com.autoscript.domain.host.RunRow
import com.autoscript.domain.host.ScheduleSpec
import com.autoscript.domain.host.ScheduledTaskRow
import com.autoscript.domain.host.ScreenRequirement
import com.autoscript.domain.host.TaskCenterSnapshot
import com.autoscript.domain.scripts.EngineRunLink
import com.autoscript.domain.scripts.RunRecord

/**
 * 任务中心快照的拼装与映射（§8.6 排期 + §8.5 执行档案/恢复账）。
 *
 * **为什么单独一个文件**：与 [CapabilityCenterRead] 同一条理由 —— `Application` 在
 * JVM 单测里构造不出来（运行期 android stub 会抛），而"调度器类型怎么映射成呈现 DTO、
 * 哪些字段留着哪些不猜"这段判断必须可测。抽出来之后 `AppShellApplication` 只剩一句转接。
 *
 * **本对象不读任何寄存器**：注册表/档案/恢复账都由调用方取好传进来。理由是
 * 取数那一步各是一句挂起调用，而**怎么映射**才是这里要被钉住的东西 ——
 * 把 IO 一起包进来会让单测被迫造真文件（`FileTaskStore`/`FileRunArchive`），
 * 而那两件东西的语义已经各有测试，这里再验一遍等于把它们的测试抄第二遍。
 *
 * 三条纪律：
 * - **逐字段映射，不做聚合**：`TimedSchedule` 三态 → [ScheduleSpec] 三态一一对应；
 *   [ScreenGuarantee] → [ScreenRequirement] 按**名**对表（[mapScreen]）。聚合（如把
 *   Cron 折成 Daily）会丢事实，而"丢事实"在 UI 上表现为一条任务看起来在正常排期；
 * - **下一跳不由本层算**：调用方传 `nextFireAfter` 的结果（调度数学的唯一出处在
 *   `TimedSchedule.nextFireAfter`，§8.6）；停用任务**不问下一跳**（调用方传 null）——
 *   问了也会得到答案，而那个答案会让人以为停用任务还会跑；
 * - **关联（intentRunId）缺了如实 null**：`RunArchive.record` 只回记录，双 id 的另一半
 *   要单独读 `link`。读不到就是读不到（独立执行，或 `recordLink` 与档案写入之间
 *   那个已知窗口），不拿 `record.runNonce` 之类的东西去凑一个 id。
 */
object TaskCenterRead {

    /**
     * 拼一份任务中心快照。
     *
     * @param tasks 已登记任务（`Scheduler.tasks()` 的结果，调用方已排序）。
     * @param nextFireAt 任务的下一跳时刻；调用方负责停用任务回 null（见对象 KDoc）。
     * @param degradedTaskIds 降级投递中的任务 id（`AlarmSchedulerProvider.degradedTasks` 的键）。
     * @param runs 未结算的执行（`RunArchive.unfinished()`）。
     * @param linkOf 取双 id 关联的读口（`RunArchive.link`）；缺省 null = 不读关联，
     *   此时 [RunRow.intentRunId] 一律 null（"没读"与"读了没有"都落成 null ——
     *   **这是本对象唯一一处两义合流**，由调用方保证只在真读不到时才不传）。
     * @param recovery 上次恢复账（`RecoverySnapshot`）；null = 本次进程还没跑过恢复。
     */
    suspend fun snapshot(
        tasks: List<ScheduledTask>,
        nextFireAt: (ScheduledTask) -> Long?,
        degradedTaskIds: Set<String>,
        runs: List<RunRecord>,
        linkOf: (suspend (Long) -> EngineRunLink?)? = null,
        recovery: RecoverySnapshot? = null,
    ): TaskCenterSnapshot = TaskCenterSnapshot(
        tasks = tasks.map { task ->
            ScheduledTaskRow(
                id = task.id,
                name = task.name,
                projectId = task.projectId,
                scriptPath = task.scriptPath,
                schedule = mapSchedule(task.schedule),
                screen = mapScreen(task.screen),
                enabled = task.enabled,
                nextFireAtMillis = nextFireAt(task),
                degraded = task.id in degradedTaskIds,
            )
        },
        runs = runs.map { record ->
            RunRow(
                engineRunId = record.id,
                intentRunId = linkOf?.invoke(record.id)?.intentRunId,
                projectId = record.projectId,
                scriptPath = record.scriptPath,
                state = record.state,
                startedAtMillis = record.startedAtMillis,
                finishedAtMillis = record.finishedAtMillis,
            )
        },
        recovery = recovery?.let { recoveryRow(it.records, it.failure) },
    )

    /** `TimedSchedule` → [ScheduleSpec]（三态一一对应；新增分支时 `when` 穷尽性会强制面对）。 */
    fun mapSchedule(schedule: TimedSchedule): ScheduleSpec = when (schedule) {
        is TimedSchedule.Once -> ScheduleSpec.Once(schedule.delaySeconds)
        is TimedSchedule.Daily -> ScheduleSpec.Daily(schedule.hourOfDay, schedule.minuteOfHour)
        is TimedSchedule.Cron -> ScheduleSpec.Cron(schedule.expr)
    }

    /**
     * `ScreenGuarantee` → [ScreenRequirement]：**按名**取，不用 `ordinal`。
     *
     * 用序数的话，调度器将来在枚举中间插一个值（比如 `SCREEN_OFF_ONLY`），
     * 所有映射会整体错位而测试照样绿 —— 名对不上就响亮失败，正是这里想要的。
     */
    fun mapScreen(guarantee: ScreenGuarantee): ScreenRequirement =
        ScreenRequirement.valueOf(guarantee.name)

    /**
     * 恢复账 → [RecoveryRow]。
     *
     * 入参是裸的 `records` + `failure` 而不是 [RecoverySnapshot]：单测要能直接喂一批
     * 假记录，不必为了构造快照去跑一遍真恢复。失败原因取 `message`，无 message 时
     * 退到类名（与 `CapabilityCenterState.failed` 同一手法：显示 `null` 会被渲染成"没失败"）。
     */
    fun recoveryRow(records: List<RecoveryRecord>, failure: Throwable?): RecoveryRow = RecoveryRow(
        total = records.size,
        expired = records.count { it.expired },
        failureText = failure?.let { it.message ?: it.javaClass.simpleName },
    )
}
