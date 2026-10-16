package com.autoscript.ui.overlay

import com.autoscript.domain.host.ScriptLogRow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * 兜底补位号的**归属过滤**（2026-10-16 用户报「运行的时候会执行上一次执行成功的脚本」）。
 *
 * 钉的是那条回归：补位号必须与窗的归属（`projectId` + 项目内 `relPath`）**全等**，
 * 不能拿全库最新的一条 —— 后者会让窗刚开时被补到上一次别的脚本的输出上，
 * 而窗里读起来就是"它跑了上一次那个脚本"。
 */
class LogWindowFallbackTest {

    private fun row(runId: Long, projectId: String, scriptPath: String) = ScriptLogRow(
        runId = runId,
        projectId = projectId,
        scriptPath = scriptPath,
        startedAtMillis = 0L,
        sizeBytes = 0L,
        running = false,
    )

    @Test
    fun `补位号只认同归属 别的脚本再新也不补`() {
        val state = LogWindowState(projectId = "demo", relPath = "data/new.js")
        val rows = listOf(
            row(7L, "demo", "data/old.js"),      // 上一次执行的是别的文件（且号更大）
            row(9L, "demo", "data/old.js"),
            row(3L, "demo", "data/new.js"),      // 本归属的更早一次
        )
        assertEquals(3L, fallbackRunIdFor(state, rows), "只能补本归属的那条，哪怕它更旧")
    }

    @Test
    fun `同归属取最近一次`() {
        val state = LogWindowState(projectId = "demo", relPath = "data/new.js")
        val rows = listOf(row(4L, "demo", "data/new.js"), row(11L, "demo", "data/new.js"))
        assertEquals(11L, fallbackRunIdFor(state, rows))
    }

    @Test
    fun `别的项目不算同归属`() {
        val state = LogWindowState(projectId = "demo", relPath = "data/new.js")
        val rows = listOf(row(5L, "other", "data/new.js"))
        assertNull(fallbackRunIdFor(state, rows), "项目不同就不是这次执行，宁可空态")
    }

    @Test
    fun `一条都没有回 null 不编号`() {
        val state = LogWindowState(projectId = "demo", relPath = "data/new.js")
        assertNull(fallbackRunIdFor(state, emptyList()))
    }
}
