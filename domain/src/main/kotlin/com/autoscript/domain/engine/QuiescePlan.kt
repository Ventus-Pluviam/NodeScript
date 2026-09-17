package com.autoscript.domain.engine

/**
 * 四步 quiesce 计划模型（docs/framework-design.md §8.3）。
 * 纯逻辑：RuntimeController 逐步骤驱动：每步成功推进，超时中止并携带"部分完成"信息。
 * 顺序为契约：SUSPEND（暂停新请求）→ DRAIN_EVENTS（排空事件）→ SOFT_STOP_TIMEOUT（软停等待）→ DESTROY（销毁）。
 */
enum class QuiesceStep { SUSPEND, DRAIN_EVENTS, SOFT_STOP_TIMEOUT, DESTROY }

data class QuiescePlan(
    val steps: List<QuiesceStep> = QuiesceStep.entries,
    val stepTimeoutMillis: Long = 1_500,
) {
    val totalSteps: Int get() = steps.size
    fun stepTimeout(step: QuiesceStep): Long = stepTimeoutMillis
}

/** 执行中的 quiesce 推进状态（进程内单例于一次停止流程，由 RuntimeController 驱动）。 */
class QuiesceRunningState(private val plan: QuiescePlan) {

    /** 最近成功完成的一步；null = 一步都还没完成。 */
    var lastCompleted: QuiesceStep? = null
        private set

    var aborted: Boolean = false
        private set

    /** 记录一步成功。返回下一步该做什么。 */
    fun onStepSuccess(step: QuiesceStep): StepProgress {
        lastCompleted = step
        return when {
            aborted -> StepProgress.ABORTED
            lastCompleted == plan.steps.last() -> StepProgress.COMPLETED
            else -> StepProgress.CONTINUE
        }
    }

    /** 记录一步超时：中止整个 quiesce（partial 语义由 [result] 计算）。 */
    fun onStepTimeout(): StepResult {
        aborted = true
        return result()
    }

    /** 当前汇总结果：干净完成 / 部分完成。 */
    fun result(): StepResult =
        if (!aborted && lastCompleted == plan.steps.last()) StepResult.CLEAN
        else StepResult.PARTIALLY_COMPLETED
}

enum class StepProgress { CONTINUE, COMPLETED, ABORTED }

enum class StepResult { CLEAN, PARTIALLY_COMPLETED }

/** 部分/超时后的兜底配对：调度方对 QuiesceRunningState 的实质判读。 */
fun QuiesceRunningState.toStopResult(): StopResult = when (result()) {
    StepResult.CLEAN -> StopResult.Clean
    StepResult.PARTIALLY_COMPLETED -> StopResult.TimedOut(partial = lastCompleted != null)
}