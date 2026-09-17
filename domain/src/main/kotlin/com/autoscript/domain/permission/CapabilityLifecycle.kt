package com.autoscript.domain.permission

/**
 * 能力生命周期辅助（docs/framework-design.md §9.5）。
 * 纯逻辑：三态之间的允许转移与"是否可申请"判断；能力中心 UI / PermissionFacade 实现方消费。
 */
object CapabilityLifecycle {

    /** 允许的三态转移矩阵。语义：GRANTED 可因会话失效/回收降级；DEGRADED 可再降为 DENIED；任何态可回 GRANTED（重新授权）。 */
    private val allowedTransitions: Map<CapabilityState, Set<CapabilityState>> = mapOf(
        CapabilityState.GRANTED to setOf(CapabilityState.GRANTED, CapabilityState.DEGRADED, CapabilityState.DENIED),
        CapabilityState.DEGRADED to setOf(CapabilityState.GRANTED, CapabilityState.DEGRADED, CapabilityState.DENIED),
        CapabilityState.DENIED to setOf(CapabilityState.GRANTED, CapabilityState.DENIED),  // DENIED 须经重新授权，不能直接 DEGRADED
    )

    /** 事件驱动的三态推进。非法事件组合抛 [IllegalCapabilityTransition]。 */
    fun onEvent(from: CapabilityState, event: CapabilityEvent): CapabilityState {
        val target = when (event) {
            CapabilityEvent.GRANTED -> CapabilityState.GRANTED
            CapabilityEvent.DEGRADED -> CapabilityState.DEGRADED
            CapabilityEvent.REVOKED -> CapabilityState.DENIED
        }
        if (target !in allowedTransitions.getValue(from)) {
            throw IllegalCapabilityTransition(from, event)
        }
        return target
    }

    /** 该态是否可发起系统授权申请（能力中心"去授权"按钮显隐）。 */
    fun canRequestGrant(state: CapabilityState): Boolean =
        state == CapabilityState.DENIED || state == CapabilityState.DEGRADED

    /** 能否执行需要该能力的高信任操作（GRANTED 才可；DEGRADED 由各能力自行判降级路径）。 */
    fun usable(state: CapabilityState): Boolean = state != CapabilityState.DENIED
}

enum class CapabilityEvent { GRANTED, DEGRADED, REVOKED }

class IllegalCapabilityTransition(val from: CapabilityState, val event: CapabilityEvent) :
    RuntimeException("非法能力状态事件 $from + $event（§9.5 三态矩阵）")