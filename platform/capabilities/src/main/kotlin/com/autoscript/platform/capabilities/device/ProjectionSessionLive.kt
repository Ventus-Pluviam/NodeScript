package com.autoscript.platform.capabilities.device

import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import com.autoscript.domain.automation.MediaProjectionSessionOwner
import com.autoscript.domain.automation.RecordingOutcome
import com.autoscript.domain.automation.SessionResourceLease
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.channels.Channel

/**
 * 在册的一条投屏会话（§9.2）—— **取帧腿与录屏腿共用同一个会话账**。
 *
 * 抽成一条继承链的理由不是"少写几行"，而是**一台设备只有一条 `MediaProjection` 会话**
 * 这条不变量必须落在**同一个 `current` 槽**上：两条腿各自持一份 `current` 就会让
 * "取帧会话在跑"与"录屏会话在跑"互不可见，于是同一时刻真能开出两条
 * `VirtualDisplay`（用户看到两份投屏、前台服务代际互相踩）。抽出来之后
 * 「同时只有一条」由 `AndroidMediaProjectionSessions` 的那一个槽结构性地保证，
 * 而不是靠两条腿各自记得去看对方。
 *
 * **两腿共有的四样**在这里：[lease]（收口凭据）、[owner]（归属）、[projection]（用于
 * 按对象身份认领系统 `onStop`）、[foregroundLease]（前台代际，旧会话的迟到收口停不掉
 * 新会话的前台）、[stopped] / [closed]（生存态 + 收口恰好一次的凭据）。
 * **两腿不同的部分**（`ImageReader` 的帧路 vs `MediaRecorder` 的落盘路）由子类自己持有
 * 并在 [release] 里还清 —— 那正是"输出汇不同"的全部内容。
 */
internal abstract class Live(
    val lease: SessionResourceLease,
    val owner: MediaProjectionSessionOwner,
    val projection: MediaProjection,
    val foregroundLease: ForegroundLease,
) {
    /** 系统已收回投屏（`onStop`）或本侧已收口 —— 句柄还在，但不可再用。 */
    @Volatile
    var stopped: Boolean = false

    /** 资源是否已经还过（收口**恰好一次**的凭据）。 */
    val closed = AtomicBoolean(false)

    /**
     * 还清本条会话的系统资源（**由 [AndroidMediaProjectionSessions.teardown] 保证恰好一次**）。
     *
     * 实现必须**逐项** `runCatching`：一份资源还不掉，不能让其余资源也留在半开态。
     * 前台那一步一律走 `if (!foreground.stop(lease)) foreground.abandon(lease)` ——
     * 停不掉就明确放弃这一代（服务迟到起来时自己退场，不留孤儿 FGS）。
     */
    abstract fun release(foreground: ProjectionForeground)
}

/** 取帧腿的在册会话（`ImageReader` 的 Surface → 帧信号 channel）。 */
internal class CaptureLive(
    lease: SessionResourceLease,
    owner: MediaProjectionSessionOwner,
    projection: MediaProjection,
    foregroundLease: ForegroundLease,
    val reader: ImageReader,
    val display: VirtualDisplay,
    val callback: MediaProjection.Callback,
    val frames: Channel<Unit>,
) : Live(lease, owner, projection, foregroundLease) {

    override fun release(foreground: ProjectionForeground) {
        frames.close()
        runCatching { reader.setOnImageAvailableListener(null, null) }
        runCatching { display.release() }
        runCatching { reader.close() }
        runCatching { projection.unregisterCallback(callback) }
        runCatching { projection.stop() }
        if (!foreground.stop(foregroundLease)) foreground.abandon(foregroundLease)
    }
}

/**
 * 录屏腿的在册会话（§9.2）：`MediaRecorder` 的 Surface 交给 `VirtualDisplay`。
 *
 * 与 [CaptureLive] 并列，共用基类 [Live] 的租约/归属/投影/前台代际 —— 于是
 * 「一台设备同时只有一条 `MediaProjection` 会话」由**同一个 `current` 槽**结构性保证，
 * 而不是靠两条腿各自记得去看对方。
 *
 * [outcome] 是**收口结果缓存**（收口时写一次，之后只读）：收口恰好一次由 [Live.closed]
 * 的 CAS 保证，第二次读它 —— 这正是录屏腿与取帧腿的关键差异
 * （帧死了就是死了，而文件是产物，脚本必须知道它在哪、多大、完不完整）。
 */
