package com.autoscript.platform.capabilities.a11y

import com.autoscript.domain.automation.GestureInput
import com.autoscript.domain.automation.GesturePoint
import com.autoscript.domain.automation.GestureStroke
import com.autoscript.domain.automation.InputChannel
import com.autoscript.domain.automation.InputChannelSession
import com.autoscript.domain.automation.InputProvider
import com.autoscript.domain.automation.ScrollDirection
import com.autoscript.domain.automation.UiBounds
import com.autoscript.domain.automation.UiEventStream
import com.autoscript.domain.automation.UiNode
import com.autoscript.domain.automation.UiNodeTreeReader
import com.autoscript.domain.automation.UiActionExecutor
import com.autoscript.domain.automation.UiSelectorDsl
import com.autoscript.domain.automation.WindowScope
import com.autoscript.domain.bridge.BridgeResponse
import com.autoscript.domain.bridge.HandleRef
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 输入通道选路（§9.3；判据见 `design-decisions.md`）。
 *
 * 四组：
 * 1. **必须显式**：既没给 `channel`
 *    也没设会话值 → `ERR_INVALID_PARAM`，**不落 auto**；
 * 2. **会话通道**（`setInputChannel`）经协程上下文传播，且**不跨连接串扰**；
 * 3. **指定不可用的通道 = `ERR_PERMISSION_DENIED`**，绝不回落到别的通道
 *    —— 这是本次改动最要紧的一条；
 * 4. 通道 → 动作形态的映射（节点语义 vs 坐标注入）。
 */
class A11yInputChannelTest {

    /** 记账输入替身：谁被调用、按什么坐标，一目了然。 */
    private class Recorder(private val usable: Boolean = true) : InputProvider {
        val taps = mutableListOf<Triple<Int, Int, Long>>()
        val gestures = mutableListOf<GestureInput>()
        override val canPerformGestures: Boolean get() = usable

        override suspend fun dispatchGesture(gesture: GestureInput): Boolean {
            gestures += gesture
            return usable
        }

        override suspend fun tap(x: Int, y: Int, durationMillis: Long): Boolean {
            taps += Triple(x, y, durationMillis)
            return usable
        }
    }

    /** 最小树/动作替身：一个 100×40 的按钮，bounds = (10,20)-(110,60)，中心 (60,40)。 */
    private open class FakeTree : UiNodeTreeReader, UiActionExecutor {
        val clicks = mutableListOf<HandleRef>()
        val longClicks = mutableListOf<HandleRef>()
        val scrolls = mutableListOf<Pair<HandleRef, ScrollDirection>>()

        override suspend fun root(scope: WindowScope): UiNode = error("未用")
        override suspend fun findBySelector(selector: UiSelectorDsl): List<UiNode> = emptyList()
        override suspend fun findByText(text: String, scope: WindowScope, timeoutMillis: Long): UiNode? = null
        override fun events(): UiEventStream = error("未用")

        override suspend fun click(handle: HandleRef): Boolean { clicks += handle; return true }
        override suspend fun longClick(handle: HandleRef): Boolean { longClicks += handle; return true }
        override suspend fun setText(handle: HandleRef, text: String): Boolean = true
        override suspend fun scroll(handle: HandleRef, direction: ScrollDirection): Boolean {
            scrolls += handle to direction
            return true
        }
        override suspend fun copy(handle: HandleRef): Boolean = true
        override suspend fun paste(handle: HandleRef): Boolean = true
        override suspend fun attribute(handle: HandleRef, name: String): String? = null
        override suspend fun bounds(handle: HandleRef): UiBounds? = UiBounds(10, 20, 110, 60)
        override suspend fun children(handle: HandleRef): List<UiNode> = emptyList()
        override suspend fun parent(handle: HandleRef): UiNode? = null
        override suspend fun dispose(handle: HandleRef) = Unit
    }

    private val ref = """{"ref":{"refId":1,"generation":1}}"""

    private fun handler(
        auto: InputProvider,
        channels: Map<InputChannel, InputProvider>,
        tree: FakeTree = FakeTree(),
    ): A11yNamespaceHandler = A11yNamespaceHandler(tree, tree, auto, null, channels)

