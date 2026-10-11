package com.autoscript.platform.system.persist

import com.autoscript.domain.json.DomainJson
import com.autoscript.domain.scripts.ScheduledTask
import com.autoscript.domain.scripts.ScreenGuarantee
import com.autoscript.domain.scripts.TaskStore
import com.autoscript.domain.scripts.TimedSchedule
import java.time.ZoneId

/**
 * 任务注册表的 SQLite 实现（§8.6；2026-10-11 批 102 P4 阶段 C）。
 *
 * 语义与 `FileTaskStore`（jsonl）**逐条等价** —— 两者共用同一套契约测试
 * （`TaskStoreContract`，见 `:domain` 的 testFixtures）。
 *
 * **为什么住 `:platform:system`**：`android.database.sqlite` 是 Android 面（依赖铁律
 * `:platform:* → :domain`），SPI（[TaskStore]）与它的载荷形状（`ScheduledTask` /
 * `TimedSchedule` / `ScreenGuarantee`）在批 102 阶段 A 一并搬进了 `:domain`。
 *
 * **SQL 不在本类里拼**：全部语句由 [TaskStoreSql] 生成（纯函数、零 Android），本机拿
 * **同一个 `sqlite3` 引擎**跑真语句（见 `SqliteTaskStoreContractTest`）。本类只剩
 * 「执行 + 读游标 + 折成 [ScheduledTask]」。
 *
 * **三条语义落点**（与 jsonl 侧逐条对应）：
 * - **upsert** → `INSERT OR REPLACE` 且**一并把 `revoked_at` 置 NULL**（重新登记一个被撤销
 *   过的 id = 复活 —— 那是调用方的意图；不置 NULL 的话 `REPLACE` 保留旧墓碑，
 *   「撤销后再登记」的任务永远不续排）；
 * - **remove 幂等** → `INSERT OR IGNORE` + 条件 UPDATE 两句：行不存在就插一条墓碑
 *   （「删除一个不存在的任务」本身可被审计，与 jsonl 侧的 `del` 墓碑同义），
 *   存在就置位；重复撤销是 0 行更新；
 * - **loadAll 收敛** → `WHERE revoked_at IS NULL`（jsonl 侧是「重放时剔除墓碑」，
 *   同一结果的两种表达）。
 *
 * **单写者**：注册表的写者只有 `:main` 的单例调度器 —— 本类不加锁（`run` 里
 * 「两句 dml + query」由 [SqliteIntentStore.Runner] 的单事务保证原子）。
 */
class SqliteTaskStore(private val runner: SqliteIntentStore.Runner) : TaskStore {

    init {
        // 先钉持久性口径再建表（建表幂等，既有库重开照样能跑）。
        runner.ddl(TaskStoreSql.OPEN_PRAGMAS + TaskStoreSql.SCHEMA)
    }

    override fun put(task: ScheduledTask): Unit {
        runner.run(listOf(TaskStoreSql.upsert(task)))
    }

    override fun remove(taskId: String): Unit {
        runner.run(listOf(TaskStoreSql.revoke(taskId, System.currentTimeMillis())))
    }

    override fun loadAll(): List<ScheduledTask> =
        runner.query(TaskStoreSql.selectLive().query).map { it.toTask() }

    /**
     * 一次性迁移导入（§8.6 存储引擎替换）：把 jsonl 侧的行**连墓碑一起**导进去。
     *
     * 幂等 + 可重入：库里已有行即跳过（[TaskStoreSql.countRows]）；单行 `INSERT OR IGNORE`
     * 兜「上一轮已成功、这一轮又跑了一次」。
     *
     * **墓碑必须一起导**：否则「历史上撤销过某个 id」在迁移后会变成「那个任务复活了」——
     * 用户看到的是「我没登记过的任务自己开始跑」。
     */
    internal fun importTasks(tasks: List<Pair<ScheduledTask, Long?>>): Int {
        if (tasks.isEmpty()) return 0
        if (count(TaskStoreSql.countRows()) > 0) return 0
        return runner.run(tasks.map { (task, revokedAt) -> TaskStoreSql.importRow(task, revokedAt) })
            .sumOf { results -> results.singleOrNull()?.long("changed") ?: 0L }
            .toInt()
    }

    /** 迁移源：连 `revoked_at` 的全量读（墓碑也要读出来才能一起导）。 */
    internal fun allWithRevoked(): List<Pair<ScheduledTask, Long?>> =
        runner.query(TaskStoreSql.selectAllWithRevoked().query)
            .map { it.toTask() to it.optLong("revoked_at") }

    private fun count(op: SqlOp): Long =
        runner.query(op.query).singleOrNull()?.long("n") ?: error("计数查询没回行（库异常）")

    private fun Map<String, Any?>.toTask(): ScheduledTask = ScheduledTask(
        id = str("task_id"),
        name = str("name"),
        projectId = str("project_id"),
        scriptPath = str("script_path"),
        schedule = decodeSchedule(str("kind"), str("a"), str("b")),
        screen = ScreenGuarantee.valueOf(str("screen")),
        args = decodeArgs(str("args_json")),
        scriptTimeoutMillis = optLong("timeout_millis"),
        timezone = ZoneId.of(str("tz")),
        enabled = (this["enabled"] as? Long ?: 0L) != 0L,
    )

    /** 排期三列 → [TimedSchedule]（与 `FileTaskStore.Codec.apply` 逐字同款的反向）。 */
    private fun decodeSchedule(kind: String, a: String, b: String): TimedSchedule = when (kind) {
        "once" -> TimedSchedule.Once(a.toLong())
        "daily" -> TimedSchedule.Daily(a.toInt(), b.toInt())
        // 非法 kind **响亮失败**（不是回 Once(0) 之类）：库里出现三种之外的 kind 说明库被
        // 外部改过或版本不匹配，而「猜一个排期」会把任务投到错误时刻。
        "cron" -> TimedSchedule.Cron(a)
        else -> error("tasks 行的 kind 非法：$kind（库里出现了三种之外的排期形态）")
    }

    /** `args_json` 解回字符串数组（形状与 jsonl 侧、意图日志侧同一份）。 */
    private fun decodeArgs(json: String): List<String> = when (val v = DomainJson.decode(json)) {
        is DomainJson.Value.Arr -> v.items.map {
            (it as? DomainJson.Value.S)?.v
                ?: error("args_json 数组含非字符串（库被外部改过？）")
        }
        else -> error("args_json 不是数组（库被外部改过？）")
    }

    private fun Map<String, Any?>.long(key: String): Long =
        this[key] as? Long ?: error("列 $key 缺失或非整数（库异常）")

    private fun Map<String, Any?>.optLong(key: String): Long? = this[key] as? Long

    private fun Map<String, Any?>.str(key: String): String =
        this[key] as? String ?: error("列 $key 缺失或非文本（库异常）")
}
