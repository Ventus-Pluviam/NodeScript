package com.autoscript.platform.capabilities.device

import android.app.KeyguardManager
import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import com.autoscript.domain.automation.LeasedMediaProjectionSession
import com.autoscript.domain.automation.MediaProjectionOpenException
import com.autoscript.domain.automation.MediaProjectionOpenFailure
import com.autoscript.domain.automation.MediaProjectionSessionOwner
import com.autoscript.domain.automation.MediaProjectionSessionState
import com.autoscript.domain.automation.LeasedScreenRecording
import com.autoscript.domain.automation.MediaProjectionSessions
import com.autoscript.domain.automation.RawFrame
import com.autoscript.domain.automation.RecordingOutcome
import com.autoscript.domain.automation.ScreenConsentToken
import com.autoscript.domain.automation.ScreenPolicy
import com.autoscript.domain.automation.ScreenRecordingSessions
import com.autoscript.domain.automation.ScreenRecordingSpec
import com.autoscript.domain.automation.ScreenSnapshot
import com.autoscript.domain.automation.SessionResourceLease
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 系统投屏同意的凭据（§9.2）：设备层对 `(resultCode, Intent)` 那一对的封装。
 *
 * **一次性**（[consume] 取用即置位）：API 34 起系统不再复用同意凭据 —— 每次
 * `getMediaProjection` 都要一次新的用户确认，所以同一份凭据**不许**换第二条会话
 * （重复使用如实 `ERR_CAPTURE_DENIED`，不假装还能开）。
 */
class AndroidScreenConsentToken(
    val resultCode: Int,
    val data: android.content.Intent?,
    /** 结果是否是一次成功同意（`Activity.RESULT_OK`）；false = 用户取消/系统拒。 */
    val granted: Boolean,
) : ScreenConsentToken {
    @Volatile
    private var used = false

    /** 取用（恰好一次）；第二次回 false。 */
    fun consume(): Boolean = synchronized(this) {
        if (used) return false
        used = true
        true
    }
}

/**
 * MediaProjection 会话的设备实现（§9.2）：**唯一碰 `android.media.projection.*` 的地方**。
 *
 * ## 时序（API 34 的硬要求，顺序反了就是 `SecurityException`）
 * 1. 起 mediaProjection 前台类型并**等到系统确认**（[ProjectionForeground.start]）；
 * 2. `getMediaProjection(resultCode, data)`；
 * 3. `registerCallback`（**必须在 createVirtualDisplay 之前**，否则系统可能在 display
 *    建好与回调注册之间停止投影，那次停止就没人接得住）；
 * 4. `createVirtualDisplay(...)` → `ImageReader` 的 Surface。
 *
 * ## 生命周期所有权（这一版的核心，逐条对应一个真实故障）
 * - **开/关状态机**（[OpeningGate]，与 [lock] 同源）：并发第二个 `open` 如实拒绝
 *   （不是两个都过关去抢同一条 `mediaProjection`）；`open` 进行中被 [closeCurrent]/
 *   连接撤销时，本次在**每个检查点**发现并放弃 —— 不会"撤销完了又被提交上去"。
 * - **取消原样传播**：`open` 挂起点被取消（脚本崩了/连接断了）时先还清已拿到的系统资源，
 *   再抛 `CancellationException` —— 不吞成 `UNAVAILABLE`（那会让调用方以为"开失败了、
 *   可以重试"，而实际上这次取消意味着"不要了"）。
 * - **收口凭租约、恰好一次**：`VirtualDisplay`/`ImageReader`/`MediaProjection`/前台服务
 *   的释放走 [teardown]，由 `closed` 的 CAS 保证恰好一次；前台那一步带**代际**
 *   （[ForegroundLease]），所以旧会话的迟到收口**停不掉新会话的前台服务**。
 * - **系统 `onStop` 既回收也清当前**：回调先把 `current` 摘掉再还资源 ——
 *   只置一个 `stopped` 标志会让 `current != null` 永久挡住重开。
 *
 * ## 不自动重新征询授权
 * 系统收回投屏后会话转 `STOPPED`（**不自动重新弹系统对话框**，§9.2：由能力中心引导
 * 用户重授权）—— 自动弹一次是"替用户做决定"，且脚本无从知晓。
 *
 * ## 诚实的 FLAG_SECURE 边界
 * MediaProjection 的合成输出对安全窗的处理由系统决定（多数 ROM 给黑块）。本实现
 * **读不到**窗口 flag，因此
 * - **不声称**能识别安全窗，也**不**用"整帧全黑 ⇒ 安全窗"这种猜测冒充精确识别
 *   （黑屏可能只是用户真的在看黑界面）；
 * - 锁屏态走**系统查询**（`KeyguardManager`）做策略预检，与 a11y 路径同一条
 *   [ScreenPolicy]（分类错误而非黑图）；
 * - 安全窗若真的黑掉，脚本拿到的是黑帧 —— 这一点如实写在这里，不粉饰。
 *
 * ## 不接 `onCapturedContentResize`
 * 系统改了被投内容尺寸（旋转/分屏）时本实现不跟着 resize（ImageReader 尺寸在建会话时
 * 固定），下一次 `nextFrame` 仍按原尺寸给帧 —— 尺寸不匹配时系统会缩放填充，画面可能变形。
 */
