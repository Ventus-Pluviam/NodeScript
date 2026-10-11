package com.autoscript.appservice.scheduler.core

import com.autoscript.domain.scripts.EngineRunLink
import com.autoscript.domain.scripts.RunArchive
import com.autoscript.domain.scripts.RunArchiveContract
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
    fun `records 回全部项目全部状态，按 engineRunId 升序`() = runBlocking {
        val archive = InMemoryRunArchive()
        archive.put(run(30, RunState.SUCCEEDED).copy(projectId = "other"))
        archive.put(run(10, RunState.RUNNING))
        archive.put(run(20, RunState.FAILED))

        assertEquals(listOf(10L, 20L, 30L), archive.records().map { it.id })
        assertEquals(setOf("p1", "other"), archive.records().map { it.projectId }.toSet())
        assertTrue(InMemoryRunArchive().records().isEmpty(), "空档案回空列表")
        Unit
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

/**
 * §8.5 契约套件打在 [InMemoryRunArchive] 上 —— 与 `FileRunArchive`（jsonl）、
 * `SqliteRunArchive`（`:platform:system`）跑的是**同一组用例**。
 *
 * 本类保留自己原有的用例（它们额外钉着「内存实现零 IO」这一面），本套件钉的是
 * **跨实现不许漂**的那部分（状态机纪律 + 读口语义）。
 *
 * 它**不**继承 [RunArchivePersistenceContract]：零 IO 形态重开即清零是它的语义本身
 * （见该套件 KDoc「为什么按能力切」），不是缺陷 —— 那一套只由两个持久实现继承。
 */
class InMemoryRunArchiveContractTest : RunArchiveContract() {
    /**
     * 内存实现**每次都是一个全新空档案** —— 它没有「指向同一份底层数据」的载体，
     * 重开即清零是它的**语义本身**（零 IO、无持久）。
     *
     * 所以三条依赖「重开仍读得回」的用例（持久性 / 裁定失败无残留 / 特殊字段往返）
     * 在这个实现上**不成立** —— 不是它违反契约，而是那些判据量的是**持久性**，
     * 而 [RunArchive] 契约对此没有要求（生产用的两个实现都是持久的）。
     *
     * 契约套件因此把这三条**从本实现上跳过并说明**；它们由 `FileRunArchive` 与
     * `SqliteRunArchive` 两个持久实现各跑一遍（那才是「换存储引擎不许漂」的真实战场）。
     * 这个「跳过」是**带理由的显式豁免**，不是 `assumeTrue` 环境门禁那种「探不到就跳过」。
     */
    override fun open(): RunArchive = InMemoryRunArchive()
}
