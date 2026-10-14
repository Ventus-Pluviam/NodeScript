package com.autoscript.platform.system.persist

import com.autoscript.domain.scripts.EngineRunLink
import com.autoscript.domain.scripts.RunArchive
import com.autoscript.domain.scripts.RunRecord
import com.autoscript.domain.scripts.RunState

/**
 * 运行档案的 SQLite 实现（§8.2 归档 + §8.5 归档入口；2026-10-11 批 102 P4 阶段 B）。
 *
 * 语义与 `FileRunArchive`（jsonl）**逐条等价** —— 两者共用同一套契约测试
 * （`RunArchiveContract`，见 `:domain` 的 testFixtures）。
 *
 * **为什么住 `:platform:system`**：`android.database.sqlite` 是 Android 面，依赖铁律是
 * `:platform:* → :domain`，故 SPI（[RunArchive]）与本实现分居两侧（同 `SqliteIntentStore`）。
 *
 * **SQL 不在本类里拼**：全部语句由 [RunArchiveSql] 生成（纯函数、零 Android），于是
 * 「表形状、终态不可改写、条件更新原子性」能在本机拿同一份语句跑真 `sqlite3` 验证。
 * 本类只剩「执行 + 读游标 + 折成 [RunRecord]」。
 *
 * **三条不变量落在哪**：
 * - **终态不可改写/复活** → [RunArchiveSql.advanceState] 的 `WHERE state NOT IN (终态)`：
 *   更新 0 行即「已终态」，**由 SQLite 拒绝**，不是应用层先查后写（与 `ux_nonce` 同一条
 *   纪律：锚点归引擎）；
 * - **关联一旦成立不改写** → [RunArchiveSql.link] 用 `INSERT`（不用 `OR IGNORE`）：
 *   主键冲突即约束错误 → 调用方据此折 `IllegalStateException`（静默吞掉「带了不同 link」
 *   的调用方 bug 是更坏的选择）；
 * - **engineRunId 崩溃后不复用** → `AUTOINCREMENT`（理由见 [RunArchiveSql] 类 KDoc，
 *   与意图日志同一套机制，本机 `sqlite3` 实测过）。
 *
 * **不做日志清理/保留期**：老记录一旦能丢，`recordsOfIntent` 的历史追溯即失真 ——
 * 那是策略变更（A13 仍未拍板），不是本次存储引擎替换的一部分。
 */
class SqliteRunArchive(private val runner: SqliteIntentStore.Runner) : RunArchive {

    init {
        // 先钉持久性口径再建表（反过来的话「建表」那次提交可能落在低持久档上）；
        // 建表幂等（IF NOT EXISTS），既有库上重开照样能跑。
        runner.ddl(RunArchiveSql.OPEN_PRAGMAS + RunArchiveSql.SCHEMA)
    }

    /**
     * 写入/前进一条记录（同 [RunArchive.put] 的语义：两条路径都在这里原子裁定）。
     *
     * 裁定的依据是**库里那一行**而不是调用方给的形状 —— 与 jsonl 侧逐条等价
     * （`FileRunArchive.adjudicate` 的四条件在 SQL 层各对应一句）。
     */
    override suspend fun put(record: RunRecord, link: EngineRunLink?): RunRecord {
        // require/check 而不是显式 throw：类型完全相同（IAE / ISE），而契约套件断言的正是
        // 「IAE / ISE + 话术里点名」——detekt 的 UseRequire/UseCheckOrError 要的就是这个形状。
        val incomingLink = link   // 局部接住：require 的 lambda 里 Kotlin 不做 smart cast
        require(incomingLink == null || incomingLink.engineRunId == record.id) {
            "EngineRunLink.engineRunId=${incomingLink?.engineRunId} 与 RunRecord.id=${record.id} 不一致"
        }
        // **登记按 id 幂等**（不是「存储层发号」）：契约套件的 `open()` 每次给的是**指定
        // engineRunId** 的记录（jsonl 侧那条路也是按 id 写）。若改成自增发号，
        // 「调用方给的 id」与「库里那一行」就对不上 —— 症状是所有断言读回的都是新号。
        // 自增只发生在**迁移导入**那条路（带原 id），日常登记一律按给定 id 写。
        val existing = findRecord(record.id)
        val flat = record.toFlat()
        val outcome = if (existing == null) {
            // 首次建档：按给定 id 插入（`INSERT OR IGNORE` 让「并发两次建同 id」幂等）。
            runner.run(listOf(RunArchiveSql.insert(flat))).first().singleOrNull()
                ?: error("登记后读不回结果（库异常）")
        } else {
            check(!existing.state.isTerminalState()) {
                val verb = if (!record.state.isTerminalState()) "复活" else "不可改写"
                "终态记录$verb: engineRunId=${record.id} state=${existing.state}"
            }
            val existingLink = findLink(record.id)
            check(link == null || existingLink == null || existingLink == link) {
                "双 id 关联已成立，不允许改写: $existingLink → $link"
            }
            // 前进：条件更新（终态那一档由 SQL 挡）。读回的是**现态**。
            runner.run(listOf(RunArchiveSql.advanceState(flat))).first().singleOrNull()
                ?: error("前进后读不回结果（库异常）")
        }
        val engineRunId = outcome["engine_run_id"] as? Long ?: record.id
        if (link != null && findLink(engineRunId) == null) {
            // 关联随行写入（同一批 op = 同一事务；`INSERT` 冲突即「试图改写」）。
            runner.run(listOf(RunArchiveSql.link(engineRunId, link.intentRunId)))
        }
        return record.copy(id = engineRunId)
    }

