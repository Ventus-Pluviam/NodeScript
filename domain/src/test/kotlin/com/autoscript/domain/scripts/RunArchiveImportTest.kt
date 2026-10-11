package com.autoscript.domain.scripts

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * [RunArchiveImport] 的解析判据（§8.5 迁移面）。
 *
 * 喂的是真 jsonl 文本（手写，与 `FileRunArchive.ArchiveCodec.put` 逐字同形）。三条重点：
 * **带原 id**（迁移硬要求）、**收敛口径**（终态优先 / 关联首个胜）、**容忍度**
 * （尾部半行丢弃 / 行内损坏响亮）。
 */
class RunArchiveImportTest {

    private fun putLine(
        id: Long = 1L,
        state: String = "SUCCEEDED",
        intentRunId: Long? = null,
        extra: String = "",
    ) = """{"op":"put","id":$id,"projectId":"p1","scriptPath":"a.js","runNonce":"n$id",""" +
        """"state":"$state","startedAt":1,"finishedAt":2,"intentRunId":${intentRunId ?: "null"}""" +
        extra + "}"

    @Test
    fun `put 行往返 原 id 保留 关联带出`() {
        val rows = RunArchiveImport.parse(putLine(id = 7, intentRunId = 3) + "\n")
        val row = rows.single()
        assertEquals(7L, row.record.id, "**必须带原 engineRunId**（不带就同号异义，recordsOfIntent 静默串档）")
        assertEquals(3L, row.intentRunId)
        assertEquals(RunState.SUCCEEDED, row.record.state)
    }

    @Test
    fun `无关联的行 intentRunId 为 null`() {
        val row = RunArchiveImport.parse(putLine() + "\n").single()
        assertNull(row.intentRunId, "无 link 的行导入后仍无 link")
    }

    @Test
    fun `诊断字段往返（B11）`() {
        // JSON 里的换行是**转义**的 `\n`（真换行会破行 —— jsonl 按行分）
        val line = putLine(id = 77, state = "CRASHED", extra = ""","exitCode":3,"crashSummary":"T\nx"""")
        val rec = RunArchiveImport.parse(line + "\n").single().record
        assertEquals(3, rec.exitCode)
        assertEquals("T" + '\n' + "x", rec.crashSummary, "转义换行解回真换行，逐字往返")
    }

    @Test
    fun `旧行缺诊断字段时为 null 不是 0`() {
        val line = """{"op":"put","id":5,"projectId":"p1","scriptPath":"a.js","runNonce":"n5",""" +
            """"state":"CRASHED","startedAt":1,"finishedAt":2,"intentRunId":3}"""
        val rec = RunArchiveImport.parse(line + "\n").single().record
        assertNull(rec.exitCode, "旧行没有退出码 → 如实 null（不是 0）")
        assertNull(rec.crashSummary)
    }

    @Test
    fun `同 id 多次 put 后者胜`() {
        val text = putLine(id = 1, state = "RUNNING") + "\n" + putLine(id = 1, state = "SUCCEEDED") + "\n"
        assertEquals(RunState.SUCCEEDED, RunArchiveImport.parse(text).single().record.state)
    }

    @Test
    fun `终态后又出现非终态行取终态（崩溃在裁定后落盘前的重复写）`() {
        val text = putLine(id = 1, state = "SUCCEEDED") + "\n" + putLine(id = 1, state = "RUNNING") + "\n"
        assertEquals(
            RunState.SUCCEEDED, RunArchiveImport.parse(text).single().record.state,
            "终态不可复活（重复写要取终态）",
        )
    }

    @Test
    fun `关联首个胜（一旦成立不改写）`() {
        val text = putLine(id = 1, intentRunId = 10) + "\n" + putLine(id = 1, intentRunId = 99) + "\n"
        assertEquals(10L, RunArchiveImport.parse(text).single().intentRunId, "首个 link 胜（与 replay 同向）")
    }

    @Test
    fun `尾部半行丢弃`() {
        val text = putLine(id = 1) + "\n" + """{"op":"put","id":2,"projectId":"""
        assertEquals(listOf(1L), RunArchiveImport.parse(text).map { it.record.id })
    }

    @Test
    fun `行内损坏响亮失败`() {
        assertThrows(IllegalArgumentException::class.java) {
            RunArchiveImport.parse("""{"op":"put","id":1}""" + "\n")
        }
        assertThrows(IllegalArgumentException::class.java) {
            RunArchiveImport.parse(putLine(id = 1, state = "NOPE") + "\n")
        }
        assertThrows(IllegalArgumentException::class.java) {
            RunArchiveImport.parse("""{"op":"nope","id":1}""" + "\n")
        }
    }
}
