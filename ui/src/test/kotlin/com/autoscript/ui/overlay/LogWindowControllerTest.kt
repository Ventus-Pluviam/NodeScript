package com.autoscript.ui.overlay

import com.autoscript.ui.state.ActiveRunState
import com.autoscript.ui.state.FakeHost
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 悬浮窗控制器的**结算敏感行为**（2026-10-15 用户令「执行完后点暂停显示该执行不支持暂停」）。
 *
 * 钉的是「暂停按钮的话术不撒谎」：已结算（离开在途表）的 run 点暂停必须**静默早退**，
 * 绝不把「已结算」翻译成「该执行不支持暂停」—— 它已经结束了，不是"不支持"。
 *
 * 触发路径：`bind` 观察到窗口从"绑着 X"变成"没绑 X"（X 离开在途表）→ 记入
 * [LogWindowController] 的结算集 → 之后 `togglePause` 对 X 一问不问、直接返回。
 * 窗口在结算集里看到的是**绑定变化的时序**，不依赖任何宿主读口。
 */
class LogWindowControllerTest {

    private fun run(runId: Long, paused: Boolean = false) = ActiveRunState(
        runId = runId,
        hostLabel = "运行中",
        poolLabel = "运行中",
        drift = false,
        projectId = "demo",
        scriptPath = "data/test.js",
        paused = paused,
    )

    @Test
    fun `绑定run结算后 togglePause 静默早退 不弹不支持暂停`() = runBlocking {
        var toasts = 0
        // 宿主摘要给 null：若结算守卫失效、落到 host 分支，会 toast「宿主摘要未接线」——
        // 断言"一条 toast 都没有"同时钉住两件事：守卫生效（先于宿主查询）且不报错。
        val ctl = LogWindowController(
            hostSummary = { null },
            toast = { toasts++ },
            systemHost = null,
        )
        ctl.open("demo", "data/test.js")
        ctl.bind(listOf(run(7L)))                 // 第一轮：在途表见到 7 号，绑上
        assertEquals(7L, ctl.state?.boundRunId)
        ctl.bind(emptyList())                     // 第二轮：7 号跑完离开在途表 → 记入结算集
        assertEquals(7L, ctl.state?.boundRunId, "结算不解绑：仍绑在刚跑完的那条")
        ctl.togglePause()
        assertEquals(0, toasts, "已结算的 run 点暂停 = 点了等于没点，不弹误导性的话术")
    }

    @Test
    fun `绑定run结算前 togglePause 仍问宿主`() = runBlocking {
        val calls = mutableListOf<Long>()
        val ctl = LogWindowController(
            hostSummary = {
                object : FakeHost() {
                    override suspend fun pauseRun(runId: Long): Boolean {
                        calls += runId
                        return false // 冻不住 —— 在途表里还有它，话术如实
                    }
                }
            },
            toast = {},
            systemHost = null,
        )
        ctl.open("demo", "data/test.js")
        ctl.bind(listOf(run(7L)))                 // 在途，未结算
        ctl.togglePause()
        assertEquals(listOf(7L), calls, "在途的 run 照常问宿主")
    }

    @Test
    fun `换绑时旧号还在在途表 不记结算`() = runBlocking {
        var toasts = 0
        val ctl = LogWindowController(
            hostSummary = { null },
            toast = { toasts++ },
            systemHost = null,
        )
        ctl.open("demo", "data/test.js")
        ctl.bind(listOf(run(7L)))                 // 绑 7 号
        ctl.bind(listOf(run(8L)))                 // 同归属新 run 顶替，7 号仍在在途表
        assertEquals(8L, ctl.state?.boundRunId)
        assertTrue(ctl.state?.paused != true)
        ctl.togglePause()                          // 8 号在途 → 落到宿主分支 → toast 宿主摘要未接线
        assertEquals(1, toasts, "换绑只换绑，不把仍在在途表的旧号当结算")
    }
}
