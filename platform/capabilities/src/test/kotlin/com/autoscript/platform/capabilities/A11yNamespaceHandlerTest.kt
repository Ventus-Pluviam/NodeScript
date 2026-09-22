package com.autoscript.platform.capabilities

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class A11yNamespaceHandlerTest {

    private lateinit var tree: InMemoryUiTree
    private lateinit var handler: A11yNamespaceHandler

    @BeforeEach
    fun setup() {
        tree = InMemoryUiTree()
        handler = A11yNamespaceHandler(tree, tree)
    }

    private suspend fun seedButton(): String {
        val ref = tree.add(
            InMemoryUiTree.Attrs(
                text = "启动", desc = "启动按钮", className = "Button",
                packageName = "com.example", viewId = "btn_go", clickable = true,
                bounds = com.autoscript.domain.automation.UiBounds(10, 20, 110, 60),
            ),
        )
        return """{"refId":${ref.refId},"generation":${ref.generation}}"""
    }

    private fun cond(vararg pairs: String): String =
        pairs.joinToString(",", "{", "}") { it }

    @Test
    fun `findOne 命中回 ref 句柄`() = runBlocking {
        seedButton()
        val resp = assertInstanceOf(
            A11yNamespaceHandler.Response.Ok::class.java,
            handler.handle(
                A11yNamespaceHandler.Request(
                    1, "findOne",
                    """{"conditions":${cond(""""text":"启动"""")},"timeout":5000,"interval":300}""",
                ),
            ),
        )
        val o = A11yBridgeJson.decodeObject(resp.payload!!)
        val ref = (o["ref"] as A11yBridgeJson.Value.Obj).fields
        assertEquals("1", (ref["refId"] as A11yBridgeJson.Value.N).raw)
        assertEquals("1", (ref["generation"] as A11yBridgeJson.Value.N).raw)
    }

    @Test
    fun `findOne 无匹配回 ERR_NOT_FOUND`() = runBlocking {
        seedButton()
        val resp = assertInstanceOf(
            A11yNamespaceHandler.Response.Err::class.java,
            handler.handle(
                A11yNamespaceHandler.Request(2, "findOne", """{"conditions":${cond(""""text":"不存在"""")}}"""),
            ),
        )
        assertEquals("ERR_NOT_FOUND", resp.code)
    }

    @Test
    fun `findOne 未知条件键回 ERR_INVALID_PARAM`() = runBlocking {
        seedButton()
        val resp = assertInstanceOf(
            A11yNamespaceHandler.Response.Err::class.java,
            handler.handle(
                A11yNamespaceHandler.Request(3, "findOne", """{"conditions":{"txt":"启动"}}"""),
            ),
        )
        assertEquals("ERR_INVALID_PARAM", resp.code, "拼写错误不得静默变全量匹配")
    }

    @Test
    fun `findAll 回数组并按 max 截断`() = runBlocking {
        seedButton()
        tree.add(InMemoryUiTree.Attrs(text = "启动", className = "TextView"))
        val all = assertInstanceOf(
            A11yNamespaceHandler.Response.Ok::class.java,
            handler.handle(
                A11yNamespaceHandler.Request(4, "findAll", """{"conditions":${cond(""""text":"启动"""")}}"""),
            ),
        )
        assertEquals(2, (A11yBridgeJson.decode(all.payload!!) as A11yBridgeJson.Value.Arr).items.size)
        val capped = assertInstanceOf(
            A11yNamespaceHandler.Response.Ok::class.java,
            handler.handle(
                A11yNamespaceHandler.Request(5, "findAll", """{"conditions":${cond(""""text":"启动"""")},"max":1}"""),
            ),
        )
        assertEquals(1, (A11yBridgeJson.decode(capped.payload!!) as A11yBridgeJson.Value.Arr).items.size)
        val neg = assertInstanceOf(
            A11yNamespaceHandler.Response.Err::class.java,
            handler.handle(
                A11yNamespaceHandler.Request(6, "findAll", """{"conditions":{},"max":-1}"""),
            ),
        )
        assertEquals("ERR_INVALID_PARAM", neg.code)
    }

    @Test
    fun `click-setText-bounds-text-desc 全链路`() = runBlocking {
        val refJson = seedButton()
        val click = assertInstanceOf(
            A11yNamespaceHandler.Response.Ok::class.java,
            handler.handle(A11yNamespaceHandler.Request(10, "click", """{"ref":$refJson}""")),
        )
        assertEquals("true", click.payload)
        val text = assertInstanceOf(
            A11yNamespaceHandler.Response.Ok::class.java,
            handler.handle(A11yNamespaceHandler.Request(11, "text", """{"ref":$refJson}""")),
        )
        assertEquals("\"启动\"", text.payload, "attr 回包是 JSON 字符串原文")
        val bounds = assertInstanceOf(
            A11yNamespaceHandler.Response.Ok::class.java,
            handler.handle(A11yNamespaceHandler.Request(12, "bounds", """{"ref":$refJson}""")),
        )
        assertEquals("""{"left":10,"top":20,"right":110,"bottom":60}""", bounds.payload)

        val inputRef = tree.add(InMemoryUiTree.Attrs(editable = true))
        val inputJson = """{"refId":${inputRef.refId},"generation":${inputRef.generation}}"""
        val set = assertInstanceOf(
            A11yNamespaceHandler.Response.Ok::class.java,
            handler.handle(A11yNamespaceHandler.Request(13, "setText", """{"ref":$inputJson,"text":"你好"}""")),
        )
        assertEquals("true", set.payload)
        // 不可编辑 → false（不抛错）
        val setNo = assertInstanceOf(
            A11yNamespaceHandler.Response.Ok::class.java,
            handler.handle(A11yNamespaceHandler.Request(14, "setText", """{"ref":$refJson,"text":"x"}""")),
        )
        assertEquals("false", setNo.payload)

        Unit  // 显式收尾：void 返回值才被 JUnit5 视为测试
    }

    @Test
    fun `跨代句柄回 ERR_STALE_HANDLE`() = runBlocking {
        val refJson = seedButton()
        val stale = refJson.replace(""""generation":1""", """"generation":2""")
        val resp = assertInstanceOf(
            A11yNamespaceHandler.Response.Err::class.java,
            handler.handle(A11yNamespaceHandler.Request(20, "click", """{"ref":$stale}""")),
        )
        assertEquals("ERR_STALE_HANDLE", resp.code)
    }

    @Test
    fun `dispose 后 click 失配`() = runBlocking {
        val refJson = seedButton()
        val disposed = assertInstanceOf(
            A11yNamespaceHandler.Response.Ok::class.java,
            handler.handle(A11yNamespaceHandler.Request(21, "dispose", """{"ref":$refJson}""")),
        )
        assertEquals("true", disposed.payload)
        val resp = assertInstanceOf(
            A11yNamespaceHandler.Response.Err::class.java,
            handler.handle(A11yNamespaceHandler.Request(22, "click", """{"ref":$refJson}""")),
        )
        assertEquals("ERR_STALE_HANDLE", resp.code)
    }

    @Test
    fun `children-parent 导航`() = runBlocking {
        val parentRef = tree.add(InMemoryUiTree.Attrs(text = "容器"))
        val childRef = tree.add(InMemoryUiTree.Attrs(text = "子"), parentId = parentRef.refId)
        val parentJson = """{"refId":${parentRef.refId},"generation":${parentRef.generation}}"""
        val childJson = """{"refId":${childRef.refId},"generation":${childRef.generation}}"""
        val kids = assertInstanceOf(
            A11yNamespaceHandler.Response.Ok::class.java,
            handler.handle(A11yNamespaceHandler.Request(30, "children", """{"ref":$parentJson}""")),
        )
        assertEquals(1, (A11yBridgeJson.decode(kids.payload!!) as A11yBridgeJson.Value.Arr).items.size)
        val back = assertInstanceOf(
            A11yNamespaceHandler.Response.Ok::class.java,
            handler.handle(A11yNamespaceHandler.Request(31, "parent", """{"ref":$childJson}""")),
        )
        assertTrue(back.payload!!.contains(""""refId":${parentRef.refId}"""))
        val top = assertInstanceOf(
            A11yNamespaceHandler.Response.Ok::class.java,
            handler.handle(A11yNamespaceHandler.Request(32, "parent", """{"ref":$parentJson}""")),
        )
        assertEquals(null, top.payload, "顶层 parent 回 null")
    }

    @Test
    fun `scroll 可滚动容器回 true 并记 nodeScrolled 事件`() = runBlocking {
        val ref = tree.add(InMemoryUiTree.Attrs(className = "ScrollView", scrollable = true))
        val refJson = """{"refId":${ref.refId},"generation":${ref.generation}}"""
        val resp = assertInstanceOf(
            A11yNamespaceHandler.Response.Ok::class.java,
            handler.handle(A11yNamespaceHandler.Request(41, "scroll", """{"ref":$refJson,"direction":"down"}""")),
        )
        assertEquals("true", resp.payload)
        val ev = tree.nextEvents(0)
        assertTrue(ev.events.any { it.type == "nodeScrolled" && it.payload == "DOWN" })

        // 缺省方向 FORWARD
        val def = assertInstanceOf(
            A11yNamespaceHandler.Response.Ok::class.java,
            handler.handle(A11yNamespaceHandler.Request(42, "scroll", """{"ref":$refJson}""")),
        )
        assertEquals("true", def.payload)

        // 不可滚动 → false（不抛错，与 click 同口径）
        val plain = tree.add(InMemoryUiTree.Attrs(text = "纯文本"))
        val plainJson = """{"refId":${plain.refId},"generation":${plain.generation}}"""
        val no = assertInstanceOf(
            A11yNamespaceHandler.Response.Ok::class.java,
            handler.handle(A11yNamespaceHandler.Request(43, "scroll", """{"ref":$plainJson}""")),
        )
        assertEquals("false", no.payload)

        // 非法方向名 → ERR_INVALID_PARAM
        val bad = assertInstanceOf(
            A11yNamespaceHandler.Response.Err::class.java,
            handler.handle(A11yNamespaceHandler.Request(44, "scroll", """{"ref":$refJson,"direction":"斜向"}""")),
        )
        assertEquals("ERR_INVALID_PARAM", bad.code)

        Unit  // 显式收尾：void 返回值才被 JUnit5 视为测试
    }

    @Test
    fun `未知方法回 ERR_NOT_IMPLEMENTED`() = runBlocking {
        val resp = assertInstanceOf(
            A11yNamespaceHandler.Response.Err::class.java,
            handler.handle(A11yNamespaceHandler.Request(40, "pinch", """{"ref":{"refId":1,"generation":1}}""")),
        )
        assertEquals("ERR_NOT_IMPLEMENTED", resp.code)
    }

    @Test
    fun `events 游标拉取与空增量语义`() = runBlocking {
        // 建树时 add 即记 nodeAdded 事件（seedButton 在 setup 外按需调用，这里显式建）
        tree.add(InMemoryUiTree.Attrs(text = "首屏"))
        val first = assertInstanceOf(
            A11yNamespaceHandler.Response.Ok::class.java,
            handler.handle(A11yNamespaceHandler.Request(60, "events", """{"sinceSeq":0,"batch":32}""")),
        )
        val batch = A11yBridgeJson.decodeObject(first.payload!!)
        val last = (batch["last"] as A11yBridgeJson.Value.N).raw.toLong()
        assertTrue(last >= 1)
        assertEquals(1, (batch["events"] as A11yBridgeJson.Value.Arr).items.size)
        val ev0 = ((batch["events"] as A11yBridgeJson.Value.Arr).items[0] as A11yBridgeJson.Value.Obj).fields
        assertEquals("nodeAdded", (ev0["type"] as A11yBridgeJson.Value.S).v)
        assertTrue(ev0["node"] is A11yBridgeJson.Value.Obj)

        // 无参调用同样合法（缺省 sinceSeq=0/batch=32）
        val noarg = assertInstanceOf(
            A11yNamespaceHandler.Response.Ok::class.java,
            handler.handle(A11yNamespaceHandler.Request(61, "events", null)),
        )
        assertTrue(noarg.payload!!.contains("nodeAdded"))

        // 游标已到最新 → 空增量（first==last==sinceSeq，events=[]）
        val empty = assertInstanceOf(
            A11yNamespaceHandler.Response.Ok::class.java,
            handler.handle(A11yNamespaceHandler.Request(62, "events", """{"sinceSeq":$last}""")),
        )
        val eb = A11yBridgeJson.decodeObject(empty.payload!!)
        assertEquals(0, (eb["events"] as A11yBridgeJson.Value.Arr).items.size)

        // batch 非法 → ERR_INVALID_PARAM
        val bad = assertInstanceOf(
            A11yNamespaceHandler.Response.Err::class.java,
            handler.handle(A11yNamespaceHandler.Request(63, "events", """{"batch":0}""")),
        )
        assertEquals("ERR_INVALID_PARAM", bad.code)
    }

    @Test
    fun `copy-paste 经剪贴板中转`() = runBlocking {
        val srcRef = tree.add(InMemoryUiTree.Attrs(text = "复制我"))
        val srcJson = """{"refId":${srcRef.refId},"generation":${srcRef.generation}}"""
        val cp = assertInstanceOf(
            A11yNamespaceHandler.Response.Ok::class.java,
            handler.handle(A11yNamespaceHandler.Request(70, "copy", """{"ref":$srcJson}""")),
        )
        assertEquals("true", cp.payload)
        assertEquals("复制我", tree.clipboard)

        val dstRef = tree.add(InMemoryUiTree.Attrs(editable = true))
        val dstJson = """{"refId":${dstRef.refId},"generation":${dstRef.generation}}"""
        val ps = assertInstanceOf(
            A11yNamespaceHandler.Response.Ok::class.java,
            handler.handle(A11yNamespaceHandler.Request(71, "paste", """{"ref":$dstJson}""")),
        )
        assertEquals("true", ps.payload)
        assertEquals("复制我", tree.attribute(dstRef, "text"))

        // 不可编辑节点 paste → false（不抛错）
        val no = assertInstanceOf(
            A11yNamespaceHandler.Response.Ok::class.java,
            handler.handle(A11yNamespaceHandler.Request(72, "paste", """{"ref":$srcJson}""")),
        )
        assertEquals("false", no.payload)
    }

    @Test
    fun `gesture 开门回 true 并记录`() = runBlocking {
        val resp = assertInstanceOf(
            A11yNamespaceHandler.Response.Ok::class.java,
            handler.handle(
                A11yNamespaceHandler.Request(
                    80, "gesture",
                    """{"strokes":[{"points":[{"x":100,"y":800},{"x":100,"y":200}],"durationMillis":300}]}""",
                ),
            ),
        )
        assertEquals("true", resp.payload)
        val gate = assertInstanceOf(
            A11yNamespaceHandler.Response.Ok::class.java,
            handler.handle(A11yNamespaceHandler.Request(81, "canPerformGestures", null)),
        )
        assertEquals("true", gate.payload)
    }

    @Test
    fun `gesture 关门回 false 不抛错`() = runBlocking {
        val closed = A11yNamespaceHandler(tree, tree, InMemoryInputProvider(canPerformGestures = false))
        val resp = assertInstanceOf(
            A11yNamespaceHandler.Response.Ok::class.java,
            closed.handle(
                A11yNamespaceHandler.Request(
                    82, "gesture",
                    """{"strokes":[{"points":[{"x":0,"y":0}]}]}""",
                ),
            ),
        )
        assertEquals("false", resp.payload)
        val gate = assertInstanceOf(
            A11yNamespaceHandler.Response.Ok::class.java,
            closed.handle(A11yNamespaceHandler.Request(83, "canPerformGestures", null)),
        )
        assertEquals("false", gate.payload)
    }

    @Test
    fun `gesture 非法载荷回 ERR_INVALID_PARAM`() = runBlocking {
        val cases = listOf(
            """{"strokes":[]}""", // 空笔画
            """{"strokes":[{"points":[]}]}""", // 空点
            """{"strokes":[{"points":[{"x":-1,"y":0}]}]}""", // 负坐标
            """{"strokes":[{"points":[{"x":0,"y":0}],"durationMillis":0}]}""", // 非正 duration
            """{"strokes":"x"}""", // 非数组
            """{"nostrokes":1}""", // 缺字段
            null,
        )
        for ((i, p) in cases.withIndex()) {
            val resp = assertInstanceOf(
                A11yNamespaceHandler.Response.Err::class.java,
                handler.handle(A11yNamespaceHandler.Request(90L + i, "gesture", p)),
            )
            assertEquals("ERR_INVALID_PARAM", resp.code, "case $i: $p")
        }
    }

    @Test
    fun `非法载荷回 ERR_INVALID_PARAM`() = runBlocking {
        for ((i, p) in listOf(null, "不是json", """{"noref":1}""", """{"ref":{"refId":"x"}}""").withIndex()) {
            val resp = assertInstanceOf(
                A11yNamespaceHandler.Response.Err::class.java,
                handler.handle(A11yNamespaceHandler.Request(50L + i, "click", p)),
            )
            assertEquals("ERR_INVALID_PARAM", resp.code, "case $i: $p")
        }
    }
}
