package com.autoscript.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [topLayerX] / [netLayerX] / [layerScale] / [anchorPivotX] / [anchorPivotY] 的判据 ——
 * 逐条对着 TG `ActionBarLayout.java`：`innerTranslationX`（`:1571`）、
 * `canvas.translate(-dx, 0)`（`:1123-1124`）、两条缩放公式（`:1119` / `:1121`）、
 * 锚点镜像（`:1133`）。
 *
 * 这几个是纯函数 —— 绘制层只在组合里读，没有别的守卫可依赖。
 *
 * **最要紧的一条是「窗口横向根本不位移」**：手势期间 `translationX ≤ dp(56)`，
 * 被 `netLayerX` 全额抵消，净位移恒为 0。屏幕上的缝**只**来自缩放锚点偏在手指那侧。
 */
class ManagementTransitionGeometryTest {

    private val density = 3.0f
    private val width = 1080f
    /** TG 的 `dpf2(56)` 满量程。 */
    private val full = 56 * density

    @Test
    fun `跟手期间净位移恒为 0——TG 每帧先 translate(-dx) 把 innerTranslationX 抵消掉`() {
        var dx = 0f
        while (dx <= full) {
            assertEquals(0f, netLayerX(dx, density, fromLeft = true), 1e-3f)
            assertEquals(0f, netLayerX(dx, density, fromLeft = false), 1e-3f)
            dx += 1f
        }
    }

    @Test
    fun `超过满量程之后净位移才开始把窗口推出屏幕`() {
        // TG `:1123` 的 `dx = Utilities.clamp(translationX, dp(56), 0)` —— 钳在 dp(56)。
        assertEquals(full, netLayerX(full, density, fromLeft = true), 1e-3f)
        assertEquals(400f - full, netLayerX(400f, density, fromLeft = true), 1e-3f)
    }

    @Test
    fun `左缘滑时窗口往右滑出`() {
        assertTrue(netLayerX(400f, density, fromLeft = true) > 0f)
    }

    @Test
    fun `右缘滑时窗口往左滑出`() {
        // 2026-10-13 口径。TG 只有右一个方向（`:1571` 恒 `setTranslationX(+dx)`），
        // 这里是本项目有意加的镜像。
        assertTrue(netLayerX(400f, density, fromLeft = false) < 0f)
    }

    @Test
    fun `两向幅度相同，只有符号相反`() {
        val left = netLayerX(500f, density, fromLeft = true)
        val right = netLayerX(500f, density, fromLeft = false)
        assertEquals(-left, right, 1e-3f)
    }

    @Test
    fun `切入位移进的是 translationX 而不是净位移——切出重进不漂`() {
        // progress=0 表示刚要切入：translationX = dp(48)，净位移被全额抵消。
        val x = topLayerX(progress = 0f, dx = 0f, dismiss = 0f, viewportWidth = width, density = density)
        assertEquals(48 * density, x, 1e-3f)
        assertEquals(0f, netLayerX(x, density, fromLeft = true), 1e-3f)
    }

    @Test
    fun `飞出终点落在屏宽——TG 的 measuredWidth，两段一起抵消`() {
        // dismiss=1 时 translationX = 屏宽 + dp(56)，减去钳在 dp(56) 的那部分正好是屏宽。
        val x = topLayerX(progress = 0f, dx = 0f, dismiss = 1f, viewportWidth = width, density = density)
        assertEquals(width + full, x, 1e-3f)
        assertEquals(width, netLayerX(x, density, fromLeft = true), 1e-3f)
        // 右缘滑同一段动画落在另一侧 —— 幅度一样，符号镜像。
        assertEquals(-width, netLayerX(x, density, fromLeft = false), 1e-3f)
    }

    @Test
    fun `手势缩放走 lerp 端点 0.90——TG 的 :1119`() {
        assertEquals(1.0f, layerScale(0f, predictive = true, density = density), 1e-6f)
        assertEquals(0.95f, layerScale(full / 2f, predictive = true, density = density), 1e-6f)
        assertEquals(0.90f, layerScale(full, predictive = true, density = density), 1e-6f)
    }

    @Test
    fun `非手势缩放走 min(0.25, 0.05x)——TG 的 :1121`() {
        assertEquals(1.0f, layerScale(0f, predictive = false, density = density), 1e-6f)
        assertEquals(0.975f, layerScale(full, predictive = false, density = density), 1e-6f)
        // 更大的位移不再继续缩：TG 那个 `min` 的上界。
        assertEquals(0.75f, layerScale(full * 10, predictive = false, density = density), 1e-6f)
    }

    @Test
    fun `左缘滑锚点靠右 82dp 左边留缝——TG 的 predictiveBackLeft 分支`() {
        val pivot = anchorPivotX(fromLeft = true, predictive = true, width = width, density = density)
        assertEquals(1f - 82 * density / width, pivot, 1e-6f)
    }

    @Test
    fun `右缘滑锚点靠左 82dp 右边留缝——TG 的 else 分支`() {
        val pivot = anchorPivotX(fromLeft = false, predictive = true, width = width, density = density)
        assertEquals(82 * density / width, pivot, 1e-6f)
    }

    @Test
    fun `非手势时锚点回中线`() {
        assertEquals(
            0.5f,
            anchorPivotX(fromLeft = true, predictive = false, width = width, density = density),
            1e-6f,
        )
    }

    @Test
    fun `视口没量出来时锚点回中线——不拿 0 当分母`() {
        assertEquals(0.5f, anchorPivotX(fromLeft = true, predictive = true, width = 0f, density = density), 1e-6f)
        assertEquals(0.5f, anchorPivotY(predictive = true, touchY = 1000f, height = 0f), 1e-6f)
    }

    @Test
    fun `锚点 y 用手指位置——TG 的 predictiveBackY`() {
        assertEquals(0.75f, anchorPivotY(predictive = true, touchY = 1500f, height = 2000f), 1e-6f)
    }

    @Test
    fun `没有触点时锚点 y 退到中线`() {
        assertEquals(0.5f, anchorPivotY(predictive = true, touchY = Float.NaN, height = 2000f), 1e-6f)
    }

    @Test
    fun `锚点不越出视口——手指按在状态栏或导航栏上是真实情况`() {
        assertEquals(0f, anchorPivotY(predictive = true, touchY = -50f, height = 2000f), 1e-6f)
        assertEquals(1f, anchorPivotY(predictive = true, touchY = 2100f, height = 2000f), 1e-6f)
    }

    @Test
    fun `缩放恒不越过 1.00 上限也不放大`() {
        var x = 0f
        while (x <= full) {
            assertTrue(layerScale(x, predictive = true, density = density) <= 1f + 1e-6f, "x=$x")
            x += 1f
        }
    }
}
