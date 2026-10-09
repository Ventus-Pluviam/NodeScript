package com.autoscript.ui.state

import com.autoscript.domain.host.HostSummary
import com.autoscript.domain.npm.InstallEvent
import com.autoscript.domain.npm.InstallEventBatch
import com.autoscript.domain.npm.NpmConsoleHandle
import com.autoscript.domain.npm.NpmPanelSnapshot
import com.autoscript.domain.npm.NpmProjectSnapshot
import com.autoscript.domain.npm.NodeModulesStats
import com.autoscript.domain.npm.SequencedInstallEvent
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 依赖面板变更半边的用例（§10.9 第 1 条，2026-10-09 批 87）。
 *
 * 断的是「界面会拼出什么、会不会冒充」，不是 npm 的行为：
 *
 * - **拼装**：旗标只拼用户勾了的那两颗，且包名在前（与界面上勾选框的语序一致）；
 * - **门禁强度不取决于入口**：面板拼出来的一行走的是与控制台**同一条**宿主口
 *   （`runNpmPanelCommand`），故宿主侧的判据原样生效 —— 界面侧不判第二遍；
 * - **进度是阶段不是百分比**：`InstallEvent.Progress.percent` 全仓从无赋值，
 *   画百分比就是编一个拿不到的数；终态到了要把条收起来（否则屏幕上永远停着"进行中"）。
 */
class NpmInstallOpsTest {

    private fun project(id: String = "demo") = NpmProjectSnapshot(
        projectId = id,
        installed = emptyList(),
        offlineGap = emptyList(),
        storage = NodeModulesStats(projectId = id, pkgCount = 1, totalBytes = 1024L),
        quotaBytes = 512L * 1024 * 1024,
        quotaWarnRatio = 0.8,
    )

    private fun loaded(vararg projects: NpmProjectSnapshot) =
        NpmState.of(NpmPanelSnapshot(projects.toList(), emptyList()))

    /** 记录被提交的那一行，并可按需失败 / 按需回一批事件。 */
    private class PanelHost(
        private val fail: Throwable? = null,
        private val events: List<InstallEvent> = emptyList(),
    ) : FakeHost() {
        val lines = mutableListOf<String>()
        var polls = 0
        var lastSince = -1L

        override suspend fun npmSnapshot(): NpmPanelSnapshot = NpmPanelSnapshot(emptyList(), emptyList())

        override suspend fun runNpmPanelCommand(projectId: String, line: String): NpmConsoleHandle {
            lines += line
            fail?.let { throw it }
            return NpmConsoleHandle(handleId = "con-1", projectId = projectId, line = line, enqueuedAtMillis = 1L)
        }

        /** 事件环按 seq 排（第 i 条 seq = i+1），只回 `seq > sinceSeq` 的那些 —— 与真环同形。 */
        override suspend fun npmInstallEvents(projectId: String, sinceSeq: Long, maxBatch: Int): InstallEventBatch {
            polls++
            lastSince = sinceSeq
            val picked = events.mapIndexedNotNull { i, e ->
                if (i + 1 > sinceSeq) SequencedInstallEvent((i + 1).toLong(), e) else null
            }
            if (picked.isEmpty()) return InstallEventBatch(sinceSeq, sinceSeq, emptyList())
            return InstallEventBatch(picked.first().seq, picked.last().seq, picked)
        }
    }

    // ── 拼装（纯函数） ──────────────────────────────────────────────────────

    @Test
    fun `拼装：包名在前，旗标只拼勾了的那两颗`() {
        assertEquals("npm install axios", buildInstallCommand("axios", dev = false, offline = false))
        assertEquals("npm install axios -D", buildInstallCommand(" axios ", dev = true, offline = false))
        assertEquals(
            "npm install axios@1.7.0 -D --prefer-offline",
            buildInstallCommand("axios@1.7.0", dev = true, offline = true),
        )
    }

    @Test
    fun `拼装：空输入拼不出东西（返回 null，不拼一个裸的 npm install）`() {
        assertNull(buildInstallCommand("", dev = true, offline = true))
        assertNull(buildInstallCommand("   ", dev = false, offline = false))
    }

