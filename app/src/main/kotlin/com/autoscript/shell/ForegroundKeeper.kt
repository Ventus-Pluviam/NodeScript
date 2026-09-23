package com.autoscript.shell

import com.autoscript.domain.core.Clock
import com.autoscript.domain.core.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 前台服务保活编排（docs §8.7）：框架 token 的取/放 + 唤醒锁账本的持有方 +
 * 到期自动释放的驱动者。**判断全在这里，系统调用全在 [ForegroundOps]**。
 *
 * 为什么把三者放一起（而不是各自一个对象）：它们共享**同一个生命周期**——
 * 保活服务的起停与"框架 token 是否持着"必须同进同退，否则出现两类假账：
 * 服务停了而账本说在保活（能力中心骗人），或账本说没保活而服务在跑（锁没人续期）。
 * 一个类持有这条生命周期的全部状态，比三个对象靠调用方记得同步它们可靠。
 *
 * 三条诚实纪律：
 * - **[start] 失败不记账**：服务拉不起来 / 进前台失败 → `isActive()` 为 false，
 *   **不写 token**；于是 `SCREEN_ON` 任务被屏幕门禁如实拒绝（见 [WakeLockLedger.isHeld]），
 *   而不是"以为有锁、跑在会休眠的 CPU 上"；
 * - **[isActive] 两侧都真才算**：系统事实（`ops.foregroundRunning`）**且** 账本真持着锁。
 *   任何一侧不成立 → false。这是能力中心"保活"那一行的判据，不许乐观；
 * - **停不下来如实回 false**：服务停掉了但系统说还在前台（`stopService` 返回后
 *   `foregroundRunning` 仍真，系统回收有延迟）→ 留账本、回 false，由 [renew] 下一轮再收。
 *   这里**不假装停过**（那会让"锁还持着而账本已清"成为可能，锁再也不释放）。
 *
 * **框架 token 永不超时**（`timeoutMillis = null`）：它的期限就是应用进程的生命周期，
 * 由 [stop] 显式释放（`AppShellApplication.onTerminate` / 测试收口）。
 * 超时自动释放只针对**有期限的持有方**（P1 的脚本 `power_manager` 请求）。
 *
 * 线程契约：方法可被任意线程调用（服务回调走主线程、门禁走调度协程），
 * 状态用 `@Synchronized` 收口；ticker 是唯一的后台线程。
 */
