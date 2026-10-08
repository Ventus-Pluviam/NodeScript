package com.autoscript.platform.system.persist

import com.autoscript.domain.json.DomainJson
import com.autoscript.domain.scripts.IntentStore

/**
 * 意图日志的 SQL 面（§8.5）：建表 DDL + 逐操作的语句文本。
 *
 * **为什么 SQL 生成单独一件、且是纯函数**：`android.database.sqlite` 的类是 Android 桩
 * （本机 JVM 跑不了），而「表形状对不对、部分唯一索引拦不拦得住、幂等锚点是不是存储层
 * 原子的、runId 会不会复用」恰恰是最该在本机验证的部分。把 SQL 文本的生成抽成不碰
 * Android 的纯函数之后，测试就能拿**同一份 SQL** 喂本机 `sqlite3` 跑真语义
 * （见 `SqliteIntentStoreSqlTest`）—— 被测的是生产用的那份语句，不是另写一份只在测试里
 * 成立的仿制品。真机侧 [SqliteIntentStore] 只做「执行这些语句 + 读游标」。
 *
 * **表形状（`intent_log`）**：
 * - `run_id INTEGER PRIMARY KEY AUTOINCREMENT` —— runId 由存储层分配且**崩溃后不复用**。
 *   依据（本机 sqlite 3.46.1 实测，见 `SqliteIntentStoreSqlTest` 的 `AUTOINCREMENT 不复用`）：
 *   AUTOINCREMENT 让 SQLite 维护 `sqlite_sequence`，新行取 `max(seq, 表内最大 rowid) + 1`，
 *   而 `seq` **只增不减** —— 删掉最大行再插、或显式写入大 id 再插，都不会回退。
 *   迁移导入带原 runId 的行（[importRow]）也靠这条：显式写 7 之后下一个自动 id 是 8。
 * - **一个部分唯一索引 `ux_nonce`**（幂等锚点 = 存储层原子拒绝，不是调用方预检）：
 *   `ON intent_log(run_nonce) WHERE outcome IS NULL OR outcome <> 'INTERRUPTED'` ——
 *   「一个 nonce 至多一条**算数的**行」，其中 `INTERRUPTED` 是「这次不算数」的封口。
 *
 *   这一条同时表达了两件事，与已退役的 jsonl 存储的两张内存表逐条等价：
 *   - **存活行唯一**：同 nonce 第二条 START 行被拒（`insertStart` 的原子兜底）；
 *   - **已提交 nonce 唯一且不可再投**：已 COMMIT 的行仍在索引里，所以同 nonce 再插一行
 *     存活行、或另一行再封成真终态，都被拒（真副作用幂等锚点）。
 *
 *   **写成两个索引（`WHERE outcome IS NULL` 一个、`WHERE outcome IS NOT NULL AND
 *   outcome <> 'INTERRUPTED'` 另一个）是错的**，本机实测过：两个部分索引覆盖的行集**不交**，
 *   于是「已 COMMIT 之后又来一条同 nonce 的 START」两边都管不着 —— 它会静静插进去，
 *   `insertStart` 的原子拒绝退化成来者不拒（§8.5 的幂等锚点当场失效）。
 *   `INTERRUPTED` 之所以必须排除在外：它是崩溃恢复的封口，随后 `sealAndReopen` 要落一条
 *   同 nonce 的新存活行，锚点若把它也算数，恢复路径会自己挡自己。
 *
 * **每个操作拆成「DML 列表 + 一句 SELECT」**（[SqlOp]）：Android 的
 * `SQLiteDatabase.execSQL` **只执行第一条语句**（`SQLiteStatement` 只 prepare 一次），
 * 所以真机侧必须逐条 execSQL，结果再由 `rawQuery` 读回；本机 CLI 侧则把两者拼成一个
 * 脚本喂 `sqlite3 -bail`。两侧同一份文本，不各写一份。
 */
internal object IntentStoreSql {

    const val TABLE = "intent_log"

