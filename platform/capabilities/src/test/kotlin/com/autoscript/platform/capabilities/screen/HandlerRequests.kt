package com.autoscript.platform.capabilities.screen

import com.autoscript.domain.bridge.BridgeRequest

/**
 * 测试请求工厂（审查步骤 4：handler 直接吃 `:domain` 的 [BridgeRequest]，自定义 Request 形状退役）。
 * 参数面与旧 `XxxNamespaceHandler.Request(id, method, payload[, ttlMillis])` 逐位对齐 ——
 * 存量调用点只改名不改参。
 *
 * 住 `screen/` 子包而非测试根（D5）：调用方全在本子包内，工厂与用例同包 ——
 * 无障碍面那份在 `a11y/HandlerRequests.kt`，两份不互相引用（各自 namespace 各管各的）。
 */
internal fun screenReq(id: Long, method: String, payload: String?, ttlMillis: Long = 5_000) =
    BridgeRequest(id, "screen", method, payload, ttlMillis)
