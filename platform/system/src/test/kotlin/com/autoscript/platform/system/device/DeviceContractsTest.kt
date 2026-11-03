package com.autoscript.platform.system.device

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * `device` 契约（`DeviceProfile` 的空型号 / SDK 越界守卫）
 *
 * 形状冻结：DTO 字段与 JS facade（`extras.ts`）的返回体逐字对齐，桥只透传 ——
 * 任何一侧改名都会在另一侧变成 `undefined`，故在此锚死。非法即拒：构造期
 * `require` 的边界在下方逐条钉住，handler 据此折叠 ERR_INVALID_PARAM。
 *
 * **来历**：原 `SystemHostContractsTest`（shell/device/floatingWindow 三面合一份，
 * 自 `:domain` `SystemContractsTest` 拆入本模块）在
 * 按命名空间子包拆开，跟各自契约同包。
 */
class DeviceContractsTest {

    // ── device ─────────────────────────────────────────────────────

    @Test
    fun `DeviceProfile 拒绝空型号与非法 SDK`() {
        assertThrows(IllegalArgumentException::class.java) { DeviceProfile(model = "", sdkInt = 34) }
        assertThrows(IllegalArgumentException::class.java) { DeviceProfile(model = "Pixel", sdkInt = 0) }
        assertEquals(34, DeviceProfile("Pixel 8", 34).sdkInt)
        Unit
    }
}
