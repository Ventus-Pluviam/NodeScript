package com.autoscript.bridge

import com.autoscript.domain.bridge.BridgeRequest
import com.autoscript.domain.bridge.BridgeResponse
import com.autoscript.domain.core.ErrorCode
import java.util.concurrent.ConcurrentHashMap

/**
 * 请求注册表（TTL 语义，docs/framework-design.md §7.5）。
 *
 * 职责：
 * - register：requestId → 待完成请求 + 到期时间（TTL 默认 [DEFAULT_TTL_MILLIS]）；
 * - complete：请求方拿到结果后核销（返回是否确实在册）；
 * - expireDue：收割到期请求，统一以 ERR_TIMEOUT 完成返回；
 * - finishAll：关停时以 ERR_ENGINE_STOPPED 全部完成（不再等待）。
 *
 * 线程安全；completer 在调用线上同步执行，调用方负责把响应投递到目标队列（TSF/EventBus）。
 */
class RequestRegistry(
    private val clock: Clock = SystemClock,
) {
    private data class Pending(
        val request: BridgeRequest,
        val deadlineMillis: Long,
        val completer: (BridgeResponse) -> Unit,
    )

    private val pending = ConcurrentHashMap<Long, Pending>()

    /** 注册请求。同 id 重复注册返回 false（发送方去重/拒绝）。 */
    fun register(request: BridgeRequest, completer: (BridgeResponse) -> Unit): Boolean {
        val ttl = request.ttlMillis.takeIf { it > 0 } ?: DEFAULT_TTL_MILLIS
        val deadline = clock.nowMillis() + ttl
        return pending.putIfAbsent(request.id, Pending(request, deadline, completer)) == null
    }

    /** 核销一个请求：若在册则完成并移除，返回 true；不存在/已到期返回 false。 */
    fun complete(id: Long, response: BridgeResponse): Boolean {
        val removed = pending.remove(id) ?: return false
        removed.completer(response)
        return true
    }

    /** 收割到期请求（以 ERR_TIMEOUT 完成）。返回收割数量；completer 已同步执行。 */
    fun expireDue(nowMillis: Long = clock.nowMillis()): Int {
        if (pending.isEmpty()) return 0
        var count = 0
        // 收集后在锁外逐个完成，避免 completer 回调里再操作 registry 导致 ConcurrentModification
        val due = pending.entries.filter { it.value.deadlineMillis <= nowMillis }
        for ((id, p) in due) {
            if (pending.remove(id) != null) {
                count++
                p.completer(
                    BridgeResponse.Err(
                        id = id,
                        errorCode = ErrorCode.ERR_TIMEOUT.code,
                        detail = "请求 ${p.request.namespace}.${p.request.method} 超过 TTL ${p.request.ttlMillis}ms",
                    ),
                )
            }
        }
        return count
    }

    /** 立即以指定错误完成全部在册请求（服务关停/引擎死亡时）。 */
    fun finishAll(errorCode: ErrorCode = ErrorCode.ERR_ENGINE_STOPPED): Int {
        val all = pending.keys.toList()
        var count = 0
        for (id in all) {
            if (complete(id, BridgeResponse.Err(id, errorCode.code, null))) count++
        }
        return count
    }

    fun isRegistered(id: Long): Boolean = pending.containsKey(id)
    fun size(): Int = pending.size

    companion object {
        const val DEFAULT_TTL_MILLIS: Long = 5_000
    }
}