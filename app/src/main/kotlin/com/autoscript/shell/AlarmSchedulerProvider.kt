package com.autoscript.shell

import com.autoscript.appservice.scheduler.core.SchedulerProvider
import com.autoscript.appservice.scheduler.core.TriggerHandle

/**
 * 闹钟投递模式（§8.6 守时契约的两种落地形态）。
 *
 * 二者的区别不是"准一点/不准一点"，而是**系统给不给你准**：
 * - [Exact]：`AlarmManager.setExactAndAllowWhileIdle`，用户/系统允许精确闹钟时的形态；
 * - [Window]：降级到 `setWindow`，只保证落在窗口内（默认 10 分钟）。**必须让 UI 看得见** ——
 *   所以 [AlarmSchedulerProvider.degradedTasks] 会列出所有降级中的任务（§8.6「可能偏差」标注）。
 */
sealed interface AlarmMode {
    data object Exact : AlarmMode
    data class Window(val windowMillis: Long) : AlarmMode
}

/**
 * 闹钟系统的最小操作面（§8.6 的投递缝；`:app` 装配层注入真实 [android.app.AlarmManager]）。
 *
 * 为什么要有这个缝：[SchedulerProvider] 的实现方是 Android 框架，而框架对象在 JVM 单测里
 * 不可构造。把"什么时候响、按哪种模式响"的**决策**留在 provider 里，把"真的去 arm 一个闹钟"
 * 压成这道窄缝 —— 于是预拉提前量、精确降级窗口、取消幂等这些真正会错的逻辑全都有单测，
 * 而 [android.app.AlarmManager] 的调用只剩一层无判断的搬运（见 `AndroidAlarmPort`）。
 */
interface AlarmPort {

    /** 当前是否允许精确闹钟（API 31+ 的 `canScheduleExactAlarms()`，含用户手动关闭的情形）。 */
    val canScheduleExact: Boolean

    /** 为 [taskId] arm 一个闹钟（同一 taskId 再次 arm = 替换，不是叠加）。 */
    fun arm(taskId: String, atMillis: Long, mode: AlarmMode)

    /** 撤掉 [taskId] 的闹钟（幂等：没 arm 过也是空操作）。 */
    fun cancel(taskId: String)
}

/**
 * Android 触发源实现（docs §8.6「Android 实现（AlarmManager setExactAndAllowWhileIdle
 * → receiver）在 :app 装配层」）：把 [SchedulerProvider.registerTrigger] 翻译成闹钟。
 *
 * 三条语义，逐条对着设计：
 * 1. **预拉**（§8.6「预热烈钟 `scheduledAt - 60s` 先拉起进程，引擎进程需时 ~1s」）：
 *    实际 arm 时刻 = `targetFireAtMillis - wakeAheadMillis`，provider **不把这个提前量藏起来** ——
 *    [wakeAheadMillis] 是 `SchedulerProvider` 契约字段，测试与调用方都能读到同一份值。
 * 2. **提前量不能跑到过去**：排期已到/已过时夹到当前时刻（`clock()`），让闹钟立刻响。
 *    否则一个负延迟的闹钟要靠 AlarmManager 兜"立即触发"的语义，而各家 ROM 对负值处理不一。
 * 3. **精确不可用时降级 setWindow 并记账**（§8.6「alarm 降 setWindow 并在 UI 标注『可能偏差』）：
 *    [AlarmMode.Window] 的宽由 [degradeWindowMillis] 给；降级中的任务进 [degradedTasks]，
 *    能力中心据此显式标注，**不静默降级**（静默降级 = 用户以为 9:00 整跑，实际 9:07 跑）。
 *
 * 取消幂等由 [TriggerHandle.cancel] 直接落到 [AlarmPort.cancel]；重复取消/取消未注册任务
 * 都不抛错（`Scheduler.cancel` 在并发路径上会取消同一个任务两次）。
 *
 * 本类**不认识 runId、不认识脚本**：它只回答"什么时候把 taskId 交回给 [com.autoscript.
 * appservice.scheduler.core.Scheduler.onTrigger]"，投递参数与幂等全在 scheduler 侧。
 */
class AlarmSchedulerProvider(
    private val port: AlarmPort,
    /** 预拉提前量（§8.6 守时契约）：引擎进程冷启动约 1s，60s 留足余量。 */
    override val wakeAheadMillis: Long = DEFAULT_WAKE_AHEAD_MILLIS,
    /** 精确闹钟不可用时的 setWindow 窗口宽度（§8.6：「可能偏差」的上界，UI 要显式呈现）。 */
    private val degradeWindowMillis: Long = DEFAULT_DEGRADE_WINDOW_MILLIS,
    /** 提前量夹取用的当前时刻（测试注入给定值钟，复现"排期已到"）。 */
    private val clock: () -> Long = { System.currentTimeMillis() },
) : SchedulerProvider {

    init {
        require(wakeAheadMillis >= 0) { "wakeAheadMillis 不得为负: $wakeAheadMillis" }
        require(degradeWindowMillis > 0) { "degradeWindowMillis 必须 > 0（0 等于立即触发，不是窗口）: $degradeWindowMillis" }
    }

    /**
     * 降级中的任务（taskId → 最近一次降级的排期时刻）。有界：一个在册任务一条，
     * 任务取消/重新拿到精确闹钟时移除（[Exact] 分支即清）。
     */
    private val degraded = LinkedHashMap<String, Long>()

    override suspend fun registerTrigger(targetFireAtMillis: Long, taskId: String): TriggerHandle {
        // 提前量只向前推、不向后扯：排期已到就立刻 arm 到当前时刻（语义 2）。
        val fireAt = maxOf(targetFireAtMillis - wakeAheadMillis, clock())
        if (port.canScheduleExact) {
            degraded.remove(taskId)
            port.arm(taskId, fireAt, AlarmMode.Exact)
        } else {
            degraded[taskId] = targetFireAtMillis
            port.arm(taskId, fireAt, AlarmMode.Window(degradeWindowMillis))
        }
        return TriggerHandle {
            port.cancel(taskId)
            degraded.remove(taskId)
        }
    }

    override suspend fun cancelTrigger(handle: TriggerHandle) = handle.cancel()

    /** 当前降级为 setWindow 的任务快照（能力中心/UI 标注「可能偏差」用）。 */
    fun degradedTasks(): Map<String, Long> = synchronized(degraded) { degraded.toMap() }

    /** 该任务此刻是否处于降级投递（便捷判定，等价于 [degradedTasks] 的 containsKey）。 */
    fun isDegraded(taskId: String): Boolean = synchronized(degraded) { taskId in degraded }

    companion object {
        /** 引擎进程冷启动约 1s，60s 留足余量（与 [SchedulerProvider.wakeAheadMillis] 缺省同值）。 */
        const val DEFAULT_WAKE_AHEAD_MILLIS: Long = 60_000

        /** 降级窗口 10 分钟：setWindow 的最小可用宽度，再窄就失去窗口意义。 */
        const val DEFAULT_DEGRADE_WINDOW_MILLIS: Long = 600_000
    }
}
