package com.autoscript.ui.state

import com.autoscript.domain.host.RecoveryRow
import com.autoscript.domain.host.RunRow
import com.autoscript.domain.host.ScheduleSpec
import com.autoscript.domain.host.ScheduledTaskRow
import com.autoscript.domain.host.TaskCenterSnapshot
import com.autoscript.domain.scripts.RunState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 任务中心呈现态（纯数据，Compose 之外可 JVM 测）。
 *
 * 与 [CapabilityCenterState] 同一条纪律，三件事分开记账，谁都不替谁说话：
 * - [load] —— 还没读到 / 读失败（带原文）/ 读到了。**不冒充**「一条任务都没有」：
 *   后者是"读成功且真的没登记过任务"，两者对用户是完全不同的结论（前者要重试，
 *   后者要新建任务）；
 * - 任务行/未结算执行/恢复账各自成段，不揉成一个"状态"（后者见 [RecoveryRowState]）。
 *
 * **操作面三字段与读账分开记账**（§8.6 登记/取消/立即执行）：
 * - [opError] ≠ 读失败：操作失败（校验不过/壳未装配/收口中）**不清任务清单** ——
 *   清单还是上次读到的事实，把它一并抹掉会让用户以为任务全没了；
 * - [opNotice] 是上一次操作的回执，只由操作成功写入；刷新现取随 [of] 归零
 *   （现取纪律：不缓存陈旧提示）；
 * - [opInFlight] 挂起期间禁用操作按钮（立即执行要挂到本次执行结算，见
 *   `HostSummary.runTaskNow` KDoc —— 不禁用就会双击双投）。
 *
 * 时间与时长在这里格式化（不在 Compose 里）：格式化是判断（相对时间怎么念、多久算
 * "刚跑完"），必须可测；`@Composable` 里的 `DateTimeFormatter` 也是每次重组都重建对象。
 */
data class TaskCenterState(
    val load: LoadState,
    val tasks: List<TaskRowState>,
    val unfinishedRuns: List<RunRowState>,
    val recovery: RecoveryRowState?,
    /** 渲染时刻（由调用方给，见 [of]）—— 类内不读 `System.currentTimeMillis()`，否则不可测。 */
    val nowMillis: Long,
    val zone: ZoneId,
    /** 上一次**操作**失败原文（≠ 读失败：两条账分开，见类 KDoc）。 */
    val opError: String? = null,
    /** 上一次**操作**成功回执（刷新/切页现取即清，不缓存）。 */
    val opNotice: String? = null,
    /** 有操作在挂起中（立即执行要等执行结算）—— 按钮禁用防双击双投。 */
    val opInFlight: Boolean = false,
    /**
     * 挂起中的「立即执行」落在**哪条**任务上（其余操作为 null）。挂起要挂到执行结算
     * （排队 10s + 脚本超时，最长 ~40s），那一行尾的播放钮在这期间画成**转圈的开口弧**
     * 而不是静止的三角 —— 「它真的在跑」与「其他按钮暂时不可用」是两件事，
     * 一个全局布尔画不出这个区分。
     */
    val opTargetTaskId: String? = null,
) {
    companion object {
        /**
         * 首帧哨兵：没读到过（**不是**"一条任务都没有"，见类 KDoc）。
         * `nowMillis` 取 0：这个实例不会渲染任何相对时间（没读到就没有行）。
         */
        val NOT_LOADED = TaskCenterState(
            load = LoadState.NotLoaded,
            tasks = emptyList(),
            unfinishedRuns = emptyList(),
            recovery = null,
            nowMillis = 0L,
            zone = ZoneId.systemDefault(),
        )

        /**
         * 读取成功。
         *
         * [nowMillis]/[zone] 由调用方注入而不是就地取当前时间：相对时间文案
         * （"3 分钟前触发"）要能被测试钉死，也要能在同一帧里对所有行用**同一个** now
         * —— 逐行各取一次 `now` 会让同一帧里的两行差几毫秒，排序看着像抖动。
         */
        fun of(
            snapshot: TaskCenterSnapshot,
            nowMillis: Long,
            zone: ZoneId = ZoneId.systemDefault(),
        ): TaskCenterState = TaskCenterState(
            load = LoadState.Loaded,
            tasks = snapshot.tasks.map { TaskRowState.of(it, nowMillis, zone) },
            unfinishedRuns = snapshot.runs.map { RunRowState.of(it, nowMillis, zone) },
            recovery = snapshot.recovery?.let { RecoveryRowState.of(it) },
            nowMillis = nowMillis,
            zone = zone,
        )

        /**
         * 读取失败。**保留原异常文案** —— 与 `CapabilityCenterState.failed` 同一手法：
         * 显示「尚未读取」会把失败说成没读。
         */
        fun failed(t: Throwable): TaskCenterState = TaskCenterState(
            load = LoadState.of(t),
            tasks = emptyList(),
            unfinishedRuns = emptyList(),
            recovery = null,
            nowMillis = 0L,
            zone = ZoneId.systemDefault(),
        )
    }
}

