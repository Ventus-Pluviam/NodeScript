package com.autoscript.shell

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import com.autoscript.appservice.permissioncenter.GrantLauncher
import com.autoscript.appservice.permissioncenter.PermissionCenter
import com.autoscript.appservice.permissioncenter.SystemStateReader
import com.autoscript.domain.permission.Capability
import com.autoscript.domain.permission.CapabilityState
import com.autoscript.domain.permission.GrantResult
import java.io.File

/**
 * 门禁生产装配（docs §9.5 唯一权限入口的生产侧）。
 *
 * [PermissionCenter] 自身纯编排（reader/launcher 两道缝，JVM 可测），但此前没有任何生产调用方 ——
 * `state()` 再诚实，没人问系统也是摆设。本文件是"问系统"的那一侧，住 `:app` 的 `shell` 装配包：
 * - 读系统服务要 `Context`（只有 `:app` 有），且 shell 包碰 `android..` 不违规
 *   （`ArchitectureTest` 只禁 platform/engine/UI，`AndroidAlarmPort` 同例）；
 * - `:app-service:permission-center` 的 arch 门禁禁它直连 Android，故真查询只能落在这里。
 *
 * 两条纪律（与 `AndroidAlarmPort` 同）：
 * - **本文件无判断**：每个 check 都是一行系统调用翻成三态，不解释、不兜底。
 *   ROM 查询崩了抛出来 —— [PermissionCenter.state] 会收敛成 `DEGRADED`（已单测），
 *   不在这里吞（吞了就分不清"查不到"和"拒绝"）；
 * - **接不上的如实写**：屏幕采集是会话型的（首截时才拉授权），无会话时回 `DEGRADED`
 *   而不是 `GRANTED`；Shizuku 输入根本没接入，回 `DENIED`（输入走无障碍手势兜底，
 *   见 `InMemoryInputProvider` 同口径）。伪造一个 GRANTED 会让脚本以为路是通的。
 */
object AndroidPermissionGates {

    /**
     * 按能力查系统态的缝（测试注入即时值，不碰框架；生产走 [defaultChecks]）。
     * 缺项抛 `NoSuchElementException` —— [PermissionCenter.state] 照样收敛 `DEGRADED`，不炸。
     */
    fun systemStateReader(checks: Map<Capability, () -> CapabilityState>): SystemStateReader =
        SystemStateReader { ability -> checks.getValue(ability)() }

    /**
     * 生产查询表（`Context` → 8 个 Capability 的真查询）。
     * map 建一次、每次 `state()` 调 lambda 直读 —— 系统态是随时会变的（用户切走就关授权），
     * 不缓存结论（缓存 = 撒谎的开始）。
     */
    fun defaultChecks(context: Context): Map<Capability, () -> CapabilityState> {
        val app = context.applicationContext
        return mapOf(
            Capability.ACCESSIBILITY to { accessibilityState(app) },
            // 会话型：MediaProjection 授权发生在首截那一刻，这里无会话可查 ——
            // DEGRADED（"可降级但受限：会话未激活"，见 §9.5），绝不 GRANTED。
            Capability.SCREEN_CAPTURE to { CapabilityState.DEGRADED },
            Capability.OVERLAY to { boolState(Settings.canDrawOverlays(app)) },
            Capability.NOTIFICATION to { notificationsEnabled(app) },
            Capability.SCHEDULE_EXACT_ALARM to { exactAlarmState(app) },
            Capability.ROOT to { boolState(hasSu()) },
            // Shizuku 通道未接入：如实 DENIED（引导文案指去 Shizuku；输入走无障碍手势）。
            Capability.ADB_INPUT to { CapabilityState.DENIED },
            Capability.POST_NOTIFICATIONS to { postNotificationsState(app) },
        )
    }

    /**
     * 设置页跳转目标（纯映射，可 JVM 单测：`Settings.ACTION_*` 全是编译期字符串常量，
     * 单测引用它们不会碰框架 —— 与 `BootEventsTest` 引 `Intent.ACTION_BOOT_COMPLETED` 同理）。
     *
     * 无专用授权页的能力（屏幕采集/Shizuku/root）→ 应用详情页：那里至少能看权限列表，
     * 不伪造一个"一点就授权"的假页。`withPackage` 的两列按各 action 的文档要求配
     * （配错了系统直接忽略 extra，表现为"跳过去了但没定位到本应用"—— 现场难查，故单测锁字面量）。
     */
    data class SettingsTarget(
        val action: String,
        /** 需要 `Settings.EXTRA_APP_PACKAGE`（通知设置）。 */
        val withAppPackageExtra: Boolean = false,
        /** 需要 `data = package:<包名>`（overlay/精确闹钟/应用详情）。 */
        val withPackageData: Boolean = false,
    )

