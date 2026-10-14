package com.autoscript.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [backGestureScale] 的判据（批 115，跟手返回手势）。
 *
 * 这是纯函数 —— 手势那层只在绘制阶段读它，没有别的守卫可依赖。抄的是 TG
 * `ActionBarLayout.java:1119`，所以**用 TG 的那几个数**当断言值。锚点那一半
 * （`:1133`）现在在 `ManagementTransitionGeometryTest`。
 */
class ManagementBackGestureTest {

    // 屏幕密度取 3.0（xxhdpi），于是 56dp = 168px —— TG 的 `dpf2(56)` 满量程。
    private val density = 3.0f
    private val full = 56 * density

    @Test
    fun `没滑动时缩放为 1——页面原尺寸，不该一进管理页就变小`() {
        assertEquals(1.0f, backGestureScale(0f, alpha = 1f, density), 1e-6f)
    }

    @Test
    fun `滑到一半且页面完全就位时缩到零点九五——TG 的 lerp 三个端点取中`() {
        val s = backGestureScale(full / 2f, alpha = 1f, density)
        assertEquals(0.95f, s, 1e-6f)
    }

    @Test
    fun `滑满且页面完全就位时缩到零点九零——TG 的 lerp 端点`() {
        val s = backGestureScale(full, alpha = 1f, density)
        assertEquals(0.90f, s, 1e-6f)
    }

    @Test
    fun `alpha 越小（越接近刚切入）终点越远——零点八五那一档只在这种情况下出现`() {
        // TG: lerp(1.00, lerp(0.90, 0.85, 1 - alpha), 1.0) —— alpha=0 时内层是 0.85。
        val s = backGestureScale(full, alpha = 0f, density)
        assertEquals(0.85f, s, 1e-6f)
    }

    @Test
    fun `超出满量程的位移被钳在一点零——TG 的 clamp01 把 dx 除以 dpf2 五十六`() {
        // 系统给的 progress 本就 ≤1，但公式自己钳一道：多给一倍位移也不该继续缩。
        val s = backGestureScale(full * 2f, alpha = 1f, density)
        assertEquals(0.90f, s, 1e-6f)
    }

    @Test
    fun `缩放恒在零点八五到一点零零之间——不会缩到看不见，也不会放大`() {
        var d = 0f
        while (d <= full) {
            val s = backGestureScale(d, alpha = 0f, density)
            assertTrue(s >= 0.85f - 1e-6f && s <= 1.0f + 1e-6f, "d=$d s=$s")
            d += 1f
        }
    }
}
