package com.autoscript.shell

import android.content.Context
import com.autoscript.domain.bridge.NamespaceHandler
import com.autoscript.platform.capabilities.AndroidFrameProducer
import com.autoscript.platform.capabilities.AndroidGestureInput
import com.autoscript.platform.capabilities.AndroidUiTree
import com.autoscript.platform.capabilities.CapabilityNamespaces
import com.autoscript.platform.capabilities.ScreenshotSource
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
 * **a11y/screen 生产已接**（同一座无障碍服务做底）：
 * - `a11y` = `AndroidUiTree`（树+动作一体，句柄注册表共享）+ `AndroidGestureInput`；
 * - `screen` = `ScreenshotSource(AndroidFrameProducer())`（§9.2 a11y 截图路径：
 *   333ms 节流 + §8.8 策略预检/回调分类；MediaProjection 高清会话是后续升级，
 *   换 producer 即插）；
 * 二者都走 `SystemA11yBridge` —— 装配期即可注入（连接态在调用期判定），服务未连 =
 * 桥如实 `ERR_SERVICE_DISABLED`（不伪造可用，也不必等 `onServiceConnected` 才装壳）。
 * **`dialogs` 仍诚实缺位**：`DialogHost` 待 §14 P2，缺位即桥如实 `ERR_NOT_IMPLEMENTED`。
 */
object PlatformWiring {

    /**
     * `AppShellKit.assemble` 的能力注入束：四个独立缝（存储/通知面，§12.2 接线表）
     * + 五命名空间束（共担门禁的系统面）。形状与 assemble 的参数一一对应，少一层猜。
     */
    data class Injection(
        val a11yHandler: NamespaceHandler,
        val screenHandler: NamespaceHandler,
        val systemHandlers: SystemHandlers,
        val datastoreHandler: NamespaceHandler,
        val zipHandler: NamespaceHandler,
        val settingsHandler: NamespaceHandler,
        val notificationHandler: NamespaceHandler,
    )

    /** SPI 束 → 注入束（纯转接：不解释 payload、不吞错误、不做权限判断）。 */
    fun inject(spis: SystemSpis.Bundle): Injection = Injection(
        // 树+动作同一个实例（句柄注册表共享，同 InMemoryUiTree 双身份形态）；
        // 事件流缺省 A11yEventRing.shared（服务 push / 树读同一环）。
        a11yHandler = a11yHandler(),
        screenHandler = screenHandler(),
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

    /** a11y 装配（[CapabilityNamespaces.a11y] 形状转接；实现在 :platform:capabilities）。 */
    private fun a11yHandler(): NamespaceHandler {
        val tree = AndroidUiTree()
        return CapabilityNamespaces.a11y(tree = tree, actions = tree, input = AndroidGestureInput())
    }

    /** screen 装配（§9.2 a11y 截图路径：语义节流/策略在 ScreenshotSource，设备面在 producer）。 */
    private fun screenHandler(): NamespaceHandler =
        CapabilityNamespaces.screen(ScreenshotSource(AndroidFrameProducer()))

    /**
     * 生产入口：`Context` → [SystemSpis.of] 八件 → [inject]。
     * `overlayAvailable` 缺省 `{ false }`：悬浮窗先走 `TYPE_APPLICATION_OVERLAY`；
     * a11y 服务在跑时由调用方改传 `{ true }`（语义见 [SystemSpis.of]）。
     */
    fun of(context: Context, overlayAvailable: () -> Boolean = { false }): Injection =
        inject(SystemSpis.of(context, overlayAvailable))
}
