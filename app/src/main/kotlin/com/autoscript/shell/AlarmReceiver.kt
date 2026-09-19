package com.autoscript.shell

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.autoscript.AppShellApplication

/**
 * 闹钟接收器（docs §8.6「AlarmManager setExactAndAllowWhileIdle → receiver」）。
 *
 * **静态注册**（见 `AndroidManifest.xml`）：精确闹钟响时进程可能已被 ROM 杀掉，
 * `registerReceiver` 的动态注册随进程一起消失，收不到这一发。
 *
 * **本类无判断、无状态**：只做三件搬运 —— 校验 action、取 taskId、把它交给
 * [AppShellApplication.alarmWork]。投递口径（幂等/意图日志/排队上限）全在 scheduler 侧，
 * 装配是否就绪由 [AlarmDispatch] 记账。真要把会错的逻辑写在这里，出错时就只剩
 * 「任务没跑」这一个症状，连**为什么**都没处查。
 *
 * `goAsync()` 是必须的：[AlarmRoute.fire] 是挂起函数（要等 [Scheduler.onTrigger] 走完
 * 意图日志落行），`onReceive` 返回后进程随时会被回收 —— 不延长广播生命周期，
 * 跑一半的投递会连着进程一起消失。
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmFires.ACTION_FIRE) return
        val taskId = intent.getStringExtra(AlarmFires.EXTRA_TASK_ID)
        if (taskId.isNullOrEmpty()) {
            Log.w(TAG, "闹钟广播缺 EXTRA_TASK_ID：action=${intent.action}（忽略本次投递）")
            return
        }
        // 进程刚好在重启途中时 application 可能还没 attach：此时不投递，由 AlarmDispatch 记账。
        val shell = context.applicationContext as? AppShellApplication
        if (shell == null) {
            Log.w(TAG, "闹钟响了但宿主 Application 不是 AppShellApplication（忽略 taskId=$taskId）")
            return
        }
        val pending = goAsync()
        shell.alarmWork(taskId) { pending.finish() }
    }

    private companion object {
        const val TAG = "AlarmReceiver"
    }
}
