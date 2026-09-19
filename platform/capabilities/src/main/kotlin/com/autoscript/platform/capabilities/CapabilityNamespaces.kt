package com.autoscript.platform.capabilities

import com.autoscript.domain.bridge.BridgeResponse
import com.autoscript.domain.bridge.NamespaceHandler

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

    /** `a11y` 命名空间（§9.1）：窗口树 + 输入通道的 JVM 可测形态。 */
    fun a11y(
        tree: InMemoryUiTree,
        input: InMemoryInputProvider = InMemoryInputProvider(),
    ): NamespaceHandler {
        val handler = A11yNamespaceHandler(tree, input)
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

    /** `screen` 命名空间（§9.2 / §8.8）：截图帧源，分类错误而非黑图。 */
    fun screen(source: ScreenshotSource): NamespaceHandler {
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
}
