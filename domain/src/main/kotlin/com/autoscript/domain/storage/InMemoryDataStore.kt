package com.autoscript.domain.storage

/**
 * 纯内存参考实现（§9.6「同步 importer 仅供纯内存」里「纯内存」一侧的 Kotlin 投影；
 * 与持久实现共用 [DataStore] 契约的可执行规格）。
 *
 * - 行为差异只允许明文写在各自 KDoc（如：本实现不持久、键序 = 插入序），
 *   不允许语义漂移 —— [com.autoscript.domain.storage.DataStoreContractTest]
 *   的口径对 SQLite 实现同样适用；
 * - 线程安全：单把锁串行全部操作，提交点（[transaction] 的 apply 循环）在锁内，
 *   对并发读者是一个原子点；
 * - **不提供同步写入口**：即便纯内存也走 suspend —— 同步 importer 是 JS 进程内
 *   纯 JS 路径的专有用法（铁律 2），契约不给 Kotlin 侧复制它留门。
 */
class InMemoryDataStore : DataStore {

    private val lock = Any()

    /** 插入序（LinkedHashMap）—— 实现细节非契约；[DataStore.keys] 不承诺顺序。 */
    private val map = LinkedHashMap<String, StoredEntry>()

    override suspend fun get(key: String): StoredEntry? = synchronized(lock) { map[key] }

    override suspend fun put(key: String, value: StoredEntry) {
        require(key.isNotBlank()) { "datastore key 不得为空白，实际 \"$key\"" }
        synchronized(lock) { map[key] = value }
    }

    override suspend fun remove(key: String): StoredEntry? = synchronized(lock) { map.remove(key) }

    override suspend fun contains(key: String): Boolean = synchronized(lock) { map.containsKey(key) }

    override suspend fun keys(): List<String> = synchronized(lock) { map.keys.toList() }

    override suspend fun clear() {
        synchronized(lock) { map.clear() }
    }

    override suspend fun transaction(block: DataStoreTxn.() -> Unit) {
        val ops = mutableListOf<Op>()

        // 暂存阶段：block 抛异常 → ops 随栈丢弃，根本走不到 apply（整批不落）。
        // 校验在此抛同样成立：非法键没进暂存集，已存状态不受影响。
        val txn = object : DataStoreTxn {
            override fun put(key: String, value: StoredEntry) {
                require(key.isNotBlank()) { "datastore key 不得为空白，实际 \"$key\"" }
                ops += Op.Put(key, value)
            }

            override fun remove(key: String) {
                ops += Op.Remove(key)
            }
        }
        txn.block()

        // 提交阶段：一把锁内 apply 全部 —— 并发读者要么看见全部旧、要么全部新。
        synchronized(lock) {
            for (op in ops) {
                when (op) {
                    is Op.Put -> map[op.key] = op.value
                    is Op.Remove -> map.remove(op.key)
                }
            }
        }
    }

    private sealed interface Op {
        data class Put(val key: String, val value: StoredEntry) : Op
        data class Remove(val key: String) : Op
    }
}
