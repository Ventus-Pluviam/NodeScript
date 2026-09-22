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
import com.autoscript.domain.scripts.EngineRunLink
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * §8.5 归档入口验证（:app 装配层）：dispatcher 必须**只对真的产生了引擎执行的投递**
 * 产出 [EngineRunLink]，让 intent 日志行与引擎 RunRecord 成对可追溯；
 * 门禁拒绝 / 排队超时 / 启动失败的 link 一律 null（绝不写孤儿记录）。
 */
class ControllerRunDispatcherArchiveTest {

    private fun pending(
        nonce: String,
        intentRunId: Long?,
        timeoutMillis: Long? = null,
    ) = PendingRun(
        projectId = "p1",
        scriptPath = "a.js",
        runNonce = nonce,
        trigger = TriggerSource.USER_CLICK,
        scheduledAtMillis = 0L,
        screen = ScreenGuarantee.ANY,
        timeoutMillis = timeoutMillis,
        intentRunId = intentRunId,
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
        return Triple(ControllerRunDispatcher(controller, gate), controller, engines)
    }

    @Test
    fun `成功投递产出 intent 与 engine 双 id 关联`() = runBlocking {
        val (d, _, engines) = rig()

        val report = d.dispatchToReport(pending("nonce-ok", intentRunId = 42))

        assertEquals(RunOutcome.Succeeded, report.outcome)
        assertNotNull(report.link, "成功的投递必须带双 id 关联")
        val link = report.link!!
        assertEquals(42L, link.intentRunId)
        assertEquals(engines[0].receiptRunIds.single(), link.engineRunId, "engineRunId = EngineRunReceipt.runId")
    }

    @Test
    fun `崩溃终态也带关联（引擎侧确有执行）`() = runBlocking {
        val (_, controller, _) = rig(autoExitAfterMillis = null)
        val d2 = ControllerRunDispatcher(controller, defaultAwaitTimeoutMillis = 300)

        val report = d2.dispatchToReport(pending("nonce-zombie", intentRunId = 7, timeoutMillis = 300))

        val crashed = assertInstanceOf(RunOutcome.Crashed::class.java, report.outcome)
        assertTrue(crashed.message!!.contains("已强杀"), "须如实记录强杀: ${crashed.message}")
        assertNotNull(report.link, "强杀结算的 run 已执行过，必须可追溯")
        assertEquals(7L, report.link!!.intentRunId)
    }

    @Test
    fun `屏幕门禁拒绝不产出关联（无引擎执行）`() = runBlocking {
        val (d, _, engines) = rig(gate = ScreenGate { ScreenGateDecision.Deny("熄屏") })

        val report = d.dispatchToReport(pending("nonce-gate", intentRunId = 1))

        assertEquals(RunOutcome.Failed, report.outcome)
        assertNull(report.link, "未投递引擎 → 如实无关联")
        assertTrue(engines[0].executed.isEmpty(), "门禁拒绝不得投递引擎")
    }

    @Test
    fun `排队超时不产出关联（未获槽，未执行）`() = runBlocking {
        val (_, controller, _) = rig()
        val held = assertInstanceOf(
            RuntimeController.StartOutcome.Started::class.java,
            controller.start(PoolAcquireRequest("p0", "hold.js")),
        )
        val d2 = ControllerRunDispatcher(controller, queueTimeoutMillis = 100)

        val report = d2.dispatchToReport(pending("nonce-queue", intentRunId = 2))

        assertEquals(RunOutcome.Cancelled, report.outcome)
        assertNull(report.link, "排队取消未执行引擎 → 无关联")
        controller.stop(held.runId)
        Unit                                           // 显式收尾：void 返回值才被 JUnit5 视为测试
    }

    @Test
    fun `引擎启动失败不产出关联`() = runBlocking {
        val (d, _, _) = rig(failOnExecute = true)

        val report = d.dispatchToReport(pending("nonce-boot", intentRunId = 3))

        assertInstanceOf(RunOutcome.Crashed::class.java, report.outcome)
        assertNull(report.link, "启动失败 = 没有引擎 RunRecord 可追")
    }

    @Test
    fun `未挂意图日志的直投用哨兵 intentRunId 关联而非伪造`() = runBlocking {
        val (d, _, _) = rig()

        val report = d.dispatchToReport(pending("nonce-direct", intentRunId = null))

        assertEquals(RunOutcome.Succeeded, report.outcome)
        assertNotNull(report.link)
        val link = report.link!!
        assertEquals(ControllerRunDispatcher.NO_INTENT_RUN_ID, link.intentRunId)
        assertTrue(link.engineRunId > 0)
    }

    @Test
    fun `成功投递回执带可调用的停止入口`() = runBlocking {
        val (d, controller, _) = rig()

        val report = d.dispatchToReport(pending("nonce-stop", intentRunId = 11))

        assertEquals(RunOutcome.Succeeded, report.outcome)
        assertNotNull(report.link, "link 与 stop 同源：有 link 才有 stop")
        assertNotNull(report.stop, "start 成功即绑定停止入口（§4.1 归口）")
        // 已结算后调用：幂等 no-op（AlreadyGone），不抛
        report.stop!!()
        assertTrue(controller.activeRunIds().isEmpty())
    }

    @Test
    fun `停止入口走优雅停语义，永不升级为 kill`() = runBlocking {
        val (d, controller, engines) = rig()

        val report = d.dispatchToReport(pending("nonce-stopkind", intentRunId = 14))

        assertEquals(RunOutcome.Succeeded, report.outcome)
        report.stop!!()
        assertEquals(0, engines.sumOf { it.killCalls }, "stop 闭包经 controller.stop：已结算落 AlreadyGone，绝不补 kill")
        assertTrue(controller.activeRunIds().isEmpty())
        assertEquals(PoolStats(1, free = 1, busy = 0), controller.stats(), "槽位记账不受 stop 调用影响")
    }

    @Test
    fun `未产生引擎执行的投递停止入口为 null`() = runBlocking {
        // 门禁拒绝：未投递引擎 → link 与 stop 双 null
        val (denied_d, _, _) = rig(gate = ScreenGate { ScreenGateDecision.Deny("熄屏") })
        val denied = denied_d.dispatchToReport(pending("nonce-denied", intentRunId = 12))
        assertNull(denied.link)
        assertNull(denied.stop, "门禁拒绝：无 link 即无 stop，不持有假句柄")

        // 排队超时：未获槽 → link 与 stop 双 null
        val (_, controller, _) = rig()
        val held = assertInstanceOf(
            RuntimeController.StartOutcome.Started::class.java,
            controller.start(PoolAcquireRequest("p0", "hold.js")),
        )
        val queued = ControllerRunDispatcher(controller, queueTimeoutMillis = 100)
        val cancelled = queued.dispatchToReport(pending("nonce-queued", intentRunId = 13))
        assertNull(cancelled.link)
        assertNull(cancelled.stop, "排队取消：未产生引擎执行，无可停的东西")
        controller.stop(held.runId)
        Unit
    }

    @Test
    fun `dispatch 与 dispatchToReport 同源：结果一致`() = runBlocking {
        val (d, _, _) = rig()
        val p = pending("nonce-same", intentRunId = 9)
        val plain = d.dispatch(p)
        assertEquals(RunOutcome.Succeeded, plain)
        // 第二次投递：同一 PendingRun 两次执行，outcome 一致（link 各带各的 engineRunId）
        assertEquals(RunOutcome.Succeeded, d.dispatchToReport(pending("nonce-same-2", intentRunId = 9)).outcome)
    }
}
