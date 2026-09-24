package com.autoscript.shell

import android.content.Context
import com.autoscript.domain.bridge.NamespaceHandler
import com.autoscript.domain.system.DialogHost
import com.autoscript.platform.capabilities.AndroidDialogHost
import com.autoscript.platform.capabilities.AndroidFrameProducer
import com.autoscript.platform.capabilities.AndroidGestureInput
import com.autoscript.platform.capabilities.AndroidUiTree
import com.autoscript.platform.capabilities.CapabilityNamespaces
import com.autoscript.platform.capabilities.ScreenshotSource
import com.autoscript.platform.capabilities.device.SystemDialogOps
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
 *
 * **`dialogs` 生产已接**：[of] 用同一 `overlayAvailable` 构造
 * `AndroidDialogHost(SystemDialogOps(...))`（实现住 :platform:capabilities ——
 * domain KDoc 约定 + 平台模块间无依赖边，构造只能在本类）；[inject] 的
 * `dialogs` 参数缺省 null（单测不传 → 仍如实 `ERR_NOT_IMPLEMENTED`，测试走
 * 另一套装配不留门靠的是"同函数可注入真/假"，不是绑死构造）。
 */
object PlatformWiring {

    /**
     * `AppShellKit.assemble` 的能力注入束：五个独立缝（存储/通知/剪贴板面，§12.2 接线表）
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
        val clipboardHandler: NamespaceHandler,
    )

    /**
     * SPI 束 → 注入束（纯转接：不解释 payload、不吞错误、不做权限判断）。
     * [dialogs] 缺省 null = 未提供（桥如实 ERR_NOT_IMPLEMENTED）；生产由 [of] 传真宿主。
     */
    fun inject(spis: SystemSpis.Bundle, dialogs: DialogHost? = null): Injection = Injection(
        // 树+动作同一个实例（句柄注册表共享，同 InMemoryUiTree 双身份形态）；
        // 事件流缺省 A11yEventRing.shared（服务 push / 树读同一环）。
        a11yHandler = a11yHandler(),
        screenHandler = screenHandler(),
        systemHandlers = SystemHandlers(
            // 缺省 null（未提供）→ 如实 ERR_NOT_IMPLEMENTED；生产由 of() 传真宿主。
            dialogs = dialogs?.let { CapabilityNamespaces.dialogs(it) },
            shell = CapabilityNamespaces.shell(spis.shell),
            device = CapabilityNamespaces.device(spis.device),
            app = CapabilityNamespaces.app(spis.app),
            floatingWindow = CapabilityNamespaces.floatingWindow(spis.floatingWindow),
        ),
        datastoreHandler = CapabilityNamespaces.datastore(spis.datastore),
        zipHandler = CapabilityNamespaces.zip(spis.zip),
        settingsHandler = CapabilityNamespaces.settings(spis.settings),
        notificationHandler = CapabilityNamespaces.notification(spis.notification),
        clipboardHandler = CapabilityNamespaces.clipboard(spis.clipboard),
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
     * 生产入口：`Context` → [SystemSpis.of] 八件 + DialogHost 构造（本类是唯一同时
     * 碰得到两个平台模块与 overlay 实况的装配点）→ [inject]。
     * `overlayAvailable` 缺省 `{ false }`：悬浮窗/对话框先走 `TYPE_APPLICATION_OVERLAY`；
     * a11y 服务在跑时由调用方改传 `{ true }`（语义见 [SystemSpis.of]）——
     * 同一个探针喂给悬浮窗与对话框两条路，不各读各的。
     */
    fun of(context: Context, overlayAvailable: () -> Boolean = { false }): Injection {
        val app = context.applicationContext
        return inject(
            SystemSpis.of(context, overlayAvailable),
            dialogs = AndroidDialogHost(SystemDialogOps(app, overlayAvailable), overlayAvailable),
        )
    }
}