    private suspend fun payloadOf(resp: BridgeResponse): String? =
        assertInstanceOf(BridgeResponse.Ok::class.java, resp).payload

    // ── 1. 必须显式：没有缺省通道 ─────────────────────────────────────

    @Test
    fun `既没给 channel 也没设会话——ERR_INVALID_PARAM，不落 auto`() = runBlocking {
        val auto = Recorder()
        val root = Recorder()
        val tree = FakeTree()
        val h = handler(auto, mapOf(InputChannel.AUTO to auto, InputChannel.ROOT to root), tree)

        val err = assertInstanceOf(
            BridgeResponse.Err::class.java,
            h.handle(a11yReq(1, "click", ref)),
        )
        assertEquals(ErrorCode.ERR_INVALID_PARAM.code, err.errorCode)
        assertTrue(err.detail!!.contains("未指定输入通道"), "要说清是缺通道：${err.detail}")
        // 一条通道都没动 —— 这正是「无默认」要钉的事。
        assertTrue(tree.clicks.isEmpty(), "不许静默走 auto")
        assertTrue(auto.taps.isEmpty() && root.taps.isEmpty())
        Unit
    }

    @Test
    fun `显式 channel=auto——click 是节点语义动作，不是坐标注入`() = runBlocking {
        val auto = Recorder()
        val root = Recorder()
        val tree = FakeTree()
        val h = handler(auto, mapOf(InputChannel.AUTO to auto, InputChannel.ROOT to root), tree)

        assertEquals("true", payloadOf(h.handle(a11yReq(1, "click", """{"ref":{"refId":1,"generation":1},"channel":"auto"}"""))))
        assertEquals(1, tree.clicks.size, "auto 通道走 ACTION_CLICK（节点语义）")
        assertTrue(auto.taps.isEmpty(), "auto 通道不该走坐标注入")
        assertTrue(root.taps.isEmpty(), "更不该走别的通道")
        Unit
    }

    @Test
    fun `会话设 auto 也算显式——与通道无关的方法不受影响`() = runBlocking {
        val auto = Recorder()
        val tree = FakeTree()
        val h = handler(auto, mapOf(InputChannel.AUTO to auto), tree)

        withContext(InputChannelSession()) {
            h.handle(a11yReq(1, "setInputChannel", """{"channel":"auto"}"""))
            assertEquals("true", payloadOf(h.handle(a11yReq(2, "click", ref))), "会话值算「说过」")
        }
        // 与通道无关的方法（copy/setText/bounds/...）**不需要** channel：通道只挑「怎么注入」。
        assertEquals("true", payloadOf(h.handle(a11yReq(3, "copy", ref))))
        Unit
    }

    // ── 2. 单次覆盖 → 坐标注入 ────────────────────────────────────────

    @Test
    fun `单次 channel=root——click 解 bounds 点中心`() = runBlocking {
        val auto = Recorder()
        val root = Recorder()
        val tree = FakeTree()
        val h = A11yNamespaceHandler(tree, tree, auto, null, mapOf(InputChannel.AUTO to auto, InputChannel.ROOT to root))

        assertEquals("true", payloadOf(h.handle(a11yReq(1, "click", """{"ref":{"refId":1,"generation":1},"channel":"root"}"""))))
        assertEquals(listOf(Triple(60, 40, A11yNamespaceHandler.LONG_PRESS_NONE_MILLIS)), root.taps, "点 bounds 中心")
        assertTrue(tree.clicks.isEmpty(), "root 通道拿不到节点语义，不许假装拿到")
        assertTrue(auto.taps.isEmpty() && auto.gestures.isEmpty(), "不许回落 auto")
        Unit
    }

    @Test
    fun `单次 channel=adb——longClick 是同点长滑（带按住时长）`() = runBlocking {
        val auto = Recorder()
        val adb = Recorder()
        val tree = FakeTree()
        val h = A11yNamespaceHandler(tree, tree, auto, null, mapOf(InputChannel.AUTO to auto, InputChannel.ADB to adb))

        h.handle(a11yReq(1, "longClick", """{"ref":{"refId":1,"generation":1},"channel":"adb"}"""))
        assertEquals(listOf(Triple(60, 40, A11yNamespaceHandler.LONG_PRESS_MILLIS)), adb.taps)
        assertTrue(tree.longClicks.isEmpty())
        Unit
    }

