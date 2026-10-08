package com.autoscript.domain.scripts

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * [IntentStore] 契约套件（§8.5）：**同一组用例打在每一个实现上**。
 *
 * 为什么要有它：§8.5 的存储引擎从 jsonl 换成 SQLite 是**替换**不是重写 ——
 * 「崩溃持久 / 幂等锚点原子 / runId 单调」三条不变量若在各实现间有任何一条不同，
 * 后果都不是"某个实现差一点"，而是"换引擎那天行为变了"（最坏形态：同一次投递
 * 跑两遍副作用）。把口径写成一份可执行的规格、每个实现各跑一遍，是唯一能在本机
 * 把这件事钉住的办法。
 *
 * 子类只需给一个 [open]：
 * - `InMemoryIntentStore`（本模块 `testFixtures`）—— 零 IO，**无环境门禁**，
 *   任何机器上都必须真跑（规格"今天确实被执行过"的那份证据）；
 * - `SqliteIntentStore`（`:platform:system`）—— SQLite + 部分唯一索引，
 *   要宿主 `sqlite3`，测试侧带 `assumeTrue` 门禁。
 *
 * **「崩溃」怎么模拟**：每次 [open] 都必须是**新实例**，而写入路径的契约是
 * 「返回前已落盘」—— 所以测试里直接把实例丢掉（不 close）就等于进程被杀。
 * 所有持久性断言都走「丢掉 → [open] 一个新实例 → 读回」这条路径，不读内存视图。
 */
abstract class IntentStoreContract {

    /**
     * 打开一个**新实例**（指向同一份底层数据）。每次调用都必须是新实例 ——
     * 崩溃重放与「重启后 runId 不复用」两条断言全靠它。
     */
    protected abstract fun open(): IntentStore

    protected fun start(
        nonce: String,
        projectId: String = "p",
        scriptPath: String = "a.js",
        trigger: String = "TIMED",
        screen: String = "ANY",
        scheduledAtMillis: Long = 5_000,
        startedAtMillis: Long = 6_000,
        deadlineMillis: Long? = null,
        args: List<String> = emptyList(),
        timeoutMillis: Long? = null,
    ): IntentStore.StartRow = IntentStore.StartRow(
        projectId = projectId,
        scriptPath = scriptPath,
        runNonce = nonce,
        trigger = trigger,
        screen = screen,
        scheduledAtMillis = scheduledAtMillis,
        startedAtMillis = startedAtMillis,
        deadlineMillis = deadlineMillis,
        args = args,
        timeoutMillis = timeoutMillis,
    )

    // ── 基本分配与封口 ──────────────────────────────────────────────

    @Test
    fun `insertStart 分配单调 runId 且行为存活`() {
        val s = open()
        val a = s.insertStart(start("n1"))
        val b = s.insertStart(start("n2"))
        assertTrue(a < b, "runId 必须单调递增：$a !< $b")
        val live = s.liveRows()
        assertEquals(listOf(a, b), live.map { it.runId }, "存活行按 runId 升序")
        assertTrue(live.all { it.outcome == null && it.committedAtMillis == null })
        assertEquals(listOf(a, b), s.allRows().map { it.runId })
        s.close()
    }

    @Test
    fun `空存储的 liveRows 与 allRows 皆空`() {
        val s = open()
        assertEquals(emptyList<Long>(), s.liveRows().map { it.runId })
        assertEquals(emptyList<Long>(), s.allRows().map { it.runId })
        assertFalse(s.hasCommittedNonce("nobody"))
        assertFalse(s.hasLiveNonce("nobody"))
        s.close()
    }

    @Test
    fun `seal 封口后退出存活集且二次封口幂等`() {
        val s = open()
        val a = s.insertStart(start("n1"))
        val sealed = s.seal(a, IntentStore.StoredOutcome.SUCCEEDED)!!
        assertEquals("SUCCEEDED", sealed.outcome?.name)
        assertTrue(sealed.committedAtMillis != null, "封口必须带时刻")
        assertEquals(emptyList<Long>(), s.liveRows().map { it.runId })
        assertTrue(s.hasCommittedNonce("n1"))

        // 幂等：第二次封口回**现态**，不覆盖首次结果（§8.5 append-only）
        val again = s.seal(a, IntentStore.StoredOutcome.FAILED)!!
        assertEquals("SUCCEEDED", again.outcome?.name, "首次终态不可被二次覆盖")
        assertEquals(sealed.committedAtMillis, again.committedAtMillis)
        s.close()
    }

    @Test
    fun `seal 未知 runId 回 null`() {
        val s = open()
        s.insertStart(start("n1"))
        assertNull(s.seal(999, IntentStore.StoredOutcome.SUCCEEDED), "不存在的 runId 不是错误，是 null")
        s.close()
    }

    // ── 幂等锚点（存储层原子拒绝，不是调用方预检）─────────────────────

