package com.autoscript.shell

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.autoscript.platform.system.power.WakeLockLedger

/**
 * 前台服务接触面（docs §8.7 保活）：唯一碰 `startForegroundService` /
 * `Service.startForeground` / `NotificationManager` 的地方。判断（什么时候起、
 * 为什么起、失败怎么记账）全在 [ForegroundKeeper]；这里只做系统调用搬运 +
 * 把系统事实折成契约形状。
 *
 * 四个方法的返回值都是**真值**，没有假成功：
 * - [startService] false = 系统拒绝拉起（缺权限/后台启动受限），调用方据此不记账；
 * - [stopService] false = 当时并没有在跑（幂等路径）；
 * - [activateForeground] false = `startForeground` 抛了（缺类型/权限被收回），
 *   服务据此**不自称保活**；
 * - [foregroundRunning] = 系统事实（服务自己写下的"我确实进了前台"），
 *   是 [ForegroundKeeper.isActive] 的一半判据 —— 不是"我请求过"。
 */
interface ForegroundOps {
    /**
     * 拉起保活服务，并把**本次持有的契约**（token + 期限）随 Intent 一起送进去。
     *
     * 为什么必须是参数（而不是让服务自己去问）：服务实例由系统创建，装配期的对象图
     * 只有 Application 有（见 [ForegroundServiceBase] 的 KDoc），Intent 是唯一通道。
     * 而契约缺任何一项，服务侧只能**拒收**（不许凭空编一个 token 去记账），代价是那条
     * `startForegroundService` 的窗口没人认领 —— **系统会连整个进程一起杀掉**
     * （2026-10-02 真机实测：界面在前台、进程 30 秒后被 ActivityManager 带下，日志
     * `Bringing down service while still waiting for start foreground`）。
     * 所以这两个参数是保活能否成立的前提，不是可选装饰。
     *
     * @param token 持有方标识（进 [ForegroundKeeper] 的账本）。
     * @param timeoutMillis 到期自动释放的期限；`null` = 无期限（框架 token 的形态）。
     */
    fun startService(token: String, timeoutMillis: Long?): Boolean
    fun stopService(): Boolean

    /** 服务侧调用：把自己提进前台（常驻通知 + specialUse 类型）。 */
    fun activateForeground(): Boolean

    /** 服务侧调用：退出前台（撤常驻通知）。 */
    fun deactivateForeground(): Boolean

    /** 系统此刻是否真把本服务放在前台（服务 `startForeground` 成功且未退）。 */
    val foregroundRunning: Boolean
}

/**
 * 进程内保活信箱（§8.7）：装配层与保活服务之间唯一的共享点。
 *
 * **为什么需要它**：服务实例由系统创建，[ForegroundKeeper] 由 Application 创建 ——
 * 两者不在同一处构造，唯一共享的上下文是进程。这里只放引用与一个标记，**不含逻辑**。
 *
 * 不写成 `AppShellApplication` 的成员再让服务 `application as?` 取：那会让服务基类
 * 依赖根包（根包已依赖 shell 装配包 → 成环），而且服务拿不到的是"装配期的对象图"，
 * 不是一个 Application 引用就能解决的。
 *
 * `@Volatile`：写在 IO 域（装配）/主线程（服务回调），读在任意线程（门禁查询）。
 */
object ForegroundHost {
    /** 装配产物（null = 装配未完成/失败：服务收到指令只记日志，不假装已保活）。 */
    @Volatile
    var keeper: ForegroundKeeper? = null

    /**
     * 服务是否真的在前台（[ForegroundOps.foregroundRunning] 的 Android 侧存储）。
     *
     * 由 [AndroidForegroundOps] 在 `startForeground` 成功后写 true、退前台/被销毁写 false。
     * **不放在 Keeper 里**：这是系统事实，Keeper 那边记的是"我请求过"；
     * 把两者合并成一个布尔正是要避免的那种撒谎（请求过 ≠ 真在前台）。
     */
    @Volatile
    var foregroundRunning: Boolean = false
}

