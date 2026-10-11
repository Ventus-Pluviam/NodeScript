package com.autoscript.platform.system.persist

import com.autoscript.domain.scripts.RunArchive
import com.autoscript.domain.scripts.RunArchiveContract
import com.autoscript.domain.scripts.RunArchivePersistenceContract
import com.autoscript.domain.scripts.RunRecord
import com.autoscript.domain.scripts.RunState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * §8.5 契约套件打在 [SqliteRunArchive] 上 —— **与 `FileRunArchive`（jsonl）跑的是同一组用例**
 * （`RunArchiveContract` 住 `:domain` 的 testFixtures，各实现各继承一次）。
 *
 * 执行体是 [CliSqlRunner]（宿主 `sqlite3` CLI）→ 本机跑的是**真 SQLite 引擎**。
 * **未被覆盖的是 `AndroidSqliteRunner` 本身**（`SQLiteOpenHelper` 建库、`rawQuery` 游标取值、
 * pragma 生效）—— 那半边只有真机能验，如实登记在 `CliSqlRunner` 的 KDoc 里。
 *
 * 环境门禁（[assumeTrue]）：宿主没有 `sqlite3` 就诚实跳过。CI 的 ubuntu 镜像预装它
 * （3.45.1 实查），真跳过了会在 `autoscript.test-guard` 当场红 —— 本类**不在**
 * `TestGuard.ENV_GATED` 里（契约套件不许静默不跑）。
 */
class SqliteRunArchiveContractTest : RunArchivePersistenceContract() {

    @TempDir
    lateinit var dir: Path

    private val sqliteAvailable: Boolean by lazy { probeSqlite() }

    @BeforeEach
    fun requireSqlite() {
        assumeTrue(sqliteAvailable, "宿主没有 sqlite3 CLI，无法在本机跑 SQLite 语义（不假扮通过）")
    }

    override fun open(): RunArchive = SqliteRunArchive(CliSqlRunner(dir.resolve("run-archive.db")))

    private fun store(): SqliteRunArchive = SqliteRunArchive(CliSqlRunner(dir.resolve("run-archive.db")))

    /** 显式本地构造（继承来的 `run` 是 protected，在 lambda 里推断不出来 —— 这里要具体类型）。 */
    private fun rec(id: Long, state: RunState) = RunRecord(
        id = id, projectId = "p1", scriptPath = "a.js", runNonce = "nonce-$id", state = state,
    )

    private fun probeSqlite(): Boolean = try {
        val p = ProcessBuilder("sqlite3", "--version").redirectErrorStream(true).start()
        p.inputStream.readBytes()
        p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0
    } catch (_: Exception) {
        false
    }

    // ── SQL 层专属（契约套件不管的机制）──

    /** 条件 UPDATE 的核心：终态那一档由 **SQLite 挡**，不是应用层先查后写。 */
    @org.junit.jupiter.api.Test
    fun `终态拒绝真由 SQL 的条件更新挡住 不是先查后写`() = kotlinx.coroutines.runBlocking {
        val s = store()
        s.put(rec(1, RunState.SUCCEEDED), null)
        // 直接绕过应用层裁定，只跑那句条件更新：它必须 0 行。
        val runner = CliSqlRunner(dir.resolve("run-archive.db"))
        val flat = RunArchiveRecord(1, "p1", "a.js", "nonce-1", RunState.RUNNING)
        val back = runner.run(listOf(RunArchiveSql.advanceState(flat))).first().singleOrNull()
        assertEquals(0L, back!!["changed"], "终态行的条件更新必须 0 行（锚点归引擎，不是应用层预检）")
        assertEquals(
            RunState.SUCCEEDED,
            s.record(1)!!.state,
            "0 行更新后库里仍是终态",
        )
    }

    @org.junit.jupiter.api.Test
    fun `AUTOINCREMENT：导入历史 id 后新号接在历史之后 不复用`() = kotlinx.coroutines.runBlocking {
        val s = store()
        val imported = s.importRecords(
            listOf(
                RunArchiveRecord(7, "p1", "a.js", "old", RunState.SUCCEEDED) to 3L,
                RunArchiveRecord(9, "p1", "a.js", "old2", RunState.SUCCEEDED) to 4L,
            ),
        )
        assertEquals(2, imported)
        assertEquals(
            com.autoscript.domain.scripts.EngineRunLink(3, 7),
            s.link(7),
            "关联随行一起导（否则迁移后按意向追溯查不到历史）",
        )
        // 「新号接在历史之后」是**自增分配**的性质，不是 put 的性质（日常 put 按给定 id 写）。
        // 直接验分配器：显式写 9 之后让它自己发一个号。
        val runner = CliSqlRunner(dir.resolve("run-archive.db"))
        runner.ddl(
            // 不给 engine_run_id —— 让 AUTOINCREMENT 自己发一个（显式写 0 会被当成合法 id 0）
            listOf("INSERT INTO ${RunArchiveSql.TABLE}(project_id, script_path, run_nonce, state) " +
                "VALUES ('p1', 'a.js', 'auto', 'RUNNING')"),
        )
        assertEquals(
            10L,
            runner.query("SELECT engine_run_id FROM ${RunArchiveSql.TABLE} WHERE run_nonce='auto'")
                .single()["engine_run_id"],
            "显式导入 9 之后自增必须从 10 起（sqlite_sequence 只增不减）",
        )
    }

    @org.junit.jupiter.api.Test
    fun `导入幂等且可重入：库里已有行整批跳过`() = kotlinx.coroutines.runBlocking {
        val s = store()
        val rows = listOf(RunArchiveRecord(3, "p1", "a.js", "h", RunState.SUCCEEDED) to 1L)
        assertEquals(1, s.importRecords(rows))
        assertEquals(0, s.importRecords(rows), "第二次导入必须跳过（库里已有行）")
        assertEquals(listOf(3L), s.records().map { it.id })
        assertEquals(0, s.importRecords(emptyList()))
    }

    @org.junit.jupiter.api.Test
    fun `run_nonce 上没有唯一索引（重投要留两条档）`() {
        val runner = CliSqlRunner(dir.resolve("run-archive.db"))
        SqliteRunArchive(runner)
        val indexes = runner.query(
            "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='${RunArchiveSql.TABLE}'",
        ).map { it["name"] as String }
        assertTrue(
            indexes.none { it.contains("nonce") },
            "档案不是幂等锚点：nonce 建唯一索引会把「重投记录」判成 bug（实际索引：$indexes）",
        )
    }

    @org.junit.jupiter.api.Test
    fun `两张表都真建出来了（关联表缺了 recordsOfIntent 恒空）`() {
        val runner = CliSqlRunner(dir.resolve("run-archive.db"))
        SqliteRunArchive(runner)
        val tables = runner.query("SELECT name FROM sqlite_master WHERE type='table'")
            .map { it["name"] as String }
        assertTrue(tables.contains(RunArchiveSql.TABLE), "档案表缺失：$tables")
        assertTrue(tables.contains(RunArchiveSql.LINK_TABLE), "关联表缺失：$tables")
    }
}
