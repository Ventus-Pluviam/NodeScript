package com.autoscript.platform.capabilities.device

import com.autoscript.platform.capabilities.DialogResultRouter
import com.autoscript.platform.capabilities.DialogOps

import android.app.AlertDialog
import android.app.Dialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.EditText
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.system.DialogChoice
import com.autoscript.domain.system.DialogOutcome
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 通知回投的 extras / 动作 / 通道（ops 与 receiver 共用，防两处字面量漂移）。 */
internal object DialogExtras {
    const val EXTRA_KEY = "autoscript.dialog.KEY"
    const val EXTRA_KIND = "autoscript.dialog.KIND"
    const val EXTRA_INDEX = "autoscript.dialog.INDEX"
    const val KIND_PROMPT = "prompt"
    const val KIND_CHOOSE = "choose"
    const val REPLY_KEY = "autoscript.dialog.REPLY"
    const val CHANNEL_ID = "autoscript.dialog"
    const val CHANNEL_NAME = "对话框"

    /**
     * PendingIntent requestCode = key×1024 + 槽位。**同 key 不同动作必须不同
     * requestCode** —— `Intent.filterEquals` 不比 extras，靠 extras 区分会让
     * 「回复」和「取消」共用同一个 PendingIntent（点了取消＝点了回复）。
     * 槽位约定：0=prompt 回复、1=prompt 取消、2+i=choose 第 i 项、2+n=choose 取消。
     */
    fun requestCode(key: Long, slot: Int): Int = (key * 1024 + slot).toInt()

    fun notifyId(key: Long): Int = key.toInt()
}

/**
 * `dialogs` 的设备面（docs §9.4；编排语义在 [AndroidDialogHost]，本类只碰系统）。
 *
 * 两条路径：
 * - **overlay 弹窗**：[AlertDialog] + `WindowManager.LayoutParams.type`（按
 *   [overlayTypeAvailable] 在 `TYPE_ACCESSIBILITY_OVERLAY`/`TYPE_APPLICATION_OVERLAY`
 *   选型，与 `AndroidFloatingWindowHost` 同探针同两常量）；建窗被系统拒 →
 *   `ERR_PERMISSION_DENIED`（与悬浮窗 create 同分类）；back/外部点击 → CANCELLED
 *   （onDismiss 兜底 + `isActive` 防双投）；
 * - **通知回调**：独立通道 [DialogExtras.CHANNEL_ID]（IMPORTANCE_HIGH）+ 动作按钮；
 *   prompt 的回复动作挂 [RemoteInput]（inline reply 取文本），取消动作不带
 *   （receiver 收不到 results = CANCELLED）；choose 每选项一个动作（下标进 extra）+
 *   取消动作。通知未授权 → `ERR_PERMISSION_DENIED`（与 `AndroidNotificationPoster`
 *   同事实同折法，不另立判据）。
 *
 * **取消义务**（[DialogOps] KDoc）：`invokeOnCancellation` 回主线程 dismiss
 * 已弹的窗；post runnable 先查 `isActive`（取消先到不得把窗弹出来）。
 * resume 竞态（取消与用户点击同时）用 `isActive` + IllegalStateException 兜底，
 * 绝不因收尾把异常抛回点击回调。
 */
