package com.autoscript.shell

import com.autoscript.appservice.scheduler.core.RunOutcome
import com.autoscript.appservice.scheduler.core.SchedulerProvider
import com.autoscript.appservice.scheduler.core.TriggerHandle
import com.autoscript.appservice.scheduler.core.TriggerSource
import com.autoscript.appservice.scheduler.persist.PersistentIntentLog
import com.autoscript.appservice.scheduler.persist.JournalFileStore
import com.autoscript.domain.host.ScheduleSpec
import com.autoscript.domain.host.ScreenRequirement
import com.autoscript.domain.host.TaskRegistration
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * 任务中心操作面的**装配侧**验证（[AppShellKit.AssembledShell] 的
 * `registerTask`/`cancelTask`/`runTaskNow`）—— 也就是 `AppShellApplication`
 * 三个写口在真机上会走的那条路。
 *
 * 单元级的映射规则在 [TaskCenterOpsTest]；这里钉的只有装配层才定的四件事：
 * 1. 登记 → 注册表可见 → 取消 → 消失（同一壳、同一寄存器，不开第二份视图）；
 * 2. 取消幂等（从未登记的 id 照样返回，与桥侧 `workManager.cancel` 同口径）；
 * 3. 立即执行 = `USER_CLICK`（停用任务也能手动跑；结局归意图日志，不在本口回报）；
 * 4. 诚实早退：任务不在册 / 调度已收口 → **抛**，不把 no-op 呈现成"已触发"
 *    （`onTrigger` 对两者是静默 return —— 不现查就撒谎）。
 */
class AppShellTaskOpsTest {

    @TempDir
    lateinit var dir: Path

    private val files: Path get() = dir.resolve("files")
    private val cache: Path get() = dir.resolve("cache")

    private class RecordingProvider : SchedulerProvider {
        val registered = mutableListOf<String>()
        override suspend fun registerTrigger(targetFireAtMillis: Long, taskId: String): TriggerHandle {
            registered += taskId
            return TriggerHandle { }
        }

        override suspend fun cancelTrigger(handle: TriggerHandle) = handle.cancel()
    }

    private fun kit(
        provider: SchedulerProvider = RecordingProvider(),
        screenGate: ScreenGate = ScreenGate.AllowAll,
    ): AppShellKit.AssembledShell = AppShellKit.assemble(
        filesDir = files,
        cacheDir = cache,
        schedulerProvider = provider,
        screenGate = screenGate,
    )

    private fun reg(
        name: String = "任务",
        schedule: ScheduleSpec = ScheduleSpec.Daily(7, 5),
        id: String? = null,
        enabled: Boolean = true,
    ) = TaskRegistration(
        name = name, projectId = "p1", scriptPath = "a.js",
        schedule = schedule, screen = ScreenRequirement.ANY, enabled = enabled, id = id,
    )

    @Test
    fun `登记进册取消出册 —— 同一壳同一寄存器`() = runBlocking {
        kit().use { s ->
            val id = s.registerTask(reg(name = "早安", id = "t1"))
            assertEquals("t1", id, "入参 id 原样回显")
            val row = s.taskCenter().tasks.single()
            assertEquals("早安", row.name)
            assertEquals(ScheduleSpec.Daily(7, 5), row.schedule)

            s.cancelTask("t1")
            assertTrue(s.taskCenter().tasks.isEmpty(), "取消后出册（刷新即事实）")

            // 幂等：重复取消 / 取消幽灵 id 不抛（与桥侧一致）。
            s.cancelTask("t1")
            s.cancelTask("ghost")
        }
        Unit
    }

    @Test
    fun `登记不带 id 时服务端分配`() = runBlocking {
        kit().use { s ->
            val id = s.registerTask(reg(id = null))
            assertTrue(id.isNotBlank(), "分配了 id：$id")
            assertEquals(listOf(id), s.taskCenter().tasks.map { it.id })
        }
        Unit
    }

    @Test
    fun `校验失败在装配层原文抛 —— 不留半登记状态`() = runBlocking {
        kit().use { s ->
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { s.registerTask(reg(name = " ")) }
            }
            assertTrue(
                s.taskCenter().tasks.isEmpty(),
                "校验不过 = 一条都没登记（store-first：闸门在落盘之前）",
            )
        }
        Unit
    }

    @Test
    fun `立即执行缺席任务抛 —— 不把 no-op 呈现成已触发`() = runBlocking {
        kit().use { s ->
            val e = assertThrows(IllegalStateException::class.java) {
                runBlocking { s.runTaskNow("ghost") }
            }
            assertTrue(e.message!!.contains("ghost"), "消息带 id：${e.message}")
        }
        Unit
    }

    @Test
    fun `调度已收口后立即执行抛 —— 停机窗口不假装成功`() = runBlocking {
        kit().use { s ->
            s.registerTask(reg(id = "t1", schedule = ScheduleSpec.Once(60)))
            s.shell.scheduler.sink()
            val e = assertThrows(IllegalStateException::class.java) {
                runBlocking { s.runTaskNow("t1") }
            }
            assertTrue(e.message!!.contains("收口"), "消息点名收口：${e.message}")
        }
        Unit
    }

    /**
     * 立即执行 = `USER_CLICK`：停用任务**也**跑（enabled 守卫豁免，§8.6），结局归
     * 意图日志（`UnavailableEngine` 缺省诚实 CRASHED + 真原因）—— 本口只负责触发，
     * 不回报成败（恒 Unit，见 `runTaskNow` KDoc）。
     */
    @Test
    fun `立即执行停用任务也触发 USER_CLICK —— 结局归意图日志`() = runBlocking {
        kit().use { s ->
            s.registerTask(reg(id = "t1", schedule = ScheduleSpec.Once(60), enabled = false))
            s.runTaskNow("t1")
        }
        val log = PersistentIntentLog(JournalFileStore(files.resolve(".autojs")))
        try {
            val row = log.all().single { it.projectId == "p1" }
            assertEquals(TriggerSource.USER_CLICK, row.trigger,
                "触发源如实记 USER_CLICK（任务中心/控制台据此分辨手动与定时）")
            assertTrue(
                row.outcome is RunOutcome.Crashed,
                "缺省 UnavailableEngine → 诚实 CRASHED：${row.outcome}",
            )
        } finally {
            log.close()
        }
        Unit
    }

    @Test
    fun `Once 立即执行后终态化出册 —— 刷新即事实`() = runBlocking {
        kit().use { s ->
            s.registerTask(reg(id = "t1", schedule = ScheduleSpec.Once(60)))
            assertEquals(listOf("t1"), s.taskCenter().tasks.map { it.id })
            s.runTaskNow("t1")
            // onTrigger 的 finally：Once 触发即 tombstone（挂起返回时已完成）。
            assertTrue(s.taskCenter().tasks.isEmpty(), "Once 执行后出册（不是被取消）")
        }
        Unit
    }
}
