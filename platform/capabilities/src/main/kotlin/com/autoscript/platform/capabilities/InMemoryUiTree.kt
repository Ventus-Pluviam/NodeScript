package com.autoscript.platform.capabilities

import com.autoscript.domain.automation.ScrollDirection
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
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/**
 * 内存窗口树（docs §9.1 紧凑索引树的 JVM 可测形态）。
 *
 * 双重身份，一处实现：
 * - 实现 [UiNodeTreeReader]：root/findBySelector/findByText/events；
 * - 实现 [UiActionExecutor]：click/longClick/setText/attribute/bounds/children/parent/dispose。
 *
 * 线程安全（Mutex 串行化变异；读快照拷贝后在锁外谓词匹配）。
 * Android 真实现（AccessibilityNodeInfo 遍历）后续替换本类，江苏契约不变
 * （选择器 AND 语义、generation 失配 → ERR_STALE_HANDLE、dispose 幂等）。
 *
 * generation 模型（§7.4）：每个登记节点 generation=1；跨代操作一律
 * ERR_STALE_HANDLE。内存树无 re-acquire 语义（不像引擎 Receipt），故 acquire
 * 只在同一 generation 上返回同一句柄。
 */
class InMemoryUiTree : UiNodeTreeReader, UiActionExecutor {

    data class Attrs(
        val text: String? = null,
        val desc: String? = null,
        val className: String? = null,
        val packageName: String? = null,
        val viewId: String? = null,
        val clickable: Boolean = false,
        val longClickable: Boolean = false,
        val editable: Boolean = false,
        val scrollable: Boolean = false,
        val bounds: UiBounds? = null,
    )

    private data class Entry(
        val id: Long,
        var attrs: Attrs,
        var parentId: Long?,
        val childIds: MutableList<Long> = mutableListOf(),
        var disposed: Boolean = false,
    )

    private val guard = Mutex()
    private val ids = AtomicLong(1)
    private val entries = HashMap<Long, Entry>()
    private var seq = 1L
    private val events = ArrayList<UiEvent>()

    private val generationOf = HashMap<Long, Long>()

    /** 剪贴板（内存形态；Android 真实现经 ClipboardManager 读写）。 */
    @Volatile var clipboard: String? = null
        private set

    // ── 建树（测试/装配用；Android 真实现不走这里） ─────────────────────

    /** 登记一个节点，返回句柄。parentId=null 即顶层（root 作用域候选）。 */
    suspend fun add(attrs: Attrs, parentId: Long? = null): HandleRef = guard.withLock {
        if (parentId != null) {
            val p = entries[parentId] ?: throw AutojsException(ErrorCode.ERR_NOT_FOUND, "未知父节点 $parentId")
            if (p.disposed) throw AutojsException(ErrorCode.ERR_STALE_HANDLE, "父节点已释放")
        }
        val id = ids.getAndIncrement()
        entries[id] = Entry(id, attrs, parentId)
        generationOf[id] = 1L
        if (parentId != null) entries[parentId]!!.childIds.add(id)
        pushEventLocked("nodeAdded", id, null)
        HandleRef(id, 1L)
    }

    /** 更新节点属性（覆盖整份 Attrs；不存在/已释放按读语义抛错）。 */
    suspend fun update(ref: HandleRef, attrs: Attrs) = guard.withLock {
        val e = liveLocked(ref)
        e.attrs = attrs
        pushEventLocked("nodeChanged", e.id, null)
    }

    // ── UiNodeTreeReader ─────────────────────────────────────────────

    override suspend fun root(scope: WindowScope): UiNode {
        val snap = guard.withLock { entries.values.filter { it.parentId == null && !it.disposed }.map { it.id to it.attrs }.toMap() }
        // ACTIVE/MODAL 在内存树无窗口概念：退化为"首个顶层"（Android 实现按窗口过滤）。
        val first = snap.keys.sorted().firstOrNull()
            ?: throw AutojsException(ErrorCode.ERR_NOT_FOUND, "空树无 root")
        return snapshotNode(first)
    }

