package com.autoscript.platform.system.power

import com.autoscript.domain.core.Clock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 唤醒锁账本验证（§8.7 保活与电源）。
 *
 * 这一层的价值全在**不变量**上，所以断言也照着不变量写：
 * 1. 账本里有 token ⇔ 系统锁真的取到了（拿不到就不记账）；
 * 2. 引用计数：多持有方共用一个系统锁，最后一个走才真放开
 *    （否则脚本侧 release 会把框架侧的锁也放掉 → 熄屏任务随机被拒）；
 * 3. 到期自动释放（§8.7 明文承诺的安全属性：卡死的持有方不该让 CPU 永不休眠）；
 * 4. [WakeLockLedger.isHeld] 两侧都真才算持着 —— 分歧按没持着算（宁可如实拒绝）；
 * 5. 第 4 条那个分歧态由**下一次 hold 补取**自愈 —— 判据取自系统事实（`ops.held`）
 *    而不是"账本是否为空"，且补取不重复取（`setReferenceCounted(false)` 下多取一次
 *    就是一条永远放不掉的锁）。
 */
class WakeLockLedgerTest {

    /** 假系统锁：自己记 `held`，并按脚本控制的成功/失败回答 acquire。 */
    private class FakeOps(
        var acquireOk: Boolean = true,
        var releaseOk: Boolean = true,
    ) : WakeLockOps {
        var acquires = 0
        var releases = 0
        override var held: Boolean = false

        override fun acquire(): Boolean {
            acquires++
            if (!acquireOk) return false
            held = true
            return true
        }

        override fun release(): Boolean {
            releases++
            if (!releaseOk) return false
            held = false
            return true
        }
    }

    private class FakeClock(var now: Long = 1_000L) : Clock {
        override fun nowMillis(): Long = now
    }

    @Test
    fun `取不到锁就不记账（账本 token 与系统持锁等价）`() {
        val ops = FakeOps(acquireOk = false)
        val ledger = WakeLockLedger(ops)
        assertFalse(ledger.hold("t1"), "acquire 失败必须回 false")
        assertTrue(ledger.heldTokens().isEmpty(), "拿不到锁不得记账")
        assertFalse(ledger.isHeld())
    }

    @Test
    fun `引用计数：最后一个 token 走了才真放开系统锁`() {
        val ops = FakeOps()
        val ledger = WakeLockLedger(ops)
        assertTrue(ledger.hold("framework"))
        assertTrue(ledger.hold("script:1"))
        assertEquals(1, ops.acquires, "同名系统锁只取一次")

        assertTrue(ledger.release("script:1"))
        assertEquals(0, ops.releases, "还有人持着，不得放开")
        assertTrue(ledger.isHeld(), "框架那份还持着：账本与系统两侧都真")
        assertEquals(setOf("framework"), ledger.heldTokens())

        assertTrue(ledger.release("framework"))
        assertEquals(1, ops.releases, "最后一个 token 走了才放开")
    }

    @Test
    fun `无期限 token 的 release 必须生效（null 值不得被当成没持有）`() {
        val ops = FakeOps()
        val ledger = WakeLockLedger(ops)
        assertTrue(ledger.hold("framework", timeoutMillis = null))
        assertTrue(ledger.release("framework"), "无期限 token 的 release 必须回 true")
        assertFalse(ledger.isHeld())
        assertEquals(1, ops.releases, "锁必须真被放开（否则永不休眠）")
    }

    @Test
    fun `重复 release 回 false 而不是假成功`() {
        val ops = FakeOps()
        val ledger = WakeLockLedger(ops)
        ledger.hold("t1")
        assertTrue(ledger.release("t1"))
        assertFalse(ledger.release("t1"), "重复释放如实回 false（账目错位可见）")
        assertFalse(ledger.release("从未持有"))
    }

    @Test
    fun `同名 token 重复 hold 只刷新到期不重复取锁`() {
        val ops = FakeOps()
        val clock = FakeClock(now = 1_000)
        val ledger = WakeLockLedger(ops, clock)
        assertTrue(ledger.hold("t1", timeoutMillis = 100))
        clock.now = 1_050
        assertTrue(ledger.hold("t1", timeoutMillis = 100))
        assertEquals(1, ops.acquires)
        assertEquals(setOf("t1"), ledger.heldTokens())

        // 到期时刻被刷新成 1050+100=1150：1100 时不该过期
        clock.now = 1_100
        assertTrue(ledger.sweep().isEmpty(), "刷新后的到期时刻必须生效")
        assertTrue(ledger.isHeld())
    }

    @Test
    fun `sweep 释放到期 token 并放开系统锁`() {
        val ops = FakeOps()
        val clock = FakeClock(now = 1_000)
        val ledger = WakeLockLedger(ops, clock)
        ledger.hold("script:1", timeoutMillis = 100)
        ledger.hold("framework", timeoutMillis = null)   // 无期限：永不被动释放

        clock.now = 1_099
        assertTrue(ledger.sweep().isEmpty(), "未到期不得释放")

        clock.now = 1_100
        assertEquals(listOf("script:1"), ledger.sweep(), "到点即释（§8.7 超时自动释放）")
        assertEquals(setOf("framework"), ledger.heldTokens())
        assertEquals(0, ops.releases, "还有人持着，系统锁不动")

        // 无期限的框架 token 不受 sweep 影响，直到显式 release
        clock.now = 999_999
        assertTrue(ledger.sweep().isEmpty(), "无期限 token 不该被 sweep 收走")
        ledger.release("framework")
        assertEquals(1, ops.releases)
    }

