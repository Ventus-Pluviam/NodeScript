package com.autoscript.ui.state

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 底部页签条的**宽度分配结果**（[TabBarMeasure.measure] 的产物）。
 *
 * 单位一律是 **px**（测量在像素域做，dp 只在最后交给 Compose 时才转）——
 * 与 TG 的 `onMeasure` 同一个域，四舍五入的落点才不会因为换了个单位而漂。
 *
 * @property tabWidthsPx 每格的宽度，**已四舍五入**；总和 = [contentWidthPx]。
 * @property tabLeftsPx 每格相对内容区左边的位置（累加得来，不是再算一遍）。
 * @property textSizeSp 这一趟选中的字号（三趟里挑一个）。
 * @property contentWidthPx 内容区总宽 = 各格之和（不含胶囊左右 padding）。
 * @property capsuleWidthPx 胶囊总宽 = [contentWidthPx] + 左右 padding × 2。
 */
data class TabBarPlan(
    val tabWidthsPx: List<Float>,
    val tabLeftsPx: List<Float>,
    val textSizeSp: Float,
    val contentWidthPx: Float,
    val capsuleWidthPx: Float,
)

/**
 * 底栏的宽度分配 —— **不按 weight 均分**，按文字实际宽度分（TG `MainTabsLayout.onMeasure`）。
 *
 * 这是 TG 底栏与 Material `NavigationBar` 最大的版式区别：四格不等宽，格子的宽窄跟着
 * 标签文字走（"项目"与"任务"两字同宽，"管理"窄一档，长标签占得更宽）。照抄的是
 * `TMessagesProj/.../MainTabsLayout.java` 的 `onMeasure`，逐句对着写：
 *
 * 1. **三趟试排**（`PASS_TEXT_SIZES_DP = {12, 12, 10}` × `PASS_PADDINGS_DP = {16, 8, 4}`）：
 *    先按 12sp / 16dp 内边距算总宽，塞不下就换 8dp 内边距（**字号不变** —— 第 1、2 趟
 *    字号相同，TG 用 `lastMeasuredTextSize` 省掉一次测量），再塞不下才降到 10sp / 4dp。
 *    选中的那一趟同时决定字号与内边距。
 * 2. **等分上限之外的长格不参与拉伸**：`maxTabTextWidthIfEq` = 内容区 ÷ 格数 − 2×内边距，
 *    比它宽的格子 `weight = 0` —— 它已经比"均分该得的"宽了，再拉就是抢别人的。
 * 3. **两端夹逼**：总宽超上限就整体等比压（`m = max/total`），不足下限（`min(dp(320), max)`）
 *    就按 weight 分掉差额。四格短标签的常态是**后者** —— 内容被撑到 320dp，胶囊才不至于
 *    缩成一小坨挤在屏幕中间。
 *
 * 本函数是**纯算术**：字体度量从 [textWidthAt] 注入，不碰 Compose。理由是这条算法有一半
 * 是能判对错的算术（谁被拉、谁不拉、夹逼到哪个数），那一半必须被单测钉住 ——
 * 与 [Particles] 同一条分工。
 *
 * @param labels 各格标签（顺序 = 显示顺序）。
 * @param density 每 dp 多少 px（`LocalDensity.current.density`）。
 * @param availableWidthPx 可用宽度（屏宽，px）。
 * @param maxWidthPx 胶囊宽上限（px）；<= 0 表示不设限。
 * @param paddingPx 胶囊内容区的左右内边距（px）—— 从可用宽度里先扣掉它。
 * @param textWidthAt 量一段文字在某个字号（sp）下的宽度（px）。**唯一的字体依赖点**。
 */
object TabBarMeasure {

    /** 三趟字号（`PASS_TEXT_SIZES_DP`，单位 sp）。第 1、2 趟同字号是 TG 原样，不是笔误。 */
    val PassTextSizesSp = floatArrayOf(12f, 12f, 10f)

