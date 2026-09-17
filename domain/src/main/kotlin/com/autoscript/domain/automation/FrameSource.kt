package com.autoscript.domain.automation

import com.autoscript.domain.bridge.HandleRef

/**
 * 帧源 SPI（docs/framework-design.md §9.2）。
 * 默认 a11y takeScreenshot（API34 333ms 节流）；会话式走 MediaProjection（Surface→ImageReader→libimgnative.so）。
 * FLAG_SECURE → ERR_SCREEN_LOCKED/ERR_BLACK_FRAME，不返回黑图。
 */
interface FrameSource {
    suspend fun capture(): ImageFrame

    /** 会话式（MediaProjection）一次性授权；reconnect 不自动重试授权。 */
    suspend fun openSession(): ScreenCaptureSession
}

/** JS `Image` 句柄 ↔ native 帧句柄；recycle() 显式 + finalize 兜底；dispose tombstone 同 §7.4。 */
data class ImageFrame(val handle: HandleRef, val width: Int, val height: Int) {
    /** 实现方默认空实现；真正释放由 native 帧句柄 dispose 协议承接（§7.4 tombstone）。 */
    suspend fun recycle() = Unit
}

interface ScreenCaptureSession {
    val isActive: Boolean
    suspend fun nextFrame(): ImageFrame
    suspend fun close()
}