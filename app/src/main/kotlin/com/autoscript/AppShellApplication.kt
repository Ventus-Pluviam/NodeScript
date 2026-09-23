package com.autoscript

import android.app.Application
import android.os.Process
import android.util.Log
import com.autoscript.domain.host.CapabilityCenterSnapshot
import com.autoscript.domain.host.HostSummary
import com.autoscript.domain.host.TaskCenterSnapshot
import com.autoscript.domain.host.TaskRegistration
import com.autoscript.domain.host.ConsoleSnapshot
import com.autoscript.domain.host.ShellSummary
import com.autoscript.domain.permission.Capability
import com.autoscript.domain.scripts.ScriptPaths
import com.autoscript.engine.nodeprocess.NodeEngineConfig
import com.autoscript.engine.nodeprocess.NodeProcessEngine
import com.autoscript.shell.AlarmDispatch
import com.autoscript.shell.AlarmFires
import com.autoscript.shell.AlarmPort
import com.autoscript.shell.AlarmReceiver
import com.autoscript.shell.AlarmSchedulerProvider
import com.autoscript.shell.AndroidAlarmPort
import com.autoscript.shell.AndroidBridgeBinder
import com.autoscript.shell.AndroidForegroundOps
import com.autoscript.shell.AndroidWakeLockOps
import com.autoscript.shell.AndroidPermissionGates
import com.autoscript.shell.AndroidScreenGate
import com.autoscript.shell.AppShell
import com.autoscript.shell.AppShellKit
import com.autoscript.shell.AutoScriptForegroundService
import com.autoscript.shell.BootRecovery
import com.autoscript.shell.BridgeSocketListener
import com.autoscript.shell.CapabilityCenterRead
import com.autoscript.shell.ForegroundHost
import com.autoscript.shell.ForegroundKeeper
import com.autoscript.shell.PlatformWiring
import com.autoscript.shell.RecoverySnapshot
import com.autoscript.shell.SchedulerAlarmRoute
import com.autoscript.shell.ScreenGateAndroid
import com.autoscript.shell.ScreenInteractive
import com.autoscript.shell.WakeLockLedger
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.nio.file.Path

/**
 * 启动装配（docs/framework-design.md §4.1 Composition Root，手写 DI，不用 Hilt）。
 *
 * 进程启动时最先跑的是这里，所以它是**唯一**能保证"闹钟到了就有人接"的地方：
 * 精确闹钟可能在壳装配完成前、装配后被系统回收又重启时、或进程刚好在重建中时响
 * （静态注册的接收器会因此被拉起）。三段状态在此闭环：
 *
 * 1. **装配中/装配前**：[AlarmDispatch] 还没接路线 → 漏投进 [AlarmDispatch.missed]
 *    （UI 据此如实告知"闹钟已响，调度未就绪"），**不静默丢弃**；
 * 2. **装配完成**：`AlarmSchedulerProvider` + `AlarmPort`（真 AlarmManager）+
 *    `AndroidScreenGate`（真 PowerManager）挂上（[onCreate] 经 [installWithFiles] 自装配，
 *    或由调用方走 [install] 注入带能力 handler 的壳），接收器的 taskId 经
 *    [SchedulerAlarmRoute] 回到 [com.autoscript.appservice.scheduler.core.Scheduler.onTrigger]；
 * 3. **重建/恢复**：接到广播时 application 已 attach，就地走 2 的路线，幂等由
 *    scheduler 的 runNonce 承担（同一个 runNonce 重复投递不会双跑）。
 *
 * §8.7 的保活与电源**已接线**（2026-09-23）：[AndroidWakeLockOps] 取真 `PARTIAL_WAKE_LOCK`、
 * [WakeLockLedger] 做 token 引用计数与超时自动释放、[ForegroundKeeper] 管 specialUse FGS 的
 * 起停与续期。屏幕门禁的持锁判定取 [WakeLockLedger.isHeld]（账本与系统两侧都真）——
 * 于是熄屏 + `SCREEN_ON` 的任务要么真有锁放行、要么**如实拒绝**，没有第三条路
 * （"锁也没拿却照样跑"的表现是"任务成功、实际什么都没发生"）。
 */
