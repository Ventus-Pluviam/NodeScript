package com.autoscript.domain.scripts

import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * [ScriptPaths.scriptFile] 的读侧判据（外审 M1 收口，2026-10-12）。
 *
 * 为什么这些用例值得单独钉：改之前这个方法**一个字都不校验**，
 * `scriptFile(d, "p", "../../databases/app.db")` 返回的就是那条真实路径 —— 而
 * `NodeProcessEngine` 拿它的结果直接拼进 `node <路径>` 执行。所以判据一旦被谁
 * 「顺手放宽」，症状是脚本在项目目录之外被执行，且**没有任何报错**。
 */
class ScriptPathsScriptFileTest {

    private fun f(filesDir: Path, p: String): Path =
        ScriptPaths.scriptFile(filesDir, "p1", p)

    @Test
    fun `正常路径拼在项目根之下`(@TempDir filesDir: Path) {
        assertEquals(filesDir.resolve("scripts/p1/main.js"), f(filesDir, "main.js"))
        assertEquals(filesDir.resolve("scripts/p1/lib/util.js"), f(filesDir, "lib/util.js"))
    }

    @Test
    fun `点开头内务文件放行`(@TempDir filesDir: Path) {
        assertEquals(filesDir.resolve("scripts/p1/.npmrc"), f(filesDir, ".npmrc"))
    }

    @Test
    fun `点号段被拒`(@TempDir filesDir: Path) {
        // "." 让 files/scripts/p1/. 变成项目根本身，点开头子目录（如 .npmrc）
        // 于是落回共享目录上。
        assertThrows(IllegalArgumentException::class.java) { f(filesDir, "./main.js") }
        assertThrows(IllegalArgumentException::class.java) { f(filesDir, "a/./b.js") }
    }

    @Test
    fun `父目录越界被拒`(@TempDir filesDir: Path) {
        val e = assertThrows(IllegalArgumentException::class.java) {
            f(filesDir, "../../databases/app.db")
        }
        assertTrue("越界" in (e.message ?: "") || "非法" in (e.message ?: ""), "拒收原文要点名原因：${e.message}")
    }

    @Test
    fun `绝对路径与反斜杠被拒`(@TempDir filesDir: Path) {
        assertThrows(IllegalArgumentException::class.java) { f(filesDir, "/data/data/x") }
        assertThrows(IllegalArgumentException::class.java) { f(filesDir, "a\\b.js") }
    }

    @Test
    fun `空路径与空段被拒`(@TempDir filesDir: Path) {
        assertThrows(IllegalArgumentException::class.java) { f(filesDir, "") }
        assertThrows(IllegalArgumentException::class.java) { f(filesDir, "  ") }
        assertThrows(IllegalArgumentException::class.java) { f(filesDir, "a//b.js") }
    }

    @Test
    fun `项目号非法被拒——同一份正则口径`(@TempDir filesDir: Path) {
        assertThrows(IllegalArgumentException::class.java) {
            ScriptPaths.scriptFile(filesDir, "..", "main.js")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ScriptPaths.scriptFile(filesDir, "p.1", "main.js")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ScriptPaths.scriptFile(filesDir, "p 1", "main.js")
        }
    }
}
