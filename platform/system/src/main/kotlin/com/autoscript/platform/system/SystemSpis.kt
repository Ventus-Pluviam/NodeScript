package com.autoscript.platform.system

import android.content.Context
import com.autoscript.domain.storage.DataStore
import com.autoscript.platform.system.app.AndroidAppLauncher
import com.autoscript.platform.system.app.AppLauncher
import com.autoscript.platform.system.app.PackageManagerOps
import com.autoscript.platform.system.app.UsageStatsEvents
import com.autoscript.platform.system.clipboard.AndroidClipboard
import com.autoscript.platform.system.clipboard.Clipboard
import com.autoscript.platform.system.clipboard.ClipboardOps
import com.autoscript.platform.system.datastore.AndroidDataStore
import com.autoscript.platform.system.datastore.SqliteKvOps
import com.autoscript.platform.system.device.AndroidDeviceInfoProvider
import com.autoscript.platform.system.device.DeviceInfoProvider
import com.autoscript.platform.system.floatingWindow.AndroidFloatingWindowHost
import com.autoscript.platform.system.floatingWindow.FloatingWindowHost
import com.autoscript.platform.system.floatingWindow.WindowManagerOps
import com.autoscript.platform.system.notification.AndroidNotificationPoster
import com.autoscript.platform.system.notification.NotificationOps
import com.autoscript.platform.system.notification.NotificationPoster
import com.autoscript.platform.system.sensors.AndroidSensorSource
import com.autoscript.platform.system.sensors.SensorOps
import com.autoscript.platform.system.sensors.SensorSource
import com.autoscript.platform.system.settings.AndroidSystemSettings
import com.autoscript.platform.system.settings.SettingsSystemOps
import com.autoscript.platform.system.settings.SystemSettings
import com.autoscript.platform.system.shell.AndroidShellExecutor
import com.autoscript.platform.system.shell.ShellExecutor
import com.autoscript.platform.system.zip.JdkZipArchiver
import com.autoscript.platform.system.zip.ZipArchiver

/**
 * `:platform:system` 的实现入口（docs §12.2「分两层」的**下面那层**）：
 * 系统面十个 SPI 的 Android 实现一次性造齐（SPI 契约本身
 * 也随各命名空间子包 —— 判据见各方 `*Contracts.kt` KDoc），交装配层组合。
 *
 * **本类与 [SystemNamespaces] 的分工（同模块内仍两对象）**：本类负责「拿 `Context`
 * 造 Android 实现」（构造签名要 Android 类型），`SystemNamespaces` 负责「SPI →
 * `NamespaceHandler` 工厂束」（纯参数注入，单测直传假 SPI）。装配层 `PlatformWiring`
 * 拿两者组装 —— 本类不 new handler，handler 侧不碰 `Context`。
 *
 * **`dialogs` 仍不在此造**（理由从"待 P2"变成"分层归属"）：`DialogHost` 实现按
 * domain KDoc 约定住 `:platform:capabilities`（`AndroidDialogHost` 编排 +
 * `SystemDialogOps` 设备面），而平台模块之间没有依赖边 —— 构造归装配层
 * `PlatformWiring.of`（同 `overlayAvailable` 参数一并传入）。本类十件不变（sensors 本批补到十件；`dialogs` 仍不在此造）。
 *
 * 构造点在装配层（持有 `Context` 的 Android 侧）；本类不做权限判断。
 *
 * **订正**：原写「§9.5：门禁在 `PermissionFacade`，先判后取」——「先判后取」这条在生产
 * 代码里不存在（`ensure` 零调用点；见 `SystemNamespaces` 类 KDoc 的同一条订正）。
 * 脚本侧的判据是**桥面掩码**（§11），系统侧的拒绝由各实现折分类错误。
 */
object SystemSpis {

    /**
     * 本模块当前能提供的十件（见 [Bundle] 字段注释）。
     * `overlayAvailable` 缺省恒假 —— 悬浮窗走 `TYPE_APPLICATION_OVERLAY`；
     * a11y 服务在跑时由上层传 `{ true }` 换成 `TYPE_ACCESSIBILITY_OVERLAY`。
     */
    fun of(
        context: Context,
        overlayAvailable: () -> Boolean = { false },
    ): Bundle {
        val app = context.applicationContext
        return Bundle(
            shell = AndroidShellExecutor(),
            device = AndroidDeviceInfoProvider(),
            app = AndroidAppLauncher(PackageManagerOps(app), UsageStatsEvents(app)),
            floatingWindow = AndroidFloatingWindowHost(
                ops = WindowManagerOps(app),
                overlayTypeAvailable = overlayAvailable,
            ),
            datastore = AndroidDataStore(SqliteKvOps(app)),
            zip = JdkZipArchiver(),
            settings = AndroidSystemSettings(SettingsSystemOps(app)),
            notification = AndroidNotificationPoster(NotificationOps(app)),
            clipboard = AndroidClipboard(ClipboardOps(app)),
            sensors = AndroidSensorSource(SensorOps(app)),
        )
    }

    /**
     * 十个 SPI 实现（`dialogs` 缺位，见 [SystemSpis] 的 KDoc）。
     * 字段声明成 SPI 类型而非具体类：上层只该看见契约，不背实现。
     *
     * `datastore` 是 SPI 束的成员、**不是** `systemHandlers` 束的成员：
     * handler 侧它是独立注入缝（存储面无共担门禁，§12.2 接线表）——
     * 两束形状不同是有意的，别对齐。
     */
    data class Bundle(
        val shell: ShellExecutor,
        val device: DeviceInfoProvider,
        val app: AppLauncher,
        val floatingWindow: FloatingWindowHost,
        val datastore: DataStore,
        val zip: ZipArchiver,
        val settings: SystemSettings,
        val notification: NotificationPoster,
        val clipboard: Clipboard,
        val sensors: SensorSource,
    )
}
