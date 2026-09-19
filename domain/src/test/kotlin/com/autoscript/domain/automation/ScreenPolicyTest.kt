package com.autoscript.domain.automation

import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ScreenPolicyTest {

    @Test
    fun `正常屏幕放行`() {
        ScreenPolicy.requireCapturable(
            ScreenSnapshot(locked = false, secureForeground = false, hasWindows = true),
        )
    }

    @Test
    fun `FLAG_SECURE 优先于锁屏判定`() {
        val e = assertThrows<AutojsException> {
            ScreenPolicy.requireCapturable(
                ScreenSnapshot(locked = true, secureForeground = true, hasWindows = true),
            )
        }
        assertEquals(ErrorCode.ERR_BLACK_FRAME, e.error)
    }

    @Test
    fun `锁屏抛 SCREEN_LOCKED`() {
        val e = assertThrows<AutojsException> {
            ScreenPolicy.requireCapturable(
                ScreenSnapshot(locked = true, secureForeground = false, hasWindows = true),
            )
        }
        assertEquals(ErrorCode.ERR_SCREEN_LOCKED, e.error)
    }

    @Test
    fun `无窗口抛 SERVICE_DISABLED`() {
        val e = assertThrows<AutojsException> {
            ScreenPolicy.requireCapturable(
                ScreenSnapshot(locked = false, secureForeground = false, hasWindows = false),
            )
        }
        assertEquals(ErrorCode.ERR_SERVICE_DISABLED, e.error)
    }
}
