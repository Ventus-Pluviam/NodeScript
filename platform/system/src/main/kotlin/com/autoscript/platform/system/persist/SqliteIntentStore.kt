package com.autoscript.platform.system.persist

import com.autoscript.domain.json.DomainJson
import com.autoscript.domain.scripts.IntentStore
import com.autoscript.domain.scripts.IntentStore.StartRow
import com.autoscript.domain.scripts.IntentStore.StoredOutcome
import com.autoscript.domain.scripts.IntentStore.StoredRow

/**
 * 意图日志的 SQLite 实现（docs §8.5「append-only（SQLite，启动即回放）」）。
 *
 * 语义与已退役的 jsonl 存储（`JournalFileStore`，2026-10-08 删除）**逐条等价** ——
 * 两者共用同一套契约测试（`IntentStoreContract`，见 `:domain` 的 testFixtures）：
 * 崩溃持久、幂等锚点原子、runId 单调不复用、`seal`/`sealAndReopen` 的边界行为。
 *
 * **为什么住 `:platform:system`**：`android.database.sqlite` 是 Android 面，而依赖铁律是
 * `:platform:*` → `:domain`。SPI（[IntentStore]）因此搬去了 `:domain`，本类在这边实现它；
 * 纯 JVM 侧改用 `:domain` `testFixtures` 的 `InMemoryIntentStore`（单测与无 Android 环境照旧跑）。
 *
 * **SQL 不在本类里拼**：全部语句由 [IntentStoreSql] 生成（纯函数、零 Android），
 * 于是「表形状/部分唯一索引/条件插入」这些真正决定语义的东西能在本机拿同一份语句跑真
 * `sqlite3` 验证（`SqliteIntentStoreSqlTest`）。本类只剩「执行 + 读游标 + 折成 StoredRow」。
 *
 * **三条不变量落在哪**：
 * - 崩溃持久 → 执行体（[Runner] 的实现）负责：真机 [AndroidSqliteRunner] 开库即
 *   `PRAGMA synchronous=FULL`（非 WAL 模式下 Android 的连接池只有一条连接，pragma 因此
 *   对整个库生效），每次写都在事务提交时 fsync 后才返回；
 * - 幂等锚点原子 → 一个**部分唯一索引**（[IntentStoreSql.SCHEMA] 的 `ux_nonce`）：同 nonce
 *   至多一条「算数的」行（存活行与真终态行同池），由 SQLite 原子拒绝（不是先查后写的预检）；
 * - runId 单调 → `INTEGER PRIMARY KEY AUTOINCREMENT`（`sqlite_sequence` 只增不减）。
 *
 * **不做日志清理/保留期**（design-status 明确记的另一个待裁问题）：老终态行与老 nonce
 * 一旦能丢，幂等锚点就随之失效 —— 那是策略变更，不是本次存储引擎替换的一部分。
 */
class SqliteIntentStore(private val runner: Runner) : IntentStore {

    init {
        // 先钉持久性口径、再建表（反过来的话「建表」那一次提交可能落在低持久档上）；
        // 建表幂等（IF NOT EXISTS），既有库上重开照样能跑。
        runner.ddl(IntentStoreSql.OPEN_PRAGMAS + IntentStoreSql.SCHEMA)
    }

    override fun insertStart(row: StartRow): Long {
        val op = IntentStoreSql.insertStart(row)
        val back = try {
            runner.run(listOf(op)).first()
        } catch (e: ConstraintViolationException) {
            // 存活 nonce 唯一 / 已提交 nonce 唯一 —— 存储层原子拒绝（调用方拿不到竞态窗口）。
            throw IllegalStateException("runNonce 已有存活或已 COMMIT 的行，拒绝重复投递: ${row.runNonce}", e)
        }
        val hit = back.singleOrNull()
            ?: error("insert 后读不回 last_insert_rowid()（库异常）")
        return hit.long("run_id")
    }

    override fun seal(runId: Long, outcome: StoredOutcome): StoredRow? {
        val op = IntentStoreSql.seal(runId, outcome, System.currentTimeMillis())
        val back = try {
            runner.run(listOf(op)).first()
        } catch (e: ConstraintViolationException) {
            // ux_nonce：该 nonce 已有别的真终态 —— 重复副作用，响亮失败。
            throw IllegalStateException("runNonce 已 COMMIT，拒绝重复副作用（runId=$runId）", e)
        }
        // 读不回行 = runId 不存在（与已退役的 jsonl 存储的 `rows[runId] ?: return null` 同义）。
        return back.singleOrNull()?.toStoredRow()
    }

    override fun sealAndReopen(oldRunId: Long): Long {
        val at = System.currentTimeMillis()
        val op = IntentStoreSql.sealAndReopen(oldRunId, at)
        val back = try {
            runner.run(listOf(op)).first().singleOrNull()
                ?: error("sealAndReopen 后读不回结果（库异常）")
        } catch (e: ConstraintViolationException) {
            throw IllegalStateException("sealAndReopen 违反唯一约束（同 nonce 存活行重复？runId=$oldRunId）", e)
        }
        // 条件插入没落行 = 旧行不存在或已终态 —— 与已退役的 jsonl 存储同一条边界。
        require(back.long("inserted") == 1L) { "旧 runId 不存在或已 COMMIT，无法重开: $oldRunId" }
        return back.long("new_id")
    }

