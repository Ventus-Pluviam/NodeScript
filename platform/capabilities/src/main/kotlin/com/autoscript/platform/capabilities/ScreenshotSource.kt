package com.autoscript.platform.capabilities

import com.autoscript.domain.automation.ImageFrame
import com.autoscript.domain.automation.ScreenCaptureSession
import com.autoscript.domain.automation.ScreenPolicy
import com.autoscript.domain.automation.ScreenSnapshot
import com.autoscript.domain.bridge.HandleRef
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/**
 * 截图帧源（docs §9.2 / §8.8 截图直连）：
 * 采集前先过 [ScreenPolicy.requireCapturable] —— 锁屏/FLAG_SECURE/无窗口一律抛
 * 分类错误（ERR_SCREEN_LOCKED/ERR_BLACK_FRAME/ERR_SERVICE_DISABLED），绝不返回黑图。
 *
 * 本类是 JVM 可测形态：帧内容由 [FrameProducer] 注入（内存假帧）；
 * Android 真实现 [AndroidFrameProducer] 已接（a11y `takeScreenshot`，节流/句柄/
 * 会话语义在本类不变）；MediaProjection ImageReader→libimgnative.so 是后续升级，
 * 换 producer 即插。
 *
 * - 333ms 节流（a11y takeScreenshotOfWindow，API34）：同源连续 capture 按
 *   [THROTTLE_MILLIS] 限频，节流命中抛 ERR_INVALID_PARAM（调用方退避重试，
 *   不静默返回上一帧——上一帧可能已 recycle，复用即野句柄）；
 * - 帧句柄 generation=1（单帧单句柄，不复用；recycle 幂等，同 §7.4 dispose 语义）；
 * - 会话式（MediaProjection）：[openSession] 一次性授权，会话内 nextFrame 同样先过
 *   屏幕策略；reconnect 不自动重试授权（用完即 close，由 PermissionCenter 引导重授权）；
 * - 会话外 nextFrame / 已 close 后操作 → ERR_SERVICE_DISABLED（诚实上报，不伪造帧）。
 */
class ScreenshotSource(
    private val producer: FrameProducer,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : com.autoscript.domain.automation.FrameSource {

    interface FrameProducer : SnapshotAwareProducer {
        /**
         * 产出一帧像素（内容 opaque；空字节视为无可用帧）。
         * 入参是**请求提示**（生产者可忽略 —— 系统给实际尺寸）；回包尺寸以
         * [ProducedFrame] 为准 —— ImageFrame 的 width/height 必须是真实帧尺寸，
         * 曾经固定回 DEFAULT 尺寸就是对 JS 报假尺寸（wire 上的谎一律不留）。
         */
        suspend fun produce(width: Int, height: Int): ProducedFrame
    }

    private val ids = AtomicLong(1)
    private val guard = Mutex()
    private val liveFrames = HashMap<Long, FrameEntry>()
    private var lastCaptureAt: Long = Long.MIN_VALUE

    private data class FrameEntry(
        val frame: ImageFrame,
        var recycled: Boolean = false,
    )

    override suspend fun capture(): ImageFrame {
        val snapshot = producer.snapshot()
        ScreenPolicy.requireCapturable(snapshot)
        val now = clock()
        guard.withLock {
            if (lastCaptureAt != Long.MIN_VALUE && now - lastCaptureAt < THROTTLE_MILLIS) {
                throw AutojsException(
                    ErrorCode.ERR_INVALID_PARAM,
                    "截图节流中（333ms）：上次 $lastCaptureAt，当前 $now，调用方退避重试",
                )
            }
            lastCaptureAt = now
        }
        val frame = producer.produce(DEFAULT_WIDTH, DEFAULT_HEIGHT)
        if (frame.bytes.isEmpty()) {
            throw AutojsException(ErrorCode.ERR_SERVICE_DISABLED, "帧生产者无可用帧")
        }
        return track(ImageFrame(HandleRef(ids.getAndIncrement(), 1L), frame.width, frame.height))
    }

    override suspend fun openSession(): ScreenCaptureSession {
        val snapshot = producer.snapshot()
        ScreenPolicy.requireCapturable(snapshot)
        return CaptureSession(this)
    }

    /** 显式释放（幂等；JS Image.recycle 对偶）。未知句柄 → ERR_STALE_HANDLE。 */
    override suspend fun recycle(handle: HandleRef) {
        guard.withLock {
            val e = liveFrames[handle.refId]
                ?: throw AutojsException(ErrorCode.ERR_STALE_HANDLE, "未知帧句柄 ${handle.refId}")
            if (e.frame.handle.generation != handle.generation) {
                throw AutojsException(
                    ErrorCode.ERR_STALE_HANDLE,
                    "帧句柄跨代 ${handle.refId} gen=${handle.generation}",
                )
            }
            e.recycled = true
        }
    }

    internal suspend fun nextFramed(snapshotFirst: Boolean = true): ImageFrame {
        if (snapshotFirst) {
            ScreenPolicy.requireCapturable(producer.snapshot())
        }
        val frame = producer.produce(DEFAULT_WIDTH, DEFAULT_HEIGHT)
        if (frame.bytes.isEmpty()) {
            throw AutojsException(ErrorCode.ERR_SERVICE_DISABLED, "会话无可用帧")
        }
        return track(ImageFrame(HandleRef(ids.getAndIncrement(), 1L), frame.width, frame.height))
    }

    private suspend fun track(frame: ImageFrame): ImageFrame {
        guard.withLock { liveFrames[frame.handle.refId] = FrameEntry(frame) }
        return frame
    }

    private inner class CaptureSession(
        private val parent: ScreenshotSource,
    ) : ScreenCaptureSession {
        @Volatile private var closed = false

        override val isActive: Boolean get() = !closed

        override suspend fun nextFrame(): ImageFrame {
            if (closed) throw AutojsException(ErrorCode.ERR_SERVICE_DISABLED, "截图会话已关闭")
            return parent.nextFramed()
        }

        override suspend fun close() {
            closed = true
        }
    }

    companion object {
        const val THROTTLE_MILLIS: Long = 333

        /** 请求提示（给生产者的建议尺寸）；**不是**回包尺寸 —— 回包取 [ProducedFrame] 真实值。 */
        const val DEFAULT_WIDTH: Int = 1080
        const val DEFAULT_HEIGHT: Int = 2400
    }
}

/**
 * 一帧的产出（bytes 内容 opaque + 实际尺寸）。
 * 尺寸随帧走：设备面（a11y `ScreenshotResult` 的 HardwareBuffer）知道真值，
 * 语义面照抄 —— 中间不留"默认尺寸"的谎位。
 */
data class ProducedFrame(val bytes: ByteArray, val width: Int, val height: Int)

/** 帧生产者附带屏幕快照（采集前策略判定输入；Android 实现经 KeyguardManager/窗口态组装）。 */
interface SnapshotAwareProducer {
    suspend fun snapshot(): ScreenSnapshot
}