package com.autoscript.ui.state

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * [ScreenStatus.hint] 的三态纪律（批 113，顶栏不再重画计数与空态）。
 *
 * 这一条判据没有 Composable 侧的守卫可依赖 —— 七处 `ActionBar(subtitle = status.hint)`
 * 只是把值传下去，画不画全看 hint 是不是 null。所以把「哪一态有 hint」钉在状态层。
 */
class ScreenStatusTest {

    @Test
    fun `没读到时 hint 就是那一句——屏内什么都不画，这句话只有顶栏一个出口`() {
        val s = Status.count(
            load = LoadState.NotLoaded,
            notLoadedText = "尚未读取（点右上「刷新」现取）",
            total = 0,
            emptyText = "还没有变量",
            unit = "个变量",
        )
        assertEquals("尚未读取（点右上「刷新」现取）", s.hint)
        // text 与 hint 同句：屏内卡片与顶栏讲同一件事，但只有顶栏那一份会被顶栏画。
        assertEquals(s.text, s.hint)
    }

    @Test
    fun `读到了时 hint 恒为 null——「共 N 行」由屏内 EmptyHint 讲，不在顶栏占第二处`() {
        val s = Status.count(
            load = LoadState.Loaded,
            notLoadedText = "尚未读取",
            total = 7,
            emptyText = "还没有变量",
            unit = "个变量",
        )
        assertNull(s.hint)
        assertEquals("共 7 个变量", s.text)
    }

    @Test
    fun `读到了且计数为 0 时 hint 同样是 null——空态归屏内`() {
        val s = Status.count(
            load = LoadState.Loaded,
            notLoadedText = "尚未读取",
            total = 0,
            emptyText = "还没有变量",
            unit = "个变量",
        )
        assertNull(s.hint)
        assertEquals("还没有变量", s.text)
    }

    @Test
    fun `读失败时 hint 恒为 null——原文已在屏内横幅，顶栏不重复`() {
        val s = Status.of(
            load = LoadState.Failed("读不到"),
            notLoadedText = "尚未读取",
            loadedText = "共 3 行",
        )
        assertNull(s.hint)
        assertEquals(StatusTone.PROBLEM, s.tone)
        assertEquals("读不到", s.text)
    }

    @Test
    fun `Status of 的没读到态与 count 同纪律`() {
        val s = Status.of(
            load = LoadState.NotLoaded,
            notLoadedText = "尚未读取",
            loadedText = "共 3 行",
        )
        assertEquals("尚未读取", s.hint)
    }
}
