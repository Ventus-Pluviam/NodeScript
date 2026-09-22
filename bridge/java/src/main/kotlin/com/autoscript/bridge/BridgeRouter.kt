package com.autoscript.bridge

import com.autoscript.domain.bridge.BridgeRequest
import com.autoscript.domain.bridge.BridgeResponse
import com.autoscript.domain.bridge.NamespaceHandler
import com.autoscript.domain.core.Clock
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.core.SystemClock
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

/**
 * namespace → 请求处理器（实现方注册，如 a11y / images / npm）。
 *
 * 即 `:domain` 的 [com.autoscript.domain.bridge.NamespaceHandler]（§4.1/§6）：
 * 实现方（`:platform:capabilities` 的 a11y/screen handler）只允许依赖 `:domain`，
 * 因此挂载缝的类型必须住在 `:domain`，本名保留了「桥侧叫法」，两端是同一个函数类型。
 */
typealias RequestHandler = com.autoscript.domain.bridge.NamespaceHandler

/**
 * 桥路由器（docs/framework-design.md §7.5）：
 * - 按 namespace 路由，走 TTL 注册表（去重 + 到期收割）；
 * - 同步 dispatch：handler 在 TTL 内返回即回，超时 → ERR_TIMEOUT；
 * - 后台扫描线程按 [SWEEP_INTERVAL_MILLIS] 收割过期请求。
 */
class BridgeRouter(
    private val registry: RequestRegistry,
    @Suppress("unused") private val clock: Clock = SystemClock,
    private val supervisor: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : AutoCloseable {

    private val handlers = ConcurrentHashMap<String, RequestHandler>()

    init {
        supervisor.launch {
            while (isActive) {
                delay(SWEEP_INTERVAL_MILLIS)
                registry.expireDue()
            }
        }
    }

    fun register(namespace: String, handler: RequestHandler): Boolean =
        handlers.putIfAbsent(namespace, handler) == null

    suspend fun dispatch(request: BridgeRequest): BridgeResponse {
        val handler = handlers[request.namespace]
        if (handler == null) {
            return BridgeResponse.Err(
                request.id, ErrorCode.ERR_NOT_IMPLEMENTED.code,
                "未知 namespace: ${request.namespace}",
            )
        }
        if (!registry.register(request) { /* 异步请求的 completer 语义：由 owner 后续 complete；同步路径直接返回 */ }) {
            return BridgeResponse.Err(
                request.id, ErrorCode.ERR_INVALID_PARAM.code,
                "重复 requestId: ${request.id}",
            )
        }
        val ttl = request.ttlMillis.takeIf { it > 0 } ?: RequestRegistry.DEFAULT_TTL_MILLIS
        return try {
            withTimeout(ttl) { handler.handle(request) }
                .also { registry.complete(request.id, it) }
        } catch (e: TimeoutCancellationException) {
            registry.complete(request.id, BridgeResponse.Err(request.id, ErrorCode.ERR_TIMEOUT.code, "handler 处理超时 $ttl ms"))
            BridgeResponse.Err(request.id, ErrorCode.ERR_TIMEOUT.code, "handler 处理超时 $ttl ms")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            registry.complete(request.id, BridgeResponse.Err(request.id, ErrorCode.ERR_INVALID_PARAM.code, "handler 异常: ${e.message}"))
            BridgeResponse.Err(request.id, ErrorCode.ERR_INVALID_PARAM.code, "handler 异常: ${e.message}")
        }
    }

    override fun close() {
        registry.finishAll()
        supervisor.cancel()
    }

    companion object {
        const val SWEEP_INTERVAL_MILLIS: Long = 500
    }
}