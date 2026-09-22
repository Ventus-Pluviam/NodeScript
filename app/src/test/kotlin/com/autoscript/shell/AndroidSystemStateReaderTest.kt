package com.autoscript.shell

import com.autoscript.appservice.permissioncenter.PermissionCenter
import com.autoscript.domain.permission.Capability
import com.autoscript.domain.permission.CapabilityState
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 三态映射表验证（§9.5）：每个能力在「系统事实」的四种组合下的结论，逐条钉住。
 *
 * 为什么值得为一张 when 表写这么多断言：这张表是**门禁的唯一判据**，
 * 错一格的表现是"某能力在真机上永远 DENIED"（用户按引导开了也没用）或
 * "永远放行"（脚本拿到空能力后发现什么都干不了）。两者都不会崩，只会静默地不对。
 * 表里的每一格都对应 `PermissionCenter.guideText` 对用户的一句承诺，所以按承诺断言。
 */
class AndroidSystemStateReaderTest {

    /** 每条事实一个开关，缺省全 false（"什么都没开"的出厂态）。 */
    private class FakeProbes(
        private val accessibility: Boolean = false,
        private val overlay: Boolean = false,
        private val notifications: Boolean = false,
        private val exactAlarm: Boolean = false,
        private val capture: Boolean = false,
        private val root: Boolean = false,
    ) : CapabilityProbes {
        override fun accessibilityEnabled(): Boolean = accessibility
        override fun overlayDrawable(): Boolean = overlay
        override fun notificationsEnabled(): Boolean = notifications
        override fun exactAlarmAllowed(): Boolean = exactAlarm
        override fun screenCaptureActive(): Boolean = capture
        override fun rootAvailable(): Boolean = root
    }

    private fun reader(probes: CapabilityProbes) = AndroidSystemStateReader(probes)

    @Test
    fun `无障碍没开是 DENIED 而非 DEGRADED —— 它没有降级路径`() = runBlocking {
        val r = reader(FakeProbes(accessibility = false))
        assertEquals(
            CapabilityState.DENIED,
            r.readSystemState(Capability.ACCESSIBILITY),
            "无障碍缺失必须被 ensure 拦住（引导文案承诺「开启后为 GRANTED」）",
        )
    }

    @Test
    fun `无障碍开了即 GRANTED`() = runBlocking {
        val r = reader(FakeProbes(accessibility = true))
        assertEquals(CapabilityState.GRANTED, r.readSystemState(Capability.ACCESSIBILITY))
    }

    @Test
    fun `悬浮窗两种路径都可 GRANTED：SYSTEM_ALERT_WINDOW 或 a11y 可信窗口`() = runBlocking {
        assertEquals(
            CapabilityState.GRANTED,
            reader(FakeProbes(overlay = true)).readSystemState(Capability.OVERLAY),
            "权限位给了就是 GRANTED",
        )
        assertEquals(
            CapabilityState.GRANTED,
            reader(FakeProbes(accessibility = true)).readSystemState(Capability.OVERLAY),
            "a11y 在跑时 TYPE_ACCESSIBILITY_OVERLAY 不需要 SYSTEM_ALERT_WINDOW（§9.4）",
        )
    }

    @Test
    fun `悬浮窗两条路都没有是 DEGRADED —— 引导文案承诺有通知降级路径`() = runBlocking {
        val r = reader(FakeProbes())
        assertEquals(
            CapabilityState.DEGRADED,
            r.readSystemState(Capability.OVERLAY),
            "overlay 不可用不是 DENIED：对话框还有通知回调那条路（§9.4 BAL）",
        )
    }

    @Test
    fun `通知与 POST_NOTIFICATIONS 查同一信号但降级语义不同`() = runBlocking {
        val off = reader(FakeProbes(notifications = false))
        assertEquals(CapabilityState.DEGRADED, off.readSystemState(Capability.NOTIFICATION))
        assertEquals(CapabilityState.DENIED, off.readSystemState(Capability.POST_NOTIFICATIONS))

        val on = reader(FakeProbes(notifications = true))
        assertEquals(CapabilityState.GRANTED, on.readSystemState(Capability.NOTIFICATION))
        assertEquals(CapabilityState.GRANTED, on.readSystemState(Capability.POST_NOTIFICATIONS))
    }

    @Test
    fun `精确闹钟被收回是 DEGRADED —— 降 setWindow 并标注偏差`() = runBlocking {
        assertEquals(
            CapabilityState.DEGRADED,
            reader(FakeProbes(exactAlarm = false)).readSystemState(Capability.SCHEDULE_EXACT_ALARM),
        )
        assertEquals(
            CapabilityState.GRANTED,
            reader(FakeProbes(exactAlarm = true)).readSystemState(Capability.SCHEDULE_EXACT_ALARM),
        )
    }

    @Test
    fun `截屏无活动会话是 DEGRADED —— 还没问过用户不等于被拒`() = runBlocking {
        assertEquals(
            CapabilityState.DEGRADED,
            reader(FakeProbes(capture = false)).readSystemState(Capability.SCREEN_CAPTURE),
            "首次截图才弹授权（§9.2），没会话不能判成 DENIED",
        )
        assertEquals(
            CapabilityState.GRANTED,
            reader(FakeProbes(capture = true)).readSystemState(Capability.SCREEN_CAPTURE),
        )
    }

    @Test
    fun `root 探测不到是 DENIED —— 不降级渲染为禁用`() = runBlocking {
        assertEquals(
            CapabilityState.DENIED,
            reader(FakeProbes(root = false)).readSystemState(Capability.ROOT),
        )
        assertEquals(
            CapabilityState.GRANTED,
            reader(FakeProbes(root = true)).readSystemState(Capability.ROOT),
        )
    }

    @Test
    fun `ADB 输入恒为 DEGRADED —— 不谎报 GRANTED，也不谎报 DENIED`() = runBlocking {
        // 平台上还没有 Shizuku 通道可查（探测不了），但引导文案承诺的降级路径
        // （输入走无障碍手势）真实存在 —— 所以是 DEGRADED 而不是其余两态。
        val r = reader(FakeProbes())
        assertEquals(CapabilityState.DEGRADED, r.readSystemState(Capability.ADB_INPUT))
    }

    @Test
    fun `出厂态下的 DENIED 集合被钉死 —— 其余全是 DEGRADED`() = runBlocking {
        // 这条断言守两件事：
        // (1) 新增 Capability 忘了给结论时，when 的穷尽性会在编译期拦住（这里再遍历一遍兜底）；
        // (2) 「被判 DENIED」是个**稀缺**结论 —— 它意味着 ensure 会直接拦人。
        //     出厂态（什么都没开）只有三种能力够格 DENIED：没有任何降级路径的无障碍、
        //     以及被拒即静默丢弃的通知发送权限、以及探测不到就是没有的 root。
        //     别的能力若哪天变成 DENIED，用户会在引导页开着开着发现某功能彻底用不了 —— 这条会红。
        val r = reader(FakeProbes())
        val denied = Capability.entries.filter { r.readSystemState(it) == CapabilityState.DENIED }.toSet()
        assertEquals(
            setOf(Capability.ACCESSIBILITY, Capability.POST_NOTIFICATIONS, Capability.ROOT),
            denied,
            "出厂态的 DENIED 集合变了：先确认这是有意的，再改这条断言",
        )
        for (ability in Capability.entries) {
            assertTrue(
                PermissionCenter.guideText(ability).isNotBlank(),
                "$ability 没有引导文案 —— 被拒时用户不知道该去哪开",
            )
        }
    }
}
