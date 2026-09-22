package com.autoscript.platform.capabilities

import com.autoscript.domain.automation.UiEvent
import com.autoscript.domain.automation.UiEventBatch
import com.autoscript.domain.automation.UiEventStream

/**
 * 无障碍事件环（§9.1 节流拉取式的宿主侧缓冲；游标契约在 [UiEventStream]）。
 *
 * - 有界（[MAX_EVENTS]，与 `InMemoryUiTree` 同值同纪律）：超界丢最旧 ——
 *   事件是开放流，消费者以 `sinceSeq` 游标为准，丢事件靠 seq 空洞可见，不静默断流；
 * - `nodeHandle` 恒 null：系统事件（窗口/内容变化）的来源节点不在句柄注册表里，
 *   伪造一个 refId 就是给 JS 一个一操作就 STALE 的假句柄；
 * - `type` 用 Android 事件的诚实名（`windowStateChanged`/`windowContentChanged`/
 *   `viewScrolled`），与内存树的合成名（`nodeAdded` 等 —— 那是注册表变异）区分；
 * - `payload` = 事件源 className（系统给的唯一附加事实；没有就 null，不编）。
 *
 * [shared] 给生产：服务 `onAccessibilityEvent` push、[AndroidUiTree.events] 读；
 * 测试自建实例（构造器公开）不串全局态。
 */
class A11yEventRing : UiEventStream {

    private val guard = Any()
    private val events = ArrayList<UiEvent>()
    private var nextSeq = 1L

    /** 服务侧投递（任意线程；锁内追加）。 */
    fun push(type: String, payload: String?) {
        synchronized(guard) {
            events.add(UiEvent(seq = nextSeq++, type = type, nodeHandle = null, payload = payload))
            while (events.size > MAX_EVENTS) events.removeAt(0)
        }
    }

    override suspend fun next(sinceSeq: Long, batch: Int): UiEventBatch {
        require(batch > 0) { "batch 必须 > 0" }
        val picked = synchronized(guard) {
            events.filter { it.seq > sinceSeq }.take(batch)
        }
        if (picked.isEmpty()) return UiEventBatch(sinceSeq, sinceSeq, emptyList())
        return UiEventBatch(picked.first().seq, picked.last().seq, picked)
    }

    companion object {
        const val MAX_EVENTS = 512

        /** 生产单例（服务 push 与树读必须是同一实例）。 */
        val shared = A11yEventRing()
    }
}
