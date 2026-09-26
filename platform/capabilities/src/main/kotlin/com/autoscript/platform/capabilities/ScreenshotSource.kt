package com.autoscript.platform.capabilities

import com.autoscript.domain.automation.ImageAnalyzer
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
 * 会话语义在本类不变）；MediaProjection ImageReader→libopencv.so 是后续升级，
 * 换 producer 即插。
 *
 * - 333ms 节流（a11y takeScreenshotOfWindow，API34）：同源连续 capture 按
 *   [THROTTLE_MILLIS] 限频，节流命中抛 ERR_INVALID_PARAM（调用方退避重试，
 *   不静默返回上一帧——上一帧可能已 recycle，复用即野句柄）；
 * - 帧句柄 generation=1（单帧单句柄，不复用；recycle 幂等，同 §7.4 dispose 语义）；
 * - 会话式（MediaProjection）：[openSession] 一次性授权，会话内 nextFrame 同样先过
 *   屏幕策略；reconnect 不自动重试授权（用完即 close，由 PermissionCenter 引导重授权）；
 *   [openSession] 的 width/height 是**请求提示**（透传给生产者，可被忽略），回包尺寸
 *   恒为真实帧 —— 请求过尺寸不代表能拿到那个尺寸；
 * - 会话外 nextFrame / 已 close 后操作 → ERR_SERVICE_DISABLED（诚实上报，不伪造帧）。
 *
 * **帧表与 `images` 共用**（§18 第 8 项 (b) 2026-09-25 拍板，发号侧归一）：给了
 * [analyzer] 就把截出的帧经 [ImageAnalyzer.ingest] 登记进**它的**帧表 —— 于是
 * `screen.capture()` 的句柄与 `images.decode()` 的句柄同号段、互认：
 * `images.findImage(screenFrame, decodeFrame)` 通，`images.release(screenFrame)` 也通，
 * "帧不通用"那条纪律取消。此时本类**不再自持在场面表**（`ids`/`liveFrames` 只在
 * analyzer 为空时用，见 [recycle]）。
 *
 * **analyzer 为空时的降级**：本地发号 + 本地 STALE 判据（老路径，`ScreenshotSourceTest`
 * 默认走这条）。两条路不会撞号 —— `images` 命名空间只在 analyzer 非空时才注册
 * （`PlatformWiring`），analyzer 为空即 `images.*` 根本不在桥上，不存在"同一号段
 * 两边各发一次"的可能。
 *
 * [FrameProducer.produce] 的 `bytes` 在 **analyzer 非空**时是契约像素：紧密打包
 * RGBA（`width*height*4`），不是 opaque 占位 —— `ingest` 会拒收尺寸不符（
 * `ERR_IO`/`IllegalArgumentException`）。analyzer 为空时照旧只要非空。
 */
class ScreenshotSource(
    private val producer: FrameProducer,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val analyzer: ImageAnalyzer? = null,
) : com.autoscript.domain.automation.FrameSource {

    interface FrameProducer : SnapshotAwareProducer {
        /**
         * 产出一帧像素（空字节视为无可用帧）。**内容语义看 ScreenshotSource 拿不拿
         * analyzer**：拿了就是契约 RGBA 紧排像素（`width*height*4`，交给
         * `ImageAnalyzer.ingest`），没拿则照旧 opaque（只验非空）。
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
        return register(frame)
    }

    /**
     * [width]/[height] 透传给会话 → 每帧的 [FrameProducer.produce] 入参（**请求提示**，
     * 生产者可忽略，见其 KDoc）。提示只影响"想截多大"，**回包尺寸恒为真实帧**。
     */
    override suspend fun openSession(width: Int?, height: Int?): ScreenCaptureSession {
        val snapshot = producer.snapshot()
        ScreenPolicy.requireCapturable(snapshot)
        return CaptureSession(this, width, height)
    }

    /**
     * 显式释放（JS Image.recycle 对偶）。**判据随发号走**：
     * analyzer 非空 → 直接 [ImageAnalyzer.release]（与 `images.release` 同一张表、
     * 同一口径 —— 于是 screen 的帧也能从 `images` 那侧放，反之亦然；二次放如实
     * `ERR_STALE_HANDLE`，与 SPI 同码）；analyzer 为空 → 本地表，老语义**幂等**
     * （第二次放静默过，`ScreenshotSourceTest` 的既有契约）。未知句柄一律
     * `ERR_STALE_HANDLE`。
     */
    override suspend fun recycle(handle: HandleRef) {
        val spi = analyzer
        if (spi != null) {
            spi.release(handle)
            return
        }
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

    /** [width]/[height] 为请求提示（缺省走 [DEFAULT_WIDTH]/[DEFAULT_HEIGHT]）；回包尺寸取 [ProducedFrame] 真值。 */
    internal suspend fun nextFramed(
        snapshotFirst: Boolean = true,
        width: Int? = null,
        height: Int? = null,
    ): ImageFrame {
        if (snapshotFirst) {
            ScreenPolicy.requireCapturable(producer.snapshot())
        }
        val frame = producer.produce(width ?: DEFAULT_WIDTH, height ?: DEFAULT_HEIGHT)
        if (frame.bytes.isEmpty()) {
            throw AutojsException(ErrorCode.ERR_SERVICE_DISABLED, "会话无可用帧")
        }
        return register(frame)
    }

    /** 登记一帧：analyzer 在场走 SPI 帧表（§18-8(b) 发号侧归一），否则本地发号。 */
    private suspend fun register(frame: ProducedFrame): ImageFrame {
        val spi = analyzer
        if (spi != null) {
            return spi.ingest(frame.width, frame.height, frame.bytes)
        }
        return guard.withLock {
            val id = ids.getAndIncrement()
            val issued = ImageFrame(HandleRef(id, 1L), frame.width, frame.height)
            liveFrames[id] = FrameEntry(issued)
            issued
        }
    }

    private inner class CaptureSession(
        private val parent: ScreenshotSource,
        private val hintWidth: Int? = null,
        private val hintHeight: Int? = null,
    ) : ScreenCaptureSession {
        @Volatile private var closed = false

        override val isActive: Boolean get() = !closed

        override suspend fun nextFrame(): ImageFrame {
            if (closed) throw AutojsException(ErrorCode.ERR_SERVICE_DISABLED, "截图会话已关闭")
            return parent.nextFramed(snapshotFirst = true, width = hintWidth, height = hintHeight)
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
 * 一帧的产出（实际尺寸随帧走；`bytes` 语义见 [ScreenshotSource.FrameProducer.produce]）。
 * 尺寸随帧走：设备面（a11y `ScreenshotResult` 的 HardwareBuffer）知道真值，
 * 语义面照抄 —— 中间不留"默认尺寸"的谎位。
 */
data class ProducedFrame(val bytes: ByteArray, val width: Int, val height: Int)

/** 帧生产者附带屏幕快照（采集前策略判定输入；Android 实现经 KeyguardManager/窗口态组装）。 */
interface SnapshotAwareProducer {
    suspend fun snapshot(): ScreenSnapshot
}