package com.autoscript.shell

import com.autoscript.appservice.packager.NpmShellKit
import com.autoscript.appservice.runtime.EngineWatchdog
import com.autoscript.appservice.runtime.ProcessMonitor
import com.autoscript.appservice.runtime.UnavailableEngine
import com.autoscript.appservice.scheduler.core.SchedulerProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import com.autoscript.appservice.scheduler.persist.FileRunArchive
import com.autoscript.appservice.scheduler.persist.FileTaskStore
import com.autoscript.appservice.scheduler.persist.JournalFileStore
import com.autoscript.appservice.scheduler.persist.PersistentIntentLog
import com.autoscript.appservice.scheduler.recovery.ScriptDeployRecovery
import com.autoscript.appservice.scriptrepo.core.BridgeAddonDeploy
import com.autoscript.appservice.scriptrepo.core.BridgeDistDeploy
import com.autoscript.domain.bridge.NamespaceHandler
import com.autoscript.domain.engine.EngineId
import com.autoscript.domain.engine.ScriptEngine
import com.autoscript.domain.host.TaskCenterSnapshot
import com.autoscript.domain.host.ConsoleSnapshot
import com.autoscript.domain.host.TaskRegistration
import com.autoscript.appservice.scheduler.core.TriggerSource
import com.autoscript.domain.scripts.RunArchive
import com.autoscript.domain.scripts.RunRecord
import java.nio.file.Files
import java.nio.file.Path

/**
 * Android 侧装壳配方（docs/framework-design.md §4.1 Composition Root 的**真调用点**）。
 *
 * 为什么单独一个文件而不是写在 [com.autoscript.AppShellApplication] 里：装壳要碰
 * `:app-service:*` 五个模块的目录约定（意图日志 / 运行档案 / npm 三份 `.autojs` 与
 * `cacheDir` 布局）与三个注入缝（能力 handler、闹钟 provider、屏幕门禁）。这些是
 * **装配知识**，不是 Application 生命周期知识；分开之后 [AppShell.assemble] 的每个参数
 * 在这里都能指着一段可测代码，而不是散在 `onCreate` 的几十行里。
 *
 * 三条纪律：
 * - **engineFactory 是参数**：`UnavailableEngine` 只是"native 宿主尚未落地"时的诚实缺省
 *   （见其 KDoc），真实现到位 = 改调用处那一行，不在本文件里留分支；
 * - **能力 handler 由调用方给**：生产由 [PlatformWiring]（同在 shell 装配包，§6 包级例外二）
 *   把 `SystemSpis` + `CapabilityNamespaces` 拼成注入束喂进来，本文件只转交给
 *   [AppShell.assemble]、不 new 实现（缝的类型住 `:domain`，本文件保持纯 JVM 可测）；
 *   null = 未接线，桥如实回 `ERR_NOT_IMPLEMENTED`；
 * - **本文件不 new 任何能力实现**，也不碰 `:bridge:java`（只有 `com.autoscript.shell.AppShell`
 *   一个类可以，见 `ArchitectureTest`；本文件只用 `:domain` 的 [NamespaceHandler] 接口）。
 *
 * 装配产物 [AssembledShell] 把壳与它自己的持久句柄捆在一起：`PersistentIntentLog` /
 * `FileRunArchive` 各持一个 `FileChannel`，[AssembledShell.close] 负责成对释放 ——
 * 让 Application 自己去记"这两个也要关"必然会漏。它同时是**读口**（[AssembledShell.runsOf] /
 * [AssembledShell.runRecord] / [AssembledShell.unfinishedRuns]）：任务中心要的
 * "某项目的执行历史 / 这次为何没跑 / 有没有没结算的执行" 全在档案里，不必让 UI 自己
 * 再开一个 `FileRunArchive`（第二个实例会各自持 channel 与内存视图，写侧两份即失真）。
 */
object AppShellKit {

