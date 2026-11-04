package com.autoscript.shell

import com.autoscript.domain.automation.MediaProjectionSessionState
import com.autoscript.domain.permission.Capability
import com.autoscript.domain.permission.CapabilityState
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * 屏幕采集三态的**投屏判据**（§9.2/§9.5）—— 能力中心那一行「屏幕采集」的
 * 结论从哪来。
 *
 * 判据只有一句：`GRANTED` 的定义是"**会话已激活**"，其余一律 `DEGRADED` ——
 * `IDLE`（还没问过用户）与 `STOPPED`（用户/系统刚收回）**都不等于被拒**，
 * 两者都还能再申请一次（首次截图时才弹授权，§9.2）。把它报成 `DENIED` 会让
 * 能力中心把"点一下就能开"说成"没有这条路"。
 *
 * 另一件同样重要的事：**只换 `SCREEN_CAPTURE` 那一行**。其余八项的判据仍是
 * `AndroidSystemStateReader` 那张表 —— 否则这次改动会顺手改掉别的能力的三态。
 */
class AndroidScreenStateReaderTest {

    /** 八项探针全给"没有"：任何非 SCREEN_CAPTURE 的能力都落在表的 DENIED/DEGRADED 支上。 */
    private class Probes(
        private val accessibility: Boolean = false,
    ) : CapabilityProbes {
        override fun accessibilityEnabled() = accessibility
        override fun overlayDrawable() = false
        override fun notificationsEnabled() = false
        override fun exactAlarmAllowed() = false
        override fun screenCaptureActive() = false
        override fun rootAvailable() = false
        override fun usageAccessGranted() = false
        override fun adbInputAvailable() = false
    }

    private fun reader(state: MediaProjectionSessionState, accessibility: Boolean = false) =
        AndroidScreenStateReader(Probes(accessibility)) { state }

    @Test
    fun `会话激活 = GRANTED`() = runBlocking {
        assertEquals(
            CapabilityState.GRANTED,
            reader(MediaProjectionSessionState.ACTIVE).readSystemState(Capability.SCREEN_CAPTURE),
        )
        Unit
    }

    @Test
    fun `未开会话与已收回都是 DEGRADED —— 不是 DENIED`() = runBlocking {
        for (state in listOf(MediaProjectionSessionState.IDLE, MediaProjectionSessionState.STOPPED)) {
            assertEquals(
                CapabilityState.DEGRADED,
                reader(state).readSystemState(Capability.SCREEN_CAPTURE),
                "$state 不是「被拒」：能力中心要能继续引导用户授权",
            )
        }
        Unit
    }

    @Test
    fun `只有 SCREEN_CAPTURE 换判据 —— 其余八项仍走原表`() = runBlocking {
        val r = reader(MediaProjectionSessionState.ACTIVE, accessibility = false)
        // 原表逐条（见 AndroidSystemStateReader KDoc 的映射理由）。
        assertEquals(CapabilityState.DENIED, r.readSystemState(Capability.ACCESSIBILITY))
        assertEquals(CapabilityState.DEGRADED, r.readSystemState(Capability.OVERLAY))
        assertEquals(CapabilityState.DEGRADED, r.readSystemState(Capability.NOTIFICATION))
        assertEquals(CapabilityState.DENIED, r.readSystemState(Capability.POST_NOTIFICATIONS))
        assertEquals(CapabilityState.DEGRADED, r.readSystemState(Capability.SCHEDULE_EXACT_ALARM))
        assertEquals(CapabilityState.DENIED, r.readSystemState(Capability.ROOT))
        assertEquals(CapabilityState.DENIED, r.readSystemState(Capability.ADB_INPUT))
        assertEquals(CapabilityState.DENIED, r.readSystemState(Capability.USAGE_ACCESS))
        // 九项都问过一遍：新增能力时这张清单必须跟着长。
        assertEquals(Capability.entries.size, 9, "设备 Capability 九项（批 79 未增未减）")
        Unit
    }

    @Test
    fun `a11y 在跑时 OVERLAY 走第二条路（原表语义未被投屏改动波及）`() = runBlocking {
        assertEquals(
            CapabilityState.GRANTED,
            reader(MediaProjectionSessionState.IDLE, accessibility = true)
                .readSystemState(Capability.OVERLAY),
        )
        Unit
    }
}
