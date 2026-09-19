package com.autoscript.platform.capabilities

import com.autoscript.domain.core.ErrorCode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class InMemoryUiTreeTest {

    private lateinit var tree: InMemoryUiTree

    @BeforeEach
    fun setup() {
        tree = InMemoryUiTree()
    }

    private suspend fun button(): com.autoscript.domain.bridge.HandleRef =
        tree.add(
            InMemoryUiTree.Attrs(
                text = "启动", desc = "启动按钮", className = "Button",
                packageName = "com.example", viewId = "btn_go",
                clickable = true, longClickable = true,
                bounds = com.autoscript.domain.automation.UiBounds(10, 20, 110, 60),
            ),
        )

    @Test
    fun `选择器 AND 语义`() = runBlocking {
        button()
        tree.add(InMemoryUiTree.Attrs(text = "启动", className = "TextView", clickable = false))
        val dsl = com.autoscript.domain.automation.UiSelectorDsl.builder()
            .copyWith(text = "启动", clickable = true)
        val found = tree.findBySelector(dsl)
        assertEquals(1, found.size)
        assertEquals("Button", found.single().className)
    }

    @Test
    fun `空条件全量匹配`() = runBlocking {
        button()
        tree.add(InMemoryUiTree.Attrs(text = "其他"))
        val found = tree.findBySelector(com.autoscript.domain.automation.UiSelectorDsl.builder())
        assertEquals(2, found.size)
    }

    @Test
    fun `desc 与 id 条件`() = runBlocking {
        button()
        val byDesc = tree.findBySelector(
            com.autoscript.domain.automation.UiSelectorDsl.builder().copyWith(desc = "启动按钮"),
        )
        assertEquals(1, byDesc.size)
        val byId = tree.findBySelector(
            com.autoscript.domain.automation.UiSelectorDsl.builder().copyWith(id = "btn_go"),
        )
        assertEquals(1, byId.size)
        val miss = tree.findBySelector(
            com.autoscript.domain.automation.UiSelectorDsl.builder().copyWith(id = "nope"),
        )
        assertTrue(miss.isEmpty())
    }

    @Test
    fun `跨代句柄失配抛 ERR_STALE_HANDLE`() = runBlocking {
        val ref = button()
        val stale = com.autoscript.domain.bridge.HandleRef(ref.refId, ref.generation + 1)
        val err = try {
            tree.click(stale)
            null
        } catch (e: com.autoscript.domain.core.AutojsException) {
            e
        }
        assertEquals(ErrorCode.ERR_STALE_HANDLE, err?.error)
    }

    @Test
    fun `dispose 后操作失配且幂等`() = runBlocking {
        val ref = button()
        tree.dispose(ref) // 首次释放通过
        tree.dispose(ref) // 重复释放幂等通过
        val err = try {
            tree.click(ref)
            null
        } catch (e: com.autoscript.domain.core.AutojsException) {
            e
        }
        assertEquals(ErrorCode.ERR_STALE_HANDLE, err?.error)
        // 释放后不再出现在查找结果
        val found = tree.findBySelector(com.autoscript.domain.automation.UiSelectorDsl.builder())
        assertTrue(found.isEmpty())
    }

    @Test
    fun `未知句柄失配`() = runBlocking {
        val err = try {
            tree.click(com.autoscript.domain.bridge.HandleRef(9999, 1))
            null
        } catch (e: com.autoscript.domain.core.AutojsException) {
            e
        }
        assertEquals(ErrorCode.ERR_STALE_HANDLE, err?.error)
    }

    @Test
    fun `click 与 setText 语义`() = runBlocking {
        val btn = button()
        assertTrue(tree.click(btn))
        val label = tree.add(InMemoryUiTree.Attrs(text = "旧", editable = false))
        assertEquals(false, tree.setText(label, "新"), "不可编辑返回 false（不抛错）")
        val input = tree.add(InMemoryUiTree.Attrs(editable = true))
        assertTrue(tree.setText(input, "新"))
        assertEquals("新", tree.attribute(input, "text"))
    }

    @Test
    fun `longClick 不可点返回 false`() = runBlocking {
        val label = tree.add(InMemoryUiTree.Attrs(text = "纯文本"))
        assertEquals(false, tree.longClick(label))
        assertTrue(tree.longClick(button()))
    }

    @Test
    fun `scroll 可滚动回 true 并记事件，不可滚动回 false`() = runBlocking {
        val list = tree.add(InMemoryUiTree.Attrs(className = "RecyclerView", scrollable = true))
        assertTrue(tree.scroll(list, com.autoscript.domain.automation.ScrollDirection.DOWN))
        val ev = tree.nextEvents(0)
        assertTrue(ev.events.any { it.type == "nodeScrolled" })
        val label = tree.add(InMemoryUiTree.Attrs(text = "纯文本"))
        assertEquals(false, tree.scroll(label, com.autoscript.domain.automation.ScrollDirection.UP))
    }

    @Test
    fun `copy-paste 剪贴板中转与空剪贴板语义`() = runBlocking {
        // 空剪贴板 paste → false（无内容可贴不是错误）
        val input = tree.add(InMemoryUiTree.Attrs(editable = true))
        assertEquals(false, tree.paste(input))

        val src = tree.add(InMemoryUiTree.Attrs(text = "带走", desc = "备用"))
        assertTrue(tree.copy(src))
        assertEquals("带走", tree.clipboard, "text 优先于 desc")
        assertTrue(tree.paste(input))
        assertEquals("带走", tree.attribute(input, "text"))

        // desc 回退：text 空时取 desc
        val descOnly = tree.add(InMemoryUiTree.Attrs(desc = "只有描述"))
        assertTrue(tree.copy(descOnly))
        assertEquals("只有描述", tree.clipboard)

        // 不可编辑 paste → false
        val label = tree.add(InMemoryUiTree.Attrs(text = "纯文本"))
        assertEquals(false, tree.paste(label))
    }

    @Test
    fun `attribute 未知名抛 ERR_INVALID_PARAM`() = runBlocking {
        val ref = button()
        assertEquals("启动", tree.attribute(ref, "text"))
        assertEquals("启动按钮", tree.attribute(ref, "desc"))
        assertEquals("Button", tree.attribute(ref, "className"))
        assertEquals("com.example", tree.attribute(ref, "packageName"))
        assertEquals("btn_go", tree.attribute(ref, "id"))
        assertEquals("true", tree.attribute(ref, "clickable"))
        val err = try {
            tree.attribute(ref, "nope")
            null
        } catch (e: com.autoscript.domain.core.AutojsException) {
            e
        }
        assertEquals(ErrorCode.ERR_INVALID_PARAM, err?.error)
    }

    @Test
    fun `bounds 与 children-parent 导航`() = runBlocking {
        val parentRef = tree.add(InMemoryUiTree.Attrs(text = "容器", className = "LinearLayout"))
        val childRef = tree.add(InMemoryUiTree.Attrs(text = "子"), parentId = parentRef.refId)
        assertEquals(com.autoscript.domain.automation.UiBounds(10, 20, 110, 60), tree.bounds(button()))
        assertNull(tree.bounds(parentRef), "无 bounds 回 null（不抛错）")
        val kids = tree.children(parentRef)
        assertEquals(1, kids.size)
        assertEquals(childRef, kids.single().handle)
        val back = tree.parent(childRef)
        assertEquals(parentRef, back?.handle)
        assertNull(tree.parent(parentRef), "顶层 parent 回 null")
    }

    @Test
    fun `释放父后子成孤儿 parent 回 null`() = runBlocking {
        val parentRef = tree.add(InMemoryUiTree.Attrs(text = "容器"))
        val childRef = tree.add(InMemoryUiTree.Attrs(text = "子"), parentId = parentRef.refId)
        tree.dispose(parentRef)
        assertNull(tree.parent(childRef))
    }

    @Test
    fun `findByText 单次快照`() = runBlocking {
        button()
        val found = tree.findByText("启动", com.autoscript.domain.automation.WindowScope.ACTIVE, 1_000)
        assertEquals("Button", found?.className)
        assertNull(tree.findByText("不存在", com.autoscript.domain.automation.WindowScope.ACTIVE, 1_000))
    }

    @Test
    fun `root 空树抛 NOT_FOUND`() = runBlocking {
        val err = try {
            tree.root(com.autoscript.domain.automation.WindowScope.ACTIVE)
            null
        } catch (e: com.autoscript.domain.core.AutojsException) {
            e
        }
        assertEquals(ErrorCode.ERR_NOT_FOUND, err?.error)
        button()
        assertEquals("Button", tree.root(com.autoscript.domain.automation.WindowScope.ACTIVE).className)
    }

    @Test
    fun `事件流游标拉取`() = runBlocking {
        button() // seq1 nodeAdded
        val batch1 = tree.nextEvents(0, batch = 32)
        assertEquals(1, batch1.events.size)
        assertEquals("nodeAdded", batch1.events.single().type)
        val ref2 = tree.add(InMemoryUiTree.Attrs(text = "二"))
        tree.dispose(ref2)
        val batch2 = tree.nextEvents(batch1.lastSeq, batch = 32)
        assertEquals(listOf("nodeAdded", "nodeRemoved"), batch2.events.map { it.type })
        val batch3 = tree.nextEvents(batch2.lastSeq, batch = 32)
        assertTrue(batch3.events.isEmpty())
        assertEquals(batch2.lastSeq, batch3.lastSeq, "空拉回原游标")
    }

    @Test
    fun `并发 add 不丢节点`() = runBlocking {
        (1..100).map { i ->
            async { tree.add(InMemoryUiTree.Attrs(text = "n$i")) }
        }.awaitAll()
        val found = tree.findBySelector(com.autoscript.domain.automation.UiSelectorDsl.builder())
        assertEquals(100, found.size)
        assertEquals(100, found.map { it.handle.refId }.toSet().size, "refId 全局唯一")
    }

    @Test
    fun `UiNode 快照视图回查 live`() = runBlocking {
        val ref = button()
        val node = tree.findBySelector(com.autoscript.domain.automation.UiSelectorDsl.builder()).single()
        assertEquals(ref, node.handle)
        assertEquals("Button", node.className)
        assertEquals("启动", node.attribute("text"))
        assertTrue(node.children().isEmpty())
        tree.dispose(ref)
        val err = try {
            node.attribute("text")
            null
        } catch (e: com.autoscript.domain.core.AutojsException) {
            e
        }
        assertEquals(ErrorCode.ERR_STALE_HANDLE, err?.error, "快照持有旧句柄：释放后回查失配")
    }
}
