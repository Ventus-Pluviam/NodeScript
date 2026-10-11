package com.autoscript.domain.scripts

/**
 * **为什么搬进 `:domain`**（2026-10-11 批 102，P4 jsonl→SQLite）：SQLite 实现必须住
 * `:platform:system`（`android.database.sqlite` 是 Android 面），而依赖铁律是
 * `:platform:* → :domain` —— 所以 SPI 与它的载荷形状（[ScheduledTask]）都得在 `:domain`，
 * 与 `IntentStore`/`RunArchive` 同一处规矩。
 *
 * 定时任务注册表持久缝（docs §8.6 调度持久性缺口的补账）。
 *
 * 为什么需要它：调度器的 `schedule`/`cancel` 之前只动内存 `running`/`handles` ——
 * 进程一退，全部登记丢失：AlarmManager 里还响着的闹钟投进空注册表（`onTrigger` 查无此任务
 * 直接 return），Once 任务直接蒸发，Daily 任务再也不会续排。意图日志（§8.5）只管"已投递的
 * 意向"，不管"还没到点的排期" —— 注册表是另一套账，必须另持久。
 *
 * 语义（与 [IntentLog] 的 append-only 对偶）：
 * - [put] 是 upsert（同 id 覆盖）：schedule 直写；
 * - [remove] 写 tombstone 而非删行：崩溃截断只丢最后一行半行，重放仍收敛；
 * - [loadAll] 返回收敛后的全量（tombstone 已剔除），供调度器的 `restoreTasks` 在
 *   `bootRecover` 之前重建内存注册表并续排闹钟。
 *
 * null = 未接存储（骨架/单测）：调度器退化为纯内存行为，不抛错。
 */
interface TaskStore : AutoCloseable {
    /** 登记/覆盖一个任务（schedule 直写；IO 失败抛错，调用方不得再动内存/闹钟）。 */
    fun put(task: ScheduledTask)

    /** 撤销登记（cancel 直写；幂等：从未登记的 id 照样落 tombstone，重放无影响）。 */
    fun remove(taskId: String)

    /** 收敛后的全量任务（按 id 排序；空 = 从未登记或全被撤销）。 */
    fun loadAll(): List<ScheduledTask>

    override fun close() {}
}
