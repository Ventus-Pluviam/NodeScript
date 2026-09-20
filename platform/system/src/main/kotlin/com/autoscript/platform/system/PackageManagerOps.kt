package com.autoscript.platform.system

import android.content.Context
import android.content.Intent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [AndroidAppLauncher.AppOps] 的真机实现（docs §9.3）：碰 `PackageManager`/`startActivity` 的地方。
 *
 * `Dispatchers.Main` 是 `startActivity` 的要求（从 Application context 起 Activity 必须有
 * 一个前台栈可落）；`getLaunchIntentForPackage` 本身线程无关，放在一起是为了让调用方只有
 * 一个线程口径。
 */
class PackageManagerOps(private val context: Context) : AndroidAppLauncher.AppOps {

    override fun hasLaunchEntry(packageName: String): Boolean =
        context.packageManager.getLaunchIntentForPackage(packageName) != null

    override suspend fun start(packageName: String) = withContext(Dispatchers.Main) {
        val intent: Intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: error("launch intent 消失（$packageName）")
        // 从 Application context 启动必须带 NEW_TASK，否则 ActivityNotFoundException。
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}

/**
 * [AndroidAppLauncher.ForegroundEvents] 的真机实现（docs §9.3）：碰 `UsageStatsManager` 的地方。
 *
 * 需要 `PACKAGE_USAGE_STATS`（用户在系统设置里授予，属"特殊权限"，不能运行时弹窗申请）。
 * **没授权就如实回空列表** —— 于是 `currentPackage` 回 null，脚本侧看得出"取不到"，
 * 而不是拿到一个恒等于自身包名的假值（那比 null 更坏，因为它看起来是对的）。
 *
 * `ACTIVITY_RESUMED`（API 29+ 的新名）与 `MOVE_TO_FOREGROUND`（旧名，值相同）两种都认，
 * 免得在 API 29+ 上把新事件漏掉。
 */
class UsageStatsEvents(private val context: Context) : AndroidAppLauncher.ForegroundEvents {

    override fun since(sinceMillis: Long): List<ForegroundEvent> {
        val usage = context.getSystemService(UsageStatsManager::class.java) ?: return emptyList()
        val out = ArrayList<ForegroundEvent>()
        try {
            val iterator = usage.queryEvents(sinceMillis, System.currentTimeMillis()) ?: return emptyList()
            val event = UsageEvents.Event()
            while (iterator.hasNextEvent()) {
                iterator.getNextEvent(event)
                if (event.eventType != UsageEvents.Event.MOVE_TO_FOREGROUND &&
                    event.eventType != UsageEvents.Event.ACTIVITY_RESUMED
                ) {
                    continue
                }
                val pkg = event.packageName ?: continue
                out += ForegroundEvent(pkg, event.timeStamp)
            }
        } catch (e: Exception) {
            // 未授予 PACKAGE_USAGE_STATS：SecurityException 是常态，如实回空（上层折成 null）。
            Log.w(TAG, "前台事件查询失败（多半缺 PACKAGE_USAGE_STATS）", e)
            return emptyList()
        }
        return out
    }

    private companion object {
        const val TAG = "UsageStatsEvents"
    }
}
