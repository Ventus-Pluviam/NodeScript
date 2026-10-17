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
import com.autoscript.domain.npm.NpmMaintenanceAction
import com.autoscript.domain.npm.NpmPanelSnapshot
import com.autoscript.domain.npm.NpmTargetScope
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
    ) = NpmProjectSnapshot(
        projectId = id,
        installed = installed,
        offlineGap = emptyList(),
        storage = storage,
    )

    /**
     * 作用域那一行怎么说（2026-10-17「项目选择改成依赖作用域，下拉框加个全局」）。
     *
     * 三条各自钉一个**不许冒充**：空串不许显示成空（用户读成"没读到"）、
     * 还没读到不许冒充「全局」（那是"还不知道"，不是"知道，是全局"）、
     * 缺省仍然落在第一个项目上（全局排最后，插到最前面就是一次没人要求过的行为变更）。
     */
    @Test
    fun `作用域标签：空串说「全局」，没读到说「—」，缺省仍是第一个项目`() {
        val global = NpmProjectSnapshot(
            projectId = NpmTargetScope.GLOBAL,
            installed = emptyList(),
            offlineGap = emptyList(),
            storage = null,
        )
        val loaded = NpmState.of(NpmPanelSnapshot(listOf(project("demo"), global)))

        assertEquals("demo", loaded.scopeLabel, "缺省是第一条；全局排最后，不抢缺省位")
        assertEquals(
            "全局",
            NpmState.withProject(loaded, NpmTargetScope.GLOBAL).scopeLabel,
            "空串在屏幕上必须说成人话",
        )
        assertEquals("—", NpmState.NOT_LOADED.scopeLabel, "还没读到 ≠ 全局：那是两句话")
        assertEquals("—", NpmState.failed(RuntimeException()).scopeLabel, "读失败也不冒充成全局")
    }

    @Test
    fun `首帧与成功空项目分开`() {
        assertFalse(NpmState.NOT_LOADED.load.isLoaded)
        assertNull(NpmState.NOT_LOADED.project)
        assertTrue(NpmState.NOT_LOADED.installed.isEmpty())
        assertNull(NpmState.NOT_LOADED.sizeLabel)

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
    fun `尺寸没量到不画成零字节，且不再有配额分母`() {
        val unmeasured = NpmState.of(NpmPanelSnapshot(listOf(project("demo", storage = null))))
        assertNull(unmeasured.sizeLabel, "没量到 → 不画（0 B 是在说这个项目不占地方）")

        val zero = NpmState.of(NpmPanelSnapshot(listOf(project("demo", storage = stats(0)))))
        assertEquals("0 B", zero.sizeLabel, "量到了、就是 0 字节 → 如实说 0")

        // 2026-10-17：配额摘了之后**那一行里不许再出现任何上限数字**（用户口径
        // 「取消 512MB 限制」「取消上限设置吧」）—— 这条断言就是防它悄悄回来。
        val big = NpmState.of(NpmPanelSnapshot(listOf(project("demo", storage = stats(700L * 1024 * 1024)))))
        assertEquals("700 MB", big.sizeLabel, "700MB 的 node_modules 只报 700 MB，不带 /512 MB")
    }

    /**
     * 「清理」那颗按钮的判据（2026-10-17 从 Composable 里提上来的那条行内表达式）。
     *
     * 三者互斥的来源是宿主的 `globalSession`（同时只跑一个安装会话）—— 界面上出现的形态
     * 是"按下去没反应"，用户读成"卡了"。这一条把判据钉在纯数据上，改判据时会被拦。
     */
    @Test
    fun `清理按钮：安装、维护、回收三者互斥`() {
        val idle = NpmState.of(NpmPanelSnapshot(listOf(project("demo"))))
        assertTrue(idle.canReclaimCache, "空闲时可按")
        assertFalse(idle.copy(installing = true).canReclaimCache, "有安装在跑 → 不可按")
        assertFalse(
            idle.copy(maintenance = NpmMaintenanceAction.PRUNE).canReclaimCache,
            "有维护在跑 → 不可按（同一个全局会话锁）",
        )
        assertFalse(idle.copy(reclaimingCache = true).canReclaimCache, "自己正在回收 → 不可按（防连点）")
    }

    @Test
    fun `缓存尺寸只报数字，全机一份那句在行标题上`() {
        val p = NpmProjectSnapshot(
            projectId = "demo",
            installed = emptyList(),
            offlineGap = emptyList(),
            storage = stats(0),
            cache = NodeModulesStats(projectId = "demo", pkgCount = 1, totalBytes = 3L * 1024 * 1024),
        )
        val s = NpmState.of(NpmPanelSnapshot(listOf(p)))
        assertEquals("3 MB", s.cacheSizeLabel, "数字归数字；「全机一份」由界面那行标题说")
        assertNull(NpmState.of(NpmPanelSnapshot(listOf(project("demo")))).cacheSizeLabel, "量不到 → 不画")
    }

    /**
     * 每行那个尺寸读数（2026-10-17 用户口径「依赖列表显示一下各个依赖占用的空间大小」）。
     *
     * 三态各自钉一句：量到就说数字、**量不到就不画**、量到 0 也**不画** ——
     * 后两样都来自宿主同一个约定（`sizeBytes <= 0` = 这个包不在盘上，见 [NpmRowState.sizeLabel]），
     * 而 "0 B" 在屏幕上说的是"这个包不占地方"，那是另一句话。
     * 宿主侧量得对不对不由这一条管（那是 `InstallCoordinatorTest` 的事）。
     */
    @Test
    fun `每行尺寸：量到说数字，量不到、量到 0 都不画`() {
        val state = NpmState.of(
            NpmPanelSnapshot(
                listOf(
                    project(
                        "demo",
                        installed = listOf(
                            PkgNode("esbuild", "0.21.5", 3L * 1024 * 1024),
                            PkgNode("@babel/core", "7.24.0"),
                            PkgNode("zero", "1.0.0", 0),
                            PkgNode("tiny", "1.0.0", 512),
                        ),
                    ),
                ),
            ),
        )
        assertEquals(
            listOf("3 MB", null, null, "512 B"),
            state.installed.map { it.sizeLabel },
            "没量到与量到 0 都不画；量到的照实说",
        )
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
        )
        val state = NpmState.of(NpmPanelSnapshot(listOf(withGap)))
        assertEquals(listOf("esbuild"), state.project!!.offlineGap.map { it.name })
    }

}
