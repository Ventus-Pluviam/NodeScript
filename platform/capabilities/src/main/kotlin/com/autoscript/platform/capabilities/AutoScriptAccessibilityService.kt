package com.autoscript.platform.capabilities

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.autoscript.domain.automation.GestureInput
import com.autoscript.domain.automation.ScrollDirection
import com.autoscript.domain.automation.ScreenSnapshot
import com.autoscript.domain.automation.UiBounds
import com.autoscript.domain.automation.WindowScope
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CompletableDeferred

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

/** 帧 JPEG 质量（P0 像素只做存活证明 + 未来像素面的原料，90 兼顾体积与可读）。 */
private const val JPEG_QUALITY = 90

/** 设备面 [A11yBridge]：窗口根/手势/剪贴板/截图直通系统 API。 */
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

    // ── 屏幕采集（§9.2 a11y 截图路径 / §8.8 分类错误）────────────────

    override suspend fun screenSnapshot(): ScreenSnapshot {
        val locked = service.getSystemService(KeyguardManager::class.java)?.isDeviceLocked ?: false
        // secureForeground 恒 false：见 A11yBridge.screenSnapshot KDoc（读不到窗口 flag，
        // 安全窗由 takeScreenshot 的 ERROR_TAKE_SCREENSHOT_SECURE_WINDOW 分类兜底）。
        val hasWindows = !service.windows.isNullOrEmpty() || service.rootInActiveWindow != null
        return ScreenSnapshot(locked = locked, secureForeground = false, hasWindows = hasWindows)
    }

    /**
     * a11y 截一帧：API34+ 走窗口级 `takeScreenshotOfWindow`（§9.2 默认），API30–33 走
     * 显示级 `takeScreenshot`（两法都要配置 `canTakeScreenshot=true`，已进 res/xml），
     * API<30 如实 ERR_NOT_IMPLEMENTED（不伪造降级通道）。
     * 回调线程 → CompletableDeferred 回到协程；失败码按下表分类（§8.8 不返回黑图）：
     * SECURE_WINDOW→BLACK_FRAME · INTERVAL_TIME_SHORT→INVALID_PARAM（退避） ·
     * NO_ACCESSIBILITY/INVALID_*→SERVICE_DISABLED · INTERNAL→ERR_IO。
     */
    override suspend fun takeScreenshot(): ProducedFrame {
        val sdk = Build.VERSION.SDK_INT
        if (sdk < Build.VERSION_CODES.R) {
            throw AutojsException(
                ErrorCode.ERR_NOT_IMPLEMENTED,
                "无障碍截图需 API30+（当前 $sdk）；MediaProjection 会话路径待接入",
            )
        }
        val deferred = CompletableDeferred<Result<AccessibilityService.ScreenshotResult>>()
        // 具名嵌套类而非 object :（匿名类的合成名进不了 ArchUnit 服务面豁免名单）。
        val callback = ScreenshotCallback(deferred)
        if (sdk >= 34) {
            val windowId = activeWindowId()
                ?: throw AutojsException(ErrorCode.ERR_SERVICE_DISABLED, "无活动窗口，截图通道不可用")
            service.takeScreenshotOfWindow(windowId, service.mainExecutor, callback)
        } else {
            service.takeScreenshot(Display.DEFAULT_DISPLAY, service.mainExecutor, callback)
        }
        val result = deferred.await().getOrElse { throw mapScreenshotFailure(it) }
        return frameOf(result)
    }

    private class ScreenshotFailed(val code: Int) : Exception("takeScreenshot failed: $code")

    /** 回调 → deferred（具名类：ArchUnit 服务面豁免按简单名匹配，匿名合成名挂不上）。 */
    private class ScreenshotCallback(
        private val deferred: CompletableDeferred<Result<AccessibilityService.ScreenshotResult>>,
    ) : AccessibilityService.TakeScreenshotCallback {
        override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
            deferred.complete(Result.success(result))
        }

        override fun onFailure(errorCode: Int) {
            deferred.complete(Result.failure(ScreenshotFailed(errorCode)))
        }
    }

    private fun mapScreenshotFailure(failure: Throwable): AutojsException {
        val code = (failure as? ScreenshotFailed)?.code
        val (error, why) = when (code) {
            AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW ->
                ErrorCode.ERR_BLACK_FRAME to "前台窗口 FLAG_SECURE，系统拒绝（不返回黑图）"
            AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT ->
                ErrorCode.ERR_INVALID_PARAM to "系统截图间隔过短，调用方退避重试"
            AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS ->
                ErrorCode.ERR_SERVICE_DISABLED to "无障碍截图通道不可用（服务失效/未授予）"
            AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY,
            AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_WINDOW ->
                ErrorCode.ERR_SERVICE_DISABLED to "无有效显示/窗口可截"
            else -> ErrorCode.ERR_IO to "系统截图内部错误（code=$code）"
        }
        return AutojsException(error, "截图失败：$why", failure)
    }

    private fun activeWindowId(): Int? {
        val windows = service.windows ?: return null
        return windows.firstOrNull { it.isActive }?.id
            ?: windows.firstOrNull { it.isFocused }?.id
            ?: windows.firstOrNull()?.id
    }

    /** ScreenshotResult → 实际尺寸 JPEG 字节（HardwareBuffer 用完即关，Bitmap 拷软后压缩）。 */
    private fun frameOf(result: AccessibilityService.ScreenshotResult): ProducedFrame {
        val buffer = result.hardwareBuffer
            ?: throw AutojsException(ErrorCode.ERR_IO, "截图结果无 HardwareBuffer")
        try {
            val hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                ?: throw AutojsException(ErrorCode.ERR_IO, "HardwareBuffer 包装失败")
            val width = hardware.width
            val height = hardware.height
            val software = try {
                hardware.copy(Bitmap.Config.ARGB_8888, false)
                    ?: throw AutojsException(ErrorCode.ERR_IO, "位图软拷贝失败")
            } finally {
                hardware.recycle()
            }
            val out = ByteArrayOutputStream()
            try {
                if (!software.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
                    throw AutojsException(ErrorCode.ERR_IO, "帧压缩失败")
                }
            } finally {
                software.recycle()
            }
            return ProducedFrame(out.toByteArray(), width, height)
        } finally {
            buffer.close()
        }
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
