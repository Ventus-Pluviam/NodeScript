package com.autoscript.ui

import com.autoscript.domain.host.CapabilityCenterSnapshot
import com.autoscript.domain.host.CapabilityRow
import com.autoscript.domain.permission.Capability
import com.autoscript.domain.permission.CapabilityState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 能力中心呈现态（纯逻辑，不碰 Compose —— :ui 的 JVM 门走 `:ui:testDebugUnitTest`）。
 *
 * 钉住的三件事（每件都对应一种"界面在撒谎"的形态）：
 * - **没读到 ≠ 一个能力都没有**：未接线/失败都留 loaded=false，不渲染成空清单；
 * - **按钮显隐跟着 `:domain` 的判据走**（`CapabilityLifecycle.canRequestGrant`），
 *   呈现层不自己写 `state != GRANTED`；
 * - **引导文案原样透传**，不被呈现层改写（改写过的文案会和系统里的真实路径漂移）。
 */
class CapabilityCenterStateTest {

    private fun row(
        capability: Capability = Capability.ACCESSIBILITY,
        state: CapabilityState = CapabilityState.GRANTED,
        guide: String = "引导文案原样",
    ) = CapabilityRow(capability = capability, state = state, guide = guide)

    @Test
    fun `首帧哨兵是未读取 不是空清单`() {
        val s = CapabilityCenterState.NOT_LOADED
        assertFalse(s.loaded)
        assertNull(s.loadError, "没读过 ≠ 读失败：两者分开，别拿一个句子盖住两种事实")
        assertTrue(s.rows.isEmpty())
    }

    @Test
    fun `读失败带原异常文案 不吞成空清单`() {
        val s = CapabilityCenterState.failed(IllegalStateException("ROM 查询崩了"))
        assertFalse(s.loaded)
        assertEquals("ROM 查询崩了", s.loadError, "原异常文案是现场唯一的区分线索")
        assertTrue(s.rows.isEmpty())
    }

    @Test
    fun `异常无 message 时退到类名 不显示 null`() {
        val s = CapabilityCenterState.failed(RuntimeException())
        assertEquals("RuntimeException", s.loadError, "loadError=null 会被渲染成「尚未读取」，把失败说成没读")
    }

    @Test
    fun `三态各自的说法不同 —— 用户该做什么逐态不同`() {
        assertEquals("可用", CapabilityRowState.stateLabel(CapabilityState.GRANTED))
        assertEquals("降级可用", CapabilityRowState.stateLabel(CapabilityState.DEGRADED))
        assertEquals("被拒绝", CapabilityRowState.stateLabel(CapabilityState.DENIED))
    }

    @Test
    fun `按钮显隐取 domain 判据：GRANTED 不打扰 其余可申请`() {
        val granted = CapabilityRowState.of(row(state = CapabilityState.GRANTED))
        val degraded = CapabilityRowState.of(row(state = CapabilityState.DEGRADED))
        val denied = CapabilityRowState.of(row(state = CapabilityState.DENIED))
        assertFalse(granted.canRequestGrant, "已授权就不要再引导用户去系统页（§9.5：requestGrant 已 GRANTED 直接回 Granted）")
        assertTrue(degraded.canRequestGrant)
        assertTrue(denied.canRequestGrant)
    }

    @Test
    fun `引导文案原样透传 呈现层不改写`() {
        val guide = "无障碍服务未开启：请前往「设置 → 无障碍 → AutoScript」开启"
        val s = CapabilityCenterState.of(
            CapabilityCenterSnapshot(rows = listOf(row(guide = guide)), degradedAlarmTaskIds = emptyList()),
        )
        assertEquals(guide, s.rows.single().guide)
    }

    @Test
    fun `每项能力都有中文名 —— 新增能力忘配就显示枚举名而不是空白`() {
        for (ability in Capability.entries) {
            assertTrue(
                CapabilityRowState.label(ability).isNotBlank(),
                "$ability 没有中文名（会渲染成空白行）",
            )
        }
    }

    @Test
    fun `降级任务账原样透传 —— 非空不是错误是如实记账`() {
        val s = CapabilityCenterState.of(
            CapabilityCenterSnapshot(
                rows = listOf(row()),
                degradedAlarmTaskIds = listOf("task-a", "task-b"),
            ),
        )
        assertTrue(s.loaded)
        assertEquals(listOf("task-a", "task-b"), s.degradedAlarmTaskIds)
    }

    @Test
    fun `loadError 为 null 只在成功与未读取两条路上`() {
        assertNull(CapabilityCenterState.of(CapabilityCenterSnapshot(emptyList(), emptyList())).loadError)
        assertNull(CapabilityCenterState.NOT_LOADED.loadError)
        assertTrue(CapabilityCenterState.failed(IllegalStateException("x")).loadError != null)
    }
}
