package com.autoscript.domain.scripts

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 可执行判据（[ScriptRunnability]）：**只认引擎真能跑的** ——
 * JS 家族（`node <路径>` 的 argv 契约）全收，shell 整体不收（引擎起的是 node）。
 */
class ScriptRunnabilityTest {

    @Test
    fun `js 家族扩展名可执行`() {
        assertTrue(ScriptRunnability.isRunnable("main.js"))
        assertTrue(ScriptRunnability.isRunnable("lib/index.mjs"))
        assertTrue(ScriptRunnability.isRunnable("auto.cjs"))
        assertTrue(ScriptRunnability.isRunnable("main.ts"))
        // 大写归一：.JS 与 .js 是一类文件（ScriptFilesRead 的 extension 也归小写）。
        assertTrue(ScriptRunnability.isRunnable("MAIN.JS"))
    }

    @Test
    fun `shell 脚本整体不可执行`() {
        // 用户口径点名了 `sh`，这里是如实收窄：引擎起的是 node，node x.sh 只会 SyntaxError；
        // shell 脚本唯一的执行面是控制台 shell（默认模式一律拒，要 root/shizuku）。
        assertFalse(ScriptRunnability.isRunnable("deploy.sh"))
        assertFalse(ScriptRunnability.isRunnable("run.bash"))
        assertFalse(ScriptRunnability.isRunnable("x.zsh"))
    }

    @Test
    fun `非脚本与无扩展名不可执行`() {
        assertFalse(ScriptRunnability.isRunnable("README.md"))
        assertFalse(ScriptRunnability.isRunnable("package.json"))
        assertFalse(ScriptRunnability.isRunnable("Makefile"))
        // 目录行 relPath 以 / 结尾（契约约定）—— 不该被当成可执行文件。
        assertFalse(ScriptRunnability.isRunnable("lib/"))
        // 无扩展名 / 点开头无扩展名。
        assertFalse(ScriptRunnability.isRunnable("script"))
        assertFalse(ScriptRunnability.isRunnable(".gitignore"))
    }

    @Test
    fun `多段路径取最后一段的扩展名`() {
        // 与 ScriptFilesRead 的 `path.extension` 口径一致：`lib/a.b.js` 是 js，不是 b。
        assertTrue(ScriptRunnability.isRunnable("lib/a.b.js"))
        assertFalse(ScriptRunnability.isRunnable("lib/a.sh.jsx"))
    }
}
