package com.autoscript.shell

import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.autoscript.domain.core.Clock
import com.autoscript.domain.core.SystemClock

/**
 * 系统唤醒锁接触面（docs §8.7 「保活与电源」）：唯一碰 `PowerManager.WakeLock` 的地方。
 *
 * 契约只有三条，判断一条也不在这里：
 * - [acquire] 取一次系统锁（同 tag 单实例）。**false = 拿不到**（无 PowerManager /
 *   权限被拒 / 系统拒绝），调用方据此不记账 —— 绝不假装拿到了；
 * - [release] 放开；**false = 当时并没有持着**。不抛，也不假装释放过；
 * - [held] 系统此刻是否真持着（`WakeLock.isHeld`）。这是**唯一事实源**：
 *   账本自己记的"应该持着"不算数（见 [WakeLockLedger.isHeld]）。
 *
 * 实现**不抛异常**（都折成 false + 日志）：调用方是投递前的门禁路径，
 * 让它去接一个 SecurityException 只会把"没锁"变成"任务失败但原因不明"。
 */
interface WakeLockOps {
    fun acquire(): Boolean
    fun release(): Boolean
    val held: Boolean
}

/**
 * 唤醒锁账本（§8.7）：token 引用计数 + 超时到期，系统锁的取/放由本类决定。
 *
 * 为什么要账本而不是布尔：唤醒锁是**进程级单资源**，而持有方至少有两类 ——
 * 框架侧的前台服务（[ForegroundKeeper] 的框架 token）与（P1 的）脚本侧
 * `power_manager` 请求。两者共用一个系统锁，必须引用计数才不会互相踩：
 * 脚本释放时把框架的锁也放掉，表现就是"熄屏任务随机被拒"，现场极难查。
 *
 * 三条诚实纪律：
 * - **拿不到就不记账**：`ops.acquire()` 回 false 时 [hold] 回 false 且不写入 token ——
 *   账本里存在 token ⇔ 系统锁真的取到了（除非下面第三条的分歧）；
 * - **超时自动释放**（§8.7「配套超时自动释放」）：带 `timeoutMillis` 的 token 到期后由
 *   [sweep] 释放。这是**安全属性**，不是优化 —— 一个卡死的持有方不该让 CPU 永远不休眠。
 *   驱动方是 [ForegroundKeeper.startTicker]（周期性 renew + sweep）；
 * - **[isHeld] 两侧都真才算持着**：账本有 token **且** `ops.held` 为真。两者分歧
 *   （例如锁被外部/系统放掉）时按**没持着**算 —— 门禁宁可如实拒绝一次亮屏任务，
 *   也不能让任务在一个其实会休眠的 CPU 上跑（那条路径的表现是"任务成功、实际什么都没发生"）。
 *
 * 线程契约：全部方法 `@Synchronized`（门禁查询、服务生命周期回调、ticker 三方并发）。
 */
class WakeLockLedger(
    private val ops: WakeLockOps,
    private val clock: Clock = SystemClock,
) {

    /** token → 到期时刻（`null` = 无期限，进程级持有）。[LinkedHashMap] 只为诊断顺序稳定。 */
    private val tokens = LinkedHashMap<String, Long?>()

    /**
     * 持有（幂等）：同名 token 重复调用只刷新到期时刻，不重复取系统锁。
     *
     * @param timeoutMillis 到期自动释放的期限；`null` = 无期限（由持有方自己负责在
     *   生命周期结束时 [release]，如前台服务的 `onDestroy`）。
     * @return false = 系统锁没取到（**未记账**）。此时 [isHeld] 为 false，
     *   `SCREEN_ON` 任务会被屏幕门禁如实拒绝。
     */
    @Synchronized
    fun hold(token: String, timeoutMillis: Long? = null): Boolean {
        require(token.isNotBlank()) { "token 不得为空白（空白 token 会让 release 找不到持有方）" }
        require(timeoutMillis == null || timeoutMillis > 0) {
            "timeoutMillis 必须 > 0（0/负数等于要求立刻过期，疑似漏配）: $timeoutMillis"
        }
        if (tokens.isEmpty() && !ops.acquire()) return false
        tokens[token] = timeoutMillis?.let { clock.nowMillis() + it }
        return true
    }

    /**
     * 释放（幂等）：只放自己那一份，最后一个 token 走了才真正放开系统锁。
     *
     * @return false = 本 token 当时并未持有（重复释放/从未持有）—— 如实回 false 而不是
     *   假成功：调用方据此能发现"我以为我持着"的账目错位。
     */
    @Synchronized
    fun release(token: String): Boolean {
        // 先 containsKey 再 remove：无期限 token 的 value 就是 null，
        // 直接看 remove 的返回值会把"持有中"误判成"没持有"（release 静默失效 → 锁永不释放）。
        if (!tokens.containsKey(token)) return false
        tokens.remove(token)
        if (tokens.isEmpty()) ops.release()
        return true
    }

    /**
     * 到期清理（§8.7 超时自动释放）：释放所有已过期的 token，返回被释放的 token 列表。
     *
     * 未过期／无期限的 token 不动；一个都没过期时**不碰系统锁**（不做无谓的 release+acquire）。
     */
    @Synchronized
    fun sweep(): List<String> {
        val now = clock.nowMillis()
        val expired = tokens.filterValues { it != null && it <= now }.keys.toList()
        if (expired.isEmpty()) return emptyList()
        expired.forEach { tokens.remove(it) }
        if (tokens.isEmpty()) ops.release()
        return expired
    }

    /** 见类 KDoc 第三条：账本与系统两侧都真才算持着（分歧按没持着算）。 */
    @Synchronized
    fun isHeld(): Boolean = tokens.isNotEmpty() && ops.held

    /** 当前持有的 token（诊断/单测；只读快照）。 */
    @Synchronized
    fun heldTokens(): Set<String> = tokens.keys.toSet()
}