/**
 * 保活服务的宿主基类（§8.7）：只做无判断的搬运 —— `onStartCommand` 的意图交给
 * [ForegroundKeeper]，`onDestroy` 折成一次如实上报。
 *
 * **为什么参数（token/超时）走 [Intent] extra 而不是服务字段**：服务实例由系统创建，
 * 装配层拿不到它的引用，Intent 是唯一能把装配期决定送进去的通道。服务**不自己装配**
 * （不 new Keeper、不查壳）—— 装配期的对象图只有 Application 有，服务里再装一套
 * 就会有两个账本（互相看不见对方持着锁，见 [WakeLockLedger] 的引用计数理由）。
 *
 * **`START_NOT_STICKY`**：被系统杀掉后不自动重启 —— 重启起来的服务没有 Keeper 上下文
 * （Application 可能还没装配完），起来也只能空转。保活续期走 `Application.onCreate`
 * 的正常装配路径（与 `BootReceiver` 同一条纪律：触发器只负责"进程起来"，装配只走一条路）。
 *
 * **先 `startForeground` 再记账**（顺序不可反）：`startForegroundService` 拉起服务后，
 * 系统给 5 秒窗口要求服务进入前台，超时即 **ANR/连进程一起杀**（不是"服务起不来"这么轻
 * —— 2026-10-02 真机实测：界面在前台，30 秒后进程消失）。所以本类**任何**一条
 * ACTION_START 分支都要走到 `activateForeground()`，包括契约不完整那条（保命优先，
 * 记账另说）。而记账（取 wakelock）是本地操作、
 * 不会失败在这个窗口上；反过来先记账再进前台，一旦进前台失败就会留下"账上说保活、
 * 实际不在前台"的假账。
 */
abstract class ForegroundServiceBase : Service() {

    private val ops: ForegroundOps by lazy { AndroidForegroundOps.forService(this) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val keeper = ForegroundHost.keeper
        when (intent?.action) {
            ForegroundKeeper.ACTION_START -> {
                val token = intent.getStringExtra(ForegroundKeeper.EXTRA_TOKEN)
                // 期限三态，别压成一个布尔：**-1 = extra 压根没来**（契约不完整）；
                // **0 = 无期限**（框架 token 的形态）；>0 = 到期时刻。`WakeLockLedger.hold`
                // 只接受 null 或 >0，把"无期限"错判成非法会让框架保活永远起不来 ——
                // 而"永远起不来"正是本类 2026-10-02 之前的状态。
                val timeout = intent.getLongExtra(
                    ForegroundKeeper.EXTRA_TIMEOUT_MILLIS,
                    ForegroundKeeper.TIMEOUT_MISSING,
                )
                when {
                    token.isNullOrBlank() || timeout == ForegroundKeeper.TIMEOUT_MISSING -> {
                        // 契约不完整。**但不能就这么放着**：这条 Intent 是
                        // startForegroundService 投的，不 startForeground 系统就会连进程一起杀
                        // （真机实测：界面在前台也照杀）。所以照样进前台——保命，但
                        // **不记账**（`isActive()` 因账本为空回 false，能力中心如实显示未生效）。
                        if (ops.activateForeground()) {
                            Log.w(TAG, "ACTION_START 缺 token/期限：已进前台保命，但不记账（不假装保活）")
                        } else {
                            Log.e(TAG, "ACTION_START 缺 token/期限且进前台失败：本进程随即会被系统回收")
                        }
                    }
                    !ops.activateForeground() -> {
                        // 进前台失败：如实记账（keeper.isActive 会因此为 false），
                        // 而**不是**让 Application 以为保活已生效。
                        Log.e(TAG, "进入前台失败：本次不保活（原因见上一条日志）")
                        ForegroundHost.foregroundRunning = false
                    }
                    keeper == null -> {
                        // 已在前台、装配层却没就绪：**不记账**，也**不 stopSelf** ——
                        // 停掉会让"装配完成后重试"这条路径失去落点（同本函数末尾 START_NOT_STICKY 的理由）。
                        Log.w(TAG, "已进前台但装配层未就绪：本次不记账（不假装已保活）")
                    }
                    else -> {
                        // 已在前台（上面那一步成功）→ 交 Keeper 记账（取锁 + 开 ticker）。
                        // 这里走的是**记账口**而不是 [ForegroundKeeper.start]：后者会再投一条
                        // ACTION_START，而本服务收到 START 又会记账 —— 那是个永不停止的 Intent 环。
                        // 取不到锁时它回 false 且**不记账**，此时服务在前台但 isActive() 为 false，
                        // 正是"系统在跑、我们没有休眠保护"的如实表达。
                        keeper.onServiceForeground(token, timeout.takeIf { it > 0L })
                    }
                }
            }
            ForegroundKeeper.ACTION_STOP -> {
                // keeper 缺席也要退前台：停服务是系统侧的事，与账本无关。
                keeper?.stop()
                ops.deactivateForeground()
            }
            else -> Log.w(TAG, "未知 action=${intent?.action}：忽略")
        }
        return START_NOT_STICKY
    }

