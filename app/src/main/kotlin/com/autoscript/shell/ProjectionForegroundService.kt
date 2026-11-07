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
import com.autoscript.platform.capabilities.device.ForegroundLease
import com.autoscript.platform.capabilities.device.ProjectionForeground
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 投屏前台服务（§9.2 / §8.7）：**独立于保活服务**的那一条 `mediaProjection` 类型 FGS。
 *
 * **为什么不并进 `AutoScriptForegroundService`**（三条，逐条都是可观测后果）：
 * 1. 生命周期互相独立 —— 保活随脚本运行在跑、投屏随一次用户同意在跑。并在一起后，
 *    用户从通知栏停掉投屏会顺手把正在跑脚本的保活前台也撤掉（反之亦然）；
 * 2. 保活服务的 `onStartCommand` 契约（token + 期限 → `WakeLockLedger` 记账）与投屏无关，
 *    硬塞进去会让"账上有 token ⇔ 真有锁"这条不变量多出一个不记账的分支；
 * 3. 通知通道独立：用户能单独关掉投屏那条常驻提示而不影响保活提示。
 *
 * （**注意**：`foregroundServiceType` 本身是 **bitmask**，一个服务声明并同时持有多种类型
 * 是合法的 —— 所以"一个服务只能有一种类型"**不是**这里的理由，上面三条才是。）
 *
 * ## 代际（generation）—— 本服务的核心契约
 * 每一次"进投屏前台"都带一个**单调递增的代际号**，它与"哪一次会话"绑定：
 * - **超时后迟到的启动自己退场**：设备层等确认超时后会调 [ProjectionForeground.abandon]，
 *   服务随后才起来时看到自己这一代已被放弃 → 立即 `stopSelf`（不留孤儿 FGS ——
 *   那种表现是"通知栏挂着正在投屏，实际没有任何会话"）；
 * - **旧代际的停止动不了新代际**：`ACTION_STOP` 必须带代际号，只有"还是这一代"才停。
 *   没有这道校验时，旧会话的迟到收口会把**新会话**的前台撤掉（表现是"重开投屏后
 *   立刻显示已停止"，而用户明明没停过）。
 *
 * ## `START_NOT_STICKY`
 * 被系统杀掉后不自动重启 —— 重启起来的服务没有投影会话（凭据是一次性的，
 * `onStartCommand` 里也拿不到），起来只能空转，而系统会以为"投屏还在"（通知栏那条
 * 提示会挂着）。停就是停，重新开会话要用户重新同意。
 *
 * 服务本体**不含判断**：它只把自己提进前台、记下"系统确认了"，停的时候如实清标记。
 * 时序（何时起、何时停）全在 [AndroidProjectionForeground] 与设备层会话。
 */
class ProjectionForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    // 本方法里两处 catch 都刻意接 `RuntimeException` 而不是收窄到某一个具体类型：
    // `startForeground` / `startForegroundService` 的失败面是**系统与厂商实现**给的
    // （缺 `FOREGROUND_SERVICE_MEDIA_PROJECTION`、类型未声明、后台启动受限、
    // 部分 ROM 抛 `SecurityException`/`IllegalArgumentException` 之外的自定义异常），
    // 而这里漏掉一个 = 服务留在半前台态 / 这一代永远等不到确认。同仓 `ForegroundOps`
    // 与 `ForegroundKeeper` 对同一类调用是同一个口径（基线里也是这么记的）。
    @Suppress("TooGenericExceptionCaught")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val generation = intent?.getLongExtra(EXTRA_GENERATION, 0L) ?: 0L
        if (intent?.action == ACTION_STOP) {
            // 走 [ProjectionForegroundHost.stopFromService] 而不是 [ProjectionForegroundHost.stop]：
            // 设备层收口（AndroidProjectionForeground.stop）在投递本 Intent 前已把账按代际
            // 清掉（"我们请求过停" ≠ "还在前台"），等服务处理到这条时 currentGeneration 已是 0 ——
            // 若按「必须正是这一代」判，服务**永远不动手**，FGS 与常驻通知就成了孤儿
            // （通知栏挂着「正在投屏」，实际没有任何会话 —— 正是本类 KDoc 要防的形态）。
            ProjectionForegroundHost.stopFromService(generation, ::stopProjectionForeground)
            return START_NOT_STICKY
        }
        if (generation <= 0L) {
            // 没有代际号的启动请求不是我们发的（或旧版本残留）——如实退场，不占前台。
            stopSelf()
            return START_NOT_STICKY
        }
        // **先 startForeground 再置标记**（顺序不可反）：`startForegroundService` 拉起后系统给
        // 5 秒窗口，超时即 ANR/连进程一起杀；而标记是"系统确认了"的事实，没进前台就不能置。
        val ok = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    ProjectionNotifications.NOTIFICATION_ID,
                    buildNotification(generation),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
                )
            } else {
                startForeground(ProjectionNotifications.NOTIFICATION_ID, buildNotification(generation))
            }
            true
        } catch (t: RuntimeException) {
            HostLog.e(TAG, "投屏前台类型进不去（缺 FOREGROUND_SERVICE_MEDIA_PROJECTION 或类型未声明）", t)
            false
        }
        if (ok && !ProjectionForegroundHost.confirm(generation)) {
            // 这一代已经被放弃/被顶掉（设备层等超时后 abandon 了，或用户已经停了投屏）：
            // 现在才起来的服务**自己退场** —— 否则就是孤儿 FGS（通知栏挂着"正在投屏"）。
            HostLog.i(TAG, "投屏前台第 $generation 代已放弃：本次启动自行退场")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!ok) stopSelf()
        return START_NOT_STICKY
    }

    /** 撤前台 + 清标记（[ProjectionForegroundHost.stop] 判定"还是这一代"后调）。 */
    private fun stopProjectionForeground() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        // 服务被系统销毁（用户停止应用/系统回收）：等确认的人若还在，如实放行"没了"。
        ProjectionForegroundHost.onServiceDestroyed()
        super.onDestroy()
    }

    private fun buildNotification(generation: Long): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        ProjectionNotifications.ensureChannel(this, manager)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, ProjectionNotifications.CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(applicationInfo.loadLabel(packageManager).toString())
            .setContentText(ProjectionNotifications.TEXT)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .addAction(ProjectionNotifications.stopAction(this, generation))
            .build()
    }

    companion object {
        private const val TAG = "ProjectionForeground"

        /** 停止指令（[AndroidProjectionForeground.stop] 投递）。 */
        const val ACTION_STOP = "com.autoscript.shell.action.PROJECTION_STOP"

        /** 代际号 extra（起/停都带；见类 KDoc 的代际契约）。 */
        const val EXTRA_GENERATION = "com.autoscript.shell.extra.PROJECTION_GENERATION"
    }
}

/**
 * 进程内投屏前台信箱（与 [ForegroundHost] 同形）：装配层与服务之间唯一的共享点。
 *
 * **状态是"当前那一代"而不是一个布尔**：一个 `running` 布尔说不出"现在前台的是第几代"，
 * 于是旧代际的停止请求会把新代际的前台撤掉。这里存 [currentGeneration]，
 * 停止/确认都带代际号比对。
 *
 * **等待者带代际**（`waiterGeneration`）：等确认的那次调用若超时放弃，它的等待者必须
 * 能被精确撤下 —— 否则一次早已超时的调用会被后到的确认（属于**另一代**）唤醒。
 */
object ProjectionForegroundHost {

    private val lock = Any()

    /** 当前已进前台的代际（0 = 没有）。 */
    private var currentGeneration: Long = 0L

    /** 已放弃的代际（超时/开投影失败）——服务迟到起来时据此自行退场。 */
    private val abandoned = HashSet<Long>()

    /** 代际号发放（单调递增；起一次发一个）。 */
    private var nextGeneration: Long = 0L

    private var waiter: ((Boolean) -> Unit)? = null
    private var waiterGeneration: Long = 0L

    /** 系统此刻是否真把本服务放在投屏前台（诊断/测试用）。 */
    val running: Boolean get() = synchronized(lock) { currentGeneration != 0L }

