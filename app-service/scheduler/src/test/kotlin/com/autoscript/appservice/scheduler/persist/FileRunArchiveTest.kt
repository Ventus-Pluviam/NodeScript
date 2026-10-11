package com.autoscript.appservice.scheduler.persist

import com.autoscript.domain.scripts.EngineRunLink
import com.autoscript.domain.scripts.RunArchive
import com.autoscript.domain.scripts.RunArchiveContract
import com.autoscript.domain.scripts.RunArchivePersistenceContract
import com.autoscript.domain.scripts.RunRecord
import com.autoscript.domain.scripts.RunState
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * FileRunArchive 单测（§8.5 归档持久化）。
 *
 * 语义锚点与 InMemoryRunArchiveTest 对齐：同一套契约，两个实现。
 * 持久化三件事另验：落盘可 replay、半行容忍、特殊字符往返。
 */
class FileRunArchiveTest {

    @TempDir
    lateinit var dir: Path

    private fun run(id: Long, state: RunState, nonce: String = "nonce-$id") = RunRecord(
        id = id,
        projectId = "p1",
        scriptPath = "a.js",
        runNonce = nonce,
        state = state,
    )

    @Test
    fun `登记与结算：与内存实现同语义`() = runBlocking {
        val archive = FileRunArchive(dir)
        val link = EngineRunLink(intentRunId = 7, engineRunId = 100)
        archive.put(run(100, RunState.RUNNING), link)
        archive.put(run(100, RunState.SUCCEEDED), link)
        assertEquals(RunState.SUCCEEDED, archive.record(100)!!.state)
        assertEquals(link, archive.link(100))
        assertEquals(listOf(run(100, RunState.SUCCEEDED)), archive.recordsOfIntent(7))
        archive.close()
    }

    @Test
    fun `records 回全部项目全部状态，重启回放后仍在`() = runBlocking {
        val first = FileRunArchive(dir)
        first.put(run(30, RunState.SUCCEEDED).copy(projectId = "other"))
        first.put(run(10, RunState.RUNNING))
        first.put(run(20, RunState.FAILED))
        assertEquals(listOf(10L, 20L, 30L), first.records().map { it.id })
        first.close()

        val second = FileRunArchive(dir)
        assertEquals(listOf(10L, 20L, 30L), second.records().map { it.id })
        assertEquals(setOf("p1", "other"), second.records().map { it.projectId }.toSet())
        second.close()
        Unit
    }

    @Test
    fun `终态不可改写不可复活，点名拒绝`() = runBlocking {
        val archive = FileRunArchive(dir)
        archive.put(run(1, RunState.SUCCEEDED), EngineRunLink(1, 1))
        val ex = assertThrows(IllegalStateException::class.java) {
            runBlocking { archive.put(run(1, RunState.RUNNING), null) }
        }
        assertTrue(ex.message!!.contains("复活"))
        val ex2 = assertThrows(IllegalStateException::class.java) {
            runBlocking { archive.put(run(1, RunState.FAILED), null) }
        }
        assertTrue(ex2.message!!.contains("不可改写"))
        assertEquals(RunState.SUCCEEDED, archive.record(1)!!.state)
        archive.close()
    }

    @Test
    fun `关联一旦成立不允许改写`() = runBlocking {
        val archive = FileRunArchive(dir)
        archive.put(run(5, RunState.RUNNING), EngineRunLink(10, 5))
        val ex = assertThrows(IllegalStateException::class.java) {
            runBlocking { archive.put(run(5, RunState.SUCCEEDED), EngineRunLink(11, 5)) }
        }
        assertTrue(ex.message!!.contains("关联已成立"))
        assertEquals(EngineRunLink(10, 5), archive.link(5))
        archive.close()
    }

    @Test
    fun `落盘后新实例 replay 找回记录与关联`() = runBlocking {
        val first = FileRunArchive(dir)
        first.put(run(100, RunState.RUNNING), EngineRunLink(7, 100))
        first.put(run(101, RunState.SUCCEEDED), EngineRunLink(7, 101))
        first.put(run(102, RunState.RUNNING), null)
        first.close()

        val second = FileRunArchive(dir)
        assertEquals(RunState.RUNNING, second.record(100)!!.state)
        assertEquals(RunState.SUCCEEDED, second.record(101)!!.state)
        assertEquals(EngineRunLink(7, 100), second.link(100))
        assertNull(second.link(102), "无 link 记录 replay 后仍无 link")
        assertEquals(listOf(100L, 101L), second.recordsOfIntent(7).map { it.id })
        assertEquals(3, second.recordsOfProject("p1").size)
        assertEquals(listOf(100L, 102L), second.unfinished().map { it.id })
        second.close()
    }

    @Test
    fun `replay 容忍最后半行`() = runBlocking {
        val archive = FileRunArchive(dir)
        archive.put(run(1, RunState.SUCCEEDED), EngineRunLink(1, 1))
        archive.close()
        // 模拟崩溃写中断：追加半行（无换行、无闭合）
        Files.write(
            dir.resolve("run-archive.jsonl"),
            """{"op":"put","id":2,"projectId":""".toByteArray(),
            java.nio.file.StandardOpenOption.APPEND,
        )
        val second = FileRunArchive(dir)
        assertNull(second.record(2), "半行被丢弃")
        assertEquals(RunState.SUCCEEDED, second.record(1)!!.state, "半行前完整行不受影响")
        second.close()
    }

