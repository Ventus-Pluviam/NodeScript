package com.autoscript.appservice.scheduler.core

/**
 * 定时任务守时诚实契约（docs/framework-design.md §8.6）：
 * 设备「亮屏 + 解锁」是保底；熄屏任务必须显式声明其一，未经声明不投递。
 */
enum class ScreenGuarantee {
    /** 任务需要亮屏（auto.images / UI 操作）：投递前要求 wakelock + 亮屏确认。 */
    SCREEN_ON,

    /** 任意屏幕状态：守时尽力而为，锁屏不保证画面类能力。 */
    ANY,

    /** 仅熄屏（不可用 MediaProjection，只允许无障碍 + 网络）：锁屏下正常投递。 */
    SCREEN_OFF,
}