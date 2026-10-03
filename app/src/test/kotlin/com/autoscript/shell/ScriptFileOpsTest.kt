package com.autoscript.shell

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * 新建文件/文件夹落盘（[ScriptFileOps]）的裁决面：
 * - 合法名字落到 `files/scripts/<projectId>/<name>`（路径出处在 [com.autoscript.domain.scripts.ScriptPaths]）；
 * - 非法名字（含 `/`、`..`、空）拒绝 —— 原文抛，呈现层不写第二套判据；
 * - **不覆盖已存在**（撞名抛 `FileAlreadyExistsException`）；
 * - 项目目录不存在拒绝（而不是静默建到别处）。
 */
class ScriptFileOpsTest {

    @TempDir
    lateinit var dir: Path

    @Test
    fun `新建文件与文件夹落位正确`() {
        Files.createDirectories(dir.resolve("scripts/demo"))
        ScriptFileOps.createFile(dir, "demo", "main.js")
        ScriptFileOps.createFolder(dir, "demo", "lib")
        assertTrue(Files.isRegularFile(dir.resolve("scripts/demo/main.js")))
        assertTrue(Files.isDirectory(dir.resolve("scripts/demo/lib")))
        // 新文件是空的（占位，不是模板）。
        assertEquals(0L, Files.size(dir.resolve("scripts/demo/main.js")))
    }

    @Test
    fun `名字含路径段拒绝`() {
        Files.createDirectories(dir.resolve("scripts/demo"))
        assertThrows(IllegalArgumentException::class.java) { ScriptFileOps.createFile(dir, "demo", "lib/main.js") }
        assertThrows(IllegalArgumentException::class.java) { ScriptFileOps.createFile(dir, "demo", "../escape.js") }
        assertThrows(IllegalArgumentException::class.java) { ScriptFileOps.createFile(dir, "demo", ".") }
        assertThrows(IllegalArgumentException::class.java) { ScriptFileOps.createFile(dir, "demo", "  ") }
        // 什么都没落下来。
        assertEquals(0, Files.list(dir.resolve("scripts/demo")).use { it.count() })
    }

    @Test
    fun `撞名不覆盖`() {
        Files.createDirectories(dir.resolve("scripts/demo"))
        Files.write(dir.resolve("scripts/demo/main.js"), "keep".toByteArray())
        assertThrows(java.nio.file.FileAlreadyExistsException::class.java) {
            ScriptFileOps.createFile(dir, "demo", "main.js")
        }
        assertThrows(java.nio.file.FileAlreadyExistsException::class.java) {
            ScriptFileOps.createFolder(dir, "demo", "main.js")
        }
        // 原内容原样。
        assertEquals("keep", String(Files.readAllBytes(dir.resolve("scripts/demo/main.js"))))
    }

    @Test
    fun `项目不存在拒绝`() {
        assertThrows(IllegalArgumentException::class.java) { ScriptFileOps.createFile(dir, "nope", "x.js") }
        // 没有替用户建出 nope 目录（静默建目录 = 错字也建出空项目）。
        assertTrue(!Files.isDirectory(dir.resolve("scripts/nope")))
    }
}