    /** 外审 B3 半（2026-10-11）：半行清盘 + 新写不被粘连，闭环同 FileTaskStore 那条。 */
    @Test
    fun `半行截断后新写入不被粘连：重开仍可读（外审 B3 半）`() = runBlocking {
        FileRunArchive(dir).use { archive -> archive.put(run(1, RunState.SUCCEEDED), EngineRunLink(1, 1)) }
        Files.write(
            dir.resolve("run-archive.jsonl"),
            """{"op":"put","id":2,"projectId":""".toByteArray(),
            java.nio.file.StandardOpenOption.APPEND,
        )
        FileRunArchive(dir).use { archive ->
            assertEquals(listOf(1L), archive.records().map { it.id })
            archive.put(run(2, RunState.RUNNING), EngineRunLink(2, 2))
        }
        FileRunArchive(dir).use { archive ->
            assertEquals(
                listOf(1L, 2L), archive.records().map { it.id },
                "半行必须已在打开时截掉：否则新行与残片粘成一行，重放直接抛 IOException",
            )
        }
    }

    @Test
    fun `特殊字符往返：引号与换行`() = runBlocking {
        val archive = FileRunArchive(dir)
        val tricky = RunRecord(
            id = 9, projectId = "p\"1", scriptPath = "a\nb.js",
            runNonce = "n\\1", state = RunState.RUNNING,
        )
        archive.put(tricky, EngineRunLink(3, 9))
        archive.close()
        val second = FileRunArchive(dir)
        assertEquals(tricky, second.record(9))
        assertEquals(EngineRunLink(3, 9), second.link(9))
        second.close()
    }

    @Test
    fun `B11 诊断字段往返：退出码与崩溃摘要落盘后可 replay`() = runBlocking {
        val archive = FileRunArchive(dir)
        val crashed = RunRecord(
            id = 77, projectId = "p1", scriptPath = "a.js", runNonce = "n77",
            state = RunState.CRASHED,
            startedAtMillis = 10, finishedAtMillis = 20,
            exitCode = 3,
            crashSummary = "TypeError: x is not a function\n    at a.js:1",
        )
        archive.put(crashed, EngineRunLink(7, 77))
        archive.close()

        val second = FileRunArchive(dir)
        assertEquals(crashed, second.record(77), "两个诊断字段随记录往返（含换行的摘要不被 JSON 转义破坏）")
        assertEquals(3, second.record(77)!!.exitCode)
        second.close()
    }

    @Test
    fun `B11 向后兼容：旧行（无诊断字段）replay 不炸且两字段为 null`() = runBlocking {
        // 旧版本写的行：整行没有 exitCode / crashSummary 两个键 —— 必须仍能 parse（append 式新增）
        Files.write(
            dir.resolve("run-archive.jsonl"),
            (
                """{"op":"put","id":5,"projectId":"p1","scriptPath":"a.js","runNonce":"n5",""" +
                    """"state":"CRASHED","startedAt":1,"finishedAt":2,"intentRunId":3}""" + "\n"
                ).toByteArray(),
        )
        val archive = FileRunArchive(dir)
        val rec = archive.record(5)!!
        assertEquals(RunState.CRASHED, rec.state, "旧行照常解析")
        assertNull(rec.exitCode, "旧行没有退出码 → 如实 null（不是 0）")
        assertNull(rec.crashSummary, "旧行没有摘要 → 如实 null")
        assertEquals(EngineRunLink(3, 5), archive.link(5))
        archive.close()
    }

    @Test
    fun `裁定失败不落盘：拒绝写入后 replay 无残留`() = runBlocking {
        val archive = FileRunArchive(dir)
        archive.put(run(1, RunState.SUCCEEDED), EngineRunLink(1, 1))
        assertThrows(IllegalStateException::class.java) {
            runBlocking { archive.put(run(1, RunState.FAILED), null) }
        }
        archive.close()
        val second = FileRunArchive(dir)
        assertEquals(RunState.SUCCEEDED, second.record(1)!!.state, "拒绝的写入不得留痕")
        second.close()
    }
}

/**
 * §8.5 契约套件打在 [FileRunArchive]（jsonl）上 —— 与 `InMemoryRunArchive`、
 * `SqliteRunArchive` 跑的是**同一组用例**。
 *
 * 本类保留自己原有的用例（它们额外钉着「jsonl 独有的机制」：半行容忍、截断清盘、
 * 旧行向后兼容 —— 那些换存储引擎后就由 SQLite 的事务性取代，不再是契约）。
 */
class FileRunArchiveContractTest : RunArchivePersistenceContract() {

    @TempDir
    lateinit var dir: Path

    /** 每次 [open] 一个新实例（契约要求）—— 指向同一个目录，等价于「重启后重开」。 */
    override fun open(): RunArchive = FileRunArchive(dir)
}
