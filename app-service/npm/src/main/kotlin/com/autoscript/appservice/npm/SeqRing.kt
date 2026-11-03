package com.autoscript.appservice.npm

/**
 * 有界 seq 环（D7 自 `InstallCoordinator.kt` 原样外迁）：它不碰协调器的
 * 任何状态，是纯数据结构；三条纪律（seq 单调递增、超界丢最旧、空洞可见）见类 KDoc。
 *
 * `A11yEventRing` 是同一形状的另一份（那边服务 a11y 拉取面），两者各自持有自己的
 * 容量与投影，不合并 —— 合并就要引一层「谁的 projectId 语义」的抽象。
 */

/**
 * 有界 seq 环（`A11yEventRing` 同纪律）：seq 单调递增、超界丢最旧、空洞可见。
 *
 * - `drain` 按 [projectId] 过滤（脚本只看自己项目的事件）、`batch` 截断（未取完的下一批从
 *   `lastSeq+1` 续）；空增量回 `(sinceSeq, sinceSeq)` —— 调用方以游标为准，不以空数组终结；
 * - 丢最旧不告警而是**留空洞**：`first > sinceSeq + 1` 就是「中间丢过」，与 a11y 同口径
 *   （进度数据面本就可丢包，§7.3；静默断流才是要禁的）。
 */
internal class SeqRing<T>(private val capacity: Int) {
    private val guard = Any()
    private val entries = ArrayList<Entry<T>>()
    private var head = 0L

    internal class Entry<T>(val seq: Long, val projectId: String, val value: T)

    /** 任意线程投递；锁内分配序号并追加（超界丢最旧）。 */
    fun push(projectId: String, value: T) {
        synchronized(guard) {
            entries.add(Entry(++head, projectId, value))
            while (entries.size > capacity) entries.removeAt(0)
        }
    }

    /** 返回 (本批首序号, 本批末序号, 命中的 (seq, value))。 */
    fun drain(projectId: String, sinceSeq: Long, batch: Int): Triple<Long, Long, List<Pair<Long, T>>> {
        require(batch > 0) { "batch 必须 > 0" }
        val picked = synchronized(guard) {
            entries.asSequence()
                .filter { it.seq > sinceSeq && it.projectId == projectId }
                .take(batch)
                .map { it.seq to it.value }
                .toList()
        }
        if (picked.isEmpty()) return Triple(sinceSeq, sinceSeq, emptyList())
        return Triple(picked.first().first, picked.last().first, picked)
    }
}
