package com.autoscript.platform.capabilities.screen

import com.autoscript.domain.automation.ColorHit
import com.autoscript.domain.automation.FeatureHit
import com.autoscript.domain.automation.ImageAnalyzer
import com.autoscript.domain.automation.ImageFrame
import com.autoscript.domain.automation.ImageMatch
import com.autoscript.domain.automation.LeasedMediaProjectionSession
import com.autoscript.domain.automation.MediaProjectionOpenException
import com.autoscript.domain.automation.MediaProjectionSessionOwner
import com.autoscript.domain.automation.MediaProjectionSessionState
import com.autoscript.domain.automation.MediaProjectionSessions
import com.autoscript.domain.automation.RawFrame
import com.autoscript.domain.automation.ScreenConsentToken
import com.autoscript.domain.automation.SessionResourceLease
import com.autoscript.domain.bridge.HandleRef
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred

/**
 * 投屏面测试替身（`MediaProjectionSourceTest` / `ScreenNamespaceHandlerProjectionTest` 共用）。
 *
 * 为什么不各写一份：两个文件测的是**同一条链路的两层**（语义层 / handler 层），
 * 替身若各写各的，两处对"设备层怎么表现"的假设就会漂 —— 一处改了另一处不报错，
 * 而它们本该在描述同一个设备。
 *
 * [FakeSessions] 只记"被要求做了什么"，**不发真帧**：真帧要 `VirtualDisplay` +
 * `ImageReader`（Android 运行时），JVM 上复现不了；设备层那条路靠代码审读与真机验证，
 * 这一点在 `AndroidMediaProjectionSessions` KDoc 里已如实写明。
 *
 * **租约/归属/开启中三条判据在这里逐条复刻真设备层**（收口凭租约、归属按三元组比、
 * `revokeOwner` 覆盖"正在开的那条"）—— 否则语义层的代际测试是在测一个比真设备层宽松的替身。
 */
internal class FakeSessions : MediaProjectionSessions {
    var failWith: MediaProjectionOpenException? = null
    val openCalls = mutableListOf<MediaProjectionSessionOwner>()
    var closeCurrentCalls = 0
    var revokeOwnerCalls = 0

    /**
     * `close` 的钩子（默认 null = 不挂）：测「语义层的收口是不是在**锁外**」用 ——
     * 钩子里挂住，再看另一个线程能不能拿到语义层那把锁（拿不到就是死等到超时）。
     * 真设备层的 `close` 是一次 binder 往返（VirtualDisplay release / projection stop），
     * 所以「持锁做它」在真机上就是**持锁跨设备调用**，本钩子把那个耗时窗口显式化。
     */
    var onClose: (() -> Unit)? = null

    /**
     * 撤销之后 `open` 是否**如实放弃**（真设备层的行为：回 `CancellationException`，资源已还清）。
     *
     * 置 false = 让 `open` 照常返回会话，用于把语义层逼到「**提交前判据**」那一支 ——
     * 真机上那是一支赛跑窗口（`open` 已返回、语义层还没登记，撤销恰好落在这中间），
     * 替身里靠这个开关复现它，否则那条分支在测试里不可达。
     */
    var abortOpenOnRevoke = true

    /** 在册会话（真设备层是 `current`）；`open` 会替换它。 */
    var current: FakeSession? = null
        private set

    /** 正在开的那条（`open` 挂在这里，直到 [finishOpen] 放行）—— 用于"开启中被撤销"。 */
    var pendingOpen: FakeSession? = null
        private set

    /** 让下一次 `open` 挂住（等 [finishOpen]），模拟"前台确认/建显示"那段挂起。 */
    var gateOpen: Boolean = false
    private var gate: CompletableDeferred<Unit>? = null

    /**
     * 框架侧收口落在 `open` 的挂起点里（真设备层的 `openAbandoned`）：`open` 建完也不提交。
     *
     * 为什么替身必须有这一条：`MediaProjectionSourceTest` 那条"开启中 closeCurrent"测的是
     * **语义层**在设备层如实放弃时的行为；替身若不放弃，测的就是一个比真设备层宽松的世界。
     */
    private var abandonPending = false

    private val leaseIds = AtomicLong(1)

    /** 系统收回过、之后还没重开（见 [systemStop]）—— `current` 已清，但这个事实还在。 */
    private var systemStopped = false

    override val state: MediaProjectionSessionState
        get() = current?.state
            ?: if (systemStopped) MediaProjectionSessionState.STOPPED else MediaProjectionSessionState.IDLE

    override val owner: MediaProjectionSessionOwner? get() = current?.owner

