package com.autoscript.shell

import com.autoscript.appservice.runtime.PoolAcquireRequest
import com.autoscript.appservice.runtime.RuntimeController
import com.autoscript.appservice.scheduler.core.DefaultDeadlines
import com.autoscript.appservice.scheduler.core.DispatchReport
import com.autoscript.appservice.scheduler.core.PendingRun
import com.autoscript.appservice.scheduler.core.RunDispatcher
import com.autoscript.appservice.scheduler.core.RunOutcome
import com.autoscript.appservice.scheduler.core.TriggerSource
import com.autoscript.domain.engine.KillCause
import com.autoscript.domain.scripts.EngineRunLink

/**
 * 调度→执行装配（docs §8.5/§8.6 checkpoint 意图日志的"…execute…"段 + §4.1 kill 权威）：
 * [Scheduler][com.autoscript.appservice.scheduler.core.Scheduler] 计算好 [PendingRun] 后经此投递，
 * 对偶到 [RunOutcome] 后由 scheduler 统一 COMMIT。
 *
 * 归属说明：住 `:app`（Composition Root），因为它同时需要：
 * - [RuntimeController]（:app-service:runtime，kill 权威持有者）；
 * - [ScreenGate]（WakeLock/亮屏/MediaProjection 会话能力只在 :app 装配层有）；
 * 而 scheduler 的 arch 门禁禁止 scheduler→runtime 直连（见其 ArchitectureTest），
 * 故该接线只能落在 :app。
 *
 * 映射语义（诚实上报，不伪造成功）：
 * - 屏幕门禁拒绝 → [RunOutcome.Failed]（本次未投递；原因由 ScreenGate 实现方日志/UI 呈现）；
 * - 池排队超时 → [RunOutcome.Cancelled]（"排队取消"口径：未获槽，未执行）；
 * - 引擎启动失败 → [RunOutcome.Crashed]（message 保留引擎侧原文）；
 * - 完成等待 StoppedClean → Succeeded；StopTimeout → Failed（软停未净，已 kill 兜底）；
 * - Killed → Crashed；UnknownRun（刚启动即失踪，理论不可达）→ Crashed（防御性如实记）；
 * - 等待超时 → 先 [RuntimeController.killRun]（REQUESTED）再回 Crashed：
 *   铁律"zombie RUNNING 不可构造"——进入 RUNNING 的路径必须绑定一条终结它的时限，
 *   dispatcher 拥有这次 run，就负责不让它悬挂。
 *
 * **归档入口（§8.5）**：拿到 EngineRunReceipt 后就地生成 [EngineRunLink]
 * （intentRunId = [PendingRun.intentRunId]，engineRunId = Receipt.runId）随 [DispatchReport]
 * 返回；未产生引擎执行的门禁拒绝/排队超时/启动失败一律回 null link（如实：没有引擎记录可追），
 * scheduler 据此决定是否写档案 —— 绝不写「有档案、实际没有对应执行」的孤儿记录。
 *
 * 等待上限：[PendingRun.timeoutMillis]（脚本自身超时）优先，否则 [defaultAwaitTimeoutMillis]。
 *
 * 排队上限（§8.6 铁律 3：满池排队也必须有 TTL，绝不无限等）：[queueTimeoutMillis] 显式覆盖优先，
 * 否则按 [DEFAULT_QUEUE_TIMEOUTS] 的触发源分级表取默认值 —— 没有「默认无限等」这条路，
 * 真要例外就在装配处显式传值并自己负责那条路径的时限。
 */
