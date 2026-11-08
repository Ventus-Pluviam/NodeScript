package com.autoscript.domain.automation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 投屏同意征询的**单请求状态机**（§9.2）判据。
 *
 * 这个状态机存在是因为 `createScreenCaptureIntent()` 的回调**不带请求标识** ——
 * 回调说不出"我是哪一次请求的结果"。所以下面每条用例都对应一个真实故障：
 *
 * - **并发覆盖**：脚本在等，能力中心又拉起一次 → 前一个等待者永远等不到（脚本卡死）；
 * - **迟到结果错配**：上一次的等待者已撤销、对话框还在系统里，此时新请求进来，
 *   旧结果回来被当成新请求的结果交付（把一次旧同意发给下一次执行）；
 * - **销毁悬挂**：Activity 销毁，等待者没人叫醒；
 * - **拉不起来却占着在途**：此后所有征询都被挡掉（`busy` 永久为真）。
 */
class ScreenConsentRequestsTest {

    private val requests = ScreenConsentRequests()
    private val granted = ScreenConsentOutcome(launched = true, resultCode = ScreenConsentOutcome.RESULT_OK, payload = null)
    private val cancelled = ScreenConsentOutcome(launched = true, resultCode = 0, payload = null)

    @Test
    fun `单请求 —— 第二轮如实拿不到票，不排队也不覆盖`() {
        val first = requests.begin {}
        assertNotNull(first)
        assertTrue(requests.busy)
        assertNull(requests.begin {}, "已有一轮在跑：第二个请求必须被挡住（覆盖会把前一个等待者永远挂住）")
        requests.deliver(granted)
        assertFalse(requests.busy, "结果回来后必须能开新的一轮")
        assertNotNull(requests.begin {})
    }

    @Test
    fun `结果交付给当前等待者，重复回调如实回 false`() {
        val seen = mutableListOf<ScreenConsentRequests.Outcome>()
        requests.begin { seen += it }
        assertTrue(requests.deliver(granted), "有等待者：交付成功")
        assertEquals(1, seen.size)
        assertEquals(granted, assertInstanceOf(ScreenConsentRequests.Outcome.Result::class.java, seen[0]).outcome)
        assertFalse(requests.deliver(granted), "没有在途轮次了：重复回调如实回 false（不抛）")
        assertEquals(1, seen.size, "重复回调不许再交付一次")
    }

    @Test
    fun `用户取消也如实交付 —— 取消不是「没发生」`() {
        val seen = mutableListOf<ScreenConsentRequests.Outcome>()
        requests.begin { seen += it }
        requests.deliver(cancelled)
        val outcome = assertInstanceOf(ScreenConsentRequests.Outcome.Result::class.java, seen.single()).outcome
        assertTrue(outcome.launched)
        assertFalse(outcome.granted, "resultCode 不是 RESULT_OK：如实回取消，由设备层分类")
    }

    @Test
    fun `等待者撤销后 —— 旧结果被丢弃，且在它回来之前新请求进不来`() {
        val ticket = requests.begin { error("已撤销的等待者不该被叫醒") }!!
        requests.revoke(ticket)
        assertTrue(requests.busy, "对话框还在用户眼前：在途必须还占着，否则旧结果会错配给下一次执行")
        assertNull(requests.begin {}, "在途未清时新请求不许进来")
        assertFalse(requests.deliver(granted), "没有等待者：结果丢弃（不进任何待领口）")
        assertFalse(requests.busy, "结果回来后这一轮才算走完")
    }

    @Test
    fun `迟到结果不打到下一轮 —— 新等待者只拿自己的`() {
        val oldTicket = requests.begin { error("旧等待者不该被叫醒") }!!
        requests.revoke(oldTicket)
        // 旧对话框还在系统里；此时来了新的一次征询 —— 被挡（见上一条）。
        assertNull(requests.begin { error("不该被登记") })
        // 旧结果回来：丢弃，不打给任何新等待者。
        requests.deliver(granted)

        val seen = mutableListOf<ScreenConsentRequests.Outcome>()
        requests.begin { seen += it }
        requests.deliver(cancelled)
        val outcome = assertInstanceOf(ScreenConsentRequests.Outcome.Result::class.java, seen.single()).outcome
        assertFalse(outcome.granted, "新等待者拿到的是**新**结果，不是上一次那份同意")
    }

    @Test
    fun `拉不起来 —— 结束本轮，等待者拿到 Unavailable 且能开新的一轮`() {
        val seen = mutableListOf<ScreenConsentRequests.Outcome>()
        val ticket = requests.begin { seen += it }!!
        requests.fail(ticket, "系统里没有这个界面")
        assertFalse(requests.busy, "拉不起来必须结束本轮 —— 否则此后所有征询都被挡掉")
        val reason = assertInstanceOf(ScreenConsentRequests.Outcome.Unavailable::class.java, seen.single()).reason
        assertEquals("系统里没有这个界面", reason)
        assertNotNull(requests.begin {})
    }

    @Test
    fun `销毁 —— 叫醒等待者并清在途（界面没了没人能再收结果）`() {
        val seen = mutableListOf<ScreenConsentRequests.Outcome>()
        requests.begin { seen += it }
        requests.abandon()
        assertInstanceOf(ScreenConsentRequests.Outcome.Unavailable::class.java, seen.single())
        assertFalse(requests.busy)
        // 销毁之后迟到的系统回调：没有在途轮次，如实回 false。
        assertFalse(requests.deliver(granted))
    }

