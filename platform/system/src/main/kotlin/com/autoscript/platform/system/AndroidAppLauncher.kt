package com.autoscript.platform.system

import com.autoscript.domain.system.AppLauncher

/**
 * `app` 命名空间的 Android 实现（docs §9.3/§9.6；SPI 见 `:domain` 的 [AppLauncher]）。
 *
 * 两条契约的落点，**都由本类（而非 ops）决定**，所以 JVM 单测能钉住：
 * - [launch] 回 `false`（**不抛异常**）：包没装/没有可启动入口是"答案"不是"故障"。
 *   JS facade 用 `=== true` 判成败（`extras.ts`），抛错会把 `try/catch` 策略退化成
 *   "只有崩了才算失败"；被系统拦下（禁用/厂商拦截）同样是 false。
 * - [currentPackage] 回 `null`（**不抛异常、不给空串**）：查不到前台包就说查不到。
 *   空串更坏 —— 它会一路拼进日志和判断，看起来像个包名。
 *
 * 前台包名取"回看窗口内时间戳最大的前台事件"（[latestForeground]）：这是与
 * "此刻在前台"最接近的可查信号。**为什么不用 `ActivityManager.getRunningTasks`**：
 * API 21 起对第三方应用阉割（只剩自己），拿它当答案会得到恒等于自身包名的假值。
 *
 * 两个缝（[AppOps] / [ForegroundEvents]）把"系统怎么查"挡在外面：真机实现见
 * [PackageManagerOps] / [UsageStatsEvents]，单测注入即时序列 —— 与
 * [AndroidFloatingWindowHost.FloatingWindowOps] 同一套办法，本类因此零 Android 依赖。
 */
class AndroidAppLauncher(
    private val ops: AppOps,
    private val events: ForegroundEvents,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : AppLauncher {

    override suspend fun launch(packageName: String): Boolean {
        if (!ops.hasLaunchEntry(packageName)) return false
        return try {
            ops.start(packageName)
            true
        } catch (e: Exception) {
            // 包在但起不来（被禁用/权限拒绝/厂商拦截）：如实 false，不假装成功。
            false
        }
    }

    override suspend fun currentPackage(): String? =
        latestForeground(events.since(clock() - LOOKBACK_MILLIS))

    /** 应用开关的系统接触面。真机 = `PackageManager` + `startActivity`，单测 = 内存替身。 */
    interface AppOps {
        /** 该包有没有可启动入口（false = 没装 / 没有 launcher activity）。 */
        fun hasLaunchEntry(packageName: String): Boolean

        /** 启动该包；起不来时抛异常（本类折成 `false`）。挂起：真机实现要切到主线程。 */
        suspend fun start(packageName: String)
    }

    /** 前台事件查询缝。真机 = `UsageStatsManager.queryEvents`，单测 = 即时序列。 */
    fun interface ForegroundEvents {
        /** 回看窗口内的前台事件（时间戳升序或乱序都行，选择逻辑不依赖顺序）。 */
        fun since(sinceMillis: Long): List<ForegroundEvent>
    }

    private companion object {
        /**
         * 前台事件回看窗口：分钟级。太短会在刚切过后查空，太长会捞到已退到后台的包。
         */
        const val LOOKBACK_MILLIS: Long = 5 * 60 * 1000L

        /** 时间戳最大者胜；无事件 → null（"查不到"就说查不到）。 */
        fun latestForeground(events: List<ForegroundEvent>): String? =
            events.maxByOrNull { it.timestamp }?.packageName
    }
}

/**
 * 前台事件三元组的纯 JVM 形态（与 `UsageEvents.Event` 解耦，后者在桌面 JVM 上不可实例化）。
 * 只带选择逻辑真正需要的两字段 + 时间戳。
 */
data class ForegroundEvent(
    val packageName: String,
    val timestamp: Long,
)
