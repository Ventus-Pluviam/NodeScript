package com.autoscript.appservice.scheduler.core

import com.autoscript.domain.scripts.EngineRunLink

/**
 * RunDispatcher 的返回值（§8.5「…execute… → COMMIT」段的执行真相）：
 * scheduler 需要的不只是结果，还有**这次执行的引擎侧身份** ——
 * - [outcome]：对偶到意图日志 COMMIT 的执行结果（不外泄引擎细节）；
 * - [link]：`intentRunId ↔ engineRunId` 双 id 关联（:domain [EngineRunLink]）。
 *   dispatcher 拿到 EngineRunReceipt 后一次性写两侧（意图日志 + 引擎 RunArchive）；
 *   启动失败/排队超时等**未产生引擎执行**的情形下 [link] 为 null（如实：没有引擎记录可追）。
 */
data class DispatchReport(
    val outcome: RunOutcome,
    val link: EngineRunLink? = null,
)

/**
 * 一次待投递的执行（scheduler 计算好以后交给 [RunDispatcher]；§8.5「…execute…」段）。
 * 引擎池获取/排队语义由 dispatcher 实现承担（runtime 的 EnginePool：定容+排队，绝不静默丢，§8.6）。
 *
 * [intentRunId]：本投递对应的意图日志 runId（RUN_START 行身份，§8.5）。scheduler 在
 * `appendStart` 之后、`dispatch` 之前填权 —— 保证 dispatcher 拿到的一定是**日志已落行**的
 * runId（先写日志后执行）；恢复重投时由新分配的 runId 承担，语义仍成立。
 * 缺省 null = 未挂意图日志的直投（此时 dispatcher 无归档关联可写，如 [DispatchReport.link] 为 null）。
 */
data class PendingRun(
    val projectId: String,
    val scriptPath: String,
    val args: List<String> = emptyList(),
    val runNonce: String,                       // 幂等键：dispatcher 端透传给引擎（EngineRunRequest.runNonce），存储层有唯一兜底
    val trigger: TriggerSource,
    val scheduledAtMillis: Long,                // 意图日志中的 scheduledAt（不再随 delay 漂移）
    val screen: ScreenGuarantee,                // 屏幕契约（§8.6）：SCREEN_ON 需 wakelock+亮屏确认后才投递；SCREEN_OFF 禁画面能力
    val timeoutMillis: Long? = null,            // 脚本自身超时（透传引擎）
    val intentRunId: Long? = null,              // 意图日志 runId（§8.5 归档关联）
)

/**
 * 执行对偶到 [DispatchReport]，由 scheduler 统一 COMMIT，不外泄引擎细节。
 *
 * **屏幕门禁**：实现层（:app 装配，有 WakeLock/亮屏能力）按 [PendingRun.screen] 执行
 * §8.6 守时契约——SCREEN_ON 投递前获取 wakelock 并确认亮屏（失败则如实返回失败 outcome，
 * 不得静默降级）；SCREEN_OFF 裁剪 MediaProjection/画面能力后投递。
 *
 * **RunRecord 归档**：实现层把 [PendingRun.runNonce] / 对应的 [PendingRun.intentRunId]
 * 写入引擎 RunRecord（:domain RunArchive），使意图日志与引擎记录可互相追溯（§8.5 归档入口）。
 */
fun interface RunDispatcher {
    suspend fun dispatch(pending: PendingRun): RunOutcome

    /**
     * 归档用完整回报（§8.5）：与 [dispatch] 的主体必须同源（同一路径、同一结果），
     * 只是额外带回 [DispatchReport.link]。默认实现按 [dispatch] 结果包一个 null link ——
     * 老实现因此保持编译与语义不变，新实现覆写本方法即可让 scheduler 归档双 id 关联。
     */
    suspend fun dispatchToReport(pending: PendingRun): DispatchReport =
        DispatchReport(dispatch(pending), null)
}
