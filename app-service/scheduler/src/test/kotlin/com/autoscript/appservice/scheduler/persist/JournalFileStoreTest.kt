package com.autoscript.appservice.scheduler.persist

import com.autoscript.appservice.scheduler.core.RunOutcome
import com.autoscript.appservice.scheduler.core.ScreenGuarantee
import com.autoscript.appservice.scheduler.core.TriggerSource
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * JournalFileStore + PersistentIntentLog 单测（§8.5）。
 * 语义锚点与 InMemoryIntentLogTest 对齐：同一套契约，两个实现。
 */
class JournalFileStoreTest {

    @TempDir
    lateinit var dir: Path

    private fun newLog() = PersistentIntentLog(JournalFileStore(dir))

    private suspend fun start(
        log: PersistentIntentLog,
        nonce: String,
        trigger: TriggerSource = TriggerSource.TIMED,
        screen: ScreenGuarantee = ScreenGuarantee.ANY,
    ) = log.appendStart("p", "a.js", nonce, trigger, 5000, screen, null)

    @Test
    fun `appendStart 分配单调 runId 且未 COMMIT`() = runBlocking {
        val log = newLog()
        val a = start(log, "n1")
        val b = start(log, "n2", TriggerSource.USER_CLICK)
        assertTrue(a.runId < b.runId)
        assertNull(a.outcome)
        assertEquals(listOf(a, b), log.uncommitted())
        log.close()
    }

    @Test
    fun `commit 幂等且封口后退出 uncommitted`() = runBlocking {
        val log = newLog()
        val a = start(log, "n1")
        val committed = log.commit(a.runId, RunOutcome.Succeeded)!!
        assertEquals(RunOutcome.Succeeded, committed.outcome)
        val again = log.commit(a.runId, RunOutcome.Failed)!!
        assertEquals(RunOutcome.Succeeded, again.outcome, "首次 COMMIT 结果不可被二次覆盖")
        assertTrue(log.uncommitted().isEmpty())
        assertTrue(log.isCommitted("n1"))
        log.close()
    }

    @Test
    fun `同 nonce 存活行拒绝直投`() = runBlocking {
        val log = newLog()
        start(log, "dup")
        assertThrows(IllegalStateException::class.java) {
            runBlocking { start(log, "dup") }
        }
        log.close()
    }

    @Test
    fun `同 nonce 已 COMMIT 拒绝直投（真副作用幂等锚点）`() = runBlocking {
        val log = newLog()
        val a = start(log, "done-nonce")
        log.commit(a.runId, RunOutcome.Succeeded)
        assertThrows(IllegalStateException::class.java) {
            runBlocking { start(log, "done-nonce") }
        }
        log.close()
    }

    @Test
    fun `reopen 封口旧行 Interrupted 且新行同 nonce 同 deadline`() = runBlocking {
        val log = newLog()
        val a = log.appendStart("p", "a.js", "rn", TriggerSource.EVENT, 5000, ScreenGuarantee.SCREEN_ON, 9999)
        val b = log.reopen(a.runId)
        assertNotEquals(a.runId, b.runId)
        assertTrue(b.runId > a.runId)
        assertEquals("rn", b.runNonce)
        assertEquals(ScreenGuarantee.SCREEN_ON, b.screen, "恢复重投不得丢失 screen 契约")
        assertEquals(9999, b.deadlineMillis, "恢复重投不得变期限")
        assertNull(b.outcome)
        val all = log.all()
        assertEquals(RunOutcome.Interrupted, all.first { it.runId == a.runId }.outcome)
        assertFalse(log.isCommitted("rn"), "Interrupted 封口不计入已 COMMIT nonce")
        assertEquals(listOf(b), log.uncommitted())
        log.close()
    }

