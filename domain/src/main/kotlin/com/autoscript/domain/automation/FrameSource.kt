package com.autoscript.domain.automation

import com.autoscript.domain.bridge.HandleRef

/**
 * 帧源 SPI（docs/framework-design.md §9.2，已有契约，本文件只补错误分类）。
 * 默认 a11y takeScreenshot（API34 333ms 节流）；会话式走 MediaProjection（Surface→ImageReader→libopencv.so）。
 * FLAG_SECURE → ERR_SCREEN_LOCKED/ERR_BLACK_FRAME（§8.8），不返回黑图。
 */
interface FrameSource {
    suspend fun capture(): ImageFrame

    /**
     * 会话式（MediaProjection）一次性授权；reconnect 不自动重试授权。
     *
     * [width]/[height] 是**请求提示**，不是承诺（§12.3.3 收口）：生产者可忽略 ——
     * 系统给什么尺寸就是什么尺寸，回包尺寸恒以真实帧为准（`ProducedFrame` 随帧走），
     * 不因请求过尺寸就报假数。缺省 null = 不带提示（与旧调用同形）。
     */
    suspend fun openSession(width: Int? = null, height: Int? = null): ScreenCaptureSession

    /**
     * 显式释放帧句柄（JS `Image.recycle` 对偶；幂等）。
     * 未知/跨代句柄抛 ERR_STALE_HANDLE（与 §7.4 dispose 语义同）—— 调用方可区分
     * "已释放"与"从未存在"，绝不把野句柄当成功回收。
     */
    suspend fun recycle(handle: HandleRef)
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

/**
 * 屏幕可用性判定（§8.8 截图直连 §9.2：分类错误而非黑图）。
 * 实现层（:app ScreenGate 真实现 / capabilities 截图源）在采集前调用：
 * 锁屏/无可用窗口/FLAG_SECURE 一律抛分类错误的 [AutojsException]，
 * 脚本侧可 try/catch 策略分支（重试/降级/报错），绝不拿到一张黑图还以为成功。
 */
object ScreenPolicy {
    /**
     * 按屏幕快照判定可否采集。不允许时抛对应 [com.autoscript.domain.core.ErrorCode]：
     * - 锁屏（keyguard）→ ERR_SCREEN_LOCKED；
     * - FLAG_SECURE 前台窗口 → ERR_BLACK_FRAME；
     * - a11y 锁屏无可用窗口 → ERR_SERVICE_DISABLED（无障碍通道本身不可用）。
     */
    fun requireCapturable(snapshot: ScreenSnapshot) {
        when {
            snapshot.secureForeground ->
                throw com.autoscript.domain.core.AutojsException(
                    com.autoscript.domain.core.ErrorCode.ERR_BLACK_FRAME,
                    "前台窗口含 FLAG_SECURE，拒绝返回黑图",
                )
            snapshot.locked ->
                throw com.autoscript.domain.core.AutojsException(
                    com.autoscript.domain.core.ErrorCode.ERR_SCREEN_LOCKED,
                    "屏幕锁定，无法截取",
                )
            !snapshot.hasWindows ->
                throw com.autoscript.domain.core.AutojsException(
                    com.autoscript.domain.core.ErrorCode.ERR_SERVICE_DISABLED,
                    "无可用窗口（a11y 锁屏无窗口树），截图通道不可用",
                )
        }
    }
}

/** 屏幕快照（采集前的一次只读判定输入；Android 实现由 :app 经 KeyguardManager/窗口态组装）。 */
data class ScreenSnapshot(
    val locked: Boolean,
    val secureForeground: Boolean,
    val hasWindows: Boolean,
)
