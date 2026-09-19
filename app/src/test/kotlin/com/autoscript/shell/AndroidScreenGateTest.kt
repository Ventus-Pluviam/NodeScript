package com.autoscript.shell

import com.autoscript.appservice.scheduler.core.ScreenGuarantee
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 屏幕门禁验证（§8.6 守时契约 / §9.5 三态门禁的降级路径）：
 * - `SCREEN_ON` 的亮屏条件不满足 → [ScreenGateDecision.Deny]，
 *   **不静默降级**成"锁屏也跑"（那会让 UI 任务对着黑屏空点，还上报成功）；
 * - `SCREEN_OFF` 放行前必须先裁掉画面能力（否则 MediaProjection 在 keyguard 下只会给黑帧，
 *   而 §8.8 要求的是**分类错误而非黑图**）；
 * - `ANY` 显式尽力而为：锁屏也放行（这是守时任务的明确选择，不是默认兜底）。
 *
 * 走 [ScreenInteractive] / [ScreenOffGuard] 两条缝而不是 Android 系统服务：
 * 框架对象在 JVM 不可构造，而门禁的判断逻辑必须可单测。
 */
class AndroidScreenGateTest {

    private var offCalls: MutableList<String> = mutableListOf()

    private fun gate(screen: Boolean, wake: Boolean): AndroidScreenGate {
        offCalls = mutableListOf()
        return AndroidScreenGate(
            interactive = ScreenInteractive { screen },
            deferWakeLock = ScreenInteractive { wake },
            onScreenOff = ScreenOffGuard { offCalls += "disabled" },
        )
    }

    @Test
    fun `SCREEN_ON 亮屏且持锁时放行`() = runBlocking {
        val g = gate(screen = true, wake = true)
        assertEquals(ScreenGateDecision.Proceed, g.pass(ScreenGuarantee.SCREEN_ON))
        assertTrue(offCalls.isEmpty(), "亮屏任务不裁剪画面能力")
    }

    @Test
    fun `SCREEN_ON 熄屏时拒绝而非静默降级`() = runBlocking {
        val g = gate(screen = false, wake = true)
        val decision = g.pass(ScreenGuarantee.SCREEN_ON)
        assertInstanceOf(ScreenGateDecision.Deny::class.java, decision, "熄屏不得静默跑")
        assertTrue((decision as ScreenGateDecision.Deny).reason.isNotBlank(), "拒绝须带可呈现原因")
        assertTrue(offCalls.isEmpty(), "被拒绝的任务不该顺手裁剪画面能力")
    }

    @Test
    fun `SCREEN_ON 拿不到 wakelock 时同样拒绝`() = runBlocking {
        val g = gate(screen = true, wake = false)
        assertInstanceOf(ScreenGateDecision.Deny::class.java, g.pass(ScreenGuarantee.SCREEN_ON))
    }

    @Test
    fun `SCREEN_OFF 锁屏也放行但先裁画面能力`() = runBlocking {
        val g = gate(screen = false, wake = false)
        assertEquals(ScreenGateDecision.Proceed, g.pass(ScreenGuarantee.SCREEN_OFF))
        assertEquals(listOf("disabled"), offCalls, "放行前必须先收起画面能力（§8.8 分类错误而非黑图）")
    }

    @Test
    fun `ANY 亮屏时直接放行不裁剪`() = runBlocking {
        val g = gate(screen = true, wake = true)
        assertEquals(ScreenGateDecision.Proceed, g.pass(ScreenGuarantee.ANY))
        assertTrue(offCalls.isEmpty())
    }

    @Test
    fun `AllowAll 与 Android 门禁在 ANY 上结论一致`() = runBlocking {
        val g = gate(screen = false, wake = false)
        // ANY 在两处实现下必须同结论：否则按环境切换门禁会悄悄改变任务语义
        // （单测里 AllowAll 放行、生产里 Android 门禁拒绝 = 差别只在真机暴露）。
        assertEquals(
            ScreenGate.AllowAll.pass(ScreenGuarantee.ANY),
            g.pass(ScreenGuarantee.ANY),
        )
    }
}
