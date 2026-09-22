package com.autoscript.platform.capabilities

import com.autoscript.domain.automation.GestureInput
import com.autoscript.domain.automation.GesturePoint
import com.autoscript.domain.automation.GestureStroke
import com.autoscript.domain.automation.ScrollDirection
import com.autoscript.domain.automation.UiBounds
import com.autoscript.domain.automation.UiSelectorDsl
import com.autoscript.domain.automation.WindowScope
import com.autoscript.domain.bridge.HandleRef
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [AndroidUiTree] + [AndroidGestureInput] 语义（假桥注入；设备面在
 * AutoScriptAccessibilityService，本机不跑 —— 语义逻辑在此钉死）。
 *
 * 覆盖：窗口作用域、选择器 AND、句柄/代数纪律、alive=false → STALE 回收、
 * 动作结果透传、剪贴板往返、服务未连 → ERR_SERVICE_DISABLED（§9.5 诚实口径）、
 * 手势关门 vs 服务未连的分界 + handler 原码折叠。
 */
class AndroidUiTreeTest {

    private fun snap(
        text: String? = null,
        desc: String? = null,
        className: String? = null,
        packageName: String? = null,
        viewId: String? = null,
        clickable: Boolean = false,
        longClickable: Boolean = false,
        editable: Boolean = false,
        scrollable: Boolean = false,
        bounds: UiBounds? = null,
    ) = A11yNodeSnap(
        text = text, desc = desc, className = className, packageName = packageName,
        viewId = viewId, clickable = clickable, longClickable = longClickable,
        editable = editable, scrollable = scrollable, bounds = bounds,
    )

    /** 蓝图树：root(frame) ─ btn(「启动」可点) + label(「标签」)。返回 (桥, btn蓝图, label蓝图)。 */
    private fun bridgeWithTree(): Triple<FakeA11yBridge, FakeA11yBridge.Blue, FakeA11yBridge.Blue> {
        val b = FakeA11yBridge()
        val root = b.seedActive(snap(className = "android.widget.FrameLayout"))
        val btn = root.add(
            snap(
                text = "启动", className = "android.widget.Button", viewId = "com.x:id/btn",
                clickable = true, longClickable = true, editable = true,
                bounds = UiBounds(10, 20, 110, 80),
            ),
        )
        val label = root.add(snap(text = "标签", className = "android.widget.TextView"))
        return Triple(b, btn, label)
    }

    private fun tree(b: FakeA11yBridge): AndroidUiTree =
        AndroidUiTree(bridge = b, eventStream = A11yEventRing())

    private fun codeOf(block: suspend () -> Unit): String {
        val e = assertThrows(AutojsException::class.java) { runBlocking { block() } }
        return e.error.code
    }

    private fun tap(): GestureInput = GestureInput(listOf(GestureStroke(listOf(GesturePoint(1, 1)))))

    // ── 服务未连：全平面如实 ERR_SERVICE_DISABLED ─────────────────────

    @Test
    fun `服务未连 读写动作手势全报 ERR_SERVICE_DISABLED`() {
        val (b, _, _) = bridgeWithTree()
        b.connected = false
        val t = tree(b)

        // 桥面方法（读树/剪贴板/手势）→ SERVICE_DISABLED（"没服务"如实喊）。
        assertEquals(ErrorCode.ERR_SERVICE_DISABLED.code, codeOf { t.root(WindowScope.ACTIVE) })
        assertEquals(ErrorCode.ERR_SERVICE_DISABLED.code, codeOf { t.findBySelector(UiSelectorDsl.builder()) })
        assertEquals(ErrorCode.ERR_SERVICE_DISABLED.code, codeOf { t.findByText("启动", WindowScope.ACTIVE, 0) })
        assertEquals(ErrorCode.ERR_SERVICE_DISABLED.code, codeOf { t.paste(HandleRef(1, 1)) })
        // 句柄面先答树面：没登记过的 ref = STALE（与连接态无关，防伪造句柄撞桥错误）。
        assertEquals(ErrorCode.ERR_STALE_HANDLE.code, codeOf { t.click(HandleRef(1, 1)) })
        assertEquals(ErrorCode.ERR_STALE_HANDLE.code, codeOf { t.copy(HandleRef(1, 1)) })

        val input = AndroidGestureInput(bridge = b)
        assertEquals(ErrorCode.ERR_SERVICE_DISABLED.code, codeOf { input.canPerformGestures })
        assertEquals(ErrorCode.ERR_SERVICE_DISABLED.code, codeOf { input.dispatchGesture(tap()) })
    }

    // ── root / 作用域 ────────────────────────────────────────────────