    fun settingsTargetFor(ability: Capability): SettingsTarget = when (ability) {
        Capability.ACCESSIBILITY -> SettingsTarget(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        Capability.SCREEN_CAPTURE -> appDetails()
        Capability.OVERLAY ->
            SettingsTarget(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, withPackageData = true)
        Capability.NOTIFICATION, Capability.POST_NOTIFICATIONS ->
            SettingsTarget(Settings.ACTION_APP_NOTIFICATION_SETTINGS, withAppPackageExtra = true)
        Capability.SCHEDULE_EXACT_ALARM ->
            // S 以下没有"精确闹钟"这个权限项（一直可用）：跳应用详情，不跳一个不存在的 action。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SettingsTarget(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, withPackageData = true)
            } else {
                appDetails()
            }
        Capability.ROOT, Capability.ADB_INPUT -> appDetails()
    }

    /** 授权拉起（真设置页跳转；`Unit` 的 `openSettings` 同一目标复用）。 */
    fun grantLauncher(context: Context): GrantLauncher {
        val app = context.applicationContext
        return object : GrantLauncher {
            override suspend fun launchGrant(ability: Capability): GrantResult {
                return try {
                    app.startActivity(launchIntent(app, ability))
                    // 用户切走了：等 UI 层拿 Activity 结果回来再刷新三态，这里只记 Deferred。
                    GrantResult.Deferred
                } catch (_: Exception) {
                    // 无处理该 intent 的系统页（某些 ROM 阉割设置项）：如实 Denied，不谎称 Deferred。
                    GrantResult.Denied
                }
            }

            override fun openSettings(ability: Capability) {
                try {
                    app.startActivity(launchIntent(app, ability))
                } catch (t: Exception) {
                    Log.w(TAG, "跳转设置页失败：$ability", t)
                }
            }
        }
    }

    /** 生产门禁实例（`AppShellApplication.permissionCenter()` 的落点）。 */
    fun permissionCenterOf(context: Context): PermissionCenter =
        PermissionCenter(systemStateReader(defaultChecks(context)), grantLauncher(context))

    // ── 真查询（一行一系统调用，无判断） ─────────────────────────────

    private fun accessibilityState(app: Context): CapabilityState {
        val manager = app.getSystemService(AccessibilityManager::class.java)
            ?: return CapabilityState.DENIED
        val enabled = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        // 本包有服务在启用列表里才算数 —— 别家的无障碍开了与我们无关。
        return boolState(enabled.any { it.resolveInfo?.serviceInfo?.packageName == app.packageName })
    }

    private fun notificationsEnabled(app: Context): CapabilityState {
        val manager = app.getSystemService(NotificationManager::class.java)
            ?: return CapabilityState.DENIED
        return boolState(manager.areNotificationsEnabled())
    }

    private fun exactAlarmState(app: Context): CapabilityState {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return CapabilityState.GRANTED
        val alarms = app.getSystemService(AlarmManager::class.java)
            ?: return CapabilityState.DENIED
        return boolState(alarms.canScheduleExactAlarms())
    }

    private fun postNotificationsState(app: Context): CapabilityState {
        if (Build.VERSION.SDK_INT >= 33) {
            return boolState(
                app.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED,
            )
        }
        return notificationsEnabled(app)
    }

    private fun hasSu(): Boolean =
        arrayOf("/system/bin/su", "/system/xbin/su").any { File(it).exists() }

    private fun boolState(ok: Boolean): CapabilityState =
        if (ok) CapabilityState.GRANTED else CapabilityState.DENIED

    private fun appDetails(): SettingsTarget =
        SettingsTarget(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, withPackageData = true)

    private fun launchIntent(app: Context, ability: Capability): Intent {
        val target = settingsTargetFor(ability)
        return Intent(target.action).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (target.withAppPackageExtra) {
                putExtra(Settings.EXTRA_APP_PACKAGE, app.packageName)
            }
            if (target.withPackageData) {
                data = Uri.parse("package:" + app.packageName)
            }
        }
    }

    private const val TAG = "PermissionGates"
}
