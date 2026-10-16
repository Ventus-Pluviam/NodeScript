package com.autoscript.shell

import com.autoscript.domain.host.ScriptFileRow
import com.autoscript.domain.scripts.ProjectId
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * 脚本文件清单读口（[ScriptFilesRead]）的装配侧验证：
 * - `files/scripts/` 整棵树平铺、**未排序**（排序归呈现层 —— 快照只保证目录遍历序）；
 * - 文件与**文件夹**都进清单（`demo` 这种项目目录本身也是一行）；
 * - `node_modules` 整棵子树（含目录本身）与点开头条目不进列表；
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
    fun `文件与文件夹都进清单目录行带斜杠与子项数且未排序`() = runBlocking {
        val root = dir.resolve("scripts")
        Files.createDirectories(root.resolve("demo/lib"))
        Files.createDirectories(root.resolve("web"))
        Files.write(root.resolve("demo/main.js"), "console.log(1)".toByteArray())
        Files.write(root.resolve("demo/lib/util.js"), "export {}".toByteArray())
        Files.write(root.resolve("web/index.html"), "<html></html>".toByteArray())

        val rows = ScriptFilesRead.snapshot(dir).rows
        // 未排序（排序归呈现层）：只验事实，不验次序。6 行 = demo、demo/lib、
        // demo/main.js、demo/lib/util.js、web、web/index.html。
        assertEquals(6, rows.size)
        val demo = rows.single { it.name == "demo" }
        assertTrue(demo.isDirectory)
        assertEquals("demo/", demo.relPath)
        val lib = rows.single { it.name == "lib" }
        assertTrue(lib.isDirectory)
        assertEquals("demo/lib/", lib.relPath)
        assertEquals(1, lib.childCount)
        assertEquals("", lib.ext)
        assertEquals(0L, lib.sizeBytes)
        val main = rows.single { it.name == "main.js" }
        assertTrue(!main.isDirectory)
        assertEquals(0, main.childCount)
        assertEquals("js", main.ext)
        assertEquals("console.log(1)".length.toLong(), main.sizeBytes)
        assertEquals("demo", main.projectId)
    }

    @Test
    fun `node_modules 整棵子树与点开头条目不进列表`() = runBlocking {
        val root = dir.resolve("scripts")
        Files.createDirectories(root.resolve("demo/node_modules/left-pad"))
        Files.write(root.resolve("demo/node_modules/left-pad/index.js"), "x".toByteArray())
        Files.write(root.resolve("demo/.DS_Store"), "junk".toByteArray())
        Files.write(root.resolve("demo/main.js"), "ok".toByteArray())

        val rows = ScriptFilesRead.snapshot(dir).rows
        // node_modules 目录本身也藏着（不是"只藏内容"）；项目目录 demo 照样是一行。
        assertEquals(listOf("demo", "main.js"), rows.map { it.name }.sorted())
    }

    /**
     * 根层条目（`files/scripts/` 本层）的 `projectId` 是 [ProjectId.ROOT]（空串），
     * 二层及以下仍是首段目录名（2026-10-16 用户裁定「根层可写」）。
     *
     * **此前一律取首段**，于是根层的文件被报成"项目 `x.js`"：列得出来，点开必得一句
     * 「项目不存在: x.js」（`ScriptFileOps.resolveInProject` 的 `require`）——
     * 这正是用户看到的"建得出、打不开"那一半。
     */
    @Test
    fun `根层条目没有项目段二层以下仍报首段`() = runBlocking {
        val root = dir.resolve("scripts")
        Files.createDirectories(root.resolve("demo"))
        Files.createDirectories(root.resolve("我的脚本"))
        Files.write(root.resolve("hello.js"), "x".toByteArray())
        Files.write(root.resolve("demo/main.js"), "y".toByteArray())
        Files.write(root.resolve("我的脚本/run.js"), "z".toByteArray())

        val rows = ScriptFilesRead.snapshot(dir).rows
        // 根层的文件与根层的文件夹（= 一个项目）都报空串；它们下面的条目报首段。
        assertEquals(ProjectId.ROOT.value, rows.single { it.name == "hello.js" }.projectId)
        assertEquals(ProjectId.ROOT.value, rows.single { it.name == "我的脚本" }.projectId)
        assertEquals("hello.js", rows.single { it.name == "hello.js" }.relPath)
        assertEquals("我的脚本/", rows.single { it.name == "我的脚本" }.relPath)
        assertEquals("我的脚本", rows.single { it.name == "run.js" }.projectId)
        assertEquals("我的脚本/run.js", rows.single { it.name == "run.js" }.relPath)
        assertEquals("demo", rows.single { it.name == "main.js" }.projectId)
    }

    @Test
    fun `行字段与文件事实一致`() = runBlocking {
        val root = dir.resolve("scripts")
        Files.createDirectories(root.resolve("demo"))
        val body = "console.log('hello')"
        Files.write(root.resolve("demo/main.js"), body.toByteArray())
        val rows = ScriptFilesRead.snapshot(dir).rows
        // 2 行 = demo 目录行 + main.js 文件行。
        assertEquals(2, rows.size)
        val r: ScriptFileRow = rows.single { it.name == "main.js" }
        assertEquals("demo", r.projectId)
        assertEquals("demo/main.js", r.relPath)
        assertEquals("main.js", r.name)
        assertEquals("js", r.ext)
        assertTrue(!r.isDirectory)
        assertEquals(0, r.childCount)
        assertEquals(body.length.toLong(), r.sizeBytes)
        assertTrue(r.modifiedMillis > 0)
    }
}