    @Test
    fun `同 nonce 存活行拒绝直投`() {
        val s = open()
        s.insertStart(start("dup"))
        val e = assertThrows(IllegalStateException::class.java) { s.insertStart(start("dup")) }
        assertTrue(e.message!!.contains("dup"), "拒绝理由必须点名 nonce：${e.message}")
        assertEquals(1, s.allRows().size, "被拒的投递不留行")
        s.close()
    }

    @Test
    fun `同 nonce 已 COMMIT 拒绝直投（真副作用幂等锚点）`() {
        val s = open()
        val a = s.insertStart(start("done"))
        s.seal(a, IntentStore.StoredOutcome.SUCCEEDED)
        assertThrows(IllegalStateException::class.java) { s.insertStart(start("done")) }
        assertEquals(1, s.allRows().size)
        s.close()
    }

    @Test
    fun `INTERRUPTED 封口不计入已提交 nonce 且随后可再投`() {
        val s = open()
        val a = s.insertStart(start("rn"))
        s.seal(a, IntentStore.StoredOutcome.INTERRUPTED)
        assertFalse(s.hasCommittedNonce("rn"), "Interrupted 不构成一次完成的对外副作用")
        assertFalse(s.hasLiveNonce("rn"))
        val b = s.insertStart(start("rn"))
        assertTrue(b > a)
        assertTrue(s.hasLiveNonce("rn"))
        s.close()
    }

    @Test
    fun `并发同 nonce 直投恰好一个赢家（存储层原子拒绝）`() {
        val s = open()
        val threads = 6
        val ready = CountDownLatch(threads)
        val go = CountDownLatch(1)
        val ok = AtomicInteger()
        val rejected = AtomicInteger()
        val unexpected = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val workers = (1..threads).map {
            Thread {
                ready.countDown()
                go.await()
                try {
                    s.insertStart(start("race"))
                    ok.incrementAndGet()
                } catch (e: IllegalStateException) {
                    rejected.incrementAndGet()
                } catch (t: Throwable) {
                    unexpected += t
                }
            }
        }
        workers.forEach { it.start() }
        assertTrue(ready.await(10, TimeUnit.SECONDS), "线程未就绪（测试自身故障）")
        go.countDown()
        workers.forEach { it.join(30_000) }

        assertTrue(unexpected.isEmpty(), "只允许 IllegalStateException，实际：$unexpected")
        assertEquals(1, ok.get(), "同 nonce 只能有一个赢家")
        assertEquals(threads - 1, rejected.get())
        assertEquals(1, s.allRows().size, "被拒的投递一行都不许留")
        s.close()
    }

    // ── sealAndReopen（崩溃恢复的唯一合法重投路径）───────────────────

    @Test
    fun `sealAndReopen 封旧行 Interrupted 且新行同 nonce 载荷全保留`() {
        val s = open()
        val payload = listOf("--fast", "值,含逗号", "带 \"引号\"")
        val a = s.insertStart(
            start(
                "rn", projectId = "proj", scriptPath = "b.js", trigger = "EVENT",
                screen = "SCREEN_ON", scheduledAtMillis = 5_000, startedAtMillis = 6_000,
                deadlineMillis = 9_999, args = payload, timeoutMillis = 7_000,
            ),
        )
        val b = s.sealAndReopen(a)
        assertTrue(b > a, "重开必须分配新 runId")

        val all = s.allRows().associateBy { it.runId }
        assertEquals("INTERRUPTED", all.getValue(a).outcome?.name, "旧行封成 Interrupted")
        val fresh = all.getValue(b)
        assertNull(fresh.outcome, "新行存活")
        assertEquals("rn", fresh.start.runNonce)
        assertEquals("proj", fresh.start.projectId)
        assertEquals("b.js", fresh.start.scriptPath)
        assertEquals("EVENT", fresh.start.trigger)
        assertEquals("SCREEN_ON", fresh.start.screen, "恢复重投不得丢失 screen 契约")
        assertEquals(9_999L, fresh.start.deadlineMillis, "恢复重投不得变期限")
        assertEquals(5_000L, fresh.start.scheduledAtMillis, "排期时刻原样保留")
        assertEquals(payload, fresh.start.args, "恢复重投不得丢载荷")
        assertEquals(7_000L, fresh.start.timeoutMillis)
        assertTrue(fresh.start.startedAtMillis >= 6_000L, "新行的 startedAt 是这次重开的时刻")
        assertEquals(listOf(b), s.liveRows().map { it.runId })
        assertFalse(s.hasCommittedNonce("rn"))
        s.close()
    }

