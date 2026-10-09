package com.autoscript.ui.state

import com.autoscript.domain.host.HostSummary
import com.autoscript.domain.npm.InstallHandle
import com.autoscript.domain.npm.NpmCacheReclaimReport
import com.autoscript.domain.npm.NpmMaintenanceAction
import com.autoscript.domain.npm.NpmPanelSnapshot
import com.autoscript.domain.npm.NpmProjectSnapshot
import com.autoscript.domain.npm.NodeModulesStats
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
 * - 回执**不许替用户宣布结果**（三颗按钮是入队，不是完成）；
 * - 回收回执要带上删/留两侧数字 —— 用户按下去就是为了看那个数字变没变。
 */
class NpmMaintenanceOpsTest {

    private fun project(id: String = "demo", bytes: Long = 10L * 1024 * 1024) = NpmProjectSnapshot(
        projectId = id,
        installed = emptyList(),
        offlineGap = emptyList(),
        storage = NodeModulesStats(projectId = id, pkgCount = 1, totalBytes = bytes),
        quotaBytes = 512L * 1024 * 1024,
        quotaWarnRatio = 0.8,
    )

    private fun loaded(vararg projects: NpmProjectSnapshot) =
        NpmState.of(NpmPanelSnapshot(projects.toList(), emptyList()))

    /** 记录被调过的 (projectId, action)，并可按需失败。 */
    private class MaintenanceHost(
        private val fail: Throwable? = null,
        private val report: NpmCacheReclaimReport = NpmCacheReclaimReport(3, 3L * 1024 * 1024, 7, 9L * 1024 * 1024, 7, false),
        private val snapshot: () -> NpmPanelSnapshot = { NpmPanelSnapshot(listOf(), emptyList()) },
    ) : FakeHost() {
        val calls = mutableListOf<Pair<String, NpmMaintenanceAction>>()
        var reclaims = 0

        override suspend fun npmSnapshot(): NpmPanelSnapshot = snapshot()

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
    }

    // ═══ 三颗维护按钮 ═══

    @Test
    fun `成功：动作落到选中项目，回执只说「已入队」不说「已完成」`() = runBlocking {
        val host = MaintenanceHost()
        val next = runNpmMaintenanceOp(host, loaded(project("demo")), NpmMaintenanceAction.PRUNE)

        assertEquals(listOf("demo" to NpmMaintenanceAction.PRUNE), host.calls)
        assertNull(next.maintenance, "跑完必须把忙碌标记清掉，否则按钮永远转圈")
        assertNull(next.opError)
        assertTrue(next.opNotice!!.contains("已入队"), "入队 ≠ 完成：${next.opNotice}")
        assertFalse(next.opNotice!!.contains("已完成"), "不许替用户宣布结果：${next.opNotice}")
    }

    @Test
    fun `三颗按钮各自的回执互不相同（不然用户不知道按的是哪颗）`() {
        val notices = NpmMaintenanceAction.entries.map { action ->
            runBlocking {
                runNpmMaintenanceOp(MaintenanceHost(), loaded(project()), action).opNotice
            }
        }
        assertEquals(3, notices.toSet().size, "三颗按钮的回执必须各自可辨：$notices")
        assertTrue(notices[2]!!.contains("验 lock 签名"), "ci 要先验 lock 签名，回执得先说：${notices[2]}")
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
    fun `没选项目 → 如实说没有项目，不假装跑过`() = runBlocking {
        val host = MaintenanceHost()
        val next = runNpmMaintenanceOp(host, NpmState.of(NpmPanelSnapshot(emptyList(), emptyList())), NpmMaintenanceAction.DEDUPE)
        assertTrue(host.calls.isEmpty(), "没有项目就不该调宿主")
        assertTrue(next.opError!!.contains("没有选中的项目"), "实为 ${next.opError}")
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
        val host = MaintenanceHost(snapshot = { NpmPanelSnapshot(listOf(project("demo", 1L * 1024 * 1024)), emptyList()) })
        val next = runNpmMaintenanceOp(host, loaded(project("demo", 10L * 1024 * 1024)), NpmMaintenanceAction.PRUNE)
        assertEquals("1 MB / 512 MB", next.quotaLabel, "回读的才是现状")
    }

    // ═══ 缓存回收 ═══

    @Test
    fun `回收回执带删与留两侧数字（按下去就是为了看这个）`() = runBlocking {
        val host = MaintenanceHost()
        val next = reclaimNpmCacheOp(host, loaded(project()))

        assertEquals(1, host.reclaims)
        assertFalse(next.reclaimingCache, "跑完要清忙碌标记")
        val n = next.opNotice!!
        assertTrue(n.contains("删了 3 条"), "删了多少要说：$n")
        assertTrue(n.contains("保留 7 条"), "留了多少更要说（缓存不是越空越好）：$n")
        assertTrue(n.contains("离线重装仍可用"), "还有内容就要说清离线没坏：$n")
    }

    @Test
    fun `缓存被清空 → 回执如实说下次安装必须联网`() = runBlocking {
        val host = MaintenanceHost(report = NpmCacheReclaimReport(9, 9L * 1024 * 1024, 0, 0, 0, false))
        val n = reclaimNpmCacheOp(host, loaded(project())).opNotice!!
        assertTrue(n.contains("下次安装必须联网"), "空了就是空了，不许说「离线仍可用」：$n")
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

    @Test
    fun `回收后必须回读快照（否则配额条还是旧值，看着像没生效）`() = runBlocking {
        val host = MaintenanceHost(snapshot = { NpmPanelSnapshot(listOf(project("demo", 2L * 1024 * 1024)), emptyList()) })
        val next = reclaimNpmCacheOp(host, loaded(project("demo", 10L * 1024 * 1024)))
        assertEquals("2 MB / 512 MB", next.quotaLabel)
    }

    @Test
    fun `回收不需要选中项目（缓存是全局的，不挂在某个项目下）`() = runBlocking {
        val host = MaintenanceHost()
        val empty = NpmState.of(NpmPanelSnapshot(emptyList(), emptyList()))
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
