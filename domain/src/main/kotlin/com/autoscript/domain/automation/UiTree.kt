package com.autoscript.domain.automation

import com.autoscript.domain.bridge.HandleRef

/**
 * 无障碍窗口树读 SPI（docs/framework-design.md §9.1）。
 * 实现位于 :platform:capabilities：紧凑索引树 + 属性按需二次查询。
 * UI 对象 = JS 侧代理（HandleRef），generation 失配 → ERR_STALE_HANDLE。
 */
interface UiNodeTreeReader {
    suspend fun root(scope: WindowScope): UiNode
    suspend fun findBySelector(selector: UiSelectorDsl): List<UiNode>
    suspend fun findByText(text: String, scope: WindowScope, timeoutMillis: Long): UiNode?
    fun events(): UiEventStream            // 节流拉取式事件流（seq 游标）
}

enum class WindowScope { ACTIVE, MODAL, ALL }

/** 只读索引节点：属性按需二次查询（惰性），基线仅索引字段。 */
interface UiNode {
    val handle: HandleRef
    val className: String?
    suspend fun attribute(name: String): String?
    suspend fun children(): List<UiNode>
}

/** 事件流（EventEmitter 语义由桥侧承接；领域层只管游标契约）。 */
interface UiEventStream {
    suspend fun next(sinceSeq: Long, batch: Int = 32): UiEventBatch
}

data class UiEventBatch(val firstSeq: Long, val lastSeq: Long, val events: List<UiEvent>)

data class UiEvent(val seq: Long, val type: String, val nodeHandle: HandleRef?, val payload: String?)

/** 选择器构建 DSL：text/desc/className/package/clickable 谓词 + id，返回谓词模型（由实现解释）。 */
class UiSelectorDsl internal constructor(
    val text: String? = null,
    val desc: String? = null,
    val className: String? = null,
    val packageName: String? = null,
    val id: String? = null,
    val clickable: Boolean? = null,
    val descendantOf: UiSelectorDsl? = null,
) {
    fun copyWith(
        text: String? = this.text,
        desc: String? = this.desc,
        className: String? = this.className,
        packageName: String? = this.packageName,
        id: String? = this.id,
        clickable: Boolean? = this.clickable,
        descendantOf: UiSelectorDsl? = this.descendantOf,
    ) = UiSelectorDsl(text, desc, className, packageName, id, clickable, descendantOf)

    companion object {
        fun builder(): UiSelectorDsl = UiSelectorDsl()
    }
}

/** 控件矩形（逻辑像素；与 JS UiObject.bounds 对齐）。 */
data class UiBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

/**
 * 控件动作执行 SPI（docs §9.1；与 [UiNodeTreeReader] 配对，实现位于 :platform:capabilities）。
 * generation 失配/已 dispose 一律抛 [com.autoscript.domain.core.AutojsException]
 *（ERR_STALE_HANDLE），由桥 handler 折叠为 Err 回包，绝不静默吞掉。
 */
interface UiActionExecutor {
    suspend fun click(handle: HandleRef): Boolean
    suspend fun longClick(handle: HandleRef): Boolean
    suspend fun setText(handle: HandleRef, text: String): Boolean
    /**
     * 滚动（docs §9.1：`scroll` 走无障碍 Action）。
     * 方向 [direction] 取 ScrollDirection；不可滚动（非 scrollable 容器）回 false（不抛错，
     * 与 click-on-unclickable 同口径）；跨代/已释放抛 ERR_STALE_HANDLE。
     */
    suspend fun scroll(handle: HandleRef, direction: ScrollDirection): Boolean
    /**
     * 复制/粘贴（docs §9.1：`copy/paste` 走无障碍 Action）。
     * copy 把节点文本（text ?? desc，皆空则记空串）写入剪贴板，回 true；
     * paste 把剪贴板内容写入可编辑节点（setText 同口径，不可编辑回 false）。
     * 空剪贴板 paste 回 false（不抛错，无内容可贴不是错误）。
     */
    suspend fun copy(handle: HandleRef): Boolean
    suspend fun paste(handle: HandleRef): Boolean
    suspend fun attribute(handle: HandleRef, name: String): String?
    suspend fun bounds(handle: HandleRef): UiBounds?
    suspend fun children(handle: HandleRef): List<UiNode>
    suspend fun parent(handle: HandleRef): UiNode?
    suspend fun dispose(handle: HandleRef)
}

/** 滚动方向（与 Android AccessibilityNodeInfo.ACTION_SCROLL_* 四向对齐）。 */
enum class ScrollDirection { FORWARD, BACKWARD, UP, DOWN, LEFT, RIGHT }