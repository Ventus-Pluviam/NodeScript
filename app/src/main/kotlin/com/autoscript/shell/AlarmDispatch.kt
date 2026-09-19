package com.autoscript.shell

import com.autoscript.appservice.scheduler.core.Scheduler
import com.autoscript.appservice.scheduler.core.TriggerSource

/**
 * 闹钟回投缝（docs §8.6「AlarmManager setExactAndAllowWhileIdle → receiver」的最后一跳）：
 * 把 taskId 交回给 [Scheduler.onTrigger]，让 runNonce/意图日志/dispatcher 全链路走正常的
 * 调度入口 —— 而不是在接收器里自己 new 一个 run（那会绕过§8.5 的幂等锚点）。
 */
fun interface AlarmRoute {
    /** 由闹钟触发的投递。`scheduledAt` 取闹钟的真实排期（TIMED 来源），不用当前时刻。 */
    suspend fun fire(taskId: String)
}

/**
 * taskId → [AlarmRoute] 的分派，外加**"没接路线时如实记账"**。
 *
 * 为什么单独一个类而不是写在 `Application.onAlarmFired` 里：接收器响在哪个时刻不取决于装配
 * 进度 —— 闹钟可以在壳还没装完（或装完后被系统回收又重启）时就响。此时如果静默 return，
 * 表现是"任务这一轮永远不跑，且再没有下一轮"（一次性任务不会重排），用户侧看不到任何原因。
 * 所以这里把漏掉的投递记下来（[missed]），能力中心据此如实呈现「闹钟已响但调度未就绪」。
 *
 * [fire] 的返回值 = 是否真的投递了（调用方据此决定要不要额外上报）。
 */
class AlarmDispatch(
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    @Volatile
    private var route: AlarmRoute? = null

    private val lock = Any()

    /** 漏投的闹钟（taskId → 记录时刻）。有界策略：同一 taskId 反复漏只留最新一条。 */
    private val missed = LinkedHashMap<String, Long>()

    /** 安装/摘除回投路线（Application 装配完成后调用；null = 回到"记账不投递"状态）。 */
    fun install(route: AlarmRoute?) {
        this.route = route
    }

    /** 当前是否已接路线。 */
    val isRouted: Boolean get() = route != null

    suspend fun fire(taskId: String): Boolean {
        val current = route
        if (current == null) {
            recordMissed(taskId)
            return false
        }
        current.fire(taskId)
        return true
    }

    /**
     * 记一次漏投（同步版）：**装配前**（壳还没装完/被回收重启）的闹钟走这里 ——
     * 接收器仍在广播的 `goAsync()` 窗口里，能同步记账并 `finish()`，不必为一个 map 插入起协程。
     *
     * 与「无路线时 [fire] 记账」是同一个口径（同一本账、同一个 [clock]），区别只在入口：
     * 装配前没有 receiver→route 的对象链，直接记账。
     */
    fun recordMissed(taskId: String) = synchronized(lock) { missed[taskId] = clock() }

    /** 漏投快照（UI/诊断用；拷贝，不被外部改动）。 */
    fun missed(): Map<String, Long> = synchronized(lock) { missed.toMap() }

    /** 取走漏投记录（UI 呈现过一次后清账，避免同一条提示永远挂着）。 */
    fun drainMissed(): Map<String, Long> = synchronized(lock) {
        val snapshot = missed.toMap()
        missed.clear()
        snapshot
    }
}

/** [Scheduler] 的 [AlarmRoute] 适配：onTrigger 的 TIMED 来源 + 闹钟真实排期。 */
class SchedulerAlarmRoute(
    private val scheduler: Scheduler,
    /** 排期时刻来源：默认取真实当前时刻；测试/重投路径可注入上一轮排期。 */
    private val scheduledAt: () -> Long = { System.currentTimeMillis() },
) : AlarmRoute {
    override suspend fun fire(taskId: String) {
        scheduler.onTrigger(taskId, TriggerSource.TIMED, scheduledAt())
    }
}
