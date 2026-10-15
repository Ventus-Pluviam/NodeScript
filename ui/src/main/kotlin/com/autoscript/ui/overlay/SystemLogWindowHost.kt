package com.autoscript.ui.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.autoscript.ui.theme.Theme
import com.autoscript.ui.theme.ThemeMode

/**
 * 执行日志悬浮窗的**系统窗宿主**（2026-10-13 用户令；住 `:ui`，不复用脚本侧链）。
 *
 * 为什么不走 `platform/system/.../floatingWindow` 那条链：它是脚本
 * `auto.floatingWindow.*` 的契约（`FloatingWindowSpec` + `HandleRef` +
 * OVERLAY 能力门禁 + 默认 TextView 视图）—— 应用自己的日志窗要 Compose 内容、
 * 拖拽、全屏切换、水印，那个 SPI 是错的形状。参数形状（OVERLAY 类型、
 * NOT_FOCUSABLE|NO_LIMITS、TOP|START）照抄 `WindowManagerOps`，类独立。
 *
 * **离屏 ComposeView 的三 owner**：窗外没有 Activity 的 ViewTree，故自己搭
 * `OverlayOwners`（Lifecycle + ViewModelStore + SavedStateRegistry）并经
 * `ViewTree*Owner.set` 挂上 —— 缺任何一个，Compose 运行时（rememberSaveable、
 * viewModel、lifecycle 感知）都会在窗内崩。`show` → RESUMED，`hide` →
 * ON_PAUSE→ON_STOP→ON_DESTROY + store.clear()。
 *
 * **FLAG 全程 NOT_FOCUSABLE**：悬浮窗是"贴在旁边看"的，抢焦点会把用户正在操作的
 * 输入法顶掉（与 `WindowManagerOps` 同一条理由）。按钮照常可点 —— 焦点与触摸是两回事。
 *
 * **show 失败回 false，不抛**：`SecurityException`（权限被撤）→ 调用方落 in-app
 * 回退窗。`Activity.onDestroy` 必须调 [hide]（泄漏的 overlay 窗会让系统弹
 * "…has leaked window" 并杀进程）。
 */
