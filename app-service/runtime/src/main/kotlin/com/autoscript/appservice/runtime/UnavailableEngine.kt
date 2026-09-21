package com.autoscript.appservice.runtime

import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.engine.EngineId
import com.autoscript.domain.engine.EngineRunReceipt
import com.autoscript.domain.engine.EngineRunRequest
import com.autoscript.domain.engine.EngineStatus
import com.autoscript.domain.engine.KillCause
import com.autoscript.domain.engine.ScriptEngine
import com.autoscript.domain.engine.StopResult

/**
 * 未接线引擎宿主（docs §19 native/NDK 切片：`:engine:node-process` 尚未把进程宿主送出来）。
 *
 * **为什么要有这个类**：生产的 `AppShell.assemble` 需要一个 engineFactory，而没有它整个壳
 * 就装不起来 —— 装了壳才有意图日志、闹钟回投、开机恢复、运行档案、npm 命名空间。为了一个
 * 尚未落地的引擎实现把其余全部推迟，代价是**开机什么都不发生**：闹钟响了没人接、任务中心
 * 一条记录都查不到、`bootRecover` 永远不会被触发。那比"跑不起来"更难查，因为它不报错。
 *
 * 于是这里给一个**契约诚实**的宿主：它不伪造任何执行，[execute] 按 §1 诚实原则直接抛
 * [AutojsException]（`ERR_NOT_IMPLEMENTED` + 可操作话术），由 [FixedEnginePool.acquire]
 * 的启动失败路径接住 → `PoolAcquireOutcome.Failed(message)` → `StartFailed` →
 * [com.autoscript.appservice.scheduler.core.RunOutcome.Crashed]：真原因进意图日志
 * （COMMIT 行）与运行档案（RUNNING/CRASHED 记录），任务中心按 runId 读得到"为何没跑"。
 *
 * **记账不失真（§8.2 推论 B）**：execute 抛错 → 池 `recycle(slot)` → 槽位复位 + 许可证归还
 * （调用方若取消则走同一收归路径），槽位可再次夺用。`UnavailableEngineTest` 对这条路径
 * 单独走一遍「在途表摘除 + 槽位复位 + 许可证归还」三件事。
 *
 * [pid] 恒 null：§8.4 明令宿主不给 pid 时如实回 null（**绝不给 0/自身 pid**），看门狗因此
 * 记 `noPid`。这与「引擎在跑但 /proc 读不到」是两条不同的清单，不混为一谈。
 *
 * **不是假替身**：真实现到了之后本类不会被悄悄换掉 —— engineFactory 是
 * [com.autoscript.shell.AppShellKit] 的显式参数，换实现 = 改装配处那一行。
 */
class UnavailableEngine(override val id: EngineId) : ScriptEngine {

    override val pid: Int? = null

    override suspend fun execute(run: EngineRunRequest): EngineRunReceipt =
        throw AutojsException(
            ErrorCode.ERR_NOT_IMPLEMENTED,
            "引擎宿主未接入（:engine:node-process native 侧待落地）：本次执行未发生" +
                "（script=${run.scriptPath}" +
                (run.runNonce?.let { ", runNonce=$it" } ?: "") + "）",
        )

    /**
     * 无执行体可停：如实回 [StopResult.Clean]。
     *
     * 这不是"假装优雅退出"——从未启动过执行体，没有东西需要排空，「已净」是事实陈述。
     * 四步 quiesce 的语义在池侧（[PoolSlot.quiesce]）照走，只是每一步都无事可做。
     */
    override suspend fun stop(): StopResult = StopResult.Clean

    /**
     * 无进程可杀：回 [KillCause.REQUESTED] 表示"调用方主动要求，且已无残留"。
     *
     * 池侧的终态归类用的是**调用方**传的 cause（[EnginePool.recycle] 的 cause 参数），
     * 本返回值只供诊断，不会被当成仲裁依据。
     */
    override suspend fun kill(): KillCause = KillCause.REQUESTED

    /** 从未启动过执行体：恒 [EngineStatus.IDLE]（回 STOPPED 会暗示"跑过并停了"，是撒谎）。 */
    override suspend fun status(): EngineStatus = EngineStatus.IDLE
}
