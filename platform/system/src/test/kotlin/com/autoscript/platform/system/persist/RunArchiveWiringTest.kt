package com.autoscript.platform.system.persist

import com.autoscript.domain.scripts.EngineRunLink
import com.autoscript.domain.scripts.RunState
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
 * §8.5 一次性迁移的判据（[RunArchiveWiring.migrate]）。
 *
 * 重点是**带原 engineRunId**（不带就同号异义，`recordsOfIntent` 静默串档）与
 * **关联一起导**（否则迁移后按意向追溯查不到历史）。
 *
 * 环境门禁（[assumeTrue]）：宿主没有 `sqlite3` 就诚实跳过（真跳过会撞 test-guard）。
 */
class RunArchiveWiringTest {

    @TempDir
    lateinit var dir: Path

    private val sqliteAvailable: Boolean by lazy { probeSqlite() }

    @BeforeEach
    fun requireSqlite() {
        assumeTrue(sqliteAvailable, "宿主没有 sqlite3 CLI，无法在本机跑 SQLite 语义（不假扮通过）")
    }

    private fun store(name: String = "run-archive.db") =
        SqliteRunArchive(CliSqlRunner(dir.resolve(name)))

    private fun putLine(id: Long, state: String = "SUCCEEDED", intentRunId: Long? = null) =
        """{"op":"put","id":$id,"projectId":"p1","scriptPath":"a.js","runNonce":"n$id",""" +
            """"state":"$state","startedAt":1,"finishedAt":2,"intentRunId":${intentRunId ?: "null"}}""" + "\n"

    private fun jsonl(text: String): Path {
        val p = dir.resolve("run-archive.jsonl")
        Files.write(p, text.toByteArray())
        return p
    }

    private fun probeSqlite(): Boolean = try {
        val p = ProcessBuilder("sqlite3", "--version").redirectErrorStream(true).start()
        p.inputStream.readBytes()
        p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0
    } catch (_: Exception) {
        false
    }

    @Test
    fun `带原 engineRunId 与关联一起导 归档 jsonl`() {
        kotlinx.coroutines.runBlocking {
        val jsonlFile = jsonl(putLine(7, intentRunId = 3) + putLine(9, state = "FAILED"))
        val s = store()

        assertEquals(2, RunArchiveWiring.migrate(s, jsonlFile))
        assertEquals(listOf(7L, 9L), s.records().map { it.id }, "**必须带原 id**（不带就同号异义）")
        assertEquals(EngineRunLink(3, 7), s.link(7), "关联随行一起导（否则按意向追溯查不到历史）")
        assertNull(s.link(9))
        assertEquals(listOf(7L), s.recordsOfIntent(3).map { it.id })
        assertFalse(Files.exists(jsonlFile), "原 jsonl 已被改名")
        assertTrue(Files.exists(dir.resolve(RunArchiveWiring.ARCHIVED_NAME)), "归档不是删除（用户要回退有路）")
        }
    }

    @Test
    fun `可重入：库非空即整批跳过`() {
        val jsonlFile = jsonl(putLine(7))
        val s = store()
        assertEquals(1, RunArchiveWiring.migrate(s, jsonlFile))
        assertEquals(0, RunArchiveWiring.migrate(s, jsonlFile))
    }

    @Test
    fun `jsonl 损坏：响亮失败且老文件原样留着`() {
        val broken = jsonl("""{"op":"put","id":1,"projectId":"p1","scriptPath":"a.js",""" +
            """"runNonce":"n1","state":"NOPE","startedAt":1,"finishedAt":2,"intentRunId":null}""" + "\n")
        val s = store()

        assertThrows(IllegalArgumentException::class.java) { RunArchiveWiring.migrate(s, broken) }
        assertTrue(Files.exists(broken), "解析失败时老文件不许动")
        assertFalse(Files.exists(dir.resolve(RunArchiveWiring.ARCHIVED_NAME)), "没导成就不许归档")
    }

    @Test
    fun `jsonl 不存在或为空：无事可做`() {
        kotlinx.coroutines.runBlocking {
        val s = store()
        assertEquals(0, RunArchiveWiring.migrate(s, dir.resolve("nope.jsonl")))
        assertEquals(0, RunArchiveWiring.migrate(s, jsonl("")))
        assertTrue(s.records().isEmpty())
        }
    }

    @Test
    fun `迁移后新执行照常（登记按调用方给的 id）`() = kotlinx.coroutines.runBlocking {
        val jsonlFile = jsonl(putLine(7))
        val s = store()
        RunArchiveWiring.migrate(s, jsonlFile)

        val fresh = s.put(
            com.autoscript.domain.scripts.RunRecord(42, "p1", "a.js", "n-new", RunState.RUNNING),
            null,
        )
        assertEquals(42L, fresh.id, "新执行按**调用方给的 id** 登记（不是自增）")
        assertEquals(listOf(7L, 42L), s.records().map { it.id }, "两行都在")
    }

    private fun assertNull(value: Any?) = assertEquals(null, value, "无关联的行导入后仍无关联")
}
