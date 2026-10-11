package com.autoscript.bridge

import com.autoscript.domain.bridge.AuthenticatedRunContext
import com.autoscript.domain.bridge.BridgeRequest
import com.autoscript.domain.bridge.BridgeResponse
import com.autoscript.domain.bridge.NamespaceHandler
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.Clock
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.core.SystemClock
import com.autoscript.domain.permission.BridgeCapabilityCatalog
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
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
 * 桥路由器（docs §7.5）：
 * - 按 namespace 路由，走 TTL 注册表（去重 + 到期收割）；
 * - **先授权后路由（A5，§11）**：按 [BridgeCapabilityCatalog] 要求本次执行的
 *   [CapabilityMask] 覆盖该面，未覆盖/未申报命名空间一律 `ERR_PERMISSION_DENIED`，
 *   且**不进请求账、不触达 handler**（deny-by-default）；
 * - 同步 dispatch：handler 在 TTL 内返回即回，超时 → ERR_TIMEOUT；
 * - 后台扫描线程按 [SWEEP_INTERVAL_MILLIS] 收割过期请求。
 *
 * **本地可信调用（无 [AuthenticatedRunContext]）不走掩码**：`:app` 装配/UI 自己的
 * 直投（`router.dispatch`）与单测走这条路，它们的授权由「能拿到宿主对象」本身承担。
 * 网络入口必然带身份（[NewlineFrameServer] 的 hello 认证），缺身份的面
 * （console/engines.heartbeat）另在各自 handler 里独立拒 —— 与本层的掩码过滤是两道闸。
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

    suspend fun dispatch(request: BridgeRequest): BridgeResponse = coroutineScope {
        val caller = coroutineContext[AuthenticatedRunContext]
        // 授权先于一切：未覆盖 → 拒绝，且不注册请求、不触达 handler。
        // 未知 namespace 在授权之后才判「未实现」——否则未授权调用方能用
        // ERR_NOT_IMPLEMENTED / ERR_PERMISSION_DENIED 的差别探出宿主挂了哪些面。
        if (caller != null) {
            val required = BridgeCapabilityCatalog.required(request.namespace, request.method)
            if (!caller.capabilityMask.covers(required)) {
                return@coroutineScope BridgeResponse.Err(
                    request.id,
                    ErrorCode.ERR_PERMISSION_DENIED.code,
                    "本次执行未授权 ${request.namespace}.${request.method}（需要 $required，持有 ${caller.capabilityMask}）",
                )
            }
        }
        val handler = handlers[request.namespace]
            ?: return@coroutineScope BridgeResponse.Err(request.id, ErrorCode.ERR_NOT_IMPLEMENTED.code, "未知 namespace: ${request.namespace}")
        // 0 仅为可信本地调用作用域；网络入口必须先认证，console/heartbeat 缺身份也会独立拒绝。
        val connectionId = caller?.connectionId ?: 0L
        val result = CompletableDeferred<BridgeResponse>()
        val ticket = registry.register(connectionId, request) { result.complete(it) }
            ?: return@coroutineScope BridgeResponse.Err(
                request.id,
                if (registry.isClosed()) ErrorCode.ERR_ENGINE_STOPPED.code else ErrorCode.ERR_INVALID_PARAM.code,
                "桥已关闭或本连接 requestId 重复",
            )
        val ttl = request.ttlMillis.takeIf { it > 0 } ?: RequestRegistry.DEFAULT_TTL_MILLIS
        val work = launch(start = CoroutineStart.LAZY) {
            val response = try {
                withTimeout(ttl) { handler.handle(request) }
            } catch (e: TimeoutCancellationException) {
                BridgeResponse.Err(request.id, ErrorCode.ERR_TIMEOUT.code, "handler 处理超时 $ttl ms")
            } catch (e: CancellationException) {
                throw e
            } catch (e: AutojsException) {
                // SPI 抛的领域异常自带真码（ERR_PERMISSION_DENIED/ERR_NOT_FOUND/…）——原样透传。
                // RpcNamespaceHandler 基类已经收过一次；这里是 handler 自己绕过基类直抛的那一半。
                BridgeResponse.Err(request.id, e.error.code, e.message)
            } catch (e: IllegalArgumentException) {
                // 参数/解码类（RpcNamespaceHandler 只收这两类之外的穿出，这里兜住 handler
                // 自己抛的那一半）——「能读懂的事实」，脚本据此改参数。
                BridgeResponse.Err(request.id, ErrorCode.ERR_INVALID_PARAM.code, "参数非法: ${e.message}")
            } catch (e: Exception) {
                // 其余一律「宿主内部错误」（2026-10-11 批 97，外审 M4）：**不是调用方的错**。
                // 此前折成 ERR_INVALID_PARAM 会让脚本把代码 bug 当成参数 bug 去改参数；
                // 回显 e.message 还会把内部路径/字段名带进 wire（外审同条的泄露面）。
                // 详情只在 detail（宿主侧另有堆栈日志），脚本侧按「宿主有问题」处理。
                BridgeResponse.Err(
                    request.id,
                    ErrorCode.ERR_INTERNAL.code,
                    "${request.namespace}.${request.method} 内部异常（${e::class.simpleName ?: "?"}）：${e.message}",
                )
            }
            registry.complete(ticket, response)
        }
        // TTL sweep/关闭与 handler 完成共用 ticket 的一次性结算；失败的一方不能回迟到成功。
        val completion = result.invokeOnCompletion { work.cancel() }
        work.invokeOnCompletion { cause ->
            if (cause is CancellationException && !result.isCompleted) result.cancel(cause)
        }
        try {
            work.start()
            result.await()
        } finally {
            registry.cancel(ticket)
            completion.dispose()
            work.cancel()
        }
    }

    fun closeConnection(connectionId: Long) { registry.finishConnection(connectionId) }

    override fun close() {
        registry.finishAll()
        supervisor.cancel()
    }

    companion object {
        const val SWEEP_INTERVAL_MILLIS: Long = 500
    }
}