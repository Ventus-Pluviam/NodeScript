package com.autoscript.ui

import com.autoscript.domain.host.RecoveryRow
import com.autoscript.domain.host.RunRow
import com.autoscript.domain.host.ScheduleSpec
import com.autoscript.domain.host.ScheduledTaskRow
import com.autoscript.domain.host.ScreenRequirement
import com.autoscript.domain.host.TaskCenterSnapshot
import com.autoscript.domain.scripts.RunState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * 任务中心呈现态（纯逻辑，不碰 Compose —— :ui 的 JVM 门走 `:ui:testDebugUnitTest`）。
 *
 * 钉住的每一件都对应一种"界面在撒谎"的形态：
 * - **没读到 ≠ 一条任务都没有**：未接线/失败都留 loaded=false，不渲染成空清单；
 * - **下一跳不编时间**：null 就是不显示，格式化用注入的 now/zone（可测、同帧一致）；
 * - **未结算执行的文案点破"不是正在跑"**：那一栏只可能是上一进程的遗物；
 * - **恢复账三笔分开**：失败时重投数为 0，不按 total-expired 报成功。
 */
class TaskCenterStateTest {

    private fun task(
        id: String = "t1",
        schedule: ScheduleSpec = ScheduleSpec.Daily(7, 5),
        enabled: Boolean = true,
        nextFireAtMillis: Long? = null,
        degraded: Boolean = false,
    ) = ScheduledTaskRow(
        id = id, name = "任务$id", projectId = "p1", scriptPath = "a.js",
        schedule = schedule, screen = ScreenRequirement.ANY,
        enabled = enabled, nextFireAtMillis = nextFireAtMillis, degraded = degraded,
    )

    private fun snapshot(vararg tasks: ScheduledTaskRow) = TaskCenterSnapshot(
        tasks = tasks.toList(),
        runs = emptyList(),
        recovery = null,
    )

    @Test
    fun `首帧哨兵是未读取 不是空清单`() {
        val s = TaskCenterState.NOT_LOADED
        assertFalse(s.loaded)
        assertNull(s.loadError, "没读过 ≠ 读失败：两者分开，别拿一个句子盖住两种事实")
        assertTrue(s.tasks.isEmpty())
        assertTrue(s.unfinishedRuns.isEmpty())
        assertNull(s.recovery)
    }

    @Test
    fun `读失败带原异常文案 不吞成空清单`() {
        val s = TaskCenterState.failed(IllegalStateException("壳未装配"))
        assertFalse(s.loaded)
        assertEquals("壳未装配", s.loadError, "原异常文案是现场唯一的区分线索")
        assertTrue(s.tasks.isEmpty())
    }

    @Test
    fun `异常无 message 时退到类名 不显示 null`() {
        val s = TaskCenterState.failed(RuntimeException())
        assertEquals("RuntimeException", s.loadError, "loadError=null 会被渲染成「尚未读取」，把失败说成没读")
    }

    @Test
    fun `读到的空清单与未读取分得开`() {
        val empty = TaskCenterState.of(snapshot(), nowMillis = 1L)
        assertTrue(empty.loaded, "读成功且一条都没有：用户该去新建任务，而不是重试")
        assertNull(empty.loadError)
        assertTrue(empty.tasks.isEmpty())
        assertFalse(TaskCenterState.NOT_LOADED.loaded, "两种「空」对用户是完全不同的结论")
    }

    @Test
    fun `注入的时刻进状态 类内不现取时间`() {
        val zone = ZoneId.of("UTC")
        val s = TaskCenterState.of(snapshot(), nowMillis = 1234L, zone = zone)
        assertEquals(1234L, s.nowMillis, "类内读 currentTimeMillis = 相对时间不可测、同帧各行还各差几毫秒")
        assertEquals(zone, s.zone)
    }

    @Test
    fun `下一跳为 null 不编时间`() {
        val s = TaskCenterState.of(snapshot(task(nextFireAtMillis = null)), nowMillis = 1L)
        assertNull(s.tasks.single().nextFireText, "「不知道」渲染成一个具体时刻就是编的")
    }

    @Test
    fun `下一跳按注入时区格式化 同一时刻不同区读数不同`() {
        val millis = ZonedDateTime.of(2026, 1, 2, 3, 4, 0, 0, ZoneOffset.UTC)
            .toInstant().toEpochMilli()
        val utc = TaskCenterState.of(
            snapshot(task(nextFireAtMillis = millis)), nowMillis = 1L, zone = ZoneId.of("UTC"),
        )
        assertEquals("01-02 03:04", utc.tasks.single().nextFireText)

        val shanghai = TaskCenterState.of(
            snapshot(task(nextFireAtMillis = millis)), nowMillis = 1L, zone = ZoneId.of("Asia/Shanghai"),
        )
        assertEquals("01-02 11:04", shanghai.tasks.single().nextFireText, "时区是任务自己的（Daily 的 DST 边界靠它），呈现层不改写")
    }

    @Test
    fun `计划人话三种 各自不冒充别的`() {
        assertEquals(
            "延迟 45 秒后执行一次",
            ScheduleText.describe(ScheduleSpec.Once(45)),
        )
        assertEquals("每天 07:05", ScheduleText.describe(ScheduleSpec.Daily(7, 5)))
        assertEquals(
            "cron 表达式「0 7 * * *」",
            ScheduleText.describe(ScheduleSpec.Cron("0 7 * * *")),
            "Cron 只说表达式本身：下一跳可能算不出（2 月 30 号/坏行回 null），那时不显示时间",
        )
    }

