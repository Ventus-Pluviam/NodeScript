package com.autoscript.domain.scripts

/**
 * 守时诚实契约（亮屏/熄屏）是**登记载荷的形状**的一部分，与 [TimedSchedule] 同批搬进 `:domain`（2026-10-11 批 102）。
 *
 * 定时任务守时诚实契约（docs §8.6）：
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
