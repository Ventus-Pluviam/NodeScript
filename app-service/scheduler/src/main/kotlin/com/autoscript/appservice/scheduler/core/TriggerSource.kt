package com.autoscript.appservice.scheduler.core

/**
 * 触发源五类（docs/framework-design.md §8.6）。
 * 所有路径归一为 [fire] 进入调度：触发 → 拉起引擎进程 → 注入 API → 归意图日志。
 */
enum class TriggerSource {
    /** 定时（cron / alarm；§8.6 诚实守时契约：亮屏+解锁保底、预热闹钟、screen 三态声明）。 */
    TIMED,

    /** Intent / 广播：外部 receiver（通知栏、快捷开关、其他 App 的 broadcast）。 */
    INTENT_BROADCAST,

    /** 事件：无障碍 / 通知监听触发（P1 细化源分类）。 */
    EVENT,

    /** 用户点击：任务中心的「立即执行」按钮。 */
    USER_CLICK,

    /** 引擎内部：`auto.engines.exec` 跨引擎调用。 */
    ENGINE_INTERNAL,
}