    @Test
    fun `卸载：包名来自清单，命令是 npm uninstall`() {
        assertEquals("npm uninstall axios", buildRemoveCommand("axios"))
    }

    // ── 提交 ────────────────────────────────────────────────────────────────

    @Test
    fun `提交：把拼好的那行交给宿主口，成功清草稿、失败留草稿`() = runBlocking {
        val host = PanelHost()
        val ok = submitInstall(host, loaded(project("demo")).copy(installDraft = "axios", installDev = true))
        assertEquals(listOf("npm install axios -D"), host.lines)
        assertEquals("", ok.installDraft, "成功之后草稿要清 —— 那行已经在控制台里回显了")
        assertFalse(ok.installing)
        assertNull(ok.opError)

        val boom = PanelHost(fail = IllegalStateException("磁盘可用 100MB < 预检下限 500MB，拒绝安装"))
        val bad = submitInstall(boom, loaded(project("demo")).copy(installDraft = "axios"))
        assertEquals("npm install axios", boom.lines.single())
        assertEquals("axios", bad.installDraft, "失败要留草稿：用户多半要改一改再敲")
        assertEquals("磁盘可用 100MB < 预检下限 500MB，拒绝安装", bad.opError, "失败原文原样透传")
        assertFalse(bad.installing)
    }

    @Test
    fun `提交：git 依赖在界面侧当场被拒（判据与控制台同一份），且不往返一趟`() = runBlocking {
        val host = PanelHost()
        val state = submitInstall(host, loaded(project("demo")).copy(installDraft = "git+https://x/y.git"))
        assertTrue(host.lines.isEmpty(), "判据不过就不该发出去")
        assertTrue(state.opError!!.contains("git: 依赖不支持"), "拒收话术来自 NpmConsoleKeys，不是界面另编的")
    }

    @Test
    fun `提交：没有选中项目时不发，且说的是"没选项目"而不是"安装失败"`() = runBlocking {
        val host = PanelHost()
        val empty = NpmState.NOT_LOADED.copy(installDraft = "axios")
        val state = submitInstall(host, empty)
        assertTrue(host.lines.isEmpty())
        assertTrue(state.opError!!.contains("还没有选中的项目"))
    }

    @Test
    fun `提交：空输入当场被告知写什么，不往返也不报"安装失败"`() = runBlocking {
        // 这条路是可达的：安装那颗按钮在草稿为空时禁用，但输入行按回车不走那个禁用
        // （`InstallField` 的 onDone 只看 canInstall）。
        val host = PanelHost()
        val state = submitInstall(host, loaded(project("demo")).copy(installDraft = "   "))
        assertTrue(host.lines.isEmpty())
        assertTrue(state.opError!!.contains("先写要装什么"))
    }

    @Test
    fun `提交：宿主摘要未接线时说的是"没接线"，不是"安装失败"`() = runBlocking {
        val state = submitInstall(null, loaded(project("demo")).copy(installDraft = "axios"))
        assertTrue(state.opError!!.contains("宿主摘要未接线"))
        assertFalse(state.installing)
    }

    @Test
    fun `进度：拉的不是当前项目时原样返回（不把别的项目的阶段折进来）`() = runBlocking {
        val host = PanelHost(events = listOf(InstallEvent.Progress("p1", "h1", InstallEvent.Phase.REIFY)))
        val state = loaded(project("p2")).copy(installProgress = InstallProgressState(InstallEvent.Phase.QUEUED))
        val after = pollInstallEvents(host, state, "p1")
        assertEquals(state, after, "界面正看着 p2，p1 的事件不该折进这条阶段条")
        assertEquals(0, host.polls, "连拉都不该拉")
    }

    @Test
    fun `卸载：走同一条命令通道（门禁强度不取决于入口）`() = runBlocking {
        val host = PanelHost()
        val state = removeInstalled(host, loaded(project("demo")), "esbuild")
        assertEquals(listOf("npm uninstall esbuild"), host.lines)
        assertNull(state.opError)
    }

