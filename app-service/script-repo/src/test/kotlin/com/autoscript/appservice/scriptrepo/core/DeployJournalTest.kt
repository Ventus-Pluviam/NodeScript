package com.autoscript.appservice.scriptrepo.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class DeployJournalTest {

    @Test
    fun `begin 后 commit 则不再未完成`(@TempDir tmp: Path) {
        val j = DeployJournal(tmp.resolve("deploy.journal"))
        j.begin("n1", "main.js", "hash1")
        j.commit("n1", "main.js", "hash1")
        assertTrue(j.unfinished().isEmpty())
        assertEquals(2, j.all().size)
    }

    @Test
    fun `仅 begin 未落定视为未完成`(@TempDir tmp: Path) {
        val j = DeployJournal(tmp.resolve("deploy.journal"))
        j.begin("n1", "main.js", "hash1")
        val recs = j.unfinished()
        assertEquals(1, recs.size)
        assertEquals("main.js", recs.single().relPath)
    }

    @Test
    fun `rollback 撤销未完成`(@TempDir tmp: Path) {
        val j = DeployJournal(tmp.resolve("deploy.journal"))
        j.begin("n1", "a.js", "h")
        j.rollback("n1", "a.js", "h")
        assertTrue(j.unfinished().isEmpty())
        assertEquals(DeployJournal.State.ROLLED_BACK, j.all().last().state)
    }

    @Test
    fun `同一 nonce 历史 STAGED 行在 commit 后不算未完成`(@TempDir tmp: Path) {
        val j = DeployJournal(tmp.resolve("deploy.journal"))
        j.begin("n1", "a.js", "h")
        j.commit("n1", "a.js", "h")
        // 再次 begin 同 nonce（理论异常路径），恢复足够健壮
        j.begin("n1", "b.js", "h2")
        assertEquals(1, j.unfinished().size)
        assertEquals("b.js", j.unfinished().single().relPath)
    }

    @Test
    fun `特殊字符相对路径可往返`(@TempDir tmp: Path) {
        val j = DeployJournal(tmp.resolve("deploy.journal"))
        j.begin("n1", "sub/中文 名.js", "hash  with sep")
        val rec = j.unfinished().single()
        assertEquals("sub/中文 名.js", rec.relPath)
        assertEquals("hash  with sep", rec.sha256)
    }
}