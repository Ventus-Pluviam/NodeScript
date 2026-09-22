package com.autoscript.platform.capabilities

import com.autoscript.domain.automation.ScrollDirection
import com.autoscript.domain.automation.UiActionExecutor
import com.autoscript.domain.automation.UiBounds
import com.autoscript.domain.automation.UiEventStream
import com.autoscript.domain.automation.UiNode
import com.autoscript.domain.automation.UiNodeTreeReader
import com.autoscript.domain.automation.UiSelectorDsl
import com.autoscript.domain.automation.WindowScope
import com.autoscript.domain.bridge.HandleRef
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Android 真实现的窗口树（docs §9.1；`InMemoryUiTree` 的系统侧对偶）。
 *
 * 双重身份一处实现（与内存树同形，句柄注册表共享）：
 * - [UiNodeTreeReader]：root/findBySelector/findByText/events；
 * - [UiActionExecutor]：click/…/dispose。
 *
 * 构造只认 [A11yBridge] 缝（缺省 [SystemA11yBridge]）—— 语义逻辑纯 JVM 可测
 * （假桥注入），设备细节（`AccessibilityNodeInfo` 遍历/`performAction`）全部在
 * `AutoScriptAccessibilityService` 的适配层。服务未连 → 桥抛 ERR_SERVICE_DISABLED，
 * handler 原码回桥（§9.5 诚实口径）。
 *
 * 与内存树的**刻意差异**（都是"设备是真相"的推论，不是漂移）：
 * - 动作不预检 clickable/editable/scrollable —— `performAction` 的返回值即事实
 *   （内存树没有系统可问，才用属性预检近似）；
 * - 分层两答：**桥面方法**（root、findBySelector、findByText、剪贴板、手势）服务未连
 *   → ERR_SERVICE_DISABLED；
 *   **句柄面动作**先解注册表 —— 未登记 ref → ERR_STALE_HANDLE（与连接态无关，伪造
 *   句柄不许报成"没服务"），已登记但服务已死 → refresh=false → ERR_STALE_HANDLE（回收）；
 * - refresh 失败（节点已离开树）→ ERR_STALE_HANDLE 并**当场回收**注册表项 ——
 *   轮询型脚本不会把注册表撑爆（内存树无此问题：树是自己的）；
 * - 选择器查询作用于**全部窗口**（[UiNodeTreeReader.findBySelector] 无 scope 参数，
 *   §9.1 `flagRetrieveInteractiveWindows` 下的最不惊讶语义）；root/findByText 尊重
 *   传入的 scope。
 *
 * generation 模型：注册项恒 generation=1（无再获取语义）；ref.generation≠1 一律
 * ERR_STALE_HANDLE（JS 侧捏造的代数不认）。stale = 已 dispose / 不在注册表 / refresh 失败。
 */
