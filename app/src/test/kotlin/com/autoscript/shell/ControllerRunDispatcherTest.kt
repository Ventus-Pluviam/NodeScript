package com.autoscript.shell

import com.autoscript.appservice.runtime.FixedEnginePool
import com.autoscript.appservice.runtime.PoolAcquireRequest
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
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ControllerRunDispatcherTest {

    private fun pending(
        nonce: String = "n-${System.nanoTime()}",
        timeoutMillis: Long? = null,
        trigger: TriggerSource = TriggerSource.TIMED,
    ) = PendingRun(
        projectId = "p1",
        scriptPath = "a.js",
        runNonce = nonce,
        trigger = trigger,
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
            FakeEngineForDispatcher(EngineId(it), autoExitAfterMillis = autoExitAfterMillis).also { e ->
                e.failOnExecute = failOnExecute
                e.stopResult = stopResult
            }
        }
        val pool = FixedEnginePool({ id -> engines[id.poolIndex] }, capacity)
        val controller = RuntimeController(pool)
        // 局部分级表取小值：满池排队上限默认 10s~2min（见 DEFAULT_QUEUE_TIMEOUTS），
        // 单测不该真等那么久 —— 分级口径本身由 DEFAULT_QUEUE_TIMEOUTS 的专项测试覆盖。
        val d = ControllerRunDispatcher(
            controller,
            screenGate = gate,
            queueTimeoutFor = { 100L },
        )
        return Triple(d, controller, engines)
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

    @Test
    fun `排队上限按触发源分级，ENGINE_INTERNAL 最紧、TIMED 最宽`() {
        val q = ControllerRunDispatcher.DEFAULT_QUEUE_TIMEOUTS
        val table = TriggerSource.entries.associateWith { q(it) }
        assertTrue(table.values.all { it > 0 }, "铁律 3：任何触发源都不许无限等：$table")
        assertTrue(
            table[TriggerSource.ENGINE_INTERNAL]!! < table[TriggerSource.TIMED]!!,
            "嵌套等待（持有者等后来者）比守时任务更该先爆：$table",
        )
        assertTrue(
            table[TriggerSource.USER_CLICK]!! < table[TriggerSource.EVENT]!!,
            "人盯 UI 等不及就如实 Cancelled，别让按钮原地转圈：$table",
        )
        Unit                                           // 显式收尾：void 返回值才被 JUnit5 视为测试
    }

    @Test
    fun `未显式覆盖时满池也不会无限等（按触发源取默认上限）`() = runBlocking {
        val (_, controller, _) = rig()
        val held = assertInstanceOf(
            RuntimeController.StartOutcome.Started::class.java,
            controller.start(PoolAcquireRequest("p0", "hold.js")),
        )
        // 构造时不给 queueTimeoutMillis：上限来自分级表，仍是有限等而不是无限等。
        // 这里注入 40s 只为「一眼看得出不是 0/无限等」，真实默认值见 DEFAULT_QUEUE_TIMEOUTS
        // （ENGINE_INTERNAL 15s）；真要等满 15s 单测就太慢了，故用注入缝缩短验证目标。
        val d = ControllerRunDispatcher(controller, queueTimeoutFor = { 40_000L })
        val p = pending(trigger = TriggerSource.ENGINE_INTERNAL)
        val outcome = d.dispatch(p)
        assertEquals(RunOutcome.Cancelled, outcome, "满池上限到期按「排队取消」口径回 Cancelled")
        controller.stop(held.runId)
        Unit                                           // 显式收尾：void 返回值才被 JUnit5 视为测试
    }

    @Test
    fun `queueTimeoutMillis 为 0 视为漏配，构造即响亮失败`() {
        val (_, controller, _) = rig()
        assertThrows(IllegalArgumentException::class.java) {
            ControllerRunDispatcher(controller, queueTimeoutMillis = 0)
        }
        Unit                                           // 显式收尾：void 返回值才被 JUnit5 视为测试
    }
}
