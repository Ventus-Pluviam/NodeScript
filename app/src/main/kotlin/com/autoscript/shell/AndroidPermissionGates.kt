package com.autoscript.shell

import android.content.Context
import com.autoscript.appservice.permissioncenter.PermissionCenter
import com.autoscript.appservice.permissioncenter.SystemStateReader
import com.autoscript.domain.permission.Capability
import com.autoscript.domain.permission.CapabilityState

/**
 * 门禁生产装配（docs §9.5 唯一权限入口的生产侧）。
 *
 * 本文件只做拼装，不做判断 —— 与 `AndroidAlarmPort` 同一条纪律：
 * - 三态映射的判据唯一出处是 [AndroidSystemStateReader]（`CapabilityProbes` 事实 →
 *   GRANTED/DEGRADED/DENIED；OVERLAY 的 a11y 第二条路、ROOT 的执行探测、通知两能力的
 *   降级语义分岔都在那张表里，对着 `PermissionCenter.guideText` 的承诺）；
 * - 去向的唯一出处是 [AndroidGrantLauncher]（`pageFor` 能力→页、`specFor` 页→Intent 规格，
 *   字面量由单测锁死；`AndroidSettingsPageOpener` 只做"规格+包名→Intent"搬运）；
 * - 读系统服务要 `Context`（只有 `:app` 有），且 shell 包碰 `android..` 不违规
 *   （`ArchitectureTest` 只禁 platform/engine/UI，`AndroidAlarmPort` 同例）；
 * - `:app-service:permission-center` 的 arch 门禁禁它直连 Android，故真查询只能落在这里。
 *
 * [PermissionCenter] 自身纯编排（reader/launcher 两道缝，JVM 可测）：`state()` 再诚实，
 * 没人问系统也是摆设 —— [permissionCenterOf] 就是"问系统"的那一次拼装。
 * 查询每次 `state()` 直读系统（结论不缓存；缓存 = 撒谎的开始）。ROM 查询崩了抛出来 ——
 * [PermissionCenter.state] 会收敛成 `DEGRADED`（已单测），不在这里吞
 * （吞了就分不清"查不到"和"拒绝"）。
 */
object AndroidPermissionGates {

    /**
     * 按能力查系统态的缝（测试注入即时值，不碰框架）。
     * 缺项抛 `NoSuchElementException` —— [PermissionCenter.state] 照样收敛 `DEGRADED`，不炸。
     */
    fun systemStateReader(checks: Map<Capability, () -> CapabilityState>): SystemStateReader =
        SystemStateReader { ability -> checks.getValue(ability)() }

    /**
     * 生产读缝：`Context` → 真探针 → 三态映射。
     * 事实由 [AndroidCapabilityProbes] 装箱（碰 Settings/Manager/ProcessBuilder 的唯一地方），
     * 判断由 [AndroidSystemStateReader] 落（那张表是判据的唯一出处，本文件不重复判断）。
     */
    fun defaultReader(context: Context): SystemStateReader =
        AndroidSystemStateReader(AndroidCapabilityProbes(context.applicationContext))

    /** 生产门禁实例（`AppShellApplication.permissionCenter()` 的落点）。 */
    fun permissionCenterOf(context: Context): PermissionCenter =
        PermissionCenter(
            defaultReader(context),
            AndroidGrantLauncher(AndroidSettingsPageOpener(context.applicationContext)),
        )
}
