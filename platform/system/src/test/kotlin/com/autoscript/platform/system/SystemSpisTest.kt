package com.autoscript.platform.system

import com.autoscript.domain.bridge.HandleRef
import com.autoscript.domain.system.AppLauncher
import com.autoscript.domain.system.DeviceInfoProvider
import com.autoscript.domain.system.DeviceProfile
import com.autoscript.domain.system.FloatingWindowHost
import com.autoscript.domain.system.FloatingWindowSpec
import com.autoscript.domain.system.ShellExecutor
import com.autoscript.domain.system.ShellMode
import com.autoscript.domain.system.ShellResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 实现入口的形状测试（docs §12.2）：[SystemSpis.Bundle] 的四件都**声明成 `:domain` 契约类型**。
 *
 * 这里不验 Android 行为（各自的契约测试覆盖），守的是两件事：
 * 1. 别把具体实现类漏进字段类型 —— 那会逼上层依赖本模块的具体类，分层就白做了；
 * 2. 别偷偷补一个"凑数的 dialogs" —— 缺位是**如实缺位**（注入侧留 null → 桥回
 *    `ERR_NOT_IMPLEMENTED`），补个假的比缺着更坏。所以字段数被钉死在 4。
 */
class SystemSpisTest {

    @Test
    fun `Bundle 四件都是 domain 契约类型（具体类不外泄）`() {
        val bundle = SystemSpis.Bundle(
            shell = FakeShell,
            device = FakeDevice,
            app = FakeApp,
            floatingWindow = FakeFloating,
        )
        // 静态类型即断言：能赋进这些字段就说明字段类型是契约而非实现类。
        assertTrue(bundle.shell is ShellExecutor)
        assertTrue(bundle.device is DeviceInfoProvider)
        assertTrue(bundle.app is AppLauncher)
        assertTrue(bundle.floatingWindow is FloatingWindowHost)
    }

    @Test
    fun `dialogs 不在束里：缺位就是缺位，不拿假实现凑`() {
        val names = SystemSpis.Bundle::class.java.declaredFields.map { it.name }.toSet()
        assertEquals(setOf("shell", "device", "app", "floatingWindow"), names)
    }

    private object FakeShell : ShellExecutor {
        override suspend fun exec(command: String, mode: ShellMode, timeoutMillis: Long) =
            ShellResult(0, null, null)
    }

    private object FakeDevice : DeviceInfoProvider {
        override fun profile() = DeviceProfile("test", 34)
    }

    private object FakeApp : AppLauncher {
        override suspend fun launch(packageName: String) = false
        override suspend fun currentPackage(): String? = null
    }

    private object FakeFloating : FloatingWindowHost {
        override suspend fun create(spec: FloatingWindowSpec) = HandleRef(1, 1)
        override suspend fun close(ref: HandleRef) = Unit
    }
}
