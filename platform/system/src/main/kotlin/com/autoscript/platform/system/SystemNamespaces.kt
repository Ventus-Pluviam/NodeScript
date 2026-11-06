package com.autoscript.platform.system

import com.autoscript.domain.automation.ImageAnalyzer
import com.autoscript.domain.bridge.NamespaceHandler
import com.autoscript.domain.storage.DataStore
import com.autoscript.platform.system.app.AppLauncher
import com.autoscript.platform.system.app.AppNamespaceHandler
import com.autoscript.platform.system.clipboard.Clipboard
import com.autoscript.platform.system.clipboard.ClipboardNamespaceHandler
import com.autoscript.platform.system.datastore.DatastoreNamespaceHandler
import com.autoscript.platform.system.device.DeviceInfoProvider
import com.autoscript.platform.system.device.DeviceNamespaceHandler
import com.autoscript.platform.system.floatingWindow.FloatingWindowHost
import com.autoscript.platform.system.floatingWindow.FloatingWindowNamespaceHandler
import com.autoscript.platform.system.images.ImagesNamespaceHandler
import com.autoscript.platform.system.notification.NotificationNamespaceHandler
import com.autoscript.platform.system.notification.NotificationPoster
import com.autoscript.platform.system.sensors.SensorSource
import com.autoscript.platform.system.sensors.SensorsNamespaceHandler
import com.autoscript.platform.system.settings.SettingsNamespaceHandler
import com.autoscript.platform.system.settings.SystemSettings
import com.autoscript.platform.system.shell.DEFAULT_SHELL_TIMEOUT_MILLIS
import com.autoscript.platform.system.shell.ShellExecutor
import com.autoscript.platform.system.shell.ShellNamespaceHandler
import com.autoscript.platform.system.zip.ZipArchiver
import com.autoscript.platform.system.zip.ZipNamespaceHandler

/**
 * 系统面十一个命名空间的 handler **工厂束**（§4.1/§6/§9.4/§9.6/§12.2；装配收拢：
 * `PlatformWiring` 不逐个 import handler 类）。
 *
 * **（D3）子包按命名空间对齐后本文件只剩工厂**：本对象无逻辑 —— 不解释
 * payload、不吞错误、不改错误码（§7 桥侧只透传），只做「SPI 参数 → handler 实例」的
 * 构造收拢。四个原本内联在此的 handler（`Shell`/`Device`/`App`/`FloatingWindow`）已
 * 拆到各自子包，与 capabilities 的 `a11y/A11yNamespaceHandler` 同形 ——
 * 每个命名空间一个子包：`shell/` `device/` `app/` `floatingWindow/` `datastore/`
 * `zip/` `settings/` `notification/` `clipboard/` `sensors/` `images/` `power/`
 * （契约、ops 缝、Android 实现、handler 四件同包；`power/` 是 §8.7 电源面，
 * 不经本束 —— 见 `PowerManagerNamespaceHandler`）。
 *
 * **归属**：自 `:platform:capabilities` 迁入本模块 —— 这批 handler 的 Android 实现与
 * SPI 替身本就住这里，语义层与实现同模块收拢；§12.2 原「语义层 handler 不住 system」
 * 口径已反转（记入 design-decisions）。
 *
 * **各子包的四件同住一处**：每个命名空间子包里**契约 + ops 缝 + Android 实现 + handler**
 * 同住（`shell/` `device/` `app/` `floatingWindow/` 四面的契约原是一份
 * `SystemHostContracts.kt` 按面拆开；它们的 handler 原本是**本文件的内联
 * 四件**，同批拆出）。拆分的理由是「按命名空间对齐」而不是「按层对齐」——同一次改动要动的
 * 四件挨在一起，跨层跳文件的开销大于分层带来的秩序。各子包文件不再重复这段沿革。
 *
 * **能力门禁不在这里**：`:app-service:permission-center` 的 `PermissionFacade` 住
 * `:app-service:*`，而本模块的 archUnit 黑名单含 `com.autoscript.appservice..`（§6）。
 * 本模块只负责**语义**：参数校验、句柄记账、分类错误。
 * 未接线的命名空间由 Router 如实回 ERR_NOT_IMPLEMENTED（§7.5），不伪造可用。
 *
 * **订正**：这里原写「所以门禁由装配层在调用工厂前完成（`ensure(Capability.OVERLAY)` 等），
 * 本模块只负责能力已保证之后的语义」—— 前半句**不成立**：`PermissionFacade.ensure` /
 * `requireGrantedOrThrow` 在生产代码里**零调用点**，装配层 `PlatformWiring.inject`
 * 是纯转接（一处 `ensure` 都没有）。三态当前只服务**能力中心 UI**（`state` 经
 * `CapabilityCenterRead` 逐项直读）。脚本这条路上真在判的是**桥面掩码**（§11）：
 * `BridgeCapabilityCatalog` 逐命名空间要位，`BridgeRouter.dispatch` 未覆盖即
 * `ERR_PERMISSION_DENIED`、不进 handler；设备侧的拒绝由各实现如实折分类错误
 * （`AndroidFloatingWindowHost` 加窗被拒 → `ERR_PERMISSION_DENIED`、`su` 不在 →
 * `ERR_SERVICE_DISABLED`）。把「装配层会先判」写成既有事实，会让读的人以为不必再判 ——
 * 而它确实没人判。
 *
 * 超时（铁律 3）：桥侧 TTL 由 `BridgeRequest.ttlMillis` 驱动，handler 必须在
 * TTL 内返回。`shell.exec` 的实现方超时（shell 子包的 `DEFAULT_SHELL_TIMEOUT_MILLIS`）
 * 是宿主侧兜底：即使请求侧忘了给 TTL，也不会有无限等待的 `Runtime.waitFor`。
 */