/**
 * [WakeLockOps] 的真机实现（§8.7）：唯一碰 `PowerManager` 的地方。
 *
 * **为什么是 `PARTIAL_WAKE_LOCK`**：自动化要的是"CPU 不休眠"，不是"屏幕亮着"——
 * 屏幕亮不亮由用户/系统决定，门禁侧另有 `PowerManager.isInteractive` 那条判据
 * （[AndroidScreenGate] 两个信号都问）。而 `FULL_WAKE_LOCK`/`SCREEN_*_WAKE_LOCK` 与
 * `ACQUIRE_CAUSES_WAKEUP` 在 API 33 起已废弃（系统不鼓励后台应用强行点屏），
 * 本实现一条都不用。
 *
 * **`setReferenceCounted(false)`**：引用计数由 [WakeLockLedger] 负责，系统那把锁
 * 只做"取一次/放一次"。默认的引用计数模式下 acquire/release 必须严格配对，
 * 一旦某条路径多 acquire 一次，最后一次 release 就放不掉（表现为"锁永远持着、耗电"）。
 *
 * 构造期取不到 `PowerManager`（极端裁剪 ROM/系统服务缺失）不是崩溃点：
 * [lock] 为 null，[acquire] 如实回 false —— 门禁因此拒绝亮屏任务，而不是让任务
 * 在一个会休眠的 CPU 上跑完还报成功。
 */
class AndroidWakeLockOps(context: Context, tag: String = DEFAULT_TAG) : WakeLockOps {

    private val lock: PowerManager.WakeLock? = try {
        context.applicationContext.getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag)
            ?.apply { setReferenceCounted(false) }
    } catch (t: Throwable) {
        Log.e(TAG, "取 PowerManager 失败：唤醒锁不可用（亮屏任务将如实被拒）", t)
        null
    }

    override fun acquire(): Boolean {
        val l = lock ?: return false
        return try {
            l.acquire()
            true
        } catch (t: Throwable) {
            // SecurityException（权限被收回）等一律折成 false：调用方要的是"有没有锁"，
            // 不是异常类型 —— 异常抛出会让投递路径以"任务失败"告终而看不出是缺锁。
            Log.e(TAG, "唤醒锁获取失败", t)
            false
        }
    }

    override fun release(): Boolean {
        val l = lock ?: return false
        return try {
            if (!l.isHeld) {
                false
            } else {
                l.release()
                true
            }
        } catch (t: Throwable) {
            Log.e(TAG, "唤醒锁释放失败", t)
            false
        }
    }

    /** 系统事实（`WakeLock.isHeld`）：账本 `isHeld` 的一半判据。 */
    override val held: Boolean
        get() = lock?.isHeld ?: false

    private companion object {
        const val TAG = "AndroidWakeLock"

        /** tag 只用于 `adb shell dumpsys power` 排查，约定 `<应用>:<用途>`。 */
        const val DEFAULT_TAG = "AutoScript:automation"
    }
}
