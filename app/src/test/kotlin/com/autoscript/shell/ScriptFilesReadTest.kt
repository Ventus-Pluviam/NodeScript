package com.autoscript.shell

import com.autoscript.domain.host.ScriptFileRow
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * 脚本文件清单读口（[ScriptFilesRead]）的装配侧验证：
 * - `files/scripts/` 整棵树平铺、修改时间倒序（呈现层不再排一次）；
 * - `node_modules` 与点开头条目不进列表（目录本身也不是文件行）；
 * - 项目根不存在回**空清单**（首装是真实事实，不是失败）；
 * - `ScriptPaths.projectsRoot` 是唯一路径出处（拼错即与部署侧一起错，编译期可见）。
 */
class ScriptFilesReadTest {

    @TempDir
    lateinit var dir: Path

    @Test
    fun `项目根不存在回空清单`() = runBlocking {
        val snap = ScriptFilesRead.snapshot(dir)
        assertTrue(snap.rows.isEmpty())
    }

    @Test
    fun `整树平铺按修改时间倒序`() = runBlocking {
        val root = dir.resolve("scripts")
        Files.createDirectories(root.resolve("demo/lib"))
        Files.createDirectories(root.resolve("web"))
        Files.write(root.resolve("demo/main.js"), "console.log(1)".toByteArray())
        Files.write(root.resolve("demo/lib/util.js"), "export {}".toByteArray())
        Files.write(root.resolve("web/index.html"), "<html></html>".toByteArray())
        // 修改时间有顺序：main.js 最新（列表第一），util.js 最旧。
        val now = System.currentTimeMillis()
        Files.setLastModifiedTime(root.resolve("demo/main.js"), java.nio.file.attribute.FileTime.fromMillis(now))
        Files.setLastModifiedTime(root.resolve("web/index.html"), java.nio.file.attribute.FileTime.fromMillis(now - 60_000))
        Files.setLastModifiedTime(root.resolve("demo/lib/util.js"), java.nio.file.attribute.FileTime.fromMillis(now - 120_000))

        val rows = ScriptFilesRead.snapshot(dir).rows
        assertEquals(listOf("main.js", "index.html", "util.js"), rows.map { it.name })
        assertEquals("demo", rows[0].projectId)
        assertEquals("demo/lib/util.js", rows[2].relPath)
        assertEquals("js", rows[0].ext)
        assertEquals("<html></html>".length.toLong(), rows[1].sizeBytes)
    }

    @Test
    fun `node_modules 与点开头的文件不进列表`() = runBlocking {
        val root = dir.resolve("scripts")
        Files.createDirectories(root.resolve("demo/node_modules/left-pad"))
        Files.write(root.resolve("demo/node_modules/left-pad/index.js"), "x".toByteArray())
        Files.write(root.resolve("demo/.DS_Store"), "junk".toByteArray())
        Files.write(root.resolve("demo/main.js"), "ok".toByteArray())

        val rows = ScriptFilesRead.snapshot(dir).rows
        assertEquals(listOf("main.js"), rows.map { it.name })
    }

    @Test
    fun `行字段与文件事实一致`() = runBlocking {
        val root = dir.resolve("scripts")
        Files.createDirectories(root.resolve("demo"))
        val body = "console.log('hello')"
        Files.write(root.resolve("demo/main.js"), body.toByteArray())
        val rows = ScriptFilesRead.snapshot(dir).rows
        assertEquals(1, rows.size)
        val r: ScriptFileRow = rows[0]
        assertEquals("demo", r.projectId)
        assertEquals("demo/main.js", r.relPath)
        assertEquals("main.js", r.name)
        assertEquals("js", r.ext)
        assertEquals(body.length.toLong(), r.sizeBytes)
        assertTrue(r.modifiedMillis > 0)
    }
}
