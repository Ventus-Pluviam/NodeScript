package com.autoscript.ui.state

import com.autoscript.domain.npm.InstallHandle
import com.autoscript.domain.npm.NpmCacheReclaimReport
import com.autoscript.domain.npm.NpmMaintenanceAction
import com.autoscript.domain.npm.NpmPanelSnapshot
import com.autoscript.domain.npm.NpmProjectSnapshot
import com.autoscript.domain.npm.NodeModulesStats
import com.autoscript.domain.npm.NpmTargetScope
import com.autoscript.domain.npm.PkgNode
import com.autoscript.domain.npm.NpmConsoleLine
import com.autoscript.domain.npm.NpmConsoleLineKind
import com.autoscript.domain.npm.NpmConsoleSnapshot
import com.autoscript.domain.npm.SequencedConsoleLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 维护动作三落点 + 缓存回收回执的用例（§10.9 第 5 条）。
 *
 * 断的是「界面会怎么说、会不会冒充」，不是 npm 的行为：
 * - 失败原文**原样透传**（`ci` 的验签拒绝那句里已经写清了为什么拒，界面再译一遍就是第二份判据）；
 * - 回执在挂起调用返回后才说完成，不再暗中串接一次全局安装；
 * - 回收回执要带上删/留两侧数字 —— 用户按下去就是为了看那个数字变没变。
 */
class NpmMaintenanceOpsTest {

    private fun project(id: String = "demo", bytes: Long = 10L * 1024 * 1024) = NpmProjectSnapshot(
        projectId = id,
        installed = emptyList(),
        offlineGap = emptyList(),
        storage = NodeModulesStats(projectId = id, pkgCount = 1, totalBytes = bytes),
    )

    private fun loaded(vararg projects: NpmProjectSnapshot) =
        NpmState.of(NpmPanelSnapshot(projects.toList()))

    /**
     * 在途写回：提交那一瞬先置位、清账，别的交给挂起的执行协程。
     *
     * 两条各钉一句：维护与回收的**专属位**置上（防连点的判据就是
     * `canReclaimCache`/`canInstall` 读的那几个位），旧账（上次动作的 opError/opNotice）
     * 一并清掉 —— 新动作的开局不该还挂着上一件事的回执。
     */
    @Test
    fun `在途写回：维护位与回收位各自置上，并清掉旧账`() {
        val busy = loaded(project()).copy(opError = "旧的失败", opNotice = "旧的回执")
        val m = busy.withMaintenanceInFlight(NpmMaintenanceAction.PRUNE)
        assertEquals(NpmMaintenanceAction.PRUNE, m.maintenance)
        assertNull(m.opError, "新动作开局不许挂着上一件事的账")
        assertNull(m.opNotice)

        val r = busy.withCacheReclaimInFlight()
        assertTrue(r.reclaimingCache)
        assertFalse(r.canReclaimCache, "回收在途 → 防连点的判据立即生效")
        assertNull(r.opError)
        assertNull(r.opNotice)
    }

    /** 记录被调过的 (projectId, action)，并可按需失败。 */
    private class MaintenanceHost(
        private val fail: Throwable? = null,
        private val report: NpmCacheReclaimReport = NpmCacheReclaimReport(3, 3L * 1024 * 1024, 7, 9L * 1024 * 1024, 7, false),
        private val snapshot: () -> NpmPanelSnapshot = { NpmPanelSnapshot(listOf()) },
    ) : FakeHost() {
        val calls = mutableListOf<Pair<String, NpmMaintenanceAction>>()
        var reclaims = 0
        var snapshotReads = 0
        val promoted = mutableListOf<String>()

        override suspend fun npmSnapshot(): NpmPanelSnapshot {
            snapshotReads++
            return snapshot()
        }

        override suspend fun runNpmMaintenance(projectId: String, action: NpmMaintenanceAction): InstallHandle {
            calls += projectId to action
            fail?.let { throw it }
            return InstallHandle(id = "h1", projectId = projectId, enqueuedAtMillis = 1L)
        }

        override suspend fun reclaimNpmCache(): NpmCacheReclaimReport {
            reclaims++
            fail?.let { throw it }
            return report
        }

        override suspend fun promoteNpmToGlobal(projectId: String): InstallHandle {
            promoted += projectId
            error("维护当前作用域不应触发全局安装")
        }
    }

    // ═══ 三颗维护按钮 ═══

