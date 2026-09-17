package com.autoscript.bridge

import java.util.concurrent.ConcurrentHashMap

/**
 * 事件总线（seq 游标 + 有界背压，docs/framework-design.md §7/§9.1 UiEventStream 对齐）。
 *
 * 语义：
 * - 每 topic 一条按 seq 递增的有界历史；消费者持自己的游标，drain(topic, sinceSeq) 拉取增量；
 * - 队列打满时按 [OverflowPolicy] 丢最老（DROP_OLDEST）或拒绝本次发布（REJECT）；
 * - publish 返回 ACCEPTED(seq) / DROPPED / REJECTED，便于调用方感知背压。
 * 线程安全；不发消息给订阅者回调（回调由消费者主动 drain 驱动），与"节流拉取式"契约一致。
 */
class EventBus(
    private val capacityPerTopic: Int = 1_024,
    private val overflow: OverflowPolicy = OverflowPolicy.DROP_OLDEST,
) {

    enum class OverflowPolicy { DROP_OLDEST, REJECT }

    sealed interface PublishResult {
        data class Accepted(val seq: Long) : PublishResult
        data object Dropped : PublishResult
        data object Rejected : PublishResult
    }

    data class Event(val seq: Long, val topic: String, val payload: String?)

    private data class TopicState(val events: ArrayList<Event> = ArrayList(), var nextSeq: Long = 1L)

    private val topics = ConcurrentHashMap<String, TopicState>()

    fun publish(topic: String, payload: String?): PublishResult {
        val state = topics.computeIfAbsent(topic) { TopicState() }
        return synchronized(state) {
            if (state.events.size >= capacityPerTopic) {
                when (overflow) {
                    OverflowPolicy.REJECT -> return@synchronized PublishResult.Rejected
                    OverflowPolicy.DROP_OLDEST -> state.events.removeAt(0)
                }
            }
            val seq = state.nextSeq++
            state.events.add(Event(seq, topic, payload))
            PublishResult.Accepted(seq)
        }
    }

    /** 拉取增量事件（seq > sinceSeq，按序）；返回本次拉到的最大 seq（无则 sinceSeq）。 */
    fun drain(topic: String, sinceSeq: Long, max: Int = 128): Pair<Long, List<Event>> {
        val state = topics[topic] ?: return sinceSeq to emptyList()
        return synchronized(state) {
            val picked = ArrayList<Event>(minOf(max, state.events.size))
            var last = sinceSeq
            for (e in state.events) {
                if (e.seq > sinceSeq) {
                    picked.add(e)
                    last = e.seq
                    if (picked.size >= max) break
                }
            }
            last to picked
        }
    }

    fun topics(): Set<String> = topics.keys
}