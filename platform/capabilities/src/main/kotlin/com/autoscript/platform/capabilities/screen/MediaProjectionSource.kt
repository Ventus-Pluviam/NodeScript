package com.autoscript.platform.capabilities.screen

import com.autoscript.domain.automation.ImageAnalyzer
import com.autoscript.domain.automation.ImageFrame
import com.autoscript.domain.automation.LeasedMediaProjectionSession
import com.autoscript.domain.automation.MediaProjectionOpenException
import com.autoscript.domain.automation.MediaProjectionSessionOwner
import com.autoscript.domain.automation.MediaProjectionSessionState
import com.autoscript.domain.automation.MediaProjectionSessions
import com.autoscript.domain.automation.RawFrame
import com.autoscript.domain.automation.ScreenConsentBroker
import com.autoscript.domain.automation.toAutojsException
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import kotlinx.coroutines.CancellationException

/**
 * 投屏会话面（§9.2 MediaProjection **会话语义**）—— handler 消费的窄缝。
 *
 * 与 [ScreenshotSource]（a11y 截图，333ms 节流、无会话）**并列**：两个帧源各自独立接线，
 * 一个不可用**绝不**静默改走另一个（那正是"脚本以为在录屏、实际拿到的是 a11y 节流帧"
 * 这类谎的来源）。本缝只回答五件事：现在有没有会话、开一条、取一帧、收一条、连接撤销时收口。
 */
interface MediaProjectionCapturer {

    /** 投影会话生存态（能力中心「屏幕采集」三态的判据，见 `AndroidCapabilityProbes`）。 */
    fun projectionState(): MediaProjectionSessionState

    /**
     * 开一条会话：**征一次系统同意**（[consent]）后交给设备层建 VirtualDisplay。
     *
     * @return 会话句柄（`refId` = 会话 id，`generation` = 投影代际，见类 KDoc）。
     * @throws AutojsException `ERR_CAPTURE_DENIED`（用户取消/已有会话在跑/已有一次开启在进行/
     *   无界面可问）、`ERR_SERVICE_DISABLED`（本机没有投屏通道）。
     * @throws CancellationException 开启过程中被取消（脚本崩了/连接断了/框架收口）——
     *   **原样传播**，且已拿到的系统资源已还清。
     */
    suspend fun start(consent: ScreenConsentBroker, owner: MediaProjectionSessionOwner): MediaProjectionHandle

    /**
     * 取一帧。三道校验**缺一不可**（§9.2 安全面）：
     * 会话 id 在场（否则 `ERR_NOT_FOUND`）→ 代际一致（否则 `ERR_STALE_HANDLE`）→
     * 归属一致（否则 `ERR_PERMISSION_DENIED`）。
     */
    suspend fun nextFrame(sessionId: Long, generation: Long, owner: MediaProjectionSessionOwner): ImageFrame

    /**
     * 收一条会话。校验同上；**归属不符不动手**（关掉别人的会话等于替他断流），
     * 如实 `ERR_PERMISSION_DENIED`。
     *
     * @return true = 确实收掉了；false = 该会话已不在场（幂等路径）。
     */
    suspend fun stop(sessionId: Long, generation: Long, owner: MediaProjectionSessionOwner): Boolean

    /**
     * **框架侧收口**（`SCREEN_OFF` 裁剪 / 进程收口）：不问归属，停当前会话并把在场面表清空。
     *
     * 为什么需要它：[stop] 是脚本面的动作（要校验归属），而"熄屏了必须收掉投屏"是框架的
     * 决定 —— 此时没有哪个执行是"会话主人"，也不该有脚本能拦住这件事。
     */
    suspend fun closeCurrent()

    /**
     * **连接/执行撤销时的收口**（非挂起，[com.autoscript.domain.bridge.ConnectionResourceRegistry]
     * 的撤销回调要调它）：把该归属名下的一切（正在开的、在册的）收干净，**别人的一概不动**。
     *
     * 为什么不是 [closeCurrent]：那是"框架决定停掉全部"（熄屏/进程收口），而连接撤销是
     * "**这一条连接**没了" —— 同一台设备上别的连接（别的脚本）不该被牵连。
     * 为什么非挂起：撤销回调是普通函数，可能在连接线程/撤销线程上被调，那里没有协程可挂。
     *
     * 幂等；对没有资源的归属是无害的 no-op。
     *
     * @return true = 确实收掉了什么。
     */
    fun releaseConnection(owner: MediaProjectionSessionOwner): Boolean
}

/**
 * 投屏会话句柄（桥面回包形状与 a11y 会话同构：`{session:{refId,generation}}`）。
 *
 * [generation] 是**投影代际**：关掉再开后旧句柄的 generation 不再匹配，
 * 于是"上一个会话的迟到回调/迟到 close"打不到新会话。
 */
data class MediaProjectionHandle(val refId: Long, val generation: Long)

