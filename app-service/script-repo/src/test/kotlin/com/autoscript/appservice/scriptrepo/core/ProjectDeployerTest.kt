package com.autoscript.appservice.scriptrepo.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** 门面集成：store + deployer + index 一体的部署/恢复闭环。 */
class ProjectDeployerTest {

    @Test
    fun `部署多文件后索引可见且 recovery 为空`(@TempDir tmp: Path) {
        val store = FsProjectStore(tmp)
        val deployer = ProjectDeployer(store)

        val n = deployer.deploy("demo", mapOf("main.js" to "run()".toByteArray(), "lib/util.js" to "u".toByteArray()))
        assertEquals(2, n)

        val root = store.rootFor("demo")
        assertTrue(Files.isRegularFile(root.resolve("main.js")))
        assertTrue(Files.isRegularFile(root.resolve("lib/util.js")))
        assertEquals("run()", Files.readString(root.resolve("main.js")))
        assertEquals(0, deployer.recover("demo"), "正常部署后无待恢复记录")
        val proj = ProjectIndex(store).read("demo")!!
        assertEquals("main.js", proj.mainScript)
    }

    @Test
    fun `非法相对路径部署直接拒绝且不留残留`(@TempDir tmp: Path) {
        val store = FsProjectStore(tmp)
        val deployer = ProjectDeployer(store)
        store.create("demo")

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            deployer.deploy("demo", mapOf("../escape.js" to "x".toByteArray()))
        }
        // 项目目录保持干净：无半截文件、无 stage 残留
        Files.list(store.rootFor("demo")).use { s -> assertEquals(0, s.count(), "项目目录应无残留") }
        assertEquals(0, deployer.recover("demo"))
    }
}