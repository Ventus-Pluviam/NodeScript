package com.autoscript.domain.scripts

/**
 * 引擎运行档案（docs/framework-design.md §8.2 归档 + §8.5 归档入口 + §14 Repository 模式）：
 * [RunRecord] 是「一次执行」在引擎侧的唯一事实源，任务中心/UI 只经此仓库读执行历史，
 * 不直连引擎池。
 *
 * 与 scheduler 意图日志（IntentLog）的分工：
 * - 意图日志回答「这次投递为什么发生、结果如何」——幂等锚点 runNonce，恢复只跟随 COMMIT；
 * - 本档案回答「引擎侧那条执行跑成了什么样」——engine runId、起止时刻、终态。
 * - 两侧身份由 [EngineRunLink] **成对写入**（§8.5）：只写一侧会变成「引擎在跑、任务中心
 *   查不到」或「有档案、实际没有对应执行」的孤儿记录。
 *
 * 状态纪律（与意图日志同构的 append-only）：非终态（PENDING/RUNNING）可前进，终态
 * （SUCCEEDED/FAILED/CRASHED/CANCELLED）落地即**不可改写、不可复活**。[put] 遇到违反该
 * 纪律的写入必须**响亮失败**（= 调用方 bug），绝不静默吞掉。
 *
 * 同 engineRunId 的写入可两路：
 * - **登记**：档案中还没有该 run —— 落 PENDING/RUNNING 起步；
 * - **结算**（§8.5 的 COMMIT 对偶）：档案里已有该 run 且在途 —— 前进到终态。
 * 两条路径都在 [put] 内原子裁定。
 */
interface RunArchive {

    /**
     * 写入/前进一条执行记录。
     * @param link 与 [RunRecord.id]（engineRunId）成对的双 id 关联；同 engineRunId 重复携带
     *   相同 link 幂等，携带不同 link 拒绝（关联一旦成立不允许改写）。
     * @throws IllegalArgumentException link.engineRunId 与 record.id 不一致
     * @throws IllegalStateException 终态被改写/复活（违反归档状态机）
     */
    suspend fun put(record: RunRecord, link: EngineRunLink? = null): RunRecord

    /** 按 engine runId 取记录（不在档 → null）。 */
    suspend fun record(engineRunId: Long): RunRecord?

    /** 按 engine runId 取双 id 关联（未建档或无关联 → null）。 */
    suspend fun link(engineRunId: Long): EngineRunLink?

    /** 按意图日志 runId 反查引擎记录（UI/任务中心按 IntentRun 追溯，§8.5）。 */
    suspend fun recordsOfIntent(intentRunId: Long): List<RunRecord>

    /** 按项目反查（任务中心列表）。 */
    suspend fun recordsOfProject(projectId: String): List<RunRecord>

    /** 尚未终态化的记录（启动时核对「档案 RUNNING 但引擎已不在途」的孤儿清理输入）。 */
    suspend fun unfinished(): List<RunRecord>
}

/** 终态判定（§8.2）：终态落地即不可改写、不可复活。 */
val RunState.isTerminal: Boolean
    get() = this == RunState.SUCCEEDED ||
        this == RunState.FAILED ||
        this == RunState.CRASHED ||
        this == RunState.CANCELLED