    /** 发一个新代际号（[AndroidProjectionForeground.start] 用）。 */
    fun nextLease(): ForegroundLease = synchronized(lock) {
        ForegroundLease(++nextGeneration)
    }

    /**
     * 设备侧：登记等待者（同一时刻只有一个 —— 会话是单例，见设备层 KDoc）。
     * 该代际**已经**在前台时立即放行 true。
     */
    fun awaitConfirmation(generation: Long, callback: (Boolean) -> Unit) {
        val immediate = synchronized(lock) {
            if (currentGeneration == generation) {
                true
            } else {
                waiter = callback
                waiterGeneration = generation
                false
            }
        }
        if (immediate) callback(true)
    }

    /**
     * 服务侧：放行等待者（恰好一次）。
     *
     * @return true = 这一代被接受（进前台有效）；false = 这一代已被放弃或已被更新的代际顶掉
     *   —— 服务据此**自行退场**，不留孤儿 FGS。
     */
    fun confirm(generation: Long): Boolean = synchronized(lock) {
        if (abandoned.remove(generation)) return false
        if (generation < currentGeneration) return false
        currentGeneration = generation
        val w = waiter.also { waiter = null }
        if (w != null && waiterGeneration == generation) w(true)
        true
    }

    /**
     * 停止指定代际的前台（**调用方侧**：投递 `ACTION_STOP` 前的预清账）。
     *
     * @return true = 前台**正是**这一代（调用方应真的撤前台）；false = 不是这一代
     *   （已被别处停掉 / 已被新会话接管）—— 如实不动手，绝不把新会话的前台拆了。
     *
     * 服务侧（`ACTION_STOP` 落点）**不要用本方法**：那时账已被调用方预清
     * （`currentGeneration == 0`），「必须正是这一代」在那里永远不成立 —— 服务侧走
     * [stopFromService]。
     */
    fun stop(generation: Long, doStop: () -> Unit): Boolean {
        val mine = synchronized(lock) {
            if (currentGeneration != generation) return false
            currentGeneration = 0L
            true
        }
        if (mine) doStop()
        return mine
    }

    /**
     * 服务侧的停止（`ACTION_STOP` 的落点）。
     *
     * 与 [stop] 的差别只在一条：设备层收口（`AndroidProjectionForeground.stop`）在投递
     * `ACTION_STOP` 之前**已经**把账按代际清掉了（注释原话：无论服务收没收到，账先按
     * 代际清掉），等服务处理这条 Intent 时 [currentGeneration] 已是 0 —— 若仍按
     * 「必须正是这一代」判，服务**永远不动手**，前台服务与常驻通知就成孤儿。
     *
     * 故本方法认两种「该停」：[currentGeneration] 正是本代（账还在 = 通知栏停止按钮
     * 那条路，没人预清），或 [currentGeneration] 为 0（本代主人已先清账）。
     * **唯一不动手**的情形：[currentGeneration] 是**别的非零代际** —— 那是旧代际迟到的
     * 收口撞上已重开的新会话，动手就把新会话的前台拆了（[stop] 的那条保护原样保留）。
     *
     * @return true = 已清账并执行了 [doStop]；false = 另一代正在前台，如实不动手。
     */
    fun stopFromService(generation: Long, doStop: () -> Unit): Boolean {
        val mine = synchronized(lock) {
            if (currentGeneration != 0L && currentGeneration != generation) return false
            currentGeneration = 0L
            true
        }
        if (mine) doStop()
        return mine
    }

    /** 放弃这一代（超时/开投影失败）：服务若迟到才起来，看到它被放弃就立即退场。 */
    fun abandon(generation: Long) {
        synchronized(lock) {
            if (currentGeneration == generation) currentGeneration = 0L
            abandoned += generation
            // **有界**：迟到的启动只可能是"最近发出去的那几代"（一次会话一个号）。
            // 不裁剪的话，每次失败的开启都往这里塞一个 Long —— 长跑进程里是条无界增长。
            if (abandoned.size > ABANDONED_KEEP) {
                abandoned.retainAll { it > nextGeneration - ABANDONED_KEEP }
            }
            if (waiterGeneration == generation) waiter = null
        }
    }

