package com.autoscript.platform.system

import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.system.NotificationSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `notification` Android 实现的契约测试（docs §12.2；假 [AndroidNotificationPoster.Ops]）。
 *
 * 守三件事（真实现是系统服务调用，只能编译不能跑 —— 语义全在这边）：
 * 1. **发前门**：canPost false → post 抛 `ERR_PERMISSION_DENIED`（**不是回 false**），
 *    且一次 ops.post 都不发 —— Android 被拒时不抛异常直接丢弃，门禁必须在它前面；
 * 2. **三字段原样到 ops**（id 同号覆盖是契约语义，不能在实现里改号）；
 * 3. **cancel 无回执**：契约回 Unit —— 系统没有"撤到了没有"的读口，不编 Boolean。
 */
class AndroidNotificationPosterTest {

    private class FakeOps(
        var postable: Boolean = true,
    ) : AndroidNotificationPoster.Ops {
        val posted = mutableListOf<NotificationSpec>()
        val cancelled = mutableListOf<Int>()
        override fun canPost() = postable
        override fun post(spec: NotificationSpec) {
            posted += spec
        }
        override fun cancel(id: Int) {
            cancelled += id
        }
    }

    private val ops = FakeOps()
    private val poster = AndroidNotificationPoster(ops)

    @Test
    fun `发前门——未授权抛 ERR_PERMISSION_DENIED 且不碰 ops`() {
        ops.postable = false
        assertEquals(false, poster.canPost())
        val e = assertThrows(AutojsException::class.java) {
            poster.post(NotificationSpec(id = 7, text = "跑完了"))
        }
        assertEquals(ErrorCode.ERR_PERMISSION_DENIED, e.error)
        assertTrue(e.message!!.contains("通知未授权"), "detail 带现场: ${e.message}")
        assertEquals(true, ops.posted.isEmpty(), "被拒的调用不投递")
        ops.postable = true
    }

    @Test
    fun `三字段原样到 ops——id 不改号 title 缺省即 null`() {
        poster.post(NotificationSpec(id = 42, text = "跑完了", title = "任务"))
        poster.post(NotificationSpec(id = 43, text = "无标题"))
        assertEquals(
            listOf(
                NotificationSpec(id = 42, text = "跑完了", title = "任务"),
                NotificationSpec(id = 43, text = "无标题", title = null),
            ),
            ops.posted,
        )
    }

    @Test
    fun `空白正文在触 ops 之前被拒`() {
        assertThrows(IllegalArgumentException::class.java) {
            poster.post(NotificationSpec(id = 1, text = "   "))
        }
        assertEquals(true, ops.posted.isEmpty())
    }

    @Test
    fun `cancel 原样透传且无回执——契约回 Unit`() {
        poster.cancel(42)
        assertEquals(listOf(42), ops.cancelled)
        poster.cancel(999)                     // 没发过的 id：照样只是发一次撤销，不抛
        assertEquals(listOf(42, 999), ops.cancelled)
    }

    @Test
    fun `canPost 透传——能力中心与脚本问的是同一个事实`() {
        ops.postable = false
        assertEquals(false, poster.canPost())
        ops.postable = true
        assertEquals(true, poster.canPost())
    }
}
