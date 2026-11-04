package com.autoscript.ui.components

import androidx.compose.ui.unit.Dp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 两处「更多」三点的**粗细层级**。
 *
 * 为什么值得一条测试：这两个参数住在同一个文件里、长得几乎一样，下一个人"顺手改成一致"
 * 是极自然的动作 —— 而那样一来层级就没了，且**编译、截图、lint 都不会报**。
 * 这里把"顶栏 > 行尾"钉成不变量，并连实际点径一起算出来
 * （点径 = 2 × 0.088 × 画布 × 粗细倍率，见 Glyphs.kt 的 MORE_VERT）。
 */
class MoreDotsTest {

    /** 三点的点半径 ÷ 画布边长（MORE_VERT 里那个 0.088 的实测值）。 */
    private val dotRatio = 0.088f

    /** 点径（dp）= 2 × 比例 × 画布边长 × 粗细倍率。 */
    private fun dotDiameter(size: Dp, weight: Float): Float = 2f * dotRatio * size.value * weight

    @Test
    fun `顶栏三点比行尾三点粗_且差得够开`() {
        val bar = dotDiameter(BarMoreDotsSize, BAR_MORE_DOTS_WEIGHT)
        val row = dotDiameter(RowMoreDotsSize, ROW_MORE_DOTS_WEIGHT)
        assertTrue(bar > row, "顶栏点径 $bar 不大于行尾 $row —— 层级反了")
        // 不只是"大一点点"：要读得出层级，差得够开（1.5 倍以上）。
        assertTrue(bar >= row * 1.5f, "顶栏 $bar 与行尾 $row 差得太近，读不出层级")
    }

    @Test
    fun `一粗一细各自跨过TG原值`() {
        // TG ic_ab_other 的实测：24dp 画布、点半径 2.1px（倍率 1.0）。
        val telegram = dotDiameter(Dp(24f), 1f)
        val bar = dotDiameter(BarMoreDotsSize, BAR_MORE_DOTS_WEIGHT)
        val row = dotDiameter(RowMoreDotsSize, ROW_MORE_DOTS_WEIGHT)
        assertTrue(bar > telegram, "顶栏 $bar 不比 TG 原值 $telegram 粗，就谈不上「加粗一点」")
        assertTrue(row < telegram, "行尾 $row 不比 TG 原值 $telegram 细，就谈不上「变细一点」")
    }

    @Test
    fun `画布尺寸顶栏24_行尾20`() {
        assertEquals(24f, BarMoreDotsSize.value, 1e-3f)
        assertEquals(20f, RowMoreDotsSize.value, 1e-3f)
    }
}
