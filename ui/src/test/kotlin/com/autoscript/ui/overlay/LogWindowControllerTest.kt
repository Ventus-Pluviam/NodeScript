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

    /**
     * 底部输入框的送行（2026-10-16 用户令「在悬浮窗最下面放一个输入框，输入框右边放
     * 一个蓝色的箭头」）。
     *
     * 钉四件，都是"不假装送到"这条纪律的分支：
     * 1. **在途 + 成功** → 宿主收到原文（trim 过），草稿清空；
     * 2. **不在途（从没绑上过）** → 一条 toast，宿主**一次都没被问**；
     * 3. **已结算**（结算不解绑，窗里还绑着一个跑完的号）→ 同样不问宿主 ——
     *    这正是本地判据的价值：不靠宿主查、也不写进一个已死的管道；
     * 4. **空白行** → 连 toast 都不弹（敲一下空格不该在脚本那头造出一个空行）。
     */
    @Test
    fun `sendInput 在途成功才清草稿 不在途与已结算与空白行都不发`() = runBlocking {
        val sent = mutableListOf<Pair<Long, String>>()
        var toasts = 0
        val ctl = LogWindowController(
            hostSummary = {
                object : FakeHost() {
                    override suspend fun sendInput(runId: Long, line: String): Boolean {
                        sent += runId to line
                        return true
                    }
                }
            },
            toast = { toasts++ },
            systemHost = null,
        )
        ctl.open("demo", "data/test.js")

        // ① 从没绑上过：不发、问都不问
        ctl.updateInputDraft("甲")
        ctl.sendInput()
        assertEquals(0, sent.size, "没有在跑的执行时不该问宿主")
        assertEquals("甲", ctl.state?.inputDraft, "没发出去就留着草稿，用户不必重敲")
        assertEquals(1, toasts, "要有一条话术说清为什么没发")

        // ② 在途：发原文（trim），成功才清
        ctl.bind(listOf(run(7L)))
        ctl.updateInputDraft("  乙  ")
        ctl.sendInput()
        assertEquals(listOf(7L to "乙"), sent, "送的是 trim 过的原文")
        assertEquals("", ctl.state?.inputDraft, "送成功了才清草稿")
        assertEquals(
            listOf(InputEcho(atSeq = 0L, text = "乙")),
            ctl.state?.echoes,
            "送出去了就回显一条（没有可见行时 atSeq = 水位）",
        )

        // ③ 已结算（结算不解绑）：不问宿主、留草稿
        ctl.bind(emptyList())
        assertEquals(7L, ctl.state?.boundRunId, "前提：结算不解绑，号还留着")
        ctl.updateInputDraft("丙")
        ctl.sendInput()
        assertEquals(1, sent.size, "已结算的号不再往管道里写")
        assertEquals("丙", ctl.state?.inputDraft)
        assertEquals(2, toasts)
        assertEquals(1, ctl.state?.echoes?.size, "没送出去就不添回显：回显是“已经到脚本那一行”的账")

        // ④ 空白行：连话术都不弹
        ctl.bind(listOf(run(8L)))
        ctl.updateInputDraft("   ")
        ctl.sendInput()
        assertEquals(1, sent.size, "空白行不发")
        assertEquals(2, toasts, "空白行是「没什么可发」不是错误 —— 不弹话术")
        assertEquals(1, ctl.state?.echoes?.size, "空白行也不添回显")
    }

    /**
     * 回显的**位置**（2026-10-16 同批）：`atSeq` = 送出那一刻窗里
     * **可见**的最大 seq。
     *
     * 两件事在这里同时钉住：① 取的是**可见**那一分（别的 run 的行不算、
     * 水位之下的行也不算）；② 每次送出各自算各自的（不是“第一次定下来就不变”）。
     */
    @Test
    fun `回显位置取送出那一刻的可见最大 seq`() = runBlocking {
        val ctl = LogWindowController(
            hostSummary = {
                object : FakeHost() {
                    override suspend fun sendInput(runId: Long, line: String): Boolean = true
                }
            },
            toast = {},
            systemHost = null,
        )
        ctl.open("demo", "data/test.js")
        ctl.bind(listOf(run(7L)))
        ctl.updateLines(
            listOf(
                line(seq = 1L, runId = 7L),
                line(seq = 2L, runId = 7L),
                line(seq = 9L, runId = 8L), // 别的 run 的行：不是本窗的，不算进可见最大 seq
            ),
        )
        ctl.updateInputDraft("甲")
        ctl.sendInput()
        assertEquals(listOf(InputEcho(atSeq = 2L, text = "甲")), ctl.state?.echoes)

        // 又来一行输出，再送一行 —— atSeq 跟着现值走
        ctl.updateLines(
            listOf(
                line(seq = 1L, runId = 7L),
                line(seq = 2L, runId = 7L),
                line(seq = 3L, runId = 7L),
            ),
        )
        ctl.updateInputDraft("乙")
        ctl.sendInput()
        assertEquals(
            listOf(InputEcho(atSeq = 2L, text = "甲"), InputEcho(atSeq = 3L, text = "乙")),
            ctl.state?.echoes,
        )
    }

    /**
     * 清空日志连回显一起清（只推水位会留下几条“上一轮我说的话”浮在空窗里）；
     * 水位本身仍只进不退（退着调 = “清空了又回来”）。
     */
    @Test
    fun `clearLogs 清掉回显 水位只进不退`() = runBlocking {
        val ctl = LogWindowController(
            hostSummary = {
                object : FakeHost() {
                    override suspend fun sendInput(runId: Long, line: String): Boolean = true
                }
            },
            toast = {},
            systemHost = null,
        )
        ctl.open("demo", "data/test.js")
        ctl.bind(listOf(run(7L)))
        ctl.updateLines(listOf(line(seq = 4L, runId = 7L)))
        ctl.updateInputDraft("甲")
        ctl.sendInput()
        assertEquals(1, ctl.state?.echoes?.size, "前提：送成了就有一条回显")

        ctl.clearLogs(visibleMaxSeq = 4L)
        assertEquals(0, ctl.state?.echoes?.size, "清空 = 窗里那本流水重来，回显跟着走")
        assertEquals(4L, ctl.state?.watermarkSeq)

        ctl.clearLogs(visibleMaxSeq = 2L)
        assertEquals(4L, ctl.state?.watermarkSeq, "水位只进不退（退着调会让旧行又回来）")
    }

    /** 一行脚本输出的呈现态（本文件只关心 seq/runId）。 */
    private fun line(seq: Long, runId: Long) = com.autoscript.ui.state.ConsoleLineState(
        seq = seq,
        runId = runId,
        external = false,
        level = "log",
        levelLabel = "日志",
        text = "t$seq",
        timeText = "00:00:00",
    )
}
