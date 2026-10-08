package com.autoscript.platform.system.persist

import com.autoscript.domain.scripts.IntentStore
import com.autoscript.domain.scripts.IntentStoreContract
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * §8.5 契约套件打在 [SqliteIntentStore] 上 —— **与 `InMemoryIntentStore` 跑的是同一组用例**
 * （`IntentStoreContract` 住 `:domain` 的 testFixtures，各实现各继承一次）。
 *
 * 执行体是 [CliSqlRunner]（宿主 `sqlite3` CLI），因此本机跑的是**真 SQLite 引擎**，
 * 不是仿制品。**没被这份测试覆盖的是 `AndroidSqliteRunner` 本身**（`SQLiteOpenHelper`
 * 建库/升级、游标取值、pragma 生效）—— 那半边只有真机能验，本轨未验，如实登记在
 * `CliSqlRunner` 的 KDoc 与最终报告里。
 *
 * 环境门禁（[assumeTrue]）：宿主没有 `sqlite3` 就诚实跳过 —— 与 `HostNpm` 那条
 * 「探不到就跳过、不假扮通过」同口径。**这不是"本机默认跳过"**：`sqlite3` 在
 * GitHub runner 的 ubuntu 镜像里是预装件（`Ubuntu2404-Readme.md` 实查：`sqlite3 3.45.1`），
 * CI 与开发机都跑得到。真跳过了会在 `autoscript.test-guard` 那里当场红 —— 本类**不在**
 * `TestGuard.ENV_GATED` 里，跳过即违规（口径：契约套件不许静默不跑）。
 */
class SqliteIntentStoreContractTest : IntentStoreContract() {

    @TempDir
    lateinit var dir: Path

    private val sqliteAvailable: Boolean by lazy { probeSqlite() }

    @BeforeEach
    fun requireSqlite() {
        assumeTrue(sqliteAvailable, "宿主没有 sqlite3 CLI，无法在本机跑 SQLite 语义（不假扮通过）")
    }

    /** 每次都是**新实例**（契约要求）—— 指向同一个库文件，因此等价于"重启后重开"。 */
    override fun open(): IntentStore = SqliteIntentStore(CliSqlRunner(dir.resolve("intent-log.db")))

    /** 直接拿一个 store 做 SQL 层专属断言（契约之外的部分）。 */
    private fun store(): SqliteIntentStore = open() as SqliteIntentStore

    private fun probeSqlite(): Boolean = try {
        val p = ProcessBuilder("sqlite3", "--version").redirectErrorStream(true).start()
        p.inputStream.readBytes()
        p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0
    } catch (_: Exception) {
        false
    }

    // ── 以下是 SQL 层专属（契约套件不管的东西）：表形状、索引、AUTOINCREMENT ──

    @org.junit.jupiter.api.Test
    fun `幂等锚点的部分唯一索引真建出来了（缺索引 = 锚点退化成来者不拒）`() {
        val s = store()
        val names = CliSqlRunner(dir.resolve("intent-log.db")).let { runner ->
            // 索引清单直接从 sqlite_master 读：DDL 建没建出来是事实，不靠"我写了 CREATE INDEX"
            runner.query(
                "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='${IntentStoreSql.TABLE}'",
            )
                .map { it["name"] as String }
        }
        org.junit.jupiter.api.Assertions.assertTrue(
            names.contains("ux_nonce"),
            "幂等锚点的部分唯一索引必须真建出来，实际索引：$names",
        )
        s.close()
    }

    @org.junit.jupiter.api.Test
    fun `AUTOINCREMENT 分配单调且删除最大行后不复用`() {
        val file = dir.resolve("intent-log.db")
        val runner = CliSqlRunner(file)
        val s = SqliteIntentStore(runner)
        val a = s.insertStart(
            IntentStore.StartRow("p", "a.js", "n1", "TIMED", "ANY", 1, 2, null),
        )
        val b = s.insertStart(
            IntentStore.StartRow("p", "a.js", "n2", "TIMED", "ANY", 1, 2, null),
        )
        // 删掉最大行（模拟"历史被清理"——今天不做保留期策略，但 id 不复用是**存储层**性质）
        runner.ddl(listOf("DELETE FROM ${IntentStoreSql.TABLE} WHERE run_id = $b"))
        val c = s.insertStart(
            IntentStore.StartRow("p", "a.js", "n3", "TIMED", "ANY", 1, 2, null),
        )
        org.junit.jupiter.api.Assertions.assertTrue(
            c > b,
            "AUTOINCREMENT 的 id 只增不减（sqlite_sequence 记着）：$a,$b → $c",
        )
        s.close()
    }

    @org.junit.jupiter.api.Test
    fun `显式导入历史 runId 之后自动分配接在历史之后`() {
        val s = store()
        val imported = s.importRows(
            listOf(
                IntentStore.StoredRow(7, IntentStore.StartRow("p", "a.js", "old", "TIMED", "ANY", 1, 2, null), null, null),
                IntentStore.StoredRow(
                    9,
                    IntentStore.StartRow("p", "a.js", "old2", "TIMED", "ANY", 1, 2, null),
                    IntentStore.StoredOutcome.SUCCEEDED, 5,
                ),
            ),
        )
        org.junit.jupiter.api.Assertions.assertEquals(2, imported)
        val next = s.insertStart(
            IntentStore.StartRow("p", "a.js", "new", "TIMED", "ANY", 1, 2, null),
        )
        org.junit.jupiter.api.Assertions.assertTrue(
            next > 9,
            "导入历史后新号必须接在历史之后（否则与 run-archive 的 intentRunId 同号异义）：$next",
        )
        org.junit.jupiter.api.Assertions.assertTrue(s.hasCommittedNonce("old2"), "导入的已提交 nonce 必须是幂等锚点")
        s.close()
    }

    @org.junit.jupiter.api.Test
    fun `导入幂等且可重入：库里已有行就整批跳过`() {
        val s = store()
        val rows = listOf(
            IntentStore.StoredRow(3, IntentStore.StartRow("p", "a.js", "h", "TIMED", "ANY", 1, 2, null), null, null),
        )
        org.junit.jupiter.api.Assertions.assertEquals(1, s.importRows(rows))
        org.junit.jupiter.api.Assertions.assertEquals(0, s.importRows(rows), "第二次导入必须跳过（库里已有行）")
        org.junit.jupiter.api.Assertions.assertEquals(listOf(3L), s.allRows().map { it.runId })
        org.junit.jupiter.api.Assertions.assertEquals(0, s.importRows(emptyList()))
        s.close()
    }
}
