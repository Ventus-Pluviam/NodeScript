package com.autoscript.shell

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 开机判定的纯 JVM 验证（§8.6 重启后排期重建）：
 * 接收器本体碰 `android.content.*`，JVM 单测构造不了 —— 故判定抽成 [BootEvents]，
 * 这里只验"什么算开机"；接收器内部无分支（isBootCompleted 一行），无可错逻辑可漏。
 */
class BootEventsTest {

    @Test
    fun `开机完成广播被识别`() {
        assertTrue(BootEvents.isBootCompleted("android.intent.action.BOOT_COMPLETED"))
    }

    @Test
    fun `非开机广播不误认`() {
        assertFalse(BootEvents.isBootCompleted("com.autoscript.shell.action.ALARM_FIRE"))
        assertFalse(BootEvents.isBootCompleted(null))
        assertFalse(BootEvents.isBootCompleted(""))
    }

    @Test
    fun `字面量与框架常量同值（改名即编译期可见）`() {
        assertTrue(
            BootEvents.isBootCompleted(android.content.Intent.ACTION_BOOT_COMPLETED),
            "字面量必须与框架常量同值：漂移了开机接收器就永远收不到广播",
        )
    }
}