    /**
     * 系统销毁服务（用户停止应用/系统回收）：服务**已经**不在前台了，
     * 这里只把"系统事实"对齐 —— 不 stopForeground（系统正在销毁，再调是白费），
     * 也不释放框架 token（框架那一路的 token 归装配层的生命周期管，
     * 服务被销毁不等于应用退出，见 [ForegroundKeeper] KDoc）。
     */
    override fun onDestroy() {
        ForegroundHost.foregroundRunning = false
        ForegroundHost.keeper?.onServiceDestroyed()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "AutoScriptForeground"
    }
}

/**
 * 生产服务类（manifest 里静态声明的那一个）。存在理由只有一个：`Service` 必须以
 * **具体类名**进清单，而逻辑住在基类（这样它可被单测读到）。清单指这里。
 */
class AutoScriptForegroundService : ForegroundServiceBase()

/**
 * [ForegroundOps] 的真机实现（§8.7 specialUse 前台服务）。
 *
 * **`FOREGROUND_SERVICE_TYPE_SPECIAL_USE`**：§8.7 选型表冻结的形态 —— 自动化没有更贴切的
 * 官方类型（不是 mediaPlayback/location/dataSync 任何一个），specialUse 是唯一能如实描述
 * "用户可见的持续自动化"且**无 6 小时超时**的类型。清单里对应的
 * `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="automation" />`
 * 在 `:app` 的 AndroidManifest 里（Play 审核要看的说明）。
 *
 * **API 34+ 必须把类型作为 `startForeground` 的第三个参数传给系统**：只写在清单里不够
 * （`MissingForegroundServiceTypeException`），而两参重载在 34 上等同声明"无类型"、同样被拒。
 * 低版本走两参重载（类型那时只从清单读）。
 *
 * **一个类两个实例**：装配侧（[forApplication]：`Context` + 服务类 + 内容 Activity，用于
 * 拉起/停止服务）与服务侧（[forService]：`Service` 实例，用于进出前台）。两者**无状态**
 * —— 唯一的共享状态是 [ForegroundHost]，因此两份实例等价于一份，不需要单例。
 *
 * **本类不碰 androidx**（不用 `ContextCompat`/`ServiceCompat`）：这里只需要
 * `startForegroundService`（API 26+，minSdk 即 26）与 `startForeground` 两个裸调用，
 * 而 API 34 的类型参数恰恰**要求调用点按 `SDK_INT` 显式给**（见上），`ServiceCompat`
 * 那层包装反倒挡在中间。裸 API 的副作用才是本类不依赖 androidx.core。
 * （2026-09-30 前这里写的理由是「本机 `tools/jvm-test.sh` 旁路没有 androidx 坐标」——
 * 该旁路已删，这条不再成立。）
 */
