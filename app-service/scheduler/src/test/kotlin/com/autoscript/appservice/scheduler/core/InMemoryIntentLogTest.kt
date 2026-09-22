package com.autoscript.appservice.scheduler.core

import com.autoscript.domain.core.Clock
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InMemoryIntentLogTest {

    private fun log(now: () -> Long = { 1000L }) =
        InMemoryIntentLog(Clock(now))

    @Test
    fun `appendStart 分配单调 runId 且状态未 COMMIT`() = runBlocking {
        val log = log()
        val a = log.appendStart("p", "a.js", "nonce-1", TriggerSource.TIMED, 5000)
        val b = log.appendStart("p", "b.js", "nonce-2", TriggerSource.USER_CLICK, 6000)
        assertTrue(a.runId < b.runId, "runId 必须单调递增")
        assertNull(a.outcome)
        assertEquals(listOf(a, b), log.all())
        assertEquals(listOf(a, b), log.uncommitted())
    }

    @Test
    fun `commit 封口后不再出现在 uncommitted`() = runBlocking {
        val log = log()
        val a = log.appendStart("p", "a.js", "nonce-1", TriggerSource.TIMED, 5000)
        val b = log.appendStart("p", "b.js", "nonce-2", TriggerSource.INTENT_BROADCAST, 6000)

        val committed = log.commit(a.runId, RunOutcome.Succeeded)
        assertEquals(RunOutcome.Succeeded, committed?.outcome)
        assertEquals(listOf(b), log.uncommitted(), "已 COMMIT 的不再是未完成意向")
        assertTrue(log.isCommitted("nonce-1"))
        assertTrue(!log.isCommitted("nonce-2"))
    }

    @Test
    fun `重复 commit 幂等且不覆盖结果`() = runBlocking {
        val log = log()
        val a = log.appendStart("p", "a.js", "nonce-1", TriggerSource.EVENT, 5000)
        log.commit(a.runId, RunOutcome.Succeeded)
        val again = log.commit(a.runId, RunOutcome.Failed)
        assertEquals(RunOutcome.Succeeded, again?.outcome, "首次 COMMIT 结果不可被二次覆盖")
        assertTrue(!log.uncommitted().any { it.runId == a.runId })
    }

    @Test
    fun `崩溃恢复用 Interrupted 封旧行后仍可再连新行`() = runBlocking {
        val log = log()
        val interrupted = log.appendStart("p", "a.js", "nonce-x", TriggerSource.TIMED, 5000)
        log.commit(interrupted.runId, RunOutcome.Interrupted)

        val fresh = log.appendStart("p", "a.js", "nonce-x", TriggerSource.TIMED, 5000)
        assertEquals(interrupted.runId + 1, fresh.runId, "恢复分配新 runId，保留 runNonce")
        log.commit(fresh.runId, RunOutcome.Succeeded)

        assertTrue(
            log.uncommitted().none { it.runId == interrupted.runId || it.runId == fresh.runId },
            "两条都封口后无未完成意向",
        )
    }

    @Test
    fun `reopen 单事务封旧重开且未完成意向不消失`() = runBlocking {
        val log = log()
        val old = log.appendStart("p", "a.js", "nonce-y", TriggerSource.TIMED, 5000, ScreenGuarantee.SCREEN_ON)

        // reopen 是单次调用（评审 S6）：封口 Interrupted + 新 runId 重开，保留 nonce 与 screen
        val fresh = log.reopen(old.runId)

        assertEquals(old.runId + 1, fresh.runId, "重开分配新 runId")
        assertEquals("nonce-y", fresh.runNonce, "保留原 runNonce（§8.5 幂等锚点）")
        assertEquals(ScreenGuarantee.SCREEN_ON, fresh.screen, "恢复重投不得丢失屏幕契约")
        assertEquals(old.deadlineMillis, fresh.deadlineMillis, "恢复重投不得变期限（§8.6：同一意向一套到期口径）")

        // 旧行已封口为 Interrupted（可追溯），新行是未完成意向（可继续派发）
        val sealedOld = log.all().single { it.runId == old.runId }
        assertEquals(RunOutcome.Interrupted, sealedOld.outcome, "旧行已封口")
        assertEquals(listOf(fresh.runId), log.uncommitted().map { it.runId }, "只有新行待重投")

        // 崩溃窗口（reclaim+append 间隙）语义：封口已完成，无「丢失一行」的中间态
        assertTrue(log.isCommitted("nonce-y") == false, "Interrupted 不计入幂等集合——同一 nonce 还要重投")
    }

    @Test
    fun `appendStart 落行带 deadline，恢复据此判过期`() = runBlocking {
        val log = log()
        // 显式期限进了日志行（§8.6）：崩溃恢复读的是这一行，不是内存里的某个表
        val a = log.appendStart(
            "p", "a.js", "nonce-dl", TriggerSource.TIMED, 5000, ScreenGuarantee.ANY, deadlineMillis = 9_000,
        )
        assertEquals(9_000L, a.deadlineMillis)
        assertTrue(log.all().single { it.runId == a.runId }.deadlineMillis == 9_000L, "期限随行落档")

        // 不传 = 无期限（老路径/直投）：恢复路径不得因此把它判死
        val b = log.appendStart("p", "b.js", "nonce-nodl", TriggerSource.USER_CLICK, 6000)
        assertNull(b.deadlineMillis)
    }

    @Test
    fun `reopen 对不存在与已 COMMIT 的 runId 拒绝`() = runBlocking {
        val log = log()
        val a = log.appendStart("p", "a.js", "nonce-z", TriggerSource.TIMED, 5000)
        log.commit(a.runId, RunOutcome.Succeeded)

        assertThrows(IllegalArgumentException::class.java) { runBlocking { log.reopen(9999L) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { log.reopen(a.runId) } }
        Unit                                           // 显式收尾：void 返回值才被 JUnit5 视为测试
    }

    @Test
    fun `appendStart 原子拒绝同一 nonce 的重复 STARTED 与已 COMMIT 副作用`() = runBlocking {
        val log = log()
        log.appendStart("p", "a.js", "nonce-dup", TriggerSource.TIMED, 5000)
        // 同名 nonce 的第二个 STARTED 行：拒绝（评审 S3 原子兜底，非 isCommitted 竞态）
        assertThrows(IllegalStateException::class.java) {
            runBlocking { log.appendStart("p", "a.js", "nonce-dup", TriggerSource.TIMED, 5000) }
        }

        val other = log.appendStart("p", "b.js", "nonce-done", TriggerSource.TIMED, 5000)
        log.commit(other.runId, RunOutcome.Succeeded)
        // 副作用已 COMMIT 的 nonce：任何新 STARTED 行都拒绝（真幂等）
        assertThrows(IllegalStateException::class.java) {
            runBlocking { log.appendStart("p", "c.js", "nonce-done", TriggerSource.TIMED, 5000) }
        }
        Unit                                           // 显式收尾：void 返回值才被 JUnit5 视为测试
    }

    @Test
    fun `screen 契约随行记录并随 all 可见`() = runBlocking {
        val log = log()
        val run = log.appendStart("p", "a.js", "nonce-s", TriggerSource.TIMED, 5000, ScreenGuarantee.SCREEN_OFF)
        assertEquals(ScreenGuarantee.SCREEN_OFF, log.all().single().screen)
        assertEquals(ScreenGuarantee.SCREEN_OFF, log.uncommitted().single().screen)
    }

    @Test
    fun `reopen 保留 args 与 timeoutMillis（恢复不丢执行载荷）`() = runBlocking {
        val log = log()
        val payload = listOf("--fast", "带 空格\"引号")
        val old = log.appendStart(
            "p", "a.js", "nonce-payload", TriggerSource.TIMED, 5000,
            args = payload, timeoutMillis = 30_000,
        )
        val fresh = log.reopen(old.runId)
        assertEquals(payload, fresh.args, "恢复重投不得丢脚本参数（§8.5）")
        assertEquals(30_000L, fresh.timeoutMillis, "恢复重投不得丢脚本超时（§8.5）")
        assertEquals(payload, log.uncommitted().single().args, "新存活行携带执行载荷")
    }
}
