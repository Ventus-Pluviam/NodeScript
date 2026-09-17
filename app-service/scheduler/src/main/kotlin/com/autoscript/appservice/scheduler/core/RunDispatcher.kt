package com.autoscript.appservice.scheduler.core

/**
 * 一次待投递的执行（scheduler 计算好以后交给 [RunDispatcher]；§8.5「…execute…」段）。
 * 引擎池获取/排队语义由 dispatcher 实现承担（runtime 的 EnginePool：定容+排队，绝不静默丢，§8.6）。
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
)

/**
 * 执行对偶到 [RunOutcome]，由 scheduler 统一 COMMIT，不外泄引擎细节。
 *
 * **屏幕门禁**：实现层（:app 装配，有 WakeLock/亮屏能力）按 [PendingRun.screen] 执行
 * §8.6 守时契约——SCREEN_ON 投递前获取 wakelock 并确认亮屏（失败则如实返回失败 outcome，
 * 不得静默降级）；SCREEN_OFF 裁剪 MediaProjection/画面能力后投递。
 *
 * **RunRecord 归档**：实现层把 [PendingRun.runNonce] / 对应的 intent-log runId 写入引擎
 * RunRecord（:domain），使意图日志与引擎记录可互相追溯（§8.2 归档入口）。
 */
fun interface RunDispatcher {
    suspend fun dispatch(pending: PendingRun): RunOutcome
}