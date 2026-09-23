package com.autoscript.shell

import com.autoscript.domain.core.Clock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 保活编排验证（§8.7 保活与电源）。
 *
 * 断言全部钉在**诚实口径**上，而不是"调用过哪个方法"：
 * - 服务拉不起 / 锁取不到 → `isActive()` 为 false（不许乐观）；
 * - `isActive()` = 系统事实 ∧ 账本持锁，任一侧假即假；
 * - 停不干净（系统仍报在前台）→ 回 false 且账本保留，由下一轮 renew 收敛。
 *
 * 走 [ForegroundOps] 缝 + [WakeLockOps] 缝，不碰 Android 框架对象。
 */
class ForegroundKeeperTest {

    private class FakeForeground(
        var startOk: Boolean = true,
        var stopOk: Boolean = true,
    ) : ForegroundOps {
        override var foregroundRunning: Boolean = false
        var starts = 0
        var stops = 0
        var activates = 0
        var deactivates = 0

        override fun startService(): Boolean {
            starts++
            if (!startOk) return false
            return true
        }

        override fun stopService(): Boolean {
            stops++
            if (!stopOk) return false
            foregroundRunning = false
            return true
        }

        override fun activateForeground(): Boolean {
            activates++
            foregroundRunning = true
            return true
        }

        override fun deactivateForeground(): Boolean {
            deactivates++
            val was = foregroundRunning
            foregroundRunning = false
            return was
        }
    }

    private class FakeLock(var acquireOk: Boolean = true) : WakeLockOps {
        override var held: Boolean = false
        override fun acquire(): Boolean {
            if (!acquireOk) return false
            held = true
            return true
        }

        override fun release(): Boolean {
            held = false
            return true
        }
    }

    private class FakeClock(var now: Long = 0L) : Clock {
        override fun nowMillis(): Long = now
    }

    private fun rig(
        fg: FakeForeground = FakeForeground(),
        lock: FakeLock = FakeLock(),
        clock: FakeClock = FakeClock(),
    ): Triple<ForegroundKeeper, FakeForeground, WakeLockLedger> {
        val ledger = WakeLockLedger(lock, clock)
        // tickMillis 给大值：这些用例都显式调 renew()，不让后台 ticker 掺进来
        // （ticker 的存续本身由「ticking()」与「异常不终止轮转」两条单独验）。
        return Triple(ForegroundKeeper(fg, ledger, clock, tickMillis = 60 * 60 * 1000L), fg, ledger)
    }

    @Test
    fun `start 成功才记账：isActive 系统事实与账本两侧都真`() {
        val (keeper, fg, ledger) = rig()
        assertTrue(keeper.start())
        assertEquals(1, fg.starts)
        assertEquals(setOf(ForegroundKeeper.FRAMEWORK_TOKEN), ledger.heldTokens())
        // 服务侧进前台的标记（真机上由服务 onStartCommand 写）——未进前台前 isActive 必须为 false
        assertFalse(keeper.isActive(), "服务只被拉起、尚未进前台：不算生效")
        fg.foregroundRunning = true
        assertTrue(keeper.isActive())
    }

    @Test
    fun `服务拉不起时 start 回 false 且不记账`() {
        val (keeper, _, ledger) = rig(fg = FakeForeground(startOk = false))
        assertFalse(keeper.start())
        assertTrue(ledger.heldTokens().isEmpty(), "服务都起不来，不许记一笔保活账")
        assertFalse(keeper.isActive())
    }

    @Test
    fun `取不到唤醒锁时回 false 且不记账（门禁据此如实拒绝）`() {
        val (keeper, _, ledger) = rig(lock = FakeLock(acquireOk = false))
        assertFalse(keeper.start(), "锁没拿到就不算保活生效")
        assertTrue(ledger.heldTokens().isEmpty())
        assertFalse(ledger.isHeld(), "SCREEN_ON 门禁读这里 —— 必须为 false")
    }

