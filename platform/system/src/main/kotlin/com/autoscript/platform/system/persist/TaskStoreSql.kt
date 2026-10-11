package com.autoscript.platform.system.persist

import com.autoscript.domain.json.DomainJson
import com.autoscript.domain.scripts.ScheduledTask
import com.autoscript.domain.scripts.TimedSchedule

/**
 * 任务注册表的 SQL 面（§8.6；2026-10-11 批 102 P4 阶段 C）。
 * 与 [RunArchiveSql] 同一架子，纯函数、零 Android。
 *
 * **表形状（`tasks`）的三个决定**：
 * - **`task_id TEXT PRIMARY KEY`** —— id 是**用户给的**（UI 的登记表单 / 桥侧 `workManager.create`），
 *   不是自增序列。所以 upsert 直接 `INSERT OR REPLACE`（同 id 覆盖 = `FileTaskStore` 的
 *   「schedule 直写」），也不存在「迁移要带原 id」那条硬要求（本来就原样）。
 * - **revoke 落 tombstone 行**（`revoked_at` 列，null = 存活）—— 与 jsonl 侧
 *   「不删行、写 `{"op":"del"}`」逐条等价：`remove` 对**从未登记**的 id 也要留一行
 *   （幂等：重放无影响，且「删除一个不存在的任务」这件事本身可被审计）。
 *   读口 `WHERE revoked_at IS NULL` 即「收敛后的全量」。
 * - **`args` 落 JSON 数组列**（`args_json`）—— 与意图日志侧同一形状，解回来必须是字符串数组。
 *
 * **排期计划落三列（`kind`/`a`/`b`）而不是序列化字符串**：与 jsonl 侧逐字同款
 * （once→a=delaySeconds；daily→a=hour,b=minute；cron→a=expr）。**为什么不是一张
 * JSON 列**：这三列是「能按它排序/查」的那部分（预拉闹钟要扫 once 里的 delay），
 * 而 JSON 列会让它们只能在应用层过滤 —— SQL 侧能做而没做，等于把索引能力白扔。
 */
internal object TaskStoreSql {

    const val TABLE = "tasks"

    val OPEN_PRAGMAS: List<String> = listOf("PRAGMA synchronous=FULL")