/**
 * [MediaProjectionCapturer] 的语义实现（JVM 可测）：**归属/代际/错误分类/开启中状态都在这里**，
 * 设备细节全在 [MediaProjectionSessions] 实现（住 `...capabilities.device`）。
 *
 * 四条不变量：
 * - **一台设备一条会话，且同一时刻只有一次开启在进行**：已有一条活会话、或已有一次
 *   `start` 卡在征询/建会话里时，再来一次如实 `ERR_CAPTURE_DENIED` —— 不替新调用方
 *   杀掉旧会话，也不让两次开启赛跑到设备层去抢同一条 `mediaProjection`；
 * - **id 单调递增、绝不复用**，`generation` = 投影代际（每次成功开会话 +1）：
 *   旧句柄在"关闭后重开"之后既 id 不中、代际也不中，**迟到回调/迟到 close 都打不到新会话**；
 * - **归属按整个 [MediaProjectionSessionOwner] 比**（引擎槽 + 执行号 + 连接号）：只按执行号
 *   比会把同一 run 的两条连接混成一个主人；
 * - **连接撤销收干净自己的**：见 [releaseConnection]。
 *
 * **帧句柄一律经 `ImageAnalyzer.ingest` 发号**（§18-8(b) 帧表共用）：投屏帧与
 * `images.decode()` / `screen.capture()` 的帧**同表同号段**，于是
 * `images.findImage(投屏帧, 模板)` 直接成立、`images.release(投屏帧)` 也放得掉。
 * **analyzer 缺位时如实 `ERR_NOT_IMPLEMENTED`**：绝不退回"本地从 1 发号"——
 * 两个帧源各自从 1 发号会让 `recycle` 把另一来源的帧放掉（**错放别人的帧**），
 * 那比"投屏不可用"严重得多。
 *
 * **本类不预检屏幕策略**（锁屏/安全窗）：投屏是系统合成面，策略判定在设备层按
 * **系统查询**（keyguard）做，**不做像素级 FLAG_SECURE 识别** —— 见设备层 KDoc 的诚实说明。
 *
 * **一把普通锁**（不是挂起 Mutex）：[releaseConnection] 是**非挂起**的撤销路径，必须与
 * `start`/`stop` 共用同一把锁才能挡住"撤销与提交赛跑"。所有临界区都只做 map 读写、
 * **绝不跨挂起点持锁**（`consent.requestConsent()` 与 `sessions.open` 都在锁外）。
 */