    @Test
    fun `成功：动作落到选中项目，回执说完成式（返回时动作已跑完）`() = runBlocking {
        val host = MaintenanceHost()
        val next = runNpmMaintenanceOp(host, loaded(project("demo")), NpmMaintenanceAction.PRUNE)

        assertEquals(listOf("demo" to NpmMaintenanceAction.PRUNE), host.calls)
        assertNull(next.maintenance, "跑完必须把忙碌标记清掉，否则按钮永远转圈")
        assertNull(next.opError)
        assertTrue(next.opNotice!!.contains("已完成"), "调用返回时动作已跑完，完成式才是实话：${next.opNotice}")
        assertFalse(next.opNotice!!.contains("已入队"), "「入队即返回」假设已过时，不许说它还在跑：${next.opNotice}")
    }

    @Test
    fun `每个动作的回执互不相同（不然用户不知道按的是哪一颗）`() {
        val notices = NpmMaintenanceAction.entries.map { action ->
            runBlocking {
                runNpmMaintenanceOp(MaintenanceHost(), loaded(project()), action).opNotice
            }
        }
        // 枚举仍是三个取值（界面只露 prune/dedupe，ci 没有入口），回执各自可辨。
        assertEquals(3, notices.toSet().size, "每个动作的回执必须各自可辨：$notices")
        assertTrue(notices[2]!!.contains("lock 签名已校验"), "ci 先校验 lock 签名这件事，回执得说：${notices[2]}")
    }

    @Test
    fun `失败原文原样透传（不译成第二份判据）`() = runBlocking {
        val raw = "lock.sig 缺失：按 lock 重建前必须先有一次成功安装（ERR_PERMISSION_DENIED）"
        val next = runNpmMaintenanceOp(
            MaintenanceHost(fail = IllegalStateException(raw)),
            loaded(project()),
            NpmMaintenanceAction.CI,
        )
        assertEquals(raw, next.opError, "失败原文必须原样到达界面")
        assertNull(next.opNotice, "失败不许留成功回执")
        assertNull(next.maintenance, "失败也要把忙碌标记清掉")
    }

    @Test
    fun `没选作用域 → 如实说没有作用域，不假装跑过`() = runBlocking {
        val host = MaintenanceHost()
        val next = runNpmMaintenanceOp(host, NpmState.of(NpmPanelSnapshot(emptyList())), NpmMaintenanceAction.DEDUPE)
        assertTrue(host.calls.isEmpty(), "没有选中作用域就不该调宿主")
        assertTrue(next.opError!!.contains("没有选中的作用域"), "实为 ${next.opError}")
    }

    @Test
    fun `宿主未接线 → opError 原文（不冒充「已清理」）`() = runBlocking {
        val next = runNpmMaintenanceOp(null, loaded(project()), NpmMaintenanceAction.PRUNE)
        assertTrue(next.opError!!.contains("未接线"), "实为 ${next.opError}")
        assertNull(next.opNotice)
    }

    @Test
    fun `动作成功后清单按宿主现取（界面不自己先抹）`() = runBlocking {
        // 宿主说 prune 完只剩 1MB：界面必须显示 1MB，而不是沿用操作前的 10MB
        val host = MaintenanceHost(snapshot = { NpmPanelSnapshot(listOf(project("demo", 1L * 1024 * 1024))) })
        val next = runNpmMaintenanceOp(host, loaded(project("demo", 10L * 1024 * 1024)), NpmMaintenanceAction.PRUNE)
        assertEquals("1 MB", next.sizeLabel, "回读的才是现状")
    }

    // ═══ 去重只维护当前作用域 ═══