    @Test
    fun `提交超长输入：当场拒，不往返`() = runBlocking {
        val host = PanelHost()
        val state = submitInstall(host, loaded(project("demo")).copy(installDraft = "a".repeat(201)))
        assertTrue(host.lines.isEmpty())
        assertTrue(state.opError!!.contains("输入过长"))
    }

    // ── 进度：阶段不是百分比 ─────────────────────────────────────────────────

    @Test
    fun `进度：一批事件折成当前阶段，终态把条收起来`() {
        val folded = foldProgress(
            InstallProgressState(InstallEvent.Phase.QUEUED),
            listOf(
                InstallEvent.Progress("demo", "h1", InstallEvent.Phase.RESOLVE),
                InstallEvent.Progress("demo", "h1", InstallEvent.Phase.DOWNLOAD, pkg = "axios"),
            ),
        )!!
        assertEquals(InstallEvent.Phase.DOWNLOAD, folded.phase)
        assertEquals("axios", folded.pkg)
        assertFalse(folded.done)

        val done = foldProgress(
            folded,
            listOf(InstallEvent.Finished("demo", "h1", success = true, detail = "npm install 完成")),
        )!!
        assertTrue(done.done)
        assertTrue(done.ok)
        assertEquals("npm install 完成", done.detail)
        // 收起来之后不该还停在"进行中"（否则一次失败之后屏幕上永远停着一条进度）
        assertEquals("完成", InstallProgressState(InstallEvent.Phase.DONE, done = true).label)
    }

    @Test
    fun `进度：失败终态带上病因，且 ok=false`() {
        val done = foldProgress(
            InstallProgressState(InstallEvent.Phase.REIFY),
            listOf(InstallEvent.Finished("demo", "h1", success = false, detail = "EINTEGRITY")),
        )!!
        assertTrue(done.done)
        assertFalse(done.ok)
        assertEquals("EINTEGRITY", done.detail)
    }

    @Test
    fun `进度：警告不进阶段条（那是控制台那条流的事，同一句不显示两处）`() {
        val before = InstallProgressState(InstallEvent.Phase.DOWNLOAD)
        val after = foldProgress(
            before,
            listOf(InstallEvent.Warning("demo", "h1", InstallEvent.Kind.DISK_QUOTA, message = "配额 80%")),
        )
        assertEquals(before, after)
    }

    @Test
    fun `进度：游标只进不退，拉过一次之后从新游标续拉`() = runBlocking {
        val host = PanelHost(events = listOf(InstallEvent.Progress("demo", "h1", InstallEvent.Phase.RESOLVE)))
        val first = pollInstallEvents(host, loaded(project("demo")), "demo")
        assertEquals(1L, first.installSeq)
        assertEquals(InstallEvent.Phase.RESOLVE, first.installProgress!!.phase)

        val second = pollInstallEvents(host, first, "demo")
        assertEquals(1L, host.lastSince, "第二次要带上次的游标，不能从 0 重放")
        assertEquals(1L, second.installSeq)
    }

    @Test
    fun `进度：读失败不动已有进度，也不把它抬成 opError`() = runBlocking {
        val host = object : FakeHost() {
            override suspend fun npmInstallEvents(projectId: String, sinceSeq: Long, maxBatch: Int): InstallEventBatch =
                error("壳未装配")
        }
        val before = loaded(project("demo")).copy(
            installProgress = InstallProgressState(InstallEvent.Phase.REIFY),
            installSeq = 7L,
        )
        val after = pollInstallEvents(host, before, "demo")
        assertEquals(before.installProgress, after.installProgress, "读不到进度不该把阶段条抹掉")
        assertEquals(7L, after.installSeq, "游标只进不退：读失败也不清零")
        assertNull(after.opError, "进度是装饰性读数，读失败不该看起来像安装失败")
    }

