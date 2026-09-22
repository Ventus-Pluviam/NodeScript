package com.autoscript.shell

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.accessibilityservice.AccessibilityServiceInfo
import com.autoscript.appservice.permissioncenter.SystemStateReader
import com.autoscript.domain.permission.Capability
import com.autoscript.domain.permission.CapabilityState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * 三态查询的系统接触面（docs §9.5）—— [AndroidSystemStateReader] 唯一的 Android 依赖点，
 * 也是它的单测缝：真机由 [AndroidCapabilityProbes] 装箱，JVM 单测注入即时布尔值。
 *
 * 每个方法只回答一个「系统现在是不是这样」的事实问题，**不做三态判断** ——
 * 怎么落到 `GRANTED/DEGRADED/DENIED` 是 reader 的事，那张映射表要能单独读、单独测。
 */
interface CapabilityProbes {
    /** AutoScript 的无障碍服务当前是否已被系统启用。 */
    fun accessibilityEnabled(): Boolean

    /** 用户是否授予了 `SYSTEM_ALERT_WINDOW`（a11y 可信窗口那条路不走本开关，见 reader 的 OVERLAY 注释）。 */
    fun overlayDrawable(): Boolean

    /** 通知是否被用户允许（API 33+ 是运行时权限，之前是应用级开关，两者查同一信号）。 */
    fun notificationsEnabled(): Boolean

    /** 精确闹钟是否可用（API 31+ 可被用户在设置里收回）。 */
    fun exactAlarmAllowed(): Boolean

    /** 本会话是否已有激活的 MediaProjection 授权。 */
    fun screenCaptureActive(): Boolean

    /** `su` 是否可用（阻塞式探测，reader 会切到 IO 线程再问）。 */
    fun rootAvailable(): Boolean
}

/**
 * 三态门禁的 Android 侧（docs §9.5）：把 [CapabilityProbes] 的事实映射成 `GRANTED/DEGRADED/DENIED`。
 *
 * **这张表为什么长这样**（逐条对着 §9.5 的三态定义 + `PermissionCenter.guideText` 对用户的承诺）：
 * - `ACCESSIBILITY` 没开 = `DENIED`（不是 DEGRADED）—— 引导文案承诺"开启后为 GRANTED"，
 *   且没有无障碍就**没有任何降级路径**，`ensure` 必须能拦住它；
 * - `OVERLAY` 没开 = `DEGRADED` —— 引导文案明写"未开启时对话框走通知回调降级路径"，
 *   这是有降级路径的能力。但**可信窗口是第二条路**：a11y 服务在跑时
 *   `TYPE_ACCESSIBILITY_OVERLAY` 不需要 `SYSTEM_ALERT_WINDOW`，所以这里问的是
 *   「overlay 可用吗」（[CapabilityProbes.overlayDrawable] 或 a11y 在跑），不是单问权限位；
 * - `NOTIFICATION` 没开 = `DEGRADED`（"任务提醒不可达"，其余功能不受影响）；
 *   `POST_NOTIFICATIONS` 没开 = `DENIED`（"静默丢弃"）。两者查同一个信号，
 *   差别在**降级语义**：前者是通道受限，后者是权限被拒；
 * - `SCHEDULE_EXACT_ALARM` 被收回 = `DEGRADED`（降 `setWindow` + UI 标注偏差，§8.6）；
 * - `SCREEN_CAPTURE` 无活动会话 = `DEGRADED` —— §9.5 把 GRANTED 定义成"含会话型
 *   MediaProjection **已激活**"，而"还没问过用户"不等于被拒（首次截图时才弹授权，§9.2）；
 * - `ROOT` 探测不到 = `DENIED`（§9.3：无 root 不降级渲染为禁用）；
 * - `ADB_INPUT` = `DEGRADED` 常量：Shizuku 尚未集成，但引导文案承诺的降级路径
 *   （"未就绪时输入走无障碍手势"）真实存在，故不是 DENIED。**也不谎报 GRANTED** ——
 *   本类不查任何 Shizuku 状态，因为平台上还没有那条通道可查。
 *
 * 探测抛异常不在这里兜底：`PermissionCenter.state` 已把读取异常折成 `DEGRADED`
 * （可用性未知即受限），本类不重复兜底、也不吞异常。
 */
