package com.autoscript.ui.state

/**
 * 屏内操作回执 → 浮层（toast）文案的**唯一**一处判读。
 *
 * 批 44：这些回执不再占列表的一行 —— 此前那一行插在搜索框下面，弹一条就把第一张卡
 * 往下推一次，读列表时整块在跳。改走外壳的浮层（`ToastHost`）后**文案一个字没动**，
 * 只是从"列表里的一行"搬到"浮起来、自己消失的一条"（用户口径即"改为 toast 显示"）。
 *
 * 判读留在纯层（而不是就地写进 `@Composable`）是为了可 JVM 测：**谁压谁**是一条
 * 会写错的规则，写在 Composable 里就只能靠人眼看。
 */
fun opToastMessage(error: String?, notice: String?, inFlight: Boolean): String? = toastMessage(
    errorPrefix = "操作失败：",
    inFlightText = "执行中…（挂起期间按钮停用）",
    error = error,
    notice = notice,
    inFlight = inFlight,
)

/** [opToastMessage] 的「停止执行」档（任务中心的行尾停止钮与控制台共用这一条）。 */
fun stopToastMessage(error: String?, notice: String?, inFlight: Boolean): String? = toastMessage(
    errorPrefix = "停止失败：",
    inFlightText = "正在停止…（挂起期间按钮停用）",
    error = error,
    notice = notice,
    inFlight = inFlight,
)

/**
 * 三态取一：**失败 > 回执 > 挂起**。
 *
 * 失败排头：它带原文，是唯一需要用户动手的一条；回执次之（刚做成的事，说一声）；
 * 挂起最轻（按钮本来就灰着，这句只是说明为什么灰）。生产写路径上三者互斥
 * （每次操作开始先清 error/notice，见 `MainActivity` 的 `performTaskOp` 一族），
 * 但读口是 data class —— 不把规则写下来，早晚会有两屏各判一套。
 */
private fun toastMessage(
    errorPrefix: String,
    inFlightText: String,
    error: String?,
    notice: String?,
    inFlight: Boolean,
): String? = when {
    error != null -> errorPrefix + error
    notice != null -> notice
    inFlight -> inFlightText
    else -> null
}