    /** 超时/放弃等待：撤下等待者（否则一个早已超时的调用会被后到的确认唤醒两次）。 */
    fun cancelWait(generation: Long) {
        synchronized(lock) { if (waiterGeneration == generation) waiter = null }
    }

    /** 服务被销毁：等确认的人如实放行"没了"；当前代际清零。 */
    fun onServiceDestroyed() {
        val w = synchronized(lock) {
            currentGeneration = 0L
            waiter.also { waiter = null }
        }
        w?.invoke(false)
    }

    /**
     * 保留多少条"已放弃"的代际号（见 [abandon]）。
     *
     * 迟到的启动只可能是最近发出去的那几代（`startForegroundService` 到
     * `onStartCommand` 是毫秒级；3 秒确认超时之后才起来的已经算很晚了）。
     * 留 64 个号足够覆盖任何真实的迟到，同时把集合钉成有界。
     */
    private const val ABANDONED_KEEP = 64L
}

/**
 * [ProjectionForeground] 的真机实现（§9.2）。
 *
 * 两个实例形态（与 [AndroidForegroundOps] 同构）：装配侧（起/停服务）与服务侧（不需要
 * —— 服务自己进前台）。本类**只做装配侧**，因为投屏前台的进出都在 `onStartCommand` 里。
 *
 * **确认是异步的，绝不阻塞调用线程**：早先的实现用 `CountDownLatch.await` 等确认，
 * 而确认信号由**主线程**上的服务发出 —— 主线程调用时就是"等自己"，必然超时。
 * 现在改成挂起（`suspendCancellableCoroutine` + `withTimeoutOrNull`），
 * 主线程调用会**让出**而不是死等；超时即 [ProjectionForeground.abandon] 放弃这一代。
 */
class AndroidProjectionForeground private constructor(
    private val context: Context,
    private val serviceClass: Class<out Service>,
    private val confirmTimeoutMillis: Long,
) : ProjectionForeground {

    // 同 [ProjectionForegroundService.onStartCommand] 的口径：`startForegroundService` 的
    // 失败面由系统/厂商实现给（后台启动受限、缺权限、ROM 自定义异常），收窄类型就会漏掉
    // 其中一支，而漏掉的后果是"这一代永远等不到确认"或"孤儿 FGS"。
    @Suppress("TooGenericExceptionCaught")
    override suspend fun start(): ForegroundLease? {
        val lease = ProjectionForegroundHost.nextLease()
        // **先登记等待者再拉起**：`startForegroundService` 之后服务可能极快地进前台，
        // 反过来的顺序会漏掉那一次确认（等的人一直挂到超时）。
        val confirmed = CompletableDeferred<Boolean>()
        ProjectionForegroundHost.awaitConfirmation(lease.generation) { confirmed.complete(it) }
        val launched = try {
            context.startForegroundService(intentFor(lease, action = null))
            true
        } catch (t: RuntimeException) {
            // 后台启动受限 / 缺权限：如实放弃这一代（服务真起来了也会自行退场）。
            HostLog.e(TAG, "拉起投屏前台服务失败（后台启动受限或缺权限）", t)
            false
        }
        if (!launched) {
            ProjectionForegroundHost.abandon(lease.generation)
            return null
        }
        // 等到的是一个**系统事实**（服务侧 `startForeground` 成功了没有），不是"我请求过"。
        val ok = withTimeoutOrNull(confirmTimeoutMillis) { confirmed.await() } ?: false
        if (!ok) {
            // 超时/失败：撤下等待者并**明确放弃**这一代 —— 服务若迟到才起来会自己退场。
            ProjectionForegroundHost.cancelWait(lease.generation)
            ProjectionForegroundHost.abandon(lease.generation)
            HostLog.e(TAG, "投屏前台服务 ${confirmTimeoutMillis}ms 内未确认进前台：本次不开投影")
            return null
        }
        return lease
    }

    // 同上：`startService` 在后台受限路径上的抛法由系统给（部分 ROM 直接抛
    // `IllegalStateException` 的子类），收窄就会漏掉一支 —— 漏掉的后果是"以为停掉了，
    // 其实前台还挂着"，那正是本方法要防的孤儿 FGS。
    @Suppress("TooGenericExceptionCaught")
    override fun stop(lease: ForegroundLease): Boolean {
        // 带代际投递：只有前台**还是这一代**时服务才动手（旧代际的迟到收口停不掉新会话）。
        return try {
            context.startService(intentFor(lease, action = ProjectionForegroundService.ACTION_STOP))
            // 服务可能在后台被拒（`startService` 返回 null 不抛）；无论服务收没收到，
            // 本进程的账先按代际清掉 —— 状态与"我们请求过停"一致，不会误判"还在前台"。
            ProjectionForegroundHost.stop(lease.generation) {}
        } catch (t: RuntimeException) {
            // 后台启动被拒：服务收不到 ACTION_STOP，那就**明确放弃**这一代 ——
            // 服务若还活着，下一次 confirm 会看到放弃标记并自行退场（不留孤儿 FGS）。
            HostLog.e(TAG, "停止投屏前台服务失败（后台启动受限）：改为放弃这一代", t)
            ProjectionForegroundHost.abandon(lease.generation)
            false
        }
    }

    override fun abandon(lease: ForegroundLease) {
        ProjectionForegroundHost.abandon(lease.generation)
    }

    private fun intentFor(lease: ForegroundLease, action: String?): Intent =
        Intent(context, serviceClass).apply {
            this.action = action
            putExtra(ProjectionForegroundService.EXTRA_GENERATION, lease.generation)
        }

    companion object {
        private const val TAG = "AndroidProjectionForeground"
        private const val DEFAULT_CONFIRM_TIMEOUT_MILLIS = 3_000L

        fun forApplication(
            context: Context,
            serviceClass: Class<out Service> = ProjectionForegroundService::class.java,
            confirmTimeoutMillis: Long = DEFAULT_CONFIRM_TIMEOUT_MILLIS,
        ): AndroidProjectionForeground = AndroidProjectionForeground(
            context.applicationContext,
            serviceClass,
            confirmTimeoutMillis,
        )
    }
}