    /**
     * 壳自己的协程域（看门狗轮转住这里）。
     *
     * 为什么由配方持有而不是让 Application 传一个：[EngineWatchdog.start] 挂上之后，
     * 「谁负责停」必须有着落，否则要么泄漏（进程级 GlobalScope）、要么停不掉。这里用
     * [SupervisorJob] + 壳自己的 close 收口：**子任务失败不牵连壳**（看门狗一轮采样抛错
     * 不能让调度也停），关壳即停轮转。调用方要换成自己的域（如 UI 生命周期域）时传
     * [assemble] 的 `watchdogScope`。
     */
    internal class ShellScope : CoroutineScope {
        override val coroutineContext = SupervisorJob() + kotlinx.coroutines.Dispatchers.Default
        val job get() = coroutineContext[kotlinx.coroutines.Job]!!
        fun cancel() = job.cancel()
    }

    /** 装壳产物：壳 + 随壳创建的持久句柄 + 看门狗域（关壳即全部收口）。 */
    class AssembledShell internal constructor(
        val shell: AppShell,
        private val log: PersistentIntentLog,
        private val archive: RunArchive,
        private val tasks: FileTaskStore?,
        /** npm 装配产出的 handler（装配测试/诊断用；null 表示本次装配未挂 npm）。 */
        val npmHandler: NamespaceHandler?,
        private val scope: ShellScope?,
        /**
         * 装配期脚本补部署报告（§9.6 `files/scripts/<projectId>/` 标准化）。
         * 空报告（`deployed` 与 `failures` 皆空）= 没有来源可补，不是"恢复成功"
         * —— 装配层日志/能力中心不得把它说成"脚本已恢复"。
         */
        val deployReport: ScriptDeployRecovery.Report = ScriptDeployRecovery.Report(),
        /**
         * 装配期 facade dist 落位报告（§12.4 资产交付轨：`assets/bridge-dist/` →
         * `filesDir/node_modules/auto/`）。空报告 = 本次没货可落（**不是**"facade 已就位"）；
         * [BridgeDistDeploy.Report.failures] 非空 = 有文件没落上 + 孤儿未清（见其 KDoc 边界）。
         */
        val bridgeDistReport: BridgeDistDeploy.Report = BridgeDistDeploy.Report(),
        /**
         * 装配期 bridge addon 落位报告（§19 交付轨：`assets/bridge-addon/` →
         * `filesDir/lib/bridge_native.node`）。空报告（`deployed` false 且无 failure）
         * = 本次没货可落（**不是**"addon 已就位"）；引擎侧 `addonPath` 缺文件即降级
         * 不注入（选填纪律），桥调用点如实 `ERR_ENGINE_STOPPED`。
         */
        val bridgeAddonReport: BridgeAddonDeploy.Report = BridgeAddonDeploy.Report(),
    ) : AutoCloseable {
        /** 没补上的脚本（路径 + 原因；能力中心呈现"有脚本没补上"，不吞成一切正常）。 */
        fun deployFailures(): List<ScriptDeployRecovery.Failure> = deployReport.failures

        /** 没落上的 facade 文件（路径 + 原因；`require('auto')` 会因此解析不到）。 */
        fun bridgeDistFailures(): List<BridgeDistDeploy.Failure> = bridgeDistReport.failures

        /** addon 没落上的原因（null = 本次没货或已就位；非空 = 来源/写入失败原文）。 */
        fun bridgeAddonFailure(): String? = bridgeAddonReport.failure
        override fun close() {
            scope?.cancel()          // 先停看门狗轮转，再关壳/持久句柄（轮转中不得关底下的池）
            shell.close()
            (archive as? AutoCloseable)?.close()
            tasks?.close()           // 注册表 channel：与意图日志同一目录、同一追加纪律
            log.close()
        }

        /**
         * 未终态的运行记录（`RunArchive.unfinished`，§8.5）。
         *
         * 生产实现里**不该有**：`Scheduler.recordLink` 落档案即终态、恢复路径两条孤儿结算
         * （逐 intent + 跨 intent 空档）负责补账。非空 = 上面两条里有一条没走完 ——
         * UI 据此如实显示"有执行没结算"，而不是把 RUNNING 记录当成"正在跑"
         * （那正是 `unfinished()` 只增不减的那种谎）。
         */
        suspend fun unfinishedRuns(): List<RunRecord> = archive.unfinished()

        /** 某项目的执行历史（任务中心读口；`recordsOfProject` 的对偶）。 */
        suspend fun runsOf(projectId: String): List<RunRecord> = archive.recordsOfProject(projectId)

        /** 某次执行的终态（null = 无此记录；UI 按 runId 读"为何没跑"的落点）。 */
        suspend fun runRecord(engineRunId: Long): RunRecord? = archive.record(engineRunId)

        /**
         * 任务中心快照（§8.6 排期 + §8.5 档案/恢复账）—— 呈现层经 `HostSummary.taskCenter()`
         * 读到的就是这一份。
         *
         * 读的是**壳自己持有的那两个寄存器**（注册表经 [AppShell.scheduler]、档案经
         * [archive]），不让 UI 另开 `FileTaskStore`/`FileRunArchive`：第二个实例会各自持
         * channel 与内存视图，写侧两份即失真（见本类 KDoc 的读口说明）。
         *
         * 下一跳由**调度数学的唯一出处**算（`TimedSchedule.nextFireAfter`），停用任务
         * 直接回 null —— 停用任务也会有一个"如果启用就会在何时跑"的答案，把它显示出来
         * 就是在骗用户说这条还会跑。
         *
         * 恢复账取 [recovery]（装配层在 `install` 时接上的那份读口）；缺省 `{ null }` =
         * 本装配没接恢复账，快照里 [TaskCenterSnapshot.recovery] 如实为 null
         * （"本次进程还没跑过恢复"，不是"恢复了 0 条"）。
         */
        suspend fun taskCenter(
            recovery: () -> RecoverySnapshot? = { null },
        ): TaskCenterSnapshot {
            val now = System.currentTimeMillis()
            val tasks = shell.scheduler.tasks()
            val degraded = (shell.schedulerProvider as? AlarmSchedulerProvider)
                ?.degradedTasks()?.keys ?: emptySet()
            return TaskCenterRead.snapshot(
                tasks = tasks,
                // 停用任务不问下一跳（见 KDoc）；时区取任务自己的（Daily 的 DST 边界靠它）。
                nextFireAt = { task ->
                    if (!task.enabled) null else task.schedule.nextFireAfter(now, task.timezone)
                },
                degradedTaskIds = degraded,
                runs = archive.unfinished(),
                linkOf = { id -> archive.link(id) },
                recovery = recovery(),
            )
        }

        /**
         * 控制台快照（§7.3 游标拉取）—— 呈现层经 `HostSummary.console()` 读到的就是这一份。
         *
         * 读的是**壳自己持有的两件东西**（收集器 [AppShell.console] + 在途表
         * [AppShell.controller]），不让 UI 另开 `ConsoleCollector`：第二个收集器收不到
         * 桥上注册的行，读到的永远是空（写侧两份视图的老问题，同 `FileRunArchive` 一条理）。
         *
         * 游标（[sinceSeq]）由调用方传：数据面是拉取不是推送，游标只进不退。
         */
        suspend fun consoleView(sinceSeq: Long, maxLines: Int): ConsoleSnapshot =
            ConsoleRead.snapshot(
                collector = shell.console,
                sinceSeq = sinceSeq,
                maxLines = maxLines,
                runStatuses = { shell.controller.runStatuses() },
            )

        /**
         * 登记任务（§8.6 操作面「登记」）—— 呈现层经 `HostSummary.registerTask()` 走到这里。
         *
         * 写的是**壳自己持有的调度器**（`Scheduler.schedule`：先落盘再动内存/闹钟，
         * 落盘失败即抛、注册表无半登记状态），不让 UI 另开 `FileTaskStore` ——
         * 第二个实例 = 写侧两份视图（同 [taskCenter] 读口一条理）。
         * 入参校验在 [TaskCenterOps.toScheduledTask]（与桥侧 `workManager.create` 同一套规则）。
         *
         * @return 分配到的任务 id（入参 id 为空时服务端 UUID）。
         */
        suspend fun registerTask(registration: TaskRegistration): String {
            val task = TaskCenterOps.toScheduledTask(registration)
            shell.scheduler.schedule(task)
            return task.id
        }

        /**
         * 取消任务（§8.6 操作面「取消」）：转发 `Scheduler.cancel` —— 幂等
         * （从未登记的 id 照样返回；先落 tombstone 再动内存），已投递的 runs 不追回。
         */
        suspend fun cancelTask(taskId: String) {
            shell.scheduler.cancel(taskId)
        }

        /**
         * 立即执行（§8.6 操作面「立即执行」，触发源 `USER_CLICK`）。
         *
         * **触发前现查两件事**（`onTrigger` 对两者都是静默 return —— 不查就会把 no-op
         * 呈现成"已触发"）：
         * - 调度已收口（`sink()` 之后宿主正在停止）→ 抛，不让按钮在停机窗口里假装成功；
         * - 任务不在册（可能已被取消/Once 已终态化）→ 抛，原文带 id。
         *
         * **挂起到本次执行结算**（与闹钟/广播同一条 `onTrigger` 路径：排队上限
         * USER_CLICK 10s + 脚本超时/默认 30s）—— 成败不由此口回报（恒 Unit，
         * 结局在意图日志/控制台），调用方文案不得把"返回了"说成"跑成功了"。
         * Once 任务触发即终态化出册，刷新后从列表消失是调度器语义，不是取消。
         *
         * 查与触发之间存在极小竞态窗口（并发取消）：后果是本次静默无事发生 ——
         * 接受它；为关掉它去改 `onTrigger` 返回值会动到全部触发调用点与既有测试的
         * 表达式体形态，收益不抵风险。
         */
        suspend fun runTaskNow(taskId: String) {
            val scheduler = shell.scheduler
            if (scheduler.sinking) {
                throw IllegalStateException("调度已收口（宿主正在停止）：不再接收新触发")
            }
            if (scheduler.tasks().none { it.id == taskId }) {
                throw IllegalStateException("任务不存在（可能已被取消）：$taskId")
            }
            scheduler.onTrigger(taskId, TriggerSource.USER_CLICK)
        }

        /**
         * 停止一次在途执行（`HostSummary.stopRun` 的装配实现，§8.2 池四步 quiesce）。
         *
         * 走的是**在途表**（`RuntimeController.stop`），不是调度单槽
         * （`Scheduler.stopLastRun`）：恢复重投会覆盖单槽的 `lastHandle`，
         * 在途表按 runId 精确命中、不受覆盖影响。`AlreadyGone`（已结算/从未存在）
         * 如实回 false —— 在途表本来就没有它，不是失败，不抛。
         * `StoppedClean`/`StoppedTimeout` 都算"停过了"（后者池侧已 kill 兜底）。
         */
        suspend fun stopRun(runId: Long): Boolean = when (shell.controller.stop(runId)) {
            com.autoscript.appservice.runtime.RuntimeController.StopOutcome.StoppedClean -> true
            is com.autoscript.appservice.runtime.RuntimeController.StopOutcome.StoppedTimeout -> true
            com.autoscript.appservice.runtime.RuntimeController.StopOutcome.AlreadyGone -> false
        }
    }

