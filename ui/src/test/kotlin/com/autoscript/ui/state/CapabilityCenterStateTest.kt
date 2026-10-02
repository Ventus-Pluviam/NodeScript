package com.autoscript.ui.state

import com.autoscript.domain.host.CapabilityCenterSnapshot
import com.autoscript.domain.host.CapabilityRow
import com.autoscript.domain.host.InstallSize
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
        assertFalse(s.load.isLoaded)
        assertNull(s.load.failedReason(), "没读过 ≠ 读失败：两者分开，别拿一个句子盖住两种事实")
        assertTrue(s.rows.isEmpty())
    }

    @Test
    fun `读失败带原异常文案 不吞成空清单`() {
        val s = CapabilityCenterState.failed(IllegalStateException("ROM 查询崩了"))
        assertFalse(s.load.isLoaded)
        assertEquals("ROM 查询崩了", s.load.failedReason(), "原异常文案是现场唯一的区分线索")
        assertTrue(s.rows.isEmpty())
    }

    @Test
    fun `异常无 message 时退到类名 不显示 null`() {
        val s = CapabilityCenterState.failed(RuntimeException())
        assertEquals("RuntimeException", s.load.failedReason(), "loadError=null 会被渲染成「尚未读取」，把失败说成没读")
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
        assertTrue(s.load.isLoaded)
        assertEquals(listOf("task-a", "task-b"), s.degradedAlarmTaskIds)
    }

    @Test
    fun `loadError 为 null 只在成功与未读取两条路上`() {
        assertNull(CapabilityCenterState.of(CapabilityCenterSnapshot(emptyList(), emptyList())).load.failedReason())
        assertNull(CapabilityCenterState.NOT_LOADED.load.failedReason())
        assertTrue(CapabilityCenterState.failed(IllegalStateException("x")).load.failedReason() != null)
    }

    // ── 安装体积（§15 E1「接受并明示」）────────────────────────────────────
    // 钉住两件会撒谎的事：①没量到不显示 0；②引擎那一份要能单独被比较（超支全在它身上）。

    @Test
    fun `安装体积没量到就不显示 而不是显示 0`() {
        val s = CapabilityCenterState.of(
            CapabilityCenterSnapshot(rows = listOf(row()), degradedAlarmTaskIds = emptyList()),
        )
        assertNull(s.installSize, "没量到 = null；0 会被读成「安装包是空的」")

        Unit  // 显式收尾：void 返回值才被 JUnit5 视为测试
    }

    @Test
    fun `安装体积给出总数与其中引擎那一段`() {
        val mib = 1024L * 1024
        val s = CapabilityCenterState.of(
            CapabilityCenterSnapshot(
                rows = listOf(row()),
                degradedAlarmTaskIds = emptyList(),
                installSize = InstallSize(totalBytes = 92 * mib, engineBytes = 85 * mib, engineFilesPresent = true),
            ),
        )
        assertEquals("安装体积 92.0 MiB（其中引擎 85.0 MiB，其余 7.0 MiB）", s.installSize!!.text())
        assertTrue(
            s.installSize!!.text().contains("85.0 MiB"),
            "引擎那段要能被单独看见：超支全在它身上，混进总数里就答不上「为什么这么大」",
        )

        Unit  // 显式收尾：void 返回值才被 JUnit5 视为测试
    }

    @Test
    fun `引擎未随包时如实说明 不隐去体积`() {
        val mib = 1024L * 1024
        val s = CapabilityCenterState.of(
            CapabilityCenterSnapshot(
                rows = listOf(row()),
                degradedAlarmTaskIds = emptyList(),
                installSize = InstallSize(totalBytes = 8 * mib, engineBytes = 0, engineFilesPresent = false),
            ),
        )
        val text = s.installSize!!.text()
        assertTrue(text.contains("引擎未随包"), "装了个跑不了脚本的壳 = 事实，隐去比显示更糟")
        assertTrue(text.contains("8.0 MiB"), "体积照样报：用户已经装了，藏起来只是让人更意外")

        Unit  // 显式收尾：void 返回值才被 JUnit5 视为测试
    }

    @Test
    fun `MiB 换算不是 MB 且不四舍五入成 0`() {
        assertEquals("0.0", InstallSizeState.mib(0))
        assertEquals("0.5", InstallSizeState.mib(512L * 1024))
        assertEquals("1.0", InstallSizeState.mib(1024L * 1024))
        // 1 MB = 1,000,000 B = 0.9537 MiB。按 MB 报与按 MiB 报差 4.9%，
        // 两套口径混用正是「体积对不上」的来源 —— 故这里固定成 MiB。
        assertEquals("1.0", InstallSizeState.mib(1_000_000), "0.9537 MiB 一位小数下是 1.0（不是「1 MB」）")
        assertEquals("0.9", InstallSizeState.mib(950_000), "0.9059 MiB → 0.9")
        assertEquals("92.0", InstallSizeState.mib(92L * 1024 * 1024))

        Unit  // 显式收尾：void 返回值才被 JUnit5 视为测试
    }
}
