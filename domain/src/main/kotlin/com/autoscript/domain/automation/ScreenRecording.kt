package com.autoscript.domain.automation

import com.autoscript.domain.scripts.ProjectId

/**
 * 录屏规格（§9.2 录屏腿）：**语义层决定「录到哪、录多大」，设备层只执行**。
 *
 * 为什么路径由语义层定而不是设备层自己拼：落点约定（`ScriptPaths`）住在 `:domain`，
 * 而设备层（`com.autoscript.platform.capabilities.device`）是唯一碰 `android..` 的地方
 * —— 让它去认识 `filesDir` 布局就是把一条路径约定劈成两份（拼错不编译失败，只表现为
 * "录完了但文件找不到"，同 `ScriptPaths` KDoc 里那条教训）。
 */
data class ScreenRecordingSpec(
    /**
     * 目标文件**绝对路径**（设备层 `MediaRecorder.setOutputFile` 用它）。
     * **父目录由语义层建好**：设备层不做 IO 准备，只做 `prepare()`。
     */
    val path: String,

    /** 视频尺寸提示；`<= 0` = 让设备层按屏幕真值（提示不是承诺，同截图那条口径）。 */
    val width: Int = 0,
    val height: Int = 0,
)

/**
 * 一次录屏的收口结果（**设备层真值**，不猜）。
 *
 * 字段都是"我们真的知道的事"：路径是我们定下去的那条、字节数是现读的、`completed`
 * 是 `MediaRecorder.stop()` 到底成没成。**不在这里编造时长/帧率** —— 那些要解 mp4，
 * 不是收口路径该干的事（脚本要时长自己拿路径去解）。
 */
data class RecordingOutcome(
    /** 录制文件绝对路径（与 [ScreenRecordingSpec.path] 同一条）。 */
    val path: String,

    /** 落盘真值（字节）；`0` = 没有文件（或还没落盘）。 */
    val sizeBytes: Long,

    /**
     * `MediaRecorder.stop()` 是否成功 —— 成功 = 文件是**完整可播**的 mp4。
     *
     * `false` 的典型来路：一帧都没录到就停（系统直接抛 `RuntimeException`，mp4 的
     * moov box 写不出来）。**如实报 false 而不是回一个"成功"**：脚本拿它决定要不要
     * 把这条产物交给下游（一个 0 字节的 mp4 看起来像文件，播起来才知道不是）。
     */
    val completed: Boolean,

    /** `completed = false` 时的原因原文（设备层给，诊断用；`completed = true` 时为 null）。 */
    val detail: String? = null,
)

/**
 * 一条存活的录屏会话（**设备面**，§9.2）。
 *
 * 与 [MediaProjectionSession]（取帧）并列：同一条 `MediaProjection` 会话账、同一个
 * `mediaProjection` 前台类型，**只是输出汇不同** —— 那边是 `ImageReader` 的 Surface
 * 给帧，这边是 `MediaRecorder` 的 Surface 给文件。
 */
interface ScreenRecordingSession {
    /** 归属（开它的那个执行；由 [ScreenRecordingSessions.startRecording] 记下，非空）。 */
    val owner: MediaProjectionSessionOwner

    /** 录制文件绝对路径（**开的时候就定下了**，收口前也答得出）。 */
    val path: String

    /** 会话生存态；系统收回投屏后转 [MediaProjectionSessionState.STOPPED]。 */
    val state: MediaProjectionSessionState

