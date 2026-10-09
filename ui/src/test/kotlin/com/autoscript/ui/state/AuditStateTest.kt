package com.autoscript.ui.state

import com.autoscript.domain.npm.InstallHistoryEntry
import com.autoscript.domain.npm.InstallHistoryOp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlinx.coroutines.runBlocking

/**
 * 审计页呈现态的用例：这里断的是"界面会怎么说"，不是审计怎么写。
 *
 * 重点在三条**不许冒充**：
 * - 未知 op **原样显示**（不编一句「未知操作」）；
 * - 空 projectId 是**全局**（不是"项目号丢了"），且筛选掉失败行时必须说出来；
 * - 读失败 ≠ 空审计（[AuditState.failed] 保留已读到的那份）。
 */
class AuditStateTest {

    private fun entry(
        op: String,
        projectId: String = "demo",
        success: Boolean = true,
        detail: String? = null,
        at: Long = 1_700_000_000_000,
    ) = InstallHistoryEntry(op = op, projectId = projectId, success = success, detail = detail, atMillis = at)

    @Test
    fun `首帧与成功空审计分开`() {
        assertFalse(AuditState.NOT_LOADED.load.isLoaded)
        assertTrue(AuditState.NOT_LOADED.entries.isEmpty())
        assertNull(AuditState.NOT_LOADED.projectFilter)

        val empty = AuditState.of(emptyList())
        assertTrue(empty.load.isLoaded, "读到了且真的没有 —— 与没读到是两句话")
        assertTrue(empty.visible.isEmpty())
        assertEquals(listOf(AuditFilterOption(null, "全部")), empty.projectOptions)
    }

    @Test
    fun `最新在最上，筛选条里全局排第一`() {
        val state = AuditState.of(
            listOf(
                entry(InstallHistoryOp.INSTALL, "alpha", at = 1),
                entry(InstallHistoryOp.REGISTRY, projectId = "", at = 2),
                entry(InstallHistoryOp.PRUNE, "beta", at = 3),
            ),
        )
        assertEquals(
            listOf(InstallHistoryOp.PRUNE, InstallHistoryOp.REGISTRY, InstallHistoryOp.INSTALL),
            state.visible.map { it.op },
            "文件里最新在最后，页面上最新在最上（第一眼该是最近发生的事）",
        )
        assertEquals(
            listOf(null, "", "alpha", "beta"),
            state.projectOptions.map { it.value },
            "全局那一档排在项目前面：它不是某个项目的记录，放中间会读成「叫空名字的项目」",
        )
        assertEquals("全局", state.visible[1].projectLabel)
    }

    @Test
    fun `未知 op 原样显示，不编人话`() {
        val row = AuditRowState.of(entry("future-op"))
        assertEquals("future-op", row.opLabel, "认不出就照原样写：编「未知操作」是把『还不认识』说成『记录有问题』")
        assertEquals("future-op", row.op)
        assertEquals("安装依赖", AuditRowState.of(entry(InstallHistoryOp.INSTALL)).opLabel)
        assertEquals("镜像源变更", AuditRowState.of(entry(InstallHistoryOp.REGISTRY)).opLabel)
    }

    @Test
    fun `筛选只影响显示，且藏掉的失败必须说出来`() {
        val state = AuditState.of(
            listOf(
                entry(InstallHistoryOp.INSTALL, "alpha"),
                entry(InstallHistoryOp.INSTALL, "beta", success = false, detail = "磁盘可用 12MB < 预检下限 500MB"),
                entry(InstallHistoryOp.REGISTRY, projectId = "", success = false),
            ),
        )
        val onlyAlpha = filterAudit(state, "alpha")
        assertEquals(1, onlyAlpha.visible.size)
        assertEquals(0, onlyAlpha.failureCount, "这个筛选下确实没有失败")
        assertEquals(2, onlyAlpha.totalFailureCount, "但全部记录里有两条 —— 界面据此说明「筛掉的不代表没有」")
        assertEquals(3, onlyAlpha.entries.size, "筛选只影响显示，读数一条不少（宿主那条读口本来无参）")

        val all = filterAudit(state, null)
        assertEquals(2, all.failureCount)
        assertEquals(
            "磁盘可用 12MB < 预检下限 500MB",
            all.visible.first { it.projectId == "beta" }.detail,
            "失败原因是原文，不重编",
        )
    }

    @Test
    fun `刷新保留仍在的筛选，没了就回全部`() {
        val first = filterAudit(AuditState.of(listOf(entry(InstallHistoryOp.INSTALL, "alpha"))), "alpha")
        val kept = AuditState.of(listOf(entry(InstallHistoryOp.INSTALL, "alpha")), first)
        assertEquals("alpha", kept.projectFilter, "刷新不该把用户正在看的项目换掉")

        val gone = AuditState.of(listOf(entry(InstallHistoryOp.INSTALL, "beta")), first)
        assertNull(gone.projectFilter, "那个项目从记录里没了 → 回到「全部」，不筛出一个空列表")
    }

    @Test
    fun `读失败保留已读到的那份`() {
        val loaded = AuditState.of(listOf(entry(InstallHistoryOp.INSTALL, "alpha")))
        val failed = AuditState.failed(IllegalStateException("读盘失败：权限不足"), loaded)
        assertFalse(failed.load.isLoaded)
        assertEquals("读盘失败：权限不足", failed.load.failedReason())
        assertEquals(1, failed.entries.size, "一次瞬时失败不该把审计抹成空 —— 空表在界面上是「你没做过任何操作」")
    }

    /** 读口替身：只覆盖 `npmHistory()`，其余一律响亮失败（`FakeHost` 的口径）。 */
    private class AuditHost(
        private val rows: List<InstallHistoryEntry> = emptyList(),
        private val failRead: Exception? = null,
    ) : FakeHost() {
        override suspend fun npmHistory(): List<InstallHistoryEntry> {
            failRead?.let { throw it }
            return rows
        }
    }

    @Test
    fun `读口三落点：未接线与抛错都带原文，成功才覆盖`() = runBlocking {
        val previous = AuditState.of(listOf(entry(InstallHistoryOp.INSTALL, "alpha")))

        val unattached = loadAudit(null, previous)
        assertFalse(unattached.load.isLoaded)
        assertTrue(
            unattached.load.failedReason()!!.contains("未接线"),
            "没接线要说「没接线」，不能画成「你没做过任何操作」",
        )
        assertEquals(1, unattached.entries.size, "读失败保留已读到的那份")

        val boom = loadAudit(AuditHost(failRead = java.io.IOException("盘只读")), previous)
        assertEquals("盘只读", boom.load.failedReason())

        val ok = loadAudit(AuditHost(listOf(entry(InstallHistoryOp.PRUNE, "beta"))), previous)
        assertTrue(ok.load.isLoaded)
        assertEquals(listOf("beta"), ok.entries.map { it.projectId }, "成功是全量覆盖，不累积")
        assertNull(ok.projectFilter, "上一个筛选（alpha）在新记录里没了 → 回全部，不筛出一个空列表")
    }

    @Test
    fun `复制出去的那串字与屏幕上看到的一致`() {
        val text = AuditRowState.of(
            entry(InstallHistoryOp.CI, "alpha", success = false, detail = "lock 签名失败: 密钥不可用"),
        ).copyText()
        assertTrue(text.contains("alpha"))
        assertTrue(text.contains("按 lock 重建（ci）"))
        assertTrue(text.contains("失败"))
        assertTrue(text.contains("lock 签名失败: 密钥不可用"))
    }
}
