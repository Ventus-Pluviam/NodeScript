package com.autoscript.bridge

import com.autoscript.domain.bridge.BridgeRequest
import com.autoscript.domain.bridge.BridgeResponse
import com.autoscript.domain.core.Clock
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.core.SystemClock

/**
 * 请求账（§7.5）：连接号由宿主分配，回复仍使用客户端 id。注册返回唯一 ticket，
 * 完成/取消/TTL 均按 ticket 核销，迟到的旧结果不能删掉后来复用 id 的请求。
 * 状态同锁，completer 在锁外执行；关闭后拒绝新增，避免 close/register 窗口漏单。
 */
class RequestRegistry(private val clock: Clock = SystemClock) {
    data class Key(val connectionId: Long, val requestId: Long)
    class Ticket internal constructor(
        val key: Key,
        internal val deadline: Long,
        internal val completer: (BridgeResponse) -> Unit,
    )

    private val lock = Any()
    private val pending = HashMap<Key, Ticket>()
    private var closed = false

    fun register(connectionId: Long, request: BridgeRequest, completer: (BridgeResponse) -> Unit): Ticket? = synchronized(lock) {
        val key = Key(connectionId, request.id)
        if (closed || pending.containsKey(key)) return null
        // 上游只挡了 <=0，正数但极大的 ttl 会在这里 `now + ttl` 溢出成负数 —— 过期即
        // 到期，语义上是"立即超时"，不是"一段很长的时间"（外审 B4：now+ttl 溢出）。
        // 显式截到上限：宁可提前收割，不让溢出把「立即超时」伪装成别的意思。
        val ttl = (request.ttlMillis.takeIf { it > 0 } ?: DEFAULT_TTL_MILLIS).coerceAtMost(MAX_TTL_MILLIS)
        Ticket(key, clock.nowMillis() + ttl, completer).also { pending[key] = it }
    }

    fun complete(ticket: Ticket, response: BridgeResponse): Boolean {
        val removed = synchronized(lock) { pending.remove(ticket.key, ticket) }
        if (removed) ticket.completer(response)
        return removed
    }

    fun cancel(ticket: Ticket): Boolean = synchronized(lock) { pending.remove(ticket.key, ticket) }

    fun expireDue(nowMillis: Long = clock.nowMillis()): Int {
        val due = synchronized(lock) { pending.values.filter { it.deadline <= nowMillis } }
        return due.count { complete(it, BridgeResponse.Err(it.key.requestId, ErrorCode.ERR_TIMEOUT.code, "请求超过 TTL")) }
    }

    fun finishConnection(connectionId: Long): Int = finishMatching { it.key.connectionId == connectionId }

    fun finishAll(errorCode: ErrorCode = ErrorCode.ERR_ENGINE_STOPPED): Int {
        synchronized(lock) { closed = true }
        return finishMatching(errorCode) { true }
    }

    private fun finishMatching(error: ErrorCode = ErrorCode.ERR_ENGINE_STOPPED, match: (Ticket) -> Boolean): Int {
        val all = synchronized(lock) { pending.values.filter(match) }
        return all.count { complete(it, BridgeResponse.Err(it.key.requestId, error.code, null)) }
    }

    fun isRegistered(connectionId: Long, id: Long): Boolean = synchronized(lock) { pending.containsKey(Key(connectionId, id)) }
    fun size(): Int = synchronized(lock) { pending.size }
    fun isClosed(): Boolean = synchronized(lock) { closed }

    companion object {
        const val DEFAULT_TTL_MILLIS: Long = 5_000

        /** 单请求 TTL 上限（外审 B4）：防 `now + ttl` 溢出成负数 = 到期即超时。 */
        const val MAX_TTL_MILLIS: Long = 24L * 60 * 60 * 1000
    }
}
