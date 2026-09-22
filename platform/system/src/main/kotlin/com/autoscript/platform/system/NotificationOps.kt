package com.autoscript.platform.system

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.autoscript.domain.system.NotificationSpec

/**
 * [AndroidNotificationPoster.Ops] 的真机实现（docs §12.2）：唯一碰
 * `android.app.NotificationManager` 的地方。门禁判断（canPost false 时抛什么）
 * 在宿主 [AndroidNotificationPoster] 里 —— 这里只有「问/发/撤 + 把系统事实折成契约形状」。
 *
 * **默认 channel 懒建**：minSdk 26 起 `notify` 必须带 channel，而 §12.2 的契约面
 * 刻意不暴露 channelId（见 `:domain` [NotificationSpec] KDoc）。于是 channel 归实现：
 * 首次投递时建一条应用自有 channel（id 固定、名取应用标签），已存在则复用 ——
 * channel 是**实现细节**，不是脚本要操心的资源。用户在系统里关掉这条 channel 之后
 * [canPost] 会跟着变 false（`areNotificationsEnabled` 覆盖 channel 级关闭），
 * 门禁与实际投递因此同源，不会出现「门禁说行、系统不显示」。
 *
 * 本机 JVM **只编译不执行**（系统服务 + Context，stub 运行期抛异常）。
 */
class NotificationOps(private val context: Context) : AndroidNotificationPoster.Ops {

    private val manager: NotificationManager?
        get() = context.getSystemService(NotificationManager::class.java)

    override fun canPost(): Boolean = manager?.areNotificationsEnabled() ?: false

    override fun post(spec: NotificationSpec) {
        val nm = manager ?: return            // 无服务 = 装不进通知，宿主已在 canPost 处理
        ensureChannel(nm)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(context)
        }
        builder.setContentText(spec.text).setSmallIcon(android.R.drawable.stat_notify_more)
        builder.setContentTitle(spec.title ?: appLabel())
        // 通知 id 同号覆盖（NotificationSpec.id 的契约语义），系统据此更新同一条而非堆叠。
        nm.notify(spec.id, builder.build())
    }

    /**
     * 撤销：`notify` 的对偶，同样**没有返回值也不抛**。契约回 Unit 正因为如此 ——
     * `NotificationManager` 没有"这条还在不在"的读口，任何 Boolean 都只能是编的。
     */
    override fun cancel(id: Int) {
        manager?.cancel(id)
    }

    private fun appLabel(): String =
        context.applicationInfo.loadLabel(context.packageManager).toString()

    private fun ensureChannel(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, appLabel(), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    companion object {
        /** 实现自有的默认 channel（契约面不暴露；固定 id 便于系统设置页稳定显示）。 */
        const val CHANNEL_ID = "autoscript.notify"
    }
}
