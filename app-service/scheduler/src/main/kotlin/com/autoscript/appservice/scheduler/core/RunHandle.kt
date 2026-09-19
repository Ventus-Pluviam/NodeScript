package com.autoscript.appservice.scheduler.core

import com.autoscript.domain.scripts.EngineRunLink

/**
 * 引擎执行句柄 —— scheduler 侧的控制面投影（docs/framework-design.md §7.4 + §8.5 + §12.3）：
 * 控制面（engine id / 停止 / 通道引用）由引擎宿主实现，本类只是 scheduler 侧看到的投影；
 * `:app` 装配层在 dispatcher 落地时用实际引擎实现填权（默认不做任何事）。
 * [idLink] 是「intent runId ↔ 引擎 RunRecord id」的双 id 关联（:domain 契约，§8.5）；
 * [stopCallback] 四步 quiesce 的 stop 入口（骨架期默认无）。
 */
data class EngineExecutionHandle(
    val idLink: EngineRunLink?,       // intent↔engine 双 id 关联（§8.5 归档入口；骨架期可为 null）
    val name: String,                 // 脚本运行名（控制台/日志关联）
    val runNonce: String? = null,     // 调度幂等锚点（透传引擎）
    val stopCallback: (suspend () -> Unit)? = null,   // 四步 quiesce 的 stop 入口（默认无）
)