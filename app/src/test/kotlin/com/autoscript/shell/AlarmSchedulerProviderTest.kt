package com.autoscript.shell

import com.autoscript.appservice.scheduler.core.TriggerSource
import com.autoscript.appservice.scheduler.core.DefaultDeadlines
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 闹钟投递缝验证（§8.6 守时契约的 Android 侧）：
 * - **预拉提前量**必须在 arm 时刻里可见（`targetFireAt - wakeAhead`），而不是藏进实现；
 * - 提前量**不向后扯**：排期已到就 arm 到当前时刻（ROM 对负延迟处置不一）；
 * - 精确闹钟不可用 → 降级 setWindow **且记账**（`degradedTasks`），供 UI 标「可能偏差」；
 * - 取消幂等：取消后降级记账一并清除（不让一个已撤销的任务永远挂在"偏差中"）。
 *
 * 走 [AlarmPort] 这个缝而不是 AlarmManager：真实 arm 不留任何可观测量，
 * 而这里的决策（提前量/夹取/降级）恰是唯一会错的逻辑。
 */
class AlarmSchedulerProviderTest {

    /** 记录型假闹钟：`armed` 按 taskId 记下 (atMillis, mode)，模拟 AlarmManager 的替换语义。 */
    private class FakeAlarmPort(
        exact: Boolean = true,
    ) : AlarmPort {
        // 可翻转（用户在系统设置里改了权限）：装配/provider 每轮重新问一次。
        override var canScheduleExact: Boolean = exact
        fun setExactAllowed(allowed: Boolean) { canScheduleExact = allowed }

        val armed = mutableMapOf<String, Pair<Long, AlarmMode>>()
        val cancelled = mutableListOf<String>()
        override fun arm(taskId: String, atMillis: Long, mode: AlarmMode) {
            armed[taskId] = atMillis to mode
        }
        override fun cancel(taskId: String) {
            cancelled += taskId
            armed.remove(taskId)
        }
    }

    private var now = 1_000_000L

    private fun provider(
        port: FakeAlarmPort,
        wakeAhead: Long = 60_000,
        window: Long = 600_000,
    ) = AlarmSchedulerProvider(port, wakeAhead, window) { now }

    @Test
    fun `预拉提前量落在 arm 时刻上`() = runBlocking {
        val port = FakeAlarmPort()
        val p = provider(port)

        val handle = p.registerTrigger(now + 600_000, "t1")

        assertEquals(now + 600_000 - 60_000, port.armed.getValue("t1").first, "arm = 排期 - 预拉提前量")
        assertTrue(p.degradedTasks().isEmpty(), "精确可用：无降级记账")
        handle.cancel()
    }

    @Test
    fun `排期已到时提前量不向后扯`() = runBlocking {
        val port = FakeAlarmPort()
        // 排期就在此刻：60s 提前量会算出一个过去时刻
        val p = provider(port)

        p.registerTrigger(now, "t1")

        assertEquals(now, port.armed.getValue("t1").first, "夹到当前时刻（ROM 对负延迟处置不一）")
    }

    @Test
    fun `排期已过更久时同样夹到当前`() = runBlocking {
        val port = FakeAlarmPort()
        now += 500_000                                   // 睡过头了：排期在 500s 前
        val p = provider(port)

        p.registerTrigger(now - 500_000, "t1")

        assertEquals(now, port.armed.getValue("t1").first)
    }

    @Test
    fun `精确可用时走 Exact 且不记降级`() = runBlocking {
        val port = FakeAlarmPort(exact = true)
        val p = provider(port)

        p.registerTrigger(now + 300_000, "t1")

        assertEquals(AlarmMode.Exact, port.armed.getValue("t1").second)
        assertFalse(p.isDegraded("t1"))
    }

    @Test
    fun `精确不可用时降级 setWindow 并记账供 UI 标注`() = runBlocking {
        val port = FakeAlarmPort(exact = false)
        val p = provider(port, window = 300_000)

        p.registerTrigger(now + 300_000, "t1")

        val mode = port.armed.getValue("t1").second
        assertTrue(mode is AlarmMode.Window, "降级到 setWindow")
        assertEquals(300_000L, (mode as AlarmMode.Window).windowMillis)
        assertEquals(now + 300_000, p.degradedTasks()["t1"], "降级记账 = 原排期时刻（不是 arm 时刻）")
        assertTrue(p.isDegraded("t1"))
    }

    @Test
    fun `取消后降级记账一并清除`() = runBlocking {
        val port = FakeAlarmPort(exact = false)
        val p = provider(port)

        val handle = p.registerTrigger(now + 300_000, "t1")
        assertTrue(p.isDegraded("t1"), "降级中")

        p.cancelTrigger(handle)

        assertEquals(listOf("t1"), port.cancelled, "取消落到闹钟侧")
        assertTrue(p.degradedTasks().isEmpty(), "撤销的任务不得永远挂在『偏差中』")
        assertFalse(p.isDegraded("t1"))
    }

    @Test
    fun `重新拿到精确闹钟时退出降级记账`() = runBlocking {
        val port = FakeAlarmPort(exact = false)
        val p = provider(port)
        p.registerTrigger(now + 300_000, "t1")
        assertTrue(p.isDegraded("t1"), "先降级")

        port.setExactAllowed(true)                   // 用户去设置里打开了精确闹钟
        p.registerTrigger(now + 400_000, "t1")       // 续排/重新登记时再问一次

        assertEquals(AlarmMode.Exact, port.armed.getValue("t1").second)
        assertTrue(p.degradedTasks().isEmpty(), "降级记账在重新注册拿到精确闹钟后清除")
    }

    @Test
    fun `预拉提前量与契约字段同源`() {
        val p = AlarmSchedulerProvider(FakeAlarmPort())
        // wakeAheadMillis 是 SchedulerProvider 契约字段（测试与调用方读到同一份值），
        // 不是 provider 自己私藏的一个常量。
        assertEquals(AlarmSchedulerProvider.DEFAULT_WAKE_AHEAD_MILLIS, p.wakeAheadMillis)
        assertEquals(60_000L, p.wakeAheadMillis)
    }

    @Test
    fun `排队上限分级表与 dispatcher 同口径`() {
        // 与 :app 的 ControllerRunDispatcher.DEFAULT_QUEUE_TIMEOUTS 对齐：
        // deadline 与排队上限漂移 = "恢复按一套、排队按另一套"（§8.6 缺口补法的闭环要求）。
        assertEquals(
            ControllerRunDispatcher.DEFAULT_QUEUE_TIMEOUTS(TriggerSource.TIMED),
            DefaultDeadlines(TriggerSource.TIMED),
            "TIMED：两处必须同一数字",
        )
        assertEquals(
            ControllerRunDispatcher.DEFAULT_QUEUE_TIMEOUTS(TriggerSource.ENGINE_INTERNAL),
            DefaultDeadlines(TriggerSource.ENGINE_INTERNAL),
            "ENGINE_INTERNAL：嵌套等待最先爆，两处同值",
        )
    }
}
