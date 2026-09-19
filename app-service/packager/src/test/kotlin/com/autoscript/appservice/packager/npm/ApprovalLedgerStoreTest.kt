package com.autoscript.appservice.packager.npm

import com.autoscript.domain.npm.ApprovalAction
import com.autoscript.domain.npm.ApprovalDecision
import com.autoscript.domain.npm.ApprovalStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * 审批账本持久化单测（§10.2 approve-ledger）：submit/resolve 双写 + replay 恢复 +
 * id 不碰撞 + 半行容忍。与 JournalFileStoreTest 同款崩溃纪律断言。
 */
class ApprovalLedgerStoreTest {

    @TempDir
    lateinit var dir: Path

    private fun store() = FileApprovalStore(dir.resolve(".autojs"))

    @Test
    fun `submit resolve 往返写入并 fsync`() {
        val s = store()
        val l = ApprovalLedger(s)
        val t = l.submit("p1", "esbuild", "sha512-v1", ApprovalAction.INSTALL_SCRIPT)
        assertEquals(ApprovalStatus.PENDING, t.status)
        l.resolve(t.requestId, ApprovalDecision.APPROVE)
        val entries = s.all()
        assertEquals(1, entries.size)
        assertEquals(ApprovalStatus.APPROVED, entries[0].status)
        assertEquals("esbuild", entries[0].request.pkg)
    }

    @Test
    fun `重启 replay：ledger 重放恢复 APPROVED（同版本不重复审批）`() {
        val s = store()
        val t = ApprovalLedger(s).let { l ->
            l.submit("p1", "esbuild", "sha512-v1", ApprovalAction.RUN_SCRIPT).requestId.let { rid ->
                l.resolve(rid, ApprovalDecision.APPROVE)
            }
            l.submit("p1", "esbuild", "sha512-v1", ApprovalAction.RUN_SCRIPT)
        }
        // 重启：新 ledger 从 store replay
        val reborn = ApprovalLedger(s)
        assertTrue(reborn.isApproved("p1", "esbuild", "sha512-v1", ApprovalAction.RUN_SCRIPT),
            "重启后批准必须仍在——否则用户被重复打扰")
        assertEquals(1, reborn.pending("p1").size, "PENDING 票重放后仍在队列（UI 审计可见）")
        // 幂等键仍在：重放后同键 submit 合并复用旧票
        assertEquals(t.requestId, reborn.submit("p1", "esbuild", "sha512-v1", ApprovalAction.RUN_SCRIPT).requestId)
    }

    @Test
    fun `id 单调不碰撞（replay 后续发新票）`() {
        val s = store()
        ApprovalLedger(s).submit("p1", "a", "h", ApprovalAction.INSTALL_SCRIPT)
        val reborn = ApprovalLedger(s)
        val next = reborn.submit("p1", "b", "h", ApprovalAction.INSTALL_SCRIPT)
        assertEquals("apr-2", next.requestId, "replay 后 seq 必须续上")
    }

    @Test
    fun `REJECTED 历史保留可回放`() {
        val s = store()
        val l = ApprovalLedger(s)
        val rid = l.submit("p1", "evil", "h", ApprovalAction.INSTALL_SCRIPT).requestId
        l.resolve(rid, ApprovalDecision.REJECT)
        val entries = ApprovalLedger(s).all()
        assertTrue(entries.any { it.first.pkg == "evil" && it.second.status == ApprovalStatus.REJECTED },
            "REJECTED 也是审计历史，不可蒸发")
        assertEquals(ApprovalStatus.REJECTED, s.all().single().status, "store 侧终态同样保留")
    }

    @Test
    fun `半行容忍：截断尾行 replay 不抛`() {
        val s = store()
        ApprovalLedger(s).submit("p1", "ok", "h", ApprovalAction.INSTALL_SCRIPT)
        // 手工追加半行（模拟断电半截写）
        val ledgerFile = dir.resolve(".autojs/approve-ledger.jsonl")
        assertTrue(Files.exists(ledgerFile), "submit 后账本文件必须已落盘")
        Files.write(ledgerFile,
            "{\"op\":\"submit\",\"requestI".toByteArray(),
            java.nio.file.StandardOpenOption.APPEND)
        val entries = s.all()
        assertEquals(1, entries.size, "半行必须被容忍跳过（完整行仍可用）")
    }

    @Test
    fun `特殊字符 roundtrip（pkg 名含引号反斜杠不变）`() {
        val s = store()
        ApprovalLedger(s).submit("p1", "we\"ird\\pkg\nline", "h", ApprovalAction.INSTALL_SCRIPT)
        val back = s.all().single()
        assertEquals("we\"ird\\pkg\nline", back.request.pkg)
        assertFalse(back.request.pkg.isEmpty())
    }

    @Test
    fun `lastSeq 空文件为 0`() {
        assertEquals(0L, store().lastSeq())
        assertTrue(store().all().isEmpty())
    }
}