/**
 * 构造工厂束（原 `CapabilityNamespaces` 的系统面部分随 handler 迁入本模块；
 * capabilities 侧保留 a11y/screen/dialogs 三件）。装配层（`PlatformWiring`）拿现成
 * `NamespaceHandler` 挂 Router，只依赖 `:domain` 的类型形状。
 */
object SystemNamespaces {

    /** `shell` 命名空间：`exec`/`shell` 两方法（JS `extras.ts` 的 `shell()` 只是 `exec()` 的别名）。 */
    fun shell(
        executor: ShellExecutor,
        defaultTimeoutMillis: Long = DEFAULT_SHELL_TIMEOUT_MILLIS,
    ): NamespaceHandler {
        return ShellNamespaceHandler(executor, defaultTimeoutMillis)
    }

    /** `device` 命名空间：`model`/`sdkInt`（P0 最小集，§12.3）。 */
    fun device(info: DeviceInfoProvider): NamespaceHandler {
        return DeviceNamespaceHandler(info)
    }

    /** `app` 命名空间：`launch`/`currentPackage`。 */
    fun app(launcher: AppLauncher): NamespaceHandler {
        return AppNamespaceHandler(launcher)
    }

    /** `floatingWindow` 命名空间：`create`/`close`（§9.4）。 */
    fun floatingWindow(host: FloatingWindowHost): NamespaceHandler {
        return FloatingWindowNamespaceHandler(host)
    }

    /**
     * `datastore` 命名空间（§9.6）：KV 六方法（`get`/`put`/`remove`/`contains`/
     * `keys`/`clear`）。参数即 [com.autoscript.domain.storage.DataStore] SPI 实现
     * （测试传 `InMemoryDataStore`，真机传 `:platform:system` 的 SQLite 实现）。
     * 存储面不共担五命名空间的门禁（应用私有 KV 无需授权）→ 独立注入缝
     * `AppShell.assemble` 的 `datastoreHandler`，不入 `systemHandlers` 束。
     */
    fun datastore(store: DataStore): NamespaceHandler {
        return DatastoreNamespaceHandler(store)
    }