    /**
     * 收口并 **finalize**（幂等）：停 `MediaRecorder`、还清 VirtualDisplay/投影/前台，
     * 回一份 [RecordingOutcome]。
     *
     * **幂等**：第二次调用回**同一次**结果，不重复 finalize（重复 `stop()` 会抛，
     * 且第二次的结果没有意义）。会话已被框架收口（熄屏裁剪/连接撤销）时同样答得出 ——
     * 收口时那份结果被记下来了（**这是录屏比截屏多出来的一条要求**：帧死了就是死了，
     * 而文件是产物，脚本必须知道它在哪、完不完整）。
     *
     * **非挂起**是刻意的（与 [MediaProjectionSession.close] 的 `suspend` 不同）：
     * 收口点之一是**连接撤销**（[com.autoscript.domain.bridge.ConnectionResourceRegistry]
     * 的回调是普通函数，可能在连接线程/撤销线程上被调），那里没有协程可挂 ——
     * 而录屏的收口**必须**在撤销路径上真的跑完（否则留下一个没 finalize 的 mp4）。
     * 设备层的收口本来就是同步的（`MediaRecorder.stop` / `VirtualDisplay.release` /
     * `MediaProjection.stop` 都不挂起）。
     */
    fun stop(): RecordingOutcome
}

/**
 * 带**租约**的录屏会话（收口凭据，同 [LeasedMediaProjectionSession] 的理由）：
 * 旧会话的迟到收口打不到新会话。
 */
interface LeasedScreenRecording : ScreenRecordingSession {
    val lease: SessionResourceLease
}

/**
 * 录屏会话的设备资源面（§9.2）：设备层交出来的录屏句柄除了收口，还能回答"我这份资源还归不归我"。
 *
 * 与 [MediaProjectionSessions]（取帧腿）**并列而不是合并**：两条腿各自的方法表分开，
 * 语义层各自只依赖自己那一条（`MediaProjectionSource` 不必认识 `startRecording`）。
 * **实现是同一个对象** —— 一台设备只有一条 `MediaProjection` 会话账（`current` 槽、
 * mediaProjection 前台代际、开/关状态机都是共享的），所以两条腿**互斥**：
 * 录屏在跑时开不了取帧会话，反之亦然（"一台设备同时只有一条"，见
 * `AndroidMediaProjectionSessions` KDoc）。
 */
interface ScreenRecordingSessions {

    /** 当前录屏会话的生存态（无录屏会话 = [MediaProjectionSessionState.IDLE]）。 */
    val recordingState: MediaProjectionSessionState

    /** 当前录屏会话归属（无 = null）。 */
    val recordingOwner: MediaProjectionSessionOwner?

    /**
     * 开一条录屏会话：**用掉**一次系统同意（[consent] 一次性，与取帧腿同一口），
     * 起 mediaProjection 前台类型，`MediaRecorder.prepare()` 之后把它的 Surface
     * 交给 `createVirtualDisplay`。
     *
     * 时序与取帧腿逐条同源（API 34+ 的硬要求）：前台起并确认 → `getMediaProjection` →
     * 注册 `MediaProjection.Callback` → `createVirtualDisplay` → `MediaRecorder.start()`；
     * 每个检查点失败都还清已拿到的资源（[spec] 的目标文件**不删** —— 半截文件如实留在
     * 原地，让调用方看得见，比"悄悄抹掉"可诊断；它是不是完整由 [RecordingOutcome.completed]
     * 回答）。
     *
     * @throws MediaProjectionOpenException 同 [MediaProjectionSessions.open]。
     * @throws com.autoscript.domain.core.AutojsException `ERR_IO`/`ERR_DISK_FULL`
     *   （落点不可写、`prepare()` 失败）。
     */
    suspend fun startRecording(
        consent: ScreenConsentToken?,
        owner: MediaProjectionSessionOwner,
        spec: ScreenRecordingSpec,
    ): LeasedScreenRecording

    /**
     * **框架级收口**（熄屏裁剪 / 进程收口）：不问归属，停当前录屏会话并**finalize 文件**。
     *
     * 为什么录屏腿需要它自己的名字：取帧腿的 `closeCurrent` 只丢帧（帧死了就是死了），
     * 而录屏腿**必须落盘收尾** —— 一个没 finalize 的 mp4 是坏文件。两条腿落到同一个
     * 设备实现上（同一个 `current`），但两条 SPI 各自表达自己的收口语义，
     * 免得"停会话"在录屏语境里被读成"丢掉就行"。
     *
     * 幂等、不抛；没有录屏会话时回 false（取帧会话在场也回 false —— 本方法只管录屏腿）。
     */
    fun closeCurrentRecording(): Boolean

