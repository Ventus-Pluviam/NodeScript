package com.autoscript.ui.components

import androidx.compose.ui.focus.FocusManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 跨窗口焦点清理登记的**两档分派**（2026-10-16 用户报「悬浮窗与应用的输入焦点互不收」）。
 *
 * 钉的是那条纪律：**只清对方、不清自己**。
 * - [FocusDismissRegistry.clearSeparateWindows]（应用窗口收到触摸）只许动**独立窗口**
 *   那一档 —— 应用侧各面由本窗口 Compose 树自己的 `dismissFocusOnTapOutside` 管；
 *   在这里连它们一起清，会出现"在输入框里点一下放光标 → 清一次再聚焦 → 输入法闪一下"；
 * - [FocusDismissRegistry.clearAppFaces]（悬浮窗被点）只许动应用侧那一档，
 *   并把 `except`（调用者自己那份 FocusManager）原样传下去。
 *
 * 登记的回调收 `FocusManager?`，所以本测试**不需要真 FocusManager**：
 * 传 null 即可 —— 这正是这条缝能在 JVM 单测里被钉住的原因（它只做分派，不做焦点操作）。
 */
class FocusDismissRegistryTest {

    @Test
    fun `应用窗口触摸只清独立窗口那一档`() {
        val hits = mutableListOf<String>()
        val offApp = FocusDismissRegistry.register(separateWindow = false) { hits += "app" }
        val offOverlay = FocusDismissRegistry.register(separateWindow = true) { hits += "overlay" }
        try {
            FocusDismissRegistry.clearSeparateWindows()
            assertEquals(listOf("overlay"), hits, "只许动独立窗口那一档")
        } finally {
            offApp(); offOverlay()
        }
    }

    @Test
    fun `悬浮窗被点只清应用侧那一档 且把 except 原样传下去`() {
        val hits = mutableListOf<String?>()
        val marker = null as FocusManager?
        val offApp = FocusDismissRegistry.register(separateWindow = false) { except -> hits += "app:${except === marker}" }
        val offOverlay = FocusDismissRegistry.register(separateWindow = true) { except -> hits += "overlay:${except === marker}" }
        try {
            FocusDismissRegistry.clearAppFaces(except = marker)
            assertEquals(listOf("app:true"), hits, "只许动应用侧那一档，且 except 要原样传下去")
        } finally {
            offApp(); offOverlay()
        }
    }

    @Test
    fun `注销之后不再被清`() {
        val hits = mutableListOf<String>()
        val off = FocusDismissRegistry.register(separateWindow = true) { hits += "overlay" }
        off()
        FocusDismissRegistry.clearSeparateWindows()
        assertTrue(hits.isEmpty(), "注销过的面不该再被清（组合离场后不留悬空引用）")
    }

    @Test
    fun `两档各自独立 一次 clear 不串到另一档`() {
        var appHits = 0
        var overlayHits = 0
        val offApp = FocusDismissRegistry.register(separateWindow = false) { appHits++ }
        val offOverlay = FocusDismissRegistry.register(separateWindow = true) { overlayHits++ }
        try {
            FocusDismissRegistry.clearAppFaces()
            assertEquals(1, appHits)
            assertEquals(0, overlayHits)
            FocusDismissRegistry.clearSeparateWindows()
            assertEquals(1, appHits)
            assertEquals(1, overlayHits)
        } finally {
            offApp(); offOverlay()
        }
    }

    @Test
    fun `注册表按实例记账 同一个动作登记两次清两次`() {
        var hits = 0
        val clear: (FocusManager?) -> Unit = { hits++ }
        val off1 = FocusDismissRegistry.register(separateWindow = true, clear = clear)
        val off2 = FocusDismissRegistry.register(separateWindow = true, clear = clear)
        try {
            FocusDismissRegistry.clearSeparateWindows()
            assertEquals(2, hits, "两次登记 = 两个面（两个窗口各一份），不是同一条被去重")
        } finally {
            off1(); off2()
        }
    }
}
