package com.autoscript.appservice.runtime

import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.engine.EngineId
import com.autoscript.domain.engine.EngineStatus
import com.autoscript.domain.engine.KillCause
import com.autoscript.domain.engine.StopResult
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 未接线引擎宿主的契约诚实性 + 池记账不变（§1 诚实原则 / §8.2 推论 B）。
 *
 * 两件事：
 * 1. **不伪造任何执行**：execute 抛 ERR_NOT_IMPLEMENTED（真原因可读），status 恒 IDLE、
 *    pid 恒 null（§8.4：宿主不给 pid 就如实回 null，绝不给 0）；
 * 2. **失败路径不缩水池**：启动失败经池 `recycle` 收归 → 槽位复位 + 许可证归还，
 *    下一次 acquire 照样拿得到槽（三件事齐全才算这条终结路径写完）。
 */
class UnavailableEngineTest {

    @Test
    fun `execute 如实拒绝：ERR_NOT_IMPLEMENTED 且带上脚本与 nonce`() = runBlocking {
        val e = UnavailableEngine(EngineId(0))
        val thrown = assertInstanceOf(
            AutojsException::class.java,
            runCatching {
                e.execute(
                    com.autoscript.domain.engine.EngineRunRequest(
                        projectId = "p1",
                        scriptPath = "a.js",
                        runNonce = "nonce-1",
                    ),
                )
            }.exceptionOrNull(),
        )
        assertEquals(ErrorCode.ERR_NOT_IMPLEMENTED, thrown.error)
        assertTrue(thrown.message!!.contains("a.js"), "话术必须指名是哪个脚本：${thrown.message}")
        assertTrue(thrown.message!!.contains("nonce-1"), "幂等锚点要出现在真原因里：${thrown.message}")
    }

    @Test
    fun `pid 恒 null 且状态恒 IDLE（未启动过就不许暗示跑过）`() = runBlocking {
        val e = UnavailableEngine(EngineId(3))
        assertNull(e.pid, "§8.4：宿主不给 pid 如实 null，绝不给 0 或自身 pid")
        assertEquals(EngineStatus.IDLE, e.status())
        assertEquals(StopResult.Clean, e.stop(), "无执行体可排空 = 已净（不是假装优雅退出）")
        assertEquals(KillCause.REQUESTED, e.kill())
        assertEquals(EngineStatus.IDLE, e.status(), "stop/kill 都不改变'从未启动'这个事实")
    }

    /**
     * §8.2 推论 B 验收口径：一条终结路径写完，必须能看到
     * 「槽位复位 + 许可证归还」两件事（在途表摘除是 controller 侧，见下一测试）。
     */
    @Test
    fun `启动失败经池收归：槽位复位且许可证归还`() = runBlocking {
        val pool = FixedEnginePool({ id -> UnavailableEngine(id) }, capacity = 1)
        assertEquals(PoolStats(1, free = 1, busy = 0), pool.stats())

        val outcome = pool.acquire(PoolAcquireRequest("p1", "a.js", runNonce = "n1"))
        val failed = assertInstanceOf(PoolAcquireOutcome.Failed::class.java, outcome)
        assertTrue(failed.message.contains("ERR_NOT_IMPLEMENTED"), "失败原因保留引擎侧原文：${failed.message}")
        assertEquals(PoolStats(1, free = 1, busy = 0), pool.stats(), "启动失败必须还槽还证")

        // 池容量不缩水：下一次 acquire 仍能立刻获准（而不是挂到超时）
        val again = pool.acquire(PoolAcquireRequest("p1", "a.js", waitTimeoutMillis = 1_000))
        assertInstanceOf(PoolAcquireOutcome.Failed::class.java, again)
        assertEquals(PoolStats(1, free = 1, busy = 0), pool.stats())
    }

    /** 在途表摘除：controller 不得把一次失败的启动留成"在跑的 run"。 */
    @Test
    fun `controller 启动失败不留悬挂在途记录`() = runBlocking {
        val controller = RuntimeController(FixedEnginePool({ id -> UnavailableEngine(id) }, capacity = 1))
        val outcome = controller.start(PoolAcquireRequest("p1", "a.js", runNonce = "n1"))
        val failed = assertInstanceOf(RuntimeController.StartOutcome.StartFailed::class.java, outcome)
        assertTrue(failed.message.contains("ERR_NOT_IMPLEMENTED"))
        assertTrue(controller.activeRunIds().isEmpty(), "失败启动不得进在途表")
        assertEquals(PoolStats(1, free = 1, busy = 0), controller.stats())
    }
}
