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