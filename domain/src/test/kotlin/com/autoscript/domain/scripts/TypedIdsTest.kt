package com.autoscript.domain.scripts

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [ProjectId] / [RelPath] 的判据（批 109，typed ids）。
 *
 * **为什么这两个值类要单独钉**：它们替代的是本仓原先**三份**逐字相同（或相近）的判据。
 * 抄在一起必然漂，漂的方向最坏是「放行的一侧比拒收的一侧宽」—— 于是界面能敲出来的
 * 项目号落盘时被拒，用户看到的是「执行失败」而不是「这个项目号不合法」。
 * 判据收成一份之后，**唯一能防它再漂回去的东西就是这份测试**。
 */
class TypedIdsTest {

    // ── ProjectId ────────────────────────────────────────────────────────

    /**
     * 判据与 [ScriptPaths.PROJECT_ID] **逐字同源**（不许第二份）。
     *
     * 这条写法是刻意的：`isValidProjectId` 要改，这里立刻红。反过来（先改正则再改这里）
     * 就会有一段两边不一致的窗口期 —— 而那正是本项要消灭的东西。
     */
    @Test
    fun `判据与 ScriptPaths 同一份正则`() {
        val samples = listOf("demo", "p1", "a-b_c", "A", "9", "-lead", "", "..", ".", "a.b", "a b", "a/b", "a\\b")
        for (s in samples) {
            assertEquals(
                ScriptPaths.isValidProjectId(s),
                ProjectId.createOrNull(s) != null,
                "两份判据对 $s 的答案必须一致",
            )
        }
    }

    @Test
    fun `非法项目号 createOrNull 回 null 不抛`() {
        for (bad in listOf("", "..", ".", "a/b", "a\\b", "a b", "p.1", " a", "a\n", "a:b")) {
            assertNull(ProjectId.createOrNull(bad), "应回 null: ${bad.replace("\n", "\\n")}")
        }
    }

    /** 直构造（`ProjectId(x)`）**必须抛** —— 「没有不校验的后门」是本类的全部价值。 */
    @Test
    fun `直构造非法项目号抛 IAE`() {
        assertThrows(IllegalArgumentException::class.java) { ProjectId("..") }
        assertThrows(IllegalArgumentException::class.java) { ProjectId("p.1") }
    }

    @Test
    fun `toString 是原文（判据不放行的是构造，不是展示）`() {
        assertEquals("demo", ProjectId("demo").toString())
    }

    // ── RelPath ──────────────────────────────────────────────────────────

    /**
     * `RelPath.isSafe` 取代了三份旧实现（`DeployPath.isSafeRelPath`、
     * `ScriptPaths.scriptFile` 内联那份、`ScriptDeployRecovery` 的私有副本）。
     * 下面这份样本**照着那三份当时的判据抄**，是「判据本身没被顺手改宽」的证据。
     */
    @Test
    fun `相对路径判据样本`() {
        val safe = listOf("main.js", "lib/util.js", ".npmrc", "a/b/c.js", "a/.hidden", "-lead", "有中文.js")
        for (s in safe) assertTrue(RelPath.isSafe(s), "应放行: $s")
        val unsafe = listOf("", "  ", ".", "./a", "a/./b", "..", "../a", "a/../b", "/abs", "\\abs", "a\\b", "a//b", "a/")
        for (s in unsafe) assertFalse(RelPath.isSafe(s), "应拒: '${s.replace("\n", "\\n")}'")
    }

    @Test
    fun `非法相对路径 createOrNull 回 null 不抛`() {
        for (bad in listOf("", "..", "../a", "/abs", "a\\b", "a//b", "./a")) {
            assertNull(RelPath.createOrNull(bad), "应回 null: $bad")
        }
    }

    @Test
    fun `直构造非法相对路径抛 IAE`() {
        assertThrows(IllegalArgumentException::class.java) { RelPath("../escape.js") }
        assertThrows(IllegalArgumentException::class.java) { RelPath("/abs.js") }
    }

    /**
     * 类型本身堵死的那条缝：**项目号不能带 `/`** —— 而裸 `String` 时 `a/b` 对
     * 「项目号」与「项目内相对路径」都合法，一次串错位既不会被类型拦下、也不会被
     * 下游的 `require` 拦下（`resolve("a/b")` 恰好仍在项目根之下）。
     */
    @Test
    fun `带斜杠的值装不进 ProjectId`() {
        assertTrue(RelPath.isSafe("a/b"), "同一串对相对路径合法")
        assertNull(ProjectId.createOrNull("a/b"), "对项目号非法 —— 这正是本项要防的串错位")
        assertThrows(IllegalArgumentException::class.java) { ProjectId("a/b") }
    }

    @Test
    fun `toString 是原文`() {
        assertEquals("lib/util.js", RelPath("lib/util.js").toString())
    }
}