/**
 * 一条任务的呈现态。
 *
 * @property scheduleText 计划的人话（[ScheduleText.describe]）。
 * @property nextFireText 下一跳的人话；null = 不显示（停用任务或算不出下一跳）——
 *   **不编一个时间**，见 [ScheduledTaskRow.nextFireAtMillis] 的两义说明。
 * @property degraded 降级投递中（§8.6「可能偏差」）：会跑但不保证守时。
 */
data class TaskRowState(
    val id: String,
    val name: String,
    val scriptPath: String,
    val scheduleText: String,
    val nextFireText: String?,
    val enabled: Boolean,
    val degraded: Boolean,
    /**
     * 一次性任务（[ScheduleSpec.Once]）：「立即执行」触发即终态化出册（调度器语义）——
     * 回执要点破"跑完就出册"，否则刷新后卡片消失会被读成"被取消了"。
     */
    val once: Boolean = false,
    /** 下一跳原始时刻（epoch ms）——「按时间排序」档的键；null = 算不出（排序时排最后）。 */
    val nextFireAtMillis: Long? = null,
) {
    /**
     * 卡片左侧那一列的**全部**标记（决定小圆点画几个、什么色）。
     *
     * 收在一个方法里而不是让 Composable 自己 `if` 一遍：TG 会话列表左侧就是一个
     * "置顶/静音/草稿"的图标列，规则同样是集中判读的一条。判据是"用户会因此改变什么动作"：
     * - 停用 → PROBLEM（该去开回来）；
     * - 降级 → ATTENTION（会跑，可能偏差，不急）；
     * - 一次性 → NEUTRAL（调度器语义，不是异常）。
     */
    fun badges(): List<Pair<StatusTone, String>> = buildList {
        if (!enabled) add(StatusTone.PROBLEM to "已停用（不会自动投递；仍可立即执行）")
        if (degraded) add(StatusTone.ATTENTION to "降级投递（可能偏差）：精确闹钟不可用，已按窗口投递")
        if (once) add(StatusTone.MUTED to "一次性任务：执行过后自动移出注册表")
    }

    companion object {
        fun of(task: ScheduledTaskRow, nowMillis: Long, zone: ZoneId): TaskRowState = TaskRowState(
            id = task.id,
            name = task.name,
            scriptPath = task.scriptPath,
            scheduleText = ScheduleText.describe(task.schedule),
            nextFireText = task.nextFireAtMillis?.let { ScheduleText.absolute(it, zone) },
            enabled = task.enabled,
            degraded = task.degraded,
            once = task.schedule is ScheduleSpec.Once,
            nextFireAtMillis = task.nextFireAtMillis,
        )
    }
}

/**
 * 任务列表的排序档（TG 联系人页的 by-name / by-time 两态，
 * `SharedConfig.toggleSortContactsByName`）。
 *
 * 图标语义与 TG 同款：**顶栏画的是「切过去的那一档」**（`ContactsActivity`：
 * `sortItem.setIcon(sortByName ? msg_contacts_time : msg_contacts_name)`）——
 * 按名称排序时顶栏显示时钟（点它切到按时间），按时间时显示字母 A。
 */
enum class TaskSort { NAME, TIME }

/**
 * 任务行的排序（呈现层的视图，不进读口 —— 与项目页 `FileSort` 同一条分工）。
 *
 * - 按名称：大小写不敏感的字典序；
 * - 按时间：下一跳早的在先；**算不出下一跳的排最后**（它们没有时刻可比，不该
 *   冒充最早也不该把有时刻的挤散），同刻并列按名称收尾 —— 排序稳定可预期。
 */
fun sortedTasks(tasks: List<TaskRowState>, sort: TaskSort): List<TaskRowState> = when (sort) {
    TaskSort.NAME -> tasks.sortedBy { it.name.lowercase(Locale.ROOT) }
    TaskSort.TIME -> tasks.sortedWith(
        compareBy<TaskRowState> { it.nextFireAtMillis ?: Long.MAX_VALUE }
            .thenBy { it.name.lowercase(Locale.ROOT) },
    )
}

/** 任务行的搜索命中：名称或脚本路径含词（大小写不敏感；空词全命中）。 */
fun TaskRowState.matches(query: String): Boolean {
    if (query.isBlank()) return true
    val q = query.trim().lowercase(Locale.ROOT)
    return name.lowercase(Locale.ROOT).contains(q) ||
        scriptPath.lowercase(Locale.ROOT).contains(q)
}

