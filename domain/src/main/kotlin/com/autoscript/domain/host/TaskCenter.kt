package com.autoscript.domain.host

import com.autoscript.domain.scripts.RunState

/**
 * 任务中心读口（§8.6 排期 + §8.5 执行档案/恢复账）的呈现面 DTO。
 *
 * **为什么这些 DTO 住 `:domain` 而不是直接暴露调度器的类型**：`ScheduledTask` /
 * `TimedSchedule` / `ScreenGuarantee` 住 `:app-service:scheduler`，只有 `:app` 见得到；
 * 而呈现层在 `:ui`（只许依赖 `:domain`）。让 `:ui` 认识调度器类型 = 给 `:ui` 加一条
 * 到 `:app-service` 的依赖边（§6 禁止，`ModuleGraphTest` 量化）。解法与能力中心同形：
 * `:domain` 出**事实 DTO**，`:app` 装配时把调度器类型映射过来，`:ui` 只画。
 *
 * **映射丢信息这件事怎么防**：DTO 逐字段对应调度器的真值，不做聚合（`ScheduleSpec`
 * 三态与 `TimedSchedule` 一一对应、`ScreenRequirement` 与 `ScreenGuarantee` 同名同值），
 * 且 `:app` 侧有一条枚举**逐名对表**的测试 —— 调度器将来加一个 `ScreenGuarantee`
 * 值，那条测试立刻红，而不是在 UI 上静默少一档。
 *
 * **下一跳时刻（[ScheduledTaskRow.nextFireAtMillis]）不在本层算**：DST 边界、`Daily`
 * 跨日推进这些是调度数学（`TimedSchedule.nextFireAfter` 是唯一时序来源，§8.6），
 * 在这里重写一遍就是第二套算法 —— 本层只接结果。
 */
data class TaskCenterSnapshot(
    /** 已登记的任务（含停用的；不含已被取消的）。 */
    val tasks: List<ScheduledTaskRow>,
    /**
     * **未结算**的执行（`RunArchive.unfinished`，§8.5）。
     *
     * 生产实现里这一栏**应当恒空**：`Scheduler.recordLink` 落档案即终态，恢复路径
     * 两条孤儿结算负责补账。非空 = 上面某条没走完 —— 用户该看到"有执行没结算"，
     * 而不是把一条 RUNNING 档案当成"正在跑"（那正是 `unfinished()` 只增不减的那种谎）。
     *
     * 某项目的**执行历史**（`RunArchive.recordsOfProject`）不在这里：那需要一个项目
     * 选择器，本片只做"现在有什么在跑/没跑完"，历史列表随项目浏览落地。
     */
    val runs: List<RunRow>,
    /** 上次启动的恢复账；null = 本次进程还没跑过恢复（不是"恢复了 0 条"）。 */
    val recovery: RecoveryRow?,
)

/**
 * 一条已登记的定时任务。
 *
 * @property nextFireAtMillis 下一次触发时刻；**null 有两种含义**，呈现层靠 [enabled]
 *   与 [schedule] 分辨：任务停用（不会跑）或计划本身算不出下一跳（[ScheduleSpec.Cron]）。
 *   绝不用 `now + 24h` 之类的兜底去填 —— 那是编一个时间给用户看。
 * @property degraded 本次投递已降级为 `setWindow`（精确闹钟被收回，§8.6 承诺要标注
 *   「可能偏差」）。true = 会跑但**不保证守时**，不是失败。
 */
data class ScheduledTaskRow(
    val id: String,
    val name: String,
    val projectId: String,
    val scriptPath: String,
    val schedule: ScheduleSpec,
    val screen: ScreenRequirement,
    val enabled: Boolean,
    val nextFireAtMillis: Long?,
    val degraded: Boolean,
)

/**
 * 调度计划（`TimedSchedule` 的呈现侧对偶；三态一一对应，字段名取用户能懂的那个）。
 *
 * [Cron] 仍留在契约里而不是删掉：`TimedSchedule.Cron` 多数时候算得出下一跳
 * （`nextFireAfter` 经调度器的 `CronTab`），但注册表里可能躺着不可能日期
 * （如 2 月 30 号，从无命中回 null）或用户手改坏掉的行（非法回 null）。
 * 装作它不存在会让那条任务从列表里凭空消失 —— 呈现层的责任是如实说表达式本身，
 * 下一跳为 null 时不显示时间（见 [ScheduledTaskRow.nextFireAtMillis] 两义）。
 */
sealed interface ScheduleSpec {
    /** 相对登记时刻的延迟（一次性）。 */
    data class Once(val delaySeconds: Long) : ScheduleSpec