    @Test
    fun `能力中心那条没有等待者 —— 结果照样结束本轮，不留在途`() {
        requests.begin(null)
        assertTrue(requests.busy)
        assertFalse(requests.deliver(granted), "没有等待者：交付回 false（结果由待领口负责）")
        assertFalse(requests.busy)
    }

    @Test
    fun `detached 之后的新征询拿到的是新结果 —— 上一张不回流`() {
        // 能力中心「去授权」：没有等待者，结果落进待领口，这一轮到此结束。
        requests.begin(null)
        requests.deliver(granted)

        // 之后的脚本征询必须是一轮**全新**的：新票、新等待者。
        val seen = mutableListOf<ScreenConsentRequests.Outcome>()
        val ticket = requests.begin { seen += it }
        assertNotNull(ticket, "上一轮已经走完，新征询必须能开始（不能因为陈票还在途而被挡）")

        // 系统回的是**这一次**的结果（拒绝），上一次的同意**不许**被当成这次的结果交付。
        requests.deliver(cancelled)
        val delivered = assertInstanceOf(ScreenConsentRequests.Outcome.Result::class.java, seen.single()).outcome
        assertEquals(0, delivered.resultCode, "拿到的必须是这一次的拒绝，不是上一次的同意")
        assertFalse(delivered.granted)
        assertFalse(requests.busy)
    }

    @Test
    fun `不属于本轮的 fail 打不动在途那一轮`() {
        val seen = mutableListOf<ScreenConsentRequests.Outcome>()
        val ticket = requests.begin { seen += it }!!
        val stale = ScreenConsentRequests.Ticket(999L)
        requests.fail(stale, "别的轮次")
        assertTrue(requests.busy, "别的轮次的失败不许把这一轮收掉")
        assertTrue(seen.isEmpty(), "也不许叫醒这一轮的等待者")
        requests.fail(ticket, "本轮失败")
        assertFalse(requests.busy)
        assertEquals(
            "本轮失败",
            assertInstanceOf(ScreenConsentRequests.Outcome.Unavailable::class.java, seen.single()).reason,
        )
    }
}

/**
 * 能力中心「去授权」的一次性待领凭据（§9.2）判据。
 *
 * 投屏没有持久 grant：一次系统同意只换一条会话。所以"弹了同意却什么都不发生、
 * 下次脚本再弹一次"是必须堵掉的一条 —— 结果落在这里，由下一次 `startCapturer` 领走。
 */
class ScreenConsentInboxTest {

    private val inbox = ScreenConsentInbox()
    private val granted = ScreenConsentOutcome(launched = true, resultCode = ScreenConsentOutcome.RESULT_OK, payload = null)

    @Test
    fun `同意过的那一次不白费 —— 领一次，第二次没了`() {
        inbox.offer(granted)
        assertEquals(granted, inbox.claim())
        assertNull(inbox.claim(), "一次同意只换一条会话（与设备层的 consume 是同一条纪律的两道闸）")
    }

    @Test
    fun `没放过东西时领不到`() {
        assertNull(inbox.claim())
    }

    @Test
    fun `非「真的同意」的结果直接丢弃 —— 收进来也开不了会话`() {
        // 用户取消：系统那次结果的 Intent 是 null，getMediaProjection 必然失败。
        // 收进来只会让下一次请求拿到一个注定失败的凭据（而且用户白等一次）。
        inbox.offer(ScreenConsentOutcome(launched = true, resultCode = 0, payload = null))
        assertNull(inbox.claim())
        inbox.offer(ScreenConsentOutcome.notLaunched("界面已销毁"))
        assertNull(inbox.claim())
    }

    @Test
    fun `clear 之后领不到 —— 销毁不是授权`() {
        inbox.offer(granted)
        inbox.clear()
        assertNull(inbox.claim())
    }

    /**
     * 「恰好一次」必须是**原子**承诺：`claim` 的调用点（`screen.startCapturer`）跑在
     * 桥面读线程上，两个脚本可以**同时**来领。旧实现是「读出来再清」两步
     * （`val claimed = pending; pending = null`），两个线程各读各清、**同一份同意**
     * 被两个执行各领走一份 —— 拿到的还是同一份系统凭据，后到者在
     * `getMediaProjection` 处被系统拒（API 34 起凭据一次性）：用户明明同意过，
     * 却有一个脚本看到 `ERR_CAPTURE_DENIED`、下一次还得再弹一次对话框 ——
     * 正是本类要堵掉的那条。
     *
     * 复现形态：屏障放行对两指令窗口对齐不够（实测 8 线程栅栏约 1.5e-4/轮），
     * 这里用**自旋闸门**（全员热等同一个标志，放行即同拍进 `claim`）——
     * 同形状旧实现实测约 68%/轮现形，64 轮下漏检率 < 1e-32，不再是撞概率。
     */
    @Test
    fun `并发领走只能有一个赢家 —— 恰好一次是原子承诺`() {
        repeat(64) { round ->
            val fresh = ScreenConsentInbox()
            fresh.offer(granted)
            val won = java.util.concurrent.ConcurrentLinkedQueue<Boolean>()
            val ready = java.util.concurrent.CountDownLatch(8)
            val go = java.util.concurrent.atomic.AtomicBoolean(false)
            val threads = (1..8).map {
                kotlin.concurrent.thread {
                    ready.countDown()
                    while (!go.get()) { /* 自旋热等：把 8 条线程压到同一拍进 claim */ }
                    won.add(fresh.claim() != null)
                }
            }
            ready.await()
            go.set(true)
            threads.forEach { it.join() }
            val winners = won.count { it }
            assertEquals(1, winners, "第 $round 轮：一次同意只能换一条会话，$winners 个执行领到了同一份凭据")
        }
    }
}
