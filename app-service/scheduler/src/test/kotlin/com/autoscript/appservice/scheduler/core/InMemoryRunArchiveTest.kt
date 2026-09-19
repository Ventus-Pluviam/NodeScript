package com.autoscript.appservice.scheduler.core

import com.autoscript.domain.scripts.EngineRunLink
import com.autoscript.domain.scripts.RunRecord
import com.autoscript.domain.scripts.RunState
import com.autoscript.domain.scripts.isTerminal
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InMemoryRunArchiveTest {

    @Test
    fun `登记与终态结算：记录可前进，终态后按 id 可取`() = runBlocking {
        val archive = InMemoryRunArchive()
        val link = EngineRunLink(intentRunId = 7, engineRunId = 100)

        archive.put(run(100, RunState.RUNNING), link)
        archive.put(run(100, RunState.SUCCEEDED), link)          // 结算：同一 link 前进状态

        assertEquals(RunState.SUCCEEDED, archive.record(100)!!.state)
        assertEquals(link, archive.link(100))
        assertEquals(listOf(run(100, RunState.SUCCEEDED)), archive.recordsOfIntent(7))
    }

    @Test
    fun `终态不可改写、不可复活`() = runBlocking {
        val archive = InMemoryRunArchive()
        archive.put(run(1, RunState.SUCCEEDED), EngineRunLink(1, 1))

        val ex = assertThrows(IllegalStateException::class.java) {
            runBlocking { archive.put(run(1, RunState.RUNNING), null) }
        }
        assertTrue(ex.message!!.contains("复活"), "复活须被点名拒绝: ${ex.message}")
        val ex2 = assertThrows(IllegalStateException::class.java) {
            runBlocking { archive.put(run(1, RunState.FAILED), null) }
        }
        assertTrue(ex2.message!!.contains("不可改写"), "改写须被点名拒绝: ${ex2.message}")
        // 旧记录保持原样
        assertEquals(RunState.SUCCEEDED, archive.record(1)!!.state)
    }

    @Test
    fun `双 id 关联一旦成立不允许改写`() = runBlocking {
        val archive = InMemoryRunArchive()
        archive.put(run(5, RunState.RUNNING), EngineRunLink(intentRunId = 10, engineRunId = 5))

        val ex = assertThrows(IllegalStateException::class.java) {
            runBlocking { archive.put(run(5, RunState.SUCCEEDED), EngineRunLink(intentRunId = 11, engineRunId = 5)) }
        }
        assertTrue(ex.message!!.contains("关联已成立"))
        assertEquals(EngineRunLink(10, 5), archive.link(5), "原关联保持")
    }

    @Test
    fun `link 与 record id 不一致一律拒绝`() = runBlocking {
        val archive = InMemoryRunArchive()
        val ex = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { archive.put(run(9, RunState.RUNNING), EngineRunLink(1, 2)) }
        }
        assertTrue(ex.message!!.contains("不一致"))
    }

    @Test
    fun `无 link 的记录不参与反查，也绝不进意图反查`() = runBlocking {
        val archive = InMemoryRunArchive()
        archive.put(run(3, RunState.RUNNING), null)
        assertNull(archive.link(3), "未关联即无 link")
        assertEquals(emptyList<RunRecord>(), archive.recordsOfIntent(0))
        assertEquals(1, archive.recordsOfProject("p1").size, "档案本身仍可查")
    }

    @Test
    fun `未终态记录可被 unfinished 观察到`() = runBlocking {
        val archive = InMemoryRunArchive()
        archive.put(run(1, RunState.PENDING), EngineRunLink(1, 1))
        archive.put(run(2, RunState.RUNNING), EngineRunLink(1, 2))
        archive.put(run(3, RunState.CRASHED), EngineRunLink(1, 3))

        assertEquals(listOf(1L, 2L), archive.unfinished().map { it.id })
    }

    @Test
    fun `同一 intent 的多次执行按 engineRunId 升序可追溯`() = runBlocking {
        val archive = InMemoryRunArchive()
        archive.put(run(200, RunState.RUNNING), EngineRunLink(42, 200))
        archive.put(run(100, RunState.RUNNING), EngineRunLink(42, 100))
        archive.put(run(300, RunState.RUNNING), EngineRunLink(42, 300))
        archive.put(run(400, RunState.RUNNING), EngineRunLink(43, 400))

        val mine = archive.recordsOfIntent(42)
        assertEquals(listOf(100L, 200L, 300L), mine.map { it.id })
        assertEquals(listOf(400L), archive.recordsOfIntent(43).map { it.id })
    }

    @Test
    // 反引号测试名不能含 `/`（JVM 反射限制）：这里说明「SUCCEEDED 到 CANCELLED」的枚举顺序。
    fun `四态终态口径：SUCCEEDED 到 CANCELLED 均为终态，PENDING 与 RUNNING 仍在途`() {
        assertTrue(RunState.SUCCEEDED.isTerminal)
        assertTrue(RunState.FAILED.isTerminal)
        assertTrue(RunState.CRASHED.isTerminal)
        assertTrue(RunState.CANCELLED.isTerminal)
        assertTrue(!RunState.PENDING.isTerminal)
        assertTrue(!RunState.RUNNING.isTerminal)
    }

    private fun run(id: Long, state: RunState) = RunRecord(
        id = id,
        projectId = "p1",
        scriptPath = "a.js",
        runNonce = "nonce-$id",
        state = state,
    )
}
