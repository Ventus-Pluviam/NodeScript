package com.autoscript.platform.system

import com.autoscript.domain.storage.DataStore
import com.autoscript.domain.storage.DataStoreTxn
import com.autoscript.domain.storage.StoredEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * `datastore` 的 Android 实现（docs §9.6；SPI 见 `:domain` 的 [DataStore]，
 * 语义层 handler 住 `:platform:capabilities` 的 `DatastoreNamespaceHandler`，
 * 纯内存参考实现是 `:domain` 的 `InMemoryDataStore` —— 三者共用同一契约口径）。
 *
 * 分层照 platform/system/README 的表：Android 接触面只有一小块 —— [KvOps] 缝
 * （真机 [SqliteKvOps] 包 `SQLiteOpenHelper`/SQL），本类只留**本机 JVM 可测**的语义：
 *
 * 1. **空白键拒写**：直写与事务暂存同一口径（require 在触 ops 之前，垃圾进不了表）；
 * 2. **事务 = 暂存 + 单次原子批**：[DataStoreTxn] 只收写操作，[KvOps.applyAll]
 *    才是提交点 —— block 抛异常 → 栈展开时 ops **一次都不碰**（零调用即回滚，
 *    与 `InMemoryDataStore` 的「锁内 apply」同一契约形状，只是原子点换成 SQLite 事务）；
 * 3. **IO 出界**：全部存储访问包在 [Dispatchers.IO]（SQLite 是阻塞调用，
 *    直接跑在桥协程上会把事件循环卡住）。
 *
 * 本类不做能力门禁（§9.5：应用私有 KV 本就无需授权）、不解释值内容
 * （[StoredEntry.Json] 是透传文本，handler/实现都不碰业务结构）。
 */
class AndroidDataStore(
    private val ops: KvOps,
) : DataStore {

    override suspend fun get(key: String): StoredEntry? = withContext(Dispatchers.IO) { ops.get(key) }

    override suspend fun put(key: String, value: StoredEntry) {
        requireNonBlank(key)
        withContext(Dispatchers.IO) { ops.put(key, value) }
    }

    override suspend fun remove(key: String): StoredEntry? = withContext(Dispatchers.IO) {
        // 先读后删：SPI 约定「回被移除的值」，delete 的受影响行数给不了值本身。
        val previous = ops.get(key)
        ops.remove(key)
        previous
    }

    override suspend fun contains(key: String): Boolean = withContext(Dispatchers.IO) { ops.contains(key) }

    override suspend fun keys(): List<String> = withContext(Dispatchers.IO) { ops.keys() }

    override suspend fun clear() {
        withContext(Dispatchers.IO) { ops.clear() }
    }

    override suspend fun transaction(block: DataStoreTxn.() -> Unit) {
        val staged = mutableListOf<KvMutation>()

        // 暂存阶段（不碰 ops）：block 抛异常 → staged 随栈丢弃，applyAll 永远不会被调。
        val txn = object : DataStoreTxn {
            override fun put(key: String, value: StoredEntry) {
                requireNonBlank(key)
                staged += KvMutation.Put(key, value)
            }

            override fun remove(key: String) {
                staged += KvMutation.Remove(key)
            }
        }
        txn.block()

        // 提交阶段：恰好一次原子批（空事务也走同一协议，均一不特判）。
        withContext(Dispatchers.IO) { ops.applyAll(staged.toList()) }
    }

    private fun requireNonBlank(key: String) {
        require(key.isNotBlank()) { "datastore key 不得为空白，实际 \"$key\"" }
    }

    /**
     * 存储接触面（README「ops 缝」表的本行）：真机 [SqliteKvOps]；
     * 单测注入内存替身 —— Android stub 运行期全抛异常，本机 JVM 只能站在缝这边测。
     * 单笔直写（put/remove/…）与原子批（[applyAll]）共用同一张表语义。
     */
    interface KvOps {
        fun put(key: String, value: StoredEntry)

        /** @return null = 键不存在 */
        fun get(key: String): StoredEntry?

        /** @return 是否真的删掉了东西 */
        fun remove(key: String): Boolean

        fun contains(key: String): Boolean

        fun keys(): List<String>

        fun clear()

        /**
         * 原子批写：全部生效或全部不生效（实现 = SQLite 事务三拍）。
         * 这是事务的**唯一提交点** —— 调用方（本类）保证只在暂存成功后调一次。
         */
        fun applyAll(ops: List<KvMutation>)
    }

    /** 暂存后交给 [KvOps.applyAll] 的单笔写（本类与 ops 缝之间的形状）。 */
    sealed interface KvMutation {
        data class Put(val key: String, val value: StoredEntry) : KvMutation
        data class Remove(val key: String) : KvMutation
    }
}