class AndroidMediaProjectionSessions(
    context: Context,
    private val foreground: ProjectionForeground,
    private val frameWaitMillis: Long = DEFAULT_FRAME_WAIT_MILLIS,
) : MediaProjectionSessions, ScreenRecordingSessions {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 开/关状态机与在册会话的唯一锁（状态转换都在锁内，系统调用都在锁外）。 */
    private val lock = Any()

    /**
     * 开启状态机（**两条腿共用一个实例**，见 [OpeningGate]）：同一时刻只允许一次开启，
     * 且"放弃开启中那条"按腿区分。
     */
    private val opening = OpeningGate(lock)

    /**
     * 在册会话（**取帧腿与录屏腿共用这一个槽**）：`Live`/`CaptureLive`/`RecordingLive`
     * 三个类型住 `ProjectionSessionLive.kt`，基类 KDoc 写了为什么必须是同一个槽。
     *
     * `@Volatile`：状态/归属 getter（`state`/`owner`/`recordingState`/`recordingOwner`）
     * 在锁**外**读它（能力中心轮询路径，不该为读一个引用进临界区），写侧全在锁内 ——
     * 没有 @Volatile 时 ARM 上读到的可以是过期值（停了的会话继续显示 ACTIVE）。
     */
    @Volatile
    private var current: Live? = null
    private var nextLeaseId = 1L

    /** 租约实现（`:domain` 的收口凭据；号由 [nextLeaseId] 单调发放，绝不复用）。 */
    private class SessionLease(
        override val leaseId: Long,
        override val owner: MediaProjectionSessionOwner,
    ) : SessionResourceLease

    override val state: MediaProjectionSessionState
        get() = when (val live = current) {
            null -> MediaProjectionSessionState.IDLE
            else -> if (live.stopped) MediaProjectionSessionState.STOPPED else MediaProjectionSessionState.ACTIVE
        }

    override val owner: MediaProjectionSessionOwner? get() = current?.owner

    /**
     * 开一条会话（取帧腿）：**用掉**一次系统同意，起 mediaProjection 前台类型，
     * 建 VirtualDisplay + ImageReader。
     *
     * 六个检查点各带 `releasePartial`（前台确认后 / 拿投影后 / 建显示提交后），
     * 失败一律还清已拿到的系统资源、**绝不留半开会话**；取消（`CancellationException`）
     * 原样传播（那不是"开失败"）。
     */
    @Suppress("TooGenericExceptionCaught") // 建会话的失败面很宽（系统/厂商实现），但都必须还清资源
    override suspend fun open(
        consent: ScreenConsentToken?,
        owner: MediaProjectionSessionOwner,
    ): LeasedMediaProjectionSession {
        val token = consumeConsent(consent)
        val data = token.data
            ?: throw MediaProjectionOpenException(MediaProjectionOpenFailure.DENIED, "同意结果里没有投屏凭据")
        beginOpening(owner, recording = false)

        var fgLease: ForegroundLease? = null
        var projection: MediaProjection? = null
        var reader: ImageReader? = null
        var display: VirtualDisplay? = null
        var callback: StopCallback? = null
        var frames: Channel<Unit>? = null
        var committed: CaptureLive? = null
        try {
            // 1) 前台类型先起、等到系统确认 —— API 34+ 的硬前提（顺序不可反）。
            fgLease = foreground.start()
                ?: throw MediaProjectionOpenException(
                    MediaProjectionOpenFailure.UNAVAILABLE,
                    "mediaProjection 前台服务起不来（缺 FOREGROUND_SERVICE_MEDIA_PROJECTION 或被系统拒）",
                )
            // 检查点①：前台那一步是阻塞的，期间可能已经被取消/被收口。
            checkAlive()
            val manager = appContext.getSystemService(MediaProjectionManager::class.java)
                ?: throw MediaProjectionOpenException(MediaProjectionOpenFailure.UNAVAILABLE, "MediaProjectionManager 不可得")
            projection = try {
                manager.getMediaProjection(token.resultCode, data)
            } catch (e: SecurityException) {
                throw MediaProjectionOpenException(MediaProjectionOpenFailure.DENIED, "系统拒绝投屏", e)
            } ?: throw MediaProjectionOpenException(
                MediaProjectionOpenFailure.DENIED,
                "系统没有给出投屏对象（凭据无效或已被用过）",
            )
            // 检查点②：拿投影之后、建显示之前。
            checkAlive()
            val size = screenSize()
            reader = ImageReader.newInstance(size.first, size.second, PixelFormat.RGBA_8888, MAX_IMAGES)
            frames = Channel(Channel.CONFLATED)
            val stopCallback = StopCallback { onProjectionStopped(projection) }
            callback = stopCallback
            // 2) 回调先注册（见类 KDoc 时序第 3 条）。
            projection.registerCallback(stopCallback, mainHandler)
            display = projection.createVirtualDisplay(
                DISPLAY_NAME,
                size.first,
                size.second,
                densityDpi(),
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                mainHandler,
            ) ?: error("createVirtualDisplay 回 null")

            val accepted = commit(
                CaptureLive(
                    lease = nextLease(owner),
                    owner = owner,
                    projection = projection,
                    reader = reader,
                    display = display,
                    callback = stopCallback,
                    foregroundLease = fgLease,
                    frames = frames,
                ),
            )
            committed = accepted
            // 检查点③：建好之后才发现被收口 —— 还清资源再放弃（绝不提交上去）。
            if (accepted == null) {
                throw CancellationException("投屏会话开启过程中被收口（连接撤销/熄屏裁剪）")
            }
            return DeviceSession(accepted)
        } catch (e: CancellationException) {
            releasePartial(fgLease, projection, reader, display, callback, frames, committed)
            throw e
        } catch (e: MediaProjectionOpenException) {
            releasePartial(fgLease, projection, reader, display, callback, frames, committed)
            throw e
        } catch (t: Throwable) {
            releasePartial(fgLease, projection, reader, display, callback, frames, committed)
            throw MediaProjectionOpenException(
                MediaProjectionOpenFailure.UNAVAILABLE,
                "建立虚拟显示失败（${t.javaClass.simpleName}: ${t.message}）",
                t,
            )
        } finally {
            endOpening()
        }
    }

    /**
     * 检查点：本次 `open` 是否已被取消/被收口。
     *
     * 两个条件都要看：协程取消（脚本崩了/连接断了）与 `closeCurrent` 的放弃标记
     * （后者不一定伴随取消 —— 熄屏裁剪是在别的协程里调的）。
     */
    private suspend fun checkAlive() {
        currentCoroutineContext().ensureActive()
        if (opening.abandoned()) {
            throw CancellationException("投屏会话开启过程中被收口（连接撤销/熄屏裁剪）")
        }
    }

    /**
     * 还清 `open` 中途拿到的系统资源（**不含**在册会话 —— 那条走 [teardown]）。
     *
     * 逐项 `runCatching`：一份资源还不掉，不能让其余资源也留在半开态。
     * 前台那一步用**本代的租约**（[ProjectionForeground.stop] 带代际校验），
     * 所以它停不掉别人后来起的那一代。
     */
    private fun releasePartial(
        fgLease: ForegroundLease?,
        projection: MediaProjection?,
        reader: ImageReader?,
        display: VirtualDisplay?,
        callback: MediaProjection.Callback?,
        frames: Channel<Unit>?,
        committed: Live?,
    ) {
        // 已提交到 current 的那条（检查点③的放弃路径）走完整收口：它已经是"在册会话"了，
        // 资源形状与在册会话一模一样。`teardown` 的 CAS 保证它不会被还第二次。
        if (committed != null) {
            detachAndTeardown(committed)
            return
        }
        frames?.close()
        display?.let { runCatching { it.release() } }
        reader?.let { runCatching { it.close() } }
        if (projection != null && callback != null) {
            runCatching { projection.unregisterCallback(callback) }
        }
        projection?.let { runCatching { it.stop() } }
        if (fgLease != null) {
            // 停不掉就**明确放弃**这一代：服务若迟到才起来，看到自己已被放弃会自己退场。
            if (!foreground.stop(fgLease)) foreground.abandon(fgLease)
        }
    }

    override fun close(lease: SessionResourceLease): Boolean {
        val live = synchronized(lock) {
            val c = current ?: return false
            // 租约对不上 = 它已经不是当前会话（已被系统收回/被新会话顶掉）：**不动手**。
            if (c.lease.leaseId != lease.leaseId || c.owner != lease.owner) return false
            current = null
            c
        }
        teardown(live)
        return true
    }

    override fun closeCurrent(): Boolean {
        // 正在开的**取帧**那条也要放弃（否则它建完就提交，收口等于没发生）。
        // 只放弃取帧腿：录屏那条有它自己的入口（[closeCurrentRecording]），
        // 两边各自管自己的开启中状态（见 [openingRecording] 的 KDoc）。
        abandonOpening(recording = false)
        val live = synchronized(lock) {
            val c = current ?: return false
            current = null
            c
        }
        teardown(live)
        return true
    }

    /**
     * 按归属收口（连接/执行撤销）：**正在开的那条也算**。
     *
     * 为什么必须覆盖"正在开的那条"：`open` 里有一段挂起（等前台确认/拿投影/建显示），
     * 撤销完全可能落在这一段里；只管已提交的会话，那条就会在建好之后照样提交上去
     * （用户看到"脚本已经没了但通知栏还在投屏"）。
     *
     * 别的归属一概不动 —— 同一台设备上别的连接（别的脚本）不该被牵连。
     */
    override fun revokeOwner(owner: MediaProjectionSessionOwner): Boolean {
        // 只放弃**取帧**腿正在开的那条：录屏那条由 [revokeRecordingOwner] 管。
        var revoked = abandonOpening(recording = false, owner = owner)
        val live = synchronized(lock) {
            val c = current
            if (c != null && c.owner == owner) {
                current = null
                revoked = true
                c
            } else {
                null
            }
        }
        if (live != null) teardown(live)
        return revoked
    }

    // ── 两条腿共用的开启状态机 ────────────────────────────────────────────
    // 取帧与录屏的"开一条会话"逐条同源（前台确认 → getMediaProjection → 注册回调 →
    // createVirtualDisplay → 提交），只有"输出汇"不同。共用下面这几个口，两条腿就
    // 不可能在"同意凭据用几次""能不能并发开两条"这类判据上漂。

    /**
     * 开启占位：**两条腿共用同一个槽**（先判后写，同一把锁内完成两条判据）。
     *
     * 为什么必须共用：否则"取帧正在开"与"录屏正在开"互不可见，两次开启会赛跑到设备层
     * 抢同一条 `mediaProjection`（用户看到两份投屏、前台代际互相踩）。
     *
     * 1. `current` 槽非空 = 已有一条会话在跑（**含另一条腿的**——槽是两腿共用的，
     *    语义层两张表各自只看自己那条腿，跨腿「开会话」只有这一道闸）：放过去
     *    提交时就会把在册那条整个顶替，被顶替者从此没有任何收口路径找得到它
     *    （`close(lease)`/`revokeOwner`/`onStop` 全走这个槽），投影照跑、前台照挂，
     *    直到进程死 —— 如实拒绝，不顶替也不替对方收口。
     * 2. 已有一次开启在进行 → 如实拒绝（[OpeningGate.tryBegin]）。
     *
     * 拒绝抛在 `try`/`finally` **之外**（调用点结构）：占位没成功就绝不 [endOpening]，
     * 否则会把别人那一次开启的记账清掉。
     */
    private fun beginOpening(owner: MediaProjectionSessionOwner, recording: Boolean) {
        synchronized(lock) {
            if (current != null) {
                throw MediaProjectionOpenException(
                    MediaProjectionOpenFailure.UNAVAILABLE,
                    "已有投屏会话在跑（一台设备同时只有一条）：本次不抢占，也不替你收掉别人的会话",
                )
            }
            if (!opening.tryBegin(owner, recording)) {
                throw MediaProjectionOpenException(
                    MediaProjectionOpenFailure.UNAVAILABLE,
                    "已有一次投屏会话正在开启（同一时刻只允许一次）：本次不抢占",
                )
            }
        }
    }

    private fun endOpening() = opening.end()

    /**
     * 放弃**正在开的这一条**（仅当它属于 [recording] 那条腿）。
     *
     * 腿判据是刻意的：两条腿共用"已放弃"标记，不判腿就会让"熄屏裁剪录屏"顺手把一条
     * 正在开的取帧会话也放弃掉（见 [OpeningGate] 的 KDoc）。
     *
     * @param owner 非 null 时还要归属一致（撤销路径只收自己那条）。
     * @return true = 这一次确实标记了放弃。
     */
    private fun abandonOpening(recording: Boolean, owner: MediaProjectionSessionOwner? = null): Boolean =
        opening.abandon(recording, owner)

    /**
     * 提交点在册。返回 null = 开启期间被收口（连接撤销/熄屏裁剪）—— 调用方**必须**
     * 还清已拿到的资源再抛 `CancellationException`，绝不把它登记上去。
     *
     * 租约号在**这里**发放（单调递增、绝不复用）：并发/迟到的收口只有拿到同一号才算
     * "还是我那条"（见 [SessionResourceLease]）。
     */
    private fun <T : Live> commit(live: T): T? = synchronized(lock) {
        if (opening.abandoned()) null else live.also { current = it }
    }

    private fun nextLease(owner: MediaProjectionSessionOwner): SessionResourceLease =
        synchronized(lock) { SessionLease(nextLeaseId++, owner) }

    /**
     * 从在册槽摘掉**这一条**（只当它还是当前那条）再还清资源。
     *
     * "只当它还是当前那条"是关键：旧会话的迟到收口不能把**新会话**从槽里摘掉。
     * [teardown] 的 CAS 再兜一层"恰好一次"。
     */
    private fun detachAndTeardown(live: Live) {
        synchronized(lock) { if (current === live) current = null }
        teardown(live)
    }

    // ── 录屏腿（§9.2）：同一个会话账，换输出汇（`MediaRecorder` 的 Surface）──────

    override val recordingState: MediaProjectionSessionState
        get() = when (val live = current) {
            null -> MediaProjectionSessionState.IDLE
            is RecordingLive -> if (live.stopped) MediaProjectionSessionState.STOPPED else MediaProjectionSessionState.ACTIVE
            // 取帧会话在场时录屏腿如实回 IDLE（**不是** ACTIVE）：本属性回答的是
            // "有没有录屏会话"，拿取帧会话冒充会让能力中心显示"正在录屏"而其实没有文件在写。
            else -> MediaProjectionSessionState.IDLE
        }

    override val recordingOwner: MediaProjectionSessionOwner?
        get() = (current as? RecordingLive)?.owner

    /**
     * 开一条录屏会话。时序与取帧腿逐条同源（见类 KDoc），只有第 4/5 步不同：
     * `MediaRecorder` 配置 + `prepare()`（唯一做真实 IO 的一步）→ 把它的 Surface 交给
     * `createVirtualDisplay` → `start()`。
     */
    @Suppress("TooGenericExceptionCaught") // 建会话的失败面很宽（系统/厂商编码器），但都必须还清资源
    override suspend fun startRecording(
        consent: ScreenConsentToken?,
        owner: MediaProjectionSessionOwner,
        spec: ScreenRecordingSpec,
    ): LeasedScreenRecording {
        val token = consumeConsent(consent)
        val data = token.data
            ?: throw MediaProjectionOpenException(MediaProjectionOpenFailure.DENIED, "同意结果里没有投屏凭据")
        beginOpening(owner, recording = true)

        var fgLease: ForegroundLease? = null
        var projection: MediaProjection? = null
        var recorder: MediaRecorder? = null
        var display: VirtualDisplay? = null
        var callback: MediaProjection.Callback? = null
        var committed: RecordingLive? = null
        try {
            // 1) 前台类型先起、等到系统确认 —— API 34+ 的硬前提（顺序不可反）。
            fgLease = foreground.start()
                ?: throw MediaProjectionOpenException(
                    MediaProjectionOpenFailure.UNAVAILABLE,
                    "mediaProjection 前台服务起不来（缺 FOREGROUND_SERVICE_MEDIA_PROJECTION 或被系统拒）",
                )
            checkAlive()
            val manager = appContext.getSystemService(MediaProjectionManager::class.java)
                ?: throw MediaProjectionOpenException(MediaProjectionOpenFailure.UNAVAILABLE, "MediaProjectionManager 不可得")
            projection = try {
                manager.getMediaProjection(token.resultCode, data)
            } catch (e: SecurityException) {
                throw MediaProjectionOpenException(MediaProjectionOpenFailure.DENIED, "系统拒绝投屏", e)
            } ?: throw MediaProjectionOpenException(
                MediaProjectionOpenFailure.DENIED,
                "系统没有给出投屏对象（凭据无效或已被用过）",
            )
            checkAlive()
            // 尺寸与编码器**一起**定下来：`VirtualDisplay` 的输出面尺寸必须与
            // `MediaRecorder.setVideoSize` 一致（不一致时编码器要么拒绝、要么给出拉伸的
            // 画面），所以这里拿的是"真的准备成功的那一对"，不是"提示的那一对"。
            val prepared = prepareRecorderWithFallback(spec, recordingSize(spec))
            recorder = prepared.recorder
            val size = prepared.size
            // 2) 回调先注册（必须在 createVirtualDisplay 之前，见类 KDoc 时序第 3 条）。
            val stopCallback = StopCallback { onProjectionStopped(projection) }
            callback = stopCallback
            projection.registerCallback(stopCallback, mainHandler)
            display = projection.createVirtualDisplay(
                RECORDING_DISPLAY_NAME,
                size.first,
                size.second,
                densityDpi(),
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                prepared.recorder.surface,
                null,
                mainHandler,
            ) ?: error("createVirtualDisplay 回 null")
            // 3) 真正的录制从 start() 开始（之前 display 上的内容不会被写进文件）。
            prepared.recorder.start()

            val accepted = commit(
                RecordingLive(
                    lease = nextLease(owner),
                    owner = owner,
                    projection = projection,
                    foregroundLease = fgLease,
                    recorder = prepared.recorder,
                    display = display,
                    callback = stopCallback,
                    // `File.toPath()`（API 26）而不是 `Path.of`（API 34）：minSdk 是 26，
                    // 用后者要靠 desugaring，而这里只是把一个绝对路径字符串变成 Path。
                    path = java.io.File(spec.path).toPath(),
                ),
            )
            committed = accepted
            if (accepted == null) {
                throw CancellationException("录屏会话开启过程中被收口（连接撤销/熄屏裁剪）")
            }
            return RecordingSession(accepted)
        } catch (e: CancellationException) {
            releasePartialRecording(fgLease, projection, recorder, display, callback, committed)
            throw e
        } catch (e: MediaProjectionOpenException) {
            releasePartialRecording(fgLease, projection, recorder, display, callback, committed)
            throw e
        } catch (e: AutojsException) {
            // `prepareRecorder` 的分类错误（`ERR_IO` 落点写不进去 / `ERR_SERVICE_DISABLED`
            // 编码器不可用）**原样传播**：折进下面的 UNAVAILABLE 会把"磁盘满了"和
            // "本机没编码器"报成同一件事，而脚本要按它决定是清空间还是换设备。
            releasePartialRecording(fgLease, projection, recorder, display, callback, committed)
            throw e
        } catch (t: Throwable) {
            releasePartialRecording(fgLease, projection, recorder, display, callback, committed)
            throw MediaProjectionOpenException(
                MediaProjectionOpenFailure.UNAVAILABLE,
                "建立录屏会话失败（${t.javaClass.simpleName}: ${t.message}）",
                t,
            )
        } finally {
            endOpening()
        }
    }

    /**
     * 配置并 `prepare()` 一个 `MediaRecorder`（**唯一会做真实 IO 的一步**）。
     *
     * 编码参数取通用安全档（H.264 + 3Mbps + 30fps）：**不按"最佳"调参** ——
     * 录屏产物要能被任何播放器打开，而"最佳"在不同设备/编码器上表现不同
     * （有的不认某些 profile/level，`prepare()` 直接失败）。
     *
     * 失败按原因分类（两者对脚本含义不同，不合并）：`IOException` 基本是落点/空间问题
     * → `ERR_IO`；其余（`IllegalStateException` 等编码器拒绝）→ `ERR_SERVICE_DISABLED`。
     */
    @Suppress("TooGenericExceptionCaught") // 编码器拒绝的异常类型厂商各异（IllegalState/IllegalArgument/…），一律折成同一档
    private fun prepareRecorder(spec: ScreenRecordingSpec, width: Int, height: Int): MediaRecorder {
        val recorder = newRecorder(appContext)
        try {
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            recorder.setVideoSize(width, height)
            recorder.setVideoFrameRate(RECORDING_FRAME_RATE)
            recorder.setVideoEncodingBitRate(RECORDING_BIT_RATE)
            recorder.setOutputFile(spec.path)
            recorder.prepare()
            return recorder
        } catch (e: java.io.IOException) {
            runCatching { recorder.release() }
            throw AutojsException(
                ErrorCode.ERR_IO,
                "录屏落点不可写或空间不足：${java.io.File(spec.path).parent ?: spec.path}（${e.message}）",
                e,
            )
        } catch (t: RuntimeException) {
            runCatching { recorder.release() }
            throw AutojsException(
                ErrorCode.ERR_SERVICE_DISABLED,
                "本机录屏编码器不可用（prepare 失败：${t.javaClass.simpleName}: ${t.message}）",
                t,
            )
        }
    }

    /** 录屏尺寸：规格给了提示就用它，否则按屏幕真值（与取帧腿同一条口径）。 */
    private fun recordingSize(spec: ScreenRecordingSpec): Pair<Int, Int> =
        if (spec.width > 0 && spec.height > 0) spec.width to spec.height else screenSize()

    /**
     * 按尺寸提示准备编码器，**编不出来就退回屏幕真值再试一次**。
     *
     * 为什么要有这一层：尺寸是**提示不是承诺**（见 `ScreenRecordingSpec.width` 的 KDoc）。
     * 提示尺寸被编码器拒（有些设备只认对齐到 16 的尺寸、或超过 `CamcorderProfile` 上限）
     * 时，直接失败等于让脚本为"我随口给的一个提示"付出"这次录屏没了"的代价 ——
     * 而按屏幕真值本来就能录。**只退一次**：退完还不行就是本机编码器的问题，
     * 如实 `ERR_SERVICE_DISABLED`，不无限试。
     */
    private fun prepareRecorderWithFallback(
        spec: ScreenRecordingSpec,
        hinted: Pair<Int, Int>,
    ): PreparedRecorder {
        val screen = screenSize()
        if (hinted == screen) return PreparedRecorder(prepareRecorder(spec, hinted.first, hinted.second), hinted)
        return try {
            PreparedRecorder(prepareRecorder(spec, hinted.first, hinted.second), hinted)
        } catch (e: AutojsException) {
            // 只有"编码器拒绝"这一档该退（`ERR_IO` 是落点问题，换尺寸也写不进去）。
            if (e.error != ErrorCode.ERR_SERVICE_DISABLED) throw e
            PreparedRecorder(prepareRecorder(spec, screen.first, screen.second), screen)
        }
    }

    /**
     * 还清 `startRecording` 中途拿到的资源（**不含**已提交的那条 —— 那条走 [teardown]）。
     *
     * **目标文件不删**：半截文件如实留在原地（比"悄悄抹掉"可诊断），它是不是完整由
     * [RecordingOutcome.completed] 回答 —— 那是脚本判断这条产物能不能用的唯一依据。
     */
    private fun releasePartialRecording(
        fgLease: ForegroundLease?,
        projection: MediaProjection?,
        recorder: MediaRecorder?,
        display: VirtualDisplay?,
        callback: MediaProjection.Callback?,
        committed: RecordingLive?,
    ) {
        if (committed != null) {
            detachAndTeardown(committed)
            return
        }
        display?.let { runCatching { it.release() } }
        // **先 `stop()` 再 `reset()`**：已经 `start()` 过的那条若不 stop 就 reset，
        // mp4 的 moov box 永远写不出来 —— 留下的是一段**坏文件**，而"录完了但 mp4 没
        // finalize"正是本任务要防的故障。没 start 过时 `stop()` 会抛
        // `IllegalStateException`（"没东西要 finalize"），runCatching 吞掉即可。
        //
        // **文件不删**（同 [releasePartialRecording] 的 KDoc）：它已 finalize 成可播的
        // 文件，只是这一次没人拿到路径（`startRecording` 抛了）。如实留在
        // `.recordings/` 下 —— 脚本列目录看得见，比"悄悄抹掉"可诊断。
        recorder?.let {
            runCatching { it.stop() }
            runCatching { it.reset() }
            runCatching { it.release() }
        }
        if (projection != null && callback != null) {
            runCatching { projection.unregisterCallback(callback) }
        }
        projection?.let { runCatching { it.stop() } }
        if (fgLease != null) {
            if (!foreground.stop(fgLease)) foreground.abandon(fgLease)
        }
    }

    /**
     * 框架侧收口录屏腿（熄屏裁剪 / 进程收口）：停当前录屏会话并 **finalize 文件**。
     *
     * 只认 [RecordingLive]：取帧会话在场时回 false（本方法只管录屏腿 —— 取帧那条走
     * [closeCurrent]，两者语义不同：帧死了就是死了，而文件必须落盘收尾）。
     */
    override fun closeCurrentRecording(): Boolean {
        // 正在开的**录屏**那条也要放弃（否则它建完就提交，收口等于没发生）。
        abandonOpening(recording = true)
        val live = synchronized(lock) {
            val c = current
            if (c !is RecordingLive) return false
            current = null
            c
        }
        teardown(live)
        return true
    }

    /**
     * 按归属收口录屏腿（连接/执行撤销）：**正在开的那条也算**。
     *
     * 别的归属一概不动 —— 同一台设备上别的连接（别的脚本）不该被牵连。
     */
    override fun revokeRecordingOwner(owner: MediaProjectionSessionOwner): Boolean {
        // 只放弃**录屏**腿正在开的那条：取帧那条由 [revokeOwner] 管。
        var revoked = abandonOpening(recording = true, owner = owner)
        val live = synchronized(lock) {
            val c = current
            if (c is RecordingLive && c.owner == owner) {
                current = null
                revoked = true
                c
            } else {
                null
            }
        }
        if (live != null) teardown(live)
        return revoked
    }

    /**
     * 录屏会话句柄（设备面）：状态读 [RecordingLive.stopped]，收口读**缓存的结果**。
     *
     * [stop] 非挂起（见 `LeasedScreenRecording` 的 KDoc）：撤销回调是普通函数，而录屏的
     * 收口**必须**在撤销路径上真的跑完（否则留下一个没 finalize 的 mp4）。
     */
    private inner class RecordingSession(private val live: RecordingLive) : LeasedScreenRecording {

        override val lease: SessionResourceLease get() = live.lease
        override val owner: MediaProjectionSessionOwner get() = live.owner
        override val path: String get() = live.path.toAbsolutePath().toString()

        override val state: MediaProjectionSessionState
            get() = if (live.stopped) MediaProjectionSessionState.STOPPED else MediaProjectionSessionState.ACTIVE

        override fun stop(): RecordingOutcome {
            detachAndTeardown(live)
            // 收口恰好一次（`teardown` 的 CAS）：已收口过就读回那一份 —— 这正是录屏腿与
            // 取帧腿的关键差异（见 `RecordingLive` KDoc）。
            return live.outcome
                ?: throw AutojsException(ErrorCode.ERR_IO, "录屏会话收口后没有结果（内部状态不一致）")
        }
    }

    /**
     * 系统说停就停（`MediaProjection.Callback.onStop`）：**先摘 `current` 再还资源**。
     *
     * 只置一个 `stopped` 标志是错的 —— `current` 还在场会永久挡住重开
     * （"系统停过之后就再也开不了"）。所以这里做完整收口。
     *
     * **按投影对象身份认领**：`onStop` 可能在旧会话收口之后才到（系统回调排在主线程
     * 队列里），直接收"当前会话"会把**新会话**拆掉。只认 `projection === live.projection`
     * 那一条；[Live.closed] 的 CAS 再兜一层"恰好一次"。
     */
    private fun onProjectionStopped(projection: MediaProjection) {
        val live = synchronized(lock) {
            val c = current ?: return
            if (c.projection !== projection) return
            current = null
            c
        }
        // 录屏腿：系统把投屏收回去了，**文件当场 finalize**（`teardown` → `release`）——
        // 留着一条"已死但没收口"的录屏会话，它的 mp4 永远写不出 moov box（坏文件）。
        // 语义层随后 `stop` 仍答得出路径/大小/完整性（`RecordingLive.outcome` 已缓存）。
        teardown(live)
    }

    /**
     * 还清一条在册会话的系统资源（**恰好一次**）。
     *
     * 前台那一步带代际（[ForegroundLease]）：旧会话的迟到收口停不掉新会话的前台服务
     * —— 这是"独立 FGS + 代际"合起来才成立的性质，缺任一条都会让重开投屏被立刻停掉。
     */
    private fun teardown(live: Live) {
        if (!live.closed.compareAndSet(false, true)) return
        live.stopped = true
        live.release(foreground)
    }

    /** 会话句柄（设备面）：状态读 [Live.stopped]，不另存一份会漂的布尔。 */
    private inner class DeviceSession(private val live: CaptureLive) : LeasedMediaProjectionSession {

        override val lease: SessionResourceLease get() = live.lease
        override val width: Int get() = live.reader.width
        override val height: Int get() = live.reader.height
        override val owner: MediaProjectionSessionOwner get() = live.owner

        override val state: MediaProjectionSessionState
            get() = if (live.stopped) MediaProjectionSessionState.STOPPED else MediaProjectionSessionState.ACTIVE

        init {
            // 监听器在建句柄时装上（只装一次）：帧信号走 CONFLATED channel ——
            // 系统可能在一帧都没被消费时连续回调，channel 只保留"有新帧"这一位事实。
            live.reader.setOnImageAvailableListener({ live.frames.trySend(Unit) }, mainHandler)
        }

        override suspend fun nextFrame(): RawFrame {
            if (state != MediaProjectionSessionState.ACTIVE) {
                throw AutojsException(
                    ErrorCode.ERR_CAPTURE_DENIED,
                    "投屏会话已停止（用户/系统收回），需重新授权；本实现不自动重试授权",
                )
            }
            // 锁屏：走系统查询的策略预检（分类错误而非黑图，§8.8）。
            // 安全窗读不到 —— 见类 KDoc 的诚实边界，不在此编造识别。
            ScreenPolicy.requireCapturable(
                ScreenSnapshot(locked = keyguardLocked(), secureForeground = false, hasWindows = true),
            )
            val image = try {
                awaitImage()
            } catch (e: ClosedReceiveChannelException) {
                // 等帧期间会话被收口（closeCurrent/撤销/系统 onStop 会把 frames 关掉，
                // 挂起的 receive 以 ClosedReceiveChannelException 醒）：这不是"等帧超时"，
                // 是"会话没了" —— 如实按已停止报，别让脚本以为重试一次就能拿到帧。
                throw AutojsException(ErrorCode.ERR_CAPTURE_DENIED, "投屏会话在等待帧期间被收口（需重新授权）", e)
            }
                ?: throw AutojsException(ErrorCode.ERR_TIMEOUT, "等待投屏帧超时（${frameWaitMillis}ms）")
            return try {
                rgbaOf(image)
            } finally {
                // **Image 必须及时 close**：ImageReader 的对象池只有 MAX_IMAGES 个缓冲，
                // 不还回去，几帧之后 acquire 就永远拿不到东西（表现是"投屏跑一会儿就卡死"）。
                runCatching { image.close() }
            }
        }

        override suspend fun close() {
            // 幂等：把自己那一份系统资源还掉（`teardown` 的 CAS 保证恰好一次）。
            synchronized(lock) { if (current === live) current = null }
            teardown(live)
        }

        /**
         * 等下一帧（**有界**）。
         *
         * 三条都是踩过的坑：
         * - **先试一次 `acquireLatestImage`**：ImageReader 里可能已经有帧（上一帧没人取），
         *   而"有新帧"的回调不会再响一次 —— 只等信号会等满整个超时，表现是"投屏卡在
         *   第一帧之后"；
         * - **用 channel 而不是自己写的信号量**：`Semaphore(0)` 这类"许可为 0"的构造在
         *   kotlinx 里是非法的（每次取帧直接 `IllegalArgumentException`）；
         * - **协程取消/超时即退出**：`withTimeoutOrNull` 兜住，不留悬挂等待
         *   （取消时 channel 不必由这里关 —— 会话收口会关它；收口把挂起的 `receive`
         *   以 [ClosedReceiveChannelException] 打醒，由 [nextFrame] 折成诚实的
         *   `ERR_CAPTURE_DENIED`，不混进"超时"）。
         */
        private suspend fun awaitImage(): Image? {
            live.reader.acquireLatestImage()?.let { return it }
            return withTimeoutOrNull(frameWaitMillis) {
                var image: Image? = null
                while (image == null) {
                    live.frames.receive()          // 等到"可能有新帧"的信号
                    image = live.reader.acquireLatestImage()
                }
                image
            }
        }

        /**
         * `Image` → [RawFrame]（紧密打包 RGBA，`width*height*4`，R,G,B,A 序）——
         * `ImageAnalyzer.ingest` 的像素契约（§18-8(b) 帧表共用）。
         *
         * **逐行按 `rowStride` 步进、逐像素按 `pixelStride` 取**：`ImageReader` 的缓冲区
         * 行间通常有填充（`rowStride > width*4`），直接整块读会把填充当成像素（画面斜切，
         * 且**只在某些设备上出现** —— 最难查的那一类）。`pixelStride` 同理：不假设它是 4。
         *
         * 缓冲是**共享内存**（`Image` 关闭即失效），所以这里**必须复制**出自有字节数组，
         * 不能把 buffer 交出去（那是"帧的内容随下一次投屏消失"）。
         */
        private fun rgbaOf(image: Image): RawFrame {
            val plane = image.planes.firstOrNull()
                ?: throw AutojsException(ErrorCode.ERR_IO, "投屏帧没有像素平面")
            val width = image.width
            val height = image.height
            requireFrameShape(width, height, plane.pixelStride)
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val out = ByteArray(width * height * 4)
            val row = ByteArray(rowStride)
            var o = 0
            for (y in 0 until height) {
                buffer.position(y * rowStride)
                val n = minOf(rowStride, buffer.remaining())
                buffer.get(row, 0, n)
                for (x in 0 until width) {
                    val p = x * pixelStride
                    if (p + 3 >= n) break
                    out[o++] = row[p]         // R（RGBA_8888 的内存序）
                    out[o++] = row[p + 1]     // G
                    out[o++] = row[p + 2]     // B
                    out[o++] = row[p + 3]     // A
                }
            }
            return RawFrame(out, width, height)
        }

        private fun requireFrameShape(width: Int, height: Int, pixelStride: Int) {
            if (width <= 0 || height <= 0) {
                throw AutojsException(ErrorCode.ERR_IO, "投屏帧尺寸非法 ${width}x$height")
            }
            if (pixelStride < 4) {
                throw AutojsException(ErrorCode.ERR_IO, "投屏帧像素步长非法 $pixelStride（RGBA 至少 4）")
            }
        }
    }

    /**
     * `MediaProjection.Callback` 的具名实现（匿名合成名进不了 ArchUnit 的服务面豁免名单）。
     *
     * 构造参数叫 `notify` 而不是 `onStop`：后者会与 `override fun onStop()` **同名**，
     * 方法体里写 `onStop()` 会解析成递归调用自己（编译器报 "recursive problem"）。
     */
    private class StopCallback(private val notify: () -> Unit) : MediaProjection.Callback() {
        override fun onStop() = notify()
    }

    private fun keyguardLocked(): Boolean =
        appContext.getSystemService(KeyguardManager::class.java)?.isDeviceLocked ?: false

    private fun densityDpi(): Int = appContext.resources.displayMetrics.densityDpi

    @Suppress("DEPRECATION") // getRealMetrics：投屏要物理像素尺寸（API30+ 的 WindowMetrics 面另接）
    private fun screenSize(): Pair<Int, Int> {
        val wm = appContext.getSystemService(WindowManager::class.java)
        if (wm != null) {
            val metrics = android.util.DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(metrics)
            if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                return metrics.widthPixels to metrics.heightPixels
            }
        }
        val dm = appContext.resources.displayMetrics
        return dm.widthPixels to dm.heightPixels
    }

    companion object {
        private const val DISPLAY_NAME = "autoscript-projection"

        /** 录屏虚拟显示名（与取帧腿分开，设备侧 `dumpsys display` 里一眼能分）。 */
        private const val RECORDING_DISPLAY_NAME = "autoscript-recording"

        /**
         * 录屏编码档位：H.264 + 3 Mbps + 30fps —— **通用安全档，不是"最佳"档**。
         *
         * 录屏产物要能被任何播放器打开，而"最佳"（高 profile/level、可变码率）在不同
         * 编码器上表现不同，有的直接在 `prepare()` 拒绝。要更高画质由脚本侧后续开参数，
         * 缺省这一档先保证"能录出来、能播"。
         */
        private const val RECORDING_FRAME_RATE = 30
        private const val RECORDING_BIT_RATE = 3_000_000

        /** 对象池大小（§9.2 `maxImages=2~3`）：够双缓冲，又不会把整屏帧囤在内存里。 */
        const val MAX_IMAGES = 3

        /** 单帧等待上限：投屏帧率通常 60fps，等不到就是内容没更新/会话已死 —— 有界。 */
        const val DEFAULT_FRAME_WAIT_MILLIS = 2_000L
    }
}