class AppShellApplication : Application(), HostSummary {

    /**
     * 壳装配产物。**可能为 null** —— 装配有两条路：
     * - [install]（注入式）：调用方（Activity/测试）把装好的壳交进来；
     * - [installWithFiles]（自装配）：本类就地用 [AppShellKit.assemble] 装一个。
     *
     * null 只持续到其中一条跑完；在那之前 [alarmWork] 走漏投记账，
     * **不伪造一次成功投递**。装配失败（磁盘/权限）同样保持 null + 记账，
     * 绝不让一个坏掉的壳冒充"就绪"。
     */
    @Volatile
    private var shell: AppShell? = null

    /** 自装配产物的持久句柄（关壳时要成对释放；见 [AppShellKit.AssembledShell]）。 */
    @Volatile
    private var assembled: AppShellKit.AssembledShell? = null

    /** 闹钟回投缝（[AlarmDispatch]）：装配前记账、装配后投递。 */
    private val alarmDispatch = AlarmDispatch()

    /** 开机恢复（§8.5）：同壳幂等 + 失败记账 + 快照供能力中心读（见 [recoverySnapshot]）。 */
    private val bootRecovery = BootRecovery()

    /** 闹钟出口（真 AlarmManager）：本类持有引用，供取消/续排路径按 taskId 撤销。 */
    private var alarmPort: AlarmPort? = null

    /**
     * 桥监听（§7.5，[BridgeSocketListener]）：**绑定一次、进程生命周期持有** ——
     * abstract 名是进程级资源，重装装配复用同一实例（[BridgeSocketListener.start]
     * 换 router 面即可）；装配失败也不关（关了重试绑不回，名字还可能已被抢）。
     */
    private var bridgeListener: BridgeSocketListener? = null

    /** 门禁生产实例缓存（见 [permissionCenter]；查询本身不缓存）。 */
    @Volatile
    private var gates: com.autoscript.appservice.permissioncenter.PermissionCenter? = null

