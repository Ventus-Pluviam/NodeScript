package com.autoscript.ui.state

import com.autoscript.domain.npm.InstallEvent
import com.autoscript.domain.npm.NpmConsoleLine
import com.autoscript.domain.npm.NpmConsoleLineKind
import com.autoscript.domain.npm.NpmConsoleSnapshot
import com.autoscript.domain.npm.SequencedConsoleLine
import java.time.ZoneId
import com.autoscript.domain.npm.MissingPkg
import com.autoscript.domain.npm.NodeModulesStats
import com.autoscript.domain.npm.NpmProjectSnapshot
import com.autoscript.domain.npm.NpmPanelSnapshot
import com.autoscript.domain.npm.PkgNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 依赖面板呈现态的用例：这里断的是"界面会怎么说"，不是 npm 的行为。
 *
 * 重点在三条**不许冒充**：尺寸没量到 ≠ 0 字节、读失败 ≠ 空清单、待审队列是全局的
 * （不跟着选中的项目走）。
 */
class NpmStateTest {

    private fun stats(bytes: Long) = NodeModulesStats(projectId = "demo", pkgCount = 1, totalBytes = bytes)

    /** 假的控制台输出快照（行号从小到大排，`lastSeq` 是读口给的下一次游标）。 */
    private fun consoleSnapshot(seq: Long, lines: List<Pair<String, NpmConsoleLineKind>>) = NpmConsoleSnapshot(
        firstSeq = 1,
        lastSeq = seq,
        lines = lines.mapIndexed { i, (text, kind) ->
            SequencedConsoleLine(
                seq = seq - lines.size + i + 1,
                line = NpmConsoleLine(kind = kind, text = text, atMillis = 0L),
            )
        },
        running = false,
    )

    private fun project(
        id: String,
        installed: List<PkgNode> = emptyList(),
        storage: NodeModulesStats? = stats(0),
        quotaBytes: Long = 512L * 1024 * 1024,
    ) = NpmProjectSnapshot(
        projectId = id,
        installed = installed,
        offlineGap = emptyList(),
        storage = storage,
        quotaBytes = quotaBytes,
        quotaWarnRatio = 0.8,
    )

    @Test
    fun `首帧与成功空项目分开`() {
        assertFalse(NpmState.NOT_LOADED.load.isLoaded)
        assertNull(NpmState.NOT_LOADED.project)
        assertTrue(NpmState.NOT_LOADED.installed.isEmpty())
        assertNull(NpmState.NOT_LOADED.quotaLabel)

        val empty = NpmState.of(NpmPanelSnapshot(projects = emptyList()))
        assertTrue(empty.load.isLoaded)
        assertNull(empty.selectedProjectId, "一个项目都没有时不该凭空选一个")
        assertTrue(empty.installed.isEmpty())
    }

    @Test
    fun `默认选中第一个项目且刷新保留仍在的选择`() {
        val snap = NpmPanelSnapshot(listOf(project("alpha"), project("beta")))
        val first = NpmState.of(snap)
        assertEquals("alpha", first.selectedProjectId)

        val switched = first.copy(selectedProjectId = "beta")
        assertEquals("beta", NpmState.of(snap, switched).selectedProjectId, "刷新不该把用户正在看的项目换掉")

        // 选中的项目消失了（被删/改名）→ 回落到第一个，而不是留一个指不到任何东西的 id。
        val shrunk = NpmPanelSnapshot(listOf(project("alpha")))
        assertEquals("alpha", NpmState.of(shrunk, switched).selectedProjectId)
    }

    @Test
    fun `读失败保留已读到的清单且不伪装成空`() {
        val loaded = NpmState.of(
            NpmPanelSnapshot(listOf(project("demo", installed = listOf(PkgNode("esbuild", "0.21.5"))))),
        )
        val after = NpmState.failed(IllegalStateException("npm 未接线"), loaded)
        assertEquals("npm 未接线", after.load.failedReason())
        assertEquals(loaded.installed, after.installed)
        assertEquals("demo", after.selectedProjectId)

        assertEquals("RuntimeException", NpmState.failed(RuntimeException()).load.failedReason())
        assertTrue(NpmState.failed(RuntimeException()).installed.isEmpty())
    }