/**
 * 投屏常驻通知（§9.2）：`startForeground` 必须带一条，且通道要先存在。
 *
 * 文案如实：只说"正在投屏"，不写"正在运行 N 个任务"这类应用层事实 —— 服务只知道
 * "我被要求做投屏前台"。**带一个真的停止按钮**（早先文案写着"可从此通知停止"却没有
 * 动作，那是对用户的假承诺）：点它经 `ACTION_STOP` + 当前代际走同一条停止路径 ——
 * 与"用户从系统投屏面板停止"落到同一处收口。
 *
 * 通道独立于保活那条（`autoscript.foreground`）：用户可以单独关掉投屏提示而不影响保活提示。
 */
internal object ProjectionNotifications {

    const val CHANNEL_ID = "autoscript.projection"

    /** `0x5052` = "PR"（projection）。 */
    const val NOTIFICATION_ID = 0x5052

    const val TEXT = "屏幕投屏会话进行中"

    /** 停止动作的 request code（与通知 id 分开，免得与内容 PendingIntent 撞号）。 */
    private const val STOP_REQUEST_CODE = 0x5053

    /**
     * 通知栏那个"停止"按钮：**必须经服务**（`ACTION_STOP`）而不是直接 `stopSelf` ——
     * 收口要同时撤前台、清账、让设备层知道（用户从通知栏停与从系统面板停必须同一条路）。
     *
     * 代际用**构造通知时那一代**（[generation]）：服务侧按 extra 比对，
     * 所以这条通知上的按钮永远只停它自己那一代，停不到后来重开的新会话。
     */
    fun stopAction(context: Context, generation: Long): Notification.Action {
        val intent = Intent(context, ProjectionForegroundService::class.java).apply {
            action = ProjectionForegroundService.ACTION_STOP
            putExtra(ProjectionForegroundService.EXTRA_GENERATION, generation)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pending = PendingIntent.getService(context, STOP_REQUEST_CODE, intent, flags)
        return Notification.Action.Builder(null, "停止", pending).build()
    }

    fun ensureChannel(context: Context, manager: NotificationManager?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        manager ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.applicationInfo.loadLabel(context.packageManager).toString(),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }
}