class AndroidUiTree(
    private val bridge: A11yBridge = SystemA11yBridge,
    private val eventStream: UiEventStream = A11yEventRing.shared,
) : UiNodeTreeReader, UiActionExecutor {

    private val guard = Mutex()
    private val ids = AtomicLong(1)
    private val live = HashMap<Long, A11yNode>()

    // ── UiNodeTreeReader ─────────────────────────────────────────────

    override suspend fun root(scope: WindowScope): UiNode {
        val roots = bridge.roots(scope) // 服务未连 → ERR_SERVICE_DISABLED
        val first = roots.firstOrNull() ?: throw AutojsException(ErrorCode.ERR_NOT_FOUND, "空树无 root")
        // 其余窗口根不进注册表：留着就是没人 dispose 的泄漏。
        for (extra in roots.drop(1)) extra.recycle()
        return wrap(first)
    }

    override suspend fun findBySelector(selector: UiSelectorDsl): List<UiNode> {
        // 搜索前先拒 descendantOf：DFS 中途抛错会把已访问节点晾在注册表外。
        if (selector.descendantOf != null) {
            throw AutojsException(ErrorCode.ERR_NOT_IMPLEMENTED, "descendantOf 暂不支持")
        }
        return search(bridge.roots(WindowScope.ALL)) { snap -> matches(snap, selector) }.map { wrap(it) }
    }

    override suspend fun findByText(text: String, scope: WindowScope, timeoutMillis: Long): UiNode? {
        // 与内存树同口径：单次快照查找，timeout 由上层 waitFor 循环驱动（KDoc 同见 InMemoryUiTree）。
        val hits = search(bridge.roots(scope)) { snap -> snap.text == text }
        val first = hits.firstOrNull() ?: return null
        for (extra in hits.drop(1)) extra.recycle() // 只回首个：其余匹配项不留注册表
        return wrap(first)
    }

    override fun events(): UiEventStream = eventStream

    // ── UiActionExecutor ─────────────────────────────────────────────

    override suspend fun click(handle: HandleRef): Boolean = live(handle).performClick()

    override suspend fun longClick(handle: HandleRef): Boolean = live(handle).performLongClick()

    override suspend fun setText(handle: HandleRef, text: String): Boolean = live(handle).performSetText(text)

    override suspend fun scroll(handle: HandleRef, direction: ScrollDirection): Boolean =
        live(handle).performScroll(direction)

    override suspend fun copy(handle: HandleRef): Boolean {
        val snap = live(handle).snapshot()
        bridge.clipboardWrite(snap.text ?: snap.desc ?: "")
        return true
    }

    override suspend fun paste(handle: HandleRef): Boolean {
        val content = bridge.clipboardRead() ?: return false
        return live(handle).performSetText(content)
    }

    override suspend fun attribute(handle: HandleRef, name: String): String? {
        val snap = live(handle).snapshot()
        return when (name) {
            "text" -> snap.text
            "desc" -> snap.desc
            "className" -> snap.className
            "packageName" -> snap.packageName
            "id" -> snap.viewId
            "clickable" -> snap.clickable.toString()
            else -> throw AutojsException(ErrorCode.ERR_INVALID_PARAM, "未知属性 $name")
        }
    }

    override suspend fun bounds(handle: HandleRef): UiBounds? = live(handle).snapshot().bounds

    override suspend fun children(handle: HandleRef): List<UiNode> =
        live(handle).children().map { wrap(it) }

    override suspend fun parent(handle: HandleRef): UiNode? =
        live(handle).parent()?.let { wrap(it) }

    /** dispose 幂等（JS `dispose()` 语义）：已回收/已释放 → 静默通过；跨代 → STALE。 */
    override suspend fun dispose(handle: HandleRef) {
        guard.withLock {
            checkGeneration(handle)
            live.remove(handle.refId)?.recycle()
        }
    }

    // ── 内部 ─────────────────────────────────────────────────────────

    /**
     * 从 [roots] 起 DFS（前序）收集匹配项（**不**注册 —— 调用方决定谁进句柄表）。
     * 非匹配节点当场 [A11yNode.recycle]（其子节点在回收前已取出，Android 节点相互独立）；
     * 匹配节点保持活，生命周期由后续 wrap/dispose 管。
     */
    private fun search(roots: List<A11yNode>, predicate: (A11yNodeSnap) -> Boolean): List<A11yNode> {
        val matched = ArrayList<A11yNode>()
        val stack = ArrayDeque<A11yNode>()
        for (i in roots.indices) stack.addLast(roots[roots.size - 1 - i]) // 保 roots 序入栈
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            val snap = node.snapshot()
            val kids = node.children()
            if (predicate(snap)) {
                matched.add(node)
            } else {
                node.recycle()
            }
            for (i in kids.indices) stack.addLast(kids[kids.size - 1 - i])
        }
        return matched
    }

    private fun matches(a: A11yNodeSnap, s: UiSelectorDsl): Boolean {
        // 全部条件 AND（与内存树/JS UiSelectorBuilder 同语义）。
        if (s.text != null && a.text != s.text) return false
        if (s.desc != null && a.desc != s.desc) return false
        if (s.className != null && a.className != s.className) return false
        if (s.packageName != null && a.packageName != s.packageName) return false
        if (s.id != null && a.viewId != s.id) return false
        if (s.clickable != null && a.clickable != s.clickable) return false
        if (s.descendantOf != null) {
            throw AutojsException(ErrorCode.ERR_NOT_IMPLEMENTED, "descendantOf 暂不支持")
        }
        return true
    }

    private fun checkGeneration(ref: HandleRef) {
        if (ref.generation != 1L) {
            throw AutojsException(
                ErrorCode.ERR_STALE_HANDLE,
                "句柄跨代 ${ref.refId} gen=${ref.generation}≠1",
            )
        }
    }

    /** 注册表取活节点：refresh 失败 = 已离开树 → 回收注册项 + ERR_STALE_HANDLE。 */
    private suspend fun live(ref: HandleRef): A11yNode = guard.withLock {
        checkGeneration(ref)
        val node = live[ref.refId]
            ?: throw AutojsException(ErrorCode.ERR_STALE_HANDLE, "句柄已回收 ${ref.refId}")
        if (!node.refresh()) {
            live.remove(ref.refId)
            node.recycle()
            throw AutojsException(ErrorCode.ERR_STALE_HANDLE, "节点已离开窗口树 ${ref.refId}")
        }
        node
    }

    private suspend fun wrap(node: A11yNode): UiNode {
        val ref = guard.withLock {
            val id = ids.getAndIncrement()
            live[id] = node
            HandleRef(id, 1L)
        }
        val tree = this
        return object : UiNode {
            override val handle: HandleRef = ref
            override val className: String? get() = node.snapshot().className
            override suspend fun attribute(name: String): String? = tree.attribute(ref, name)
            override suspend fun children(): List<UiNode> = tree.children(ref)
        }
    }
}