    /**
     * `INTERRUPTED` 的字面量（「这次不算数」的封口，不占幂等锚点）。
     *
     * 声明在 [SCHEMA] **之前**：Kotlin object 的属性按文本顺序初始化，`SCHEMA` 的
     * 初始化表达式里要用到它 —— 放到文件后半段会让 `SCHEMA` 拿到未初始化的 null。
     */
    private const val INTERRUPTED = "INTERRUPTED"

    /**
     * 建表 + 幂等锚点的那个部分唯一索引（`ux_nonce`，理由见本对象 KDoc）。逐条一句
     * （真机逐条 execSQL），幂等（`IF NOT EXISTS`）—— 打开既有库时照样能跑。
     */
    val SCHEMA: List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS $TABLE (
            run_id INTEGER PRIMARY KEY AUTOINCREMENT,
            project_id TEXT NOT NULL,
            script_path TEXT NOT NULL,
            run_nonce TEXT NOT NULL,
            trigger_name TEXT NOT NULL,
            screen_name TEXT NOT NULL,
            scheduled_at INTEGER NOT NULL,
            started_at INTEGER NOT NULL,
            deadline_at INTEGER,
            args_json TEXT NOT NULL,
            timeout_millis INTEGER,
            outcome TEXT,
            outcome_detail TEXT,
            committed_at INTEGER
        )
        """.trimIndent(),
        "CREATE UNIQUE INDEX IF NOT EXISTS ux_nonce ON $TABLE(run_nonce) " +
            "WHERE outcome IS NULL OR outcome <> ${q(INTERRUPTED)}",
    )

    /**
     * 打开连接后立刻执行的 pragma（崩溃持久口径，§8.5）。
     *
     * `synchronous=FULL` 是**非 WAL 模式**下的选择（真机理由见 [AndroidSqliteRunner] 的 KDoc：
     * WAL 下 pragma 是每连接的、连接池会开出多条）。放在 [SCHEMA] 之前跑：先钉持久性口径，
     * 再建表 —— 反过来的话「建表」这一次提交就可能落在低持久档上。
     */
    val OPEN_PRAGMAS: List<String> = listOf("PRAGMA synchronous=FULL")

    /** 一行（全列 + 可选 `changes()`）。 [selectAll] 与 [selectRow] 共用同一段列清单。 */
    private val COLUMNS = listOf(
        "run_id", "project_id", "script_path", "run_nonce", "trigger_name", "screen_name",
        "scheduled_at", "started_at", "deadline_at", "args_json", "timeout_millis",
        "outcome", "outcome_detail", "committed_at",
    ).joinToString(", ")

    /**
     * `insertStart`：插入 START 行，回 `{run_id, changed}`。
     *
     * 单条 INSERT + `last_insert_rowid()`：同一 nonce 已有存活行或已提交行时，INSERT 被
     * 部分唯一索引**原子拒绝**（调用方拿到约束错误，不是先查后写的竞态）。
     * `project_id`/`script_path` 为 null 时被 `NOT NULL` 挡下 —— `StartRow` 的那几个字段是
     * 非空 `String`，真出现 null 是调用方 bug，响亮失败优于写一行脏数据。
     */
    fun insertStart(row: IntentStore.StartRow): SqlOp = SqlOp(
        dml = listOf("INSERT INTO $TABLE($START_COLUMNS) VALUES (${startValues(row)})"),
        query = "SELECT last_insert_rowid() AS run_id, changes() AS changed",
    )

    /**
     * `seal`：封口 [runId] 为 [outcome]，回该行**现态**（含 `changes()`）。
     *
     * `WHERE run_id = ? AND outcome IS NULL` 让重复封口变成 0 行更新（幂等，回现态），
     * 而「该 nonce 已有别的真终态」由 `ux_nonce` 原子拒绝（约束错误）。
     * 先写后读同一份语句里完成：幂等分支与首次封口走同一段读，读回的就是当前真值。
     */
    fun seal(runId: Long, outcome: IntentStore.StoredOutcome, atMillis: Long): SqlOp = SqlOp(
        dml = listOf(
            "UPDATE $TABLE SET outcome = ${q(outcome.name)}, " +
                "outcome_detail = ${outcome.detail?.let(::q) ?: "NULL"}, " +
                "committed_at = $atMillis WHERE run_id = $runId AND outcome IS NULL",
        ),
        query = "SELECT $COLUMNS, changes() AS changed FROM $TABLE WHERE run_id = $runId",
    )

    /**
     * `sealAndReopen`：一个事务里「封旧行 INTERRUPTED + 同 nonce 新开一行」，
     * 回新行 id（`{new_id, inserted}`）。
     *
     * 三步为什么必须在一个事务里：只封不开 = 意向凭空消失（§8.5 的静默丢任务窗口），
     * 只开不封 = 同 nonce 两条存活行（被 `ux_nonce` 拒绝）。事务由调用方包住
     * （真机 `beginTransaction`、本机 CLI 脚本 `BEGIN`/`COMMIT`）—— 这里只出语句。
     *
     * 条件插入（`SELECT … WHERE changes() = 1`）是**纯 SQL 表达「更新成功才插入」**的手段
     * （本机实测：中间的 SELECT 不影响 `changes()`）。旧行不存在或已终态 → 更新 0 行 →
     * 插入 0 行 → 调用方按 `inserted = 0` 判 `IllegalArgumentException`。
     * 除 `started_at` 换成 [atMillis] 外**整行复制**（args/timeout/deadline/screen 一个不丢，§8.5）。
     */
    fun sealAndReopen(oldRunId: Long, atMillis: Long): SqlOp = SqlOp(
        dml = listOf(
            "UPDATE $TABLE SET outcome = ${q(INTERRUPTED)}, outcome_detail = NULL, " +
                "committed_at = $atMillis WHERE run_id = $oldRunId AND outcome IS NULL",
            "INSERT INTO $TABLE($START_COLUMNS) SELECT " +
                "project_id, script_path, run_nonce, trigger_name, screen_name, " +
                "scheduled_at, $atMillis, deadline_at, args_json, timeout_millis " +
                "FROM $TABLE WHERE run_id = $oldRunId AND changes() = 1",
        ),
        query = "SELECT last_insert_rowid() AS new_id, changes() AS inserted",
    )

    /**
     * 全量行读取（`liveRows`/`allRows` 共用）：按 `run_id` 升序 —— 与
     * 已退役的 jsonl 存储的 `sortedMapOf` 迭代序一致（契约是「按 runId 升序」）。
     *
     * @param liveOnly true = 只取未封口行（启动回放入口）
     */
    fun selectRows(liveOnly: Boolean): SqlOp = SqlOp(
        dml = emptyList(),
        query = "SELECT $COLUMNS FROM $TABLE" +
            (if (liveOnly) " WHERE outcome IS NULL" else "") +
            " ORDER BY run_id",
    )

    /** `hasCommittedNonce`：该 nonce 是否已有**真**终态（INTERRUPTED 不算）。 */
    fun hasCommittedNonce(nonce: String): SqlOp = SqlOp(
        dml = emptyList(),
        query = "SELECT COUNT(*) AS n FROM $TABLE WHERE run_nonce = ${q(nonce)} " +
            "AND outcome IS NOT NULL AND outcome <> ${q(INTERRUPTED)}",
    )

    /** `hasLiveNonce`：该 nonce 是否已有存活行。 */
    fun hasLiveNonce(nonce: String): SqlOp = SqlOp(
        dml = emptyList(),
        query = "SELECT COUNT(*) AS n FROM $TABLE WHERE run_nonce = ${q(nonce)} AND outcome IS NULL",
    )

    /**
     * 批次分隔标记（执行体专用，不是存储操作）：`Runner.run` 要靠它把「一批 op 的查询结果」
     * 切开 —— `sqlite3` CLI 只吐一个 JSON 数组，零行的 SELECT 又什么都不吐，所以边界必须
     * 显式写出来。真机侧不需要（`rawQuery` 逐条天然分开），照跑一遍也无害。
     */
    const val MARKER: String = "__intent_store_op_boundary__"

    /** 库里现有行数（迁移守卫：非空即已迁移过，不再导入）。 */
    fun countRows(): SqlOp = SqlOp(emptyList(), "SELECT COUNT(*) AS n FROM $TABLE")

    /**
     * 迁移导入一行（§8.5 存储引擎替换的一次性导入）：**带原 runId** 落库。
     *
     * 为什么必须保 runId：`run-archive.jsonl` 里的 `EngineRunLink.intentRunId` 指向的就是
     * 这些 id。新库若从 1 重新发号，新意向会与历史 link **同号异义** ——
     * `recordsOfIntent(3)` 会把旧引擎记录当成新意向的执行历史（静默串档，不是"丢数据"
     * 那么容易被发现）。显式写 id 之后 `sqlite_sequence` 随之上抬（见类 KDoc），
     * 后续自动分配自然接在历史之后。
     *
     * `INSERT OR IGNORE`：**已导入过的 runId 跳过**（可重入）。整批导入由调用方包在一个
     * 事务里（见 [SqliteIntentStore.importRows]），所以正常路径不会留下"导入一半"的库；
     * OR IGNORE 只兜「上一轮已成功、这一轮又跑了一次」这种幂等重放。
     */
    fun importRow(row: IntentStore.StoredRow): SqlOp = SqlOp(
        dml = listOf(
            "INSERT OR IGNORE INTO $TABLE(run_id, $START_COLUMNS, outcome, outcome_detail, committed_at) " +
                "VALUES (${row.runId}, ${startValues(row.start)}, " +
                "${row.outcome?.name?.let(::q) ?: "NULL"}, " +
                "${row.outcome?.detail?.let(::q) ?: "NULL"}, " +
                "${row.committedAtMillis?.toString() ?: "NULL"})",
        ),
        query = "SELECT changes() AS changed",
    )

    // —— 列清单与取值（INSERT 三处共用同一份，列序不靠人肉对齐）——

    private const val START_COLUMNS =
        "project_id, script_path, run_nonce, trigger_name, screen_name, " +
            "scheduled_at, started_at, deadline_at, args_json, timeout_millis"

    private fun startValues(r: IntentStore.StartRow): String = listOf(
        q(r.projectId),
        q(r.scriptPath),
        q(r.runNonce),
        q(r.trigger),
        q(r.screen),
        r.scheduledAtMillis.toString(),
        r.startedAtMillis.toString(),
        r.deadlineMillis?.toString() ?: "NULL",
        q(DomainJson.encode(r.args)),
        r.timeoutMillis?.toString() ?: "NULL",
    ).joinToString(", ")

    /**
     * SQL 字符串字面量：单引号双写转义。
     *
     * 只转义单引号是**正确且充分**的：SQLite 的字符串字面量里反斜杠不是转义符，
     * 换行/制表/多字节字符都可以原样躺在字面量里（真机 `execSQL` 不在乎语句里有换行）。
     * 本对象生成的每条语句都是**一条一句**（[SqlOp.dml] 逐条），所以本机 CLI 逐行执行与
     * 真机逐条 execSQL 是同一份文本的两种喂法。
     */
    private fun q(s: String): String = "'" + s.replace("'", "''") + "'"
}

/**
 * 一个存储操作的语句组：[dml] 逐条执行，[query] 是唯一那句 SELECT（结果读回）。
 *
 * 形状由 Android 侧逼出来：`SQLiteDatabase.execSQL` 只跑第一条语句、且跑不了 SELECT，
 * 所以「写」与「读回」必须分开表达。两侧执行体（真机 [AndroidSqliteRunner]、本机
 * `CliSqlRunner`）都按「先逐条 dml、再 query」这一条固定次序跑，`last_insert_rowid()` /
 * `changes()` 这类**连接级**状态因此总读得到自己那条语句的结果。
 */
data class SqlOp(val dml: List<String>, val query: String)
