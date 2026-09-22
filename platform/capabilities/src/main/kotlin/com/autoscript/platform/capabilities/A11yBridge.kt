package com.autoscript.platform.capabilities

import com.autoscript.domain.automation.GestureInput
import com.autoscript.domain.automation.ScrollDirection
import com.autoscript.domain.automation.UiBounds
import com.autoscript.domain.automation.WindowScope
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode

/**
 * 无障碍服务侧缝（docs §9.1 Android 真实现的可测接缝）。
 *
 * 分层（与 §12.2「两层」同纪律）：
 * - 语义层 [AndroidUiTree]/[AndroidGestureInput] 只认本文件的接口 —— 纯 JVM 可测，
 *   假桥注入即可跑全部选择器/句柄/手势逻辑；
 * - 设备层 [AutoScriptAccessibilityService]（`AccessibilityNodeInfo` 遍历 /
 *   `dispatchGesture` / `ClipboardManager`）实现同一批接口，由
 *   [A11yServiceHolder] 在 `onServiceConnected` 登记、`onDestroy` 清空。
 *
 * **铁律：服务未连接时，本接口的每个方法都抛
 * [ErrorCode.ERR_SERVICE_DISABLED]**（不是回 null/空表/`false` 混充 ——
 * 「没服务」与「查无此控件」是两回事）。handler 各路径 `catch AutojsException`
 * 原码回桥；能力中心的 ACCESSIBILITY 三态是引导入口（§9.5）。
 */
interface A11yBridge {
    /** 按窗口作用域取根节点列表；无窗口/无内容回空表（**不**抛 —— 空树是查询语义）。 */
    fun roots(scope: WindowScope): List<A11yNode>

    /** 系统 `canPerformGestures()` 的语义位；false = 手势关门（能力中心引导）。 */
    val canPerformGestures: Boolean

    /** 派发手势；false = 系统拒绝执行（非能力问题，与 click 同口径）。 */
    fun dispatchGesture(gesture: GestureInput): Boolean

    /** 剪贴板读：null = 无剪贴板内容（空串是合法内容，不与缺失混淆）。 */
    fun clipboardRead(): String?

    fun clipboardWrite(text: String)
}

/** 单个活节点的访问面（设备侧 = `AccessibilityNodeInfo` 适配；测试 = 内存图）。 */
interface A11yNode {
    /** 属性快照（紧凑索引字段，§9.1「属性按需二次查询」的"一次查询"）。 */
    fun snapshot(): A11yNodeSnap

    /**
     * 刷新到窗口树现状：false = 节点已离开树（调用方按 ERR_STALE_HANDLE 处理，
     * 并应回收本节点 —— 这是 Android 侧句柄失活的唯一判据）。
     */
    fun refresh(): Boolean

    fun children(): List<A11yNode>

    fun parent(): A11yNode?

    fun performClick(): Boolean

    fun performLongClick(): Boolean

    fun performSetText(text: String): Boolean

    fun performScroll(direction: ScrollDirection): Boolean

    /** 归还底层资源（`AccessibilityNodeInfo.recycle`；API 33+ 由 GC 接管，本调用空转）。 */
    fun recycle()
}

/** [A11yNode.snapshot] 的字段面（与 `InMemoryUiTree.Attrs` 同构，选择器谓词共用语义）。 */
data class A11yNodeSnap(
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

/**
 * 服务登记处（`:main` 进程内单例，§9.1「服务在 :main」）。
 * `internal`：外部只见 [SystemA11yBridge]，不直接碰登记位。
 */
internal object A11yServiceHolder {
    @Volatile
    var bridge: A11yBridge? = null
}

/**
 * 生产缺省桥：委托 [A11yServiceHolder] 的当前服务。
 * 服务未连 → 每次调用抛 ERR_SERVICE_DISABLED（见 [A11yBridge] 铁律）。
 * 这是 [AndroidUiTree]/[AndroidGestureInput] 的缺省构造参数 —— 装配期即可创建，
 * 连接态在调用期判定（[AppShellKit] 的 a11yHandler 缝因此不必等服务连上）。
 */
object SystemA11yBridge : A11yBridge {

    private const val DOWN_MESSAGE = "无障碍服务未连接（能力中心开启 AutoScript 无障碍后重试）"

    private fun current(): A11yBridge = A11yServiceHolder.bridge
        ?: throw AutojsException(ErrorCode.ERR_SERVICE_DISABLED, DOWN_MESSAGE)

    override fun roots(scope: WindowScope): List<A11yNode> = current().roots(scope)

    override val canPerformGestures: Boolean
        get() = current().canPerformGestures

    override fun dispatchGesture(gesture: GestureInput): Boolean = current().dispatchGesture(gesture)

    override fun clipboardRead(): String? = current().clipboardRead()

    override fun clipboardWrite(text: String) = current().clipboardWrite(text)
}
