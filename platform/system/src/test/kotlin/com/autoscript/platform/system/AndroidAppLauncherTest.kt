package com.autoscript.platform.system

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `app` Android 实现的契约测试（docs §9.3）：false / null 都是**答案**而非异常。
 *
 * 两个缝（[AndroidAppLauncher.AppOps] / [AndroidAppLauncher.ForegroundEvents]）
 * 让本测试零 Android 依赖：可枚举的启动结果 + 可控的前台事件序列。
 */
class AndroidAppLauncherTest {

    @Test
    fun `包不存在回 false，不抛异常`() = runBlocking {
        val launcher = AndroidAppLauncher(FakeOps(hasEntry = false), AndroidAppLauncher.ForegroundEvents { emptyList() })
        assertFalse(launcher.launch("com.nope"))
    }

    @Test
    fun `包在但起不来（被系统拦）也回 false`() = runBlocking {
        val launcher = AndroidAppLauncher(FakeOps(hasEntry = true, failStart = true), AndroidAppLauncher.ForegroundEvents { emptyList() })
        assertFalse(launcher.launch("com.blocked"))
    }

    @Test
    fun `启动成功回 true`() = runBlocking {
        val ops = FakeOps(hasEntry = true)
        val launcher = AndroidAppLauncher(ops, AndroidAppLauncher.ForegroundEvents { emptyList() })
        assertTrue(launcher.launch("com.demo"))
        assertEquals(listOf("com.demo"), ops.started)
    }

    @Test
    fun `前台包名取时间戳最大者，不看查询顺序`() = runBlocking {
        // 乱序给你：选择逻辑按时间戳而非"最后一条"（queryEvents 的顺序无契约保证）。
        val events = listOf(
            ForegroundEvent("com.later", 300),
            ForegroundEvent("com.earlier", 100),
            ForegroundEvent("com.middle", 200),
        )
        val launcher = AndroidAppLauncher(FakeOps(hasEntry = true), AndroidAppLauncher.ForegroundEvents { events })
        assertEquals("com.later", launcher.currentPackage())
    }

    @Test
    fun `查不到前台事件回 null，不给空串`() = runBlocking {
        val launcher = AndroidAppLauncher(FakeOps(hasEntry = true), AndroidAppLauncher.ForegroundEvents { emptyList() })
        assertNull(launcher.currentPackage())   // 空串会一路拼进日志，看起来像个包名
    }

    @Test
    fun `回看窗口是分钟级（太长会捞到已退到后台的包）`() = runBlocking {
        var since = -1L
        val now = 1_700_000_000_000L
        val launcher = AndroidAppLauncher(
            FakeOps(hasEntry = true),
            AndroidAppLauncher.ForegroundEvents { since = it; emptyList() },
            clock = { now },
        )
        launcher.currentPackage()
        assertEquals(now - 5 * 60 * 1000L, since)
    }

    private class FakeOps(
        private val hasEntry: Boolean,
        private val failStart: Boolean = false,
    ) : AndroidAppLauncher.AppOps {
        val started = ArrayList<String>()

        override fun hasLaunchEntry(packageName: String): Boolean = hasEntry

        override suspend fun start(packageName: String) {
            if (failStart) error("被系统拦下")
            started += packageName
        }
    }
}
