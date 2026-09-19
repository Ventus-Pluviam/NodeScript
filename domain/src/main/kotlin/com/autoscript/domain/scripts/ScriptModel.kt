package com.autoscript.domain.scripts

/**
 * 脚本项目/资源模型（docs/framework-design.md §9.6）。
 * filesDir 布局：files/scripts/<projectId>/；资产经 assets→filesDir 原子部署（tmp + sha256 + rename）。
 */
data class ScriptProject(
    val id: String,
    val name: String,
    val version: Int = 1,
    val mainScript: String,          // 相对 project root 的入口文件名（如 "main.js"）
    val createdAtMillis: Long,
)

/** 项目内资源：脚本/图片/模型等一律按资源建模，带哈希以支持校验与增量。 */
data class ScriptResource(
    val projectId: String,
    val relPath: String,             // 相对项目 root
    val sha256: String,
    val sizeBytes: Long,
    val kind: ResourceKind,
)

enum class ResourceKind { SCRIPT, IMAGE, MODEL, DATA, OTHER }

/** 一次执行记录（scheduler 的 runNonce 幂等锚点，§14 P0）。 */
data class RunRecord(
    val id: Long,
    val projectId: String,
    val scriptPath: String,
    val runNonce: String,            // 幂等键：同一 nonce 不重复投递
    val state: RunState,
    val startedAtMillis: Long? = null,
    val finishedAtMillis: Long? = null,
)

enum class RunState { PENDING, RUNNING, SUCCEEDED, FAILED, CRASHED, CANCELLED }
/**
 * 一次执行在两端寄存器的关联合约（docs/framework-design.md §8.5）：两套 runId 是
 * **一个真值的两个投影，必须成对写入** —— 只写一侧会变成「引擎在跑，任务中心查不到」
 * 或「有档案，实际没有对应执行」的孤儿记录。
 *
 * | 侧 | 身份字段 | 寄存器 |
 * |---|---|---|
 * | 意图日志（scheduler） | intentRunId（IntentRun.runId） | sqlite intent log |
 * | 引擎运行记录（engine） | engineRunId（EngineRunReceipt.runId） | RunRecord(id) |
 *
 * 关联时机：dispatcher 实现（:app 装配层）拿到 EngineRunReceipt 后一次性写两侧；
 * UI/任务中心按 intent 追溯引擎记录（反过来也可以）。
 */
data class EngineRunLink(
    val intentRunId: Long,           // 意图日志 runId（§8.5 RUN_START 行身份）
    val engineRunId: Long,           // 引擎 RunRecord 身份（EngineRunReceipt.runId）
)
