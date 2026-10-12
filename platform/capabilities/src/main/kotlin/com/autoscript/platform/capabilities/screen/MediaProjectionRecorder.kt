package com.autoscript.platform.capabilities.screen

import com.autoscript.domain.automation.MediaProjectionOpenException
import com.autoscript.domain.automation.MediaProjectionSessionOwner
import com.autoscript.domain.automation.MediaProjectionSessionState
import com.autoscript.domain.automation.RecordingHandle
import com.autoscript.domain.automation.RecordingOutcome
import com.autoscript.domain.automation.ScreenConsentBroker
import com.autoscript.domain.automation.ScreenRecordingController
import com.autoscript.domain.automation.ScreenRecordingSpec
import com.autoscript.domain.automation.ScreenRecordingSessions
import com.autoscript.domain.automation.LeasedScreenRecording
import com.autoscript.domain.automation.toAutojsException
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.scripts.ProjectId
import com.autoscript.domain.scripts.ScriptPaths
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException

/**
 * 录屏会话面（§9.2 MediaProjection **录屏腿**）—— handler 消费的窄缝。
 *
 * 与 [MediaProjectionCapturer]（取帧腿）**并列**：同一条 `MediaProjection` 会话账、
 * 同一个 `mediaProjection` 前台类型，只是输出汇不同（`MediaRecorder` 的 Surface 给文件）。
 * 本类只做**语义**：归属/代际/落点/幂等收口；设备细节全在 [ScreenRecordingSessions]
 * 实现（住 `...capabilities.device`，唯一碰 `android.media.MediaRecorder` 的地方）。
 *
 * 四条不变量（前三条与取帧腿逐字同源，第四条是录屏腿**独有**的）：
 * - **同一时刻只有一次开启在进行**：已有一次 `start` 卡在征询/建会话里时，再来一次如实
 *   `ERR_CAPTURE_DENIED`（不让两次开启赛跑到设备层去抢同一条 `mediaProjection`）；
 *   已有**未收口**的录屏会话在跑时同样拒绝（不替新调用方杀掉旧会话 —— 那会留下一个
 *   没人 finalize 的 mp4）；
 * - **id 单调递增、绝不复用**，`generation` = 录屏代际（每次成功开会话 +1）：
 *   旧句柄在"关闭后重开"之后既 id 不中、代际也不中，**迟到收口打不到新会话**；
 * - **归属按整个 [MediaProjectionSessionOwner] 比**（引擎槽 + 执行号 + 连接号）；
 * - **收口幂等，且收口后仍答得出产物**（[stop] 回**同一次** [RecordingOutcome]）：
 *   录屏的产物是文件 —— 会话被框架收口（熄屏裁剪 / 连接撤销）之后，脚本仍然要知道
 *   "文件在哪、多大、完不完整"。取帧腿没有这条：帧死了就是死了，没有产物要交代。
 *
 * **落点在语义层算**（[ScriptPaths.recordingsDir]，`files/scripts/<projectId>/.recordings`）：
 * 设备层只拿到一条现成的绝对路径（见 [ScreenRecordingSpec]）。目录由本类建好 ——
 * 设备层不做 IO 准备，只做 `MediaRecorder.prepare()`。
 *
 * **一把普通锁**（不是挂起 Mutex）：[releaseConnection] 是**非挂起**的撤销路径，
 * 必须与 `start`/`stop` 共用同一把锁才能挡住"撤销与提交赛跑"。所有临界区都只做 map
 * 读写 + **非挂起**的设备收口（[LeasedScreenRecording.stop] 刻意非挂起，见其 KDoc），
 * 因此可以持锁调用；**绝不跨挂起点持锁**（`consent.requestConsent()` 与
 * `sessions.startRecording` 都在锁外）。
 */