class AndroidSystemStateReader(
    private val probes: CapabilityProbes,
) : SystemStateReader {

    override suspend fun readSystemState(ability: Capability): CapabilityState = when (ability) {
        Capability.ACCESSIBILITY ->
            if (probes.accessibilityEnabled()) CapabilityState.GRANTED else CapabilityState.DENIED

        Capability.OVERLAY ->
            if (probes.overlayDrawable() || probes.accessibilityEnabled()) {
                CapabilityState.GRANTED
            } else {
                CapabilityState.DEGRADED
            }

        Capability.NOTIFICATION ->
            if (probes.notificationsEnabled()) CapabilityState.GRANTED else CapabilityState.DEGRADED

        Capability.POST_NOTIFICATIONS ->
            if (probes.notificationsEnabled()) CapabilityState.GRANTED else CapabilityState.DENIED

        Capability.SCHEDULE_EXACT_ALARM ->
            if (probes.exactAlarmAllowed()) CapabilityState.GRANTED else CapabilityState.DEGRADED

        Capability.SCREEN_CAPTURE ->
            if (probes.screenCaptureActive()) CapabilityState.GRANTED else CapabilityState.DEGRADED

        // su 探测是阻塞的（等进程退出），不许压在调用方的线程上。
        Capability.ROOT ->
            if (withContext(Dispatchers.IO) { probes.rootAvailable() }) {
                CapabilityState.GRANTED
            } else {
                CapabilityState.DENIED
            }

        Capability.ADB_INPUT -> CapabilityState.DEGRADED
    }
}

/**
 * [CapabilityProbes] 的真机实现：唯一碰 `Settings`/`AccessibilityManager`/`NotificationManager`/
 * `AlarmManager`/`ProcessBuilder` 的地方。这些查询都是同步只读的短调用，不需要切线程
 * （唯一的例外是 `su`，由 reader 负责切到 IO）。
 *
 * **诚实口径（重要）**：本类只报"系统现在怎么说"，不报"我们希望它怎样"。
 * 无障碍服务本体已落地（:platform:capabilities 的 AutoScriptAccessibilityService，
 * 清单随库合并进 :app）——用户在设置里开启后 [accessibilityEnabled] 即为 true；
 * 启用列表与进程内 `onServiceConnected` 之间有极短窗口，此时桥侧如实
 * ERR_SERVICE_DISABLED（探针不冒充连接态）。[screenCaptureActive] 仍如实为
 * false 直到 MediaProjection 会话随 §9.2 落地。门禁说不可用，就不会有脚本
 * 以为自己拿到了无障碍。
 */
class AndroidCapabilityProbes(context: Context) : CapabilityProbes {

    private val appContext = context.applicationContext

    override fun accessibilityEnabled(): Boolean {
        val am = appContext.getSystemService(AccessibilityManager::class.java) ?: return false
        // 问系统"现在启用着哪些服务"，而不是读 Settings 字符串：前者反映真实存活态
        // （服务崩了/被系统停掉，Settings 里可能还留着记录）。
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo?.serviceInfo?.packageName == appContext.packageName }
    }

    override fun overlayDrawable(): Boolean = Settings.canDrawOverlays(appContext)

    override fun notificationsEnabled(): Boolean =
        appContext.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() ?: false

    override fun exactAlarmAllowed(): Boolean {
        // API 31 以下没有 SCHEDULE_EXACT_ALARM 这一说：老设备上精确闹钟一直可用
        //（与 AndroidAlarmPort.canScheduleExact 同一口径）。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val alarms = appContext.getSystemService(AlarmManager::class.java) ?: return false
        return alarms.canScheduleExactAlarms()
    }

    /** MediaProjection 会话态（§9.2）：会话实现落地前恒 false —— 如实说"没有活动会话"。 */
    override fun screenCaptureActive(): Boolean = false

    /**
     * `su` 探测：跑 `su -c id` 看能不能拿到 uid 0。
     *
     * 为什么带超时：没有 root 的设备上 `su` 二进制根本不存在（`IOException`，立即返回），
     * 但装了 root 管理器又没授权的设备会**挂着等用户点弹窗** —— 铁律 3 不许无限等待，
     * 超时即判"当前不可用"（用户点了允许之后，能力中心再查一次就变 GRANTED）。
     */
    override fun rootAvailable(): Boolean = try {
        val process = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
        try {
            process.waitFor(ROOT_PROBE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) && process.exitValue() == 0
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    } catch (e: Exception) {
        false   // 没有 su 是常态，不是异常路径
    }

    private companion object {
        const val ROOT_PROBE_TIMEOUT_MILLIS = 1_500L
    }
}
