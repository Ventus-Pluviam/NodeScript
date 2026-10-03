package com.autoscript.ui.state

import com.autoscript.domain.host.ScriptFileRow
import com.autoscript.domain.host.ScriptFilesSnapshot
import java.time.ZoneId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 项目页（文件列表）呈现态纯逻辑：三态措辞、搜索过滤、大小/时刻格式化。
 * `:ui` 的门走 `:ui:testDebugUnitTest`，状态判定与渲染分开（见 [HomeStateTest] 同一条）。
 */
class ProjectStateTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    /** 2026-10-03 10:00 CST 的 epoch ms（"今天"锚点；行时刻都相对它造）。 */
    private val now = java.time.LocalDateTime.of(2026, 10, 3, 10, 0)
        .atZone(zone).toInstant().toEpochMilli()

    private fun row(
        name: String,
        ext: String = "js",
        size: Long = 2048L,
        modified: Long = now,
        project: String = "demo",
        isDirectory: Boolean = false,
        childCount: Int = 0,
    ) = ScriptFileRow(
        projectId = project,
        relPath = if (isDirectory) "$project/$name/" else "$project/$name",
        name = name,
        ext = if (isDirectory) "" else ext,
        isDirectory = isDirectory,
        childCount = childCount,
        sizeBytes = size,
        modifiedMillis = modified,
    )

    @Test
    fun `of 逐行成行且次行是大小加时刻`() {
        val s = ProjectState.of(ScriptFilesSnapshot(listOf(row("main.js", size = 2048L))), nowMillis = now, zone = zone)
        assertTrue(s.load.isLoaded)
        assertEquals(1, s.files.size)
        assertEquals("main.js", s.files[0].name)
        assertEquals("2.0 KB · 10:00", s.files[0].subtitle)
    }

    @Test
    fun `failed 保留原异常文案且清单为空`() {
        val s = ProjectState.failed(IllegalStateException("磁盘炸了"))
        assertEquals("磁盘炸了", s.load.failedReason())
        assertTrue(s.files.isEmpty())
    }

    @Test
    fun `搜索按文件名与路径命中且大小写不敏感`() {
        val files = listOf(
            ScriptFileRowUi.of(row("main.js"), now, zone),
            ScriptFileRowUi.of(row("lib/helper.js", project = "demo").copy(relPath = "demo/lib/helper.js"), now, zone),
            ScriptFileRowUi.of(row("README.md", ext = "md"), now, zone),
        )
        assertTrue(files[0].matches(""))
        assertTrue(files[0].matches("MAIN"))
        assertTrue(files.any { it.matches("helper") })
        assertTrue(files.none { it.matches("xlsx") })
    }

    @Test
    fun `大小格式化与 TG formatFileSize 同口径`() {
        assertEquals("0 KB", ScriptFileRowUi.formatFileSize(0L))
        assertEquals("348 B", ScriptFileRowUi.formatFileSize(348L))
        assertEquals("1.0 KB", ScriptFileRowUi.formatFileSize(1024L))
        assertEquals("2.0 KB", ScriptFileRowUi.formatFileSize(2048L))
        assertEquals("1.5 MB", ScriptFileRowUi.formatFileSize(1572864L))
        // TG 的 GB 档是 MB/1000（AndroidUtilities.formatFileSize 原样）：1024MB → 1.02 GB。
        assertEquals("1.02 GB", ScriptFileRowUi.formatFileSize(1073741824L))
    }

    @Test
    fun `文件夹行次行是 N 项加时刻且不显示字节数`() {
        val s = ProjectState.of(
            ScriptFilesSnapshot(listOf(row("lib", isDirectory = true, childCount = 3))),
            nowMillis = now,
            zone = zone,
        )
        assertEquals("3 项 · 10:00", s.files[0].subtitle)
        assertTrue(s.files[0].isDirectory)
    }

    @Test
    fun `排序——目录恒在最前逆向只翻文件段四档各按各的键`() {
        val dirs = listOf(
            ScriptFileRowUi.of(row("zeta", isDirectory = true, childCount = 1), now, zone),
            ScriptFileRowUi.of(row("alpha", isDirectory = true, childCount = 2), now, zone),
        )
        val files = listOf(
            ScriptFileRowUi.of(row("b.js", size = 300L, modified = now - 1000), now, zone),
            ScriptFileRowUi.of(row("a.js", size = 100L, modified = now - 3000), now, zone),
            ScriptFileRowUi.of(row("c.md", ext = "md", size = 200L, modified = now - 2000), now, zone),
        )
        val all = dirs + files

        // 日期档（缺省）：新者在前，目录在最前。
        assertEquals(
            listOf("alpha", "zeta", "b.js", "c.md", "a.js"),
            ScriptFileRowUi.sorted(all, FileSort.DATE, reversed = false).map { it.name },
        )
        // 逆向：文件段翻过来，目录段不动。
        assertEquals(
            listOf("alpha", "zeta", "a.js", "c.md", "b.js"),
            ScriptFileRowUi.sorted(all, FileSort.DATE, reversed = true).map { it.name },
        )
        // 名称档：大小写不敏感字母序。
        assertEquals(
            listOf("alpha", "zeta", "a.js", "b.js", "c.md"),
            ScriptFileRowUi.sorted(all, FileSort.NAME, reversed = false).map { it.name },
        )
        // 大小档：大者在前。
        assertEquals(
            listOf("alpha", "zeta", "b.js", "c.md", "a.js"),
            ScriptFileRowUi.sorted(all, FileSort.SIZE, reversed = false).map { it.name },
        )
        // 类型档：扩展名分组（md < js? —— ext 字母序 js < md），组内按名称。
        assertEquals(
            listOf("alpha", "zeta", "a.js", "b.js", "c.md"),
            ScriptFileRowUi.sorted(all, FileSort.TYPE, reversed = false).map { it.name },
        )
    }

    @Test
    fun `重读保留排序与回执——偏好不因刷新消失`() {
        val first = ProjectState.of(ScriptFilesSnapshot(listOf(row("a.js"))), now, zone)
            .copy(sort = FileSort.NAME, reversed = true, opNotice = "已新建文件「x.js」")
        val again = ProjectState.of(
            ScriptFilesSnapshot(listOf(row("a.js"), row("b.js"))),
            now,
            zone,
            previous = first,
        )
        assertEquals(FileSort.NAME, again.sort)
        assertTrue(again.reversed)
        assertEquals("已新建文件「x.js」", again.opNotice)
        assertEquals(2, again.files.size)
    }

    @Test
    fun `时刻三档——今天时分、今年月日、跨年带年份`() {
        val today = now - 3600_000L
        val thisYear = java.time.LocalDateTime.of(2026, 9, 14, 22, 10).atZone(zone).toInstant().toEpochMilli()
        val lastYear = java.time.LocalDateTime.of(2025, 3, 2, 8, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals("09:00", ScriptFileRowUi.formatTime(today, now, zone))
        assertEquals("09-14", ScriptFileRowUi.formatTime(thisYear, now, zone))
        assertEquals("2025-03-02", ScriptFileRowUi.formatTime(lastYear, now, zone))
    }
}
