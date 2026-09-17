package com.autoscript.domain.engine

import com.autoscript.domain.bridge.HandleRef

/**
 * 执行句柄 + 运行时通道契约（docs/framework-design.md §12.3 engines.exec / §8）。
 * JS 侧 `engines.exec()` 返回的 handle：可取消/停止，并带一个命名 RuntimeChannel 双向通信。
 * 事件语义 EventEmitter 由桥侧实现（:bridge:js/JS facade），领域层只给纯契约。
 */
interface RuntimeChannel {
    val name: String

    /** 脚本 -> 宿主：发事件（JSON 字符串载荷；大二进制走 side-channel，不走本载荷）。 */
    fun emit(event: String, payload: String?)

    /** 宿主 -> 脚本：订阅事件。返回可取消订阅的句柄。 */
    fun on(event: String, listener: (payload: String?) -> Unit): ChannelSubscription

    fun close()
}

interface ChannelSubscription {
    fun cancel()
}

/**
 * engines.exec 的执行句柄：取消（走引擎停止）/ 通道 / 退出回调。
 *
 * 退出订阅不可能是领域的 free 实现——引擎退出事件由：engine:node-process 状态回调经桥
 * EventBus 转发（docs §7/§12.3）。故 [exitSink] 由装配方注入（绑定引擎退出 → listener），
 * 领域层只维护签名契约。作为句柄而非值对象，刻意不用 data class（无值语义）。
 */
class EngineSessionHandle(
    val engine: ScriptEngine,
    val runReceipt: EngineRunReceipt,
    val channel: RuntimeChannel,
    private val exitSink: (listener: (CrashInfo?) -> Unit) -> ChannelSubscription,
) {
    /** 请求停止（优雅四步 quiesce，签名见 [ScriptEngine.stop]）。 */
    suspend fun cancel() = engine.stop()

    /** 订阅退出（委托装配方注入的 [exitSink]，STOPPED/CRASHED 时回调）。 */
    fun onExit(listener: (CrashInfo?) -> Unit): ChannelSubscription = exitSink(listener)
}