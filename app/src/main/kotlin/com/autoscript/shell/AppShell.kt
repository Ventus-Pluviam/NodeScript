package com.autoscript.shell

import com.autoscript.appservice.runtime.EnginesNamespaceHandler
import com.autoscript.appservice.runtime.FixedEnginePool
import com.autoscript.appservice.runtime.RuntimeController
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
import com.autoscript.domain.engine.ScriptEngine

/**
 * :app 装配根（docs §4.1 Composition Root，手写 DI，不用 Hilt）。
 *
 * 组装 P0 最小闭环的全部接线：
 * - 引擎池（[FixedEnginePool]，P0 定容 1）+ [RuntimeController]（kill 权威）；
 * - 调度（[Scheduler] + [IntentLog] + [SchedulerProvider]）经 [ControllerRunDispatcher]
 *   落到 controller（scheduler arch 门禁禁止 scheduler→runtime 直连，接线只能在此）；
 * - 桥（[BridgeRouter] + [RequestRegistry] + [EventBus]）挂 `console`/`engines`
 *   命名空间（a11y/images 等能力 handler 由真实现就绪后在此注册）。
 *
 * Android 能力缝（[engineFactory]/[schedulerProvider]/[screenGate]）由 Application/
 * Activity 在此注入；JVM 单测走 [forTest] 传 fake。
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
            screenGate: ScreenGate = ScreenGate.AllowAll,
            poolCapacity: Int = 1,
        ): AppShell {
            val events = EventBus()
            val registry = RequestRegistry()
            val router = BridgeRouter(registry)
            val console = ConsoleCollector()
            router.register("console", console)

            val controller = RuntimeController(FixedEnginePool(engineFactory, poolCapacity))
            val enginesHandler = EnginesNamespaceHandler(controller)
            router.register("engines") { request -> enginesHandler.handleLike(request) }

            val dispatcher = ControllerRunDispatcher(controller, screenGate)
            val scheduler = Scheduler(schedulerProvider, intentLog, dispatcher)

            return AppShell(
                router = router,
                console = console,
                events = events,
                controller = controller,
                enginesHandler = enginesHandler,
                dispatcher = dispatcher,
                scheduler = scheduler,
                intentLog = intentLog,
            )
        }
    }
}

/** engines handler 用自有 Request/Response 形状；桥接层做字段级转接（无逻辑）。 */
private suspend fun EnginesNamespaceHandler.handleLike(request: BridgeRequest): BridgeResponse {
    return when (
        val r = handle(EnginesNamespaceHandler.Request(request.id, request.method, request.payload))
    ) {
        is EnginesNamespaceHandler.Response.Ok ->
            BridgeResponse.Ok(r.id, r.payload)
        is EnginesNamespaceHandler.Response.Err ->
            BridgeResponse.Err(r.id, r.code, r.detail)
    }
}