class MediaProjectionSource(
    private val sessions: MediaProjectionSessions,
    private val analyzer: ImageAnalyzer? = null,
) : MediaProjectionCapturer {

    private val lock = Any()

    /** 在场会话：id → (代际, 归属, 设备面会话)。 */
    private val live = HashMap<Long, Live>()

    private var nextSessionId = 1L

    /** 投影代际：每次**成功**开会话 +1（旧句柄因此跨代失效）。 */
    private var epoch = 0L

    /** 正在开启的那一次（归属）；null = 没有开启在进行。 */
    private var opening: MediaProjectionSessionOwner? = null

    /** 已被撤销的归属：开启中的那次在提交前拿它做最后一道判据。 */
    private val revokedOwners = HashSet<MediaProjectionSessionOwner>()

    private data class Live(
        val generation: Long,
        val owner: MediaProjectionSessionOwner,
        val session: LeasedMediaProjectionSession,
    )

    override fun projectionState(): MediaProjectionSessionState = sessions.state

    override suspend fun start(
        consent: ScreenConsentBroker,
        owner: MediaProjectionSessionOwner,
    ): MediaProjectionHandle {
        synchronized(lock) {
            val existing = live.values.firstOrNull { it.session.state == MediaProjectionSessionState.ACTIVE }
            if (existing != null) {
                throw AutojsException(
                    ErrorCode.ERR_CAPTURE_DENIED,
                    "已有投屏会话在跑（一台设备同时只有一条）：本次不抢占，也不替你关掉别人的会话",
                )
            }
            if (opening != null) {
                // 上一次开启还卡在征询/建会话里（例如系统对话框开着）。放它过去就是两次
                // 开启赛跑到设备层抢同一条 mediaProjection —— 如实拒绝这一次。
                throw AutojsException(
                    ErrorCode.ERR_CAPTURE_DENIED,
                    "已有一次投屏会话正在开启（同一时刻只允许一次）：本次不抢占",
                )
            }
            opening = owner
        }
        try {
            val token = consent.requestConsent()
            val opened = try {
                sessions.open(token, owner)
            } catch (e: MediaProjectionOpenException) {
                throw e.toAutojsException()
            }
            return synchronized(lock) {
                // 征询/建会话期间连接被撤销（`releaseConnection`）→ 设备层已按归属把这条
                // 会话收掉；这里绝不能把它登记进在场面表（登记了就是一个永远读不到帧的野句柄）。
                if (revokedOwners.contains(owner)) {
                    sessions.close(opened.lease)
                    throw CancellationException("投屏会话开启过程中被收口（连接撤销）")
                }
                val id = nextSessionId++
                val generation = ++epoch
                live[id] = Live(generation, owner, opened)
                MediaProjectionHandle(id, generation)
            }
        } finally {
            synchronized(lock) {
                if (opening == owner) opening = null
                // 撤销簿里自己这条随本次开启结束即清：它的全部用途就是「这一次开启的
                // 提交前判据」，而归属三元组（runId/connectionId）单调不复用 —— 不清掉
                // 就是每条被撤销过的连接都留一条尸，长跑宿主上只进不退。
                revokedOwners.remove(owner)
            }
        }
    }

    override suspend fun nextFrame(
        sessionId: Long,
        generation: Long,
        owner: MediaProjectionSessionOwner,
    ): ImageFrame {
        val entry = requireOwned(sessionId, generation, owner)
        val frame = try {
            entry.session.nextFrame()
        } catch (e: MediaProjectionOpenException) {
            throw e.toAutojsException()
        }
        return ingest(frame)
    }

    /** 帧入表（§18-8(b)）：analyzer 缺位 = 如实不可用，绝不本地发号（见类 KDoc）。 */
    private suspend fun ingest(frame: RawFrame): ImageFrame {
        val spi = analyzer
            ?: throw AutojsException(
                ErrorCode.ERR_NOT_IMPLEMENTED,
                "投屏帧无法入表（图像面未接线）：本地发号会让两个帧源撞号，故如实不可用",
            )
        return spi.ingest(frame.width, frame.height, frame.rgba)
    }

    override suspend fun stop(
        sessionId: Long,
        generation: Long,
        owner: MediaProjectionSessionOwner,
    ): Boolean {
        val entry = synchronized(lock) {
            val e = live[sessionId] ?: return false
            if (e.generation != generation) {
                throw AutojsException(
                    ErrorCode.ERR_STALE_HANDLE,
                    "投屏会话句柄跨代 $sessionId gen=$generation（当前 ${e.generation}）",
                )
            }
            if (e.owner != owner) {
                throw AutojsException(
                    ErrorCode.ERR_PERMISSION_DENIED,
                    "投屏会话 $sessionId 不属于本次执行，不能关闭",
                )
            }
            live.remove(sessionId)
            e
        }
        // 收口在锁外：设备层要还资源，不该占着本类的锁（撤销路径也要用它）。
        sessions.close(entry.session.lease)
        return true
    }

    /** 三道校验的公共路径（见 [nextFrame] KDoc 的顺序理由）。 */
    private fun requireOwned(
        sessionId: Long,
        generation: Long,
        owner: MediaProjectionSessionOwner,
    ): Live = synchronized(lock) {
        val e = live[sessionId]
            ?: throw AutojsException(ErrorCode.ERR_NOT_FOUND, "未知投屏会话 $sessionId")
        if (e.generation != generation) {
            throw AutojsException(
                ErrorCode.ERR_STALE_HANDLE,
                "投屏会话句柄跨代 $sessionId gen=$generation（当前 ${e.generation}）",
            )
        }
        if (e.owner != owner) {
            throw AutojsException(
                ErrorCode.ERR_PERMISSION_DENIED,
                "投屏会话 $sessionId 不属于本次执行",
            )
        }
        e
    }

    /**
     * 框架侧收口：清空在场面表 + 让设备层停掉当前会话（[MediaProjectionSessions.closeCurrent]）。
     *
     * **在场句柄全部作废**（连同别人的）：投屏是进程级单资源，框架收掉它之后所有句柄
     * 都已失效 —— 留着它们只会让后续 `nextFrame` 打到一条已死的会话上（如实报
     * `ERR_NOT_FOUND` 比报"还能用"好）。
     */
    override suspend fun closeCurrent() {
        synchronized(lock) { live.clear() }
        sessions.closeCurrent()
    }

    /**
     * 连接/执行撤销（见接口 KDoc）：**只收自己的**。
     *
     * 三步都必要：
     * 1. 记下这个归属已撤销 —— 正在开启的那次提交时会看到它并放弃（否则"撤销完了又被
     *    提交上去"，留一条没人能读的会话）；
     * 2. 从在场面表摘掉自己的条目（别人的不动）；
     * 3. 让设备层按归属收口（它同时覆盖"正在开的那条"，那条还没进在场面表）。
     *
     * 非挂起：撤销回调可能在任意线程被调；设备层的收口本来就是同步的。
     */
    override fun releaseConnection(owner: MediaProjectionSessionOwner): Boolean {
        val mine = synchronized(lock) {
            // 只在「开启在进行」时记账（提交前那道判据只在那个窗口里被读）。
            // 开启空窗期的撤销由下面的摘表/补 outcome + 设备层归属收口直接覆盖；
            // 「撤销在先、开启在后」的尾帧竞态由 handler 侧
            // `ConnectionResourceRegistry`（撤销后 register 立刻兑现）兜底 ——
            // 簿里那条留着永远不会有人来读，只进不退就是一条慢漏。
            if (opening == owner) revokedOwners += owner
            val ids = live.filterValues { it.owner == owner }.keys.toList()
            ids.map { live.remove(it)!! }
        }
        // 设备层按归属收（覆盖"正在开的那条" + 已提交的那条）；本类再按租约兜一次，
        // 两次都幂等（租约判"还是不是我那条"）。
        var released = sessions.revokeOwner(owner)
        mine.forEach { entry ->
            if (sessions.close(entry.session.lease)) released = true
        }
        return released
    }
}