    @Test
    fun `项目去重不把相同或不同版本的依赖复制或替换到全局`() = runBlocking {
        val local = project("demo").copy(installed = listOf(PkgNode("axios", "1.7.0"), PkgNode("@scope/tool", "2.0.0")))
        val refreshed = local.copy(storage = local.storage!!.copy(totalBytes = 1024 * 1024))
        for (globalVersion in listOf(null, "1.7.0", "1.8.0", "2.0.0")) {
            val global = project(NpmTargetScope.GLOBAL).copy(
                installed = globalVersion?.let { listOf(PkgNode("axios", it), PkgNode("@scope/tool", it)) }.orEmpty(),
            )
            val before = NpmPanelSnapshot(listOf(global, local, project("other")))
            val after = NpmPanelSnapshot(listOf(global, refreshed, project("other")))
            val host = MaintenanceHost(snapshot = { after })
            val picked = NpmState.withProject(NpmState.of(before), "demo")
            val next = runNpmMaintenanceOp(host, picked, NpmMaintenanceAction.DEDUPE)

            assertEquals(listOf("demo" to NpmMaintenanceAction.DEDUPE), host.calls)
            assertTrue(host.promoted.isEmpty(), "全局版本 $globalVersion：去重不能顺带安装或替换全局依赖")
            assertEquals(1, host.snapshotReads, "完成后只现取一次真实快照")
            assertEquals(after, next.snapshot, "全局与其他项目保持宿主原样，不能伪造已经移走项目依赖")
            assertEquals("demo", next.selectedProjectId)
            assertEquals(listOf("1.7.0", "2.0.0"), next.installed.map { it.version })
            assertEquals("1 MB", next.sizeLabel, "当前作用域的去重结果必须回读")
            assertNull(next.maintenance)
            assertNull(next.opError)
            assertTrue(next.opNotice!!.contains("当前作用域"))
            assertFalse(next.opNotice!!.contains("提升"))
        }
        Unit
    }

    @Test
    fun `全局去重只维护全局树，不改项目也不触发提升`() = runBlocking {
        val snapshot = NpmPanelSnapshot(listOf(project(NpmTargetScope.GLOBAL), project("demo")))
        val host = MaintenanceHost(snapshot = { snapshot })
        val next = runNpmMaintenanceOp(host, NpmState.of(snapshot), NpmMaintenanceAction.DEDUPE)

        assertEquals(listOf(NpmTargetScope.GLOBAL to NpmMaintenanceAction.DEDUPE), host.calls)
        assertTrue(host.promoted.isEmpty())
        assertEquals(1, host.snapshotReads)
        assertEquals(snapshot, next.snapshot)
        assertEquals(NpmTargetScope.GLOBAL, next.selectedProjectId)
        assertNull(next.maintenance)
        assertNull(next.opError)
        assertTrue(next.opNotice!!.contains("dedupe 已完成"))
        assertFalse(next.opNotice!!.contains("提升"), "去重不再包含需要解释跳过的提升步骤")
    }

    @Test
    fun `去重失败保留原清单和失败原文，不再执行全局安装`() = runBlocking {
        val raw = "npm 退出码 1: ERESOLVE could not resolve"
        val host = MaintenanceHost(fail = IllegalStateException(raw))
        val previous = loaded(project("demo").copy(installed = listOf(PkgNode("axios", "1.7.0"))))
        val next = runNpmMaintenanceOp(host, previous, NpmMaintenanceAction.DEDUPE)

        assertEquals(listOf("demo" to NpmMaintenanceAction.DEDUPE), host.calls)
        assertEquals(raw, next.opError, "失败原文必须原样到达界面")
        assertTrue(host.promoted.isEmpty())
        assertEquals(0, host.snapshotReads, "失败不伪造刷新后的成功清单")
        assertEquals(previous.snapshot, next.snapshot)
        assertNull(next.opNotice)
        assertNull(next.maintenance)
    }