class SystemDialogOps(
    private val context: Context,
    private val overlayTypeAvailable: () -> Boolean = { false },
) : DialogOps {

    private val mainHandler = Handler(Looper.getMainLooper())

    override suspend fun overlayPrompt(title: String, placeholder: String?): DialogOutcome =
        suspendCancellableCoroutine { cont ->
            val holder = AtomicReference<Dialog?>(null)
            mainHandler.post {
                if (!cont.isActive) return@post // 取消先到：不弹窗
                fun finish(outcome: DialogOutcome) {
                    try {
                        if (cont.isActive) cont.resume(outcome)
                    } catch (_: IllegalStateException) {
                        // 取消与点击的残余竞态：收尾优先，不把 ISE 抛进 UI 线程
                    }
                }
                try {
                    val edit = EditText(context)
                    edit.hint = placeholder ?: ""
                    val dialog = AlertDialog.Builder(context)
                        .setTitle(title)
                        .setView(edit)
                        .setPositiveButton("确定") { _, _ ->
                            finish(DialogOutcome(edit.text.toString(), confirmed = true))
                        }
                        .setNegativeButton("取消") { _, _ -> finish(DialogOutcome.CANCELLED) }
                        .setOnDismissListener { finish(DialogOutcome.CANCELLED) } // back/系统收窗兜底
                        .create()
                    holder.set(dialog)
                    dialog.window?.setType(overlayWindowType())
                    dialog.show()
                } catch (e: Exception) {
                    holder.set(null)
                    try {
                        if (cont.isActive) {
                            cont.resumeWithException(
                                AutojsException(
                                    ErrorCode.ERR_PERMISSION_DENIED,
                                    "对话框弹窗被系统拒绝（overlay 权限或窗口类型不可用）：${e.message}",
                                    e,
                                ),
                            )
                        }
                    } catch (_: IllegalStateException) { /* 取消竞态：已收尾 */ }
                }
            }
            cont.invokeOnCancellation {
                mainHandler.post { holder.getAndSet(null)?.let { d -> runCatching { d.dismiss() } } }
            }
        }

    override suspend fun overlayChoose(title: String, options: List<String>): DialogChoice =
        suspendCancellableCoroutine { cont ->
            val holder = AtomicReference<Dialog?>(null)
            mainHandler.post {
                if (!cont.isActive) return@post
                fun finish(choice: DialogChoice) {
                    try {
                        if (cont.isActive) cont.resume(choice)
                    } catch (_: IllegalStateException) { /* 取消竞态 */ }
                }
                try {
                    val labels: Array<CharSequence> = options.map { it as CharSequence }.toTypedArray()
                    val dialog = AlertDialog.Builder(context)
                        .setTitle(title)
                        .setItems(labels) { _, which -> finish(DialogChoice(which)) }
                        .setNegativeButton("取消") { _, _ -> finish(DialogChoice.CANCELLED) }
                        .setOnDismissListener { finish(DialogChoice.CANCELLED) }
                        .create()
                    holder.set(dialog)
                    dialog.window?.setType(overlayWindowType())
                    dialog.show()
                } catch (e: Exception) {
                    holder.set(null)
                    try {
                        if (cont.isActive) {
                            cont.resumeWithException(
                                AutojsException(
                                    ErrorCode.ERR_PERMISSION_DENIED,
                                    "选择框弹窗被系统拒绝（overlay 权限或窗口类型不可用）：${e.message}",
                                    e,
                                ),
                            )
                        }
                    } catch (_: IllegalStateException) { /* 取消竞态 */ }
                }
            }
            cont.invokeOnCancellation {
                mainHandler.post { holder.getAndSet(null)?.let { d -> runCatching { d.dismiss() } } }
            }
        }

    override fun postPromptNotification(key: Long, title: String, placeholder: String?) {
        val manager = notificationManager()
        ensureChannel(manager)
        requireNotificationsEnabled(manager)

        val replyInput = RemoteInput.Builder(DialogExtras.REPLY_KEY)
            .setLabel(placeholder ?: "输入")
            .build()
        val replyAction = Notification.Action.Builder(
            android.R.drawable.sym_def_app_icon,
            "回复",
            broadcast(
                slot = SLOT_PROMPT_REPLY,
                key = key,
                kind = DialogExtras.KIND_PROMPT,
                mutable = true, // RemoteInput：系统要往 intent 里填 results，必须可变
            ),
        ).addRemoteInput(replyInput).build()
        val cancelAction = Notification.Action.Builder(
            android.R.drawable.sym_def_app_icon,
            "取消",
            broadcast(
                slot = SLOT_PROMPT_CANCEL,
                key = key,
                kind = DialogExtras.KIND_PROMPT,
                mutable = false,
            ),
        ).build()
        val notification = Notification.Builder(context, DialogExtras.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setContentTitle(title)
            .setContentText(placeholder ?: "点「回复」输入，或点「取消」")
            .setAutoCancel(true)
            .addAction(replyAction)
            .addAction(cancelAction)
            .build()
        manager.notify(NOTIF_TAG, DialogExtras.notifyId(key), notification)
    }

    override fun postChooseNotification(key: Long, title: String, options: List<String>) {
        val manager = notificationManager()
        ensureChannel(manager)
        requireNotificationsEnabled(manager)

        val builder = Notification.Builder(context, DialogExtras.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setContentTitle(title)
            .setContentText("点选一项，或点「取消」")
            .setAutoCancel(true)
        options.forEachIndexed { index, label ->
            builder.addAction(
                android.R.drawable.sym_def_app_icon,
                label,
                broadcast(
                    slot = SLOT_CHOOSE_BASE + index,
                    key = key,
                    kind = DialogExtras.KIND_CHOOSE,
                    mutable = false,
                    extras = { putExtra(DialogExtras.EXTRA_INDEX, index) },
                ),
            )
        }
        builder.addAction(
            android.R.drawable.sym_def_app_icon,
            "取消",
            broadcast(
                slot = SLOT_CHOOSE_BASE + options.size,
                key = key,
                kind = DialogExtras.KIND_CHOOSE,
                mutable = false,
                extras = { putExtra(DialogExtras.EXTRA_INDEX, DialogChoice.CANCELLED_INDEX) },
            ),
        )
        manager.notify(NOTIF_TAG, DialogExtras.notifyId(key), builder.build())
    }

    override fun cancelNotification(key: Long) {
        notificationManager().cancel(NOTIF_TAG, DialogExtras.notifyId(key))
    }

    /** 显式组件广播（exported=false，仅同进程 key 登记过才回投）；requestCode 按槽位区分动作。 */
    private fun broadcast(
        slot: Int,
        key: Long,
        kind: String,
        mutable: Boolean,
        extras: Intent.() -> Unit = {},
    ): PendingIntent {
        val intent = Intent(context, DialogActionReceiver::class.java)
            .putExtra(DialogExtras.EXTRA_KEY, key)
            .putExtra(DialogExtras.EXTRA_KIND, kind)
            .apply(extras)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        flags = flags or if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, DialogExtras.requestCode(key, slot), intent, flags)
    }

    private fun overlayWindowType(): Int =
        if (overlayTypeAvailable()) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    private fun notificationManager(): NotificationManager =
        context.getSystemService(NotificationManager::class.java)
            ?: throw AutojsException(ErrorCode.ERR_SERVICE_DISABLED, "NotificationManager 不可用")

    private fun ensureChannel(manager: NotificationManager) {
        manager.createNotificationChannel(
            NotificationChannel(
                DialogExtras.CHANNEL_ID,
                DialogExtras.CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
    }

    private fun requireNotificationsEnabled(manager: NotificationManager) {
        if (!manager.areNotificationsEnabled()) {
            throw AutojsException(
                ErrorCode.ERR_PERMISSION_DENIED,
                "通知未授权发送，通知回调路径不可用（与 notification 命名空间同一事实同一折法）",
            )
        }
    }

    private companion object {
        /** 与 poster 的通知隔离：同 id 不同 tag 互不覆盖。 */
        const val NOTIF_TAG = "autoscript.dialog"

        const val SLOT_PROMPT_REPLY = 0
        const val SLOT_PROMPT_CANCEL = 1
        const val SLOT_CHOOSE_BASE = 2
    }
}

/** 通知动作回投 receiver（清单声明；显式 Intent + 未登记 key 进不来 —— 晚到答案丢弃）。 */
class DialogActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getLongExtra(DialogExtras.EXTRA_KEY, -1L)
        if (key < 0) return
        when (intent.getStringExtra(DialogExtras.EXTRA_KIND)) {
            DialogExtras.KIND_PROMPT -> {
                val results = RemoteInput.getResultsFromIntent(intent)
                val text = results?.getString(DialogExtras.REPLY_KEY)
                if (text != null) {
                    DialogResultRouter.deliverPrompt(key, text, confirmed = true)
                } else {
                    DialogResultRouter.deliverPrompt(key, null, confirmed = false)
                }
            }
            DialogExtras.KIND_CHOOSE -> {
                val index = intent.getIntExtra(DialogExtras.EXTRA_INDEX, DialogChoice.CANCELLED_INDEX)
                DialogResultRouter.deliverChoose(key, index)
            }
            else -> return // 未知动作不投（伪造/残留 intent 进不来）
        }
    }
}
