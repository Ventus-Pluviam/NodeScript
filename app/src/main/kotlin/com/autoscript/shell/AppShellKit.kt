package com.autoscript.shell

import com.autoscript.appservice.npm.HeavyOpExecutor
import com.autoscript.appservice.npm.HostNodeExecutor
import com.autoscript.appservice.npm.NpmGlobalConfig
import com.autoscript.appservice.npm.InstallCoordinator
import com.autoscript.appservice.npm.LockSigner
import com.autoscript.appservice.npm.NpmCliDeployer
import com.autoscript.appservice.npm.NpmBridgeHandler
import com.autoscript.appservice.npm.NpmShellKit
import com.autoscript.appservice.npm.NpmSpawnGate
import com.autoscript.appservice.runtime.EngineWatchdog
import com.autoscript.appservice.runtime.ProcessMonitor
import com.autoscript.appservice.runtime.UnavailableEngine
import com.autoscript.appservice.scheduler.core.SchedulerProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import com.autoscript.appservice.scheduler.persist.FileRunArchive
import com.autoscript.appservice.scheduler.persist.FileTaskStore
import com.autoscript.appservice.scheduler.persist.PersistentIntentLog
import com.autoscript.appservice.scheduler.recovery.ScriptDeployRecovery
import com.autoscript.appservice.scriptrepo.core.BridgeAddonDeploy
import com.autoscript.appservice.scriptrepo.core.BridgeDistDeploy
import com.autoscript.domain.bridge.NamespaceHandler
import com.autoscript.domain.engine.RunIdentityIssuer
import com.autoscript.domain.engine.EngineId
import com.autoscript.domain.engine.ScriptEngine
import com.autoscript.domain.host.TaskCenterSnapshot
import com.autoscript.domain.host.ConsoleSnapshot
import com.autoscript.domain.host.TaskRegistration
import com.autoscript.domain.permission.CapabilityMask
import com.autoscript.domain.scripts.IntentStore
import com.autoscript.domain.permission.ScriptAuthorizationPolicy
import com.autoscript.appservice.scheduler.core.TriggerSource
import com.autoscript.domain.scripts.RunArchive
import com.autoscript.domain.scripts.RunRecord
import java.nio.file.Files
import java.nio.file.Path
import com.autoscript.platform.capabilities.CapabilityNamespaces

