package com.autoscript.platform.system.persist

import com.autoscript.domain.scripts.RunState

/**
 * 运行档案的 SQL 面（§8.5 归档入口；2026-10-11 批 102 P4 阶段 B）：
 * 建表 DDL + 逐操作的语句文本。
 *
 * **为什么照 [IntentStoreSql] 的架子抄一遍而不是复用它**：两者是**两份账**（键不同 ——
 * 意图日志按 `intentRunId`，档案按 `engineRunId`），合并成一个文件会让「只写一侧」的
 * 孤儿记录在格式上不可见 —— 分开存，孤儿才可被审计（这条已写在
 * `FileRunArchive` 的类 KDoc 里，批 96 之前的原文）。抄架子不抄语义：约束纪律各按各的。
 *
 * **表形状**：
 * - `run_archive.engine_run_id INTEGER PRIMARY KEY AUTOINCREMENT` —— 与意图日志同一套
 *   理由（runId 由存储层分配且**崩溃后不复用**）。迁移导入带原 id 的行（[importRow]）
 *   也靠这条：显式写 7 之后下一个自动 id 是 8，`sqlite_sequence` 随之上抬。
 * - `run_archive.run_nonce` **不建唯一索引**：档案不是幂等锚点（锚点在意图日志侧，
 *   `ux_nonce` 那一道），同一 nonce 在档案里可以有多行（重投 → 两次执行 → 两条记录）。
 *   建了就会把「重投记录」判成 bug —— 而那正是档案要保留的事实。
 * - `engine_run_link(engine_run_id PRIMARY KEY, intent_run_id)` —— 双 id 关联**成对**
 *   （§8.5）；「关联一旦成立不改写」由 `INSERT OR REPLACE` 之外的**先查后写**表达不了
 *   原子性，所以这里用「`INSERT` + 主键冲突即约束错误」的形状：调用方据此拒「改写关联」。
 *
 * **终态不可改写/复活怎么表达**：靠 [advanceState] 的 `WHERE state NOT IN (终态)` 条件
 * 更新 —— 更新 0 行即「这条已终态」，由调用方折成 `IllegalStateException`。
 * **由 SQLite 原子拒绝，不是应用层先查后写**（与 `ux_nonce` 同一条纪律：锚点归引擎）。
 */
internal object RunArchiveSql {

    const val TABLE = "run_archive"
    const val LINK_TABLE = "engine_run_link"

    /**
     * 终态集合（SQL 字面量，`IN (...)` 用）。
     *
     * 与 `com.autoscript.domain.scripts.isTerminal` **同源但独立**：那份是 Kotlin 侧的
     * 判定（读口用），这里是 SQL 侧的判定（写口用）。两侧各有一份不是疏忽而是必然 ——
     * SQL 字符串不能引用 Kotlin 函数。**改一处必须看另一处**（本文件 KDoc 点名的纪律）。
     */
    private val TERMINAL_STATES = listOf("SUCCEEDED", "FAILED", "CRASHED", "CANCELLED")

    /** 终态集合的 SQL 字面量（`IN (...)` 用）—— **每个值都要单引号**，否则被当成列名。 */
    private val TERMINAL_IN = TERMINAL_STATES.joinToString(", ") { "'$it'" }