    /** 三趟格内左右 padding（`PASS_PADDINGS_DP`，单位 dp）。 */
    val PassPaddingsDp = intArrayOf(16, 8, 4)

    /** 内容区总宽的**下限**（`Math.min(dp(320), maxTotalWidthForTabs)` 里那个 320dp）。 */
    const val MinTotalWidthDp = 320f

    fun measure(
        labels: List<String>,
        density: Float,
        availableWidthPx: Float,
        maxWidthPx: Float,
        paddingPx: Float,
        textWidthAt: (label: String, textSizeSp: Float) -> Float,
    ): TabBarPlan {
        val n = labels.size
        if (n == 0) {
            return TabBarPlan(emptyList(), emptyList(), PassTextSizesSp[0], 0f, 0f)
        }

        // 胶囊先被 maxWidth 夹一次（TG `if (maxWidthPx > 0 && width > maxWidthPx) width = maxWidthPx`），
        // 内容区再扣掉左右内边距 —— 顺序不可反：反了就是在"已经扣过 padding 的宽度"上再夹上限。
        val viewWidth = if (maxWidthPx > 0f && availableWidthPx > maxWidthPx) maxWidthPx else availableWidthPx
        val maxTotal = max(0f, viewWidth - paddingPx * 2f)
        val minTotal = min(MinTotalWidthDp * density, maxTotal)

        // 三趟试排：选中的那一趟同时定下字号与内边距。
        var chosen = PassTextSizesSp.size - 1
        var lastMeasuredSize = -1f
        val textWidths = FloatArray(n)
        for (pass in PassTextSizesSp.indices) {
            if (PassTextSizesSp[pass] != lastMeasuredSize) {
                for (i in 0 until n) textWidths[i] = textWidthAt(labels[i], PassTextSizesSp[pass])
                lastMeasuredSize = PassTextSizesSp[pass]
            }
            val pad = PassPaddingsDp[pass] * density
            var total = 0f
            for (i in 0 until n) total += textWidths[i] + pad * 2f
            if (total <= maxTotal || pass == PassTextSizesSp.size - 1) {
                chosen = pass
                break
            }
        }

        val tabPadding = PassPaddingsDp[chosen] * density
        val maxTabTextWidthIfEq = maxTotal / max(1, n) - tabPadding * 2f

        val withMargin = FloatArray(n)
        val weight = IntArray(n)
        var totalWidth = 0f
        var totalWeight = 0
        for (i in 0 until n) {
            withMargin[i] = textWidths[i] + tabPadding * 2f
            // 比"均分该得的"还宽 = 不参与拉伸（见类注释第 2 条）。
            weight[i] = if (withMargin[i] > maxTabTextWidthIfEq + tabPadding * 2f) 0 else 1
            totalWidth += withMargin[i]
            totalWeight += weight[i]
        }
        if (totalWeight == 0) {
            // 全都被判成"过宽"（比如格数少、标签长）：退回全体均分，否则差额没人接。
            for (i in 0 until n) weight[i] = 1
            totalWeight = n
        }

        if (totalWidth > maxTotal && totalWidth > 0f) {
            val m = maxTotal / totalWidth
            for (i in 0 until n) withMargin[i] *= m
        } else if (totalWidth < minTotal) {
            val growP = (minTotal - totalWidth) / totalWeight
            for (i in 0 until n) withMargin[i] += growP * weight[i]
        }

        val widths = ArrayList<Float>(n)
        val lefts = ArrayList<Float>(n)
        var l = 0f
        for (i in 0 until n) {
            val w = withMargin[i].roundToInt().toFloat()
            widths += w
            lefts += l
            l += w
        }
        return TabBarPlan(
            tabWidthsPx = widths,
            tabLeftsPx = lefts,
            textSizeSp = PassTextSizesSp[chosen],
            contentWidthPx = l,
            capsuleWidthPx = l + paddingPx * 2f,
        )
    }
}
