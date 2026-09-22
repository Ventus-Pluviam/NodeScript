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
import com.autoscript.domain.bridge.NamespaceHandler
import com.autoscript.domain.engine.EngineId
import com.autoscript.domain.engine.ScriptEngine
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
 * - **能力 handler 由调用方给**：[AppShellApplication] 持有 `:platform:capabilities` 的真实现
 *   （依赖方向见 §6：`:app` 不得直连 `:platform`，缝的类型住 `:domain`），本文件只负责把它
 *   转交给 [AppShell.assemble]；null = 未接线，桥如实回 `ERR_NOT_IMPLEMENTED`；
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
    ) : AutoCloseable {
        /** 没补上的脚本（路径 + 原因；能力中心呈现"有脚本没补上"，不吞成一切正常）。 */
        fun deployFailures(): List<ScriptDeployRecovery.Failure> = deployReport.failures
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
        return AssembledShell(shell, log, archive, tasks, npm, ownedScope, deployReport)
    }
}