class AndroidForegroundOps private constructor(
    private val context: Context,
    private val serviceClass: Class<out Service>,
    private val contentActivity: Class<*>?,
    private val service: Service?,
) : ForegroundOps {

    override fun startService(token: String, timeoutMillis: Long?): Boolean = try {
        context.startForegroundService(
            Intent(context, serviceClass)
                .setAction(ForegroundKeeper.ACTION_START)
                // 契约随 Intent 走（见接口 KDoc）。不带这两项 = 服务只能拒收 = 进程被杀，
                // 而"进程被杀"表现成界面 30 秒后自己消失，现场完全看不出是保活引起的。
                .putExtra(ForegroundKeeper.EXTRA_TOKEN, token)
                .putExtra(
                    ForegroundKeeper.EXTRA_TIMEOUT_MILLIS,
                    timeoutMillis ?: ForegroundKeeper.TIMEOUT_UNLIMITED,
                ),
        )
        true
    } catch (t: Throwable) {
        // 缺 FOREGROUND_SERVICE 权限 / 后台启动受限（BAL）等：如实 false。
        Log.e(TAG, "拉起保活服务失败（后台启动受限或缺权限）", t)
        false
    }

    override fun stopService(): Boolean = try {
        context.stopService(Intent(context, serviceClass))
    } catch (t: Throwable) {
        Log.e(TAG, "停止保活服务失败", t)
        false
    }

    override fun activateForeground(): Boolean {
        val s = service ?: run {
            Log.e(TAG, "activateForeground 只许服务侧调用（本实例没有 Service）")
            return false
        }
        return try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                s.startForeground(
                    ForegroundNotifications.NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                s.startForeground(ForegroundNotifications.NOTIFICATION_ID, notification)
            }
            ForegroundHost.foregroundRunning = true
            true
        } catch (t: Throwable) {
            Log.e(TAG, "startForeground 失败（类型/权限）", t)
            ForegroundHost.foregroundRunning = false
            false
        }
    }

    override fun deactivateForeground(): Boolean {
        val wasRunning = ForegroundHost.foregroundRunning
        val s = service ?: return false
        return try {
            s.stopForeground(Service.STOP_FOREGROUND_REMOVE)
            ForegroundHost.foregroundRunning = false
            wasRunning
        } catch (t: Throwable) {
            Log.e(TAG, "stopForeground 失败", t)
            false
        }
    }

    override val foregroundRunning: Boolean get() = ForegroundHost.foregroundRunning

    private fun buildNotification(): Notification {
        val manager = context.getSystemService(NotificationManager::class.java)
        ForegroundNotifications.ensureChannel(context, manager)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, ForegroundNotifications.CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        builder
            .setContentTitle(appLabel())
            .setContentText(ForegroundNotifications.TEXT)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
        contentIntent()?.let { builder.setContentIntent(it) }
        return builder.build()
    }

    /**
     * 通知点开去哪：由装配层传 launcher 类（`:app` 不认识 `:ui` 的 `MainActivity`）。
     * 传 null = 不设内容 Intent —— 通知点不开，但服务照常保活（缺个快捷入口不是错误，
     * 编一个假的才是）。
     */
    private fun contentIntent(): PendingIntent? {
        val activity = contentActivity ?: return null
        return try {
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, activity),
                // API 31+ 要求显式可变性；自家显式 Intent 用 IMMUTABLE 更安全。
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "构造通知内容 Intent 失败（通知仍可用，只是点不开）", t)
            null
        }
    }

    private fun appLabel(): String =
        context.applicationInfo.loadLabel(context.packageManager).toString()

    companion object {
        private const val TAG = "AndroidForegroundOps"

        /** 装配侧实例（拉起/停止服务；[activateForeground] 不可用）。 */
        fun forApplication(
            context: Context,
            serviceClass: Class<out Service>,
            contentActivity: Class<*>? = null,
        ): AndroidForegroundOps =
            AndroidForegroundOps(context.applicationContext, serviceClass, contentActivity, null)

        /** 服务侧实例（进出前台；[startService]/[stopService] 同样可用，`context` = 服务自身）。 */
        fun forService(service: Service): AndroidForegroundOps =
            AndroidForegroundOps(service, service.javaClass, null, service)
    }
}

/**
 * 保活常驻通知（§8.7）：`startForeground` 必须带一条通知，API 26+ 通道也必须先存在。
 *
 * **为什么通知归实现而不是契约面**：与 `notification` 命名空间同一条纪律 ——
 * 通道 id 是应用自有资源，脚本不该操心。id 固定（`autoscript.foreground`）便于用户在
 * 系统设置里稳定找到并单独关闭（关掉通道不影响前台服务本身，只是通知不再显示）。
 *
 * 文案如实：不写"正在运行 N 个任务"这类应用层事实 —— 服务只知道"我被要求保活"。
 * 写错了就是撒谎，写含糊不如写准确。
 */
internal object ForegroundNotifications {

    const val CHANNEL_ID = "autoscript.foreground"

    /** 常驻通知 id：固定值（同号覆盖，不会堆叠）。`0x4155` = "AU"。 */
    const val NOTIFICATION_ID = 0x4155

    const val TEXT = "自动化服务运行中（前台服务保活）"

    fun ensureChannel(context: Context, manager: NotificationManager?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        manager ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.applicationInfo.loadLabel(context.packageManager).toString(),
                // LOW：常驻通知是状态标示而非提醒，不该发声/震动。
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }
}
