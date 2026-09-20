package com.autoscript.platform.capabilities

import com.autoscript.domain.automation.FrameSource
import com.autoscript.domain.automation.InputProvider
import com.autoscript.domain.automation.UiActionExecutor
import com.autoscript.domain.automation.UiEventStream
import com.autoscript.domain.automation.UiNodeTreeReader
import com.autoscript.domain.bridge.BridgeResponse
import com.autoscript.domain.bridge.NamespaceHandler
import com.autoscript.domain.system.AppLauncher
import com.autoscript.domain.system.DeviceInfoProvider
import com.autoscript.domain.system.DialogHost
import com.autoscript.domain.system.FloatingWindowHost
import com.autoscript.domain.system.ShellExecutor

/**
 * 能力 handler → 桥挂载缝的薄转接（§4.1/§6）。
 *
 * 为什么转接层住本模块：`BridgeRouter` 的 `RequestHandler` 是 `:domain` 的
 * [NamespaceHandler]（`typealias`），而本模块只允许依赖 `:domain`
 * （§6 + ArchitectureTest 把 `com.autoscript.bridge..` 列进黑名单），
 * `:app` 又禁止直连 `:platform`。于是「自有 Request/Response 形状 → 桥信封」的
 * 字段级转接只能落在两端都合法的位置 = 本模块；`:app` 装配层
 * （`AppShell.assemble` 的 `a11yHandler`/`screenHandler` 缝）只拿现成的
 * [NamespaceHandler] 挂 Router —— 既不 new 具体实现，也不直连 `:platform`。
 *
 * 本文件无逻辑：逐字段搬运，不解释 payload、不吞错误、不改错误码（§7 桥侧只透传）。
 * handler 自己的方法表/错误分类在 [A11yNamespaceHandler] / [ScreenNamespaceHandler]。
 */
object CapabilityNamespaces {

    /**
     * `a11y` 命名空间（§9.1）：窗口树 + 动作 + 输入通道的装配缝。
     * 缺省三参 = 内存实现（单测/骨架可测）；Android 真实现（AccessibilityNodeInfo 遍历 /
     * dispatchGesture）到位 = 传三块 SPI 实现（树、动作、输入，可选事件流）再转接 ——
     * 本函数不解释 payload（见上），只做形状转接。
     */
    fun a11y(
        tree: UiNodeTreeReader,
        actions: UiActionExecutor,
        input: InputProvider = InMemoryInputProvider(),
        events: UiEventStream? = null,
    ): NamespaceHandler {
        val handler = A11yNamespaceHandler(tree, actions, input, events)
        return NamespaceHandler { request ->
            when (
                val r = handler.handle(
                    A11yNamespaceHandler.Request(request.id, request.method, request.payload),
                )
            ) {
                is A11yNamespaceHandler.Response.Ok -> BridgeResponse.Ok(r.id, r.payload)
                is A11yNamespaceHandler.Response.Err -> BridgeResponse.Err(r.id, r.code, r.detail)
            }
        }
    }

    /**
     * `screen` 命名空间（§9.2 / §8.8）：截图帧源，分类错误而非黑图。
     * 参数即 `FrameSource` SPI 实现 —— 本函数从不构造内存帧源（构造是调用方的事），
     * 单测传 `ScreenshotSource`、真机传 a11y takeScreenshot / MediaProjection 实现。
     */
    fun screen(source: FrameSource): NamespaceHandler {
        val handler = ScreenNamespaceHandler(source)
        return NamespaceHandler { request ->
            when (
                val r = handler.handle(
                    ScreenNamespaceHandler.Request(request.id, request.method, request.payload),
                )
            ) {
                is ScreenNamespaceHandler.Response.Ok -> BridgeResponse.Ok(r.id, r.payload)
                is ScreenNamespaceHandler.Response.Err -> BridgeResponse.Err(r.id, r.code, r.detail)
            }
        }
    }

    // ── 系统侧五个命名空间（§9.4/§9.6，handler 见 SystemNamespaces.kt）──────

    /** `shell` 命名空间：`exec`/`shell` 两方法（JS `extras.ts` 的 `shell()` 只是 `exec()` 的别名）。 */
    fun shell(
        executor: ShellExecutor,
        defaultTimeoutMillis: Long = DEFAULT_SHELL_TIMEOUT_MILLIS,
    ): NamespaceHandler {
        val handler = ShellNamespaceHandler(executor, defaultTimeoutMillis)
        return lite { request -> handler.handle(request) }
    }

    /** `device` 命名空间：`model`/`sdkInt`（P0 最小集，§12.3）。 */
    fun device(info: DeviceInfoProvider): NamespaceHandler {
        val handler = DeviceNamespaceHandler(info)
        return lite { request -> handler.handle(request) }
    }

    /** `app` 命名空间：`launch`/`currentPackage`。 */
    fun app(launcher: AppLauncher): NamespaceHandler {
        val handler = AppNamespaceHandler(launcher)
        return lite { request -> handler.handle(request) }
    }

    /** `dialogs` 命名空间：`prompt`/`choose`（§9.4 BAL 安全路径）。 */
    fun dialogs(host: DialogHost): NamespaceHandler {
        val handler = DialogsNamespaceHandler(host)
        return lite { request -> handler.handle(request) }
    }

    /** `floatingWindow` 命名空间：`create`/`close`（§9.4）。 */
    fun floatingWindow(host: FloatingWindowHost): NamespaceHandler {
        val handler = FloatingWindowNamespaceHandler(host)
        return lite { request -> handler.handle(request) }
    }
}

/**
 * [BridgeRequestLite] 形状的 handler → 桥信封的字段级转接（本文件私有）。
 * 五个系统侧命名空间共用（§9.4/§9.6）；同 [NamespaceHandler] 缝，无逻辑。
 */
private inline fun lite(crossinline handle: suspend (BridgeRequestLite) -> ResponseLite): NamespaceHandler =
    NamespaceHandler { request ->
        when (val r = handle(BridgeRequestLite(request.id, request.method, request.payload))) {
            is ResponseLite.Ok -> BridgeResponse.Ok(r.id, r.payload)
            is ResponseLite.Err -> BridgeResponse.Err(r.id, r.code, r.detail)
        }
    }