    @Test
    fun `reopen 已 COMMIT 行拒绝`() = runBlocking {
        val log = newLog()
        val a = start(log, "x")
        log.commit(a.runId, RunOutcome.Succeeded)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { log.reopen(a.runId) }
        }
        log.close()
    }

    @Test
    fun `崩溃恢复：新实例 replay 后存活行可重投且 runId 不复用`() = runBlocking {
        val log1 = newLog()
        val a = start(log1, "crash-nonce")
        val b = start(log1, "done-nonce")
        log1.commit(b.runId, RunOutcome.Succeeded)
        log1.close()

        // 模拟进程重启：新实例从 jsonl replay
        val log2 = newLog()
        assertEquals(listOf(a.runId), log2.uncommitted().map { it.runId })
        assertTrue(log2.isCommitted("done-nonce"))

        // 崩溃恢复路径：reopen 存活行
        val fresh = log2.reopen(a.runId)
        assertTrue(fresh.runId > b.runId, "runId 崩溃后不复用")
        assertEquals("crash-nonce", fresh.runNonce)
        log2.close()
    }

    @Test
    fun `journal 容忍最后半行（写中断截断）`() = runBlocking {
        val log1 = newLog()
        val a = start(log1, "half")
        log1.close()
        // 人为追加半行（模拟崩溃截断）
        Files.write(dir.resolve("intent-log.jsonl"), ("""{"op":"seal","runId":""").toByteArray(), java.nio.file.StandardOpenOption.APPEND)

        val log2 = newLog()
        assertEquals(1, log2.uncommitted().size, "半行必须被丢弃，存活行仍是原样")
        assertEquals(a.runId, log2.uncommitted().first().runId)
        log2.close()
    }

    @Test
    fun `Crashed 终态携带 message 且计入已提交 nonce`() = runBlocking {
        val log = newLog()
        val a = start(log, "crash-msg")
        val sealed = log.commit(a.runId, RunOutcome.Crashed("OOM killed"))!!
        assertEquals(RunOutcome.Crashed("OOM killed"), sealed.outcome)
        assertTrue(log.isCommitted("crash-msg"))
        log.close()

        // replay 后 detail 保留
        val log2 = newLog()
        assertEquals(RunOutcome.Crashed("OOM killed"), log2.all().first().outcome)
        log2.close()
    }

    @Test
    fun `特殊字符（引号反斜杠换行）往返无损`() = runBlocking {
        val log = newLog()
        val nasty = "p\"\\1\n\t"
        val a = log.appendStart(nasty, "a\"b.js", "n\nonce", TriggerSource.TIMED, 1, ScreenGuarantee.ANY, null)
        log.commit(a.runId, RunOutcome.Crashed("boom \"\\\n"))
        log.close()

        val log2 = newLog()
        val row = log2.all().first()
        assertEquals(nasty, row.projectId)
        assertEquals("a\"b.js", row.scriptPath)
        assertEquals("n\nonce", row.runNonce)
        assertEquals(RunOutcome.Crashed("boom \"\\\n"), row.outcome)
        log2.close()
    }

    @Test
    fun `reopen 保留 args 与 timeoutMillis 且 journal 重放不丢`() = runBlocking {
        val payload = listOf("--fast", "值,含逗号", "带 \"引号\"")
        val log = newLog()
        val a = log.appendStart(
            "p", "a.js", "rn-payload", TriggerSource.TIMED, 5000, ScreenGuarantee.ANY, null,
            args = payload, timeoutMillis = 7_000,
        )
        assertEquals(payload, a.args, "落行即回读（jsonl 编解码往返）")
        assertEquals(7_000L, a.timeoutMillis)

        val b = log.reopen(a.runId)
        assertEquals(payload, b.args, "sealAndReopen 复制 StartRow：恢复不丢载荷")
        assertEquals(7_000L, b.timeoutMillis)
        log.close()

        // 重启（replay 自 journal 文件）字段仍在 —— 持久化面同样不丢
        val log2 = newLog()
        val reloaded = log2.all().first { it.runId == b.runId }
        assertEquals(payload, reloaded.args, "journal 重放后 args 仍在")
        assertEquals(7_000L, reloaded.timeoutMillis, "journal 重放后 timeoutMillis 仍在")
        log2.close()
    }

    @Test
    fun `老 journal 行缺 args 与 timeout 键按默认解析（升级兼容）`() = runBlocking {
        // 升级前落盘的 start 行：无 args / timeoutMillis 两键
        val legacy = """{"op":"start","runId":1,"projectId":"p","scriptPath":"a.js","runNonce":"old","trigger":"TIMED","screen":"ANY","scheduledAt":1,"startedAt":2,"deadlineAt":null}"""
        Files.write(dir.resolve("intent-log.jsonl"), (legacy + "\n").toByteArray())
        val log = newLog()
        val row = log.uncommitted().single()
        assertEquals(emptyList<String>(), row.args, "缺键 = 空参数（老任务没有参数）")
        assertEquals(null, row.timeoutMillis, "缺键 = 无超时")
        log.close()
    }
}

