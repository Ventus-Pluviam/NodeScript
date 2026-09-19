package com.autoscript.shell

import com.autoscript.appservice.runtime.FixedEnginePool
import com.autoscript.appservice.runtime.PoolStats
import com.autoscript.appservice.runtime.RuntimeController
import com.autoscript.appservice.scheduler.core.PendingRun
import com.autoscript.appservice.scheduler.core.RunOutcome
import com.autoscript.appservice.scheduler.core.ScreenGuarantee
import com.autoscript.appservice.scheduler.core.TriggerSource
import com.autoscript.domain.engine.EngineId
import com.autoscript.domain.engine.StopResult
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ControllerRunDispatcherTest {

    private fun pending(
        nonce: String = "n-${System.nanoTime()}",
        timeoutMillis: Long? = null,
    ) = PendingRun(
        projectId = "p1",
        scriptPath = "a.js",
        runNonce = nonce,
        trigger = TriggerSource.TIMED,
        scheduledAtMillis = 0L,
        screen = ScreenGuarantee.ANY,
        timeoutMillis = timeoutMillis,
    )

    private fun rig(
        capacity: Int = 1,
        gate: ScreenGate = ScreenGate.AllowAll,
        failOnExecute: Boolean = false,
        stopResult: StopResult = StopResult.Clean,
        autoExitAfterMillis: Long? = 50,
    ): Triple<ControllerRunDispatcher, RuntimeController, MutableList<FakeEngineForDispatcher>> {
        val engines = MutableList(capacity) {
            FakeEngineForDispatcher(EngineId(it), autoExitAfterMillis).also { e ->
                e.failOnExecute = failOnExecute
                e.stopResult = stopResult
            }
        }
        val pool = FixedEnginePool({ id -> engines[id.poolIndex] }, capacity)
        val controller = RuntimeController(pool)
        return Triple(ControllerRunDispatcher(controller, gate), controller, engines)
    }

    @Test
    fun `正常投递 Succeeded 且 runNonce 透传引擎`() = runBlocking {
        val (d, controller, engines) = rig()
        val p = pending()
        assertEquals(RunOutcome.Succeeded, d.dispatch(p))
        assertEquals(p.runNonce, engines[0].executed.single().runNonce)
        assertTrue(controller.activeRunIds().isEmpty(), "结算后在途归档")
    }

    @Test
    fun `屏幕门禁拒绝回 Failed 且不占槽`() = runBlocking {
        val (d, controller, engines) = rig(gate = ScreenGate { ScreenGateDecision.Deny("熄屏") })
        assertEquals(RunOutcome.Failed, d.dispatch(pending()))
        assertTrue(engines[0].executed.isEmpty(), "门禁拒绝不得投递引擎")
        assertTrue(controller.activeRunIds().isEmpty())
    }

    @Test
    fun `引擎启动失败回 Crashed 保留原文`() = runBlocking {
        val (d, _, _) = rig(failOnExecute = true)
        val outcome = d.dispatch(pending())
        val crashed = assertInstanceOf(RunOutcome.Crashed::class.java, outcome)
        assertTrue(crashed.message!!.contains("fake boot failure"))
    }

    @Test
    fun `满池排队超时回 Cancelled`() = runBlocking {
        val (_, controller, _) = rig()
        // 先占满槽（直接经 controller，不经 dispatcher）
        val first = assertInstanceOf(
            RuntimeController.StartOutcome.Started::class.java,
            controller.start(
                com.autoscript.appservice.runtime.PoolAcquireRequest("p0", "hold.js"),
            ),
        )
        val d2 = ControllerRunDispatcher(controller, queueTimeoutMillis = 100)
        assertEquals(RunOutcome.Cancelled, d2.dispatch(pending()))
        controller.stop(first.runId)
        // 释放后恢复：dispatcher 可再次投递成功
        val d3 = ControllerRunDispatcher(controller)
        assertEquals(RunOutcome.Succeeded, d3.dispatch(pending()))
    }

    @Test
    fun `软停超时回 Failed`() = runBlocking {
        val (d, _, _) = rig(stopResult = StopResult.TimedOut(partial = false))
        // 脚本自退出（STOPPED）后 release 走 quiesce → TimedOut → StopTimeout → Failed
        assertEquals(RunOutcome.Failed, d.dispatch(pending()))
    }

    @Test
    fun `悬挂脚本等待超时强杀回 Crashed（zombie 不可构造）`() = runBlocking {
        val (_, controller, engines) = rig(autoExitAfterMillis = null)
        val dispatcher = ControllerRunDispatcher(
            controller,
            screenGate = ScreenGate.AllowAll,
            defaultAwaitTimeoutMillis = 300,
        )
        val outcome = dispatcher.dispatch(pending())
        val crashed = assertInstanceOf(RunOutcome.Crashed::class.java, outcome)
        assertTrue(crashed.message!!.contains("已强杀"), "须如实记录强杀: ${crashed.message}")
        assertEquals(1, engines[0].killCalls, "超时必须 kill，不留悬挂 RUNNING")
        assertTrue(controller.activeRunIds().isEmpty())
    }

    @Test
    fun `脚本自身超时透传为等待上限`() = runBlocking {
        val (d, _, engines) = rig()
        assertEquals(RunOutcome.Succeeded, d.dispatch(pending(timeoutMillis = 5_000)))
        assertEquals(5_000L, engines[0].executed.single().timeoutMillis)
    }

    @Test
    fun `槽位释放：连续两次投递复用同一槽`() = runBlocking {
        val (d, controller, _) = rig()
        assertEquals(RunOutcome.Succeeded, d.dispatch(pending()))
        assertEquals(RunOutcome.Succeeded, d.dispatch(pending()))
        assertEquals(PoolStats(1, free = 1, busy = 0), controller.stats())
    }
}
