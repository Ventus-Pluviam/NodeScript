package com.autoscript.shell

import com.autoscript.appservice.scheduler.core.InMemoryIntentLog
import com.autoscript.appservice.scheduler.core.InMemoryRunArchive
import com.autoscript.appservice.scheduler.core.SchedulerProvider
import com.autoscript.appservice.scheduler.core.TriggerHandle
import com.autoscript.appservice.scheduler.core.TriggerSource
import com.autoscript.domain.bridge.HandleRef
import com.autoscript.domain.engine.EngineId
import com.autoscript.domain.engine.EngineRunReceipt
import com.autoscript.domain.engine.EngineRunRequest
import com.autoscript.domain.engine.EngineStatus
import com.autoscript.domain.engine.KillCause
import com.autoscript.domain.engine.ScriptEngine
import com.autoscript.domain.engine.StopResult
import com.autoscript.domain.scripts.RunState
import com.autoscript.domain.scripts.ScheduledTask
import com.autoscript.domain.scripts.TimedSchedule
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 与 `NodeProcessEngine` **同形**的编号：**实例内**计数器从 1 起（真机分配器就是进程内计数器，
 * 每次重启从 1 重数）。水位经构造配置 [HighWaterFakeEngine.configHighWater] 传入 ——
 * 与生产 `NodeEngineConfig.runIdHighWater` 同一条缝，只前移不后退。
 */
private class HighWaterFakeEngine(
    override val id: EngineId,
    override val pid: Int? = null,
    private val configHighWater: Long = 0,
) : ScriptEngine {
    val executed = mutableListOf<EngineRunRequest>()
    var status: EngineStatus = EngineStatus.IDLE
    private val nextRunId = AtomicLong(1)

    /** 构造时被顶到的水位（断言"在 execute 之前顶过"）。 */
    @Volatile var adopted: Long = 0L

    init {
        if (configHighWater > 0) {
            val floor = if (configHighWater == Long.MAX_VALUE) Long.MAX_VALUE else configHighWater + 1
            nextRunId.updateAndGet { cur -> if (cur >= floor) cur else floor }
            adopted = configHighWater
        }
    }

    override suspend fun execute(run: EngineRunRequest): EngineRunReceipt {
        executed += run
        status = EngineStatus.RUNNING
        val runId = nextRunId.getAndIncrement()
        Thread({
            try {
                Thread.sleep(40)
            } catch (_: InterruptedException) {
                return@Thread
            }
            if (status == EngineStatus.RUNNING) status = EngineStatus.STOPPED
        }, "highwater-fake-autoexit-$runId").also { it.isDaemon = true }.start()
        return EngineRunReceipt(runId = runId, handle = HandleRef(refId = runId, generation = 1))
    }

    override suspend fun stop(): StopResult {
        status = EngineStatus.STOPPED
        return StopResult.Clean
    }

    override suspend fun kill(): KillCause {
        status = EngineStatus.CRASHED
        return KillCause.REQUESTED
    }

    override suspend fun status(): EngineStatus = status
}

private object HighWaterProvider : SchedulerProvider {
    override suspend fun registerTrigger(targetFireAtMillis: Long, taskId: String): TriggerHandle =
        TriggerHandle { }
    override suspend fun cancelTrigger(handle: TriggerHandle) = handle.cancel()
}

/**
 * 2026-10-14 真机 bug「终态记录不可改写」的**装配层回归**：装配根从档案读到高水位后，
 * 必须在引擎第一次 execute 之前把它送进每个引擎实例（生产经 `NodeEngineConfig.runIdHighWater`，
 * 构造即站在历史之后）—— 这样"重启后的第一次执行"拿到的是历史之后的号，归档不再撞
 * 上一进程留下的终态行。
 */
class AppShellHighWaterTest {

    @Test
    fun `高水位在首次执行前顶给引擎 重启后不再撞档案终态行`() = runBlocking {
        // 上一进程留下的终态档案行（模拟设备上 run_archive 里 engine_run_id=1 的 CRASHED）。
        // 关联挂一个**上一辈子**的 intentRunId（99）：新进程的意图日志从 1 开始发号，
        // 用 1 的话 `recordsOfIntent(1)` 会把这条历史行也算进本次执行 —— 那是测试自己的
        // 号段撞车，不是被测行为。
        val archive = InMemoryRunArchive()
        archive.put(
            com.autoscript.domain.scripts.RunRecord(
                id = 1L, projectId = "demo", scriptPath = "main.js",
                runNonce = "old", state = RunState.CRASHED,
            ),
            com.autoscript.domain.scripts.EngineRunLink(intentRunId = 99, engineRunId = 1),
        )
        assertEquals(1L, archive.maxEngineRunId(), "档案高水位 = 上一进程用过的最大号")

        // 新进程装配：把高水位传下去，池里每个引擎实例在 execute 之前先顶水位。
        // 这里模拟的是 AppShellApplication 的装配动作 —— 高水位在**装配前**同步读出
        // （生产是 `RunArchiveWiring.highWater` 里 `runBlocking` 收口的那一下），
        // 池工厂只做构造配置，不是挂起缝。
        val highWater = archive.maxEngineRunId()
        val engines = mutableListOf<HighWaterFakeEngine>()
        val log = InMemoryIntentLog()
        val shell = AppShell.assemble(
            engineFactory = { id, _ ->
                HighWaterFakeEngine(id, configHighWater = highWater).also { engines += it }
            },
            schedulerProvider = HighWaterProvider,
            intentLog = log,
            runArchive = archive,
        )
        shell.use {
            it.scheduler.schedule(
                ScheduledTask("t1", "任务", "demo", "main.js", TimedSchedule.Once(0)),
            )
            it.scheduler.onTrigger("t1", TriggerSource.TIMED, System.currentTimeMillis())

            val engine = engines.single()
            assertEquals(1L, engine.adopted, "高水位在第一次 execute 之前已顶给引擎（值 = 档案高水位）")
            assertEquals(1, engine.executed.size)

            // 引擎发的是历史之后的号（它自己从 1 数，被顶到 2），归档这条新执行：
            // 不撞「终态记录不可改写」、成对写回档案。
            val records = archive.recordsOfIntent(log.all().single().runId)
            assertEquals(1, records.size, "重启后的执行要能正常归档（不再被旧终态行挡掉）")
            assertTrue(records.single().id > 1L, "新 runId 必须大于上一进程用过的最大号")
        }
    }
}