    /**
     * 装配生产壳（§4.1）。
     *
     * @param filesDir App 私有文件目录（`files/scripts/<projectId>`、`files/.autojs` 都在这下面，
     *   §10.2 存储布局）；Android 侧传 `context.filesDir.toPath()`。
     * @param cacheDir 可被系统清理的缓存目录（npm cacache `cacheDir/npm-cache`，§10.2）；
     *   被清掉只是缓存缺口（`CacacheIndex` 如实报缺口），不是错误。
     * @param schedulerProvider 闹钟触发源（Android 侧 = `AlarmSchedulerProvider(AndroidAlarmPort(...))`）。
     * @param screenGate 屏幕门禁（Android 侧 = `AndroidScreenGate.of(context)`）；
     *   缺省 [ScreenGate.AllowAll] **只对非 Android 调用方成立** —— 真机上必须传真实现，
     *   否则 `SCREEN_ON` 契约（§8.6 亮屏+解锁保底）在装配层被静默取消。
     * @param engineFactory 引擎宿主工厂。缺省 = [UnavailableEngine]：一槽一实例（与
     *   [com.autoscript.appservice.runtime.FixedEnginePool] 的构造约定一致，槽位不共享宿主）。
     *   生产（`com.autoscript.AppShellApplication`）已显式注入 `NodeProcessEngine`（§19 Kotlin
     *   spawn）—— 缺省保留给纯 JVM 配方与测试，两边互不覆盖。
     * @param a11yHandler / @param screenHandler `:platform:capabilities` 的真实现（经
     *   `CapabilityNamespaces.{a11y,screen}` 转接）；null = 未接线，桥如实 `ERR_NOT_IMPLEMENTED`。
     * @param npmHandler npm 命名空间实现；null = 本配方自建（[NpmShellKit]）。
     * @param datastoreHandler `datastore` 命名空间实现（§9.6，经 `CapabilityNamespaces.datastore` 转接）；
     *   独立缝（不入 [SystemHandlers] 束 —— 存储面无共担门禁）；null = 未接线，桥如实 `ERR_NOT_IMPLEMENTED`。
     * @param zipHandler `zip` 命名空间实现（§9.6，经 `CapabilityNamespaces.zip` 转接）；
     *   同 datastore 独立缝；null = 未接线，桥如实 `ERR_NOT_IMPLEMENTED`。
     * @param settingsHandler `settings` 命名空间实现（§9.6，经 `CapabilityNamespaces.settings` 转接）；
     *   同 datastore 独立缝（WRITE_SETTINGS 判据在 SPI，不入 [SystemHandlers]）；
     *   null = 未接线，桥如实 `ERR_NOT_IMPLEMENTED`。
     * @param notificationHandler `notification` 命名空间实现（§12.2，经 `CapabilityNamespaces.notification` 转接）；
     *   同 datastore 独立缝（`POST_NOTIFICATIONS` 判据在 SPI，不入 [SystemHandlers]）；
     * @param clipboardHandler `clipboard` 命名空间实现（§12.2，经 `CapabilityNamespaces.clipboard` 转接）；
     *   同 datastore 独立缝（剪贴板无门禁，判据在 SPI，不入 [SystemHandlers]）；
     *   null = 未接线，桥如实 `ERR_NOT_IMPLEMENTED`。
     * @param powerManagerHandler `power_manager` 命名空间实现（§8.7，由 Application 从
     *   `foregroundKeeper()` 账本现建 `PowerManagerNamespaceHandler(...).mount()` 后传入）；
     *   同 datastore 独立缝，不入 [SystemHandlers]；null = 未接线，桥如实 `ERR_NOT_IMPLEMENTED`。
     * @param systemHandlers `dialogs`/`shell`/`device`/`app`/`floatingWindow` 五个命名空间实现（§9.4/§9.6，经 `:platform:capabilities` 的 `CapabilityNamespaces.{shell,device,app,dialogs,floatingWindow}` 转接）；null = 未接线，桥如实 `ERR_NOT_IMPLEMENTED`。与 [a11yHandler]/[screenHandler] 同一注入缝，合成一个束（见 [SystemHandlers]）——本配方只透传，不 new 实现。
     * @param watchdogScope 看门狗轮转的协程域；null = 本配方自建一个壳自己的域
     *   （[AssembledShell.close] 时取消）。传自己的域 = 你自己负责停（见 [EngineWatchdog.start]）。
     */
    fun assemble(
        filesDir: Path,
        cacheDir: Path,
        schedulerProvider: SchedulerProvider,
        screenGate: ScreenGate = ScreenGate.AllowAll,
        engineFactory: (EngineId) -> ScriptEngine = { id -> UnavailableEngine(id) },
        a11yHandler: NamespaceHandler? = null,
        screenHandler: NamespaceHandler? = null,
        npmHandler: NamespaceHandler? = null,
        datastoreHandler: NamespaceHandler? = null,
        zipHandler: NamespaceHandler? = null,
        settingsHandler: NamespaceHandler? = null,
        notificationHandler: NamespaceHandler? = null,
        clipboardHandler: NamespaceHandler? = null,
        powerManagerHandler: NamespaceHandler? = null,
        systemHandlers: SystemHandlers? = null,
        /**
         * 装配期脚本补部署的来源（projectId → 项目内相对路径 → 字节；§9.6）。
         * 缺省空映射 = 本次没补任何东西（`deployReport.changed == false`），**不粉饰成"已恢复"**。
         * 生产提供方之一是 `script-repo` 的 `AndroidAssetsSource`（首批内置脚本），
         * 但补部署的判据与来源无关 —— 只补缺、绝不覆盖已有文件。
         */
        scriptSources: Map<String, Map<String, ByteArray>> = emptyMap(),
        /**
         * 装配期脚本补部署的来源清单（`assets/scripts/` 下的 projectId 枚举；§9.6）。
         * 缺省空表 = 不枚举（`scriptSources` 有货照样补）。生产由 Application 传
         * `AssetLister` 的枚举结果 —— 本配方不直连 AssetManager（`:app` 测试源集
         * 无 android 桩之外的资产能力，且配方保持纯 JVM 可测）。
         */
        scriptProjects: List<String> = emptyList(),
        /**
         * 按 projectId 读资产（`assets/scripts/<projectId>/` → 相对路径 → 字节；§9.6）。
         * 缺省 null = 无资产来源（只用 [scriptSources]）。生产实现两行：
         * `{ id -> AndroidAssetsSource(applicationContext.assets, id).readScripts() }`
         * —— 不能写进本文件（`:app-service:script-repo` 的 assets 包直连
         * `android.content.res.AssetManager`，配方保持纯 JVM 可测），故由 Application 喂。
         */
        assetReader: ((String) -> Map<String, ByteArray>)? = null,
        /**
         * 装配期 facade dist 落位的来源（`assets/bridge-dist/` 扁平文件名 → 字节；§12.4）。
         * 缺省空映射 = 本次没货（`bridgeDistReport.changed == false`），**不粉饰成"已部署"**。
         * 生产由 Application 枚举 assets 喂入（本配方不直连 AssetManager，同 scriptSources 纪律）。
         */
        bridgeDist: Map<String, ByteArray> = emptyMap(),
        /**
         * 装配期 bridge addon 落位的来源（`assets/bridge-addon/bridge_native.node` 的字节；
         * §19 交付轨）。null = 本次没货（`bridgeAddonReport.changed == false`），**不粉饰
         * 成"addon 已就位"** —— 引擎按 `ScriptPaths.bridgeAddonFile` 缺文件即降级不注入。
         */
        bridgeAddon: ByteArray? = null,
        poolCapacity: Int = 1,
        monitor: ProcessMonitor = ProcessMonitor(),
        watchdog: EngineWatchdog? = null,
        watchdogScope: CoroutineScope? = null,
    ): AssembledShell {
        val autojsDir = filesDir.resolve(".autojs")
        Files.createDirectories(autojsDir)

        // 装配期脚本补部署（§9.6）：排期/意向持久了但脚本内容可能已被清掉
        // （用户"清除数据"、系统回收空间、预装包升级），先补缺再装配 —— 否则装配出的壳
        // 每次执行都以"脚本文件不存在"告终，而任务中心只看到 CRASHED、说不出为什么。
        // 只补缺不覆盖：用户手改过的脚本原样留着（见 ScriptDeployRecovery KDoc 三条诚实边界）。
        // 资产来源合并（§9.6）：显式传入的 scriptSources 优先，assets 按 projectId 补齐缺的
        // 项目 —— 合并只做"缺项目补"，同项目同文件以调用方显式传入为准（不覆盖、不合并文件级）。
        val mergedSources: Map<String, Map<String, ByteArray>> =
            if (assetReader == null || scriptProjects.isEmpty()) scriptSources
            else {
                val merged = HashMap(scriptSources)
                for (projectId in scriptProjects) {
                    if (merged.containsKey(projectId)) continue
                    merged[projectId] = try {
                        assetReader(projectId)
                    } catch (_: Exception) {
                        // 单项目资产读失败不带走整批（与 ScriptDeployRecovery 单文件诚实边界同理）——
                        // 该项目本次不补，deployReport 如实无此项目（不是"已恢复"）。
                        continue
                    }
                }
                merged
            }
        val deployReport = ScriptDeployRecovery(filesDir, mergedSources).run()

        // facade dist 落位（§12.4 资产交付轨）：应用自有资产，覆盖语义与脚本补部署相反
        // （字节不同即替换 —— 旧 dist 跨版本形状不配对会把"没更新"变成"模块坏了"）。
        // 落位根 = ScriptPaths.autoModuleRoot（require('auto') 的解析点，契约住 :domain）。
        val bridgeDistReport = BridgeDistDeploy(filesDir, bridgeDist).run()

        // bridge addon 落位（§19 交付轨，选填件）：同一条字节即版本纪律，但单文件不 claim
        // 目录（filesDir/lib 可能住别的，没有孤儿清理）。没货 = 不动盘 —— 引擎侧
        // addonPath 缺文件降级不注入，与 bridgeDistPath 同一条选填纪律。
        val bridgeAddonReport = BridgeAddonDeploy(filesDir, bridgeAddon).run()

        // 意图日志与运行档案分文件（§8.5）：键不同（intentRunId vs engineRunId），
        // 只写一侧的孤儿因此可被审计。两个都持久：重启后任务中心与 bootRecover 才有据可依。
        val log = PersistentIntentLog(JournalFileStore(autojsDir))
        val archive = FileRunArchive(autojsDir)
        // 注册表第三持久（§8.6）：意图日志管"已投递的意向"，这里管"还没到点的排期"。
        // 同一 `.autojs` 目录（`tasks.jsonl`），同一追加+tombstone 纪律；Scheduler 经
        // [AppShell][com.autoscript.shell.AppShell] 的 `taskStore` 缝拿到它。
        val tasks = FileTaskStore(autojsDir)

        val npm = npmHandler ?: NpmShellKit.assembleHandler(filesDir = filesDir, cacheDir = cacheDir)

        val shell = AppShell.assemble(
            engineFactory = engineFactory,
            schedulerProvider = schedulerProvider,
            intentLog = log,
            runArchive = archive,
            taskStore = tasks,
            screenGate = screenGate,
            poolCapacity = poolCapacity,
            a11yHandler = a11yHandler,
            screenHandler = screenHandler,
            monitor = monitor,
            watchdog = watchdog,
            npmHandler = npm,
            datastoreHandler = datastoreHandler,
            zipHandler = zipHandler,
            settingsHandler = settingsHandler,
            notificationHandler = notificationHandler,
            clipboardHandler = clipboardHandler,
            powerManagerHandler = powerManagerHandler,
            systemHandlers = systemHandlers,
        )
        // 看门狗开机即转（§8.4）：不转的话三路判据就只是"可以转"——在途 run 的出格行为
        // 没有一个周期性的观察者，`awaitCompletion` 的等待超时是唯一兜底（而它只管
        // dispatcher 自己发起的那条路）。域要么调用方给，要么壳自己持（close 时取消）。
        val ownedScope: ShellScope?
        if (watchdogScope != null) {
            shell.startWatchdog(watchdogScope)
            ownedScope = null
        } else {
            val fresh = ShellScope()
            shell.startWatchdog(fresh)
            ownedScope = fresh
        }
        return AssembledShell(shell, log, archive, tasks, npm, ownedScope, deployReport, bridgeDistReport, bridgeAddonReport)
    }
}
