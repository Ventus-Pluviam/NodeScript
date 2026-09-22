package com.autoscript.shell

import android.os.Build
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
 * - `pageFor` + `specFor` 是纯映射：action 字面量锁死（配错了系统直接忽略 extra，
 *   现场表现为"跳过去了但没定位到本应用"，极难查）；
 * - 缝缺项不炸：`systemStateReader` 缺某能力 → `PermissionCenter.state` 收敛 DEGRADED。
 *
 * `Settings.ACTION_*` 全是编译期字符串常量，JVM 单测引用不碰框架
 * （与 `BootEventsTest` 引 `Intent.ACTION_BOOT_COMPLETED` 同理）。
 * `specFor` 的 `sdkInt` **一律显式传值**：缺省参数在调用点求值 `Build.VERSION.SDK_INT`，
 * 那是运行期字段读——本机旁路（tools/jvm-test.sh）的 android.jar 只上编译期，
 * 缺省一求值就 `NoClassDefFoundError: android/os/Build$VERSION`。显式传参既绕开
 * 缺省求值，又是钉分支本来就该做的事（`VERSION_CODES.S/R` 是编译期常量，内联不碰框架）。
 * 生产路径（`AndroidSettingsPageOpener`）继续用缺省读真 SDK——真机运行时才有该字段。
 */
class AndroidPermissionGatesTest {

    @Test
    fun `跳转规格锁字面量：页到action与extra配列`() {
        val access = AndroidGrantLauncher.specFor(GrantPage.ACCESSIBILITY, Build.VERSION_CODES.S)
        assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, access.action)

        val overlay = AndroidGrantLauncher.specFor(GrantPage.OVERLAY, Build.VERSION_CODES.S)
        assertEquals(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, overlay.action)
        assertTrue(overlay.withPackageData, "overlay 页需 data=package: 定位本应用")

        val notif = AndroidGrantLauncher.specFor(GrantPage.NOTIFICATIONS, Build.VERSION_CODES.S)
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, notif.action)
        assertTrue(notif.withAppPackageExtra, "通知页需 EXTRA_APP_PACKAGE 定位本应用")

        // 无专用授权页的三项经 pageFor 落到同一详情页（不伪造"一点就授权"的假页）
        for (ability in listOf(Capability.SCREEN_CAPTURE, Capability.ROOT, Capability.ADB_INPUT)) {
            assertEquals(GrantPage.APP_DETAILS, AndroidGrantLauncher.pageFor(ability), "$ability 无专页")
        }
        val details = AndroidGrantLauncher.specFor(GrantPage.APP_DETAILS, Build.VERSION_CODES.S)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, details.action)
        assertTrue(details.withPackageData, "详情页需 data=package:")

        // 两类通知能力同页
        assertEquals(
            AndroidGrantLauncher.pageFor(Capability.NOTIFICATION),
            AndroidGrantLauncher.pageFor(Capability.POST_NOTIFICATIONS),
            "两类通知能力同页",
        )
    }

    @Test
    fun `精确闹钟页高低版本分岔`() {
        val high = AndroidGrantLauncher.specFor(GrantPage.EXACT_ALARM, Build.VERSION_CODES.S)
        assertEquals(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, high.action)
        assertTrue(high.withPackageData, "精确闹钟页需 data=package: 定位本应用")

        val low = AndroidGrantLauncher.specFor(GrantPage.EXACT_ALARM, Build.VERSION_CODES.R)
        assertEquals(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS, low.action,
            "S 以下没有精确闹钟权限项：回退应用详情，不跳一个不存在的 action",
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
