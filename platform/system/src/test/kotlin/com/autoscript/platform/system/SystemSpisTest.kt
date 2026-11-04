package com.autoscript.platform.system

import com.autoscript.domain.bridge.HandleRef
import com.autoscript.domain.storage.DataStore
import com.autoscript.domain.storage.StoredEntry
import com.autoscript.platform.system.app.AppLauncher
import com.autoscript.platform.system.clipboard.Clipboard
import com.autoscript.platform.system.device.DeviceInfoProvider
import com.autoscript.platform.system.device.DeviceProfile
import com.autoscript.platform.system.floatingWindow.FloatingWindowHost
import com.autoscript.platform.system.floatingWindow.FloatingWindowSpec
import com.autoscript.platform.system.notification.NotificationPoster
import com.autoscript.platform.system.notification.NotificationSpec
import com.autoscript.platform.system.sensors.SensorDelay
import com.autoscript.platform.system.sensors.SensorEventBatch
import com.autoscript.platform.system.sensors.SensorSource
import com.autoscript.platform.system.settings.SystemSettings
import com.autoscript.platform.system.shell.ShellExecutor
import com.autoscript.platform.system.shell.ShellMode
import com.autoscript.platform.system.shell.ShellResult
import com.autoscript.platform.system.zip.ZipArchiver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 实现入口的形状测试（docs §12.2）：[SystemSpis.Bundle] 的十件都**声明成 `:domain` 契约类型**。
 *
 * 这里不验 Android 行为（各自的契约测试覆盖），守的是两件事：
 * 1. 别把具体实现类漏进字段类型 —— 那会逼上层依赖本模块的具体类，分层就白做了；
 * 2. 别偷偷补一个"凑数的 dialogs" —— 缺位是**如实缺位**（注入侧留 null → 桥回
 *    `ERR_NOT_IMPLEMENTED`），补个假的比缺着更坏。字段集合被钉死在**已落地的 SPI 名单**上
 *    （datastore/zip/settings/notification/clipboard/sensors 都在名单里；dialogs 仍不许出现）。
 */
class SystemSpisTest {

    @Test
    fun `Bundle 十件都是 domain 契约类型（具体类不外泄）`() {
        val bundle = SystemSpis.Bundle(
            shell = FakeShell,
            device = FakeDevice,
            app = FakeApp,
            floatingWindow = FakeFloating,
            datastore = FakeDatastore,
            zip = FakeZip,
            settings = FakeSettings,
            notification = FakeNotification,
            clipboard = FakeClipboard,
            sensors = FakeSensors,
        )
        // 静态类型即断言：能赋进这些字段就说明字段类型是契约而非实现类。
        assertTrue(bundle.shell is ShellExecutor)
        assertTrue(bundle.device is DeviceInfoProvider)
        assertTrue(bundle.app is AppLauncher)
        assertTrue(bundle.floatingWindow is FloatingWindowHost)
        assertTrue(bundle.datastore is DataStore)
        assertTrue(bundle.zip is ZipArchiver)
        assertTrue(bundle.settings is SystemSettings)
        assertTrue(bundle.notification is NotificationPoster)
        assertTrue(bundle.clipboard is Clipboard)
        assertTrue(bundle.sensors is SensorSource)
    }

    @Test
    fun `dialogs 不在束里：缺位就是缺位，不拿假实现凑`() {
        val names = SystemSpis.Bundle::class.java.declaredFields.map { it.name }.toSet()
        assertEquals(
            setOf("shell", "device", "app", "floatingWindow", "datastore", "zip", "settings", "notification", "clipboard", "sensors"),
            names,
        )
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

    private object FakeSettings : SystemSettings {
        override fun canWrite() = false
        override fun getString(key: String): String? = null
        override fun getInt(key: String): Int? = null
        override fun putString(key: String, value: String) = Unit
        override fun putInt(key: String, value: Int) = Unit
    }

    private object FakeClipboard : Clipboard {
        override fun getText(): String? = null
        override fun setText(text: String) = Unit
    }

    private object FakeSensors : SensorSource {
        override fun isSupported(name: String): Boolean = false
        override suspend fun register(name: String, delay: SensorDelay) = HandleRef(1, 1)
        override suspend fun unregister(ref: HandleRef) = Unit
        override suspend fun unregisterAll() = Unit
        override suspend fun drain(ref: HandleRef, sinceSeq: Long, max: Int) =
            SensorEventBatch(sinceSeq, sinceSeq, emptyList())
    }

    private object FakeNotification : NotificationPoster {
        override fun canPost() = false
        override fun post(spec: NotificationSpec) = Unit
        override fun cancel(id: Int) = Unit
    }

    private object FakeZip : ZipArchiver {
        override suspend fun compress(source: java.nio.file.Path, archive: java.nio.file.Path) = Unit
        override suspend fun extract(archive: java.nio.file.Path, targetDir: java.nio.file.Path) = Unit
    }

    private object FakeDatastore : DataStore {
        override suspend fun get(key: String): StoredEntry? = null
        override suspend fun put(key: String, value: StoredEntry) = Unit
        override suspend fun remove(key: String): StoredEntry? = null
        override suspend fun contains(key: String): Boolean = false
        override suspend fun keys(): List<String> = emptyList()
        override suspend fun clear() = Unit
        override suspend fun transaction(block: com.autoscript.domain.storage.DataStoreTxn.() -> Unit) = Unit
    }
}
