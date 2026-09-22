package com.autoscript.appservice.scheduler.core

import com.autoscript.domain.scripts.EngineRunLink

/**
 * 调度侧控制面句柄（docs §12.3 engines.exec 的调度侧投影）：
 * dispatcher 把「这次投递产生了哪个引擎执行、怎么停它」收敛到这一形状，scheduler 只持有、
 * 不解释 —— 停止归口（§4.1）与双 id 归档（§8.5）都经它落地。
 *
 * - [idLink]：intent runId ↔ 引擎 RunRecord id（:domain 契约；骨架期引擎未分配 = null）；
 * - [stop]：四步 quiesce 的 stop 入口（默认无 = 停不了，如实 false，不假装）。
 */
data class EngineStopHandle(
    val idLink: EngineRunLink?,
    val name: String,
    val runNonce: String? = null,
    val stop: (suspend () -> Unit)? = null,
)