    override suspend fun findBySelector(selector: UiSelectorDsl): List<UiNode> {
        val snap = guard.withLock {
            entries.values.filter { !it.disposed }.map { it.id to it.attrs }.toMap()
        }
        return snap.filter { (_, a) -> matches(a, selector) }.keys.sorted().map { snapshotNode(it) }
    }

    override suspend fun findByText(text: String, scope: WindowScope, timeoutMillis: Long): UiNode? {
        // 内存树无轮询等待语义（timeout 由上层 waitFor 循环驱动，见 A11yNamespaceHandler）；
        // 单次快照查找，scope 退化（同 root）。
        val snap = guard.withLock {
            entries.values.filter { !it.disposed }.map { it.id to it.attrs }.toMap()
        }
        val id = snap.filter { (_, a) -> a.text == text }.keys.sorted().firstOrNull() ?: return null
        return snapshotNode(id)
    }

    override fun events(): UiEventStream = stream()

    private fun stream(): UiEventStream {
        val tree = this
        return object : UiEventStream {
            override suspend fun next(sinceSeq: Long, batch: Int): UiEventBatch =
                tree.nextEvents(sinceSeq, batch)
        }
    }

    suspend fun nextEvents(sinceSeq: Long, batch: Int = 32): UiEventBatch = guard.withLock {
        require(batch > 0) { "batch 必须 > 0" }
        val picked = events.filter { it.seq > sinceSeq }.take(batch)
        if (picked.isEmpty()) return UiEventBatch(sinceSeq, sinceSeq, emptyList())
        UiEventBatch(picked.first().seq, picked.last().seq, picked)
    }

    // ── UiActionExecutor ─────────────────────────────────────────────

    override suspend fun click(handle: HandleRef): Boolean = guard.withLock {
        liveLocked(handle)
        true
    }

    override suspend fun longClick(handle: HandleRef): Boolean = guard.withLock {
        val e = liveLocked(handle)
        if (!e.attrs.longClickable && !e.attrs.clickable) return false
        true
    }

    override suspend fun setText(handle: HandleRef, text: String): Boolean = guard.withLock {
        val e = liveLocked(handle)
        if (!e.attrs.editable) return false
        e.attrs = e.attrs.copy(text = text)
        pushEventLocked("nodeChanged", e.id, null)
        true
    }

    override suspend fun scroll(handle: HandleRef, direction: ScrollDirection): Boolean = guard.withLock {
        val e = liveLocked(handle)
        if (!e.attrs.scrollable) return false
        pushEventLocked("nodeScrolled", e.id, direction.name)
        true
    }

    override suspend fun copy(handle: HandleRef): Boolean = guard.withLock {
        val e = liveLocked(handle)
        clipboard = e.attrs.text ?: e.attrs.desc ?: ""
        pushEventLocked("nodeCopied", e.id, null)
        true
    }

    override suspend fun paste(handle: HandleRef): Boolean = guard.withLock {
        val e = liveLocked(handle)
        val content = clipboard ?: return false
        if (!e.attrs.editable) return false
        e.attrs = e.attrs.copy(text = content)
        pushEventLocked("nodeChanged", e.id, null)
        true
    }

    override suspend fun attribute(handle: HandleRef, name: String): String? = guard.withLock {
        val e = liveLocked(handle)
        when (name) {
            "text" -> e.attrs.text
            "desc" -> e.attrs.desc
            "className" -> e.attrs.className
            "packageName" -> e.attrs.packageName
            "id" -> e.attrs.viewId
            "clickable" -> e.attrs.clickable.toString()
            else -> throw AutojsException(ErrorCode.ERR_INVALID_PARAM, "未知属性 $name")
        }
    }

    override suspend fun bounds(handle: HandleRef): UiBounds? = guard.withLock {
        liveLocked(handle).attrs.bounds
    }