    @Test
    fun `时长三档 不足一分钟说秒`() {
        assertEquals("45 秒", ScheduleText.duration(45_000L))
        assertEquals("1 分钟", ScheduleText.duration(90_000L))
        assertEquals("2 分钟", ScheduleText.duration(120_000L))
        assertEquals("3 小时", ScheduleText.duration(3 * 3_600_000L))
    }

    @Test
    fun `未结算执行文案点破不是正在跑`() {
        assertTrue(
            RunStateText.describe(RunState.RUNNING).contains("不是此刻正在跑"),
            "unfinished() 只增不减 —— 念成「正在运行」会让用户等一个永远不会结束的东西",
        )
        assertTrue(
            RunStateText.describe(RunState.PENDING).contains("上一进程遗物"),
        )
        // 终态各一句，原样可读（在场才轮到它们：档案有 finishedAt 才可能终态）
        assertEquals("成功", RunStateText.describe(RunState.SUCCEEDED))
        assertEquals("失败", RunStateText.describe(RunState.FAILED))
        assertEquals("崩溃（被杀/OOM/看门狗）", RunStateText.describe(RunState.CRASHED))
        assertEquals("已取消", RunStateText.describe(RunState.CANCELLED))
    }

    @Test
    fun `恢复账三笔分开 重投数由 domain 判据算`() {
        val ok = RecoveryRowState.of(RecoveryRow(total = 3, expired = 1, failureText = null))
        assertEquals(3, ok.total)
        assertEquals(1, ok.expired)
        assertEquals(2, ok.retried, "重投 = total - expired，呈现层不另写一套算术")
        assertNull(ok.failureText)

        val failed = RecoveryRowState.of(RecoveryRow(total = 3, expired = 1, failureText = "恢复崩了"))
        assertEquals("恢复崩了", failed.failureText)
        assertEquals(
            0, failed.retried,
            "失败时 domain 判据回 0 —— 呈现层照抄，不按 total-expired 把失败报成成功",
        )
    }

    @Test
    fun `停用与降级如实带到行上`() {
        val s = TaskCenterState.of(
            snapshot(
                task(id = "a", enabled = false, degraded = false),
                task(id = "b", enabled = true, degraded = true),
            ),
            nowMillis = 1L,
        )
        val a = s.tasks.first { it.id == "a" }
        val b = s.tasks.first { it.id == "b" }
        assertFalse(a.enabled)
        assertTrue(b.degraded, "降级 = 会跑但不保证守时（「可能偏差」），逐任务标记不整屏共用")
        assertFalse(a.degraded)
    }

    @Test
    fun `未结算执行行投影 关联缺失如实 null`() {
        val zone = ZoneId.of("UTC")
        val state = TaskCenterState.of(
            TaskCenterSnapshot(
                tasks = emptyList(),
                runs = listOf(
                    RunRow(
                        engineRunId = 11L, intentRunId = null, projectId = "p1",
                        scriptPath = "a.js", state = RunState.RUNNING,
                        startedAtMillis = 1_700_000_000_000L, finishedAtMillis = null,
                    ),
                ),
                recovery = null,
            ),
            nowMillis = 1L,
            zone = zone,
        )
        val run = state.unfinishedRuns.single()
        assertEquals(11L, run.engineRunId)
        assertNull(run.intentRunId, "没读到关联如实 null，不拿档案 id 凑")
        assertEquals(
            "档案停在运行中（上一进程遗物，未结算 —— 不是此刻正在跑）",
            run.stateLabel,
        )
        assertEquals(
            ScheduleText.absolute(1_700_000_000_000L, zone),
            run.startedText,
            "起点时刻与下一跳共用同一格式",
        )
    }

    // ---- 操作面三字段（登记/取消/立即执行）----

    @Test
    fun `哨兵与读失败的操作字段全空 —— 没操作过不冒充有回执`() {
        for (s in listOf(TaskCenterState.NOT_LOADED, TaskCenterState.failed(IllegalStateException("崩")))) {
            assertNull(s.opError, "读失败 ≠ 操作失败：两条账分开")
            assertNull(s.opNotice)
            assertEquals(false, s.opInFlight)
        }
    }

    @Test
    fun `操作失败保留任务清单 —— copy 不抹已读到的事实`() {
        val loaded = TaskCenterState.of(snapshot(task()), nowMillis = 1L)
        val failed = loaded.copy(opError = "任务不存在（可能已被取消）：t1")
        assertEquals(1, failed.tasks.size, "操作失败把清单抹掉 = 用户以为任务全没了")
        assertEquals("任务不存在（可能已被取消）：t1", failed.opError)
        assertNull(failed.loadError, "opError 与 loadError 分行记账，不互相顶替")
        assertEquals(true, failed.loaded, "清单还在 = 还是「读成功」的状态")
    }

    @Test
    fun `现取归零操作回执 —— 刷新不缓存陈旧提示`() {
        val s = TaskCenterState.of(snapshot(task()), nowMillis = 1L)
        assertNull(s.opNotice, "of() 是现取投影：上一次操作的回执不穿越到新快照")
    }

    @Test
    fun `once 标记由排期推导 —— 回执据此点破出册`() {
        val once = TaskRowState.of(task(schedule = ScheduleSpec.Once(60)), 1L, zone = ZoneId.systemDefault())
        val daily = TaskRowState.of(task(schedule = ScheduleSpec.Daily(7, 5)), 1L, zone = ZoneId.systemDefault())
        assertEquals(true, once.once, "Once：立即执行后出册是调度器语义，回执要说破")
        assertEquals(false, daily.once)
    }
}
