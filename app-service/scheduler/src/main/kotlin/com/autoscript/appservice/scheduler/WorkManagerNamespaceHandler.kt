package com.autoscript.appservice.scheduler
import com.autoscript.domain.bridge.generated.WireMethods

import com.autoscript.domain.scripts.CronTab
import com.autoscript.domain.scripts.ScheduledTask
import com.autoscript.appservice.scheduler.core.Scheduler
import com.autoscript.domain.scripts.ScreenGuarantee
import com.autoscript.domain.scripts.TimedSchedule
import com.autoscript.domain.bridge.BridgeRequest
import com.autoscript.domain.bridge.BridgeResponse
import com.autoscript.domain.json.DomainJson
import com.autoscript.domain.bridge.RpcNamespaceHandler
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import java.time.ZoneId

/**
 * `workManager` 命名空间桥处理器（docs §8.6/§9.6 定时 API；JS `workManager.*` 的 Kotlin 对偶）。
 *
 * 归属：自 `:app` 装配包迁入 `:app-service:scheduler` —— 与 `engines` 住
 * `:app-service:runtime` 同形态：**handler 归位实现模块**，
 * 直驱本模块 [Scheduler]（登记/取消/列举）。原驻 `:app` 的理由是 arch 门禁禁
 * `domain.bridge`，本批同门撤该条并量化：handler 只碰转接面三型
 * （BridgeRequest/BridgeResponse/RpcNamespaceHandler），不进引擎/权限/平台。
 * 挂载点仍在 `AppShell.assemble` 恒挂载（调度器是本壳自建的，不经注入缝）——
 * 与 a11y/screen 缝的区别：能力实现在 `:platform`（必须注入）。
 *
 * **创建授权（A5，§11 派生运行入口）**：[create] 会写一条**持久**任务，它此后每次触发
 * 都会拉起一次新的执行 —— 是一条绕过掩码的派生入口（脚本这次被授权窄掩码，但建下的任务
 * 在脚本退出后照样跑）。故装配层注入 [authorizeCreate]：由它按**真实调用身份**算这次要
 * 建的任务**将来会拿到什么授权**，并要求调用方覆盖之；缺注入 = 无授权接缝 → 一律拒
 * （fail closed，见 [authorizeCreate] 的 KDoc）。
 *
 * 线格式（与 `bridge/js` workManager.ts 逐字段对齐，扁平 JSON，无嵌套数组之外的结构）：
 * - `create`：`{id?,name,projectId,scriptPath,schedule:{kind,...},screen?,args?,
 *   scriptTimeoutMillis?,timezone?,enabled?}` → Ok `{"id":"…"}`。
 *   `schedule.kind` = `once`（`delaySeconds`）/`daily`（`hourOfDay`+`minuteOfHour`）；
 *   `cron`（`expr`，5 字段 `分 时 日 月 周`）→ 非法表达式 Err INVALID_PARAM
 *   （校验出处 [CronTab.parse]，与 UI 侧 `TaskCenterOps` 同口径）；
 *   `screen` 缺省 `ANY`；`timezone` 缺省系统默认；`enabled` 缺省 true；
 *   `id` 缺省服务端分配（UUID）。
 * - `cancel`：`{id}` → Ok `true`（幂等：从未登记的 id 照样 true，与 `Scheduler.cancel` 一致）。
 * - `list`：无参 → Ok `[task,…]`（与 create 同一任务形状；按 id 排序）。
 * - 未知方法 → ERR_NOT_IMPLEMENTED；非法载荷 → ERR_INVALID_PARAM。
 *
 * @param authorizeCreate 建任务前的授权判据（A5，§11）。入参 = 被建任务的项目号；
 *   返回 null = 放行，非 null = 拒并给出原因。它由装配层（`:app`）注入 —— 本模块的
 *   arch 门禁禁止依赖 `:domain` 的权限面（`domain.permission..` 在黑名单里），所以
 *   判据（算子掩码、比调用方掩码）只能住在 `:app`；这里只留一个**不含权限类型**的缝。
 *   **null（缺注入）= 全拒**：没接就是"没有授权来源"，按 fail closed 处理，绝不默认放行。
 */
