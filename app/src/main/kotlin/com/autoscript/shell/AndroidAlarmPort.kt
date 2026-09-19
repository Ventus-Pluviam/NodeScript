package com.autoscript.shell

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * [AlarmPort] 的 Android 实现（docs §8.6：`AlarmManager setExactAndAllowWhileIdle → receiver`）。
 *
 * **本类无判断**：什么时候 arm、按哪种模式 arm、降不降级全由 [AlarmSchedulerProvider] 决定
 * （那里才有预拉提前量、夹取与降级记账，且可单测）。这里只做框架搬运：
 * - taskId → requestCode（稳定正数，`requestCode` 决定 PendingIntent 的同一性）；
 * - `action`/`extra` 常量取 [AlarmFires]（与 `AndroidManifest` 里声明的那条 action 同一份）；
 * - `setExactAndAllowWhileIdle` / `setWindow` 两个调用点；
 * - 取消 = `alarmManager.cancel` + `pendingIntent.cancel`（**两个都要**：只撤 AlarmManager
 *   会留下可被复活的 PendingIntent，只撤 PendingIntent 则闹钟照响）。
 *
 * **`PendingIntent.FLAG_MUTABLE` 是 API 31+ 的硬要求**（隐式 intent 必须显式可变性），
 * 而闹钟 PendingIntent 必须是 MUTABLE —— 系统要往里面填 `AlarmManager.EXTRA_ALARM_*`
 * （`EXTRA_ALARM_COUNT` 等），IMMUTABLE 会让填充失败。因此低版本（<31）用 `FLAG_UPDATE_CURRENT`
 * 保持同一 requestCode 下的替换语义，高版本用 `MUTABLE | UPDATE_CURRENT`。
 */
class AndroidAlarmPort(
    context: Context,
    private val receiver: Class<*>,
) : AlarmPort {

    private val appContext = context.applicationContext
    private val alarms: AlarmManager =
        appContext.getSystemService(AlarmManager::class.java)
            ?: error("AlarmManager 不可得（系统服务缺失）")

    override val canScheduleExact: Boolean =
        // API 31 以下无 SCHEDULE_EXACT_ALARM 权限这一说：老设备上精确闹钟一直可用。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarms.canScheduleExactAlarms()
        } else {
            true
        }

    override fun arm(taskId: String, atMillis: Long, mode: AlarmMode) {
        val pending = pendingIntentFor(taskId)
        when (mode) {
            is AlarmMode.Exact -> alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
            is AlarmMode.Window ->
                alarms.setWindow(AlarmManager.RTC_WAKEUP, atMillis, mode.windowMillis, pending)
        }
    }

    override fun cancel(taskId: String) {
        val pending = pendingIntentFor(taskId)
        alarms.cancel(pending)
        pending.cancel()
    }

    /**
     * taskId → PendingIntent（广播，`action`/`extra` 取 [AlarmFires] 的常量：闹钟响时
     * 由静态注册的接收器按 `EXTRA_TASK_ID` 转回 `Scheduler.onTrigger`）。
     *
     * requestCode 取 taskId 的稳定哈希（`KeyStableHash`）：同一 taskId 永远同一 requestCode，
     * 所以重复 arm 是**替换**；不同 taskId 冲突概率由 32 位空间的稳定哈希摊掉
     * （真冲突的表现是"两个任务共用一个闹钟"，任务中心会看到少一个触发 ——
     *  比崩溃好查，且 [AlarmSchedulerProvider] 的路由按 taskId 而非 requestCode 判定）。
     */
    private fun pendingIntentFor(taskId: String): PendingIntent {
        val intent = Intent(appContext, receiver).apply {
            action = AlarmFires.ACTION_FIRE
            putExtra(AlarmFires.EXTRA_TASK_ID, taskId)
            // 显式 component 已在 Intent(context, cls) 构造里给出；这里不需要 package 限定。
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        }
        return PendingIntent.getBroadcast(
            appContext,
            KeyStableHash.requestCode(taskId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or mutableFlag(),
        )
    }

    private fun mutableFlag(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
}

/**
 * taskId → requestCode 的稳定映射（`String.hashCode` 取到 32 位签名空间后夹成正数）。
 *
 * 为什么不用 `taskId.hashCode().mod(...)` 之外的花样：**稳定**比"分散"更重要 ——
 * 同一次安装内 taskId 不变则 requestCode 不变，`FLAG_UPDATE_CURRENT` 才会替换而不是叠加；
 * 换一套哈希算法只会让升级后"看起来像新任务"，而老闹钟还挂着（表现为重复触发）。
 */
internal object KeyStableHash {
    fun requestCode(taskId: String): Int = taskId.hashCode() and 0x0000FFFF
}
