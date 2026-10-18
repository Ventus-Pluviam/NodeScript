package com.autoscript.domain.scripts

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [RunArchive] 契约套件（§8.5 归档）：**同一组用例打在每一个实现上**。
 *
 * 与 [IntentStoreContract] 同一架子：§8.5 的存储引擎从 jsonl 换成 SQLite（2026-10-11
 * 批 102 P4）是**替换**不是重写 —— 状态机纪律（终态不可改写/复活、关联一旦成立不改写、
 * 不一致拒绝）若在各实现间有任何一条不同，后果不是「某个实现差一点」，而是
 * 「换引擎那天任务中心的历史变了」。把口径写成一份可执行的规格、每个实现各跑一遍，
 * 是唯一能在本机把这件事钉住的办法。
 *
 * 三个实现各跑一遍（继承三次）：
 * - `InMemoryRunArchive`（`:app-service:scheduler` 的单测里）—— 零 IO；
 * - `FileRunArchive`（jsonl，本模块的既有实现）—— **它是「换引擎之前」的那份事实**；
 * - `SqliteRunArchive`（`:platform:system`）—— SQLite + 条件 UPDATE。
 *
 * **不在套件里的两类断言**（它们是各实现自己的机制，不是契约）：
 * - **崩溃重放**（丢掉实例 → 重开 → 读回）：靠「每次 [open] 都是新实例」这条 + 本套件的
 *   「登记 → 读回」用例隐含覆盖；jsonl 侧的半行容忍是它**独有的**机制，各实现自测；
 * - **迁移导入**（带原 id / 可重入）：那是 SQLite 独有的路径，在 `:platform:system` 自测。
 */
abstract class RunArchiveContract {

    /**
     * 打开一个**新实例**（指向同一份底层数据）。每次调用都必须是新实例 ——
     * 「重开」这一路的断言全靠它（旧实例直接丢掉即等价于进程被杀）。
     */
    protected abstract fun open(): RunArchive



    protected fun run(
        id: Long,
        state: RunState,
        nonce: String = "nonce-$id",
        projectId: String = "p1",
    ) = RunRecord(
        id = id,
        projectId = projectId,
        scriptPath = "a.js",
        runNonce = nonce,
        state = state,
    )

    @Test
    fun `登记与结算同语义`() = kotlinx.coroutines.runBlocking {
        val archive = open()
        val link = EngineRunLink(intentRunId = 7, engineRunId = 100)
        archive.put(run(100, RunState.RUNNING), link)
        archive.put(run(100, RunState.SUCCEEDED), link)
        assertEquals(RunState.SUCCEEDED, archive.record(100)!!.state)
        assertEquals(link, archive.link(100))
        assertEquals(listOf(run(100, RunState.SUCCEEDED)), archive.recordsOfIntent(7))
        Unit
    }

    @Test
    fun `records 回全部项目全部状态 按 engineRunId 升序`() = kotlinx.coroutines.runBlocking {
        val archive = open()
        archive.put(run(30, RunState.SUCCEEDED).copy(projectId = "other"))
        archive.put(run(10, RunState.RUNNING))
        archive.put(run(20, RunState.FAILED))
        assertEquals(listOf(10L, 20L, 30L), archive.records().map { it.id })
        assertEquals(setOf("p1", "other"), archive.records().map { it.projectId }.toSet())
        Unit
    }

    @Test
    fun `终态不可改写不可复活 点名拒绝`() = kotlinx.coroutines.runBlocking {
        val archive = open()
        archive.put(run(1, RunState.SUCCEEDED), EngineRunLink(1, 1))
        val revived = assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { archive.put(run(1, RunState.RUNNING), null) }
        }
        assertTrue(revived.message!!.contains("复活"), "「复活」要写在话术里：${revived.message}")
        val rewritten = assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { archive.put(run(1, RunState.FAILED), null) }
        }
        assertTrue(rewritten.message!!.contains("不可改写"), "「不可改写」要点名：${rewritten.message}")
        assertEquals(RunState.SUCCEEDED, archive.record(1)!!.state)
        Unit
    }

    @Test
    fun `关联一旦成立不允许改写`() = kotlinx.coroutines.runBlocking {
        val archive = open()
        archive.put(run(5, RunState.RUNNING), EngineRunLink(10, 5))
        val e = assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { archive.put(run(5, RunState.SUCCEEDED), EngineRunLink(11, 5)) }
        }
        assertTrue(e.message!!.contains("关联已成立"), "话术要点名：${e.message}")
        assertEquals(EngineRunLink(10, 5), archive.link(5))
        Unit
    }

    @Test
    fun `link 与 record id 不一致即拒`() = kotlinx.coroutines.runBlocking {
        val archive = open()
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { archive.put(run(9, RunState.RUNNING), EngineRunLink(1, 8)) }
        }
        Unit
    }

    @Test
    fun `未登记的行读回 null 关联也 null`() = kotlinx.coroutines.runBlocking {
        val archive = open()
        assertNull(archive.record(999))
        assertNull(archive.link(999))
        archive.put(run(102, RunState.RUNNING), null)
        assertNull(archive.link(102), "无 link 的记录 replay 后仍无 link")
        Unit
    }

    /**
     * 高水位（2026-10-14 真机 bug 的契约面）：`maxEngineRunId()` = 已落过的**最大 id**，
     * 空档回 `0`。它是引擎侧分配器跨进程的避让依据 —— 三个实现给出不同的数，
     * 就是"换存储引擎那天新进程重发旧号"。
     */
    @Test
    fun `高水位是已落过的最大 engineRunId 空档回零`() = kotlinx.coroutines.runBlocking {
        val archive = open()
        assertEquals(0L, archive.maxEngineRunId(), "空档案 = 从没跑过 → 0（不是 null、不抛）")
        archive.put(run(7, RunState.SUCCEEDED), EngineRunLink(1, 7))
        archive.put(run(3, RunState.RUNNING), null)
        assertEquals(7L, archive.maxEngineRunId(), "取最大 id，不是行数、不是最近写的那条")
        Unit
    }

    @Test
    fun `同一 runNonce 可有多条记录（重投不是 bug）`() = kotlinx.coroutines.runBlocking {
        // 档案**不是**幂等锚点（锚点在意图日志侧）：重投 → 两次执行 → 两条记录。
        // 档案若给它建唯一索引，这一条会变成「静默丢一次执行的历史」。
        val archive = open()
        archive.put(run(1, RunState.RUNNING, nonce = "same"), null)
        archive.put(run(2, RunState.SUCCEEDED, nonce = "same"), null)
        assertEquals(2, archive.records().size, "同一 nonce 的两条执行都要留档")
        Unit
    }
}

