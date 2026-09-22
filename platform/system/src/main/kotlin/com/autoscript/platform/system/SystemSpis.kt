package com.autoscript.platform.system

import android.content.Context
import com.autoscript.domain.storage.DataStore
import com.autoscript.domain.storage.SystemSettings
import com.autoscript.domain.storage.ZipArchiver
import com.autoscript.domain.system.AppLauncher
import com.autoscript.domain.system.DeviceInfoProvider
import com.autoscript.domain.system.FloatingWindowHost
import com.autoscript.domain.system.ShellExecutor

/**
 * `:platform:system` 的实现入口（docs §12.2「分两层」的**下面那层**）：
 * 把 `com.autoscript.domain.system` 的各 SPI 在本模块的实现一次性造齐，
 * 交给上层（`:platform:capabilities` 的 handler → 装配层注入束）用。
 *
 * **为什么这里只到 SPI 为止、不直接产出 `NamespaceHandler`**：handler 是桥面形状
 * （`SystemNamespaces.kt` 在 `:platform:capabilities`），本模块若去 new 它就要依赖
 * `:platform:capabilities` —— 那是一条 §6 模块表没有的平台内互赖。分层的好处正在于此：
 * 本模块只认 `:domain`，谁把它接到桥上都不影响这里。
 *
 * **`dialogs` 刻意缺席**：`DialogHost` 要 overlay 真弹窗 + 通知回调两条 UI 路径
 * （§14 把它排在 P2）。这里不提供"凑数的 DialogHost"—— 缺了就是缺了，
 * 注入侧那个字段留 null，桥对 `dialogs.*` 如实回 `ERR_NOT_IMPLEMENTED`。
 *
 * 构造点在装配层（持有 `Context` 的 Android 侧）；本类不做权限判断（§9.5：
 * 门禁在 `PermissionFacade`，先判后取）。
 */
object SystemSpis {

    /**
     * 本模块当前能提供的七件（见 [Bundle] 字段注释）。
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
        )
    }

    /**
     * 七个 SPI 实现（`dialogs` 缺位，见 [SystemSpis] 的 KDoc）。
     * 字段声明成 SPI 类型而非具体类：上层只该看见 `:domain` 的契约。
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
    )
}
