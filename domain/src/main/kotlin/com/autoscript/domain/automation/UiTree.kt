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

/** 选择器构建 DSL：text/className/package/desc 谓词 + id，返回谓词模型（由实现解释）。 */
class UiSelectorDsl internal constructor(
    val text: String? = null,
    val className: String? = null,
    val packageName: String? = null,
    val id: String? = null,
    val descendantOf: UiSelectorDsl? = null,
) {
    fun copyWith(
        text: String? = this.text,
        className: String? = this.className,
        packageName: String? = this.packageName,
        id: String? = this.id,
        descendantOf: UiSelectorDsl? = this.descendantOf,
    ) = UiSelectorDsl(text, className, packageName, id, descendantOf)

    companion object {
        fun builder(): UiSelectorDsl = UiSelectorDsl()
    }
}