/**
 * 一个**已经 `prepare()` 好**的编码器 + 它实际用的尺寸。
 *
 * 两个字段必须一起交出来：`MediaRecorder.setVideoSize` 与 `VirtualDisplay` 的输出面尺寸
 * 必须一致（不一致时编码器要么拒绝、要么给拉伸的画面），而尺寸可能因为编码器拒绝提示值
 * 而**退回屏幕真值**（见 `prepareRecorderWithFallback`）—— 只交 recorder 会让调用方拿着
 * "提示的尺寸"去建显示，两边就此错位。
 */
private class PreparedRecorder(
    val recorder: MediaRecorder,
    val size: Pair<Int, Int>,
)

/**
 * 取用一次性系统同意凭据（三道闸：类型对、用户真的同意、还没被用过）。
 *
 * 两条腿共用**同一个同意口**（`ScreenConsentBroker`）与**同一种凭据**
 * （[AndroidScreenConsentToken]）：各写一遍就会漂，而漂的后果是某一条腿悄悄放行了
 * "已被用过的凭据"——API 34+ 上那是必然的 `SecurityException`。
 *
 * 住文件级扩展而不是成员：它一个实例状态都不读，而设备类的方法数已经贴着
 * detekt `TooManyFunctions` 的类内阈值 —— 能挪出去的纯函数就挪出去。
 */
