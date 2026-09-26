package com.autoscript.domain.npm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 契约锚定测试（§10.7）：PackageManagerFacade 的方法面与 DTO 形状是 §10 的冻结点，
 * 任何签名变更都必须同步改本测试 + §10.7 文档。
 */
class PackageManagerFacadeContractTest {

    @Test
    fun `门面方法面冻结`() {
        val names = PackageManagerFacade::class.java.methods.map { it.name }.toSet() -
            setOf("equals", "hashCode", "toString", "wait", "notify", "notifyAll", "getClass", "getDeclaringClass")
        val expected = setOf(
            // 重操作
            "install", "ci", "update", "uninstall", "dedupe", "prune",
            "audit", "importOfflineBundle", "importTarball", "cancel",
            // 轻操作
            "list", "offlineGap", "config", "storage",
            // 审批（人机分离：requestApprove 仅入队 / resolveApproval 仅 UI 回调）
            "requestApprove", "resolveApproval", "pendingApprovals",
            // P1
            "runScript", "exec",
            // 事件流（Flow 供 :main 订阅；drain* 是脚本侧拉取口，§10.7）
            "progress", "approvals", "drainEvents", "drainApprovals",
            // 快照
            "exportSnapshot",
        )
        assertEquals(expected, names, "门面方法面必须与 §10.7 冻结清单一致")
    }

    @Test
    fun `拉取批次 DTO 形状（first 与 last 与 seq 三件套，与 a11y events 同口径）`() {
        assertEquals(
            listOf("firstSeq", "lastSeq", "events"),
            InstallEventBatch::class.java.declaredFields.map { it.name },
        )
        assertEquals(
            listOf("firstSeq", "lastSeq", "requests"),
            ApprovalBatch::class.java.declaredFields.map { it.name },
        )
        assertEquals(listOf("seq", "event"), SequencedInstallEvent::class.java.declaredFields.map { it.name })
        assertEquals(listOf("seq", "request"), SequencedApproval::class.java.declaredFields.map { it.name })
    }

    @Test
    fun `审批 DTO 绑定 pkg 与版本哈希`() {
        val props = ApprovalRequest::class.java.declaredFields.map { it.name }.toSet()
        assertTrue("versionHash" in props, "ApprovalRequest 必须携带 versionHash")
        assertTrue("pkg" in props)
        assertTrue("action" in props)
    }

    @Test
    fun `审批状态机四态`() {
        assertEquals(
            listOf("PENDING", "APPROVED", "REJECTED", "EXPIRED"),
            ApprovalStatus.entries.map { it.name },
        )
    }

    @Test
    fun `安装事件阶段覆盖安装全生命周期`() {
        assertEquals(
            listOf("QUEUED", "RESOLVE", "DOWNLOAD", "REIFY", "POST_CHECK", "DONE"),
            InstallEvent.Phase.entries.map { it.name },
        )
    }

    @Test
    fun `审计报告区分在线签名与离线 OSV`() {
        val props = AuditReport::class.java.declaredFields.map { it.name }
        assertTrue("offline" in props, "AuditReport.offline 是签名端点不可用时显式降级的载体")
    }
}
