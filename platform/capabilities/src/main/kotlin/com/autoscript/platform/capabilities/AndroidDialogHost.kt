package com.autoscript.platform.capabilities

import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.system.DialogChoice
import com.autoscript.domain.system.DialogHost
import com.autoscript.domain.system.DialogMode
import com.autoscript.domain.system.DialogOutcome
import com.autoscript.domain.system.DialogChooseRequest
import com.autoscript.domain.system.DialogPromptRequest
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred

/**
 * `dialogs` 宿主的 Android 编排层（docs §9.4 / domain [DialogHost] KDoc：实现住本模块）。
 *
 * 本类零 Android 触点（纯 JVM 可测）：路径选择、登记/回投、TTL 取消收尾全在这，
 * 真弹窗/真通知在 [SystemDialogOps]（[DialogOps] 缝后面）。
 *
 * **选路（§9.4 BAL，handler 看不到 overlay 实况、决策在这）**：
 * - `AUTO` → [overlayAvailable] 可见则弹窗，否则通知回调；
 * - `OVERLAY` → 强制弹窗；不可用即抛 `ERR_PERMISSION_DENIED`（**不静默降级**到通知 ——
 *   调用方点名要弹窗，换成通知就是换了交互）；
 * - `NOTIFICATION` → 强制通知回调（overlay 可用也不用）。
 *
 * **登记纪律**：
 * - 先登记后 post（回投可能在 await 之前到达，deferred 必须先在）；
 * - `finally` 双清：注销登记 + 撤通知 —— 桥 TTL 斩杀（协程取消）后不留幽灵通知，
 *   也绝不让晚到的答案投进已死的请求（[deliverPrompt]/[deliverChoose] 找不到 key
 *   回 false，静默丢弃是诚实的：请求已经没了）；
 * - overlay 路径不登记（结果就地回），取消收窗归 [DialogOps.overlayPrompt] 自己
 *   （它必须在协程取消时自行 dismiss —— 缝的义务，见 [DialogOps] KDoc）。
 *
 * **单宿主**：[DialogResultRouter] 是进程内回投汇（通知 receiver → 宿主），
 * 构造即绑定、后者顶前者 —— P0 装配点唯一（[PlatformWiring.of]），多宿主时改注入。
 *
 * 超时不自建：桥 TTL（router `withTimeout`）取消协程即走 `finally` 收尾
 *（domain SPI KDoc「超时由实现方收尾」的实现处）。
 */
class AndroidDialogHost(
    private val ops: DialogOps,
    private val overlayAvailable: () -> Boolean,
) : DialogHost {

    private val ids = AtomicLong(1)
    private val lock = Any()
    private val pendingPrompt = HashMap<Long, CompletableDeferred<DialogOutcome>>()
    private val pendingChoose = HashMap<Long, CompletableDeferred<DialogChoice>>()

    init {
        DialogResultRouter.bind(this)
    }

    override suspend fun prompt(request: DialogPromptRequest): DialogOutcome {
        if (resolveOverlay(request.mode)) {
            return ops.overlayPrompt(request.title, request.placeholder)
        }
        val key = ids.getAndIncrement()
        val deferred = CompletableDeferred<DialogOutcome>()
        synchronized(lock) { pendingPrompt[key] = deferred }
        try {
            ops.postPromptNotification(key, request.title, request.placeholder)
            return deferred.await()
        } finally {
            synchronized(lock) { pendingPrompt.remove(key) }
            runCatching { ops.cancelNotification(key) } // 清幽灵通知是收尾，不再抛新错
        }
    }

    override suspend fun choose(request: DialogChooseRequest): DialogChoice {
        if (resolveOverlay(request.mode)) {
            return ops.overlayChoose(request.title, request.options)
        }
        val key = ids.getAndIncrement()
        val deferred = CompletableDeferred<DialogChoice>()
        synchronized(lock) { pendingChoose[key] = deferred }
        try {
            ops.postChooseNotification(key, request.title, request.options)
            return deferred.await()
        } finally {
            synchronized(lock) { pendingChoose.remove(key) }
            runCatching { ops.cancelNotification(key) }
        }
    }

    /** 通知回投入口（[DialogResultRouter] 转发）：晚到/未知 key 回 false，不抛。 */
    fun deliverPrompt(key: Long, value: String?, confirmed: Boolean): Boolean {
        val deferred = synchronized(lock) { pendingPrompt.remove(key) } ?: return false
        return deferred.complete(DialogOutcome(value, confirmed))
    }

    /** 同上；[index] 即 JS 契约下标（取消 = [DialogChoice.CANCELLED_INDEX]）。 */
    fun deliverChoose(key: Long, index: Int): Boolean {
        val deferred = synchronized(lock) { pendingChoose.remove(key) } ?: return false
        return deferred.complete(DialogChoice(index.coerceAtLeast(DialogChoice.CANCELLED_INDEX)))
    }

    /** mode → 是否走 overlay（OVERLAY 强制不可用即抛；这里抛 = handler 原码折叠）。 */
    private fun resolveOverlay(mode: DialogMode): Boolean = when (mode) {
        DialogMode.OVERLAY -> {
            if (!overlayAvailable()) {
                throw AutojsException(
                    ErrorCode.ERR_PERMISSION_DENIED,
                    "mode=overlay 要求悬浮窗，但 overlay 不可用（不静默降级到通知）",
                )
            }
            true
        }
        DialogMode.NOTIFICATION -> false
        DialogMode.AUTO -> overlayAvailable()
    }

    private companion object {
        // ids 只在通知路径自增；overlay 路径不发号（无登记即无回投）。
    }
}

/**
 * 对话框操作缝（真机 [SystemDialogOps]；单测注入内存替身）。
 *
 * **[overlayPrompt]/[overlayChoose] 的取消义务**：协程被取消（桥 TTL 斩杀）时，
 * 实现必须在 [kotlinx.coroutines.suspendCancellableCoroutine] 的
 * `invokeOnCancellation` 里自行 dismiss —— 宿主碰不到具体窗体，收窗只能在这条缝上。
 * 结果语义：back/外部点击/无选择 = [DialogOutcome.CANCELLED] / [DialogChoice.CANCELLED]。
 *
 * 通知两法：发完即返回，结果经 [DialogResultRouter] 回投（receiver 持 key）；
 * [cancelNotification] 在宿主 `finally` 里被调（清幽灵），实现幂等即可。
 */
interface DialogOps {
    suspend fun overlayPrompt(title: String, placeholder: String?): DialogOutcome

    suspend fun overlayChoose(title: String, options: List<String>): DialogChoice

    fun postPromptNotification(key: Long, title: String, placeholder: String?)

    fun postChooseNotification(key: Long, title: String, options: List<String>)

    fun cancelNotification(key: Long)
}

/**
 * 进程内回投汇：[SystemDialogOps] 的通知 receiver → 当前宿主。
 * 单宿主（P0 装配点唯一）：后构造的宿主顶掉前者；未绑定/未知 key 回 false（丢弃）。
 */
internal object DialogResultRouter {
    @Volatile
    private var host: AndroidDialogHost? = null

    fun bind(target: AndroidDialogHost) {
        host = target
    }

    fun deliverPrompt(key: Long, value: String?, confirmed: Boolean): Boolean =
        host?.deliverPrompt(key, value, confirmed) ?: false

    fun deliverChoose(key: Long, index: Int): Boolean =
        host?.deliverChoose(key, index) ?: false
}
