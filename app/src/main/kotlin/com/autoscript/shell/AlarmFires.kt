package com.autoscript.shell

/**
 * 闹钟广播的 action / extra 常量（docs §8.6：`AlarmManager setExactAndAllowWhileIdle
 * → receiver`）。
 *
 * 独立成文件而不是塞进接收器类里：**两处都要用** —— `AndroidAlarmPort` 按 taskId 填 extra，
 * manifest 里静态注册的接收器按 taskId 路由。常量住进其中任何一边，另一边就要为一个自己
 * 用不到的组件付依赖。
 *
 * 分派不靠 action 分叉：预拉/精确/降级窗口三条路都发同一个 [ACTION_FIRE]，
 * 差别只体现在 arm 时刻与投递模式上（那在 provider 侧已记账）。
 */
object AlarmFires {
    const val ACTION_FIRE: String = "com.autoscript.shell.action.ALARM_FIRE"
    const val EXTRA_TASK_ID: String = "com.autoscript.shell.extra.TASK_ID"
}
