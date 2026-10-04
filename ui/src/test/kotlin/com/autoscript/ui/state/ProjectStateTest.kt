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

    /**
     * 按**项目内相对路径**造行：`name` = 末段、`ext` 从末段取 —— 与生产侧
     * `ScriptFilesRead` 的 `name = path.name` 同口径（[row] 那份只够造第一层）。
     *
     * @param relPath 项目内路径（不含 projectId 前缀，如 `"lib/helper.js"`）——
     *   生产侧 `relPath` 是**带** projectId 前缀的整串，故这里拼上。
     */
    private fun rowAt(
        relPath: String,
        project: String = "demo",
        isDirectory: Boolean = false,
        childCount: Int = 0,
    ): ScriptFileRow {
        val leaf = relPath.removeSuffix("/").substringAfterLast('/')
        return ScriptFileRow(
            projectId = project,
            relPath = if (isDirectory) "$project/$relPath/" else "$project/$relPath",
            name = leaf,
            ext = if (isDirectory) "" else leaf.substringAfterLast('.', ""),
            isDirectory = isDirectory,
            childCount = childCount,
            sizeBytes = if (isDirectory) 0L else 2048L,
            modifiedMillis = now,
        )
    }

    /**
     * 项目那一层的目录行（`files/scripts/<project>/` 本身也是清单里的一行 ——
     * 生产侧 `ScriptFilesRead` 只滤掉根，项目目录照进）。
     */
    private fun projectRow(project: String, childCount: Int = 2) = ScriptFileRow(
        projectId = project,
        relPath = "$project/",
        name = project,
        ext = "",
        isDirectory = true,
        childCount = childCount,
        sizeBytes = 0L,
        modifiedMillis = now,
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

    @Test
    fun `childrenOf 一层只回一层且目录文件都算`() {
        val files = listOf(
            ScriptFileRowUi.of(projectRow("demo"), now, zone),
            ScriptFileRowUi.of(projectRow("demo2", childCount = 1), now, zone),
            ScriptFileRowUi.of(rowAt("main.js"), now, zone),
            ScriptFileRowUi.of(rowAt("lib", isDirectory = true, childCount = 2), now, zone),
            ScriptFileRowUi.of(rowAt("lib/helper.js"), now, zone),
            ScriptFileRowUi.of(rowAt("lib/sub", isDirectory = true, childCount = 1), now, zone),
            ScriptFileRowUi.of(rowAt("other.js", project = "demo2"), now, zone),
        )
        // 全库根：列的是**各项目**那一层（demo/ 与 demo2/），不递归到项目里面。
        val root = ProjectState.childrenOf(files, folder = null)
        assertEquals(listOf("demo", "demo2"), root.map { it.name })

        // 项目层：main.js 与 lib/ 两个直接子项；lib/helper.js 与 lib/sub/ 是下一层。
        val project = ProjectState.childrenOf(files, folder = "demo/")
        assertEquals(listOf("main.js", "lib"), project.map { it.name })

        val lib = ProjectState.childrenOf(files, folder = "demo/lib/")
        assertEquals(listOf("helper.js", "sub"), lib.map { it.name })

        val sub = ProjectState.childrenOf(files, folder = "demo/lib/sub/")
        assertTrue(sub.isEmpty())
    }

    @Test
    fun `搜索时候选集是整棵树而不是当前一层`() {
        val files = listOf(
            ScriptFileRowUi.of(projectRow("demo"), now, zone),
            ScriptFileRowUi.of(rowAt("main.js"), now, zone),
            ScriptFileRowUi.of(rowAt("lib", isDirectory = true, childCount = 2), now, zone),
            ScriptFileRowUi.of(rowAt("lib/helper.js"), now, zone),
            ScriptFileRowUi.of(rowAt("other.js", project = "demo2"), now, zone),
        )
        // 不搜索 = 当前一层的直接子项（"demo/" 那层只有 main.js 与 lib/）。
        assertEquals(
            listOf("main.js", "lib"),
            ProjectState.poolFor(files, folder = "demo/", searching = false).map { it.name },
        )
        // 搜索 = 整棵树（否则在 "demo/" 层搜 "helper.js" 会一个都搜不到 —— 它在下一层）。
        assertEquals(
            files.map { it.name },
            ProjectState.poolFor(files, folder = "demo/", searching = true).map { it.name },
        )
        // 命中判定：整棵树里 matches 能捞到深层与别的项目。
        assertEquals(
            listOf("helper.js"),
            ProjectState.poolFor(files, folder = "demo/", searching = true)
                .filter { it.matches("helper") }.map { it.name },
        )
        assertEquals(
            listOf("other.js"),
            ProjectState.poolFor(files, folder = "demo/", searching = true)
                .filter { it.matches("other") }.map { it.name },
        )
    }

    @Test
    fun `parentFolder 逐层回退到根收口`() {
        assertEquals(null, ProjectState.parentFolder(null))
        // 项目那一层再退 = 全库根。
        assertEquals(null, ProjectState.parentFolder("demo/"))
        assertEquals("demo/", ProjectState.parentFolder("demo/lib/"))
        assertEquals("demo/lib/", ProjectState.parentFolder("demo/lib/sub/"))
    }

    @Test
    fun `folderTitle 取目录名根层为空`() {
        // 全库根不是"某个目录"—— 顶栏保持项目页标题（null），不冒充一个目录名。
        assertEquals(null, ProjectState.folderTitle(folder = null))
        assertEquals("demo", ProjectState.folderTitle(folder = "demo/"))
        assertEquals("lib", ProjectState.folderTitle(folder = "demo/lib/"))
        assertEquals("sub", ProjectState.folderTitle(folder = "demo/lib/sub/"))
    }

    @Test
    fun `多选 逐行开关与选择全部取消全选`() {
        val files = listOf(
            ScriptFileRowUi.of(projectRow("demo"), now, zone),
            ScriptFileRowUi.of(rowAt("main.js"), now, zone),
            ScriptFileRowUi.of(rowAt("lib", isDirectory = true, childCount = 2), now, zone),
            ScriptFileRowUi.of(rowAt("other.js", project = "demo2"), now, zone),
        )
        val visible = ProjectState.childrenOf(files, folder = "demo/")
        val mainKey = ProjectState.keyOf(visible[0])

        // 逐行开关：加进去、再点一次去掉。
        val one = ProjectState.toggleSelection(emptySet(), mainKey)
        assertEquals(setOf(mainKey), one)
        assertTrue(ProjectState.toggleSelection(one, mainKey).isEmpty())

        // 选择全部 = 当前可见集（不是整棵树：demo2/ 那层不在当前目录里）。
        val all = ProjectState.toggleSelectAll(emptySet(), visible)
        assertEquals(visible.map { ProjectState.keyOf(it) }.toSet(), all)
        assertTrue(ProjectState.allSelected(all, visible))
        // 再按一次 = 取消全选（同一格开关）。
        assertTrue(ProjectState.toggleSelectAll(all, visible).isEmpty())
        // 只选了一半时按 = 补满，不是清空。
        val half = ProjectState.toggleSelection(emptySet(), mainKey)
        assertEquals(all, ProjectState.toggleSelectAll(half, visible))
        // 空可见集：选择全部不动已选（"选满了 0 行"没有意义，别把已选清掉）。
        assertEquals(half, ProjectState.toggleSelectAll(half, emptyList()))
        assertTrue(!ProjectState.allSelected(half, emptyList()))
    }
}
