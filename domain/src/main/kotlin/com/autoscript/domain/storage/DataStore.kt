package com.autoscript.domain.storage

/**
 * `datastore` 领域契约（docs/framework-design.md §9.6）：SQLite-backed KV +
 * serializer 适配（JSON / native 对象 / byte）、事务语义。
 *
 * 为什么住 `:domain`：真实现要碰 SQLite（Android），§6 要求 `:platform:*` 只依赖
 * `:domain`；把「问什么」（本文件）与「怎么存」（`:platform:system`）切开，
 * handler 才是纯 JVM 可测的 —— 与 [com.autoscript.domain.system.ShellExecutor] /
 * [com.autoscript.domain.system.FloatingWindowHost] 同一模式。
 *
 * **同步 importer 不进本 SPI**：铁律 2（跨进程/线程边界一律 Promise，禁同步变体）
 * 把同步写死死拦在边界外；「纯 JS datastore importer」只活在 JS 进程内的纯内存
 * 路径（§7 铁律 2 注脚），不经本契约 —— 本 SPI 全部挂起，纯内存参考实现
 * [InMemoryDataStore] 也不例外（不给未来复制同步入口留门）。
 *
 * **命名空间不进本层**：`storages.create(name)` 一类的多库由 handler 在键前缀上
 * 兑现（`name:key`，实现仍是同一张 KV 面）；领域层不发明第二套路由。
 *
 * **值解释权不进本层**：[StoredEntry.Json] 是透传文本 —— handler 原样搬运、
 * 实现原样存储，谁都不在这里解析业务 JSON（domain 无 JSON 解析器，是刻意的）。
 */

/**
 * 可存值（serializer 适配的两面）。
 * 「JSON / native 对象 / byte」三形态在契约层的投影：结构化值一律以 JSON 文本
 * 过界（native 对象由 JS 侧序列化、handler 透传），二进制走 [Bytes]。
 */
sealed interface StoredEntry {

    /**
     * JSON 文本承载（对象/数组/标量）。
     * - 文本须是**合法 JSON**，由调用方（handler）保证 —— 本层不解析、不重排；
     * - 存 JSON `null`（本类 text = `"null"`）是一个**值**，与「键缺失」
     *   （`get` 回 null）是两回事，实现不得把两者折叠；
     * - 持久实现存 TEXT。
     */
    data class Json(val text: String) : StoredEntry

    /**
     * 原始字节（二进制原样存取，不走 JSON；持久实现存 BLOB）。
     * 相等性按**内容**（`data class` 默认引用比对会把同内容的两个数组判不等）。
     */
    class Bytes(val bytes: ByteArray) : StoredEntry {
        override fun equals(other: Any?): Boolean =
            other is Bytes && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = bytes.contentHashCode()

        override fun toString(): String = "StoredEntry.Bytes(size=${bytes.size})"
    }
}

/**
 * datastore SPI（§9.6）。Android 生产实现住 `:platform:system`（SQLite 单表
 * `kv`）；纯内存参考实现 [InMemoryDataStore] 是同一契约的可执行规格。
 * 键序、单键/单值大小上限等存储细节属实现自由，契约只冻结「问什么」。
 */
interface DataStore {

    /** 读一键；缺失回 null —— 缺键是常态不是错误，不抛（[StoredEntry.Json] `"null"` 是值，见上）。 */
    suspend fun get(key: String): StoredEntry?

    /** 写/覆盖一键。实现须拒绝空白键（抛 IllegalArgumentException，handler 折叠 ERR_INVALID_PARAM）。 */
    suspend fun put(key: String, value: StoredEntry)

    /**
     * 删一键。
     * @return 被移除的值；键本就不存在回 null（幂等删除，不抛）。
     */
    suspend fun remove(key: String): StoredEntry?

    suspend fun contains(key: String): Boolean

    /** 全部键。**顺序不作契约保证**（调用方如需稳定序自行排序）。 */
    suspend fun keys(): List<String>

    suspend fun clear()

    /**
     * 事务：[block] 内暂存的写要么全部生效、要么全部不生效。
     * - [block] 正常返回 → 提交；抛异常 → 整批丢弃，已存状态不变（异常照常抛出）；
     * - 事务内只**暂存写**（[DataStoreTxn]），读走事务外常规读 —— KV 的读-改-写
     *   在单脚本事件循环内天然串行，契约不为此再开读锁面；
     * - 暂存阶段对外不可见；提交对并发读者是一个原子点（SQLite 事务 / 内存锁由实现兑现）。
     */
    suspend fun transaction(block: DataStoreTxn.() -> Unit)
}

/** 事务暂存面：只收写操作，提交前对外不可见。空 block = 空提交（合法 no-op）。 */
interface DataStoreTxn {
    fun put(key: String, value: StoredEntry)
    fun remove(key: String)
}
