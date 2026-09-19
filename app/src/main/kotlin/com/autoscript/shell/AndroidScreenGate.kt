package com.autoscript.shell

import android.content.Context
import com.autoscript.appservice.scheduler.core.ScreenGuarantee

/**
 * 屏幕门禁的 Android 实现（docs §8.6 守时契约：亮屏 + 解锁是保底）。
 *
 * 契约逐条对着 §8.6 / §9.5：
 * - **SCREEN_ON**（`screen.on`：需 wakelock + 确认）：熄屏或被 `deferWakeLock` 缝拒绝时
 *   [ScreenGateDecision.Deny] —— **不静默降级**成"锁屏也跑"（那会让一个 UI 任务在锁屏下
 *   对着黑屏空点，用户看到的是"任务成功、实际什么都没发生"）；
 * - **SCREEN_OFF**：只禁画面类能力（MediaProjection 走 `onScreenOff` 缝交给实现方裁剪），
 *   锁屏下照常放行 —— 无障碍 + 网络在锁屏下是真实可用的；
 * - **ANY**：尽力而为，锁屏也放行（保时任务的显式选择，不是默认兜底）。
 *
 * 为什么读的是 [android.os.PowerManager.isInteractive] 而不是 keyguard：
 * 两个信号表达的是两件事 —— "屏亮着"决定 MediaProjection 有没有画面，
 * "已解锁"决定 UI 操作有没有目标。`SCREEN_ON` 两件都要，故 [deferWakeLock] 的实现方
 * 持 wakelock + 解锁态一起判（本类只问"现在能不能放行"），判定需要的两个系统查询
 * 因此都留在能拿到系统服务的 `:app` 侧。
 *
 * [AndroidScreenGate] 是本契约的生产实现；`ScreenGate.AllowAll` 是 JVM/非 Android 的替身。
 * 两者在 `ANY` 上的结论必须一致（`AndroidScreenGateTest` 有断言守着）——
 * 否则同一条任务在单测与真机上行为不同，差别只在现场暴露。
 */
interface ScreenGateAndroid : ScreenGate

/** [ScreenGateAndroid] 的两个系统查询缝（测试注入即时返回值，不构造 Android 对象）。 */
fun interface ScreenInteractive {
    /** 屏幕是否处于交互态（`PowerManager.isInteractive`）。 */
    fun isInteractive(): Boolean
}

/** 熄屏任务的能力裁剪点（`SCREEN_OFF` 禁 MediaProjection；由实现方落地）。 */
fun interface ScreenOffGuard {
    /** 熄灭/锁屏前该收起什么（会话、悬浮窗、录屏授权等）；幂等。 */
    fun disableVisualCapabilities()
}

/**
 * 生产实现。[interactive] 与 [deferWakeLock] 都是缝：真机由 `:app` 装配层注入
 * （PowerManager + 解锁监听），JVM 单测注入即时值 —— 门禁的判断逻辑因此可测，
 * 不需要 Mock 任何 Android 框架对象。
 */
class AndroidScreenGate(
    private val interactive: ScreenInteractive,
    private val deferWakeLock: ScreenInteractive,
    private val onScreenOff: ScreenOffGuard,
) : ScreenGateAndroid {

    override suspend fun pass(screen: ScreenGuarantee): ScreenGateDecision = when (screen) {
        // 亮屏契约：屏不亮或拿不到 wakelock 都如实拒绝（不降级成 ANY 悄悄跑）。
        ScreenGuarantee.SCREEN_ON -> if (interactive.isInteractive() && deferWakeLock.isInteractive()) {
            ScreenGateDecision.Proceed
        } else {
            ScreenGateDecision.Deny("亮屏条件不满足（熄屏或未取得 wakelock）：SCREEN_ON 任务不投递")
        }

        // 熄屏契约：先确认可裁剪画面能力，再放行。
        ScreenGuarantee.SCREEN_OFF -> {
            onScreenOff.disableVisualCapabilities()
            ScreenGateDecision.Proceed
        }

        ScreenGuarantee.ANY -> ScreenGateDecision.Proceed
    }

    companion object {
        /**
         * 从 [Context] 取系统服务构造生产实现。
         *
         * 装配层（`AppShellApplication`）调用；单测不应走这里 ——
         * 它拿的是真 `PowerManager`，桌面 JVM 上取不到。
         */
        fun of(context: Context): ScreenGateAndroid {
            val power = context.getSystemService(android.os.PowerManager::class.java)
                ?: error("PowerManager 不可得（系统服务缺失）")
            return AndroidScreenGate(
                interactive = ScreenInteractive { power.isInteractive },
                deferWakeLock = ScreenInteractive { true },   // 真 wakelock 由 FGS/持锁路径保证（§8.7）
                onScreenOff = ScreenOffGuard { },             // MediaProjection 裁剪随会话实现落地
            )
        }
    }
}
