package com.autoscript.platform.system

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * `device` Android 实现的契约测试（docs §9.6）：字段直通 + 空型号如实抛。
 *
 * 构造器缝（`model`/`sdkInt` 两个 lambda）让本测试零 Android 依赖 ——
 * 真机路径就是 `Build.MODEL` / `Build.VERSION.SDK_INT` 两个取值，没有别的逻辑。
 */
class AndroidDeviceInfoProviderTest {

    @Test
    fun `型号与 SDK 原样直通`() {
        val p = AndroidDeviceInfoProvider(model = { "Pixel 8" }, sdkInt = { 34 }).profile()
        assertEquals("Pixel 8", p.model)
        assertEquals(34, p.sdkInt)
    }

    @Test
    fun `空型号与非法 SDK 由 DeviceProfile 守卫拒绝，不静默放行`() {
        val blank = AndroidDeviceInfoProvider(model = { "" }, sdkInt = { 34 })
        assertThrows(IllegalArgumentException::class.java) { blank.profile() }

        val badSdk = AndroidDeviceInfoProvider(model = { "X" }, sdkInt = { 0 })
        assertThrows(IllegalArgumentException::class.java) { badSdk.profile() }
    }
}
