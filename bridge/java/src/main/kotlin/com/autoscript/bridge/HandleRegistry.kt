package com.autoscript.bridge

import com.autoscript.domain.bridge.HandleRef
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 句柄注册表（generation + tombstone，docs/framework-design.md §7.4）。
 *
 * JS 侧代理对象持 HandleRef(refId, generation)；每次 acquire 提升 generation，
 * 使旧代理立即失效（ERR_STALE_HANDLE）；release 落 tombstone 拒绝后续访问；
 * sweep 供内存紧张时的 GC 扫描（对象池化场景）。
 * 线程安全。
 */
class HandleRegistry(private val seed: AtomicLong = AtomicLong(1)) {

    private class Holder(
        @Volatile var resource: Any,
        @Volatile var generation: Long = 1,
        @Volatile var tombstoned: Boolean = false,
    )

    private val holders = ConcurrentHashMap<Long, Holder>()

    /** 登记一个新资源，返回首个句柄（generation=1）。 */
    fun register(resource: Any): HandleRef {
        val id = seed.getAndIncrement()
        holders[id] = Holder(resource)
        return HandleRef(id, 1)
    }

    /** 重新获取：提升 generation 使旧代理失效，返回新句柄；id 不存在返回 null。 */
    fun acquire(id: Long): HandleRef? {
        val holder = holders[id] ?: return null
        if (holder.tombstoned) return null
        val gen = holder.generation + 1
        holder.generation = gen
        return HandleRef(id, gen)
    }

    /** 校验句柄仍有效（存在、未 tombstone、generation 匹配）。 */
    fun validate(ref: HandleRef): Boolean {
        val holder = holders[ref.refId] ?: return false
        return !holder.tombstoned && holder.generation == ref.generation
    }

    /** 校验通过则返回资源，否则 null。 */
    fun resourceOf(ref: HandleRef): Any? {
        val holder = holders[ref.refId] ?: return null
        return if (!holder.tombstoned && holder.generation == ref.generation) holder.resource else null
    }

    /** 释放（tombstone）：generation 匹配才生效，返回是否确实释放。 */
    fun release(ref: HandleRef): Boolean {
        val holder = holders[ref.refId] ?: return false
        if (holder.tombstoned || holder.generation != ref.generation) return false
        holder.tombstoned = true
        return true
    }

    /** 物理删除（对象池回收后清理登记）。 */
    fun forget(id: Long): Boolean = holders.remove(id) != null

    /** 扫描：对每个未 tombstone 的 id 应用判定，返回被清除的 id 集合。 */
    fun sweep(predicate: (id: Long) -> Boolean): List<Long> {
        val gone = holders.entries
            .filter { (id, h) -> !h.tombstoned && predicate(id) }
            .map { (id, h) -> h.tombstoned = true; id }
        return gone
    }

    fun size(): Int = holders.size
}