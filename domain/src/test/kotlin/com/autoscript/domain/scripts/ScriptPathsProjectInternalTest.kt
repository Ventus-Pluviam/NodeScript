package com.autoscript.domain.scripts

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * [ScriptPaths.projectInternalOrNull] 的形状转换，以及两个展示口（2026-10-16 补）。
 *
 * **为什么此前一条测试都没有、现在必须有**：这条转换是"文件清单形状（首段 = 项目名）→
 * 执行侧形状（项目内路径）"的唯一收口点，`AssembledShell.runScriptFile` 与
 * `ScriptFileOps` 都靠它。根层可写（用户裁定）之后它多了一档**根层分支** ——
 * 那一档要是判错，症状是"根层的文件点 ▶ 报执行失败"，而**编译期毫无迹象**。
 *
 * 另一半价值是"削不出就回 null，不猜"：项目 `demo` 下真有 `demo/main.js` 时，
 * 容错的削法会把 `demo/main.js` 削成 `main.js` 去跑**另一个文件**。
 */
class ScriptPathsProjectInternalTest {

    @Test
    fun `首段是项目名才削`() {
        assertEquals("main.js", ScriptPaths.projectInternalOrNull("demo", "demo/main.js"))
        assertEquals("lib/util.js", ScriptPaths.projectInternalOrNull("demo", "demo/lib/util.js"))
        // 目录行（尾随 `/`，是清单里目录行的形状）与文件行同一个答案。
        assertEquals("lib", ScriptPaths.projectInternalOrNull("demo", "demo/lib/"))
    }

    @Test
    fun `削不出就回 null 不猜`() {
        assertNull(ScriptPaths.projectInternalOrNull("demo", "main.js"), "首段不是项目名")
        assertNull(ScriptPaths.projectInternalOrNull("demo", "other/main.js"), "别的项目")
        assertNull(ScriptPaths.projectInternalOrNull("demo", "demo/"), "削完是空串")
    }

    /**
     * 根层分支（[ProjectId.ROOT] = 空串）：根层条目**本身就是**项目内路径
     * （`files/scripts/x.js` 的 `scriptPath` 就是 `x.js`），不削前缀。
     *
     * 但**必须是单段**：根层只有一层，`a/b.js` 是"更深的层"，回 null 由调用方响亮拒绝
     * —— 与"削不出就回 null"同一条纪律（不然 `a/b.js` 会被当成 `files/scripts/a/b.js`）。
     */
    @Test
    fun `根层：单段就是它本身，更深的层回 null`() {
        val root = ProjectId.ROOT.value
        assertEquals("x.js", ScriptPaths.projectInternalOrNull(root, "x.js"))
        assertEquals("新项目", ScriptPaths.projectInternalOrNull(root, "新项目/"))
        assertNull(ScriptPaths.projectInternalOrNull(root, "a/b.js"))
        assertNull(ScriptPaths.projectInternalOrNull(root, ""))
        assertNull(ScriptPaths.projectInternalOrNull(root, "/"))
    }

    /**
     * 展示口：根层条目**没有项目段**，直接拼会渲染成 `//x.js` / `" / x.js"`
     * （悬浮窗、日志管理、任务日志详情三处各拼一次就三处都错），故收在这两个函数里。
     */
    @Test
    fun `展示路径：根层不渲染成双斜杠`() {
        assertEquals("/x.js", "/" + ScriptPaths.fullRelPath("", "x.js"))
        assertEquals("/demo/main.js", "/" + ScriptPaths.fullRelPath("demo", "main.js"))
        assertEquals("根目录", ScriptPaths.projectLabel(""))
        assertEquals("demo", ScriptPaths.projectLabel("demo"))
    }
}