/**
 * Android 侧装壳配方（docs §4.1 Composition Root 的**真调用点**）。
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
 * **2026-10-01 D7**：[AssembledShell] 与它的协程域 [ShellScope] 已外迁到同包的
 * `AssembledShell.kt` —— 本文件只剩「怎么装」（配方与注入缝），产物与收口责任在那边。
 * 判据没变，只是读的人不必先翻过 250 行产物类才能看到 `assemble` 的参数表。
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
     * @param npmCliSource vendored npm CLI 的素材源（§10.2 调用链首段；`assets/npm/` 那棵
     *   资产树）。null = 无来源（纯 JVM 配方/测试）→ 不部署、不注入执行体，桥对 npm.*
     *   如实 `ERR_NOT_IMPLEMENTED`。生产由 Application 喂 `AssetTreeCliSource("npm", …)`
     *   —— 本配方不直连 AssetManager（同 [scriptSources]/[bridgeDist] 纪律）。
     *   （路径一律写单斜杠形态：Kotlin 块注释**会嵌套**，注释里连写两个星号会被当成新注释
     *   开头，后面真正的注释结束符就吃不掉它了 —— 本文件踩过一次，别再写。）
     * @param npmNodeBin npm 执行体的 Node 宿主绝对路径（Android 侧 =
     *   `nativeLibraryDir/libnoden.so`，与 [engineFactory] 的 hostBinary 同一个文件）。
     *   null = 没有能跑 CLI 的宿主 → **不注入执行体**（素材照样部署，便于诊断：
     *   "装了 CLI 却没有 node"比"什么都没接线"更接近病因）。
     *   **有宿主时同时落 `child_process` 拦截 shim**（§10.11 P0 / §10.12 末行）：它是零 spawn
     *   不变量的第二层兜底，落位失败 = 不注入执行体（fail closed，见 [NpmSpawnGate] KDoc）。
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
     * @param sensorsHandler `sensors` 命名空间实现（§12.2，经 `CapabilityNamespaces.sensors` 转接）；
     *   同 datastore 独立缝（P0 名单无运行时门禁，判据在 SPI，不入 [SystemHandlers]）；
     *   null = 未接线，桥如实 `ERR_NOT_IMPLEMENTED`。
     * @param imagesHandler `images` 命名空间实现（§9.2，经 `CapabilityNamespaces.images` 转接）；
     *   同 datastore 独立缝（图像面无共担门禁，`decode`/`matchTemplate`/`findImage`/`release`
     *   四方法判据在 SPI，不入 [SystemHandlers]）；
     *   null = 未接线，桥如实 `ERR_NOT_IMPLEMENTED`。
     * @param powerManagerHandler `power_manager` 命名空间实现（§8.7，由 Application 从
     *   `foregroundKeeper()` 账本现建 `PowerManagerNamespaceHandler(...)` 后传入）；
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
        engineFactory: (EngineId, RunIdentityIssuer) -> ScriptEngine = { id, _ -> UnavailableEngine(id) },
        a11yHandler: NamespaceHandler? = null,
        screenHandler: NamespaceHandler? = null,
        npmHandler: NamespaceHandler? = null,
        /**
         * 素材源见 KDoc；**缺省 null 是诚实缺省**：没有素材就不该注入执行体。
         */
        npmCliSource: NpmCliDeployer.CliSource? = null,
        /**
         * Node 宿主绝对路径（见 KDoc）。null = 不注入执行体（素材照样部署）。
         */
        npmNodeBin: String? = null,
        /**
         * 应用密钥缝（§10.5-1 T2 / §11.3 第 8 条）。给的是 Keystore 面
         * （[LockKeyStore.HmacKeys]），**本配方自己经 [LockKeyStore.resolve] 决定
         * 「取已有 / 首次建」** —— 取钥判定住在能记账的地方（失败原因进
         * [AssembledShell.npmLockKeyFailure]），调用方只负责「钥匙在哪」。
         *
         * **缺省 null = 不装 lock 签名**：`ci` 不验签直接走、快照导出如实
         * `ERR_NOT_IMPLEMENTED`（不假装验过）。生产传 [LockKeyStore.AndroidKeystore]。
         */
        npmLockKeys: LockKeyStore.HmacKeys? = null,
        /**
         * `child_process` 拦截 shim 的落位缝（§10.11 P0）。缺省 = 真从 classpath 资源落盘
         * （[NpmSpawnGate.deploy]）；测试注入 [NpmSpawnGate.Deploy.Failed] 验「落位失败 →
         * 不注入执行体」那条 fail-closed 判据 —— 真资源永远在 classpath 上，不注入就够不到
         * 那个分支，而它正是「不许静默降级」这条承诺的落点。
         */
        npmGateDeploy: (Path) -> NpmSpawnGate.Deploy = { NpmSpawnGate.deploy(it) },
        datastoreHandler: NamespaceHandler? = null,
        zipHandler: NamespaceHandler? = null,
        settingsHandler: NamespaceHandler? = null,
        notificationHandler: NamespaceHandler? = null,
        clipboardHandler: NamespaceHandler? = null,
        sensorsHandler: NamespaceHandler? = null,
        imagesHandler: NamespaceHandler? = null,
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
        /**
         * 意图日志存储（§8.5）—— **必填，无缺省**。
         *
         * 生产由 `PlatformWiring.intentStore` 给 SQLite（并在那里做一次性迁移）；
         * 测试给 `:domain` `testFixtures` 的 `InMemoryIntentStore`。
         *
         * **为什么必填**（2026-10-08 裁定）：原先的缺省是「不给就本配方自建 jsonl」，
         * 而那条路正是被删掉的回落 —— 留着这个缺省等于 fail closed 没做。也不在本文件里
         * 选：打开存储要碰 Android（`Context` → `SQLiteOpenHelper`），而本文件是纯 JVM
         * 可测的配方（不 import `android.`）。于是「谁碰 Android 谁开」落在 `PlatformWiring`。
         */
        intentStore: IntentStore,
        /**
         * **来源授权策略**（A5，§11）：本壳所有执行拿到什么桥面能力的判据来源。
         *
         * 缺省 = 无来源元数据（`TrustTier.UNKNOWN` 的保守档 A，见
         * [com.autoscript.domain.permission.TrustTierMasks.UNKNOWN_DEFAULT]）。
         * 生产接真元数据时在这里传一个带
         * [com.autoscript.domain.permission.TrustTierResolver] 的策略，就能**按项目**分级 ——
         * 这是本参数的用途（它是注入缝），不是三个全局布尔开关。
         */
        authorization: ScriptAuthorizationPolicy = ScriptAuthorizationPolicy(),
        /**
         * 便捷覆盖（A5，§11）：非 null 时所有执行按它拿掩码，等价于
         * `ScriptAuthorizationPolicy(override = capabilityMask)`。测试/受信直投路径用。
         *
         * **与 [authorization] 同时给时以本参数为准**（见 [AppShell.assemble] 同名参数的 KDoc：
         * 本参数整体替换策略对象，不是叠加）。本方法把两个参数无条件转下去，不做取舍判断 ——
         * 规则只有一条，写在装配根那一处。
         */
        capabilityMask: CapabilityMask? = null,
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
        // 存储引擎由调用方给（§8.5）：生产 = SQLite（PlatformWiring.intentStore，含一次性迁移），
        // 测试 = InMemoryIntentStore。两者语义由 `IntentStoreContract` 同一组用例守着。
        // **无回落**：打不开在 PlatformWiring 就抛了，装配层据此判壳未就绪。
        val log = PersistentIntentLog(intentStore)
        val archive = FileRunArchive(autojsDir)
        // 注册表第三持久（§8.6）：意图日志管"已投递的意向"，这里管"还没到点的排期"。
        // 同一 `.autojs` 目录（`tasks.jsonl`），同一追加+tombstone 纪律；Scheduler 经
        // [AppShell][com.autoscript.shell.AppShell] 的 `taskStore` 缝拿到它。
        val tasks = FileTaskStore(autojsDir)

        // vendored npm CLI 落位 + 执行体注入（§10.2 调用链首段/末段）。
        // 素材随包在 `assets/npm/**`，启动期先按打包前清单检疫 APK 文件集合与摘要，
        // 再与落盘目录对账；全树未变化才幂等跳过。**只有"部署就位 + 有 Node 宿主"两条同时成立才注入
        // [HostNodeExecutor]**；任一不成立就保持 Unavailable，桥对 npm.* 如实
        // `ERR_NOT_IMPLEMENTED` —— 装了 CLI 却没有能跑它的 node，"注入"就等于把必失败
        // 伪装成已接线。失败原因原文进 [AssembledShell.npmCliFailure]，不吞成"一切正常"。
        //
        // 异常不外抛：npm 只是能力之一，素材缺失（绝大多数本机构建的 APK 就是这样）不该
        // 让整个壳装不起来 —— 而 `NpmCliDeployer.deploy` 对"素材缺失/半瘫"是 loud 的
        // （锚校验一票否决），所以这里必须接住并如实记账，而不是放它掀翻装配。
        // 调用方自带 handler（测试/替换实现）时**本配方不碰素材**：不部署、不注入，
        // 两个报告字段保持 null（它们的语义是"本配方自建 npm 时的落位结果"，不是
        // "npm 一切正常"）。
        // 应用密钥（§10.5-1 T2）：**装配期就取一次**，不把「Keystore 坏没坏」推到用户
        // 第一次 npm ci 才炸 —— 那时看到的是一条验签失败，分不清是 lock 被换了还是
        // 钥匙取不动。取不到不外抛（npm 只是能力之一，为一把钥匙掀翻装配不成比例）：
        // 传 null = 本次不装 lock 防线，原因原文进 npmLockKeyFailure，不吞成"一切正常"。
        var npmLockKeyFailure: String? = null
        val lockKey: LockSigner.KeyProvider? = npmLockKeys?.let { keys ->
            try {
                LockKeyStore.resolve(keys).also { it.secretKey() }
            } catch (e: Exception) {
                npmLockKeyFailure = "应用密钥取不到，lock 签名与快照签名本次不生效（ci 不验签直接走）：${e.message}"
                null
            }
        }
        // 自建 npm 的两步（素材落位 + 执行体注入）外迁成 [wireNpmExecutor]：本方法已经
        // 是三十几个参数的装配根，把这段判断留在里面只会让"怎么装"淹没在嵌套里。
        val npmWiring: NpmWiring? = if (npmHandler == null) {
            wireNpmExecutor(filesDir, cacheDir, npmCliSource, npmNodeBin, npmGateDeploy)
        } else {
            null   // 调用方自带 handler：本配方不碰素材（见上方 KDoc），四个报告字段保持 null
        }
        val built: NpmBridgeHandler? = if (npmHandler == null) {
            NpmShellKit.assembleHandler(
                filesDir = filesDir,
                cacheDir = cacheDir,
                executor = npmWiring!!.executor,
                // T2 装配缺口收口（2026-10-08 批 79）：此前这里从不传 `lockKey`，
                // 于是生产路径上 lock 既不签也不验 —— §11.3 第 8 条记的就是这件事。
                // 接上后 `ci` 先验签、`install` 收尾重签、快照导出带 snapshot.sig。
                lockKey = lockKey,
            )
        } else {
            null   // 调用方自带 handler：本配方不参与，呈现面读口随之缺席（如实 null）
        }
        val npm: NamespaceHandler = npmHandler ?: built!!

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
            sensorsHandler = sensorsHandler,
            imagesHandler = imagesHandler,
            powerManagerHandler = powerManagerHandler,
            systemHandlers = systemHandlers,
            authorization = authorization,
            capabilityMask = capabilityMask,
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
        return AssembledShell(
            shell, log, archive, tasks, npm, ownedScope,
            deployReport, bridgeDistReport, bridgeAddonReport,
            npmWiring?.cli, npmWiring?.cliFailure,
            npmLockKeyFailure, npmWiring?.gate,
            built?.facade,
        )
    }

    /**
     * 自建 npm 装配的两步结果：**执行体**（喂 [NpmShellKit.assembleHandler]）+ 三个如实记账位。
     *
     * [cli] 非 null 就一定是"CLI 真在盘上"；[cliFailure] 是"哪一步没接上"的原文；
     * [gate] 非 null = child_process 拦截 shim 在盘上（§10.11 P0 承诺面）。
     */
    internal class NpmWiring(
        val executor: HeavyOpExecutor,
        val cli: NpmCliDeployer.Outcome?,
        val cliFailure: String?,
        val gate: Path?,
    )

    /**
     * 素材落位 → 执行体注入（§10.2 调用链首段/末段）。
     *
     * **只有"部署就位 + 有 Node 宿主 + 拦截 shim 落位"三条同时成立才注入
     * [HostNodeExecutor]**；任一不成立就保持 [HeavyOpExecutor.Unavailable]，桥对 npm.*
     * 如实 `ERR_NOT_IMPLEMENTED` —— 装了 CLI 却没有能跑它的 node（或没有守卫）时，
     * "注入"就等于把必失败（或**无门禁**）伪装成已接线。
     *
     * 异常不外抛：npm 只是能力之一，素材缺失（绝大多数本机构建的 APK 就是这样）不该让
     * 整个壳装不起来 —— 而 `NpmCliDeployer.deploy` 对"素材缺失/半瘫"是 loud 的
     * （锚校验一票否决），所以这里必须接住并如实记账，而不是放它掀翻装配。
     */
    internal fun wireNpmExecutor(
        filesDir: Path,
        cacheDir: Path,
        source: NpmCliDeployer.CliSource?,
        host: String?,
        gateDeploy: (Path) -> NpmSpawnGate.Deploy,
    ): NpmWiring {
        if (source == null) {
            return NpmWiring(HeavyOpExecutor.Unavailable, null, "无素材来源（assets/npm 未随包）", null)
        }
        // 落位与执行体分两步记账：cli 非 null 就一定是"CLI 真在盘上"，
        // 不吃"部署成了、执行体没接上"的中间态（那种情况两者都非 null，
        // 由 cliFailure 的原文说清差在哪一步）。
        val deployed = try {
            NpmCliDeployer.deploy(filesDir, source) as NpmCliDeployer.Outcome.Ready
        } catch (e: Exception) {
            return NpmWiring(HeavyOpExecutor.Unavailable, null, "素材部署失败：${e.message}", null)
        }
        if (host == null) {
            return NpmWiring(
                HeavyOpExecutor.Unavailable, deployed,
                "CLI 已落位（${deployed.cliJs}），但没有 Node 宿主" +
                    "（nativeLibraryDir/libnoden.so 缺）→ 不注入执行体",
                null,
            )
        }
        // child_process 拦截 shim（§10.11 P0 承诺面 / §10.12 末行「零 spawn 不变量漂移」）：
        // **只有真要去起 CLI 时才落**（没宿主 = 本来就没有安装会话可守，落一个没人 require
        // 的 .cjs 是噪声）。落位失败 → **不注入执行体**：静默降级成「装是能装、守卫没了」
        // 正是那条风险本身。
        val gate = when (val g = gateDeploy(filesDir)) {
            is NpmSpawnGate.Deploy.Ready -> g.file
            is NpmSpawnGate.Deploy.Failed -> {
                val why = "CLI 已落位（${deployed.cliJs}）且宿主就位，但 child_process 拦截 shim 未落位" +
                    " → 不注入执行体（零 spawn 不变量是 P0 承诺面，缺它不许静默降级）：${g.reason}"
                return NpmWiring(HeavyOpExecutor.Unavailable, deployed, why, null)
            }
        }
        return try {
            NpmWiring(
                HostNodeExecutor(
                    deployed.cliJs, cacheDir, nodeBin = host, spawnGateFile = gate,
                    // 全局镜像源走 userconfig（§10.2 三层链；2026-10-09 批 83）。
                    // **不再传 registryOverride**：此前无条件注入 `--registry 官方`，
                    // 于是用户设的镜像源对真实安装毫无影响。现在让 npm 自己按
                    // `--prefix`（workDir，里面已拷了项目 .npmrc）→ userconfig → 出厂解析，
                    // 与 InstallCoordinator.resolveRegistry 的两层链同源。
                    userConfig = filesDir.resolve(NpmGlobalConfig.FILE_NAME),
                ),
                deployed, null, gate,
            )
        } catch (e: Exception) {
            NpmWiring(
                HeavyOpExecutor.Unavailable, deployed,
                "CLI 已落位（${deployed.cliJs}），但执行体构造失败：${e.message} → 不注入",
                null,
            )
        }
    }
}
