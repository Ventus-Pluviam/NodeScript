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
import com.autoscript.appservice.scheduler.WorkManagerNamespaceHandler
import com.autoscript.bridge.BridgeRouter
import com.autoscript.bridge.ConsoleCollector
import com.autoscript.bridge.EventBus
import com.autoscript.bridge.RequestHandler
import com.autoscript.bridge.NewlineFrameServer
import com.autoscript.bridge.RunIdentityRegistry
import com.autoscript.domain.engine.RunIdentityIssuer
import com.autoscript.bridge.RequestRegistry
import com.autoscript.domain.engine.EngineId
import com.autoscript.domain.bridge.AuthenticatedRunContext
import com.autoscript.domain.bridge.NamespaceHandler
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.engine.ScriptEngine
import com.autoscript.domain.permission.CapabilityMask
import com.autoscript.domain.permission.ScriptAuthorizationPolicy
import com.autoscript.domain.scripts.RunArchive
import com.autoscript.platform.capabilities.CapabilityNamespaces

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
    val frameServer: NewlineFrameServer,
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

    val router: BridgeRouter get() = frameServer.router
    val identities: RunIdentityRegistry get() = frameServer.identities

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

    private var hostLogConnection: AutoCloseable? = null

    /** 激活壳时才接宿主日志；构造另一个壳不得抢走当前壳的诊断输出。 */
    fun connectHostLog() = connectHostLog(HostLog.writer)

    @Synchronized
    internal fun connectHostLog(writer: HostLogWriter) {
        hostLogConnection?.close()
        hostLogConnection = writer.attach(console)
    }

    @Synchronized
    override fun close() {
        hostLogConnection?.close()
        hostLogConnection = null
        frameServer.close()
        identities.close()
        router.close()
    }

    companion object {
        fun assemble(
            engineFactory: (EngineId, RunIdentityIssuer) -> ScriptEngine,
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
             * （真实实现 InstallCoordinator + NpmBridgeHandler 住 :app-service:npm）。
             * null = 未接线，桥对 `npm.*` 如实回 ERR_NOT_IMPLEMENTED（不伪造可用）。
             */
            npmHandler: NamespaceHandler? = null,
            /**
             * `datastore` 命名空间实现（§9.6 KV 面）：与 a11y/screen/npm 同一形态的
             * **独立**注入缝 —— 存储面不与五命名空间共担门禁（应用私有 KV 无需授权），
             * 故不入 [SystemHandlers] 束。实现经 `:platform:capabilities` 的
             * `CapabilityNamespaces.datastore(store)` 转接；null = 未接线，桥如实
             * `ERR_NOT_IMPLEMENTED`（不伪造可用）。
             */
            datastoreHandler: NamespaceHandler? = null,
            /**
             * `zip` 命名空间实现（§9.6 归档面）：同 [datastoreHandler] 的独立缝 ——
             * 归档无需能力门禁，不入 [SystemHandlers] 束。实现经
             * `:platform:capabilities` 的 `CapabilityNamespaces.zip(archiver)` 转接；
             * null = 未接线，桥如实 `ERR_NOT_IMPLEMENTED`。
             */
            zipHandler: NamespaceHandler? = null,
            /**
             * `settings` 命名空间实现（§9.6 系统设置面）：同 [datastoreHandler] 的**独立**缝 ——
             * `WRITE_SETTINGS` 的授权判定在 SPI 自己身上（未授抛 `ERR_PERMISSION_DENIED`），
             * 不与五命名空间共担门禁束，故也不入 [SystemHandlers]。实现经
             * `:platform:capabilities` 的 `CapabilityNamespaces.settings(systemSettings)` 转接；
             * null = 未接线，桥如实 `ERR_NOT_IMPLEMENTED`（不伪造可用）。
             */
            settingsHandler: NamespaceHandler? = null,
            /**
             * `notification` 命名空间实现（§12.2 通知面）：同 [datastoreHandler] 的**独立**缝 ——
             * 通知的门禁是 `POST_NOTIFICATIONS`，判定在 SPI 自己身上（未授抛 `ERR_PERMISSION_DENIED`），
             * 与五命名空间的 OVERLAY/ROOT/ADB_INPUT 不共担，故也不入 [SystemHandlers]。实现经
             * `:platform:capabilities` 的 `CapabilityNamespaces.notification(poster)` 转接；
             * null = 未接线，桥如实 `ERR_NOT_IMPLEMENTED`（不伪造可用）。
             */
            notificationHandler: NamespaceHandler? = null,
            /**
             * `clipboard` 命名空间实现（§12.2 剪贴板面）：同 [datastoreHandler] 的**独立**缝 ——
             * 剪贴板无门禁（读受限是系统的 null 答案、写不受限，判据在 SPI 自己身上），
             * 与五命名空间不共担，故也不入 [SystemHandlers]。实现经
             * `:platform:capabilities` 的 `CapabilityNamespaces.clipboard(clipboard)` 转接；
             * null = 未接线，桥如实 `ERR_NOT_IMPLEMENTED`（不伪造可用）。
             */
            clipboardHandler: NamespaceHandler? = null,
            /**
             * `sensors` 命名空间实现（§12.2 传感器面）：同 [datastoreHandler] 的**独立**缝 ——
             * P0 名单无运行时门禁（未知名→`ERR_NOT_SUPPORTED`、系统拒收→`ERR_SERVICE_DISABLED`
             * 判据在 SPI 自己身上），与五命名空间不共担，故也不入 [SystemHandlers]。实现经
             * `:platform:capabilities` 的 `CapabilityNamespaces.sensors(sensors)` 转接；
             * null = 未接线，桥如实 `ERR_NOT_IMPLEMENTED`（不伪造可用）。
             */
            sensorsHandler: NamespaceHandler? = null,
            /**
             * `images` 命名空间实现（§9.2 图像面）：同 [datastoreHandler] 的**独立**缝 ——
             * 图像面无共担门禁（读图是应用私有目录内的 IO、匹配是纯计算；文件缺失/句柄失效
             * 判据在 SPI 自己身上），与五命名空间不共担，故也不入 [SystemHandlers]。实现经
             * `:platform:capabilities` 的 `CapabilityNamespaces.images(analyzer)` 转接；
             * null = 未接线，桥如实 `ERR_NOT_IMPLEMENTED`（不伪造可用）。
             */
            imagesHandler: NamespaceHandler? = null,
            /**
             * `power_manager` 命名空间实现（§8.7 脚本电源面）：同 [datastoreHandler] 的**独立**缝 ——
             * 电源面无共担门禁（`WAKE_LOCK` 是安装时授予的 normal 权限，判定在账本与系统侧），
             * 不入 [SystemHandlers] 束。生产由 Application 从 `foregroundKeeper()` 的账本现建
             * `PowerManagerNamespaceHandler(...)` 后传入（与 `workManager` 恒挂载不同 ——
             * 调度器是本壳自建的，账本是 Application 持有的进程级单例）；
             * null = 未接线，桥如实 `ERR_NOT_IMPLEMENTED`（不伪造可用）。
             */
            powerManagerHandler: NamespaceHandler? = null,
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
            /**
             * **来源授权策略**（A5，§11）：本壳所有执行拿到什么桥面能力的**唯一**判据来源。
             *
             * 缺省 = `ScriptAuthorizationPolicy()`：没有来源元数据 → `TrustTier.UNKNOWN` 档
             * （[com.autoscript.domain.permission.TrustTierMasks.UNKNOWN_DEFAULT]）。
             * **这是一处真实的行为变化**（2026-10-08 已裁定为「保守档 A」）：跨脚本面
             * （`engines.exec/stop/status/poolStats`）与 `workManager.*` 从此对脚本拒
             * （`ERR_PERMISSION_DENIED`），其余面（a11y/截图/文件/npm/设备面）保持批 74 的全量。
             * 逐条对照见 [com.autoscript.domain.permission.TrustTierMasks] 的 KDoc 表格 ——
             * 别把它读成"保持现行行为"。
             *
             * **这是注入缝，不是全局开关**：生产接真元数据时传一个带
             * [com.autoscript.domain.permission.TrustTierResolver] 的策略，就能**按项目**分级
             * （内置项目全量、市场项目窄档…），而不是被一个全局布尔一刀切。
             * 这一份实例同时喂给 [RunIdentityRegistry] 与 [RuntimeController]（见装配处），
             * 两处各建一份 = 两套矩阵，改了其一静默不一致。
             *
             * **它不改变三态门禁**：掩码只决定「这次执行被授权用哪些桥面」，
             * 系统层给不给（a11y 服务、投屏授权）仍由 `PermissionFacade` 现问系统（§9.5）。
             * 用户在系统里开了投屏/无障碍**不会**给脚本新增掩码位。
             * 两张目录是两个枚举（设备 `Capability` vs 桥面 `BridgeCapability`），别混。
             */
            authorization: ScriptAuthorizationPolicy = ScriptAuthorizationPolicy(),
            /**
             * 便捷覆盖（A5，§11）：非 null 时**所有**执行按它拿掩码，等价于传
             * `ScriptAuthorizationPolicy(override = capabilityMask)` —— 测试/受信直投路径用。
             * 需要**按项目**分级时用 [authorization] 接 `TrustTierResolver`，别用本参数
             * （它是"一刀切"的便利口，不是策略）。
             *
             * **与 [authorization] 同时给时以本参数为准**（实现是
             * `capabilityMask?.let { ScriptAuthorizationPolicy(override = it) } ?: authorization`，
             * 即本参数**整体替换**策略对象，不是叠加）。刻意不做 `require` 禁掉这种组合：
             * [AppShellKit.assemble] 是把两个参数**无条件**转下来的，没法区分"调用方显式传了
             * 缺省策略"与"调用方没传"（Kotlin 缺省实参每次调用新建实例），加了 require 会把
             * 合法的转发路径一并打红。规则就这一条：**给了 [capabilityMask] 就等于放弃
             * [authorization]** —— 要按项目分级就别给前者。
             */
            capabilityMask: CapabilityMask? = null,
        ): AppShell {
            // 便捷覆盖与显式策略二选一：显式给了 capabilityMask 就包一层 override 策略，
            // 否则用调用方给的策略（缺省 = 无来源元数据档）。**一份实例两处用**
            // （身份签发 + controller 的派生授权判据）—— 见 [authorization] 的 KDoc。
            val policy = capabilityMask?.let { ScriptAuthorizationPolicy(override = it) } ?: authorization
            val identities = RunIdentityRegistry()
            val events = EventBus()
            val registry = RequestRegistry()
            val router = BridgeRouter(registry)
            val frameServer = NewlineFrameServer(router, identities)
            var assembled = false
            try {
                val console = ConsoleCollector()
                router.register("console", console)

                val controller = RuntimeController(
                    FixedEnginePool({ id -> engineFactory(id, identities) }, poolCapacity),
                    authorization = policy,
                    // A11：run 终结时收掉那条连接的进程级资源（投屏会话），不再依赖 socket 断
                    // —— 脚本把桥 fd 继承给子进程时主进程死掉不产生 EOF（见缝的 KDoc）。
                    revokeRunResources = frameServer::revokeRunResources,
                )
                val enginesHandler = EnginesNamespaceHandler(controller)
                router.register("engines", enginesHandler)

                // 能力命名空间按挂载缝注入（§4.1/§6）：本层只负责把 handler 挂上 Router，
                // 不 new 具体实现（那需要直连 :platform，被 archUnit 禁止）。缺省不挂 =
                // 未知 namespace → ERR_NOT_IMPLEMENTED（§7.5 Router 契约，诚实上报）。
                if (a11yHandler != null) router.register("a11y", a11yHandler)
                if (screenHandler != null) router.register("screen", screenHandler)
                if (npmHandler != null) router.register("npm", npmHandler)
                if (datastoreHandler != null) router.register("datastore", datastoreHandler)
                if (zipHandler != null) router.register("zip", zipHandler)
                if (settingsHandler != null) router.register("settings", settingsHandler)
                if (notificationHandler != null) router.register("notification", notificationHandler)
                if (clipboardHandler != null) router.register("clipboard", clipboardHandler)
                if (imagesHandler != null) router.register("images", imagesHandler)
                if (sensorsHandler != null) router.register("sensors", sensorsHandler)
                if (powerManagerHandler != null) router.register("power_manager", powerManagerHandler)
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
                //
                // 但**建任务要过授权闸**（A5，§11 派生运行入口）：create 写的是持久任务，
                // 将来每次触发都拉起新执行 —— 那是一次绕过掩码的派生。判据放这里（装配层），
                // 因为它要算子掩码、比调用方掩码，而这两件事只有 `:app` 同时看得见
                // （scheduler 模块的 arch 门禁禁 `domain.permission`）。
                //
                // **判据 = 「自建」与「替别人建」分开**（2026-10-08 批 78，对齐批 75 给
                // `engines.stop/status` 定下的形状）：那一次把「是不是别的执行」的判据从
                // 目录级下沉到 handler，好让窄掩码脚本停得掉**自己**。本闸原先照抄
                // `engines.exec` 的一刀切 —— 无条件要求 `CROSS_SCRIPT_CONTROL`，
                // 于是脚本连**自己的**任务都建不了，而 `workManager` 是 §14 P0 用户故事
                // 明写的闭环之一。实测（探针）：掩码已含 `SCHEDULER_WRITE` 仍被本闸拒，
                // 放开掩码那一半零收益。
                //
                // 现在的判据两条：
                // - **自建**（`caller.projectId` 与目标项目号相同）→ 只要求调用方掩码含
                //   `SCHEDULER_WRITE`（路由闸已经按目录查过，本闸不重复判）。比对用的
                //   [AuthenticatedRunContext.projectId] **由认证点从 lease 装填、不是 wire
                //   字段**（与 capabilityMask 同一条「不可自报」纪律），所以这个相等判断
                //   用的是可信身份，不是脚本自报的值。**空串 = 不知道 → 走跨脚本那条**
                //   （fail closed，不套默认项目名）。
                // - **替别人建** → 回到 `authorizeStart` 的全套（跨脚本位 + 不得提权）。
                // 宿主直投（无认证上下文）= 链的根，放行。
                val authorizeCreate: suspend (String) -> String? = { projectId ->
                    val caller = kotlin.coroutines.coroutineContext[AuthenticatedRunContext]
                    if (caller != null && caller.projectId.isNotBlank() && caller.projectId == projectId) {
                        null
                    } else {
                        try {
                            controller.authorizeStart(caller, projectId)
                            null
                        } catch (e: AutojsException) {
                            e.message
                        }
                    }
                }
                router.register("workManager", WorkManagerNamespaceHandler(scheduler, authorizeCreate))

                // 看门狗：采样器 + 心跳来源在此装配；policy 取 controller 自己那份（单一事实来源，
                //  Threshold 改变只改一处）。缺省 new 一个套在真 controller 上的生产实例。
                val dog = watchdog ?: EngineWatchdog(controller)
                dog.withMonitor(monitor).withHeartbeat(heartbeatMillis ?: { runId -> controller.heartbeatMillis(runId) })

                return AppShell(
                    schedulerProvider = schedulerProvider,
                    frameServer = frameServer,
                    console = console,
                    events = events,
                    controller = controller,
                    enginesHandler = enginesHandler,
                    dispatcher = dispatcher,
                    scheduler = scheduler,
                    intentLog = intentLog,
                    runArchive = runArchive,
                    watchdog = dog,
                ).also { assembled = true }
            } finally {
                if (!assembled) {
                    frameServer.close()
                    identities.close()
                    router.close()
                }
            }
        }
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
 * 工厂产出、经同包 [PlatformWiring] 填入（§6 包级例外二：只有 shell 装配包可直连
 * `:platform`）；装配包之外的 `:app` 类只搬运，一律不 new 具体实现。
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