class WorkManagerNamespaceHandler(
    private val scheduler: Scheduler,
    private val authorizeCreate: (suspend (projectId: String) -> String?)? = null,
) : RpcNamespaceHandler() {


    /** 申报方法表（wire-schema 对账挂点）：单源指向生成物 [WireMethods.BY_NS]，不手抄。 */
    override fun methods(): Set<String> = WireMethods.BY_NS.getValue("workManager")

    override suspend fun dispatch(request: BridgeRequest): BridgeResponse = ok(request, payload(request))

    private suspend fun payload(request: BridgeRequest): String? {
        return when (request.method) {
            "create" -> create(DomainJson.decodeObject(requirePayload(request)))
            "cancel" -> {
                val f = DomainJson.decodeObject(requirePayload(request))
                scheduler.cancel(DomainJson.reqStr(f, "id"))
                "true"
            }
            "list" -> DomainJson.encode(scheduler.tasks().map { encodeTask(it) })
            else -> throw AutojsException(
                ErrorCode.ERR_NOT_IMPLEMENTED, "未知 workManager 方法: ${request.method}",
            )
        }
    }

    private suspend fun create(f: Map<String, DomainJson.Value>): String {
        val projectId = DomainJson.reqStr(f, "projectId").takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("projectId 不得为空串")
        // 授权先于一切（A5，§11）：建任务 = 派生运行的持久入口，未过闸不得登记，
        // 更不得先落盘再拒（那就留下一条无人授权的任务）。
        //
        // 注意**不能**写成 `authorizeCreate?.invoke(projectId) ?: "缺判据"`：那样
        // 「接缝存在且放行（返回 null）」与「接缝不存在」会落进同一个分支，
        // 结果是把所有放行都翻成拒绝。两种"没有授权"要分开表达：
        // 接缝缺席 = 装配缺陷（拒，附说明）；接缝回 null = 明确放行。
        val seam = authorizeCreate
            ?: throw AutojsException(
                ErrorCode.ERR_PERMISSION_DENIED,
                "workManager.create 未接授权判据（装配层未注入）：拒绝建立持久任务",
            )
        seam.invoke(projectId)?.let { deny ->
            throw AutojsException(ErrorCode.ERR_PERMISSION_DENIED, deny)
        }
        val schedule = parseSchedule(DomainJson.reqObj(f, "schedule"))
        val task = ScheduledTask(
            id = (f["id"] as? DomainJson.Value.S)?.v?.takeIf { it.isNotBlank() }
                ?: java.util.UUID.randomUUID().toString(),
            name = DomainJson.reqStr(f, "name").takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("name 不得为空串"),
            projectId = projectId,
            scriptPath = DomainJson.reqStr(f, "scriptPath").takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("scriptPath 不得为空串"),
            schedule = schedule,
            screen = when (val v = f["screen"]) {
                null, is DomainJson.Value.Null -> ScreenGuarantee.ANY
                is DomainJson.Value.S -> try {
                    ScreenGuarantee.valueOf(v.v)
                } catch (_: IllegalArgumentException) {
                    throw IllegalArgumentException("screen 非法: ${v.v}（SCREEN_ON/ANY/SCREEN_OFF）")
                }
                else -> throw IllegalArgumentException("screen 必须是字符串")
            },
            args = when (val v = f["args"]) {
                null, is DomainJson.Value.Null -> emptyList()
                is DomainJson.Value.Arr -> v.items.map {
                    (it as? DomainJson.Value.S)?.v
                        ?: throw IllegalArgumentException("args 元素必须是字符串")
                }
                else -> throw IllegalArgumentException("args 必须是数组")
            },
            scriptTimeoutMillis = when (val v = f["scriptTimeoutMillis"]) {
                null, is DomainJson.Value.Null -> null
                is DomainJson.Value.N -> v.raw.toLongOrNull()?.takeIf { it > 0 }
                    ?: throw IllegalArgumentException("scriptTimeoutMillis 必须 > 0")
                else -> throw IllegalArgumentException("scriptTimeoutMillis 必须是数字")
            },
            timezone = when (val v = f["timezone"]) {
                null, is DomainJson.Value.Null -> ZoneId.systemDefault()
                is DomainJson.Value.S -> try {
                    ZoneId.of(v.v)
                } catch (_: Exception) {
                    throw IllegalArgumentException("timezone 非法: ${v.v}")
                }
                else -> throw IllegalArgumentException("timezone 必须是字符串")
            },
            enabled = when (val v = f["enabled"]) {
                null, is DomainJson.Value.Null -> true
                is DomainJson.Value.B -> v.v
                else -> throw IllegalArgumentException("enabled 必须是布尔")
            },
        )
        scheduler.schedule(task)
        return """{"id":${DomainJson.encode(task.id)}}"""
    }

    private fun parseSchedule(f: Map<String, DomainJson.Value>): TimedSchedule {
        return when (DomainJson.reqStr(f, "kind")) {
            "once" -> TimedSchedule.Once(
                (f["delaySeconds"] as? DomainJson.Value.N)?.raw?.toLongOrNull()?.takeIf { it >= 0 }
                    ?: throw IllegalArgumentException("once 需要非负 delaySeconds"),
            )
            "daily" -> TimedSchedule.Daily(
                (f["hourOfDay"] as? DomainJson.Value.N)?.raw?.toIntOrNull()
                    ?: throw IllegalArgumentException("daily 需要 hourOfDay"),
                (f["minuteOfHour"] as? DomainJson.Value.N)?.raw?.toIntOrNull()
                    ?: throw IllegalArgumentException("daily 需要 minuteOfHour"),
            )
            "cron" -> {
                // CronTab.parse 是唯一校验出处（与 UI 侧 Ops 同口径）：非法表达式抛
                // IllegalArgumentException → handle 折叠为 ERR_INVALID_PARAM。
                val expr = (f["expr"] as? DomainJson.Value.S)?.v
                    ?: throw IllegalArgumentException("cron 需要字符串 expr")
                try {
                    CronTab.parse(expr)
                } catch (e: IllegalArgumentException) {
                    throw IllegalArgumentException("cron 表达式非法：${e.message}")
                }
                TimedSchedule.Cron(expr.trim())
            }
            else -> throw IllegalArgumentException("schedule.kind 非法（once/daily/cron）")
        }
        // TimedSchedule.Daily 的 init require 负责越界拒绝（hour 0..23/minute 0..59），
        // 抛出的 IllegalArgumentException 由 handle 折叠为 ERR_INVALID_PARAM。
    }

    private fun encodeTask(t: ScheduledTask): Map<String, Any?> = mapOf(
        "id" to t.id,
        "name" to t.name,
        "projectId" to t.projectId,
        "scriptPath" to t.scriptPath,
        "schedule" to when (val s = t.schedule) {
            is TimedSchedule.Once -> mapOf("kind" to "once", "delaySeconds" to s.delaySeconds)
            is TimedSchedule.Daily -> mapOf(
                "kind" to "daily", "hourOfDay" to s.hourOfDay, "minuteOfHour" to s.minuteOfHour,
            )
            is TimedSchedule.Cron -> mapOf("kind" to "cron", "expr" to s.expr)
        },
        "screen" to t.screen.name,
        "args" to t.args,
        "scriptTimeoutMillis" to t.scriptTimeoutMillis,
        "timezone" to t.timezone.id,
        "enabled" to t.enabled,
    )

    private fun requirePayload(request: BridgeRequest): String =
        request.payload ?: throw IllegalArgumentException("${request.method} 需要 payload 对象")
}
