package com.autoscript.ui

import com.autoscript.domain.host.HostSummary
import com.autoscript.domain.host.ShellSummary
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 首屏状态纯逻辑（不碰 Compose/Android —— :ui 的 JVM 门走 `:ui:testDebugUnitTest`，
 * 本机裸 kotlinc 旁路编不了 @Composable，见 jvm-test-all 头注释）。
 */
class HomeStateTest {

    @Test
    fun `读口未接线回哨兵（不冒充未就绪）`() {
        val s = HomeState.read(null)
        assertFalse(s.summaryWired)
        assertEquals(HomeState.UNWIRED, s)
    }

    @Test
    fun `接线后逐字段回读快照`() {
        val host = object : HostSummary {
            override fun shellSummary() = ShellSummary(shellReady = true, missedAlarms = 3)
        }
        val s = HomeState.read(host)
        assertTrue(s.summaryWired)
        assertTrue(s.shellReady)
        assertEquals(3, s.missedAlarms)
    }

    @Test
    fun `未就绪如实透传（装配中或失败不细分）`() {
        val host = object : HostSummary {
            override fun shellSummary() = ShellSummary(shellReady = false, missedAlarms = 0)
        }
        val s = HomeState.read(host)
        assertTrue(s.summaryWired, "接了读口 ≠ 壳就绪：两态分开")
        assertFalse(s.shellReady)
    }

    @Test
    fun `read 每次现取（不缓存首读）`() {
        var ready = false
        val host = object : HostSummary {
            override fun shellSummary() = ShellSummary(shellReady = ready, missedAlarms = 0)
        }
        assertFalse(HomeState.read(host).shellReady)   // 装配完成前
        ready = true
        assertTrue(HomeState.read(host).shellReady)    // 装配完成后必须能看到
    }
}
