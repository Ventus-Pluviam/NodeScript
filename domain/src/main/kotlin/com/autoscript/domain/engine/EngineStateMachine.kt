package com.autoscript.domain.engine

/**
 * 引擎生命周期状态机（docs/framework-design.md §8）。
 * 纯逻辑：RuntimeController / ScriptEngine 实现方驱动，非法转移抛 [IllegalStateTransition]。
 *
 * IDLE → BOOTING → RUNNING → QUIESCING → STOPPED → IDLE（回收复用）
 *    ↘           ↘ → CRASHED（启动失败）  ↗ → CRASHED（异常）
 * 任意非 IDLE 状态可入 CRASHED（看门狗：心跳缺失/CPU 风暴/OOM/引擎自毁）。
 * kill() 按原因归类：REQUESTED → STOPPED；异常原因 → CRASHED。
 */
class EngineStateMachine(private val initial: EngineStatus = EngineStatus.IDLE) {

    var status: EngineStatus = initial
        private set

    /** 合法转移表。key = 当前状态 → 允许的目标状态集（允许集取超集，语义见各调用点）。 */
    private val allowed: Map<EngineStatus, Set<EngineStatus>> = mapOf(
        EngineStatus.IDLE to setOf(EngineStatus.BOOTING, EngineStatus.CRASHED),
        EngineStatus.BOOTING to setOf(EngineStatus.RUNNING, EngineStatus.QUIESCING, EngineStatus.CRASHED),
        EngineStatus.RUNNING to setOf(EngineStatus.QUIESCING, EngineStatus.CRASHED, EngineStatus.STOPPED),
        EngineStatus.QUIESCING to setOf(EngineStatus.STOPPED, EngineStatus.CRASHED),
        EngineStatus.STOPPED to setOf(EngineStatus.IDLE, EngineStatus.CRASHED),
        EngineStatus.CRASHED to setOf(EngineStatus.IDLE),   // 崩溃后必须经回收回 IDLE 复用
    )

    fun transition(target: EngineStatus) {
        val from = status
        val ok = target in allowed.getValue(from)
        if (!ok) throw IllegalStateTransition(from, target)
        status = target
    }

    /** 启动：IDLE → BOOTING。 */
    fun onExecuteRequested() = transition(EngineStatus.BOOTING)

    /** 启动完成 / 失败。 */
    fun onBootCompleted() = transition(EngineStatus.RUNNING)
    fun onBootFailed() = transition(EngineStatus.CRASHED)

    /** 优雅停止开始：RUNNING|BOOTING → QUIESCING。 */
    fun onQuiesceStart() {
        check(status == EngineStatus.RUNNING || status == EngineStatus.BOOTING) {
            "仅 RUNNING|BOOTING 可进入 QUIESCING（当前 $status）"
        }
        status = EngineStatus.QUIESCING
    }

    /** 四步 quiesce 干净完成：QUIESCING → STOPPED。 */
    fun onQuiesceCompleted() = transition(EngineStatus.STOPPED)

    /** 崩溃/看门狗：任意非 IDLE 状态入 CRASHED。 */
    fun onCrash() {
        check(status != EngineStatus.IDLE) { "IDLE 状态不可直接崩溃" }
        status = EngineStatus.CRASHED
    }

    /**
     * 强制 kill：按原因归类。
     * REQUESTED（管理者主动）→ STOPPED；其余（心跳/CPU/OOM/引擎请求）→ CRASHED。
     */
    fun onKill(cause: KillCause) {
        if (cause == KillCause.REQUESTED) {
            status = EngineStatus.STOPPED
        } else {
            status = EngineStatus.CRASHED
        }
    }

    /** 回收复用：STOPPED|CRASHED → IDLE。 */
    fun onRecycle() = transition(EngineStatus.IDLE)

    val isLive: Boolean
        get() = status == EngineStatus.BOOTING || status == EngineStatus.RUNNING || status == EngineStatus.QUIESCING
}

class IllegalStateTransition(val from: EngineStatus, val target: EngineStatus) :
    RuntimeException("非法引擎状态转移 $from → $target（见 §8 状态机）")