    override suspend fun children(handle: HandleRef): List<UiNode> {
        val childIds = guard.withLock { liveLocked(handle).childIds.toList() }
        return childIds.map { snapshotNode(it) }
    }

    override suspend fun parent(handle: HandleRef): UiNode? {
        val pid = guard.withLock { liveLocked(handle).parentId } ?: return null
        return try {
            snapshotNode(pid)
        } catch (_: AutojsException) {
            null // 父已释放 → 孤儿节点，诚实回 null（不断言）
        }
    }

    /** dispose 幂等：重复释放同一 generation 不抛错（JS dispose() 语义）。 */
    override suspend fun dispose(handle: HandleRef) {
        guard.withLock {
            checkGenerationLocked(handle)
            val e = entries[handle.refId] ?: return // 已 forget（平台回收）→ 幂等通过
            if (!e.disposed) {
                e.disposed = true
                pushEventLocked("nodeRemoved", e.id, null)
            }
        }
    }

    // ── 内部 ─────────────────────────────────────────────────────────

    private fun matches(a: Attrs, s: UiSelectorDsl): Boolean {
        // 全部条件 AND（与 JS UiSelectorBuilder 语义对齐）。
        if (s.text != null && a.text != s.text) return false
        if (s.desc != null && a.desc != s.desc) return false
        if (s.className != null && a.className != s.className) return false
        if (s.packageName != null && a.packageName != s.packageName) return false
        if (s.id != null && a.viewId != s.id) return false
        if (s.clickable != null && a.clickable != s.clickable) return false
        if (s.descendantOf != null) {
            // 内存树暂不支持 descendantOf 向上回溯谓词：如实拒收，不静默忽略条件。
            throw AutojsException(ErrorCode.ERR_NOT_IMPLEMENTED, "descendantOf 暂不支持")
        }
        return true
    }

    private fun checkGenerationLocked(ref: HandleRef) {
        val gen = generationOf[ref.refId]
            ?: throw AutojsException(ErrorCode.ERR_STALE_HANDLE, "未知句柄 ${ref.refId}")
        if (gen != ref.generation) {
            throw AutojsException(ErrorCode.ERR_STALE_HANDLE, "句柄跨代 ${ref.refId} gen=${ref.generation}≠$gen")
        }
    }

    private fun liveLocked(ref: HandleRef): Entry {
        checkGenerationLocked(ref)
        val e = entries[ref.refId]
            ?: throw AutojsException(ErrorCode.ERR_STALE_HANDLE, "句柄已回收 ${ref.refId}")
        if (e.disposed) throw AutojsException(ErrorCode.ERR_STALE_HANDLE, "节点已释放 ${ref.refId}")
        return e
    }

    private fun pushEventLocked(type: String, nodeId: Long?, payload: String?) {
        events.add(UiEvent(seq = seq++, type = type, nodeHandle = nodeId?.let { HandleRef(it, generationOf[it] ?: 1L) }, payload = payload))
        while (events.size > MAX_EVENTS) events.removeAt(0)
    }

    /** 锁外快照节点（UiNode 只读视图；动作经 UiActionExecutor 回查 live）。 */
    private suspend fun snapshotNode(id: Long): UiNode {
        val (attrs, gen) = guard.withLock {
            val e = entries[id] ?: throw AutojsException(ErrorCode.ERR_STALE_HANDLE, "句柄已回收 $id")
            if (e.disposed) throw AutojsException(ErrorCode.ERR_STALE_HANDLE, "节点已释放 $id")
            e.attrs to (generationOf[id] ?: 1L)
        }
        val tree = this
        val ref = HandleRef(id, gen)
        return object : UiNode {
            override val handle: HandleRef = ref
            override val className: String? = attrs.className
            override suspend fun attribute(name: String): String? = tree.attribute(ref, name)
            override suspend fun children(): List<UiNode> = tree.children(ref)
        }
    }

    companion object {
        const val MAX_EVENTS = 512
    }
}