class MediaProjectionRecorder(
    private val sessions: ScreenRecordingSessions,
    /** App 私有文件目录（`ScriptPaths` 的根）；落点 = [ScriptPaths.recordingsDir] 之下。 */
    private val filesDir: Path,
) : ScreenRecordingController {

    private val lock = Any()

    /** 在册会话：id → 条目（条目带 [Entry.outcome]，收口后**不摘除** —— 见类 KDoc 第 4 条）。 */
    private val live = HashMap<Long, Entry>()

    /** 已收口条目按收口先后排的队（见 [trimFinished]）：超过上限就丢最老的。 */
    private val finishedOrder = LinkedHashSet<Long>()

    private var nextSessionId = 1L

    /** 录屏代际：每次**成功**开会话 +1（旧句柄因此跨代失效）。 */
    private var epoch = 0L

    /** 正在开启的那一次（归属）；null = 没有开启在进行。 */
    private var opening: MediaProjectionSessionOwner? = null

    /** 已被撤销的归属：开启中的那次在提交前拿它做最后一道判据。 */
    private val revokedOwners = HashSet<MediaProjectionSessionOwner>()

    /** 文件名去重用的进程内序号（与毫秒时间戳合起来保证同一毫秒内多次开启也不撞名）。 */
    private val fileSeq = AtomicLong(1)

    private class Entry(
        val generation: Long,
        val owner: MediaProjectionSessionOwner,
        val session: LeasedScreenRecording,
    ) {
        /** 收口结果；null = 还没收口。**非挂起**写入（持 [lock]）。 */
        var outcome: RecordingOutcome? = null
    }

    override fun recordingState(): MediaProjectionSessionState = sessions.recordingState

    override suspend fun start(
        consent: ScreenConsentBroker,
        owner: MediaProjectionSessionOwner,
        projectId: ProjectId,
        width: Int,
        height: Int,
    ): RecordingHandle {
        synchronized(lock) {
            val running = live.values.firstOrNull { it.outcome == null }
            if (running != null) {
                throw AutojsException(
                    ErrorCode.ERR_CAPTURE_DENIED,
                    "已有录屏会话在跑（一台设备同时只有一条投屏会话）：本次不抢占，" +
                        "也不替你收掉别人的会话（那会留下一个没人 finalize 的 mp4）",
                )
            }
            if (opening != null) {
                // 上一次开启还卡在征询/建会话里（例如系统对话框开着）。放它过去就是两次
                // 开启赛跑到设备层抢同一条 mediaProjection —— 如实拒绝这一次。
                throw AutojsException(
                    ErrorCode.ERR_CAPTURE_DENIED,
                    "已有一次录屏会话正在开启（同一时刻只允许一次）：本次不抢占",
                )
            }
            opening = owner
        }
        try {
            val spec = specFor(projectId, width, height)
            val token = consent.requestConsent()
            val opened = try {
                sessions.startRecording(token, owner, spec)
            } catch (e: MediaProjectionOpenException) {
                throw e.toAutojsException()
            }
            return synchronized(lock) {
                // 征询/建会话期间连接被撤销（`releaseConnection`）→ 设备层已按归属把这条
                // 会话收掉（**并 finalize 了文件**）；这里绝不能把它登记进在册表
                // （登记了就是一个永远收不了口的野句柄，而它的文件已经落了盘）。
                if (revokedOwners.contains(owner)) {
                    val outcome = opened.stop()
                    throw CancellationException(
                        "录屏会话开启过程中被收口（连接撤销）：文件已 finalize 在 ${outcome.path}",
                    )
                }
                val id = nextSessionId++
                val generation = ++epoch
                live[id] = Entry(generation, owner, opened)
                RecordingHandle(id, generation, opened.path)
            }
        } finally {
            synchronized(lock) { if (opening == owner) opening = null }
        }
    }

    /**
     * 算落点并**把目录建好**（设备层只做 `prepare()`，不做 IO 准备）。
     *
     * 文件名 = `rec-<毫秒>-<序号>.mp4`：时间戳让用户一眼看出录的是什么时段，序号保证
     * 同一毫秒内连续开两次也不撞名（撞名的后果是第二次 `prepare()` 覆盖掉第一个文件）。
     * **不检查/不覆盖同名文件**：名字由我们现造，重名只可能是"同一毫秒 + 同一序号"，
     * 而那被序号排除了。
     */
    private fun specFor(projectId: ProjectId, width: Int, height: Int): ScreenRecordingSpec {
        val dir = ScriptPaths.recordingsDir(filesDir, projectId)
        try {
            Files.createDirectories(dir)
        } catch (e: java.io.IOException) {
            throw AutojsException(ErrorCode.ERR_IO, "录屏落点目录建不出来：$dir（${e.message}）", e)
        }
        val name = "rec-${System.currentTimeMillis()}-${fileSeq.getAndIncrement()}.mp4"
        // 尺寸提示**原样带下去**（不在这里判合法性）：合法性由 handler 的 `optSize` 把守
        // （非正/非整数当场 ERR_INVALID_PARAM），设备层再按自己的编码器能力决定用不用。
        return ScreenRecordingSpec(
            path = dir.resolve(name).toAbsolutePath().toString(),
            width = width,
            height = height,
        )
    }

    /**
     * 收口并 finalize（**幂等**：第二次调用回同一次结果，不重复 finalize）。
     *
     * 三道校验的顺序与取帧腿同源：id 在场（否则 `ERR_NOT_FOUND`）→ 代际一致
     * （否则 `ERR_STALE_HANDLE`）→ 归属一致（否则 `ERR_PERMISSION_DENIED` —— 关别人的
     * 录屏等于替他断流并决定他的文件收在哪，两者对调用方含义不同）。
     *
     * **已收口的条目留在册**（见类 KDoc 第 4 条）：所以框架收口（熄屏裁剪/连接撤销）
     * 之后脚本再来 `stop` 仍答得出路径与完整性，而不是 `ERR_NOT_FOUND`。
     */
    override fun stop(sessionId: Long, generation: Long, owner: MediaProjectionSessionOwner): RecordingOutcome =
        synchronized(lock) {
            val entry = live[sessionId]
                ?: throw AutojsException(ErrorCode.ERR_NOT_FOUND, "未知录屏会话 $sessionId")
            if (entry.generation != generation) {
                throw AutojsException(
                    ErrorCode.ERR_STALE_HANDLE,
                    "录屏会话句柄跨代 $sessionId gen=$generation（当前 ${entry.generation}）",
                )
            }
            if (entry.owner != owner) {
                throw AutojsException(
                    ErrorCode.ERR_PERMISSION_DENIED,
                    "录屏会话 $sessionId 不属于本次执行，不能关闭",
                )
            }
            entry.outcome?.let { return it }
            // 设备收口**非挂起**（见 LeasedScreenRecording.stop 的 KDoc）：持锁调用是安全的
            // ——设备层不会回调进本类。持锁也顺手把"两次并发 stop"排成先后，第二次看到
            // 已写入的 outcome 直接回同一份。
            val outcome = entry.session.stop()
            entry.outcome = outcome
            trimFinished()
            outcome
        }

    /**
     * 框架侧收口（熄屏裁剪 / 进程收口）：停当前录屏会话并 **finalize**。
     *
     * **在册条目一个不摘**（与取帧腿的 `closeCurrent` 刻意相反）：它们的产物信息要留着
     * （见类 KDoc 第 4 条）。取帧腿那边清空是对的 —— 帧死了就是死了。
     *
     * 非挂起：调用点之一是 `ScreenGate.pass(SCREEN_OFF)` 的 `onScreenOff`（普通函数）。
     */
    override fun closeCurrent() {
        val pending = synchronized(lock) { live.filterValues { it.outcome == null }.keys.toList() }
        sessions.closeCurrentRecording()
        synchronized(lock) {
            pending.forEach { id ->
                val entry = live[id] ?: return@forEach
                if (entry.outcome == null) entry.outcome = entry.session.stop()
            }
            trimFinished()
        }
    }

    /**
     * 连接/执行撤销（见接口 KDoc）：**只收自己的**。
     *
     * 三步都必要：
     * 1. 记下这个归属已撤销 —— 正在开启的那次提交时会看到它并放弃；
     * 2. 让设备层按归属收口（它同时覆盖"正在开的那条"，那条还没进在册表）并 finalize；
     * 3. 把在册条目的 outcome 补上（设备层刚收的那条，`session.stop()` 回它记下的结果）。
     *
     * 非挂起：撤销回调可能在任意线程被调；设备层的收口本来就是同步的。
     */
    override fun releaseConnection(owner: MediaProjectionSessionOwner): Boolean {
        val mine = synchronized(lock) {
            revokedOwners += owner
            live.filterValues { it.owner == owner && it.outcome == null }.keys.toList()
        }
        var released = sessions.revokeRecordingOwner(owner)
        synchronized(lock) {
            mine.forEach { id ->
                val entry = live[id] ?: return@forEach
                if (entry.outcome == null) {
                    entry.outcome = entry.session.stop()
                    released = true
                }
            }
            trimFinished()
        }
        return released
    }

    /**
     * 丢弃最老的已收口条目（**必须在持有 [lock] 时调用**）。
     *
     * 为什么要有界：一个长跑脚本可以开成千上万次录屏，条目若只增不减就是一条无界增长
     * （每次一个 path 字符串 + 一份 outcome）。[FINISHED_KEEP] 覆盖任何真实的"收口之后
     * 再问一次"（那是脚本在同一个函数里紧接着做的事，不是跨几十次录屏的事）。
     * 被丢掉的条目再 `stop` 会回 `ERR_NOT_FOUND` —— 如实（产物信息不再在册），
     * 而不是编一份出来。
     */
    private fun trimFinished() {
        live.forEach { (id, entry) -> if (entry.outcome != null) finishedOrder.add(id) }
        while (finishedOrder.size > FINISHED_KEEP) {
            val oldest = finishedOrder.first()
            finishedOrder.remove(oldest)
            live.remove(oldest)
        }
    }

    private companion object {
        /**
         * 已收口条目的保留上限（见 [trimFinished]）。
         *
         * 64 与 `ProjectionForegroundHost.ABANDONED_KEEP` 同量级：迟到的"再问一次"
         * 只可能是最近那几次（脚本在收口之后紧接着读结果），不是几十次之前。
         */
        const val FINISHED_KEEP = 64
    }
}