class ControllerRunDispatcher(
    private val controller: RuntimeController,
    private val screenGate: ScreenGate = ScreenGate.AllowAll,
    private val defaultAwaitTimeoutMillis: Long = DEFAULT_AWAIT_TIMEOUT_MILLIS,
    /** 满池排队上限的显式覆盖；null = 按触发源分级取 [queueTimeoutFor]（§8.6 默认有界）。 */
    private val queueTimeoutMillis: Long? = null,
    /** 分级表（§8.6 铁律 3）；测试注入小值，避免为了验边界真等十几秒。 */
    private val queueTimeoutFor: (TriggerSource) -> Long = DEFAULT_QUEUE_TIMEOUTS,
) : RunDispatcher {
    init {
        require(queueTimeoutMillis == null || queueTimeoutMillis > 0) {
            "queueTimeoutMillis 必须 > 0（0 会让每次满池立即取消，疑似漏配）: $queueTimeoutMillis"
        }
    }

    override suspend fun dispatch(pending: PendingRun): RunOutcome = run(pending).outcome

    override suspend fun dispatchToReport(pending: PendingRun): DispatchReport = run(pending)

    private suspend fun run(pending: PendingRun): DispatchReport {
        when (val gate = screenGate.pass(pending.screen)) {
            is ScreenGateDecision.Deny -> return DispatchReport(RunOutcome.Failed, null, null)
            ScreenGateDecision.Proceed -> Unit
        }
        val started = when (
            val s = controller.start(
                PoolAcquireRequest(
                    projectId = pending.projectId,
                    scriptPath = pending.scriptPath,
                    args = pending.args,
                    runNonce = pending.runNonce,
                    scriptTimeoutMillis = pending.timeoutMillis,
                    // 满池排队必有 TTL（铁律 3 / §8.6）：显式覆盖优先，否则按触发源分级。
                    waitTimeoutMillis = queueTimeoutMillis ?: queueTimeoutFor(pending.trigger),
                ),
            )
        ) {
            is RuntimeController.StartOutcome.Started -> s
            RuntimeController.StartOutcome.QueueTimeout -> return DispatchReport(RunOutcome.Cancelled, null, null)
            is RuntimeController.StartOutcome.StartFailed -> return DispatchReport(RunOutcome.Crashed(s.message), null, null)
        }
        // 双 id 关联在此生成（engineRunId = EngineRunReceipt.runId）；intentRunId 缺省（直投时）为 0。
        val link = EngineRunLink(
            intentRunId = pending.intentRunId ?: NO_INTENT_RUN_ID,
            engineRunId = started.runId,
        )
        // 停止入口在 start 成功时即绑定 runId（§4.1 归口 → controller.stop → 池四步 quiesce）。
        // 已结算后调用落到 AlreadyGone（幂等 no-op，不抛）；run 仍在途时走优雅停。
        // 未产生引擎执行的门禁拒绝/排队超时/启动失败三条早退分支回 null stop（如实：无可停的东西）。
        val stop: suspend () -> Unit = { controller.stop(started.runId); Unit }
        val awaitTimeout = pending.timeoutMillis ?: defaultAwaitTimeoutMillis
        return when (controller.awaitCompletion(started.runId, awaitTimeout)) {
            RuntimeController.Completed.StoppedClean -> DispatchReport(RunOutcome.Succeeded, link, stop)
            RuntimeController.Completed.StopTimeout -> DispatchReport(RunOutcome.Failed, link, stop)
            RuntimeController.Completed.Killed ->
                // Completed.Killed 两条来路：自然 CRASHED（脚本抛错/宿主 exit≠0）与真被强杀 ——
                // 措辞必须同时盖住，否则"脚本自己崩了"被日志写成"被强杀"就是撒谎（§1 诚实）。
                DispatchReport(RunOutcome.Crashed("引擎异常结算（崩溃或被强杀）runId=${started.runId}"), link, stop)
            RuntimeController.Completed.UnknownRun ->
                DispatchReport(RunOutcome.Crashed("run 已结算或从未存在 runId=${started.runId}"), link, stop)
            RuntimeController.Completed.TimedOut -> {
                controller.killRun(started.runId, KillCause.REQUESTED)
                DispatchReport(RunOutcome.Crashed("完成等待超时，已强杀 runId=${started.runId}"), link, stop)
            }
        }
    }

    companion object {
        const val DEFAULT_AWAIT_TIMEOUT_MILLIS: Long = 30_000

        /**
         * 满池排队上限的分级表（§8.6「排队上限由投递方给」的落地默认值；铁律 3，值恒 > 0）。
         *
         * 就是 scheduler 侧的 [DefaultDeadlines] 同一引用，不是"另一份相同的数字" ——
         * 两处各写一份的漂移（恢复按一套、排队按另一套）在编译期不可见、现场极难查，
         * 故以别名收敛（分级依据的注释见 [DefaultDeadlines]）：ENGINE_INTERNAL 最紧
         * （嵌套等待先爆）、USER_CLICK 次之（人盯等）、INTENT/EVENT 60s、TIMED 最宽 120s。
         */
        val DEFAULT_QUEUE_TIMEOUTS: (TriggerSource) -> Long = DefaultDeadlines

        /** 未挂意图日志的直投（PendingRun.intentRunId == null）的哨兵值：关联存在但无日志行可追。 */
        const val NO_INTENT_RUN_ID: Long = 0L
    }
}
