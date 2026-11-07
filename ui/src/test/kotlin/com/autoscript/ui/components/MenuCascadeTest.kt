package com.autoscript.ui.components

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 菜单子项瀑布动画（`AndroidUtilities.cascade` 的移植）的**错相**语义。
 *
 * 钉的是移植最容易写错的一处：上游 `position`/`count` 是 `float`，比值是 0..1 的
 * 错相系数；Kotlin 里写成 `Int / Int` 就是整数除法 —— position 恒 < count 时
 * 比值恒为 0，整列同起同落、瀑布静默消失（不崩不红，肉眼只在慢放时看得出）。
 */
class MenuCascadeTest {

    @Test
    fun `错相真实存在 —— 同刻不同项的进度不同，靠前的项领先`() {
        // count=6、waveLength=4：waveDuration = 4/6 = 2/3，waveOffset 按位置铺开。
        val count = 6
        val t = 0.5f
        val progresses = (0 until count).map { cascade(t, it, count, 4f) }
        // 整数除法下这一列会全相等（waveOffset 恒 0）—— 正是要钉死的退化。
        assertTrue(
            progresses.zipWithNext().all { (a, b) -> a > b },
            "同一时刻靠前的项必须领先靠后的项（瀑布），实际：$progresses",
        )
    }

    @Test
    fun `错相系数逐项核对 —— waveOffset = position除count 乘 (1 - waveDuration)`() {
        // count=6、waveLength=4：waveDuration=2/3；position=3 → offset = 0.5 × 1/3 = 1/6。
        // t=0.5 → (0.5 - 1/6) / (2/3) = 0.5。
        assertEquals(0.5f, cascade(0.5f, 3, 6, 4f), 1e-6f)
        // position=0 → offset=0 → 0.5 / (2/3) = 0.75。
        assertEquals(0.75f, cascade(0.5f, 0, 6, 4f), 1e-6f)
    }

    @Test
    fun `边界 —— 空列回原始进度，首尾各自夹进 0 与 1`() {
        assertEquals(0.5f, cascade(0.5f, 0, 0, 4f), 1e-6f)
        // 首项在 t=0 才起步（不提前），末项在 t=1 必然就位（不迟到）。
        assertEquals(0f, cascade(0f, 0, 6, 4f), 1e-6f)
        assertEquals(1f, cascade(1f, 5, 6, 4f), 1e-6f)
        // count=1：waveDuration = min(4,1) = 1，独项随总进度走。
        assertEquals(0.5f, cascade(0.5f, 0, 1, 4f), 1e-6f)
    }
}