    /** 建表 + 关联表（逐条一句，真机逐条 execSQL；幂等 —— 既有库重开照样能跑）。 */
    val SCHEMA: List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS $TABLE (
            engine_run_id INTEGER PRIMARY KEY AUTOINCREMENT,
            project_id TEXT NOT NULL,
            script_path TEXT NOT NULL,
            run_nonce TEXT NOT NULL,
            state TEXT NOT NULL,
            started_at INTEGER,
            finished_at INTEGER,
            exit_code INTEGER,
            crash_summary TEXT
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS $LINK_TABLE (
            engine_run_id INTEGER PRIMARY KEY,
            intent_run_id INTEGER NOT NULL
        )
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS ix_run_archive_project ON $TABLE(project_id)
        """.trimIndent(),
    )

    val OPEN_PRAGMAS: List<String> = listOf("PRAGMA synchronous=FULL")

    private val COLUMNS = listOf(
        "engine_run_id", "project_id", "script_path", "run_nonce", "state",
        "started_at", "finished_at", "exit_code", "crash_summary",
    ).joinToString(", ")

    /**
     * 首次建档：**按给定 engineRunId 写**（不是自增发号）。
     *
     * 为什么不自增：档案的 id 由调用方给（它来自 [com.autoscript.domain.scripts.EngineRunReceipt]），
     * 自增会在「调用方给的 id」与「库里那一行」之间错开 —— 症状是所有读回断言都拿到别的号。
     * `AUTOINCREMENT` 仍然有用：它让**迁移导入**带原 id 之后的下一个自动号接在历史之后
     * （那一条靠 `sqlite_sequence` 只增不减）。
     *
     * `INSERT OR IGNORE` + 回读：让「同一 id 两次建档」幂等（并发建档或重放），而
     * `query` 仍回那一行的**现态**（调用方据此拿到真值而不是自己给的那个形状）。
     */
    fun insert(record: RunArchiveRecord): SqlOp = SqlOp(
        dml = listOf(
            "INSERT OR IGNORE INTO $TABLE(engine_run_id, $DATA_COLUMNS) " +
                "VALUES (${record.engineRunId}, ${dataValues(record)})",
        ),
        query = "SELECT engine_run_id, changes() AS changed FROM $TABLE WHERE engine_run_id = ${record.engineRunId}",
    )

    /**
     * 结算（前进到终态）：`WHERE engine_run_id = N AND state NOT IN (终态)`。
     *
     * **更新 0 行 = 该 run 已终态** → 调用方折 `IllegalStateException`（终态不可改写）。
     * 「复活」（已终态 → 非终态）也被这一条挡住 —— 它同样是「从终态出发」的更新。
     *
     * 中间态之间的前进（PENDING → RUNNING）走同一条语句，`changed = 1` 表示前进成功。
     */
    fun advanceState(record: RunArchiveRecord): SqlOp = SqlOp(
        dml = listOf(
            "UPDATE $TABLE SET state = ${q(record.state.name)}, " +
                "started_at = ${record.startedAtMillis?.toString() ?: "NULL"}, " +
                "finished_at = ${record.finishedAtMillis?.toString() ?: "NULL"}, " +
                "exit_code = ${record.exitCode?.toString() ?: "NULL"}, " +
                "crash_summary = ${record.crashSummary?.let(::q) ?: "NULL"} " +
                "WHERE engine_run_id = ${record.engineRunId} " +
                "AND state NOT IN ($TERMINAL_IN)",
        ),
        query = "SELECT $COLUMNS, changes() AS changed FROM $TABLE WHERE engine_run_id = ${record.engineRunId}",
    )

    /**
     * 双 id 关联（§8.5 成对写入）：`INSERT` + 主键冲突即约束错误
     * → 「关联一旦成立不改写」由**存储层**拒绝（`INSERT OR IGNORE` 那条路不行 ——
     * 那样会静默吞掉「带了不同 link」的调用方 bug）。
     *
     * `intent_run_id` 建索引：`recordsOfIntent` 是任务中心按意向追溯的主查询。
     */
    fun link(engineRunId: Long, intentRunId: Long): SqlOp = SqlOp(
        dml = listOf("INSERT INTO $LINK_TABLE(engine_run_id, intent_run_id) VALUES ($engineRunId, $intentRunId)"),
        query = "SELECT changes() AS changed",
    )

    /**
     * 读口（七个变体合成一个入口）。
     *
     * **为什么合并**（不是省事，是分类学）：它们本来就是「同一条 SELECT 的七个 WHERE 变体」，
     * 拆成七个函数会让 [RunArchive] 的六个读方法与七个 SQL 函数之间形成**一对一**的映射 ——
     * 而那一映射里有三对是「读方法换了个名」（`record`/`link`/`records`）。合成一个带
     * [Query] 参数的入口后，「哪些读口存在」在 [Query] 的枚举名里一目了然，
     * 而 SQL 文本只有一处要维护。
     */
    fun select(query: Query): SqlOp = SqlOp(
        dml = emptyList(),
        query = when (query) {
            is Query.ById -> "SELECT $COLUMNS FROM $TABLE WHERE engine_run_id = ${query.engineRunId}"
            is Query.LinkById -> "SELECT intent_run_id FROM $LINK_TABLE WHERE engine_run_id = ${query.engineRunId}"
            is Query.ByIntent ->
                "SELECT r.$COLUMNS FROM $TABLE r JOIN $LINK_TABLE l ON l.engine_run_id = r.engine_run_id " +
                    "WHERE l.intent_run_id = ${query.intentRunId} ORDER BY r.engine_run_id"
            is Query.ByProject ->
                "SELECT $COLUMNS FROM $TABLE WHERE project_id = ${q(query.projectId)} ORDER BY engine_run_id"
            Query.All -> "SELECT $COLUMNS FROM $TABLE ORDER BY engine_run_id"
            Query.Unfinished ->
                "SELECT $COLUMNS FROM $TABLE WHERE state NOT IN ($TERMINAL_IN) ORDER BY engine_run_id"
        },
    )

    /** 读口变体（[select] 的参数）。sealed 而非 enum：三个变体带 id/名字，不是无参形态。 */
    sealed interface Query {
        data class ById(val engineRunId: Long) : Query
        data class LinkById(val engineRunId: Long) : Query
        data class ByIntent(val intentRunId: Long) : Query
        data class ByProject(val projectId: String) : Query
        data object All : Query
        data object Unfinished : Query
    }

    fun countRows(): SqlOp = SqlOp(emptyList(), "SELECT COUNT(*) AS n FROM $TABLE")

    /**
     * 迁移导入一行：**带原 engineRunId**（与 `intent-log.jsonl` 迁移同一条硬要求 ——
     * 不带就同号异义，`recordsOfIntent` 静默串档）。
     *
     * 关联随行一起导（[importLink]）—— 否则迁移后任务中心按意向追溯**查不到历史**，
     * 而那正是「带原 id」要保的东西的一半。
     */
    fun importRow(record: RunArchiveRecord, intentRunId: Long? = null): SqlOp = SqlOp(
        dml = buildList {
            add(
                "INSERT OR IGNORE INTO $TABLE(engine_run_id, $DATA_COLUMNS) " +
                    "VALUES (${record.engineRunId}, ${dataValues(record)})",
            )
            // 关联随行一起导 —— 同一条 op 的两条 dml = 同一事务：分开就是「记录导了、关联没导」
            // 的中间态，而那正是迁移后按意向追溯查不到历史的形态。
            if (intentRunId != null) {
                add(
                    "INSERT OR IGNORE INTO $LINK_TABLE(engine_run_id, intent_run_id) " +
                        "VALUES (${record.engineRunId}, $intentRunId)",
                )
            }
        },
        query = "SELECT changes() AS changed",
    )

    private const val DATA_COLUMNS =
        "project_id, script_path, run_nonce, state, started_at, finished_at, exit_code, crash_summary"

    private fun dataValues(r: RunArchiveRecord): String = listOf(
        q(r.projectId),
        q(r.scriptPath),
        q(r.runNonce),
        q(r.state.name),
        r.startedAtMillis?.toString() ?: "NULL",
        r.finishedAtMillis?.toString() ?: "NULL",
        r.exitCode?.toString() ?: "NULL",
        r.crashSummary?.let(::q) ?: "NULL",
    ).joinToString(", ")

    /** 单引号双写转义（SQLite 字符串字面量里反斜杠不是转义符 —— 同 `IntentStoreSql`）。 */
    private fun q(s: String): String = "'" + s.replace("'", "''") + "'"
}

/**
 * SQL 生成面用的**扁平记录**（不带 `link`）——与 `:domain` 的 [com.autoscript.domain.scripts.RunRecord]
 * 一一对应，但**刻意不直接用它**：本模块的实现类需要「带 link 的一次写入」这个形状，
 * 而 `RunRecord` + `EngineRunLink` 是两个类型（分属 [com.autoscript.domain.scripts.RunArchive.put]
 * 的两个参数）。把两者在 SQL 层合成一个扁平类型，是为了让「列 ↔ 值」的生成只有一处。
 */
internal data class RunArchiveRecord(
    val engineRunId: Long,
    val projectId: String,
    val scriptPath: String,
    val runNonce: String,
    val state: RunState,
    val startedAtMillis: Long? = null,
    val finishedAtMillis: Long? = null,
    val exitCode: Int? = null,
    val crashSummary: String? = null,
)
