package com.autoscript.platform.system.persist

import com.autoscript.domain.scripts.ScheduledTask
import com.autoscript.domain.scripts.TimedSchedule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * §8.6 一次性迁移的判据（[TaskStoreWiring.migrate]）。
 *
 * 钉的是三条纪律：**导**（含墓碑）+ **归档**、**可重入**、**失败响亮**。
 * 执行体是 [CliSqlRunner]（宿主 `sqlite3`）→ 跑的是真 SQLite 引擎。
 *
 * 环境门禁（[assumeTrue]）：宿主没有 `sqlite3` 就诚实跳过（真跳过会撞 test-guard）。
 */
class TaskStoreWiringTest {

    @TempDir
    lateinit var dir: Path

    private val sqliteAvailable: Boolean by lazy { probeSqlite() }

    @BeforeEach
    fun requireSqlite() {
        assumeTrue(sqliteAvailable, "宿主没有 sqlite3 CLI，无法在本机跑 SQLite 语义（不假扮通过）")
    }

    private fun db(name: String = "tasks.db") = SqliteTaskStore(CliSqlRunner(dir.resolve(name)))

    private fun jsonl(text: String): Path {
        val p = dir.resolve("tasks.jsonl")
        Files.write(p, text.toByteArray())
        return p
    }

    private fun putLine(id: String, a: String = "60") =
        """{"op":"put","id":"$id","name":"n","project":"p","script":"s.js",""" +
            """"kind":"once","a":"$a","b":"","screen":"ANY","argsN":0,""" +
            """"timeout":null,"tz":"UTC","enabled":1}""" + "\n"

    private fun probeSqlite(): Boolean = try {
        val p = ProcessBuilder("sqlite3", "--version").redirectErrorStream(true).start()
        p.inputStream.readBytes()
        p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0
    } catch (_: Exception) {
        false
    }

    @Test
    fun `库空且 jsonl 非空：导入并改名归档`() {
        val jsonlFile = jsonl(putLine("t1") + putLine("t2", "120"))
        val store = db()

        assertEquals(2, TaskStoreWiring.migrate(store, jsonlFile))
        assertEquals(listOf("t1", "t2"), store.loadAll().map { it.id })
        assertFalse(Files.exists(jsonlFile), "原 jsonl 已被改名")
        assertTrue(
            Files.exists(dir.resolve(TaskStoreWiring.ARCHIVED_NAME)),
            "归档不是删除（用户要回退有路）：${TaskStoreWiring.ARCHIVED_NAME}",
        )
        assertEquals(TimedSchedule.Once(120), store.loadAll().first { it.id == "t2" }.schedule)
    }

    @Test
    fun `墓碑一起导：撤销过的任务迁移后不复活`() {
        val jsonlFile = jsonl(putLine("alive") + putLine("gone") + """{"op":"del","id":"gone"}""" + "\n")
        val store = db()

        assertEquals(2, TaskStoreWiring.migrate(store, jsonlFile), "两行都要导（含墓碑那一行）")
        assertEquals(
            listOf("alive"), store.loadAll().map { it.id },
            "墓碑必须随行导入（否则撤销过的任务迁移后自己复活）",
        )
    }

    @Test
    fun `可重入：库非空即整批跳过`() {
        val jsonlFile = jsonl(putLine("t1"))
        val store = db()
        assertEquals(1, TaskStoreWiring.migrate(store, jsonlFile))
        // 第二次：库已有行 → 跳过，且 jsonl 已归档（不再被读）
        assertEquals(0, TaskStoreWiring.migrate(store, jsonlFile))
    }

    @Test
    fun `jsonl 损坏：响亮失败且老文件原样留着`() {
        val broken = jsonl("""{"op":"put","id":"t1","kind":"weekly","a":"1","b":"","name":"n",""" +
            """"project":"p","script":"s.js","argsN":0,"screen":"ANY","timeout":null,"tz":"UTC","enabled":1}""" + "\n")
        val store = db()

        assertThrows(IllegalArgumentException::class.java) { TaskStoreWiring.migrate(store, broken) }
        assertTrue(Files.exists(broken), "解析失败时老文件不许动（否则用户既没导成也没了原档）")
        assertFalse(
            Files.exists(dir.resolve(TaskStoreWiring.ARCHIVED_NAME)),
            "没导成就不许归档（改名在验完之后）",
        )
    }

    @Test
    fun `jsonl 不存在或为空：无事可做`() {
        val store = db()
        assertEquals(0, TaskStoreWiring.migrate(store, dir.resolve("nope.jsonl")))
        assertEquals(0, TaskStoreWiring.migrate(store, jsonl("")), "空文件无事可做")
        assertTrue(store.loadAll().isEmpty())
    }

    @Test
    fun `迁移后新登记照常（不因归档了 jsonl 就只读）`() {
        val jsonlFile = jsonl(putLine("t1"))
        val store = db()
        TaskStoreWiring.migrate(store, jsonlFile)

        store.put(ScheduledTask("t9", "n", "p", "s.js", TimedSchedule.Once(30)))
        assertEquals(setOf("t1", "t9"), store.loadAll().map { it.id }.toSet())
    }
}