/**
 * 一条未结算执行的呈现态（[TaskCenterSnapshot.runs] 的一行）。
 *
 * 这一栏的文案必须点破"它**不是**正在跑"：`RunArchive.unfinished()` 只增不减，
 * 生产实现里应当恒空，非空即"上一进程留下的、没结算完的记录"（§8.5 两条孤儿结算
 * 没走完）。念成"正在运行"会让用户等一个永远不会结束的东西。
 */
data class RunRowState(
    val engineRunId: Long,
    val intentRunId: Long?,
    val scriptPath: String,
    val stateLabel: String,
    val startedText: String?,
) {
    companion object {
        fun of(run: RunRow, nowMillis: Long, zone: ZoneId): RunRowState = RunRowState(
            engineRunId = run.engineRunId,
            intentRunId = run.intentRunId,
            scriptPath = run.scriptPath,
            stateLabel = RunStateText.describe(run.state),
            startedText = run.startedAtMillis?.let { ScheduleText.absolute(it, zone) },
        )
    }
}

/** 恢复账的呈现态（§8.5/§8.6）。三笔分开：投了几条、几条过期未投、有没有失败。 */
data class RecoveryRowState(
    val total: Int,
    val expired: Int,
    val retried: Int,
    val failureText: String?,
) {
    /** 恢复账那一段的话（有失败时先说失败 —— 那是唯一需要动手的一条）。 */
    fun text(): String? = failureText?.let { "上次启动恢复失败：$it" } ?: buildString {
        append("上次启动恢复 $total 条，重投 $retried 条")
        if (expired > 0) {
            // 过期不等于没恢复：它是"封口记账、不重投"（§8.6 deadline），单独说。
            append("，其中 $expired 条已过约定时刻（未重投）")
        }
    }

    companion object {
        fun of(row: RecoveryRow): RecoveryRowState = RecoveryRowState(
            total = row.total,
            expired = row.expired,
            retried = row.retried,
            failureText = row.failureText,
        )
    }
}

/**
 * 调度计划的人话。
 *
 * 为什么这层要自己写文案而不复用调度器的 `toString`：数据类的 `toString` 是调试形态
 * （`Daily(hourOfDay=7, minuteOfHour=5)`），而这里的每一句都对应"用户能不能一次读懂
 * 这条任务什么时候跑"。Cron 单独一句是因为它的下一跳**可能算不出**
 * （不可能日期如 2 月 30 号、或注册表手改坏掉的行回 null 留名不续排）——
 * 那时下一跳不显示（见 [TaskRowState.nextFireText]），文案只说表达式本身。
 */
object ScheduleText {

    fun describe(spec: ScheduleSpec): String = when (spec) {
        is ScheduleSpec.Once -> "延迟 ${duration(spec.delaySeconds * 1000)}后执行一次"
        is ScheduleSpec.Daily -> "每天 ${hhmm(spec.hourOfDay, spec.minuteOfHour)}"
        is ScheduleSpec.Cron -> "cron 表达式「${spec.expr}」"
    }

    /** 绝对时刻（本地时区，`MM-dd HH:mm`）—— 下一跳与执行起点共用同一格式。 */
    fun absolute(millis: Long, zone: ZoneId): String =
        FORMAT.format(Instant.ofEpochMilli(millis).atZone(zone))

    private val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

    private fun hhmm(hour: Int, minute: Int): String =
        "%02d:%02d".format(hour, minute)

    /** 时长的人话（秒/分/时三档；不足一分钟说秒，避免"0 分钟"）。 */
    fun duration(millis: Long): String = when {
        millis < 60_000L -> "${millis / 1000} 秒"
        millis < 3_600_000L -> "${millis / 60_000} 分钟"
        else -> "${millis / 3_600_000} 小时"
    }
}

/**
 * 执行状态的用户说法（`RunState` 六态逐态一句）。
 *
 * `PENDING`/`RUNNING` 的措辞刻意带上"档案"两个字：出现在这一栏的它们只可能是孤儿记录
 * （见 [RunRowState] KDoc），而**在途**的执行状态在控制台/首屏那一侧，不在任务中心。
 * 把两种"RUNNING"混成一句话，用户就没法分辨"它在跑"和"它上次没结算"。
 */
object RunStateText {

    fun describe(state: RunState): String = when (state) {
        RunState.PENDING -> "档案停在待投递（上一进程遗物，未结算）"
        RunState.RUNNING -> "档案停在运行中（上一进程遗物，未结算 —— 不是此刻正在跑）"
        RunState.SUCCEEDED -> "成功"
        RunState.FAILED -> "失败"
        RunState.CRASHED -> "崩溃（被杀/OOM/看门狗）"
        RunState.CANCELLED -> "已取消"
    }
}