class SystemLogWindowHost(
    private val context: Context,
    private val themeMode: () -> ThemeMode,
) {
    private val wm: WindowManager? =
        context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    private var view: ComposeView? = null
    private var owners: OverlayOwners? = null
    private var params: WindowManager.LayoutParams? = null
    private var compactW = 0
    private var compactH = 0
    /** 紧凑态位置（px，绝对）：拖动与全屏往返都从这一对恢复。 */
    private var compactX = 0
    private var compactY = 0
    /** 窗可视边界（px）：夹取用；拿不到（无 WindowManager）则不夹。 */
    private var boundsW = 0
    private var boundsH = 0
    /**
     * 可用区顶边在屏幕上的 y（px）。
     *
     * 为什么需要它：`WindowManager.LayoutParams` 的 y 是**相对屏幕**的，而"可用区"
     * 从状态栏下面才开始 —— 不加这一道，窗会被摆到状态栏底下（标题行与四颗按钮
     * 正好被盖住，看得见摸不着，而拖动把手就在那一行）。加上它之后，
     * 状态里的 `y = 0` 在两个宿主里含义一致："贴可用区顶边"。
     */
    private var topOffsetPx = 0

    /** dp → px（Dp 上的换算只能取 `.value`：`Dp * Float` 还是 Dp）。 */
    private fun px(dp: Float): Int = (dp * context.resources.displayMetrics.density).toInt()

    /**
     * 弹系统窗（已开则只换内容）。[content] 是 `LogWindowContent` 那一份
     * （`Theme` 由本类自己包 —— 窗外没有 MainActivity 的主题链）。
     *
     * **两条分支都不许把异常放出去**（2026-10-14「点执行就闪退」）：`setContent`
     * 在离屏 `ComposeView` 上跑的是**组合**，组合期抛出的任何东西都会顺着调用栈
     * 冒到调用方 —— 而调用方是 `SystemWindowSync` 的 `LaunchedEffect`，那里的异常
     * 没有接收者，直接终结进程。重喂那条此前是裸调，首建那条才有 `catch`。
     * 两条现在同口径：失败回 false，调用方落 in-app 回退窗（**看得见的降级**，
     * 比闪退好，也比静默不弹好）。
     *
     * @return false = 弹不起来（无 WindowManager / 被拒 / 重喂失败）→ 调用方用 in-app 回退。
     */
    fun show(content: @Composable () -> Unit, xDp: Float = 0f, yDp: Float = 0f): Boolean {
        val manager = wm ?: return false
        syncBounds(manager)
        val existing = view
        if (existing != null) {
            // 重喂：换内容（主题/归属/改画态）。**同样包 catch** —— 见本函数 KDoc。
            return try {
                existing.setContent { Theme(mode = themeMode()) { content() } }
                moveTo(xDp, yDp)
                true
            } catch (_: Exception) {
                hide()
                false
            }
        }
        return try {
            val o = OverlayOwners().also { owners = it }
            val v = ComposeView(context).apply {
                setViewTreeLifecycleOwner(o)
                setViewTreeViewModelStoreOwner(o)
                setViewTreeSavedStateRegistryOwner(o)
                setContent { Theme(mode = themeMode()) { content() } }
            }
            compactW = px(LogWindowState.COMPACT_WIDTH_DP)
            compactH = px(LogWindowState.COMPACT_HEIGHT_DP)
            compactX = px(xDp)
            compactY = topOffsetPx + px(yDp)
            val p = WindowManager.LayoutParams(
                compactW,
                compactH,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = compactX
                y = compactY
            }
            manager.addView(v, p)
            o.moveTo(Lifecycle.State.RESUMED)
            view = v
            params = p
            true
        } catch (_: Exception) {
            // SecurityException（权限被撤）与其余加窗失败一律回 false ——
            // 调用方落 in-app 回退，不拿异常挡开窗的路。
            hide()
            false
        }
    }

    /** 全屏切换（[fullscreen] = true → 铺满；false → 回紧凑尺寸与拖拽位置）。 */
    fun setFullscreen(fullscreen: Boolean) {
        val manager = wm ?: return
        val v = view ?: return
        val p = params ?: return
        if (fullscreen) {
            compactX = p.x
            compactY = p.y
            p.width = WindowManager.LayoutParams.MATCH_PARENT
            p.height = WindowManager.LayoutParams.MATCH_PARENT
            p.x = 0
            p.y = 0
        } else {
            p.width = compactW
            p.height = compactH
            p.x = compactX
            p.y = compactY
        }
        runCatching { manager.updateViewLayout(v, p) }
    }

    /**
     * **输入焦点开关**（2026-10-16 用户令：悬浮窗底部输入框要能打字）。
     *
     * 病：本窗全程带 `FLAG_NOT_FOCUSABLE`（见类 KDoc —— 悬浮窗是"贴在旁边看"的，
     * 抢焦点会把用户正在操作的输入法顶掉），而**不可聚焦的窗口拿不到输入法**
     * （`FLAG_NOT_FOCUSABLE` 单独在场时 IME 不与会话交互）—— 输入框能画、能点，
     * 就是打不出字。
     *
     * 药：**只在用户真的要打字的那段时间**把窗口变可聚焦 —— 与"全程可聚焦"相比，
     * 原设计那条理由（看日志时不抢别人的焦点）一字不动：
     * - `on = true`：清 `FLAG_NOT_FOCUSABLE`、加 `FLAG_NOT_TOUCH_MODAL`
     *   （窗外触摸仍落到下层 App，否则整个屏幕都被这颗窗吃掉），并把
     *   `softInputMode` 设成 `ADJUST_RESIZE | STATE_ALWAYS_VISIBLE`；
     * - `on = false`：原样加回 `FLAG_NOT_FOCUSABLE`、去掉 `FLAG_NOT_TOUCH_MODAL`。
     *
     * **为什么还要 `requestFocus` + `showSoftInput`**：Compose 的文本框在拿到焦点时
     * 自己会要输入法，但那一刻窗口还不可聚焦 —— 那次请求是**空放**的（窗口没资格）。
     * 这里在把窗口变可聚焦之后补一次，顺序才对得上。两个调用都包 `runCatching`：
     * 拿不到 IME（无输入法的设备）不该把开窗路径带崩。
     *
     * 幂等：目标 flags 与现值相同即早退（拖动每帧都走 `updateViewLayout`，这里不必凑热闹）。
     */
    fun setImeFocus(on: Boolean) {
        val manager = wm ?: return
        val v = view ?: return
        val p = params ?: return
        val notFocusable = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        val notTouchModal = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        val flags = if (on) {
            (p.flags and notFocusable.inv()) or notTouchModal
        } else {
            (p.flags or notFocusable) and notTouchModal.inv()
        }
        if (flags == p.flags) return
        p.flags = flags
        if (on) {
            p.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
        }
        runCatching { manager.updateViewLayout(v, p) }
        if (!on) return
        // 窗口此刻才可聚焦：补一次"要焦点 + 起输入法"（见本函数 KDoc 的那段顺序说明）。
        runCatching {
            v.requestFocus()
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            v.post { runCatching { imm?.showSoftInput(v, 0) } }
        }
    }

    /**
     * 落位到绝对坐标（dp）：拖动、开窗、切回紧凑三处共用同一条路。
     *
     * **收绝对坐标而不是增量**：增量要在宿主里累加，于是位置就有了两份事实源
     * （状态里一份、宿主里一份），拖动与全屏往返各写一份必然漂。调用方（状态层）
     * 算好目标，这里只落位 —— 夹取也在状态层（它才知道窗多大、边界多宽）。
     *
     * 全屏时忽略：位置被锁在 0,0（[setFullscreen] 存下的紧凑位置还在，退出全屏即回）。
     */
    fun moveTo(xDp: Float, yDp: Float) {
        val manager = wm ?: return
        val v = view ?: return
        val p = params ?: return
        if (p.width == WindowManager.LayoutParams.MATCH_PARENT) return
        compactX = px(xDp)
        compactY = topOffsetPx + px(yDp)
        p.x = compactX
        p.y = compactY
        runCatching { manager.updateViewLayout(v, p) }
    }

    /**
     * 可视边界（px）—— 夹取判据的一半，另一半是窗自己的尺寸。
     *
     * **要的是"能摆到哪"而不是"窗最多多大"**：`getCurrentSizeRange` 给的是**尺寸范围**
     * （min/max），窗宽 340dp 在多数设备上恰好落在 max 那一端，于是"max 减去窗宽"≈0，
     * 夹取会把窗钉死在左上角 —— 那是把"窗能长多大"误当成"窗能挪多远"。
     * 正确来源是 `getCurrentWindowMetrics().bounds`（API 30+）：它给的是**可用区矩形**，
     * 四条边一起给 —— 宽度夹取、顶边偏移（[topOffsetPx]）都从它出，两处同源。
     * 本仓 minSdk 26，30 以下退回 `displayMetrics` + 状态栏资源（近似，见下）。
     */
    private fun syncBounds(manager: WindowManager) {
        val m = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            runCatching { manager.currentWindowMetrics.bounds }.getOrNull()
        } else {
            null
        }
        // **两处口径必须同源**：边界与顶边偏移都取自 `getCurrentWindowMetrics().bounds`
        // （API 30+，可用区，左/上/右/下四条边一起给），于是"窗能摆到哪"与"y=0 是哪"
        // 用的是同一个矩形。此前写成"边界取 windowMetrics、顶边偏移另查
        // `status_bar_height` 资源" —— 两个来源在刘海/挖孔/横屏下必然对不上
        // （那是本仓反复踩过的形状：同一件事两份事实源）。
        if (m != null && m.width() > 0 && m.height() > 0) {
            boundsW = m.width()
            boundsH = m.height()
            topOffsetPx = m.top
            return
        }
        // API 30 以下：`bounds` 拿不到，退回整屏 + 状态栏资源。这一档下
        // 左/上偏移取状态栏高度（挖孔屏会偏一点 —— 老设备上的近似，如实记在这里）。
        val dm = context.resources.displayMetrics
        boundsW = dm.widthPixels
        boundsH = dm.heightPixels
        topOffsetPx = statusBarHeightPx()
    }

    /**
     * 状态栏高度（px；**只有 API 30 以下那条路用它**，见 [syncBounds]）。
     *
     * 为什么不去问 `WindowInsets`：那是**某个 View 的** insets（要有一个已 attach 的
     * View 才成立），而本类要在开窗**之前**就知道摆在哪。资源查询是这条路里唯一
     * 与 View 无关的来源；查不到就给 0（不猜，宁可让窗贴到顶）。
     */
    private fun statusBarHeightPx(): Int = runCatching {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (id <= 0) 0 else context.resources.getDimensionPixelSize(id)
    }.getOrDefault(0)

    /** 可视边界（dp；0 = 未知，调用方据此不夹取）。 */
    fun boundsDp(): Pair<Float, Float> {
        val d = context.resources.displayMetrics.density
        if (boundsW <= 0 || boundsH <= 0) return 0f to 0f
        // 高度报**可用区**（扣掉顶边偏移）：状态里的 y 从可用区顶边起算，
        // 夹取也必须按同一段算，否则窗底会伸到屏幕外面（差一个状态栏）。
        return (boundsW / d) to ((boundsH - topOffsetPx) / d)
    }

    /** 撤窗（幂等；生命周期走完 ON_DESTROY + store.clear）。 */
    fun hide() {
        val manager = wm
        val v = view
        view = null
        params = null
        val o = owners
        owners = null
        if (manager != null && v != null) {
            runCatching { manager.removeView(v) }
        }
        if (o != null) {
            runCatching {
                o.moveTo(Lifecycle.State.DESTROYED)
                o.store.clear()
            }
        }
    }

    /** 离屏窗口的三个 owner（一份实例管三个身份，show 前 restore，hide 后 destroy）。 */
    private class OverlayOwners : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
        override val viewModelStore: ViewModelStore = ViewModelStore()
        val store: ViewModelStore get() = viewModelStore
        private val ssr = SavedStateRegistryController.create(this)
        override val savedStateRegistry: SavedStateRegistry get() = ssr.savedStateRegistry

        init {
            // 离屏没有 savedState 可恢复：空 Bundle 起账（performRestore(null) 在
            // lifecycle 2.8 的形态实现时若拒 null，改传 `Bundle()` —— 见计划备注）。
            ssr.performAttach()
            runCatching { ssr.performRestore(Bundle()) }
        }

        fun moveTo(state: Lifecycle.State) {
            registry.currentState = state
        }
    }

}