    @Test
    fun `尺寸没量到不画成零字节`() {
        val unmeasured = NpmState.of(NpmPanelSnapshot(listOf(project("demo", storage = null))))
        assertNull(unmeasured.quotaLabel, "没量到 → 不画（0 B / 512 MB 是在说这个项目不占地方）")
        assertNull(unmeasured.project?.quotaFraction)

        val zero = NpmState.of(NpmPanelSnapshot(listOf(project("demo", storage = stats(0)))))
        assertEquals("0 B / 512 MB", zero.quotaLabel, "量到了、就是 0 字节 → 如实说 0")
        assertEquals(0f, zero.project?.quotaFraction)
    }

    @Test
    fun `配额两档与宿主拦安装的判据同源`() {
        val quota = 1000L
        val under = NpmState.of(NpmPanelSnapshot(listOf(project("demo", storage = stats(799), quotaBytes = quota))))
        assertFalse(under.project!!.overQuota)
        assertFalse(under.project!!.quotaWarned)

        val warned = NpmState.of(NpmPanelSnapshot(listOf(project("demo", storage = stats(800), quotaBytes = quota))))
        assertFalse(warned.project!!.overQuota)
        assertTrue(warned.project!!.quotaWarned, "恰好 80% 即预警（与宿主 >= 同口径）")
        assertEquals(0.8f, warned.project!!.quotaFraction)

        val over = NpmState.of(NpmPanelSnapshot(listOf(project("demo", storage = stats(1000), quotaBytes = quota))))
        assertTrue(over.project!!.overQuota, "恰好 100% 即拦（与宿主 >= 同口径）")
        assertEquals(1f, over.project!!.quotaFraction, "比例封顶 1，超配额不画出 >100% 的条")
    }

    @Test
    fun `已装清单保留名字与版本`() {
        val state = NpmState.of(
            NpmPanelSnapshot(
                listOf(project("demo", installed = listOf(PkgNode("esbuild", "0.21.5"), PkgNode("@babel/core", "7.24.0")))),
            ),
        )
        assertEquals(listOf("esbuild" to "0.21.5", "@babel/core" to "7.24.0"), state.installed.map { it.name to it.version })
    }

    /**
     * 日志大框那一段：本页操作的账在前、npm 输出在后（用户 2026-10-17 裁定「两者都显示」），
     * 且**裁过要说**。
     */
    @Test
    fun `日志框先操作账后 npm 输出，且累积不重复`() {
        val base = NpmState.of(NpmPanelSnapshot(listOf(project("demo"))))
        val withLog = NpmState.withConsole(
            base.copy(opError = "npm 退出码 1: E404", opNotice = "已提交 npm install"),
            consoleSnapshot(
                seq = 2,
                lines = listOf("npm install axios" to NpmConsoleLineKind.ECHO, "ok" to NpmConsoleLineKind.OUTPUT),
            ),
            // 时区**注入**（不读系统默认）：断言里那个 `00:00:00` 才是个定值，而不是"跑在哪个时区就变一次"。
            zone = ZoneId.of("UTC"),
        )
        assertEquals(
            listOf(
                "npm 退出码 1: E404",
                "已提交 npm install",
                "00:00:00  npm install axios",
                "00:00:00  ok",
            ),
            withLog.logLines.map { it.text },
            "两条操作账要排在 npm 输出**前面**",
        )
        assertEquals(
            listOf(StatusTone.PROBLEM, StatusTone.MUTED, StatusTone.MUTED, StatusTone.NEUTRAL),
            withLog.logLines.map { it.tone },
        )

        // 同一批再折一遍：seq 去重，不重复入列、游标不倒退。
        val same = NpmState.withConsole(withLog, consoleSnapshot(2, listOf("ok" to NpmConsoleLineKind.OUTPUT)))
        assertEquals(4, same.logLines.size, "同一个 seq 读两遍不许变成两行")
        assertEquals(2L, same.consoleSeq)
    }