    /**
     * **按归属收口**（连接/执行撤销）：收掉这个归属名下的录屏会话 —— **包括正在开的那条**
     * （`startRecording` 还没返回时连接就断了也算），并 finalize 文件。别的归属一概不动。
     *
     * 非挂起（撤销回调是普通函数）、幂等、不抛。
     */
    fun revokeRecordingOwner(owner: MediaProjectionSessionOwner): Boolean
}

/**
 * 录屏面（§9.2）—— handler 消费的窄缝，与 [MediaProjectionCapturer] 并列。
 *
 * 只回答五件事：现在有没有录屏会话、开一条、收一条、框架收口、连接撤销时收口。
 * 归属/代际/错误分类与取帧腿同一条纪律（三道校验缺一不可），**但有一条刻意的不同**：
 * [stop] 是**幂等**的 —— 第二次调用回**同一次**结果而不是"未知会话"。
 *
 * 为什么这条不同：录屏的产物是**文件**。会话被框架收口（熄屏裁剪 / 连接撤销）之后，
 * 脚本仍然需要知道"文件在哪、多大、完不完整" —— 回 `ERR_NOT_FOUND` 等于把产物信息
 * 弄丢，而那正是本任务要防的（"录完了但 mp4 没 finalize"这一类故障的下游）。
 * 取帧腿没有这个问题：帧死了就是死了，没有产物要交代。
 */
interface ScreenRecordingController {

    /** 录屏会话生存态（能力中心判据与诊断用）。 */
    fun recordingState(): MediaProjectionSessionState

    /**
     * 开一条录屏会话：先征一次系统同意（[consent]），再交给设备层建
     * `MediaRecorder` + `VirtualDisplay`。
     *
     * @param projectId 本次执行的项目号（来自已认证身份，**不是** payload 自报）；
     *   空/非法 → `ERR_PERMISSION_DENIED`（身份不全，无法决定落点；**绝不**套默认项目名 ——
     *   那会把产物静默写进别人的项目目录）。落点由实现按
     *   `ScriptPaths.recordingsDir(filesDir, projectId)` 算（`filesDir` 是构造参数）。
     * @return 录屏句柄（`refId` = 会话 id，`generation` = 录屏代际，`path` = 落点）。
     * @throws com.autoscript.domain.core.AutojsException `ERR_CAPTURE_DENIED`（用户取消/
     *   已有会话在跑/已有一次开启在进行/无界面可问）、`ERR_PERMISSION_DENIED`（无身份/
     *   项目号不全）、`ERR_SERVICE_DISABLED`（本机没有投屏通道）、`ERR_IO`（落点不可写）。
     * @throws kotlinx.coroutines.CancellationException 开启过程中被取消 —— **原样传播**，
     *   且已拿到的系统资源已还清。
     */
    suspend fun start(
        consent: ScreenConsentBroker,
        owner: MediaProjectionSessionOwner,
        projectId: ProjectId,
        /**
         * 视频尺寸**提示**（`<= 0` = 让设备层按屏幕真值）。与 `screen.startCapturer` 的
         * 尺寸参数同一条口径：**提示不是承诺** —— 设备层可以忽略它（编码器不接受该尺寸时
         * 必须回退到屏幕真值，否则 `MediaRecorder.prepare()` 直接失败）。
         */
        width: Int = 0,
        height: Int = 0,
    ): RecordingHandle

    /**
     * 收一条录屏会话并 **finalize** 文件。三道校验同 [MediaProjectionCapturer.nextFrame]
     * （id 在场 → 代际一致 → 归属一致）；归属不符**不动手**，如实 `ERR_PERMISSION_DENIED`。
     *
     * **幂等**：第二次调用回同一次 [RecordingOutcome]（见接口 KDoc）。
     * **非挂起**：连接撤销那条路也要能收口（见 [ScreenRecordingSession.stop]）。
     */
    fun stop(sessionId: Long, generation: Long, owner: MediaProjectionSessionOwner): RecordingOutcome