    @Test
    fun `取消去重原样传播，不吞成失败也不续跑全局安装`() = runBlocking {
        val host = MaintenanceHost(fail = CancellationException("页面已离开"))
        val failure = runCatching {
            runNpmMaintenanceOp(host, loaded(project("demo")), NpmMaintenanceAction.DEDUPE)
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertEquals(listOf("demo" to NpmMaintenanceAction.DEDUPE), host.calls)
        assertTrue(host.promoted.isEmpty())
        assertEquals(0, host.snapshotReads)
        Unit
    }

    // ═══ 缓存回收 ═══

    @Test
    fun `回收回执带删与留两侧数字（按下去就是为了看这个）`() = runBlocking {
        val host = MaintenanceHost()
        val next = reclaimNpmCacheOp(host, loaded(project()))

        assertEquals(1, host.reclaims)
        assertFalse(next.reclaimingCache, "跑完要清忙碌标记")
        val n = next.opNotice!!
        assertTrue(n.contains("删除 3 项"), "删了多少要说：$n")
        assertTrue(n.contains("保留 7 项"), "留了多少更要说（缓存不是越空越好）：$n")
        assertTrue(n.contains("离线重装不受影响"), "还有内容就要说清离线没坏：$n")
    }

    @Test
    fun `缓存被清空 → 回执如实说下次安装必须联网`() = runBlocking {
        val host = MaintenanceHost(report = NpmCacheReclaimReport(9, 9L * 1024 * 1024, 0, 0, 0, false))
        val n = reclaimNpmCacheOp(host, loaded(project())).opNotice!!
        assertTrue(n.contains("下次安装需联网"), "空了就是空了，不许说「离线仍可用」：$n")
    }

    @Test
    fun `顺带修了悬空索引 → 回执点名（那是看不见的收益）`() = runBlocking {
        val host = MaintenanceHost(report = NpmCacheReclaimReport(1, 1024, 5, 2048, 5, true))
        val n = reclaimNpmCacheOp(host, loaded(project())).opNotice!!
        assertTrue(n.contains("悬空"), "修好了要说：$n")
    }

    @Test
    fun `回收失败原文透传且不留回执`() = runBlocking {
        val next = reclaimNpmCacheOp(
            MaintenanceHost(fail = IllegalStateException("缓存回收失败（/data/cache/npm-cache）：Permission denied")),
            loaded(project()),
        )
        assertTrue(next.opError!!.contains("Permission denied"))
        assertNull(next.opNotice)
        assertFalse(next.reclaimingCache)
    }

    // ═══ 日志大框那一段的取数 ═══

    @Test
    fun `现取读数时顺带把 npm 输出拉进日志框`() = runBlocking {
        // 宿主缺省那份（`consoleOutput`）在真装配里是有内容的，这里给一段假的：
        // 断言的是**接线**（读口 → `NpmState.withConsole`），不是环本身的行为。
        val host = object : FakeHost() {
            override suspend fun npmSnapshot(): NpmPanelSnapshot = NpmPanelSnapshot(listOf(project("demo")))

            override suspend fun consoleOutput(projectId: String, sinceSeq: Long, maxLines: Int): NpmConsoleSnapshot =
                NpmConsoleSnapshot(
                    firstSeq = 1,
                    lastSeq = 2,
                    lines = listOf(
                        SequencedConsoleLine(1, NpmConsoleLine(NpmConsoleLineKind.ECHO, "$ npm install axios", 0L)),
                        SequencedConsoleLine(2, NpmConsoleLine(NpmConsoleLineKind.RESULT, "退出码 0", 0L, ok = true)),
                    ),
                    running = false,
                )
        }
        val state = loadNpmSnapshot(host, NpmState.NOT_LOADED)
        assertEquals(2, state.consoleLines.size, "快照与日志必须是同一趟现取（两条各拉一次会互相矛盾）")
        assertEquals("$ npm install axios", state.consoleLines.first().text)
        assertEquals(listOf("退出码 0"), state.consoleLines.drop(1).map { it.text })
    }

    @Test
    fun `日志读失败不算这次操作失败（两条账分开）`() = runBlocking {
        // 这份替身**只**给快照，`consoleOutput` 走 FakeHost 的"未提供"（抛）——
        // 日志是装饰性的读数：它读不到，清单照样要画出来，也不该在页面上报一句错。
        val host = object : FakeHost() {
            override suspend fun npmSnapshot(): NpmPanelSnapshot = NpmPanelSnapshot(listOf(project("demo")))
        }
        val state = loadNpmSnapshot(host, NpmState.NOT_LOADED)
        assertTrue(state.load.isLoaded, "清单读到了就是读到了，不被日志那半拖下水")
        assertEquals("demo", state.selectedProjectId)
        assertNull(state.opError, "日志读不到不许冒充成操作失败")
        assertTrue(state.consoleLines.isEmpty())
    }

    @Test
    fun `回收后必须回读快照（否则尺寸那行还是旧值，看着像没生效）`() = runBlocking {
        val host = MaintenanceHost(snapshot = { NpmPanelSnapshot(listOf(project("demo", 2L * 1024 * 1024))) })
        val next = reclaimNpmCacheOp(host, loaded(project("demo", 10L * 1024 * 1024)))
        assertEquals("2 MB", next.sizeLabel)
    }

    @Test
    fun `回收不需要选中项目（缓存是全局的，不挂在某个项目下）`() = runBlocking {
        val host = MaintenanceHost()
        val empty = NpmState.of(NpmPanelSnapshot(emptyList()))
        val next = reclaimNpmCacheOp(host, empty)
        assertEquals(1, host.reclaims, "一个项目都没有时也该能回收缓存")
        assertNull(next.opError)
    }

    @Test
    fun `宿主未接线时回收也如实失败`() = runBlocking {
        val next = reclaimNpmCacheOp(null, loaded(project()))
        assertTrue(next.opError!!.contains("未接线"))
    }
}
