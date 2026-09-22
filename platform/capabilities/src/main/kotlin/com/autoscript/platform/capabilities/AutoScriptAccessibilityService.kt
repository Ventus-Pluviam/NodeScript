package com.autoscript.platform.capabilities

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.autoscript.domain.automation.GestureInput
import com.autoscript.domain.automation.ScrollDirection
import com.autoscript.domain.automation.UiBounds
import com.autoscript.domain.automation.WindowScope

/**
 * 无障碍服务本体（docs §9.1「服务在 :main」；清单见本模块 AndroidManifest.xml）。
 *
 * 职责只有三件：
 * 1. 连接登记：`onServiceConnected` → [A11yServiceHolder]，`onDestroy` → 清空
 *    （[SystemA11yBridge] 的"连接态"唯一事实来源；崩了/被系统停掉走 onDestroy，
 *    下一次桥调用如实 ERR_SERVICE_DISABLED）；
 * 2. 事件入环：`onAccessibilityEvent` → [A11yEventRing.shared]（只映射三种已命名
 *    事件，其余类型不透出 —— 不给 JS 一个查无此物的假 type）；
 * 3. 通过 [ServiceBridge]/[ServiceNode] 把设备面实现成 [A11yBridge] 缝。
 *
 * 本类不含选择器/句柄/校验语义（那些在 [AndroidUiTree]，纯 JVM 可测）。
 */
class AutoScriptAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        A11yServiceHolder.bridge = ServiceBridge(this)
    }

    override fun onDestroy() {
        // 只清自己登记的桥（P0 单实例；防御未来热重启交错）。
        if (A11yServiceHolder.bridge != null) A11yServiceHolder.bridge = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val type = when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "windowStateChanged"
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "windowContentChanged"
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> "viewScrolled"
            else -> return
        }
        A11yEventRing.shared.push(type, event.className?.toString())
    }

    override fun onInterrupt() = Unit
}

/** 设备面 [A11yBridge]：窗口根/手势/剪贴板直通系统 API。 */
@Suppress("DEPRECATION") // AccessibilityWindowInfo.root API33 起弃用（改 activeChild），P0 取根语义不变
private class ServiceBridge(private val service: AccessibilityService) : A11yBridge {

    private fun wrap(node: AccessibilityNodeInfo?): A11yNode? = node?.let { ServiceNode(it) }

    override fun roots(scope: WindowScope): List<A11yNode> {
        val active = wrap(service.rootInActiveWindow)
        return when (scope) {
            WindowScope.ACTIVE -> listOfNotNull(active)
            WindowScope.MODAL -> {
                // AOSP AccessibilityWindowInfo 没有 isModal（主源码实证）；模态窗的可观察
                // 事实是"输入焦点"——对话框弹出即夺焦，退化态（无对话框）焦点=活动窗，
                // 与 ACTIVE 同结果不劣化。焦点窗根优先，缺则回落活动根。
                val modal = service.windows?.firstOrNull { it.isFocused }?.root.let { wrap(it) }
                listOfNotNull(modal ?: active)
            }
            WindowScope.ALL -> {
                val windows = service.windows
                if (windows.isNullOrEmpty()) {
                    listOfNotNull(active)
                } else {
                    windows.mapNotNull { wrap(it.root) }.ifEmpty { listOfNotNull(active) }
                }
            }
        }
    }

    override val canPerformGestures: Boolean
        // 系统没有 AccessibilityManager.canPerformGestures()（AOSP 主源码实证）；
        // 运行期事实是本服务的 CAPABILITY_CAN_PERFORM_GESTURES（配置 canPerformGestures=true
        // 声明 + 系统授予面）。派发仍可能被拒（dispatchGesture=false 原样透传）。
        get() = (service.serviceInfo?.capabilities ?: 0) and
            AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES != 0

    override fun dispatchGesture(gesture: GestureInput): Boolean {
        val builder = GestureDescription.Builder()
        for (stroke in gesture.strokes) {
            val path = Path()
            val pts = stroke.points
            path.moveTo(pts[0].x.toFloat(), pts[0].y.toFloat())
            for (i in 1 until pts.size) path.lineTo(pts[i].x.toFloat(), pts[i].y.toFloat())
            builder.addStroke(
                GestureDescription.StrokeDescription(path, stroke.startDelayMillis, stroke.durationMillis),
            )
        }
        return service.dispatchGesture(builder.build(), null, null)
    }

    private val clipboard: ClipboardManager
        get() = service.getSystemService(ClipboardManager::class.java)

    override fun clipboardRead(): String? {
        val clip = clipboard.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).coerceToText(service)?.toString()
    }

    override fun clipboardWrite(text: String) {
        clipboard.setPrimaryClip(ClipData.newPlainText("autoscript", text))
    }
}

/** 单节点适配：`AccessibilityNodeInfo` → [A11yNode]。 */
@Suppress("DEPRECATION") // recycle API33 弃用（GC 接管）；低版本必须显式归还
private class ServiceNode(private val node: AccessibilityNodeInfo) : A11yNode {

    override fun snapshot(): A11yNodeSnap {
        val r = Rect()
        node.getBoundsInScreen(r)
        return A11yNodeSnap(
            text = node.text?.toString(),
            desc = node.contentDescription?.toString(),
            className = node.className?.toString(),
            packageName = node.packageName?.toString(),
            viewId = node.viewIdResourceName,
            clickable = node.isClickable,
            longClickable = node.isLongClickable,
            editable = node.isEditable,
            scrollable = node.isScrollable,
            bounds = UiBounds(r.left, r.top, r.right, r.bottom),
        )
    }

    override fun refresh(): Boolean = try {
        node.refresh()
    } catch (_: Exception) {
        false // 服务侧失效按"已离开树"处理（stale 判据唯一出处在 AndroidUiTree.live）
    }

    override fun children(): List<A11yNode> = buildList {
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { add(ServiceNode(it)) }
        }
    }

    override fun parent(): A11yNode? = node.parent?.let { ServiceNode(it) }

    override fun performClick(): Boolean = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)

    override fun performLongClick(): Boolean = node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)

    override fun performSetText(text: String): Boolean {
        val args = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    override fun performScroll(direction: ScrollDirection): Boolean {
        // 四向常量只在 AccessibilityAction 嵌套类上（顶层 int 只有 FORWARD/BACKWARD，
        // AOSP 实证）—— getId() 取回 performAction 要的 int。
        val action: Int = when (direction) {
            ScrollDirection.FORWARD -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            ScrollDirection.BACKWARD -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            ScrollDirection.UP -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.getId()
            ScrollDirection.DOWN -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.getId()
            ScrollDirection.LEFT -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.getId()
            ScrollDirection.RIGHT -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.getId()
        }
        return node.performAction(action)
    }

    override fun recycle() {
        node.recycle()
    }
}