/**
 * [RunArchive] 的**持久性**那一半（§8.5）：**只由持久实现继承**。
 *
 * 为什么单独一套而不是并进 [RunArchiveContract]：[RunArchive] 契约**没有**要求持久
 * —— 零 IO 的 `InMemoryRunArchive`（JVM 单测用）重开即清零是它的**语义本身**，不是缺陷。
 * 把「重开仍读得回」塞进共享套件，逼得要么让那个实现假装持久、要么在套件里塞跳过 ——
 * 后者会撞 `autoscript.test-guard`（「skipped ≠ 绿」），而前者是把假话钉成门。
 *
 * 正确的切法是**按能力切**：状态机纪律 + 读口语义（三个实现都必须一致）留在
 * [RunArchiveContract]；持久性（两个生产实现必须一致）住本套件。
 * 「换存储引擎那天行为没变」的真实战场正是这两个实现 —— 它们各跑一遍本套件。
 */
abstract class RunArchivePersistenceContract : RunArchiveContract() {

    @Test
    fun `重开新实例仍读得回（持久性）`() = kotlinx.coroutines.runBlocking {
        val first = open()
        first.put(run(100, RunState.RUNNING), EngineRunLink(7, 100))
        first.put(run(101, RunState.SUCCEEDED), EngineRunLink(7, 101))

        val second = open()
        assertEquals(RunState.RUNNING, second.record(100)!!.state)
        assertEquals(RunState.SUCCEEDED, second.record(101)!!.state)
        assertEquals(EngineRunLink(7, 100), second.link(100))
        assertEquals(listOf(100L, 101L), second.recordsOfIntent(7).map { it.id })
        assertEquals(3, second.recordsOfProject("p1").size + 1)
        assertEquals(listOf(100L), second.unfinished().map { it.id })
        Unit
    }
    @Test
    fun `裁定失败不落盘：拒绝写入后重开无残留`() = kotlinx.coroutines.runBlocking {
        val archive = open()
        archive.put(run(1, RunState.SUCCEEDED), EngineRunLink(1, 1))
        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { archive.put(run(1, RunState.FAILED), null) }
        }
        val second = open()
        assertEquals(RunState.SUCCEEDED, second.record(1)!!.state, "拒绝的写入不得留痕")
        Unit
    }
    /**
     * 高水位必须**跨实例**成立 —— 这正是它存在的理由（新进程读的是上一进程留下的档案）。
     * 与 `重开新实例仍读得回` 同一条：只由持久实现继承。
     */
    @Test
    fun `重开后高水位不倒退`() = kotlinx.coroutines.runBlocking {
        open().put(run(42, RunState.CRASHED), EngineRunLink(5, 42))
        assertEquals(42L, open().maxEngineRunId(), "新进程必须读到上一进程用过的最大号")
        Unit
    }

    @Test
    fun `特殊字符与诊断字段往返`() = kotlinx.coroutines.runBlocking {
        val archive = open()
        val tricky = RunRecord(
            id = 9, projectId = "p\"1", scriptPath = "a\nb.js",
            runNonce = "n\\1", state = RunState.CRASHED,
            startedAtMillis = 10, finishedAtMillis = 20,
            exitCode = 3,
            crashSummary = "TypeError: x is not a function\n    at a.js:1",
        )
        archive.put(tricky, EngineRunLink(3, 9))
        val second = open()
        assertEquals(tricky, second.record(9), "含引号/换行/反斜杠的字段逐字往返")
        assertEquals(3, second.record(9)!!.exitCode)
        assertEquals(EngineRunLink(3, 9), second.link(9))
        Unit
    }

}
