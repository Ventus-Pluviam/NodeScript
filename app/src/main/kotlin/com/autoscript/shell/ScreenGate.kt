package com.autoscript.shell

import com.autoscript.appservice.scheduler.core.ScreenGuarantee

/**
 * 屏幕门禁（docs §8.6 守时契约，§9.5 三态门禁）：
 * dispatcher 投递前按 [PendingRun.screen][com.autoscript.appservice.scheduler.core.PendingRun.screen]
 * 执行守时检查——SCREEN_ON 需 wakelock + 亮屏确认，SCREEN_OFF 裁剪画面能力后投递。
 *
 * 本模块（:app 装配层）是唯一有 WakeLock/亮屏/MediaProjection 会话能力的地方；
 * [AndroidScreenGate] 是真实现（判断逻辑经 `ScreenInteractive`/`ScreenOffGuard` 两条缝单测），
 * JVM 单测/非 Android 环境用 [AllowAll]。
 *
 * **两条实现的 `ANY` 必须同结论**：否则同一条任务在单测里放行、真机上被拒（或反之），
 * 差别只会在现场暴露 —— `AndroidScreenGateTest` 有一条断言专门守这件事。
 */
fun interface ScreenGate {
    suspend fun pass(screen: ScreenGuarantee): ScreenGateDecision

    companion object {
        /** 单测/非 Android 环境默认：全放行（门禁逻辑由真实现 + 其单测覆盖）。 */
        val AllowAll = ScreenGate { ScreenGateDecision.Proceed }
    }
}

sealed interface ScreenGateDecision {
    data object Proceed : ScreenGateDecision
    data class Deny(val reason: String) : ScreenGateDecision
}