    override fun liveRows(): List<StoredRow> = rows(IntentStoreSql.selectRows(liveOnly = true))

    override fun allRows(): List<StoredRow> = rows(IntentStoreSql.selectRows(liveOnly = false))

    override fun hasCommittedNonce(nonce: String): Boolean =
        count(IntentStoreSql.hasCommittedNonce(nonce)) > 0

    override fun hasLiveNonce(nonce: String): Boolean =
        count(IntentStoreSql.hasLiveNonce(nonce)) > 0

    override fun close() {
        runner.close()
    }

    /**
     * 一次性迁移导入（§8.5 存储引擎替换）：把 jsonl 侧的行**带着原 runId** 落进 SQLite。
     *
     * 幂等 + 可重入：整批一个事务（要么全落要么全不落，不留"导入一半"的库），
     * 且库里已有行时直接跳过（[IntentStoreSql.countRows]）—— 重跑一次不会重复导入。
     * 单行用 `INSERT OR IGNORE` 兜「已导入过的 runId」。
     *
     * **保留原 runId 是硬要求**：`run-archive.jsonl` 的 `EngineRunLink.intentRunId` 指向的
     * 就是这些 id，重新发号会让新旧记录**同号异义**（`recordsOfIntent` 静默串档）。
     *
     * @return 真正落库的行数（0 = 库里已有行，本次没导）
     */
    fun importRows(rows: List<StoredRow>): Int {
        if (rows.isEmpty()) return 0
        // 库里已有行 = 迁移做过了（本类是这份库的唯一写者，读-判-写之间没有并发写者）。
        if (count(IntentStoreSql.countRows()) > 0) return 0
        return runner.run(rows.map(IntentStoreSql::importRow))
            .sumOf { results -> results.singleOrNull()?.long("changed") ?: 0L }
            .toInt()
    }

    private fun rows(op: SqlOp): List<StoredRow> = runner.query(op.query).map { it.toStoredRow() }

    private fun count(op: SqlOp): Long =
        runner.query(op.query).singleOrNull()?.long("n")
            ?: error("计数查询没回行（库异常）")

    // —— 行折装：列 → StoredRow（列名与 [IntentStoreSql] 的 COLUMNS 一一对应）——

    private fun Map<String, Any?>.toStoredRow(): StoredRow = StoredRow(
        runId = long("run_id"),
        start = StartRow(
            projectId = str("project_id"),
            scriptPath = str("script_path"),
            runNonce = str("run_nonce"),
            trigger = str("trigger_name"),
            screen = str("screen_name"),
            scheduledAtMillis = long("scheduled_at"),
            startedAtMillis = long("started_at"),
            deadlineMillis = optLong("deadline_at"),
            args = decodeArgs(str("args_json")),
            timeoutMillis = optLong("timeout_millis"),
        ),
        outcome = optStr("outcome")?.let { StoredOutcome(it, optStr("outcome_detail")) },
        committedAtMillis = optLong("committed_at"),
    )

    /** `args` 落成 JSON 数组列（jsonl 侧同一形状）—— 解回来必须是字符串数组。 */
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

    private fun Map<String, Any?>.optStr(key: String): String? = this[key] as? String

    /**
     * SQL 执行面（与 `AndroidDataStore.KvOps` 同一条分层纪律：Android 触点收在一个缝后面，
     * 本类只留语义）。
     *
     * 两个实现：真机 [AndroidSqliteRunner]（`SQLiteOpenHelper`）；本机测试
     * `CliSqlRunner`（把同一份语句喂宿主 `sqlite3`）—— 于是本类的全部逻辑在本机可测。
     */
    interface Runner : AutoCloseable {

        /**
         * **单事务**执行整批 [SqlOp]：每条 op 先跑它的 [SqlOp.dml]，紧接着跑它的
         * [SqlOp.query]（`last_insert_rowid()`/`changes()` 是**连接级**状态，只有「紧跟着」
         * 才读得到自己那条语句的结果）。返回每条 op 的查询行表，**与 [ops] 同序同长**。
         *
         * 为什么整批一次而不是逐条：`sealAndReopen` 的「封旧 + 开新」必须原子
         * （§8.5 的静默丢任务窗口），而事务边界只有执行体知道怎么开。
         *
         * 约束冲突必须抛 [ConstraintViolationException]；其余错误原样逃逸。
         */
        fun run(ops: List<SqlOp>): List<List<Map<String, Any?>>>

        /** 建表 / pragma 这类**无结果**的语句（逐条执行，不读回任何东西）。 */
        fun ddl(statements: List<String>)

        /** 单条 SELECT（无事务语义），回「列名 → 值」的行表（值域：String/Long/null）。 */
        fun query(sql: String): List<Map<String, Any?>>

        override fun close() {}
    }
}

/**
 * 存储层唯一约束冲突（存活 nonce 唯一 / 已提交 nonce 唯一 / 非空列）。
 *
 * 单独一个类型是刻意的：**只有**它会被 [SqliteIntentStore] 折成
 * `IllegalStateException`（= 幂等锚点拒绝，调用方的正常分支）；其余 SQL 错误
 * （库损坏、磁盘满、SQL 拼错）照原样逃逸成 `android.database.SQLException` ——
 * 那些是故障不是判决，折进同一个 `IllegalStateException` 会把"库坏了"伪装成
 * "重复投递被拒"。
 */
class ConstraintViolationException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)
