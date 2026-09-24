package com.autoscript.ui

import com.autoscript.domain.host.ScheduleSpec
import com.autoscript.domain.host.ScreenRequirement
import com.autoscript.domain.host.TaskRegistration

/**
 * 任务中心「登记任务」表单的纯状态（文本字段 → [TaskRegistration] 的解析在提交时跑一次）。
 *
 * 为什么数据与解析住这里而不是 Compose 里：解析是判断（哪一格空了、哪个不是整数），
 * 必须可 JVM 测（[RegistrationFormTest]）；`@Composable` 里的校验没法进 `:ui` 的单测门
 * （compose 没有裸 kotlinc 配方，见 CLAUDE.md）。文本全部 **trim 后**进 DTO ——
 * 表格里顺手打的空格不该变成"name 不得为空"。
 *
 * 两层校验的分工：
 * - 本层管**形状**（数字格填了什么）：非整数 → 本处抛，消息点名哪一格；
 * - **语义**（空串/越界/cron 拒绝）留给 `:app` 的 `TaskCenterOps.toScheduledTask`
 *   —— 与桥侧 `workManager.create` 同一套闸门，本表单不再抄一份规则（两份必漂移）。
 *   越界（如小时 99）因此在提交后的 [TaskCenterState.opError] 里原文出现，而不是
 *   表单内联 —— 单一事实来源优先于"每格即时反馈"。
 *
 * 本版表单提供 once/daily/cron 三态（cron 表达式格；语义校验仍归装配层 ——
 * `TaskCenterOps.toScheduledTask` 经 [CronTab.parse] 拒非法表达式）。
 */
data class RegistrationForm(
    val name: String = "",
    val projectId: String = "",
    val scriptPath: String = "",
    /** 排期三态：一次（延迟 N 秒）/ 每日定点 / cron 表达式。 */
    val kind: ScheduleKind = ScheduleKind.ONCE,
    val delaySecondsText: String = "60",
    val hourText: String = "9",
    val minuteText: String = "0",
    val cronText: String = "0 9 * * *",
    val screen: ScreenRequirement = ScreenRequirement.ANY,
) {
    /**
     * 文本 → [TaskRegistration]（trim；形状非法**抛** [IllegalArgumentException]，
     * 消息点名格子 —— 由调用方收进 [TaskCenterState.opError]，不吞）。
     */
    fun toRegistration(): TaskRegistration = TaskRegistration(
        name = name.trim(),
        projectId = projectId.trim(),
        scriptPath = scriptPath.trim(),
        schedule = when (kind) {
            ScheduleKind.ONCE -> ScheduleSpec.Once(parseLong(delaySecondsText, "延迟秒数"))
            ScheduleKind.DAILY -> ScheduleSpec.Daily(
                parseInt(hourText, "小时"),
                parseInt(minuteText, "分钟"),
            )
            // cron 表达式只 trim 不解析：非法与否由装配层闸门裁决（见类 KDoc），
            // 本层不抄语义规则（两份必漂移）。
            ScheduleKind.CRON -> ScheduleSpec.Cron(cronText.trim())
        },
        screen = screen,
    )

    private fun parseLong(text: String, label: String): Long =
        text.trim().toLongOrNull()
            ?: throw IllegalArgumentException("${label}必须是整数，收到「$text」")

    private fun parseInt(text: String, label: String): Int =
        text.trim().toIntOrNull()
            ?: throw IllegalArgumentException("${label}必须是整数，收到「$text」")
}

/** 登记表单的排期三态（与 [ScheduleSpec] 三态一一对应；`once` 布尔已并入本枚举）。 */
enum class ScheduleKind {
    ONCE,
    DAILY,
    CRON,
}
