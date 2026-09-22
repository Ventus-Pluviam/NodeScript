package com.autoscript.domain.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EngineStateMachineTest {

    @Test
    fun `happy path full lifecycle`() {
        val m = EngineStateMachine()
        assertEquals(EngineStatus.IDLE, m.status)

        m.onExecuteRequested(); assertEquals(EngineStatus.BOOTING, m.status)
        m.onBootCompleted();    assertEquals(EngineStatus.RUNNING, m.status)
        m.onQuiesceStart();     assertEquals(EngineStatus.QUIESCING, m.status)
        m.onQuiesceCompleted(); assertEquals(EngineStatus.STOPPED, m.status)
        m.onRecycle();          assertEquals(EngineStatus.IDLE, m.status)
    }

    @Test
    fun `crash from RUNNING goes CRASHED then recycle`() {
        val m = EngineStateMachine()
        m.onExecuteRequested()
        m.onBootCompleted()
        m.onCrash()
        assertEquals(EngineStatus.CRASHED, m.status)
        m.onRecycle()
        assertEquals(EngineStatus.IDLE, m.status)
    }

    @Test
    fun `invalid skip of BOOTING is rejected`() {
        val m = EngineStateMachine()
        assertThrows(IllegalStateTransition::class.java) { m.onBootCompleted() }  // IDLE → RUNNING
    }

    @Test
    fun `quiesce only allowed from RUNNING or BOOTING`() {
        val m = EngineStateMachine()
        assertThrows(IllegalStateTransition::class.java) { m.onQuiesceStart() }  // IDLE → QUIESCING 越表
        m.onExecuteRequested()
        m.onQuiesceStart()   // BOOTING → QUIESCING allowed
        assertEquals(EngineStatus.QUIESCING, m.status)
    }

    @Test
    fun `kill maps cause to terminal state`() {
        val m = EngineStateMachine()
        m.onExecuteRequested(); m.onBootCompleted()
        m.onKill(KillCause.REQUESTED)
        assertEquals(EngineStatus.STOPPED, m.status)

        val m2 = EngineStateMachine()
        m2.onExecuteRequested(); m2.onBootCompleted()
        m2.onKill(KillCause.WATCHDOG_CPU)
        assertEquals(EngineStatus.CRASHED, m2.status)

        // DRIFT 是仲裁层在宿主可疑时的主动杀（"他杀"），归 CRASHED 而非 STOPPED：
        // 归档/日志据此区分"管理者主动停"（REQUESTED → STOPPED）与"分歧杀"。
        val m3 = EngineStateMachine()
        m3.onExecuteRequested(); m3.onBootCompleted()
        m3.onKill(KillCause.DRIFT)
        assertEquals(EngineStatus.CRASHED, m3.status)

        // BOOTING 期 REQUESTED（启动失败收归/未跑起来就停）不伪造干净停 → CRASHED
        val m4 = EngineStateMachine()
        m4.onExecuteRequested()
        m4.onKill(KillCause.REQUESTED)
        assertEquals(EngineStatus.CRASHED, m4.status)

        // 已 CRASHED 再 kill：绕过转移表的直写已删除，必须抛非法转移（c6cf32c 反证点）
        val m5 = EngineStateMachine()
        m5.onExecuteRequested(); m5.onBootCompleted()
        m5.onKill(KillCause.WATCHDOG_CPU)
        assertThrows(IllegalStateTransition::class.java) { m5.onKill(KillCause.REQUESTED) }
    }

    @Test
    fun `isLive covers active states`() {
        val m = EngineStateMachine()
        assertFalse(m.isLive)
        m.onExecuteRequested()
        assertTrue(m.isLive)
        m.onBootCompleted()
        assertTrue(m.isLive)
        m.onQuiesceStart()
        assertTrue(m.isLive)
        m.onQuiesceCompleted()
        assertFalse(m.isLive)
    }
}