    /** 每日定点。 */
    data class Daily(val hourOfDay: Int, val minuteOfHour: Int) : ScheduleSpec

    /** cron 表达式（5 字段 `分 时 日 月 周`；不可能日期/非法行算不出下一跳）。 */
    data class Cron(val expr: String) : ScheduleSpec
}

/**
 * 屏幕契约（`ScreenGuarantee` 的呈现侧对偶）。
 * 三个字面量与调度器那一份**同名**，`:app` 侧按名对表（见 [ScheduledTaskRow] KDoc）。
 */
enum class ScreenRequirement {
    /** 需要亮屏（画面类能力）：投递前要求唤醒锁 + 亮屏确认，保活没生效时会被如实拒绝。 */
    SCREEN_ON,

    /** 任意屏幕状态：守时尽力而为。 */
    ANY,

    /** 仅熄屏：锁屏下正常投递。 */
    SCREEN_OFF,
}

/**
 * 一条执行记录（`RunRecord` 的呈现侧投影；[state] 直接复用 `:domain` 的 `RunState`）。
 *
 * @property intentRunId 双 id 关联的另一半（§8.5，`EngineRunLink`）——「这次执行是为什么
 *   发生的」要从意图日志那一侧读。null = 无关联（独立执行，或关联那一步没写成：
 *   后者是孤儿记录，`recordLink` 与档案写入不同事务的已知窗口）。
 */
data class RunRow(
    val engineRunId: Long,
    val intentRunId: Long?,
    val projectId: String,
    val scriptPath: String,
    val state: RunState,
    val startedAtMillis: Long?,
    val finishedAtMillis: Long?,
)

/**
 * 上次启动的恢复账（`RecoverySnapshot` 的呈现侧投影，§8.5/§8.6）。
 *
 * 三条事实分开，谁都不替谁说话：
 * - [total] 恢复路径处理过的遗留意向条数；
 * - [expired] 其中因过了约定时刻而**只封口、不重投**的条数（§8.6 deadline）——
 *   它既不是"失败"也不是"没恢复"，是一条独立结论，所以单列；
 * - [failureText] 恢复整体失败的原因原文（null = 没失败）。失败时前三项无意义
 *   （一条都没处理成），呈现层必须先看这一条。
 */
data class RecoveryRow(
    val total: Int,
    val expired: Int,
    val failureText: String?,
) {
    /** 真的重投出去的条数（`total - expired`）；失败时为 0 —— 谁都没投出去。 */
    val retried: Int get() = if (failed) 0 else total - expired

    val failed: Boolean get() = failureText != null

    val ok: Boolean get() = !failed
}


/**
 * 任务登记入参（任务中心操作面的写口载荷；§8.6「登记/取消/立即执行」）。
 *
 * 为什么住 `:domain` 而不是 `:ui`/`:app`：`:ui` 表单要构造它、`:app` 装配层要消费它，
 * 中间隔一个 `:domain` 才不让呈现层 import 装配层（与 [ScheduledTaskRow] 同一条依赖方向铁律）。
 *
 * 校验**不在本 DTO 上**：镜像 `workManager.create` 的那套规则（空串/越界/cron 拒绝）住在
 * `:app` 的 `TaskCenterOps.toScheduledTask` —— 桥与 UI 两个登记入口过**同一套**映射，
 * DTO 保持纯数据（呈现层测试不必背校验语义）。
 *
 * @property schedule 三态都可给：[ScheduleSpec.Cron] 由装配层经调度器的 `CronTab.parse`
 *   校验（与桥侧 `workManager.create` 同一口径）—— 非法表达式登记时即拒；
 *   不可能日期是合法表达式，登记放行、排期回 null 留名不续排。
 * @property id null = 登记时由服务端分配（与桥侧一致；空串同样当没给）。
 * @property timezoneId null = 系统默认时区（Daily 的 DST 边界靠它，见 `TimedSchedule.nextFireAfter`）。
 * @property enabled 缺省 true（本版操作面**没有**启停开关 —— 该字段只为与
 *   `workManager.create` 载荷对齐；停用过的任务仍从任务列表里取消）。
 */
data class TaskRegistration(
    val name: String,
    val projectId: String,
    val scriptPath: String,
    val schedule: ScheduleSpec,
    val screen: ScreenRequirement = ScreenRequirement.ANY,
    val args: List<String> = emptyList(),
    val scriptTimeoutMillis: Long? = null,
    val timezoneId: String? = null,
    val enabled: Boolean = true,
    val id: String? = null,
)
