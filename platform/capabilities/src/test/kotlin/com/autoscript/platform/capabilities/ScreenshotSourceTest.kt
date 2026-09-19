package com.autoscript.platform.capabilities

import com.autoscript.domain.automation.ImageFrame
import com.autoscript.domain.automation.ScreenSnapshot
import com.autoscript.domain.bridge.HandleRef
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

private fun producerOf(
    snapshot: ScreenSnapshot = ScreenSnapshot(locked = false, secureForeground = false, hasWindows = true),
    bytes: ByteArray = byteArrayOf(1, 2, 3),
    snapshots: ArrayDeque<ScreenSnapshot>? = null,
): ScreenshotSource.FrameProducer = object : ScreenshotSource.FrameProducer {
    override suspend fun snapshot(): ScreenSnapshot =
        if (snapshots != null && snapshots.isNotEmpty()) snapshots.removeFirst() else snapshot

    override suspend fun produce(width: Int, height: Int): ByteArray = bytes
}

private fun assertCode(code: ErrorCode, block: suspend () -> ImageFrame) {
    val e = assertThrows<AutojsException> { runBlocking { block() } }
    assertEquals(code, e.error)
}

class ScreenshotSourceTest {

    @Test
    fun `正常采集回帧句柄`() = runBlocking {
        val src = ScreenshotSource(producerOf())
        val f = src.capture()
        assertTrue(f.width > 0 && f.height > 0)
        assertEquals(1L, f.handle.generation)
    }

    @Test
    fun `锁屏抛 ERR_SCREEN_LOCKED（分类错误而非黑图）`() {
        val src = ScreenshotSource(
            producerOf(snapshot = ScreenSnapshot(locked = true, secureForeground = false, hasWindows = true)),
        )
        assertCode(ErrorCode.ERR_SCREEN_LOCKED) { src.capture() }
    }

    @Test
    fun `FLAG_SECURE 抛 ERR_BLACK_FRAME`() {
        val src = ScreenshotSource(
            producerOf(snapshot = ScreenSnapshot(locked = false, secureForeground = true, hasWindows = true)),
        )
        assertCode(ErrorCode.ERR_BLACK_FRAME) { src.capture() }
    }

    @Test
    fun `无窗口抛 ERR_SERVICE_DISABLED`() {
        val src = ScreenshotSource(
            producerOf(snapshot = ScreenSnapshot(locked = false, secureForeground = false, hasWindows = false)),
        )
        assertCode(ErrorCode.ERR_SERVICE_DISABLED) { src.capture() }
    }

    @Test
    fun `333ms 节流内连续采集拒绝`() = runBlocking {
        var now = 1_000L
        val src = ScreenshotSource(producerOf(), clock = { now })
        src.capture()
        now += 100
        assertCode(ErrorCode.ERR_INVALID_PARAM) { src.capture() }
        now += 300
        src.capture() // 窗口外恢复
        Unit                                           // 显式收尾：节流窗口外恢复成功
    }

    @Test
    fun `空帧抛 ERR_SERVICE_DISABLED`() {
        val src = ScreenshotSource(producerOf(bytes = byteArrayOf()))
        assertCode(ErrorCode.ERR_SERVICE_DISABLED) { src.capture() }
    }

    @Test
    fun `recycle 幂等，未知句柄抛 STALE_HANDLE`() = runBlocking {
        val src = ScreenshotSource(producerOf())
        val f = src.capture()
        src.recycle(f.handle)
        src.recycle(f.handle) // 幂等通过
        val e = assertThrows<AutojsException> {
            runBlocking { src.recycle(HandleRef(999L, 1L)) }
        }
        assertEquals(ErrorCode.ERR_STALE_HANDLE, e.error)
    }

    @Test
    fun `会话 nextFrame 同样先过屏幕策略`() = runBlocking {
        val src = ScreenshotSource(
            producerOf(
                snapshots = ArrayDeque(
                    listOf(
                        ScreenSnapshot(locked = false, secureForeground = false, hasWindows = true),
                        ScreenSnapshot(locked = true, secureForeground = false, hasWindows = true),
                    ),
                ),
            ),
        )
        val s = src.openSession()
        assertTrue(s.isActive)
        val e = assertThrows<AutojsException> { runBlocking { s.nextFrame() } }
        assertEquals(ErrorCode.ERR_SCREEN_LOCKED, e.error)
        s.close()
    }

    @Test
    fun `会话关闭后 nextFrame 抛 SERVICE_DISABLED`() = runBlocking {
        val src = ScreenshotSource(producerOf())
        val s = src.openSession()
        s.close()
        val e = assertThrows<AutojsException> { runBlocking { s.nextFrame() } }
        assertEquals(ErrorCode.ERR_SERVICE_DISABLED, e.error)
    }

    @Test
    fun `会话 open 时即做策略判定`() {
        val src = ScreenshotSource(
            producerOf(snapshot = ScreenSnapshot(locked = true, secureForeground = false, hasWindows = true)),
        )
        val e = assertThrows<AutojsException> { runBlocking { src.openSession() } }
        assertInstanceOf(AutojsException::class.java, e)
        assertEquals(ErrorCode.ERR_SCREEN_LOCKED, e.error)
    }
}
