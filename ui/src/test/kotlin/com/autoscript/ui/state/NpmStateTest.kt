package com.autoscript.ui.state

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