    @Test
    fun `账本与系统分歧按没持着算（宁可如实拒绝）`() {
        val ops = FakeOps()
        val ledger = WakeLockLedger(ops)
        ledger.hold("t1")
        assertTrue(ledger.isHeld())

        ops.held = false          // 系统侧把锁放掉了（外部/ROM 行为），账本还记着
        assertFalse(ledger.isHeld(), "分歧必须按没持着算：门禁宁可拒绝，也不能让任务在会休眠的 CPU 上跑")
    }

    @Test
    fun `分歧态的下一次 hold 补取锁 —— 不得回一次没有锁的假成功`() {
        // 钉的是 [WakeLockLedger.hold] 的判据**取自系统事实**（`ops.held`）而不是
        // "账本是否为空"。此前写的是 `tokens.isEmpty() && !ops.acquire()`：账本非空
        // 就完全不碰系统锁，于是在上面那条分歧态里 hold 回 true 而**实际没有锁** ——
        // 一次假成功，而且账本没有别的自愈路（sweep 只放到期 token、renew 只补服务
        // 不补锁），分歧会一直挂到最后一个持有方 release 为止。
        //
        // 后果是可观测的：`power_manager.acquire` 拿到 token（handler 只在 hold 回
        // false 时才抛 ERR_SERVICE_DISABLED），而 SCREEN_ON 门禁读的 `lockHeld()`
        // 仍是 false —— 脚本以为自己有休眠保护，任务在一个会休眠的 CPU 上跑。
        val ops = FakeOps()
        val ledger = WakeLockLedger(ops)
        assertTrue(ledger.hold("framework"))
        assertEquals(1, ops.acquires)

        ops.held = false                      // 系统侧放掉了（分歧态）
        assertTrue(ledger.hold("script:1"), "补取成功时如实回 true")
        assertEquals(2, ops.acquires, "分歧态的 hold 必须**补取**系统锁（账本非空不是理由）")
        assertTrue(ops.held, "锁必须真回来了")
        assertTrue(ledger.isHeld(), "补取后门禁读到的必须是真值")
        assertEquals(setOf("framework", "script:1"), ledger.heldTokens())
    }

    @Test
    fun `分歧态补取失败 —— 如实 false 且不记新账（假成功在这里也必须是失败）`() {
        val ops = FakeOps()
        val ledger = WakeLockLedger(ops)
        assertTrue(ledger.hold("framework"))

        ops.held = false
        ops.acquireOk = false                 // 补取也拿不到（权限被收回等）
        assertFalse(ledger.hold("script:1"), "补取失败必须如实 false，不得记一条没有锁的账")
        assertEquals(setOf("framework"), ledger.heldTokens(), "拿不到的 token 不进账本")
        assertFalse(ledger.isHeld())
    }

    @Test
    fun `系统已持着时 hold 不重复取锁 —— 补取不得补成一条放不掉的锁`() {
        // 上一条的**反面**：把判据改成"每次都 acquire"（`!ops.acquire()`）就能过上一条，
        // 但会在系统已持着时多取一次 —— 而 `AndroidWakeLockOps` 是
        // `setReferenceCounted(false)`（引用计数归账本，系统那把锁只做"取一次/放一次"），
        // 多出来的那次 acquire 对应的 release 永远不会来，现场是"锁永远持着、耗电"。
        val ops = FakeOps()
        val ledger = WakeLockLedger(ops)
        assertTrue(ledger.hold("framework"))
        assertTrue(ledger.hold("script:1"))
        assertTrue(ledger.hold("script:2", timeoutMillis = 60_000))
        assertEquals(1, ops.acquires, "系统已持着 → 一次都不多取")
        assertEquals(3, ledger.heldTokens().size)

        // 最后一个走了才放开，且只放一次（与上一条的"不多取"合成配对纪律）。
        assertTrue(ledger.release("script:1"))
        assertTrue(ledger.release("script:2"))
        assertEquals(0, ops.releases, "还有人持着，系统锁不动")
        assertTrue(ledger.release("framework"))
        assertEquals(1, ops.releases, "最后一个 token 走了放开**一次**（不多放也不留锁）")
    }

    @Test
    fun `空白 token 与非法超时在构造期拒绝`() {
        val ledger = WakeLockLedger(FakeOps())
        assertThrows(IllegalArgumentException::class.java) { ledger.hold("  ") }
        assertThrows(IllegalArgumentException::class.java) { ledger.hold("t", timeoutMillis = 0) }
        assertThrows(IllegalArgumentException::class.java) { ledger.hold("t", timeoutMillis = -1) }
        assertTrue(ledger.heldTokens().isEmpty(), "非法入参不得留下 token")
    }

    @Test
    fun `没有任何 token 时 sweep 不无谓碰系统锁`() {
        val ops = FakeOps()
        val ledger = WakeLockLedger(ops, FakeClock())
        assertTrue(ledger.sweep().isEmpty())
        assertEquals(0, ops.releases, "空账本的 sweep 不该产生 release（避免多余的 acquire/release 抖动）")
        Unit
    }
}
