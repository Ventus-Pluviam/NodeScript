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
 * 本版表单只提供 once/daily 两态（cron 不给入口 —— 装配层反正会拒，给入口才是骗）。
 */
data class RegistrationForm(
    val name: String = "",
    val projectId: String = "",
    val scriptPath: String = "",
    /** true = 一次（延迟 N 秒）；false = 每日定点。 */
    val once: Boolean = true,
    val delaySecondsText: String = "60",
    val hourText: String = "9",
    val minuteText: String = "0",
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
        schedule = if (once) {
            ScheduleSpec.Once(parseLong(delaySecondsText, "延迟秒数"))
        } else {
            ScheduleSpec.Daily(
                parseInt(hourText, "小时"),
                parseInt(minuteText, "分钟"),
            )
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
