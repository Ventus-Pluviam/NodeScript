package com.autoscript.appservice.packager.npm

import com.autoscript.domain.npm.ApprovalAction
import com.autoscript.domain.npm.ApprovalDecision
import com.autoscript.domain.npm.ApprovalRequest
import com.autoscript.domain.npm.ApprovalStatus
import com.autoscript.domain.npm.ApprovalTicket
import com.autoscript.domain.npm.InstallEvent
import com.autoscript.domain.npm.PackageManagerFacade
import java.util.concurrent.ConcurrentHashMap

/**
 * 审批账本（docs/framework-design.md §10.5 人机分离）：
 *
 * - [requestApprove] 只入队（PENDING），永不执行；
 * - [resolveApproval] 仅 UI 审批卡回调可携人工决定落账——PENDING→APPROVED/REJECTED 单向；
 * - 审批记录绑定 `pkg+versionHash`：[isApproved] 按此键查询，版本升级（hash 变化）自动失配必须重批；
 * - EXPIRED 由调用方按 requestedAtMillis+ttl 判（账本只存事实，不内置时钟策略——测试注入 now）。
 *
 * 持久化由实现层决定（P0 内存账本 + JVM 单测；Android 生产落 `files/.autojs/approve-ledger.json`，
 * HMAC keyed 于 :main，§10.2 存储布局）。
 */
class ApprovalLedger(
    private val store: ApprovalStore? = null,
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    private val lock = Any()
    private val records = LinkedHashMap<String, ApprovalTicket>()          // requestId → 票
    private val requests = LinkedHashMap<String, ApprovalRequest>()        // requestId → 请求
    private var seq = store?.lastSeq() ?: 0L

    /** 入队（幂等：同 projectId+pkg+versionHash+action 的 PENDING 请求合并复用同一张票）。 */
    fun submit(projectId: String, pkg: String, versionHash: String, action: ApprovalAction): ApprovalTicket =
        synchronized(lock) {
            val dup = requests.values.firstOrNull {
                it.projectId == projectId && it.pkg == pkg && it.versionHash == versionHash &&
                    it.action == action && records[it.id]?.status == ApprovalStatus.PENDING
            }
            if (dup != null) return records[dup.id]!!
            val id = "apr-${++seq}"
            val req = ApprovalRequest(id, projectId, pkg, versionHash, action, now())
            requests[id] = req
            val ticket = ApprovalTicket(id, ApprovalStatus.PENDING)
            records[id] = ticket
            store?.insertSubmit(req)
            ticket
        }

    /** UI 审批卡回调：PENDING → APPROVED/REJECTED（单向，已决票不可改）。 */
    fun resolve(requestId: String, decision: ApprovalDecision): ApprovalTicket = synchronized(lock) {
        val cur = records[requestId] ?: throw IllegalArgumentException("审批票不存在: $requestId")
        if (cur.status != ApprovalStatus.PENDING) return cur
        val next = ApprovalTicket(
            requestId,
            if (decision == ApprovalDecision.APPROVE) ApprovalStatus.APPROVED else ApprovalStatus.REJECTED,
            decidedAtMillis = now(),
        )
        records[requestId] = next
        store?.insertResolve(requestId, next.status, next.decidedAtMillis ?: now())
        next
    }

    /** 执行前校验（runScript/exec/install lifecycle）：存在匹配键的 APPROVED 票才放行。 */
    fun isApproved(projectId: String, pkg: String, versionHash: String, action: ApprovalAction): Boolean =
        synchronized(lock) {
            requests.values.any {
                it.projectId == projectId && it.pkg == pkg && it.versionHash == versionHash &&
                    it.action == action && records[it.id]?.status == ApprovalStatus.APPROVED
            }
        }

    init {
        store?.all()?.forEach { e ->
            requests[e.request.id] = e.request
            records[e.request.id] = ApprovalTicket(e.request.id, e.status, e.decidedAtMillis)
            seq = maxOf(seq, e.request.id.removePrefix("apr-").toLongOrNull() ?: 0L)
        }
    }

    fun pending(projectId: String): List<ApprovalRequest> = synchronized(lock) {
        requests.values.filter { it.projectId == projectId && records[it.id]?.status == ApprovalStatus.PENDING }
    }

    fun all(): List<Pair<ApprovalRequest, ApprovalTicket>> = synchronized(lock) {
        requests.values.map { it to records[it.id]!! }
    }
}
