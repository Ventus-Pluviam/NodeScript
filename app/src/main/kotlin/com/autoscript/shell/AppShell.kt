package com.autoscript.shell

import com.autoscript.appservice.runtime.EngineWatchdog
import com.autoscript.appservice.runtime.EnginesNamespaceHandler
import com.autoscript.appservice.runtime.ProcessMonitor
import com.autoscript.appservice.runtime.FixedEnginePool
import com.autoscript.appservice.runtime.HeartbeatLedger
import com.autoscript.appservice.runtime.RuntimeController
import com.autoscript.appservice.scheduler.core.InMemoryRunArchive
import com.autoscript.appservice.scheduler.core.IntentLog
import com.autoscript.appservice.scheduler.core.TaskStore
import com.autoscript.appservice.scheduler.core.RecoveryRecord
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
 * - 桥（[BridgeRouter] + [RequestRegistry] + [EventBus]）挂 `console`/`engines`/
 *   `workManager` 命名空间（`workManager` 恒挂载：调度器是本壳自建的，无注入缝）；
 *   `a11y`/`screen` 走 [NamespaceHandler] 挂载缝（见 [assemble] 的
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
    /**
     * 闹钟触发源（与 [scheduler] 是同一份引用）。
     * 留住它是因为能力中心要读生产 provider 的账本（如 `AlarmSchedulerProvider.degradedTasks`
     * 的「可能偏差」标注）—— [Scheduler.provider][com.autoscript.appservice.scheduler.core.Scheduler]
     * 是 private，跨模块加访问器不如在装配层（本类就是装配根）保留同一份。
     */
    val schedulerProvider: SchedulerProvider,
    val intentLog: IntentLog,
    val runArchive: RunArchive,
    /**
     * 看门狗调度循环（§8.4）：周期性把 [ProcessMonitor][com.autoscript.appservice.runtime.ProcessMonitor]
     * 的采样喂给 [controller] 的裁决（[WatchdogPolicy][com.autoscript.appservice.runtime.WatchdogPolicy]），
     * Kill 落 [RuntimeController.killRun]。是否轮转由调用方决定 —— 见 [startWatchdog]。
     */
    val watchdog: EngineWatchdog,
) : AutoCloseable {

    /** 按 namespace 薄转接 handler 到 [BridgeRouter]（本层无逻辑，只做形状适配）。 */
    fun mount(namespace: String, handler: RequestHandler): Boolean =
        router.register(namespace, handler)

    /** 启动看门狗轮转（幂等）。[scope] 的所有权归调用方（Application 的 SupervisorJob 域）。 */
    fun startWatchdog(scope: kotlinx.coroutines.CoroutineScope) = watchdog.start(scope)

    /** 停止看门狗轮转（幂等；不夺调用方 scope 的所有权）。 */
    suspend fun stopWatchdog() = watchdog.stop()

    /**
     * 进程级收口（docs §13 铁律 4：先停调度、再停执行 —— 顺序不可反）。
     *
     * 两步都幂等、可重复调用：
     * 1. `scheduler.quiesceThenStop()` —— 调度侧先 sink（撤销触发器、拒收新投递），
     *    再停最近一次 run（经 `DispatchReport.stop` 填权的 §4.1 归口）；
     * 2. `controller.forceStopAll(cause)` —— 执行侧急停：杀全部非 FREE 槽位并复用，
     *    不重建 guard 请求语义（与 `killAll` 的广播停止区分，见该方法 KDoc）。
     *
     * 顺序的理由：先杀执行再停调度，会在"调度不知情"的时间窗里继续投递 ——
     * 投出去的 run 落到一个正在被清空的池里。先 sink，投递先停，剩下的才是收口。
     *
     * @return 调度侧被停止的句柄（供归档/审计；无句柄/无停止入口时为空，不假装停过）。
     */
    suspend fun shutdown(cause: com.autoscript.domain.engine.KillCause): List<com.autoscript.appservice.scheduler.core.EngineStopHandle> {
        val stopped = scheduler.quiesceThenStop()
        controller.forceStopAll(cause)
        return stopped
    }

    /**
     * 开机恢复（§8.5 崩溃恢复的装配层接线点）：把意图日志里未 COMMIT 的遗留意向
     * 重新入队（`Scheduler.recoverUncommitted`：旧行封口 Interrupted + 新 runId 重开、
     * 保留 runNonce；过期意向封账不重投）。
     *
     * 调用时机 = 壳就绪之后（生产由 Application.install 在 IO 域触发），而不是
     * [assemble] 里：恢复要走 dispatcher 真投递（落引擎 + 写归档），assemble 只做
     * 纯装配、无副作用。恢复前投递的闹钟走漏投记账（AlarmDispatch.missed），
     * 不与这里的重投混在一起 —— 两条路各记各的账。
     *
     * @return 每条遗留的旧/新 runId 与投递结果（供恢复日志/UI 呈现"开机恢复了 N 条"）。
     */
    /**
     * 启动双恢复（§8.6 先排期、§8.5 后意向）：先 [Scheduler.restoreTasks] 把注册表续上
     * （AlarmManager 里还响的闹钟才有人接），再 `recoverUncommitted` 重投遗留意向。
     * 顺序反了不丢数据，但恢复重投的 Once 任务会被续排又注册一次 —— 故在此写死顺序，
     * 调用方（Application.install）只需调这一处。
     */
    suspend fun bootRecover(): List<RecoveryRecord> {
        scheduler.restoreTasks()
        return scheduler.recoverUncommitted()
    }

    override fun close() {
        router.close()
    }

    companion object {
        fun assemble(
            engineFactory: (EngineId) -> ScriptEngine,
            schedulerProvider: SchedulerProvider,
            intentLog: IntentLog,
            runArchive: RunArchive = InMemoryRunArchive(),
            /**
             * 任务注册表持久缝（§8.6 调度持久性）。null = 未接存储：Scheduler 纯内存行为
             * （骨架/单测）。生产由 [AppShellKit][com.autoscript.shell.AppShellKit] 传
             * `FileTaskStore`（与意图日志同一 `.autojs` 目录，同一追加纪律）。
             */
            taskStore: TaskStore? = null,
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
            /**
             * 看门狗的采样器（§8.4 `/proc` 读取缝）。缺省读宿主自己的 `/proc`；
             * 桌面/测试无对应进程时注入替身 —— 看门狗不会因此假装健康。
             */
            monitor: ProcessMonitor = ProcessMonitor(),
            /**
             * 心跳来源（runId → 距上次心跳毫秒；null = 该 run 量不到心跳）。
             *
             * 生产问的是 [HeartbeatLedger]（[RuntimeController.heartbeatMillis]，
             * §8.4 缺口②的宿主侧收单方）：JS 侧经 `engines.heartbeat {runId,seq}` 打点，
             * 账本只认递增序号，`forget` 与 run 终结同生共死。
             *
             * **绝不拿看门狗轮转周期冒充心跳**（那是伪造：`while(true)` 这种「心跳活着、
             * CPU 打满」的形态正是 CPU 外带差分唯一抓得到的，伪造会让它永久失效）。
             * null（缺省）= 不显式指定，装配时换成 controller 的真账本；显式传 `{ null }`
             * = 明确"这一路不接"：看门狗如实记 [EngineWatchdog.Tick.noHeartbeat]，不猜值也不伪造。
             */
            heartbeatMillis: ((Long) -> Long?)? = null,
            watchdog: EngineWatchdog? = null,
            /**
             * `npm` 命名空间实现（§10.8 auto.npm）：与 [a11yHandler]/[screenHandler] 同一注入缝
             * （真实实现 InstallCoordinator + NpmBridgeHandler 住 :app-service:packager）。
             * null = 未接线，桥对 `npm.*` 如实回 ERR_NOT_IMPLEMENTED（不伪造可用）。
             */
            npmHandler: NamespaceHandler? = null,
            /**
             * `dialogs`/`shell`/`device`/`app`/`floatingWindow` 五个命名空间实现（§9.4/§9.6）。
             * 与 [a11yHandler] 同一注入缝，但合成一个参数而非五个：五个命名空间在 §12.2
             * 的 JS facade（`extras.ts`）里是一个整体，且共一批能力门禁（OVERLAY /
             * ROOT / ADB_INPUT），装配层按「接就五个一起接」处理更符合现场。
             *
             * 每个字段 null = 该命名空间未接线，桥如实回 ERR_NOT_IMPLEMENTED。
             * 真实现经 `:platform:capabilities` 的 [com.autoscript.platform.capabilities.CapabilityNamespaces]
             * 工厂产出后注入；`:app` 不 new 具体实现、不直连 `:platform`（§6）。
             */
            systemHandlers: SystemHandlers? = null,
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
            if (npmHandler != null) router.register("npm", npmHandler)
            systemHandlers?.registerAll(router::register)

            val dispatcher = ControllerRunDispatcher(controller, screenGate)
            // §8.6 同源接线：deadline（恢复判过期）与排队上限（dispatcher 在途等多久）
            // 是同一张表（`DEFAULT_QUEUE_TIMEOUTS === DefaultDeadlines`），这里显式喂给两边 ——
            // 缺省参数恰好相同是巧合，写出来才是契约。
            val scheduler = Scheduler(
                schedulerProvider, intentLog, dispatcher, runArchive,
                deadlineFor = ControllerRunDispatcher.DEFAULT_QUEUE_TIMEOUTS,
                taskStore = taskStore,
            )
            // 脚本建任务面（`auto.workManager.*`）：调度器是本壳自建的（与 a11y/screen
            // 注入缝不同 —— 真实现不在 `:platform`），故恒挂载，无注入缝。
            router.register("workManager", WorkManagerNamespaceHandler(scheduler).mount())

            // 看门狗：采样器 + 心跳来源在此装配；policy 取 controller 自己那份（单一事实来源，
            //  Threshold 改变只改一处）。缺省 new 一个套在真 controller 上的生产实例。
            val dog = watchdog ?: EngineWatchdog(controller)
            dog.withMonitor(monitor).withHeartbeat(heartbeatMillis ?: { runId -> controller.heartbeatMillis(runId) })

            return AppShell(
                schedulerProvider = schedulerProvider,
                router = router,
                console = console,
                events = events,
                controller = controller,
                enginesHandler = enginesHandler,
                dispatcher = dispatcher,
                scheduler = scheduler,
                intentLog = intentLog,
                runArchive = runArchive,
                watchdog = dog,
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

/**
 * `dialogs`/`shell`/`device`/`app`/`floatingWindow` 五个命名空间的注入束（§9.4/§9.6/§12.2）。
 *
 * 合成一个类型而不是五个 `NamespaceHandler?` 参数：五个命名空间在 JS facade（`extras.ts`）
 * 里是一个整体、共一批能力门禁（OVERLAY/ROOT/ADB_INPUT），装配层按「接就五个一起接」
 * 处理；[registerAll] 逐个 null 检查，缺哪个就哪个如实 ERR_NOT_IMPLEMENTED（§7.5）。
 *
 * 字段由 `:platform:capabilities` 的 [com.autoscript.platform.capabilities.CapabilityNamespaces]
 * 工厂产出后填入；`:app` 只搬运，不 new 具体实现、不直连 `:platform`（§6）。
 */
data class SystemHandlers(
    val dialogs: NamespaceHandler? = null,
    val shell: NamespaceHandler? = null,
    val device: NamespaceHandler? = null,
    val app: NamespaceHandler? = null,
    val floatingWindow: NamespaceHandler? = null,
) {
    /** 逐个挂 Router；null 项跳过（未接线 → Router 的未知 namespace 路径，如实未实现）。 */
    fun registerAll(register: (String, NamespaceHandler) -> Boolean) {
        if (dialogs != null) register("dialogs", dialogs)
        if (shell != null) register("shell", shell)
        if (device != null) register("device", device)
        if (app != null) register("app", app)
        if (floatingWindow != null) register("floatingWindow", floatingWindow)
    }
}
