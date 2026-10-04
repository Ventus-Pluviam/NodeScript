package com.autoscript.ui.state

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 底栏宽度分配（[TabBarMeasure]）—— 这条测试存在的理由与 [ParticlesTest] 同款：
 * 分格算法进不了自动化门（要 Compose 运行时/真机才看得见），但"谁被拉、谁不拉、
 * 夹逼到哪个数"**全是能判对错的算术**，那一半必须被钉住。
 *
 * 字体度量由桩注入（`chars × 字号`，即 1em/字），于是每个期望值都能手算 ——
 * 真字体的宽度会随字形变，但**分配规则**不该跟着变。
 */
class TabBarMeasureTest {

    /** 量宽桩：1 个字符占 1em（= 字号那么大）。 */
    private val emPerChar: (String, Float) -> Float = { label, sizeSp -> label.length * sizeSp }

    /** 密度 1：dp 与 px 同值，期望值可以直接按 dp 手算。 */
    private fun plan(
        labels: List<String>,
        availableWidthPx: Float = 400f,
        maxWidthPx: Float = 328f,
        paddingPx: Float = 4f,
        textWidthAt: (String, Float) -> Float = emPerChar,
    ) = TabBarMeasure.measure(
        labels = labels,
        density = 1f,
        availableWidthPx = availableWidthPx,
        maxWidthPx = maxWidthPx,
        paddingPx = paddingPx,
        textWidthAt = textWidthAt,
    )

    @Test
    fun `四格短标签被撑到下限 —— 内容 320、胶囊 328（与 TG 的 328 上限互为倒影）`() {
        val p = plan(listOf("项目", "任务", "管理", "设置"))
        // pass 0（12sp / 16dp 内边距）就塞得下：每格 24 + 32 = 56，总 224 ≤ 320。
        assertEquals(12f, p.textSizeSp)
        // 224 < 下限 320 → 按 weight 均摊差额 96/4 = 24，每格 80。
        assertEquals(listOf(80f, 80f, 80f, 80f), p.tabWidthsPx)
        assertEquals(320f, p.contentWidthPx)
        // 胶囊 = 内容 + 左右各 4dp —— 正好 328，即 `setMaxWidth(dp(328 + 8*2))` 里那个 328。
        assertEquals(328f, p.capsuleWidthPx)
    }

    @Test
    fun `第一趟塞不下、第二趟塞得下时 —— 只收内边距，字号不动`() {
        // 每格 5 字 @12sp = 60：pass0 = (60+32)×4 = 368 > 320；pass1 = (60+16)×4 = 304 ≤ 320。
        val p = plan(List(4) { "五字标签" })
        // PASS_TEXT_SIZES_DP 的前两趟**同为 12f**（TG 原样，不是笔误）：选中的是第二趟，
        // 字号因此与第一趟相同，变的是内边距。
        assertEquals(12f, p.textSizeSp)
        // maxTabTextWidthIfEq = 320/4 − 16 = 64；withMargin = 76 > 64+16 = 80? 否 → 全体参与拉伸。
        // 304 < 320 → 差额 16/4 = 4，每格 80。
        assertEquals(listOf(80f, 80f, 80f, 80f), p.tabWidthsPx)
        assertEquals(320f, p.contentWidthPx)
    }

    @Test
    fun `前两趟都塞不下才降到 10sp`() {
        // 每格 6 字：@12sp pass1 = (72+16)×4 = 352 > 320；@10sp pass2 = (60+8)×4 = 272 ≤ 320。
        val p = plan(List(4) { "六字长的标签" })
        assertEquals(10f, p.textSizeSp)
        assertEquals(320f, p.contentWidthPx)
    }

    @Test
    fun `同一个字号只量一次 —— 第 1、2 趟共用一次测量`() {
        var calls = 0
        plan(List(4) { "五字标签" }, textWidthAt = { label, sizeSp ->
            calls++
            label.length * sizeSp
        })
        // 选中第二趟（字号与第一趟相同）→ 只该量 4 次（每格一次），不是 8 次。
        assertEquals(4, calls)
    }

    @Test
    fun `比「均分该得的」更宽的格子不参与拉伸 —— 宽标签保有自己的宽`() {
        // 前三格 2 字（@12sp = 24）、末格 8 字（= 96）：
        // pass0 总 = (24+32)×3 + (96+32) = 296 ≤ 320 → 选中第一趟。
        val p = plan(listOf("项目", "任务", "管理", "很长很长的标签啊"))
        assertEquals(12f, p.textSizeSp)
        // maxTabTextWidthIfEq = 320/4 − 32 = 48。末格 withMargin = 128 > 48+32 = 80 → weight 0。
        // 前三格 56 ≤ 80 → weight 1；差额 24/3 = 8 → 56+8 = 64。
        assertEquals(listOf(64f, 64f, 64f, 128f), p.tabWidthsPx)
        assertEquals(320f, p.contentWidthPx)
    }

    @Test
    fun `总宽超上限时整体等比压 —— 但仍铺满内容区`() {
        // 末格 60 字：三趟都塞不下（pass2 总 = 692 > 320），选中最后一趟。
        val p = plan(listOf("项目", "任务", "管理", "六".repeat(60)))
        assertEquals(10f, p.textSizeSp)
        // 均分上限 320/4 = 80：末格 withMargin = 608 > 80 → weight 0；前三格 28 ≤ 80 → weight 1。
        // m = 320/692 → 前三格 12.95→13，末格 281.16→281；13×3 + 281 = 320。
        assertEquals(13f, p.tabWidthsPx[0])
        assertEquals(281f, p.tabWidthsPx[3])
        assertEquals(320f, p.contentWidthPx)
    }

    @Test
    fun `窄屏上上限跟着屏宽走 —— 胶囊不会比屏还宽`() {
        // 可用 280 < 上限 328 → 内容区上限 272，下限 min(320, 272) = 272。
        val p = plan(listOf("项目", "任务", "管理", "设置"), availableWidthPx = 280f)
        // pass0 = 224 ≤ 272 也能过？(24+32)×4 = 224 ≤ 272 → 选中第一趟。
        assertEquals(12f, p.textSizeSp)
        // maxTabTextWidthIfEq = 272/4 − 32 = 36；withMargin = 56 > 36+32 = 68? 否 → weight 1。
        // 224 < 272 → 差额 48/4 = 12 → 每格 68；内容 272、胶囊 280。
        assertEquals(listOf(68f, 68f, 68f, 68f), p.tabWidthsPx)
        assertEquals(272f, p.contentWidthPx)
        assertEquals(280f, p.capsuleWidthPx)
    }

    @Test
    fun `屏比上限宽时按上限排 —— 胶囊恒 328`() {
        val p = plan(listOf("项目", "任务", "管理", "设置"), availableWidthPx = 1000f)
        assertEquals(328f, p.capsuleWidthPx)
    }

    @Test
    fun `左位置是累加出来的，不是另算一遍`() {
        val p = plan(listOf("项目", "任务", "管理", "很长很长的标签啊"))
        var acc = 0f
        p.tabWidthsPx.forEachIndexed { i, w ->
            assertEquals(acc, p.tabLeftsPx[i])
            acc += w
        }
        assertEquals(p.contentWidthPx, acc)
    }

    @Test
    fun `没有页签时不炸 —— 空计划`() {
        val p = plan(emptyList())
        assertTrue(p.tabWidthsPx.isEmpty())
        assertEquals(0f, p.contentWidthPx)
        assertEquals(0f, p.capsuleWidthPx)
    }
}