    @Test
    fun `单次 channel=root——scroll 按方向在节点内划一条`() = runBlocking {
        val auto = Recorder()
        val root = Recorder()
        val tree = FakeTree()
        val h = A11yNamespaceHandler(tree, tree, auto, null, mapOf(InputChannel.AUTO to auto, InputChannel.ROOT to root))

        h.handle(a11yReq(1, "scroll", """{"ref":{"refId":1,"generation":1},"direction":"forward","channel":"root"}"""))
        val g = root.gestures.single().strokes.single()
        // forward = 看后面的内容 = 手指往上划（起于下、止于上）
        assertEquals(GesturePoint(60, 50), g.points.first(), "起于下四分之一")
        assertEquals(GesturePoint(60, 30), g.points.last(), "止于上四分之一")
        assertTrue(tree.scrolls.isEmpty(), "root 通道不走 ACTION_SCROLL")
        Unit
    }

    // ── 3. 会话通道 + 隔离 ───────────────────────────────────────────

    @Test
    fun `会话通道 setInputChannel 生效，单次 channel 可覆盖`() = runBlocking {
        val auto = Recorder()
        val root = Recorder()
        val tree = FakeTree()
        val h = A11yNamespaceHandler(tree, tree, auto, null, mapOf(InputChannel.AUTO to auto, InputChannel.ROOT to root))

        withContext(InputChannelSession()) {
            assertEquals("true", payloadOf(h.handle(a11yReq(1, "setInputChannel", """{"channel":"root"}"""))))
            h.handle(a11yReq(2, "click", ref))
            assertEquals(1, root.taps.size, "会话通道生效")

            h.handle(a11yReq(3, "click", """{"ref":{"refId":1,"generation":1},"channel":"auto"}"""))
            assertEquals(1, tree.clicks.size, "单次覆盖回 auto")
            assertEquals(1, root.taps.size, "覆盖不改变会话值")
            h.handle(a11yReq(4, "click", ref))
            assertEquals(2, root.taps.size, "会话值仍是 root")
        }
        Unit
    }

    @Test
    fun `会话通道不跨连接——另一条连接既不继承也不回落`() = runBlocking {
        val auto = Recorder()
        val root = Recorder()
        val tree = FakeTree()
        val h = A11yNamespaceHandler(tree, tree, auto, null, mapOf(InputChannel.AUTO to auto, InputChannel.ROOT to root))

        // 连接 A 设 root
        withContext(InputChannelSession()) {
            h.handle(a11yReq(1, "setInputChannel", """{"channel":"root"}"""))
            h.handle(a11yReq(2, "click", ref))
        }
        assertEquals(1, root.taps.size)
        // 连接 B：新的会话对象（NewlineFrameServer 每连接建一个），**没有**任何显式选择
        withContext(InputChannelSession()) {
            val err = assertInstanceOf(
                BridgeResponse.Err::class.java,
                h.handle(a11yReq(3, "click", ref)),
            )
            assertEquals(ErrorCode.ERR_INVALID_PARAM.code, err.errorCode, "不继承、也不静默落 auto")
        }
        assertTrue(tree.clicks.isEmpty(), "另一个连接不该继承上一个连接的通道")
        assertEquals(1, root.taps.size)
        Unit
    }

    @Test
    fun `无会话对象时 setInputChannel 如实报错——不静默吞掉`() = runBlocking {
        val h = handler(Recorder(), mapOf(InputChannel.AUTO to Recorder()))
        val resp = h.handle(a11yReq(1, "setInputChannel", """{"channel":"root"}"""))
        val err = assertInstanceOf(BridgeResponse.Err::class.java, resp)
        assertEquals(ErrorCode.ERR_SERVICE_DISABLED.code, err.errorCode)
        Unit
    }

    // ── 4. 不可用 = 如实拒绝，绝不回落 ────────────────────────────────

