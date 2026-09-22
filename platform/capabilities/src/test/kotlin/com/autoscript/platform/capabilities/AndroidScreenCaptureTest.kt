package com.autoscript.platform.capabilities

import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * [AndroidFrameProducer] 经假 [A11yBridge] 的采集链（语义面 = 真 [ScreenshotSource]，
 * 设备面 = 假桥注入；设备层回调码映射在 ServiceBridge，本机不跑）。
 *
 * 钉三件事：服务未连全链 ERR_SERVICE_DISABLED；锁屏/无窗口走 ScreenPolicy 预检分类；
 * **回包尺寸 = 系统真值**（1440×3200 ≠ DEFAULT 1080×2400 —— wire 上不许报假尺寸）。
 */
class AndroidScreenCaptureTest {

    private fun source(b: FakeA11yBridge): ScreenshotSource =
        ScreenshotSource(AndroidFrameProducer(b))

    @Test
    fun `服务未连 采集全链 ERR_SERVICE_DISABLED`() {
        val b = FakeA11yBridge()
        b.connected = false
        val e = assertThrows(AutojsException::class.java) { runBlocking { source(b).capture() } }
        assertEquals(ErrorCode.ERR_SERVICE_DISABLED.code, e.error.code)
    }

    @Test
    fun `锁屏与无窗口走策略预检分类 不进截帧`() {
        val locked = FakeA11yBridge().apply { locked = true }
        val e1 = assertThrows(AutojsException::class.java) { runBlocking { source(locked).capture() } }
        assertEquals(ErrorCode.ERR_SCREEN_LOCKED.code, e1.error.code)

        val noWindows = FakeA11yBridge().apply { hasWindows = false }
        val e2 = assertThrows(AutojsException::class.java) { runBlocking { source(noWindows).capture() } }
        assertEquals(ErrorCode.ERR_SERVICE_DISABLED.code, e2.error.code)

        assertEquals(0, locked.screenshotCount, "锁屏：策略预检拦截，不许走到截帧")
        assertEquals(0, noWindows.screenshotCount, "无窗口：同上 —— 预检在 produce 之前")
    }

    @Test
    fun `成功回包携带系统真实尺寸 不再固定 DEFAULT`() {
        val b = FakeA11yBridge() // 假桥回 1440×3200（DEFAULT 是 1080×2400）
        val frame = runBlocking { source(b).capture() }
        assertEquals(1440, frame.width, "回包宽度必须是生产者真值（DEFAULT 尺寸是对 JS 报谎）")
        assertEquals(3200, frame.height)
        assertEquals(1L, frame.handle.generation)
        assertEquals(1, b.screenshotCount, "成功路径恰好截一次")
    }

    @Test
    fun `设备层分类错误原码穿语义面`() {
        val b = FakeA11yBridge().apply {
            screenshotFailure = AutojsException(ErrorCode.ERR_BLACK_FRAME, "FLAG_SECURE")
        }
        val e = assertThrows(AutojsException::class.java) { runBlocking { source(b).capture() } }
        assertEquals(ErrorCode.ERR_BLACK_FRAME.code, e.error.code, "安全窗分类必须原码穿到桥面")
    }

    @Test
    fun `produce 直连假桥 字节与尺寸原样`() {
        val b = FakeA11yBridge()
        val out = runBlocking { AndroidFrameProducer(b).produce(1080, 2400) }
        assertArrayEquals(byteArrayOf(1, 2, 3), out.bytes)
        assertEquals(1440, out.width, "尺寸以系统为准，入参提示被忽略")
        assertEquals(3200, out.height)
    }
}