    val SCHEMA: List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS $TABLE (
            task_id TEXT PRIMARY KEY NOT NULL,
            name TEXT NOT NULL,
            project_id TEXT NOT NULL,
            script_path TEXT NOT NULL,
            kind TEXT NOT NULL,
            a TEXT NOT NULL,
            b TEXT NOT NULL,
            screen TEXT NOT NULL,
            args_json TEXT NOT NULL,
            timeout_millis INTEGER,
            tz TEXT NOT NULL,
            enabled INTEGER NOT NULL,
            revoked_at INTEGER
        )
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS ix_tasks_live ON $TABLE(revoked_at)
        """.trimIndent(),
    )

    private val COLUMNS = listOf(
        "task_id", "name", "project_id", "script_path", "kind", "a", "b",
        "screen", "args_json", "timeout_millis", "tz", "enabled",
    ).joinToString(", ")

    /**
     * 登记/覆盖（upsert）：同 id 再写就是覆盖 —— 与 jsonl 侧「schedule 直写」同语义。
     *
     * `revoked_at = NULL` 一并写入：**重新登记一个被撤销过的 id = 复活**（这是调用方的
     * 意图，不是意外）；不写这一列的话 `INSERT OR REPLACE` 会保留旧行的 tombstone 值，
     * 于是「撤销后再登记」的任务永远不续排。
     */
    fun upsert(task: ScheduledTask): SqlOp = SqlOp(
        dml = listOf(
            "INSERT OR REPLACE INTO $TABLE(task_id, $DATA_COLUMNS, revoked_at) " +
                "VALUES (${q(task.id)}, ${dataValues(task)}, NULL)",
        ),
        query = "SELECT changes() AS changed",
    )

    /**
     * 撤销（tombstone）：`revoked_at` 置位，**不删行**（逐条对齐 jsonl 侧的 `del` 墓碑）。
     *
     * 条件 `revoked_at IS NULL` 让重复撤销变成 0 行更新（幂等）；而「从未登记的 id」
     * 那条路必须**插一行**（否则它的墓碑无处安放，「删除不存在的任务」就不可审计）——
     * 用 `INSERT OR IGNORE` + 条件 UPDATE 两句表达：行不存在就插，存在就置位。
     */
    fun revoke(taskId: String, atMillis: Long): SqlOp = SqlOp(
        dml = listOf(
            "INSERT OR IGNORE INTO $TABLE(task_id, $DATA_COLUMNS, revoked_at) " +
                "VALUES (${q(taskId)}, ${EMPTY_ROW}, $atMillis)",
            "UPDATE $TABLE SET revoked_at = $atMillis WHERE task_id = ${q(taskId)} AND revoked_at IS NULL",
        ),
        query = "SELECT changes() AS changed",
    )

    /** 收敛后的全量（按 task_id 升序 —— 与 jsonl 侧 `values.sortedBy { it.id }` 一致）。 */
    fun selectLive(): SqlOp = SqlOp(
        dml = emptyList(),
        query = "SELECT $COLUMNS FROM $TABLE WHERE revoked_at IS NULL ORDER BY task_id",
    )

    fun countRows(): SqlOp = SqlOp(emptyList(), "SELECT COUNT(*) AS n FROM $TABLE")

    /**
     * 迁移导入一行（§8.6 存储引擎替换）：**原样导**（id 是用户给的，没有自增序列那条硬要求）。
     * `revoked_at` 一并带过来 —— 否则「历史上有过一条撤销」会变成「迁移后它复活了」。
     */
    fun importRow(task: ScheduledTask, revokedAtMillis: Long?): SqlOp = SqlOp(
        dml = listOf(
            "INSERT OR IGNORE INTO $TABLE(task_id, $DATA_COLUMNS, revoked_at) " +
                "VALUES (${q(task.id)}, ${dataValues(task)}, ${revokedAtMillis?.toString() ?: "NULL"})",
        ),
        query = "SELECT changes() AS changed",
    )

    /** 迁移要连墓碑一起导（否则「已撤销」的历史会复活），故这一条要读出 `revoked_at`。 */
    fun selectAllWithRevoked(): SqlOp = SqlOp(
        dml = emptyList(),
        query = "SELECT $COLUMNS, revoked_at FROM $TABLE ORDER BY task_id",
    )

    private const val DATA_COLUMNS =
        "name, project_id, script_path, kind, a, b, screen, args_json, timeout_millis, tz, enabled"

    /** 撤销一条**从未登记**的 id 时用的占位载荷（非空列全给合法值，墓碑语义不依赖它们）。 */
    private val EMPTY_ROW = listOf(
        q(""), q(""), q(""), q(""), q(""), q(""), q(""), q("[]"), "NULL", q("UTC"), "0",
    ).joinToString(", ")

    private fun dataValues(t: ScheduledTask): String {
        val (kind, a, b) = when (val s = t.schedule) {
            is TimedSchedule.Once -> Triple("once", s.delaySeconds.toString(), "")
            is TimedSchedule.Daily -> Triple("daily", s.hourOfDay.toString(), s.minuteOfHour.toString())
            is TimedSchedule.Cron -> Triple("cron", s.expr, "")
        }
        return listOf(
            q(t.name),
            q(t.projectId),
            q(t.scriptPath),
            q(kind),
            q(a),
            q(b),
            q(t.screen.name),
            q(DomainJson.encode(t.args)),
            t.scriptTimeoutMillis?.toString() ?: "NULL",
            q(t.timezone.id),
            if (t.enabled) "1" else "0",
        ).joinToString(", ")
    }

    /** 单引号双写转义（与 `RunArchiveSql`/`IntentStoreSql` 同一份转义规则）。 */
    private fun q(s: String): String = "'" + s.replace("'", "''") + "'"
}