private fun AndroidMediaProjectionSessions.consumeConsent(
    consent: ScreenConsentToken?,
): AndroidScreenConsentToken {
    val token = consent as? AndroidScreenConsentToken
    if (token != null && token.granted && token.consume()) return token
    // 三道闸合成一个出口：三处失败对调用方是**同一件事**（这次没有可用的同意），
    // 差别只在文案 —— 分成三个 throw 只会让"哪一道拦下的"藏在行号里。
    throw MediaProjectionOpenException(MediaProjectionOpenFailure.DENIED, consentDenial(consent, token))
}

/**
 * `consumeConsent` 的失败文案（诊断用；三道闸的差别只体现在这里）。
 *
 * 住文件级而不是成员：它不读任何实例状态，而设备类已经有 20+ 个方法
 * （detekt `TooManyFunctions` 的类内阈值 25）—— 能挪出去的纯函数就挪出去。
 */
private fun consentDenial(
    consent: ScreenConsentToken?,
    token: AndroidScreenConsentToken?,
): String = when {
    token == null ->
        "没有系统投屏同意的结果（凭据缺失或类型不符：${consent?.javaClass?.simpleName ?: "null"}）"
    !token.granted -> "用户取消了投屏授权"
    else -> "本次系统同意已被用过（API 34+ 一次同意只换一条会话）"
}

/**
 * 造一个 `MediaRecorder`。
 *
 * 抽成函数只有一个理由：**厂商定制 ROM 的构造器签名不一致**（个别 ROM 的
 * `MediaRecorder(Context)` 会抛 `NoSuchMethodError`），所以这里按 `Build.VERSION`
 * 走公开 API 的两条分支，并把它收在一处 —— 散在调用点上就没人记得还有旧分支。
 */
@Suppress("DEPRECATION") // API 31 起 Context 版才可用，minSdk 26 必须留无参分支
private fun newRecorder(context: Context): MediaRecorder =
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
        MediaRecorder(context)
    } else {
        MediaRecorder()
    }
