package com.autoscript

import android.app.Application
import android.util.Log
import com.autoscript.shell.AlarmDispatch
import com.autoscript.shell.AlarmFires
import com.autoscript.shell.AlarmPort
import com.autoscript.shell.AlarmReceiver
import com.autoscript.shell.AlarmSchedulerProvider
import com.autoscript.shell.AndroidAlarmPort
import com.autoscript.shell.AndroidPermissionGates
import com.autoscript.shell.AndroidScreenGate
import com.autoscript.shell.AppShell
import com.autoscript.shell.AppShellKit
import com.autoscript.shell.SchedulerAlarmRoute
import com.autoscript.shell.ScreenGateAndroid
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
 * §8.7 的 PowerManager 持锁（wake lock）不在这里假装接线：`deferWakeLock` 缝在
 * [AndroidScreenGate.of] 里默认恒真，真锁的获取/释放随 FGS 路径落地。当前状态下
 * 熄屏任务的 `SCREEN_ON` 会**如实拒绝**而不是"锁也拿不到却照样跑"
 * （那条路径的表现是"任务成功、实际什么都没发生"）。
 */
class AppShellApplication : Application() {

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

    /** 闹钟出口（真 AlarmManager）：本类持有引用，供取消/续排路径按 taskId 撤销。 */
    private var alarmPort: AlarmPort? = null

    /** 门禁生产实例缓存（见 [permissionCenter]；查询本身不缓存）。 */
    @Volatile
    private var gates: com.autoscript.appservice.permissioncenter.PermissionCenter? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "装配启动：壳创建中（引擎/a11y 按 §6 缝注入，未接 = 如实记账）")
        // 自装配（§4.1 的真实调用点）：闹钟要有人接、崩溃遗留要有人重投、任务中心要有档案 ——
        // 这三件事都要求「壳在 Application 起来时就存在」，而不是等某个 Activity 顺手装配。
        // 屏幕门禁取真 PowerManager（[screenGateOf]）；引擎工厂走 [AppShellKit] 的诚实缺省
        // （native 宿主未落地 → 每次执行如实 CRASHED + 真原因，见 UnavailableEngine）。
        //
        // 放后台线程：`onCreate` 里做文件 IO（replay 两个 jsonl + 建目录）会拖慢冷启动，
        // 而这几件事没有一件是"必须在 onCreate 返回前完成"的 —— 闹钟在那之前响就走
        // 漏投记账（[AlarmDispatch]，不丢账），装配完成后再由 [install] 接上路线。
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
     * **不传 a11y/screen 能力 handler**：`:app` 不得直连 `:platform`（§6），而持有真实现的
     * 只能是本类的调用方（Activity 从 `:platform:capabilities` 拿到 `NamespaceHandler` 后
     * 走 [install] 注入）。未注入 = 桥对 `a11y.*`/`screen.*` 如实回 `ERR_NOT_IMPLEMENTED`，
     * 不伪造可用。
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
            val built = AppShellKit.assemble(
                filesDir = filesDir,
                cacheDir = cacheDir,
                schedulerProvider = AlarmSchedulerProvider(port = port),
                screenGate = screenGateOf(this),
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
            )
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
        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val recovered = shell.bootRecover()
                if (recovered.isNotEmpty()) {
                    Log.i(TAG, "开机恢复：${recovered.size} 条遗留意向已重投")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "开机恢复失败（下次启动重试）", t)
            }
        }
    }

    /**
     * 权限门禁（§9.5 唯一权限入口的生产实例）。
     *
     * 懒建 + 缓存：查询表是 lambda 直读（结论不缓存，每次 `state()` 重新问系统），
     * 建一次复用即可。能力中心 UI / 脚本桥的门禁查询都走这里 —— 不再各自拼查询。
     */
    fun permissionCenter(): com.autoscript.appservice.permissioncenter.PermissionCenter =
        gates ?: AndroidPermissionGates.permissionCenterOf(applicationContext).also { gates = it }

    /** 降级中的定时任务（`AlarmSchedulerProvider.degradedTasks` 的只读视图；能力中心标「可能偏差」用）。 */
    fun degradedAlarmTasks(): Map<String, Long> =
        (shell?.schedulerProvider as? AlarmSchedulerProvider)?.degradedTasks() ?: emptyMap()

    /** 壳（null = 未就绪）。UI/能力中心据此如实显示"调度未就绪"，不假装可用。 */
    fun shell(): AppShell? = shell

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

    companion object {
        private const val TAG = "AppShellApplication"

        /** 静态注册的接收器拿不到壳实例（进程可能刚重建）：从这里取当前宿主。 */
        @Volatile
        internal var instance: AppShellApplication? = null
            private set

        /** 屏幕门禁的生产实现（真 PowerManager）。见 [AndroidScreenGate.of]。 */
        fun screenGateOf(app: AppShellApplication): ScreenGateAndroid =
            AndroidScreenGate.of(app.applicationContext)

        /** 闹钟广播 action（与 manifest 里静态注册的是同一条，常量出处 [AlarmFires]）。 */
        const val ALARM_ACTION: String = AlarmFires.ACTION_FIRE
    }
}
