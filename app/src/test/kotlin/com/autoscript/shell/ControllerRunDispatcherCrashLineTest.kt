package com.autoscript.shell

import com.autoscript.appservice.runtime.FixedEnginePool
import com.autoscript.appservice.runtime.RuntimeController
import com.autoscript.appservice.scheduler.core.PendingRun
import com.autoscript.appservice.scheduler.core.TriggerSource
import com.autoscript.domain.engine.EngineId
import com.autoscript.domain.engine.EngineStatus
import com.autoscript.domain.engine.RunSummary
import com.autoscript.domain.scripts.ScreenGuarantee
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 崩溃病因**进控制台**（2026-10-16 用户报「有不支持的函数应该在悬浮窗里输出报错」）。
 *
 * 钉的是那条：脚本抛错（`ReferenceError: prompt is not defined` 这类）时，
 * stderr 尾部必须按 error 档写一行、**归属仍是这次 runId** —— 否则悬浮窗画的是
 * 「本次执行没有输出」，用户读到的就是"脚本没反应"，而真相躺在没人读的档案里。
 */
class ControllerRunDispatcherCrashLineTest {

    @Test
    fun `崩溃结算把 stderr 尾部写进控制台 归属是这次 runId`() = runBlocking {
        val lines = mutableListOf<Triple<Long, String, String>>()
        val engines = listOf(
            FakeEngineForDispatcher(EngineId(0), autoExitAfterMillis = 30).apply {
                summaryToReturn = RunSummary(
                    exitCode = 1,
                    stderrTail = "ReferenceError: prompt is not defined\n    at a.js:1",
                )
                statusAfterAutoExit = EngineStatus.CRASHED
            },
        )
        val controller = RuntimeController(FixedEnginePool({ id -> engines[id.poolIndex] }, 1))
        val dispatcher = ControllerRunDispatcher(
            controller,
            crashLine = { runId, level, text -> lines += Triple(runId, level, text) },
        )
        dispatcher.dispatch(
            PendingRun(
                projectId = "p1",
                scriptPath = "a.js",
                runNonce = "n-1",
                trigger = TriggerSource.USER_CLICK,
                scheduledAtMillis = 0L,
                screen = ScreenGuarantee.ANY,
            ),
        )
        assertEquals(1, lines.size, "崩溃必须写一行（且只写一行）")
        val (runId, level, text) = lines.single()
        assertTrue(runId > 0L, "归属必须是这次执行的 runId，不能是 0（引擎外）")
        assertEquals("error", level)
        assertTrue(text.contains("ReferenceError: prompt is not defined"), "病因原文要在里面：$text")
    }

    @Test
    fun `干净退出不写崩溃行`() = runBlocking {
        val lines = mutableListOf<String>()
        val engines = listOf(FakeEngineForDispatcher(EngineId(0), autoExitAfterMillis = 30))
        val controller = RuntimeController(FixedEnginePool({ id -> engines[id.poolIndex] }, 1))
        val dispatcher = ControllerRunDispatcher(
            controller,
            crashLine = { _, _, text -> lines += text },
        )
        dispatcher.dispatch(
            PendingRun(
                projectId = "p1",
                scriptPath = "a.js",
                runNonce = "n-2",
                trigger = TriggerSource.USER_CLICK,
                scheduledAtMillis = 0L,
                screen = ScreenGuarantee.ANY,
            ),
        )
        assertTrue(lines.isEmpty(), "跑成功了不该报错行：$lines")
    }
}
