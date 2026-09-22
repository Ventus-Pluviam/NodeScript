package com.autoscript.platform.capabilities

import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.system.DialogChoice
import com.autoscript.domain.system.DialogChooseRequest
import com.autoscript.domain.system.DialogMode
import com.autoscript.domain.system.DialogOutcome
import com.autoscript.domain.system.DialogPromptRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [AndroidDialogHost] 编排语义（假 [DialogOps] 注入；设备面在 SystemDialogOps，本机不跑）。
 * 覆盖：三态选路（含 OVERLAY 强制不降级）、通知先登记后 post、回投完成 await、
 * TTL 取消双清（注销 + 撤通知）、晚到答案丢弃、receiver 回投汇绑定。
 */
class AndroidDialogHostTest {

    private class FakeOps : DialogOps {
        var overlayAvailableOnOps = true // 仅记录：选路决策在 host 的 overlayAvailable 缝
        val overlayPrompts = mutableListOf<String>()
        val overlayChooses = mutableListOf<String>()
        val postedPrompts = mutableListOf<Long>()
        val postedChooses = mutableListOf<Long>()
        val cancelled = mutableListOf<Long>()
        var overlayPromptResult = DialogOutcome("from-overlay", true)
        var overlayChooseResult = DialogChoice(1)
        var postPromptThrows: AutojsException? = null

        override suspend fun overlayPrompt(title: String, placeholder: String?): DialogOutcome {
            overlayPrompts += title
            return overlayPromptResult
        }

        override suspend fun overlayChoose(title: String, options: List<String>): DialogChoice {
            overlayChooses += title
            return overlayChooseResult
        }

        override fun postPromptNotification(key: Long, title: String, placeholder: String?) {
            postPromptThrows?.let { throw it }
            postedPrompts += key
        }

        override fun postChooseNotification(key: Long, title: String, options: List<String>) {
            postedChooses += key
        }

        override fun cancelNotification(key: Long) {
            cancelled += key
        }
    }

    private fun host(ops: FakeOps, overlay: Boolean) = AndroidDialogHost(ops) { overlay }

    private fun prompt(mode: DialogMode, title: String = "标题") =
        DialogPromptRequest(title = title, placeholder = null, mode = mode)

    private fun choose(mode: DialogMode) =
        DialogChooseRequest(title = "选", options = listOf("a", "b"), mode = mode)

    @Test
    fun `AUTO 按 overlay 可见性选路`() {
        runBlocking {
            val up = FakeOps()
            host(up, overlay = true).let { h ->
                assertEquals(DialogOutcome("from-overlay", true), h.prompt(prompt(DialogMode.AUTO)))
            }
            assertEquals(listOf("标题"), up.overlayPrompts, "overlay 可见 → 弹窗")
            assertTrue(up.postedPrompts.isEmpty(), "不发通知")

            val down = FakeOps()
            val key = CompletableDeferred<Long>()
            // 通知路径：post 后挂起 → 回投完成
            val job = launch {
                host(down, overlay = false).prompt(prompt(DialogMode.AUTO))
            }
            while (down.postedPrompts.isEmpty()) yield()
            val k = down.postedPrompts.first()
            key.complete(k)
            assertTrue(DialogResultRouter.deliverPrompt(k, "张三", confirmed = true))
            job.join()
            assertTrue(down.cancelled.contains(k), "答完 finally 撤通知（幂等清幽灵）")
        }
    }

    @Test
    fun `OVERLAY 强制 不可用即 PERMISSION_DENIED 绝不降级`() {
        val ops = FakeOps()
        val h = host(ops, overlay = false)
        val e = assertThrows(AutojsException::class.java) {
            runBlocking { h.prompt(prompt(DialogMode.OVERLAY)) }
        }
        assertEquals(ErrorCode.ERR_PERMISSION_DENIED.code, e.error.code)
        assertTrue(ops.postedPrompts.isEmpty(), "强制弹窗失败不得偷渡通知")
        assertTrue(ops.overlayPrompts.isEmpty())
    }

    @Test
    fun `NOTIFICATION 强制 overlay 可用也走通知`() {
        runBlocking {
            val ops = FakeOps()
            val h = host(ops, overlay = true)
            val job = launch { h.prompt(prompt(DialogMode.NOTIFICATION)) }
            while (ops.postedPrompts.isEmpty()) yield()
            assertTrue(DialogResultRouter.deliverPrompt(ops.postedPrompts.first(), null, confirmed = false))
            job.join()
            assertTrue(ops.overlayPrompts.isEmpty(), "点名通知就不用弹窗")
        }
    }

    @Test
    fun `choose 通知回投下标与取消`() {
        runBlocking {
            val ops = FakeOps()
            val h = host(ops, overlay = false)
            val picked = launch { h.choose(choose(DialogMode.NOTIFICATION)) }
            while (ops.postedChooses.isEmpty()) yield()
            val k = ops.postedChooses.first()
            assertTrue(DialogResultRouter.deliverChoose(k, 1))
            picked.join()
            assertTrue(ops.cancelled.contains(k))

            val cancelled = launch { h.choose(choose(DialogMode.NOTIFICATION)) }
            while (ops.postedChooses.size < 2) yield()
            val k2 = ops.postedChooses[1]
            assertTrue(DialogResultRouter.deliverChoose(k2, DialogChoice.CANCELLED_INDEX))
            cancelled.join()
        }
    }

    @Test
    fun `晚到与未知 key 回投丢弃 不抛`() {
        assertFalse(DialogResultRouter.deliverPrompt(999_999L, "x", true), "未登记 key：丢弃")
        assertFalse(DialogResultRouter.deliverChoose(999_999L, 0), "未登记 key：丢弃")
    }

    @Test
    fun `TTL 取消双清 注销加撤通知 晚到答案进不来`() {
        runBlocking {
            val ops = FakeOps()
            val h = host(ops, overlay = false)
            val job = launch { h.prompt(prompt(DialogMode.NOTIFICATION)) }
            while (ops.postedPrompts.isEmpty()) yield()
            val k = ops.postedPrompts.first()
            job.cancelAndJoin() // 模拟桥 TTL 斩杀
            assertTrue(ops.cancelled.contains(k), "取消必须撤掉幽灵通知")
            assertFalse(DialogResultRouter.deliverPrompt(k, "late", true), "注销后晚到答案：丢弃")
        }
    }

    @Test
    fun `通知 post 抛权限错原码上抛并注销登记`() {
        val ops = FakeOps()
        ops.postPromptThrows = AutojsException(ErrorCode.ERR_PERMISSION_DENIED, "通知未授权")
        val h = host(ops, overlay = false)
        val e = assertThrows(AutojsException::class.java) {
            runBlocking { h.prompt(prompt(DialogMode.AUTO)) }
        }
        assertEquals(ErrorCode.ERR_PERMISSION_DENIED.code, e.error.code)
        assertFalse(
            DialogResultRouter.deliverPrompt(1L, "x", true),
            "post 失败后登记必须已清（finally 走过）",
        )
    }
}
