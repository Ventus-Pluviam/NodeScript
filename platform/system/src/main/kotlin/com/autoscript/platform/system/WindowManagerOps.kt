package com.autoscript.platform.system

import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import com.autoscript.domain.system.FloatingWindowSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [AndroidFloatingWindowHost.FloatingWindowOps] 的真机实现（docs §9.4）：唯一碰
 * `WindowManager` 的地方。发号/幂等/错误分类都在宿主里，这里只有"加窗"和"撤窗"两个动作。
 *
 * - 窗口类型随 [overlay] 二选一：`TYPE_ACCESSIBILITY_OVERLAY`（a11y 服务在跑时不易被系统收走）
 *   或 `TYPE_APPLICATION_OVERLAY`（需 `SYSTEM_ALERT_WINDOW`）。选哪条由宿主的缝决定，本类不判断。
 * - `FLAG_NOT_FOCUSABLE`：悬浮窗是"贴在旁边看"的，抢焦点会把用户正在操作的输入法顶掉；
 *   `FLAG_LAYOUT_NO_LIMITS` 允许贴边。
 * - `Dispatcher.Main`：`WindowManager` 必须在有 Looper 的线程上调用，协程切回来即可，
 *   不需要额外的 Handler。
 * - 尺寸：null = `WRAP_CONTENT`（[FloatingWindowSpec] 的可空约定）。
 *
 * [viewFactory] 是缝：默认造一个只带标题的 [FrameLayout]（P0 验证宿主链路；脚本侧自定义布局
 * 的 `UiHost` 是 P1）。
 */
class WindowManagerOps(
    private val context: Context,
    private val viewFactory: (Context, FloatingWindowSpec) -> View = ::defaultView,
) : AndroidFloatingWindowHost.FloatingWindowOps {

    override suspend fun add(spec: FloatingWindowSpec, overlay: Boolean): Any = withContext(Dispatchers.Main) {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            ?: throw AutojsException(ErrorCode.ERR_SERVICE_DISABLED, "WindowManager 不可得")
        val view = viewFactory(context, spec)
        val layout = WindowManager.LayoutParams(
            spec.width ?: WindowManager.LayoutParams.WRAP_CONTENT,
            spec.height ?: WindowManager.LayoutParams.WRAP_CONTENT,
            if (overlay) {
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        wm.addView(view, layout)   // 拒绝时抛（SecurityException 等），由宿主折成分类错误
        Log.i(TAG, "悬浮窗已加：type=${layout.type} w=${layout.width} h=${layout.height}")
        Token(view, wm)
    }

    override suspend fun remove(token: Any) = withContext(Dispatchers.Main) {
        val t = token as? Token ?: return@withContext
        t.wm.removeView(t.view)
        Log.i(TAG, "悬浮窗已撤")
    }

    private class Token(val view: View, val wm: WindowManager)

    companion object {
        private const val TAG = "WindowManagerOps"

        /** 默认视图：只有标题的容器（自定义布局 P1 接 `UiHost`）。 */
        fun defaultView(context: Context, spec: FloatingWindowSpec): View =
            FrameLayout(context).apply {
                addView(
                    TextView(context).apply {
                        text = spec.title ?: ""
                        setPadding(32, 24, 32, 24)
                    },
                )
            }
    }
}