class ForegroundKeeper(
    private val ops: ForegroundOps,
    private val wakeLocks: WakeLockLedger,
    private val clock: Clock = SystemClock,
    /** 续期/清理周期。默认 15 分钟：Android 的 FGS 没有固定超时（specialUse 尤其），
     *  这个 tick 只为**到期唤醒锁的及时回收**与 FGS 心跳，不是续命所需。 */
    private val tickMillis: Long = DEFAULT_TICK_MILLIS,
) {

    /** 框架 token：无期限（见类 KDoc）。 */
    private var frameworkToken: String? = null

    private var ticker: ScheduledExecutorService? = null
    private var tickerTask: ScheduledFuture<*>? = null

    /**
     * 起保活（幂等）：拉服务 → token 取锁 → 成功才记账并开 ticker。
     *
     * @param token 持有方标识（同一 token 重复 start 只刷新到期，见 [WakeLockLedger.hold]）；
     *   缺省 [FRAMEWORK_TOKEN]（框架侧唯一持有方）。
     * @param timeoutMillis 该 token 的到期时刻（null = 无期限，见类 KDoc）。
     * @return false = 任一步没成（服务拉不起/取不到锁）—— **未记账**，
     *   [isActive] 为 false。
     */
    @Synchronized
    fun start(token: String = FRAMEWORK_TOKEN, timeoutMillis: Long? = null): Boolean {
        if (!ops.startService()) return false
        if (!wakeLocks.hold(token, timeoutMillis)) {
            // 锁没拿到：服务已拉起但不记账 —— 保住"账上有 token ⇔ 真有锁"这条不变量。
            // 服务留在前台是系统的决定，isActive() 会因账本为空而回 false。
            return false
        }
        frameworkToken = token
        startTickerLocked()
        return true
    }

    /**
     * 停保活（幂等）：停 ticker → 放框架 token → 停服务。
     *
     * **顺序**：先停 ticker（别再续期一个马上要放的锁）→ 再放 token（[WakeLockLedger] 在
     * 最后一个 token 走时释放系统锁）→ 最后停服务。
     *
     * @return false = 服务停不下来（系统仍报在前台）或有东西没放掉 —— 此时**账本保持**，
     *   由下一次 [renew] 继续收。真值语义见类 KDoc 第三条。
     */
    @Synchronized
    fun stop(): Boolean {
        stopTickerLocked()
        val token = frameworkToken
        if (token != null) wakeLocks.release(token)
        frameworkToken = null
        val stopped = ops.stopService()
        // 系统说还在前台 = 没停干净：账本已清但服务仍跑 —— 这时**不算成功**。
        // 下一轮 renew 会再停一次（stop 幂等）。
        return stopped && !ops.foregroundRunning
    }

    /**
     * 系统事实 + 账本两侧都真才算保活（见类 KDoc 第二条）。
     *
     * 能力中心的「保活」那一行读这里；`SCREEN_ON` 门禁读的是 [WakeLockLedger.isHeld]
     * （它只关心 CPU 不休眠，不关心服务在不在前台 —— 两个问题不该混在一个布尔里）。
     */
    fun isActive(): Boolean = ops.foregroundRunning && wakeLocks.isHeld()

    /**
     * 续期一轮（ticker 的落地，也是能力中心"立即刷新"的入口）：
     * 1. [WakeLockLedger.sweep] 释放到期 token（§8.7 超时自动释放）；
     * 2. 若账本仍有 token 而系统没在跑服务 → 补拉（服务被 ROM 杀掉但进程还活着）；
     * 3. 若账本空了而服务还在前台 → 停掉（别留一个没有持有方的常驻通知）。
     *
     * @return 本轮动作的如实描述（诊断/ticker 日志用；无动作时为空列表）。
     */
    @Synchronized
    fun renew(): List<String> {
        val actions = mutableListOf<String>()
        val expired = wakeLocks.sweep()
        if (expired.isNotEmpty()) actions += "释放到期锁：${expired.joinToString(",")}"
        if (wakeLocks.heldTokens().isNotEmpty() && !ops.foregroundRunning) {
            if (ops.startService()) actions += "补拉保活服务" else actions += "补拉保活服务失败"
        }
        if (wakeLocks.heldTokens().isEmpty() && ops.foregroundRunning) {
            if (ops.stopService()) actions += "停掉无持有方的保活服务" else actions += "停保活服务失败"
        }
        return actions
    }

    /**
     * 服务被系统销毁（[ForegroundServiceBase.onDestroy] 上报）。
     *
     * **只对齐系统事实，不动账本**：服务被销毁不等于框架持有方退出（应用进程可能还活着，
     * 下一轮 [renew] 会补拉服务）。**也不释放框架 token** —— 释放它才是错的：
     * 那会让"锁还该持着"的持有方（正在跑的脚本/正在等的投递）突然失去休眠保护，
     * 而它们完全不知道服务被系统回收过。
     */
    @Synchronized
    fun onServiceDestroyed() {
        // 系统事实由 ForegroundHost.foregroundRunning 承载（服务自己写 false），
        // 这里只记一笔诊断：账本与服务不同步是**预期**，下一轮 renew 收敛。
    }

    /** ticker 是否在转（诊断/单测；不是保活判据 —— 那个是 [isActive]）。 */
    @Synchronized
    fun ticking(): Boolean = tickerTask != null

    /** 账本（能力中心要显示"谁持着锁、还剩多久"时读）。 */
    fun wakeLocks(): WakeLockLedger = wakeLocks

    private fun startTickerLocked() {
        if (tickerTask != null) return
        val exec = ticker ?: Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "autoscript-keepalive").apply { isDaemon = true }
        }.also { ticker = it }
        tickerTask = exec.scheduleWithFixedDelay(
            { tickerTick() },
            tickMillis, tickMillis, TimeUnit.MILLISECONDS,
        )
    }

    private fun stopTickerLocked() {
        tickerTask?.cancel(false)
        tickerTask = null
    }

    /**
     * ticker 体：跑一轮 [renew]。
     *
     * **绝不让异常终止轮转**：`scheduleWithFixedDelay` 的任务一旦抛出就不再调度
     * （静默停止 = 到期锁再也没人释放）。这是 §8.7「配套超时自动释放」唯一的驱动，
     * 停转的后果正是这条承诺失效，所以这里必须兜住一切（与 `EngineWatchdog` 同纪律）。
     */
    private fun tickerTick() {
        try {
            renew()
        } catch (t: Throwable) {
            // 吞掉：轮转的存续比单轮的结果重要（单轮失败下一轮还会来）。
        }
    }

    companion object {
        /** 框架侧持有方 token（[WakeLockLedger] 里与 P1 脚本请求区分开）。 */
        const val FRAMEWORK_TOKEN = "framework:keepalive"

        /** 续期/清理周期（见构造参数说明）。 */
        const val DEFAULT_TICK_MILLIS: Long = 15 * 60 * 1000L

        /** 服务指令 action（[Intent] 里与服务侧 `when` 逐字对齐）。 */
        const val ACTION_START = "com.autoscript.shell.action.FOREGROUND_START"
        const val ACTION_STOP = "com.autoscript.shell.action.FOREGROUND_STOP"

        /** 服务指令 extra（token/到期；见 [ForegroundServiceBase.onStartCommand]）。 */
        const val EXTRA_TOKEN = "com.autoscript.shell.extra.FOREGROUND_TOKEN"
        const val EXTRA_TIMEOUT_MILLIS = "com.autoscript.shell.extra.FOREGROUND_TIMEOUT"
    }
}