    @Test
    fun `root 按作用域取根 多余根回收 空树 NOT_FOUND`() {
        val b = FakeA11yBridge()
        b.seedActive(snap(className = "a1"))
        b.seedActive(snap(className = "a2"))
        val modal = b.modalRoots.let {
            FakeA11yBridge.Blue(snap(className = "modal-root")).also(it::add)
        }
        val t = tree(b)

        val gotModal = runBlocking { t.root(WindowScope.MODAL) }
        assertEquals("modal-root", gotModal.className, "MODAL 有模态窗时取模态根")
        assertTrue(b.materialized[0].blueAlive(), "匹配根进句柄表")
        assertEquals(1, b.materialized.size, "模态命中时只物化一个根")

        val active = runBlocking { t.root(WindowScope.ACTIVE) }
        assertEquals("a1", active.className)
        assertFalse(b.materialized[0].recycled, "首个根进句柄表不回收")
        assertTrue(b.materialized[1].recycled, "多余根必须回收（防泄漏）")

        val empty = tree(FakeA11yBridge())
        assertEquals(ErrorCode.ERR_NOT_FOUND.code, codeOf { empty.root(WindowScope.ACTIVE) })
    }

    // ── 选择器 / 文本 ───────────────────────────────────────────────

    @Test
    fun `选择器 AND 语义 descendantOf 拒收 findByText 首个命中`() {
        val (b, _, _) = bridgeWithTree()
        val t = tree(b)

        val hit = runBlocking {
            t.findBySelector(UiSelectorDsl.builder().copyWith(text = "启动", className = "android.widget.Button"))
        }
        assertEquals(1, hit.size, "AND：text+className 同时满足才命中")
        assertEquals("android.widget.Button", hit[0].className)

        val miss = runBlocking {
            t.findBySelector(UiSelectorDsl.builder().copyWith(text = "启动", packageName = "com.other"))
        }
        assertTrue(miss.isEmpty(), "任一条件不满足即不命中（拼错条件不得静默变全量）")

        val d = UiSelectorDsl.builder().copyWith(descendantOf = UiSelectorDsl.builder())
        assertEquals(ErrorCode.ERR_NOT_IMPLEMENTED.code, codeOf { t.findBySelector(d) })

        val byText = runBlocking { t.findByText("标签", WindowScope.ACTIVE, 0) }
        assertNotNull(byText, "text 精确匹配")
        assertNull(runBlocking { t.findByText("不存在", WindowScope.ACTIVE, 0) })
    }

    @Test
    fun `搜索时非匹配包装当场回收 根不豁免`() {
        val (b, _, _) = bridgeWithTree()
        val t = tree(b)
        runBlocking { t.findBySelector(UiSelectorDsl.builder().copyWith(text = "启动")) }
        val rootWrap = b.materialized.first()
        assertTrue(rootWrap.recycled, "根不匹配条件 → 搜索中回收（下次 roots() 重新物化，屏幕没变）")
        // 回收快照 ≠ 节点离开：重新 roots 仍能拿到
        assertNotNull(runBlocking { t.root(WindowScope.ACTIVE) })
    }

    // ── 句柄纪律 ─────────────────────────────────────────────────────

    @Test
    fun `alive 翻 false refresh 失败 回收注册项 后续 STALE`() {
        val (b, btnBlue, _) = bridgeWithTree()
        val t = tree(b)
        val node = runBlocking { t.findBySelector(UiSelectorDsl.builder().copyWith(text = "启动"))[0] }
        assertTrue(runBlocking { t.click(node.handle) })

        btnBlue.alive = false // 节点离开窗口树
        assertEquals(ErrorCode.ERR_STALE_HANDLE.code, codeOf { t.click(node.handle) })
        assertEquals(ErrorCode.ERR_STALE_HANDLE.code, codeOf { t.click(node.handle) }, "首次失败已回收 → 恒 STALE")
    }

    @Test
    fun `dispose 幂等 跨代与未知句柄 STALE`() {
        val (b, _, _) = bridgeWithTree()
        val t = tree(b)
        val node = runBlocking { t.findBySelector(UiSelectorDsl.builder().copyWith(text = "启动"))[0] }
        runBlocking { t.dispose(node.handle) }
        runBlocking { t.dispose(node.handle) } // 幂等
        assertEquals(ErrorCode.ERR_STALE_HANDLE.code, codeOf { t.click(node.handle) })
        assertEquals(ErrorCode.ERR_STALE_HANDLE.code, codeOf { t.click(HandleRef(9999, 1)) })
        assertEquals(ErrorCode.ERR_STALE_HANDLE.code, codeOf { t.click(HandleRef(1, 2)) }, "generation≠1 捏造代数不认")
    }

    // ── 动作透明转发 ─────────────────────────────────────────────────

