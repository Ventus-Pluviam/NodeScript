package com.autoscript.shell

import android.provider.Settings
import com.autoscript.appservice.permissioncenter.PermissionCenter
import com.autoscript.domain.permission.Capability
import com.autoscript.domain.permission.CapabilityState
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 门禁生产装配验证（§9.5 唯一权限入口的生产侧）：
 * - `settingsTargetFor` 是纯映射：action 字面量锁死（配错了系统直接忽略 extra，
 *   现场表现为"跳过去了但没定位到本应用"，极难查）；
 * - 缝缺项不炸：`systemStateReader` 缺某能力 → `PermissionCenter.state` 收敛 DEGRADED。
 *
 * `Settings.ACTION_*` 全是编译期字符串常量，JVM 单测引用不碰框架
 * （与 `BootEventsTest` 引 `Intent.ACTION_BOOT_COMPLETED` 同理）。
 * `SCHEDULE_EXACT_ALARM` 在 JVM 上走 S-以下分支（`Build.VERSION.SDK_INT` 未 mock 恒 0）——
 * S+ 的精确闹钟页分支由真机覆盖，这里只锁"JVM 上是应用详情页"。
 */
class AndroidPermissionGatesTest {

    @Test
    fun `设置页目标锁字面量`() {
        assertEquals(
            Settings.ACTION_ACCESSIBILITY_SETTINGS,
            AndroidPermissionGates.settingsTargetFor(Capability.ACCESSIBILITY).action,
        )
        val overlay = AndroidPermissionGates.settingsTargetFor(Capability.OVERLAY)
        assertEquals(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, overlay.action)
        assertTrue(overlay.withPackageData, "overlay 页需 data=package: 定位本应用")

        val notif = AndroidPermissionGates.settingsTargetFor(Capability.NOTIFICATION)
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, notif.action)
        assertTrue(notif.withAppPackageExtra, "通知页需 EXTRA_APP_PACKAGE 定位本应用")
        assertEquals(
            notif.action,
            AndroidPermissionGates.settingsTargetFor(Capability.POST_NOTIFICATIONS).action,
            "两类通知能力同页",
        )

        // 无专用授权页的三项 → 应用详情页（不伪造"一点就授权"的假页）
        for (ability in listOf(Capability.SCREEN_CAPTURE, Capability.ROOT, Capability.ADB_INPUT)) {
            val t = AndroidPermissionGates.settingsTargetFor(ability)
            assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, t.action, "$ability 无专页")
            assertTrue(t.withPackageData, "$ability 详情页需 data=package:")
        }

        val exact = AndroidPermissionGates.settingsTargetFor(Capability.SCHEDULE_EXACT_ALARM)
        assertEquals(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS, exact.action,
            "JVM 上 SDK_INT=0 走 S-以下分支：精确闹钟无此权限项，跳应用详情",
        )
    }

    @Test
    fun `缝缺项经门禁收敛为 DEGRADED 而非炸`() = runBlocking {
        val center = PermissionCenter(
            AndroidPermissionGates.systemStateReader(emptyMap()),
            object : com.autoscript.appservice.permissioncenter.GrantLauncher {
                override suspend fun launchGrant(ability: Capability) =
                    com.autoscript.domain.permission.GrantResult.Denied
                override fun openSettings(ability: Capability) = Unit
            },
        )
        assertEquals(
            CapabilityState.DEGRADED,
            center.state(Capability.ACCESSIBILITY),
            "缺项抛 NoSuchElementException → state 收敛 DEGRADED（见 PermissionCenter KDoc）",
        )
    }

    @Test
    fun `缝直读不解释：GRANTED 原样过`() = runBlocking {
        val center = PermissionCenter(
            AndroidPermissionGates.systemStateReader(
                mapOf(Capability.ACCESSIBILITY to { CapabilityState.GRANTED }),
            ),
            object : com.autoscript.appservice.permissioncenter.GrantLauncher {
                override suspend fun launchGrant(ability: Capability) =
                    com.autoscript.domain.permission.GrantResult.Denied
                override fun openSettings(ability: Capability) = Unit
            },
        )
        assertEquals(CapabilityState.GRANTED, center.ensure(Capability.ACCESSIBILITY))
        Unit                                           // 显式收尾：void 返回值才被 JUnit5 视为测试
    }
}
