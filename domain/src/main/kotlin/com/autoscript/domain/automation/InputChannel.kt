package com.autoscript.domain.automation

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * 输入通道（docs §9.3）：`auto`（无障碍）/ `adb`（Shizuku）/ `root`（`su`）。
 *
 * **不是「降级顺序」，是「三个平级通道」**（拍板，见 `design-decisions.md`）：
 * 调用方**必须显式**指定要走哪一条；指定的那条不可用就**如实失败**，绝不改用别的通道。
 * 这一条与本仓既有的降级哲学**刻意相反**（§9.5 的 `DEGRADED` 是「能力受限但仍可用」），
 * 因为三者的**可观测后果不同**：无障碍注入会被前台应用看出（`FLAG_SECURE` 类对抗、
 * 输入法/悬浮窗争抢），root 注入在系统层不留无障碍痕迹，adb 注入的进程身份是 shell。
 * 对「自动化签到/抢购/压测」这类场景，**走了哪条通道是语义的一部分**，不是实现细节 ——
 * 静默换通道等于让脚本作者以为自己在测 A 实际在测 B。
 *
 * 取值与 JS 侧字面量逐字对齐（`auto` / `adb` / `root`，小写）。
 */
enum class InputChannel {
    /** 无障碍手势（`AccessibilityService.dispatchGesture` / `ACTION_CLICK`）。 */
    AUTO,

    /** Shizuku 代理的 adb shell（`input tap` / `input swipe`）。 */
    ADB,

    /** `su -c` 的 root shell（`input tap` / `input swipe`）。 */
    ROOT,
    ;

    companion object {
        /**
         * wire 字面量 → 枚举；**未知取值抛**（`IllegalArgumentException`，handler 折叠
         * `ERR_INVALID_PARAM`）。不静默落回缺省 —— 拼错 `"uiautomator"` 却按 `auto` 跑，
         * 正是本枚举要防的那类事。
         *
         * **大小写不敏感**（`"Root"` 与 `"root"` 同义）：wire 名的大小写口径在本仓不统一
         * （`ScrollDirection` 也走 lowercase 比对），这里跟既有口径对齐，不另立一套。
         */
        fun ofWire(value: String): InputChannel = when (value.lowercase()) {
            "auto" -> AUTO
            "adb" -> ADB
            "root" -> ROOT
            else -> throw IllegalArgumentException("未知输入通道 '$value'（可选：auto / adb / root）")
        }
    }
}

/**
 * 会话级输入通道（`setInputChannel` 设、单次调用可覆盖）。
 *
 * **为什么是协程上下文元素而不是 handler 的一个字段**：handler 是**全局单例**
 * （`AppShell` 一个 `BridgeRouter` 挂一套 handler，所有脚本共用一条桥连接），
 * 字段级会话态会让脚本 A 设的通道漏给脚本 B —— 一个**静默的跨脚本串扰**，
 * 而它恰好长着「省事」的样子。[NewlineFrameServer] 每条连接建一个本对象、
 * 作为 [CoroutineContext.Element] 传给该连接的所有帧任务，隔离是**结构上**的
 * （同连接内的帧共享，不同连接天然不共享），不靠人记得清。
 *
 * **缺省是「没选」而不是 `auto`**：
 * [current] 为 null 时调用方**必须**在单次调用的载荷里给 `channel`，否则
 * `ERR_INVALID_PARAM`。这里**刻意不预置 `auto`** —— 预置等于「什么都没说 = 走了无障碍」，
 * 而三者可观测后果不同（见 [InputChannel] 的 KDoc），那种静默正是本机制要消灭的东西。
 * 会话值是**显式选择的一种**（`setInputChannel` 是脚本自己发的帧），所以它满足
 * 「必须显式」；**没有任何显式选择**才报错。
 */
class InputChannelSession(
    initial: InputChannel? = null,
) : AbstractCoroutineContextElement(Key) {

    @Volatile
    var current: InputChannel? = initial

    companion object Key : CoroutineContext.Key<InputChannelSession>
}
