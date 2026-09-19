package com.autoscript.appservice.scriptrepo.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class AtomicDeployerTest {

    private fun deployer(tmp: Path): Pair<AtomicDeployer, DeployJournal> {
        val journal = DeployJournal(tmp.resolve("deploy.journal"))
        return AtomicDeployer(tmp, journal) to journal
    }

    @Test
    fun `部署后就位且 journal 落定无残留`(@TempDir tmp: Path) {
        val (d, j) = deployer(tmp)
        val staged = d.stage("main.js", "console.log(1)".toByteArray())

        assertFalse(Files.exists(staged.target), "finalize 前不得出现半截目标文件")

        d.finalize(staged)
        val target = tmp.resolve("main.js")
        assertTrue(Files.isRegularFile(target), "目标文件应就位")
        assertEquals("console.log(1)", String(Files.readAllBytes(target), StandardCharsets.UTF_8))
        assertTrue(j.unfinished().isEmpty())
        assertFalse(Files.exists(tmp.resolve(".stage")), "stage 目录应清空")
    }

    @Test
    fun `篡改 stage 后 finalize 拒绝且不落盘`(@TempDir tmp: Path) {
        val (d, j) = deployer(tmp)
        val staged = d.stage("main.js", "origin".toByteArray())
        Files.write(staged.stageFile, "tampered".toByteArray()) // 磁盘哈希与登记不符
        assertThrows(IllegalArgumentException::class.java) { d.finalize(staged) }
        assertFalse(Files.exists(staged.target), "哈希不符绝不写目标")
        d.abort(staged)
        assertTrue(j.unfinished().isEmpty())
    }

    @Test
    fun `abort 清理 stage 与 journal`(@TempDir tmp: Path) {
        val (d, j) = deployer(tmp)
        val staged = d.stage("a.js", "x".toByteArray())
        d.abort(staged)
        assertFalse(Files.exists(staged.target))
        assertFalse(Files.exists(staged.stageFile))
        assertTrue(j.unfinished().isEmpty())
    }

    @Test
    fun `崩溃恢复-目标未就位则回滚`(@TempDir tmp: Path) {
        val (d, j) = deployer(tmp)
        val staged = d.stage("b.js", "data".toByteArray())
        // 模拟 finalize 前崩溃：target 从未出现
        assertEquals(1, d.recover())
        assertFalse(Files.exists(tmp.resolve("b.js")))
        assertTrue(j.unfinished().isEmpty())
        assertEquals(DeployJournal.State.ROLLED_BACK, j.all().last().state)
        assertFalse(Files.exists(staged.stageFile))
        assertFalse(Files.exists(tmp.resolve(".stage")))
    }

    @Test
    fun `崩溃恢复-rename 已发生则补 journal`(@TempDir tmp: Path) {
        val journal = DeployJournal(tmp.resolve("deploy.journal"))
        val d = AtomicDeployer(tmp, journal)
        val bytes = "done".toByteArray()
        // 模拟 finalize 半途崩溃：rename 成功、目标就位，但 journal 仍停留在 STAGED
        journal.begin("crash-nonce", "c.js", DeployPath.sha256(bytes))
        Files.write(tmp.resolve("c.js"), bytes)

        assertEquals(0, d.recover())
        assertTrue(journal.unfinished().isEmpty())
        assertEquals(2, journal.all().size) // STAGED + 补写的 COMMITTED
        assertEquals("done", String(Files.readAllBytes(tmp.resolve("c.js")), StandardCharsets.UTF_8))
        // 恢复幂等：再跑一次无事发生
        assertEquals(0, d.recover())
    }

    @Test
    fun `无未完成记录时恢复返回 0`(@TempDir tmp: Path) {
        val (d, _) = deployer(tmp)
        assertEquals(0, d.recover())
    }
}