    @Test
    fun `进度：换项目要把阶段条与游标一起归零（否则新项目的历史事件会被漏掉）`() {
        val before = loaded(project("p1"), project("p2")).copy(
            installProgress = InstallProgressState(InstallEvent.Phase.REIFY),
            installSeq = 9L,
            installDraft = "axios",
            installDev = true,
            installing = true,
        )
        val after = NpmState.withProject(before, "p2")
        assertEquals("p2", after.selectedProjectId)
        assertNull(after.installProgress, "游标归零了却留着旧阶段条，会画出一条不属于这个项目的进度")
        assertEquals(0L, after.installSeq, "seq 是环内全局单调的：沿用旧游标会漏掉 seq 更小的那些事件")
        assertEquals("axios", after.installDraft, "草稿是用户的输入，换项目不该清掉")
        assertTrue(after.installDev, "旗标同上")
        assertTrue(after.installing, "安装会话是全局互斥的，换个项目看不会让它停下来")

        // 选中的还是同一个项目 → 原样返回（不把在跑的阶段条平白清掉）。
        assertEquals(before, NpmState.withProject(before, "p1"))
    }

    // ── 现取不抹操作面 ──────────────────────────────────────────────────────

    @Test
    fun `现取：不许把用户的草稿、旗标、进度抹掉`() {
        val previous = loaded(project("demo")).copy(
            installDraft = "axios",
            installDev = true,
            installOffline = true,
            installProgress = InstallProgressState(InstallEvent.Phase.DOWNLOAD),
            installSeq = 3L,
        )
        val refreshed = NpmState.of(
            NpmPanelSnapshot(listOf(project("demo")), emptyList()),
            previous,
        )
        assertEquals("axios", refreshed.installDraft)
        assertTrue(refreshed.installDev)
        assertTrue(refreshed.installOffline)
        assertEquals(InstallEvent.Phase.DOWNLOAD, refreshed.installProgress!!.phase)
        assertEquals(3L, refreshed.installSeq)
    }

    @Test
    fun `输入行可提交的判据：没读到、没项目、有动作在跑，都不行`() {
        assertFalse(NpmState.NOT_LOADED.canInstall)
        assertFalse(loaded(project("demo")).copy(installing = true).canInstall)
        assertFalse(loaded(project("demo")).copy(reclaimingCache = true).canInstall)
        assertFalse(
            loaded(project("demo")).copy(maintenance = com.autoscript.domain.npm.NpmMaintenanceAction.PRUNE).canInstall,
        )
        assertTrue(loaded(project("demo")).canInstall)
    }

    /** 缓存一栏的文案（§10.9 第 5 条的 `npm-cache` 尺寸）：量不到不画，且点明全机一份。 */
    @Test
    fun `缓存栏：量不到不画；量到了点明"全机一份"`() {
        assertNull(loaded(project("demo")).cacheLabel, "老宿主没这个口 → 不画，不显示 0 字节")

        val withCache = NpmProjectSnapshot(
            projectId = "demo",
            installed = emptyList(),
            offlineGap = emptyList(),
            storage = NodeModulesStats("demo", 1, 1024L),
            quotaBytes = 512L * 1024 * 1024,
            quotaWarnRatio = 0.8,
            cache = NodeModulesStats("", 0, 12L * 1024 * 1024),
        )
        val label = loaded(withCache).cacheLabel!!
        assertTrue(label.contains("全机一份"), "缓存跨项目共享，写成「本项目缓存」是撒谎")
        assertTrue(label.contains("12 MB"))
    }

    /** 这条替身必须覆盖新口 —— 否则用例会在误用替身时拿到假绿。 */
    @Test
    fun `替身未覆盖的口响亮失败（不回空快照）`() {
        val host: HostSummary = object : FakeHost() {}
        val t = runCatching { runBlocking { host.npmInstallEvents("demo", 0, 8) } }.exceptionOrNull()
        assertTrue(t is UnsupportedOperationException)
        val t2 = runCatching { runBlocking { host.runNpmPanelCommand("demo", "npm ls") } }.exceptionOrNull()
        assertTrue(t2 is UnsupportedOperationException)
    }

    /** 维护动作与安装入口不串线：安装只碰 `runNpmPanelCommand` + 事件口，不碰维护/回收。 */
    @Test
    fun `维护动作与安装入口不串线`() = runBlocking {
        val host = PanelHost()
        submitInstall(host, loaded(project("demo")).copy(installDraft = "axios"))
        assertEquals(1, host.lines.size, "只提交了一次命令")
        assertEquals(1, host.polls, "只拉了一次进度")
    }
}