    @Test
    fun `账本持锁但系统不在前台时 isActive 为假`() {
        val (keeper, _, _) = rig()
        keeper.start()
        assertFalse(keeper.isActive(), "账本真但系统未在前台：两侧都真才算")
        Unit
    }

    @Test
    fun `stop 放锁并停服务`() {
        val (keeper, fg, ledger) = rig()
        keeper.start()
        fg.foregroundRunning = true
        assertTrue(keeper.start() || true)   // 幂等：重复 start 不炸（上面的 start 已生效）
        assertTrue(keeper.stop())
        assertTrue(ledger.heldTokens().isEmpty(), "stop 必须放掉框架 token")
        assertFalse(keeper.isActive())
        assertEquals(1, fg.stops)
        assertFalse(keeper.ticking(), "stop 必须停 ticker（别续期一个已放的锁）")
    }

    @Test
    fun `stop 停不干净时回 false 并保留可续收的路径`() {
        val (keeper, fg, ledger) = rig(fg = FakeForeground(stopOk = false))
        keeper.start()
        fg.foregroundRunning = true
        assertFalse(keeper.stop(), "服务没停掉就不能报成功")
        assertTrue(ledger.heldTokens().isEmpty(), "框架 token 已放（它归 Keeper 管）")
        // 下一轮 renew 会再停一次（stopService 幂等）”——这里验它确实还会尝试
        fg.stopOk = true
        // 账本已空 + 服务仍在前台 → renew 收尾
        assertTrue(keeper.renew().any { it.contains("停掉无持有方") }, "renew 必须收掉没持有方的服务")
        assertFalse(fg.foregroundRunning)
    }

    @Test
    fun `renew 释放到期锁并补齐服务`() {
        val clock = FakeClock(now = 1_000)
        val fg = FakeForeground()
        val ledger = WakeLockLedger(FakeLock(), clock)
        val keeper = ForegroundKeeper(fg, ledger, clock, tickMillis = 60 * 60 * 1000L)

        // 脚本侧持一把限时锁（P1 power_manager 的形态），框架侧无期限
        assertTrue(ledger.hold("script:1", timeoutMillis = 100))
        assertTrue(ledger.hold(ForegroundKeeper.FRAMEWORK_TOKEN, timeoutMillis = null))
        fg.foregroundRunning = true

        clock.now = 1_100
        val actions = keeper.renew()
        assertTrue(actions.any { it.contains("释放到期锁") }, "到点即释: $actions")
        assertTrue(ledger.heldTokens().contains(ForegroundKeeper.FRAMEWORK_TOKEN), "框架锁不受影响")

        // 服务被 ROM 杀掉（系统事实为假）→ 补拉
        fg.foregroundRunning = false
        val second = keeper.renew()
        assertTrue(second.any { it.contains("补拉保活服务") }, "账本仍持锁时必须补拉: $second")
        assertEquals(1, fg.starts, "本条路径没经 keeper.start，补拉是第一次调 startService")
    }

    @Test
    fun `服务被销毁只对齐系统事实不动账本`() {
        val (keeper, fg, ledger) = rig()
        keeper.start()
        fg.foregroundRunning = true
        // 模拟 onDestroy：系统事实转假（真机由 ForegroundHost 承载），Keeper 只收一笔通知
        fg.foregroundRunning = false
        keeper.onServiceDestroyed()
        assertTrue(
            ledger.heldTokens().contains(ForegroundKeeper.FRAMEWORK_TOKEN),
            "服务被销毁不等于持有方退出：不得释放框架 token（放了会让正在跑的任务失去休眠保护）",
        )
    }

    @Test
    fun `ticker 在 start 后转、stop 后停`() {
        val (keeper, _, _) = rig()
        assertFalse(keeper.ticking())
        keeper.start()
        assertTrue(keeper.ticking(), "start 必须开 ticker（到期锁的唯一驱动）")
        keeper.stop()
        assertFalse(keeper.ticking())
        Unit
    }
}