    /**
     * **框架侧收口**（`SCREEN_OFF` 裁剪 / 进程收口）：不问归属，停当前录屏会话并 finalize。
     *
     * **在册句柄不丢**（与取帧腿的 `closeCurrent` 刻意相反）：它们的**产物信息**
     * （路径/大小/完整性）要留着，脚本随后 [stop] 仍答得出（见接口 KDoc）。
     */
    fun closeCurrent()

    /**
     * **连接/执行撤销时的收口**（非挂起）：把该归属名下的录屏会话（正在开的、在册的）
     * 收干净并 finalize，**别人的一概不动**。幂等；无资源时是无害的 no-op。
     *
     * @return true = 确实收掉了什么。
     */
    fun releaseConnection(owner: MediaProjectionSessionOwner): Boolean
}

/**
 * 录屏会话句柄（桥面回包形状与取帧腿同构：`{session:{refId,generation}}` + **`path`**）。
 *
 * [path] 在**开的时候**就回给脚本，不是等到 `stop` 才给：脚本崩了/被掐了的时候，
 * 文件仍会被框架收口 finalize（`ConnectionResourceRegistry`），此时脚本已经问不到
 * `stop` 了 —— 路径若只在 stop 回，那条产物就成了"存在但没人知道在哪"。
 */
data class RecordingHandle(val refId: Long, val generation: Long, val path: String)

/**
 * 本次录屏的上下文（归属 + 落点项目号）—— 从协程上下文里的认证身份取。
 *
 * 为什么不拆成两次取：两者必须来自**同一份**身份快照。分两次读会让"取归属"与
 * "取项目号"之间出现一个可被换掉上下文的窗口，而落点与归属若来自两次读取，
 * 就可能出现"归属是 A、落点是 B 的项目目录"这种没人能解释的组合。
 */
data class ScreenRecordingContext(
    val owner: MediaProjectionSessionOwner,
    val projectId: ProjectId,
)

/**
 * 从协程上下文取录屏上下文（§9.2 安全面）。
 *
 * 两条判据**都要**，缺一即 `ERR_PERMISSION_DENIED`（**绝不放行**）：
 * 1. 有已认证身份（[com.autoscript.domain.bridge.AuthenticatedRunContext]）——
 *    没有身份 = 不是一条可信的脚本请求；
 * 2. 身份上带**非空项目号** —— 没有它就算不出落点，而落点不能从 payload 取
 *    （脚本可影响）。宿主直调若确实要录屏，也必须先经认证点装填项目号。
 */
suspend fun screenRecordingContextOrThrow(): ScreenRecordingContext {
    val ctx = kotlin.coroutines.coroutineContext[com.autoscript.domain.bridge.AuthenticatedRunContext]
        ?: throw com.autoscript.domain.core.AutojsException(
            com.autoscript.domain.core.ErrorCode.ERR_PERMISSION_DENIED,
            "本连接没有已认证的执行身份，不能开启录屏会话",
        )
    // 判据进 `ProjectId`（批 109）：空串与「不是项目号的串」在此**同一处**被拒，
    // 而下游 [MediaProjectionRecorder.start] 收的是类型 —— 拿到类型就意味着它能拼落点。
    // 两句话术合并成一条的后果要照实记：空串现在报的是「不是合法项目号」而不是
    // 「没有项目号」，而空串**本来就是**不是合法项目号 —— 合并没有丢信息，
    // 只是把「认证点没装填」这个原因换成了判据本身的说法。
    val projectId = ProjectId.createOrNull(ctx.projectId)
        ?: throw com.autoscript.domain.core.AutojsException(
            com.autoscript.domain.core.ErrorCode.ERR_PERMISSION_DENIED,
            "本次执行没有可用项目号（认证点未装填或非合法项目号：${ctx.projectId}）：算不出录屏落点，本次拒绝（不套默认项目名）",
        )
    return ScreenRecordingContext(MediaProjectionSessionOwner(ctx.engineId, ctx.engineRunId, ctx.connectionId), projectId)
}
