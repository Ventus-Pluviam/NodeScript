package com.autoscript.appservice.scriptrepo.core

import com.autoscript.domain.scripts.ProjectId
import com.autoscript.domain.scripts.RelPath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** 门面集成：store + deployer + index 一体的部署/恢复闭环。 */
class ProjectDeployerTest {

    private fun src(vararg entries: Pair<String, ByteArray>): Map<RelPath, ByteArray> =
        entries.associate { RelPath(it.first) to it.second }

    @Test
    fun `部署多文件后索引可见且 recovery 为空`(@TempDir tmp: Path) {
        val store = FsProjectStore(tmp)
        val deployer = ProjectDeployer(store)

        val n = deployer.deploy(ProjectId("demo"), src("main.js" to "run()".toByteArray(), "lib/util.js" to "u".toByteArray()))
        assertEquals(2, n)

        val root = store.rootFor(ProjectId("demo"))
        assertTrue(Files.isRegularFile(root.resolve("main.js")))
        assertTrue(Files.isRegularFile(root.resolve("lib/util.js")))
        assertEquals("run()", String(Files.readAllBytes(root.resolve("main.js")), StandardCharsets.UTF_8))
        assertEquals(0, deployer.recover(ProjectId("demo")), "正常部署后无待恢复记录")
        val proj = ProjectIndex(store).read(ProjectId("demo"))!!
        assertEquals("main.js", proj.mainScript)
    }

    @Test
    fun `非法相对路径部署直接拒绝且不留残留`(@TempDir tmp: Path) {
        val store = FsProjectStore(tmp)
        val deployer = ProjectDeployer(store)
        store.create(ProjectId("demo"))

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            deployer.deploy(ProjectId("demo"), src("../escape.js" to "x".toByteArray()))
        }
        // 项目目录保持干净：无半截文件、无 stage 残留
        Files.list(store.rootFor(ProjectId("demo"))).use { s -> assertEquals(0, s.count(), "项目目录应无残留") }
        assertEquals(0, deployer.recover(ProjectId("demo")))
    }

    @Test
    fun `组内任一文件 stage 失败 整组回滚 不 finalize 任何文件`(@TempDir tmp: Path) {
        val store = FsProjectStore(tmp)
        val deployer = ProjectDeployer(store)
        store.create(ProjectId("demo"))
        // 先部一版基线
        deployer.deploy(ProjectId("demo"), src("a.js" to "old-a".toByteArray(), "b.js" to "old-b".toByteArray()))

        // 第二版：b.js 路径非法 → 整组拒绝，a.js 不得被新版本替换（两阶段：全 stage 成才 finalize）
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            deployer.deploy(ProjectId("demo"), src("a.js" to "new-a".toByteArray(), "../b.js" to "evil".toByteArray()))
        }
        val root = store.rootFor(ProjectId("demo"))
        assertEquals("old-a", String(Files.readAllBytes(root.resolve("a.js")), StandardCharsets.UTF_8), "组原子性：失败组不得覆盖任何已就位文件")
        assertEquals("old-b", String(Files.readAllBytes(root.resolve("b.js")), StandardCharsets.UTF_8))
        assertEquals(0, deployer.recover(ProjectId("demo")), "失败组全部 abort，无遗留 STAGED 记录")
    }
}