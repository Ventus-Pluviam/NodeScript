package com.autoscript.ui.state

import com.autoscript.domain.host.ScriptLogRow
import com.autoscript.domain.host.ScriptLogSnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.ZoneId

/**
 * 脚本输出文件列表呈现态（2026-10-13）：标题归一 / 大小格式化 / 运行中让位。
 */
class ScriptLogStateTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    private fun row(
        runId: Long,
        projectId: String = "demo",
        scriptPath: String = "data/test.js",
        sizeBytes: Long = 2048L,
        running: Boolean = false,
    ) = ScriptLogRow(
        runId = runId,
        projectId = projectId,
        scriptPath = scriptPath,
        startedAtMillis = 1_700_000_000_000L,
        sizeBytes = sizeBytes,
        running = running,
    )

    @Test
    fun `标题斜杠加项目加路径 大小走 formatFileSize`() {
        val s = ScriptLogState.of(ScriptLogSnapshot(listOf(row(7L))), zone)
        val ui = s.rows.single()
        assertEquals("/demo/data/test.js", ui.title)
        assertEquals("js", ui.ext)
        assertEquals("2.0 KB", ui.sizeText)
        assertFalse(ui.running)
    }

    @Test
    fun `running 时大小让位给运行中`() {
        val s = ScriptLogState.of(ScriptLogSnapshot(listOf(row(7L, running = true))), zone)
        assertTrue(s.rows.single().running)
    }

    @Test
    fun `失败保留已读到的行`() {
        val loaded = ScriptLogState.of(ScriptLogSnapshot(listOf(row(7L))), zone)
        val failed = ScriptLogState.failed(RuntimeException("x"), loaded)
        assertEquals(1, failed.rows.size, "一次瞬时失败不抹掉已看到的列表")
        assertTrue(failed.load is LoadState.Failed)
    }
}
