package com.autoscript.appservice.runtime

import com.autoscript.domain.engine.EngineId
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.system.measureTimeMillis
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 外审 B1（2026-10-11）的守卫：**排队中的 start 不得阻塞 stop/killAll/看门狗**。
 *
 * 病灶：`RuntimeController.start` 整段持 `guard`，而 `pool.acquire` 会在满池时挂起等证
 * （默认容量 1、调度触发等 60–120s）。于是「A 在跑、B 排队」时，A 的 stop/killAll 与
 * 看门狗取锚全被 B 的排队挡住 —— 现场表现为「脚本停不下来、急停按不动」，B 还会排队超时。
 *
 * 这条用例是本仓「本机绿不是门禁有效」纪律的兑现：它**先红后绿** —— 修之前
 * `stop` 耗时 ≈ B 的排队超时（3s），修之后 ≈ 0。
 */
class RuntimeControllerQueuedStartTest {

    private fun controller(capacity: Int = 1): RuntimeController {
        val list = MutableList(capacity) { FakeEngine(EngineId(it)) }
        return RuntimeController(FixedEnginePool({ id -> list[id.poolIndex] }, capacity), WatchdogPolicy())
    }

    @Test
    fun `持有者被停时排队中的 start 不挡 stop`() = runBlocking {
        val c = controller()
        val a = assertInstanceOf(
            RuntimeController.StartOutcome.Started::class.java,
            c.start(PoolAcquireRequest("p1", "a.js")),
        ).runId
        // B 排队（容量 1，唯一槽被 A 占着）：它会挂在 acquire 的等证上。
        val b = async { c.start(PoolAcquireRequest("p2", "b.js", waitTimeoutMillis = 3_000)) }
        delay(100)   // 让 B 真进到等证那一步

        val stopMillis = measureTimeMillis { c.stop(a) }
        assertTrue(
            stopMillis < 1_000,
            "stop 被排队中的 start 挡了 ${stopMillis}ms（应≈0）：start 整段持 guard 而 acquire 会挂起",
        )
        // 停掉 A 即腾出槽位：B 必须能拿到它（而不是一路排到超时）。
        val bOutcome = b.await()
        assertInstanceOf(
            RuntimeController.StartOutcome.Started::class.java, bOutcome,
            "A 释放槽位后 B 该立刻拿到，实际 $bOutcome",
        )
        c.stop((bOutcome as RuntimeController.StartOutcome.Started).runId)
        Unit
    }

    @Test
    fun `持有者被停时排队中的 start 不挡 killAll`() = runBlocking {
        val c = controller()
        c.start(PoolAcquireRequest("p1", "a.js"))
        val b = async { c.start(PoolAcquireRequest("p2", "b.js", waitTimeoutMillis = 3_000)) }
        delay(100)

        val killAllMillis = measureTimeMillis { c.killAll(com.autoscript.domain.engine.KillCause.WATCHDOG_CPU) }
        assertTrue(
            killAllMillis < 1_000,
            "killAll（急停/看门狗裁决的落点）被排队中的 start 挡了 ${killAllMillis}ms —— 急停按不动正是这条",
        )
        b.await()
        Unit
    }

    @Test
    fun `持有者被停时排队中的 start 不挡看门狗取锚`() = runBlocking {
        val c = controller()
        c.start(PoolAcquireRequest("p1", "a.js"))
        val b = async { c.start(PoolAcquireRequest("p2", "b.js", waitTimeoutMillis = 3_000)) }
        delay(100)

        val anchorMillis = measureTimeMillis { c.watchAnchors() }
        assertTrue(
            anchorMillis < 1_000,
            "看门狗取锚被排队中的 start 挡了 ${anchorMillis}ms：心跳失联/超时裁决会跟着停摆",
        )
        b.await()
        c.killAll(com.autoscript.domain.engine.KillCause.REQUESTED)
        Unit
    }
}
