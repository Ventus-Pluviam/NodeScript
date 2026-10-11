package com.autoscript.domain.bridge

import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode

/**
 * RPC 处理器基类：**错误映射收口在这里**，子类只写业务分发。
 *
 * 折叠规则与全仓 60 处手写 catch 逐字同语义，只收两类：
 * - [AutojsException] → `ERR`（SPI 原码透传，`e.error.code`）；
 * - [IllegalArgumentException] → `ERR_INVALID_PARAM`（解码/参数校验的统一出口，
 *   `Decode.kt` 的 helpers 全抛它）。
 *
 * **未知异常**：本基类**确实不接**（照旧穿出）；但**穿出不等于「裸奔到 wire」** ——
 * `:bridge:java` 的 `BridgeRouter` 在更外层还有一档 `catch (e: Exception)`，把它折成
 * `ERR_INTERNAL`。所以对写 handler 的人：**未知异常最终会变成 `ERR_INTERNAL` 回到脚本，
 * 而不是进程炸、也不是 `ERR_INVALID_PARAM`**（后者是批 97 之前的旧行为，已订正）。
 *
 * **本基类为什么还要留着这两档而不是全交给 Router**：子类调用栈深，中途穿出的
 * `AutojsException` 会被自己的 `try`/`catch` 误捕或变形；就地收口让「这个 handler 的
 * 参数错误」在最近的一层就被命名。Router 那档只兜「绕过基类的那一半」（见其 KDoc）。
 *
 * 未知方法不在这里兜：`dispatch` 的 `else` 分支按各自命名空间回
 * `ERR_NOT_IMPLEMENTED`（消息带方法名，§7.5 不猜别名）。
 *
 * [methods] 挂点在 [NamespaceHandler]：本基类不再重复声明，子类申报。
 */
abstract class RpcNamespaceHandler : NamespaceHandler {

    final override suspend fun handle(request: BridgeRequest): BridgeResponse = try {
        dispatch(request)
    } catch (e: AutojsException) {
        BridgeResponse.Err(request.id, e.error.code, e.message)
    } catch (e: IllegalArgumentException) {
        BridgeResponse.Err(request.id, ErrorCode.ERR_INVALID_PARAM.code, e.message)
    }

    /** 业务分发：`when (request.method)` + 业务 + 参数校验；错误处理不再出现在本函数。 */
    protected abstract suspend fun dispatch(request: BridgeRequest): BridgeResponse

    protected fun ok(request: BridgeRequest, payload: String?): BridgeResponse =
        BridgeResponse.Ok(request.id, payload)

    protected fun err(request: BridgeRequest, code: ErrorCode, detail: String?): BridgeResponse =
        BridgeResponse.Err(request.id, code.code, detail)

    protected fun notImplemented(request: BridgeRequest, namespace: String): BridgeResponse =
        err(request, ErrorCode.ERR_NOT_IMPLEMENTED, "未知 $namespace 方法: ${request.method}")
}
