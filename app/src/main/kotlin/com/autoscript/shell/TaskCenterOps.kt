package com.autoscript.shell

import com.autoscript.appservice.scheduler.core.ScheduledTask
import com.autoscript.appservice.scheduler.core.ScreenGuarantee
import com.autoscript.appservice.scheduler.core.TimedSchedule
import com.autoscript.domain.host.ScheduleSpec
import com.autoscript.domain.host.TaskRegistration
import java.time.ZoneId
import java.util.UUID

/**
 * 任务登记的映射与校验（§8.6 操作面「登记」；`:domain` [TaskRegistration] → 调度器 `ScheduledTask`）。
 *
 * **为什么单独一个文件**：与 [TaskCenterRead]/[ConsoleRead] 同一条理由 —— `Application`
 * 在 JVM 单测里构造不出来，而"入参怎么校验、哪个入口拒绝"这段判断必须可测。读口那份是
 * 快照拼装，本对象是**写入口的单一闸门**：UI 操作面经 [AssembledShell.registerTask] 走这里，
 * 规则与桥侧 `workManager.create`（[WorkManagerNamespaceHandler]）**逐条对齐** ——
 * 两边各自测住同一套语义（空串拒绝 / cron 拒绝 / 越界拒绝），任何一侧单改先红。
 *
 * 三条纪律：
 * - **校验在此、DTO 纯数据**：[TaskRegistration] 不带 `init` —— 呈现层测试构造合法样本
 *   不该背校验语义；闸门只此一处（桥侧的 JSON 解析留在 handler，形状错与语义错分开）；
 * - **cron 如实拒绝**（IllegalArgumentException，UI 原文显示）：P1 排期未落地 ——
 *   登记一条算不出下一跳、看起来却"在册"的任务，是把问题推迟到用户看不见的地方
 *   （与桥侧 `ERR_NOT_IMPLEMENTED` 同一口径，那边要错误码故包成 `AutojsException`）；
 * - **ScreenGuarantee 按名对表**（不用 `ordinal`）：与 [TaskCenterRead.mapScreen] 反向
 *   同源，两边枚举同集由 `TaskCenterReadTest` 的同集断言守住 —— 调度器加值先红再谈映射。
 */
object TaskCenterOps {

    /**
     * 入参 → 调度器任务（不触碰调度器 —— 纯映射，落盘/闹钟由 [AssembledShell.registerTask] 走
     * `Scheduler.schedule` 的 store-first 纪律）。
     *
     * @throws IllegalArgumentException 空 name/projectId/scriptPath、once 负延迟、
     *   daily 钟点越界（`TimedSchedule.Daily` 的 init require）、timeout ≤ 0、
     *   非法 timezone、cron 表达式（P1 未落地）。
     */
    fun toScheduledTask(registration: TaskRegistration): ScheduledTask {
        val name = registration.name.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("name 不得为空串")
        val projectId = registration.projectId.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("projectId 不得为空串")
        val scriptPath = registration.scriptPath.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("scriptPath 不得为空串")
        val schedule = when (val s = registration.schedule) {
            is ScheduleSpec.Once -> {
                if (s.delaySeconds < 0) {
                    throw IllegalArgumentException("once 需要非负 delaySeconds：${s.delaySeconds}")
                }
                TimedSchedule.Once(s.delaySeconds)
            }
            // Daily 的 init require 负责越界拒绝（hour 0..23 / minute 0..59），消息原样上抛。
            is ScheduleSpec.Daily -> TimedSchedule.Daily(s.hourOfDay, s.minuteOfHour)
            is ScheduleSpec.Cron -> throw IllegalArgumentException(
                "cron 排期 P1 未落地（见 §8.6）：本版只接受一次（once）/ 每日（daily）",
            )
        }
        registration.scriptTimeoutMillis?.let {
            if (it <= 0) throw IllegalArgumentException("scriptTimeoutMillis 必须 > 0：$it")
        }
        val timezone = registration.timezoneId?.let { zoneId ->
            try {
                ZoneId.of(zoneId)
            } catch (_: Exception) {
                throw IllegalArgumentException("timezone 非法: $zoneId")
            }
        } ?: ZoneId.systemDefault()
        return ScheduledTask(
            // id 缺省服务端分配（与桥侧 create 一致；空串同样当没给）。
            id = registration.id?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
            name = name,
            projectId = projectId,
            scriptPath = scriptPath,
            schedule = schedule,
            screen = ScreenGuarantee.valueOf(registration.screen.name),
            args = registration.args,
            scriptTimeoutMillis = registration.scriptTimeoutMillis,
            timezone = timezone,
            enabled = registration.enabled,
        )
    }
}
