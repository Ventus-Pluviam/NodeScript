package com.autoscript.appservice.runtime

import com.autoscript.domain.engine.StopResult

/**
 * 运行会话（docs/framework-design.md §8 执行层骨架）：
 * Scheduler 投递成功 → :main 装配出运行会话（一次性体积小，自重后即弃，父进程不持有长引用）。
 * 通过 [quiesce] 显式归还池槽 + 收口 RunContext，让上层不必进来拼 stdio。
 * dispatcher 钩子到位前：默认 [ReleaseOnly]（静默归还），scheduler 侧已有下游断言；接入真实 dispatcher 后替换。
 */
interface RunSession {
    /** 会话标题（看门狗/日志关联）。 */
    val title: String

    /** 展开并执行（内部走 PoolHandle 的 slot 生命周期），幂等：重复启动视为已净。 */
    suspend fun run(): RunSession

    /** 归还池槽 + 收口 RunContext；重复调用幂等。 */
    suspend fun quiesce(): StopResult
}

/** 池侧 run 视图（与 Path 拼接后的产物）：不直接暴露 slot（维度统一由 :main 收敛）。 */
interface SessionView {
    /** 展开后的脚本路径（供执行体分辨机器/用户/打包注入等）。 */
    val expandedScriptPath: String
}

/** 骨架期默认：静默归还；生产替换为实际 dispatcher。 */
class ReleaseOnly : RunSession {
    override val title: String = "ReleaseOnly"
    override suspend fun run(): RunSession = this
    override suspend fun quiesce(): StopResult = StopResult.Clean
}

/**
 * RunContext 收口：重建执行体、停调度、上报事件（:main 侧接入）。
 * [EntryPoint] 是用例视图投影（projectId/scriptPath/args + 幂等 runNonce）：RunSession 的
 * 「展开并执行」只消费这个投影，与持久化 Path 形态解耦（§8：engine 侧要求 EntryPoint 不可见持久化细节）。
 */
data class EntryPoint(
    val projectId: String,
    val scriptPath: String,
    val args: List<String> = emptyList(),
    val runNonce: String? = null,
)

interface RunContext {
    /** 映射缺省路径（缺省 = 不注入 path）。 */
    suspend fun exec(expanded: String, args: List<String>)

    /** 投影：由 [EntryPoint] 构造装配段（scheduler/引擎都只见此投影）。 */
    suspend fun entry(): EntryPoint = EntryPoint(projectId = "", scriptPath = "", args = emptyList())
}