package com.autoscript.appservice.scriptrepo.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class ProjectIndexTest {

    @Test
    fun `从仓库布局派生项目元数据`(@TempDir tmp: Path) {
        val store = FsProjectStore(tmp)
        val root = store.create("demo")
        Files.write(root.resolve("package.json"), """{"name":"demo","version":"2","main":"src/index.js"}""".toByteArray(StandardCharsets.UTF_8))
        Files.createDirectories(root.resolve("src"))
        Files.write(root.resolve("src/index.js"), "console.log(1)".toByteArray(StandardCharsets.UTF_8))

        val proj = ProjectIndex(store).read("demo")!!
        assertEquals("demo", proj.id)
        assertEquals(2, proj.version)
        assertEquals("src/index.js", proj.mainScript)
        assertTrue(proj.createdAtMillis > 0, "creationTime 应为真实时间")
    }

    @Test
    // 注：Kotlin 反引号函数名不允许含 '.'，故不写成 package.json
    fun `缺 package 元数据时回退 main 猜测`(@TempDir tmp: Path) {
        val store = FsProjectStore(tmp)
        val root = store.create("legacy")
        Files.write(root.resolve("main.js"), "x".toByteArray(StandardCharsets.UTF_8))
        Files.write(root.resolve("index.js"), "y".toByteArray(StandardCharsets.UTF_8))

        val proj = ProjectIndex(store).read("legacy")!!
        assertEquals("main.js", proj.mainScript, "main.js 优先于 index.js")
        assertEquals(1, proj.version)
        assertNull(ProjectIndex(store).read("missing"))
    }

    @Test
    fun `list 只返回已存在项目`(@TempDir tmp: Path) {
        val store = FsProjectStore(tmp)
        store.create("a")
        assertEquals(listOf("a"), ProjectIndex(store).list().map { it.id })
    }
}