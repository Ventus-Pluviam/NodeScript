package com.autoscript.appservice.scheduler.core

/**
 * 注册表内存实现（JVM 单测；生产用 persist.[FileTaskStore][com.autoscript.appservice.scheduler.persist.FileTaskStore]）。
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