    override suspend fun record(engineRunId: Long): RunRecord? = findRecord(engineRunId)

    override suspend fun link(engineRunId: Long): EngineRunLink? = findLink(engineRunId)

    override suspend fun recordsOfIntent(intentRunId: Long): List<RunRecord> =
        rows(RunArchiveSql.Query.ByIntent(intentRunId))

    override suspend fun recordsOfProject(projectId: String): List<RunRecord> =
        rows(RunArchiveSql.Query.ByProject(projectId))

    override suspend fun records(): List<RunRecord> = rows(RunArchiveSql.Query.All)

    override suspend fun unfinished(): List<RunRecord> = rows(RunArchiveSql.Query.Unfinished)

    /** 高水位：一句 `MAX(engine_run_id)`（空表 COALESCE 回 0；见 [RunArchiveSql.maxEngineRunId]）。 */
    override suspend fun maxEngineRunId(): Long =
        runner.query(RunArchiveSql.maxEngineRunId().query).singleOrNull()?.long("max_id")
            ?: error("高水位查询没回行（库异常）")

    /**
     * 一次性迁移导入（§8.5 存储引擎替换）：把 jsonl 侧的行**带着原 engineRunId** 落进 SQLite，
     * 关联随行一起导。
     *
     * 幂等 + 可重入：库里已有行即跳过（[RunArchiveSql.countRows]）；单行 `INSERT OR IGNORE`
     * 兜「上一轮已成功、这一轮又跑了一次」。
     */
    internal fun importRecords(records: List<Pair<RunArchiveRecord, Long?>>): Int {
        if (records.isEmpty()) return 0
        if (count(RunArchiveSql.countRows()) > 0) return 0
        // 整批**一个事务**：导入一半的库比「导不进去」更坏（它看起来是导过的）。
        return runner.run(records.map { (record, intentRunId) -> RunArchiveSql.importRow(record, intentRunId) })
            .sumOf { results -> results.singleOrNull()?.long("changed") ?: 0L }
            .toInt()
    }

    // —— 读口（各查询走同一句 SQL，结果折成 RunRecord/EngineRunLink）——

    private fun findRecord(engineRunId: Long): RunRecord? =
        runner.query(RunArchiveSql.select(RunArchiveSql.Query.ById(engineRunId)).query).singleOrNull()?.toRecord()

    private fun findLink(engineRunId: Long): EngineRunLink? =
        runner.query(RunArchiveSql.select(RunArchiveSql.Query.LinkById(engineRunId)).query).singleOrNull()
            ?.let { EngineRunLink(it.long("intent_run_id"), engineRunId) }

    private fun rows(query: RunArchiveSql.Query): List<RunRecord> =
        runner.query(RunArchiveSql.select(query).query).map { it.toRecord() }

    private fun count(op: SqlOp): Long =
        runner.query(op.query).singleOrNull()?.long("n") ?: error("计数查询没回行（库异常）")

    private fun Map<String, Any?>.long(key: String): Long =
        this[key] as? Long ?: error("列 $key 缺失或非整数（库异常）")

    /**
     * `:domain` 的 [RunRecord] → SQL 层的扁平行。internal（不是 private）：
     * [RunArchiveWiring.migrate] 拿到迁移解析出来的 [RunRecord] 后要造同一种行。
     */
    internal fun RunRecord.toFlat(): RunArchiveRecord = RunArchiveRecord(
        engineRunId = id,
        projectId = projectId,
        scriptPath = scriptPath,
        runNonce = runNonce,
        state = state,
        startedAtMillis = startedAtMillis,
        finishedAtMillis = finishedAtMillis,
        exitCode = exitCode,
        crashSummary = crashSummary,
    )

    private fun Map<String, Any?>.toRecord(): RunRecord = RunRecord(
        id = long("engine_run_id"),
        projectId = str("project_id"),
        scriptPath = str("script_path"),
        runNonce = str("run_nonce"),
        state = RunState.valueOf(str("state")),
        startedAtMillis = optLong("started_at"),
        finishedAtMillis = optLong("finished_at"),
        exitCode = optLong("exit_code")?.toInt(),
        crashSummary = optStr("crash_summary"),
    )

    private fun Map<String, Any?>.optLong(key: String): Long? = this[key] as? Long

    private fun Map<String, Any?>.str(key: String): String =
        this[key] as? String ?: error("列 $key 缺失或非文本（库异常）")

    private fun Map<String, Any?>.optStr(key: String): String? = this[key] as? String

    private fun RunState.isTerminalState(): Boolean =
        this == RunState.SUCCEEDED || this == RunState.FAILED ||
            this == RunState.CRASHED || this == RunState.CANCELLED
}
