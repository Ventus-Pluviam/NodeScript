package com.autoscript.shell

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
 * 4. [WakeLockLedger.isHeld] 两侧都真才算持着 —— 分歧按没持着算（宁可如实拒绝）。
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
