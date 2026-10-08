package com.autoscript.domain.scripts

/**
 * §8.5 契约套件打在 [InMemoryIntentStore] 上。
 *
 * **本类存在的意义是「无门禁」**：另一个子类 `SqliteIntentStoreContractTest` 要宿主
 * `sqlite3`（`assumeTrue` 门禁，见其 KDoc），门禁一挂那整套语义规格就不跑了。
 * 本实现零环境依赖，所以这套用例在任何机器上都必须真跑 —— 它不是"SQLite 那份的备份"，
 * 而是「规格今天确实被执行过」的那份证据。
 *
 * `open()` 每次给新实例、共享同一份 [InMemoryIntentStore.State] —— 这就是契约里
 * 「崩溃后重开」的模拟（见 [IntentStoreContract] 的类 KDoc）。
 */
class InMemoryIntentStoreContractTest : IntentStoreContract() {

    private val state = InMemoryIntentStore.State()

    override fun open(): IntentStore = InMemoryIntentStore(state)
}
