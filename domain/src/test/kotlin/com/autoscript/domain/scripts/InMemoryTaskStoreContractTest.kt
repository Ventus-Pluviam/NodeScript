package com.autoscript.domain.scripts

/**
 * §8.6 契约套件打在 [InMemoryTaskStore] 上。
 *
 * **本类存在的意义是「无门禁」**：另一个子类 `SqliteTaskStoreContractTest` 要宿主
 * `sqlite3`（`assumeTrue` 门禁，见其 KDoc），门禁一挂那整套语义规格就不跑了。
 * 本实现零环境依赖，所以这套用例在任何机器上都必须真跑 —— 它不是「SQLite 那份的备份」，
 * 而是「规格今天确实被执行过」的那份证据。
 *
 * **不**继承 [TaskStorePersistenceContract]：零 IO 形态重开即清零是它的**语义本身**
 * （见该套件 KDoc「为什么按能力切」）。持久性那一套由两个持久实现各跑一遍。
 */
class InMemoryTaskStoreContractTest : TaskStoreContract() {

    /** 每次一个新实例、零 IO —— 故持久性判据不适用（见类 KDoc）。 */
    override fun open(): TaskStore = InMemoryTaskStore()
}