    @Test
    fun `日志框读失败也进框且说清是旧数据`() {
        val failed = NpmState.failed(
            IllegalStateException("宿主未接线"),
            NpmState.of(NpmPanelSnapshot(listOf(project("demo")))),
        )
        val first = failed.logLines.first()
        assertEquals(StatusTone.PROBLEM, first.tone)
        assertTrue(first.text.startsWith("读取失败：宿主未接线"), "失败要带原文：${first.text}")
        assertTrue(first.text.contains("上次读到的"), "失败态下屏上还有旧清单，不说这句就是把过期读数当现在的")
    }

    @Test
    fun `日志框超过上限就裁掉并如实标注`() {
        val base = NpmState.of(NpmPanelSnapshot(listOf(project("demo"))))
        val many = (1..(LOG_KEEP + 10)).map { "line-$it" to NpmConsoleLineKind.OUTPUT }
        val state = NpmState.withConsole(base, consoleSnapshot(seq = many.size.toLong(), lines = many))
        assertEquals(LOG_KEEP, state.consoleLines.size, "留最近 $LOG_KEEP 行")
        assertTrue(state.consoleTruncated, "裁过就要置位：框里那句「更早的输出已裁掉」靠它")
        assertEquals("line-11", state.consoleLines.first().text, "裁的是**最早**的那些，不是最新的")
        assertEquals("line-${many.size}", state.consoleLines.last().text, "最新的那行必须在（裁的是最早的那些）")
        assertTrue(state.logLines.first().text.contains("更早的输出已裁掉"), "裁过的说明排在最前")
    }

    @Test
    fun `换项目清日志与游标，刷新不清`() {
        val a = NpmState.withConsole(
            NpmState.of(NpmPanelSnapshot(listOf(project("alpha"), project("beta")))),
            consoleSnapshot(seq = 1, lines = listOf("alpha 的输出" to NpmConsoleLineKind.OUTPUT)),
        )
        // 刷新（同一项目）**不许**把已经拉到的日志抹掉。
        val refreshed = NpmState.of(NpmPanelSnapshot(listOf(project("alpha"), project("beta"))), a)
        assertEquals(1, refreshed.consoleLines.size)
        // 换项目：行与游标一起归零（seq 是环内全局单调，沿用旧游标会漏掉新项目 seq 更小的行）。
        val b = NpmState.withProject(a, "beta")
        assertTrue(b.consoleLines.isEmpty())
        assertEquals(0L, b.consoleSeq)
        assertFalse(b.consoleTruncated)
    }

    @Test
    fun `离线缺口与尺寸格式化不四舍五入撒谎`() {
        assertEquals("0 B", NpmRowState.bytes(0))
        assertEquals("1023 B", NpmRowState.bytes(1023))
        assertEquals("1 KB", NpmRowState.bytes(1024))
        assertEquals("1.5 MB", NpmRowState.bytes(1024L * 1024 + 512 * 1024))

        // 缺口带尺寸（离线闭包差集）—— 这里只确认它随项目快照一路带得过来。
        val withGap = NpmProjectSnapshot(
            projectId = "demo",
            installed = emptyList(),
            offlineGap = listOf(MissingPkg("esbuild", "0.21.5", 9_000_000)),
            storage = stats(0),
            quotaBytes = 1000,
            quotaWarnRatio = 0.8,
        )
        val state = NpmState.of(NpmPanelSnapshot(listOf(withGap)))
        assertEquals(listOf("esbuild"), state.project!!.offlineGap.map { it.name })
    }

}
