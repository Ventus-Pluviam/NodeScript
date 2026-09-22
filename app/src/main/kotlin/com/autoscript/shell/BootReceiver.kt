package com.autoscript.shell

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 开机广播的纯判定（docs §8.6 重启后排期重建）。
 *
 * 独立成对象而不是塞进接收器里：`Intent.ACTION_BOOT_COMPLETED` 是 android 常量，
 * JVM 单测碰不得 —— 这里只存它的字面量（与框架常量同值），判定逻辑于是纯 JVM 可测；
 * 接收器只剩搬运（见 [BootReceiver]，与 AlarmReceiver 同一"无判断"纪律）。
 */
object BootEvents {
    /** 与 `Intent.ACTION_BOOT_COMPLETED` 同值的字面量（JVM 可测，见上）。 */
    const val ACTION_BOOT_COMPLETED: String = "android.intent.action.BOOT_COMPLETED"

    /** 是否开机完成广播（action 缺失/不对 → false，不抛错）。 */
    fun isBootCompleted(action: String?): Boolean = action == ACTION_BOOT_COMPLETED
}

/**
 * 开机接收器（docs §8.6 重启后排期重建）。
 *
 * **静态注册**（见 `AndroidManifest.xml`）：设备重启清掉全部 AlarmManager 闹钟，
 * 且不会主动拉起本进程 —— 没有这个接收器，持久的注册表（`tasks.jsonl`）再完整
 * 也没人续排（Daily 任务重启后永久停排，且无任何报错，比崩溃更难查）。
 *
 * **本类无判断、无状态、不起协程**：同进程内 application 一定先于任何 receiver 创建，
 * `Application.onCreate` 的装配与恢复（`restoreTasks` 续排 + `recoverUncommitted`
 * 重投）已在 IO 域进行中；这里再调一次恢复会与 `install` 的那次并发撞车
 * （两处同时 `reopen` 同一行，后到的抛 IllegalArgumentException）。所以只记日志、
 * 同步返回，不占 `goAsync()` 窗口 —— "进程起来"本身就是本次投递的全部作用。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!BootEvents.isBootCompleted(intent.action)) return
        Log.i(TAG, "开机完成：走正常装配路径续排（restoreTasks 重建闹钟，重投遗留意向）")
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