    /**
     * 保活编排（§8.7）：唤醒锁账本 + specialUse FGS 的起停/续期。
     *
     * **进程级单例式**（懒建 + 缓存）：它持有的唤醒锁是进程级单资源，
     * 第二份实例会各记各的 token（互相看不见对方持着锁 → 一方 release 把另一方的锁也放掉，
     * 表现是"熄屏任务随机被拒"，见 [WakeLockLedger] 的引用计数理由）。
     */
    @Volatile
    private var keepAlive: ForegroundKeeper? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "装配启动：壳创建中（引擎/a11y 按 §6 缝注入，未接 = 如实记账）")
        // 自装配（§4.1 的真实调用点）：闹钟要有人接、崩溃遗留要有人重投、任务中心要有档案 ——
        // 这三件事都要求「壳在 Application 起来时就存在」，而不是等某个 Activity 顺手装配。
        // 屏幕门禁取真 PowerManager（[screenGateOf]）；引擎工厂 = [NodeProcessEngine] 真 spawn
        //（§19 Kotlin spawn 生产换线；缺件由 execute 预检点名绝对路径，比笼统"未接入"更可操作）。
        // [AppShellKit] 缺省仍是 UnavailableEngine —— JVM 配方/测试不经 Application 装配时走诚实缺省。
        //
        // 放后台线程：`onCreate` 里做文件 IO（replay 两个 jsonl + 建目录）会拖慢冷启动，
        // 而这几件事没有一件是"必须在 onCreate 返回前完成"的 —— 闹钟在那之前响就走
        // 漏投记账（[AlarmDispatch]，不丢账），装配完成后再由 [install] 接上路线。
        // §8.7 保活先于装配起：屏幕门禁的持锁判定读的就是本对象（[screenGateOf]），
        // 而门禁在装配期就被交给 dispatcher —— 装完再起会让"装配完成到保活生效"之间
        // 出现一个窗口，期间 SCREEN_ON 任务被如实拒绝（不是错，但没必要让用户撞上）。
        foregroundKeeper()
        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            installWithFiles(filesDir.toPath(), cacheDir.toPath())
        }
    }

    /**
     * 自装配装壳（[onCreate] 的落点；§4.1 Composition Root 的生产调用方）。
     *
     * 与 [install] 的分工：[install] 是"调用方已经装好了"，本条是"本类就地装" ——
     * 目录约定与持久句柄的全在这一处（[AppShellKit.assemble]），Application 只负责
     * 把 Android 侧的两件事喂进去：`filesDir`/`cacheDir` 与真屏幕门禁。
     *
     * 系统/存储/通知面经 [PlatformWiring]（shell 装配包，§6 包级例外二）接上
     * `SystemSpis` + `CapabilityNamespaces` 的真实现；**a11y/screen 同批传**——
     * `AndroidUiTree`/`AndroidGestureInput` 与 `ScreenshotSource(AndroidFrameProducer)`
     * 都经 `SystemA11yBridge` 走无障碍服务（清单在 :platform:capabilities），服务未连 =
     * 桥如实 `ERR_SERVICE_DISABLED`；screen 走 §9.2 a11y 截图路径（MediaProjection
     * 高清会话是后续升级，换 producer 即插）；走 [install] 的调用方仍可自行覆盖注入。
     *
     * 失败如实降级：装配抛错 → 记日志 + 壳保持 null（[alarmWork] 继续漏投记账），
     * **绝不让一个半装的壳冒充就绪**（那会让闹钟投给一个没有 scheduler 的路线）。
     *
     * @return 装好的壳；null = 装配失败（已记日志，调用方可重试）。
     */
    fun installWithFiles(filesDir: Path, cacheDir: Path): AppShell? {
        return try {
            // 闹钟出口只建一次（[installAlarmPort] 先装过就用那份：取消/续排路径按同一份撤销）。
            val port = alarmPort ?: AndroidAlarmPort(applicationContext, AlarmReceiver::class.java)
                .also { alarmPort = it }
            val appContext = applicationContext
            val wiring = PlatformWiring.of(appContext)
            // §7.5 生产桥监听：**bind 必须赶在 assemble 前** —— FixedEnginePool.init 是
            // eager 构造引擎（engineFactory 闭包在 init 时就捕获 hostSocketName）。
            // 绑定失败 = 离线降级：不注入名 → spawn 无 socket env → main.cpp stderr 提示，
            // 绝不注入一个绑不上的名字（那会触发 exit 3 硬失败）。名按 uid 隔离（见 defaultName）。
            if (bridgeListener == null) {
                val uid = Process.myUid()
                bridgeListener = BridgeSocketListener.bind(
                    BridgeSocketListener.defaultName(uid),
                    AndroidBridgeBinder,
                    uid,
                )
                if (bridgeListener == null) {
                    Log.w(TAG, "桥监听绑定失败（abstract 名被抢/权限）：本次装配走离线 spawn（不注入 hostSocketName）")
                }
            }
            val bridge = bridgeListener
            // §19 Kotlin spawn 生产装配：jniLibs 交付位的宿主（命名随打包管线，缺位预检点名 ——
            // 这一行就是接线点）。socket 名 = 桥监听**绑定成功才注入**（离线降级见上）；
            // addon 仍 null = 不预载（脚本照跑，桥调用点如实 ERR_ENGINE_STOPPED，不悬挂）。
            val nativeDir = Path.of(applicationInfo.nativeLibraryDir)
            val built = AppShellKit.assemble(
                filesDir = filesDir,
                cacheDir = cacheDir,
                schedulerProvider = AlarmSchedulerProvider(port = port),
                screenGate = screenGateOf(this),
                engineFactory = { engineId ->
                    NodeProcessEngine(
                        engineId,
                        NodeEngineConfig(
                            filesDir = filesDir,
                            hostBinary = nativeDir.resolve("libnoden.so"),
                            libnodePath = nativeDir.resolve("libnode.so"),
                            hostSocketName = bridge?.socketName,
                            // facade 落位根（§12.4）：引擎按 bootstrap.js 在位与否决定
                            // 注入与否（选填纪律）—— 这里只给"应该在哪"，落位归 assemble。
                            bridgeDistPath = ScriptPaths.autoModuleRoot(filesDir),
                            // addon 落位（§19 交付轨）：同一条选填纪律 —— 引擎按文件在位
                            // 决定注入与否，这里只给"应该在哪"，落位归 assemble（assets→
                            // BridgeAddonDeploy）。文件从没落过 = 不注入，脚本照跑。
                            addonPath = ScriptPaths.bridgeAddonFile(filesDir),
                        ),
                    )
                },
                // 首批内置脚本（§9.6 `assets/scripts/<projectId>/`）：枚举 + 按需读，
                // 补部署只补缺不覆盖 —— 用户"清除数据"后重装配时缺的脚本从这里回来。
                scriptProjects = try {
                    appContext.assets.list("scripts")?.toList() ?: emptyList()
                } catch (_: Exception) {
                    emptyList()      // 枚举失败 = 无资产来源（不炸装配，deployReport 如实为空）
                },
                assetReader = { projectId ->
                    com.autoscript.appservice.scriptrepo.assets.AndroidAssetsSource(
                        appContext.assets, projectId,
                    ).readScripts()
                },
                // facade dist（§12.4 资产交付轨）：`assets/bridge-dist/` 全量读成扁平 map。
                // 枚举或任一读失败 = 整体空 map（**宁可这次不落，不可半量落**：半量 + 孤儿
                // 清理会把"读失败那个文件"当成旧版删掉）—— bridgeDistReport 如实为空。
                bridgeDist = try {
                    val names = appContext.assets.list("bridge-dist")?.toList() ?: emptyList()
                    names.associateWith { name ->
                        appContext.assets.open("bridge-dist/$name").use { it.readBytes() }
                    }
                } catch (_: Exception) {
                    emptyMap()
                },
                // bridge addon（§19 交付轨）：单文件资产，没货 = null（不注入的诚实缺省，
                // 不是"空文件注入"）。读失败与没货同形 —— 引擎侧缺文件降级，不半装。
                bridgeAddon = try {
                    appContext.assets.open("bridge-addon/bridge_native.node").use { it.readBytes() }
                } catch (_: Exception) {
                    null
                },
                // 能力面生产装配（§12.2）：shell 装配包的 PlatformWiring 拿
                // SystemSpis + CapabilityNamespaces 拼成注入束 —— 本类（根包）只调它，
                // 不 import 任何 com.autoscript.platform..（ArchitectureTest 看住）。
                a11yHandler = wiring.a11yHandler,
                screenHandler = wiring.screenHandler,
                datastoreHandler = wiring.datastoreHandler,
                zipHandler = wiring.zipHandler,
                settingsHandler = wiring.settingsHandler,
                notificationHandler = wiring.notificationHandler,
                systemHandlers = wiring.systemHandlers,
            )
            // accept 开 serve：壳 router 就绪才收（bind 与 start 之间的入连接在内核 backlog
            // 排队，start 后取用）；必须在 install 前 —— install 后闹钟路线即通，执行体可能
            // 随时 spawn 来连。
            bridge?.start(built.shell)
            install(built.shell)
            assembled = built
            built.shell
        } catch (t: Throwable) {
            Log.e(TAG, "壳自装配失败：保持未就绪（闹钟走漏投记账，不伪造投递）", t)
            null
        }
    }

    /**
     * 装壳（engineFactory 由 Android 侧注入；屏幕门禁取真 PowerManager）。
     * 幂等：重复 install 只替换旧壳，[AlarmDispatch] 的路线随之换新。
     */
    fun install(shell: AppShell) {
        this.shell = shell
        alarmDispatch.install(SchedulerAlarmRoute(shell.scheduler))
        Log.i(TAG, "壳就绪：闹钟路线接通（dispatch.missed=${alarmDispatch.missed().size}）")
        // 开机恢复（§8.5）：壳就绪后把崩溃遗留意向重新入队。挂后台协程、不阻塞装配；
        // 恢复走 dispatcher 真投递（落引擎 + 写归档），失败只记日志 —— 绝不让恢复异常
        // 把刚装好的壳掀翻（装配完成 > 恢复成功，恢复下次启动仍可重试：未 COMMIT 行还在）。
        recover(shell)
    }

    /**
     * 崩溃恢复（§8.5）：壳就绪后把未 COMMIT 的意图重新入队。
     *
     * 在 [install] 里同步起协程而不是在挂起函数里 await：`install` 的签名是同步的
     * （调用方是 Activity/引导页的装配路径，不是协程），而恢复是挂起的
     * （读意图日志 + dispatch）。壳已经**先**装上了 —— 这一步失败也只是恢复没跑，
     * 闹钟路线/壳都还在，不会出现"恢复异常把 install 打断、壳装了一半"的形态。
     *
     * 幂等由 [BootRecovery] 保证（同一个壳只恢复一次）；异常不外抛（记进快照，
     * 由 [recoverySnapshot] 让 UI 如实呈现）。这里只负责把结果写进日志 ——
     * 开机日志是排查"重启后任务没跑"的唯一现场，宁可在 logcat 里多一行。
     */
    private fun recover(shell: AppShell) {
        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val snapshot = try {
                bootRecovery.recoverOnce(shell)
            } catch (t: Throwable) {
                // BootRecovery 自己兜住异常，这里是双保险：调度器抛出的任何东西
                // 都不该把开机流程变成崩溃。
                Log.e(TAG, "开机恢复抛出异常（已吞，见 recoverySnapshot）", t)
                return@launch
            }
            Log.i(TAG, "开机恢复：${snapshot.describe()}")
        }
    }

    /**
     * 恢复账（§8.5）：重投了几条、几条过期未投、是否失败。
     *
     * 能力中心据此显示"重启后恢复了 N 条任务"，而不是让用户以为重启把任务吃掉了。
     * 失败时 [RecoverySnapshot.ok] 为 false、[RecoverySnapshot.describe] 给出原因
     * —— **不**因为恢复失败就假装无事发生。
     */
    fun recoverySnapshot(): RecoverySnapshot = bootRecovery.snapshot()

    /**
     * 权限门禁（§9.5 唯一权限入口的生产实例）。
     *
     * 懒建 + 缓存：查询表是 lambda 直读（结论不缓存，每次 `state()` 重新问系统），
     * 建一次复用即可。能力中心 UI / 脚本桥的门禁查询都走这里 —— 不再各自拼查询。
     */
    fun permissionCenter(): com.autoscript.appservice.permissioncenter.PermissionCenter =
        gates ?: AndroidPermissionGates.permissionCenterOf(applicationContext).also { gates = it }

    /**
     * 保活编排（§8.7）：懒建一次并**立即尝试起保活**。
     *
     * 起失败（后台启动受限/权限被收回）**不抛**：保活失败的应用仍应能跑前台任务，
     * 只是 `SCREEN_ON` 任务被如实拒绝 + 能力中心的「保活」显示未生效 ——
     * 而不是开机就崩。`keeper.isActive()` 是能力中心该读的东西。
     */
    fun foregroundKeeper(): ForegroundKeeper = keepAlive ?: synchronized(this) {
        keepAlive ?: run {
            val ops = AndroidForegroundOps.forApplication(
                applicationContext,
                AutoScriptForegroundService::class.java,
                // 通知点开去哪：`:app` 不认识 `:ui` 的 MainActivity，类名只能由这里给。
                contentActivity = launcherActivityOrNull(),
            )
            val keeper = ForegroundKeeper(ops, WakeLockLedger(AndroidWakeLockOps(applicationContext)))
            ForegroundHost.keeper = keeper
            if (!keeper.start()) {
                Log.w(TAG, "保活未生效：服务拉不起或唤醒锁取不到（SCREEN_ON 任务将被如实拒绝）")
            }
            keeper.also { keepAlive = it }
        }
    }

    /**
     * 保活是否真在跑（§8.7）：**系统事实 ∧ 账本持锁**，两侧都真才算（见 [ForegroundKeeper.isActive]）。
     * 能力中心「保活/电源」那一行读这里；不许乐观（"请求过"不是"生效了"）。
     */
    fun keepAliveActive(): Boolean = keepAlive?.isActive() ?: false

    /**
     * launcher Activity 的类名（通知点开用）。
     *
     * 走 `PackageManager` 查 LAUNCHER intent 而不是写死 `com.autoscript.ui.MainActivity`：
     * `:app` 不认识 `:ui`（依赖方向是 app→ui 只为打包，源码零 import），写死类名等于把
     * 呈现层的类名焊进装配层 —— 换个入口类就得改两处，且编译器不会提醒。
     * 查不到（无 launcher 声明）= null，通知点不开但服务照常保活（不编一个假 Intent）。
     */
    private fun launcherActivityOrNull(): Class<*>? = try {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        val component = intent?.component
        if (component == null || component.packageName != packageName) {
            null
        } else {
            Class.forName(component.className)
        }
    } catch (t: Throwable) {
        Log.w(TAG, "查 launcher Activity 失败：通知点不开（保活本身不受影响）", t)
        null
    }

    /** 降级中的定时任务（`AlarmSchedulerProvider.degradedTasks` 的只读视图；能力中心标「可能偏差」用）。 */
    fun degradedAlarmTasks(): Map<String, Long> =
        (shell?.schedulerProvider as? AlarmSchedulerProvider)?.degradedTasks() ?: emptyMap()

    /** 壳（null = 未就绪）。UI/能力中心据此如实显示"调度未就绪"，不假装可用。 */
    fun shell(): AppShell? = shell

    /**
     * 宿主摘要（[HostSummary] 的生产实现；`:ui` 首屏经 `as? HostSummary` 读，不反向 import 本类）。
     * 现取不缓存：装配在 IO 域异步完成，缓存会把"装配完成"钉死在首读那一刻（首读大概率
     * 还是装配中，回前台重读才看得到变化 —— 见 MainActivity onResume）。
     */
    override fun shellSummary(): ShellSummary = ShellSummary(
        shellReady = shell != null,
        missedAlarms = alarmDispatch.missed().size,
        // §8.7：保活两侧都真才算（见 ForegroundKeeper.isActive）；未建时如实 false。
        keepAliveActive = keepAliveActive(),
    )

    /**
     * 能力中心快照（[HostSummary] 的生产实现，§9.5）。
     *
     * **全量枚举**：`Capability.entries` 逐项问系统（[permissionCenter] 每次直读不缓存）——
     * 只列异常项会让用户以为其余能力不存在。引导文案取 `PermissionCenter.guideText`
     * 的同一份，不在这里另写一套（两套文案必然漂移）。
     *
     * **读失败抛**（不吞成全 DENIED 的假快照）：`:ui` 据此如实显示「读能力态失败」——
     * 让用户以为授权全丢了，比不显示更糟。
     */
    override suspend fun capabilityCenter(): CapabilityCenterSnapshot =
        CapabilityCenterRead.snapshot(
            facade = permissionCenter(),
            // 降级任务账（§8.6「可能偏差」）：键排序只为让 UI 上的顺序稳定，不改账本语义。
            degradedAlarmTaskIds = degradedAlarmTasks().keys.sorted(),
        )

    /**
     * 一键跳转（能力中心「去授权」）：转给 [permissionCenter] 的 launcher。
     * 本类不做判断（去哪一页是 `AndroidGrantLauncher.pageFor` 的事）。
     */
    override fun openCapabilitySettings(capability: Capability) {
        permissionCenter().openSystemSettings(capability)
    }

    /**
     * 任务中心快照（[HostSummary] 的生产实现，§8.6/§8.5）。
     *
     * **壳没装好就抛**（不返回空快照）：空快照长得像"一条任务都没有"，而用户看到的会是
     * 自己的定时任务凭空消失 —— 那是比"读失败"严重得多的谎。抛出去由 `:ui` 如实显示，
     * 文案里点名"壳未装配"（与首屏的 `ShellSummary.shellReady` 是同一条事实的两种说法：
     * 首屏答"装配到哪一步了"，这里答"所以任务读不到"）。
     *
     * 读的是 [AppShellKit.AssembledShell.taskCenter]（壳自己持有的两个寄存器），
     * 不让 UI 另开一份 `FileTaskStore`/`FileRunArchive`（第二个实例 = 写侧两份视图）。
     * 恢复账取 [recoverySnapshot]（[BootRecovery] 的账）：它答的是"重启后那些遗留任务
     * 怎么样了"，与任务列表是两件事，分列在快照里。
     */
    override suspend fun taskCenter(): TaskCenterSnapshot {
        val built = assembled
            ?: throw IllegalStateException("壳未装配（装配中或失败）：任务与执行记录暂不可读")
        return built.taskCenter { recoverySnapshot() }
    }

    /**
     * 控制台快照（§7.3 seq 游标拉取）。
     *
     * **壳没装好就抛**（与 [taskCenter] 同一条纪律）：返回一份空快照长得像"暂无日志"，
     * 而事实是"根本没读到" —— 用户会以为脚本安静地什么都没输出。
     *
     * 读的是 [AppShellKit.AssembledShell.consoleView]（壳持有的收集器与在途表），
     * 不让 UI 另开收集器（第二个收集器收不到桥上的行）。
     */
    override suspend fun console(sinceSeq: Long, maxLines: Int): ConsoleSnapshot {
        val built = assembled
            ?: throw IllegalStateException("壳未装配（装配中或失败）：控制台暂不可读")
        return built.consoleView(sinceSeq, maxLines)
    }

    /**
     * 登记任务（[HostSummary] 的生产实现，§8.6 操作面「登记」）。
     *
     * **壳没装好就抛**（与 [taskCenter]/[console] 同一条纪律）：静默返回一个"假 id"
     * 会让用户以为任务已入册 —— 那是比报错严重得多的谎。
     * 写的是壳自己持有的调度器（store-first 落盘），不让 UI 另开 `FileTaskStore`。
     */
    override suspend fun registerTask(registration: TaskRegistration): String {
        val built = assembled
            ?: throw IllegalStateException("壳未装配（装配中或失败）：任务不可登记")
        return built.registerTask(registration)
    }

    /**
     * 取消任务（[HostSummary] 的生产实现，§8.6 操作面「取消」）。
     * 壳没装好就抛（同 [registerTask]）；幂等语义在 `Scheduler.cancel`。
     */
    override suspend fun cancelTask(taskId: String) {
        val built = assembled
            ?: throw IllegalStateException("壳未装配（装配中或失败）：任务不可取消")
        built.cancelTask(taskId)
    }

    /**
     * 立即执行（[HostSummary] 的生产实现，§8.6 操作面「立即执行」，`USER_CLICK`）。
     * 壳没装好就抛；任务不存在/调度已收口由 [AppShellKit.AssembledShell.runTaskNow]
     * 现查后抛（`onTrigger` 对两者静默 return，不查会把 no-op 呈现成"已触发"）。
     */
    override suspend fun runTaskNow(taskId: String) {
        val built = assembled
            ?: throw IllegalStateException("壳未装配（装配中或失败）：无法立即执行")
        built.runTaskNow(taskId)
    }

    /** 漏投账本（能力中心呈现「闹钟已响但调度未就绪」）。 */
    fun missedAlarms(): Map<String, Long> = alarmDispatch.missed()

    /** 取走漏投记录并清账（用户看过一次后不再重复提示）。 */
    fun drainMissedAlarms(): Map<String, Long> = alarmDispatch.drainMissed()

    /**
     * 闹钟出口（[AlarmSchedulerProvider] 的底座）。装配层在登记任务时先 [installAlarmPort]，
     * 再把 provider 交给 [Scheduler][com.autoscript.appservice.scheduler.core.Scheduler.schedule]，
     * 否则闹钟只落在账本里、进程一退就再也不会响。
     */
    fun installAlarmPort(port: AlarmPort) {
        alarmPort = port
        Log.i(TAG, "闹钟出口就绪：${port.javaClass.simpleName}（exact=${port.canScheduleExact}）")
    }

    /**
     * 投递入口（[AlarmReceiver] 的下一跳）。跑在广播的 `goAsync()` 窗口里，
     * 所以 [done] 必须调一次 —— 漏投记账那条路也不例外（不 finish 会挂着直到超时）。
     */
    fun alarmWork(taskId: String, done: () -> Unit) {
        val current = shell
        if (current == null) {
            Log.w(TAG, "闹钟响了但壳未就绪：taskId=$taskId（记账不投递，见 missedAlarms()）")
            // 记账是同步的，广播窗口内即可 finish；后续若装了壳，重投由调度器自己的排期负责。
            alarmDispatch.recordMissed(taskId)     // 同步记账（装配前的漏投入口）
            done()
            return
        }
        // fire 是挂起函数：在广播的 goAsync 窗口内起协程，保证 onTrigger 走完（意图日志落行）。
        kotlinx.coroutines.GlobalScope.launch {
            try {
                val delivered = alarmDispatch.fire(taskId)
                if (!delivered) Log.w(TAG, "闹钟回投无路线：taskId=$taskId（已计入漏投）")
            } catch (t: Throwable) {
                Log.e(TAG, "闹钟回投失败：taskId=$taskId", t)
            } finally {
                done()
            }
        }
    }

    /**
     * 进程终止收口（§8.7）：停保活、放唤醒锁。
     *
     * **诚实说明**：真机上 `onTerminate` **不会被调用**（进程被杀时没有回调），
     * 所以它不是"锁一定被释放"的保证 —— 系统在进程死亡时会回收它持有的 wakelock，
     * 这才是真机上的兜底。这里保留实现是为了两件事：① 测试/模拟器进程里显式收口，
     * 不留悬挂 ticker；② 让"谁负责停"在代码里有落点（而不是靠"系统会回收"这条隐含假设）。
     */
    override fun onTerminate() {
        keepAlive?.stop()
        keepAlive = null
        ForegroundHost.keeper = null
        super.onTerminate()
    }

    companion object {
        private const val TAG = "AppShellApplication"

        /** 静态注册的接收器拿不到壳实例（进程可能刚重建）：从这里取当前宿主。 */
        @Volatile
        internal var instance: AppShellApplication? = null
            private set

        /**
         * 屏幕门禁的生产实现（真 PowerManager + 真持锁判定）。见 [AndroidScreenGate.of]。
         *
         * `deferWakeLock` 取 [WakeLockLedger.isHeld]（账本与系统两侧都真）：
         * §8.7 原先那条"缝默认恒真 = 明写的待接"在此收口 —— 拿不到锁时 `SCREEN_ON`
         * 任务如实被拒，而不是在一个会休眠的 CPU 上跑完还报成功。
         */
        fun screenGateOf(app: AppShellApplication): ScreenGateAndroid =
            AndroidScreenGate.of(
                app.applicationContext,
                deferWakeLock = ScreenInteractive { app.foregroundKeeper().wakeLocks().isHeld() },
            )

        /** 闹钟广播 action（与 manifest 里静态注册的是同一条，常量出处 [AlarmFires]）。 */
        const val ALARM_ACTION: String = AlarmFires.ACTION_FIRE
    }
}
