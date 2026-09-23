package com.autoscript.shell

import com.autoscript.appservice.permissioncenter.PermissionCenter
import com.autoscript.domain.host.CapabilityCenterSnapshot
import com.autoscript.domain.host.CapabilityRow
import com.autoscript.domain.permission.Capability
import com.autoscript.domain.permission.PermissionFacade

/**
 * 能力中心快照的拼装（§9.5）。
 *
 * **为什么单独一个文件而不是写在 [com.autoscript.AppShellApplication] 里**：
 * `Application` 在 JVM 单测里构造不出来（运行期 android stub 会抛），而"枚举全部能力、
 * 逐个问三态、配引导文案、带上降级任务账"这段**判断**必须可测 —— 它是能力中心
 * 那一屏的全部事实来源。抽到纯 JVM 面之后，`AppShellApplication` 只剩一句转接。
 *
 * 两条纪律：
 * - **全量枚举**（`Capability.entries`）：能力中心要能回答"我到底有哪些能力"，
 *   只列异常项会让人以为其余不存在；新增 `Capability` 时这里自动跟上（没有第二张清单）；
 * - **读失败抛**（[PermissionFacade.state] 自己已把查询崩溃收敛成 DEGRADED，这里的抛
 *   来自更外层）：由调用方（`:ui`）如实显示，**绝不**在这里吞成"全 DENIED"的假快照 ——
 *   让用户以为授权全丢了，比不显示更糟。
 */
object CapabilityCenterRead {

    /**
     * 拼一份快照。
     *
     * @param facade 唯一权限入口（生产 = [PermissionCenter]；测试注入即时三态）。
     * @param degradedAlarmTaskIds 降级中的定时任务 id（`AlarmSchedulerProvider.degradedTasks`
     *   的键）。与三态**分开**记账：那是精确闹钟被系统收回后降 `setWindow` 的账（§8.6），
     *   不是权限三态问题。
     */
    suspend fun snapshot(
        facade: PermissionFacade,
        degradedAlarmTaskIds: List<String>,
    ): CapabilityCenterSnapshot = CapabilityCenterSnapshot(
        rows = Capability.entries.map { ability ->
            CapabilityRow(
                capability = ability,
                state = facade.state(ability),
                guide = PermissionCenter.guideText(ability),
            )
        },
        degradedAlarmTaskIds = degradedAlarmTaskIds,
    )
}
