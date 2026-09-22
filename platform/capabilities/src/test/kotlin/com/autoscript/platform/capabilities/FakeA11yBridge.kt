package com.autoscript.platform.capabilities

import com.autoscript.domain.automation.GestureInput
import com.autoscript.domain.automation.ScreenSnapshot
import com.autoscript.domain.automation.ScrollDirection
import com.autoscript.domain.automation.WindowScope
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode

/**
 * 测试假桥：蓝图（[Blue]，设备屏幕的持久面）× 包装（[FakeNode]，一次访问一个的
 * `AccessibilityNodeInfo` 对偶）两层，语义与设备面对齐：
 * - `roots()` **每次重新物化**包装（真机每次 `rootInActiveWindow` 都是新 info）；
 * - `recycle` 只杀包装，不碰蓝图（回收快照 ≠ 节点离开屏幕）；
 * - `blue.alive=false` = 节点离开树 → 所有包装 `refresh()=false`（stale 判据）；
 * - **未连接时每个方法抛 ERR_SERVICE_DISABLED**（[A11yBridge] 铁律的可测形态）。
 */
internal class FakeA11yBridge : A11yBridge {

    class Blue(val snap: A11yNodeSnap) {
        var alive = true
        var clickResult = true
        var longClickResult = true
        var setTextResult = true
        var scrollResult = true
        val kids = mutableListOf<Blue>()
        var parentBlue: Blue? = null

        fun add(childSnap: A11yNodeSnap): Blue =
            Blue(childSnap).also { it.parentBlue = this; kids += it }
    }

    inner class FakeNode(private val blue: Blue) : A11yNode {
        init {
            materialized += this // 物化登记（roots() 清一次；children/parent 滚动追加）
        }

        var recycled = false

        override fun snapshot(): A11yNodeSnap = blue.snap
        // 服务死 → refresh=false（真机 AccessibilityNodeInfo.refresh 同语义）→ 调用方按 STALE 回收。
        override fun refresh(): Boolean = blue.alive && !recycled && connected
        override fun children(): List<A11yNode> = blue.kids.map { FakeNode(it) }
        override fun parent(): A11yNode? = blue.parentBlue?.let { FakeNode(it) }
        // 属性门 = 设备 performAction 的事实（不可点 ACTION_CLICK 返 false）——
        // 结果位（clickResult 等）是测试用来模拟"系统拒绝"的第二道开关。
        override fun performClick(): Boolean = blue.snap.clickable && blue.clickResult && refresh()
        override fun performLongClick(): Boolean =
            (blue.snap.longClickable || blue.snap.clickable) && blue.longClickResult && refresh()
        override fun performSetText(text: String): Boolean =
            blue.snap.editable && blue.setTextResult && refresh()
        override fun performScroll(direction: ScrollDirection): Boolean =
            blue.snap.scrollable && blue.scrollResult && refresh()
        override fun recycle() {
            recycled = true
        }
    }

    var connected = true
    var canPerform = true
    var dispatchResult = true
    var dispatchCount = 0
    var clipboard: String? = null
    var locked = false
    var hasWindows = true

    /** 截帧成功回包（改尺寸可测"回包尺寸=系统真值"）。 */
    var screenshotFrame: ProducedFrame? = ProducedFrame(byteArrayOf(1, 2, 3), 1440, 3200)

    /** 非 null = 下一次 takeScreenshot 抛它（分类错误/失败注入）。 */
    var screenshotFailure: AutojsException? = null

    /** 真正走到截帧的次数（策略预检拦截时必须是 0）。 */
    var screenshotCount = 0
    val activeRoots = mutableListOf<Blue>()
    val modalRoots = mutableListOf<Blue>()
    val allRoots = mutableListOf<Blue>()

    /** 最近一次 `roots()` 以来物化的包装（测试断言用；roots() 清一次，children 滚动追加）。 */
    val materialized = mutableListOf<FakeNode>()

    fun seedActive(snap: A11yNodeSnap): Blue = Blue(snap).also { activeRoots += it }

    private fun up() {
        if (!connected) {
            throw AutojsException(ErrorCode.ERR_SERVICE_DISABLED, "无障碍服务未连接（测试桩）")
        }
    }

    private fun materialize(blues: List<Blue>): List<A11yNode> {
        materialized.clear()
        return blues.map { FakeNode(it) }
    }

    override fun roots(scope: WindowScope): List<A11yNode> {
        up()
        val blues = when (scope) {
            WindowScope.ACTIVE -> activeRoots
            WindowScope.MODAL -> modalRoots.ifEmpty { activeRoots }
            WindowScope.ALL -> allRoots.ifEmpty { activeRoots }
        }
        return materialize(blues)
    }

    override val canPerformGestures: Boolean
        get() {
            up()
            return canPerform
        }

    override fun dispatchGesture(gesture: GestureInput): Boolean {
        up()
        dispatchCount++
        if (!canPerform) return false
        return dispatchResult
    }

    override fun clipboardRead(): String? {
        up()
        return clipboard
    }

    override fun clipboardWrite(text: String) {
        up()
        clipboard = text
    }

    override suspend fun screenSnapshot(): ScreenSnapshot {
        up()
        return ScreenSnapshot(locked = locked, secureForeground = false, hasWindows = hasWindows)
    }

    override suspend fun takeScreenshot(): ProducedFrame {
        up()
        screenshotCount++
        screenshotFailure?.let { throw it }
        return screenshotFrame
            ?: throw AutojsException(ErrorCode.ERR_SERVICE_DISABLED, "假桥无帧（测试桩）")
    }
}