    @Test
    fun `动作结果原样透传 设备说 false 就是 false`() {
        val (b, btnBlue, _) = bridgeWithTree()
        val t = tree(b)
        val btn = runBlocking { t.findBySelector(UiSelectorDsl.builder().copyWith(text = "启动"))[0] }

        assertTrue(runBlocking { t.click(btn.handle) })
        btnBlue.clickResult = false
        assertFalse(runBlocking { t.click(btn.handle) }, "设备侧 false 必须透传（不伪造 true）")
        btnBlue.setTextResult = false
        assertFalse(runBlocking { t.setText(btn.handle, "x") })
        assertTrue(runBlocking { t.longClick(btn.handle) }, "longClickable 节点先走通")
        btnBlue.longClickResult = false
        assertFalse(runBlocking { t.longClick(btn.handle) }, "系统拒绝原样透传")
        // 不可滚动（btn.scrollable=false，属性门）→ false，不靠结果位
        assertFalse(runBlocking { t.scroll(btn.handle, ScrollDirection.FORWARD) })
    }

    @Test
    fun `copy paste 剪贴板往返 空剪贴板 false 非可编辑 false`() {
        val (b, _, _) = bridgeWithTree()
        val t = tree(b)
        val btn = runBlocking { t.findBySelector(UiSelectorDsl.builder().copyWith(text = "启动"))[0] }

        assertNull(b.clipboard, "起点无剪贴板内容")
        assertFalse(runBlocking { t.paste(btn.handle) }, "无剪贴板内容 → false（不是抛错）")

        assertTrue(runBlocking { t.copy(btn.handle) })
        assertEquals("启动", b.clipboard, "copy 取 text（皆空则空串）")

        val label = runBlocking { t.findByText("标签", WindowScope.ACTIVE, 0)!! }
        assertFalse(runBlocking { t.paste(label.handle) }, "不可编辑目标 → 设备 false")
        assertTrue(runBlocking { t.paste(btn.handle) }, "可编辑目标粘贴成功")
    }

    @Test
    fun `属性白名单与 bounds children parent`() {
        val (b, _, _) = bridgeWithTree()
        val t = tree(b)
        val btn = runBlocking { t.findBySelector(UiSelectorDsl.builder().copyWith(text = "启动"))[0] }
        assertEquals("com.x:id/btn", runBlocking { t.attribute(btn.handle, "id") })
        assertEquals("true", runBlocking { t.attribute(btn.handle, "clickable") })
        assertEquals(ErrorCode.ERR_INVALID_PARAM.code, codeOf { t.attribute(btn.handle, "nope") })
        assertEquals(UiBounds(10, 20, 110, 80), runBlocking { t.bounds(btn.handle) }, "bounds 原样回传")

        val rootNode = runBlocking { t.root(WindowScope.ACTIVE) }
        val kids = runBlocking { t.children(rootNode.handle) }
        assertEquals(2, kids.size)
        val parent = runBlocking { t.parent(kids[0].handle) }
        assertNotNull(parent, "子节点的 parent 回到根")
    }

    // ── 手势：关门 vs 未连 ───────────────────────────────────────────

    @Test
    fun `手势 关门 false 不发系统 服务未连 handler 原码折叠`() {
        val b = FakeA11yBridge()
        val input = AndroidGestureInput(bridge = b)

        b.canPerform = false
        assertFalse(runBlocking { input.dispatchGesture(tap()) })
        assertEquals(0, b.dispatchCount, "关门：不往系统发一次")
        assertFalse(runBlocking { input.canPerformGestures })

        b.canPerform = true
        b.dispatchResult = false
        assertFalse(runBlocking { input.dispatchGesture(tap()) }, "系统拒绝原样透传")
        assertEquals(1, b.dispatchCount)

        // handler 折叠：服务未连 → Err ERR_SERVICE_DISABLED（不折 false、不折 INVALID_PARAM）
        b.connected = false
        val mem = InMemoryUiTree()
        val handler = A11yNamespaceHandler(tree = mem, actions = mem, input = input)
        val resp = runBlocking {
            handler.handle(A11yNamespaceHandler.Request(1, "canPerformGestures", null))
        }
        assertTrue(resp is A11yNamespaceHandler.Response.Err, "未连时 canPerformGestures 必须是 Err")
        assertEquals(
            ErrorCode.ERR_SERVICE_DISABLED.code,
            (resp as A11yNamespaceHandler.Response.Err).code,
        )
        val g = runBlocking {
            handler.handle(
                A11yNamespaceHandler.Request(
                    2, "gesture",
                    """{"strokes":[{"points":[{"x":1,"y":1}]}]}""",
                ),
            )
        }
        assertTrue(g is A11yNamespaceHandler.Response.Err, "gesture 同折叠")
        assertEquals(ErrorCode.ERR_SERVICE_DISABLED.code, (g as A11yNamespaceHandler.Response.Err).code)
    }

    /** 断言辅助：materialized 里的根是否仍活（未回收）。 */
    private fun FakeA11yBridge.FakeNode.blueAlive(): Boolean = !recycled
}