internal class RecordingLive(
    lease: SessionResourceLease,
    owner: MediaProjectionSessionOwner,
    projection: MediaProjection,
    foregroundLease: ForegroundLease,
    val recorder: android.media.MediaRecorder,
    val display: VirtualDisplay,
    val callback: MediaProjection.Callback,
    val path: Path,
) : Live(lease, owner, projection, foregroundLease) {

    /** 收口结果（收口时写入一次；之后只读）。 */
    @Volatile
    var outcome: RecordingOutcome? = null

    @Suppress("TooGenericExceptionCaught") // stop() 的失败面是厂商编码器实现（RuntimeException 一族），这里要的是"如实记下失败"而不是分类
    override fun release(foreground: ProjectionForeground) {
        // `MediaRecorder.stop()` 是**唯一**把 mp4 的 moov box 写完的动作 —— 它抛异常时
        // 文件就是个坏 mp4。所以这里**不能**把异常吞成"成功"：如实记进 outcome.completed。
        var completed = true
        var detail: String? = null
        try {
            recorder.stop()
        } catch (t: RuntimeException) {
            // 一帧都没录到就停是这条路径最常见的来路（系统直接抛 RuntimeException）。
            completed = false
            detail = "${t.javaClass.simpleName}: ${t.message}"
        }
        // reset 在 stop 失败之后也可能抛（非法状态机）；它只影响复用，本实例马上丢弃。
        runCatching { recorder.reset() }
        runCatching { recorder.release() }
        runCatching { display.release() }
        runCatching { projection.unregisterCallback(callback) }
        runCatching { projection.stop() }
        if (!foreground.stop(foregroundLease)) foreground.abandon(foregroundLease)
        outcome = RecordingOutcome(
            path = path.toAbsolutePath().toString(),
            sizeBytes = sizeOf(path),
            completed = completed,
            detail = detail,
        )
    }

    /**
     * 落盘真值。量不到就回 0 —— **不编一个数**：收口路径上抛异常会让资源留在半开态，
     * 而脚本拿 `sizeBytes == 0` 就知道这条产物不可信（配合 `completed` 一起看）。
     */
    private fun sizeOf(file: Path): Long = try {
        if (Files.isRegularFile(file)) Files.size(file) else 0L
    } catch (_: java.io.IOException) {
        0L
    }
}

/**
 * "一次开启"的状态机（§9.2）—— **取帧腿与录屏腿共用同一个实例**。
 *
 * 三个字段是同一件事的三面，拆开放会漂，所以合成一个对象：
 * - **同时只允许一次开启**（第二个并发 `open` 被 [tryBegin] 如实拒绝，而不是两条都去抢投影）；
 * - **归属**（撤销路径要能在 `open` 中途收掉自己那条）；
 * - **哪条腿**（"放弃开启中那条"必须按腿区分）。
 *
 * 最后一条最容易漏：`abandoned` 是两腿**共用**的一个标记，而两条腿各有自己的
 * "收口开启中那条"入口（取帧 `closeCurrent`、录屏 `closeCurrentRecording`）。
 * 不记腿的话，"熄屏裁剪录屏"会把一条**正在开的取帧会话**也一起放弃（反之亦然）——
 * 表现是"录屏那条报错，取帧那条也莫名其妙开不出来"，而两条腿的代码各自看都没错。
 *
 * 锁**从外面传进来**（不是自带一把）：这几个字段与 `current` 槽必须由**同一把锁**保护
 * —— 否则"检查 current 为空 → 置 opening"这条复合判据会与提交/收口赛跑。
 */
internal class OpeningGate(private val lock: Any) {

    private var active = false
    private var owner: MediaProjectionSessionOwner? = null
    private var recording = false
    private var abandoned = false

    /**
     * 占位一次开启。**false = 已有一次开启在进行，本次被如实拒绝**（字段一个都不动）。
     *
     * 旧的 `begin` 无条件覆盖四字段：第二个并发 `open` 不但进了门，还把第一次的
     * 归属/腿记账踩掉、`abandoned` 重置回 false —— 于是两条腿各开一条
     * `MediaProjection` 赛跑去提交，后到者把 `current` 槽整个顶替（被顶替那条从此
     * 没有任何收口路径找得到它：投影照跑、前台照挂，直到进程死）。拒绝必须发生在
     * **置位之前**，所以本函数先判后写，调用方拿 false 去抛诚实的错。
     */
    fun tryBegin(owner: MediaProjectionSessionOwner, recording: Boolean): Boolean =
        synchronized(lock) {
            if (active) return false
            active = true
            this.owner = owner
            this.recording = recording
            abandoned = false
            true
        }

    fun end() {
        synchronized(lock) {
            active = false
            owner = null
        }
    }

    /** 本次开启是否已被放弃（`open` 的检查点读它；提交前读它）。 */
    fun abandoned(): Boolean = synchronized(lock) { abandoned }

    /**
     * 放弃正在开的那条（仅当它属于 [recording] 那条腿）。
     *
     * @param owner 非 null 时还要归属一致（撤销路径只收自己那条）。
     * @return true = 这一次确实标记了放弃。
     */
    fun abandon(recording: Boolean, owner: MediaProjectionSessionOwner? = null): Boolean =
        synchronized(lock) {
            if (!active || this.recording != recording) return false
            if (owner != null && this.owner != owner) return false
            abandoned = true
            true
        }
}

