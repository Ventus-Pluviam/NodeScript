package com.autoscript.domain.scripts

/**
 * 注册表内存实现（零 IO，任何机器上都必须真跑 —— 与 [InMemoryIntentStore] 同一地位）。
 *
 * 生产走 SQLite（批 102 阶段 C），`FileTaskStore` 保留为契约套件的第二个被测实现
 * （删了就没人证明「换存储引擎那天行为没变」）。
 *
 * 与 [InMemoryIntentLog] 同一地位：语义锚点（upsert/tombstone 收敛），不是"假实现" ——
 * remove 对从未登记的 id 同样记 tombstone（与文件版一致，重放无影响）。
 */
class InMemoryTaskStore : TaskStore {
    private val lock = Any()
    private val tasks = HashMap<String, ScheduledTask>()

    override fun put(task: ScheduledTask): Unit = synchronized(lock) { tasks[task.id] = task }

    override fun remove(taskId: String): Unit = synchronized(lock) { tasks.remove(taskId) }

    override fun loadAll(): List<ScheduledTask> = synchronized(lock) {
        tasks.values.sortedBy { it.id }
    }
}