    @Test
    fun `sealAndReopen 已终态或未知 runId 拒绝`() {
        val s = open()
        val a = s.insertStart(start("x"))
        s.seal(a, IntentStore.StoredOutcome.SUCCEEDED)
        assertThrows(IllegalArgumentException::class.java) { s.sealAndReopen(a) }
        assertThrows(IllegalArgumentException::class.java) { s.sealAndReopen(999) }
        // 被拒的重开不留痕：既没多一行，也没把已终态行改掉
        assertEquals(listOf(a), s.allRows().map { it.runId })
        assertEquals("SUCCEEDED", s.allRows().single().outcome?.name)
        s.close()
    }

    // ── 崩溃持久与 replay 等价 ──────────────────────────────────────

    @Test
    fun `崩溃后重开：存活行可重投且 runId 不复用`() {
        val s1 = open()
        val a = s1.insertStart(start("crash-nonce"))
        val b = s1.insertStart(start("done-nonce"))
        s1.seal(b, IntentStore.StoredOutcome.SUCCEEDED)
        // 「崩溃」= 不 close，直接丢掉实例（写入路径已保证返回前落盘）

        val s2 = open()
        assertEquals(listOf(a), s2.liveRows().map { it.runId }, "replay 必须找回崩溃遗留")
        assertTrue(s2.hasCommittedNonce("done-nonce"), "已提交 nonce 表同样要重建")
        assertEquals(listOf(a, b), s2.allRows().map { it.runId })

        val fresh = s2.sealAndReopen(a)
        assertTrue(fresh > b, "runId 崩溃后不复用")
        assertEquals("crash-nonce", s2.allRows().first { it.runId == fresh }.start.runNonce)
        s2.close()
    }

    @Test
    fun `终态与诊断详情跨重开完好（Crashed 携带 message）`() {
        val s1 = open()
        val a = s1.insertStart(start("crash-msg"))
        s1.seal(a, IntentStore.StoredOutcome.crashed("OOM killed \"\\\n"))

        val s2 = open()
        val back = s2.allRows().single()
        assertEquals("CRASHED", back.outcome?.name)
        assertEquals("OOM killed \"\\\n", back.outcome?.detail, "detail 必须逐字回放")
        assertTrue(s2.hasCommittedNonce("crash-msg"))
        s2.close()
    }

    @Test
    fun `args 与 timeoutMillis 跨重开不丢（含多字节与特殊字符）`() {
        val huge = "参数据".repeat(2_000)
        val payload = listOf("--fast", "值,含逗号", "带 \"引号\"", huge, "行\n内换行", "制表\t符")
        val s1 = open()
        val a = s1.insertStart(
            start("n-payload", projectId = "p\"\\1\n\t", scriptPath = "a\"b.js", args = payload, timeoutMillis = 1_234),
        )
        s1.seal(a, IntentStore.StoredOutcome.SUCCEEDED)

        val s2 = open()
        val back = s2.allRows().single()
        assertEquals(payload, back.start.args, "载荷逐字回放")
        assertEquals(1_234L, back.start.timeoutMillis)
        assertEquals("p\"\\1\n\t", back.start.projectId)
        assertEquals("a\"b.js", back.start.scriptPath)
        assertEquals("n-payload", back.start.runNonce)
        // 重开后 runId 仍单调（不只是"读得回来"）
        val next = s2.insertStart(start("n-next"))
        assertTrue(next > a)
        s2.close()
    }

    @Test
    fun `多个存活行跨重开后全部可重投且各自独立`() {
        val s1 = open()
        val ids = (1..3).map { s1.insertStart(start("n$it")) }
        val s2 = open()
        assertEquals(ids, s2.liveRows().map { it.runId })
        val reopened = ids.map { s2.sealAndReopen(it) }
        assertEquals(3, reopened.toSet().size, "三次重开拿到三个不同的 runId")
        assertTrue(reopened.min() > ids.max(), "重开的 id 都在历史之后")
        assertEquals(reopened, s2.liveRows().map { it.runId }.sorted())
        s2.close()
    }

    @Test
    fun `hasLiveNonce 与 hasCommittedNonce 是两条独立判据`() {
        val s = open()
        val a = s.insertStart(start("both"))
        assertTrue(s.hasLiveNonce("both"))
        assertFalse(s.hasCommittedNonce("both"))
        s.seal(a, IntentStore.StoredOutcome.CANCELLED)
        assertFalse(s.hasLiveNonce("both"), "封口后不再存活")
        assertTrue(s.hasCommittedNonce("both"), "Cancelled 是真终态")
        assertNotEquals(s.hasLiveNonce("both"), s.hasCommittedNonce("both"))
        s.close()
    }

    @Test
    fun `封口时刻由存储层写，调用方给不了`() {
        val s = open()
        val a = s.insertStart(start("ts"))
        val before = System.currentTimeMillis()
        val sealed = s.seal(a, IntentStore.StoredOutcome.SUCCEEDED)!!
        assertTrue(sealed.committedAtMillis!! >= before, "committedAt 必须是封口那一刻")
        s.close()
    }
}
