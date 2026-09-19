package com.autoscript.shell

import com.autoscript.appservice.runtime.EnginesNamespaceHandler
import com.autoscript.appservice.runtime.FixedEnginePool
import com.autoscript.appservice.runtime.RuntimeController
import com.autoscript.appservice.scheduler.core.InMemoryRunArchive
import com.autoscript.appservice.scheduler.core.IntentLog
import com.autoscript.appservice.scheduler.core.Scheduler
import com.autoscript.appservice.scheduler.core.SchedulerProvider
import com.autoscript.bridge.BridgeRouter
import com.autoscript.bridge.ConsoleCollector
import com.autoscript.bridge.EventBus
import com.autoscript.bridge.RequestHandler
import com.autoscript.bridge.RequestRegistry
import com.autoscript.domain.bridge.BridgeRequest
import com.autoscript.domain.bridge.BridgeResponse
import com.autoscript.domain.engine.EngineId
import com.autoscript.domain.bridge.NamespaceHandler
import com.autoscript.domain.engine.ScriptEngine
import com.autoscript.domain.scripts.RunArchive

/**
 * :app 装配根（docs §4.1 Composition Root，手写 DI，不用 Hilt）。
 *
 * 组装 P0 最小闭环的全部接线：
 * - 引擎池（[FixedEnginePool]，P0 定容 1）+ [RuntimeController]（kill 权威）；
 * - 调度（[Scheduler] + [IntentLog] + [SchedulerProvider]）经 [ControllerRunDispatcher]
 *   落到 controller（scheduler arch 门禁禁止 scheduler→runtime 直连，接线只能在此）；
 * - **归档（§8.5）**：dispatcher 产出的 [com.autoscript.domain.scripts.EngineRunLink]
 *   （intentRunId ↔ engineRunId）与 :domain `RunRecord` 一并写入 [RunArchive]，
 *   使意图日志行与引擎执行可互相追溯（任务中心/UI 按 IntentRun 读引擎记录）；
 * - 桥（[BridgeRouter] + [RequestRegistry] + [EventBus]）挂 `console`/`engines`
 *   命名空间；`a11y`/`screen` 走 [NamespaceHandler] 挂载缝（见 [assemble] 的
 *   [a11yHandler]/[screenHandler] 注入说明）。
 *
 * Android 能力缝（[engineFactory]/[schedulerProvider]/[screenGate]/[runArchive]）由
 * Application/Activity 在此注入；JVM 单测走 [assemble] 传 fake。
 */
class AppShell(
    val router: BridgeRouter,
    val console: ConsoleCollector,
    val events: EventBus,
    val controller: RuntimeController,
    val enginesHandler: EnginesNamespaceHandler,
    val dispatcher: ControllerRunDispatcher,
    val scheduler: Scheduler,
    val intentLog: IntentLog,
    val runArchive: RunArchive,
) : AutoCloseable {

    /** 按 namespace 薄转接 handler 到 [BridgeRouter]（本层无逻辑，只做形状适配）。 */
    fun mount(namespace: String, handler: RequestHandler): Boolean =
        router.register(namespace, handler)

    override fun close() {
        router.close()
    }

    companion object {
        fun assemble(
            engineFactory: (EngineId) -> ScriptEngine,
            schedulerProvider: SchedulerProvider,
            intentLog: IntentLog,
            runArchive: RunArchive = InMemoryRunArchive(),
            screenGate: ScreenGate = ScreenGate.AllowAll,
            poolCapacity: Int = 1,
            /**
             * `a11y` 命名空间实现（§9.1）。注入缝：实现在 `:platform:capabilities`，
             * 而 `:app` 禁止直连 `:platform`（§6，archUnit 强制），故由持有实现的
             * Android 侧（Application/Activity）在调用 [assemble] 时传入；
             * null = 未接线，桥对 `a11y.*` 如实回 ERR_NOT_IMPLEMENTED（不伪造可用）。
             */
            a11yHandler: NamespaceHandler? = null,
            /** `screen` 命名空间实现（§9.2）；同 [a11yHandler] 的注入缝。 */
            screenHandler: NamespaceHandler? = null,
        ): AppShell {
            val events = EventBus()
            val registry = RequestRegistry()
            val router = BridgeRouter(registry)
            val console = ConsoleCollector()
            router.register("console", console)

            val controller = RuntimeController(FixedEnginePool(engineFactory, poolCapacity))
            val enginesHandler = EnginesNamespaceHandler(controller)
            router.register("engines") { request -> enginesHandler.handleLike(request) }

            // 能力命名空间按挂载缝注入（§4.1/§6）：本层只负责把 handler 挂上 Router，
            // 不 new 具体实现（那需要直连 :platform，被 archUnit 禁止）。缺省不挂 =
            // 未知 namespace → ERR_NOT_IMPLEMENTED（§7.5 Router 契约，诚实上报）。
            if (a11yHandler != null) router.register("a11y", a11yHandler)
            if (screenHandler != null) router.register("screen", screenHandler)

            val dispatcher = ControllerRunDispatcher(controller, screenGate)
            val scheduler = Scheduler(schedulerProvider, intentLog, dispatcher, runArchive)

            return AppShell(
                router = router,
                console = console,
                events = events,
                controller = controller,
                enginesHandler = enginesHandler,
                dispatcher = dispatcher,
                scheduler = scheduler,
                intentLog = intentLog,
                runArchive = runArchive,
            )
        }
    }
}

/** engines handler 用自有 Request/Response 形状；桥接层做字段级转接（无逻辑）。 */
private suspend fun EnginesNamespaceHandler.handleLike(request: BridgeRequest): BridgeResponse {
    return when (
        val r = handle(
            EnginesNamespaceHandler.Request(
                id = request.id,
                method = request.method,
                payload = request.payload,
                ttlMillis = request.ttlMillis,
            ),
        )
    ) {
        is EnginesNamespaceHandler.Response.Ok ->
            BridgeResponse.Ok(r.id, r.payload)
        is EnginesNamespaceHandler.Response.Err ->
            BridgeResponse.Err(r.id, r.code, r.detail)
    }
}
