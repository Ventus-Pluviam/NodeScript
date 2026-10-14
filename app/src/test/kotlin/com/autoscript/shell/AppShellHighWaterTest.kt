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
 * 每次重启从 1 重数），`adoptRunIdHighWater` 只前移不后退。
 *
 * 刻意不共用一个全局序列：那样每次执行都是新号，"重发旧号"这条病根本复现不出来 ——
 * 这条用例要能在**没接高水位**时真红（`put` 撞 `id=1` 的终态行 → ISE），接了才绿。
 */
private class HighWaterFakeEngine(
    override val id: EngineId,
    /** 看门狗按 pid 采样 `/proc`；本测试不接看门狗，如实回 null（"没有真进程"）。 */
    override val pid: Int? = null,
) : ScriptEngine {
    val executed = mutableListOf<EngineRunRequest>()
    var status: EngineStatus = EngineStatus.IDLE
    private val nextRunId = AtomicLong(1)

    /** 最近一次被顶到的水位（断言"在 execute 之前顶过"）。 */
    @Volatile var adopted: Long = 0L

    override fun adoptRunIdHighWater(usedThrough: Long): Long {
        adopted = usedThrough
        if (usedThrough <= 0) return nextRunId.get()
        val floor = if (usedThrough == Long.MAX_VALUE) Long.MAX_VALUE else usedThrough + 1
        return nextRunId.updateAndGet { cur -> if (cur >= floor) cur else floor }
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
 * 2026-10-14 真机 bug「终态记录不可改写」的**装配层回归**：`AppShell.assemble` 收到
 * 档案高水位后，必须在引擎第一次 execute 之前把它顶给每个引擎实例 —— 这样"重启后的
 * 第一次执行"拿到的是历史之后的号，归档不再撞上一进程留下的终态行。
 */
class AppShellHighWaterTest {

    @Test
    fun `高水位在首次执行前顶给引擎 重启后不再撞档案终态行`() = runBlocking {
        // 上一进程留下的终态档案行（模拟设备上 run_archive 里 engine_run_id=1 的 CRASHED）。
        val archive = InMemoryRunArchive()
        archive.put(
            com.autoscript.domain.scripts.RunRecord(
                id = 1L, projectId = "demo", scriptPath = "main.js",
                runNonce = "old", state = RunState.CRASHED,
            ),
            com.autoscript.domain.scripts.EngineRunLink(intentRunId = 1, engineRunId = 1),
        )
        assertEquals(1L, archive.maxEngineRunId(), "档案高水位 = 上一进程用过的最大号")

        // 新进程装配：把高水位传下去，池里每个引擎实例在 execute 之前先顶水位。
        val engines = mutableListOf<HighWaterFakeEngine>()
        val log = InMemoryIntentLog()
        val shell = AppShell.assemble(
            engineFactory = { id, _ -> HighWaterFakeEngine(id).also { engines += it } },
            schedulerProvider = HighWaterProvider,
            intentLog = log,
            runArchive = archive,
            engineRunIdHighWater = archive.maxEngineRunId(),
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