    /**
     * `zip` 命名空间（§9.6）：`compress`/`extract` 两方法。参数即
     * [ZipArchiver] SPI 实现（测试传假归档器，
     * 真机传 `:platform:system` 的 `JdkZipArchiver`）。同 datastore：
     * 无共担门禁 → 独立注入缝 `AppShell.assemble` 的 `zipHandler`。
     */
    fun zip(archiver: ZipArchiver): NamespaceHandler {
        return ZipNamespaceHandler(archiver)
    }

    /**
     * `settings` 命名空间（§9.6 系统设置面）：`canWrite`/`getString`/`getInt`/
     * `putString`/`putInt` 五方法（照抄 [SystemSettings] 的两型，不提供猜型的 `get`/`put`
     * 别名 —— 串与数是两套系统 API，推断就是发明策略）。参数即 [SystemSettings] SPI 实现
     * （测试传真读假写的替身，真机传 `:platform:system` 的 `AndroidSystemSettings`）。
     * 同 datastore/zip：三者同属 §9.6 存储面、与五命名空间无共担门禁 → 独立注入缝
     * `AppShell.assemble` 的 `settingsHandler`。
     */
    fun settings(systemSettings: SystemSettings): NamespaceHandler {
        return SettingsNamespaceHandler(systemSettings)
    }

    /**
     * `notification` 命名空间（§12.2）：`canPost`/`post`/`cancel` 三方法。参数即
     * [NotificationPoster] SPI 实现（测试传真读假写的替身，真机传 `:platform:system`
     * 的 `AndroidNotificationPoster`）。同 datastore/zip/settings：**独立注入缝**
     * `AppShell.assemble` 的 `notificationHandler`，不入 `systemHandlers` 束 ——
     * 通知的门禁是 `POST_NOTIFICATIONS`，判据在 SPI（与那五个不共担）。
     */
    fun notification(poster: NotificationPoster): NamespaceHandler {
        return NotificationNamespaceHandler(poster)
    }

    /**
     * `clipboard` 命名空间（§12.2 系统剪贴板面）：`getText`/`setText` 两方法。
     * 参数即 [Clipboard] SPI 实现（测试传内存替身，真机传 `:platform:system` 的
     * `AndroidClipboard`）。同 datastore/zip/settings/notification：**独立注入缝**
     * `AppShell.assemble` 的 `clipboardHandler`，不入 `systemHandlers` 束 ——
     * 剪贴板无门禁（读受限是系统的 null 答案、写不受限，判据在 SPI 自己身上）。
     */
    fun clipboard(clipboard: Clipboard): NamespaceHandler {
        return ClipboardNamespaceHandler(clipboard)
    }

    /**
     * `sensors` 命名空间（§12.2 传感器面）：`isSupported`/`register`/`unregister`/
     * `unregisterAll`/`drain` 五方法。参数即 [SensorSource] SPI 实现（测试传内存替身，
     * 真机传 `:platform:system` 的 `AndroidSensorSource`）。同 datastore/zip/settings/
     * notification/clipboard：**独立注入缝** `AppShell.assemble` 的 `sensorsHandler`，
     * 不入 `systemHandlers` 束 —— P0 名单无运行时门禁（未知名→`ERR_NOT_SUPPORTED`、
     * 系统拒收→`ERR_SERVICE_DISABLED`，判据在 SPI 自己身上）。
     */
    fun sensors(sensors: SensorSource): NamespaceHandler {
        return SensorsNamespaceHandler(sensors)
    }

    /**
     * `images` 命名空间（§9.2 图像面）：`decode`/`matchTemplate`/`findImage`/`findColor`/
     * `toGrayscale`/`crop`/`resize`/`rotate`/`findFeature`/`release` 十方法。参数即
     * [ImageAnalyzer] SPI 实现（测试传假分析器，真机传
     * `:bridge:image` 的 native 管线）。同 datastore/zip/settings/notification/clipboard/
     * sensors：**独立注入缝** `AppShell.assemble` 的 `imagesHandler`，不入
     * `systemHandlers` 束 —— 图像面无共担门禁（文件缺失/句柄失效判据在 SPI 自己身上）。
     */
    fun images(analyzer: ImageAnalyzer): NamespaceHandler {
        return ImagesNamespaceHandler(analyzer)
    }
}