    @Test
    fun `指定未接线的通道——ERR_PERMISSION_DENIED，且一个字节都没注入`() = runBlocking {
        val auto = Recorder()
        val tree = FakeTree()
        // channels 表里只有 auto：root 未接线
        val h = A11yNamespaceHandler(tree, tree, auto, null, mapOf(InputChannel.AUTO to auto))

        val resp = h.handle(a11yReq(1, "gesture", """{"strokes":[{"points":[{"x":1,"y":2}]}],"channel":"root"}"""))
        val err = assertInstanceOf(BridgeResponse.Err::class.java, resp)
        assertEquals(ErrorCode.ERR_PERMISSION_DENIED.code, err.errorCode, "不可用是权限/能力问题，不是参数问题")
        assertTrue(err.detail!!.contains("root"), "要说清是哪条通道：${err.detail}")
        assertTrue(auto.gestures.isEmpty(), "**绝不回落到 auto** —— 这正是本条要钉的事")
        Unit
    }

    @Test
    fun `未知通道字面量——ERR_INVALID_PARAM（拼错不静默变 auto）`() = runBlocking {
        val h = handler(Recorder(), mapOf(InputChannel.AUTO to Recorder()))
        // 注意 "Root" **不是**未知字面量：ofWire 按小写比对（wire 名大小写不敏感是既有口径），
        // 它会解析成 ROOT 然后如实报「那条通道不可用」。这里用的是真正的拼错。
        val resp = h.handle(a11yReq(1, "gesture", """{"strokes":[{"points":[{"x":1,"y":2}]}],"channel":"uiautomator"}"""))
        val err = assertInstanceOf(BridgeResponse.Err::class.java, resp)
        assertEquals(ErrorCode.ERR_INVALID_PARAM.code, err.errorCode)
        Unit
    }

    @Test
    fun `canPerformGestures 问的是会话通道，不是恒问 auto`() = runBlocking {
        val auto = Recorder(usable = false)
        val root = Recorder(usable = true)
        val h = handler(auto, mapOf(InputChannel.AUTO to auto, InputChannel.ROOT to root))

        val missing = assertInstanceOf(
            BridgeResponse.Err::class.java,
            h.handle(a11yReq(1, "canPerformGestures", null)),
        )
        assertEquals(ErrorCode.ERR_INVALID_PARAM.code, missing.errorCode, "问能力也得先说是哪条通道")
        assertEquals(
            "false",
            payloadOf(h.handle(a11yReq(2, "canPerformGestures", """{"channel":"auto"}"""))),
            "问 auto 就问无障碍的能力位",
        )
        assertEquals(
            "true",
            payloadOf(h.handle(a11yReq(3, "canPerformGestures", """{"channel":"root"}"""))),
            "指定 root 就问 root 那条",
        )
        Unit
    }

    @Test
    fun `通道 provider 抛错——原码透传，不折成 false`() = runBlocking {
        val broken = object : InputProvider {
            override val canPerformGestures: Boolean = true
            override suspend fun dispatchGesture(gesture: GestureInput): Boolean =
                throw AutojsException(ErrorCode.ERR_SERVICE_DISABLED, "su 不见了", null)
        }
        val tree = FakeTree()
        val h = A11yNamespaceHandler(tree, tree, Recorder(), null, mapOf(InputChannel.AUTO to broken))
        val err = assertInstanceOf(
            BridgeResponse.Err::class.java,
            h.handle(a11yReq(1, "gesture", """{"strokes":[{"points":[{"x":1,"y":2}]}],"channel":"auto"}""")),
        )
        assertEquals(ErrorCode.ERR_SERVICE_DISABLED.code, err.errorCode, "「通道没了」与「这次被拒」是两回事")
        Unit
    }

    @Test
    fun `节点无 bounds——按坐标注入的通道如实报 ERR_STALE_HANDLE`() = runBlocking {
        val noBounds = object : FakeTree() {
            override suspend fun bounds(handle: HandleRef): UiBounds? = null
        }
        val root = Recorder()
        val h = A11yNamespaceHandler(noBounds, noBounds, Recorder(), null, mapOf(InputChannel.ROOT to root))
        val err = assertInstanceOf(
            BridgeResponse.Err::class.java,
            h.handle(a11yReq(1, "click", """{"ref":{"refId":1,"generation":1},"channel":"root"}""")),
        )
        assertEquals(ErrorCode.ERR_STALE_HANDLE.code, err.errorCode)
        assertTrue(root.taps.isEmpty())
        Unit
    }
}