    override suspend fun open(
        consent: ScreenConsentToken?,
        owner: MediaProjectionSessionOwner,
    ): LeasedMediaProjectionSession {
        failWith?.let { throw it }
        openCalls += owner
        val session = FakeSession(SessionLease(leaseIds.getAndIncrement(), owner), owner)
        pendingOpen = session
        abandonPending = false
        if (gateOpen) {
            val g = CompletableDeferred<Unit>()
            gate = g
            g.await()
        }
        pendingOpen = null
        // 开启期间被撤销：真设备层回 CancellationException（资源已还清）。
        if (abortOpenOnRevoke && revoked.contains(owner)) {
            session.markClosed()
            throw kotlinx.coroutines.CancellationException("投屏会话开启过程中被收口（连接撤销）")
        }
        // 开启期间被框架收口（closeCurrent / 熄屏裁剪）：同样不提交。
        if (abandonPending) {
            session.markClosed()
            throw kotlinx.coroutines.CancellationException("投屏会话开启过程中被收口（连接撤销/熄屏裁剪）")
        }
        current = session
        systemStopped = false
        return session
    }

    /** 放行被 [gateOpen] 挂住的那次 `open`。 */
    fun finishOpen() {
        gate?.complete(Unit)
        gate = null
    }

    private val revoked = HashSet<MediaProjectionSessionOwner>()

    override fun close(lease: SessionResourceLease): Boolean {
        onClose?.invoke()
        val c = current ?: return false
        if (c.lease.leaseId != lease.leaseId || c.owner != lease.owner) return false
        c.markClosed()
        current = null
        return true
    }

    override fun closeCurrent(): Boolean {
        closeCurrentCalls++
        // 正在开的那条也要放弃（真设备层置 `openAbandoned`）：否则它建完就提交，
        // 收口等于没发生 —— 语义层那条"开启中 closeCurrent"测的正是这件事。
        if (pendingOpen != null) abandonPending = true
        val c = current ?: return pendingOpen != null
        c.markClosed()
        current = null
        return true
    }

    override fun revokeOwner(owner: MediaProjectionSessionOwner): Boolean {
        revokeOwnerCalls++
        revoked += owner
        var revokedSomething = false
        pendingOpen?.let { if (it.owner == owner) revokedSomething = true }
        val c = current
        if (c != null && c.owner == owner) {
            c.markClosed()
            current = null
            revokedSomething = true
        }
        return revokedSomething
    }

    /** 模拟系统收回（`MediaProjection.Callback.onStop`）：真设备层会**连 current 一起清掉**。 */
    fun systemStop() {
        val c = current ?: return
        c.markStopped()
        current = null
    }
}

internal class SessionLease(
    override val leaseId: Long,
    override val owner: MediaProjectionSessionOwner,
) : SessionResourceLease

internal class FakeSession(
    override val lease: SessionResourceLease,
    override val owner: MediaProjectionSessionOwner,
) : LeasedMediaProjectionSession {
    @Volatile
    private var live: MediaProjectionSessionState = MediaProjectionSessionState.ACTIVE
    var closeCalls = 0
        private set

    override val width: Int = 2
    override val height: Int = 2

    override val state: MediaProjectionSessionState get() = live

    override suspend fun nextFrame(): RawFrame {
        if (live != MediaProjectionSessionState.ACTIVE) {
            throw AutojsException(ErrorCode.ERR_CAPTURE_DENIED, "投屏会话已停止")
        }
        return RawFrame(byteArrayOf(1, 2, 3, 4), 1, 1)
    }

    override suspend fun close() = markClosed()

    /** 收口的共同落点（非挂起，供 `FakeSessions.closeCurrent`/`revokeOwner` 用）。 */
    fun markClosed() {
        closeCalls++
        live = MediaProjectionSessionState.STOPPED
    }

    /** 系统单方面收回：**不是**我们的 close（[closeCalls] 不涨）—— 两者在真机上确实不同源。 */
    fun markStopped() {
        live = MediaProjectionSessionState.STOPPED
    }
}

/** 假分析器：只记 ingest 调用并回自增句柄（帧表归一是它的契约，见 SPI KDoc）。 */
internal class FakeAnalyzer : ImageAnalyzer {
    var ingestCalls = 0
    private var nextRefId = 1L

    override suspend fun ingest(width: Int, height: Int, rgba: ByteArray): ImageFrame {
        ingestCalls++
        return ImageFrame(HandleRef(nextRefId++, 1L), width, height)
    }

    override suspend fun decode(path: String): ImageFrame = error("本测试不用")
    override suspend fun release(handle: HandleRef) = Unit
    override suspend fun matchTemplate(h: HandleRef, n: HandleRef, t: Double, r: List<Int>?): ImageMatch? = null
    override suspend fun findImage(h: HandleRef, n: HandleRef, t: Double, r: List<Int>?): ImageMatch? = null
    override suspend fun findColor(h: HandleRef, c: List<Int>, t: Int, r: List<Int>?): ColorHit? = null
    override suspend fun toGrayscale(frame: HandleRef): ImageFrame = error("本测试不用")
    override suspend fun crop(frame: HandleRef, region: List<Int>): ImageFrame = error("本测试不用")
    override suspend fun resize(frame: HandleRef, width: Int, height: Int): ImageFrame = error("本测试不用")
    override suspend fun rotate(frame: HandleRef, degrees: Double): ImageFrame = error("本测试不用")
    override suspend fun findFeature(scene: HandleRef, template: HandleRef): FeatureHit? = null
}
