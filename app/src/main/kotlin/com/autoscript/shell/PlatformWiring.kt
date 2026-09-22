package com.autoscript.shell

import android.content.Context
import com.autoscript.domain.bridge.NamespaceHandler
import com.autoscript.platform.capabilities.CapabilityNamespaces
import com.autoscript.platform.system.SystemSpis

/**
 * 生产能力装配：`SystemSpis` + `CapabilityNamespaces` → `AppShellKit.assemble` 的注入束
 * （docs §12.2「分两层」的接线落点 + §6 **包级例外二**）。
 *
 * 为什么住 `com.autoscript.shell`：§6 只放行这一个包依赖 `:platform:*`（与 `:bridge:java`
 * 例外同形）——`AppShellApplication`（根包）只调本类、不 import 任何 `com.autoscript.platform..`，
 * `ArchitectureTest` 的「平台实现只许装配包碰」把这条钉死。
 *
 * 分两步是刻意的：
 * - [of] 是**唯一碰 Android 的一步**（`Context` → `SystemSpis.of` 八件 SPI）；
 * - [inject] 是纯转接（SPI 束 → handler 束，零 Android 触点）→ JVM 可单测，
 *   真假实现共用同一条拼装路径，不给"测试走另一套装配"留门。
 *
 * **诚实缺位**：
 * - `dialogs` 恒 null —— `DialogHost` 待 §14 P2，缺位即桥如实 `ERR_NOT_IMPLEMENTED`，
 *   不拿假弹窗凑；
 * - `a11y`/`screen` 不在本类接 —— 它们的 Android 真实现（AccessibilityService /
 *   MediaProjection 的服务实例）尚未落地，接内存实现就是伪造可用；落地后走
 *   `AppShellKit.assemble` 的同一条缝（调用方也可经 `install` 自行注入）。
 */
object PlatformWiring {

    /**
     * `AppShellKit.assemble` 的能力注入束：四个独立缝（存储/通知面，§12.2 接线表）
     * + 五命名空间束（共担门禁的系统面）。形状与 assemble 的参数一一对应，少一层猜。
     */
    data class Injection(
        val systemHandlers: SystemHandlers,
        val datastoreHandler: NamespaceHandler,
        val zipHandler: NamespaceHandler,
        val settingsHandler: NamespaceHandler,
        val notificationHandler: NamespaceHandler,
    )

    /** SPI 束 → 注入束（纯转接：不解释 payload、不吞错误、不做权限判断）。 */
    fun inject(spis: SystemSpis.Bundle): Injection = Injection(
        systemHandlers = SystemHandlers(
            dialogs = null,   // DialogHost 待 §14 P2：缺位如实 ERR_NOT_IMPLEMENTED
            shell = CapabilityNamespaces.shell(spis.shell),
            device = CapabilityNamespaces.device(spis.device),
            app = CapabilityNamespaces.app(spis.app),
            floatingWindow = CapabilityNamespaces.floatingWindow(spis.floatingWindow),
        ),
        datastoreHandler = CapabilityNamespaces.datastore(spis.datastore),
        zipHandler = CapabilityNamespaces.zip(spis.zip),
        settingsHandler = CapabilityNamespaces.settings(spis.settings),
        notificationHandler = CapabilityNamespaces.notification(spis.notification),
    )

    /**
     * 生产入口：`Context` → [SystemSpis.of] 八件 → [inject]。
     * `overlayAvailable` 缺省 `{ false }`：悬浮窗先走 `TYPE_APPLICATION_OVERLAY`；
     * a11y 服务在跑时由调用方改传 `{ true }`（语义见 [SystemSpis.of]）。
     */
    fun of(context: Context, overlayAvailable: () -> Boolean = { false }): Injection =
        inject(SystemSpis.of(context, overlayAvailable))
}
