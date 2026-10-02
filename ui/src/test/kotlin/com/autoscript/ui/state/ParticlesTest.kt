package com.autoscript.ui.state

import androidx.compose.ui.geometry.Rect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 删除粒子的物理（[Particles]）—— 这条测试存在的理由：效果本身进不了自动化门
 * （要真机/Compose 运行时才看得见），但"散开得对不对"里有一半是**能判对错的算术**，
 * 那一半必须被钉住，否则整件事就只剩"我编译过了"。
 */
class ParticlesTest {

    private val row = Rect(left = 0f, top = 100f, right = 360f, bottom = 152f)

    @Test
    fun `同一种子必得同一簇（可复现，不是每次都不一样）`() {
        val a = Particles.burst(row, count = 40, seed = 12345L)
        val b = Particles.burst(row, count = 40, seed = 12345L)
        assertEquals(a, b)
        assertFalse(a == Particles.burst(row, count = 40, seed = 999L))
    }

    @Test
    fun `颗粒数与请求一致，且被上限截住`() {
        assertEquals(40, Particles.burst(row, count = 40, seed = 1L).size)
        assertEquals(0, Particles.burst(row, count = 0, seed = 1L).size)
        assertEquals(0, Particles.burst(row, count = -3, seed = 1L).size)
        assertEquals(
            Particles.MAX_PARTICLES,
            Particles.burst(row, count = 99_999, seed = 1L).size,
        )
    }

    @Test
    fun `零尺寸的行不产粒子（不画一堆同一点的碎屑）`() {
        val degenerate = Rect(left = 10f, top = 10f, right = 10f, bottom = 30f)
        assertEquals(0, Particles.burst(degenerate, count = 30, seed = 1L).size)
    }

    @Test
    fun `起点铺在行内 —— 散开的位置就是行原来的位置`() {
        for (p in Particles.burst(row, count = 120, seed = 7L)) {
            assertTrue(p.startX in 0f..row.width, "起点 x 越界：${p.startX}")
            assertTrue(p.startY in 0f..row.height, "起点 y 越界：${p.startY}")
        }
    }

    @Test
    fun `每颗都朝上窜（初速为负），横向有扩散`() {
        val ps = Particles.burst(row, count = 120, seed = 7L)
        assertTrue(ps.all { it.vy < 0f }, "有粒子没有向上初速")
        assertTrue(ps.any { it.vx > 0f } && ps.any { it.vx < 0f }, "横向没有双向扩散")
        assertTrue(ps.all { Particles.speed(it) > 0f })
    }

    @Test
    fun `进度 0 在起点、进度 1 淡完（透明度归零）`() {
        val p = Particles.burst(row, count = 1, seed = 3L).single()
        val start = Particles.at(p, row, 0f)
        assertEquals(row.left + p.startX, start.center.x, 1e-3f)
        assertEquals(row.top + p.startY, start.center.y, 1e-3f)
        assertEquals(p.alpha, start.alpha, 1e-3f)

        val end = Particles.at(p, row, 1f)
        assertEquals(0f, end.alpha, 1e-3f)
        assertFalse(Particles.alive(p, row, 1f), "结束时仍有粒子可见")
    }

    @Test
    fun `整簇在寿命内先升后落 —— 重力确实在起作用`() {
        val ps = Particles.burst(row, count = 60, seed = 11L)
        // 中途（半程）整体重心高于起点，说明"窜起来了"。
        val midLift = ps.map { Particles.at(it, row, 0.35f).center.y - row.top }
            .average()
        val startLift = ps.map { it.startY.toDouble() }.average()
        assertTrue(midLift < startLift, "半程没有升起来（mid=$midLift start=$startLift）")

        // 同一颗粒子：后半程的纵向速度比前半程慢（重力在减速），即落差比前半程小。
        val p = ps.first()
        val firstHalf = Particles.at(p, row, 0.5f).center.y - Particles.at(p, row, 0f).center.y
        val secondHalf = Particles.at(p, row, 1f).center.y - Particles.at(p, row, 0.5f).center.y
        assertTrue(secondHalf > firstHalf, "没有减速（前半 $firstHalf / 后半 $secondHalf）")
    }

    @Test
    fun `按面积给颗粒数，且不超上限`() {
        assertTrue(Particles.countFor(row) in 1..Particles.MAX_PARTICLES)
        assertEquals(0, Particles.countFor(Rect(0f, 0f, 0f, 0f)))
        assertEquals(
            Particles.MAX_PARTICLES,
            Particles.countFor(Rect(0f, 0f, 4000f, 4000f)),
        )
    }

    // ---- vanished：粒子该炸哪几行（绘制层据此决定炸不炸，故判据必须在可测面上）----

    @Test
    fun `首帧不炸 —— 没有上一次可比`() {
        assertEquals(emptyList<String>(), Particles.vanished(emptyList(), listOf("a", "b")))
    }

    @Test
    fun `消失的 id 按上一次的顺序返回（先消失先炸）`() {
        assertEquals(
            listOf("a", "b"),
            Particles.vanished(listOf("a", "b", "c"), listOf("c")),
        )
    }

    @Test
    fun `只是换了顺序不算消失`() {
        // 刷新后列表重排（按下次触发时间排序等）不该满屏粒子 —— 这条是那次误炸的回归线。
        assertEquals(
            emptyList<String>(),
            Particles.vanished(listOf("a", "b"), listOf("b", "a")),
        )
    }

    @Test
    fun `新增 id 不炸`() {
        assertEquals(emptyList<String>(), Particles.vanished(listOf("a"), listOf("a", "b")))
    }

    @Test
    fun `同一个 id 重复出现只炸一次`() {
        assertEquals(listOf("a"), Particles.vanished(listOf("a", "a"), emptyList()))
    }

    @Test
    fun `全部消失时逐条返回`() {
        assertEquals(
            listOf("a", "b", "c"),
            Particles.vanished(listOf("a", "b", "c"), emptyList()),
        )
    }
}
