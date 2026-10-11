package com.autoscript.platform.system.persist

import com.autoscript.domain.scripts.ScheduledTask
import com.autoscript.domain.scripts.TaskStore
import com.autoscript.domain.scripts.TaskStorePersistenceContract
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * §8.6 契约套件打在 [SqliteTaskStore] 上 —— **与 `FileTaskStore`（jsonl）跑的是同一组用例**
 * （`TaskStoreContract` 住 `:domain` 的 testFixtures）。
 *
 * 执行体是 [CliSqlRunner]（宿主 `sqlite3` CLI）→ 本机跑的是**真 SQLite 引擎**。
 * **未被覆盖的是 `AndroidSqliteRunner` 本身**（`SQLiteOpenHelper` 建库、`rawQuery` 游标取值、
 * pragma 生效）—— 那半边只有真机能验。
 *
 * 环境门禁（[assumeTrue]）：宿主没有 `sqlite3` 就诚实跳过；真跳过了会在
 * `autoscript.test-guard` 当场红（本类不在 `TestGuard.ENV_GATED` 里 —— 契约套件不许静默不跑）。
 */
class SqliteTaskStoreContractTest : TaskStorePersistenceContract() {

    @TempDir
    lateinit var dir: Path

    private val sqliteAvailable: Boolean by lazy { probeSqlite() }

    @BeforeEach
    fun requireSqlite() {
        assumeTrue(sqliteAvailable, "宿主没有 sqlite3 CLI，无法在本机跑 SQLite 语义（不假扮通过）")
    }

    override fun open(): TaskStore = SqliteTaskStore(CliSqlRunner(dir.resolve("tasks.db")))

    private fun store(): SqliteTaskStore = SqliteTaskStore(CliSqlRunner(dir.resolve("tasks.db")))

    private fun probeSqlite(): Boolean = try {
        val p = ProcessBuilder("sqlite3", "--version").redirectErrorStream(true).start()
        p.inputStream.readBytes()
        p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0
    } catch (_: Exception) {
        false
    }

    // ── SQL 层专属（契约套件不管的机制）──

    /** 墓碑是**行**不是删行（否则「删除一个不存在的任务」不可审计）。 */
    @org.junit.jupiter.api.Test
    fun `撤销落行不删行：墓碑可在库里查到`() {
        val s = store()
        s.put(task("t1"))
        s.remove("t1")
        val rows = CliSqlRunner(dir.resolve("tasks.db"))
            .query("SELECT task_id, revoked_at FROM ${TaskStoreSql.TABLE}")
        org.junit.jupiter.api.Assertions.assertEquals(1, rows.size, "撤销不许删行（墓碑要留得住）")
        org.junit.jupiter.api.Assertions.assertNotNull(rows.single()["revoked_at"], "墓碑要带撤销时刻")
    }

    /** 重新登记 = 复活（`INSERT OR REPLACE` 必须一并把 `revoked_at` 置 NULL）。 */
    @org.junit.jupiter.api.Test
    fun `撤销后重新登记把 revoked_at 置回 NULL（不靠应用层先查）`() {
        val s = store()
        s.put(task("t1"))
        s.remove("t1")
        s.put(task("t1"))
        val row = CliSqlRunner(dir.resolve("tasks.db"))
            .query("SELECT revoked_at FROM ${TaskStoreSql.TABLE} WHERE task_id='t1'")
            .single()
        org.junit.jupiter.api.Assertions.assertNull(row["revoked_at"], "复活必须清掉墓碑，否则任务永远不续排")
    }

    /** 迁移：墓碑**一起导**（否则「历史上撤销过」会变成「迁移后复活」）。 */
    @org.junit.jupiter.api.Test
    fun `迁移把墓碑一起导 撤销的任务不会复活`() {
        // 源库：两行 + 一个墓碑（模拟「用户登记过 t2 又撤销了」）
        val source = SqliteTaskStore(CliSqlRunner(dir.resolve("source.db")))
        source.put(task("alive"))
        source.put(task("gone"))
        source.remove("gone")

        // 目标库：**空库** —— 这才是迁移现场（迁移守卫：库非空即跳过）
        val target = SqliteTaskStore(CliSqlRunner(dir.resolve("target.db")))
        org.junit.jupiter.api.Assertions.assertEquals(
            2, target.importTasks(source.allWithRevoked()),
            "两行都要导（含墓碑那一行）",
        )
        org.junit.jupiter.api.Assertions.assertEquals(
            listOf("alive"), target.loadAll().map { it.id },
            "墓碑必须随行导入（否则撤销过的任务迁移后自己复活）",
        )
        val rows = CliSqlRunner(dir.resolve("target.db"))
            .query("SELECT task_id FROM ${TaskStoreSql.TABLE}")
        org.junit.jupiter.api.Assertions.assertEquals(
            2, rows.size, "撤销落墓碑行，不删行 —— 墓碑要留得住",
        )
    }

    @org.junit.jupiter.api.Test
    fun `导入幂等且可重入：库里已有行整批跳过`() {
        val source = SqliteTaskStore(CliSqlRunner(dir.resolve("src.db")))
        source.put(task("x"))
        val rows = source.allWithRevoked()

        val target = SqliteTaskStore(CliSqlRunner(dir.resolve("tgt.db")))
        org.junit.jupiter.api.Assertions.assertEquals(1, target.importTasks(rows), "空库首次导：真导")
        org.junit.jupiter.api.Assertions.assertEquals(0, target.importTasks(rows), "库里已有行 → 整批跳过（可重入）")
        org.junit.jupiter.api.Assertions.assertEquals(0, target.importTasks(emptyList()))
    }

    /** 排期三列（`kind`/`a`/`b`）—— JSON 列会把「能按它排序/查」的能力白扔。 */
    @org.junit.jupiter.api.Test
    fun `排期落三列而不是 JSON 列`() {
        val s = store()
        s.put(cronTask("c", "0 9 * * 1"))
        val row = CliSqlRunner(dir.resolve("tasks.db"))
            .query("SELECT kind, a, b FROM ${TaskStoreSql.TABLE} WHERE task_id='c'")
            .single()
        org.junit.jupiter.api.Assertions.assertEquals("cron", row["kind"])
        org.junit.jupiter.api.Assertions.assertEquals("0 9 * * 1", row["a"])
        org.junit.jupiter.api.Assertions.assertEquals("", row["b"])
    }

    private fun task(id: String) = ScheduledTask(
        id = id, name = "n", projectId = "p", scriptPath = "$id.js",
        schedule = com.autoscript.domain.scripts.TimedSchedule.Once(60),
    )

    private fun cronTask(id: String, expr: String) = ScheduledTask(
        id = id, name = "n", projectId = "p", scriptPath = "$id.js",
        schedule = com.autoscript.domain.scripts.TimedSchedule.Cron(expr),
    )
}
