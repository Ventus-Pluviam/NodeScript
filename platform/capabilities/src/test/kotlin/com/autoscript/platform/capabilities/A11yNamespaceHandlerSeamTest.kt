package com.autoscript.platform.capabilities

import com.autoscript.domain.automation.GestureInput
import com.autoscript.domain.automation.InputProvider
import com.autoscript.domain.automation.UiActionExecutor
import com.autoscript.domain.automation.UiBounds
import com.autoscript.domain.automation.UiEvent
import com.autoscript.domain.automation.UiEventBatch
import com.autoscript.domain.automation.UiEventStream
import com.autoscript.domain.automation.UiNode
import com.autoscript.domain.automation.UiNodeTreeReader
import com.autoscript.domain.automation.UiSelectorDsl
import com.autoscript.domain.automation.WindowScope
import com.autoscript.domain.bridge.HandleRef
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 接缝证明（§9.1）：[A11yNamespaceHandler] 只依赖 `:domain` 的三条 SPI，
 * **不依赖**本模块的 [InMemoryUiTree]/[InMemoryInputProvider]。
 *
 * 为什么值得单开一个文件：`A11yNamespaceHandlerTest` 用内存树当被测 handler 的依赖，
 * 那测的是语义；这里换成**手写的另一套实现**（刻意不 extend/不包装内存类），
 * 测的是"接上 Android 真实现时不必改语义层一行"。哪天有人把
 * `InMemoryUiTree.nextEvents` 这类实现专有方法塞回 handler，本文件编译即失败 ——
 * 比 archUnit 更早抓到（编译期而非测试期），也不需要为它新开一条黑名单规则。
 *
 * 三个替身各自独立（reader / executor / input 是不同对象）：这也顺带钉住
 * "树只读、动作走 [UiActionExecutor]" 的分工 —— `click` 记在 executor 上而
 * 不在 reader 上，handler 没把两者混成一个。
 */
class A11yNamespaceHandlerSeamTest {

    /** 树读替身：单节点 + 自己的事件 seq（与内存树的 512 条环形缓冲无关）。 */
    private class SpyReader : UiNodeTreeReader {
        val finds = mutableListOf<String>()
        private var seq = 1L
        private val events = mutableListOf<UiEvent>()

        fun emit(type: String) {
            events += UiEvent(seq++, type, HandleRef(1L, 1L), null)
        }

        private fun node(id: Long): UiNode = object : UiNode {
            override val handle: HandleRef = HandleRef(id, 1L)
            override val className: String? = "SpyNode"
            override suspend fun attribute(name: String): String? = "spy-$name"
            override suspend fun children(): List<UiNode> = emptyList()
        }

        override suspend fun root(scope: WindowScope): UiNode = node(1L)

        override suspend fun findBySelector(selector: UiSelectorDsl): List<UiNode> {
            finds += selector.text.orEmpty()
            return if (selector.text == "启动") listOf(node(1L)) else emptyList()
        }

        override suspend fun findByText(text: String, scope: WindowScope, timeoutMillis: Long): UiNode? =
            if (text == "启动") node(1L) else null

        override fun events(): UiEventStream = object : UiEventStream {
            override suspend fun next(sinceSeq: Long, batch: Int): UiEventBatch {
                val picked = events.filter { it.seq > sinceSeq }.take(batch)
                if (picked.isEmpty()) return UiEventBatch(sinceSeq, sinceSeq, emptyList())
                return UiEventBatch(picked.first().seq, picked.last().seq, picked)
            }
        }
    }

    /** 动作替身：只记被点了什么，回 true（与内存树的 clickable 判定不同口径，刻意可辨）。 */
    private class SpyExecutor : UiActionExecutor {
        val clicks = mutableListOf<HandleRef>()
        val texts = mutableListOf<String>()
        val disposed = mutableListOf<HandleRef>()

        private fun node(id: Long): UiNode = object : UiNode {
            override val handle: HandleRef = HandleRef(id, 1L)
            override val className: String? = "SpyNode"
            override suspend fun attribute(name: String): String? = null
            override suspend fun children(): List<UiNode> = emptyList()
        }

        override suspend fun click(handle: HandleRef): Boolean {
            clicks += handle
            return true
        }

        override suspend fun longClick(handle: HandleRef): Boolean = true
        override suspend fun setText(handle: HandleRef, text: String): Boolean {
            texts += text
            return true
        }

        override suspend fun scroll(handle: HandleRef, direction: com.autoscript.domain.automation.ScrollDirection) = true
        override suspend fun copy(handle: HandleRef) = true
        override suspend fun paste(handle: HandleRef) = true
        override suspend fun attribute(handle: HandleRef, name: String): String? = "spy-$name"
        override suspend fun bounds(handle: HandleRef) = UiBounds(1, 2, 3, 4)
        override suspend fun children(handle: HandleRef): List<UiNode> = listOf(node(9L))
        override suspend fun parent(handle: HandleRef): UiNode? = node(8L)
        override suspend fun dispose(handle: HandleRef) {
            disposed += handle
        }
    }

