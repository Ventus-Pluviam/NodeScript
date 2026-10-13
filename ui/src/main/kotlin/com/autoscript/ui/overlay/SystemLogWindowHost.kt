package com.autoscript.ui.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
    private var compactX = 0
    private var compactY = 0

    /**
     * 弹系统窗（已开则只换内容）。[content] 是 `LogWindowContent` 那一份
     * （`Theme` 由本类自己包 —— 窗外没有 MainActivity 的主题链）。
     *
     * @return false = 弹不起来（无 WindowManager / 被拒）→ 调用方用 in-app 回退。
     */
    fun show(content: @Composable () -> Unit): Boolean {
        val manager = wm ?: return false
        val existing = view
        if (existing != null) {
            existing.setContent { Theme(mode = themeMode()) { content() } }
            return true
        }
        return try {
            val o = OverlayOwners().also { owners = it }
            val v = ComposeView(context).apply {
                setViewTreeLifecycleOwner(o)
                setViewTreeViewModelStoreOwner(o)
                setViewTreeSavedStateRegistryOwner(o)
                setContent { Theme(mode = themeMode()) { content() } }
            }
            val density = context.resources.displayMetrics.density
            compactW = (COMPACT_WIDTH_DP * density).toInt()
            compactH = (COMPACT_HEIGHT_DP * density).toInt()
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

    /** 拖拽（头部手势的增量，dp→px 由调用方换算好传进来）。 */
    fun moveBy(dxPx: Int, dyPx: Int) {
        val manager = wm ?: return
        val v = view ?: return
        val p = params ?: return
        // 全屏时不漂：位置被锁在 0,0（setFullscreen 存的紧凑位置还在，退出全屏即回）。
        if (p.width == WindowManager.LayoutParams.MATCH_PARENT) return
        p.x += dxPx
        p.y += dyPx
        runCatching { manager.updateViewLayout(v, p) }
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

    companion object {
        /** 紧凑尺寸（日志窗是"盯着看"的面，不是阅读器 —— 默认不大）。 */
        val COMPACT_WIDTH_DP: Dp = 340.dp
        val COMPACT_HEIGHT_DP: Dp = 420.dp
    }
}