    /** 输入替身：只记派发了什么（不用 [InMemoryInputProvider]，同上的理由）。 */
    private class SpyInput : InputProvider {
        val gestures = mutableListOf<GestureInput>()
        override val canPerformGestures: Boolean = true

        override suspend fun dispatchGesture(gesture: GestureInput): Boolean {
            gestures += gesture
            return true
        }
    }

    private val reader = SpyReader()
    private val executor = SpyExecutor()
    private val input = SpyInput()
    private val handler = A11yNamespaceHandler(reader, executor, input)

    @Test
    fun `findOne 走 reader 缝命中`() = runBlocking {
        val resp = assertInstanceOf(
            A11yNamespaceHandler.Response.Ok::class.java,
            handler.handle(A11yNamespaceHandler.Request(1, "findOne", """{"conditions":{"text":"启动"}}""")),
        )
        val o = A11yBridgeJson.decodeObject(resp.payload!!)
        val ref = (o["ref"] as A11yBridgeJson.Value.Obj).fields
        assertEquals("1", (ref["refId"] as A11yBridgeJson.Value.N).raw)
        assertEquals(listOf("启动"), reader.finds, "选择器条件原样交给 reader，语义层不自己过滤")
        Unit
    }

    @Test
    fun `click 走 executor 缝而非 reader`() = runBlocking {
        val resp = assertInstanceOf(
            A11yNamespaceHandler.Response.Ok::class.java,
            handler.handle(
                A11yNamespaceHandler.Request(
                    2, "click",
                    """{"ref":{"refId":1,"generation":1}}""",
                ),
            ),
        )
        assertEquals("true", resp.payload)
        assertEquals(1, executor.clicks.size, "动作必须记在 executor 上")
        assertEquals(HandleRef(1L, 1L), executor.clicks.single())
        Unit
    }

    @Test
    fun `setText bounds children 都走 executor 缝`() = runBlocking {
        val set = assertInstanceOf(
            A11yNamespaceHandler.Response.Ok::class.java,
            handler.handle(
                A11yNamespaceHandler.Request(3, "setText", """{"ref":{"refId":1,"generation":1},"text":"hi"}"""),
            ),
        )
        assertEquals("true", set.payload)
        assertEquals(listOf("hi"), executor.texts)

        val bounds = assertInstanceOf(
            A11yNamespaceHandler.Response.Ok::class.java,
            handler.handle(A11yNamespaceHandler.Request(4, "bounds", """{"ref":{"refId":1,"generation":1}}""")),
        )
        assertEquals("""{"left":1,"top":2,"right":3,"bottom":4}""", bounds.payload)

        val kids = assertInstanceOf(
            A11yNamespaceHandler.Response.Ok::class.java,
            handler.handle(A11yNamespaceHandler.Request(5, "children", """{"ref":{"refId":1,"generation":1}}""")),
        )
        assertTrue(kids.payload!!.contains(""""refId":9"""), "子节点来自 executor：${kids.payload}")
        Unit
    }

    @Test
    fun `events 经 UiEventStream 缝取到实现自己的 seq`() = runBlocking {
        reader.emit("windowChanged")
        val resp = assertInstanceOf(
            A11yNamespaceHandler.Response.Ok::class.java,
            handler.handle(A11yNamespaceHandler.Request(6, "events", """{"sinceSeq":0,"batch":8}""")),
        )
        val o = A11yBridgeJson.decodeObject(resp.payload!!)
        val events = (o["events"] as A11yBridgeJson.Value.Arr).items
        assertEquals(1, events.size)
        val ev = (events[0] as A11yBridgeJson.Value.Obj).fields
        assertEquals("windowChanged", (ev["type"] as A11yBridgeJson.Value.S).v)
        assertEquals("1", (ev["seq"] as A11yBridgeJson.Value.N).raw)
        Unit
    }

    @Test
    fun `gesture 与 canPerformGestures 走 input 缝`() = runBlocking {
        val resp = assertInstanceOf(
            A11yNamespaceHandler.Response.Ok::class.java,
            handler.handle(
                A11yNamespaceHandler.Request(
                    7, "gesture",
                    """{"strokes":[{"points":[{"x":5,"y":6}],"durationMillis":50}]}""",
                ),
            ),
        )
        assertEquals("true", resp.payload)
        assertEquals(1, input.gestures.size, "手势必须记在 input 上")
        assertEquals(5, input.gestures.single().strokes.single().points.single().x)

        val gate = assertInstanceOf(
            A11yNamespaceHandler.Response.Ok::class.java,
            handler.handle(A11yNamespaceHandler.Request(8, "canPerformGestures", null)),
        )
        assertEquals("true", gate.payload)
        Unit
    }

    @Test
    fun `dispose 走 executor 缝`() = runBlocking {
        val resp = assertInstanceOf(
            A11yNamespaceHandler.Response.Ok::class.java,
            handler.handle(A11yNamespaceHandler.Request(9, "dispose", """{"ref":{"refId":1,"generation":1}}""")),
        )
        assertEquals("true", resp.payload)
        assertEquals(listOf(HandleRef(1L, 1L)), executor.disposed)
        Unit
    }
}
